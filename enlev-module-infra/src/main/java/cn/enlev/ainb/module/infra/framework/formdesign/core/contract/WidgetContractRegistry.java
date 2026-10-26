package cn.enlev.ainb.module.infra.framework.formdesign.core.contract;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 表单控件契约注册表
 *
 * 启动时从 classpath 加载 form-design/form-widget-contracts.json，之后全模块统一从这里查询控件事实。
 *
 * 为什么要有它：改造前控件的结构性事实散落在前后端各处硬编码，最典型的是子表控件类型 "group" —— 它在
 * 两个仓库里重复了 6 处（本类的 {@link #getSubTableWidgetType()} 现在是后端侧的唯一来源），
 * 每处都靠注释提醒「必须与另一处保持一致」。这种结构漏改一处不会报错，只会静默出错。
 *
 * 加载失败直接抛异常让应用起不来，不做降级：契约是整个表单模块的地基，
 * 带着一份空契约启动只会把问题推迟到用户发布表单时才炸，那时更难定位。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class WidgetContractRegistry {

    /**
     * 契约文件在 classpath 下的位置
     */
    private static final String CONTRACT_RESOURCE = "form-design/form-widget-contracts.json";

    /**
     * type → 契约。用 LinkedHashMap 保持 JSON 里的声明顺序，便于接口返回给前端时顺序稳定
     */
    private Map<String, WidgetContract> contractByType = Collections.emptyMap();

    /**
     * 结构性事实
     */
    private StructuralFacts structural;

    /**
     * 契约版本号
     */
    private Integer contractVersion;

    /**
     * JDBC 类型族的匹配规则，顺序即优先级
     *
     * 顺序敏感，原因见契约文件里 jdbcTypeFamilies 的说明。
     */
    private List<JdbcTypeFamilyRule> jdbcTypeFamilyRules = Collections.emptyList();

    @PostConstruct
    public void init() {
        String json = ResourceUtil.readUtf8Str(CONTRACT_RESOURCE);
        if (StrUtil.isBlank(json)) {
            throw new IllegalStateException("表单控件契约文件为空：" + CONTRACT_RESOURCE);
        }
        ContractFile file = JsonUtils.parseObject(json, ContractFile.class);
        if (file == null || file.getWidgets() == null || file.getWidgets().isEmpty()) {
            throw new IllegalStateException("表单控件契约文件解析失败或不含任何控件：" + CONTRACT_RESOURCE);
        }

        Map<String, WidgetContract> map = new LinkedHashMap<>();
        Set<String> duplicated = new HashSet<>();
        for (WidgetContract contract : file.getWidgets()) {
            if (StrUtil.isBlank(contract.getType())) {
                throw new IllegalStateException("表单控件契约存在没有 type 的条目：" + CONTRACT_RESOURCE);
            }
            if (map.put(contract.getType(), contract) != null) {
                duplicated.add(contract.getType());
            }
        }
        // 重复的 type 会让后放入的悄悄覆盖前一条，是最难查的一类配置错误，直接拦住
        if (!duplicated.isEmpty()) {
            throw new IllegalStateException("表单控件契约存在重复的 type：" + duplicated);
        }

        this.contractByType = Collections.unmodifiableMap(map);
        this.structural = file.getStructural() != null ? file.getStructural() : new StructuralFacts();
        this.contractVersion = file.getContractVersion();
        this.jdbcTypeFamilyRules = file.getJdbcTypeFamilies() != null
                ? List.copyOf(file.getJdbcTypeFamilies()) : Collections.emptyList();

        selfCheck();
        log.info("[init][表单控件契约加载完成，版本 {}，控件 {} 个]", contractVersion, contractByType.size());
    }

    /**
     * 自检：结构性事实必须与控件清单自洽
     *
     * 这些检查不是防御用户输入，而是防我们自己改契约时改漏。
     * 例如把 subTableWidgetType 改成一个不存在的 type，或改成一个 supported=false 的控件 ——
     * 两者都会让子表功能整体失效，且在运行时表现为「子表字段莫名其妙收集不到」。
     */
    private void selfCheck() {
        String subTableType = getSubTableWidgetType();
        if (StrUtil.isBlank(subTableType)) {
            throw new IllegalStateException("表单控件契约缺少 structural.subTableWidgetType");
        }
        WidgetContract subTable = contractByType.get(subTableType);
        if (subTable == null) {
            throw new IllegalStateException(
                    "structural.subTableWidgetType 指向了不存在的控件类型：" + subTableType);
        }
        if (!subTable.isSupported()) {
            throw new IllegalStateException(
                    "structural.subTableWidgetType 指向的控件被标记为不支持：" + subTableType);
        }
        if (!subTable.isSubTableStorage()) {
            throw new IllegalStateException(
                    "structural.subTableWidgetType 指向的控件 storage 不是 subTable：" + subTableType);
        }
        if (StrUtil.isBlank(getSubFieldSeparator())) {
            throw new IllegalStateException("表单控件契约缺少 structural.subFieldSeparator");
        }
        if (StrUtil.isBlank(getNestedRulePath())) {
            throw new IllegalStateException("表单控件契约缺少 structural.nestedRulePath");
        }
        // 类型族规则：每个族都要有关键字，且 family 必须是已知枚举值。
        // 拼错一个族名（如把 DATETIME 写成 DATE_TIME）会让该族永远匹配不上，
        // 表现为「日期列怎么都映射不上去」，从现象很难反推到契约拼写错误
        if (jdbcTypeFamilyRules.isEmpty()) {
            throw new IllegalStateException("表单控件契约缺少 jdbcTypeFamilies");
        }
        for (JdbcTypeFamilyRule rule : jdbcTypeFamilyRules) {
            if (rule.getFamily() == null) {
                throw new IllegalStateException("jdbcTypeFamilies 里存在无法识别的 family（检查是否拼错）");
            }
            if (rule.getKeywords() == null || rule.getKeywords().isEmpty()) {
                throw new IllegalStateException(
                        "jdbcTypeFamilies 里的族没有关键字：" + rule.getFamily());
            }
        }
        // 契约里所有控件引用的族，都必须在类型族规则里有定义，否则那个族永远解析不出来
        Set<JdbcTypeFamily> definedFamilies = new HashSet<>();
        jdbcTypeFamilyRules.forEach(rule -> definedFamilies.add(rule.getFamily()));
        for (WidgetContract contract : contractByType.values()) {
            for (JdbcTypeFamily family : contract.getColumnTypesOrEmpty()) {
                if (!definedFamilies.contains(family)) {
                    throw new IllegalStateException("控件 " + contract.getType()
                            + " 引用的类型族 " + family + " 在 jdbcTypeFamilies 里没有定义");
                }
            }
        }
    }

    // ==================== JDBC 类型族解析 ====================

    /**
     * 按 JDBC 类型名解析所属类型族
     *
     * 按契约里 jdbcTypeFamilies 的声明顺序做首个命中匹配。顺序敏感，
     * 原因见契约文件里的说明（"TIMESTAMP" 含 "TIME"、"BIGINT" 含 "INT" 等）。
     *
     * @param jdbcTypeName JDBC 类型名，大小写不敏感
     * @return 类型族；无法识别返回 null
     */
    public JdbcTypeFamily resolveJdbcTypeFamily(String jdbcTypeName) {
        if (StrUtil.isBlank(jdbcTypeName)) {
            return null;
        }
        String upper = jdbcTypeName.toUpperCase();
        for (JdbcTypeFamilyRule rule : jdbcTypeFamilyRules) {
            for (String keyword : rule.getKeywords()) {
                if (upper.contains(keyword)) {
                    return rule.getFamily();
                }
            }
        }
        return null;
    }

    /**
     * 一列有两个候选类型名时，挑<b>契约认得的那个</b>；都认得就挑驱动报的那个
     *
     * <p>为什么会有两个名字：读表结构走的是 JDBC 元数据，同一列因此有两种说法 ——
     * 驱动报的类型名（{@code DatabaseMetaData} 的 {@code TYPE_NAME}，如 {@code JSON}、{@code ENUM}）
     * 与 {@code java.sql.Types} 那个码对应的枚举名（如 {@code LONGVARCHAR}、{@code CHAR}）。
     * 后者是一份<b>压扁过的</b>词表：MySQL 的 json 列与 text 列同为 {@code LONGVARCHAR}，
     * 「这列是 json」这个事实只在驱动名里。
     *
     * <p>🔴 而这个事实丢了会炸在写库那一刻：{@code FormValueSerializer#resolveMode} 只在
     * {@code JdbcTypeFamily.JSON} 时把多选存成 JSON 串，否则拼逗号串，
     * 于是 {@code 微生物,重金属} 被写进 json 列 ——
     * {@code Data truncation: Invalid JSON text: "Invalid value." at position 0}，
     * 用户看到的是一句「服务器错误,请联系管理员!」。
     * 实测于表单 880510、物理表 {@code biz_lab_sample} 的 {@code test_items} 列。
     *
     * <p>为什么不是无脑用驱动名：契约关键字表认不出的名字（{@code ENUM}、{@code GEOMETRY}）
     * 解析出 null 族，下游一律按「未知 ⟹ 放宽」处理，反倒丢了枚举名本来给出的信息
     * （enum 列的枚举名是 {@code CHAR}，归 STRING 族，超长值还能被 {@code ValueLengthGuard} 拦住）。
     * 所以判据是「谁更具体就用谁」，而「具体」的唯一裁判就是本注册表持有的那张关键字表。
     *
     * @param driverTypeName 驱动报的类型名，可为 null
     * @param jdbcEnumName   {@code java.sql.Types} 那侧的枚举名，可为 null
     * @return 更该写进 {@code tableBinding.fieldMapping.jdbcType} 的那个
     */
    public String resolveColumnTypeName(String driverTypeName, String jdbcEnumName) {
        return resolveJdbcTypeFamily(driverTypeName) != null ? driverTypeName : jdbcEnumName;
    }

    /**
     * 判断某个控件能否映射到某个 JDBC 类型的列
     *
     * @param type         控件类型
     * @param jdbcTypeName JDBC 类型名
     * @return 兼容返回 true
     */
    public boolean acceptsColumnType(String type, String jdbcTypeName) {
        WidgetContract contract = get(type);
        if (contract == null) {
            // 契约未覆盖的控件不做类型限制，理由同 isSupported
            return true;
        }
        JdbcTypeFamily family = resolveJdbcTypeFamily(jdbcTypeName);
        if (family == null) {
            // 类型识别不出来时放过：宁可漏判也不要因为一个没见过的数据库类型
            // 把本来能用的映射拦掉。真正的类型错误会在写入时由数据库自己报出来
            return true;
        }
        return contract.acceptsColumnFamily(family);
    }

    // ==================== 结构性事实 ====================

    /**
     * 子表控件的类型
     *
     * 后端侧的唯一来源。它的内部规则挂在 {@link #getNestedRulePath()} 下，而不是布局容器那样的 children。
     */
    public String getSubTableWidgetType() {
        return structural != null ? structural.getSubTableWidgetType() : null;
    }

    /**
     * 子表内部字段拼层级 key 时的分隔符
     *
     * 与前端 parseFormFields 生成的 key 格式必须一致，否则字段权限配了却不生效。
     */
    public String getSubFieldSeparator() {
        return structural != null ? structural.getSubFieldSeparator() : null;
    }

    /**
     * 子表控件内部规则所在的属性路径
     */
    public String getNestedRulePath() {
        return structural != null ? structural.getNestedRulePath() : null;
    }

    /**
     * 布局容器类型集合
     */
    public List<String> getLayoutContainerTypes() {
        if (structural == null || structural.getLayoutContainerTypes() == null) {
            return Collections.emptyList();
        }
        return structural.getLayoutContainerTypes();
    }

    /**
     * 判断某个类型是否为子表控件
     */
    public boolean isSubTableWidget(String type) {
        return type != null && type.equals(getSubTableWidgetType());
    }

    /**
     * 值是 HTML 的控件类型集合
     */
    public List<String> getRichTextWidgetTypes() {
        if (structural == null || structural.getRichTextWidgetTypes() == null) {
            return Collections.emptyList();
        }
        return structural.getRichTextWidgetTypes();
    }

    /**
     * 判断某个类型的值是否为 HTML
     *
     * 富文本值要走两条别的字段不走的处理：内联 base64 图片转存、按白名单清洗标签。
     * 判断依据放在契约里而不是 guard 里硬编码 —— 将来若换编辑器控件，
     * 漏改硬编码的表现是「新控件的富文本既不转存也不清洗」，而且不报错。
     */
    public boolean isRichTextWidget(String type) {
        return type != null && getRichTextWidgetTypes().contains(type);
    }

    /**
     * 判断某个类型的值是否为文件（URL 或文件对象）
     */
    public boolean isFileWidget(String type) {
        WidgetContract contract = get(type);
        return contract != null && WidgetContract.VALUE_TYPE_FILE.equals(contract.getValueType());
    }

    /**
     * storage=delimited 的控件落库时用的分隔符
     *
     * 它同时是拆分的依据 —— 写入时用它拼串，读取时用它拆回数组。
     * 前后端读同一份声明，两侧不一致的表现是「存进去 3 项、读回来 2 项」。
     */
    public String getDelimitedSeparator() {
        return structural != null ? structural.getDelimitedSeparator() : null;
    }

    /**
     * 契约版本号
     */
    public Integer getContractVersion() {
        return contractVersion;
    }

    // ==================== 契约查询 ====================

    /**
     * 按类型取契约
     *
     * @param type 控件类型
     * @return 契约；未登记返回 null
     */
    public WidgetContract get(String type) {
        return type == null ? null : contractByType.get(type);
    }

    /**
     * 全部契约，按 JSON 里的声明顺序
     */
    public List<WidgetContract> listAll() {
        return List.copyOf(contractByType.values());
    }

    /**
     * 某个类型是否被本模块支持
     *
     * 未登记的类型返回 true —— 契约可能还没补全，此时不该把用户的表单卡死在发布环节。
     * 真正要拦的是「已登记且明确标了不支持」的控件，那是我们知道会出错的情况。
     */
    public boolean isSupported(String type) {
        WidgetContract contract = get(type);
        return contract == null || contract.isSupported();
    }

    /**
     * 某个类型是否已废弃
     */
    public boolean isDeprecated(String type) {
        WidgetContract contract = get(type);
        return contract != null && contract.isDeprecated();
    }

    // ==================== 契约文件的反序列化结构 ====================

    /**
     * 契约文件根结构
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ContractFile {

        private Integer contractVersion;
        private StructuralFacts structural;
        private List<JdbcTypeFamilyRule> jdbcTypeFamilies;
        private List<WidgetContract> widgets;

    }

    /**
     * 单个 JDBC 类型族的匹配规则
     *
     * 在契约文件的数组里，位置即优先级。
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JdbcTypeFamilyRule {

        /**
         * 类型族
         */
        private JdbcTypeFamily family;
        /**
         * 匹配关键字，对 JDBC 类型名大写后做包含匹配
         */
        private List<String> keywords;

    }

    /**
     * 跨前后端的结构性事实
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StructuralFacts {

        /**
         * 子表控件类型
         */
        private String subTableWidgetType;
        /**
         * 子表内部字段的层级 key 分隔符
         */
        private String subFieldSeparator;
        /**
         * 布局容器类型
         */
        private List<String> layoutContainerTypes;
        /**
         * 嵌套规则所在的属性路径
         */
        private String nestedRulePath;
        /**
         * 值是 HTML 的控件类型
         */
        private List<String> richTextWidgetTypes;
        /**
         * storage=delimited 的控件落库时用的分隔符
         */
        private String delimitedSeparator;

    }

}
