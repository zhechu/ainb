package cn.enlev.ainb.module.infra.framework.formdesign.core.binding;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 逻辑删除配置
 *
 * 绑定的是已有表，各表的逻辑删除约定可能不同（列名、删除值、列类型），因此做成可配置。
 * 未启用时执行物理 DELETE。
 *
 * ⚠️ 值类型必须显式配置：deletedValue / normalValue 以字符串存储（JSON 里就是字符串），
 * 但物理列可能是 bit / tinyint / char，直接把字符串绑给 bit 列会报
 * 「Data truncation: Data too long for column」。valueType 决定绑参前转成什么 Java 类型。
 *
 * @author 走进新时代
 */
@Data
public class LogicDeleteConfig {

    @Schema(description = "是否启用逻辑删除", example = "true")
    private Boolean enabled = false;

    @Schema(description = "逻辑删除列名", example = "deleted")
    private String column;

    @Schema(description = "已删除时的值", example = "1")
    private String deletedValue;

    @Schema(description = "未删除时的值。查询时会自动追加此条件", example = "0")
    private String normalValue;

    /**
     * 值类型：BOOLEAN / NUMBER / STRING，见 {@link cn.enlev.ainb.module.infra.enums.formdesign.LogicDeleteValueTypeEnum}
     *
     * 默认 NUMBER —— 项目内绝大多数表的 deleted 列是 bit/tinyint，
     * 而 MySQL 的 bit(1) 能接受 0/1 数字，兼容面最广。
     */
    @Schema(description = "值类型", example = "NUMBER")
    private String valueType = "NUMBER";

}
