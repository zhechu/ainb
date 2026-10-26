package cn.enlev.ainb.module.system.service.permission;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.biz.bpm.BpmParticipantCommonApi;
import cn.enlev.ainb.framework.common.biz.system.permission.dto.DataRowFilterRespDTO;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.datapermission.core.annotation.DataPermission;
import cn.enlev.ainb.module.system.dal.dataobject.permission.GrantDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.RecordShareDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.RuleDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.GrantMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.RuleMapper;
import cn.enlev.ainb.module.system.framework.permission.core.dataset.DatasetResourceNaming;
import cn.enlev.ainb.module.system.framework.permission.core.rule.BuiltInVariableProvider;
import cn.enlev.ainb.module.system.framework.permission.core.rule.ConditionNode;
import cn.enlev.ainb.module.system.framework.permission.core.rule.ConditionTreeEvaluator;
import cn.enlev.ainb.module.system.framework.permission.core.rule.RuleVariableProvider;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectTypeEnum;
import cn.enlev.ainb.module.system.enums.permission.RuleTemplateEnum;
import cn.enlev.ainb.module.system.enums.permission.RuleTemplateMetaRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 行过滤条件解析 Service
 *
 * <h3>为什么要把这段逻辑抽出来</h3>
 *
 * 行级权限有<b>两个</b>消费方，走的是完全不同的通道：
 *
 * <ol>
 *   <li>{@code RuleDataPermissionRule} —— MyBatis-Plus 拦截器，拦走 MyBatis 的 SQL，
 *       需要的是 JSqlParser 的 {@code Expression}</li>
 *   <li>在线表单运行时 —— 用 JdbcTemplate 拼原生 SQL，MyBatis 拦截器对它完全不生效，
 *       需要的是 SQL 片段 + 参数（见 {@link DataRowFilterRespDTO}）</li>
 * </ol>
 *
 * 两边都要「取主体闭包 → 查命中的授权 → 求值条件树 → 按 ALLOW/DENY 合并」这套逻辑。
 * 各写一份的话必然漂移，而漂移的表现是<b>两个通道的可见范围不一致</b> ——
 * 同一个用户，列表里看得到的行，详情接口打不开；或者反过来，列表看不到但详情能读。
 * 这种不一致极难归因，所以逻辑只能有一份。
 *
 * <h3>合并规则（v9 §7.1 第 6 步）</h3>
 *
 * {@code OR(ALLOW 规则) AND NOT OR(DENY 规则)}
 *
 * @author 走进新时代
 */
@Service
@Slf4j
public class DataRowFilterResolver {

    @Resource
    private SubjectClosureService subjectClosureService;
    @Resource
    private ResourceMapper resourceMapper;
    @Resource
    private GrantMapper grantMapper;
    @Resource
    private RuleMapper ruleMapper;
    @Resource
    private cn.enlev.ainb.module.system.dal.mysql.permission.RecordShareMapper recordShareMapper;
    @Resource
    private ConditionTreeEvaluator conditionTreeEvaluator;
    @Resource
    private List<RuleVariableProvider> variableProviders;
    @Resource
    private ObjectMapper objectMapper;

    /**
     * 流程参与关系查询口
     *
     * 用 ObjectProvider 而不是直接注入：system <b>不依赖</b> bpm，
     * 实现由 bpm 提供。单测与「只装配 system」的环境里这个 Bean 不存在，
     * 直接注入会让所有相关用例起不来
     */
    @Resource
    private ObjectProvider<BpmParticipantCommonApi> bpmParticipantApiProvider;

    @Resource
    private cn.enlev.ainb.module.system.framework.permission.config.PermissionProperties permissionProperties;

    /**
     * 只用于超管判定。@Lazy 与 {@code GrantDecisionService} 同一理由：避免与
     * {@code RoleServiceImpl} 之间的循环依赖（角色查询自己也要过数据权限）
     */
    @Resource
    @Lazy
    private RoleService roleService;

    /**
     * 只在第一次缺失时告警，避免刷屏
     */
    private final AtomicBoolean bpmApiMissingWarned = new AtomicBoolean(false);

    /**
     * 派生可见性注入的变量名
     */
    private static final String BPM_PARTICIPANT_KEYS_VARIABLE = "bpmParticipantBusinessKeys";

    /**
     * 记录共享注入的变量名（S7b-2）
     */
    private static final String RECORD_SHARE_KEYS_VARIABLE = "sharedRecordIds";

    /**
     * 恒假片段：表达「一行都看不到」
     *
     * ⚠️ 不能用 null 或空片段代替 —— 那两者的含义是「这条规则不表达任何限制」，
     * 若它是唯一的 ALLOW 规则，最终会退化成<b>整表不过滤</b>（见 combine）
     */
    private static final ConditionTreeEvaluator.SqlFragment DENY_ALL_FRAGMENT =
            new ConditionTreeEvaluator.SqlFragment("1 = 0", List.of());

    /**
     * 恒真片段，用于表达「这个主体对这张表没有行限制」
     *
     * <h3>它对应旧 {@code data_scope} 里的 ALL（全部数据权限）</h3>
     *
     * 一条 <b>ALLOW 且没绑规则</b> 的授权，语义是「这个主体能看这张表的全部行」。
     * 这在多主体合并时至关重要：{@link #combine} 是 {@code OR(ALLOW)}，
     * 恒真参与 OR 就会盖过其它受限规则 —— 与旧体系「多角色取并集、ALL 胜出」
     * 逐字对齐。
     *
     * ⚠️ 不能用 null 或空片段代替：那两者会被当作「这条授权不表达任何东西」而被
     * 跳过，于是<b>有 ALL 角色的用户反而被别的角色的规则限制住</b> ——
     * 迁移后表现为「原本能看全部的人突然只能看本部门」，且不报任何错
     */
    private static final ConditionTreeEvaluator.SqlFragment ALLOW_ALL_FRAGMENT =
            new ConditionTreeEvaluator.SqlFragment("1 = 1", List.of());

