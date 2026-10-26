package cn.enlev.ainb.module.infra.framework.formdesign.core.resource;

import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormResourceDescriptorDTO;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignDO;
import cn.enlev.ainb.module.infra.framework.formdesign.core.meta.TableMetadataCache;
import com.baomidou.mybatisplus.generator.config.po.TableField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * {@link FormResourceDescriptorBuilder} 的单元测试类
 *
 * 这个解析器是「权限模块看到什么」的唯一来源，它和 {@code FormDataMasker} 走的是同一棵
 * form-create 规则树。两处走法一旦不一致，就会出现「脱敏认得这个字段、权限不认得」
 * —— 那正是漏掉一个敏感字段的方式。所以嵌套、子表、布局容器三种形态都要覆盖。
 *
 * @author 走进新时代
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT) // 部分用例不读表结构
public class FormResourceDescriptorBuilderTest extends BaseMockitoUnitTest {

    @InjectMocks
    private FormResourceDescriptorBuilder builder;

    @Mock
    private TableMetadataCache metadataCache;

    // ============================= 整体 =============================

    @Test
    public void testBuild_未绑定主表时返回null() {
        FormDesignDO design = new FormDesignDO();
        design.setCode("demo");
        assertNull(builder.build(design));
        assertNull(builder.build(null));
    }

    @Test
    public void testBuild_带出主表物理列供槽位推断() {
        when(metadataCache.columns(any(), anyString())).thenReturn(columns("id", "title", "status"));

        FormResourceDescriptorDTO dto = builder.build(design(null, null, null));

        assertEquals(List.of("id", "title", "status"), dto.getTableColumns());
        assertEquals("biz_demo", dto.getMainTable());
        assertEquals("demo", dto.getFormCode());
    }

    @Test
    public void testBuild_读不到表结构时退化为空列表而不是抛异常() {
        when(metadataCache.columns(any(), anyString()))
                .thenThrow(new RuntimeException("数据源连不上"));

        FormResourceDescriptorDTO dto = builder.build(design(null, null, null));

        assertTrue(dto.getTableColumns().isEmpty());
    }

    // ============================= 字段提取 =============================

    @Test
    public void testParseFields_提取布局容器里的嵌套字段() {
        String formFields = fields(
                rule("{\"type\":\"row\",\"children\":[" +
                        rule("{\"type\":\"input\",\"field\":\"title\",\"title\":\"标题\"}") + "]}"));

        List<FormResourceDescriptorDTO.Item> items = builder.parseFields(formFields, Map.of());

        assertEquals(1, items.size());
        assertEquals("title", items.get(0).getKey());
        assertEquals("标题", items.get(0).getLabel());
    }

    @Test
    public void testParseFields_子表明细字段带上子表控件前缀() {
        String formFields = fields(
                "{\"type\":\"group\",\"field\":\"items\",\"title\":\"明细\",\"props\":{\"rule\":["
                        + "{\"type\":\"input\",\"field\":\"mobile\",\"title\":\"联系电话\"}]}}");

        List<FormResourceDescriptorDTO.Item> items = builder.parseFields(formFields, Map.of());

        assertEquals(2, items.size());
        assertEquals("items", items.get(0).getKey());
        // 分隔符与控件契约、BPM 三处必须一致，否则子表字段权限配了不生效
        assertEquals("items.mobile", items.get(1).getKey());
        assertTrue(items.get(1).isSubItem());
    }

    @Test
    public void testParseFields_识别敏感与脱敏两种标记() {
        String formFields = fields(
                "{\"field\":\"idCard\",\"title\":\"身份证\",\"props\":{\"sensitive\":true}}",
                "{\"field\":\"mobile\",\"title\":\"手机号\",\"props\":{\"maskRule\":\"MOBILE\"}}",
                "{\"field\":\"title\",\"title\":\"标题\",\"props\":{\"maskRule\":\"NONE\"}}");

        List<FormResourceDescriptorDTO.Item> items = builder.parseFields(formFields, Map.of());

        assertTrue(items.get(0).isSensitive());
        assertFalse(items.get(0).isMasked());
        assertTrue(items.get(0).isGrantworthy());

        assertFalse(items.get(1).isSensitive());
        assertTrue(items.get(1).isMasked());
        assertTrue(items.get(1).isGrantworthy());

        assertFalse(items.get(2).isGrantworthy(), "NONE 等于没配脱敏，不该落行");
    }

    @Test
    public void testParseFields_没有title时用key兜底() {
        List<FormResourceDescriptorDTO.Item> items =
                builder.parseFields(fields("{\"field\":\"title\"}"), Map.of());

        assertEquals("title", items.get(0).getLabel());
    }

    @Test
    public void testParseFields_脏数据不抛异常() {
        // 历史脏规则不该让整张表单发布不了
        assertTrue(builder.parseFields("不是 JSON", Map.of()).isEmpty());
        assertTrue(builder.parseFields(null, Map.of()).isEmpty());
        assertTrue(builder.parseFields(fields("{\"没有field\":1}"), Map.of()).isEmpty());
    }

    // ============================= 字段映射 =============================

