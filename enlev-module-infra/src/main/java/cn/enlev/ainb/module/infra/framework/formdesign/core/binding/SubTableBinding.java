package cn.enlev.ainb.module.infra.framework.formdesign.core.binding;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

/**
 * 子表配置（一对多明细）
 *
 * 对应 form-create 的子表控件（{@code type = "group"}），控件的值是一个对象数组，
 * 每个元素是一行明细，键为子表控件内部规则的 field。
 *
 * <h3>增删改语义：逐行 diff，不是全量替换</h3>
 * 提交时按主键与库里现存明细比对，分别产出「新增 / 修改 / 删除」三组
 * （见 {@link cn.enlev.ainb.module.infra.framework.formdesign.core.sql.SubTableDiffEngine}）。
 * 之所以不做「删光再插」：很多业务会引用明细行的 id（附件挂载、流程节点指向某一行等），
 * 全量替换会让 id 全部漂移，下游引用直接断掉。
 *
 * <h3>fieldMapping 是子表能否落库的前提</h3>
 * 它为空时，读写两侧都只剩主键与外键 —— 明细字段既存不进去也查不出来，
 * 且不会有任何报错。这类「配了但静默不生效」的失败最难排查，
 * 因此发布校验会强制要求非空（见 FormDesignPublishValidator#validateSubTable）。
 *
 * @author 走进新时代
 */
@Data
public class SubTableBinding {

    @Schema(description = "form-create 中子表控件的 field", requiredMode = Schema.RequiredMode.REQUIRED, example = "orderItems")
    private String field;

    @Schema(description = "子表名", requiredMode = Schema.RequiredMode.REQUIRED, example = "biz_customer_order")
    private String table;

    @Schema(description = "子表主键列名", example = "id")
    private String pk = "id";

    /**
     * 明细行的主键策略：AUTO-数据库自增 / SNOWFLAKE-雪花 / UUID
     *
     * 早期实现把子表主键写死成自增，绑定「主键是 bigint 但没有 auto_increment」的老表时，
     * INSERT 会因主键为 null 直接失败。改为与主表一样可配。
     *
     * 默认自增而非雪花：子表通常是随主表一起新建的明细表，自增是最常见的存量形态；
     * 主表默认雪花是因为它要对外暴露 ID。选雪花时主键列必须是 bigint，发布校验会核对。
     */
    @Schema(description = "明细主键策略", example = "AUTO")
    private String pkStrategy = "AUTO";

    @Schema(description = "外键列名，指回主表主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "customer_id")
    private String foreignKey;

    /**
     * 子表字段映射：form-create 子表控件内的 field → 子表物理列
     */
    @Schema(description = "字段映射", example = "{ \"productName\": { \"column\": \"product_name\", \"jdbcType\": \"VARCHAR\" } }")
    private Map<String, FieldMapping> fieldMapping;

    /**
     * 明细行的系统字段自动填充配置
     *
     * 独立于主表：明细表的审计列名可能与主表不同，且用户可能只想给主表填。
     * 为空时明细行不填审计列。
     */
    @Schema(description = "系统字段自动填充配置")
    private AutoFillConfig autoFill;

    @Schema(description = "逻辑删除配置")
    private LogicDeleteConfig logicDelete;

}
