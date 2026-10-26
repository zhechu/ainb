package cn.enlev.ainb.module.infra.framework.datainterface.core.script;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ScriptPostProcessor} 的单元测试
 *
 * 执行任意脚本是本功能最大的安全风险面，沙箱限制必须逐条验证真的生效，
 * 而不是「配了选项就以为安全」。
 *
 * @author 走进新时代
 */
class ScriptPostProcessorTest {

    private final ScriptPostProcessor processor = new ScriptPostProcessor();

    private List<Map<String, Object>> sampleData() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", 1);
        a.put("name", "A");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("id", 2);
        b.put("name", "B");
        return List.of(a, b);
    }

    // ==================== 基础功能 ====================

    @Test
    @DisplayName("脚本为空：原样返回，不产生额外开销")
    void testBlankScript() {
        Object data = sampleData();
        assertSame(data, processor.process(null, data));
        assertSame(data, processor.process("", data));
        assertSame(data, processor.process("   ", data));
    }

    @Test
    @DisplayName("原样返回 data")
    void testReturnData() {
        Object result = processor.process("return data;", sampleData());
        assertInstanceOf(List.class, result);
        assertEquals(2, ((List<?>) result).size());
    }

    @Test
    @DisplayName("字段映射：把结果转成下拉框需要的结构")
    void testMapFields() {
        Object result = processor.process(
                "return data.map(function(r){ return { value: r.id, label: r.name }; });",
                sampleData());
        assertInstanceOf(List.class, result);
        List<?> list = (List<?>) result;
        assertEquals(2, list.size());
        Map<?, ?> first = (Map<?, ?>) list.get(0);
        assertEquals(1L, first.get("value"), "整数不该变成小数");
        assertEquals("A", first.get("label"));
    }

    @Test
    @DisplayName("过滤")
    void testFilter() {
        Object result = processor.process(
                "return data.filter(function(r){ return r.id > 1; });", sampleData());
        assertEquals(1, ((List<?>) result).size());
    }

    @Test
    @DisplayName("返回标量")
    void testScalar() {
        assertEquals(2L, processor.process("return data.length;", sampleData()));
        assertEquals("hello", processor.process("return 'hello';", sampleData()));
        assertEquals(true, processor.process("return true;", sampleData()));
        assertNull(processor.process("return null;", sampleData()));
    }

    @Test
    @DisplayName("整数与小数分别映射，避免 1 变成 1.0")
    void testNumberType() {
        assertEquals(3L, processor.process("return 1 + 2;", null));
        assertEquals(1.5, processor.process("return 3 / 2;", null));
    }

    // ==================== 沙箱限制 ====================

    @Test
    @DisplayName("⚠️ 禁止访问 Java 类")
    void testSandbox_noJavaType() {
        ServiceException ex = assertThrows(ServiceException.class, () ->
                processor.process("return Java.type('java.lang.System').getenv();", null));
        assertTrue(ex.getMessage().contains("脚本执行失败"), ex.getMessage());
    }

    @Test
    @DisplayName("⚠️ 禁止通过 Java 反射读文件")
    void testSandbox_noFileAccess() {
        assertThrows(ServiceException.class, () ->
                processor.process(
                        "var F = Java.type('java.io.File'); return new F('/').list();", null));
    }

    @Test
    @DisplayName("⚠️ 禁止创建线程")
    void testSandbox_noThread() {
        assertThrows(ServiceException.class, () ->
                processor.process(
                        "var T = Java.type('java.lang.Thread'); new T().start(); return 1;", null));
    }

    @Test
    @DisplayName("⚠️ 死循环会被超时中断，不会挂住线程")
    void testSandbox_timeout() {
        long start = System.currentTimeMillis();
        ServiceException ex = assertThrows(ServiceException.class, () ->
                processor.process("while(true){}", null));
        long cost = System.currentTimeMillis() - start;
        assertTrue(ex.getMessage().contains("超时"), ex.getMessage());
        // 上限从常量推导，不写死数字 —— 这个超时值曾被悄悄从 2 秒改成 10 秒，
        // 而注释与本用例都没跟着动，于是测试红了很久没人当回事。
        // 引用常量之后，改值要么连带改这里，要么立刻暴露成失败
        long limit = ScriptPostProcessor.TIMEOUT_MS * 3;
        assertTrue(cost < limit,
                "应在超时后尽快返回，上限 " + limit + "ms，实际耗时 " + cost + "ms");
    }

    @Test
    @DisplayName("⚠️ 脚本过长拒绝")
    void testSandbox_tooLong() {
        String longScript = "var x = 1;".repeat(ScriptPostProcessor.MAX_SCRIPT_LENGTH);
        assertThrows(ServiceException.class, () -> processor.process(longScript, null));
    }

    @Test
    @DisplayName("语法错误：报明确的失败原因")
    void testSyntaxError() {
        ServiceException ex = assertThrows(ServiceException.class, () ->
                processor.process("return {{{;", null));
        assertTrue(ex.getMessage().contains("脚本执行失败"), ex.getMessage());
    }

    @Test
    @DisplayName("运行时错误：报失败而非静默返回 null")
    void testRuntimeError() {
        assertThrows(ServiceException.class, () ->
                processor.process("return undefinedVariable.foo;", null));
    }

    @Test
    @DisplayName("校验方法可单独调用（保存时用）")
    void testValidateScript() {
        assertDoesNotThrow(() -> processor.validateScript(null));
        assertDoesNotThrow(() -> processor.validateScript("return data;"));
        assertThrows(ServiceException.class, () ->
                processor.validateScript("x".repeat(ScriptPostProcessor.MAX_SCRIPT_LENGTH + 1)));
    }

    @Test
    @DisplayName("data 为 null 时脚本仍可执行")
    void testNullData() {
        assertEquals("ok", processor.process("return 'ok';", null));
    }

    // ==================== 🔴 共享 Engine（B4）：隔离没被共享掉、中断没把 Engine 打坏 ====================
    //
    // 这三条与 agent 侧 ScriptSandboxTest 同款。冷启动的实测数字记在 ScriptSandbox#warmUp() 的表里，
    // 本类刻意不抄第二份，也不断言任何耗时阈值 —— surefire 一个 JVM 里跑整模块的测试，
    // 轮到本类时 Truffle 早被别的用例暖过了，耗时断言只会时而红时而绿，最后被人删掉。

    @Test
    @DisplayName("🔴 共享 Engine 不串全局对象：上一段脚本写进 globalThis 的东西，下一段读不到")
    void testSharedEngine_globalsDoNotLeak() {
        assertEquals(1L, processor.process("globalThis.leaked = 42; return 1;", null));
        assertEquals("undefined", processor.process("return typeof globalThis.leaked;", null),
                "两次执行共享一个 Engine，但必须各自一个新 Context —— "
                        + "全局对象串了就等于两个数据接口的后处理脚本能互相读写变量");
    }

    @Test
    @DisplayName("🔴 共享 Engine 不串 data 绑定：上一次的 __dataJson 不会残留到下一次")
    void testSharedEngine_dataBindingDoesNotLeak() {
        assertEquals(2, ((List<?>) processor.process("return data;", sampleData())).size());
        assertEquals(0L, processor.process("return data.length;", List.of()),
                "第二次传的是空列表，脚本就必须看到空列表 —— 绑定是 Context 级的，"
                        + "看到上一次的 2 条就意味着一个接口能读到另一个接口的返回值");
        assertEquals(Boolean.TRUE, processor.process("return data === null;", null),
                "data 为 null 时也得是新绑定的 null，而不是上一次那份残留");
    }

    /**
     * 🔴 本节最重要的一条：超时中断之后沙箱<b>仍然可用</b>
     *
     * <p>{@code Context.close(true)} 是「带着 cancelIfExecuting 关闭」，会把正在跑的那段 JS 硬掐掉。
     * 改成共享 Engine 之前，被掐的是那个一次性 Context 连同它自己的隐式 Engine —— 掐坏了也无所谓，
     * 下次重建一个就是。现在 Engine 是<b>长命</b>的，所以必须确认「掐掉一个 Context」不会殃及它。
     *
     * <p>⚠️ 顺序有讲究：先制造一次真超时，<b>再</b>跑一段正常脚本。反过来写就什么都没验到。
     */
    @Test
    @DisplayName("🔴 死循环被中断后，共享 Engine 没被打坏，后续脚本照常执行")
    void testSharedEngine_usableAfterTimeoutCancellation() {
        assertThrows(ServiceException.class, () -> processor.process("while(true){}", null),
                "死循环必须被超时掐掉（这一半上面 testSandbox_timeout 已验，这里只是把 Engine 置于同样的境地）");
        assertEquals("ok", processor.process("return 'ok';", null),
                "上一次是强行中断的，共享的 Engine 必须还能继续服务 —— 否则一个死循环脚本"
                        + "就能废掉此后所有数据接口的后处理，比它本来那个「首跑超时」的毛病严重得多");
    }

    /** 同一条，只是把「中断」换成「脚本自己抛错」—— 那是另一条关闭路径（finally 里非取消式的 close） */
    @Test
    @DisplayName("脚本运行期抛错之后，共享 Engine 同样没被打坏")
    void testSharedEngine_usableAfterScriptError() {
        assertThrows(ServiceException.class, () -> processor.process("throw new Error('炸了');", null));
        assertEquals("ok", processor.process("return 'ok';", null));
    }

    /**
     * 预热把整条真实代码路径跑通（建 Context、绑 {@code __dataJson}、{@code JSON.parse}、eval、
     * convert、close），且之后沙箱立即可用、可重复调用
     *
     * <p>🔴 预热失败绝不能冒出来：它挂在 {@code @PostConstruct} 上，一抛就是<b>容器起不来</b>，
     * 那会把一个后处理脚本的毛病放大成全站不可用。真正的兜底是 {@code warmUp()} 里那个
     * {@code catch (Throwable)} —— 谁把它删了，本条就失去意义。
     */
    @Test
    @DisplayName("预热不抛异常，且预热之后沙箱立即可用")
    void testWarmUp_neverThrowsAndLeavesSandboxUsable() {
        assertDoesNotThrow(() -> processor.warmUp());
        assertDoesNotThrow(() -> processor.warmUp());
        assertEquals(2L, processor.process("return 1 + 1;", null));
    }

}
