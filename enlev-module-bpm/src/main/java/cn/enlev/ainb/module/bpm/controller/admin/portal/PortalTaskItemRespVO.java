package cn.enlev.ainb.module.bpm.controller.admin.portal;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@Schema(description = "管理后台 - 门户流程列表项")
public class PortalTaskItemRespVO {

    private String id;
    private String taskId;
    private String processInstanceId;
    private String name;
    private String processName;
    private LocalDateTime createTime;
}
