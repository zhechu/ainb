package cn.enlev.ainb.module.system.framework.permission.runner;

import cn.enlev.ainb.module.system.framework.permission.core.dataset.NativeDatasetScanner;
import cn.enlev.ainb.module.system.service.permission.DatasetResourceService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 原生模块数据集登记 Runner（S7a）
 *
 * <h3>它补的是行级权限的另一半</h3>
 *
 * 在此之前，进得了 {@code RuleDataPermissionRule} 表名集合的只有在线表单。
 * {@code system_users}、{@code infra_file} 这些原生模块的表一张都没有 ——
 * 在授权页上给它们配的行级规则<b>配了不会被调用</b>，且不报错。
 *
 * 本 Runner 扫描 {@code @PermissionDataset}，把声明过的原生表登记成
 * 与在线表单同构的数据集（DATASET 资源 + 槽位映射 + 表名注册）。
 *
 * <h3>⚠️ @Order 必须排在 DatasetTableRegisterRunner 之后</h3>
 *
 * 那个 Runner 读的是 {@code sys_resource} 里<b>已有的</b> DATASET 行。
 * 如果本 Runner 排在它前面，首次启动时新建的原生数据集会被它重复注册一次
 * （无害），但更要紧的是：本 Runner 自己就会调 {@code registerTable}，
 * 顺序反过来并不能少做什么。排在后面是为了让「迁移 → 种子 → 重建内存态」
 * 这条既有的链先完整跑完，本 Runner 只做增量。
 *
 * <h3>失败不阻断启动</h3>
 *
 * 单张表登记失败只影响那张表，不该把整个应用拖住。但必须 ERROR 留痕 ——
 * 静默跳过的表现是「这张表的行级权限从来不生效」，而那是查不出来的。
 *
 * @author 走进新时代
 */
@Component
@Order(6)
@Slf4j
public class NativeDatasetRegisterRunner implements ApplicationRunner {

    @Resource
    private NativeDatasetScanner scanner;
    @Resource
    private DatasetResourceService datasetResourceService;

    @Override
    public void run(ApplicationArguments args) {
        List<NativeDatasetScanner.NativeDatasetDescriptor> descriptors = scanner.scan();
        if (descriptors.isEmpty()) {
            log.info("[NativeDatasetRegisterRunner][没有扫描到 @PermissionDataset 声明，跳过]");
            return;
        }
        int success = 0;
        for (NativeDatasetScanner.NativeDatasetDescriptor descriptor : descriptors) {
            try {
                datasetResourceService.registerNative(descriptor);
                success++;
            } catch (Exception ex) {
                log.error("[run][原生数据集({} → 表 {})登记失败，这张表的行级/字段级权限本次不生效]",
                        descriptor.permission(), descriptor.table(), ex);
            }
        }
        log.info("[NativeDatasetRegisterRunner][扫描到 {} 个原生数据集声明，成功登记 {} 个]",
                descriptors.size(), success);
    }

}
