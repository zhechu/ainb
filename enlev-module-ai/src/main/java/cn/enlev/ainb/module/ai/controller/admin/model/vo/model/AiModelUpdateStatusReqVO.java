package cn.enlev.ainb.module.ai.controller.admin.model.vo.model;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.validation.InEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.experimental.Accessors;

@Schema(description = "管理后台 - AI 模型更新状态 Request VO")
@Data
@Accessors(chain = true)
public class AiModelUpdateStatusReqVO {

    // 🔴 刻意不打 @DiffLogField：审计 DIFF 比的是 Service 里构造的那对 AiModelSaveReqVO，
    //    注解已经在那边了，两边都打会让同一处改动在操作日志里输出两遍。

    @Schema(description = "编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @NotNull(message = "模型编号不能为空")
    private Long id;

    @Schema(description = "状态，见 CommonStatusEnum 枚举", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @NotNull(message = "状态不能为空")
    @InEnum(value = CommonStatusEnum.class, message = "修改状态必须是 {value}")
    private Integer status;

}
