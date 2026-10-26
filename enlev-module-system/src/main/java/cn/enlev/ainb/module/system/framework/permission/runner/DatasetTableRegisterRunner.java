package cn.enlev.ainb.module.system.framework.permission.runner;

import cn.enlev.ainb.module.system.service.permission.DatasetResourceService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 数据集表名注册 Runner
 *
 * <h3>不做这一步会怎样</h3>
 *
 * {@code RuleDataPermissionRule.getTableNames()} 是框架决定「要不要改写这条 SQL」的依据。
 * 这个集合是内存态的，只在表单发布时才被填进去 —— 应用一重启它就空了。
 *
 * 后果：所有已经配好的行级权限规则<b>静默失效</b>。
 * 查询照常返回结果，只是不再有过滤，越权数据就这么出去了，
 * 没有报错、没有日志、界面完全正常。这是最危险的一类失效，
 * 因为没有任何信号提示你「权限现在是关着的」。
 *
 * 所以启动时必须把 sys_resource 里已登记的数据集表名全部灌回去。
 *
 * <h3>@Order 的取值</h3>
 *
 * 排在 {@code BuiltInRuleSeedRunner}(@Order(4)) 之后。它只读 sys_resource，
 * 不依赖种子数据，但排在后面能保证「资源迁移(1) → 授权迁移(2) → … → 种子(4)」
 * 这条链都走完了再重建内存态。
 *
 * @author 走进新时代
 */
@Component
@Order(5)
@Slf4j
public class DatasetTableRegisterRunner implements ApplicationRunner {

    @Resource
    private DatasetResourceService datasetResourceService;

    @Override
    public void run(ApplicationArguments args) {
        int count = datasetResourceService.reloadRegisteredTables();
        log.info("[DatasetTableRegisterRunner][已把 {} 张数据集表注册进行级权限规则]", count);
    }

}
