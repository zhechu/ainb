package cn.enlev.ainb.module.infra.service.formdesign;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignDO;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import cn.enlev.ainb.module.infra.framework.formdesign.core.logic.FormLogicGuard;
import cn.enlev.ainb.module.infra.framework.formdesign.core.contract.WidgetContractRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;

import java.util.List;

import static cn.enlev.ainb.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.FORM_DESIGN_LOGIC_RULE_FIELD_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link FormDesignPublishValidator#validateLogicRuleFields} 的单元测试
 *
 * 这段校验防的是一类没有任何报错线索的故障：先配好联动，之后回表单设计把某个字段删了。
 * 运行时取不到这个字段就当条件不成立 / 动作无目标，**把整条规则静默跳过** ——
 * 界面上没有任何异常，用户以为联动还在生效。
 *
 * 补这条之前，联动的字段存在性从保存到发布全程无人过问（只有环形赋值检测），
 * 而隔壁跨字段校验早就有 FORM_DESIGN_VALIDATE_RULE_FIELD_NOT_EXISTS 在拦，
 * 两者不对称。
 *
 * 用例里 rowScope 那两条尤其不能少：行作用域规则的字段名是**明细列的裸名**，
 * 而主表字段清单里没有它们 —— 实现上稍有不慎就会把每一条 rowScope 规则
 * 都误判成引用了不存在的字段，那会把一批本来正常的表单卡在发布上，比漏报严重得多。
 */
public class FormDesignLogicRuleFieldValidatorTest extends BaseMockitoUnitTest {

    @InjectMocks
    private FormDesignPublishValidator validator;

    /** parseRules / parseFieldMetas 都是纯解析，不碰它注入的 evaluator，可以用真实实例 */
    @Spy
    private FormLogicGuard logicGuard = new FormLogicGuard();

    @Mock
    private WidgetContractRegistry widgetContractRegistry;

    /** 主表两个字段：amount、remark */
    private static final String MAIN_FIELDS = FormFieldsCodec.write(List.of(
            "{\"type\":\"inputNumber\",\"field\":\"amount\",\"title\":\"金额\"}",
            "{\"type\":\"input\",\"field\":\"remark\",\"title\":\"备注\"}"));

    /** 主表一个字段 + 一个子表 items，明细列 price、qty */
    private static final String FIELDS_WITH_SUB_TABLE = FormFieldsCodec.write(List.of(
            "{\"type\":\"inputNumber\",\"field\":\"amount\",\"title\":\"金额\"}",
            "{\"type\":\"group\",\"field\":\"items\",\"title\":\"明细\",\"props\":{\"rule\":["
                    + "{\"type\":\"inputNumber\",\"field\":\"price\",\"title\":\"单价\"},"
                    + "{\"type\":\"inputNumber\",\"field\":\"qty\",\"title\":\"数量\"}"
                    + "]}}"));

    private FormDesignDO form(String formFields, String logicRules) {
        FormDesignDO formDesign = new FormDesignDO();
        formDesign.setFormFields(formFields);
        formDesign.setLogicRules(logicRules);
        return formDesign;
    }

    /** 条件左值 = field，动作目标 = target */
    private String rule(String id, String when, String then) {
        return String.format("{\"id\":\"%s\",\"name\":\"%s\",\"enabled\":true,"
                + "\"when\":%s,\"then\":%s}", id, id, when, then);
    }

    private String cond(String field) {
        return String.format("{\"logic\":\"AND\",\"items\":[{\"field\":\"%s\","
                + "\"operator\":\"NOT_EMPTY\"}]}", field);
    }

    /** 右值也是字段引用的条件：field 与 value 都是字段名 */
    private String condFieldRef(String field, String refField) {
        return String.format("{\"logic\":\"AND\",\"items\":[{\"field\":\"%s\",\"operator\":\"GE\","
                + "\"valueType\":\"FIELD\",\"value\":\"%s\"}]}", field, refField);
    }

    private String action(String target) {
        return String.format("[{\"type\":\"SHOW\",\"target\":\"%s\"}]", target);
    }

    @Test
    public void testValidateLogicRuleFields_success() {
        FormDesignDO formDesign = form(MAIN_FIELDS,
                "[" + rule("r1", cond("amount"), action("remark")) + "]");
        assertDoesNotThrow(() -> validator.validateLogicRuleFields(formDesign));
    }

    @Test
    public void testValidateLogicRuleFields_noRules() {
        assertDoesNotThrow(() -> validator.validateLogicRuleFields(form(MAIN_FIELDS, null)));
    }

