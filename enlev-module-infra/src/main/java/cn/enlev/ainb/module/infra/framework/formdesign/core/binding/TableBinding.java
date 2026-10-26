package cn.enlev.ainb.module.infra.framework.formdesign.core.binding;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 表绑定配置
 *
 * 对应 {@link cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignDO#getTableBinding()} 的 JSON 结构。
 * 本模块采用「只绑定已有表」方案，不建表不改表，表单只能挂到已存在的物理表上。
 *
 * 核心约定：
 * 1. 三类表的区别：
 *    - sideTables 副表：1:1，与主表同生命周期，一起增删改（如订单扩展表）
 *    - subTables 子表：1:N，明细表，逐行 diff 增删改（如订单明细）
 *    - joinTables 关联表：N:1，只读，仅供列表展示（如显示业务员姓名）
 * 2. 写入只涉及 mainTable、sideTables、subTables，joinTables 只读
 * 3. 子表增删改采用逐行 diff（按主键比对），保持明细 ID 稳定
 * 4. 主表与所有从表必须在同一数据源，否则无法保证事务一致性（发布校验会拒绝）
 *
 * @author 走进新时代
 */
@Data
public class TableBinding {

    @Schema(description = "主表名", requiredMode = Schema.RequiredMode.REQUIRED, example = "biz_customer")
    private String mainTable;

    @Schema(description = "主表主键列名", example = "id")
    private String mainPk = "id";

    /**
     * 主键策略：AUTO-数据库自增 / SNOWFLAKE-雪花 / UUID
     *
     * 默认雪花：自增主键在分库分表、数据迁移、多环境同步时会冲突，
     * 而雪花 ID 全局唯一。三种策略都保留，绑定老表时仍可选自增。
     *
     * ⚠️ 选雪花时主键列必须是 bigint —— 19 位 Long 塞进 int 列会溢出，
     * 发布校验会拦住这种组合。
     */
    @Schema(description = "主键策略", example = "SNOWFLAKE")
    private String pkStrategy = "SNOWFLAKE";

    @Schema(description = "逻辑删除配置")
    private LogicDeleteConfig logicDelete;

    /**
     * 乐观并发锁配置
     *
     * 与逻辑删除并列放在这里：两者都是「绑定的已有表上某一列的约定」，
     * 都得按表配，也都由发布校验核对那一列真的存在。
     */
    @Schema(description = "乐观并发锁配置")
    private OptimisticLockConfig optimisticLock;

    /**
     * 数据变更日志配置
     *
     * 同样放在这里：它是「这张表的数据怎么写」的一部分。
     */
    @Schema(description = "数据变更日志配置")
    private ChangeLogConfig changeLog;

    @Schema(description = "系统字段自动填充配置")
    /**
     * 业务主键去重配置
     *
     * 按指定的几个字段判重：新增时若库里已存在这几个字段值全都相同的记录，直接拒绝。
     *
     * 放在 tableBinding 这个 JSON 里而不是单开一列：它本来就是承载绑定配置的地方，
     * 加一列意味着又一次 DDL 与一轮存量数据回填，收益不匹配。
     */
    private BusinessKeyConfig businessKey;

    private AutoFillConfig autoFill;

    /**
     * 字段映射：form-create 的 field → 物理列
     *
     * key 为 form-create rule 的 field，value 为映射配置。
     * 映射关系不写进 form-create 的 rule 里，避免污染第三方结构。
     */
    @Schema(description = "字段映射", example = "{ \"customerName\": { \"table\": \"biz_customer\", \"column\": \"name\", \"jdbcType\": \"VARCHAR\" } }")
    private Map<String, FieldMapping> fieldMapping;

    /**
     * 副表：一对一，与主表同生命周期，一起增删改
     */
    @Schema(description = "副表配置")
    private List<SideTableBinding> sideTables;

    /**
     * 从表：一对一，用于列表展示关联字段（LEFT JOIN，只读）
     */
    @Schema(description = "从表关联配置")
    private List<JoinTableBinding> joinTables;

    /**
     * 子表：一对多明细，对应 form-create 的 fcTable/subForm 控件
     */
    @Schema(description = "子表配置")
    private List<SubTableBinding> subTables;


    /**
     * 业务主键去重配置
     */
    @Data
    public static class BusinessKeyConfig {

        /**
         * 是否启用
         */
        private Boolean enabled;
        /**
         * 参与判重的字段（表单字段名，不是物理列名）
         */
        private java.util.List<String> fields;
        /**
         * 判重命中时的提示文案
         */
        private String tip;

        public boolean isEnabled() {
            return Boolean.TRUE.equals(enabled) && fields != null && !fields.isEmpty();
        }

        public String tipOrDefault() {
            return cn.hutool.core.util.StrUtil.isNotBlank(tip) ? tip : "已存在相同的数据，请勿重复提交";
        }

    }

}
