package cn.enlev.ainb.module.agent.service.def;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.module.agent.controller.admin.def.vo.AgentDefPageReqVO;
import cn.enlev.ainb.module.agent.controller.admin.def.vo.AgentDefSaveReqVO;
import cn.enlev.ainb.module.agent.dal.dataobject.def.AgentDefDO;
import cn.enlev.ainb.module.agent.dal.mysql.def.AgentDefMapper;
import cn.enlev.ainb.module.agent.enums.AgentDefStatusEnum;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolCatalog;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_DEF_BUILTIN_READONLY;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_DEF_CODE_EXISTS;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_DEF_NOT_DRAFT;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_DEF_NOT_EXISTS;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_DEF_NOT_PUBLISHED;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_DEF_PUBLISHED_NOT_DELETABLE;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_DEF_TOOL_INVALID;

/**
 * Agent 定义 Service 实现 —— P5 Agent Builder 第一刀
 *
 * <p>纪律与 {@code AgentWorkflowDefServiceImpl} 一致：就地编辑只放行 DRAFT，发布/停用只翻状态且幂等，
 * 翻状态用「只带 id + status 的部分 DO」{@code updateById}（MP 默认 NOT_NULL 策略，其余列不动）。
 *
 * <h3>🔴 改已发布的 Agent 走「派生新版本」，不是就地改（需求11）</h3>
 * {@link #deriveDef} 复制一份源定义成新 DRAFT（同 code、version+1），前端「编辑」按钮对非草稿先调它拿到新 id
 * 再进编辑器。这样既守住「已发布版本是不可变快照」，又不逼管理员照着原配置手抄一遍「新增」。
 *
 * <h3>🔴 系统内置行（默认平台助手）是版本化的唯一例外（需求10）</h3>
 * 见 {@link AgentDefDO#getBuiltin()}：就地改、只认 name/persona/description、不可删不可停用不可派生。
 *
 * @author 走进新时代
 */
@Service
@Slf4j
public class AgentDefServiceImpl implements AgentDefService {

    @Resource
    private AgentDefMapper defMapper;

    /**
     * 工具目录（增量12 / slice-3b；B2 起换成 {@link AgentToolCatalog}）：发布 Agent 时校验
     * {@code toolKeys} 里每个键都已登记且为只读（<b>声明</b>风险级不要求确认）。
     * 聊天工具循环无人工确认，写工具绝不能绑进来自动执行。
     *
     * <p>🔴 此前这里注入的是 {@code AgentToolRegistry}，即<b>只认代码内置工具</b>——而运行期的
     * {@code AgentSecurityGateway#checkTool} 与 {@code AgentToolExecutor#execute} 都是
     * registry-first、{@code agent_tool_def} 回落，自定义工具是真能执行的。于是「自定义工具绑不到智能体」
     * 成了必然：发布期一句「工具(x)未登记」把它挡死。换成目录之后两类工具走同一份判据，
     * 理由与其余四处同源调用点写在 {@link AgentToolCatalog} 的类注释里。
     */
    @Resource
    private AgentToolCatalog toolCatalog;

    @Override
    public PageResult<AgentDefDO> getDefPage(AgentDefPageReqVO pageReqVO) {
        return defMapper.selectPage(pageReqVO);
    }

    @Override
    public AgentDefDO getDef(Long id) {
        AgentDefDO def = defMapper.selectById(id);
        if (def == null) {
            throw exception(AGENT_DEF_NOT_EXISTS);
        }
        return def;
    }

    @Override
    public AgentDefDO getExecutableDef(Long id) {
        AgentDefDO def = getDef(id); // 不存在已抛 AGENT_DEF_NOT_EXISTS
        // fail-close：非 PUBLISHED（草稿/已停用）与「不存在」同文案，不泄漏状态存在性；
        // 与「前端选择器只列 PUBLISHED」自洽，陈旧 UI 选了已停用/草稿的边界也安全兜住。
        if (!AgentDefStatusEnum.PUBLISHED.getStatus().equals(def.getStatus())) {
            throw exception(AGENT_DEF_NOT_EXISTS);
        }
        return def;
    }

