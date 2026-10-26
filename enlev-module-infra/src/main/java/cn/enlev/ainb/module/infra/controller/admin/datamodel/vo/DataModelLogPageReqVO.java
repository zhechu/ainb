package cn.enlev.ainb.module.infra.controller.admin.datamodel.vo;

import cn.enlev.ainb.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

import static cn.enlev.ainb.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

@Schema(description = "管理后台 - 数据模型 DDL 日志分页 Request VO")
@Data
public class DataModelLogPageReqVO extends PageParam {

    @Schema(description = "表名称，模糊匹配", example = "biz_order")
    private String tableName;

    @Schema(description = "操作类型", example = "CREATE")
    private String operateType;

    @Schema(description = "是否执行成功", example = "true")
    private Boolean success;

    @Schema(description = "创建时间", example = "[2026-07-01 00:00:00,2026-07-01 23:59:59]")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] createTime;

}
