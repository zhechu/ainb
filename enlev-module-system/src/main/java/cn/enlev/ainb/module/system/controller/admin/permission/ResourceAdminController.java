package cn.enlev.ainb.module.system.controller.admin.permission;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.resource.ResourceRespVO;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.resource.ResourceSaveReqVO;
import cn.enlev.ainb.module.system.dal.mysql.permission.GrantMapper;
import cn.enlev.ainb.module.system.enums.permission.ResourceTypeEnum;
import cn.enlev.ainb.module.system.service.permission.ResourceService;
import jakarta.validation.Valid;
import java.util.Objects;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper;
import cn.enlev.ainb.module.system.enums.permission.RecordSharePolicyEnum;
import cn.enlev.ainb.module.system.enums.permission.ResourceTypeEnum;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.*;

/**
 * 管理后台 - 权限资源（S8a）
 *
 * 只提供查询：资源由发布钩子与迁移 Runner 自动登记，没有手动 CRUD
 * （改 permission 会直接改变鉴权结果，不该是一个随手能点的操作）。
 * 授权配置页用它渲染「可授权对象」树。
 *
 * @author 走进新时代
 */
@Tag(name = "管理后台 - 权限资源")
@RestController
@RequestMapping("/system/resource")
@Validated
@Slf4j
public class ResourceAdminController {

    @Resource
    private ResourceMapper resourceMapper;
    @Resource
    private ResourceService resourceService;
    @Resource
    private GrantMapper grantMapper;

    /**
     * 结果规模告警阈值
     *
     * ⚠️ 超过只打 ERROR，<b>不截断</b>。与派生可见性的 IN 列表阈值（TODO #29）同一条口径：
     * 截断 = 静默少数据（管理员在树里找不到某个资源，会以为它不存在），
     * 而「配置界面少给了几个可授权对象」这种事没人会来报。
     * 真正缺的是「在变成线上问题前能被看见」。
     */
    @Value("${enlev.permission.resource-list-warn-threshold:5000}")
    private int warnThreshold;

    @GetMapping("/list")
    @Operation(summary = "查询资源列表",
            description = "返回启用的资源，前端自行组装树。默认不含 COLUMN/FIELD —— 它们按表单数量线性增长，"
                    + "且属于「某张表单的字段权限」，在资源树里配没有意义，入口在资源详情页（S8b）")
    @PreAuthorize("@ss.hasPermission('system:resource:query')")
    public CommonResult<List<ResourceRespVO>> getResourceList(
            @Parameter(description = "资源类型，不传则查全部（受 includeFieldLevel 影响）")
            @RequestParam(required = false) String type,
            @Parameter(description = "是否包含 COLUMN/FIELD 级资源，默认 false")
            @RequestParam(required = false, defaultValue = "false") Boolean includeFieldLevel) {

        LambdaQueryWrapper<ResourceDO> query = new LambdaQueryWrapper<ResourceDO>()
                .eq(ResourceDO::getStatus, 0);
        if (type != null && !type.isBlank()) {
            // 显式指定类型时以调用方为准，不再叠加 includeFieldLevel 的排除
            query.eq(ResourceDO::getType, type);
        } else if (!Boolean.TRUE.equals(includeFieldLevel)) {
            // 字段/列级资源不进全局树：一张表单几十个字段，几十张表单就是几千个叶子，
            // 把真正要配的 ACTION 淹没掉
            query.notIn(ResourceDO::getType,
                    ResourceTypeEnum.COLUMN.getCode(), ResourceTypeEnum.FIELD.getCode());
        }
        query.orderByAsc(ResourceDO::getDepth).orderByAsc(ResourceDO::getSort);

        List<ResourceDO> list = resourceMapper.selectList(query);
        if (list.size() > warnThreshold) {
            log.error("[getResourceList][资源数 {} 超过阈值 {}，授权页的树会很难用。"
                            + "处理方向：① 确认压测数据是否已清理（script/perf/gen_perf_data.py --clean）；"
                            + "② 给本接口加按父节点懒加载]",
                    list.size(), warnThreshold);
        }
        return success(BeanUtils.toBean(dedupeByPermission(list), ResourceRespVO.class));
    }

