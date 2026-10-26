package cn.enlev.ainb.module.agent.tool.impl.form;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.AgentValidateResult;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.FormSchemaValidator;
import cn.enlev.ainb.module.infra.api.datamodel.DataModelApi;
import cn.enlev.ainb.module.infra.api.datamodel.dto.DataModelStructureRespDTO;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDesignSaveReqDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FormSaveDraftTool} 的列表默认配置回归
 *
 * <p>锁死 BUG#8：AI 建表单必须是 formType=2（列表+表单）且带 list_conf，
 * 否则发布出去只有一个录入框、没有列表，不满足「表单设计里面的列表，能够发布出去直接使用」。
 * 同时锁死工具栏要有 create（新增）按钮 —— 漏了它用户只能看不能录。
 *
 * <p>还锁死本次三处 BUG 里的两处：required 与物理列 NOT NULL 对齐、
 * searchable 字段进 list_conf.searchItems（否则「指定要能查询却被忽略」）。
 */
public class FormSaveDraftToolTest extends BaseMockitoUnitTest {

    @Mock
    private FormDesignApi formDesignApi;
    @Mock
    private FormSchemaValidator schemaValidator;
    @Mock
    private DataModelApi dataModelApi;

    @InjectMocks
    private FormSaveDraftTool tool;

    /**
     * 转换工厂用<b>真实实例</b>，不 mock
     *
     * <p>🔴 rule / 列表列 / 查询项 / 落库映射的具体形状正是本类断言的对象
     * （日期列的 {@code DATE} formatter、{@code TEXT+LIKE}、{@code DATE_RANGE+BETWEEN}、
     * {@code _fc_id} 发号…），mock 掉等于把这些断言全部悬空、而测试照样全绿。
     * 工厂内部依赖的 schemaValidator / dataModelApi 仍是 mock，与工具<b>共用同一份桩</b> ——
     * 这一点很要紧：两边各挂一份桩的话，「归一」在工厂里生效、在工具里不生效，看不出来。
     */
    private FormFieldFactory fieldFactory;

