package cn.enlev.ainb.module.system.controller.admin.dict.vo.type;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - 字典类型移动 Request VO")
@Data
public class DictTypeMoveReqVO {

    @Schema(description = "字典类型编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @NotNull(message = "字典类型编号不能为空")
    private Long id;

    @Schema(description = "目标父分类编号，0 表示根", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @NotNull(message = "父分类编号不能为空")
    private Long parentId;

    @Schema(description = "目标同级排序", requiredMode = Schema.RequiredMode.REQUIRED, example = "3")
    @NotNull(message = "排序不能为空")
    private Integer sort;

}
