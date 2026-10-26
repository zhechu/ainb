package cn.enlev.ainb.module.agent.controller.admin.diagnostic;

import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils;
import cn.enlev.ainb.framework.tenant.core.context.TenantContextHolder;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolExecutor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;

/**
 * SSE 上下文诊断
 *
 * 🔬 <b>这个端点是一份可复现的实测证据</b>，用来回答 agent 模块的一个设计前提：
 * 流式响应（SSE / Flux）的回调里，还能不能拿到 userId 与 tenantId？
 *
 * <h3>实测结论（2026-09-21，Spring Boot 3.5.15 + Reactor）</h3>
 * <table border="1">
 *   <tr><th>采样点</th><th>线程</th><th>userId / tenantId</th><th>为什么</th></tr>
 *   <tr><td>controller-entry</td><td>http-nio-exec</td><td>✅ 拿得到</td><td>Servlet 线程，直接读 ThreadLocal</td></tr>
 *   <tr><td>flux-sync-emit</td><td>订阅线程</td><td>✅ 拿得到</td><td>同步发射，没换线程</td></tr>
 *   <tr><td>flux-async-emit</td><td>parallel-N</td><td>✅ 拿得到</td><td>Reactor 上下文传播在调度切换时搬运了快照</td></tr>
 *   <tr><td>external-thread-emit</td><td>请求期间 new 的裸线程</td><td>✅ 拿得到</td><td>TTL 继承自 InheritableThreadLocal，<b>创建时</b>继承</td></tr>
 *   <tr><td><b>prestarted-pool-emit</b></td><td><b>预热线程池</b></td><td><b>❌ 都是 null</b></td><td><b>线程早于请求创建，继承不到；未经 TTL 装饰</b></td></tr>
 * </table>
 *
 * <h3>这修正了原方案的一处归因</h3>
 * 原先推断「{@code TenantContextWebFilter} 在 finally 里 clear()，而 Servlet 异步下
 * filter chain 先返回，所以上下文丢失」。<b>实测否定了这条</b>：
 * 外部线程 sleep 800ms（filter 的 finally 早已执行）后依然读得到 ——
 * 因为 TTL 是<b>继承语义</b>，子线程持有的是自己那份副本，父线程清理不影响它。
 *
 * <p><b>真正的风险点是线程池复用</b>：池化线程在应用启动时就已创建，继承不到任何请求上下文。
 * HTTP 客户端的 IO 线程正是这种形态。
 *
 * <h3>为什么 agent 模块仍然必须显式快照 + 重建</h3>
 * 尽管实测显示「大多数情况下拿得到」，但这份「拿得到」建立在两个隐式前提上：
 * <ol>
 *   <li><b>Reactor 自动上下文传播</b> —— 项目里没有任何一行显式配置，
 *       它来自 {@code io.micrometer:context-propagation} 被自动装配。
 *       依赖一个没人显式声明过的行为，等于把租户隔离押在框架默认值上</li>
 *   <li><b>上下文是可变且会漂移的</b> —— 实测中在 {@code parallel-1} 上写入的哨兵值，
 *       被原样传播到了 {@code parallel-2}。也就是说链路上任何一环改了上下文，
 *       后续全都跟着变</li>
 * </ol>
 * 而 agent 的工具执行一旦交给自建线程池（很可能），就<b>必然</b>落到 null 那一档。
 *
 * <p>所以 {@code AgentExecutionContext} 的显式快照 + 重建不是防御性冗余，
 * 而是把「能不能拿到」从框架默认值手里拿回来，变成本模块自己可验证的事。
 *
 * @author 走进新时代
 */
@Tag(name = "管理后台 - AI 助手诊断")
@RestController
@RequestMapping("/agent/diagnostic")
@Validated
@Slf4j
public class AgentDiagnosticController {

