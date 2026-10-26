package cn.enlev.ainb.module.system.service.permission;

import cn.hutool.core.collection.CollUtil;
import cn.enlev.ainb.module.system.dal.dataobject.permission.GrantDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.RoleMenuDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.GrantMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.RoleMenuMapper;
import cn.enlev.ainb.module.system.enums.permission.GrantActionEnum;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * system_role_menu → sys_grant 迁移 Service
 *
 * 幂等：按 (subject_type, subject_ref_id, resource_id, action, context_resource_id) 判重，
 * 已存在的跳过，不存在的插入。可重复执行。
 *
 * 调用时机：
 *   ① {@link GrantMigrateRunner} 在应用启动后自动跑一次 —— <b>仅限没迁过的环境</b>，
 *      判据见 {@link #isMigrated()}。每次启动都跑会让旧表的「取消勾选」永远收不回权限
 *   ② 可通过管理接口手动触发（S8a 提供 UI 入口），补偷跑了一段时间的增量数据
 *
 * @author 走进新时代
 */
@Service
@Slf4j
public class GrantMigrateService {

    @Resource
    private RoleMenuMapper roleMenuMapper;
    @Resource
    private ResourceMapper resourceMapper;
    @Resource
    private GrantMapper grantMapper;
    @Resource
    private ResourceService resourceService;

    /**
     * 这个环境迁移过没有
     *
     * 判据是「{@code sys_grant} 里有没有 ROLE 主体的 VIEW 行」—— 迁移产出的正是这类行。
     * 用它代替标记文件 / 标记表：判据来自数据本身，不会因为标记丢失而重复迁，
     * 也不需要在部署流程里额外维护一个状态。
     *
     * ⚠️ 调用方需自行处理租户上下文（启动期没有租户，要用 {@code TenantUtils.executeIgnore} 包住）
     */
    public boolean isMigrated() {
        return grantMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<GrantDO>()
                .eq(GrantDO::getSubjectType, SubjectTypeEnum.ROLE.name())
                .eq(GrantDO::getAction, GrantActionEnum.VIEW.getCode())) > 0;
    }

    /**
     * 执行迁移
     *
     * @return 新建行数
     */
    @Transactional(rollbackFor = Exception.class)
    public int migrate() {
        // 1. 先确保 sys_resource 有数据（ResourceMigrateRunner 在这之前已跑过，这里再调一次保证幂等）
        resourceService.migrateFromMenu();

        // 2. 读出所有 system_role_menu 行
        List<RoleMenuDO> roleMenus = roleMenuMapper.selectList();
        if (CollUtil.isEmpty(roleMenus)) {
            log.info("[migrate][system_role_menu 为空，跳过]");
            return 0;
        }

        // 3. 构建 menu_id → resource_id 的映射
        //    sys_resource.menu_id 是迁移期的临时字段，正好用来关联
        List<ResourceDO> resources = resourceMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ResourceDO>()
                        .isNotNull(ResourceDO::getMenuId));
        Map<Long, Long> menuIdToResourceId = resources.stream()
                .collect(Collectors.toMap(ResourceDO::getMenuId, ResourceDO::getId, (a, b) -> a));

        // 4. 读出现有的 sys_grant（用于去重）
        //    量级在 2000 条以内，全部拉进内存比逐条查库快得多
        List<GrantDO> existingGrants = grantMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<GrantDO>()
                        .eq(GrantDO::getSubjectType, SubjectTypeEnum.ROLE.name())
                        .eq(GrantDO::getAction, GrantActionEnum.VIEW.getCode()));
        // 去重 key：roleId:resourceId
        java.util.Set<String> existingKeys = new java.util.HashSet<>();
        for (GrantDO g : existingGrants) {
            existingKeys.add(g.getSubjectRefId() + ":" + g.getResourceId());
        }

        // 5. 建需要插入的行
        List<GrantDO> toInsert = new ArrayList<>();
        int skipped = 0, unmapped = 0;
        for (RoleMenuDO roleMenu : roleMenus) {
            Long resourceId = menuIdToResourceId.get(roleMenu.getMenuId());
            if (resourceId == null) {
                // 这个 menu_id 在 sys_resource 里没有对应行（目录层、空 permission 等被 migrateFromMenu 跳过的）
                unmapped++;
                continue;
            }
            String key = roleMenu.getRoleId() + ":" + resourceId;
            if (existingKeys.contains(key)) {
                skipped++;
                continue;
            }
            GrantDO grant = new GrantDO();
            grant.setSubjectType(SubjectTypeEnum.ROLE.name());
            grant.setSubjectRefId(roleMenu.getRoleId());
            grant.setResourceId(resourceId);
            grant.setAction(GrantActionEnum.VIEW.getCode());
            grant.setEffect("ALLOW");
            grant.setTenantId(roleMenu.getTenantId());
            toInsert.add(grant);
            existingKeys.add(key); // 防止同一批次里重复
        }

        if (!toInsert.isEmpty()) {
            // 批量插入，每批 500 条
            List<List<GrantDO>> batches = partition(toInsert, 500);
            for (List<GrantDO> batch : batches) {
                grantMapper.insertBatch(batch);
            }
        }
        log.info("[migrate][完成。新建 {} 条，跳过(已存在) {} 条，跳过(资源未映射) {} 条]",
                toInsert.size(), skipped, unmapped);
        return toInsert.size();
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            result.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return result;
    }

}
