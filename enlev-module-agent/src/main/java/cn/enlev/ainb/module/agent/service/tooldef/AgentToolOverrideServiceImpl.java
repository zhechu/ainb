package cn.enlev.ainb.module.agent.service.tooldef;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils;
import cn.enlev.ainb.framework.tenant.core.context.TenantContextHolder;
import cn.enlev.ainb.module.agent.controller.admin.tooldef.vo.AgentRegisteredToolRespVO;
import cn.enlev.ainb.module.agent.controller.admin.tooldef.vo.AgentRegisteredToolUpdateReqVO;
import cn.enlev.ainb.module.agent.dal.dataobject.tooldef.AgentToolOverrideAuditDO;
import cn.enlev.ainb.module.agent.dal.dataobject.tooldef.AgentToolOverrideDO;
import cn.enlev.ainb.module.agent.dal.mysql.tooldef.AgentToolOverrideAuditMapper;
import cn.enlev.ainb.module.agent.dal.mysql.tooldef.AgentToolOverrideMapper;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolRegistry;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolRegistry.ToolDefinition;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolRegistry.ToolOverride;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_TOOL_DEF_RISK_INVALID;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_TOOL_OVERRIDE_RELAX_NEEDS_CONFIRM;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_TOOL_OVERRIDE_TOOL_NOT_REGISTERED;

