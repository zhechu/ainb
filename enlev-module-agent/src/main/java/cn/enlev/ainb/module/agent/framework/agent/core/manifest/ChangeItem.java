package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 变更清单里的一项：「这个节点将会对平台做什么改动」
 *
 * <h3>字段的归属：谁填哪一份</h3>
 * 本类字段分三组，<b>刻意</b>由三方分别负责，不允许越界：
 * <ul>
 *   <li><b>抽取器</b>（{@link ToolChangeExtractor} 的实现）填 {@link #kind}、{@link #target}、
 *       {@link #detail}、{@link #summary} —— 这些是「这个工具的参数意味着什么」，只有懂该工具的人写得出来。</li>
 *   <li><b>builder</b>（{@link WorkflowChangeManifestBuilder}）填 {@link #nodeId}、{@link #nodeName}、
 *       {@link #tool}、{@link #toolDisplayName}、{@link #riskLevel}、{@link #riskDescription}、
 *       {@link #reversible} —— 这些全部来自 {@code AgentToolRegistry} 的<b>生效值</b>，
 *       一份 DSL 里只该有一个来源。</li>
 *   <li><b>干跑器</b>（{@link ChangeManifestDryRunner}）填 {@link #dryRun}、{@link #conflict} ——
 *       这两份要<b>查平台</b>才答得出，抽取器不查库（见下面「抽取器报什么、干跑器报什么」）。</li>
 * </ul>
 * 🔴 builder 是<b>覆盖</b>而非「仅在为空时补」：若允许抽取器自己写风险等级，
 * 就会出现「同一个工具在名称目录里标中风险、在变更清单里标低风险」这种界面上查不出原因的矛盾 ——
 * 风险等级的唯一权威是工具注册表（含租户在线维护的覆盖值），不是抽取器。
 *
 * <h3>🔴 抽取器报「DSL 里写了什么」，干跑器报「平台将会做什么」</h3>
 * 这条边界是本清单不出错的前提，判据可测：<b>不需要查库就能确定的平台语义</b>，
 * 抽取器可以说（但必须能引到同模块的一处来源，或有两处独立来源互证）；
 * <b>凡是要查库/查接口才知道的</b>，一律留给 {@link ChangeManifestDryRunner}。
 * <p>越界的代价不是「多查一次库」，而是<b>第二份口径</b>：抽取器若自己推断
 * 「这次发布会建 8 条菜单记录」，它就得复刻 {@code FormDesignServiceImpl.buildMenuConfig}
 * 与「首发才建菜单」的判据 —— 而那两处一改，清单就开始报一件不会发生的事。
 * 这正是本功能要消灭的缺陷类。
 *
 * <h3>target / detail 为什么是 Map 而不是每种 kind 一个强类型子类</h3>
 * 与 {@code NodeDTO.config} 同一个理由：变更类型会随工具增加，十种 kind 配十个 DTO
 * 意味着前端也要写十套渲染分支，漏一套的表现是「这类变更显示为空白」。
 * 用有序 Map 把「显示什么键」交给抽取器（它最懂这个工具），前端按「有就渲染、没有就跳过」处理，
 * 新增一种变更不需要改前端骨架。键的<b>顺序</b>即展示顺序，故必须用 {@link LinkedHashMap}。
 *
 * @author 走进新时代
 */
@Data
public class ChangeItem {

    /**
     * 变更类型
     */
    private ChangeKind kind;

    /**
     * 产生这项变更的节点 id
     */
    private String nodeId;

    /**
     * 节点名（DSL 里的 {@code name}）。为空时 builder 回落节点 id ——
     * 审批人看到「n3」等于没看到，但看到空白更糟，故必须有个回落。
     */
    private String nodeName;

    /**
     * 工具键（机器名，如 {@code datamodel_create}）
     */
    private String tool;

    /**
     * 工具中文展示名（生效值，含租户在线维护的覆盖）
     */
    private String toolDisplayName;

    /**
     * 是否可逆
     *
     * <p>🔴 由风险等级推导（{@code risk != IRREVERSIBLE}），<b>不</b>按 kind 各判一套。
     * 按 kind 判会与工具自己声明的风险打架：{@code datamodel_import} 与 {@code datamodel_create}
     * 都归在「表」这一类，前者 LOW（纯登记、无 DDL）、后者 IRREVERSIBLE，
     * 写死在 kind 上就必然错一个。风险等级已经表达了这件事，不再造第二份口径。
     */
    private boolean reversible;

    /**
     * 风险等级标识（{@code READ_ONLY/LOW/MEDIUM/HIGH/IRREVERSIBLE}）
     */
    private String riskLevel;

    /**
     * 风险等级中文名（只读/低风险/中风险/高风险/不可逆）
     *
     * <p>字段名与 {@code AgentNameCatalogRespVO.ToolItem.riskDescription} 逐字对齐 ——
     * 同一模块里 {@code riskDescription} 只能有一种含义，否则前端两处拿到同名字段要记住形状不同。
     */
    private String riskDescription;

    /**
     * 一句话说明这项变更，如「新建数据表 biz_employee（员工信息，7 个字段）」
     *
     * <p>方案原样例里没有这个字段，是落地时加的：四个渲染点里有三个（聊天确认卡、
     * BPM 审批详情、通过弹层）只需要一行列表，不该为了显示一行去理解每种 kind 的
     * {@link #detail} 内部结构 —— 那等于把「哪种变更长什么样」的知识复制四份。
     */
    private String summary;

    /**
     * 作用对象：这项变更改的是<b>哪个</b>东西（表名 / 表单编码 / 流程 key……）
     *
     * <p>空 Map 表示「不适用」（如 {@code GENERIC_TOOL} 说不出作用对象）。
     */
    private Map<String, Object> target = new LinkedHashMap<>();

    /**
     * 细节：具体<b>怎么</b>改（列清单 / 字段清单 / 权限串 / 审批节点树……）
     *
     * <p>空 Map 表示抽取器没有更多可说的。
     */
    private Map<String, Object> detail = new LinkedHashMap<>();

    /**
     * 只读干跑的结论：这么改，平台<b>接不接受</b>
     *
     * <p>🔴 由 {@link ChangeManifestDryRunner} 填，<b>永远不为 null</b>。
     * 没探成也是一态（{@code DryRunResult.Outcome.NOT_PROBED}）而不是留空 ——
     * 留空的话前端分不清「这项不适用干跑」与「忘了干跑」，而后者的表现是
     * 一份看起来全绿、其实什么都没查的清单。
     */
    private DryRunResult dryRun;

    /**
     * 与平台上<b>已有东西</b>的撞车：表名被占、表单编码被占……
     *
     * <p>与 {@link #dryRun} 分开，是因为两者答的是<b>不同的问题</b>：
     * {@code dryRun} 答「这份参数本身合不合法」，{@code conflict} 答「它跟已有的东西重不重名」。
     * 出路也不同 —— 参数不合法要「改参数」，撞车要「换名字」或「直接用那个现成的」，
     * 两句话不能互相代替。
     *
     * <p>🔴 但「一定会让执行失败」的撞车，<b>同时</b>要把 {@code dryRun} 落成
     * {@link DryRunResult.Outcome#REJECTED}，两处都写。理由不是冗余，是<b>只能有一个阻断信号</b>：
     * S4b 的发布闸门读的是 {@code dryRun}，而「一眼看到全貌」那个
     * {@code stats.dryRunRejected} 也是按它数的。撞车若只写 {@code conflict}，
     * 就得到一份「0 项跑不通」却注定跑不通的清单 —— 与
     * {@code stats.dryRunNotProbed} 那段注释担心的事一模一样，只是换了个方向。
     *
     * <p>⚠️ 方案 §3-L2 的样例写的是 {@code dryRun.passed=true} 同时
     * {@code conflict=FORM_CODE_TAKEN}。那是两态模型下的写法：布尔 {@code passed}
     * 表达不了「参数合法但执行会失败」，只好把整件事推给 {@code conflict}。
     * 三态模型下 {@code REJECTED} 本身就是「这么改跑不通」，不必再让两个字段互相打脸。
     *
     * <p>null 表示没撞上（或本轮还没有这一类的探测）。
     */
    private Conflict conflict;

    /**
     * 一处撞车
     *
     * <p>{@code TYPE_*} 常量的写法照 {@code ChangeManifest.UnresolvedItem}：
     * 给机器判分颜色与出路，{@link #message} 给人看。
     */
    @Data
    public static class Conflict {

        /**
         * 要建的表已经在库里，<b>且已登记进数据模型</b>。出路是换个表名
         */
        public static final String TYPE_TABLE_ALREADY_EXISTS = "TABLE_ALREADY_EXISTS";

        /**
         * 要建的表在库里、但没登记 —— 孤儿表。出路是<b>导入它</b>，不是换名字重建：
         * 换名的结果是库里多一张结构相同的表，而原表仍然躺着没人管
         * （判据与文案口径同 {@code DataModelServiceImpl#createDataModel}，那里为此专门分了两个错误码）
         */
        public static final String TYPE_TABLE_EXISTS_UNREGISTERED = "TABLE_EXISTS_UNREGISTERED";

        /**
         * 要用的表单编码已经被另一张表单占了。出路有两条，都不是「改参数」：
         * 那张表单若还是草稿，直接接着编辑发布即可（不必重跑整条流程）；
         * 确要重建就得先删掉它
         */
        public static final String TYPE_FORM_CODE_TAKEN = "FORM_CODE_TAKEN";

        /**
         * 上面 {@code TYPE_*} 之一
         */
        private String type;

        /**
         * 中文说明，要说清「怎么改」而不只是「撞了什么」
         */
        private String message;

    }

}
