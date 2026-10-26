package cn.enlev.ainb.module.infra.framework.formdesign.core.contract;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link WidgetContractRegistry} 的单元测试
 *
 * 注册表是「控件即契约」的入口：全模块的控件事实都从它读。它自己出错的后果是全局性的，
 * 所以这里既要覆盖查询语义，也要覆盖启动自检 —— 自检拦的是我们改契约时改漏，
 * 例如把 subTableWidgetType 指到一个不存在或不支持的控件上，那会让子表功能整体静默失效。
 *
 * <p>本测试加载的是真实的契约文件（classpath 下的 form-design/form-widget-contracts.json），
 * 不用桩数据 —— 契约文件本身的自洽性正是要被守住的东西。
 *
 * @author 走进新时代
 */
class WidgetContractRegistryTest {

    private WidgetContractRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new WidgetContractRegistry();
        registry.init();
    }

    // ==================== 加载与自检 ====================

    @Test
    @DisplayName("真实契约文件能正常加载，且通过全部自检")
    void testLoadRealContractFile() {
        // init() 里的 selfCheck 不抛异常即通过
        assertNotNull(registry.getContractVersion());
        assertFalse(registry.listAll().isEmpty());
    }

    @Test
    @DisplayName("契约文件里不存在重复的 type")
    void testNoDuplicateTypes() {
        List<WidgetContract> all = registry.listAll();
        long distinctCount = all.stream().map(WidgetContract::getType).distinct().count();
        assertEquals(all.size(), distinctCount, "契约里有重复的 type，后放入的会静默覆盖前一条");
    }

    @Test
    @DisplayName("每个契约都声明了 type、valueType、storage 与能力位")
    void testEveryContractIsComplete() {
        for (WidgetContract contract : registry.listAll()) {
            assertNotNull(contract.getType(), "存在没有 type 的契约");
            assertNotNull(contract.getValueType(), contract.getType() + " 缺少 valueType");
            assertNotNull(contract.getStorage(), contract.getType() + " 缺少 storage");
            assertNotNull(contract.getCaps(), contract.getType() + " 缺少 caps");
        }
    }

    @Test
    @DisplayName("⚠️ 不落库的控件不得声明可映射列类型，反之也不得为空")
    void testStorageAndColumnTypesConsistent() {
        for (WidgetContract contract : registry.listAll()) {
            boolean hasColumnTypes = !contract.getColumnTypesOrEmpty().isEmpty();
            if (contract.isNoStorage() || contract.isSubTableStorage()) {
                // 展示型/布局型不落库，子表走独立明细表，两者都不该映射主表列
                assertFalse(hasColumnTypes,
                        contract.getType() + " 声明了不落库或走子表，却又声明了 columnTypes");
            } else if (contract.isSupported()) {
                // 落库的受支持控件必须说明能映射到什么列，否则发布校验无从判断类型兼容性
                assertTrue(hasColumnTypes,
                        contract.getType() + " 需要落库却没声明 columnTypes");
            }
        }
    }

    @Test
    @DisplayName("⚠️ 非数据字段的控件不得声明列表列/查询/导出/排序能力")
    void testNonFieldWidgetsHaveNoDataCapabilities() {
        for (WidgetContract contract : registry.listAll()) {
            if (contract.isField()) {
                continue;
            }
            String type = contract.getType();
            assertFalse(contract.isListColumnAllowed(), type + " 不是数据字段却可做列表列");
            assertFalse(contract.isSearchable(), type + " 不是数据字段却可做查询条件");
            assertFalse(contract.isExportable(), type + " 不是数据字段却可导出");
            assertFalse(contract.isSortable(), type + " 不是数据字段却可排序");
        }
    }

    @Test
    @DisplayName("⚠️ 可排序必须蕴含可做列表列（排序作用在列表查询上）")
    void testSortableImpliesListColumn() {
        for (WidgetContract contract : registry.listAll()) {
            if (contract.isSortable()) {
                assertTrue(contract.isListColumnAllowed(),
                        contract.getType() + " 声明可排序，却不能做列表列");
            }
        }
    }

    // ==================== 结构性事实 ====================

    @Test
    @DisplayName("子表控件类型是 group，且指向一个受支持的 subTable 存储控件")
    void testSubTableWidgetType() {
        assertEquals("group", registry.getSubTableWidgetType());
        assertTrue(registry.isSubTableWidget("group"));
        assertFalse(registry.isSubTableWidget("tableForm"));
        assertFalse(registry.isSubTableWidget(null));

        WidgetContract subTable = registry.get(registry.getSubTableWidgetType());
        assertTrue(subTable.isSupported());
        assertTrue(subTable.isSubTableStorage());
    }

    @Test
    @DisplayName("子字段分隔符与嵌套规则路径都有值")
    void testOtherStructuralFacts() {
        assertEquals(".", registry.getSubFieldSeparator());
        assertEquals("props.rule", registry.getNestedRulePath());
        assertFalse(registry.getLayoutContainerTypes().isEmpty());
    }

    // ==================== 查询语义 ====================

    @Test
    @DisplayName("未登记的类型按「支持」处理，不把用户表单卡在发布环节")
    void testUnknownTypeIsSupported() {
        assertNull(registry.get("SomeBrandNewWidget"));
        assertTrue(registry.isSupported("SomeBrandNewWidget"));
        assertFalse(registry.isDeprecated("SomeBrandNewWidget"));
        assertTrue(registry.isSupported(null));
    }

    @Test
    @DisplayName("⚠️ tableForm 与 subForm 明确标为不支持：它们会静默丢明细数据")
    void testUnsupportedSubFormVariants() {
        assertFalse(registry.isSupported("tableForm"));
        assertFalse(registry.isSupported("subForm"));
        // 内核内置的上传与富文本已被自建控件替代，同样标为不支持作第二道防线
        assertFalse(registry.isSupported("upload"));
        // ApiSelect 已在 B3 删除，契约条目保留并标为不支持作第二道防线
        assertFalse(registry.isSupported("ApiSelect"));
        // UserSelect / DeptSelect 已在 B4 删除，由 OrgPicker 替代
        assertFalse(registry.isSupported("UserSelect"));
        assertFalse(registry.isSupported("DeptSelect"));
        assertFalse(registry.isSupported("fcEditor"));
    }

    @Test
    @DisplayName("待删除的旧控件被标记为 deprecated")
    void testDeprecatedWidgets() {
        assertTrue(registry.isDeprecated("UserSelect"));
        assertTrue(registry.isDeprecated("DeptSelect"));
        assertTrue(registry.isDeprecated("ApiSelect"));
        // 新增的合并控件必须已登记且不是废弃品。
        // ⚠️ 只断言 isDeprecated 为 false 是空洞的 —— 未登记的类型同样返回 false，
        // 那样契约里漏了这两个控件也测不出来（本次就踩到了）
        assertNotNull(registry.get("OrgPicker"), "OrgPicker 必须已登记在契约里");
        assertNotNull(registry.get("SystemValue"), "SystemValue 必须已登记在契约里");
        assertFalse(registry.isDeprecated("OrgPicker"));
        assertFalse(registry.isDeprecated("SystemValue"));
        // 有替代关系的才标废弃，普通控件不该被误标
        assertFalse(registry.isDeprecated("input"));
        assertFalse(registry.isDeprecated("group"));
    }

    @Test
    @DisplayName("listAll 保持契约文件里的声明顺序，且不可被外部修改")
    void testListAllIsStableAndImmutable() {
        List<WidgetContract> first = registry.listAll();
        List<WidgetContract> second = registry.listAll();
        assertEquals(first.stream().map(WidgetContract::getType).toList(),
                second.stream().map(WidgetContract::getType).toList());
        assertThrows(UnsupportedOperationException.class,
                () -> registry.listAll().add(new WidgetContract()));
    }

    // ==================== JDBC 类型族解析 ====================
    //
    // 匹配方式是「关键字包含」，存在一批必须靠顺序才能区分的陷阱对。顺序一旦被人
    // 「整理」成字母序或枚举自然序，这些类型就会静默归错族，表现为本可映射的列被判为
    // 不兼容、或反过来放过了不兼容的映射。每一对陷阱都要有用例钉住。

    @Test
    @DisplayName("⚠️ TIMESTAMP 归 DATETIME 而非 TIME（\"TIMESTAMP\".contains(\"TIME\") 为真）")
    void testTimestampNotResolvedAsTime() {
        assertEquals(JdbcTypeFamily.DATETIME, registry.resolveJdbcTypeFamily("TIMESTAMP"));
        assertEquals(JdbcTypeFamily.DATETIME, registry.resolveJdbcTypeFamily("timestamp"));
    }

    @Test
    @DisplayName("⚠️ DATETIME 归 DATETIME 而非 DATE（\"DATETIME\".contains(\"DATE\") 为真）")
    void testDatetimeNotResolvedAsDate() {
        assertEquals(JdbcTypeFamily.DATETIME, registry.resolveJdbcTypeFamily("DATETIME"));
    }

    @Test
    @DisplayName("⚠️ BIGINT 归 LONG 而非 INTEGER（\"BIGINT\".contains(\"INT\") 为真）")
    void testBigintNotResolvedAsInteger() {
        assertEquals(JdbcTypeFamily.LONG, registry.resolveJdbcTypeFamily("BIGINT"));
        assertEquals(JdbcTypeFamily.LONG, registry.resolveJdbcTypeFamily("bigint unsigned"));
    }

    @Test
    @DisplayName("⚠️ LONGVARCHAR 归 TEXT 而非 STRING（\"LONGVARCHAR\".contains(\"VARCHAR\") 为真）")
    void testLongVarcharNotResolvedAsString() {
        assertEquals(JdbcTypeFamily.TEXT, registry.resolveJdbcTypeFamily("LONGVARCHAR"));
    }

    @Test
    @DisplayName("⚠️ VARBINARY 归 BINARY 而非 STRING")
    void testVarbinaryNotResolvedAsString() {
        assertEquals(JdbcTypeFamily.BINARY, registry.resolveJdbcTypeFamily("VARBINARY"));
        assertEquals(JdbcTypeFamily.BINARY, registry.resolveJdbcTypeFamily("BLOB"));
    }

    @Test
    @DisplayName("纯 DATE、纯 TIME 与其余各族")
    void testRemainingFamilies() {
        assertEquals(JdbcTypeFamily.DATE, registry.resolveJdbcTypeFamily("DATE"));
        assertEquals(JdbcTypeFamily.TIME, registry.resolveJdbcTypeFamily("TIME"));
        assertEquals(JdbcTypeFamily.STRING, registry.resolveJdbcTypeFamily("VARCHAR"));
        assertEquals(JdbcTypeFamily.STRING, registry.resolveJdbcTypeFamily("VARCHAR2"));
        assertEquals(JdbcTypeFamily.TEXT, registry.resolveJdbcTypeFamily("LONGTEXT"));
        assertEquals(JdbcTypeFamily.TEXT, registry.resolveJdbcTypeFamily("CLOB"));
        assertEquals(JdbcTypeFamily.INTEGER, registry.resolveJdbcTypeFamily("TINYINT"));
        assertEquals(JdbcTypeFamily.DECIMAL, registry.resolveJdbcTypeFamily("NUMERIC"));
        assertEquals(JdbcTypeFamily.BOOLEAN, registry.resolveJdbcTypeFamily("BOOLEAN"));
        assertEquals(JdbcTypeFamily.JSON, registry.resolveJdbcTypeFamily("JSONB"));
    }

    @Test
    @DisplayName("空值与无法识别的类型返回 null")
    void testUnresolvableJdbcType() {
        assertNull(registry.resolveJdbcTypeFamily(null));
        assertNull(registry.resolveJdbcTypeFamily(""));
        assertNull(registry.resolveJdbcTypeFamily("   "));
        assertNull(registry.resolveJdbcTypeFamily("SOME_VENDOR_SPECIFIC_TYPE"));
    }

    // ==================== 一列两个候选类型名时挑哪个（B17） ====================

    // JDBC 元数据给同一列两种说法：驱动报的 TYPE_NAME 与 java.sql.Types 那侧的枚举名。
    // 后者是压扁过的词表 —— MySQL 的 json 与 text 同为 LONGVARCHAR，「这列是 json」只有前者知道。
    // 而这个事实丢了会炸在写库那一刻：FormValueSerializer 只在 JSON 族时把多选存成 JSON 串，
    // 归到 TEXT 族就拼逗号串，「微生物,重金属」写进 json 列被 MySQL 拒（Invalid JSON text）。

    @Test
    @DisplayName("🔴 json 列：驱动名 JSON 胜出，枚举名 LONGVARCHAR 会把「是 json」这件事抹掉")
    void testJsonColumnPrefersDriverTypeName() {
        assertEquals("JSON", registry.resolveColumnTypeName("JSON", "LONGVARCHAR"));
        // 挑出来的名字必须真能归到 JSON 族，否则这一挑等于白挑
        assertEquals(JdbcTypeFamily.JSON,
                registry.resolveJdbcTypeFamily(registry.resolveColumnTypeName("JSON", "LONGVARCHAR")));
    }

    @Test
    @DisplayName("契约认不得驱动名时沿用枚举名：ENUM 归 STRING 族的信息不能丢")
    void testUnknownDriverTypeNameFallsBackToEnumName() {
        // enum 列：驱动报 ENUM（关键字表里没有），枚举名 CHAR 归 STRING 族。
        // 无脑用驱动名的话这一列会变成「未知族」，ValueLengthGuard 的超长拦截随之失效
        assertEquals("CHAR", registry.resolveColumnTypeName("ENUM", "CHAR"));
        assertEquals(JdbcTypeFamily.STRING, registry.resolveJdbcTypeFamily("CHAR"));
        assertEquals("OTHER", registry.resolveColumnTypeName("GEOMETRY", "OTHER"));
    }

    @Test
    @DisplayName("两个名字归同一族时随便挑哪个都不影响下游：常见列一律原样")
    void testSameFamilyColumnsUnaffected() {
        assertEquals("VARCHAR", registry.resolveColumnTypeName("VARCHAR", "VARCHAR"));
        assertEquals("TEXT", registry.resolveColumnTypeName("TEXT", "LONGVARCHAR"));
        assertEquals("BIGINT", registry.resolveColumnTypeName("BIGINT", "BIGINT"));
        assertEquals("DATETIME", registry.resolveColumnTypeName("DATETIME", "TIMESTAMP"));
        assertEquals("BIT", registry.resolveColumnTypeName("BIT", "BIT"));
    }

    @Test
    @DisplayName("驱动没报类型名时退回枚举名，两个都没有就是 null")
    void testMissingDriverTypeName() {
        assertEquals("LONGVARCHAR", registry.resolveColumnTypeName(null, "LONGVARCHAR"));
        assertEquals("VARCHAR", registry.resolveColumnTypeName("  ", "VARCHAR"));
        assertNull(registry.resolveColumnTypeName(null, null));
    }

    // ==================== 列类型兼容 ====================

    @Test
    @DisplayName("单行输入可映射到 varchar/text，不可映射到 int")
    void testInputColumnTypeCompatibility() {
        assertTrue(registry.acceptsColumnType("input", "VARCHAR"));
        assertTrue(registry.acceptsColumnType("input", "TEXT"));
        assertFalse(registry.acceptsColumnType("input", "INT"));
        assertFalse(registry.acceptsColumnType("input", "DATETIME"));
    }

    @Test
    @DisplayName("数字输入可映射到 int/bigint/decimal，不可映射到 varchar")
    void testNumberColumnTypeCompatibility() {
        assertTrue(registry.acceptsColumnType("inputNumber", "INT"));
        assertTrue(registry.acceptsColumnType("inputNumber", "BIGINT"));
        assertTrue(registry.acceptsColumnType("inputNumber", "DECIMAL"));
        assertFalse(registry.acceptsColumnType("inputNumber", "VARCHAR"));
    }

    @Test
    @DisplayName("富文本只允许 TEXT 族：映射到 varchar 几乎必然截断")
    void testEditorOnlyAcceptsText() {
        assertTrue(registry.acceptsColumnType("Editor", "TEXT"));
        assertTrue(registry.acceptsColumnType("Editor", "LONGTEXT"));
        assertFalse(registry.acceptsColumnType("Editor", "VARCHAR"));
    }

    @Test
    @DisplayName("日期控件可映射到 date/datetime，也允许存字符串或时间戳")
    void testDatePickerColumnTypeCompatibility() {
        assertTrue(registry.acceptsColumnType("datePicker", "DATE"));
        assertTrue(registry.acceptsColumnType("datePicker", "DATETIME"));
        assertTrue(registry.acceptsColumnType("datePicker", "TIMESTAMP"));
        assertTrue(registry.acceptsColumnType("datePicker", "VARCHAR"));
        assertTrue(registry.acceptsColumnType("datePicker", "BIGINT"));
        assertFalse(registry.acceptsColumnType("datePicker", "DECIMAL"));
    }

    @Test
    @DisplayName("识别不出的列类型、未登记的控件一律放过")
    void testUnknownPassesThrough() {
        assertTrue(registry.acceptsColumnType("input", "SOME_VENDOR_TYPE"));
        assertTrue(registry.acceptsColumnType("input", null));
        assertTrue(registry.acceptsColumnType("SomeBrandNewWidget", "VARCHAR"));
    }

    // ==================== 契约对象的 null 兜底 ====================

    @Test
    @DisplayName("契约字段缺失时便捷判断不抛异常")
    void testContractNullSafety() {
        WidgetContract empty = new WidgetContract();
        // supported 缺省按支持处理
        assertTrue(empty.isSupported());
        assertFalse(empty.isDeprecated());
        assertFalse(empty.isField());
        assertFalse(empty.isSubTableAllowed());
        assertFalse(empty.isListColumnAllowed());
        assertFalse(empty.isSearchable());
        assertFalse(empty.isExportable());
        assertFalse(empty.isSortable());
        assertTrue(empty.isNoStorage());
        assertFalse(empty.isSubTableStorage());
        assertTrue(empty.getColumnTypesOrEmpty().isEmpty());
        // 不落库的控件不接受任何类型族
        assertFalse(empty.acceptsColumnFamily(JdbcTypeFamily.STRING));
        assertFalse(empty.acceptsColumnFamily(null));
        // props 缺失时也不能抛
        assertTrue(empty.getPropsOrEmpty().isEmpty());
        assertTrue(empty.getRequiredPropNames().isEmpty());
    }

    // ==================== 控件配置项（契约 v2） ====================

    @Test
    @DisplayName("🔴 配置型控件必须有 props —— 没有它 AI 只能用基础控件")
    void testConfigurableWidgetsHaveProps() {
        // 这几个是「不配就没法用」的典型：字典选择器不填 dictType 就是个空下拉，
        // 关联表单不填 targetFormCode 就点不出任何东西。
        // 契约 v2 之前它们一条 props 都没有，于是 LLM 无从得知该配什么
        for (String type : List.of("DictSelect", "RelationForm", "RemotePicker",
                "SystemValue", "BillNumberInput", "Formula", "CodeImage")) {
            WidgetContract contract = registry.get(type);
            assertNotNull(contract, "契约里没有控件 " + type);
            assertFalse(contract.getPropsOrEmpty().isEmpty(),
                    "控件 " + type + " 缺少 props 定义，AI 无从得知它该怎么配");
        }
    }

    @Test
    @DisplayName("🔴 SystemValue 的键名是 source 而不是 valueType（实测踩过）")
    void testSystemValueSourceKey() {
        // 实测发现一张示例表单把它配成了 valueType，而组件读的是 source ——
        // 界面上安静地回落成默认的「创建人」，没有任何提示。
        // 契约把键名钉死，才有被拦住的可能
        WidgetContract contract = registry.get("SystemValue");
        assertNotNull(contract);
        List<String> names = contract.getPropsOrEmpty().stream()
                .map(WidgetContract.PropSpec::getName).toList();
        assertTrue(names.contains("source"), "SystemValue 的取值来源键名必须是 source，实得：" + names);
        assertFalse(names.contains("valueType"),
                "valueType 是错误的键名，不能写进契约 —— 契约里写什么，模型就会照着配");
        assertTrue(contract.getRequiredPropNames().contains("source"), "source 应当是必填");
    }

    @Test
    @DisplayName("props 自身要自洽：键名唯一、受控取值不为空数组、说明不能敷衍")
    void testPropSpecsWellFormed() {
        int total = 0;
        for (WidgetContract contract : registry.listAll()) {
            List<String> names = new java.util.ArrayList<>();
            for (WidgetContract.PropSpec spec : contract.getPropsOrEmpty()) {
                total++;
                String where = "控件 " + contract.getType() + " 的配置项 " + spec.getName();
                assertNotNull(spec.getName(), where + "：键名不能为空");
                assertNotNull(spec.getType(), where + "：缺少类型");
                assertTrue(spec.getDesc() != null && spec.getDesc().length() >= 4,
                        where + "：说明太短，契约是给模型看的");
                // 空数组的语义是「一个合法值都没有」，会让校验把所有配置判成非法
                assertTrue(spec.getAllowedValues() == null || !spec.getAllowedValues().isEmpty(),
                        where + "：allowedValues 不能是空数组");
                assertFalse(names.contains(spec.getName()), where + "：键名重复");
                names.add(spec.getName());
            }
        }
        // 防止这条测试在 props 全被删光之后变成永远通过的空测试
        assertTrue(total > 50, "契约里只有 " + total + " 条 props，这条测试等于没跑");
    }

}
