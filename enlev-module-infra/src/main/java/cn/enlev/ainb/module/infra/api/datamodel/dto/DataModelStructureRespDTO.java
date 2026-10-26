package cn.enlev.ainb.module.infra.api.datamodel.dto;

import lombok.Data;

import java.util.List;

/**
 * 数据模型结构 DTO
 *
 * 跨模块传递用。刻意不复用 {@code DataModelStructureRespVO} ——
 * 那是 Controller 的出参，随界面需要变动；api 是跨模块契约，两者变更节奏不同。
 *
 * @author 走进新时代
 */
@Data
public class DataModelStructureRespDTO {

    /**
     * 表名
     */
    private String tableName;

    /**
     * 表描述
     */
    private String tableComment;

    /**
     * 字段列表，实时读取真实表结构
     */
    private List<ColumnDTO> columns;

    /**
     * 索引列表（不含主键）
     */
    private List<IndexDTO> indexes;

    /**
     * 结构指纹，由<b>本次实时读到的</b> {@link #columns} 现算
     *
     * 🔴 改表时原样回传给 {@code DataModelSaveReqDTO#schemaFingerprint}。
     * 它是「读结构」与「执行 DDL」之间的那道闸：DDL 前会用库里存的基线跟真实结构比一次，
     * 不一致就中止，防的是「读到旧结构、改新表」—— 多条 ALTER 顺序执行、失败即停、
     * 已执行的部分不回滚。
     *
     * <p>⚠️ 这里给的是<b>实时值</b>而不是库存基线。拿库存基线当返回值，
     * 调用方回传后就变成「自己跟自己比」，永远相等，那道闸就成了空操作。
     */
    private String schemaFingerprint;

    /**
     * 登记行的乐观锁版本，改表时原样回传给 {@code DataModelSaveReqDTO#version}
     *
     * <p>{@code DataModelApi} 没有「按 id 读登记行」的方法，
     * 所以改表所需的两个基线（指纹 + 版本）都只能挂在本结构上一起给出去。
     */
    private Integer version;

    @Data
    public static class ColumnDTO {

        private String columnName;
        private String columnComment;
        /**
         * 受控类型：STRING/TEXT/LONG_TEXT/JSON/TINYINT/INT/LONG/DECIMAL/BOOLEAN/DATE/DATETIME
         *
         * ⚠️ 这份清单是手抄 {@code DataModelColumnTypeEnum} 的，加了类型要回来同步。
         * 权威出口是 {@code DataModelApi#getColumnTypeList()}，别拿这句注释当判据。
         */
        private String columnType;
        private Integer length;
        private Integer scale;
        private Boolean nullable;
        private Boolean primaryKey;
        private Boolean autoIncrement;
        private String defaultValue;
        /**
         * 是否带 ON UPDATE CURRENT_TIMESTAMP
         */
        private Boolean onUpdateCurrentTimestamp;
        /**
         * 是否为系统托管列（id/审计/version/deleted/tenant_id）
         *
         * 🔴 调用方最需要的一个标记：改表是<b>全量同步</b>语义，
         * 提交的列表里缺了系统列就会被判为删除。带上这个标记，调用方才能明确
         * 「这些列必须原样回传」。
         */
        private Boolean systemColumn;

        /*
         * 业务语义（不落物理表结构，存 infra_data_model.column_meta）
         */
        private String dictType;
        private String refTable;
        private String refColumn;
    }

    @Data
    public static class IndexDTO {

        private String indexName;
        /**
         * NORMAL / UNIQUE
         */
        private String indexType;
        private List<String> columnNames;
    }

}