    @Override
    public AgentDefDO getPlatformAssistant() {
        // 按 builtin 标记取而不是按 code：code 是给人看的业务编码，万一有环境没跑种子迁移、
        // 或有人手工插过一行同 code 的普通 Agent，按 code 取会 selectOne 撞上 TooManyResults 把对话打死。
        // 取 id 最小的那行（种子只插一行，多出来的必是脏数据，选最早的那条行为最可预期）。
        List<AgentDefDO> builtins = defMapper.selectList(AgentDefDO::getBuiltin, true);
        return builtins.stream()
                .min(Comparator.comparing(AgentDefDO::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null); // 查不到返回 null，调用方回落代码里的 BASE_PROMPT（见接口注释）
    }

    @Override
    public Long createDef(AgentDefSaveReqVO createReqVO) {
        validateCodeNotReserved(createReqVO.getCode());
        AgentDefDO def = newVersionFrom(null, createReqVO.getCode(), createReqVO.getName());
        def.setPersona(createReqVO.getPersona());
        def.setModelId(createReqVO.getModelId()); // 可空：空=平台动态默认模型
        def.setKnowledgeId(createReqVO.getKnowledgeId()); // 可空：空=该 Agent 不检索知识库
        def.setToolKeys(normalizeToolKeys(createReqVO.getToolKeys())); // 可空：空=不启用工具循环
        def.setDescription(createReqVO.getDescription());
        defMapper.insert(def);
        log.info("[createDef][新增 Agent 定义 code({}) version({}) status({}) id({})]",
                def.getCode(), def.getVersion(), def.getStatus(), def.getId());
        return def.getId();
    }

    @Override
    public void updateDef(AgentDefSaveReqVO updateReqVO) {
        AgentDefDO existing = getDef(updateReqVO.getId());
        // 需求10：内置行（默认平台助手）是单例配置，虽为 PUBLISHED 也允许就地改，但只认三列
        if (isBuiltin(existing)) {
            updateBuiltinDef(existing, updateReqVO);
            return;
        }
        // 🔴 只放行草稿：已发布/已停用是不可变版本快照，就地改人设会让按该版本路由的对话前后不一致。
        if (!AgentDefStatusEnum.DRAFT.getStatus().equals(existing.getStatus())) {
            throw exception(AGENT_DEF_NOT_DRAFT, existing.getStatus());
        }
        // 部分 DO：只带 id + 允许改的列（name/persona/modelId/knowledgeId/description），其余列（code/version/status）不动。
        // MP updateById 默认 NOT_NULL 策略，null 字段不进 SET，故未提供的列保持原值。
        // ⚠️ 副作用：把 modelId / knowledgeId 从「已绑定」清回 null（恢复动态默认 / 恢复不检索）经此路径不生效——NOT_NULL 会跳过 null。
        //    这是有意的保护（编辑表单未加载到该列时不会误清绑定）；「换绑到另一个模型 / 知识库」正常生效。
        AgentDefDO update = new AgentDefDO();
        update.setId(existing.getId());
        update.setName(updateReqVO.getName());
        update.setPersona(updateReqVO.getPersona());
        update.setModelId(updateReqVO.getModelId());
        update.setKnowledgeId(updateReqVO.getKnowledgeId());
        // 🔴 toolKeys 归一成「逗号串或空串」（绝不 null）：与 modelId/knowledgeId 的 NOT_NULL 保护不同，
        //    空串会进 SET 子句，故「取消勾选全部工具」能真正清空该列（清空工具是常见操作，不该被 NOT_NULL 挡住）。
        update.setToolKeys(normalizeToolKeys(updateReqVO.getToolKeys()));
        update.setDescription(updateReqVO.getDescription());
        defMapper.updateById(update);
        log.info("[updateDef][修改 Agent 草稿定义 id({}) code({})]", existing.getId(), existing.getCode());
    }

    /**
     * 内置行就地改（需求10）
     *
     * <p>🔴 只写 name / persona / description 三列，<b>刻意不碰</b> modelId / knowledgeId / toolKeys：
     * 「未选 Agent」这条路上根本不读这三列（默认助手固定检索平台内置手册、固定用动态默认模型、不跑工具循环）。
     * 把它们写进去就是造一份「保存成功但不生效」的死配置 —— 本平台头号缺陷。
     * 编辑表单对内置行也不渲染这三项，两端一致，管理员不会以为自己配上了。
     *
     * <p>code / version / status 同样不动：内置行不参与版本化，恒为 version=1 / PUBLISHED。
     */
    private void updateBuiltinDef(AgentDefDO existing, AgentDefSaveReqVO updateReqVO) {
        AgentDefDO update = new AgentDefDO();
        update.setId(existing.getId());
        update.setName(updateReqVO.getName());
        update.setPersona(updateReqVO.getPersona());
        update.setDescription(updateReqVO.getDescription());
        defMapper.updateById(update);
        log.info("[updateBuiltinDef][就地修改系统内置智能体 id({}) code({})，下一句对话即生效]",
                existing.getId(), existing.getCode());
    }

    @Override
    public Long deriveDef(Long id) {
        AgentDefDO source = getDef(id);
        if (isBuiltin(source)) {
            throw exception(AGENT_DEF_BUILTIN_READONLY, source.getName());
        }
        // 同 code 出新版本：与 createDef 共用版本号计算，避免两处各写一份 nextVersion 逻辑而漂移
        AgentDefDO draft = newVersionFrom(source, source.getCode(), source.getName());
        defMapper.insert(draft);
        log.info("[deriveDef][基于 id({}) 派生新草稿 code({}) version({}) 新id({})]",
                id, draft.getCode(), draft.getVersion(), draft.getId());
        return draft.getId();
    }

    @Override
    public Long copyDef(Long id, String newCode, String newName) {
        AgentDefDO source = getDef(id);
        validateCodeNotReserved(newCode);
        // 查重：撞上已有 code 会静默变成「给别的 Agent 加了个版本」，见 AGENT_DEF_CODE_EXISTS 注释
        if (!defMapper.selectList(AgentDefDO::getCode, newCode).isEmpty()) {
            throw exception(AGENT_DEF_CODE_EXISTS, newCode);
        }
        AgentDefDO draft = newVersionFrom(source, newCode,
                StrUtil.blankToDefault(newName, source.getName() + "（副本）"));
        defMapper.insert(draft);
        log.info("[copyDef][复制 id({}) code({}) → 新智能体 code({}) id({})]",
                id, source.getCode(), newCode, draft.getId());
        return draft.getId();
    }

    @Override
    public void deleteDef(Long id) {
        AgentDefDO existing = getDef(id);
        validateDeletable(existing);
        defMapper.deleteById(id);
        log.info("[deleteDef][删除 Agent 定义 id({}) code({}) status({})]",
                id, existing.getCode(), existing.getStatus());
    }

    @Override
    public void deleteDefList(List<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return;
        }
        // 🔴 先整体校验、再整体删除，不边删边校验：批量操作里「删了一半才报错」最难收拾——
        //    管理员看到失败提示以为一条都没动，实际已经少了几行，回到列表才发现，还得逐个回想删了哪些。
        //    一次查回来判完再删，要么全成要么全不成。
        List<AgentDefDO> defs = defMapper.selectByIds(ids);
        // 数量对不上说明有 id 查不到（被别人先删了 / 跨租户）。此时报「不存在」而不是静默少删几条：
        // 静默成功会让用户以为勾中的都删了，而列表刷新后仍有残留，看起来像「删不掉」。
        if (defs.size() != new HashSet<>(ids).size()) {
            throw exception(AGENT_DEF_NOT_EXISTS);
        }
        defs.forEach(this::validateDeletable);
        defMapper.deleteByIds(ids);
        log.info("[deleteDefList][批量删除 Agent 定义 ids({})]", ids);
    }

