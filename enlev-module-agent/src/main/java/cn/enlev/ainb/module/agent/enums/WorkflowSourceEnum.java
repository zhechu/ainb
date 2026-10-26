package cn.enlev.ainb.module.agent.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Workflow 定义的来源（{@code agent_workflow_def.source}）：这条流程是「人建的」还是「系统自动建的」
 *
 * <h3>🔴 为什么需要这一列</h3>
 * 除了管理页的「新增流程」，还有两条<b>由大模型驱动、会在后台悄悄落库</b>的路径也会写 {@code agent_workflow_def}：
 * <ul>
 *   <li>{@code POST /agent/planner/plan-workflow}：plan-as-workflow 生成器，把 LLM 产出的 DSL 落成定义（默认还顺手发布）；</li>
 *   <li>{@code AgentAssistantServiceImpl.emitWorkflowDraft}：智能助手对话里识别出「搭流程」意图后生成 DSL 草稿。</li>
 * </ul>
 * 两者产出的 code 都是 {@code wf_plan_<时间戳base36>_<序号base36>}（见 {@code WorkflowPlannerServiceImpl.generateCode}），
 * 于是列表里混进一堆「我没建过、名字也看不懂」的行，管理员既不敢删也分不清哪条是自己的。
 * 加了这一列，列表可以直接标出来源，并支持「只看手动添加的」把噪音一次滤掉。
 *
 * <h3>🔴 为什么刻意只有两个值、且由服务端决定</h3>
 * 只分「手动 / 自动」：这正是管理员维护时唯一需要的判断（谁建的、能不能删）。再细分成 PLANNER/CHAT
 * 对存量数据<b>做不到</b>——两条自动路径共用 {@code wf_plan_} 前缀，历史行无从区分，与其回填出一个
 * 可能是错的细分类，不如给一个百分百准确的粗分类。
 *
 * <p>该值<b>不接受前端传入</b>（{@code AgentWorkflowDefSaveReqVO} 里没有 source 字段），
 * 由 {@code AgentWorkflowDefServiceImpl} 按「走的哪个 createDef 入口」写死：
 * 走 SaveReqVO 的管理页入口 = {@link #MANUAL}，走 4 参重载的程序化入口 = {@link #AUTO}。
 * 若允许客户端指定，任何调用方都能把自动生成的行伪装成手动，这一列就失去意义了。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum WorkflowSourceEnum {

    /** 手动添加：管理员在「流程编排」页点「新增」建出来的（含版本回滚复制出的新草稿） */
    MANUAL("MANUAL", "手动添加"),

    /** 自动生成：规划器或智能助手对话由 LLM 生成后落库的，管理员没有主动建过 */
    AUTO("AUTO", "自动生成"),
    ;

    private final String source;
    private final String description;

    /**
     * 是不是系统自动生成的行
     *
     * <p>列定义为 {@code NOT NULL DEFAULT 'MANUAL'}，故 {@code ALTER TABLE} 会把存量行直接填成 MANUAL，
     * 回填脚本再把 {@code wf_plan_} 前缀的行改成 AUTO，库里不会留 NULL。
     * 但内存里新 new 出来、还没落库的 DO 仍可能是 null，故 {@link #isManual} 把 null 一并算作手动，
     * 避免「未设置来源」被误判成自动生成。
     */
    public static boolean isAuto(String source) {
        return AUTO.source.equals(source);
    }

    /**
     * 是不是人工添加的行；null（尚未落库/未设置）按手动处理
     */
    public static boolean isManual(String source) {
        return !isAuto(source);
    }

}
