package cn.enlev.ainb.module.infra.framework.formdesign.core.imports;

import lombok.Data;

/**
 * 一个可导入的字段
 *
 * 由表单字段规则（{@code form_fields}）与列表列配置（{@code list_conf}）合并而来，
 * 是导入全流程的统一模型：表头匹配、单元格解析、干跑校验都用它。
 *
 * <h3>为什么要合并列表列</h3>
 *
 * 真正会被导入的文件，多半是「先导出、改一改、再导回来」。
 * 而导出的表头取的是**列表列的 label**（见 exportExcel），不是表单字段的 title ——
 * 两者可以不一样（列上叫「编号（含年份）」，字段上叫「项目编号」）。
 *
 * 只认 title 的话，用户改完导出文件原样导回来会一列都匹配不上，
 * 而这恰恰是最常见的用法。
 *
 * @author 走进新时代
 */
@Data
public class ImportWidget {

    /**
     * 字段名。对应 form-create 规则的 field，也是提交数据里的键
     */
    private String field;

    /**
     * 字段标题。报错时说这个，比报 field 有用
     */
    private String title;

    /**
     * 控件类型。如 input / inputNumber / select / datePicker / group
     */
    private String type;

    /**
     * 是否必填。来自规则的 $required，会被联动规则覆盖（见 DryRunValidator）
     */
    private boolean required;

    /**
     * 是否禁用。禁用字段的值是服务端算出来的，导入时应当忽略文件里的值
     */
    private boolean disabled;

    /**
     * 精度。inputNumber 用，决定小数位
     */
    private Integer precision;

    /**
     * 数值下限。来自 props.min
     *
     * 导入侧只用它生成模板示例值 —— 真正的范围校验在服务端的
     * 联动/格式校验里，这里再判一次会出现两处规则不一致
     */
    private java.math.BigDecimal min;

    /**
     * 数值上限。来自 props.max
     */
    private java.math.BigDecimal max;

    /**
     * 字典类型。select / DictSelect 等控件在 props.dictSource.dictType 里带
     */
    private String dictType;

    /**
     * 字典值的类型：str / num。决定反查出来的是字符串还是数字
     */
    private String dictValueType;

    /**
     * 日期控件的时间粒度：date / datetime / time
     */
    private String dateType;

    /**
     * 日期值格式，来自 props.valueFormat
     */
    private String valueFormat;

    /**
     * 关联的数据接口编码（ApiSelect 之类）。这类字段的值依赖外部数据源，
     * 导入时不做存在性校验，只原样透传
     */
    private String apiCode;

    /**
     * 所属子表控件的 field。为空表示主表字段
     */
    private String subField;

    /**
     * 所属子表控件的标题
     */
    private String subTitle;

    /**
     * 列表列上的展示名。有的话优先用它匹配表头 —— 导出文件里就是这个
     */
    private String columnLabel;

    /**
     * 是否配了脱敏。配了的话导入时要识别「掩码回显」
     */
    private boolean masked;

    /**
     * 是否配了唯一性校验
     */
    private boolean uniqueCheck;

    /**
     * 是否公式字段。是的话文件里的值不作数，服务端会重算
     */
    private boolean formula;

    /**
     * 报错时用的展示名。有标题用标题 —— 报字段 ID 用户看不懂
     */
    public String label() {
        if (title != null && !title.isBlank()) {
            return title;
        }
        if (columnLabel != null && !columnLabel.isBlank()) {
            return columnLabel;
        }
        return field;
    }

    /**
     * 是否属于某张子表
     */
    public boolean isSubField() {
        return subField != null;
    }

}