    /**
     * 预热好的线程池：线程在<b>任何请求到达之前</b>就已创建
     *
     * 🔴 这个「预先创建」是实验成立的关键。TTL（TransmittableThreadLocal）继承自
     * {@code InheritableThreadLocal}，在有上下文的线程里 {@code new Thread()} 会<b>天然继承</b> ——
     * 若线程是在请求处理过程中才创建的，就会「碰巧」拿到正确上下文，得出错误结论。
     *
     * <p>真实的 HTTP 客户端 IO 线程正是池化复用的：它们早在应用启动时就创建好了，
     * 继承不到任何请求上下文。这一条才是 LLM 流式的真实形态。
     */
    /**
     * 字段名与 bean 名一致：{@code @Resource} 先按<b>字段名</b>找，
     * 通用名容易撞上别的模块的同名 bean，表现是启动直接失败
     * （"could not be injected because it is a JDK dynamic proxy"）。agent 模块踩过
     */
    @Resource
    private AgentToolExecutor agentToolExecutor;

    private static final ExecutorService PRESTARTED_POOL;

    static {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "agent-diagnostic-pool");
            t.setDaemon(true);
            return t;
        });
        // 立刻把核心线程全部创建出来，确保它们不继承任何请求上下文
        pool.prestartAllCoreThreads();
        PRESTARTED_POOL = pool;
    }

    @Data
    public static class ContextSnapshot {

        /** 采样点名称 */
        private String stage;
        /** 当前线程名，用于判断是否还在 Servlet 线程上 */
        private String thread;
        /** 当前登录用户编号，拿不到为 null */
        private Long userId;
        /** 当前租户编号，拿不到为 null */
        private Long tenantId;
        /** 是否处于「忽略租户」状态 */
        private boolean tenantIgnore;

        static ContextSnapshot of(String stage) {
            ContextSnapshot s = new ContextSnapshot();
            s.stage = stage;
            s.thread = Thread.currentThread().getName();
            s.userId = SecurityFrameworkUtils.getLoginUserId();
            s.tenantId = TenantContextHolder.getTenantId();
            s.tenantIgnore = TenantContextHolder.isIgnore();
            return s;
        }
    }

    @GetMapping("/context")
    @Operation(summary = "同步采样：当前请求线程上的上下文")
    @PreAuthorize("@ss.hasPermission('agent:diagnostic:query')")
    public CommonResult<ContextSnapshot> context() {
        return success(ContextSnapshot.of("servlet-sync"));
    }

    @GetMapping(value = "/sse-context", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式采样：对比各类线程上的上下文可见性")
    @PreAuthorize("@ss.hasPermission('agent:diagnostic:query')")
    public Flux<CommonResult<ContextSnapshot>> sseContext() {
        // ① 进入方法时仍在 Servlet 线程上，这一份是基准
        ContextSnapshot entry = ContextSnapshot.of("controller-entry");
        log.info("[sseContext][进入方法] {}", entry);

        // ② 同步发射：订阅发生在 Servlet 线程，ThreadLocal 预期还在
        Flux<CommonResult<ContextSnapshot>> sync = Flux
                .just(1)
                .map(i -> success(ContextSnapshot.of("flux-sync-emit")));

        // ③ 异步发射：delayElements 会把后续操作切到 parallel 调度器，
        //    这是 Reactor <b>自己管理</b>的线程，上下文传播机制能覆盖到
        Flux<CommonResult<ContextSnapshot>> async = Flux
                .range(1, 2)
                .delayElements(Duration.ofMillis(300))
                .map(i -> success(ContextSnapshot.of("flux-async-emit-" + i)));

        // ④ 🔴 决定性的一条：从「Reactor 完全不认识的外部线程」发射。
        //    真实的 LLM 流式就是这个形态 —— 元素由 HTTP 客户端的 IO 线程送达，
        //    那个线程既不是 Servlet 线程、也不由 Reactor 调度器创建。
        //    同时刻意 sleep 到 filter chain 早已返回（finally 里的 clear() 必然已执行），
        //    以此分辨「上下文是订阅时被快照捕获」还是「运行时现读 ThreadLocal」。
        Flux<CommonResult<ContextSnapshot>> external = Flux.create(sink -> {
            Thread raw = new Thread(() -> {
                try {
                    Thread.sleep(800);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                sink.next(success(ContextSnapshot.of("external-thread-emit")));
                sink.complete();
            }, "agent-diagnostic-raw");
            raw.setDaemon(true);
            raw.start();
        });

        // ⑤ 🔴🔴 真正决定性的一条：在「早已创建好」的池化线程上采样。
        //    与 ④ 的区别只有一个 —— 线程不是本次请求期间新建的，因此继承不到任何上下文。
        //    HTTP 客户端的 IO 线程就是这样：应用启动时就建好，被所有请求复用。
        //    TTL 要在池化线程上生效，必须用 TtlRunnable 包装或 TtlExecutors 装饰线程池，
        //    这里刻意都不做，看「裸提交」是什么结果。
        Flux<CommonResult<ContextSnapshot>> pooled = Flux.create(sink ->
                PRESTARTED_POOL.execute(() -> {
                    sink.next(success(ContextSnapshot.of("prestarted-pool-emit")));
                    sink.complete();
                }));

        return Flux.concat(Flux.just(success(entry)), sync, async, external, pooled)
                .doOnComplete(() -> log.info("[sseContext][完成] {}", ContextSnapshot.of("doOnComplete")))
                .doOnCancel(() -> log.info("[sseContext][取消] {}", ContextSnapshot.of("doOnCancel")));
    }

    // ==================== 流式中途的工具调用（权限边界清单第 6 条） ====================

    @Data
    public static class ToolContextSample {

        /** 采样点名称 */
        private String stage;
        private String thread;
        private Long userId;
        private Long tenantId;
        /** 🔴 是否处于「忽略租户」状态。清单第 6 条明确要求它是 false */
        private Boolean tenantIgnore;
        /** 工具是否执行成功；不跑工具的采样点为 null */
        private Boolean toolOk;
        /**
         * 工具返回值的指纹
         *
         * 用来做<b>行为</b>对比而不是只看 ThreadLocal：Servlet 线程与池化线程上
         * 跑同一个只读工具，指纹必须一致。租户上下文若丢了或被 ignore，
         * 查出来的行数会变，指纹随之变 —— 这是读 ThreadLocal 看不出来的。
         */
        private String toolFingerprint;
        /** 失败原因 */
        private String error;

        static ToolContextSample of(String stage) {
            ToolContextSample s = new ToolContextSample();
            s.stage = stage;
            s.thread = Thread.currentThread().getName();
            s.userId = SecurityFrameworkUtils.getLoginUserId();
            s.tenantId = TenantContextHolder.getTenantId();
            s.tenantIgnore = TenantContextHolder.isIgnore();
            return s;
        }
    }

    /**
     * 流式中途执行工具，逐点回报上下文
     *
     * <h3>它回答的问题</h3>
     * 权限边界清单第 6 条：「流式响应中途的工具调用，租户上下文正确（不是 ignore）」。
     * {@code AgentToolExecutorTest} 已经在单测里验过一遍，但那是 Mock 出来的上下文；
     * 这个端点跑的是<b>真服务、真登录态、真工具、真 SQL</b>。
     *
     * <h3>为什么要在「预热线程池」上跑</h3>
     * 它是实测过的最坏线程形态（见本类类注释）：线程早于请求创建，
     * 继承不到任何请求上下文，裸读 userId 与 tenantId 都是 null。
     * 真实的 LLM 流式就是这个形态 —— 元素由 HTTP 客户端的 IO 线程送达。
     * 在这里能跑通，别处就不会更糟。
     *
     * <h3>判据不只是「读得到」</h3>
     * 前两个采样点读 ThreadLocal，最后一个<b>真的跑一次只读工具并比对返回值指纹</b>。
     * 因为「上下文读得到」和「SQL 真的拼上了 tenant_id」是两件事 ——
     * 前者对后者只是必要条件。指纹一致才说明查出来的是同一批数据。
     *
     * <p>⚠️ 本方法<b>不抛异常</b>，全部接住转成流里的元素。
     * SSE 接口上同步抛出的异常拿不到全局异常处理器（客户端 Accept 是 text/event-stream，
     * 而处理器要写 JSON → 内容协商失败 → 落错误页 → 重过安全过滤器），
     * 用户最终看到的是「账号未登录」，真实原因全丢。踩过一次。
     *
     * @param tool 用来探测的<b>只读</b>工具名，默认 datamodel_list
     */
    @GetMapping(value = "/sse-tool-context", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式中途执行工具：验证租户上下文正确且不是 ignore")
    @Parameter(name = "tool", description = "只读工具名，默认 datamodel_list")
    @PreAuthorize("@ss.hasPermission('agent:diagnostic:query')")
    public Flux<CommonResult<ToolContextSample>> sseToolContext(
            @RequestParam(value = "tool", defaultValue = "datamodel_list") String tool) {
        // ① 基准：还在 Servlet 线程上，顺便把工具跑一遍留下指纹
        AgentExecutionContext context = AgentExecutionContext.snapshot();
        ToolContextSample baseline = ToolContextSample.of("servlet-baseline");
        runTool(baseline, context, tool);

        // ② 池化线程裸读：预期 userId / tenantId 都是 null，复现最坏情况
        Flux<CommonResult<ToolContextSample>> naked = Flux.create(sink ->
                PRESTARTED_POOL.execute(() -> {
                    sink.next(success(ToolContextSample.of("pool-naked")));
                    sink.complete();
                }));

        // ③ 同一个池化线程，经快照显式重建后再读：预期身份回来了、且 ignore 为 false
        Flux<CommonResult<ToolContextSample>> rebuilt = Flux.create(sink ->
                PRESTARTED_POOL.execute(() -> {
                    sink.next(success(context.execute(
                            () -> ToolContextSample.of("pool-rebuilt"))));
                    sink.complete();
                }));

        // ④ 决定性的一条：在池化线程上走完整执行器（网关 → 重建 → 工具），比对指纹
        Flux<CommonResult<ToolContextSample>> executed = Flux.create(sink ->
                PRESTARTED_POOL.execute(() -> {
                    // 采样点放在重建之后：这一行要回答的是「工具实际跑在什么身份下」，
                    // 而不是「这个线程本来有什么」（后者是 pool-naked 那一行的事）
                    ToolContextSample sample =
                            context.execute(() -> ToolContextSample.of("pool-tool-executed"));
                    runTool(sample, context, tool);
                    sink.next(success(sample));
                    sink.complete();
                }));

        return Flux.concat(Flux.just(success(baseline)), naked, rebuilt, executed);
    }

    /**
     * 跑一次工具并记下返回值指纹
     *
     * 指纹用「JSON 长度 + hash」而不是原样回传：只读工具的返回值可能有几百条，
     * 塞进诊断响应既看不清也没必要 —— 要对比的只是「两边是不是同一批数据」。
     */
    private void runTool(ToolContextSample sample, AgentExecutionContext context, String tool) {
        try {
            Object result = agentToolExecutor.execute(context, tool, Map.of());
            String json = JSONUtil.toJsonStr(result);
            sample.setToolOk(true);
            sample.setToolFingerprint(json.length() + ":" + json.hashCode());
        } catch (Exception ex) {
            // 这里同样不能抛 —— 见方法注释里的 SSE 异常陷阱
            sample.setToolOk(false);
            sample.setError(ex.getMessage());
            log.warn("[runTool][探测工具({}) 失败：{}]", tool, ex.getMessage());
        }
    }

}