    /**
     * 删除前的可删性校验（{@link #deleteDef} / {@link #deleteDefList} 共用）
     *
     * <p>需求11：DRAFT 直接删（从未被选用）；DISABLED 也放行（停用已把它从选择器摘掉，不会再有新对话绑上来）；
     * PUBLISHED 拒删——它此刻还在聊天入口的选择器里，可能有人正在用，见 AGENT_DEF_PUBLISHED_NOT_DELETABLE。
     * 内置行一律拒删，见 AGENT_DEF_BUILTIN_READONLY。
     *
     * <p>🔴 两个入口必须共用这一份判据：各写一份必然漂移，漂移的表现是「单删拒了、批量删却过了」，
     * 而批量删正是绕过单删校验最省力的路子。
     */
    private void validateDeletable(AgentDefDO def) {
        if (isBuiltin(def)) {
            throw exception(AGENT_DEF_BUILTIN_READONLY, def.getName());
        }
        if (AgentDefStatusEnum.PUBLISHED.getStatus().equals(def.getStatus())) {
            throw exception(AGENT_DEF_PUBLISHED_NOT_DELETABLE, def.getName());
        }
    }

    @Override
    public Long publishDefById(Long id) {
        AgentDefDO def = getDef(id);
        // 已发布：幂等返回（重复点发布不报错、不新增版本）
        if (AgentDefStatusEnum.PUBLISHED.getStatus().equals(def.getStatus())) {
            return def.getId();
        }
        // 🔴 DISABLED 也放行（重新上线）：停用只是把它从聊天选择器摘掉，配置本身仍是完整可用的一份快照。
        //    真实业务里「临时下线排障、修好了再放回来」是常态；此前一律要求派生新版本，
        //    管理员为了恢复一个原封不动的 Agent 得先派生、再进编辑器、再发布，凭空多出一个内容完全相同的 v(n+1)。
        //    剩下能走到这里的状态只有 DRAFT 与 DISABLED（枚举是封闭的），两者都该发布。
        // 🔴 发布期 fail-close 校验绑定的工具（增量12 / slice-3b）：未登记 / 写工具一律拒发布。
        //    草稿不拦（允许半成品），发布是「要真正生效」的动作——聊天工具循环无人工确认，必须在此挡住写工具。
        //    重新发布同样过这道闸：停用期间工具注册表可能变了（某个只读工具被改成写工具），不重校验就会把它放回线上。
        //    「已停用」不在硬闸里，只作为返回值交给下面那句 warn —— 理由见 validateToolKeys 的 javadoc（D4-25）。
        List<String> disabledTools = validateToolKeys(def.getToolKeys());
        // 只翻状态：部分 DO 只带 id + status，其余列（persona/version）不动
        AgentDefDO update = new AgentDefDO();
        update.setId(def.getId());
        update.setStatus(AgentDefStatusEnum.PUBLISHED.getStatus());
        defMapper.updateById(update);
        log.info("[publishDefById][发布 Agent 定义 id({}) code({}) version({}) {}→PUBLISHED]",
                id, def.getCode(), def.getVersion(), def.getStatus());
        // 🔴 放在翻状态【之后】：这句要成立的前提是「它真的上线了」。写在前面而 updateById 抛了的话，
        //    日志里会留下一条「带着停用工具上线」的记录，而线上根本没这一版 —— 一条对不上现实的告警
        //    比没有告警更坏（与 B1「被拒的放松不落审计行」是同一条纪律）。
        if (!disabledTools.isEmpty()) {
            log.warn("[publishDefById][🔴带着停用工具上线] Agent id({}) code({}) version({}) 绑定的工具 {} "
                            + "当前是停用状态。已照常发布（D4-25 裁定：停用是「工具管理」页的动作，与本 Agent 的配置无关，"
                            + "拦下来会让改人设、换模型这类无关改动也发布不了，连「重新发布」这个恢复动作都会被堵死）。"
                            + "后果：聊天里模型每次调这几个工具都会被安全网关拒（AGENT_TOOL_DISABLED，"
                            + "「AI 助手工具(<键>)已被停用」），用户只看到一句失败。"
                            + "两条出路：从该 Agent 的工具绑定里移除它们，或到「工具管理」页重新打开「启用」开关。",
                    id, def.getCode(), def.getVersion(), disabledTools);
        }
        return def.getId();
    }

