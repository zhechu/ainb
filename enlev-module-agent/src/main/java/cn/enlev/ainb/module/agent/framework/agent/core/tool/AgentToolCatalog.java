package cn.enlev.ainb.module.agent.framework.agent.core.tool;

import cn.enlev.ainb.module.agent.dal.dataobject.tooldef.AgentToolDefDO;
import cn.enlev.ainb.module.agent.enums.ToolSourceEnum;
import cn.enlev.ainb.module.agent.service.tooldef.AgentToolDefService;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「一个工具键到底存不存在、危不危险、怎么向模型描述它」的<b>唯一</b>解析点（B2）
 *
 * <h3>🔴 这个类存在的理由：同一件事此前有五份实现，其中四份是错的</h3>
 * 平台有两类工具 —— {@code @AgentTool} 代码工具（{@link AgentToolRegistry} 权威登记）与
 * {@code agent_tool_def} 里的用户自定义工具。运行期的两道真闸早就合流了：
 * {@code AgentSecurityGateway#checkTool} 与 {@code AgentToolExecutor#execute} 都是
 * <b>registry-first、自定义目录回落</b>，所以自定义工具是<b>真能执行</b>的。
 * 但「决定要不要把工具交给模型」的那几处各写各的，且全都只查 registry：
 * <table border="1">
 *   <tr><th>调用点</th><th>B2 之前的行为</th></tr>
 *   <tr><td>{@code AgentDefServiceImpl#validateToolKeys}</td><td>发布期报「工具(x)未登记」——自定义工具<b>绑不到智能体</b></td></tr>
 *   <tr><td>{@code AgentLoopPrompt#appendTools}</td><td>{@code get()==null} 就 {@code continue}——自定义工具<b>静默不进 system prompt</b>，模型压根不知道有它</td></tr>
 *   <tr><td>{@code AgentChatToolLoop} 运行期只读闸</td><td>报「该工具不存在」</td></tr>
 *   <tr><td>{@code AgentLoopGraphBuilder#resolveSpec} 编译期闸</td><td>报「声明的工具(x)未登记」</td></tr>
 *   <tr><td>{@code AgentConfigController#toolList}</td><td>只列 registry——前端下拉里<b>没有</b>自定义工具可选</td></tr>
 * </table>
 *
 * <p>最坏的一条不是「绑不上」，而是<b>三处口径互相矛盾</b>：{@code WorkflowDslValidator} 的
 * {@code ToolResolver}、{@code WorkflowDslPlannerPrompt#customTools} 与名称目录<b>都</b>认自定义工具，
 * 提示词里甚至明写「只读工具……<b>也可放进 agent_loop.tools</b>」——模型照着编，
 * {@code /validate} 放行，发布通过，运行到 {@code resolveSpec} 才炸「未登记」。
 * 这正是本平台头号缺陷「配置保存成功但不生效」的另一个形状：<b>体检说是好的</b>。
 *
 * <p>所以修法不是在这五处各补一个 {@code resolveForGateway} 回落（那就是第六份实现），
 * 而是把「合流」这件事收到一处，五个调用点一律问它。
 *
 * <h3>🔴 声明值 vs 生效值：口径与既有各道闸逐字一致</h3>
 * <ul>
 *   <li><b>风险</b>取<b>声明</b>值（{@link ToolView#declaredRisk()}）。内置工具读
 *       {@code getDeclaredRisk()}，自定义工具读它自己的 {@code risk_level} 列——
 *       自定义工具<b>没有覆盖表</b>（{@code AgentToolRegistry#sanitize} 会丢掉指向未注册工具的覆盖行），
 *       那一列就是它的声明，不存在「被一行数据洗成只读」的路子。
 *       🔴 红线（D11）的准确表述是「<b>各道只读闸只准读 {@code declaredRisk()}</b>」，
 *       而不是「代码里不许出现 {@code getRisk()}」——本类<b>必然</b>要读后者，
 *       因为它同时供 {@code /agent/config/tool-list} 那屏「生效值快照」使用，
 *       两个值必须分别摆在 {@code declaredRisk} / {@code effectiveRisk} 两格里、绝不合并成一格。
 *       一旦合并，闸读到的就是被一行 {@code agent_tool_override} 推动的值，
 *       整个分型矩阵静默失效且界面上什么都看不出来。理由详见 {@code ToolResolver#declaredRiskOf}。</li>
 *   <li><b>启用状态</b>取<b>生效</b>值（{@link ToolView#enabled()}）。停用是<b>更安全</b>的方向，
 *       被数据推动不构成绕过；且网关读的就是它，两处必须同源才不会出现「闸说能用、网关拒了」。</li>
 *   <li><b>展示类字段</b>（displayName / description / paramsSchema / groupName）取生效值，
 *       与名称目录、planner 提示词同口径：管理员在线改完，下一次打开界面就是新值。</li>
 * </ul>
 *
 * <h3>🔴 刻意不加缓存</h3>
 * 本类是<b>单例</b>，而 {@code agent_tool_def} 与 {@code agent_tool_override} 都是<b>租户级</b>数据。
 * 成员缓存会变成跨请求、跨租户的共享态——A 租户解析过的工具被 B 租户读到，那是比「多查一次库」
 * 严重得多的问题。与 {@code AgentNameCatalogServiceImpl}（S1 明确「不加缓存」）、
 * {@code WorkflowDslValidator.ToolResolver}（缓存生命周期只有「一次校验」）是同一条纪律。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class AgentToolCatalog {

    /** 内置（{@code @AgentTool} 注解、由 {@link AgentToolRegistry} 登记）工具的 source 取值 */
    public static final String SOURCE_BUILTIN = "BUILTIN";

    @Resource
    private AgentToolRegistry toolRegistry;
    @Resource
    private AgentToolDefService toolDefService;

    /** Spring 走这个：两个依赖由上面的 {@code @Resource} 字段注入 */
    public AgentToolCatalog() {
    }

    /** 供单测直接构造（省掉反射塞字段）；生产不走它 */
    public AgentToolCatalog(AgentToolRegistry toolRegistry, AgentToolDefService toolDefService) {
        this.toolRegistry = toolRegistry;
        this.toolDefService = toolDefService;
    }

    /**
     * 一个工具键的合并视图
     *
     * <p>🔴 字段虽多，但每一个都有明确的消费方，不是「顺手都带上」：
     * {@code declaredRisk} 给四道只读闸，{@code enabled} 给发布期的停用告警与前端置灰，
     * {@code description}/{@code paramsSchema} 给 system prompt 渲染，
     * {@code truncateResult} 给 observation 回灌的截断决策，
     * {@code permissions}/{@code effectiveRisk}/{@code overridden} 给 {@code /agent/config/tool-list} 那屏快照。
     *
     * @param name           工具键
     * @param builtin        是否代码内置工具
     * @param source         {@link #SOURCE_BUILTIN} 或 {@code agent_tool_def.source} 的取值
     * @param executable     当前<b>能不能真的执行</b>：内置恒 true；自定义只有 DATA_INTERFACE 为 true
     *                       （FORM/SCRIPT 是预留位，{@code AgentToolDefService#buildFunction} 遇到它们抛
     *                       {@code AGENT_TOOL_SOURCE_UNSUPPORTED}）。
     *                       🔴 这一格是「登记时 source 只放行 DATA_INTERFACE」之外的第二道防线：
     *                       直接改库塞一行 FORM 的话，没有它就会「发布通过、运行期才炸」
     * @param enabled        生效的启用状态（网关读的就是它）
     * @param declaredRisk   <b>声明</b>风险级，恒非 null（认不出的按 HIGH 兜，与 {@code parseOrHigh} 同方向）。
     *                       各道只读闸<b>只能</b>按它判
     * @param effectiveRisk  生效风险级，恒非 null；自定义工具没有覆盖表，故与 {@code declaredRisk} 恒等
     * @param displayName    中文展示名，恒非 null（内置未维护时回落声明值再回落键；自定义回落键）
     * @param description    工具说明，进 prompt；可空
     * @param paramsSchema   参数说明，进 prompt；可空
     * @param groupName      分组名，恒非 null（空串=不分组）
     * @param permissions    调用所需权限串（AND 语义）；空列表=登录即可调用
     * @param truncateResult 结果回灌时是否可被截断。
     *                       🔴 自定义工具恒 true：{@code @AgentTool(truncateResult=false)} 是<b>代码里的白名单</b>
     *                       （「截断会让模型照着残缺清单编」），而数据接口回多大平台无从静态断言，
     *                       无从断言就不能声明成白名单
     * @param overridden     内置工具是否被当前租户在线维护过；自定义恒 false（它没有覆盖表，
     *                       整行<b>就是</b>它的定义，谈不上「被改过」）
     */
    public record ToolView(String name, boolean builtin, String source, boolean executable,
                           boolean enabled, RiskLevel declaredRisk, RiskLevel effectiveRisk,
                           String displayName, String description, String paramsSchema,
                           String groupName, List<String> permissions, boolean truncateResult,
                           boolean overridden) {

        /** 声明口径的「是否必须进 Plan 等用户确认」——各道只读闸的判据 */
        public boolean declaredRequireConfirm() {
            return declaredRisk.isRequireConfirm();
        }

        /** 生效口径的「是否需确认」，仅 {@code /agent/config/tool-list} 那屏展示用 */
        public boolean requireConfirm() {
            return effectiveRisk.isRequireConfirm();
        }

        /** 生效风险 ≠ 声明风险，即风险标注被在线维护改过（自定义工具恒 false） */
        public boolean riskOverridden() {
            return declaredRisk != effectiveRisk;
        }
    }

    /**
     * 解析一个工具键；<b>两类工具都查不到时返回 {@code null}</b>
     *
     * <p>返回 null 而不是抛异常，是为了让五个调用点各自决定「查不到」该说什么话——
     * 发布期说「未登记」、运行期 observation 说「该工具不存在」（且必须过
     * {@code PromptDataFence.neutralize}，那个名字来自模型输出、未经校验）、下拉里则压根不出现。
     * 与 {@code AgentToolRegistry#get} / {@code AgentToolDefService#resolveForGateway} 的既有约定一致。
     *
     * <p>🔴 registry-first：代码工具永远赢。即便有人绕过登记校验直接往
     * {@code agent_tool_def} 塞了一行与代码工具同名的记录，它也抢不到那个名字——
     * 与网关、执行器、名称目录同一优先级，五处不可能各认一个。
     */
    public ToolView resolve(String toolKey) {
        if (StrUtil.isBlank(toolKey)) {
            return null;
        }
        AgentToolRegistry.ToolDefinition builtin = toolRegistry.get(toolKey);
        if (builtin != null) {
            return toView(builtin);
        }
        for (AgentToolDefDO row : customRows()) {
            if (row != null && toolKey.equals(row.getToolKey())) {
                return toView(row);
            }
        }
        return null;
    }

    /**
     * 全部工具的合并视图：内置全量 ∪ 自定义全量（<b>含停用行</b>），按内置的登记顺序在前
     *
     * <p>🔴 含停用行、且用 {@code enabled} 让前端置灰，而不是滤掉（D4-25 / D4-27 的同一条取舍）：
     * 滤掉只是把候选藏了，存量 {@code toolKeys} 里已经绑着的那个键不会因此消失，
     * 而 Element Plus 的 {@code el-select} 对「已选中但不在候选里」的值会回落成
     * {@code currentLabel = value}，标签退化成一串裸机器键。
     *
     * <p>🔴 与内置重名的自定义行<b>丢弃并 log.warn</b>（registry-first 的列表版），
     * 与 {@code AgentNameCatalogServiceImpl#buildTools} 同处置。
     */
    public List<ToolView> list() {
        Map<String, ToolView> byName = new LinkedHashMap<>();
        for (AgentToolRegistry.ToolDefinition def : toolRegistry.list()) {
            byName.put(def.getName(), toView(def));
        }
        for (AgentToolDefDO row : customRows()) {
            if (row == null || StrUtil.isBlank(row.getToolKey())) {
                continue;
            }
            if (byName.containsKey(row.getToolKey())) {
                log.warn("[list][自定义工具({}) 与内置工具重名，按 registry-first 采用内置那一行]", row.getToolKey());
                continue;
            }
            byName.put(row.getToolKey(), toView(row));
        }
        return new ArrayList<>(byName.values());
    }

    /**
     * 当前租户的全部自定义工具行（含停用）
     *
     * <p>🔴 查库失败时返回空列表而不是把异常抛上去：本方法的消费方里有 system prompt 渲染
     * 与运行期只读闸，两处都在<b>对话进行中</b>。数据库抖一下就让整轮对话失败，
     * 比「这一轮少列几个自定义工具」严重得多。而 fail-close 由调用点保证——
     * 查不到就是 {@code null}，闸照样拒，不会因为这里吞了异常就放行一个写工具。
     */
    private List<AgentToolDefDO> customRows() {
        try {
            List<AgentToolDefDO> rows = toolDefService.getToolDefListIncludingDisabled();
            return rows == null ? List.of() : rows;
        } catch (Exception ex) {
            log.warn("[customRows][读取自定义工具目录失败，本轮按「没有自定义工具」处理：{}]", ex.getMessage());
            return List.of();
        }
    }

    private ToolView toView(AgentToolRegistry.ToolDefinition def) {
        // declaredRisk 理论上不会为 null（注解必填）；真为 null 按 HIGH 兜，
        // 与 WorkflowDslValidator.ToolResolver、WorkflowChangeManifestBuilder#resolveTool 同方向
        RiskLevel declared = def.getDeclaredRisk() == null ? RiskLevel.HIGH : def.getDeclaredRisk();
        RiskLevel effective = def.getRisk() == null ? declared : def.getRisk();
        return new ToolView(def.getName(), true, SOURCE_BUILTIN, true,
                def.isEnabled(), declared, effective,
                def.getDisplayName(), def.getDescription(), def.getParamsSchema(),
                StrUtil.nullToEmpty(def.getGroupName()), def.getPermissions(),
                def.isTruncateResult(), def.isOverridden());
    }

    private ToolView toView(AgentToolDefDO row) {
        String key = row.getToolKey();
        // riskLevel 走 parseOrHigh：缺失或非法一律当 HIGH。与 resolveForGateway 同一句，
        // 方向是「宁可多说风险」——会改数据的接口不该因为一列填错就被当成只读放进无人值守的循环
        RiskLevel declared = RiskLevel.parseOrHigh(row.getRiskLevel());
        return new ToolView(key, false, StrUtil.nullToEmpty(row.getSource()),
                ToolSourceEnum.isDataInterface(row.getSource()),
                Boolean.TRUE.equals(row.getEnabled()), declared, declared,
                StrUtil.blankToDefault(row.getDisplayName(), key),
                row.getDescription(), row.getParamsSchema(),
                StrUtil.nullToEmpty(row.getGroupName()),
                splitPermissions(row.getPermissions()), true, false);
    }

    /** 逗号分隔的权限串 → 列表；与 {@code AgentToolDefServiceImpl#splitPermissions} 同语义（trim、丢空、去重） */
    private static List<String> splitPermissions(String raw) {
        if (StrUtil.isBlank(raw)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String item : raw.split(",")) {
            String permission = item.trim();
            if (!permission.isEmpty() && !out.contains(permission)) {
                out.add(permission);
            }
        }
        return List.copyOf(out);
    }

}
