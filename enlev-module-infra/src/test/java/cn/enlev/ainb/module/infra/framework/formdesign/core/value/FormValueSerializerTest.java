package cn.enlev.ainb.module.infra.framework.formdesign.core.value;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.FieldMapping;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.SubTableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.TableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.contract.WidgetContractRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Spy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 落库形态转换的测试
 *
 * 守的是一件在本类出现之前完全没人做的事：**数组值怎么进到一列里去**。
 *
 * 改造前的真实表现（用真实接口验证过）：多选控件映射到 varchar，提交 ["a","b"] 得到
 *
 * <pre>
 * Incorrect string value: '\xAC\xED\x00\x05sr...' for column 'reason'
 * </pre>
 *
 * 那串 \xAC\xED 是 Java 序列化的魔数 —— MySQL 驱动把 ArrayList 当 Serializable
 * 序列化后往列里写。字符列上报 500，能存任意字节的列上则是静默写进一段乱码。
 *
 * 这些用例里有相当一部分是【成对】的（写进去再读回来），因为只做一个方向的话，
 * 症状会从「报错」变成「看起来存进去了但打开是乱的」，更难发现。
 *
 * @author 走进新时代
 */
public class FormValueSerializerTest extends BaseMockitoUnitTest {

    @InjectMocks
    private FormValueSerializer serializer;

    /** 用真实契约注册表：要守的正是「按契约里的 storage 决定怎么存」这条链路 */
    @Spy
    private WidgetContractRegistry contractRegistry = new WidgetContractRegistry();

    @BeforeEach
    void setUp() {
        contractRegistry.init();
    }

    // ==================== 造数据的辅助方法 ====================

    private String widget(String type, String field, String title) {
        return String.format("{\"type\":\"%s\",\"field\":\"%s\",\"title\":\"%s\"}", type, field, title);
    }

    private String fields(String... rules) {
        return FormFieldsCodec.write(List.of(rules));
    }

