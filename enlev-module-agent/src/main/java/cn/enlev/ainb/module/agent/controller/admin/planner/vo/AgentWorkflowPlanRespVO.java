package cn.enlev.ainb.module.agent.controller.admin.planner.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * plan-as-workflow 生成结果（增量7a）
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - 复杂需求拆解为 Workflow DSL 结果")
@Data
public class AgentWorkflowPlanRespVO {

    @Schema(description = "服务端生成的唯一流程 code", example = "wf_plan_lz4k2a_3")
    private String code;

    @Schema(description = "完整的 Workflow DSL JSON 原文（id 已置为 code）")
    private String dsl;

    @Schema(description = "流程名", example = "查询数据接口")
    private String name;

    @Schema(description = "一句话摘要")
    private String summary;

    @Schema(description = "校验器提出的警告（不阻断，但建议核对）")
    private List<String> warnings;

    @Schema(description = "生成-校验-自修正循环实际跑了几轮", example = "1")
    private Integer rounds;

    @Schema(description = "落库后的定义状态：DRAFT / PUBLISHED", example = "PUBLISHED")
    private String status;

}
