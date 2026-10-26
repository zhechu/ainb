package cn.enlev.ainb.module.system.controller.admin.dict.vo.transfer;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 迁移包里的一个字典分类节点
 *
 * <h3>为什么不带 id / parentId</h3>
 * 主键是自增的，同一个字典在测试库和生产库拿到的 id 几乎不可能相同。
 * 包里若存 id，换个环境导入就会挂错父级，或者与别人的 id 撞车。
 * 改用编码寻址（{@link #type} + {@link #parentType}）之后，包的含义与环境无关。
 *
 * <h3>为什么不带创建人、创建时间</h3>
 * 那是导出环境的审计信息，导进来之后属于新环境的一次「导入」动作，
 * 原样搬过去只会让审计字段说谎。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - 字典迁移包中的分类节点")
@Data
public class DictTypeTransferVO {

    @Schema(description = "分类编码，包内唯一", requiredMode = Schema.RequiredMode.REQUIRED, example = "sys_common_sex")
    private String type;

    @Schema(description = "父分类编码；为空表示它是本包的根，导入时挂到用户指定的位置", example = "sys_common")
    private String parentType;

    @Schema(description = "节点类型：1-分组 2-字典", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    private Integer nodeType;

    @Schema(description = "分类名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "性别")
    private String name;

    @Schema(description = "同级排序", example = "0")
    private Integer sort;

    @Schema(description = "字典数据是否树形：0-平铺 1-树形", example = "0")
    private Integer treeEnable;

    @Schema(description = "缓存模式：1-全量下发 2-按需懒加载", example = "1")
    private Integer cacheMode;

    @Schema(description = "状态，参见 CommonStatusEnum 枚举类", example = "0")
    private Integer status;

    @Schema(description = "备注", example = "快乐的备注")
    private String remark;

}
