package cn.enlev.ainb.module.system.api.permission;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.system.api.permission.dto.ResourceDiagnosticRespDTO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.GrantDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.RoleDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.GrantMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.RoleMapper;
import cn.enlev.ainb.module.system.enums.permission.FieldModeEnum;
import cn.enlev.ainb.module.system.framework.permission.core.dataset.NativeDatasetScanner;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 权限体系诊断 API 实现类
 *
 * @author 走进新时代
 */
@Service
@Slf4j
public class SystemDiagnosticApiImpl implements SystemDiagnosticApi {

    /**
     * 主体类型：角色
     *
     * 只有角色主体才谈得上「授权的租户应当等于角色的租户」——
     * 用户、部门等主体的租户来源不同，不能一并判
     */
    private static final String SUBJECT_TYPE_ROLE = "ROLE";

    @Resource
    private ResourceMapper resourceMapper;
    @Resource
    private GrantMapper grantMapper;
    @Resource
    private RoleMapper roleMapper;
    @Resource
    private NativeDatasetScanner nativeDatasetScanner;

    @Override
    public ResourceDiagnosticRespDTO diagnoseResource(String permission) {
        ResourceDiagnosticRespDTO result = new ResourceDiagnosticRespDTO();
        result.setPermission(permission);
        if (StrUtil.isBlank(permission)) {
            result.setRegistered(false);
            result.setResourceCount(0);
            return result;
        }

        List<ResourceDO> resources = resourceMapper.selectListByPermission(permission);
        result.setRegistered(CollUtil.isNotEmpty(resources));
        result.setResourceCount(resources.size());
        if (CollUtil.isEmpty(resources)) {
            result.setGrantCount(0);
            result.setTenantMismatchGrantCount(0);
            result.setIllegalFieldModes(List.of());
            return result;
        }
        // 数据集表名取第一条非空的 —— 同一权限串的多行资源指向不同表是配置错误，
        // 但那属于另一个问题，这里只如实报出能查到的那一个
        result.setDatasetTable(resources.stream()
                .map(ResourceDO::getDatasetTable)
                .filter(StrUtil::isNotBlank)
                .findFirst().orElse(null));

        List<GrantDO> grants = grantMapper.selectListByResourceIds(
                resources.stream().map(ResourceDO::getId).toList());
        result.setGrantCount(grants.size());
        result.setTenantMismatchGrantCount(countTenantMismatch(grants));
        result.setIllegalFieldModes(collectIllegalFieldModes(grants));
        return result;
    }

    /**
     * 数出租户写错的授权行
     *
     * 判据：角色主体的授权，其 {@code tenant_id} 应当等于该角色的 {@code tenant_id}。
     * 对不上的那些在判定时匹配不到，表现为「配了等于没配」。
     */
    private int countTenantMismatch(List<GrantDO> grants) {
        List<Long> roleIds = grants.stream()
                .filter(item -> Objects.equals(item.getSubjectType(), SUBJECT_TYPE_ROLE))
                .map(GrantDO::getSubjectRefId)
                .filter(Objects::nonNull)
                .distinct().toList();
        if (CollUtil.isEmpty(roleIds)) {
            return 0;
        }
        Map<Long, Long> roleTenantMap = roleMapper.selectByIds(roleIds).stream()
                .collect(Collectors.toMap(RoleDO::getId, RoleDO::getTenantId, (a, b) -> a));
        int mismatch = 0;
        for (GrantDO grant : grants) {
            if (!Objects.equals(grant.getSubjectType(), SUBJECT_TYPE_ROLE)
                    || grant.getSubjectRefId() == null) {
                continue;
            }
            Long roleTenantId = roleTenantMap.get(grant.getSubjectRefId());
            // 查不到角色就不算错配 —— 那是「角色被删了」，是另一个问题
            if (roleTenantId != null && !Objects.equals(roleTenantId, grant.getTenantId())) {
                mismatch++;
            }
        }
        return mismatch;
    }

    /**
     * 收集非法的 fieldMode 取值
     */
    private List<String> collectIllegalFieldModes(List<GrantDO> grants) {
        Set<String> legal = legalFieldModes();
        return grants.stream()
                .map(GrantDO::getFieldMode)
                .filter(StrUtil::isNotBlank)
                .filter(mode -> !legal.contains(mode))
                .distinct()
                .toList();
    }

    @Override
    public Set<String> getNativeDatasetTables() {
        return nativeDatasetScanner.scan().stream()
                .map(NativeDatasetScanner.NativeDatasetDescriptor::table)
                .filter(StrUtil::isNotBlank)
                .map(table -> table.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public List<String> getLegalFieldModes() {
        return List.copyOf(legalFieldModes());
    }

    private Set<String> legalFieldModes() {
        return Arrays.stream(FieldModeEnum.values())
                .map(FieldModeEnum::getCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

}
