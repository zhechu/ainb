package cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.runtime;

import cn.enlev.ainb.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 表单运行时分页查询 Request VO")
@Data
public class FormDataPageReqVO extends PageParam {

    @Schema(description = "查询条件列表")
    private List<Condition> conditions;

    @Schema(description = "排序字段（form-create 的 field，需在配置中声明过）", example = "customerName")
    private String sortField;

    @Schema(description = "排序方向，只允许 ASC / DESC", example = "DESC")
    private String sortOrder;

    /**
     * 查询条件
     *
     * field 必须能在 tableBinding.fieldMapping 里解析出物理列，否则该条件被服务端忽略。
     * 这层过滤在服务端做，不依赖前端自律。
     */
    @Schema(description = "查询条件")
    @Data
    public static class Condition {

        @Schema(description = "字段名", requiredMode = Schema.RequiredMode.REQUIRED, example = "customerName")
        private String field;

        @Schema(description = "操作符：EQ/NE/GT/GE/LT/LE/LIKE/IN/BETWEEN/IS_NULL 等", example = "LIKE")
        private String operator;

        @Schema(description = "值")
        private Object value;

        @Schema(description = "第二个值，BETWEEN 时使用")
        private Object value2;
    }

}
