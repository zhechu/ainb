package cn.enlev.ainb.module.infra.controller.admin.datamodel.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 数据模型 DDL 日志 Response VO")
@Data
public class DataModelLogRespVO {

    @Schema(description = "编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "表名", requiredMode = Schema.RequiredMode.REQUIRED, example = "biz_order")
    private String tableName;

    @Schema(description = "操作类型", requiredMode = Schema.RequiredMode.REQUIRED, example = "CREATE")
    private String operateType;

    @Schema(description = "实际执行的 DDL", requiredMode = Schema.RequiredMode.REQUIRED)
    private String executeSql;

    @Schema(description = "是否执行成功", requiredMode = Schema.RequiredMode.REQUIRED, example = "true")
    private Boolean success;

    @Schema(description = "失败原因", example = "Unknown column")
    private String errorMsg;

    @Schema(description = "执行耗时，单位：毫秒", requiredMode = Schema.RequiredMode.REQUIRED, example = "35")
    private Integer duration;

    @Schema(description = "操作人", example = "admin")
    private String creator;

    @Schema(description = "操作时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createTime;

}
