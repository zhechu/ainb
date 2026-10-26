package cn.enlev.ainb.module.system.framework.permission.runner;

import cn.enlev.ainb.module.system.framework.permission.core.scan.PermissionAnnotationScanner;
import cn.enlev.ainb.module.system.framework.permission.core.scan.PermissionDriftDetector;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 权限点扫描 + 漂移检测 Runner（S2c）
 *
 * 在 ResourceMigrateRunner(@Order(1)) 和 GrantMigrateRunner(@Order(2)) 之后执行，
 * 保证 sys_resource 里已有迁移数据，漂移对比才有意义。
 *
 * 做两件事：
 * 1. 扫描代码里所有 @PreAuthorize("@ss.hasPermission('...')") 的权限串
 * 2. 按命名约定推导出资源树，把缺失的中间目录节点写进 sys_resource；
 *    同时告警「代码里有但库里没有」的叶子权限点（漂移，意味着某个功能永远鉴权失败）
 *
 * @author 走进新时代
 */
@Component
@Order(3)
@Slf4j
public class PermissionScanRunner implements ApplicationRunner {

    @Resource
    private PermissionAnnotationScanner scanner;
    @Resource
    private PermissionDriftDetector detector;

    @Override
    public void run(ApplicationArguments args) {
        log.info("[PermissionScanRunner][开始权限点扫描与漂移检测]");
        Set<String> permissions = scanner.scan();
        log.info("[PermissionScanRunner][扫描到 {} 个权限串]", permissions.size());
        PermissionDriftDetector.DriftReport report = detector.detect(permissions);
        if (report.hasDrift()) {
            log.warn("[PermissionScanRunner][⚠️ 发现 {} 个漂移权限点，这些权限的鉴权永远返回 false！请尽快修复]",
                    report.missing().size());
        } else {
            // ⚠️ 报 unexplainedStale 而不是 stale：后者含运行时生成的数据集资源，
            //    塌缩落库后永久停在 137，报它等于每次启动都报同一个常量 ——
            //    而一个永远不变的告警数字，很快就没有人会再读
            log.info("[PermissionScanRunner][无漂移，约定式推导新建 {} 个节点，待查陈旧 {} 个"
                            + "（另有 {} 个是运行时生成的数据集资源，属预期）]",
                    report.created(), report.unexplainedStale().size(),
                    report.datasetDrivenStale().size());
        }
    }

}
