package cn.enlev.ainb.module.infra.controller.admin.datainterface.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 接口管理 - 版本 Response VO")
@Data
public class DataInterfaceVersionRespVO {

    @Schema(description = "编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "接口编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long interfaceId;

    @Schema(description = "版本号", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    private Integer version;

    @Schema(description = "配置快照 JSON")
    private String snapshot;

    @Schema(description = "变更说明", example = "增加状态筛选")
    private String changeSummary;

    @Schema(description = "操作人", example = "admin")
    private String creator;

    @Schema(description = "操作时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createTime;

}
