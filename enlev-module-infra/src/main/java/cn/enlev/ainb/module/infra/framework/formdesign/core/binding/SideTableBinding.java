package cn.enlev.ainb.module.infra.framework.formdesign.core.binding;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

/**
 * 副表配置（一对一，参与写入）
 *
 * 与主表同生命周期，一起增删改。典型场景：
 * 1. 主表字段太多需拆表（如订单主表 + 订单扩展表）
 * 2. 扩展属性表（如客户主表 + 客户自定义字段表）
 *
 * 与 {@link JoinTableBinding} 的区别：
 * - JoinTableBinding 只读，用于列表展示关联字段（如显示业务员姓名）
 * - SideTableBinding 读写，与主表数据同步维护
 *
 * 注意：副表的 1:1 关系没有数据库层面的保证。如果副表里同一个外键有多行，
 * 查询时取第一行并记录警告日志。建议副表外键上加唯一索引，但本模块不强制。
 *
 * @author 走进新时代
 */
@Data
public class SideTableBinding {

    @Schema(description = "副表名", requiredMode = Schema.RequiredMode.REQUIRED, example = "biz_order_ext")
    private String table;

    @Schema(description = "副表主键列名", example = "id")
    private String pk = "id";

    /**
     * 副表主键策略：AUTO-数据库自增 / SNOWFLAKE-雪花 / UUID
     *
     * 早期实现从不写入副表主键，等同于假定它是自增列。绑定「主键是 bigint 但没有
     * auto_increment」的老表时，INSERT 会因主键为 null 直接失败，且报错信息
     * （Column 'id' cannot be null）完全指不到表单配置上。改为与主表一样可配。
     *
     * 默认自增，保持与既有配置的行为一致。
     */
    @Schema(description = "副表主键策略", example = "AUTO")
    private String pkStrategy = "AUTO";

    @Schema(description = "外键列名，存主表主键值", requiredMode = Schema.RequiredMode.REQUIRED, example = "order_id")
    private String foreignKey;

    /**
     * 关联到主表的哪一列。通常是主表主键，但也支持关联到主表的其它唯一列。
     */
    @Schema(description = "关联到主表的列", example = "id")
    private String relationField = "id";

    /**
     * 副表字段映射：form-create 的 field → 副表物理列
     */
    @Schema(description = "字段映射", example = "{ \"extField1\": { \"column\": \"ext_field_1\", \"jdbcType\": \"VARCHAR\" } }")
    private Map<String, FieldMapping> fieldMapping;

    @Schema(description = "逻辑删除配置")
    private LogicDeleteConfig logicDelete;

}