    /**
     * 解析某张物理表的行过滤条件
     *
     * @param userId     用户编号；为 null 表示没有登录用户
     * @param tableName  物理表名
     * @param tableAlias 列名前缀，形如 {@code `biz_order`.}；可为空
     */
    @DataPermission(enable = false) // 关掉数据权限：算权限这件事本身不该再被权限过滤一遍
    public DataRowFilterRespDTO resolve(Long userId, String tableName, String tableAlias) {
        return resolve(userId, tableName, tableAlias, null);
    }

    /**
     * 同上，并额外接收「本次查询里每张表的合法限定符」
     *
     * <p>存在理由：槽位映射可以绑到<b>关联表</b>上（{@code SlotMappingDO.tableName}），
     * 而只有一个 {@code tableAlias} 时，求值器分不清「这个列该用主表限定还是关联表限定」。
     * 详见 {@code ConditionTreeEvaluator#evaluate} 那个五参重载的注释。
     *
     * @param tableQualifiers 物理表名（小写）→ 本次查询里合法的限定前缀。
     *                        传 null 表示调用方不知道（只有单表），此时一律用 tableAlias
     */
    @DataPermission(enable = false)
    public DataRowFilterRespDTO resolve(Long userId, String tableName, String tableAlias,
                                        Map<String, String> tableQualifiers) {
        if (StrUtil.isBlank(tableName)) {
            return DataRowFilterRespDTO.pass();
        }
        // 1. 没有登录用户：交给上层决定拒绝与否，这里不追加过滤。
        //    定时任务、MQ 消费者这类无人上下文会走到这里
        if (userId == null) {
            return DataRowFilterRespDTO.pass();
        }

        // 2. 主体闭包。取不到就全拒 —— 把异常变成「不过滤」等于把异常变成越权
        Set<SubjectKey> subjects;
        try {
            subjects = subjectClosureService.getSubjectKeysByUserId(userId);
        } catch (Exception e) {
            log.warn("[resolve][获取主体闭包失败 userId={}，按全拒处理]", userId, e);
            return DataRowFilterRespDTO.denyAll();
        }
        if (CollUtil.isEmpty(subjects)) {
            return DataRowFilterRespDTO.denyAll();
        }

        // 2.5 超管不受行级限制，理由见 isSuperAdmin
        if (isSuperAdmin(subjects)) {
            return DataRowFilterRespDTO.pass();
        }

        // 3. 这张表对应的数据集资源
        List<ResourceDO> resources = resourceMapper.selectListByDatasetTable(tableName.toLowerCase());
        if (resources.isEmpty()) {
            return DataRowFilterRespDTO.pass();
        }
        Set<Long> resourceIds = new LinkedHashSet<>();
        resources.forEach(r -> resourceIds.add(r.getId()));

        // 4. 这些资源上命中当前主体的授权
        //
        // ⚠️ 这里【不能】先按 ruleId != null 过滤。
        // 「ALLOW 且没绑规则」不是「一条无关的授权」，而是明确的「这个主体不受行限制」——
        // 等价于旧 data_scope 里的 ALL。把它滤掉的后果是多角色合并时 ALL 不参与表决：
        // 一个既有超管角色（ALL）又有普通角色（本部门）的用户，
        // 会只剩下「本部门」那条规则生效，<b>迁移后权限凭空收紧且不报错</b>。
        List<GrantDO> matched = grantMapper.selectListByResourceIds(resourceIds).stream()
                .filter(g -> subjects.stream().anyMatch(g::subjectMatches))
                .toList();
        if (matched.isEmpty()) {
            return defaultEffectOf(resources);
        }
        // 一条规则都没绑过 → 这张表上没有任何行级配置，保持不过滤（与历史行为一致）
        if (matched.stream().allMatch(g -> g.getRuleId() == null && !"DENY".equals(g.getEffect()))) {
            return DataRowFilterRespDTO.pass();
        }

        // 6. 逐条求值并按效果分组
        //
        // ⚠️ 这个循环里「求值失败」的处理是有讲究的，别改成 continue ——
        // 详见下面 DENY / ALLOW 两个分支各自的注释。一句话：跳过等于把
        // 「规则配错了」变成「不过滤」，而那比不配规则还宽。
        // （「规则被停用」是另一回事，它就该 continue，理由见那里）
        Map<String, Object> variables;
        try {
            variables = buildVariables(userId, subjects);
        } catch (Exception e) {
            // 变量算不出来就往下走的话，引用了它的条件会被求值器静默丢掉，
            // 结果是过滤变宽而不是变窄。与上面「取主体闭包失败」同一处置
            log.error("[resolve][构建规则变量失败 userId={}，表({})按全拒处理]", userId, tableName, e);
            return DataRowFilterRespDTO.denyAll();
        }
        List<ConditionTreeEvaluator.SqlFragment> allow = new ArrayList<>();
        List<ConditionTreeEvaluator.SqlFragment> deny = new ArrayList<>();
        // 本次因「规则已停用」而跳过的授权条数。只用于最后那条告警，见 combine 之后
        int disabledSkipped = 0;
        for (GrantDO grant : matched) {
            // 没绑规则的授权：表达「无行限制」（ALLOW）或「整表拒绝」（DENY），不需要求值
            //
            // ALLOW 无规则 = 旧 data_scope 的 ALL，贡献恒真，OR 之后盖过其它受限规则。
            // DENY 无规则 = 「这个主体完全不能看这张表」，贡献恒真的 DENY，
            // combine 里 NOT(恒真) 会把整表挡掉 —— 与「配了 DENY 却没生效」相比，
            // 这是有人会来报的那种失败方式
            if (grant.getRuleId() == null) {
                if ("DENY".equals(grant.getEffect())) {
                    deny.add(ALLOW_ALL_FRAGMENT);
                } else {
                    allow.add(ALLOW_ALL_FRAGMENT);
                }
                continue;
            }
            RuleDO rule = ruleMapper.selectById(grant.getRuleId());
            if (rule == null) {
                // 授权指向了一条不存在的规则。这是数据损坏，不是「没配规则」
                log.error("[resolve][授权 {} 指向的规则 {} 不存在，表({})按全拒处理]",
                        grant.getId(), grant.getRuleId(), tableName);
                return DataRowFilterRespDTO.denyAll();
            }
            // 规则已停用：这条授权在本次判定里不参与。
            //
            // 🔴 这里是 continue（放行），而下面「求不出条件」是恒假/全拒 —— 两种看起来
            //    同类的失败被刻意区别对待，别把它们统一掉。区别在于「谁的决定」：
            //      · 求不出条件 = 事故（槽位没配、params 缺字段）。此时我们【不知道】
            //        管理员想挡住哪些行，猜错就是越权，所以一律往紧里落。
            //      · 停用 = 管理员在界面上亲手按下的开关，语义就是「这条规则别生效了」。
            //        此时往紧里落不是安全，是把开关的含义反过来：
            //        停用一条 DENY 规则本该解除禁令，结果变成整表不可见 ——
            //        而且内置规则只能停用不能删（RULE_BUILT_IN_UNDELETABLE），
            //        一旦落恒假，管理员就再没有任何办法把它关掉。
            //
            // 这也与本方法既有口径一致：数据集资源停用时 selectListByDatasetTable
            // 直接查不出来（ResourceMapper:126 带 status=ENABLE），结果是整表不过滤；
            // 授权配置页的规则下拉同样只列启用规则（RuleAdminController#getSimpleRuleList）。
            // 前端删除规则的确认文案说的也是这个语义：「引用它的授权行将失去行过滤效果」。
            //
            // ⚠️ 代价要说清楚：若停用的是该表上【唯一】的 ALLOW 规则，allow 与 deny
            //    会同时为空 → combine 返回 pass() → 整表不过滤。这是上面那套语义的
            //    必然推论，不是漏洞，但它确实会让可见范围变宽且界面无感，
            //    所以 combine 之后专门补了一条 WARN 把这种情况喊出来
            if (CommonStatusEnum.isDisable(rule.getStatus())) {
                disabledSkipped++;
                log.warn("[resolve][规则 {}({}) 已停用，授权 {} 在表({})上不参与行过滤]",
                        rule.getId(), rule.getName(), grant.getId(), tableName);
                continue;
            }
            // 用授权行自己的 resourceId 解析槽位：一张物理表可能被多张表单绑定，
            // 槽位映射按资源存，取错资源拿到的是另一张表单的列名
            ConditionTreeEvaluator.SqlFragment fragment =
                    evaluate(rule, grant.getResourceId(), variables, tableAlias, userId, tableQualifiers);
            // ⚠️ unevaluable 与 isEmpty 是两件事：前者说「树里有条件求不出来」，
            //    此时 sql 可能是非空的半截条件。拿半截去过滤比不过滤更危险 ——
            //    它看着像生效了，实际比管理员配的规则宽。两者一律按「求不出来」处理
            boolean unevaluable = fragment == null || fragment.isEmpty() || fragment.unevaluable();
            if ("DENY".equals(grant.getEffect())) {
                if (unevaluable) {
                    // 一条算不出来的 DENY：我们不知道该挡住哪些行。
                    // 忽略它 = 这条禁止令静默失效 = 用户看到了本不该看的数据，
                    // 而且没有任何迹象。整表拒掉至少是「有人会来报」的失败方式
                    log.error("[resolve][DENY 规则 {}（授权 {}）求不出条件，表({})按全拒处理。"
                            + "常见原因：槽位没配映射、槽位绑的表不在本次查询里、params 缺字段]",
                            rule.getId(), grant.getId(), tableName);
                    return DataRowFilterRespDTO.denyAll();
                }
                deny.add(fragment);
            } else {
                // 一条算不出来的 ALLOW：它什么都没授予，所以贡献一个恒假条件。
                //
                // ⚠️ 这里【不能】continue。跳过的话，若它是唯一的 ALLOW 规则，
                // 最终 allow 与 deny 都为空 → combine 返回 pass() → 整表不过滤，
                // 比不配这条规则还宽。用恒假条件则天然正确：
                // 与其它 ALLOW 取 OR 时不影响它们，独自存在时就是「一行都看不到」
                if (unevaluable) {
                    log.warn("[resolve][ALLOW 规则 {}（授权 {}）求不出条件，"
                            + "该条按「不授予任何行」处理。常见原因：槽位没配映射、"
                            + "槽位绑的表不在本次查询里、params 缺字段]",
                            rule.getId(), grant.getId());
                    allow.add(DENY_ALL_FRAGMENT);
                } else {
                    allow.add(fragment);
                }
            }
        }

        DataRowFilterRespDTO result = combine(allow, deny);
        if (disabledSkipped > 0 && !result.isFiltered()) {
            // 「停用规则」这条路上唯一会静默放宽的场景：停完之后这张表对这个人
            // 已经没有任何行级限制了。管理员在界面上只看到开关变灰，看不到这句，
            // 所以必须落在日志里 —— 否则事后排查「为什么他能看全表」毫无线索
            log.warn("[resolve][表({}) 用户({}) 跳过了 {} 条停用规则后已无任何行级限制，"
                    + "当前按整表可见处理]", tableName, userId, disabledSkipped);
        }
        return result;
    }

