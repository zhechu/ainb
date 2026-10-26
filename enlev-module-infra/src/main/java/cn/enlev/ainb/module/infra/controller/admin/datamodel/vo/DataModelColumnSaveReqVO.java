package cn.enlev.ainb.module.infra.controller.admin.datamodel.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 数据模型字段 Request VO")
@Data
public class DataModelColumnSaveReqVO {

    @Schema(description = "字段名", requiredMode = Schema.RequiredMode.REQUIRED, example = "order_no")
    @NotEmpty(message = "字段名不能为空")
    private String columnName;

    // 描述是否必填由 Service 层按字段区分：业务字段必填，系统内置字段的描述由框架托管
    // （导入的真实表里 creator/deleted 等可能本就为空，且这些字段只读、用户无法填写），
    // 故此处不加 @NotEmpty，避免设计已有表时被内置字段的空描述卡住预览与保存。
    @Schema(description = "字段描述", example = "订单号")
    private String columnComment;

    @Schema(description = "字段类型", requiredMode = Schema.RequiredMode.REQUIRED, example = "STRING")
    @NotEmpty(message = "字段类型不能为空")
    private String columnType;

    @Schema(description = "长度", example = "64")
    private Integer length;

    @Schema(description = "精度（小数位），仅 DECIMAL 使用", example = "2")
    private Integer scale;

    @Schema(description = "是否允许为空", requiredMode = Schema.RequiredMode.REQUIRED, example = "true")
    @NotNull(message = "是否允许为空不能为空")
    private Boolean nullable;

    @Schema(description = "是否主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "false")
    @NotNull(message = "是否主键不能为空")
    private Boolean primaryKey;

    @Schema(description = "是否自增", example = "false")
    private Boolean autoIncrement;

    @Schema(description = "默认值", example = "")
    private String defaultValue;

    /**
     * 是否 ON UPDATE CURRENT_TIMESTAMP
     *
     * 🔴 这个字段必须能在保存时表达出来，否则<b>改表会把它悄悄抹掉</b>。
     *
     * <p>{@code TableMetaReader} 读结构时读得出它，{@code TableDiffer} 比对时也比它，
     * 唯独保存的入参上没有 —— 于是「取回结构 → 改一点 → 存回去」这个最自然的操作，
     * 会让 {@code update_time} 从 {@code ON UPDATE CURRENT_TIMESTAMP} 变成普通列。
     * 表现是这张表的 update_time 从此不再自动更新，而且不报任何错。
     *
     * <p>实测确认过：给一张表加一个字段，update_time 的 EXTRA 就从
     * {@code DEFAULT_GENERATED on update CURRENT_TIMESTAMP} 变成 {@code DEFAULT_GENERATED}。
     */
    @Schema(description = "是否在更新时自动填充当前时间", example = "false")
    private Boolean onUpdateCurrentTimestamp;

    @Schema(description = "原字段名。改名时传入；为空表示不改名", example = "order_code")
    private String oldColumnName;

    /*
     * 以下三项是业务语义，不落物理表结构，单独存进 infra_data_model.column_meta。
     * 它们让「这列配哪个字典、关联哪张表」可被程序读取 —— 此前只存在于列注释的自然语言里。
     */

    @Schema(description = "字典类型，对应 system_dict_data.dict_type", example = "order_status")
    @Size(max = 100, message = "字典类型不能超过 100 个字符")
    private String dictType;

    @Schema(description = "关联的表名。仅业务语义，不建物理外键", example = "biz_customer")
    @Size(max = 64, message = "关联表名不能超过 64 个字符")
    private String refTable;

    @Schema(description = "关联的列名", example = "id")
    @Size(max = 64, message = "关联列名不能超过 64 个字符")
    private String refColumn;

}
