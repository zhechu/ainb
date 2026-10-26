package cn.enlev.ainb.module.bpm.controller.admin.portal;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@Schema(description = "管理后台 - 门户流程统计")
public class PortalTaskSummaryRespVO {

    private Long pendingSign;
    private Long pendingTodo;
    private Long running;
    private Long completed;
    private Long copied;
}
