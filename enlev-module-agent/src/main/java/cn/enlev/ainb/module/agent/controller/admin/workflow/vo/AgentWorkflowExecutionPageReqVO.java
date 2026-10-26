package cn.enlev.ainb.module.agent.controller.admin.workflow.vo;

import cn.enlev.ainb.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 管理后台 - Workflow 执行实例分页 Request VO（增量8 / P4 管理页）
 *
 * <p>🔴 这是<b>管理端</b>分页：按定义/状态过滤，租户内全量（不按 userId 收窄）。
 * 与用户端「只看自己」的 {@code selectPageByUserId} 语义不同——见 {@code AgentWorkflowExecutionMapper.selectPage} 注释。
 * 租户隔离由 {@code TenantBaseDO} 自动保证，跨租户绝不可见。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - Workflow 执行实例分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentWorkflowExecutionPageReqVO extends PageParam {

    @Schema(description = "定义编号", example = "1")
    private Long workflowDefId;

    @Schema(description = "状态，见 WorkflowExecutionStatusEnum", example = "SUCCEEDED")
    private String status;

}
