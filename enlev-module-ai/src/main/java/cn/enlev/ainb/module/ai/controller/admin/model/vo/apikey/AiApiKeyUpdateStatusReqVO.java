package cn.enlev.ainb.module.ai.controller.admin.model.vo.apikey;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.validation.InEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.experimental.Accessors;

@Schema(description = "管理后台 - AI API 密钥更新状态 Request VO")
@Data
@Accessors(chain = true)
public class AiApiKeyUpdateStatusReqVO {

    // 🔴 这个 VO 里【没有】apiKey 字段，这是刻意的：
    //    AiApiKeyRespVO.apiKey 带 @SliderDesensitize，列表页拿到的是打码串。
    //    若让前端把整行回传 /update，BeanUtils.toBean + updateById 会把「sk-abc***wxyz」
    //    这样的掩码当成真密钥加密落库，静默毁掉每一个密钥。状态切换只需要 id + status。

    @Schema(description = "编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @NotNull(message = "API 密钥编号不能为空")
    private Long id;

    @Schema(description = "状态，见 CommonStatusEnum 枚举", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @NotNull(message = "状态不能为空")
    @InEnum(value = CommonStatusEnum.class, message = "修改状态必须是 {value}")
    private Integer status;

}