/**
 * 内置（已注册）工具在线维护 Service 实现（2026-09-28 增量）
 *
 * <h3>🔴 生效值按租户现算，缓存整体刷新</h3>
 * 注册表是单例、覆盖是租户级数据（{@code tenant.enable=true}）。故本 Service <b>不</b>把覆盖写进注册表的
 * 某个可变字段（那会跨租户污染），而是：改动落库后，从库里<b>重建当前租户的完整覆盖快照</b>，
 * 调 {@link AgentToolRegistry#refreshTenantOverrides} 整体替换缓存。注册表的 getter 在读时按
 * {@link TenantContextHolder} 现取现算，于是网关/提示词/需确认判定一行都不用改就跟随维护结果、且天然租户隔离。
 *
 * <h3>🔴 放松要表态、要落库（B1 / S11，2026-10-05）</h3>
 * 「全字段自由维护」这个产品决策包含调低风险与删空权限，迭代四 §11 的 A-Ⅲ 又确认了一次：
 * <b>保留放松能力 + 落库审计 + 二次确认</b>，而不是「降险即从自主路径消失」。故本 Service 对放松的处置是三件事：
 * <ol>
 *   <li>{@link #detectRelaxation} 算出「到底放松了什么、从什么到什么、后果是什么」，没放松返回 null；</li>
 *   <li>放松了却没带 {@code confirmRelax=true} ⟹ 抛 {@code AGENT_TOOL_OVERRIDE_RELAX_NEEDS_CONFIRM}。
 *       🔴 这道闸在 upsert <b>之前</b>，所以被拒的那次一个字都不落库（覆盖行、审计行都没有）；</li>
 *   <li>放松了且已确认 ⟹ upsert 之后插一行 {@code agent_tool_override_audit}，同时照旧 {@code log.warn}。
 *       两者同一个事务，不会出现「真放松了却没痕迹」或「有痕迹但没真放松」的错位。</li>
 * </ol>
 * 只 {@code log.warn} 是不够的：日志会滚会清、生产上没人 grep，而审计行要能在事后脱离当次请求被读懂。
 *
 * <h3>🔴 两种放松的代价<b>不一样重</b>，别混为一谈（2026-10-05 实测口径）</h3>
 * 全部运行期闸——{@code WorkflowDslValidator} 的 V3/V11、{@code WorkflowDslPlannerPrompt} 的工具目录、
 * {@code AgentLoopGraphBuilder} 的两道闸、{@code AgentChatToolLoop}、{@code AgentDefServiceImpl} 的发布判定、
 * {@code AgentToolExecutor} 的沙箱 mock 判定、{@code WorkflowChangeManifestBuilder}——读的<b>都是</b>
 * {@code getDeclaredRisk()}（§3.1 第 5 条红线，由 {@code AgentArchitectureTest} 守着）。
 * 生效值 {@code getRisk()}/{@code isRequireConfirm()} 在 main 里的调用方<b>全是界面</b>
 * （管理页 tool-list、设计器名称目录、诊断端点、本类的合并视图）。于是：
 * <ul>
 *   <li><b>降风险</b>：不改变任何强制行为，只改变界面上的风险标注。真正要紧的后果反而是
 *       「设计器把它标成无需确认、用户放进 agent_loop、发布期被 V3 按声明值拒」这种<b>界面误导</b>；</li>
 *   <li><b>删权限</b>：是<b>真</b>放松。{@code AgentSecurityGateway#checkTool} 逐个权限 AND 判定时读的是
 *       生效值 {@code getPermissions()}，删空之后「调用只需登录」立刻对 AI 生效。</li>
 * </ul>
 * {@link #detectRelaxation} 因此把两者分开描述，二次确认的文案与审计行都按各自<b>真实</b>后果说话。
 * 🔴 旧文案（前端面板顶部那句「降为只读后不再需要用户确认即可被 AI 调用」）是迭代三把闸改读声明值<b>之前</b>
 * 写的，已经不成立，见台账 D4-22。
 *
 * <h3>启动装配</h3>
 * {@link #registerOverrideLoader()} 把一个「按租户懒加载覆盖」的回调注册进注册表。注册表在某租户<b>首次被读到</b>时
 * 回调 {@link #loadTenantOverrides}——彼时正处在该租户的请求上下文里，用普通租户隔离查询即可拿到且只拿到该租户的
 * 覆盖行，天然隔离、无需绕过租户拦截器（本模块架构测试明令禁止 {@code executeIgnore}）。
 *
 * <h3>🔴 同名多行是<b>允许存在</b>的状态，本类负责收敛它（D4-23，2026-10-05）</h3>
 * {@code agent_tool_override} 上没有唯一索引，而且<b>加不上</b>：{@code deleted} 是软删标记，
 * 「恢复默认」再「重新维护」留下的墓碑会继续占着同一个 {@code (tenant_id, tool_name)}
 * （实测本仓 {@code datamodel_create} 名下已积 28 行墓碑）。于是并发保存能落下两行 live，
 * 而旧实现用 {@code selectOne} 读它 —— 多行时<b>永久</b>抛 {@code TooManyResultsException}，
 * 且 {@code update} 与 {@code reset} 都走这个查询，等于「保存」与「恢复默认」两个按钮一起坏，UI 里没有出路。
 *
 * <p>本类的处置是「读到时自愈」：{@link #healDuplicateOverrides} 保留最新一行、软删其余并 {@code log.error}；
 * {@link #reset} 则把同名的 live 行<b>全删</b>（它的语义是回到默认值，留一行等于没 reset）。
 * 三处读快照（注册表懒加载、维护后重建缓存、管理页合并视图）统一按「最新那条生效」收敛 ——
 * 这个口径必须与自愈保留的是<b>同一条</b>，否则自愈本身就会改掉工具的生效配置。详见
 * {@link AgentToolOverrideMapper} 类注释与 {@link #buildTenantOverrideSnapshot}。
 *
 * @author 走进新时代
 */
@Service
@Validated
@Slf4j
public class AgentToolOverrideServiceImpl implements AgentToolOverrideService {

    @Resource
    private AgentToolRegistry toolRegistry;
    @Resource
    private AgentToolOverrideMapper overrideMapper;
    @Resource
    private AgentToolOverrideAuditMapper overrideAuditMapper;

    /**
     * 启动装配：把「按租户懒加载覆盖」的能力注册进注册表。
     *
     * <p>🔴 不在此预读全租户覆盖：那需要 {@code TenantUtils.executeIgnore} 关掉租户隔离，而本模块架构测试
     * （{@code AgentArchitectureTest#testNoTenantExecuteIgnore}）明令禁止。改为注册一个加载器，
     * 由注册表在<b>某租户首次被读到</b>时回调 {@link #loadTenantOverrides}——彼时正处在该租户的请求上下文里，
     * 用普通租户隔离查询即可拿到且只拿到该租户的覆盖行，天然隔离、无需绕过。
     */
    @PostConstruct
    public void registerOverrideLoader() {
        toolRegistry.setOverrideLoader(this::loadTenantOverrides);
    }