    private Map<String, Object> data(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    // ==================== delimited：7 个控件走这条 ====================

    @Test
    @DisplayName("多选值拼成分隔串落库，读回来还是数组")
    void testDelimitedRoundTrip() {
        String form = fields(widget("checkbox", "tags", "标签"));

        Map<String, Object> db = serializer.serialize(form, data("tags", List.of("a", "b", "c")));
        assertEquals("a,b,c", db.get("tags"));

        Map<String, Object> back = serializer.deserialize(form, db);
        assertEquals(List.of("a", "b", "c"), back.get("tags"));
    }

    @Test
    @DisplayName("日期区间同样走分隔串 —— 它的值也是数组")
    void testDateRange() {
        String form = fields(widget("dateRange", "range", "日期区间"));
        Map<String, Object> db = serializer.serialize(form,
                data("range", List.of("2026-09-01", "2026-09-10")));
        assertEquals("2026-09-01,2026-09-10", db.get("range"));
    }

    @Test
    @DisplayName("空数组存成空串，读回来是空数组而不是 [\"\"]")
    void testEmptyCollection() {
        // [""] 会让多选框勾中一个空选项，看起来像「莫名其妙多了个选项」
        String form = fields(widget("checkbox", "tags", "标签"));
        Map<String, Object> db = serializer.serialize(form, data("tags", List.of()));
        assertEquals("", db.get("tags"));
        assertEquals(List.of(), serializer.deserialize(form, db).get("tags"));
    }

    @Test
    @DisplayName("⚠️ 中间的空项要保留：存 3 项就得读回 3 项")
    void testKeepsEmptyParts() {
        String form = fields(widget("checkbox", "tags", "标签"));
        Map<String, Object> back = serializer.deserialize(form, data("tags", "a,,c"));
        assertEquals(List.of("a", "", "c"), back.get("tags"));
    }

    @Test
    @DisplayName("⚠️ 选项值里含分隔符：报错而不是照拼")
    void testSeparatorConflictRejected() {
        // 照拼进去的话，读回来会比存进去多一项，而且不会有任何报错 ——
        // 这正是本模块反复在避免的那类静默错误
        String form = fields(widget("checkbox", "tags", "标签"));
        ServiceException e = assertThrows(ServiceException.class,
                () -> serializer.serialize(form, data("tags", List.of("a,b", "c"))));
        assertTrue(e.getMessage().contains("标签"), e.getMessage());
    }

    // ==================== json ====================

    @Test
    @DisplayName("组织选择器的对象数组存 JSON 串，读回来还是数组")
    void testJsonRoundTrip() {
        String form = fields(widget("OrgPicker", "owner", "负责人"));
        List<Object> value = new ArrayList<>(List.of(data("type", "user", "id", 1)));

        Map<String, Object> db = serializer.serialize(form, data("owner", value));
        assertEquals("[{\"type\":\"user\",\"id\":1}]", db.get("owner"));

        Object back = serializer.deserialize(form, db).get("owner");
        assertInstanceOf(List.class, back);
        assertEquals(1, ((List<?>) back).size());
    }

    @Test
    @DisplayName("多图上传：统一值契约的对象数组整份存 JSON")
    void testUploadImgsJson() {
        String form = fields(widget("UploadImgs", "imgs", "现场照片"));
        Map<String, Object> file = data("name", "a.png", "url", "http://cdn/a.png",
                "size", 100, "type", "image/png");

        Map<String, Object> db = serializer.serialize(form, data("imgs", List.of(file)));
        String stored = (String) db.get("imgs");
        assertTrue(stored.startsWith("["), stored);
        assertTrue(stored.contains("http://cdn/a.png"), stored);

        Object back = serializer.deserialize(form, db).get("imgs");
        assertEquals(1, ((List<?>) back).size());
    }

    @Test
    @DisplayName("列里存的不是合法 JSON：原样返回，不让一条脏数据把整个列表打不开")
    void testBrokenJsonKept() {
        String form = fields(widget("OrgPicker", "owner", "负责人"));
        assertEquals("这不是JSON", serializer.deserialize(form, data("owner", "这不是JSON")).get("owner"));
    }

    @Test
    @DisplayName("⚠️ 文件上传在「数量限制 = 1」时发的是裸 URL：包成单元素数组再落库")
    void testSingleFileWrappedIntoArray() {
        // 不包的话，同一个字段会时而存 ["u"]、时而存 u —— 读的时候前者解析成数组、
        // 后者解析失败按原样返回，每次读还记一条「列值不是合法 JSON」的告警。
        // 这是「落库值形态不统一」这条技术债的最后一处
        String form = fields(widget("UploadFile", "files", "附件"));

        Map<String, Object> db = serializer.serialize(form, data("files", "http://cdn/a.pdf"));
        assertEquals("[\"http://cdn/a.pdf\"]", db.get("files"));

        assertEquals(List.of("http://cdn/a.pdf"), serializer.deserialize(form, db).get("files"));
    }

    @Test
    @DisplayName("非文件的 json 字段收到裸字符串时不包：那不是同一件事")
    void testNonFileStringNotWrapped() {
        // 组织选择器的值是 [{type,id}]，收到一个裸字符串说明前端传错了类型，
        // 包成 ["str"] 只会把「传错了」变成一个看不见的自动修正
        String form = fields(widget("OrgPicker", "owner", "负责人"));
        assertEquals("abc", serializer.serialize(form, data("owner", "abc")).get("owner"));
    }

    // ==================== scalar + 文件：只存首个 URL ====================

    @Test
    @DisplayName("单图上传：统一值契约提交的是数组，落库只留首个 URL")
    void testFirstUrl() {
        String form = fields(widget("UploadImg", "avatar", "头像"));
        Map<String, Object> file = data("name", "a.png", "url", "http://cdn/a.png");

        Map<String, Object> db = serializer.serialize(form, data("avatar", List.of(file)));
        assertEquals("http://cdn/a.png", db.get("avatar"));

        // 读回来仍是一个 URL 字符串。包成长度 1 的数组是前端适配层的事 ——
        // 在这里包的话，「落库形态」与「逻辑形态」在这个控件上就不是一一对应了
        assertEquals("http://cdn/a.png", serializer.deserialize(form, db).get("avatar"));
    }

    @Test
    @DisplayName("签名已经是裸 URL 字符串时原样落库")
    void testSignatureScalar() {
        String form = fields(widget("Signature", "sign", "签名"));
        Map<String, Object> db = serializer.serialize(form, data("sign", "http://cdn/s.png"));
        assertEquals("http://cdn/s.png", db.get("sign"));
    }

    @Test
    @DisplayName("空数组的单图上传落成 null，而不是字符串 \"[]\"")
    void testFirstUrlEmpty() {
        String form = fields(widget("UploadImg", "avatar", "头像"));
        assertNull(serializer.serialize(form, data("avatar", List.of())).get("avatar"));
    }

    // ==================== 子表明细列 ====================

    @Test
    @DisplayName("⚠️ 子表明细里的多选列同样要转：只转主表的话，明细照旧把 ArrayList 交给 JDBC")
    void testSubTableColumn() {
        String form = fields("{\"type\":\"group\",\"field\":\"items\",\"title\":\"明细\",\"props\":{\"rule\":["
                + widget("checkbox", "tags", "标签") + "]}}");
        Map<String, Object> row = data("tags", List.of("x", "y"));

        Map<String, Object> db = serializer.serialize(form, data("items", List.of(row)));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) db.get("items");
        assertEquals("x,y", rows.get(0).get("tags"));
        // 入参不能被就地改：单据编号重试依赖「每次尝试用一份干净副本」
        assertEquals(List.of("x", "y"), row.get("tags"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> back =
                (List<Map<String, Object>>) serializer.deserialize(form, db).get("items");
        assertEquals(List.of("x", "y"), back.get(0).get("tags"));
    }

    // ==================== delimited 控件映射到 JSON 列 ====================

    private TableBinding binding(String field, String jdbcType) {
        FieldMapping mapping = new FieldMapping();
        mapping.setColumn(field + "_col");
        mapping.setJdbcType(jdbcType);
        TableBinding binding = new TableBinding();
        binding.setFieldMapping(Map.of(field, mapping));
        return binding;
    }

    private TableBinding subBinding(String subField, String field, String jdbcType) {
        FieldMapping mapping = new FieldMapping();
        mapping.setColumn(field + "_col");
        mapping.setJdbcType(jdbcType);
        SubTableBinding subTable = new SubTableBinding();
        subTable.setField(subField);
        subTable.setTable("biz_detail");
        subTable.setFieldMapping(Map.of(field, mapping));
        TableBinding binding = new TableBinding();
        binding.setSubTables(List.of(subTable));
        return binding;
    }

    @Test
    @DisplayName("⚠️ 多选映射到 JSON 列：改存 JSON 串 —— 逗号串永远不是合法 JSON，写进去必被数据库拒绝")
    void testDelimitedOnJsonColumn() {
        String form = fields(widget("checkbox", "tags", "标签"));
        TableBinding binding = binding("tags", "JSON");

        Map<String, Object> db = serializer.serialize(form,
                data("tags", List.of("微生物", "重金属")), binding);
        assertEquals("[\"微生物\",\"重金属\"]", db.get("tags"));

        // 读方向必须成对：JSON 串解析回数组，而不是按逗号切出 ["[\"微生物\"", " \"重金属\"]"]
        assertEquals(List.of("微生物", "重金属"),
                serializer.deserialize(form, db, binding).get("tags"));
    }

    @Test
    @DisplayName("JSON 列上的空数组存 []，读回来是空数组")
    void testDelimitedOnJsonColumnEmpty() {
        String form = fields(widget("checkbox", "tags", "标签"));
        TableBinding binding = binding("tags", "JSON");

        Map<String, Object> db = serializer.serialize(form, data("tags", List.of()), binding);
        assertEquals("[]", db.get("tags"));
        assertEquals(List.of(), serializer.deserialize(form, db, binding).get("tags"));
    }

    @Test
    @DisplayName("多选映射到 VARCHAR 列仍存分隔串 —— 存量数据不受影响")
    void testDelimitedOnStringColumnUnchanged() {
        String form = fields(widget("checkbox", "tags", "标签"));
        TableBinding binding = binding("tags", "VARCHAR");

        Map<String, Object> db = serializer.serialize(form, data("tags", List.of("a", "b")), binding);
        assertEquals("a,b", db.get("tags"));
        assertEquals(List.of("a", "b"), serializer.deserialize(form, db, binding).get("tags"));
    }

    @Test
    @DisplayName("⚠️ 子表明细里的多选列映射到 JSON 列同样升级")
    void testSubTableColumnOnJsonColumn() {
        String form = fields("{\"type\":\"group\",\"field\":\"items\",\"title\":\"明细\",\"props\":{\"rule\":["
                + widget("checkbox", "tags", "标签") + "]}}");
        TableBinding binding = subBinding("items", "tags", "JSON");
        Map<String, Object> row = data("tags", List.of("x", "y"));

        Map<String, Object> db = serializer.serialize(form, data("items", List.of(row)), binding);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) db.get("items");
        assertEquals("[\"x\",\"y\"]", rows.get(0).get("tags"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> back =
                (List<Map<String, Object>>) serializer.deserialize(form, db, binding).get("items");
        assertEquals(List.of("x", "y"), back.get(0).get("tags"));
    }

    // ==================== 不该动的字段 ====================

    @Test
    @DisplayName("普通输入框、数字、日期这些 scalar 字段一律不动")
    void testScalarUntouched() {
        String form = fields(widget("input", "name", "姓名"),
                widget("inputNumber", "qty", "数量"),
                widget("datePicker", "day", "日期"));
        Map<String, Object> db = serializer.serialize(form,
                data("name", "张三", "qty", 3, "day", "2026-09-10"));

        assertEquals("张三", db.get("name"));
        assertEquals(3, db.get("qty"));
        assertEquals("2026-09-10", db.get("day"));
    }

    @Test
    @DisplayName("子表控件本身走独立明细表，不当成一个列值去序列化")
    void testSubTableWidgetItselfUntouched() {
        String form = fields("{\"type\":\"group\",\"field\":\"items\",\"title\":\"明细\",\"props\":{\"rule\":[]}}");
        Map<String, Object> rows = data();
        Map<String, Object> db = serializer.serialize(form, data("items", List.of(rows)));
        assertInstanceOf(List.class, db.get("items"));
    }

    @Test
    @DisplayName("null 值保持 null，不变成空串")
    void testNullKept() {
        String form = fields(widget("checkbox", "tags", "标签"));
        Map<String, Object> db = serializer.serialize(form, data("tags", null));
        assertNull(db.get("tags"));
    }

    @Test
    @DisplayName("数据里没有这个键时不要凭空补一个")
    void testAbsentKeyNotAdded() {
        // 补上的话，部分更新会把一个用户没提交的字段一起写成空值
        String form = fields(widget("checkbox", "tags", "标签"));
        Map<String, Object> db = serializer.serialize(form, data("other", 1));
        assertTrue(!db.containsKey("tags"), "不该凭空多出 tags 这个键");
    }

    @Test
    @DisplayName("表单里没有需要转换的字段时原样返回")
    void testNoStoredField() {
        String form = fields(widget("input", "name", "姓名"));
        Map<String, Object> incoming = data("name", "张三");
        assertEquals(incoming, serializer.serialize(form, incoming));
    }

}
