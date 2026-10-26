package cn.enlev.ainb.module.system.controller.admin.permission.vo.grant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 生效权限 - 资源树节点
 *
 * 资源字段与 {@link cn.enlev.ainb.module.system.controller.admin.permission.vo.resource.ResourceRespVO}
 * 对齐（前端要拿它和列表页逐列比对），另加两个树形专属字段：{@link #structural} 与 {@link #children}。
 */
@Schema(description = "管理后台 - 生效权限资源节点")
@Data
public class EffectiveResourceNodeVO {

    @Schema(description = "资源编号")
    private Long id;

    @Schema(description = "父资源编号")
    private Long parentId;

    @Schema(description = "资源名称")
    private String name;

    @Schema(description = "权限标识，如 system:user:query")
    private String permission;

    @Schema(description = "资源类型：MENU/ACTION/PROCESS/CATEGORY/DATASET/COLUMN/FIELD")
    private String type;

    @Schema(description = "层级深度")
    private Short depth;

    @Schema(description = "排序")
    private Integer sort;

    /**
     * 资源自身的默认效果
     *
     * 🔴 读的是 {@code sys_resource.default_effect} **列**，不是
     * {@link cn.enlev.ainb.module.system.enums.permission.ResourceTypeEnum#defaultEffect()}。
     * 那个枚举方法只在 {@code ResourceAdminController#createResource} 里当填充默认值用 ——
     * 实测 PROCESS 类型有 18 行 default_effect=ALLOW、9 行 DENY，而枚举说 PROCESS 该是 DENY。
     * 按 type 推会算错 18 个节点
     */
    @Schema(description = "默认效果：ALLOW 或 DENY")
    private String defaultEffect;

    /**
     * 是否为「纯路径节点」：自身没有任何生效动作，仅因后代被保留而留在树里
     *
     * 不标出来的话，前端会把它渲染成一个「有权限」的节点，而它其实什么权限都没有 ——
     * 但不留它树就断链了（祖先一并带上的写法见
     * {@code GrantDecisionService#listAllowedMenuIdsInternal}）
     */
    @Schema(description = "是否为撑起树结构而保留的路径节点（自身无生效动作）")
    private Boolean structural;

    @Schema(description = "该节点上的生效动作清单，按动作码排序；structural=true 时为空")
    private List<EffectiveActionVO> actions;

    @Schema(description = "子节点，按 sort 再按 id 排序；叶子为空列表")
    private List<EffectiveResourceNodeVO> children;

}
