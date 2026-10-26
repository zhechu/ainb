package cn.enlev.ainb.module.system.controller.admin.dept.vo.post;

import cn.enlev.ainb.framework.excel.core.annotations.DictFormat;
import cn.enlev.ainb.framework.excel.core.convert.DictConvert;
import cn.enlev.ainb.module.system.enums.DictTypeConstants;
import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 岗位信息 Response VO")
@Data
@ExcelIgnoreUnannotated
public class PostRespVO {

    @Schema(description = "岗位序号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @ExcelProperty("岗位序号")
    private Long id;

    @Schema(description = "岗位名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "小土豆")
    @ExcelProperty("岗位名称")
    private String name;

    @Schema(description = "岗位编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "enlev")
    @ExcelProperty("岗位编码")
    private String code;

    @Schema(description = "显示顺序", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @ExcelProperty("岗位排序")
    private Integer sort;

    // 刻意不加 @ExcelProperty：导出的是给人看的清单，裸组织编号既不可读、
    // 也无法在不 join 的前提下换成组织名（本仓 mapper 从不做 join）。
    // 类上的 @ExcelIgnoreUnannotated 会把它排除在 Excel 之外，接口响应仍带上供前端映射名称
    @Schema(description = "所属组织编号，可为空表示不挂组织", example = "100")
    private Long deptId;

    @Schema(description = "状态，参见 CommonStatusEnum 枚举类", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @ExcelProperty(value = "状态", converter = DictConvert.class)
    @DictFormat(DictTypeConstants.COMMON_STATUS)
    private Integer status;

    @Schema(description = "备注", example = "快乐的备注")
    private String remark;

    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createTime;

}
