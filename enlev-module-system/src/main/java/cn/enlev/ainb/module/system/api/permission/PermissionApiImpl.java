package cn.enlev.ainb.module.system.api.permission;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.biz.system.permission.dto.DataRowFilterRespDTO;
import cn.enlev.ainb.framework.common.biz.system.permission.dto.DatasetFieldModeRespDTO;
import cn.enlev.ainb.framework.common.biz.system.permission.dto.DeptDataPermissionRespDTO;
import cn.enlev.ainb.module.system.enums.permission.FieldModeEnum;
import cn.enlev.ainb.module.system.enums.permission.ResourceTypeEnum;
import cn.enlev.ainb.module.system.framework.permission.core.dataset.DatasetResourceNaming;
import cn.enlev.ainb.module.system.service.permission.DataRowFilterResolver;
import cn.enlev.ainb.module.system.service.permission.GrantDecisionService;
import cn.enlev.ainb.module.system.service.permission.PermissionService;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Collection;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 权限 API 实现类
 *
 * @author 芋道源码
 */
@Service
public class PermissionApiImpl implements PermissionApi {

    @Resource
    private PermissionService permissionService;
    @Resource
    private DataRowFilterResolver dataRowFilterResolver;
    @Resource
    private GrantDecisionService grantDecisionService;
    @Resource
    private cn.enlev.ainb.module.system.service.permission.ResourceService resourceService;

    @Override
    public Set<Long> getUserRoleIdListByRoleIds(Collection<Long> roleIds) {
        return permissionService.getUserRoleIdListByRoleId(roleIds);
    }

    @Override
    public boolean hasAnyPermissions(Long userId, String... permissions) {
        return permissionService.hasAnyPermissions(userId, permissions);
    }

    @Override
    public boolean hasAnyRoles(Long userId, String... roles) {
        return permissionService.hasAnyRoles(userId, roles);
    }

    @Override
    public boolean hasResourceAction(Long userId, String permission, String action) {
        if (userId == null || permission == null || action == null) {
            return false;
        }
        return grantDecisionService.hasPermission(userId, permission, null, action);
    }

    /**
     * 解析当前线程上的上下文资源编号
     *
     * @return 没有上下文、或该上下文尚未登记成资源时返回 null（退回全局判定）
     */
    private Long resolveContextResourceId() {
        String contextPermission = cn.enlev.ainb.framework.common.biz.system.permission
                .PermissionContextHolder.get();
        if (cn.hutool.core.util.StrUtil.isBlank(contextPermission)) {
            return null;
        }
        List<cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO> resources =
                resourceService.getResourceListByPermission(contextPermission);
        return cn.hutool.core.collection.CollUtil.isEmpty(resources) ? null : resources.get(0).getId();
    }

    @Override
    public boolean hasExplicitDeny(String subjectType, String subjectCode,
                                   String permission, String action) {
        if (subjectType == null || permission == null || action == null) {
            return false;
        }
        return grantDecisionService.hasExplicitDeny(subjectType, subjectCode, permission, action);
    }

    @Override
    public DeptDataPermissionRespDTO getDeptDataPermission(Long userId) {
        return permissionService.getDeptDataPermission(userId);
    }

    @Override
    public DataRowFilterRespDTO getDataRowFilter(Long userId, String tableName, String tableAlias) {
        return dataRowFilterResolver.resolve(userId, tableName, tableAlias);
    }

    @Override
    public DataRowFilterRespDTO getDataRowFilter(Long userId, String tableName, String tableAlias,
                                                 Map<String, String> tableQualifiers) {
        return dataRowFilterResolver.resolve(userId, tableName, tableAlias, tableQualifiers);
    }

    @Override
    public DatasetFieldModeRespDTO getDatasetFieldModes(Long userId, String datasetCode,
                                                        Collection<String> fieldKeys, boolean column) {
        if (StrUtil.isBlank(datasetCode)) {
            return DatasetFieldModeRespDTO.unrestricted();
        }
        // 在线表单：调用方传的是表单 code，权限串由命名规则拼出来
        // （命名规则只在 system 模块内部，不能让 infra 自己拼 —— 两份迟早漂）
        return resolveFieldModes(userId, DatasetResourceNaming.dataset(datasetCode), fieldKeys, column);
    }

    @Override
    public DatasetFieldModeRespDTO getFieldModes(Long userId, String datasetPermission,
                                                 Collection<String> fieldKeys, boolean column) {
        // 原生模块：数据集权限串就是模块自己的前缀，没有命名规则可言，原样使用
        return resolveFieldModes(userId, datasetPermission, fieldKeys, column);
    }

    private DatasetFieldModeRespDTO resolveFieldModes(Long userId, String datasetPermission,
                                                      Collection<String> fieldKeys, boolean column) {
        DatasetFieldModeRespDTO result = new DatasetFieldModeRespDTO();
        if (StrUtil.isBlank(datasetPermission) || CollUtil.isEmpty(fieldKeys)) {
            return DatasetFieldModeRespDTO.unrestricted();
        }
        ResourceTypeEnum type = column ? ResourceTypeEnum.COLUMN : ResourceTypeEnum.FIELD;
        Map<String, String> permissionToKey = new LinkedHashMap<>();
        for (String fieldKey : fieldKeys) {
            if (StrUtil.isNotBlank(fieldKey)) {
                permissionToKey.putIfAbsent(
                        DatasetResourceNaming.childOf(datasetPermission, type, fieldKey), fieldKey);
            }
        }
        // 上下文（审批节点）。S6d 接上 —— 在此之前这里恒传 null，
        // 意味着字段模式三层合并里的「上下文」那一层【从未被触发过】：
        // 在流程设计器里给某个节点配的字段权限，配了等于没配，且不报错。
        //
        // ⚠️ 解析不出资源时传 null（退回全局判定），不能因此拒绝 ——
        // 节点资源是按需登记的，没登记过的节点应当表现为「没有节点级配置」，
        // 而不是「什么都看不到」
        Long contextId = resolveContextResourceId();
        Map<String, FieldModeEnum> modes = grantDecisionService.resolveFieldModes(
                userId, permissionToKey.keySet(), contextId);

        Map<String, String> byFieldKey = new LinkedHashMap<>();
        modes.forEach((permission, mode) -> {
            String fieldKey = permissionToKey.get(permission);
            if (fieldKey != null && mode != null) {
                byFieldKey.put(fieldKey, mode.getCode());
            }
        });
        result.setModes(byFieldKey);
        return result;
    }

}
