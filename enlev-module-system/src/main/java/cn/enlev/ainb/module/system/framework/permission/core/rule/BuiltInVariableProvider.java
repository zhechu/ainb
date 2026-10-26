package cn.enlev.ainb.module.system.framework.permission.core.rule;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils;
import cn.enlev.ainb.module.system.dal.dataobject.user.AdminUserDO;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectTypeEnum;
import cn.enlev.ainb.module.system.service.dept.DeptService;
import cn.enlev.ainb.module.system.service.user.AdminUserService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 12 个内置变量提供者（v8 §4.2，v9 §7）
 *
 * 变量名以 : 引用（如 :currentUserId），在规则模板表达式和条件树参数里使用。
 *
 * ## 12 个内置变量
 *
 * 用户维度：currentUserId / currentDeptId / currentDeptAndChildren / currentRoleIds / currentPostIds / currentTenantId
 * 时间维度：currentDate / currentDateTime / currentTimestamp / currentYear / currentMonth / currentDayOfWeek
 *
 * ## 流程变量（S5 接入前为空）
 *
 * processInstanceId / taskId 在 requestContext 里传入（由流程鉴权层注入），不在这里负责。
 *
 * ## requestContext 还兼作入参通道
 *
 * {@link #CONTEXT_SUBJECT_KEYS} 走同一个 Map 传进来，但它是给本类自己用的入参，
 * 不是变量，{@link #provide} 会在返回前把它摘掉。
 *
 * @author 走进新时代
 */
@Component
public class BuiltInVariableProvider implements RuleVariableProvider {

    @Resource
    private AdminUserService adminUserService;
    @Resource
    private DeptService deptService;

    /**
     * {@code requestContext} 里传「调用方已经算好的主体闭包」用的 key，值是
     * {@code Collection<SubjectKey>}
     *
     * <p>它是<b>入参</b>而不是变量：不进 {@link #VARIABLE_METADATA}，规则里也不能用
     * {@code :__subjectKeys} 引用它，所以 {@link #provide} 末尾会把它从返回值里摘掉。
     * 双下划线前缀就是为了跟 12 个正式变量在形状上区分开
     */
    public static final String CONTEXT_SUBJECT_KEYS = "__subjectKeys";

    /**
     * 变量元数据：name（引用名，条件树里以 {@code :name} 引用）、label（中文名）、
     * collection（是否集合类型）。
     *
     * <p>{@code collection=true} 的变量才能用于 IN / NOT_IN 操作符——求值器对多值操作符
     * 走集合展开，字面量会被当成单元素字符串（见 {@code ConditionTreeEvaluator.resolveValue}）。
     * 规则编辑器据此限制「IN/NOT_IN 的值只能选集合变量」。
     *
     * <p>⚠️ 这份表是 {@link #provide} 注入的变量的<b>视图元数据</b>，两者必须一致：
     * 改了 provide 里的变量名/增删变量，务必同步这里。
     */
    public record VariableMeta(String name, String label, boolean collection) {
    }

    public static final List<VariableMeta> VARIABLE_METADATA = List.of(
            new VariableMeta("currentUserId", "当前用户", false),
            new VariableMeta("currentDeptId", "当前部门", false),
            new VariableMeta("currentDeptAndChildren", "当前部门及下级", true),
            new VariableMeta("currentRoleIds", "当前用户角色", true),
            new VariableMeta("currentPostIds", "当前用户岗位", true),
            new VariableMeta("currentTenantId", "当前租户", false),
            new VariableMeta("currentDate", "今天日期", false),
            new VariableMeta("currentDateTime", "当前时间", false),
            new VariableMeta("currentTimestamp", "当前时间戳", false),
            new VariableMeta("currentYear", "今年", false),
            new VariableMeta("currentMonth", "本月", false),
            new VariableMeta("currentDayOfWeek", "今天星期几", false)
    );

    @Override
    public Map<String, Object> provide(Long userId, Map<String, Object> requestContext) {
        Map<String, Object> vars = new LinkedHashMap<>();

        // ====== 用户维度 ======
        vars.put("currentUserId", userId);

        Long deptId = null;
        Set<Long> postIds = Set.of();
        if (userId != null) {
            AdminUserDO user = adminUserService.getUser(userId);
            if (user != null) {
                deptId = user.getDeptId();
                postIds = user.getPostIds() != null ? user.getPostIds() : Set.of();
            }
        }
        vars.put("currentDeptId", deptId);

        // currentDeptAndChildren：本部门 + 子孙部门 id 集合
        Set<Long> deptAndChildren = new LinkedHashSet<>();
        if (deptId != null) {
            deptAndChildren.add(deptId);
            Set<Long> children = deptService.getChildDeptIdListFromCache(deptId);
            if (children != null) {
                deptAndChildren.addAll(children);
            }
        }
        vars.put("currentDeptAndChildren", deptAndChildren);

        // currentRoleIds：当前用户【有效】的角色 id 集合
        //
        // 🔴 取自主体闭包，不是直接查 sys_user_role，两个理由：
        //   ① 闭包里的 ROLE 主体已经滤掉停用角色（SubjectClosureServiceImpl#addRoleSeeds
        //      只对 status=ENABLE 的角色下种子）。而 getUserRoleIdListByUserIdFromCache
        //      返回的是原始关联、不做这层过滤 —— 用它的话，一个被停用的角色仍然会出现在
        //      :currentRoleIds 里，与「禁用的角色不该再给任何权限」这条既有口径直接冲突
        //   ② 匹配授权用的就是这份闭包。变量若来自别处，就会出现
        //      「授权是按 A 角色命中的，规则里的 :currentRoleIds 却没有 A」
        //
        // ⚠️ 这里【不】自己去调 SubjectClosureService：闭包在 DataRowFilterResolver#resolve
        //    里已经算过一次，而它内部要取 @Cacheable 在 Redis 上的【全量】主体祖先图。
        //    resolve 是每条带行级权限的查询都要走的热路径，重算一遍等于把开销翻倍，
        //    所以由调用方通过 requestContext 把算好的那份交进来
        Set<Long> roleIds = new LinkedHashSet<>();
        Object subjectKeys = requestContext == null ? null : requestContext.get(CONTEXT_SUBJECT_KEYS);
        if (subjectKeys instanceof Collection<?> keys) {
            for (Object key : keys) {
                if (key instanceof SubjectKey subject && subject.type() == SubjectTypeEnum.ROLE) {
                    roleIds.add(subject.refId());
                }
            }
        }
        vars.put("currentRoleIds", roleIds);

        vars.put("currentPostIds", postIds);

        Long tenantId = SecurityFrameworkUtils.getLoginUser() != null
                ? SecurityFrameworkUtils.getLoginUser().getTenantId()
                : null;
        vars.put("currentTenantId", tenantId);

        // ====== 时间维度 ======
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        vars.put("currentDate", today);
        vars.put("currentDateTime", now);
        vars.put("currentTimestamp", now.toInstant(ZoneOffset.UTC).toEpochMilli());
        vars.put("currentYear", today.getYear());
        vars.put("currentMonth", today.getMonthValue());
        vars.put("currentDayOfWeek", today.getDayOfWeek().getValue()); // 1=周一 … 7=周日

        // ====== 请求上下文（流程等） ======
        if (requestContext != null) {
            vars.putAll(requestContext); // processInstanceId / taskId 等由调用方注入
            // 入参不能漏成变量：漏出去就是一个不在 VARIABLE_METADATA 里、
            // 编辑器选不到、也没人知道存在的第 13 个变量（见 CONTEXT_SUBJECT_KEYS）
            vars.remove(CONTEXT_SUBJECT_KEYS);
        }

        return vars;
    }

}
