package cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.WorkflowContext;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.support.ScriptSandbox;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScriptNodeExecutor} + {@link ScriptSandbox} 单测（P7 收口，0 token，跑<b>真实</b> GraalJS 沙箱）
 *
 * <p>刻意用真沙箱而非 mock：脚本节点的价值与风险都在「真的执行了一段 JS 且被关死」，mock 掉沙箱等于什么都没验。
 * 沙箱安全限制逐条验证真的生效（不是「配了选项就以为安全」），与 infra {@code ScriptPostProcessorTest} 同款纪律：
 * <ol>
 *   <li>基础：return 标量/对象/数组、读 state、整数不变小数、自定义 outputVar</li>
 *   <li>🔴 沙箱：禁访问 Java 类、禁文件、禁线程、死循环超时中断、脚本过长拒绝</li>
 *   <li>fail-close：缺 code / 不支持的语言 / 语法错 / 运行期错 → 抛 ServiceException（绝不静默返回 null）</li>
 * </ol>
 *
 * @author 走进新时代
 */
@DisplayName("agent ScriptNodeExecutor GraalJS 沙箱脚本节点")
class ScriptNodeExecutorTest {

    private ScriptNodeExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new ScriptNodeExecutor();
        // 真沙箱：脚本节点的全部安全语义都在这里发生，不能 mock
        ReflectionTestUtils.setField(executor, "scriptSandbox", new ScriptSandbox());
    }

    private static WorkflowContext ctx(Map<String, Object> data) {
        OverAllState state = new OverAllState(new HashMap<>(data));
        return new WorkflowContext(state, 1L, "t1:1", AgentExecutionContext.ofForTest(1L, 1L));
    }

    private static NodeDTO node(Map<String, Object> config) {
        NodeDTO n = new NodeDTO();
        n.setId("calc");
        n.setType(ScriptNodeExecutor.TYPE);
        n.setConfig(config);
        return n;
    }

    private static Map<String, Object> cfg(String code) {
        Map<String, Object> c = new HashMap<>();
        c.put("code", code);
        return c;
    }

    // ==================== 基础功能 ====================

    @Test
    @DisplayName("getType 逐字对齐 DSL 节点类型")
    void getType_matchesDsl() {
        assertEquals("script", executor.getType());
    }

    @Test
    @DisplayName("return 标量：整数不变成小数")
    void execute_returnsScalar() {
        Map<String, Object> delta = executor.execute(node(cfg("return 1 + 2;")), ctx(Map.of()));
        assertEquals(3L, delta.get("script_result"));

        assertEquals(1.5, executor.execute(node(cfg("return 3 / 2;")), ctx(Map.of())).get("script_result"));
        assertEquals("hello", executor.execute(node(cfg("return 'hello';")), ctx(Map.of())).get("script_result"));
        assertEquals(true, executor.execute(node(cfg("return true;")), ctx(Map.of())).get("script_result"));
        assertNull(executor.execute(node(cfg("return null;")), ctx(Map.of())).get("script_result"));
    }

    @Test
    @DisplayName("读 state：脚本内以 state 对象访问工作流状态")
    void execute_readsState() {
        Map<String, Object> delta = executor.execute(node(cfg("return state.a + state.b;")),
                ctx(Map.of("a", 2, "b", 3)));
        assertEquals(5L, delta.get("script_result"));
    }

    @Test
    @DisplayName("读嵌套 state 并返回对象（转成纯 Java Map）")
    void execute_returnsObjectFromNestedState() {
        Map<String, Object> delta = executor.execute(
                node(cfg("return { total: state.order.price * state.order.qty, ok: true };")),
                ctx(Map.of("order", Map.of("price", 10, "qty", 3))));

        Object result = delta.get("script_result");
        assertInstanceOf(Map.class, result);
        Map<?, ?> map = (Map<?, ?>) result;
        assertEquals(30L, map.get("total"));
        assertEquals(true, map.get("ok"));
    }

    @Test
    @DisplayName("返回数组（转成纯 Java List），典型「过滤/映射」加工")
    void execute_returnsArray() {
        Map<String, Object> delta = executor.execute(
                node(cfg("return state.rows.filter(function(r){return r.id>1;}).map(function(r){return r.name;});")),
                ctx(Map.of("rows", List.of(Map.of("id", 1, "name", "A"), Map.of("id", 2, "name", "B")))));

        assertEquals(List.of("B"), delta.get("script_result"));
    }

    @Test
    @DisplayName("自定义 outputVar 生效")
    void execute_customOutputVar() {
        Map<String, Object> config = cfg("return 'hi';");
        config.put("outputVar", "greeting");
        Map<String, Object> delta = executor.execute(node(config), ctx(Map.of()));
        assertEquals("hi", delta.get("greeting"));
    }

    // ==================== 🔴 沙箱安全限制（逐条验证真的生效） ====================

    @Test
    @DisplayName("🔴 禁止访问 Java 类（HostAccess.NONE）")
    void execute_sandbox_noJavaType() {
        assertThrows(ServiceException.class, () ->
                executor.execute(node(cfg("return Java.type('java.lang.System').getenv();")), ctx(Map.of())));
    }

    @Test
    @DisplayName("🔴 禁止通过 Java 反射读文件")
    void execute_sandbox_noFileAccess() {
        assertThrows(ServiceException.class, () ->
                executor.execute(node(cfg("var F = Java.type('java.io.File'); return new F('/').list();")), ctx(Map.of())));
    }

    @Test
    @DisplayName("🔴 禁止创建线程")
    void execute_sandbox_noThread() {
        assertThrows(ServiceException.class, () ->
                executor.execute(node(cfg("var T = Java.type('java.lang.Thread'); new T().start(); return 1;")), ctx(Map.of())));
    }

    @Test
    @DisplayName("🔴 死循环被超时中断，不挂住线程；报错文案含超时")
    void execute_sandbox_timeout() {
        Map<String, Object> config = cfg("while(true){}");
        config.put("timeoutMs", 300);
        long start = System.currentTimeMillis();
        ServiceException ex = assertThrows(ServiceException.class,
                () -> executor.execute(node(config), ctx(Map.of())));
        long cost = System.currentTimeMillis() - start;
        assertTrue(ex.getMessage().contains("超时"), ex.getMessage());
        assertTrue(cost < 300 * 3, "应在超时后尽快返回，实际耗时 " + cost + "ms");
    }

    @Test
    @DisplayName("🔴 脚本过长拒绝（不进沙箱就拦下）")
    void execute_sandbox_tooLong() {
        String longScript = "var x = 1;".repeat(ScriptSandbox.MAX_SCRIPT_LENGTH);
        assertThrows(ServiceException.class,
                () -> executor.execute(node(cfg(longScript)), ctx(Map.of())));
    }

    // ==================== fail-close ====================

    @Test
    @DisplayName("缺 code → 抛 ServiceException（DSL 不合法）")
    void execute_missingCode_throws() {
        assertThrows(ServiceException.class, () -> executor.execute(node(new HashMap<>()), ctx(Map.of())));
        assertThrows(ServiceException.class, () -> executor.execute(node(cfg("   ")), ctx(Map.of())));
    }

    @Test
    @DisplayName("不支持的语言 → 抛 ServiceException（只认 javascript，绝不默认放行）")
    void execute_unsupportedLanguage_throws() {
        Map<String, Object> config = cfg("return 1;");
        config.put("language", "python");
        assertThrows(ServiceException.class, () -> executor.execute(node(config), ctx(Map.of())));
    }

    @Test
    @DisplayName("语法错误 → 报明确失败，而非静默返回 null")
    void execute_syntaxError_throws() {
        assertThrows(ServiceException.class, () -> executor.execute(node(cfg("return {{{;")), ctx(Map.of())));
    }

    @Test
    @DisplayName("运行期错误 → 抛 ServiceException，而非静默返回 null")
    void execute_runtimeError_throws() {
        assertThrows(ServiceException.class, () ->
                executor.execute(node(cfg("return undefinedVariable.foo;")), ctx(Map.of())));
    }

    // ==================== V22 那句用户可见文案的实测依据（缺陷 D4-19） ====================

    /**
     * 🔴 这两条钉的是<b>已经发给用户的那句话</b>，不是新需求。
     *
     * <p>{@code WorkflowDslValidator.validateScriptToolCalls}（V22）的报错原文写着
     * 「script 跑在沙箱里，只注入了一个只读的 state，<b>没有任何工具句柄，运行期必抛 ReferenceError</b>」。
     * 这句话在落地时是<b>推定</b>：D4-19 的现场（def {@code #463 wf_plan_muuj0est_2}）从来没被执行过 ——
     * 按平台铁律，草稿要点「确认并运行」才真跑，而 e2e 刻意不点（红线 16），
     * 所以 {@code _backend-run13.log} 里 {@code ReferenceError} 与 {@code tools is not defined} 都是 <b>0 次</b>。
     * 一条用户看得见的断言长期没有实测支撑，就是「配置保存成功但不生效且无提示」那一型的近亲：
     * 万一沙箱其实注了个 {@code tools} 句柄、或者 GraalJS 抛的是别的错，文案与提示词就都在骗人。
     * 这里用真沙箱把它量死（0 token）。
     */
    @Test
    @DisplayName("沙箱里根本没有 tools 这个全局 —— V22 文案「没有任何工具句柄」的实测依据")
    void execute_sandboxHasNoToolsGlobal() {
        // 直接问沙箱：typeof tools 是什么。返回 "undefined" 才叫「没有工具句柄」
        Object typeof = executor.execute(node(cfg("return typeof tools;")), ctx(Map.of())).get("script_result");
        assertEquals("undefined", typeof,
                "沙箱里存在 tools 全局 —— 那 V22 的文案「没有任何工具句柄」就是错的，必须改口径");
    }

    @Test
    @DisplayName("脚本里调 tools.form_save_draft(...) → 抛 ServiceException，且原文点名 ReferenceError（D4-19 现场复现）")
    void execute_scriptCallsTools_throwsReferenceError() {
        // 形状逐字取自 def #463 的 analyze_and_save 节点（D4-19 的现场），只把入参换成局部变量，
        // 好让「抛错的原因是 tools 不存在」而不是「formCode 未定义」——
        // 否则量到的是另一个 ReferenceError，V22 那句话依旧没被验到。
        String code = """
                var formCode = 'customer_visit_record';
                var tableName = 'biz_customer_visit_record';
                var fields = [{ field: 'next_follow_date', widget: 'datePicker', searchable: true }];
                var saveResult = tools.form_save_draft({
                  name: '客户拜访记录表',
                  code: formCode,
                  mainTable: tableName,
                  fields: fields
                });
                return { formId: saveResult.id };
                """;
        ServiceException ex = assertThrows(ServiceException.class,
                () -> executor.execute(node(cfg(code)), ctx(Map.of())));
        String msg = String.valueOf(ex.getMessage());
        assertTrue(msg.contains("ReferenceError"),
                "V22 的文案承诺「运行期必抛 ReferenceError」，实际报错原文是：" + msg);
        assertTrue(msg.contains("tools is not defined"),
                "报错要点名是 tools 不存在（否则用户看不出该改哪儿），实际原文是：" + msg);
    }

}
