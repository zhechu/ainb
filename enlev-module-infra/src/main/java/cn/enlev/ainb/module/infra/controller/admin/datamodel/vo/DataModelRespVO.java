package cn.enlev.ainb.module.infra.controller.admin.datamodel.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 数据模型 Response VO")
@Data
public class DataModelRespVO {

    @Schema(description = "编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "数据源配置的编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    private Long dataSourceConfigId;

    @Schema(description = "表名", requiredMode = Schema.RequiredMode.REQUIRED, example = "biz_order")
    private String tableName;

    @Schema(description = "表描述", requiredMode = Schema.RequiredMode.REQUIRED, example = "订单")
    private String tableComment;

    @Schema(description = "模型名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "订单模型")
    private String modelName;

    @Schema(description = "备注", example = "订单主表")
    private String remark;

    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createTime;

    // ========== AI 产物治理（B4-1）==========

    /**
     * 乐观锁版本。<b>改表时必须原样回传</b>，不传会被硬拒绝
     *
     * <p>两个出口都给它是刻意的：改表页面既可能从 {@code /get} 起手，
     * 也可能从 {@code /structure} 起手（后者是列与索引的唯一权威来源）。
     * 只挂在一个上面，另一个入口进来的调用方就凑不出版本号，
     * 而「凑不出」的表现是被 {@code DATA_MODEL_RECORD_VERSION_REQUIRED} 拒掉 —— 用户看不出自己错在哪。
     */
    @Schema(description = "乐观锁版本，改表时原样回传", example = "0")
    private Integer version;

    @Schema(description = "来源：MANUAL=人工创建 AI=Agent 工具创建 IMPORT=导入已有表登记", example = "AI")
    private String source;

    @Schema(description = "是否已被人工接管（AI 建的表被人在数据模型界面改过）", example = "false")
    private Boolean manualEditedFlag;

}
