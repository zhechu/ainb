package cn.enlev.ainb.module.infra.service.formdesign;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import cn.enlev.ainb.module.infra.framework.formdesign.core.contract.WidgetContractRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Spy;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FormDesignPublishValidator} 中「字段标识不重复」校验的单元测试
 *
 * 本类里按钮 key、页签 key、表名都查了重复，唯独表单字段的 field 没查 —— 这是补上的那一项。
 *
 * <p>为什么重要：字段映射（tableBinding.fieldMapping）是按 field 组织的 Map，
 * 重复意味着后写的覆盖先写的。界面上两个输入框、用户填了两个不同的值，
 * 入库只剩一个，且全程不报错 —— 又一个「配了但没生效」。
 *
 * <p>设计器拖控件时会自动生成不重名的 field，所以人工操作碰不到；
 * 但脚本生成、接口直传、导入历史配置都可能产生重复。
 *
 * @author 走进新时代
 */
public class FormDesignFieldKeyUniqueValidatorTest extends BaseMockitoUnitTest {

    @InjectMocks
    private FormDesignPublishValidator validator;

    /** 真实注册表：子表边界的判定要读契约里的 structural.subTableWidgetType */
    @Spy
    private WidgetContractRegistry widgetContractRegistry = newRealRegistry();

    private static WidgetContractRegistry newRealRegistry() {
        WidgetContractRegistry registry = new WidgetContractRegistry();
        registry.init();
        return registry;
    }

    private String fields(String... rules) {
        return FormFieldsCodec.write(List.of(rules));
    }

    // ==================== 应当拦住 ====================

    @Test
    @DisplayName("⚠️ 同名字段：拒绝，并报出重复次数")
    void testDuplicateFieldRejected() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validateFieldKeysUnique(fields(
                        "{\"type\":\"input\",\"field\":\"amount\",\"title\":\"金额\"}",
                        "{\"type\":\"inputNumber\",\"field\":\"amount\",\"title\":\"金额（数字）\"}")));
        assertTrue(ex.getMessage().contains("amount"), ex.getMessage());
    }

    @Test
    @DisplayName("⚠️ 重复藏在布局容器里也要查出来 —— 栅格不是字段域")
    void testDuplicateInsideLayoutRejected() {
        assertThrows(ServiceException.class,
                () -> validator.validateFieldKeysUnique(fields(
                        "{\"type\":\"input\",\"field\":\"name\",\"title\":\"姓名\"}",
                        "{\"type\":\"fcRow\",\"children\":["
                                + "{\"type\":\"col\",\"children\":["
                                + "{\"type\":\"input\",\"field\":\"name\",\"title\":\"又一个姓名\"}]}]}")));
    }

    @Test
    @DisplayName("⚠️ 三次重复也要报，且次数要对")
    void testTripleDuplicateReportsCount() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validateFieldKeysUnique(fields(
                        "{\"type\":\"input\",\"field\":\"code\"}",
                        "{\"type\":\"input\",\"field\":\"code\"}",
                        "{\"type\":\"input\",\"field\":\"code\"}")));
        assertTrue(ex.getMessage().contains("3"), ex.getMessage());
    }

    // ==================== 应当放过 ====================

    @Test
    @DisplayName("字段各不相同：放过")
    void testDistinctFieldsPass() {
        assertDoesNotThrow(() -> validator.validateFieldKeysUnique(fields(
                "{\"type\":\"input\",\"field\":\"name\"}",
                "{\"type\":\"inputNumber\",\"field\":\"amount\"}",
                "{\"type\":\"datePicker\",\"field\":\"apply_time\"}")));
    }

    @Test
    @DisplayName("⚠️ 子表字段与主表字段同名：放过 —— 它们落在不同的物理表上")
    void testSameNameAcrossSubTablePasses() {
        assertDoesNotThrow(() -> validator.validateFieldKeysUnique(fields(
                "{\"type\":\"input\",\"field\":\"remark\",\"title\":\"主表备注\"}",
                "{\"type\":\"group\",\"field\":\"items\",\"title\":\"明细\",\"children\":["
                        + "{\"type\":\"input\",\"field\":\"remark\",\"title\":\"明细备注\"}]}")));
    }

    @Test
    @DisplayName("纯展示控件没有 field：不参与查重")
    void testDisplayOnlyWidgetsPass() {
        assertDoesNotThrow(() -> validator.validateFieldKeysUnique(fields(
                "{\"type\":\"div\",\"_fc_drag_tag\":\"text\",\"title\":\"说明文字\"}",
                "{\"type\":\"div\",\"_fc_drag_tag\":\"text\",\"title\":\"另一段说明\"}",
                "{\"type\":\"elDivider\",\"title\":\"分割线\"}")));
    }

    @Test
    @DisplayName("空的或解析不出的 formFields 不报错，交给其它校验项处理")
    void testBlankFieldsPass() {
        assertDoesNotThrow(() -> validator.validateFieldKeysUnique(null));
        assertDoesNotThrow(() -> validator.validateFieldKeysUnique(""));
        assertDoesNotThrow(() -> validator.validateFieldKeysUnique(fields("not-a-json")));
    }

}
