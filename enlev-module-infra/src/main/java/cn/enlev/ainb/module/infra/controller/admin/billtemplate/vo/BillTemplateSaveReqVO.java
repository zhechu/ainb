package cn.enlev.ainb.module.infra.controller.admin.billtemplate.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

@Schema(description = "管理后台 - 取号管理 - 创建/修改 Request VO")
@Data
public class BillTemplateSaveReqVO {

    @Schema(description = "编号。修改时传入", example = "1")
    private Long id;

    @Schema(description = "模板名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "采购订单号")
    @NotEmpty(message = "模板名称不能为空")
    @Size(max = 100, message = "模板名称不能超过 100 个字符")
    private String name;

    /**
     * 编码限制为字母数字下划线：它会被拼进 Redis key（{@code infra:bill_template:no:{tenantId}:{code}:...}），
     * 允许冒号会破坏 key 的分段结构。
     */
    @Schema(description = "模板编码，表单与其它模块引用它", requiredMode = Schema.RequiredMode.REQUIRED, example = "purchase_order")
    @NotEmpty(message = "模板编码不能为空")
    @Size(max = 100, message = "模板编码不能超过 100 个字符")
    @Pattern(regexp = "^[A-Za-z_][A-Za-z0-9_]*$",
            message = "模板编码只允许字母、数字、下划线，且以字母或下划线开头")
    private String code;

    @Schema(description = "分类", example = "business")
    private String category;

    @Schema(description = "生成方式：1-时间流水 2-随机数 3-UUID", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "生成方式不能为空")
    private Integer genType;

    @Schema(description = "前缀", example = "CG")
    @Size(max = 50, message = "前缀不能超过 50 个字符")
    private String prefix;

    @Schema(description = "后缀", example = "")
    @Size(max = 50, message = "后缀不能超过 50 个字符")
    private String suffix;

    @Schema(description = "日期格式，NONE 表示不含日期段。时间流水方式使用", example = "yyyyMMdd")
    private String dateFormat;

    @Schema(description = "流水位数 1-9。时间流水方式使用", example = "4")
    @Min(value = 1, message = "流水位数不能小于 1")
    @Max(value = 9, message = "流水位数不能大于 9")
    private Integer digit;

    @Schema(description = "流水起始值。时间流水方式使用", example = "1")
    @Min(value = 0, message = "流水起始值不能小于 0")
    private Integer startNumber;

    @Schema(description = "流水步长。时间流水方式使用", example = "1")
    @Min(value = 1, message = "流水步长不能小于 1")
    private Integer step;

    @Schema(description = "重置周期：AUTO-跟随日期格式 / NONE / DAY / MONTH / YEAR", example = "AUTO")
    private String resetCycle;

    @Schema(description = "流水是否按前缀分组重置", example = "false")
    private Boolean groupByPrefix;

    @Schema(description = "随机位数：16 / 32。随机数方式使用", example = "16")
    private Integer randomDigit;

    @Schema(description = "随机类型：1-纯数字 2-字母+数字。随机数方式使用", example = "1")
    private Integer randomType;

    @Schema(description = "是否记录取号日志，默认关闭", example = "false")
    private Boolean logEnabled;

    @Schema(description = "状态：0-开启 1-停用", example = "0")
    private Integer status;

    @Schema(description = "排序", example = "0")
    private Integer sort;

    @Schema(description = "备注", example = "采购模块使用")
    @Size(max = 500, message = "备注不能超过 500 个字符")
    private String remark;

}
