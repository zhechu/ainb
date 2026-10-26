package cn.enlev.ainb.module.infra.framework.datamodel.core.model;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 表字段模型
 *
 * 数据模型的内部模型：既是「读表结构」的产物，也是「生成 DDL」的输入。
 * 用同一个模型双向流转，便于做新旧结构的差异比对。
 *
 * @author 走进新时代
 */
@Data
@Accessors(chain = true)
public class TableColumnModel {

    /**
     * 字段名
     */
    private String columnName;
    /**
     * 字段描述（落成 MySQL 的 COMMENT）
     */
    private String columnComment;
    /**
     * 字段类型
     *
     * 枚举 {@link cn.enlev.ainb.module.infra.enums.datamodel.DataModelColumnTypeEnum#getType()}
     */
    private String columnType;
    /**
     * 长度
     *
     * STRING 表示字符数；DECIMAL 表示总位数
     */
    private Integer length;
    /**
     * 精度（小数位），仅 DECIMAL 使用
     */
    private Integer scale;
    /**
     * 是否允许为空
     */
    private Boolean nullable;
    /**
     * 是否主键
     */
    private Boolean primaryKey;
    /**
     * 是否自增，仅主键且为 INT/LONG 时有效
     */
    private Boolean autoIncrement;
    /**
     * 默认值，null 表示不设默认值
     */
    private String defaultValue;

    /**
     * 是否带 {@code ON UPDATE CURRENT_TIMESTAMP}
     *
     * 只对 DATETIME 有意义。框架表的 update_time 都带它，这样绕开应用层的直接 UPDATE
     * 也能刷新更新时间；改造前 {@code MysqlDdlBuilder} 完全不支持生成这个子句。
     */
    private Boolean onUpdateCurrentTimestamp;

    /**
     * 修改字段时的原字段名
     *
     * 为空表示不改名；不为空且与 columnName 不同时，生成 CHANGE COLUMN 而非 MODIFY COLUMN。
     * 这个字段只在「改表」请求里有意义，读表结构时不会填充。
     */
    private String oldColumnName;

    /*
     * 以下三项是「业务语义」而非「存储结构」：不参与 DDL 生成，也不参与结构差异比对，
     * 单独存在 infra_data_model.column_meta 里。详见 ColumnMetaStore。
     */

    /**
     * 字典类型，对应 system_dict_data.dict_type
     *
     * 状态/类型这类 TINYINT 字段配上它，表单层就不用再填一遍
     */
    private String dictType;
    /**
     * 关联的表名
     *
     * 只是业务语义，不建物理外键（全项目零 FOREIGN KEY）
     */
    private String refTable;
    /**
     * 关联的列名
     */
    private String refColumn;

    public boolean isPrimaryKey() {
        return Boolean.TRUE.equals(primaryKey);
    }

    public boolean isAutoIncrement() {
        return Boolean.TRUE.equals(autoIncrement);
    }

    public boolean isNullable() {
        return Boolean.TRUE.equals(nullable);
    }

    public boolean isOnUpdateCurrentTimestamp() {
        return Boolean.TRUE.equals(onUpdateCurrentTimestamp);
    }

}