    /**
     * 默认「要绑的表都存在」：主表存在性是硬校验（见 FormSaveDraftTool.validateMainTableExists），
     * 不桩的话每个用例会先撞「主表不存在」。按请求的表名回一个同名物理表，等于放行。
     */
    @BeforeEach
    public void setUp() {
        lenient().when(dataModelApi.getPhysicalTableList(anyString())).thenAnswer(inv -> {
            DataModelApi.PhysicalTableRespDTO table = new DataModelApi.PhysicalTableRespDTO();
            table.setTableName(inv.getArgument(0));
            return List.of(table);
        });
        fieldFactory = new FormFieldFactory();
        setField(fieldFactory, "schemaValidator", schemaValidator);
        setField(fieldFactory, "dataModelApi", dataModelApi);
        setField(tool, "fieldFactory", fieldFactory);
    }

    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("注入 " + name + " 失败 —— 字段被改名了？", ex);
        }
    }

    @Test
    public void testSaveDraftRejectsMissingMainTable() {
        when(dataModelApi.getPhysicalTableList("biz_device")).thenReturn(List.of());

        ServiceException ex = assertThrows(ServiceException.class, () -> tool.execute(Map.of(
                "name", "设备管理",
                "code", "device_management",
                "mainTable", "biz_device",
                "fields", List.of(Map.of("field", "device_name", "widget", "input",
                        "title", "设备名称", "jdbcType", "VARCHAR")))));
        assertTrue(ex.getMessage().contains("biz_device 在数据库里不存在"), ex.getMessage());
        assertTrue(ex.getMessage().contains("datamodel_create"), "要给出路：" + ex.getMessage());
        verify(formDesignApi, never()).createFormDesign(any());
    }

    @Test
    public void testSaveDraftProducesListWithCreateButton() {
        when(schemaValidator.canonicalType(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(schemaValidator.validateNewForm(any(), any())).thenReturn(new AgentValidateResult());
        when(formDesignApi.createFormDesign(any())).thenReturn(1L);

        Map<String, Object> params = Map.of(
                "name", "设备报修单",
                "code", "equipment_repair_form",
                "mainTable", "biz_equipment_repair",
                "fields", List.of(
                        Map.of("field", "device_name", "widget", "input", "title", "设备名称", "jdbcType", "VARCHAR"),
                        Map.of("field", "repair_date", "widget", "datePicker", "title", "报修日期", "jdbcType", "TIMESTAMP")));

        tool.execute(params);

        ArgumentCaptor<FormDesignSaveReqDTO> captor = ArgumentCaptor.forClass(FormDesignSaveReqDTO.class);
        org.mockito.Mockito.verify(formDesignApi).createFormDesign(captor.capture());
        FormDesignSaveReqDTO req = captor.getValue();

        // 列表+表单模式，而不是仅录入
        assertEquals(2, req.getFormType());

        JSONObject listConf = JSONUtil.parseObj(req.getListConf());
        // 每个字段一列
        assertEquals(2, listConf.getJSONArray("columns").size());
        JSONObject dateCol = listConf.getJSONArray("columns").getJSONObject(1);
        assertEquals("repair_date", dateCol.getStr("field"));
        // 日期列带 DATE 格式化，否则列表里是原始时间戳
        assertEquals("DATE", dateCol.getJSONObject("formatter").getStr("type"));
        // 工具栏必须有 create（新增），行内有 edit/detail/delete
        List<String> buttons = listConf.getJSONObject("operationColumn")
                .getJSONArray("buttons").toList(String.class);
        assertTrue(buttons.contains("create"), "工具栏缺少新增按钮：" + buttons);
        assertTrue(buttons.containsAll(List.of("edit", "detail", "delete")), "操作列缺少行按钮：" + buttons);
    }

    /**
     * 物理列 NOT NULL ⇒ 表单字段自动必填，即便模型没传 required
     *
    /**
     * 🔴 编码已被占用（上一轮流程建的草稿还在）时，报错要告诉用户「怎么办」，
     *    而不是等 create 撞唯一索引、或只丢一句「编码已存在」让人以为又是 bug
     */
    @Test
    public void testSaveDraftRejectsOccupiedCodeWithGuidance() {
        when(schemaValidator.canonicalType(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(schemaValidator.validateNewForm(any(), any())).thenReturn(new AgentValidateResult());
        FormDesignSaveReqDTO existing = new FormDesignSaveReqDTO();
        existing.setId(880473L);
        when(formDesignApi.getFormDesignByCode("device_management")).thenReturn(existing);

        ServiceException ex = assertThrows(ServiceException.class, () -> tool.execute(Map.of(
                "name", "设备管理",
                "code", "device_management",
                "mainTable", "biz_device",
                "fields", List.of(Map.of("field", "device_name", "widget", "input",
                        "title", "设备名称", "jdbcType", "VARCHAR")))));
        assertTrue(ex.getMessage().contains("880473"), "要点名占用者：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("无需重跑流程"), "要给出路：" + ex.getMessage());
        verify(formDesignApi, never()).createFormDesign(any());
    }

    /**
     * 锁死「表把字段设计成必填、表单页面填不填都行」的不一致。
     */
    @Test
    public void testRequiredReconciledWithNotNullColumn() {
        when(schemaValidator.canonicalType(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(schemaValidator.validateNewForm(any(), any())).thenReturn(new AgentValidateResult());
        when(formDesignApi.createFormDesign(any())).thenReturn(1L);

        // 表结构：write_target 是 NOT NULL，description 可空
        DataModelApi.DataModelSimpleRespDTO model = new DataModelApi.DataModelSimpleRespDTO();
        model.setId(9L);
        model.setTableName("biz_assessment_write");
        when(dataModelApi.getDataModelList()).thenReturn(List.of(model));
        DataModelStructureRespDTO structure = new DataModelStructureRespDTO();
        structure.setColumns(List.of(
                column("write_target", false),
                column("description", true)));
        when(dataModelApi.getStructure(9L)).thenReturn(structure);

        Map<String, Object> params = Map.of(
                "name", "评估撰写",
                "code", "assessment_write_form",
                "mainTable", "biz_assessment_write",
                "fields", List.of(
                        // 都没传 required
                        Map.of("field", "write_target", "widget", "input", "title", "撰写对象", "jdbcType", "VARCHAR"),
                        Map.of("field", "description", "widget", "textarea", "title", "描述", "jdbcType", "VARCHAR")));

        tool.execute(params);

        ArgumentCaptor<FormDesignSaveReqDTO> captor = ArgumentCaptor.forClass(FormDesignSaveReqDTO.class);
        org.mockito.Mockito.verify(formDesignApi).createFormDesign(captor.capture());
        List<String> formFields = captor.getValue().getFormFields();

        // NOT NULL 列 → required true
        assertTrue(JSONUtil.parseObj(formFields.get(0)).getBool("$required"),
                "NOT NULL 列 write_target 的表单字段应自动必填");
        // 可空列 → required false
        assertFalse(JSONUtil.parseObj(formFields.get(1)).getBool("$required"),
                "可空列 description 的表单字段不应必填");
    }

    /**
     * searchable=true 的字段进 list_conf.searchItems，widget/operator 对齐前端推断
     */
    @Test
    public void testSearchableProducesSearchItems() {
        when(schemaValidator.canonicalType(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(schemaValidator.validateNewForm(any(), any())).thenReturn(new AgentValidateResult());
        when(formDesignApi.createFormDesign(any())).thenReturn(1L);

        Map<String, Object> params = Map.of(
                "name", "评估撰写",
                "code", "assessment_write_form",
                "mainTable", "biz_assessment_write",
                "fields", List.of(
                        Map.of("field", "write_target", "widget", "input", "title", "撰写对象",
                                "jdbcType", "VARCHAR", "searchable", true),
                        Map.of("field", "assessment_date", "widget", "datePicker", "title", "评估日期",
                                "jdbcType", "TIMESTAMP", "searchable", true),
                        Map.of("field", "description", "widget", "textarea", "title", "描述", "jdbcType", "VARCHAR")));

        tool.execute(params);

        ArgumentCaptor<FormDesignSaveReqDTO> captor = ArgumentCaptor.forClass(FormDesignSaveReqDTO.class);
        org.mockito.Mockito.verify(formDesignApi).createFormDesign(captor.capture());
        JSONObject listConf = JSONUtil.parseObj(captor.getValue().getListConf());

        // 只有两个 searchable=true 的字段进查询区
        assertEquals(2, listConf.getJSONArray("searchItems").size());
        JSONObject textItem = listConf.getJSONArray("searchItems").getJSONObject(0);
        assertEquals("write_target", textItem.getStr("field"));
        assertEquals("TEXT", textItem.getStr("widget"));
        assertEquals("LIKE", textItem.getStr("operator"));
        JSONObject dateItem = listConf.getJSONArray("searchItems").getJSONObject(1);
        assertEquals("DATE_RANGE", dateItem.getStr("widget"));
        assertEquals("BETWEEN", dateItem.getStr("operator"));
    }

    // ==================== 字段级格式校验（props.validateRules） ====================

    /**
     * 🔴 键名是<b>跨模块的线上契约</b>，不是本模块的内部命名
     *
     * <p>infra 的 {@code FieldValidationRule} 按这个名字读、前端按这个名字派生 form-create 的
     * {@code validate}。改名不会有任何编译错误（三处消费方共用这一个常量，一起改就一起过），
     * 表现是「AI 建的表单格式校验全部失效、且没人报错」—— 只有这条断言会红。
     */
    @Test
    public void testValidateRulesKeyIsTheCrossModuleContractName() {
        assertEquals("validateRules", FormFieldFactory.VALIDATE_RULES_KEY);
    }

    /**
     * 🔴 落点必须是 {@code props.validateRules}
     *
     * <p>这一条钉的是修复前那个缺陷：{@code toRule} 当时只写 {@code props.placeholder}，
     * 模型传来的 {@code validateRules} 被<b>整个丢掉</b> —— 用户说「身份证号要真实的」，
     * AI 答「表单建好了」，然后任何输入都收，而<b>没有任何一处报错</b>：
     * 草稿保存不校验它、发布体检看不到它（压根没写进去）、运行期更不会提。
     *
     * <p>同时钉「不许写到 rule 顶层」：form-create 内核那个 {@code validate} 是 async-validator
     * 的规则数组，语义完全不同；挂顶层的话前后端都不读，等于换个地方继续静默失效。
     */
    @Test
    public void testValidateRulesLandsInsideProps() {
        when(schemaValidator.canonicalType(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(schemaValidator.validateNewForm(any(), any())).thenReturn(new AgentValidateResult());
        when(formDesignApi.createFormDesign(any())).thenReturn(1L);

        tool.execute(Map.of(
                "name", "客户登记",
                "code", "customer_register_form",
                "mainTable", "biz_customer",
                "fields", List.of(Map.of(
                        "field", "id_card", "widget", "input", "title", "身份证号", "jdbcType", "VARCHAR",
                        "validateRules", List.of(Map.of("type", "BUILTIN", "patternKey", "ID_CARD"))))));

        ArgumentCaptor<FormDesignSaveReqDTO> captor = ArgumentCaptor.forClass(FormDesignSaveReqDTO.class);
        verify(formDesignApi).createFormDesign(captor.capture());
        JSONObject rule = JSONUtil.parseObj(captor.getValue().getFormFields().get(0));

        JSONArray entries = rule.getJSONObject("props").getJSONArray("validateRules");
        // 先 assertNotNull 再断长度：落点漏了的时候，红出来的该是这句诊断而不是一个 NullPointer
        assertNotNull(entries, "校验规则没落进 props.validateRules：" + rule);
        assertEquals(1, entries.size(), "校验规则没落进 props.validateRules：" + rule);
        assertEquals("BUILTIN", entries.getJSONObject(0).getStr("type"));
        assertEquals("ID_CARD", entries.getJSONObject(0).getStr("patternKey"));
        // 🔴 原样透传，工具不猜也不改写：形状对不对由 FormSchemaValidator 判，
        //    新建与修改两条路都过它。在这里悄悄丢掉一个看不懂的写法，就又造出一例静默失效
        assertFalse(rule.containsKey("validateRules"), "挂到 rule 顶层的话前后端都不读：" + rule);
        assertFalse(rule.containsKey("validate"),
                "那是 form-create 内核的 async-validator 规则数组，语义不同：" + rule);
    }

    /**
     * 没要校验的字段<b>不许</b>凭空长出一个空的 {@code validateRules}
     *
     * <p>这不是洁癖：空数组会被 {@code FormSchemaValidator} 判成「形状不对」而报错
     * （它分不清「模型想清空校验」与「模型写了个空壳」），
     * 于是工厂多设一格 ⟹ 每一张不带校验的表单都建不出来。
     */
    @Test
    public void testAbsentValidateRulesDoesNotInventAnEmptyOne() {
        when(schemaValidator.canonicalType(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(schemaValidator.validateNewForm(any(), any())).thenReturn(new AgentValidateResult());
        when(formDesignApi.createFormDesign(any())).thenReturn(1L);

        tool.execute(Map.of(
                "name", "客户登记",
                "code", "customer_register_form",
                "mainTable", "biz_customer",
                "fields", List.of(Map.of("field", "customer_name", "widget", "input",
                        "title", "客户名称", "jdbcType", "VARCHAR"))));

        ArgumentCaptor<FormDesignSaveReqDTO> captor = ArgumentCaptor.forClass(FormDesignSaveReqDTO.class);
        verify(formDesignApi).createFormDesign(captor.capture());
        JSONObject props = JSONUtil.parseObj(captor.getValue().getFormFields().get(0)).getJSONObject("props");

        assertFalse(props.containsKey("validateRules"), "凭空多出的空数组会让每张表单都过不了体检：" + props);
    }

    /**
     * 🔴 文案面：{@code validateRules} 必须写在模型看得见的地方，且键名清单是<b>渲染</b>进来的
     *
     * <p>老版 paramsSchema 全文一次都没提格式校验，所以「身份证号要真实的」这类要求
     * 从来没有对应的入参可传 —— 执行侧就算支持了，模型也不知道该传。
     * 这与 {@code FormUpdateToolTest} 里钉 {@code searchable}/{@code remove} 那条是同一条纪律：
     * 执行侧与文案必须一起改，只改一半都还是丢数据。
     *
     * <p>清单必须是渲染的：写死一份的话，正则库加一条 / 改个名，这份 schema 就成了假话，
     * 而模型照着假话写的键名在运行期是「放过 + 记日志」。
     */
    @Test
    public void testParamsSchemaTeachesValidateRulesWithRenderedCatalog() {
        when(schemaValidator.validateRulesCatalog()).thenReturn("ID_CARD（身份证）、MOBILE（手机号）");

        String schema = tool.paramsSchema();

        assertTrue(schema.contains("validateRules"), schema);
        assertTrue(schema.contains("patternKey"), "不教键名的话模型只能凭记忆编：" + schema);
        assertTrue(schema.contains("ID_CARD（身份证）"),
                "键名清单必须由 validateRulesCatalog() 渲染进来，不能是写死的第二份：" + schema);
        assertTrue(schema.contains("不会报错"),
                "要说清「不传的后果是静默失效」，否则模型没有理由主动补这一格：" + schema);
        assertTrue(schema.contains("身份证号要真实的"),
                "要举一个用户真会那么说的隐含表述，抽象规则它已经有一条了、正是那条不够用：" + schema);
        // 尾部那两句安全约束（跨租户写入 / 平台自动维护列）不许被后续改写挤掉
        assertTrue(schema.contains("tenant_id") && schema.contains("平台自动维护"), schema);
    }

    private DataModelStructureRespDTO.ColumnDTO column(String name, boolean nullable) {
        DataModelStructureRespDTO.ColumnDTO col = new DataModelStructureRespDTO.ColumnDTO();
        col.setColumnName(name);
        col.setNullable(nullable);
        return col;
    }

    // ==================== 选项来源（options / dictType） ====================

    /**
     * 🔴 这一条钉的是修复前那个缺陷的<b>本体</b>：{@code toRule} 当时压根不写选项，
     * 于是「状态：下拉，选项是待处理/已处理」建出来的表单存得进、发布也过得去，
     * 而运行期那一格是个点开为空的下拉。AI 答「表单建好了」，用户点进去才发现选不了任何值。
     *
     * <p>落点必须是 rule <b>顶层</b>：契约 JSON 里 select 的 {@code options} PropSpec 明写
     * 「本控件的静态选项写在规则顶层的 options 上，不在 props 里」（只有 cascader 是
     * {@code props.options}）。写错位置同样是静默失效，所以这里两处都断言。
     */
    @Test
    public void testStaticOptionsLandOnTheRuleTopLevel() {
        when(schemaValidator.canonicalType(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(schemaValidator.validateNewForm(any(), any())).thenReturn(new AgentValidateResult());
        when(formDesignApi.createFormDesign(any())).thenReturn(1L);

        tool.execute(Map.of(
                "name", "工单登记",
                "code", "work_order_form",
                "mainTable", "biz_work_order",
                "fields", List.of(Map.of(
                        "field", "status", "widget", "select", "title", "状态", "jdbcType", "VARCHAR",
                        "options", List.of(Map.of("label", "待处理", "value", "pending"),
                                Map.of("label", "已处理", "value", "done"))))));

        ArgumentCaptor<FormDesignSaveReqDTO> captor = ArgumentCaptor.forClass(FormDesignSaveReqDTO.class);
        verify(formDesignApi).createFormDesign(captor.capture());
        JSONObject rule = JSONUtil.parseObj(captor.getValue().getFormFields().get(0));

        JSONArray options = rule.getJSONArray(FormFieldFactory.OPTIONS_KEY);
        assertNotNull(options, "固定选项没落进 rule 顶层的 options：" + rule);
        assertEquals(2, options.size(), "选项一个都不能少：" + rule);
        assertEquals("待处理", options.getJSONObject(0).getStr("label"));
        assertEquals("pending", options.getJSONObject(0).getStr("value"));
        assertFalse(rule.getJSONObject("props").containsKey(FormFieldFactory.DICT_SOURCE_KEY),
                "没写字典就不该凭空长出 dictSource，否则前端按字典取、下拉还是空的：" + rule);
    }

    /**
     * 🔴 字典的落点是 {@code props.dictSource}，且顶层 {@code options} 要写成<b>空数组</b>
     * （前端 {@code stripResolvedOptions} 就是把派生来源的选项路径清空的，
     * 留着旧值会被当成静态选项）。
     *
     * <p>同时钉「查询区那一半不许回归」：{@code dictType} 原先<b>唯一</b>的消费者就是查询项，
     * 修表单那一格的时候把它弄丢的话，表现是「表单里下拉好了、列表查询区退化成文本框」。
     */
    @Test
    public void testDictTypeLandsInPropsAndStillFeedsTheSearchItem() {
        when(schemaValidator.canonicalType(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(schemaValidator.validateNewForm(any(), any())).thenReturn(new AgentValidateResult());
        when(formDesignApi.createFormDesign(any())).thenReturn(1L);

        tool.execute(Map.of(
                "name", "工单登记",
                "code", "work_order_form",
                "mainTable", "biz_work_order",
                "fields", List.of(Map.of(
                        "field", "status", "widget", "select", "title", "状态", "jdbcType", "VARCHAR",
                        "searchable", true, "dictType", "common_status"))));

        ArgumentCaptor<FormDesignSaveReqDTO> captor = ArgumentCaptor.forClass(FormDesignSaveReqDTO.class);
        verify(formDesignApi).createFormDesign(captor.capture());
        FormDesignSaveReqDTO req = captor.getValue();
        JSONObject rule = JSONUtil.parseObj(req.getFormFields().get(0));

        JSONObject source = rule.getJSONObject("props").getJSONObject(FormFieldFactory.DICT_SOURCE_KEY);
        assertNotNull(source, "字典没落进 props.dictSource：" + rule);
        assertEquals("common_status", source.getStr(FormFieldFactory.DICT_TYPE_KEY));
        assertEquals(FormFieldFactory.DICT_VALUE_TYPE, source.getStr("valueType"),
                "valueType 对不上的后果不是报错，而是字典选项匹配不上、下拉又是空的");
        assertEquals(0, rule.getJSONArray(FormFieldFactory.OPTIONS_KEY).size(),
                "字典来源下顶层 options 必须是空数组：" + rule);

        JSONObject searchItem = JSONUtil.parseObj(req.getListConf())
                .getJSONArray("searchItems").getJSONObject(0);
        assertEquals("SELECT", searchItem.getStr("widget"));
        assertEquals("common_status", searchItem.getStr(FormFieldFactory.DICT_TYPE_KEY),
                "查询项丢了字典类型就会退化成一个文本框");
    }

    /**
     * 🔴 钉住「新建走 {@code validateNewForm} 那一档」这件事本身
     *
     * <p>两档的唯一差别是「选项类控件一个来源都没有」判 error 还是 warning。
     * 用错档不会有任何编译或测试上的提示：改表单那条路照样绿，
     * 而新建那条路把「AI 建的下拉是空的」重新放回去 —— 正是这条断言要挡的。
     */
    @Test
    public void testSaveDraftUsesTheNewFormGradeAndRejectsWhenItFails() {
        when(schemaValidator.canonicalType(anyString())).thenAnswer(inv -> inv.getArgument(0));
        AgentValidateResult failed = new AgentValidateResult();
        failed.addError("字段 status 是 select 控件，却没有任何选项来源");
        when(schemaValidator.validateNewForm(any(), any())).thenReturn(failed);

        ServiceException ex = assertThrows(ServiceException.class, () -> tool.execute(Map.of(
                "name", "工单登记",
                "code", "work_order_form",
                "mainTable", "biz_work_order",
                "fields", List.of(Map.of("field", "status", "widget", "select",
                        "title", "状态", "jdbcType", "VARCHAR")))));

        assertTrue(ex.getMessage().contains("没有任何选项来源"), ex.getMessage());
        verify(schemaValidator).validateNewForm(any(), any());
        // 🔴 两档都得钉：改表单那一档现在有两个入口（点名新增字段的三参、与不点名的两参），
        //    只钉两参的话，本工具哪天改走三参就悄悄退成「新建按改表单判」——
        //    本次自己造出来的空控件只被提醒一句就存进去了，而这条断言还是绿的
        verify(schemaValidator, never()).validate(any(), any());
        verify(schemaValidator, never()).validate(any(), any(), any());
        verify(formDesignApi, never()).createFormDesign(any());
    }

    /**
     * 🔴 文案面：{@code options} 这个键必须写在模型看得见的地方
     *
     * <p>修复前字段描述里<b>压根没有</b>能表达「固定选项」的键 —— 于是模型想给用户说出来的
     * 那几个选项找个落点也找不到，只能去编一个 dictType（实测 7 个 dictType 里 6 个是编的）。
     * 执行侧修好了而文案没跟上，模型仍然不会写 {@code options}，缺陷等于没修。
     */
    @Test
    public void testParamsSchemaTeachesOptionSource() {
        when(schemaValidator.validateRulesCatalog()).thenReturn("ID_CARD（身份证）");

        String schema = tool.paramsSchema();

        assertTrue(schema.contains(FormFieldFactory.OPTIONS_KEY), schema);
        assertTrue(schema.contains("label") && schema.contains("value"),
                "要教清静态选项的形状，写错形状同样是静默失效：" + schema);
        assertTrue(schema.contains("没有建字典的工具"),
                "要说清为什么不能凭记忆编 dictType：" + schema);
        assertTrue(schema.contains("点开为空的下拉"),
                "要说清「两个都不写」的后果是静默失效，否则模型没有理由主动补这一格：" + schema);
        assertTrue(schema.contains("【必须】"),
                "这一格不是建议：不写死口气，模型会把它当成可选的装饰：" + schema);
    }

    // ==================== binding 里的 jdbcType 必须是物理列的真值（B17） ====================

    /**
     * 🔴 钉的是修复前那个缺陷的<b>本体</b>：模型声明的 {@code jdbcType} 被原样写进
     * {@code tableBinding.fieldMapping}，而模型<b>看不到物理列</b> —— 建表是上一步
     * {@code datamodel_create} 干的，列类型由那边按受控词表定，这里是让模型对同一件事再猜一次。
     *
     * <p>两次猜测不一致时炸在运行期写库：{@code FormValueSerializer#resolveMode} 靠这一格决定
     * 多选是存 JSON 串还是拼逗号串（只有 {@code JdbcTypeFamily.JSON} 才升级成 JSON 串），
     * 于是 {@code 微生物,重金属} 被写进一个 json 列 ——
     * {@code Data truncation: Invalid JSON text: "Invalid value." at position 0}，
     * 界面上只有一句「服务器错误,请联系管理员!」。实测于表单 880510 / 表 {@code biz_lab_sample}。
     */
    @Test
    public void testJdbcTypeComesFromPhysicalColumnNotFromModel() {
        when(schemaValidator.canonicalType(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(schemaValidator.validateNewForm(any(), any())).thenReturn(new AgentValidateResult());
        when(formDesignApi.createFormDesign(any())).thenReturn(1L);
        stubPhysicalTable("biz_lab_sample", 11L,
                column("test_items", true, "JSON"),
                column("sample_name", false, "STRING"));

        tool.execute(Map.of(
                "name", "实验室样品登记",
                "code", "lab_sample_register",
                "mainTable", "biz_lab_sample",
                "fields", List.of(
                        // 模型把一个 json 列声明成 VARCHAR —— 正是实测那一次的入参
                        Map.of("field", "test_items", "widget", "checkbox", "title", "检测项目",
                                "jdbcType", "VARCHAR",
                                "options", List.of(Map.of("label", "微生物", "value", "微生物"))),
                        Map.of("field", "sample_name", "widget", "input", "title", "样品名称",
                                "jdbcType", "VARCHAR"))));

        JSONObject mapping = savedFieldMapping();
        assertEquals("JSON", mapping.getJSONObject("test_items").getStr("jdbcType"),
                "json 列必须写 JSON：写成 VARCHAR 会让多选拼成逗号串，MySQL 拒收整条 INSERT");
        // 对照组：不是「无脑写 JSON」，而是逐列照物理类型。
        // 少了它，把 resolveJdbcType 写成常量 "JSON" 也能让上一条绿
        assertEquals("VARCHAR", mapping.getJSONObject("sample_name").getStr("jdbcType"),
                "STRING 列的物理类型是 varchar，与模型声明的一致");
    }

    /**
     * 词表<b>不在本模块抄一份</b>：换名字靠 {@code DataModelApi#getColumnTypeList()}。
     *
     * <p>所以词表里没有那个受控类型时只能退回声明值 —— 这条钉的是那个边界。
     * 别把它「优化」成直接把受控类型名写进 binding：{@code STRING} 与 {@code LONG}
     * 两个受控名在契约关键字表里<b>一个都不认</b>（{@code LONG} 的关键字是 {@code BIGINT}），
     * 归到 null 族之后 {@code ValueLengthGuard} 的超长拦截会跟着失效。
     */
    @Test
    public void testJdbcTypeFallsBackToDeclaredWhenWordTableLacksTheType() {
        when(schemaValidator.canonicalType(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(schemaValidator.validateNewForm(any(), any())).thenReturn(new AgentValidateResult());
        when(formDesignApi.createFormDesign(any())).thenReturn(1L);
        stubPhysicalTable("biz_lab_sample", 11L, column("test_items", true, "JSON"));
        // 词表里只有 STRING，没有 JSON
        when(dataModelApi.getColumnTypeList()).thenReturn(List.of(word("STRING", "varchar")));

        tool.execute(Map.of(
                "name", "实验室样品登记",
                "code", "lab_sample_register",
                "mainTable", "biz_lab_sample",
                "fields", List.of(Map.of("field", "test_items", "widget", "checkbox",
                        "title", "检测项目", "jdbcType", "VARCHAR",
                        "options", List.of(Map.of("label", "微生物", "value", "微生物"))))));

        assertEquals("VARCHAR", savedFieldMapping().getJSONObject("test_items").getStr("jdbcType"),
                "换不出 MySQL 类型名时沿用声明值，不能把受控类型名当 jdbcType 写进去");
    }

    /**
     * 读不到物理列结构时整条建表单路径照常走通，jdbcType 退回声明值
     *
     * <p>🔴 fail-soft 是这里的硬要求：读结构只是「让 binding 更准」，
     * 把它变成硬校验的话，表还没登记进数据模型时 AI 就建不出表单了 ——
     * 而主表存在性另有 {@code validateMainTableExists} 那道硬校验管着。
     */
    @Test
    public void testJdbcTypeFallsBackToDeclaredWhenStructureUnreadable() {
        when(schemaValidator.canonicalType(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(schemaValidator.validateNewForm(any(), any())).thenReturn(new AgentValidateResult());
        when(formDesignApi.createFormDesign(any())).thenReturn(1L);
        when(dataModelApi.getDataModelList()).thenThrow(new RuntimeException("infra 无响应"));

        tool.execute(Map.of(
                "name", "实验室样品登记",
                "code", "lab_sample_register",
                "mainTable", "biz_lab_sample",
                "fields", List.of(Map.of("field", "test_items", "widget", "checkbox",
                        "title", "检测项目", "jdbcType", "VARCHAR",
                        "options", List.of(Map.of("label", "微生物", "value", "微生物"))))));

        assertEquals("VARCHAR", savedFieldMapping().getJSONObject("test_items").getStr("jdbcType"),
                "读不到结构就沿用声明值 —— 这正是修复前的行为，不能因为读不到就建不出表单");
    }

    /**
     * 受控列类型 → MySQL 类型名的词表
     *
     * <p>桩的这份<b>逐条照</b> {@code DataModelColumnTypeEnum}：真实运行时工厂读的是
     * {@code getColumnTypeList()}，而那个接口的实现就是把这份枚举转出来。
     * 抄在这里是测试桩 unavoidable 的一份副本 —— 它漂移了只会让本模块的测试变红，
     * 不会让线上悄悄写错类型。
     */
    private static List<DataModelApi.ColumnTypeRespDTO> mysqlTypeWords() {
        return List.of(word("STRING", "varchar"), word("TEXT", "text"), word("LONG_TEXT", "longtext"),
                word("JSON", "json"), word("TINYINT", "tinyint"), word("INT", "int"),
                word("LONG", "bigint"), word("DECIMAL", "decimal"), word("BOOLEAN", "bit"),
                word("DATE", "date"), word("DATETIME", "datetime"));
    }

    private static DataModelApi.ColumnTypeRespDTO word(String type, String mysqlType) {
        DataModelApi.ColumnTypeRespDTO dto = new DataModelApi.ColumnTypeRespDTO();
        dto.setType(type);
        dto.setMysqlType(mysqlType);
        return dto;
    }

    private DataModelStructureRespDTO.ColumnDTO column(String name, boolean nullable, String columnType) {
        DataModelStructureRespDTO.ColumnDTO col = column(name, nullable);
        col.setColumnType(columnType);
        return col;
    }

    /**
     * 桩一张已登记进数据模型的物理表，连带那份列类型词表
     */
    private void stubPhysicalTable(String tableName, Long modelId,
                                   DataModelStructureRespDTO.ColumnDTO... columns) {
        DataModelApi.DataModelSimpleRespDTO model = new DataModelApi.DataModelSimpleRespDTO();
        model.setId(modelId);
        model.setTableName(tableName);
        when(dataModelApi.getDataModelList()).thenReturn(List.of(model));
        DataModelStructureRespDTO structure = new DataModelStructureRespDTO();
        structure.setColumns(List.of(columns));
        when(dataModelApi.getStructure(modelId)).thenReturn(structure);
        when(dataModelApi.getColumnTypeList()).thenReturn(mysqlTypeWords());
    }

    /**
     * 本次保存下来的 {@code tableBinding.fieldMapping}
     */
    private JSONObject savedFieldMapping() {
        ArgumentCaptor<FormDesignSaveReqDTO> captor = ArgumentCaptor.forClass(FormDesignSaveReqDTO.class);
        verify(formDesignApi).createFormDesign(captor.capture());
        return JSONUtil.parseObj(captor.getValue().getTableBinding()).getJSONObject("fieldMapping");
    }

}
