package cn.enlev.ainb.module.system.controller.admin.dict.vo.transfer;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 迁移包里的一条字典数据
 *
 * 字段与 {@link cn.enlev.ainb.module.system.controller.admin.dict.vo.data.DictDataExcelVO}
 * 一一对应，只多一个 {@link #dictType}：Excel 是「一个文件一个字典」，
 * 迁移包是「一个文件一棵分类子树」，所以每条数据得自报家门属于哪个字典。
 *
 * 父子关系同样用「上级键值」表达，理由见 DictDataExcelVO 的类注释——
 * 两条导入通道用同一种层级表达方式，人只需要理解一次。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - 字典迁移包中的字典数据")
@Data
public class DictDataTransferVO {

    @Schema(description = "所属字典编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "sys_common_sex")
    private String dictType;

    @Schema(description = "字典标签", requiredMode = Schema.RequiredMode.REQUIRED, example = "男")
    private String label;

    @Schema(description = "字典键值", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private String value;

    @Schema(description = "上级节点的键值；为空表示顶层节点", example = "elec")
    private String parentValue;

    @Schema(description = "显示排序", example = "0")
    private Integer sort;

    @Schema(description = "状态，参见 CommonStatusEnum 枚举类", example = "0")
    private Integer status;

    @Schema(description = "颜色类型", example = "primary")
    private String colorType;

    @Schema(description = "CSS Class", example = "btn-visible")
    private String cssClass;

    @Schema(description = "备注", example = "快乐的备注")
    private String remark;

}
