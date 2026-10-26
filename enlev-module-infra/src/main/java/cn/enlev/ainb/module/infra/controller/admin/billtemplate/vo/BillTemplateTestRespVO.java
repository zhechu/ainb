package cn.enlev.ainb.module.infra.controller.admin.billtemplate.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 取号管理 - 取号测试 Response VO")
@Data
public class BillTemplateTestRespVO {

    @Schema(description = "生成（或试算）的单据号", requiredMode = Schema.RequiredMode.REQUIRED, example = "HT202609200001")
    private String no;

    @Schema(description = "本次使用的流水序号；随机数 / UUID 方式为 null", example = "1")
    private Long seq;

    @Schema(description = "计数周期段", example = "202609")
    private String period;

    @Schema(description = "是否真实取号：true-已消耗流水 false-仅模拟", requiredMode = Schema.RequiredMode.REQUIRED, example = "false")
    private Boolean real;

}
