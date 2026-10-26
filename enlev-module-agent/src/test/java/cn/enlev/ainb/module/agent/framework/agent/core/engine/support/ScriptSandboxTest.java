package cn.enlev.ainb.module.agent.framework.agent.core.engine.support;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScriptSandbox} 单测：专管 <b>B4（共享 Engine + 启动预热）</b> 这一改动带来的<b>新</b>风险面
 *
 * <p>脚本节点的基础语义（返回值转换、读 state、各类沙箱封锁、超时、脚本过长、fail-close）已由
 * {@code ScriptNodeExecutorTest} 用<b>真沙箱</b>逐条覆盖，本类<b>刻意不重复</b>。
 * 这里只钉三件「改成共享 Engine 之后才存在」的事：
 * <ol>
 *   <li>🔴 <b>隔离没被共享掉</b>：两次执行之间既不串全局对象、也不串 {@code __stateJson} 绑定。
 *       这是共享 Engine 唯一真可能捅出的娄子 —— 一旦有人日后为了再省一点开销改成「复用一个 Context」，
 *       这两条会立刻变红；</li>
 *   <li>🔴 <b>超时中断没把共享 Engine 打坏</b>：{@code future.cancel(true)} + {@code Context.close(true)}
 *       是<b>强行中断</b>一段正在跑的 JS。它作用在 Context 上，而 Context 现在挂在共享的 Engine 上 ——
 *       若中断把 Engine 也带坏了，后果是「一个死循环脚本废掉之后所有 script 节点」，
 *       比原来的冷启动超时严重得多。这条必须显式验；</li>
 *   <li><b>预热真的把沙箱跑通了</b>，且可重复调用（{@code @PostConstruct} 之外还有人被调到也不炸）。</li>
 * </ol>
 *
 * <p>⚠️ 刻意<b>不</b>断言「首次执行的耗时」：surefire 一个 JVM 里跑整模块的测试，
 * 轮到本类时 Truffle 早被别的测试暖过了，任何耗时阈值都只是随机数 ——
 * 写成断言就是一条时而红时而绿、最后被人删掉的测试。冷启动开销由 {@code warmUp()} 的启动日志实测。
 *
 * @author 走进新时代
 */
@DisplayName("agent ScriptSandbox 共享 Engine 与预热（B4）")
class ScriptSandboxTest {

    private ScriptSandbox sandbox;

    @BeforeEach
    void setUp() {
        sandbox = new ScriptSandbox();
    }

    @Test
    @DisplayName("🔴 共享 Engine 不串全局对象：上一段脚本写进 globalThis 的东西，下一段读不到")
    void eval_globalsDoNotLeakAcrossExecutions() {
        assertEquals(1L, sandbox.eval("globalThis.leaked = 42; return 1;", Map.of(),
                ScriptSandbox.DEFAULT_TIMEOUT_MS));

        assertEquals("undefined",
                sandbox.eval("return typeof globalThis.leaked;", Map.of(), ScriptSandbox.DEFAULT_TIMEOUT_MS),
                "两段脚本共享一个 Engine，但必须各自一个新 Context —— 全局对象串了就等于"
                        + "工作流之间能互相读写变量，那是比超时严重得多的问题");
    }

    @Test
    @DisplayName("🔴 共享 Engine 不串 state 绑定：上一次执行的 __stateJson 不会残留到下一次")
    void eval_stateBindingDoesNotLeakAcrossExecutions() {
        assertEquals(1L, sandbox.eval("return state.a;", Map.of("a", 1), ScriptSandbox.DEFAULT_TIMEOUT_MS));

        assertEquals("undefined",
                sandbox.eval("return typeof state.a;", Map.of(), ScriptSandbox.DEFAULT_TIMEOUT_MS),
                "state 为空的那次执行必须真的看不到上一次的数据 —— 绑定是 Context 级的，"
                        + "残留就意味着一个工作流能读到另一个的 state");
    }

