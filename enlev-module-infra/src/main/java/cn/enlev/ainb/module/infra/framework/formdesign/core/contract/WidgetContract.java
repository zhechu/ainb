package cn.enlev.ainb.module.infra.framework.formdesign.core.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * 单个表单控件的契约
 *
 * 描述一个控件的全部结构性事实：值类型、落库形态、可映射的物理列类型、能力位。
 * 属性面板、列表列、查询条件、导出、发布校验全部读这里，不再各自硬编码。
 *
 * 数据来自 classpath 下的 form-design/form-widget-contracts.json，见 {@link WidgetContractRegistry}。
 *
 * @author 走进新时代
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true) // JSON 里有 _comment 说明字段，不参与反序列化
public class WidgetContract {

    /**
     * 值类型「文件」：值是文件 URL 或文件对象，而不是普通标量
     */
    public static final String VALUE_TYPE_FILE = "file";

    /**
     * 控件类型，即 form-create 规则里的 type。契约的唯一标识
     */
    private String type;
    /**
     * 展示名称
     */
    private String label;
    /**
     * 左侧菜单分组：basic / select / advanced / layout / display / system
     */
    private String group;
    /**
     * 本模块是否支持
     *
     * false 表示内核提供了该控件但本模块不支持它。发布校验会明确拒绝，
     * 而不是让它静默走到错误的处理分支去 —— 后者的表现是「数据看起来存了，其实没进库」。
     */
    private Boolean supported;
    /**
     * 是否已废弃
     *
     * 标记为待删除的控件（如被合并控件替代的旧控件）。当前仍可用，
     * 但不应在新表单中使用。删除批次见契约 JSON 里的 _comment。
     */
    private Boolean deprecated;
    /**
     * 值类型：none / string / number / boolean / date / array / object / file
     */
    private String valueType;
    /**
     * 落库形态：none / scalar / json / delimited / subTable
     */
    private String storage;
    /**
     * 可映射的物理列类型族
     *
     * 空列表表示该控件不落库（展示型、布局型）。
     */
    private List<JdbcTypeFamily> columnTypes;
    /**
     * 能力位
     */
    private Capabilities caps;
    /**
     * 是否支持数据脱敏
     */
    private Boolean maskable;
    /**
     * 字段级权限预留占位
     *
     * 本阶段不参与任何逻辑，仅为日后接入字段级权限时不必改契约结构（见设计方案 6.2）。
     */
    private String permissionKey;

    /**
     * 控件自身的配置项（契约 v2 新增）
     *
     * <h3>为什么要把它写进契约</h3>
     * 前面那些字段描述的是「这个控件的值怎么存」，而 props 描述的是
     * 「这个控件怎么配才能用起来」—— {@code DictSelect} 该填哪个字典、
     * {@code RelationForm} 该绑哪张表单。这部分原先<b>一个字都没有</b>，
     * 于是 AI 建表单时只能用基础控件，配置型控件一律配不出来。
     *
     * <h3>🔴 它防的是「配了、保存成功了、就是不生效」</h3>
     * 这类控件的配置<b>写错键名不会报错</b>。实测踩到过一张示例表单，
     * {@code SystemValue} 配的是 {@code valueType}，而组件读的是 {@code source} ——
     * 界面上安静地显示成默认值「创建人」，没有任何提示。
     * 把键名、受控取值、引用来源写进契约，这类错误才有被拦住的可能。
     *
     * <p>只收录「配错了会静默失效」的配置项，纯样式属性（宽高、占位符）不收 ——
     * 契约越长，模型越容易忽略其中的关键约束。
     */
    private List<PropSpec> props;

