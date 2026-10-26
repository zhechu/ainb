package cn.enlev.ainb.module.agent.controller.admin.workflow.vo;

import cn.enlev.ainb.module.agent.enums.WorkflowExecutionStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Workflow 执行实例 Response VO
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - Workflow 执行实例 Response VO")
@Data
public class AgentWorkflowExecutionRespVO {

    @Schema(description = "执行实例编号", example = "1024")
    private Long id;

    @Schema(description = "定义编号", example = "1")
    private Long workflowDefId;

    @Schema(description = "绑定的定义版本", example = "1")
    private Integer workflowVersion;

    @Schema(description = "状态", example = "SUCCEEDED")
    private String status;

    @Schema(description = "当前/最后停留节点", example = "draft_contract")
    private String currentNode;

    @Schema(description = "挂起时正在等待的闸口节点；null=非挂起态，或取不到 checkpoint 快照",
            example = "approve_gate")
    private String suspendNode;

    @Schema(description = "resume 时会被静默丢弃、且作者补一条 variables[] 声明就能救的 state 键集"
            + "（JSON 数组字符串）；null=非挂起态，或滤网后没有作者能自己修的键",
            example = "[\"my_tool_out\"]")
    private String droppedKeys;

    @Schema(description = "失败所在节点", example = "create_record")
    private String failedAtNode;

    @Schema(description = "触发用户编号", example = "1")
    private Long userId;

    @Schema(description = "失败原因")
    private String errorMsg;

    @Schema(description = "开始时间")
    private LocalDateTime beginTime;

    @Schema(description = "结束时间")
    private LocalDateTime endTime;

    @Schema(description = "耗时毫秒", example = "3200")
    private Integer durationMs;

    /**
     * 这一行能不能被「中止」（迭代七 B3-2；<b>服务端派生，不落库</b>，与
     * {@code AgentWorkflowDefRespVO#getEditable()} 同一套路）
     *
     * <p>🔴 为什么由服务端算、而不是让前端判 {@code status}：准入闸的字面量集合只有
     * {@link WorkflowExecutionStatusEnum#isCancellable(String)} <b>一份</b>，
     * 它同时喂 {@code cancel} 的预检与 {@code casToCancelled} 的 SQL {@code IN}。
     * 前端再抄一份 {@code ['CREATED','RUNNING','INTERRUPTED']}，等第 05 轮（B3-4）或 A-Ⅰ 复议
     * 改动那份集合时，就会出现「按钮亮着、点了被拒」—— 正是 G14 要收敛的那份重复。
     *
     * <p>⚠️ 只有 getter、没有字段也没有 setter：{@code BeanUtils.toBean} 按属性拷贝，
     * 目标上的只读属性拷不进去也不报错，Jackson 序列化时照常输出 {@code cancellable} 这一格。
     *
     * <p>🔴 前端一律写 {@code row.cancellable}（真值判断），<b>不要</b>写 {@code row.cancellable !== false}：
     * 本格是 boolean 而非包装类，恒有值；但把它声明成「拿不到就当不可取消」的方向与后端对
     * {@code status=null} 的处理一致（{@code isCancellable} 对 null 返回 false），失败朝安全那一侧倒。
     */
    @Schema(description = "是否可中止（status ∈ CREATED/RUNNING/INTERRUPTED；服务端派生，不落库）",
            example = "true")
    public boolean getCancellable() {
        return WorkflowExecutionStatusEnum.isCancellable(status);
    }

}