    /**
     * 按权限串去重：同一个权限串只在树里出现一次
     *
     * <h3>为什么需要</h3>
     *
     * {@code system_menu.permission} 上<b>没有唯一索引</b>，同一个权限串挂在多个菜单下
     * 是上游的常见做法（比如「用户新增」既在系统管理下、又在别处复制了一份）。
     * 迁移时每个菜单行各建一条资源，于是授权页的资源树里会出现
     * <b>两个名字相同、路径也完全相同的节点</b> —— 选哪个都对，但没人分得清，
     * 这是实际用下来最让人困惑的一点。
     *
     * <h3>为什么去重是安全的</h3>
     *
     * 判定侧 {@code GrantDecisionService#hasPermission} 对「一个权限串多行资源」
     * 是<b>逐行建链、任意一行通过即通过</b>。所以授权落在保留的那一行上，
     * 判定照样认 —— 不会出现「配了却不生效」。
     *
     * <h3>保留哪一行</h3>
     *
     * <b>id 最小的那行</b>。与 {@code selectListByPermission(...).get(0)} 的口径一致
     * （MySQL 无 ORDER BY 时通常按主键返回），这样界面上选中的那一行
     * 和排查时看到的那一行是同一条，不会对不上。
     *
     * ⚠️ 只去重<b>显示</b>，不动数据。根子在菜单表的重复行，那要单独治理。
     */
    private List<ResourceDO> dedupeByPermission(List<ResourceDO> list) {
        Map<String, ResourceDO> kept = new LinkedHashMap<>();
        List<ResourceDO> result = new ArrayList<>(list.size());
        int removed = 0;
        for (ResourceDO r : list) {
            String permission = r.getPermission();
            if (StrUtil.isBlank(permission)) {
                result.add(r); // 没有权限串的（目录层占位）不参与去重
                continue;
            }
            ResourceDO exist = kept.get(permission);
            if (exist == null) {
                kept.put(permission, r);
                result.add(r);
            } else if (r.getId() < exist.getId()) {
                // 换成 id 更小的那行，保持与判定侧 get(0) 同口径
                result.set(result.indexOf(exist), r);
                kept.put(permission, r);
                removed++;
            } else {
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("[getResourceList][按权限串去重，隐藏了 {} 个重复节点]", removed);
        }
        return result;
    }

    @GetMapping("/get")
    @Operation(summary = "查询单个资源")
    @Parameter(name = "id", description = "资源编号", required = true)
    @PreAuthorize("@ss.hasPermission('system:resource:query')")
    public CommonResult<ResourceRespVO> getResource(@RequestParam("id") Long id) {
        ResourceDO resource = resourceMapper.selectById(id);
        return success(resource == null ? null : BeanUtils.toBean(resource, ResourceRespVO.class));
    }

    /**
     * 按父节点查子资源（树表懒加载）
     *
     * <h3>为什么需要它：折叠并不减少 DOM</h3>
     *
     * el-table 的树形表格会把<b>全部</b>节点一次性渲染进 tbody，折叠只是 CSS 隐藏。
     * 全库 650+ 个资源意味着首屏就是 650 行 DOM，每次筛选还要重建一遍 —— 实测明显卡顿。
     *
     * 改成懒加载后首屏只渲染约 100 个根节点，子节点展开时才拉。
     * 返回体里带 {@code hasChildren}，前端据此决定要不要画展开箭头 ——
     * 没有它的话，叶子节点也会显示成可展开，点开却是空的
     */
    @GetMapping("/list-children")
    @Operation(summary = "按父节点查子资源（树表懒加载用）")
    @Parameter(name = "parentId", description = "父资源编号，根节点传 0", required = true)
    @PreAuthorize("@ss.hasPermission('system:resource:query')")
    public CommonResult<List<ResourceRespVO>> getChildren(
            @RequestParam("parentId") Long parentId,
            @RequestParam(value = "includeFieldLevel", required = false, defaultValue = "false")
            Boolean includeFieldLevel) {
        LambdaQueryWrapper<ResourceDO> query = new LambdaQueryWrapper<ResourceDO>()
                .eq(ResourceDO::getParentId, parentId)
                .eq(ResourceDO::getStatus, CommonStatusEnum.ENABLE.getStatus());
        if (!Boolean.TRUE.equals(includeFieldLevel)) {
            query.notIn(ResourceDO::getType,
                    ResourceTypeEnum.COLUMN.getCode(), ResourceTypeEnum.FIELD.getCode());
        }
        query.orderByAsc(ResourceDO::getSort).orderByAsc(ResourceDO::getId);
        List<ResourceDO> children = resourceMapper.selectList(query);
        if (children.isEmpty()) {
            return success(List.of());
        }
        // 一次查出「这批子节点各自还有没有子节点」，避免前端每行再问一次
        List<Long> ids = children.stream().map(ResourceDO::getId).toList();
        LambdaQueryWrapper<ResourceDO> subQuery = new LambdaQueryWrapper<ResourceDO>()
                .in(ResourceDO::getParentId, ids)
                .eq(ResourceDO::getStatus, CommonStatusEnum.ENABLE.getStatus());
        if (!Boolean.TRUE.equals(includeFieldLevel)) {
            subQuery.notIn(ResourceDO::getType,
                    ResourceTypeEnum.COLUMN.getCode(), ResourceTypeEnum.FIELD.getCode());
        }
        Set<Long> parentsWithChildren = resourceMapper.selectList(subQuery).stream()
                .map(ResourceDO::getParentId).collect(Collectors.toSet());
        List<ResourceRespVO> result = BeanUtils.toBean(children, ResourceRespVO.class);
        result.forEach(vo -> vo.setHasChildren(parentsWithChildren.contains(vo.getId())));
        return success(result);
    }

    @PostMapping("/create")
    @Operation(summary = "新增资源",
            description = "用于补建自定义权限点。菜单派生的资源不需要手工建 —— 它们由菜单同步自动维护")
    @PreAuthorize("@ss.hasPermission('system:resource:update')")
    public CommonResult<Long> createResource(@Valid @RequestBody ResourceSaveReqVO reqVO) {
        validatePermissionUnique(reqVO.getPermission(), null);
        ResourceTypeEnum type = ResourceTypeEnum.parse(reqVO.getType());
        if (type == null) {
            throw exception(RESOURCE_TYPE_INVALID, reqVO.getType());
        }
        ResourceDO resource = BeanUtils.toBean(reqVO, ResourceDO.class);
        resource.setId(null);
        if (StrUtil.isBlank(resource.getDefaultEffect())) {
            resource.setDefaultEffect(type.defaultEffect());
        }
        if (resource.getStatus() == null) {
            resource.setStatus(CommonStatusEnum.ENABLE.getStatus());
        }
        if (resource.getSort() == null) {
            resource.setSort(0);
        }
        // 手工建的资源不挂 menu_id：挂上去会被 syncFromMenu 当成「菜单派生资源」接管，
        // 菜单一变就把这里填的内容覆盖掉
        resource.setMenuId(null);
        return success(resourceService.createResource(resource));
    }

    /**
     * 修改资源
     *
     * <h3>⚠️ 菜单派生的资源只允许改 sort / status</h3>
     *
     * {@code menu_id} 非空的行是 {@code syncFromMenu} 按菜单生成并持续维护的：
     * 它的 permission / name 下次菜单一变就会被原样覆盖回去。
     * 允许在这里改，表现就是「改了、保存成功了、过一会儿自己变回去」——
     * 这正是本项目最贵的那类缺陷，所以直接在入口挡掉，而不是让人改完再发现。
     *
     * 要改这类资源的权限标识，去「菜单管理」改对应菜单。
     */
    @PutMapping("/update")
    @Operation(summary = "修改资源",
            description = "菜单派生的资源（menu_id 非空）只允许改显示顺序与状态，其余字段请去菜单管理改")
    @PreAuthorize("@ss.hasPermission('system:resource:update')")
    public CommonResult<Boolean> updateResource(@Valid @RequestBody ResourceSaveReqVO reqVO) {
        if (reqVO.getId() == null) {
            throw exception(RESOURCE_NOT_EXISTS);
        }
        ResourceDO existing = resourceMapper.selectById(reqVO.getId());
        if (existing == null) {
            throw exception(RESOURCE_NOT_EXISTS);
        }
        boolean derivedFromMenu = existing.getMenuId() != null;
        if (derivedFromMenu) {
            boolean touchedProtected = !StrUtil.equals(existing.getPermission(), reqVO.getPermission())
                    || !StrUtil.equals(existing.getName(), reqVO.getName())
                    || !StrUtil.equals(existing.getType(), reqVO.getType())
                    || !Objects.equals(existing.getParentId(), reqVO.getParentId());
            if (touchedProtected) {
                throw exception(RESOURCE_DERIVED_READONLY, existing.getMenuId());
            }
            existing.setSort(reqVO.getSort());
            existing.setStatus(reqVO.getStatus());
            resourceMapper.updateById(existing);
            return success(true);
        }
        // 自定义资源：权限标识变更要查重，并走 updateResource 级联重算路径
        if (!StrUtil.equals(existing.getPermission(), reqVO.getPermission())) {
            validatePermissionUnique(reqVO.getPermission(), reqVO.getId());
        }
        ResourceDO update = BeanUtils.toBean(reqVO, ResourceDO.class);
        update.setMenuId(existing.getMenuId());
        if (StrUtil.isBlank(update.getDefaultEffect())) {
            update.setDefaultEffect(existing.getDefaultEffect());
        }
        resourceService.updateResource(update);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除资源",
            description = "有子资源或已被授权引用时拒绝删除 —— 直接删会留下孤儿授权，且没有任何提示")
    @Parameter(name = "id", description = "资源编号", required = true)
    @PreAuthorize("@ss.hasPermission('system:resource:update')")
    public CommonResult<Boolean> deleteResource(@RequestParam("id") Long id) {
        ResourceDO existing = resourceMapper.selectById(id);
        if (existing == null) {
            throw exception(RESOURCE_NOT_EXISTS);
        }
        // ⚠️ 菜单派生的资源不能在这里删：syncFromMenu 下次跑会照着菜单原样重建，
        // 表现是「删了、列表里没了、重启之后又回来了」
        if (existing.getMenuId() != null) {
            throw exception(RESOURCE_DERIVED_UNDELETABLE, existing.getMenuId());
        }
        Long grantCount = grantMapper.selectCountByResourceId(id);
        if (grantCount != null && grantCount > 0) {
            throw exception(RESOURCE_HAS_GRANT, grantCount);
        }
        // 有子节点时 deleteResource 自己会拒绝
        resourceService.deleteResource(id);
        return success(true);
    }

    /**
     * 批量删除资源，全有或全无。
     *
     * <h3>⚠️ 校验逐条对齐单条删除，只是范围是一批</h3>
     *
     * 与 {@link #deleteResource} 完全一致的三条拒绝理由（不存在 / 菜单派生 / 被授权引用）在这里
     * 对整批一次性校验：只要有任意一行命中，整批都不删。批量特有的第四条「子资源没被一起勾选」
     * 由 {@link ResourceService#deleteResourceList} 在同一事务内判定，避免在资源树里留下孤儿。
     *
     * <h3>为什么要 all-or-nothing</h3>
     *
     * 部分成功会让前端拿到一个「删了几个、剩几个不知道」的中间态，用户无从判断该重选哪些。
     * 全有或全无的语义最简单：要么这批全没了，要么一个都没动，错误信息说明是哪一类原因挡住的。
     */
    @DeleteMapping("/delete-list")
    @Operation(summary = "批量删除资源",
            description = "全有或全无：只要有一个菜单派生、被授权引用、或有子资源没一起勾选，整批都不删")
    @Parameter(name = "ids", description = "资源编号列表", required = true)
    @PreAuthorize("@ss.hasPermission('system:resource:update')")
    public CommonResult<Boolean> deleteResourceList(@RequestParam("ids") List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return success(true);
        }
        // 前端树表勾选可能把同一 id 传两次；不去重会让 size 比对把「重复」误判成「资源不存在」
        List<Long> distinctIds = ids.stream().distinct().toList();
        List<ResourceDO> resources = resourceMapper.selectByIds(distinctIds);
        if (resources.size() != distinctIds.size()) {
            throw exception(RESOURCE_NOT_EXISTS);
        }
        // 菜单派生的资源不能删：syncFromMenu 下次跑会照菜单原样重建（与单条删除同口径）
        for (ResourceDO resource : resources) {
            if (resource.getMenuId() != null) {
                throw exception(RESOURCE_DERIVED_UNDELETABLE, resource.getMenuId());
            }
        }
        // 被授权引用的不能删：删了会留下指向不存在资源的孤儿授权，且没有任何提示
        Long grantCount = grantMapper.selectCountByResourceIds(distinctIds);
        if (grantCount != null && grantCount > 0) {
            throw exception(RESOURCE_HAS_GRANT, grantCount);
        }
        resourceService.deleteResourceList(distinctIds);
        return success(true);
    }

