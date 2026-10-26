package cn.enlev.ainb.module.infra.service.formdesign;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignDO;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.AutoFillConfig;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.FieldMapping;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.JoinTableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.OptimisticLockConfig;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.SideTableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.SubTableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.TableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.logic.FormLogicGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.FORM_DESIGN_PRINT_FIELD_NOT_EXISTS;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.FORM_DESIGN_PRINT_LOOP_FIELD_NOT_EXISTS;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.FORM_DESIGN_PRINT_LOOP_SUB_NOT_EXISTS;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.FORM_DESIGN_PRINT_LOOP_STRAY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link FormDesignPublishValidator} 中「打印模板占位符」校验的单元测试
 *
 * 这段校验防的又是那类没有报错线索的故障：模板里的 {@code {字段}} 引用了一个
 * 取不到值的键，打印出来的单据上就是一片空白。设计者拿到的反馈是「这一格印不出来」，
 * 而他会先去怀疑模板的样式。
 *
 * 用例的重点不在「不存在的字段会被拦」那一条，而在**允许集合到底包含哪些键**：
 * 少放一类（主键、审计列、关联表别名、副表列）就是一次「配对了却发布不了」，
 * 而那比漏拦更让人恼火 —— 用户看着能印出来的字段，却被告知不存在。
 *
 * @author 走进新时代
 */
public class FormDesignPrintTemplateValidatorTest extends BaseMockitoUnitTest {

    @InjectMocks
    private FormDesignPublishValidator validator;

    /**
     * 表单字段的解析由它负责，这里只关心「解析出来之后允许集合怎么拼」，
     * 所以直接给定字段清单。解析本身有 FormLogicGuard 自己的用例守着
     */
    @Mock
    private FormLogicGuard logicGuard;

    private void givenFormFields(String... fields) {
        Map<String, FormLogicGuard.FieldMeta> metas = new LinkedHashMap<>();
        for (String field : fields) {
            FormLogicGuard.FieldMeta meta = new FormLogicGuard.FieldMeta();
            meta.setField(field);
            metas.put(field, meta);
        }
        when(logicGuard.parseFieldMetas(any())).thenReturn(metas);
    }

    private FormDesignDO formWithTemplate(String template) {
        FormDesignDO formDesign = new FormDesignDO();
        formDesign.setPrintTemplate(template);
        return formDesign;
    }

    private TableBinding binding() {
        TableBinding binding = new TableBinding();
        binding.setMainPk("id");
        return binding;
    }

    @Test
    @DisplayName("引用表单字段：放行")
    public void testFormField() {
        givenFormFields("bill_no", "customer");

        validator.validatePrintTemplate(
                formWithTemplate("<p>单号：{bill_no}</p><p>客户：{customer}</p>"), binding());
    }

    @Test
    @DisplayName("引用不存在的字段：拦下")
    public void testUnknownField() {
        givenFormFields("bill_no");

        assertServiceException(
                () -> validator.validatePrintTemplate(
                        formWithTemplate("<p>{bill_no} / {not_exists}</p>"), binding()),
                FORM_DESIGN_PRINT_FIELD_NOT_EXISTS, "not_exists");
    }

    @Test
    @DisplayName("没配模板 / 模板里没有占位符：不需要解析字段，直接放行")
    public void testNoTemplate() {
        // 刻意不 stub logicGuard：走到解析那一步就说明白跑了一趟没必要的活。
        // 发布是同步接口，每一步都要能省
        validator.validatePrintTemplate(formWithTemplate(null), binding());
        validator.validatePrintTemplate(formWithTemplate(""), binding());
        validator.validatePrintTemplate(formWithTemplate("<h1>出库单</h1>"), binding());
    }

    @Test
    @DisplayName("主键可以印 —— 它不是表单字段，但运行时必查")
    public void testMainPk() {
        givenFormFields("bill_no");

        // 拦下来的话就是一次「配对了却发布不了」：{id} 是完全正常的用法，
        // buildSelectColumns 第一行就把主键查出来了
        validator.validatePrintTemplate(formWithTemplate("<p>ID：{id}</p>"), binding());
    }