    /**
     * 主体在这张表上<b>没有任何命中授权</b>时，按数据集资源的 {@code default_effect} 兜底
     *
     * <h3>🔴 这里不能硬编码 pass()</h3>
     *
     * {@code sys_resource.default_effect} 的契约就是「没有授权命中时的效果」
     * （{@code ResourceTypeEnum#defaultEffect}：ACTION/MENU 为 DENY，DATASET 为 ALLOW）。
     * 硬编码放行的后果是这个配置项<b>永远是死的</b>：管理员把某个数据集的默认效果
     * 改成 DENY，界面上显示生效了，判定侧却照旧全表放行 —— 一种不报错的配置失效。
     *
     * <h3>为什么多个数据集时 DENY 优先</h3>
     *
     * 一张物理表理论上能挂多个数据集资源。合并口径与授权的合并规则
     * （{@code OR(ALLOW) AND NOT OR(DENY)}，见类注释）保持一致：DENY 一票否决。
     *
     * <h3>对存量数据零影响</h3>
     *
     * 出厂时 DATASET / COLUMN / FIELD 的 default_effect 全是 ALLOW
     * （实测 178 个 DATASET 无一例外），所以「没配过行级权限的表照旧不过滤」
     * 这条历史行为不变；只有管理员显式配了 DENY 的数据集才会走到 denyAll。
     */
    private DataRowFilterRespDTO defaultEffectOf(List<ResourceDO> resources) {
        for (ResourceDO resource : resources) {
            if ("DENY".equals(resource.getDefaultEffect())) {
                return DataRowFilterRespDTO.denyAll();
            }
        }
        return DataRowFilterRespDTO.pass();
    }

