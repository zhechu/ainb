package cn.enlev.ainb.module.agent.controller.admin.workflow.vo;

import cn.enlev.ainb.module.agent.enums.WorkflowDefStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理后台 - Workflow 定义 Response VO（增量8 / P4 管理页）
 *
 * <p>🔴 {@link #dsl} 是整份 DSL 原文，可能几十 KB（DO 用 mediumtext 正因如此）。
 * 分页列表端点会把它置 null 再返回（列表不需要正文），只有 {@code GET /get?id} 才带完整 dsl 供只读画布渲染。
 *
 * <p>末尾四个 {@code getXxx()} 是<b>派生属性</b>（无字段、无 setter）：由 {@link #status} 现算，
 * 每个出口（{@code /get}、{@code /page}、{@code /version-list}）都自动带上，不需要也不允许手工填。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - Workflow 定义 Response VO")
@Data
public class AgentWorkflowDefRespVO {

    /**
     * 不可就地编辑时，给前端只读横幅渲染的那句话
     *
     * <p>🔴 文案与判据一起从服务端下发，前端只负责显示：状态机将来加一档（如 ARCHIVED），
     * 前端不用改一个字，也就不会出现「新状态落进旧文案」的错话。
     */
    private static final String EDIT_DENIED_REASON =
            "非草稿版本是执行实例绑定的版本快照，不能就地修改；如需调整请复制或回滚生成新草稿";

    /**
     * 不可删除时给前端 tooltip / 提示渲染的那句话
     *
     * <p>🔴 与 {@code WORKFLOW_DEF_NOT_DELETABLE} 的错误文案同口径，只是把「报错」换成「解释」，
     * 并且<b>不带</b>那句占位符 —— 见下面 {@link #getDeleteDeniedReason()} 的说明。
     */
    private static final String DELETE_DENIED_REASON =
            "仅草稿与已停用的版本可删除；其余状态请先停用，再删除";

    @Schema(description = "编号", example = "1")
    private Long id;

    @Schema(description = "业务编码，同一 code 多版本共存", example = "wf_plan_customer")
    private String code;

    @Schema(description = "名称", example = "客户跟进流程")
    private String name;

    @Schema(description = "版本号，单调递增", example = "1")
    private Integer version;

    @Schema(description = "状态：DRAFT / PUBLISHED / DISABLED", example = "PUBLISHED")
    private String status;

    @Schema(description = "是否主版本：同 code 多已发布版本中被指定为当前生效的那一版（增量14b）", example = "false")
    private Boolean primaryFlag;

    @Schema(description = "描述", example = "表单提交后自动跟进")
    private String description;

    @Schema(description = "来源：MANUAL（手动添加）/ AUTO（规划器或助手对话自动生成）", example = "MANUAL")
    private String source;

    @Schema(description = "Workflow DSL v1 JSON 原文（列表端点为 null，get?id 才返回）")
    private String dsl;

    @Schema(description = "触发器类型：form_event / schedule / chat / webhook；NULL=无触发器", example = "form_event")
    private String triggerType;

    @Schema(description = "触发器匹配键（form_event 为 {formCode}:{eventType}，schedule 为 cron 原文）",
            example = "test_demo_customer:create")
    private String triggerKey;

    @Schema(description = "schedule 触发器下次应触发时间；非 schedule 恒为 null")
    private LocalDateTime triggerNextFireAt;

    @Schema(description = "定义发布者用户编号 = 触发执行时的运行身份", example = "1")
    private Long ownerUserId;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    // ==================== 派生属性（由 status 现算，不落库、不手工填） ====================

    /**
     * 这一行能不能就地编辑（= 能不能进设计器改 DSL、能不能改名称/描述）
     *
     * <h3>🔴 判据不在这里，在 {@link WorkflowDefStatusEnum#isDraft}</h3>
     * 后端强制点（{@code updateDef} 抛 {@code WORKFLOW_DEF_NOT_DRAFT}）与本属性
     * 共用那一个方法，所以「前端显示可编辑」与「后端接受保存」不可能各说各话。方案 §G14 要修的就是
     * 这条规则原先在前端 {@code editor/index.vue} 里又抄了一遍。
     *
     * <h3>⚠️ 删除<b>不</b>看这一格，看 {@link #getDeletable()}</h3>
     * 可删的状态比可编辑多一档（草稿 + 已停用），是两个不同的判据、两个不同的 getter。
     * 拿 {@code editable} 当「可删」用会让停用版永远删不掉。
     *
     * <h3>为什么是派生 getter 而不是字段</h3>
     * 字段要在每个出口手工填（{@code /get}、{@code /page}、{@code /version-list} 共 3 处），
     * 漏一处就是「列表页按钮态对了、详情页错了」这种查半天的鬼故事；而 getter 无从漏填。
     * 方案亦明确<b>不</b>给它落库列：它是 status 的纯函数，落库必然漂移。
     *
     * @return 恒非 null（status 为 null 时按「不可编辑」处理，与后端强制点同结论）
     */
    @Schema(description = "是否可就地编辑（status == DRAFT；服务端派生，不落库）", example = "false")
    public boolean getEditable() {
        return WorkflowDefStatusEnum.isDraft(status);
    }

    /**
     * 不可编辑时给前端只读横幅用的说明；可编辑时为 {@code null}（前端不必再判 status）
     *
     * <p>⚠️ 文案与重构方案 S5-c 原稿不同：原稿写「<b>已发布的</b>流程是执行实例绑定的版本快照…」，
     * 但 DISABLED（曾发布、现停用）同样不可就地编辑，那句话会让状态徽标是「已停用」的行配上一句
     * 「已发布的流程」，自相矛盾。故这里不点名具体状态，结论与 {@code WORKFLOW_DEF_NOT_DRAFT}
     * 的错误文案同口径（非草稿 ⟹ 请出新版本），措辞从「报错」换成「解释」。
     */
    @Schema(description = "不可就地编辑的原因（editable=true 时为 null）；前端只读横幅直接渲染这句")
    public String getEditDeniedReason() {
        return getEditable() ? null : EDIT_DENIED_REASON;
    }

    /**
     * 这一行在<b>状态上</b>能不能删（草稿与已停用放行，已发布拒删）
     *
     * <h3>🔴 判据不在这里，在 {@link WorkflowDefStatusEnum#isDeletable}</h3>
     * 与后端强制点（{@code AgentWorkflowDefServiceImpl#assertStatusDeletable}，
     * 抛 {@code WORKFLOW_DEF_NOT_DELETABLE}）共用那一个方法，故「前端复选框亮着」与
     * 「后端接受这一行」在状态这一维上不可能分岔。前端<b>不许</b>自己写 {@code status === 'DRAFT'}。
     *
     * <h3>🔴 这<b>只是状态闸门</b>，不是「可删」的承诺</h3>
     * 删除还要过两道要扫<b>别的行</b>的闸门：被活定义的 sub_workflow pin 住
     * （{@code WORKFLOW_DEF_DELETE_PINNED}）、名下还有未终态执行
     * （{@code WORKFLOW_DEF_DELETE_HAS_UNFINISHED}）。这两道在列表接口上算不出来 ——
     * 每行都要一次「全表活定义 + 逐行 DSL 解析」，一页 20 行就是 20 倍代价。
     * 故本属性的正确读法是「这行<b>值得</b>给出删除入口」，而<b>权威的拒绝理由是删除接口返回的那句错误</b>
     * （它点名了引用方 / 未终态执行 id）。前端把那句原样显示即可，不要再编一份。
     *
     * @return 恒非 null（status 为 null 或未知值时按「不可删」处理 = fail-close，与后端强制点同结论）
     */
    @Schema(description = "状态上是否可删除（status ∈ {DRAFT, DISABLED}；服务端派生，不落库）", example = "true")
    public boolean getDeletable() {
        return WorkflowDefStatusEnum.isDeletable(status);
    }

    /**
     * 不可删时给前端 tooltip 用的说明；可删时为 {@code null}（前端不必再判 status）
     *
     * <p>🔴 与 {@link #getEditDeniedReason()} 同一条纪律：<b>不点名当前状态</b>。
     * 这里能走到「不可删」的不止 PUBLISHED 一档 —— {@code isDeletable} 对 {@code null}
     * 与未知状态值同样返回 false（fail-close），若文案写死「已发布的版本正在被选用」，
     * 那些行就会配上一句解释不了自己处境的话。故只陈述<b>要求</b>与<b>出路</b>。
     *
     * <p>⚠️ 这句只解释<b>状态</b>那一档。被 pin / 有在途执行时列表页给的仍是「可删」，
     * 真正的理由由删除接口的错误文案带回 —— 见 {@link #getDeletable()} 的说明。
     */
    @Schema(description = "不可删除的原因（deletable=true 时为 null）；前端删除按钮的 tooltip 直接渲染这句")
    public String getDeleteDeniedReason() {
        return getDeletable() ? null : DELETE_DENIED_REASON;
    }

}
