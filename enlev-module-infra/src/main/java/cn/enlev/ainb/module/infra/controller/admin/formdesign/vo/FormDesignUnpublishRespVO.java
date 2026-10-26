package cn.enlev.ainb.module.infra.controller.admin.formdesign.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 下线结果
 *
 * 与 {@link FormDesignPublishRespVO} 同一条道理：infra 不能反向依赖 system，
 * 菜单与导航节点的物理删除都由前端完成，本 VO 只把「该回收哪些」交还出去。
 */
@Schema(description = "管理后台 - 表单设计下线结果 Response VO")
@Data
public class FormDesignUnpublishRespVO {

    @Schema(description = "需要前端删除的菜单编号列表，父菜单在前（必须整批交给 /system/menu/delete-list）",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private List<Long> menuIds;

    @Schema(description = "需要前端删除的 App 导航节点编号。未发布过 App 端时为 null", example = "356")
    private Long appNavNodeId;

}
