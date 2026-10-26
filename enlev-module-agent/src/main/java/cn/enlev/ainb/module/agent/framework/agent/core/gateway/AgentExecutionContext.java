package cn.enlev.ainb.module.agent.framework.agent.core.gateway;

import cn.enlev.ainb.framework.security.core.LoginUser;
import cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils;
import cn.enlev.ainb.framework.tenant.core.context.TenantContextHolder;
import lombok.Getter;
import lombok.ToString;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.function.Supplier;

/**
 * Agent 执行上下文
 *
 * 把 userId / tenantId / loginUser 从 ThreadLocal 里<b>取出来存成普通值</b>，
 * 之后在任何线程上都能显式重建 —— 不再依赖「当前线程恰好有上下文」。
 *
 * <h3>为什么需要它（有实测支撑，不是防御性冗余）</h3>
 * 见 {@code AgentDiagnosticController} 的类注释。一句话概括：
 * 上下文在异步链路上<b>大多数情况下能拿到</b>，但这份「能拿到」建立在两个隐式前提上 ——
 * Reactor 自动上下文传播（项目里没有一行显式配置）、以及 TTL 的线程创建时继承。
 * 一旦工作交给<b>预先创建好的线程池</b>（实测：userId 与 tenantId 都是 null），
 * 或者框架默认值在某次升级里变了，租户隔离就会<b>静默失效</b>。
 *
 * <p>更微妙的是：实测中在一个调度器线程上写入的值会被原样传播到下一个 ——
 * 也就是说链路上任何一环改了上下文，后面全跟着变。快照让本模块的执行不受这种漂移影响。
 *
 * <h3>与 ai 模块做法的区别</h3>
 * ai 模块用 {@code TenantUtils.executeIgnore}（8 处），那是<b>把租户隔离整个关掉</b> ——
 * {@code TenantDatabaseInterceptor} 直接 {@code return true} 不拼 {@code tenant_id}。
 * 本模块反过来：<b>拿不到身份就罢工，绝不降级放行</b>。
 * 有一条架构测试钉死 agent 模块不得出现 {@code executeIgnore}。
 *
 * @author 走进新时代
 */
@Getter
@ToString(exclude = "loginUser") // loginUser 里有权限集合，打印出来会淹没日志
public class AgentExecutionContext {

    /**
     * 用户编号
     */
    private final Long userId;

    /**
     * 租户编号
     */
    private final Long tenantId;

    /**
     * 登录用户（含权限信息），重建时要塞回 SecurityContext
     */
    private final LoginUser loginUser;

    /**
     * 是否为沙箱执行（流程编排设计器的「沙箱测试」）
     *
     * <p>沙箱语义：<b>用测试输入真跑当前草稿 DSL</b>，但写操作被 mock —— 只读工具与大模型真实执行，
     * 风险等级非 {@code READ_ONLY} 的工具在 {@code AgentToolExecutor} 处返回占位结果、不产生真实副作用；
     * 节点 trace 也不落库。该标志是<b>普通不可变字段</b>（非 ThreadLocal），随 {@link #snapshot()}
     * 与 {@link #withSandbox()} 复制跨线程天然传播，无需任何线程绑定。
     */
    private final boolean sandbox;

    private AgentExecutionContext(Long userId, Long tenantId, LoginUser loginUser, boolean sandbox) {
        this.userId = userId;
        this.tenantId = tenantId;
        this.loginUser = loginUser;
        this.sandbox = sandbox;
    }

    /**
     * 为「无 Servlet 请求」的后台任务构造上下文（增量4a：wait 轮询器自动 resume 用）
     *
     * <p>与 {@link #snapshot()} 的区别：snapshot 从<b>当前线程</b>的 ThreadLocal 捕获身份，必须还在 HTTP 请求里；
     * 而定时任务 / 轮询线程上没有任何请求，身份只能从持久化的执行实例（{@code user_id} + {@code tenant_id}）
     * <b>显式重建</b>。构造出的上下文 {@link #isComplete()} 为真（loginUser 非空），{@link #execute} 会把租户与
     * LoginUser 贴回当前线程，使「后台自动 resume」与「控制器发起的 resume」走完全相同的身份链路（同样 fail-close）。
     *
     * @param userId   执行实例记录的触发用户（取自 {@code agent_workflow_execution.user_id}）；为 null 时调用方应跳过
     * @param tenantId 执行实例所属租户
     */
    public static AgentExecutionContext ofSystem(Long userId, Long tenantId) {
        LoginUser loginUser = new LoginUser();
        loginUser.setId(userId);
        return new AgentExecutionContext(userId, tenantId, loginUser, false);
    }

    /**
     * 为单元测试快速构造一个上下文，不依赖 SecurityContext / TenantContext
     *
     * <h3>只在测试里用</h3>
     * 这个工厂方法绕过了 ThreadLocal 捕获逻辑，因为测试里根本没有 HTTP 请求。
     * 命名带 {@code ForTest} 是刻意的 —— 让 IDE 的用法搜索能一眼区分测试用例和真实调用。
     *
     * <p>不放在测试类里是因为多个测试类都需要它，放在这里避免每处都写一遍完全相同的 mock 逻辑。
     * 实现与 {@link #ofSystem} 一致（后者是生产后台任务用的同形工厂）。
     */
    public static AgentExecutionContext ofForTest(Long userId, Long tenantId) {
        return ofSystem(userId, tenantId);
    }

