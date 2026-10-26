package cn.enlev.ainb.module.system.controller.admin.permission.vo.slotmapping;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 语义槽位映射响应 VO
 */
@Schema(description = "管理后台 - 语义槽位映射响应")
@Data
public class SlotMappingRespVO {

    @Schema(description = "映射编号")
    private Long id;

    @Schema(description = "资源编号")
    private Long resourceId;

    @Schema(description = "语义槽位名称，如 owner / dept / status / bizKey")
    private String slot;

    @Schema(description = "列所在的物理表名，如 biz_order")
    private String tableName;

    @Schema(description = "真实列名，如 create_user_id")
    private String columnName;

    @Schema(description = "槽位说明")
    private String description;

    /**
     * 这一列在 {@link #tableName} 上是否真的存在（查 information_schema 得出）
     *
     * <p>🔴 只有 {@code /list-by-resource} 会填它，{@code /list} 不填（返回 null）。
     * 全量那个端点是给批量授权算覆盖度用的，量级两百来行、只关心 slot 名，
     * 为它多查一轮库结构不划算；而明细面板一次只展示一个资源的几行，正好核得起。
     *
     * <p>null 表示「没核实过」，与 false（核实过、确实不存在）是两件事 ——
     * 前端只在 false 时标红，别把「没查」当成「查出来是坏的」
     */
    @Schema(description = "这一列在所绑的表上是否真实存在；null 表示该端点没核实", example = "true")
    private Boolean columnExists;

}