    @Override
    public void disableDefById(Long id) {
        AgentDefDO def = getDef(id);
        // 需求10：停用平台助手 = 让所有未选 Agent 的对话失去系统提示词，必须在入口挡掉
        if (isBuiltin(def)) {
            throw exception(AGENT_DEF_BUILTIN_READONLY, def.getName());
        }
        // 已停用：幂等
        if (AgentDefStatusEnum.DISABLED.getStatus().equals(def.getStatus())) {
            return;
        }
        // 只有 PUBLISHED 能停用；DRAFT 没发布过谈不上停用（不想要直接删）
        if (!AgentDefStatusEnum.PUBLISHED.getStatus().equals(def.getStatus())) {
            throw exception(AGENT_DEF_NOT_PUBLISHED, def.getStatus());
        }
        // 只翻状态：停用后不再被选用（slice-2 起聊天入口选不到它）
        AgentDefDO update = new AgentDefDO();
        update.setId(def.getId());
        update.setStatus(AgentDefStatusEnum.DISABLED.getStatus());
        defMapper.updateById(update);
        log.info("[disableDefById][停用 Agent 定义 id({}) code({}) version({}) PUBLISHED→DISABLED]",
                id, def.getCode(), def.getVersion());
    }

    /**
     * 是否系统内置行（默认平台助手）
     *
     * <p>{@code null} 视为 false：加了 {@code builtin} 列之前建的行、以及 H2 测试库里没设这列的行，
     * 都该按普通 Agent 走，而不是被误判成内置而拒绝编辑/删除。
     */
    private boolean isBuiltin(AgentDefDO def) {
        return Boolean.TRUE.equals(def.getBuiltin());
    }

