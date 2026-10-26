package cn.enlev.ainb.module.system.framework.permission.runner;

import cn.enlev.ainb.framework.tenant.core.util.TenantUtils;
import cn.enlev.ainb.module.system.framework.permission.config.PermissionProperties;
import cn.enlev.ainb.module.system.service.permission.GrantMigrateService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * system_role_menu → sys_grant 迁移 Runner
 *
 * 在 {@link ResourceMigrateRunner}（@Order(1)）之后执行（@Order(2)），
 * 保证 sys_resource 有数据后再建 sys_grant 关联。
 *
 * <h3>🔴 为什么不能每次启动都跑</h3>
 *
 * {@link GrantMigrateService#migrate()} 是 <b>insert-only</b> 的：只补不删。
 * 在旧表还有写入方的时候每次启动都跑，会构成一条<b>单向的半通路</b>，
 * 表现是一组不报错但结果相反的缺陷：
 *
 * <ul>
 *   <li>在「菜单权限」里<b>勾上</b>一个菜单 → 当场不生效 →
 *       <b>下次重启后</b>悄悄变成 sys_grant 的 ALLOW → 生效了。时机完全不可预期</li>
 *   <li>在「菜单权限」里<b>取消勾选</b> → 旧表的行删了 →
 *       sys_grant 里那条 ALLOW <b>永远留着</b> → 权限再也收不回。
 *       界面上显示「已取消」，人也确认过了，实际没收回</li>
 * </ul>
 *
 * 所以改成<b>只在没迁过的环境跑一次</b>。判据不是写个标记文件或加张表，
 * 而是直接问库：{@code sys_grant} 里有没有 ROLE 主体的 VIEW 行。
 * 这个判据自适应，不需要人去配，也不会因为标记丢失而重复迁：
 *
 * <ul>
 *   <li>全新安装 / 从未迁移 → 没有这类行 → 跑一次</li>
 *   <li>已迁移过的环境（本库 984 条）→ 跳过</li>
 *   <li>需要补跑增量 → 把 {@code enlev.permission.grant-migrate-on-startup} 设为 true，
 *       或走 S8a 的手动触发入口</li>
 * </ul>
 *
 * @author 走进新时代
 */
@Component
@Order(2)
@Slf4j
public class GrantMigrateRunner implements ApplicationRunner {

    @Resource
    private GrantMigrateService grantMigrateService;

    @Resource
    private PermissionProperties permissionProperties;

    @Override
    public void run(ApplicationArguments args) {
        // 必须忽略租户：sys_grant 与 system_role_menu 都是分租户的表，
        // 而启动期没有租户上下文 —— 不忽略的话 TenantContextHolder 会直接抛
        // 「不存在租户编号」，ApplicationRunner 抛异常会让整个应用启动失败。
        //
        // 忽略之后读到的是全部租户的数据，正是这里要的：
        // 迁移是一次性跨租户的全量搬运，每行的 tenant_id 由 migrate() 从源行原样抄过去
        TenantUtils.executeIgnore(() -> {
            boolean forced = Boolean.TRUE.equals(permissionProperties.getGrantMigrateOnStartup());
            if (!forced && grantMigrateService.isMigrated()) {
                log.info("[GrantMigrateRunner][sys_grant 已有迁移数据，跳过。" +
                        "需要补跑请设 enlev.permission.grant-migrate-on-startup=true]");
                return null;
            }
            log.info("[GrantMigrateRunner][开始迁移 system_role_menu → sys_grant，forced={}]", forced);
            int created = grantMigrateService.migrate();
            log.info("[GrantMigrateRunner][迁移完成，新建 {} 条]", created);
            return null;
        });
    }

}
