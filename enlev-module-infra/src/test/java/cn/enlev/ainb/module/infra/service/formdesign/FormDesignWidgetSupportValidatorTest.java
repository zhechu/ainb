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

import static cn.enlev.ainb.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.FORM_DESIGN_WIDGET_UNSUPPORTED;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FormDesignPublishValidator} 中「控件是否受支持」校验的单元测试
 *
 * 这段校验防的是一类零报错的数据丢失：设计器内核提供的控件比本模块能正确落库的控件多。
 * 最典型的是「表格表单」(tableForm) —— 内核里它和子表控件一样是 array 值的 subForm，
 * 但本模块的子表绑定只认 group。改造前它在菜单里可拖、发布也不报错，
 * 用户填完明细提交、接口返回成功，数据却一行都没进库。
 *
 * <p>注册表用真实实例而非 mock：要守的正是「契约怎么声明，校验就怎么拦」这条链路，
 * mock 掉注册表等于把被测逻辑一起 mock 掉了。
 *
 * @author 走进新时代
 */
public class FormDesignWidgetSupportValidatorTest extends BaseMockitoUnitTest {

    @InjectMocks
    private FormDesignPublishValidator validator;

    /**
     * 真实注册表。@Spy 让 Mockito 把这个真实实例注入 @InjectMocks 的目标
     */
    @Spy
    private WidgetContractRegistry widgetContractRegistry = newRealRegistry();

    private static WidgetContractRegistry newRealRegistry() {
        WidgetContractRegistry registry = new WidgetContractRegistry();
        registry.init();
        return registry;
    }

    /** 把若干条规则 JSON 打包成 formFields */
    private String fields(String... rules) {
        return FormFieldsCodec.write(List.of(rules));
    }

    // ==================== 应当放过 ====================

    @Test
    @DisplayName("常规控件放过")
    void testSupportedWidgetsPass() {
        assertDoesNotThrow(() -> validator.validateWidgetsSupported(fields(
                "{\"type\":\"input\",\"field\":\"name\",\"title\":\"姓名\"}",
                "{\"type\":\"inputNumber\",\"field\":\"amount\",\"title\":\"金额\"}",
                "{\"type\":\"group\",\"field\":\"items\",\"title\":\"明细\"}")));
    }

    @Test
    @DisplayName("⚠️ 契约里没登记的控件：拒绝（原先是放过的，已反转）")
    void testUnknownWidgetRejected() {
        // 原先这里断言「放过」，理由是「契约可能尚未覆盖某个新控件，不该把用户卡在发布环节」。
        //
        // 反转的理由：代价不对等。放过换来的好处是「新控件没登记时也能先发布」，
        // 而新控件没登记本身就是该修的缺陷（WidgetContractRegistryTest 与
        // form-widget-contract-sync 两道一致性测试就是干这个的）；
        // 代价却是 type 写错一个字母就静默通过 —— 脚本生成、接口直传、导入历史配置都可能，
        // 运行时既渲染不出来也存不进库，全程不报错，正是本子系统最难反查的那类缺陷。
        ServiceException ex = assertThrows(ServiceException.class,
                () -> validator.validateWidgetsSupported(
                        fields("{\"type\":\"SomeBrandNewWidget\",\"field\":\"x\",\"title\":\"新控件\"}")));
        assertTrue(ex.getMessage().contains("SomeBrandNewWidget"), ex.getMessage());
    }

    @Test
    @DisplayName("⚠️ 文字/间距落库都是 type=div，按 _fc_drag_tag 认身份，不能误拦")
    void testDivWidgetsResolvedByDragTag() {
        // 这两个纯展示控件在内核里落库的 type 都是 div，契约里登记的却是 text / space。
        // 只看 type 的话它们在契约里永远查不到，收紧后会被当成未登记类型误拦 ——
        // 控件总览示例（test_demo_widget_gallery）就用了它们。
        assertDoesNotThrow(() -> validator.validateWidgetsSupported(fields(
                "{\"type\":\"div\",\"_fc_drag_tag\":\"text\",\"title\":\"文字\"}",
                "{\"type\":\"div\",\"_fc_drag_tag\":\"space\",\"title\":\"间距\"}")));
    }

