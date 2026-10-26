package cn.enlev.ainb.module.system.controller.admin.dict.vo.data;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import cn.enlev.ainb.framework.excel.core.annotations.DictFormat;
import cn.enlev.ainb.framework.excel.core.convert.DictConvert;
import cn.enlev.ainb.module.system.enums.DictTypeConstants;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 字典数据的 Excel 模型，导入导出共用
 *
 * <h3>为什么导入导出用同一个类</h3>
 * 字典维护的常见路径是「导出 → 在 Excel 里批量改 → 导回来」，跨环境迁移也是导出再导入。
 * 两边各定义一套列，迟早会因为加了一列忘了同步而让往返失效——而且失效的方式很隐蔽：
 * 导入不报错，只是某一列的修改没生效。共用一个类，往返闭环由类型系统保证。
 *
 * <h3>为什么用「上级键值」而不是「上级编号」表达父子</h3>
 * 编号是自增主键，跨环境不一致，导出的文件换个环境导入就全乱了。
 * 键值在同一个字典内唯一且由人指定，既跨环境稳定，也方便在 Excel 里直接看懂层级关系。
 *
 * @author 走进新时代
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ExcelIgnoreUnannotated
public class DictDataExcelVO {

    @ExcelProperty("字典标签")
    private String label;

    @ExcelProperty("字典键值")
    private String value;

    /**
     * 上级节点的键值；为空表示顶层节点
     */
    @ExcelProperty("上级键值")
    private String parentValue;

    @ExcelProperty("显示排序")
    private Integer sort;

    @ExcelProperty(value = "状态", converter = DictConvert.class)
    @DictFormat(DictTypeConstants.COMMON_STATUS)
    private Integer status;

    @ExcelProperty("颜色类型")
    private String colorType;

    @ExcelProperty("CSS Class")
    private String cssClass;

    @ExcelProperty("备注")
    private String remark;

}
