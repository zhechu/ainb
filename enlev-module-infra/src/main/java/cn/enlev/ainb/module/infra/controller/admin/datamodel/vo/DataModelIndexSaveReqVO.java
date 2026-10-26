package cn.enlev.ainb.module.infra.controller.admin.datamodel.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 数据模型索引 Request VO")
@Data
public class DataModelIndexSaveReqVO {

    @Schema(description = "索引名", requiredMode = Schema.RequiredMode.REQUIRED, example = "idx_order_no")
    @NotEmpty(message = "索引名不能为空")
    private String indexName;

    @Schema(description = "索引类型：NORMAL 普通 / UNIQUE 唯一", requiredMode = Schema.RequiredMode.REQUIRED, example = "NORMAL")
    @NotEmpty(message = "索引类型不能为空")
    private String indexType;

    @Schema(description = "索引列，顺序即最左前缀顺序", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "索引至少需要指定一个列")
    private List<String> columnNames;

}