    /**
     * 🔴 本类最重要的一条：超时中断之后沙箱<b>仍然可用</b>
     *
     * <p>{@code Context.close(true)} 是「带着 cancelIfExecuting 关闭」，它会把正在跑的那段 JS 硬掐掉。
     * 改成共享 Engine 之前，被掐的是那个一次性 Context 连同它自己的隐式 Engine —— 掐坏了也无所谓，
     * 下次重建一个就是。现在 Engine 是<b>长命</b>的，所以必须确认「掐掉一个 Context」不会殃及它。
     *
     * <p>⚠️ 顺序有讲究：先制造一次真超时，<b>再</b>跑一段正常脚本。反过来写就什么都没验到。
     */
    @Test
    @DisplayName("🔴 死循环被中断后，共享 Engine 没被打坏，后续脚本照常执行")
    void eval_sandboxStillUsableAfterTimeoutCancellation() {
        assertThrows(ServiceException.class,
                () -> sandbox.eval("while(true){}", Map.of(), 300L),
                "死循环必须被超时掐掉（这一半 ScriptNodeExecutorTest 已验，这里只是把 Engine 置于同样的境地）");

        assertEquals(3L, sandbox.eval("return 1 + 2;", Map.of(), ScriptSandbox.DEFAULT_TIMEOUT_MS),
                "上一次是强行中断的，共享的 Engine 必须还能继续服务 —— 否则一个死循环脚本"
                        + "就能废掉此后所有 script 节点，比它本来那个「首跑超时」的毛病严重得多");
    }

    /** 同一条，只是把「中断」换成「脚本自己抛错」—— 那是另一条关闭路径（finally 里非取消式的 close） */
    @Test
    @DisplayName("脚本运行期抛错之后，共享 Engine 同样没被打坏")
    void eval_sandboxStillUsableAfterScriptError() {
        assertThrows(ServiceException.class,
                () -> sandbox.eval("throw new Error('炸了');", Map.of(), ScriptSandbox.DEFAULT_TIMEOUT_MS));

        assertEquals("ok", sandbox.eval("return 'ok';", Map.of(), ScriptSandbox.DEFAULT_TIMEOUT_MS));
    }

    /**
     * 预热把整条真实代码路径跑通（建 Context、绑 {@code __stateJson}、{@code JSON.parse}、eval、
     * convert、close），且之后沙箱立即可用
     *
     * <p>🔴 这里给的是<b>远小于</b> {@link ScriptSandbox#DEFAULT_TIMEOUT_MS} 的预算：
     * 预热之后这段脚本只剩「建一个挂在已初始化 Engine 上的 Context + 跑一行 JS」，
     * 500ms 是宽裕的。它不是耗时基准（理由见类注释），只是「预热没把沙箱搞坏、且确实可用」的下界证明。
     */
    @Test
    @DisplayName("预热之后沙箱立即可用，且预热可重复调用")
    void warmUp_leavesSandboxUsable() {
        sandbox.warmUp();
        sandbox.warmUp();

        assertEquals(2L, sandbox.eval("return 1 + 1;", Map.of(), 500L),
                "预热必须是幂等的：@PostConstruct 只会调一次，但任何「再暖一遍」的调用都不该把 Engine 弄坏");
    }

    /**
     * 预热失败绝不能冒出来 —— 它挂在 {@code @PostConstruct} 上，一抛就是<b>容器起不来</b>
     *
     * <p>{@code enlev-dependencies/pom.xml} 里那段注释写着「GraalJS 各构件版本不匹配会打断
     * {@code JavaScriptLanguageProvider}」，那是个已知的、由依赖管理引起的失败模式。
     * 真出这种事，正确的表现是启动照常、等有人跑脚本时报出真错。
     *
     * <p>⚠️ 这条验不了「GraalJS 真坏了时 warmUp 不抛」（本仓库的依赖是好的，造不出那个场景），
     * 它验的是<b>契约</b>：warmUp 的返回类型是 void 且声明上不带 checked 异常，
     * 且正常路径下调用它不会抛。真正的兜底是 {@code warmUp()} 里那个 {@code catch (Throwable)} ——
     * 谁把它删了，本条与下面那条「预热后仍可用」会一起失去意义，故两条并列留着。
     */
    @Test
    @DisplayName("预热本身不抛异常（它挂在 @PostConstruct 上，一抛就是容器起不来）")
    void warmUp_neverThrows() {
        assertTrue(runQuietly(() -> sandbox.warmUp()),
                "warmUp 抛异常 ⟹ Spring 容器启动失败 ⟹ 一个 script 节点的毛病被放大成全站不可用");
        assertEquals(1L, sandbox.eval("return 1;", Map.of(), ScriptSandbox.DEFAULT_TIMEOUT_MS));
    }

    /** 跑一段不该抛的代码，抛了就记下来并返回 false（用来把「不抛」写成一条能红的断言） */
    private static boolean runQuietly(Runnable runnable) {
        try {
            runnable.run();
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }

}
