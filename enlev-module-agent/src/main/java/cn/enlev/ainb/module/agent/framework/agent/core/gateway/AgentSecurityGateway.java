package cn.enlev.ainb.module.agent.framework.agent.core.gateway;

import cn.hutool.core.util.ObjUtil;
import cn.enlev.ainb.framework.security.core.LoginUser;
import cn.enlev.ainb.framework.security.core.service.SecurityFrameworkService;
import cn.enlev.ainb.module.agent.dal.mysql.config.AgentConfigMapper;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolRegistry;
import cn.enlev.ainb.module.agent.service.tooldef.AgentToolDefService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.*;

/**
 * Agent 权限网关
 *
 * <b>所有工具执行的唯一入口。</b>任一条不满足就直接拒绝并终止 —— 不降级、不继续。
 *
 * <h3>为什么 agent 必须自带完整防线，不能指望下游兜底</h3>
 * 权限系统三层在「拿不到 userId」时语义<b>并不一致</b>：
 * <table border="1">
 *   <tr><th>层</th><th>无 userId 时</th><th>位置</th></tr>
 *   <tr><td>功能权限</td><td>拒绝（fail-close）</td><td>{@code SecurityFrameworkServiceImpl#hasAnyPermissions}</td></tr>
 *   <tr><td>行权限</td><td><b>放行</b>，不追加任何过滤</td><td>{@code DataRowFilterResolver#resolve}</td></tr>
 *   <tr><td>字段权限</td><td><b>放行</b>，字段一个都不裁</td><td>{@code PermissionFieldHelper#resolve}</td></tr>
 * </table>
 * 后两者的设计理由写在 {@code PermissionFieldHelper} 类注释「无登录上下文 = 不限制」那一条：
 * 「走到这里的只有内部调用（定时任务、MQ、流程引擎回写），结果不会流向用户」。
 * <b>这个前提对 AI 不成立</b> —— AI 的执行结果恰恰要流向用户。
 *
 * <h3>每一步执行前都要重新校验</h3>
 * Plan 从生成到确认之间可能隔着几分钟，期间权限完全可能变。
 * 所以网关在<b>每一步执行前</b>都跑一遍，不复用生成 Plan 时的判定结果。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class AgentSecurityGateway {

    @Resource
    private SecurityFrameworkService securityFrameworkService;
    @Resource
    private AgentToolRegistry toolRegistry;
    @Resource
    private AgentConfigMapper agentConfigMapper;
    /**
     * 用户自定义工具目录（增量5a）
     *
     * <p>仅当 {@link #toolRegistry} 查不到工具名时，才回落到这里校验「启用状态 + 权限」。
     * 代码工具路径逐字节不变（registry-first）。
     */
    @Resource
    private AgentToolDefService toolDefService;

    /**
     * 执行前的完整校验
     *
     * @param context  执行上下文快照（在 Servlet 线程上捕获的那一份）
     * @param toolName 工具名
     */
    public void check(AgentExecutionContext context, String toolName) {
        // 1~3：身份必须完整且未处于跨租户状态
        checkIdentity(context);
        // 4：租户级总开关
        checkEnabled();
        // 5~6：工具存在、已启用、且用户被授权
        checkTool(toolName);
    }

    /**
     * 对话入口的校验：只跑前 4 步（身份 + 总开关），不涉及具体工具
     *
     * <h3>为什么对话本身也要过闸门</h3>
     * 一次对话未必会调到工具（比如模型直接作答，或者检索那一步失败了），
     * 那样就一个 {@link #check} 都不会跑 —— 身份是否完整、总开关是否开着，
     * 全都没人验过，而 LLM 已经被调用、token 已经被消耗。
     *
     * <p>⚠️ 这里<b>不校验 {@code agent:assistant:use} 权限</b>，那一条由 Controller 的
     * {@code @PreAuthorize} 负责。刻意不在这里重复：两处都写，将来改权限串时
     * 很容易只改一处，而「改了却还有一处在用旧串」的表现是随机的 403，极难查。
     * 分工是明确的 —— <b>入口权限归 Controller，工具权限归网关</b>。
     */
    public void checkChat(AgentExecutionContext context) {
        checkIdentity(context);
        checkEnabled();
    }

    /**
     * 第 1~3 步：身份断言
     *
     * ⚠️ 读的是<b>快照</b>而不是当前线程的 ThreadLocal。
     * 实测表明预先创建好的线程池上读到的是 null（见 {@code AgentDiagnosticController}），
     * 若网关自己去读 ThreadLocal，就会在这类线程上误判成「没登录」而全面罢工。
     */
    private void checkIdentity(AgentExecutionContext context) {
        if (context == null || context.getUserId() == null) {
            throw exception(AGENT_NO_LOGIN_CONTEXT);
        }
        if (context.getTenantId() == null) {
            throw exception(AGENT_NO_TENANT_CONTEXT);
        }
        LoginUser loginUser = context.getLoginUser();
        if (loginUser == null) {
            throw exception(AGENT_NO_LOGIN_CONTEXT);
        }
        // 🔴 跨租户状态下必须拒绝。
        // 理由不是「不方便」而是「校验会失效」：SecurityFrameworkUtils.skipPermissionCheck()
        // 在 visitTenantId != tenantId 时返回 true，功能权限与数据权限会被<b>一起短路</b>。
        // 会话若在这个状态下发起，后面第 6 步的权限校验等于没做。
        if (loginUser.getVisitTenantId() != null
                && ObjUtil.notEqual(loginUser.getVisitTenantId(), loginUser.getTenantId())) {
            throw exception(AGENT_CROSS_TENANT_DENIED,
                    loginUser.getVisitTenantId(), loginUser.getTenantId());
        }
    }

    /**
     * 第 4 步：租户级总开关
     *
     * 查不到配置行就是未启用 —— 「默认关着」是数据层面的事实，不靠默认值写对。
     */
    private void checkEnabled() {
        if (agentConfigMapper.selectCurrent() == null) {
            throw exception(AGENT_DISABLED);
        }
    }

    /**
     * 第 5~6 步：工具授权
     *
     * <h3>🔴 registry-first，目录回落（增量5a）</h3>
     * 先查代码工具注册表；命中则走<b>逐字节不变</b>的原路径。查不到才回落到用户自定义工具目录
     * （{@code agent_tool_def}），镜像同一套「存在 → 启用 → 逐个权限 AND」校验。
     * 代码工具永远优先——即便有人漏了登记时的重名校验，用户工具也抢不到代码工具的名字。
     */
    private void checkTool(String toolName) {
        AgentToolRegistry.ToolDefinition tool = toolRegistry.get(toolName);
        if (tool != null) {
            // —— 代码工具：以下逻辑与增量5a 之前完全一致 ——
            if (!tool.isEnabled()) {
                throw exception(AGENT_TOOL_DISABLED, toolName);
            }
            // 与界面按钮走同一套权限点。注意这里是编程式校验 ——
            // AI 不经过 Controller，@PreAuthorize 对它完全无效。
            //
            // 🔴 「原子动作」类工具声明了多个权限串，这里要求<b>全都有</b>才放行。
            //    只查第一个的话，用户会卡在链条中间 —— 而中间态往往比没做更糟
            //    （见 AgentTool#extraPermissions 的说明）
            for (String permission : tool.getPermissions()) {
                if (!securityFrameworkService.hasPermission(permission)) {
                    throw exception(AGENT_PERMISSION_DENIED, toolName, permission);
                }
            }
            return;
        }

        // —— 回落到用户自定义工具目录（HTTP 等无 Java 代码的工具）——
        AgentToolDefService.GatewayView catalogTool = toolDefService.resolveForGateway(toolName);
        if (catalogTool == null) {
            throw exception(AGENT_TOOL_NOT_FOUND, toolName);
        }
        if (!catalogTool.enabled()) {
            throw exception(AGENT_TOOL_DISABLED, toolName);
        }
        // 权限同样 AND 语义：登记的管理员决定调用方需要哪些权限点
        for (String permission : catalogTool.permissions()) {
            if (!securityFrameworkService.hasPermission(permission)) {
                throw exception(AGENT_PERMISSION_DENIED, toolName, permission);
            }
        }
    }

}
