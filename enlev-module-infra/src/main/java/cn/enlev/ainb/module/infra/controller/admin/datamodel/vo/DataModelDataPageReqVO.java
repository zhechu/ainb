package cn.enlev.ainb.module.infra.controller.admin.datamodel.vo;

import cn.enlev.ainb.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 动态数据分页 Request VO")
@Data
public class DataModelDataPageReqVO extends PageParam {

    @Schema(description = "模型编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "模型编号不能为空")
    private Long modelId;

    @Schema(description = "查询条件列表")
    private List<Condition> conditions;

    @Schema(description = "排序字段。必须是表的真实字段，否则被拒绝", example = "create_time")
    private String sortField;

    @Schema(description = "是否升序", example = "false")
    private Boolean sortAsc;

    @Schema(description = "查询条件")
    @Data
    public static class Condition {

        @Schema(description = "字段名", requiredMode = Schema.RequiredMode.REQUIRED, example = "name")
        private String columnName;

        @Schema(description = "操作符：EQ/NE/LIKE/GT/GE/LT/LE/BETWEEN/IN/IS_NULL/IS_NOT_NULL",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "LIKE")
        private String operator;

        @Schema(description = "值。BETWEEN 传两元素数组，IN 传数组，IS_NULL 不传", example = "张三")
        private Object value;

    }

}
