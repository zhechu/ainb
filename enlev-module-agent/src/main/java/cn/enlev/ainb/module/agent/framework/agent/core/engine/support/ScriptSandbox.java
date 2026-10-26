package cn.enlev.ainb.module.agent.framework.agent.core.engine.support;

import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_SCRIPT_EXECUTE_FAIL;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_SCRIPT_TIMEOUT;

/**
 * {@code script} 节点的 GraalJS 沙箱（P7 收口）
 *
 * <h3>🔴 执行任意用户脚本是本模块最大的安全风险面，沙箱逐项封死</h3>
 * 与 infra 数据接口后处理脚本（{@code ScriptPostProcessor}）同款纪律——两处独立实现而非共用，
 * 是因为①架构红线禁止 agent import infra 的非 api 类；②两者错误语义/绑定不同（那边绑 {@code data}
 * 且带「data 实际是 {total,list}」的接口专属提示，这边绑工作流 {@code state}）。共用的只有「怎么关死沙箱」
 * 这段安全核心，逐字对齐：
 * <table>
 *   <tr><td>禁访问 Java 类/对象</td><td>{@code HostAccess.NONE} + {@code allowHostClassLookup} 全拒 + 禁 class loading</td></tr>
 *   <tr><td>禁 IO / 线程 / 进程 / 原生 / 环境变量 / 跨语言</td><td>对应 allow* 全 false / NONE</td></tr>
 *   <tr><td>执行超时</td><td>独立守护线程执行 + {@code Context#close(true)} 强制中断（毫秒级生效）</td></tr>
 *   <tr><td>超时预算不被冷启动吃掉</td><td>共享一个 {@code Engine} + 启动预热，见 {@link #engine} 与 {@link #warmUp()}</td></tr>
 *   <tr><td>脚本长度 / 结果规模 / 嵌套深度上限</td><td>防打爆内存与栈溢出</td></tr>
 * </table>
 *
 * <h3>🔴 状态以 JSON 文本传入，返回值转纯 Java 集合再带出</h3>
 * 入参 {@code state} 序列化成 JSON 文本绑定为 {@code __stateJson}，脚本内 {@code JSON.parse} 成 {@code state}
 * 对象——即便宿主访问已禁，少暴露一层 Java 对象少一分风险。返回值必须在 {@code Context} 关闭前
 * {@link #convert} 成纯 Java 值（Value 依赖 Context 存活，逃逸会偶发 IllegalStateException），
 * 也正好满足 DSL §3.2「state 只放 JSON 可序列化值」的硬约束（checkpoint 要序列化）。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class ScriptSandbox {

    /** 缺省执行超时（毫秒）：数据加工应当是毫秒级，给死循环留的时间越短越好 */
    public static final long DEFAULT_TIMEOUT_MS = 2000L;
    /** 超时上限（毫秒）：作者可通过 config.timeoutMs 上调，但不得越过此界，避免长时间占住 elastic 线程 */
    public static final long MAX_TIMEOUT_MS = 10_000L;
    /** 脚本长度上限（字符） */
    public static final int MAX_SCRIPT_LENGTH = 10_000;
    /** 结果元素数量上限，防脚本构造巨大数组打爆内存 */
    private static final int MAX_RESULT_SIZE = 100_000;
    /** 返回值转换的最大递归深度，防自引用结构栈溢出 */
    private static final int MAX_DEPTH = 20;

    /**
     * 脚本执行线程池：用独立守护线程是为了能超时中断（{@code Future.cancel} + {@code Context.close(true)}）。
     * 守护线程不阻塞应用退出。
     */
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "agent-script-sandbox");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 全部 Context 共用的一个 GraalJS 运行时（🔴 B4 的一半，另一半是 {@link #warmUp()}）
     *
     * <h3>为什么必须共享：不共享时<b>每次</b>执行都在重建语言运行时</h3>
     * {@code Context.newBuilder("js")} 不给 {@code .engine(...)} 时会<b>隐式新建一个 Engine</b>，
     * 于是 Truffle 运行时、JS 内建对象、代码缓存全都按次重建。而这份重建开销是落在
     * {@code eval} 里那句 {@code future.get(timeoutMs)} 的<b>计时区内</b>的 ——
     * {@code buildSandbox()} 是 {@link #doEval} 的第一条语句，计时却早就开始了。
     * 结果就是「首跑必超时」：JVM 内第一次跑脚本要吃掉进程级的 Truffle/JS 语言初始化（秒级），
     * 而预算只有 {@link #DEFAULT_TIMEOUT_MS} = 2 秒。
     *
     * <h3>🔴 共享 Engine <b>不削弱</b>沙箱，这一点会被反复问到</h3>
     * Engine 共享的只有<b>语言运行时</b>（Truffle 运行时、JS 内建、编译后的代码缓存），
     * 而 {@link #buildSandbox()} 里那九项 {@code allow*} 封锁<b>全是 Context 级策略</b>、逐字未动，
     * 变量绑定（{@code __stateJson}）也是 Context 级的。隔离粒度没有任何变化：
     * 仍然是<b>一次执行一个新 Context、用完立刻 {@code close(true)}</b>，
     * 两段脚本之间既不共享全局对象、也拿不到对方的绑定。
     */
    private final Engine engine = Engine.newBuilder("js").build();

    /**
     * 启动时把冷启动开销吃掉（🔴 B4 的另一半）
     *
     * <p>光共享 Engine 还不够：Engine 只省掉「按次重建运行时」，
     * <b>进程内第一次</b>建 Context 仍要付 Truffle 框架类初始化 + JS 语言 provider 的 ServiceLoader 发现。
     * 那笔钱若留给第一个真跑工作流的用户付，他看到的就是「脚本节点超时」——
     * 一个与他的脚本内容毫无关系的失败，且重跑一次就没了（于是永远复现不出来）。
     *
     * <p>🔴 预热必须<b>直接调 {@link #doEval}</b>、绕开 {@link #eval} 的超时：
     * 走 {@code eval} 的话预热自己就会超时（冷启动 &gt; 2 秒正是本条要修的缺陷），
     * 那会变成「预热失败 → 记一条 WARN → 首跑照样超时」，修了个寂寞。
     * 走 {@code doEval} 也顺带把真实代码路径整个跑了一遍（建 Context、绑 {@code __stateJson}、
     * {@code JSON.parse}、eval、{@link #convert}、{@code close}），而不是只暖一半。
     *
     * <p>🔴 预热失败<b>绝不能</b>让容器起不来，所以这里必须吞掉异常：
     * {@code enlev-dependencies/pom.xml} 里那段注释就写着「GraalJS 各构件版本不匹配会打断
     * {@code JavaScriptLanguageProvider}」—— 那是个<b>已知的</b>、由依赖管理引起的失败模式。
     * 真出这种事，正确的表现是启动照常、等有人跑脚本时报出真错，
     * 而不是整个后端起不来（那会把一个 script 节点的毛病放大成全站不可用）。
     *
     * <h3>🔴 实测（2026-10-18，冷 JVM、bare 单测进程、本机）</h3>
     * <table>
     *   <tr><td>进程内第一次 {@code Context.newBuilder("js").build()}（= 修复前的形状）</td><td><b>1091 ms</b></td></tr>
     *   <tr><td>之后每次隐式新建 Engine</td><td>22 ms</td></tr>
     *   <tr><td>{@code Engine.newBuilder("js").build()}</td><td>4 ms</td></tr>
     *   <tr><td>共享 Engine 上建 Context + eval（稳态）</td><td>6~14 ms</td></tr>
     *   <tr><td>{@code warmUp()}（JVM 已被上面几趟暖过）</td><td><b>408 ms</b></td></tr>
     *   <tr><td>预热之后一次真实 {@code eval}</td><td><b>15 ms</b>（预算 {@link #DEFAULT_TIMEOUT_MS} = 2000 ms）</td></tr>
     * </table>
     * 三点读数：① 冷启动那 1091 ms 是<b>光秃秃的 JVM</b> 里量的，占预算 55%；
     * 真跑在 Spring Boot 里（类加载器更重、JIT 全冷、还要抢启动期的 CPU 与磁盘）只会更长，
     * 与「首跑必超时」的现象吻合 —— 机制被量到了，不是推测。
     * ② {@code warmUp()} 那 408 ms <b>不全是</b> Truffle：JVM 已经暖过了，
     * 剩下的是 {@code JsonUtils} 第一次建 ObjectMapper 与 JS 里 {@code JSON.parse} 第一次被编译 ——
     * 也就是说预热顺带把「真实代码路径」上的其它首触成本一起付了，这正是它要走
     * {@link #doEval} 而不是随手 {@code eval("1")} 的原因。
     * ③ 代价是启动慢约 0.4~1.1 秒（infra 那一份因为 JVM 已暖会更便宜），换掉一次必失败的用户请求，值。
     */
    @PostConstruct
    public void warmUp() {
        long begin = System.currentTimeMillis();
        try {
            doEval("return state;", Map.of());
            log.info("[warmUp][GraalJS 沙箱预热完成，耗时 {} ms —— 这笔钱若不在这儿付，"
                    + "就会落在第一个跑 script 节点的用户头上并直接超时]", System.currentTimeMillis() - begin);
        } catch (Throwable ex) {
            log.warn("[warmUp][GraalJS 沙箱预热失败，耗时 {} ms；不影响启动，"
                    + "首次执行脚本时仍会重试并抛出真实错误]", System.currentTimeMillis() - begin, ex);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
        // Engine 与本 bean 同生命周期，显式关掉，免得 polyglot 的关闭钩子在 JVM 退出时报「engine 未关闭」。
        // doEval 的 finally 保证此刻已无存活的 Context
        engine.close();
    }

    /**
     * 在沙箱里执行一段 JS，返回转成纯 Java 值的结果。
     *
     * @param code      用户脚本；可直接写 {@code return ...}（会被包成函数体）。不可为空（调用方校验）
     * @param state     工作流当前状态（只读快照），脚本内以 {@code state} 对象访问
     * @param timeoutMs 执行超时（毫秒）；调用方负责夹到 [1, {@link #MAX_TIMEOUT_MS}]
     * @return 脚本返回值的纯 Java 表示（Map/List/String/Number/Boolean/null）
     */
    public Object eval(String code, Map<String, Object> state, long timeoutMs) {
        if (code.length() > MAX_SCRIPT_LENGTH) {
            throw exception(WORKFLOW_SCRIPT_EXECUTE_FAIL, "脚本过长，上限 " + MAX_SCRIPT_LENGTH + " 字符");
        }
        Future<Object> future = executor.submit(() -> doEval(code, state));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            // 取消任务，doEval 的 finally 会 close(true) 中断脚本
            future.cancel(true);
            throw exception(WORKFLOW_SCRIPT_TIMEOUT, timeoutMs);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            log.warn("[eval][script 节点脚本执行失败]", cause);
            String msg = cause == null ? "未知错误" : cause.getMessage();
            throw exception(WORKFLOW_SCRIPT_EXECUTE_FAIL, msg == null ? cause.getClass().getSimpleName() : msg);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw exception(WORKFLOW_SCRIPT_EXECUTE_FAIL, "脚本执行被中断");
        }
    }

    private Object doEval(String code, Map<String, Object> state) {
        Context context = buildSandbox();
        try {
            context.getBindings("js").putMember("__stateJson", JsonUtils.toJsonString(state == null ? Map.of() : state));
            // 包成函数体，让作者可直接写 return；state 由 JSON 文本解析而来，与宿主 Java 对象彻底隔离
            String wrapped = "(function(){ var state = JSON.parse(__stateJson);\n" + code + "\n})()";
            Value value = context.eval("js", wrapped);
            return convert(value, 0);
        } finally {
            // close(true) 中断仍在执行的脚本，这是超时能生效的关键
            context.close(true);
        }
    }

    /**
     * 构建全封锁的沙箱 Context（安全核心，逐项对齐 infra ScriptPostProcessor#buildSandbox）
     *
     * <p>🔴 那九项 {@code allow*} 是<b>沙箱本体</b>，改任何一项都是放开一个攻击面；
     * {@code .engine(engine)} 只是让它们跑在共享的语言运行时上（理由见 {@link #engine} 那段），
     * <b>不在这九项之内</b>，加它不构成放宽。
     */
    private Context buildSandbox() {
        return Context.newBuilder("js")
                .engine(engine)
                .allowHostAccess(HostAccess.NONE)
                .allowHostClassLookup(className -> false)
                .allowHostClassLoading(false)
                .allowIO(false)
                .allowCreateThread(false)
                .allowCreateProcess(false)
                .allowNativeAccess(false)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                .allowPolyglotAccess(PolyglotAccess.NONE)
                .build();
    }

    /**
     * 把脚本返回值转成纯 Java 对象。必须在 Context 关闭前完成——Value 依赖 Context 存活。
     * 限制递归深度与数组规模，防自引用/巨量数据打爆栈与内存。
     */
    private Object convert(Value value, int depth) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (depth > MAX_DEPTH) {
            throw new IllegalStateException("脚本返回的数据嵌套过深（上限 " + MAX_DEPTH + " 层）");
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isString()) {
            return value.asString();
        }
        if (value.isNumber()) {
            // JS 所有数字底层都是 double：fitsInLong 命中就取 long；未命中但值是整数（如 1+2=3.0）也回落成 long，
            // 避免「3 变成 3.0」——否则下游 condition.rule 的数值比较与 checkpoint JSON 会平白多出小数点。
            if (value.fitsInLong()) {
                return value.asLong();
            }
            double d = value.asDouble();
            if (!Double.isInfinite(d) && !Double.isNaN(d) && d == Math.rint(d)
                    && Math.abs(d) <= (double) Long.MAX_VALUE) {
                return (long) d;
            }
            return d;
        }
        if (value.hasArrayElements()) {
            long size = value.getArraySize();
            if (size > MAX_RESULT_SIZE) {
                throw new IllegalStateException("脚本返回的数据量过大：" + size);
            }
            List<Object> list = new ArrayList<>((int) size);
            for (long i = 0; i < size; i++) {
                list.add(convert(value.getArrayElement(i), depth + 1));
            }
            return list;
        }
        if (value.hasMembers()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (String key : value.getMemberKeys()) {
                map.put(key, convert(value.getMember(key), depth + 1));
            }
            return map;
        }
        // 函数等无法映射的类型，用字符串兜底
        return value.toString();
    }

}