    /**
     * 单个配置项的说明
     *
     * 🔴 {@code NON_NULL}：没设置的可选字段不要输出成 {@code null}。
     * 契约 JSON 里这些键是<b>直接不写</b>的，接口若补一堆 null 出去，
     * 「接口返回」与「前端副本」就永远对不上 —— 一致性测试是靠 deep equal 比的。
     * 顺带也让给 LLM 的那份少掉几十行 {@code "source": null} 的噪声。
     */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PropSpec {

        /**
         * 键名，即 {@code rule.props} 里的 key
         *
         * ⚠️ 这是整条记录里<b>最要紧</b>的一项：键名写错不报错，只是安静地不生效。
         */
        private String name;
        /**
         * 值类型：string / number / boolean / array / object / any
         */
        private String type;
        /**
         * 是否必填。不填则该控件无法正常工作
         */
        private Boolean required;
        /**
         * 中文说明，含「配错了会怎样」
         */
        private String desc;
        /**
         * 受控取值。非空表示只能从这里面选
         */
        private List<String> allowedValues;
        /**
         * 取值来源。需要引用别处编码时说明去哪儿取（如「字典管理里的 dict_type」）
         */
        private String source;
        /**
         * 示例值（JSON 片段）
         */
        private String example;

    }

    /**
     * 配置项列表，null 安全
     */
    public List<PropSpec> getPropsOrEmpty() {
        return props != null ? props : Collections.emptyList();
    }

    /**
     * 必填的配置项键名
     */
    public List<String> getRequiredPropNames() {
        return getPropsOrEmpty().stream()
                .filter(item -> Boolean.TRUE.equals(item.getRequired()))
                .map(PropSpec::getName)
                .toList();
    }

    /**
     * 控件能力位
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Capabilities {

        /**
         * 是否是数据字段（规则里有 field，参与表单值）
         */
        private Boolean field;
        /**
         * 能否放进子表控件内部
         */
        private Boolean subTable;
        /**
         * 能否作为列表展示列
         */
        private Boolean listColumn;
        /**
         * 能否作为列表查询条件
         */
        private Boolean searchable;
        /**
         * 能否导出
         */
        private Boolean exportable;
        /**
         * 能否作为列表排序字段
         */
        private Boolean sortable;

    }

    // ==================== 便捷判断。统一在这里做 null 兜底，调用方不必到处判空 ====================

    /**
     * 是否支持。契约缺失该字段时按「支持」处理，避免漏写一个布尔就让控件整体不可用
     */
    public boolean isSupported() {
        return supported == null || supported;
    }

    /**
     * 是否已废弃
     */
    public boolean isDeprecated() {
        return Boolean.TRUE.equals(deprecated);
    }

    /**
     * 是否是数据字段
     */
    public boolean isField() {
        return caps != null && Boolean.TRUE.equals(caps.getField());
    }

    /**
     * 能否放进子表
     */
    public boolean isSubTableAllowed() {
        return caps != null && Boolean.TRUE.equals(caps.getSubTable());
    }

    /**
     * 能否做列表列
     */
    public boolean isListColumnAllowed() {
        return caps != null && Boolean.TRUE.equals(caps.getListColumn());
    }

    /**
     * 能否做查询条件
     */
    public boolean isSearchable() {
        return caps != null && Boolean.TRUE.equals(caps.getSearchable());
    }

    /**
     * 能否导出
     */
    public boolean isExportable() {
        return caps != null && Boolean.TRUE.equals(caps.getExportable());
    }

    /**
     * 能否排序
     */
    public boolean isSortable() {
        return caps != null && Boolean.TRUE.equals(caps.getSortable());
    }

    /**
     * 是否走子表存储
     */
    public boolean isSubTableStorage() {
        return "subTable".equals(storage);
    }

    /**
     * 是否不落库
     */
    public boolean isNoStorage() {
        return storage == null || "none".equals(storage);
    }

    /**
     * 可映射的列类型族，null 安全
     */
    public List<JdbcTypeFamily> getColumnTypesOrEmpty() {
        return columnTypes != null ? columnTypes : Collections.emptyList();
    }

    /**
     * 该控件能否映射到某个类型族的列
     *
     * <p>注意：本方法只做「族是否在允许清单里」的判断，不负责把 JDBC 类型名解析成族 ——
     * 解析要用契约里的有序关键字表，那是 {@link WidgetContractRegistry#acceptsColumnType} 的活。
     * 调用方通常应该用后者，直接传 JDBC 类型名。
     *
     * @param family 类型族
     * @return 允许返回 true
     */
    public boolean acceptsColumnFamily(JdbcTypeFamily family) {
        return family != null && getColumnTypesOrEmpty().contains(family);
    }

}