    @Test
    @DisplayName("审计列可以印 —— 单据上的「制单人 / 制单时间」就是它们")
    public void testAuditColumns() {
        givenFormFields("bill_no");
        TableBinding binding = binding();
        AutoFillConfig autoFill = new AutoFillConfig();
        autoFill.setCreatorColumn("creator");
        autoFill.setCreateTimeColumn("create_time");
        binding.setAutoFill(autoFill);

        validator.validatePrintTemplate(
                formWithTemplate("<p>制单：{creator} {create_time}</p>"), binding);
    }

    @Test
    @DisplayName("关联表的展示列按 AS 别名认，没有别名时按列名")
    public void testJoinTableColumns() {
        givenFormFields("bill_no");
        TableBinding binding = binding();
        JoinTableBinding join = new JoinTableBinding();
        join.setTable("crm_customer");
        JoinTableBinding.SelectColumn aliased = new JoinTableBinding.SelectColumn();
        aliased.setColumn("name");
        aliased.setAs("customer_name");
        JoinTableBinding.SelectColumn plain = new JoinTableBinding.SelectColumn();
        plain.setColumn("mobile");
        join.setSelectColumns(List.of(aliased, plain));
        binding.setJoinTables(List.of(join));

        // 前端按别名取值 —— 与 buildSelectColumns 的 AS 一致。
        // 这里按列名（name）认的话，用户配了别名反而会被拦
        validator.validatePrintTemplate(
                formWithTemplate("<p>{customer_name} {mobile}</p>"), binding);
    }

    @Test
    @DisplayName("只映射不放控件的副表列也可以印")
    public void testSideTableColumns() {
        givenFormFields("bill_no");
        TableBinding binding = binding();
        SideTableBinding side = new SideTableBinding();
        side.setTable("biz_order_ext");
        Map<String, FieldMapping> sideMapping = new LinkedHashMap<>();
        FieldMapping memo = new FieldMapping();
        memo.setColumn("memo");
        sideMapping.put("yd_biz_order_ext_yd_memo", memo);
        side.setFieldMapping(sideMapping);
        binding.setSideTables(List.of(side));

        // 副表列由 mergeSideTableData 单独查回来 putAll 进同一个 Map，
        // 键就是 fieldMapping 的键
        validator.validatePrintTemplate(
                formWithTemplate("<p>{yd_biz_order_ext_yd_memo}</p>"), binding);
    }

    @Test
    @DisplayName("乐观锁的版本列可以印 —— 它也在 SELECT 里")
    public void testVersionColumn() {
        givenFormFields("bill_no");
        TableBinding binding = binding();
        OptimisticLockConfig lock = new OptimisticLockConfig();
        lock.setEnabled(true);
        lock.setColumn("version");
        binding.setOptimisticLock(lock);

        validator.validatePrintTemplate(formWithTemplate("<p>版本：{version}</p>"), binding);
    }

    @Test
    @DisplayName("子表明细列被拦下 —— 它是多行数据，插不进一个位置")
    public void testSubTableFieldRejected() {
        // parseFieldMetas 只递归布局容器、不递归子表内部规则，所以明细列天然不在
        // 允许集合里。放过去的话，单据上那一格是一串 [object Object]
        givenFormFields("bill_no");

        assertServiceException(
                () -> validator.validatePrintTemplate(
                        formWithTemplate("<p>{detail_amount}</p>"), binding()),
                FORM_DESIGN_PRINT_FIELD_NOT_EXISTS, "detail_amount");
    }

    // ================= 子表明细的行标记 =================

    private TableBinding bindingWithSub(String subField, String... columns) {
        TableBinding binding = binding();
        SubTableBinding sub = new SubTableBinding();
        sub.setField(subField);
        Map<String, FieldMapping> mapping = new LinkedHashMap<>();
        for (String column : columns) {
            mapping.put(column, new FieldMapping());
        }
        sub.setFieldMapping(mapping);
        binding.setSubTables(List.of(sub));
        return binding;
    }

