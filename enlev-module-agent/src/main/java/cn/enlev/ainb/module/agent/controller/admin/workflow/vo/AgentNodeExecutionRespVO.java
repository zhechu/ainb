package cn.enlev.ainb.module.agent.controller.admin.workflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Workflow 节点执行 trace Response VO
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - Workflow 节点执行 trace Response VO")
@Data
public class AgentNodeExecutionRespVO {

    @Schema(description = "trace 编号", example = "1")
    private Long id;

    @Schema(description = "执行实例编号", example = "1024")
    private Long executionId;

    @Schema(description = "节点 id", example = "classify")
    private String nodeId;

    @Schema(description = "节点显示名（执行当时的快照，流程改名不回填；老行为 null）", example = "意图分类")
    private String nodeName;

    @Schema(description = "所属 sub_workflow/iteration/agent_loop 节点的裸 id；null=顶层节点。"
            + "🔴 不是自引用外键：那三种节点自身不落 trace 行", example = "sub_order")
    private String parentNodeId;

    @Schema(description = "节点类型", example = "condition.llm")
    private String nodeType;

    @Schema(description = "tool 节点调用的工具标识；null=不是 tool 节点 / agent_loop 的 act 行（每步现选工具，"
            + "按构造就没有单一 toolId）/ 本列加上之前的老行", example = "form_update")
    private String toolId;

    @Schema(description = "状态", example = "SUCCESS")
    private String status;

    @Schema(description = "第几次尝试", example = "1")
    private Integer attempt;

    @Schema(description = "实际使用的模型编号", example = "66")
    private Long modelId;

    @Schema(description = "总 token", example = "128")
    private Integer totalTokens;

    @Schema(description = "token 来源", example = "PROVIDER")
    private String tokenSource;

    @Schema(description = "耗时毫秒", example = "900")
    private Integer durationMs;

    @Schema(description = "失败原因")
    private String errorMsg;

    @Schema(description = "开始时间")
    private LocalDateTime beginTime;

    @Schema(description = "结束时间")
    private LocalDateTime endTime;

}