    /**
     * 在<b>当前线程</b>捕获上下文快照
     *
     * ⚠️ 必须在进入任何异步链路<b>之前</b>调用，也就是还在 Servlet 线程上的时候。
     * 晚一步落到调度器线程或线程池里，捕到的可能就是 null（实测见诊断端点）。
     *
     * @return 快照，即使字段全为 null 也会返回对象；是否可用交给 {@link #isComplete()} 判断
     */
    public static AgentExecutionContext snapshot() {
        return new AgentExecutionContext(
                SecurityFrameworkUtils.getLoginUserId(),
                TenantContextHolder.getTenantId(),
                SecurityFrameworkUtils.getLoginUser(),
                false);
    }

    /**
     * 复制出一个<b>沙箱</b>上下文：保留 userId / tenantId / loginUser，仅把 sandbox 置真
     *
     * <p>供流程编排设计器的「沙箱测试」端点使用 —— 在<b>当前登录用户身份</b>下真跑草稿 DSL，
     * 但下游据此 mock 写操作、跳过 trace 落库。身份完整与否仍由 {@link #isComplete()} 判定，
     * 沙箱不放宽身份要求（fail-close 语义不变）。
     */
    public AgentExecutionContext withSandbox() {
        return new AgentExecutionContext(userId, tenantId, loginUser, true);
    }

    /**
     * 身份是否完整
     *
     * 只要缺一样就不完整。调用方据此 fail-close —— 这是与下游横切能力最大的语义差别：
     * 行权限与字段权限在拿不到 userId 时是<b>放行</b>的，
     * 它们的前提是「走到这里的只有内部调用，结果不会流向用户」，
     * 而 AI 的执行结果恰恰要流向用户。
     */
    public boolean isComplete() {
        return userId != null && tenantId != null && loginUser != null;
    }

    /**
     * 在本快照的身份下执行
     *
     * 无论当前线程原本有没有上下文，都<b>显式</b>贴上快照里的身份，执行完再恢复原状。
     *
     * <p>恢复而不是简单清理，是因为调用方所在的线程可能本来就有自己的上下文
     * （例如 Servlet 线程上的嵌套调用）—— 直接 clear 会把它一并抹掉。
     *
     * @param action 要执行的动作
     * @return 动作的返回值
     */
    public <T> T execute(Supplier<T> action) {
        LoginUser previousUser = SecurityFrameworkUtils.getLoginUser();
        Long previousTenantId = TenantContextHolder.getTenantId();
        Boolean previousIgnore = TenantContextHolder.isIgnore();
        try {
            // 🔴 刻意不用 TenantUtils.execute(tenantId, Callable)：
            // 它在 catch 里把所有异常<b>包装成 RuntimeException</b>（TenantUtils#execute）。
            // 那会毁掉两件事：
            //   ① ServiceException 被包装后，全局异常处理器认不出来，
            //      用户看到的是「系统异常」而不是「您没有执行该操作的权限：…」
            //   ② AI 的自修正依赖准确的错误信息，包装后拿到的是一层无意义的外壳
            // 所以这里自己管理租户上下文 —— 逻辑与 TenantUtils 一致，只是不吞掉异常类型。
            TenantContextHolder.setTenantId(tenantId);
            TenantContextHolder.setIgnore(false);
            setLoginUserWithoutRequest(loginUser);
            return action.get();
        } finally {
            // 还原调用方原本的身份：有就塞回去，没有就清掉。
            // 用 SecurityContextHolder.clearContext() 而不是 setLoginUser(null, ...) ——
            // 后者会构造一个 principal 为 null 的 Authentication 塞进去，那不是「没有身份」
            TenantContextHolder.setTenantId(previousTenantId);
            TenantContextHolder.setIgnore(previousIgnore);
            if (previousUser != null) {
                setLoginUserWithoutRequest(previousUser);
            } else {
                SecurityContextHolder.clearContext();
            }
        }
    }

    /**
     * 把登录用户塞进 SecurityContext，<b>不依赖 HttpServletRequest</b>
     *
     * ⚠️ 不能直接用 {@code SecurityFrameworkUtils.setLoginUser(user, null)}：
     * 它内部走 {@code WebAuthenticationDetailsSource.buildDetails(request)}，
     * 而后者会读 {@code request.getRemoteAddr()} —— 传 null 直接 NPE。
     *
     * <p>那个方法隐含假设「设置身份这件事总发生在一次 HTTP 请求里」，
     * 而 agent 的执行恰恰可能落在与请求无关的线程上。
     * details 里只有 IP 与 sessionId，异步执行本来也没有，省掉不影响
     * {@code getLoginUser()} / {@code getLoginUserId()} —— 它们只读 principal。
     */
    private static void setLoginUserWithoutRequest(LoginUser loginUser) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                loginUser, null, Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /**
     * 在本快照的身份下执行（无返回值）
     */
    public void execute(Runnable action) {
        execute(() -> {
            action.run();
            return null;
        });
    }

}