    /** 与设计器「插入明细表格」产出的结构一致 */
    private static final String ROW_TPL =
            "<table><tbody><tr><td>{#items}{item_name}</td><td>{item_qty}</td></tr></tbody></table>";

    @Test
    @DisplayName("明细行引用已配的子表与它的列：放行")
    public void testLoopOk() {
        givenFormFields("bill_no");

        validator.validatePrintTemplate(
                formWithTemplate("<p>{bill_no}</p>" + ROW_TPL),
                bindingWithSub("items", "item_name", "item_qty"));
    }

    @Test
    @DisplayName("⚠️ 整张模板只有明细行时也要校验 —— 主表字段集为空不能提前放行")
    public void testLoopOnlyTemplateStillValidated() {
        // 这一条是实测踩出来的：校验方法开头「没有主表占位符就 return」，
        // 于是一张只有明细表格的模板，引用了不存在的子表也照样发布成功
        // 不 stub 表单字段：循环段校验跑在字段校验之前，所以这条路径上
        // 根本不会去解析表单字段。stub 了反而会被 Mockito 判为 UnnecessaryStubbing ——
        // 这本身就是「循环校验确实在前面」的一条旁证

        assertServiceException(
                () -> validator.validatePrintTemplate(
                        formWithTemplate(
                                "<table><tbody><tr><td>{#nosuch}{a}</td></tr></tbody></table>"),
                        bindingWithSub("items", "item_name")),
                FORM_DESIGN_PRINT_LOOP_SUB_NOT_EXISTS, "nosuch");
    }

    @Test
    @DisplayName("明细行里引用的列不是该子表的字段：拦下")
    public void testLoopColumnNotExists() {
        // 不 stub 表单字段：循环段校验跑在字段校验之前，所以这条路径上
        // 根本不会去解析表单字段。stub 了反而会被 Mockito 判为 UnnecessaryStubbing ——
        // 这本身就是「循环校验确实在前面」的一条旁证

        assertServiceException(
                () -> validator.validatePrintTemplate(
                        formWithTemplate("<p>{bill_no}</p><table><tbody>"
                                + "<tr><td>{#items}{no_such}</td></tr></tbody></table>"),
                        bindingWithSub("items", "item_name")),
                FORM_DESIGN_PRINT_LOOP_FIELD_NOT_EXISTS, "items", "no_such");
    }

    @Test
    @DisplayName("⚠️ 标记没放在表格行里时报「没放在行里」，而不是「找不到字段 #items」")
    public void testStrayMarkMessage() {
        // 两种写法都拦得住，但报错内容差很多：报「找不到字段 #items」的话，
        // 用户会去表单里找一个叫 #items 的字段，而问题其实在于标记的位置。
        //
        // 不 stub 表单字段：明细行校验跑在字段校验之前，这条路径上根本不会
        // 去解析表单字段。stub 了反而会被 Mockito 判为 UnnecessaryStubbing ——
        // 这本身就是「明细行校验确实在前面」的一条旁证
        assertServiceException(
                () -> validator.validatePrintTemplate(
                        formWithTemplate("<p>{bill_no}{#items}</p>"),
                        bindingWithSub("items", "item_name")),
                FORM_DESIGN_PRINT_LOOP_STRAY, "items");
    }

    @Test
    @DisplayName("一个子表都没配时，任何明细行都拦下")
    public void testLoopWithoutSubTables() {
        // 不 stub 表单字段：循环段校验跑在字段校验之前，所以这条路径上
        // 根本不会去解析表单字段。stub 了反而会被 Mockito 判为 UnnecessaryStubbing ——
        // 这本身就是「循环校验确实在前面」的一条旁证

        assertServiceException(
                () -> validator.validatePrintTemplate(
                        formWithTemplate(
                                "<table><tbody><tr><td>{#items}{a}</td></tr></tbody></table>"),
                        binding()),
                FORM_DESIGN_PRINT_LOOP_SUB_NOT_EXISTS, "items");
    }

}
