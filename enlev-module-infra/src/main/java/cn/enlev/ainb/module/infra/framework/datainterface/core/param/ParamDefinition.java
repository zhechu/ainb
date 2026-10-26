package cn.enlev.ainb.module.infra.framework.datainterface.core.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 数据接口的入参定义
 *
 * 存在接口定义的 params 列（JSON 数组）。运行时据此做必填校验与类型转换。
 *
 * @author 走进新时代
 */
@Schema(description = "数据接口入参定义")
@Data
public class ParamDefinition {

    @Schema(description = "参数名，对应 SQL 里的 #{name}", example = "status")
    private String name;

    @Schema(description = "显示名称", example = "订单状态")
    private String label;

    @Schema(description = "类型：STRING/INT/LONG/DECIMAL/BOOLEAN/DATE/DATETIME", example = "INT")
    private String type;

    @Schema(description = "是否必填", example = "true")
    private Boolean required;

    @Schema(description = "默认值。必填项未传时也会用它兜底", example = "1")
    private String defaultValue;

    @Schema(description = "说明", example = "1-待付款 2-已付款")
    private String remark;

    public boolean isRequired() {
        return Boolean.TRUE.equals(required);
    }

}
