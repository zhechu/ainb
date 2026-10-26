package cn.enlev.ainb.module.system.controller.admin.permission.vo.navnode;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotNull;

/**
 * 分端导航节点批量排序 Request VO（「移动端导航」编辑器拖拽排序用）
 *
 * <p>一次提交一批 {@code (id, parentId, sort)}，服务端在同一事务内落库 —— 拖拽跨分组移动时
 * 既要改 {@code sort} 又要改 {@code parentId}，逐条 update 会有「改了一半」的中间态，故走批量
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - 分端导航节点批量排序 Request VO")
@Data
public class NavNodeSortReqVO {

    @Schema(description = "节点编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @NotNull(message = "节点编号不能为空")
    private Long id;

    @Schema(description = "父节点编号，0 表示根", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @NotNull(message = "父节点编号不能为空")
    private Long parentId;

    @Schema(description = "同级排序", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "同级排序不能为空")
    private Integer sort;

}