    /**
     * 权限标识查重
     *
     * ⚠️ 不过滤 status：停用的行同样占位。漏掉它会插出第二行同权限串的资源，
     * 而「同一 permission 多行资源」正是历史上导致授权静默失效的那个缺陷
     */
    private void validatePermissionUnique(String permission, Long excludeId) {
        ResourceDO exists = resourceMapper.selectByPermissionIncludeDisabled(permission);
        if (exists != null && !Objects.equals(exists.getId(), excludeId)) {
            throw exception(RESOURCE_PERMISSION_DUPLICATE, permission, exists.getId());
        }
    }

    /**
     * 配置数据集的记录分享策略（TODO #48）
     *
     * <h3>为什么单独开一个接口而不是通用的资源 update</h3>
     *
     * 资源行的其它字段（permission / path / depth / parentId）是迁移与树结构的产物，
     * 手工改一个就可能让整棵树对不上；而 {@code share_policy} 是<b>纯业务配置</b>，
     * 是这张表上唯一适合由管理员在界面上改的字段。开小口子比开 update 安全。
     *
     * <h3>⚠️ OWNER_ONLY 依赖 owner 槽位</h3>
     *
     * {@code OWNER_ONLY} 要读记录的归属人列，而归属人是靠 {@code sys_slot_mapping}
     * 的 owner 槽位解析的。没配槽位时读不到归属人 —— 那种情况下分享会<b>一律被拒</b>
     * （看得见的失败，比悄悄放开安全）。所以配 OWNER_ONLY 之前要先配 owner 槽位。
     */
    @PutMapping("/update-share-policy")
    @Operation(summary = "配置记录分享策略",
            description = "OWNER_ONLY 仅归属人可分享 / ANY_VIEWER 可见者均可分享 / DISABLED 禁止分享")
    @Parameter(name = "id", description = "资源编号", required = true)
    @Parameter(name = "sharePolicy", description = "分享策略，传空字符串表示清除（回落到默认）")
    @PreAuthorize("@ss.hasPermission('system:resource:update')")
    public CommonResult<Boolean> updateSharePolicy(
            @RequestParam("id") Long id,
            @RequestParam(value = "sharePolicy", required = false) String sharePolicy) {
        ResourceDO resource = resourceMapper.selectById(id);
        if (resource == null) {
            throw exception(RESOURCE_NOT_EXISTS);
        }
        // 空串/null 视为「清除配置」，判定侧会回落到默认策略
        String normalized = StrUtil.blankToDefault(sharePolicy, null);
        // ⚠️ 不能用 RecordSharePolicyEnum.parse() 做校验：它解析不出来时返回的是
        //    OWNER_ONLY 而【不是 null】（那是判定侧刻意的安全兜底，见其注释）。
        //    拿它当校验用，非法值会被"parse 成功"从而原样写进库，
        //    表现是「界面上配了个错值、保存成功、实际按 OWNER_ONLY 执行」。
        //    写入口必须显式比对合法 code。
        if (normalized != null && Arrays.stream(RecordSharePolicyEnum.values())
                .noneMatch(e -> e.getCode().equals(normalized))) {
            throw exception(RESOURCE_SHARE_POLICY_INVALID, normalized);
        }
        ResourceDO update = new ResourceDO();
        update.setId(id);
        update.setSharePolicy(normalized);
        resourceMapper.updateById(update);
        log.info("[updateSharePolicy][资源({}) 的分享策略改为 {}]", id, normalized);
        return success(true);
    }

}
