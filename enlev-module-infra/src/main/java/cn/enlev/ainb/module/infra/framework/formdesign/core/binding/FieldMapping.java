package cn.enlev.ainb.module.infra.framework.formdesign.core.binding;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 字段映射：form-create 的字段 → 物理列
 *
 * 映射关系【不】写进 form-create 的 rule 里 —— 那会污染第三方结构且升级易冲突。
 * 统一放在 {@link TableBinding#getFieldMapping()} 中，以 rule 的 field 作为键关联。
 *
 * @author 走进新时代
 */
@Data
public class FieldMapping {

    @Schema(description = "所属表名。为空时默认主表", example = "biz_customer")
    private String table;

    @Schema(description = "物理列名", requiredMode = Schema.RequiredMode.REQUIRED, example = "name")
    private String column;

    /**
     * 显式声明 JDBC 类型，不靠运行时反射猜 ——
     * 这是参数绑定安全与类型正确的基础。
     */
    @Schema(description = "JDBC 类型", example = "VARCHAR")
    private String jdbcType;

}
