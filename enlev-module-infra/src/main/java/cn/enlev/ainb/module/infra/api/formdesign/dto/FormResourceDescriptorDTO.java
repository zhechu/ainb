package cn.enlev.ainb.module.infra.api.formdesign.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 表单的「可授权对象」描述
 *
 * <h3>它存在的理由：把 form-create 的结构知识挡在 infra 里</h3>
 *
 * 权限模块需要知道「这张表单有哪些列、哪些字段、落在哪张物理表、哪些是敏感的」，
 * 但它<b>不应该</b>去解析 formFields / listConf / tableBinding 这三份 JSON ——
 * 那是 form-create 的私有结构，会随设计器版本变，解析逻辑散到第二个模块里之后，
 * 两处迟早对不上，而对不上的表现是「权限配了不生效」，不是报错。
 *
 * 所以 infra 负责解析，只把解析结果（这个扁平描述）交出去。
 *
 * @author 走进新时代
 */
@Data
public class FormResourceDescriptorDTO {

    /**
     * 子表明细字段的标识分隔符 —— 🔴 Java 侧<b>唯一</b>的一份；权威是控件契约的
     * {@code structural.subFieldSeparator}
     *
     * <p>本常量与契约 JSON 之间由 {@code SubFieldSeparatorSingleSourceTest#javaConstantMatchesContract}
     * 钉住（左边是运行期解析 JSON 得到的值、右边是本编译期常量，两边不是同一个符号，故<b>不是</b>恒真断言）；
     * infra 内不许再出现第二份字面量，由同一测试类的源码扫描守（形态 + 数量断言，与台账 #98 同一套路）。
     *
     * <p>{@code BpmTaskServiceImpl.SUB_FIELD_SEPARATOR} 原先是一份<b>独立字面量</b>、与本常量之间零个测试，
     * 现已改成引用本常量（台账 #101(b)）。🔴 当时写在三处文本里的理由是「跨模块不互相依赖内部实现」，
     * 而它与事实不符：bpm 的 pom 本来就依赖 infra、且已经在 import {@code infra.api.formdesign} 下的两个类，
     * 本常量又正是在 api 包里 ⟹ <b>判据不是「跨不跨模块」，是「被引的符号在不在 api 包」</b>。
     * 对照 {@code BpmProcessPermissionChecker} 那几个动作码：它引不到，因为 {@code GrantActionEnum} 在 system 的
     * {@code enums/} 下、{@code ProcessResourceNaming} 在 {@code framework/} 下，那一处才是真正的残余耦合。
     *
     * <p>🔴 改这个值要付的代价（缺一条就会红，别只改一处）：本常量、契约 JSON（改完 cp 到前端）、
     * 以及 infra 与 bpm 各一条「钉字面量」的断言（{@code WidgetContractRegistryTest#testOtherStructuralFacts}、
     * {@code BpmSubFieldSeparatorReferenceTest#separatorValueIsPinned}）。
     * ⚠️ 还有个编译期常量内联的坑：{@code static final String} 会被 javac 内联进引用方的字节码，
     * 改完必须<b>全量</b> {@code mvn install} —— 只重装 infra 的 jar，bpm 的 jar 里仍是旧值。
     * 那种「jar 之间不同步」单测结构上测不到（单测跑在同一次编译里，两边必然同源），
     * 只能靠运行期的诊断工具 {@code DiagnoseNotWorkingTool#diagnoseSubFieldSeparator} 兜，故它保留。
     *
     * <p>放在这个 DTO 上而不是放在解析器里：它是<b>跨模块契约</b>的一部分 ——
     * 权限模块要靠它判断一个标识是不是子表字段，而权限模块看不到解析器
     */
    public static final String SUB_FIELD_SEPARATOR = ".";

    /**
     * 表单编号
     */
    private Long formId;
    /**
     * 表单编码。发布后不可改，跨模块引用一律用它
     */
    private String formCode;
    /**
     * 表单名称，用作资源的展示名
     */
    private String formName;

    /**
     * 数据源编号，0 为主库
     */
    private Long dataSourceConfigId;
    /**
     * 主表名。行级权限规则挂在这张表上
     */
    private String mainTable;
    /**
     * 主表主键列名
     */
    private String mainPk;

    /**
     * 主表的物理列名全集（小写）
     *
     * 给语义槽位推断用。只看表单映射到的列是不够的 —— status、create_dept_id
     * 这类列通常<b>不在</b>表单字段里，而它们恰恰是行过滤最常用的槽位。
     * 读不到表结构（数据源不通）时为空列表，此时推断退化为只看字段映射。
     */
    private List<String> tableColumns = new ArrayList<>();
    /**
     * 后端自动填充的创建人列名
     *
     * 槽位 owner 的首选来源：它是表单设计者<b>显式声明</b>的「这一列存创建人」，
     * 比按列名猜可靠。为空表示该表单没配自动填充创建人
     */
    private String creatorColumn;

    /**
     * 表单详情里的字段（对应 FIELD 类型资源）
     */
    private List<Item> fields = new ArrayList<>();
    /**
     * 列表里的列（对应 COLUMN 类型资源）
     */
    private List<Item> columns = new ArrayList<>();

    /**
     * 一个可授权对象
     */
    @Data
    public static class Item {

        /**
         * 标识。主表字段为 field 本身；子表明细字段为 {@code 子表控件field.明细field}
         *
         * 分隔符就是 {@link FormResourceDescriptorDTO#SUB_FIELD_SEPARATOR}：控件契约的
         * {@code structural.subFieldSeparator} 与 BPM 侧引用的都是同一份，
         * 字段级权限才能在流程侧与表单侧指向同一个东西
         */
        private String key;
        /**
         * 展示名。取控件的 title / 列的 label，为空时回退成 key
         */
        private String label;
        /**
         * 映射到的物理列名，小写。未映射（纯展示控件、布局容器）时为 null
         */
        private String column;
        /**
         * 是否被设计器标了「敏感」（props.sensitive === true）
         *
         * 这是 v9 §5.2「按需创建」的落行条件之一，且默认效果要翻成 DENY
         */
        private boolean sensitive;
        /**
         * 是否配了脱敏规则（props.maskRule 非 NONE）
         *
         * 同样落行，但默认效果保持 ALLOW —— 见 DatasetResourceServiceImpl 的说明
         */
        private boolean masked;

        /**
         * 是否需要为它单独建资源行
         */
        public boolean isGrantworthy() {
            return sensitive || masked;
        }

        /**
         * 是否是子表明细里的字段
         */
        public boolean isSubItem() {
            return key != null && key.contains(SUB_FIELD_SEPARATOR);
        }

    }

}
