package cn.enlev.ainb.module.infra.controller.admin.datamodel.vo;

import cn.enlev.ainb.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

import static cn.enlev.ainb.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

@Schema(description = "管理后台 - 数据模型分页 Request VO")
@Data
public class DataModelPageReqVO extends PageParam {

    @Schema(description = "数据源配置的编号", example = "1")
    private Long dataSourceConfigId;

    @Schema(description = "表名称，模糊匹配", example = "biz_order")
    private String tableName;

    @Schema(description = "表描述，模糊匹配", example = "订单")
    private String tableComment;

    @Schema(description = "模型名称，模糊匹配", example = "订单模型")
    private String modelName;

    @Schema(description = "创建时间", example = "[2026-07-01 00:00:00,2026-07-01 23:59:59]")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] createTime;

}
