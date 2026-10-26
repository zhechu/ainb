package cn.enlev.ainb.module.infra.api.datamodel.dto;

import lombok.Data;

import java.util.List;

/**
 * 数据模型保存 DTO（建表 / 改表）
 *
 * @author 走进新时代
 */
@Data
public class DataModelSaveReqDTO {

    /**
     * 编号。改表时必传，建表时为 null
     */
    private Long id;

    /**
     * 数据源配置编号
     *
     * ⚠️ 恒传 0。多数据源在数据模型上<b>实际不支持</b>（Service 用的是默认 JdbcTemplate、
     * 没有 @DS 注解），表永远建在主库。留这个字段只为与既有接口对齐。
     */
    private Long dataSourceConfigId = 0L;

    /**
     * 表名。必须 snake_case 且以 biz_ 开头（可配），且不得撞 24 个框架前缀
     */
    private String tableName;

    /**
     * 表描述。至少 2 个字符、含字母或汉字、不等于表名
     */
    private String tableComment;

    /**
     * 模型名称
     */
    private String modelName;

    /**
     * 备注
     */
    private String remark;

    /**
     * 字段列表
     *
     * 🔴 <b>建表与改表的语义正好相反</b>：
     * <ul>
     *   <li><b>建表</b>：只传业务列，系统列由注入器补齐（除非要自定义主键，
     *       那时必须显式提交 id 并设为非主键）</li>
     *   <li><b>改表</b>：必须传<b>全量列（含系统列）</b>。
     *       改表是全量同步语义，没传的列一律被判为删除</li>
     * </ul>
     */
    private List<ColumnDTO> columns;

    /**
     * 索引列表。UNIQUE 须 uk_ 开头、NORMAL 须 idx_ 开头
     */
    private List<IndexDTO> indexes;

    /**
     * 是否已知晓风险
     *
     * 删字段且表里有数据时必须为 true。
     * ⚠️ 对系统列无效 —— 删系统列无论如何都会被拒。
     */
    private Boolean riskConfirmed;

    /**
     * 系统列策略：TENANT（默认）/ GLOBAL / LEGACY
     *
     * 仅建表时生效。GLOBAL 与 LEGACY 都<b>不注入 tenant_id</b>，
     * 意味着该表数据对所有租户可见 —— 业务表一律用 TENANT。
     */
    private String systemColumnProfile;

    /**
     * 登记行的乐观锁版本。<b>改表时必传</b>，取值来自 {@link DataModelApi#getStructure}
     *
     * 不传<b>不是</b>「跳过加锁」，而是<b>硬拒绝</b>：
     * MyBatis-Plus 的乐观锁插件在 version 为 null 时静默跳过版本条件，
     * 那时锁形同没开，所以由 Service 显式判空并报
     * {@code DATA_MODEL_RECORD_VERSION_REQUIRED}。
     */
    private Integer version;

    /**
     * 结构指纹基线，取值来自 {@link DataModelApi#getStructure} 的 {@code schemaFingerprint}
     *
     * 🔴 <b>原样回传，不要自己算</b>。DDL 执行前会拿它跟真实表结构比，不一致就中止 ——
     * 防的是「读到的是旧结构、改的是新表」：多条 ALTER 顺序执行、失败即停、
     * <b>已执行的部分不回滚</b>，撞上一张中途被别人改过的表就是半截结构。
     *
     * <p>基线为空（存量登记行）时放行，DDL 成功后补记基线。
     */
    private String schemaFingerprint;

    /**
     * 确认删除字段
     *
     * 本次改表会删字段时必须为 true，否则拒绝 —— <b>无论表里有没有数据</b>。
     * 它与 {@code riskConfirmed} 不是同一件事：后者是「我看过数据、确认这些列可以丢」，
     * 只在有数据时才需要；前者是「我确实要删这些列」，跟数据无关。
     *
     * <p>🔴 <b>AI 不许设置本字段</b>，{@code DataModelUpdateValidator} 会拦。
     * 用户在计划卡片上点的是「同意执行这个计划」，不是「同意删列」，
     * 把前者当成后者就等于让模型自己给自己开了删列的闸门。
     */
    private Boolean confirmDropColumns;

    @Data
    public static class ColumnDTO {

        private String columnName;
        private String columnComment;
        private String columnType;
        private Integer length;
        private Integer scale;
        private Boolean nullable;
        private Boolean primaryKey;
        private Boolean autoIncrement;
        private String defaultValue;
        /**
         * 是否 ON UPDATE CURRENT_TIMESTAMP
         *
         * 🔴 改表时必须原样带回，否则 {@code update_time} 会被悄悄改成普通列 ——
         * 从此不再自动更新，且不报任何错。见 {@code DataModelColumnSaveReqVO} 上的说明。
         */
        private Boolean onUpdateCurrentTimestamp;
        /**
         * 原字段名。改名时传入，为空表示不改名
         */
        private String oldColumnName;
        private String dictType;
        private String refTable;
        private String refColumn;
    }

    @Data
    public static class IndexDTO {

        private String indexName;
        private String indexType;
        private List<String> columnNames;
    }

}