    /**
     * 主体闭包里有没有超管角色
     *
     * <h3>🔴 为什么行级过滤也必须短路</h3>
     *
     * 功能权限侧三处都已经对超管短路了 —— {@code PermissionServiceImpl#hasAnyPermissions}、
     * {@code GrantDecisionService#listAllowedPermissions}、{@code #listAllowedMenuIds}。
     * 唯独行级过滤没有，于是「超管能看到哪些<b>行</b>」变成了一件取决于
     * {@code sys_grant} 数据是否完好的事：那条「无规则的 ALLOW」一旦被误删
     * （实测会被套餐重算删掉，见 {@code PermissionServiceImpl#filterMenuDerived} 的注释），
     * 超管就退化成它身上另一个普通角色的数据范围 ——
     * 表现是「我是超管，怎么只看得到本部门的人」，而且没有任何报错。
     *
     * 短路之后这条路径不再依赖授权数据，与「super_admin 绕过一切权限点」的既有口径一致。
     *
     * <h3>为什么读闭包而不是照抄 {@code GrantDecisionService#isSuperAdmin}</h3>
     *
     * 闭包上一步已经算好了，里面就有 {@code ROLE} 主体，再查一次 {@code system_user_role}
     * 是白花 —— 本方法在每条命中数据集的 SQL 上都要跑一次。
     * 两者只有一处差异：闭包只收<b>启用中</b>的角色，那条路不看角色状态。
     * 停用的超管角色在这里不短路，是刻意的：判不出来时宁可少放行。
     */
    private boolean isSuperAdmin(Set<SubjectKey> subjects) {
        Set<Long> roleIds = new LinkedHashSet<>();
        for (SubjectKey subject : subjects) {
            if (subject.type() == SubjectTypeEnum.ROLE) {
                roleIds.add(subject.refId());
            }
        }
        if (roleIds.isEmpty()) {
            return false;
        }
        try {
            return roleService.hasAnySuperAdmin(roleIds);
        } catch (Exception e) {
            // 判不出来时按【非超管】处理：宁可少给权限，也不能因为一次异常把全部数据放出去
            log.warn("[isSuperAdmin][超管判定失败，按非超管处理]", e);
            return false;
        }
    }

    /**
     * 按 v9 §7.1 第 6 步合并：OR(ALLOW) AND NOT OR(DENY)
     */
    private DataRowFilterRespDTO combine(List<ConditionTreeEvaluator.SqlFragment> allow,
                                         List<ConditionTreeEvaluator.SqlFragment> deny) {
        List<Object> params = new ArrayList<>();
        String allowSql = joinOr(allow, params);
        String denySql = joinOr(deny, params);

        if (allowSql == null && denySql == null) {
            return DataRowFilterRespDTO.pass();
        }
        String sql;
        if (allowSql == null) {
            // 只有 DENY，没有 ALLOW：除了被禁的都能看
            sql = "NOT (" + denySql + ")";
        } else if (denySql == null) {
            sql = "(" + allowSql + ")";
        } else {
            sql = "(" + allowSql + ") AND NOT (" + denySql + ")";
        }
        return DataRowFilterRespDTO.of(sql, params);
    }

