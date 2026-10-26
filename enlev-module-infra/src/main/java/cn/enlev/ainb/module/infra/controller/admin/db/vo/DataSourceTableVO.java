package cn.enlev.ainb.module.infra.controller.admin.db.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 数据源表结构 Response VO")
@Data
public class DataSourceTableVO {

    @Schema(description = "表名", requiredMode = Schema.RequiredMode.REQUIRED, example = "biz_order")
    private String tableName;

    @Schema(description = "表注释", example = "订单表")
    private String tableComment;

    @Schema(description = "列信息")
    private List<ColumnInfo> columns;

    @Schema(description = "列信息")
    @Data
    public static class ColumnInfo {

        @Schema(description = "列名", example = "id")
        private String columnName;

        @Schema(description = "列注释", example = "主键")
        private String columnComment;

        @Schema(description = "数据类型", example = "bigint")
        private String dataType;

    }

}