    /**
     * 造一个「新 DRAFT 版本」的壳：code / name / 版本号 / status / builtin 已就位，业务列按 source 决定填不填
     *
     * <p>{@code createDef} / {@code deriveDef} / {@code copyDef} 三个入口共用，为的是让
     * 「版本号怎么算」「新行一定是 DRAFT」「builtin 一定不继承」这三条纪律只有一处真相 ——
     * 各写一份必然漂移，漂移的表现是某个入口建出来的行状态不对，而要等到发布/对话时才炸。
     *
     * @param source 复制源。{@code null}=全新建（业务列留空，由调用方从 ReqVO 填）；
     *               非 null=派生/复制（persona / modelId / knowledgeId / toolKeys / description 一并搬过来）
     */
    private AgentDefDO newVersionFrom(AgentDefDO source, String code, String name) {
        AgentDefDO def = new AgentDefDO();
        def.setCode(code);
        def.setName(name);
        // 🔴 builtin 恒 false：内置标记绝不能被继承，否则「复制一份平台助手」会多出一行不可删、不可停用的僵尸记录
        def.setBuiltin(false);
        // 版本号 = 同 code 现有最大 + 1。复制走的是全新 code，故自然从 1 起（新智能体的版本序列与源无关）
        def.setVersion(nextVersion(code));
        def.setStatus(AgentDefStatusEnum.DRAFT.getStatus());
        if (source != null) {
            def.setPersona(source.getPersona());
            def.setModelId(source.getModelId());
            def.setKnowledgeId(source.getKnowledgeId());
            def.setToolKeys(source.getToolKeys());
            def.setDescription(source.getDescription());
        }
        return def;
    }

    /**
     * 同 code 现有最大版本 + 1（单调递增；已发布版本视为不可变快照，故改动走新版本，见 {@code AgentDefDO} 注释）
     */
    private int nextVersion(String code) {
        return defMapper.selectList(AgentDefDO::getCode, code).stream()
                .map(AgentDefDO::getVersion)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;
    }

