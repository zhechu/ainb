package cn.enlev.ainb.module.infra.controller.admin.billtemplate.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 单据模板试算 Request VO
 *
 * 不复用 {@link BillTemplateSaveReqVO}：那个要求名称与编码非空，而试算发生在用户
 * 边配边看的过程中，此时编码往往还没填。试算只关心号码长什么样，与身份无关。
 */
@Schema(description = "管理后台 - 取号管理 - 试算 Request VO")
@Data
public class BillTemplatePreviewReqVO {

    @Schema(description = "生成方式：1-时间流水 2-随机数 3-UUID", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "生成方式不能为空")
    private Integer genType;

    @Schema(description = "前缀", example = "CG")
    private String prefix;

    @Schema(description = "后缀")
    private String suffix;

    @Schema(description = "日期格式，NONE 表示不含日期段", example = "yyyyMMdd")
    private String dateFormat;

    @Schema(description = "流水位数 1-9", example = "4")
    @Min(value = 1, message = "流水位数不能小于 1")
    @Max(value = 9, message = "流水位数不能大于 9")
    private Integer digit;

    @Schema(description = "流水起始值", example = "1")
    @Min(value = 0, message = "流水起始值不能小于 0")
    private Integer startNumber;

    @Schema(description = "流水步长", example = "1")
    @Min(value = 1, message = "流水步长不能小于 1")
    private Integer step;

    @Schema(description = "随机位数：16 / 32", example = "16")
    private Integer randomDigit;

    @Schema(description = "随机类型：1-纯数字 2-字母+数字", example = "1")
    private Integer randomType;

}
