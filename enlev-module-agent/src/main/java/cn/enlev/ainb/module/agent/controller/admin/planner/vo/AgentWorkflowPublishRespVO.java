package cn.enlev.ainb.module.agent.controller.admin.planner.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 发布 Workflow 草稿结果（增量7b）
 *
 * <p>聊天确认卡点「确认并运行」的第一步：把 DRAFT 翻成 PUBLISHED（{@code publishDef}，无状态幂等）。
 * 发布成功后前端再拿 {@code code} 去 {@code POST /agent/workflow/run-stream} 执行。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - 发布 Workflow 草稿结果")
@Data
public class AgentWorkflowPublishRespVO {

    @Schema(description = "业务编码", example = "wf_plan_lz4k2a_3")
    private String code;

    @Schema(description = "被发布（或本就已发布）的定义编号", example = "555")
    private Long defId;

    @Schema(description = "发布后的状态：PUBLISHED", example = "PUBLISHED")
    private String status;

}