    /**
     * 挡住保留编码 {@code platform_assistant}
     *
     * <p>🔴 为什么必须挡：内置助手是按 {@code builtin} 标记取的，但在管理员眼里「默认平台助手」这个名字和
     * 这个编码是同一个东西。放行同 code 的普通 Agent，列表上就会出现两行 code 都叫 platform_assistant 的记录，
     * 而其中只有一行真的驱动聊天入口 —— 改另一行半天没有任何效果，正是本平台头号缺陷
     * 「配置保存成功但不生效且无提示」的形状。
     */
    private void validateCodeNotReserved(String code) {
        if (AgentDefDO.CODE_PLATFORM_ASSISTANT.equals(code)) {
            throw exception(AGENT_DEF_CODE_EXISTS, code);
        }
    }

    /**
     * 归一化工具键串：逐个 trim、丢空项、去重后用逗号重连；空白输入返回<b>空串</b>（非 null，见 updateDef 注释）
     */
    private String normalizeToolKeys(String toolKeys) {
        if (StrUtil.isBlank(toolKeys)) {
            return "";
        }
        List<String> keys = new java.util.ArrayList<>();
        for (String raw : toolKeys.split(",")) {
            String key = raw.trim();
            if (!key.isEmpty() && !keys.contains(key)) {
                keys.add(key);
            }
        }
        return String.join(",", keys);
    }