    @Test
    public void testBuild_字段与子表字段都映射到物理列() {
        when(metadataCache.columns(any(), anyString())).thenReturn(columns("id"));
        String tableBinding = "{\"mainTable\":\"biz_demo\",\"mainPk\":\"id\","
                + "\"fieldMapping\":{\"title\":{\"column\":\"TITLE\",\"jdbcType\":\"VARCHAR\"}},"
                + "\"subTables\":[{\"field\":\"items\",\"table\":\"biz_demo_item\",\"foreignKey\":\"demo_id\","
                + "\"fieldMapping\":{\"mobile\":{\"column\":\"mobile\"}}}]}";
        String formFields = fields(
                "{\"field\":\"title\",\"title\":\"标题\"}",
                "{\"field\":\"items\",\"title\":\"明细\",\"props\":{\"rule\":["
                        + "{\"field\":\"mobile\",\"title\":\"电话\"}]}}");

        FormResourceDescriptorDTO dto = builder.build(design(formFields, null, tableBinding));

        Map<String, String> columnByKey = new LinkedHashMap<>();
        dto.getFields().forEach(f -> columnByKey.put(f.getKey(), f.getColumn()));
        assertEquals("title", columnByKey.get("title"), "物理列名统一转小写");
        assertEquals("mobile", columnByKey.get("items.mobile"));
        assertNull(columnByKey.get("items"), "子表控件本身不映射列");
    }

    @Test
    public void testBuild_创建人列只在AUTO模式下带出() {
        when(metadataCache.columns(any(), anyString())).thenReturn(columns("id"));

        String auto = "{\"autoFill\":{\"creatorColumn\":\"Create_User_Id\",\"creatorMode\":\"AUTO\"}}";
        assertEquals("create_user_id", builder.build(design(null, null, auto)).getCreatorColumn());

        // MANUAL 表示这一列交给表单字段自己填，不是「后端知道这列存创建人」
        String manual = "{\"autoFill\":{\"creatorColumn\":\"create_user_id\",\"creatorMode\":\"MANUAL\"}}";
        assertNull(builder.build(design(null, null, manual)).getCreatorColumn());
    }

    @Test
    public void testBuild_表绑定是脏数据时映射为空而不是发布失败() {
        when(metadataCache.columns(any(), anyString())).thenReturn(columns("id"));

        FormResourceDescriptorDTO dto = builder.build(design(
                fields("{\"field\":\"title\",\"title\":\"标题\"}"), null, "{坏掉的 JSON"));

        assertEquals(1, dto.getFields().size());
        assertNull(dto.getFields().get(0).getColumn());
    }

    // ============================= 列表列 =============================

    @Test
    public void testBuild_列继承同名字段的敏感标记() {
        when(metadataCache.columns(any(), anyString())).thenReturn(columns("id"));
        String formFields = fields(
                "{\"field\":\"mobile\",\"title\":\"手机号\",\"props\":{\"maskRule\":\"MOBILE\"}}",
                "{\"field\":\"title\",\"title\":\"标题\"}");
        String listConf = "{\"columns\":[{\"field\":\"mobile\",\"label\":\"联系方式\"},"
                + "{\"field\":\"title\",\"label\":\"标题\"}]}";

        FormResourceDescriptorDTO dto = builder.build(design(formFields, listConf, null));

        assertEquals(2, dto.getColumns().size());
        // 敏感性是数据本身的属性，不该在详情和列表上各标一次 ——
        // 那必然出现只标了一处的配置，漏掉的那一处就是泄露口
        assertTrue(dto.getColumns().get(0).isMasked());
        assertEquals("联系方式", dto.getColumns().get(0).getLabel(), "展示名用列自己的 label");
        assertFalse(dto.getColumns().get(1).isGrantworthy());
    }

    @Test
    public void testBuild_子表列的标识与子表字段对齐() {
        when(metadataCache.columns(any(), anyString())).thenReturn(columns("id"));
        String formFields = fields("{\"field\":\"items\",\"title\":\"明细\",\"props\":{\"rule\":["
                + "{\"field\":\"mobile\",\"title\":\"电话\",\"props\":{\"sensitive\":true}}]}}");
        String listConf = "{\"columns\":[{\"field\":\"mobile\",\"subField\":\"items\",\"label\":\"电话\"}]}";

        FormResourceDescriptorDTO dto = builder.build(design(formFields, listConf, null));

        assertEquals("items.mobile", dto.getColumns().get(0).getKey());
        assertTrue(dto.getColumns().get(0).isSensitive(), "对不齐的话列的敏感标记会丢");
    }

    @Test
    public void testBuild_列表配置为空时列为空() {
        when(metadataCache.columns(any(), anyString())).thenReturn(columns("id"));

        assertTrue(builder.build(design(null, null, null)).getColumns().isEmpty());
        assertTrue(builder.build(design(null, "{坏 JSON", null)).getColumns().isEmpty());
    }

    // ==================== 构造 ====================

    private FormDesignDO design(String formFields, String listConf, String tableBinding) {
        FormDesignDO design = new FormDesignDO();
        design.setId(1L);
        design.setCode("demo");
        design.setName("示例");
        design.setDataSourceConfigId(0L);
        design.setMainTable("biz_demo");
        design.setMainPk("id");
        design.setFormFields(formFields);
        design.setListConf(listConf);
        design.setTableBinding(tableBinding);
        return design;
    }

    /**
     * formFields 在库里是「逐条序列化的规则字符串」的 JSON 数组
     */
    private String fields(String... rules) {
        return JsonUtils.toJsonString(List.of(rules));
    }

    private String rule(String json) {
        return json;
    }

    /**
     * 解析器只用到列名（Map 的键），值是什么无所谓
     */
    private Map<String, TableField> columns(String... names) {
        Map<String, TableField> result = new LinkedHashMap<>();
        for (String name : names) {
            result.put(name, null);
        }
        return result;
    }

}
