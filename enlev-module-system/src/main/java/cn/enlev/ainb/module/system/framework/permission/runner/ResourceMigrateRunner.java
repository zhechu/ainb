package cn.enlev.ainb.module.system.framework.permission.runner;

import cn.enlev.ainb.module.system.service.permission.ResourceService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 权限资源迁移 Runner
 *
 * 应用启动后执行一次，把 system_menu 里的按钮层迁移到 sys_resource。
 * 必须在 GrantMigrateRunner(@Order(2)) 之前执行，因为后者依赖 sys_resource 有数据。
 *
 * <h3>🔴 幂等性在塌缩开启后换了判据</h3>
 *
 * 原本「已迁移的行通过 menu_id 判断，直接跳过，所以可以重复启动」——
 * 这句话在 {@code enlev.permission.collapse-enabled=true} 之后<b>不再成立</b>：
 * 塌缩的正题是一个身份行承载同一页的多个按钮，R1 只给每行留一个代表 menu_id，
 * 于是「menu_id 查不到」不再等于「没迁过」。实测非目录菜单里 341 / 666 个
 * 已经没有承载自己 menu_id 的存活资源行（塌缩前是 0），
 * 一次重启就会 insert 341 行成员行，退回四分之三个塌缩。
 *
 * 所以现在 {@code migrateFromMenu} 自己判：<b>塌缩开启且 sys_resource 非空 ⟹ 整体跳过</b>，
 * 增量走 {@code syncFromMenu}（菜单增改删都会调它）。
 * 判据放在 Service 而不是本 Runner，是因为 {@code GrantMigrateService#migrate}
 * 也会调 {@code migrateFromMenu}，只在 Runner 上拦会漏掉那条路。
 *
 * <p>空库仍然要跑：全新安装靠它引导。引导出来的是<b>塌缩后的形状</b>
 * （一族一行、permission 是身份串），与计划器 {@code newIdentityTemplate} 同口径。
 *
 * <h3>为什么用 ApplicationRunner 而不是 @EventListener(ContextRefreshedEvent)</h3>
 *
 * ContextRefreshedEvent 在集成测试里每个测试类都会触发，会把测试产生的 system_menu 数据也迁移进来，
 * 干扰 sys_resource 的测试。ApplicationRunner 在单测里不触发（测试类不跑整个应用），更安全。
 *
 * @author 走进新时代
 */
@Component
@Order(1)
@Slf4j
public class ResourceMigrateRunner implements ApplicationRunner {

    @Resource
    private ResourceService resourceService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("[ResourceMigrateRunner][开始迁移 system_menu → sys_resource]");
        resourceService.migrateFromMenu();
        log.info("[ResourceMigrateRunner][迁移完成]");
    }

}
