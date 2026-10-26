package cn.enlev.ainb.module.system.service.permission;

import cn.hutool.core.collection.CollUtil;
import cn.enlev.ainb.module.system.dal.dataobject.permission.GrantDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.RoleDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.RuleDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.GrantMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.RoleMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.RuleMapper;
import cn.enlev.ainb.module.system.enums.permission.DataScopeEnum;
import cn.enlev.ainb.module.system.enums.permission.GrantActionEnum;
import cn.enlev.ainb.module.system.enums.permission.RuleTemplateEnum;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectTypeEnum;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code system_role.data_scope} → {@code sys_grant} + {@code sys_rule} 迁移 Service
 *
 * <h3>它收敛的是什么</h3>
 *
 * 在此之前，同一张 {@code system_users} 表上压着<b>两套互不知情的行过滤</b>：
 *
 * <ol>
 *   <li>旧的 {@code data_scope} —— 由 {@code DeptDataPermissionRule} 消费，
 *       与 {@code grant-enabled} 开关无关，一直在生效</li>
 *   <li>新的行过滤规则 —— 由 {@code RuleDataPermissionRule} 消费，
 *       走 {@code sys_grant.rule_id}</li>
 * </ol>
 *
 * 两者都是 MyBatis-Plus 的 {@code DataPermissionRule}，会<b>同时</b>作用于同一条 SQL
 * 并取交集。排查时分不清一条被过滤掉的记录是哪套干的。
 *
 * 本 Service 把前者翻译成后者，翻译完就可以摘掉 {@code DataPermissionConfiguration}
 * 里的 {@code addDeptColumn}/{@code addUserColumn}，只留一套。
 *
 * <h3>⚠️ 翻译的等价性靠三件事保证</h3>
 *
 * <ol>
 *   <li><b>ALL 必须落成一条「无规则的 ALLOW」</b>，不能什么都不写。
 *       旧体系多角色取并集、ALL 胜出；新体系若 ALL 角色不留痕，
 *       该用户就只剩下受限角色的规则生效 —— 权限凭空收紧。
 *       见 {@code DataRowFilterResolver} 里 ALLOW_ALL_FRAGMENT 的注释</li>
 *   <li><b>DEPT_CUSTOM 要带上「自己的部门」兜底</b>，由
 *       {@link RuleTemplateEnum#DEPT_IN} 的表达式保证</li>
 *   <li><b>dept_ids 为空的 DEPT_CUSTOM 等价于 DEPT_ONLY</b>。
 *       旧实现在自定义部门为空时只剩兜底那一项，就是「只看自己部门」</li>
 * </ol>
 *
 * @author 走进新时代
 */
@Service
@Slf4j
public class DataScopeMigrateService {

    /**
     * 旧 data_scope 覆盖的两张表，判据与 {@code DataPermissionConfiguration} 逐字对应
     *
     * ⚠️ 改这里之前先去看那个类：两边不一致的话，会出现「某张表的旧规则被摘了、
     * 新规则却没建」的窗口，那段时间这张表完全不过滤
     */
    private static final List<String> LEGACY_TABLES = List.of("system_users", "system_dept");

    @Resource
    private RoleMapper roleMapper;
    @Resource
    private ResourceMapper resourceMapper;
    @Resource
    private GrantMapper grantMapper;
    @Resource
    private RuleMapper ruleMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 迁移结果，同时用于 dry-run 预演
     */
    @Data
    public static class MigrateResult {

        /** 预演模式（不落库） */
        private boolean dryRun;
        /** 新建的规则数 */
        private int ruleCreated;
        /** 新建的授权数 */
        private int grantCreated;
        /** 跳过的角色数（已迁过） */
        private int skipped;
        /** 无法翻译的角色，需要人工处理 */
        private final List<String> unsupported = new ArrayList<>();
        /** 逐角色的翻译说明，给人看的 */
        private final List<String> details = new ArrayList<>();

    }

    /**
     * 执行迁移
     *
     * @param dryRun true 时只计算不落库，用于上线前确认翻译结果
     */
    @Transactional(rollbackFor = Exception.class)
    public MigrateResult migrate(boolean dryRun) {
        MigrateResult result = new MigrateResult();
        result.setDryRun(dryRun);

        // 1. 找到两张表的数据集资源。找不到说明 @PermissionDataset 还没登记上，
        //    这时候迁移毫无意义 —— 建出来的授权指向不存在的资源，等于没配
        List<ResourceDO> datasets = new ArrayList<>();
        for (String table : LEGACY_TABLES) {
            List<ResourceDO> found = resourceMapper.selectListByDatasetTable(table);
            if (found.isEmpty()) {
                result.getUnsupported().add(String.format(
                        "表 %s 没有 DATASET 资源，请确认对应 DO 上已标 @PermissionDataset 且已重启", table));
                continue;
            }
            datasets.addAll(found);
        }
        if (datasets.isEmpty()) {
            log.error("[migrate][两张表的数据集资源一个都没找到，迁移中止]");
            return result;
        }

        // 2. 逐角色翻译
        for (RoleDO role : roleMapper.selectList()) {
            Integer scope = role.getDataScope();
            if (scope == null) {
                continue; // 没配过，天然不受限
            }
            for (ResourceDO dataset : datasets) {
                translateOne(role, dataset, scope, dryRun, result);
            }
        }
        log.info("[migrate][dryRun={} 新建规则 {} 条、授权 {} 条，跳过 {} 个，待人工处理 {} 项]",
                dryRun, result.getRuleCreated(), result.getGrantCreated(),
                result.getSkipped(), result.getUnsupported().size());
        return result;
    }

    /**
     * 把一个角色在一张数据集上的 data_scope 翻译成授权行
     */
    private void translateOne(RoleDO role, ResourceDO dataset, Integer scope,
                              boolean dryRun, MigrateResult result) {
        // 幂等：这个角色在这个资源上已经有 VIEW 授权就不再建
        if (existsGrant(role.getId(), dataset.getId())) {
            result.setSkipped(result.getSkipped() + 1);
            return;
        }

        Long ruleId = null;
        String desc;
        if (Objects.equals(scope, DataScopeEnum.ALL.getScope())) {
            // ALL → 无规则的 ALLOW。必须留痕，理由见类注释第 1 条
            desc = "全部数据 → 无行限制的 ALLOW";
        } else if (Objects.equals(scope, DataScopeEnum.DEPT_CUSTOM.getScope())) {
            if (CollUtil.isEmpty(role.getDataScopeDeptIds())) {
                // 空列表的 DEPT_CUSTOM，旧实现只剩「自己的部门」这一项兜底
                ruleId = ensureBuiltInRule(RuleTemplateEnum.DEPT_ONLY, dryRun, result);
                desc = "指定部门(列表为空) → 等价于本部门数据";
            } else {
                ruleId = ensureDeptInRule(role, dryRun, result);
                desc = "指定部门" + role.getDataScopeDeptIds() + " → DEPT_IN 规则（含本部门兜底）";
            }
        } else if (Objects.equals(scope, DataScopeEnum.DEPT_ONLY.getScope())) {
            ruleId = ensureBuiltInRule(RuleTemplateEnum.DEPT_ONLY, dryRun, result);
            desc = "本部门数据";
        } else if (Objects.equals(scope, DataScopeEnum.DEPT_AND_CHILD.getScope())) {
            ruleId = ensureBuiltInRule(RuleTemplateEnum.DEPT_AND_CHILDREN, dryRun, result);
            desc = "本部门及以下";
        } else if (Objects.equals(scope, DataScopeEnum.SELF.getScope())) {
            // ⚠️ 部门表没有 owner 槽位（一条部门记录没有「归属人」这个概念）。
            // 规则求不出条件时 DataRowFilterResolver 会按「不授予任何行」处理，
            // 结果是这个角色的人一个部门都看不到 —— 与其静默这样，不如报出来让人决定
            if (dataset.getPermission() != null && dataset.getPermission().contains(":dept")) {
                result.getUnsupported().add(String.format(
                        "角色[%s](%d) 的 SELF 数据范围在部门表上无法表达（部门没有归属人语义），请人工确认",
                        role.getName(), role.getId()));
                return;
            }
            ruleId = ensureBuiltInRule(RuleTemplateEnum.OWNER_ONLY, dryRun, result);
            desc = "仅本人数据";
        } else {
            result.getUnsupported().add(String.format(
                    "角色[%s](%d) 的 data_scope=%d 无法识别", role.getName(), role.getId(), scope));
            return;
        }

        if (!dryRun) {
            GrantDO grant = new GrantDO();
            grant.setSubjectType(SubjectTypeEnum.ROLE.name());
            grant.setSubjectRefId(role.getId());
            grant.setResourceId(dataset.getId());
            grant.setAction(GrantActionEnum.VIEW.getCode());
            grant.setEffect("ALLOW");
            grant.setRuleId(ruleId);
            grant.setTenantId(role.getTenantId());
            grantMapper.insert(grant);
        }
        result.setGrantCreated(result.getGrantCreated() + 1);
        result.getDetails().add(String.format("角色[%s](%d) 在 %s 上：%s",
                role.getName(), role.getId(), dataset.getDatasetTable(), desc));
    }

    private boolean existsGrant(Long roleId, Long resourceId) {
        return grantMapper.selectCount(new LambdaQueryWrapper<GrantDO>()
                .eq(GrantDO::getSubjectType, SubjectTypeEnum.ROLE.name())
                .eq(GrantDO::getSubjectRefId, roleId)
                .eq(GrantDO::getResourceId, resourceId)
                .eq(GrantDO::getAction, GrantActionEnum.VIEW.getCode())) > 0;
    }

    /**
     * 取（或建）一条无参数的内置规则。同模板全局复用一条，不按角色重复建
     */
    private Long ensureBuiltInRule(RuleTemplateEnum template, boolean dryRun, MigrateResult result) {
        List<RuleDO> existing = ruleMapper.selectList(new LambdaQueryWrapper<RuleDO>()
                .eq(RuleDO::getTemplate, template.getCode())
                .isNull(RuleDO::getParams));
        if (CollUtil.isNotEmpty(existing)) {
            return existing.get(0).getId();
        }
        if (dryRun) {
            return null;
        }
        RuleDO rule = new RuleDO();
        rule.setName(template.getDisplayName());
        rule.setTemplate(template.getCode());
        rule.setDescription(template.getExpression());
        rule.setStatus(0);
        ruleMapper.insert(rule);
        result.setRuleCreated(result.getRuleCreated() + 1);
        return rule.getId();
    }

    /**
     * 取（或建）一条 DEPT_IN 规则
     *
     * 按 deptIds 的内容去重复用：两个角色配了同一组部门时共用一条规则，
     * 避免 sys_rule 里堆出一堆内容相同、只有名字不同的行
     */
    private Long ensureDeptInRule(RoleDO role, boolean dryRun, MigrateResult result) {
        List<Long> deptIds = new ArrayList<>(role.getDataScopeDeptIds());
        deptIds.sort(Long::compareTo); // 排序后再序列化，保证同一组部门产出同一个 params
        String params;
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("deptIds", deptIds);
            params = objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            result.getUnsupported().add(String.format(
                    "角色[%s](%d) 的部门列表序列化失败：%s", role.getName(), role.getId(), e.getMessage()));
            return null;
        }
        List<RuleDO> existing = ruleMapper.selectList(new LambdaQueryWrapper<RuleDO>()
                .eq(RuleDO::getTemplate, RuleTemplateEnum.DEPT_IN.getCode())
                .eq(RuleDO::getParams, params));
        if (CollUtil.isNotEmpty(existing)) {
            return existing.get(0).getId();
        }
        if (dryRun) {
            return null;
        }
        RuleDO rule = new RuleDO();
        rule.setName("指定部门 " + deptIds);
        rule.setTemplate(RuleTemplateEnum.DEPT_IN.getCode());
        rule.setParams(params);
        rule.setDescription(RuleTemplateEnum.DEPT_IN.getExpression());
        rule.setStatus(0);
        ruleMapper.insert(rule);
        result.setRuleCreated(result.getRuleCreated() + 1);
        return rule.getId();
    }

}