    /** 条件左值引用了已删字段 */
    @Test
    public void testValidateLogicRuleFields_conditionFieldMissing() {
        FormDesignDO formDesign = form(MAIN_FIELDS,
                "[" + rule("r1", cond("deleted"), action("remark")) + "]");
        assertServiceException(() -> validator.validateLogicRuleFields(formDesign),
                FORM_DESIGN_LOGIC_RULE_FIELD_NOT_EXISTS, "r1", "deleted");
    }

    /**
     * 条件右值引用了已删字段
     *
     * 「结束日期 ≥ 开始日期」正是把开始日期写在右值上。只查左值等于漏掉一半 ——
     * 前端 invalidFieldRefs 原先就漏了这一支。
     */
    @Test
    public void testValidateLogicRuleFields_conditionValueRefMissing() {
        FormDesignDO formDesign = form(MAIN_FIELDS,
                "[" + rule("r1", condFieldRef("amount", "deleted"), action("remark")) + "]");
        assertServiceException(() -> validator.validateLogicRuleFields(formDesign),
                FORM_DESIGN_LOGIC_RULE_FIELD_NOT_EXISTS, "r1", "deleted");
    }

    /** 动作目标引用了已删字段 */
    @Test
    public void testValidateLogicRuleFields_actionTargetMissing() {
        FormDesignDO formDesign = form(MAIN_FIELDS,
                "[" + rule("r1", cond("amount"), action("deleted")) + "]");
        assertServiceException(() -> validator.validateLogicRuleFields(formDesign),
                FORM_DESIGN_LOGIC_RULE_FIELD_NOT_EXISTS, "r1", "deleted");
    }

    /** 停用的规则不校验：用户可能是先停用、留着以后再用 */
    @Test
    public void testValidateLogicRuleFields_disabledRuleSkipped() {
        String disabled = "{\"id\":\"r1\",\"name\":\"r1\",\"enabled\":false,"
                + "\"when\":" + cond("deleted") + ",\"then\":" + action("remark") + "}";
        assertDoesNotThrow(() ->
                validator.validateLogicRuleFields(form(MAIN_FIELDS, "[" + disabled + "]")));
    }

    /**
     * 行作用域规则引用明细列 —— 必须放行
     *
     * 这是最容易写错的一条：price / qty 不在主表字段清单里，
     * 拿主表清单去比对会把这条正常规则误判成引用了不存在的字段。
     */
    @Test
    public void testValidateLogicRuleFields_rowScopeColumnResolved() {
        lenient().when(widgetContractRegistry.getSubTableWidgetType()).thenReturn("group");
        String rowRule = "{\"id\":\"r1\",\"name\":\"r1\",\"enabled\":true,\"rowScope\":\"items\","
                + "\"when\":" + cond("price") + ",\"then\":" + action("qty") + "}";
        assertDoesNotThrow(() ->
                validator.validateLogicRuleFields(form(FIELDS_WITH_SUB_TABLE, "[" + rowRule + "]")));
    }

    /** 行作用域规则引用了该子表没有的明细列 */
    @Test
    public void testValidateLogicRuleFields_rowScopeColumnMissing() {
        lenient().when(widgetContractRegistry.getSubTableWidgetType()).thenReturn("group");
        String rowRule = "{\"id\":\"r1\",\"name\":\"r1\",\"enabled\":true,\"rowScope\":\"items\","
                + "\"when\":" + cond("price") + ",\"then\":" + action("deleted") + "}";
        assertServiceException(
                () -> validator.validateLogicRuleFields(
                        form(FIELDS_WITH_SUB_TABLE, "[" + rowRule + "]")),
                FORM_DESIGN_LOGIC_RULE_FIELD_NOT_EXISTS, "r1", "deleted");
    }

    /**
     * 行作用域规则里的主表字段名不会被放行
     *
     * rowScope 规则的求值上下文是行数据，主表字段在那里取不到 ——
     * 所以 amount 对一条 items 作用域的规则来说就是「不存在的字段」。
     */
    @Test
    public void testValidateLogicRuleFields_rowScopeRejectsMainField() {
        lenient().when(widgetContractRegistry.getSubTableWidgetType()).thenReturn("group");
        String rowRule = "{\"id\":\"r1\",\"name\":\"r1\",\"enabled\":true,\"rowScope\":\"items\","
                + "\"when\":" + cond("price") + ",\"then\":" + action("amount") + "}";
        assertServiceException(
                () -> validator.validateLogicRuleFields(
                        form(FIELDS_WITH_SUB_TABLE, "[" + rowRule + "]")),
                FORM_DESIGN_LOGIC_RULE_FIELD_NOT_EXISTS, "r1", "amount");
    }
}
