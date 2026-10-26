package cn.enlev.ainb.module.system.controller.admin.dict.vo.data;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Schema(description = "管理后台 - 字典数据导入 Response VO")
@Data
@Builder
public class DictDataImportRespVO {

    @Schema(description = "创建成功的字典键值数组", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> createValues;

    @Schema(description = "更新成功的字典键值数组", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> updateValues;

    @Schema(description = "导入失败的字典数据，key 为字典键值，value 为失败原因", requiredMode = Schema.RequiredMode.REQUIRED)
    private Map<String, String> failureValues;

}
