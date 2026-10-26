package cn.enlev.ainb.module.system.controller.admin.permission;

import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.tenant.core.util.TenantUtils;
import cn.enlev.ainb.module.system.service.permission.DataScopeMigrateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.GRANT_MIGRATE_CONFIRM_REQUIRED;

/**
 * 管理后台 - 数据范围迁移（旧 data_scope → 行过滤规则）
 *
 * 一次性收敛工具，不是日常功能。只挂给超管用的粗权限点 ——
 * 跑这个的是运维，挂太细会把自己堵在外面
 *
 * @author 走进新时代
 */
@Tag(name = "管理后台 - 数据范围迁移")
@RestController
@RequestMapping("/system/data-scope-migrate")
@Validated
public class DataScopeMigrateController {

    @Resource
    private DataScopeMigrateService dataScopeMigrateService;

    @PostMapping("/preview")
    @Operation(summary = "预演迁移（不落库）",
            description = "先跑这个看翻译结果对不对，再跑 execute。两者的计算路径完全一致")
    @PreAuthorize("@ss.hasPermission('system:grant:create')")
    public CommonResult<DataScopeMigrateService.MigrateResult> preview() {
        // 迁移要跨租户读写，启动期/接口调用都没有稳定的租户上下文，
        // 与 GrantMigrateRunner 同一口径：忽略租户，每行的 tenant_id 从源角色抄过来
        return success(TenantUtils.executeIgnore(() -> dataScopeMigrateService.migrate(true)));
    }

    @PostMapping("/execute")
    @Operation(summary = "执行迁移（幂等，可重复跑）")
    @Parameter(name = "confirm", description = "必须显式传 true，防误触")
    @PreAuthorize("@ss.hasPermission('system:grant:create')")
    public CommonResult<DataScopeMigrateService.MigrateResult> execute(
            @RequestParam("confirm") Boolean confirm) {
        if (!Boolean.TRUE.equals(confirm)) {
            throw exception(GRANT_MIGRATE_CONFIRM_REQUIRED);
        }
        return success(TenantUtils.executeIgnore(() -> dataScopeMigrateService.migrate(false)));
    }

}
