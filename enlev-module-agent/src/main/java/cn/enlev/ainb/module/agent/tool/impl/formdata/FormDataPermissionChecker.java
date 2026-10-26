package cn.enlev.ainb.module.agent.tool.impl.formdata;

import cn.enlev.ainb.framework.security.core.service.SecurityFrameworkService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_FORM_DATA_PERMISSION_DENIED;

/**
 * 表单数据的「按 code 动态」权限门
 *
 * <h3>🔴 为什么必须在工具内自己校验，而不能靠网关</h3>
 * 权限网关（{@code AgentSecurityGateway}）校验的是 {@code @AgentTool.permission()} 声明的
 * <b>静态</b>权限串 —— 它在编译期就固定了，拿不到运行时的 {@code code} 参数。
 * 而表单数据权限是<b>资源实例级</b>的：每张已发布表单在发布时都会随菜单按钮创建一个
 * {@code infra:form-design-run:{code}:{action}} 权限点，管理员按角色逐个勾选。
 * 「静态串无法表达动态 code」这一点，与 infra 的 {@code FormDesignRuntimeController}
 * 因 {@code @PreAuthorize} 表达不了 code 而<b>弃注解、改编程式校验</b>是同一个道理。
 *
 * <h3>权限串的两个来源（agent 侧刻意对齐，不是巧合）</h3>
 * <ol>
 *   <li>{@code FormDesignRuntimeController.checkPermission}（每个数据端点进来先过它）：
 *       {@code String.format("infra:form-design-run:%s:%s", code, action)}</li>
 *   <li>{@code FormDesignRuntimeService}（写路径里复述同一格式，如 update 的
 *       {@code infra:form-design-run:{code}:update}）</li>
 * </ol>
 * 这两处硬编码<b>同一串</b>是刻意的；本类是第三处副本，必须与前两处逐字一致。
 * 各端点的 action：{@code get}/{@code page}/{@code distinctValues}→{@code query}，
 * {@code create}→{@code create}，{@code update}→{@code update}，
 * {@code delete}/{@code deleteBatch}→{@code delete}。
 *
 * <h3>为什么 {@link cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction}
 * 说「实现里不要做权限校验」，这里却要做</h3>
 * 那条规矩针对的是<b>网关已校验的静态工具权限</b>（避免各工具重复校验同一静态串）。
 * 每张表单的数据权限是网关<b>无从校验</b>的另一层，与静态工具权限正交 —— 不做就是越权。
 *
 * <h3>🔴 漂移风险 fail-CLOSED</h3>
 * 若 infra 改了权限串格式，本类的 {@code hasPermission} 会对着一个<b>不存在的串</b>查询，
 * 返回 false → 拒绝（安全，不会越权，但功能会<b>静默失效</b>）。因此
 * {@code FormDataPermissionCheckerTest} 把格式与各 action 拼串<b>钉死</b>，
 * infra 一旦改格式，那条测试会先红，提醒同步本类。
 *
 * @author 走进新时代
 */
@Component
public class FormDataPermissionChecker {

    /**
     * 表单数据权限串的格式，逐字对齐 infra 的两处来源（见类注释）
     */
    private static final String PERMISSION_FORMAT = "infra:form-design-run:%s:%s";

    /**
     * 动作词表，与 infra {@code FormDesignRuntimeController} 各端点用的字面量逐一对齐：
     * get/page/distinctValues→{@link #ACTION_QUERY}，create→{@link #ACTION_CREATE}，
     * update→{@link #ACTION_UPDATE}，delete/deleteBatch→{@link #ACTION_DELETE}。
     * 集中在此，避免五个工具各写字符串字面量而漂移。
     */
    public static final String ACTION_QUERY = "query";
    public static final String ACTION_CREATE = "create";
    public static final String ACTION_UPDATE = "update";
    public static final String ACTION_DELETE = "delete";

    @Resource
    private SecurityFrameworkService securityFrameworkService;

    /**
     * 校验当前登录人对<b>这张表单</b>的数据有没有<b>这个动作</b>的权限
     *
     * <p>调用时机：每个 {@code form_data_*} 工具 {@code execute} 的<b>第一行</b>（解析出 code 之后）。
     * 身份时序成立 —— {@code AgentToolExecutor.execute} 把 {@code function.execute(params)}
     * 放进重建身份的 {@code context.execute(...)} 块内，故此处 {@code hasPermission}
     * 读到的是正确的当前用户。
     *
     * @param code   表单编码
     * @param action 动作：query / create / update / delete
     */
    public void check(String code, String action) {
        String permission = String.format(PERMISSION_FORMAT, code, action);
        if (!securityFrameworkService.hasPermission(permission)) {
            throw exception(AGENT_FORM_DATA_PERMISSION_DENIED, code, action, permission);
        }
    }

}