    private String joinOr(List<ConditionTreeEvaluator.SqlFragment> fragments, List<Object> params) {
        if (fragments.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>(fragments.size());
        for (ConditionTreeEvaluator.SqlFragment fragment : fragments) {
            parts.add("(" + fragment.sql() + ")");
            params.addAll(fragment.params());
        }
        return String.join(" OR ", parts);
    }

    /**
     * 派生可见性：从流程参与关系反推「能看业务表的哪几行」
     *
     * <h3>⚠️ 这个方法里所有失败路径都返回「恒假」，一条都不返回 null</h3>
     *
     * 这是本方法最要紧的一条约束。看一眼 {@link #combine} 就明白为什么：
     * 一条 ALLOW 规则若求值成 null 或空片段，它会被 {@code resolve} 的循环
     * {@code continue} 掉；如果它是<b>唯一</b>的 ALLOW 规则，
     * 最终 {@code allowSql == null && denySql == null} → 返回 {@code pass()}
     * —— <b>整表不过滤</b>。
     *
     * 也就是说：「配了派生可见性但取不到参与数据」会变成
     * <b>比不配还宽</b>的全表可见。这正是本项目最贵的那类缺陷
     * （配了、存了、没报错、但效果与预期相反）。
     *
     * 所以：表单编码解析不出来、bpm 模块不可用、他一条流程都没参与过、
     * 槽位没映射 —— 统统落成 {@code 1 = 0}，宁可让他什么都看不到。
     * 看不到会有人来报，悄悄放开没有人会发现。
     */
    private ConditionTreeEvaluator.SqlFragment evaluateBpmParticipant(
            Long resourceId, Map<String, Object> variables, String columnPrefix, Long userId,
            Map<String, String> tableQualifiers) {
        // 1. 从资源反查表单编码。数据集资源的权限串形如 infra:form-design-run:<formCode>
        String formCode = resolveFormCode(resourceId);
        if (formCode == null) {
            log.warn("[evaluateBpmParticipant][资源 {} 不是在线表单的数据集资源，"
                    + "派生可见性无从谈起，按全拒处理]", resourceId);
            return DENY_ALL_FRAGMENT;
        }

        // 2. 取参与过的业务主键。
        //    ⚠️ resolve() 上的 @DataPermission(enable = false) 会一路传导到这次查询 ——
        //    否则「算权限」这件事本身又会触发一次行级过滤，直接递归
        BpmParticipantCommonApi api = bpmParticipantApiProvider.getIfAvailable();
        if (api == null) {
            if (bpmApiMissingWarned.compareAndSet(false, true)) {
                log.error("[evaluateBpmParticipant][BpmParticipantCommonApi 不可用，"
                        + "派生可见性规则一律按全拒处理！若非单元测试环境，"
                        + "请检查 enlev-module-bpm 是否已装配]");
            }
            return DENY_ALL_FRAGMENT;
        }
        Set<String> businessKeys = api.getParticipatedBusinessKeys(userId, formCode);
        if (CollUtil.isEmpty(businessKeys)) {
            // 他一条都没参与过 —— 这不是异常，是正常的「看不到任何行」
            return DENY_ALL_FRAGMENT;
        }
        // 规模告警。⚠️ 只告警，不改变行为 —— 截断会静默少数据、
        // 拒绝会让「参与得越多看到的越少」，两者都比慢查询糟糕得多。
        // 这条日志的作用是让问题在变成线上事故之前先被人看见（见配置项注释）
        int threshold = permissionProperties.getDerivedKeyWarnThreshold() == null
                ? Integer.MAX_VALUE : permissionProperties.getDerivedKeyWarnThreshold();
        if (businessKeys.size() > threshold) {
            log.error("[evaluateBpmParticipant][用户({})在表单({})上的派生可见范围已达 {} 条，"
                    + "超过阈值 {}。IN 列表会随之变长，先慢查询、最终撞 max_allowed_packet。"
                    + "处理方向：把参与表按 retain_until 归档，或改用子查询（需确认业务表与"
                    + "bpm_instance_participant 在同一数据源）]",
                    userId, formCode, businessKeys.size(), threshold);
        }

        // 3. 走条件树求值，借它的列名白名单校验（防注入的关键在那一步，不能绕过）。
        //    集合值通过变量注入，与 STATUS_IN 是同一个路数 ——
        //    ConditionNode.value 是 String，装不下集合
        variables.put(BPM_PARTICIPANT_KEYS_VARIABLE, businessKeys);
        ConditionNode node = cond("bizKey", "IN", ":" + BPM_PARTICIPANT_KEYS_VARIABLE);
        ConditionTreeEvaluator.SqlFragment fragment =
                conditionTreeEvaluator.evaluate(node, resourceId, variables, columnPrefix, tableQualifiers);
        if (fragment == null || fragment.isEmpty() || fragment.unevaluable()) {
            // 走到这里基本只有一个原因：资源上没配 bizKey 槽位映射，
            // 或者配了但绑到了一张本次查询里没有的表上。
            // 求值器对此只打日志并返回空片段/半截片段，两者都不能拿去过滤
            log.warn("[evaluateBpmParticipant][资源 {} 的 bizKey 槽位求不出列，"
                    + "派生可见性无法落到具体列上，按全拒处理]", resourceId);
            return DENY_ALL_FRAGMENT;
        }
        return fragment;
    }

    /**
     * 由资源编号反查在线表单编码
     *
     * @return 表单编码；该资源不是在线表单数据集时返回 null
     */
    /**
     * 记录共享的派生可见性（S7b-2）
     *
     * <h3>与 {@link #evaluateBpmParticipant} 共用一条通道</h3>
     *
     * 两者都是「一串具体的业务主键对某人可见」，都落在 {@code bizKey} 槽位上，
     * 只是主键集合的来源不同：一个来自流程参与事实，一个来自人工分享。
     * 所以这里的每一条降级方向都与那边<b>逐字对齐</b> ——
     * 两条派生路径对「取不到数据」给出不同答案的话，
     * 会出现「共享的看得到、参与的看不到」这种没人能解释的不一致。
     *
     * <h3>⚠️ 取不到就是恒假，不是不过滤</h3>
     *
     * 理由完全同 {@link #evaluateBpmParticipant}：
     * 一条求不出条件的 ALLOW 若被跳过，最终会退化成<b>整表可见</b> ——
     * 配了比不配还宽。看不到会有人来报，悄悄放开没有人会发现。
     */
    private ConditionTreeEvaluator.SqlFragment evaluateRecordShared(
            Long resourceId, Map<String, Object> variables, String columnPrefix, Long userId,
            Map<String, String> tableQualifiers) {
        // 1. 数据集编码。在线表单取 form code，原生数据集取权限串本身 ——
        //    两者都是 sys_record_share.dataset_code 的合法取值
        String datasetCode = resolveDatasetCode(resourceId);
        if (datasetCode == null) {
            log.warn("[evaluateRecordShared][资源 {} 不是数据集资源，记录共享无从谈起，按全拒处理]",
                    resourceId);
            return DENY_ALL_FRAGMENT;
        }

        // 2. 取分享给他的记录。主体闭包已经在 resolve() 里算过一次，这里重算一次 ——
        //    看着浪费，但 getSubjectKeysByUserId 走的是缓存，且把它顺着参数传下来
        //    要穿过四层方法签名，漏传一层就是一处静默的「共享不生效」
        Set<SubjectKey> subjects;
        try {
            subjects = subjectClosureService.getSubjectKeysByUserId(userId);
        } catch (Exception e) {
            log.error("[evaluateRecordShared][获取主体闭包失败 userId={}，按全拒处理]", userId, e);
            return DENY_ALL_FRAGMENT;
        }
        Set<String> recordIds = new LinkedHashSet<>();
        for (RecordShareDO share : recordShareMapper.selectListBySubjects(datasetCode, subjects)) {
            // ⚠️ 内存里再核一遍主体。SQL 那边的分支拼错了的话，
            // 后果是【多放行】—— 而多放行是没人会来报的那种错。
            // 这一遍的成本是一次 Set 遍历，值得
            if (StrUtil.isNotBlank(share.getRecordId())
                    && subjects.stream().anyMatch(share::subjectMatches)) {
                recordIds.add(share.getRecordId());
            }
        }
        if (CollUtil.isEmpty(recordIds)) {
            // 没人分享给他 —— 这不是异常，是正常的「看不到任何行」
            return DENY_ALL_FRAGMENT;
        }
        // 规模告警，口径同派生可见性：只告警、不改变行为
        int threshold = permissionProperties.getDerivedKeyWarnThreshold() == null
                ? Integer.MAX_VALUE : permissionProperties.getDerivedKeyWarnThreshold();
        if (recordIds.size() > threshold) {
            log.error("[evaluateRecordShared][用户({})在数据集({})上被分享的记录已达 {} 条，"
                            + "超过阈值 {}。IN 列表会随之变长，先慢查询、最终撞 max_allowed_packet。"
                            + "处理方向：清理过期共享，或改用子查询]",
                    userId, datasetCode, recordIds.size(), threshold);
        }

        // 3. 走条件树求值，借它的列名白名单校验（防注入的关键在那一步，不能绕过）
        variables.put(RECORD_SHARE_KEYS_VARIABLE, recordIds);
        ConditionNode node = cond("bizKey", "IN", ":" + RECORD_SHARE_KEYS_VARIABLE);
        ConditionTreeEvaluator.SqlFragment fragment =
                conditionTreeEvaluator.evaluate(node, resourceId, variables, columnPrefix, tableQualifiers);
        if (fragment == null || fragment.isEmpty() || fragment.unevaluable()) {
            log.warn("[evaluateRecordShared][资源 {} 的 bizKey 槽位求不出列，"
                            + "记录共享算不出条件，按全拒处理。"
                            + "正常情况下这个槽位由发布/启动登记自动写入]", resourceId);
            return DENY_ALL_FRAGMENT;
        }
        return fragment;
    }

    /**
     * 从资源反查数据集编码
     *
     * 在线表单是 {@code infra:form-design-run:<formCode>} → 取 formCode；
     * 原生数据集的权限串本身就是编码（{@code system:user}）→ 原样返回。
     *
     * ⚠️ 两种形态都要认。只认在线表单的话，原生模块上配的记录共享
     * 会因为「解析不出数据集」而恒假 —— 表现是「分享了但对方看不到」
     */
    private String resolveDatasetCode(Long resourceId) {
        String formCode = resolveFormCode(resourceId);
        if (formCode != null) {
            return formCode;
        }
        if (resourceId == null) {
            return null;
        }
        ResourceDO resource = resourceMapper.selectById(resourceId);
        if (resource == null || StrUtil.isBlank(resource.getPermission())
                || StrUtil.isBlank(resource.getDatasetTable())) {
            // 要求 datasetTable 非空 = 它确实是个数据集节点，而不是菜单/动作节点
            return null;
        }
        return resource.getPermission();
    }

    private String resolveFormCode(Long resourceId) {
        if (resourceId == null) {
            return null;
        }
        ResourceDO resource = resourceMapper.selectById(resourceId);
        if (resource == null || StrUtil.isBlank(resource.getPermission())) {
            return null;
        }
        String permission = resource.getPermission();
        if (!permission.startsWith(DatasetResourceNaming.RUN_PERMISSION_PREFIX)) {
            return null;
        }
        String formCode = permission.substring(DatasetResourceNaming.RUN_PERMISSION_PREFIX.length());
        // 列/字段资源的权限串是 <dataset>:col:xxx / :fld:xxx，它们不是数据集本身
        if (formCode.contains(DatasetResourceNaming.COLUMN_SEGMENT)
                || formCode.contains(DatasetResourceNaming.FIELD_SEGMENT)) {
            return null;
        }
        return StrUtil.blankToDefault(formCode, null);
    }

    private ConditionTreeEvaluator.SqlFragment evaluate(RuleDO rule, Long resourceId,
                                                        Map<String, Object> variables,
                                                        String columnPrefix, Long userId,
                                                        Map<String, String> tableQualifiers) {
        try {
            RuleTemplateEnum tpl = RuleTemplateEnum.parse(rule.getTemplate());

            // 0. 派生可见性路径：从「一串具体的业务主键」反推可见的业务行。
            //    两条来源，同一条通道（都落在 bizKey 槽位上）
            if (tpl == RuleTemplateEnum.BPM_PARTICIPANT) {
                return evaluateBpmParticipant(resourceId, variables, columnPrefix, userId, tableQualifiers);
            }
            if (tpl == RuleTemplateEnum.RECORD_SHARED) {
                return evaluateRecordShared(resourceId, variables, columnPrefix, userId, tableQualifiers);
            }

            // 1. 内置模板路径：根据 template 字段展开成条件节点。
            //
            // ⚠️ 这是一个既有缺陷的修复。旧代码只认 params.conditionTree，
            // 而 8 条内置种子规则的 params 全是 NULL —— 于是每一条都静默返回 null，
            // 等于不过滤，管理员配的「仅自己的数据」「本部门」等规则从来没生效过。
            //
            // 内置模板不需要 params 里的 conditionTree，它们的结构是固定的，
            // 直接在这里用 Java 构建出来，不走 JSON 反序列化。
            if (tpl != null && !tpl.isCustomTree() && !tpl.isDerived()) {
                ConditionNode node = expandBuiltInTemplate(tpl, rule, variables);
                if (node == null) {
                    return null;
                }
                return conditionTreeEvaluator.evaluate(node, resourceId, variables, columnPrefix, tableQualifiers);
            }

            // 2. 自定义条件树路径：从 params.conditionTree 读
            if (StrUtil.isBlank(rule.getParams())) {
                return null;
            }
            Map<?, ?> params = objectMapper.readValue(rule.getParams(), Map.class);
            Object tree = params.get(RuleTemplateMetaRegistry.PARAM_CONDITION_TREE);
            if (tree == null) {
                return null;
            }
            ConditionNode node = objectMapper.readValue(
                    objectMapper.writeValueAsString(tree), ConditionNode.class);
            // 列前缀在求值阶段注入，不做事后正则替换 —— 理由见 ConditionTreeEvaluator.evaluate
            return conditionTreeEvaluator.evaluate(node, resourceId, variables, columnPrefix, tableQualifiers);
        } catch (Exception e) {
            log.warn("[evaluate][解析规则 {} 失败，跳过该条]", rule.getId(), e);
            return null;
        }
    }

    /**
     * 把内置模板展开成条件节点
     *
     * <p>为什么用 Java 构建而不是在 sys_rule.params 里预填 conditionTree JSON：
     *
     * <ol>
     *   <li>内置种子数据已经在库里，无法保证所有部署环境都跑了 UPDATE —— 用代码展开，
     *       不论 params 列是空还是老格式都正确</li>
     *   <li>内置模板的结构是固定的，code review 里看得到，而预填 JSON
     *       埋在种子 SQL 里，下一个读到代码的人不会联想到那边</li>
     *   <li>STATUS_IN 需要从 params 里读 allowedStatuses，通过把值注入 variables
     *       让评估器走集合展开路径，其余内置模板不需要 params</li>
     * </ol>
     *
     * @param variables 可变的变量上下文，STATUS_IN 会往里注入 allowedStatuses
     * @return 展开后的节点；DERIVED / BPM_PARTICIPANT 等不走这条路径，返回 null
     */
    // ⚠️ 本方法是模板「读哪些 params key」的契约真相。新增/修改模板参数时，
    //    必须同步 RuleTemplateMetaRegistry 里对应模板的 paramSpecs（规则编辑器据此渲染），
    //    两边共用 PARAM_* 常量；RuleTemplateMetaRegistryTest 会做穷尽性校验。
    private ConditionNode expandBuiltInTemplate(RuleTemplateEnum tpl, RuleDO rule,
                                                Map<String, Object> variables) throws Exception {
        return switch (tpl) {
            case OWNER_ONLY -> cond("owner", "EQ", ":currentUserId");
            case DEPT_ONLY -> cond("dept", "EQ", ":currentDeptId");
            case DEPT_AND_CHILDREN -> cond("dept", "IN", ":currentDeptAndChildren");
            case OWNER_OR_DEPT -> group(ConditionNode.LogicOperator.OR,
                    cond("owner", "EQ", ":currentUserId"),
                    cond("dept", "EQ", ":currentDeptId"));
            case STATUS_IN -> {
                // allowedStatuses 来自配置期参数，存在 sys_rule.params 里。
                // 注入到 variables 让评估器通过 :allowedStatuses 变量引用取到它，
                // 进而走 IN (?,?,?) 的集合展开路径 ——
                // 不能直接赋给 ConditionNode.value，那个字段是 String，装不了 List
                if (StrUtil.isBlank(rule.getParams())) {
                    log.warn("[expandBuiltInTemplate][STATUS_IN 规则 {} 缺少 params，跳过]",
                            rule.getId());
                    yield null;
                }
                Map<?, ?> params = objectMapper.readValue(rule.getParams(), Map.class);
                Object statuses = params.get(RuleTemplateMetaRegistry.PARAM_ALLOWED_STATUSES);
                if (statuses == null) {
                    log.warn("[expandBuiltInTemplate][STATUS_IN 规则 {} 的 params 里没有 allowedStatuses，跳过]",
                            rule.getId());
                    yield null;
                }
                // 注入到变量上下文，ConditionNode 里用 ":allowedStatuses" 引用
                variables.put("allowedStatuses", statuses);
                yield cond("status", "IN", ":allowedStatuses");
            }
            case DEPT_IN -> {
                // deptIds 来自配置期参数，存在 sys_rule.params 里，路数同 STATUS_IN
                if (StrUtil.isBlank(rule.getParams())) {
                    log.warn("[expandBuiltInTemplate][DEPT_IN 规则 {} 缺少 params，跳过]", rule.getId());
                    yield null;
                }
                Map<?, ?> params = objectMapper.readValue(rule.getParams(), Map.class);
                Object deptIds = params.get(RuleTemplateMetaRegistry.PARAM_DEPT_IDS);
                if (deptIds == null) {
                    log.warn("[expandBuiltInTemplate][DEPT_IN 规则 {} 的 params 里没有 deptIds，跳过]",
                            rule.getId());
                    yield null;
                }
                variables.put("ruleDeptIds", deptIds);
                // ⚠️ 必须 OR 上「自己的部门」，这是旧 DEPT_CUSTOM 的兜底语义。
                // 漏掉它的表现是「配了指定部门，那个部门的人反而登不进来」——
                // 登录时基于 username 查 system_users 会被 dept_id 条件挡掉
                yield group(ConditionNode.LogicOperator.OR,
                        cond("dept", "IN", ":ruleDeptIds"),
                        cond("dept", "EQ", ":currentDeptId"));
            }
            case FIELD_EQUALS -> {                if (StrUtil.isBlank(rule.getParams())) {
                    log.warn("[expandBuiltInTemplate][FIELD_EQUALS 规则 {} 缺少 params，跳过]", rule.getId());
                    yield null;
                }
                Map<?, ?> params = objectMapper.readValue(rule.getParams(), Map.class);
                Object field = params.get(RuleTemplateMetaRegistry.PARAM_FIELD);
                Object value = params.get(RuleTemplateMetaRegistry.PARAM_VALUE);
                if (field == null || value == null) {
                    log.warn("[expandBuiltInTemplate][FIELD_EQUALS 规则 {} 的 params 缺少 field 或 value，跳过]",
                            rule.getId());
                    yield null;
                }
                yield cond(String.valueOf(field), "EQ", String.valueOf(value));
            }
            default -> null;
        };
    }

    /** 构建一个 CONDITION 节点 */
    private static ConditionNode cond(String slot, String operator, String value) {
        ConditionNode node = new ConditionNode();
        node.setType(ConditionNode.NodeType.CONDITION);
        node.setSlot(slot);
        node.setOperator(operator);
        node.setValue(value);
        return node;
    }

    /** 构建一个 GROUP 节点 */
    private static ConditionNode group(ConditionNode.LogicOperator logic, ConditionNode... children) {
        ConditionNode node = new ConditionNode();
        node.setType(ConditionNode.NodeType.GROUP);
        node.setLogic(logic);
        node.setChildren(new ArrayList<>(List.of(children)));
        return node;
    }

    /**
     * 汇总所有 {@link RuleVariableProvider} 提供的变量
     *
     * @param subjects 本次判定用的主体闭包，原样透给提供者复用（见
     *                 {@link BuiltInVariableProvider#CONTEXT_SUBJECT_KEYS}）
     * @throws RuntimeException 任一提供者抛异常时原样上抛，由 {@link #resolve} 落成全拒
     */
    private Map<String, Object> buildVariables(Long userId, Set<SubjectKey> subjects) {
        // 🔴 这里【不能】catch 住提供者的异常然后跳过它。
        //
        // 跳过的后果不是「少一个变量」那么轻。ConditionTreeEvaluator 对「变量取不到值」的
        // 处置是丢掉那个条件、且刻意【不】标 unevaluable —— 那本来是为「这个用户没有部门」
        // 这类正常的数据状况准备的。而 AND 组里少一个条件 = 过滤变宽 = 提权，全程不报错。
        // 所以异常必须往上冒：与「取主体闭包失败按全拒」是同一个口径，
        // 异常不该有机会变成越权
        Map<String, Object> context = new HashMap<>(2);
        context.put(BuiltInVariableProvider.CONTEXT_SUBJECT_KEYS, subjects);
        Map<String, Object> vars = new LinkedHashMap<>();
        for (RuleVariableProvider provider : variableProviders) {
            Map<String, Object> provided = provider.provide(userId, context);
            if (provided != null) {
                vars.putAll(provided);
            }
        }
        return vars;
    }

}
