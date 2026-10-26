package cn.enlev.ainb.module.agent.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 失败恢复策略
 *
 * <p>方案 §7 / DSL §6：默认 FORWARD（失败即终态，不自动反做）。
 * 只有节点显式声明 {@code recovery: compensate} 时，失败才触发 Saga 逆序语义反做。
 *
 * <p>🔴 本类原先写的是「默认 FORWARD（从断点续跑，不重来）」——**「续跑」这半句是假的**，已改。
 * 实测：FORWARD 除了 {@link #parse} 的兜底返回值之外，全仓唯一的读者是
 * {@code AgentWorkflowCompensator#shouldCompensate}，而它只判 {@code COMPENSATE}；
 * {@code WorkflowExecutionStatusEnum#isResumable} 也只认 INTERRUPTED。
 * ⟹ **没有任何代码会在失败后按 FORWARD 把执行从断点拉起来。**
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum WorkflowRecoveryEnum {

    /**
     * 不反做：失败即终态，副作用可能已部分生效且无自动撤销 → 告警交人工
     *
     * 这是默认值。
     *
     * <p>🔴 本条原先叫「续跑：从 checkpoint 断点继续，已完成的节点不重做」，并说
     * 「适用于重跑代价高、或前置节点是幂等只读的流程」——**那段描述的能力不存在**（理由见类注释）。
     * 唯一真的能从断点续跑的是 INTERRUPTED（hitl 确认 / wait 到期 / 外部事件），
     * 那是**正常挂起**、走 {@code resume}，与本枚举无关：本枚举管的是**失败之后**怎么办。
     */
    FORWARD("FORWARD", "断点续跑"),

    /**
     * 反做：按 compensate_ref 逆序撤销已生效的写操作
     *
     * 用 before_image + idempotency_key 保证反做本身幂等（可能重放）。
     */
    COMPENSATE("COMPENSATE", "Saga 反做"),
    ;

    private final String recovery;
    private final String description;

    /**
     * 解析，认不出来时回退 FORWARD（安全默认：不擅自触发反做）
     */
    public static WorkflowRecoveryEnum parse(String value) {
        if (value == null) {
            return FORWARD;
        }
        for (WorkflowRecoveryEnum item : values()) {
            if (item.recovery.equalsIgnoreCase(value)) {
                return item;
            }
        }
        return FORWARD;
    }

}
