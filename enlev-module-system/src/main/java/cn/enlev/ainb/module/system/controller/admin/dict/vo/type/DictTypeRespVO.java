package cn.enlev.ainb.module.system.controller.admin.dict.vo.type;

import cn.enlev.ainb.framework.excel.core.annotations.DictFormat;
import cn.enlev.ainb.framework.excel.core.convert.DictConvert;
import cn.enlev.ainb.module.system.enums.DictTypeConstants;
import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 字典类型信息 Response VO")
@Data
@ExcelIgnoreUnannotated
public class DictTypeRespVO {

    @Schema(description = "字典类型编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @ExcelProperty("字典主键")
    private Long id;

    @Schema(description = "父分类编号，0 表示根", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    private Long parentId;

    @Schema(description = "节点类型：1-分组 2-字典", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    private Integer nodeType;

    @Schema(description = "字典名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "性别")
    @ExcelProperty("字典名称")
    private String name;

    @Schema(description = "字典类型", requiredMode = Schema.RequiredMode.REQUIRED, example = "sys_common_sex")
    @ExcelProperty("字典类型")
    private String type;

    @Schema(description = "同级排序", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    private Integer sort;

    @Schema(description = "字典数据是否树形：0-平铺 1-树形", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    private Integer treeEnable;

    @Schema(description = "缓存模式：1-全量下发 2-按需懒加载", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer cacheMode;

    @Schema(description = "状态，参见 CommonStatusEnum 枚举类", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @ExcelProperty(value = "状态", converter = DictConvert.class)
    @DictFormat(DictTypeConstants.COMMON_STATUS)
    private Integer status;

    @Schema(description = "备注", example = "快乐的备注")
    private String remark;

    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED, example = "时间戳格式")
    private LocalDateTime createTime;

}