    /**
     * 发布期校验绑定的工具：每个键都必须已登记且为只读（<b>声明</b>风险级不要求确认）
     *
     * <p>与 {@code AgentLoopGraphBuilder.resolveSpec} 的「编译期只读闸」同款判据：聊天 agentic 循环无人工确认，
     * 写工具（建表/改表/发布等）绝不能绑进来被模型自动执行。未登记工具也拒——填错键会让循环运行期「查不到工具」，
     * 不如发布时就 fail-loud（本平台头号缺陷「配了不生效且无提示」的翻版）。
     *
     * <h3>🔴 「已登记」= 内置注册表 ∪ 当前租户的自定义工具目录（B2，2026-10-18）</h3>
     * 解析统一走 {@link AgentToolCatalog}，与运行期的网关、执行器、system prompt 渲染同一份合流结果。
     * 此前本方法只查 {@code AgentToolRegistry}，而 {@code AgentToolExecutor} 早就支持回落自定义工具，
     * 于是「自定义工具绑不到智能体」是<b>准入自己造出来的</b>限制，不是能力缺失。
     * ⚠️ 但只放开这一处是不够的、而且会造出新的假绿灯：{@code AgentLoopPrompt#appendTools} 若仍旧只查
     * registry，绑上去的自定义工具<b>不会进 system prompt</b>，模型压根不知道有它 —— 发布通过、聊天里
     * 模型永远不调它、也没有任何提示，恰好是本平台头号缺陷的形状。故那一处与两道运行期只读闸同步改了，
     * 五个调用点全部改问目录（清单见 {@link AgentToolCatalog} 类注释的表）。
     *
     * <h3>🔴 「已停用」刻意<b>不</b>进硬闸，只作为警告返回（D4-25 后端半，2026-10-05 裁定）</h3>
     * 前两条判的是<b>这份配置本身对不对</b>——键写错了、或绑了写工具，都是发布者的错，拒了让他改。
     * 「已停用」判的却是<b>别人后来改了什么</b>：工具是在「工具管理」页被管理员停用的，与本 Agent 的配置无关。
     * 把它也做成 error，后果是管理员停用某个工具之后，所有绑了它的 Agent <b>连无关的改动都发布不了</b>
     * （改个人设、换个模型，点发布一律被拒），而拒绝理由指向一个当前操作者既没改、也多半无权改的东西。
     * 更要紧的是本方法只在 {@link #publishDefById} 里被调（create/update 只过 {@link #normalizeToolKeys}），
     * 于是「重新发布」这个恢复动作会一起被堵死 —— 正好与 {@code publishDefById} 上面那段
     * 「DISABLED 也放行（重新上线）」的取舍直接打架。
     *
     * <p>所以这一条走警告：照样发布，但把停用项返回给调用方去 {@code log.warn}，前端发布确认框里也就地说明
     * （{@code views/agent/def/index.vue} 的 {@code handlePublish}）。运行期那道真闸
     * （{@code AgentSecurityGateway#checkTool}）一点没松，该拒还是拒 —— 警告改的只是「有没有提前告诉人」，
     * 不是「放行不放行」。
     *
     * @return 已登记、声明为只读、但当前被<b>停用</b>的工具键；没有则空列表
     */
    private List<String> validateToolKeys(String toolKeys) {
        if (StrUtil.isBlank(toolKeys)) {
            return List.of();
        }
        List<String> disabled = new java.util.ArrayList<>();
        for (String raw : toolKeys.split(",")) {
            String key = raw.trim();
            if (key.isEmpty()) {
                continue;
            }
            AgentToolCatalog.ToolView tool = toolCatalog.resolve(key);
            if (tool == null) {
                // 🔴 文案要说清「查的是哪两处」：B2 之前只查内置注册表，于是管理员在「工具管理」页
                //    明明登记好了一个自定义工具、也开着启用，绑到 Agent 上点发布却报「未登记」——
                //    他会去反复检查那个工具是不是没保存成功。点名两个来源，他才知道该往哪儿看。
                throw exception(AGENT_DEF_TOOL_INVALID,
                        "工具(" + key + ")未登记：既不在内置工具注册表里，当前租户的「工具管理」页也没有它");
            }
            if (!tool.executable()) {
                // FORM / SCRIPT 是 {@code ToolSourceEnum} 里的预留位，buildFunction 遇到它们直接抛
                // AGENT_TOOL_SOURCE_UNSUPPORTED。登记入口只放行 DATA_INTERFACE，故这一支只有
                // 「绕过接口直接改库」才走得到 —— 但真走到了必须拦在发布期：放过去就是
                // 「发布成功、聊天里每次调用都失败」，而用户看不出失败与这次发布有关。
                throw exception(AGENT_DEF_TOOL_INVALID,
                        "工具(" + key + ")的来源(" + tool.source() + ")当前不可执行，不能绑定到 Agent");
            }
            // 🔴 读「声明」风险级，而不是走租户生效值的那个（{@code effectiveRisk}/{@code requireConfirm()}）：
            //    内置工具的生效值可被 agent_tool_override 一行数据从 IRREVERSIBLE 压成 READ_ONLY
            //    （ToolOverride 的 javadoc 明写「risk 允许低于声明值，产品决策」），压完之后
            //    这道发布闸就看不见写工具了 —— 而聊天工具循环是<b>无人值守</b>的，没有 Plan 确认兜着。
            //    界面上工具照样标着写操作、审计里也照样是写操作，只有准入判定被改写，故静默。
            //    自定义工具没有覆盖表，声明值就是它 risk_level 那一列，不存在被洗的路子。
            //    与 AgentLoopGraphBuilder 的两道只读闸、WorkflowDslValidator 的 V3/V11 同口径。
            RiskLevel declaredRisk = tool.declaredRisk();
            if (declaredRisk.isRequireConfirm()) {
                throw exception(AGENT_DEF_TOOL_INVALID,
                        "工具(" + key + ")是写操作工具（声明风险等级 " + declaredRisk.getDescription()
                                + "），聊天工具循环只能绑定只读工具；写操作请走 Plan 确认或工作流 tool 节点");
            }
            // 🔴 这一条读的偏偏是【生效值】isEnabled()，与上面的声明值口径相反，不是笔误：
            //    判「算不算只读」必须按声明值（否则一行覆盖就能把写工具洗成只读，绕过无人值守的准入闸）；
            //    判「调用会不会被拒」必须按生效值 —— 网关读的就是它，「停用」这个维护动作当次即真生效。
            //    两句判的是不同的问题，所以取不同的来源。前端 toolList 与 D4-25 的置灰同此口径。
            if (!tool.enabled()) {
                disabled.add(key);
            }
        }
        return disabled;
    }

}
