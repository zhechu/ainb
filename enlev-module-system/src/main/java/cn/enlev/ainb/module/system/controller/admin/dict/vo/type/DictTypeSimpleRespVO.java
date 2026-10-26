package cn.enlev.ainb.module.system.controller.admin.dict.vo.type;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 字典类型精简信息 Response VO")
@Data
public class DictTypeSimpleRespVO {

    @Schema(description = "字典类型编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    private Long id;

    @Schema(description = "父分类编号，0 表示根", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    private Long parentId;

    @Schema(description = "节点类型：1-分组 2-字典", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    private Integer nodeType;

    @Schema(description = "字典类型名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "启凌智搭")
    private String name;

    @Schema(description = "字典类型", requiredMode = Schema.RequiredMode.REQUIRED, example = "sys_common_sex")
    private String type;

    @Schema(description = "字典数据是否树形：0-平铺 1-树形", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    private Integer treeEnable;

    @Schema(description = "缓存模式：1-全量下发 2-按需懒加载", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer cacheMode;

    /**
     * 该接口刻意返回「开启 + 禁用」的全部字典，所以必须下发状态，
     * 否则调用方无法区分 —— 表单设计器要据此排除选不出选项的禁用字典。
     */
    @Schema(description = "状态：0-开启 1-禁用", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    private Integer status;

}
