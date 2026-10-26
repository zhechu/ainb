package cn.enlev.ainb.module.infra.service.formdesign;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignDO;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import cn.enlev.ainb.module.infra.framework.formdesign.core.contract.WidgetContractRegistry;
import cn.enlev.ainb.module.infra.framework.formdesign.core.formula.FormulaEvaluator;
import cn.enlev.ainb.module.infra.framework.formdesign.core.formula.FormulaRecalculator;
import cn.enlev.ainb.module.infra.framework.formdesign.core.logic.FormLogicGuard;
import cn.enlev.ainb.module.infra.framework.formdesign.core.logic.LogicRuleEvaluator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Spy;

import java.util.List;

import static cn.enlev.ainb.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.FORM_DESIGN_FORMULA_NODE_UNKNOWN;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.FORM_DESIGN_MASK_RULE_INVALID;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * {@link FormDesignPublishValidator} 中「公式节点类型」与「脱敏策略」两段校验的单元测试
 *
 * <h3>这两段校验各自防的是什么</h3>
 *
 * 它们防的是同一类缺陷：<b>配了、保存成功了、但不生效，且没有任何提示</b>。
 *
 * <ul>
 *   <li><b>公式节点类型</b>：{@code FormulaEvaluator#eval} 对认不出的节点类型
 *       返回 null（那是为兼容旧版 AST 的有意设计）。于是把字面量的 type
 *       写成 {@code value} 而不是 {@code num} 时，整条公式算出 0 ——
 *       发布不报错、运行时不报错，列表上只是那一列永远是 0</li>
 *   <li><b>脱敏策略</b>：{@code MaskRule#parse} 对认不出的策略名返回 NONE
 *       （不脱敏，同样是有意的 —— 拼错的配置不该把正常字段变成一片星号）。
 *       于是 {@code maskRule} 写成对象或拼错时脱敏静默失效，
 *       而<b>脱敏是敏感字段目前唯一的防线</b>，失效意味着列表、详情、导出
 *       三个出口全是明文</li>
 * </ul>
 *
 * 两处的运行时兜底都是对的，不该改；要补的是<b>发布这一关</b>。
 *
 * <p>公式解析器与联动守卫用真实实例而非 mock：要守的正是「写错了会被拦住」
 * 这条链路，mock 掉解析器等于把被测逻辑一起 mock 掉了。
 *
 * @author 走进新时代
 */
public class FormDesignSilentConfigValidatorTest extends BaseMockitoUnitTest {

    @InjectMocks
    private FormDesignPublishValidator validator;

    @Spy
    private FormulaRecalculator formulaRecalculator = new FormulaRecalculator();

    @Spy
    private FormLogicGuard logicGuard = newLogicGuard();

    @Spy
    private WidgetContractRegistry widgetContractRegistry = newRealRegistry();

    private static WidgetContractRegistry newRealRegistry() {
        WidgetContractRegistry registry = new WidgetContractRegistry();
        registry.init();
        return registry;
    }

    private static FormLogicGuard newLogicGuard() {
        FormLogicGuard guard = new FormLogicGuard();
        // FormLogicGuard 只用到 evaluator，这里手工装上 —— @Spy 不会做依赖注入
        try {
            java.lang.reflect.Field field = FormLogicGuard.class.getDeclaredField("evaluator");
            field.setAccessible(true);
            field.set(guard, new LogicRuleEvaluator());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return guard;
    }

    private FormDesignDO design(String... rules) {
        FormDesignDO design = new FormDesignDO();
        design.setFormFields(FormFieldsCodec.write(List.of(rules)));
        return design;
    }

    // ==================== 公式节点类型 ====================

    /** 一条合法的公式：{amount} * 0.3 */
    private static final String GOOD_FORMULA =
            "{\"type\":\"Formula\",\"field\":\"limit\",\"title\":\"授信额度\",\"props\":{"
                    + "\"expression\":\"{amount} * 0.3\",\"ast\":{\"type\":\"binary\",\"op\":\"*\","
                    + "\"left\":{\"type\":\"field\",\"name\":\"amount\"},"
                    + "\"right\":{\"type\":\"num\",\"value\":0.3}}}}";

    /** 同一条公式，字面量的 type 写成了 value */
    private static final String BAD_FORMULA =
            "{\"type\":\"Formula\",\"field\":\"limit\",\"title\":\"授信额度\",\"props\":{"
                    + "\"expression\":\"{amount} * 0.3\",\"ast\":{\"type\":\"binary\",\"op\":\"*\","
                    + "\"left\":{\"type\":\"field\",\"name\":\"amount\"},"
                    + "\"right\":{\"type\":\"value\",\"value\":0.3}}}}";

    private static final String AMOUNT_FIELD =
            "{\"type\":\"inputNumber\",\"field\":\"amount\",\"title\":\"年营业额\"}";

    @Test
    @DisplayName("合法公式放过")
    void testGoodFormulaPasses() {
        assertDoesNotThrow(() ->
                validator.validateFormulaFields(design(AMOUNT_FIELD, GOOD_FORMULA)));
    }

    @Test
    @DisplayName("⚠️ 字面量写成 value 被拒绝 —— 否则整条公式静默算成 0")
    void testUnknownLiteralTypeRejected() {
        assertServiceException(
                () -> validator.validateFormulaFields(design(AMOUNT_FIELD, BAD_FORMULA)),
                FORM_DESIGN_FORMULA_NODE_UNKNOWN, "limit", "value");
    }

    @Test
    @DisplayName("⚠️ 嵌在函数参数里的坏节点也要被找出来")
    void testUnknownTypeInCallArgsRejected() {
        String rule = "{\"type\":\"Formula\",\"field\":\"limit\",\"title\":\"授信额度\",\"props\":{"
                + "\"expression\":\"ROUND({amount}, 2)\",\"ast\":{\"type\":\"call\",\"name\":\"ROUND\","
                + "\"args\":[{\"type\":\"field\",\"name\":\"amount\"},"
                + "{\"type\":\"literal\",\"value\":2}]}}}";
        assertServiceException(
                () -> validator.validateFormulaFields(design(AMOUNT_FIELD, rule)),
                FORM_DESIGN_FORMULA_NODE_UNKNOWN, "limit", "literal");
    }

    @Test
    @DisplayName("⚠️ 节点缺 type 也要被拒绝，而不是当成「没配」放过")
    void testMissingTypeRejected() {
        String rule = "{\"type\":\"Formula\",\"field\":\"limit\",\"title\":\"授信额度\",\"props\":{"
                + "\"expression\":\"{amount} * 0.3\",\"ast\":{\"type\":\"binary\",\"op\":\"*\","
                + "\"left\":{\"type\":\"field\",\"name\":\"amount\"},"
                + "\"right\":{\"value\":0.3}}}}";
        // 缺 type 时报的是「(空)」而不是 null —— 报 null 用户看不出是「没填」
        assertServiceException(
                () -> validator.validateFormulaFields(design(AMOUNT_FIELD, rule)),
                FORM_DESIGN_FORMULA_NODE_UNKNOWN, "limit", "(空)");
    }

    @Test
    @DisplayName("没有公式字段时不报错")
    void testNoFormulaPasses() {
        assertDoesNotThrow(() -> validator.validateFormulaFields(design(AMOUNT_FIELD)));
    }

    // ==================== 脱敏策略 ====================

    @Test
    @DisplayName("合法的脱敏策略放过")
    void testGoodMaskRulePasses() {
        assertDoesNotThrow(() -> validator.validateMaskRules(design(
                "{\"type\":\"input\",\"field\":\"mobile\",\"title\":\"手机号\","
                        + "\"props\":{\"maskRule\":\"MOBILE\"}}",
                "{\"type\":\"input\",\"field\":\"idCard\",\"title\":\"身份证\","
                        + "\"props\":{\"maskRule\":\"ID_CARD\"}}")));
    }

    @Test
    @DisplayName("没配脱敏的字段放过")
    void testNoMaskRulePasses() {
        assertDoesNotThrow(() -> validator.validateMaskRules(design(
                "{\"type\":\"input\",\"field\":\"name\",\"title\":\"姓名\"}",
                "{\"type\":\"input\",\"field\":\"code\",\"title\":\"编码\",\"props\":{}}")));
    }

    @Test
    @DisplayName("显式写 NONE 放过 —— 那是「明确不脱敏」，不是配错")
    void testExplicitNonePasses() {
        assertDoesNotThrow(() -> validator.validateMaskRules(design(
                "{\"type\":\"input\",\"field\":\"name\",\"title\":\"姓名\","
                        + "\"props\":{\"maskRule\":\"NONE\"}}")));
    }

    @Test
    @DisplayName("⚠️ maskRule 写成对象被拒绝 —— 否则脱敏静默失效，三个出口全是明文")
    void testObjectMaskRuleRejected() {
        assertServiceException(
                () -> validator.validateMaskRules(design(
                        "{\"type\":\"input\",\"field\":\"mobile\",\"title\":\"联系电话\","
                                + "\"props\":{\"maskRule\":{\"type\":\"MOBILE\"}}}")),
                // 报错里把原样的 JSON 带出来 —— 用户得看到自己填的是什么
                FORM_DESIGN_MASK_RULE_INVALID, "联系电话", "{\"type\":\"MOBILE\"}");
    }

    @Test
    @DisplayName("⚠️ 策略名拼错被拒绝")
    void testTypoMaskRuleRejected() {
        assertServiceException(
                () -> validator.validateMaskRules(design(
                        "{\"type\":\"input\",\"field\":\"mobile\",\"title\":\"联系电话\","
                                + "\"props\":{\"maskRule\":\"PHONE\"}}")),
                FORM_DESIGN_MASK_RULE_INVALID, "联系电话", "PHONE");
    }

    @Test
    @DisplayName("⚠️ 子表明细列里的坏脱敏配置同样要拦 —— 把敏感字段挪进明细不该能绕过")
    void testMaskRuleInSubTableRejected() {
        String rule = "{\"type\":\"group\",\"field\":\"contacts\",\"title\":\"联系人明细\","
                + "\"props\":{\"rule\":["
                + "{\"type\":\"input\",\"field\":\"tel\",\"title\":\"明细电话\","
                + "\"props\":{\"maskRule\":\"MOBILE_PHONE\"}}]}}";
        assertServiceException(() -> validator.validateMaskRules(design(rule)),
                FORM_DESIGN_MASK_RULE_INVALID, "明细电话", "MOBILE_PHONE");
    }

    @Test
    @DisplayName("⚠️ 藏在布局容器里的坏脱敏配置同样要拦")
    void testMaskRuleInLayoutRejected() {
        String rule = "{\"type\":\"elCard\",\"children\":["
                + "{\"type\":\"input\",\"field\":\"tel\",\"title\":\"卡片里的电话\","
                + "\"props\":{\"maskRule\":\"TEL\"}}]}";
        assertServiceException(() -> validator.validateMaskRules(design(rule)),
                FORM_DESIGN_MASK_RULE_INVALID, "卡片里的电话", "TEL");
    }

}
