package cn.enlev.ainb.module.agent.controller.admin.workflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 管理后台 - Workflow 定义存量盘点 Response VO（重构 S0：开闸前第 0 步）
 *
 * <h3>这份响应是给「要不要开闸」这个决定用的</h3>
 * S4b 会把 V1~V19 接进发布闸门（{@code assertPublishable}），从此体检不过的流程发不出去。
 * 而库里已经有几十条流程是在这些规则<b>存在之前</b>发布/存下的 —— 开闸等于追溯既往。
 * 本 VO 就是「追溯之前先看清楚会追溯到谁」的那份清单，故它的首要性质是<b>可信</b>，不是好看。
 *
 * <h3>🔴 为什么是端点而不是一次性脚本</h3>
 * 因为它必须与闸门<b>同一套判据</b>：实现直接复用 {@code AgentWorkflowDefService#validateDsl}，
 * 也就是 S4b 的 {@code assertPublishable} 将要调的那个方法。若盘点自己写一套检查，它就是判据的
 * 第二份副本（方案 §2-D7），会与闸门各说各话 —— 那种「盘点说没事、发布被拒」的清单比没有清单更坏，
 * 因为它会让人带着错误的安心去开闸。做成端点还有一层好处：规则每次变动都能重跑，清单不会过期成文物。
 *
 * <h3>🔴 三处「不许把『没查』说成『没问题』」</h3>
 * 这是本 VO 大部分字段存在的理由。一份 0 error 的盘点报告，若读者分不清它是「真干净」还是
 * 「压根没探」，就会直接导致错误开闸：
 * <ul>
 *   <li>{@link #dryRunIncluded} / {@link Item#dryRunState}：干跑层（V4/V5/V12 折平台拒绝）
 *       默认<b>不跑</b> —— 每行最多 15 秒的只读探测，几十行会撞 HTTP 超时。故默认关掉，
 *       但关掉这件事必须写在响应里，不能只是「少了几个 error」。
 *       ⚠️ V16（折污点写）<b>不在</b>这份「没查」名单里：它是纯静态溯源、只读 DSL 本身，
 *       台账 C9 之后拿不到清单也自己追一遍，两档都报且文案逐字相同（实测 2026-10-07）。
 *       把它算进名单，读者就会把默认档报告里的「没有污点警告」读成「这行没有污点流向」——
 *       那恰恰是本节要防的那类误读。</li>
 *   <li>{@link Item#auditFailure}：某行体检<b>自己</b>抛了异常时记在这里，绝不混进 {@link Item#errors}。
 *       「盘点工具坏了」与「这行违规」是两件事（方案 §6.1：探测失败不是 error）；混进去会让人去修
 *       一条其实没毛病的流程，而不修真正坏掉的盘点。</li>
 *   <li>{@link #scanned} 与 {@link #byStatus}：扫了多少行、都是什么状态，逐行可核。
 *       没有这两个数，「0 error」还可能意味着「一行都没扫到」。</li>
 * </ul>
 *
 * <p>⚠️ 本盘点是<b>单租户</b>的：{@code agent_workflow_def} 是 {@code TenantBaseDO}，租户隔离由框架
 * 自动拼 {@code tenant_id=?}，且 agent 模块<b>绝不用</b> {@code executeIgnore} 关掉隔离（架构红线）。
 * 要看别的租户得用那个租户的身份再跑一次。
 *
 * <p>🔴 只读：本端点不写任何表、不改任何状态，权限因此归 {@code agent:workflow:query}
 * （与 {@code validate} / {@code manifest} / {@code name-catalog} 同一档）。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - Workflow 定义存量盘点 Response VO")
@Data
public class AgentWorkflowDefAuditRespVO {

    @Schema(description = "本次扫描的定义行数（当前租户全部未删行）", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "64")
    private Integer scanned;

    @Schema(description = "有 ≥1 条 error 的行数（= 开闸后发不出去的行数）", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "7")
    private Integer failed;

    @Schema(description = "无 error 但有 ≥1 条 warning 的行数（开闸后仍可发布，只是会看到提示）",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "12")
    private Integer warned;

    @Schema(description = "无 error 且无 warning 的行数", requiredMode = Schema.RequiredMode.REQUIRED, example = "44")
    private Integer clean;

    /**
     * 体检<b>自身</b>失败（抛异常）的行数 —— 与 {@link #failed} 互斥，不计入其中。
     *
     * <p>🔴 单独计数的理由见类注释：这些行的 error 数<b>未知</b>，不是 0。把它们算进 clean 会说成
     * 「没问题」，算进 failed 会说成「违规」，两者都是假话，故只能自成一档。
     */
    @Schema(description = "体检自身抛异常、结果未知的行数（不计入 failed，也不算 clean）",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer auditFailed;

    /**
     * 本次是否跑了干跑层（V4/V5/V12 折「平台已拒绝」）。
     *
     * <p>{@code false} 时这三条<b>一条都没查</b>，逐行 {@link Item#dryRunState} 会是
     * {@code NOT_REQUESTED}。
     *
     * <p>⚠️ V16（折「污点流进写工具」）<b>不受本字段约束</b>：它是纯静态溯源、只读 DSL 本身，
     * 台账 C9 之后拿不到清单也自己追一遍，{@code false} / {@code true} 两档都判且文案逐字相同
     * （实测 2026-10-07）。
     */
    @Schema(description = "是否包含干跑层（false 表示 V4/V5/V12 本次一条都没查；V16 不受本开关约束）",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "false")
    private Boolean dryRunIncluded;

    /**
     * 按状态分组的行数，键序 = <b>状态名字典序</b>（DISABLED / DRAFT / PUBLISHED）
     *
     * <p>🔴 刻意排序而不是「保持插入顺序」：这份报告是拿来做开闸决定、并且要<b>两份逐行对照</b>的。
     * 插入顺序取自 {@code selectListForAudit} 的行序（code ASC, version DESC），于是新增或删除任意一行
     * 都会把它之后所有状态的键序整体推移 —— 对照时看到的是「顺序变了」，而不是「哪一档的行数变了」。
     * 排序后键序只由「出现了哪些状态」决定，与行的增删、改名、版本回滚全无关。
     */
    @Schema(description = "按状态分组的行数（键序为状态名字典序，便于两份报告逐行对照）")
    private Map<String, Integer> byStatus;

    @Schema(description = "逐行体检结果（不含 DSL 正文）")
    private List<Item> items;

    /**
     * 单行体检结果
     *
     * <p>🔴 刻意<b>不带 dsl 正文</b>：一份 DSL 可能几十 KB（含内联 prompt），几十行就是兆级负载，
     * 而盘点要的是「哪一行、什么问题」。要看正文按 {@link #id} 另调 {@code GET /agent/workflow-def/get}
     * （与 {@code /page} 裁掉 dsl 同一套理由）。
     */
    @Schema(description = "单行体检结果")
    @Data
    public static class Item {

        @Schema(description = "定义编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
        private Long id;

        @Schema(description = "业务编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "wf_plan_customer")
        private String code;

        @Schema(description = "名称", example = "客户方案生成流程")
        private String name;

        @Schema(description = "版本号", requiredMode = Schema.RequiredMode.REQUIRED, example = "3")
        private Integer version;

        /**
         * 状态（DRAFT/PUBLISHED/DISABLED）。
         *
         * <p>🔴 三种状态<b>都</b>要盘，不只 DRAFT+PUBLISHED：{@code rollbackToVersion} 接受任意状态作蓝本
         * （见 {@code AgentWorkflowDefService#rollbackToVersion}），故 DISABLED 行同样会被用户「改到」。
         * 分档由读报告的人按这一列做，端点不替他决定哪些行不重要。
         */
        @Schema(description = "状态：DRAFT/PUBLISHED/DISABLED", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "PUBLISHED")
        private String status;

        @Schema(description = "来源：MANUAL=管理员手建，AUTO=大模型生成", example = "AUTO")
        private String source;

        /**
         * 触发器短名（{@code none}/{@code chat}/{@code form_event}/{@code schedule}/{@code webhook}）。
         *
         * <p>⚠️ {@code null} 与 {@code "none"} 在存量里同时存在且含义有别：null 是
         * {@code WorkflowTriggerExtractor} 从未投影过（老行），"none" 是 DSL 明确没有 trigger 块。
         * 对 V11/V13/V14 的判定两者都是「无头」，但报告里要如实分开摆，别把 null 改写成 none。
         */
        @Schema(description = "触发器短名；null 表示存量行从未做过触发器投影（不等于 none）", example = "chat")
        private String triggerType;

        @Schema(description = "是否主版本", example = "false")
        private Boolean primaryFlag;

        @Schema(description = "是否通过（无 error）", requiredMode = Schema.RequiredMode.REQUIRED, example = "false")
        private Boolean passed;

        @Schema(description = "阻断性错误原文（每条都说清怎么改）")
        private List<String> errors;

        @Schema(description = "警告原文（不阻断）")
        private List<String> warnings;

        /**
         * 本行所属 code 名下（<b>含全部版本</b>）被<b>拉起</b>过几次（台账 #81）。
         *
         * <p>🔴 这是<b>尝试</b>次数，不是成功次数 —— FAILED / INTERRUPTED 的每一次都算在内。
         * 取数口 {@code AgentWorkflowExecutionMapper#countByDefIds} 的 {@code COUNT(*)} 刻意不带 status 过滤：
         * 一次失败的执行完全可能已经建完了表、只是在后面的节点上炸的，只数成功会让这批行归零、
         * 重新犯 #81 那个病。所以<b>这个数不能当「成功跑通过」的证据读</b>，它只证明「真被拉起过」。
         *
         * <p>它存在的唯一理由是<b>让报告自解释</b>：{@code withDryRun=true} 时，一条「表名已被占用」
         * 若拉起过 &gt; 0 次，会被降成 {@link #warnings} 里的一条而不是 {@link #errors}。
         * 不带上这个数，读报告的人看到的就只是「同一种撞车，这行拦、那行不拦」，
         * 而无从判断分档到底是按证据做的还是随手做的 —— 而这份报告是拿来<b>做开闸决定</b>的。
         *
         * <p>🔴 用原始类型而不是 {@code Long}：0 是<b>量出来的零</b>，不是「不知道」。
         * 取数与发布闸门是同一个实现（{@code sumExecutionsByCode}），故「报告里 executedCount&gt;0 的那一行」
         * 与「闸门会放行的那一行」是同一批；写成可空就会多出一个「null 算不算跑过」的口径分岔。
         *
         * <p>⚠️ 按 code 而不是按本行 id：回滚会造出同 code 的<b>新 id</b>，那个新版本自己一次都没跑过，
         * 执行史全在旧 id 上。少这一步展开，「回滚出的草稿」就会被报成 0 次而照样拦下。
         */
        @Schema(description = "本 code 名下全部版本的执行【尝试】次数合计，含 FAILED/INTERRUPTED，不等于成功次数"
                + "（0=从没被拉起过；>0 时「目标物件已在平台上」那类撞车会降成警告）",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "3")
        private long executedCount;

        /**
         * 干跑层状态：{@code NOT_REQUESTED}（本次没请求）/ {@code PROBED}（清单建成了，结论已折进 errors）
         * / {@code UNAVAILABLE}（请求了但清单生成失败，V4/V5/V12 <b>这一行没查</b>）。
         *
         * <p>🔴 三态而不是布尔：{@code UNAVAILABLE} 与 {@code PROBED 且 0 error} 在界面上若都显示成
         * 「干净」，就等于把「infra/bpm 当时不通」说成「这行没问题」。这与
         * {@code AgentWorkflowDefValidateRespVO#manifest} 的 null 语义是同一条纪律。
         *
         * <p>⚠️ 三态只覆盖 V4/V5/V12。V16 不看清单（纯静态溯源，台账 C9），故 {@code UNAVAILABLE}
         * 的那一行<b>照样</b>可能带 V16 警告；也别把 {@code NOT_REQUESTED} 读成「污点流向没查」。
         */
        @Schema(description = "干跑层状态：NOT_REQUESTED/PROBED/UNAVAILABLE", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "NOT_REQUESTED")
        private String dryRunState;

        /**
         * 体检自身失败的原因；{@code null} 表示体检正常跑完（哪怕跑出 error）。
         *
         * <p>🔴 非 null 时 {@link #errors} 与 {@link #passed} <b>都不可信</b>（是抛异常前收集到的部分结果），
         * 该行只计入顶层 {@code auditFailed}。逐行隔离是刻意的：一行坏 DSL 不该让其余几十行的盘点全部丢失。
         */
        @Schema(description = "体检自身抛异常的原因；null 表示体检正常跑完", example = "NullPointerException")
        private String auditFailure;

    }

}
