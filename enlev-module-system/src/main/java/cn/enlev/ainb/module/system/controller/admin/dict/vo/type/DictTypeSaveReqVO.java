package cn.enlev.ainb.module.system.controller.admin.dict.vo.type;

import cn.enlev.ainb.framework.common.validation.InEnum;
import cn.enlev.ainb.module.system.enums.dict.DictCacheModeEnum;
import cn.enlev.ainb.module.system.enums.dict.DictTypeNodeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "管理后台 - 字典类型创建/修改 Request VO")
@Data
public class DictTypeSaveReqVO {

    @Schema(description = "字典类型编号", example = "1024")
    private Long id;

    @Schema(description = "父分类编号，0 表示根", example = "0")
    private Long parentId;

    @Schema(description = "节点类型：1-分组 2-字典", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    @NotNull(message = "节点类型不能为空")
    @InEnum(value = DictTypeNodeEnum.class, message = "节点类型必须是 {value}")
    private Integer nodeType;

    @Schema(description = "字典名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "性别")
    @NotBlank(message = "字典名称不能为空")
    @Size(max = 100, message = "字典类型名称长度不能超过100个字符")
    private String name;

    @Schema(description = "字典类型", requiredMode = Schema.RequiredMode.REQUIRED, example = "sys_common_sex")
    @NotNull(message = "字典类型不能为空")
    @Size(max = 100, message = "字典类型类型长度不能超过 100 个字符")
    private String type;

    @Schema(description = "同级排序", example = "0")
    private Integer sort;

    @Schema(description = "字典数据是否树形：0-平铺 1-树形", example = "0")
    private Integer treeEnable;

    @Schema(description = "缓存模式：1-全量下发 2-按需懒加载", example = "1")
    @InEnum(value = DictCacheModeEnum.class, message = "缓存模式必须是 {value}")
    private Integer cacheMode;

    @Schema(description = "状态，参见 CommonStatusEnum 枚举类", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "状态不能为空")
    private Integer status;

    @Schema(description = "备注", example = "快乐的备注")
    private String remark;

}
