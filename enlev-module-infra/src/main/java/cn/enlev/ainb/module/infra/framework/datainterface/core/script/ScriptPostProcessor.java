package cn.enlev.ainb.module.infra.framework.datainterface.core.script;

import cn.hutool.core.util.StrUtil;
import com.google.common.annotations.VisibleForTesting;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.*;

/**
 * JS 后处理脚本执行器
 *
 * 让用户用一小段 JS 对接口返回结果做二次加工（改字段名、拼接、过滤）。
 *
 * ⚠️ 执行任意脚本是本功能最大的安全风险面，沙箱做了这些限制：
 * <table>
 *     <tr><td>禁访问 Java 类</td><td>{@code HostAccess.NONE} + {@code allowHostClassLookup} 全拒</td></tr>
 *     <tr><td>禁 IO / 线程 / 进程</td><td>{@code allowIO/allowCreateThread/allowNativeAccess} 全 false</td></tr>
 *     <tr><td>执行超时</td><td>独立线程执行 + {@code Context#close(true)} 强制中断（实测毫秒级生效）</td></tr>
 *     <tr><td>超时预算不被冷启动吃掉</td><td>共享一个 {@code Engine} + 启动预热，见 {@link #engine} 与 {@link #warmUp()}</td></tr>
 *     <tr><td>脚本长度上限</td><td>保存与执行时都校验</td></tr>
 * </table>
 *
 * 结果转换为纯 Java 集合后再返回，避免 Value 对象逃逸到 Context 之外
 * （Context 关闭后 Value 不可用，逃逸会导致偶发的 IllegalStateException）。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class ScriptPostProcessor {

    /**
     * 脚本执行超时（毫秒）
     *
     * 超时后强制中断。数据加工应当是毫秒级的，2 秒足够宽松；
     * 给死循环留的时间越短越好 —— 超时期间这条线程被完全占住，
     * 并发几个死循环脚本就能把线程池拖垮。
     *
     * ⚠️ 改这个值时记得一起改上面这段说明与 {@code ScriptPostProcessorTest#testSandbox_timeout}。
     * 它曾经被从 2 秒悄悄改成 10 秒，注释与测试都没跟着动，于是
     * 「注释说 2 秒、实际 10 秒、测试红着」这个状态存在了很久没人发现。
     * 测试现在直接引用本常量，值与断言不会再各说各话。
     */
    @VisibleForTesting
    static final long TIMEOUT_MS = 2000L;

    /**
     * 脚本长度上限
     */
    public static final int MAX_SCRIPT_LENGTH = 10000;

    /**
     * 结果元素数量上限，防止脚本构造出巨大数组打爆内存
     */
    private static final int MAX_RESULT_SIZE = 100000;

    /**
     * 脚本执行线程池
     *
     * 用独立线程是为了能超时中断。守护线程，不阻塞应用退出。
     */
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "data-interface-script");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 全部 Context 共用的一个 GraalJS 运行时（🔴 B4 的一半，另一半是 {@link #warmUp()}）
     *
     * <p>与 agent 侧 {@code ScriptSandbox#engine} 同款，理由逐字相同（那两处刻意保持对齐）：
     * {@code Context.newBuilder("js")} 不给 {@code .engine(...)} 时会<b>隐式新建一个 Engine</b>，
     * 于是 Truffle 运行时、JS 内建、代码缓存按次重建；而这笔开销落在 {@link #process} 里
     * {@code future.get(TIMEOUT_MS)} 的<b>计时区内</b>（{@code buildSandbox()} 是 {@link #doProcess}
     * 的第一条语句，计时却早就开始了）。JVM 内第一次跑脚本还要再付一次进程级的语言初始化（秒级），
     * 而预算只有 {@link #TIMEOUT_MS} = 2 秒 —— 表现就是「首跑必超时」。
     *
     * <p>🔴 共享 Engine <b>不削弱</b>沙箱：Engine 只共享<b>语言运行时</b>，
     * 而 {@link #buildSandbox()} 里那九项 {@code allow*} 全是 <b>Context 级</b>策略、逐字未动，
     * 绑定（{@code __dataJson}）也是 Context 级的。隔离粒度没变：仍然一次执行一个新 Context、
     * 用完立刻 {@code close(true)}，两段脚本之间不共享全局对象、也拿不到对方的绑定。
     */
    private final Engine engine = Engine.newBuilder("js").build();

    /**
     * 启动时把冷启动开销吃掉（🔴 B4 的另一半）
     *
     * <p>光共享 Engine 还不够：Engine 只省掉「按次重建运行时」，<b>进程内第一次</b>建 Context 仍要付
     * Truffle 框架类初始化 + JS 语言 provider 的 ServiceLoader 发现。那笔钱若留给第一个真跑接口的用户付，
     * 他看到的就是「后处理脚本超时」—— 一个与他的脚本内容毫无关系的失败，且重跑一次就没了
     * （于是永远复现不出来）。
     *
     * <p>🔴 预热必须<b>直接调 {@link #doProcess}</b>、绕开 {@link #process} 的超时：
     * 走 {@code process} 的话预热自己就会超时（冷启动 &gt; 2 秒正是本条要修的缺陷），
     * 那会变成「预热失败 → 记一条 WARN → 首跑照样超时」，修了个寂寞。
     * 走 {@code doProcess} 也顺带把真实代码路径整个跑了一遍（建 Context、绑 {@code __dataJson}、
     * {@code JSON.parse}、eval、{@link #convert}、{@code close}），而不是只暖一半。
     *
     * <p>🔴 预热失败<b>绝不能</b>让容器起不来，所以这里必须吞掉异常：
     * {@code enlev-dependencies/pom.xml} 里那段注释就写着「GraalJS 各构件版本不匹配会打断
     * {@code JavaScriptLanguageProvider}」—— 那是个<b>已知的</b>、由依赖管理引起的失败模式。
     * 真出这种事，正确的表现是启动照常、等有人跑脚本时报出真错，
     * 而不是整个后端起不来（那会把一个后处理脚本的毛病放大成全站不可用）。
     *
     * <p>🔴 冷启动到底多大、预热到底省了多少，<b>实测数字记在 agent 侧
     * {@code ScriptSandbox#warmUp()} 的表里</b>，这里刻意不抄第二份 —— 两份数字必然漂
     * （同一个 JVM 里两处沙箱谁先预热，另一处就便宜得多，抄过来就是假话）。
     * 一句话结论：进程内第一次建 Context 是<b>秒级</b>，占满 2 秒预算；预热之后一次执行是<b>十几毫秒</b>。
     */
    @PostConstruct
    public void warmUp() {
        long begin = System.currentTimeMillis();
        try {
            doProcess("return data;", Map.of());
            log.info("[warmUp][GraalJS 沙箱预热完成，耗时 {} ms —— 这笔钱若不在这儿付，"
                    + "就会落在第一个跑后处理脚本的用户头上并直接超时]", System.currentTimeMillis() - begin);
        } catch (Throwable ex) {
            log.warn("[warmUp][GraalJS 沙箱预热失败，耗时 {} ms；不影响启动，"
                    + "首次执行脚本时仍会重试并抛出真实错误]", System.currentTimeMillis() - begin, ex);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
        // Engine 与本 bean 同生命周期，显式关掉，免得 polyglot 的关闭钩子在 JVM 退出时报「engine 未关闭」。
        // doProcess 的 finally 保证此刻已无存活的 Context
        engine.close();
    }

    /**
     * 执行脚本
     *
     * @param script 用户脚本；为空则原样返回 data
     * @param data   接口执行结果
     * @return 加工后的结果
     */
    public Object process(String script, Object data) {
        if (StrUtil.isBlank(script)) {
            return data;
        }
        validateScript(script);

        Future<Object> future = executor.submit(() -> doProcess(script, data));
        try {
            return future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            // 取消任务，doProcess 里的 finally 会关闭 Context 并中断脚本
            future.cancel(true);
            throw exception(DATA_INTERFACE_SCRIPT_TIMEOUT);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            log.warn("[process][脚本执行失败] data类型: {}", data == null ? "null" : data.getClass().getSimpleName(), cause);
            // 给出更友好的提示：告诉用户 data 实际是什么，避免盲猜
            String hint = buildErrorHint(data, cause);
            throw exception(DATA_INTERFACE_SCRIPT_EXECUTE_FAIL, hint);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw exception(DATA_INTERFACE_SCRIPT_EXECUTE_FAIL, "脚本执行被中断");
        }
    }

    /**
     * 校验脚本本身
     */
    public void validateScript(String script) {
        if (StrUtil.isBlank(script)) {
            return;
        }
        if (script.length() > MAX_SCRIPT_LENGTH) {
            throw exception(DATA_INTERFACE_SCRIPT_EXECUTE_FAIL,
                    "脚本过长，上限 " + MAX_SCRIPT_LENGTH + " 字符");
        }
    }

    /**
     * 在沙箱里执行
     */
    private Object doProcess(String script, Object data) {
        Context context = buildSandbox();
        try {
            // data 以 JSON 文本传入，避免把 Java 对象暴露给脚本 ——
            // 即便宿主访问被禁，少一层暴露少一分风险
            context.getBindings("js").putMember("__dataJson",
                    cn.enlev.ainb.framework.common.util.json.JsonUtils.toJsonString(data));
            // 包成函数体，让用户可以直接写 return
            String wrapped = "(function(){ var data = JSON.parse(__dataJson);\n"
                    + script + "\n})()";
            Value value = context.eval("js", wrapped);
            return convert(value, 0);
        } finally {
            // close(true) 会中断仍在执行的脚本，这是超时能生效的关键
            context.close(true);
        }
    }

    /**
     * 构建脚本执行失败时的错误提示
     *
     * 常见错误是 `data.map is not a function` —— 用户以为 data 是数组，
     * 实际上可能是分页对象 {total, list} 或写操作结果 {affectedRows}。
     * 告诉用户 data 实际的结构，比只抛原始错误有用得多。
     */
    private String buildErrorHint(Object data, Throwable cause) {
        String originalMsg = cause == null ? "未知错误" : cause.getMessage();

        // data.map / data.filter / data.xxx is not a function 这类错误
        if (originalMsg != null && originalMsg.contains("is not a function")) {
            String dataDesc = describeData(data);
            return originalMsg + "。data 实际是：" + dataDesc
                    + "。提示：开启分页时 data 是 {total, list}，需访问 data.list；"
                    + "写操作（INSERT/UPDATE/DELETE）返回 {affectedRows}，不适合用后处理脚本";
        }

        return originalMsg;
    }

    /**
     * 描述 data 的结构，方便用户排查
     */
    private String describeData(Object data) {
        if (data == null) {
            return "null";
        }
        if (data instanceof java.util.List) {
            return "数组，长度 " + ((java.util.List<?>) data).size();
        }
        if (data instanceof java.util.Map) {
            java.util.Map<?, ?> map = (java.util.Map<?, ?>) data;
            String keys = map.keySet().stream()
                    .map(String::valueOf)
                    .limit(5)
                    .collect(java.util.stream.Collectors.joining(", "));
            if (map.size() > 5) {
                keys += ", ...";
            }
            return "对象 {" + keys + "}";
        }
        return data.getClass().getSimpleName();
    }

    /**
     * 构建沙箱 Context
     *
     * <p>🔴 那九项 {@code allow*} 是<b>沙箱本体</b>，改任何一项都是放开一个攻击面；
     * {@code .engine(engine)} 只是让它们跑在共享的语言运行时上（理由见 {@link #engine} 那段），
     * <b>不在这九项之内</b>，加它不构成放宽。
     */
    private Context buildSandbox() {
        return Context.newBuilder("js")
                .engine(engine)
                // 宿主访问全禁：脚本拿不到任何 Java 对象与类
                .allowHostAccess(HostAccess.NONE)
                .allowHostClassLookup(className -> false)
                .allowHostClassLoading(false)
                // IO、线程、进程、原生调用全禁
                .allowIO(false)
                .allowCreateThread(false)
                .allowCreateProcess(false)
                .allowNativeAccess(false)
                .allowEnvironmentAccess(org.graalvm.polyglot.EnvironmentAccess.NONE)
                // 不允许加载其它语言
                .allowPolyglotAccess(org.graalvm.polyglot.PolyglotAccess.NONE)
                .build();
    }

    /**
     * 把脚本返回值转成纯 Java 对象
     *
     * 必须在 Context 关闭前完成转换 —— Value 对象依赖 Context 存活。
     * 限制递归深度，防止脚本构造自引用结构导致栈溢出。
     */
    private Object convert(Value value, int depth) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (depth > 20) {
            throw new IllegalStateException("脚本返回的数据嵌套过深");
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isString()) {
            return value.asString();
        }
        if (value.isNumber()) {
            // 整数与小数分开，避免 1 变成 1.0
            if (value.fitsInLong()) {
                return value.asLong();
            }
            return value.asDouble();
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