    /**
     * 懒加载某租户的覆盖快照（注册表回调）。
     *
     * <p>在调用时的租户上下文里跑（注册表用 {@code TenantContextHolder.getTenantId()} 触发，二者一致），
     * {@code selectListAll()} 被租户拦截器自动拼 {@code tenant_id=?}，只返回当前租户的行。
     *
     * @param tenantId 注册表递过来的租户号。<b>本方法不使用它</b>——隔离由租户拦截器按线程上下文完成，
     *                 而触发回调的正是这个上下文的持有者，故两者必然一致。参数留着是因为它是
     *                 {@code Function<Long, Map<String, ToolOverride>>} 这个回调形状要求的。
     */
    private Map<String, ToolOverride> loadTenantOverrides(Long tenantId) {
        return buildTenantOverrideSnapshot();
    }

    @Override
    public List<AgentRegisteredToolRespVO> listMerged() {
        // 预载当前租户全部覆盖行（含元数据），避免逐工具查库。
        // 🔴 合并函数取 (a, b) -> a 即「先出现的赢」，与 selectListAll 的「最新在前」排序配套 =
        //    同名多行（D4-23）时元数据回显的是最新那行。别顺手改成 (a, b) -> b：那会让「覆盖行元数据」
        //    这一栏显示最旧那行的 updater/updateTime，而生效值取自最新那行，界面上两栏对不上。
        Map<String, AgentToolOverrideDO> overrideByTool = overrideMapper.selectListAll().stream()
                .filter(o -> StrUtil.isNotBlank(o.getToolName()))
                .collect(Collectors.toMap(AgentToolOverrideDO::getToolName, o -> o, (a, b) -> a));

        List<AgentRegisteredToolRespVO> result = new ArrayList<>();
        for (ToolDefinition def : toolRegistry.list()) {
            // 🔴 在请求的租户上下文里读 getter → 生效值自动带当前租户的维护结果
            AgentToolOverrideDO row = overrideByTool.get(def.getName());
            result.add(toRespVO(def, row));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(AgentRegisteredToolUpdateReqVO reqVO) {
        String toolName = reqVO.getToolName();
        ToolDefinition def = toolRegistry.get(toolName);
        if (def == null) {
            throw exception(AGENT_TOOL_OVERRIDE_TOOL_NOT_REGISTERED, toolName);
        }
        // 风险等级：留空=不覆盖（沿用声明）；非空必须合法
        RiskLevel newRisk = parseRiskOrNull(reqVO.getRiskLevel());
        List<String> newPermissions = splitPermissions(reqVO.getPermissions());

        // 🔴 放松判定必须在 upsert 之前：未确认就抛，此时覆盖行与审计行【都还没写】。
        //    这个顺序就是「放松但被拒不留痕」这条断言的全部内容 —— 反过来写（先落库再判）
        //    会产出一行记录了「一次并未发生的放松」的审计，比没有审计更难查。
        Relaxation relaxation = detectRelaxation(def, newRisk, newPermissions);
        if (relaxation != null && !Boolean.TRUE.equals(reqVO.getConfirmRelax())) {
            throw exception(AGENT_TOOL_OVERRIDE_RELAX_NEEDS_CONFIRM, toolName, relaxation.summary());
        }

        // upsert 覆盖行（整份重写）
        // 🔴 先自愈再 upsert：本表建不了唯一索引（见 Mapper 类注释），并发保存能落下两行 live，
        //    而旧的 selectOne 在这种情况下会永久抛 TooManyResultsException。这里恰好是唯一能把它修回来的
        //    入口 —— 连「恢复默认」也走同一个查询，所以不自愈的话两个按钮一起坏，UI 里没有出路。
        AgentToolOverrideDO row = healDuplicateOverrides(toolName, overrideMapper.selectListByToolName(toolName));
        boolean isNew = row == null;
        if (isNew) {
            row = new AgentToolOverrideDO();
            row.setToolName(toolName);
        }
        row.setDisplayName(StrUtil.nullToEmpty(reqVO.getDisplayName()));
        row.setDescription(reqVO.getDescription());
        row.setGroupName(StrUtil.nullToEmpty(reqVO.getGroupName()));
        row.setRiskLevel(StrUtil.isBlank(reqVO.getRiskLevel()) ? "" : newRisk.name());
        row.setPermissions(StrUtil.nullToEmpty(reqVO.getPermissions()));
        row.setEnabled(reqVO.getEnabled());
        row.setParamsSchema(reqVO.getParamsSchema());
        row.setRemark(reqVO.getRemark());
        if (isNew) {
            overrideMapper.insert(row);
        } else {
            overrideMapper.updateById(row);
        }

        // 审计行在 upsert 之后：只记「真的生效了的放松」。与上面同一个事务，写不进就一起回滚
        if (relaxation != null) {
            recordRelaxation(toolName, relaxation, reqVO.getRemark());
        }

        refreshCurrentTenant();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reset(String toolName) {
        if (toolRegistry.get(toolName) == null) {
            throw exception(AGENT_TOOL_OVERRIDE_TOOL_NOT_REGISTERED, toolName);
        }
        // 🔴 全删，不复用 healDuplicateOverrides：reset 的语义是「回到代码声明的默认值」，
        //    留下「最新的那一行」等于没 reset。D4-23 之下同名可能有多行 live，而旧实现只删
        //    selectOne 返回的那一行 —— 即便 selectOne 不抛，也会漏删另一行，于是管理员点了「恢复默认」、
        //    界面提示成功、工具却仍被覆盖着（正是本平台头号缺陷形态「操作成功但不生效且无提示」）。
        // 🔴 加了 @Transactional：本方法从「删一行」变成了「删 N 行 + 重建缓存」。没有事务的话，
        //    第 2 行删失败会留下「删了一半、缓存还是旧的」这种半吊子状态 —— 那是本次改动引入的新失败形态，
        //    必须一起收掉。
        List<AgentToolOverrideDO> rows = overrideMapper.selectListByToolName(toolName);
        if (rows.size() > 1) {
            log.error("[reset][🔴覆盖行重复] 工具({}) 在当前租户({}) 下有 {} 行 live 覆盖，"
                            + "本次「恢复默认」把它们全部软删（id={}）。多行的成因见 D4-23：本表没有唯一索引，"
                            + "并发保存能落多行。",
                    toolName, TenantContextHolder.getTenantId(), rows.size(),
                    rows.stream().map(AgentToolOverrideDO::getId).toList());
        }
        for (AgentToolOverrideDO row : rows) {
            overrideMapper.deleteById(row.getId());
        }
        // 幂等：即便没有覆盖行，也重建一次当前租户缓存（结果都是回到默认）
        refreshCurrentTenant();
    }

    // ==================== 内部 ====================

    /** 从库里重建【当前租户】的完整覆盖快照并整体替换注册表缓存 */
    private void refreshCurrentTenant() {
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        toolRegistry.refreshTenantOverrides(tenantId, buildTenantOverrideSnapshot());
    }

    /**
     * 读出当前租户的覆盖快照：{@code toolName} → 生效覆盖值。
     *
     * <p>🔴 用 {@code putIfAbsent} 而不是 {@code put}，与 {@code selectListAll()} 的「最新在前」排序配套，
     * 于是同名多行（D4-23）时保留的是<b>最新</b>那条 —— 与 {@link #healDuplicateOverrides} 保留的是同一条。
     * 这个一致性是有实质后果的：两边口径若相反，「自愈」这个动作本身就会改掉工具的生效配置
     * （读路径原本按无序结果集的末行生效，自愈后换成另一行，而期间没有任何人做过一次维护操作）。
     *
     * <p>🔴 注册表懒加载与在线维护后的缓存重建<b>共用这一份</b>，就是为了「哪一行生效」只有一个答案。
     * 此前 {@code loadTenantOverrides} 与 {@code refreshCurrentTenant} 是两段逐字相同的循环，
     * 改一份忘一份的话，启动装配与在线维护会按不同口径收敛同一批数据 ——
     * 表现是「重启后工具的行为和刚才在管理页看到的不一样」，而这种缺陷没有异常也没有红测试。
     */
    private Map<String, ToolOverride> buildTenantOverrideSnapshot() {
        Map<String, ToolOverride> map = new LinkedHashMap<>();
        for (AgentToolOverrideDO row : overrideMapper.selectListAll()) {
            if (StrUtil.isNotBlank(row.getToolName())) {
                map.putIfAbsent(row.getToolName(), toOverride(row));
            }
        }
        return map;
    }

    /**
     * 同名多行时保留最新那条、软删其余；返回留下的那行（一行都没有则返回 {@code null}）。
     *
     * <p>🔴 这是 D4-23 的自愈。本表建不了唯一索引（软删墓碑会占着同一个键，理由见
     * {@link AgentToolOverrideMapper} 类注释），所以「并发保存落下两行 live」拦不住，只能在读到的时候修。
     * 不修的后果不是「多一行脏数据」，而是 {@code selectOne} 从此<b>永久</b>抛
     * {@code TooManyResultsException}，而 {@code update} 与 {@code reset} 都走它 ——
     * 「保存」坏了，界面上「恢复默认」那个逃生门也一起坏，管理员在 UI 里无路可走。
     *
     * <p>🔴 用<b>软删</b>而不是物理删（与裁定的措辞有一处偏离，理由如下）：
     * <ol>
     *   <li>{@code deleteById} 经 {@code @TableLogic} 本来就是软删，而软删<b>已经</b>让这些行对
     *       {@code selectOne} 与全部读路径不可见 —— 自愈的目的完全达到，物理删换不到任何额外效果；</li>
     *   <li>保住了证据：两行的值可能不同（两个管理员各填了一套），事后要查「当时到底写进去什么」，
     *       墓碑是唯一的一手材料。物理删之后除了日志里那一句，什么都留不下；</li>
     *   <li>与本表既有的墓碑同构（实测 59 行，其中 {@code datamodel_create} 名下 28 行），
     *       不引入第二种删除语义；</li>
     *   <li>物理删要在 Mapper 上另开一个手写 {@code @Delete}，多一处面积、还得自己保证租户条件不被漏掉。</li>
     * </ol>
     *
     * <p>🔴 记 {@code log.error} 而不是 {@code warn}：能走到这里说明数据已经处于本设计<b>不该出现</b>的形状，
     * 且它是并发写竞态的证据。自愈只让功能恢复，不代表这次竞态不该被查 ——
     * 尤其当两行的值不同时，「留下哪一行」是有实质后果的取舍，必须有人看得见。
     *
     * @param rows {@code selectListByToolName} 的结果，已按「最新在前」排序
     */
    private AgentToolOverrideDO healDuplicateOverrides(String toolName, List<AgentToolOverrideDO> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        AgentToolOverrideDO keep = rows.get(0);
        if (rows.size() == 1) {
            return keep;
        }
        List<AgentToolOverrideDO> extras = rows.subList(1, rows.size());
        log.error("[healDuplicateOverrides][🔴覆盖行重复自愈] 工具({}) 在当前租户({}) 下有 {} 行 live 覆盖，"
                        + "本表没有唯一索引、并发保存能落多行（D4-23）。保留最新的一行(id={})，软删其余(id={})。"
                        + "被删行的原值仍在库里（deleted=1 的墓碑），本次没有物理删除。",
                toolName, TenantContextHolder.getTenantId(), rows.size(), keep.getId(),
                extras.stream().map(AgentToolOverrideDO::getId).toList());
        for (AgentToolOverrideDO extra : extras) {
            overrideMapper.deleteById(extra.getId());
        }
        return keep;
    }

    /** DO 行 → 注册表覆盖值载体（空值语义见 {@link ToolOverride}） */
    private ToolOverride toOverride(AgentToolOverrideDO row) {
        return new ToolOverride(
                row.getDisplayName(),
                row.getDescription(),
                row.getGroupName(),
                parseRiskOrNull(row.getRiskLevel()),
                splitPermissions(row.getPermissions()),
                row.getEnabled(),
                row.getParamsSchema());
    }

    private AgentRegisteredToolRespVO toRespVO(ToolDefinition def, AgentToolOverrideDO row) {
        AgentRegisteredToolRespVO vo = new AgentRegisteredToolRespVO();
        vo.setToolName(def.getName());
        // 生效值
        vo.setDisplayName(def.getDisplayName());
        vo.setDescription(def.getDescription());
        vo.setGroupName(def.getGroupName());
        vo.setRiskLevel(def.getRisk().getLevel());
        vo.setRiskDescription(def.getRisk().getDescription());
        vo.setPermissions(joinPermissions(def.getPermissions()));
        vo.setEnabled(def.isEnabled());
        vo.setRequireConfirm(def.isRequireConfirm());
        vo.setParamsSchema(def.getParamsSchema());
        vo.setOverridden(def.isOverridden());
        // 声明值（回显「正在覆盖什么」）
        vo.setDeclaredDisplayName(def.getDeclaredDisplayName());
        vo.setDeclaredDescription(def.getDeclaredDescription());
        vo.setDeclaredGroupName(def.getDeclaredGroupName());
        vo.setDeclaredRiskLevel(def.getDeclaredRisk().getLevel());
        vo.setDeclaredPermissions(joinPermissions(def.getDeclaredPermissions()));
        vo.setDeclaredEnabled(def.isDeclaredEnabled());
        vo.setDeclaredParamsSchema(def.getDeclaredParamsSchema());
        vo.setImplClassName(def.getType() != null ? def.getType().getName() : null);
        // 覆盖行元数据
        if (row != null) {
            vo.setOverrideId(row.getId());
            vo.setOverrideUpdateTime(row.getUpdateTime());
            vo.setOverrideUpdater(row.getUpdater());
            vo.setRemark(row.getRemark());
        }
        return vo;
    }

    /**
     * 一次「安全放松」的完整事实。
     *
     * @param summary 一句话代价说明，<b>同时</b>用于二次确认的报错占位符、审计留痕与 {@code log.warn}。
     *                三处必须是同一句话，否则「操作人当时确认的」与「库里事后查到的」会对不上，
     *                而审计行的全部价值就在于它记的是当时那一句
     */
    private record Relaxation(String summary,
                              String riskFrom, String riskTo,
                              Boolean requireConfirmFrom, Boolean requireConfirmTo,
                              String permissionsFrom, String permissionsTo) {
    }

    /**
     * 算出本次维护放松了什么；<b>没放松返回 {@code null}</b>。
     *
     * <p>两个维度分开判、分开描述，因为它们的代价不一样重（见类注释）。
     *
     * <p>🔴 风险比的是 {@link RiskLevel#getSeverity()}，<b>不是</b> {@code ordinal()}。
     * {@code severity} 那个字段的 javadoc 明写了「不让调用方直接比 {@code ordinal()}/{@code compareTo()}」，
     * 理由是五个常量的声明顺序只是给人看的排版、没有任何东西强制它。而这里比的恰恰是一条<b>安全判定</b>：
     * 谁在 {@code LOW} 前面插一个常量，按 ordinal 比就会静默改判「这算不算放松」，
     * 表现只是审计行少记一条、二次确认不再弹 —— 没有异常、没有红测试。
     * 原实现（{@code warnIfRelaxed}）用的正是 ordinal，本次一并改掉。
     *
     * <p>权限维度判的是「新集合是否仍覆盖声明集合的全部权限点」，不是「字符串是否变短」：
     * {@code a,b} 改成 {@code b,a} 不算放松，改成 {@code a,c} 算（丢了 b）。
     */
    private Relaxation detectRelaxation(ToolDefinition def, RiskLevel newRisk, List<String> newPermissions) {
        RiskLevel declaredRisk = def.getDeclaredRisk();
        List<String> declaredPermissions = def.getDeclaredPermissions();
        boolean riskRelaxed = newRisk != null && newRisk.getSeverity() < declaredRisk.getSeverity();
        boolean permissionRelaxed = !newPermissions.containsAll(declaredPermissions);
        if (!riskRelaxed && !permissionRelaxed) {
            return null;
        }

        String riskFrom = "";
        String riskTo = "";
        Boolean confirmFrom = null;
        Boolean confirmTo = null;
        String permissionsFrom = "";
        String permissionsTo = "";
        StringBuilder summary = new StringBuilder();

        if (riskRelaxed) {
            riskFrom = declaredRisk.getLevel();
            riskTo = newRisk.getLevel();
            confirmFrom = declaredRisk.isRequireConfirm();
            confirmTo = newRisk.isRequireConfirm();
            // 🔴 这段「不会绕过什么」必须写进代价说明，不能只写「风险降低了」：
            //    界面上给管理员看的一直是「降为只读后就不再需要用户确认即可被 AI 调用」，
            //    而迭代三把全部运行期闸改读声明值之后那句话已经不成立（台账 D4-22）。
            //    照着旧口径确认，等于让人为一个不会发生的后果担责，也让他对真后果毫无准备。
            summary.append(String.format(
                    "风险 %s→%s（界面上的「需用户确认」标注 %s→%s；但运行期各道闸读的是【声明】风险级，"
                            + "故这一改不会让它绕过发布判定、agent_loop 只读限制或沙箱 mock，"
                            + "真会发生的是设计器与工具清单从此把它标成「无需确认」，而校验仍按声明值拒它）",
                    riskFrom, riskTo, confirmFrom, confirmTo));
        }

        if (permissionRelaxed) {
            if (!summary.isEmpty()) {
                summary.append("；");
            }
            permissionsFrom = joinPermissions(declaredPermissions);
            permissionsTo = joinPermissions(newPermissions);
            List<String> missing = declaredPermissions.stream()
                    .filter(p -> !newPermissions.contains(p))
                    .toList();
            summary.append(String.format("权限 [%s]→[%s]（少了 %s；网关逐个 AND 判定时读的就是这份【生效】权限串，"
                            + "所以没有这些权限点的用户、其 AI 也能调它 —— 这一条【立即生效】）",
                    permissionsFrom, permissionsTo, missing));
        }

        return new Relaxation(summary.toString(), riskFrom, riskTo, confirmFrom, confirmTo,
                permissionsFrom, permissionsTo);
    }

    /**
     * 记一次<b>已确认且已生效</b>的放松：插一行审计 + {@code log.warn}。
     *
     * <p>两者都留，不是重复：审计行给事后复核的人（可查、带操作人昵称、不会被日志滚动冲掉），
     * {@code log.warn} 给当下正在盯生产日志的人 —— 放松是安全事件，值得在日志里以 WARN 级别冒出来一次。
     *
     * <p>🔴 取不到登录用户（定时任务、单测里直接调 Service）时 {@code operatorId} 为 null、昵称为空串，
     * 但<b>照样落行</b>：痕迹宁可少一个名字，也不能因为拿不到名字就不记这次放松。
     */
    private void recordRelaxation(String toolName, Relaxation relaxation, String remark) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        String nickname = StrUtil.nullToEmpty(SecurityFrameworkUtils.getLoginUserNickname());

        AgentToolOverrideAuditDO audit = new AgentToolOverrideAuditDO();
        audit.setToolName(toolName);
        audit.setRiskFrom(relaxation.riskFrom());
        audit.setRiskTo(relaxation.riskTo());
        audit.setRequireConfirmFrom(relaxation.requireConfirmFrom());
        audit.setRequireConfirmTo(relaxation.requireConfirmTo());
        audit.setPermissionsFrom(relaxation.permissionsFrom());
        audit.setPermissionsTo(relaxation.permissionsTo());
        audit.setOperatorId(userId);
        audit.setOperatorName(nickname);
        // 抄一份当时的理由：覆盖行的 remark 会被后续维护整份重写，审计行必须自带当时的事实
        audit.setRemark(StrUtil.nullToEmpty(remark));
        overrideAuditMapper.insert(audit);

        log.warn("[recordRelaxation][🔴安全放松留痕] 操作人(userId={}, nickname={}) 放松了内置工具({})：{}。"
                        + "产品决策允许放松（不拦截），操作人已二次确认，已落 agent_tool_override_audit 一行。租户={}",
                userId, nickname, toolName, relaxation.summary(), TenantContextHolder.getTenantId());
    }

    /** 逗号分隔权限串 → 去空白、去重、不可变列表；空串=空列表（调用只需登录） */
    private List<String> splitPermissions(String raw) {
        if (StrUtil.isBlank(raw)) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .collect(Collectors.toUnmodifiableList());
    }

    private String joinPermissions(List<String> permissions) {
        return permissions == null ? "" : String.join(",", permissions);
    }

    /** 空白 → null（不覆盖风险，沿用声明）；非法值抛业务异常 */
    private RiskLevel parseRiskOrNull(String riskLevel) {
        if (StrUtil.isBlank(riskLevel)) {
            return null;
        }
        try {
            return RiskLevel.valueOf(riskLevel.trim());
        } catch (IllegalArgumentException ex) {
            throw exception(AGENT_TOOL_DEF_RISK_INVALID, riskLevel);
        }
    }

}
