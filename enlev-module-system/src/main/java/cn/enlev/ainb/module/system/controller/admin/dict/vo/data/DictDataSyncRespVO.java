package cn.enlev.ainb.module.system.controller.admin.dict.vo.data;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Schema(description = "管理后台 - 字典数据同步 Response VO")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DictDataSyncRespVO {

    @Schema(description = "字典版本号", requiredMode = Schema.RequiredMode.REQUIRED, example = "7")
    private Long version;

    @Schema(description = "字典数据列表", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<DictDataSimpleRespVO> list;

}
