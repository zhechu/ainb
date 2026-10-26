package cn.enlev.ainb.module.system.framework.datapermission.config;

import org.springframework.context.annotation.Configuration;

/**
 * system 模块的数据权限 Configuration
 *
 * <h3>🔴 旧的 data_scope 行过滤已于 2026-09-18 收敛掉，这里刻意留空</h3>
 *
 * 原来这里注册了一个 {@code DeptDataPermissionRuleCustomizer}：
 *
 * <pre>
 * rule.addDeptColumn(AdminUserDO.class);       // system_users
 * rule.addDeptColumn(DeptDO.class, "id");      // system_dept
 * rule.addUserColumn(AdminUserDO.class, "id");
 * </pre>
 *
 * 它消费的是 {@code system_role.data_scope}，与 {@code grant-enabled} 开关<b>无关</b>，
 * 一直在生效。而这两张表同时又被新的 {@code RuleDataPermissionRule} 覆盖
 * （{@code AdminUserDO} / {@code DeptDO} 上的 {@code @PermissionDataset}）——
 * 两个都是 MyBatis-Plus 的 {@code DataPermissionRule}，会<b>同时</b>作用于同一条 SQL
 * 并取交集。一条记录被过滤掉时，没有任何办法分辨是哪套干的。
 *
 * 收敛做法见 {@code DataScopeMigrateService}：把每个角色的 data_scope 翻译成
 * {@code sys_grant} + {@code sys_rule}，翻译完摘掉这个 Bean，只留一套。
 *
 * <h3>⚠️ 不要把这段加回来</h3>
 *
 * 加回来就是恢复双轨。要调整用户/部门表的可见范围，去「角色 → 资源授权」里
 * 给对应资源（{@code system:user} / {@code system:dept}）绑行过滤规则。
 *
 * {@code system_role.data_scope} 字段本身还在（角色列表接口仍返回它），
 * 但<b>已经没有任何消费方</b>，留着只是为了让迁移可以重跑、可以对账。
 *
 * @author 芋道源码
 */
@Configuration(proxyBeanMethods = false)
public class DataPermissionConfiguration {

}