    @Test
    @DisplayName("空的或解析不出的 formFields 不报错，交给其它校验项处理")
    void testBlankFieldsPass() {
        assertDoesNotThrow(() -> validator.validateWidgetsSupported(null));
        assertDoesNotThrow(() -> validator.validateWidgetsSupported(""));
        assertDoesNotThrow(() -> validator.validateWidgetsSupported(fields("not-a-json")));
    }

    // ==================== 应当拦住 ====================

    @Test
    @DisplayName("⚠️ 表格表单被拒绝，并提示改用子表")
    void testTableFormRejected() {
        assertServiceException(
                () -> validator.validateWidgetsSupported(
                        fields("{\"type\":\"tableForm\",\"field\":\"items\",\"title\":\"订单明细\"}")),
                FORM_DESIGN_WIDGET_UNSUPPORTED, "订单明细", "子表");
    }

    @Test
    @DisplayName("⚠️ 分组（object 值的嵌套表单）被拒绝")
    void testSubFormRejected() {
        assertServiceException(
                () -> validator.validateWidgetsSupported(
                        fields("{\"type\":\"subForm\",\"field\":\"ext\",\"title\":\"扩展信息\"}")),
                FORM_DESIGN_WIDGET_UNSUPPORTED, "扩展信息", "子表");
    }

    @Test
    @DisplayName("内核内置上传被拒绝，并提示改用自建上传控件")
    void testBuiltInUploadRejected() {
        assertServiceException(
                () -> validator.validateWidgetsSupported(
                        fields("{\"type\":\"upload\",\"field\":\"file\",\"title\":\"附件\"}")),
                FORM_DESIGN_WIDGET_UNSUPPORTED, "附件", "文件上传 / 单图上传 / 多图上传");
    }

    @Test
    @DisplayName("内核内置富文本被拒绝，并提示改用自建富文本")
    void testBuiltInEditorRejected() {
        assertServiceException(
                () -> validator.validateWidgetsSupported(
                        fields("{\"type\":\"fcEditor\",\"field\":\"content\",\"title\":\"正文\"}")),
                FORM_DESIGN_WIDGET_UNSUPPORTED, "正文", "富文本");
    }

    @Test
    @DisplayName("⚠️ 已删除的 ApiSelect 被拒绝，并提示改用远程选择器")
    void testDeletedApiSelectRejected() {
        // ApiSelect 在 B3 被删除，契约条目保留并标为不支持 ——
        // 万一有历史表单还留着它，要在发布时明确报错而不是运行时静默失效
        assertServiceException(
                () -> validator.validateWidgetsSupported(
                        fields("{\"type\":\"ApiSelect\",\"field\":\"c\",\"title\":\"老的接口选择器\"}")),
                FORM_DESIGN_WIDGET_UNSUPPORTED, "老的接口选择器", "远程选择器");
    }

    @Test
    @DisplayName("没填标题时用契约里的控件名报错，而不是报一个空字符串")
    void testFallbackToContractLabel() {
        assertServiceException(
                () -> validator.validateWidgetsSupported(
                        fields("{\"type\":\"tableForm\",\"field\":\"items\"}")),
                FORM_DESIGN_WIDGET_UNSUPPORTED, "表格表单（不支持）", "子表");
    }

    // ==================== 递归：藏在容器里也要被找到 ====================

    @Test
    @DisplayName("⚠️ 藏在布局容器 children 里的不支持控件也要被拦住")
    void testNestedInLayoutContainer() {
        String rule = "{\"type\":\"fcRow\",\"children\":["
                + "{\"type\":\"col\",\"children\":["
                + "{\"type\":\"tableForm\",\"field\":\"items\",\"title\":\"藏在栅格里的明细\"}"
                + "]}]}";
        assertServiceException(
                () -> validator.validateWidgetsSupported(fields(rule)),
                FORM_DESIGN_WIDGET_UNSUPPORTED, "藏在栅格里的明细", "子表");
    }

    @Test
    @DisplayName("⚠️ 藏在子表控件 props.rule 里的不支持控件也要被拦住")
    void testNestedInSubTableRule() {
        String rule = "{\"type\":\"group\",\"field\":\"items\",\"title\":\"明细\","
                + "\"props\":{\"rule\":["
                + "{\"type\":\"subForm\",\"field\":\"nested\",\"title\":\"明细里的嵌套分组\"}"
                + "]}}";
        assertServiceException(
                () -> validator.validateWidgetsSupported(fields(rule)),
                FORM_DESIGN_WIDGET_UNSUPPORTED, "明细里的嵌套分组", "子表");
    }

}
