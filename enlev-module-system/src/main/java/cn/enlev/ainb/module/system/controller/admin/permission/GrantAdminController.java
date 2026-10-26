package cn.enlev.ainb.module.system.controller.admin.permission;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.biz.system.permission.dto.DatasetFieldModeRespDTO;
import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils;
import cn.enlev.ainb.module.system.api.permission.PermissionApiImpl;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.grant.EffectiveTreeRespVO;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.grant.GrantBatchSaveReqVO;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.grant.GrantBatchSaveRespVO;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.grant.GrantPageReqVO;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.grant.GrantRespVO;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.grant.GrantSaveReqVO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.GrantDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.RuleDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.GrantMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.RuleMapper;
import cn.enlev.ainb.module.system.dal.redis.RedisKeyConstants;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectTypeEnum;
import cn.enlev.ainb.module.system.service.permission.GrantAdminService;
import cn.enlev.ainb.module.system.service.permission.GrantDecisionService;
import cn.enlev.ainb.module.system.service.permission.SubjectDisplayNameResolver;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.GRANT_SUBJECT_IDENTIFIER_REQUIRED;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.GRANT_SUBJECT_TYPE_INVALID;

/**
 * 管理后台 - 授权配置（S8a）
 *
 * 提供授权行（sys_grant）的增删改查，是权限配置页的核心后端接口。
 * 读路径（鉴权用）走 GrantDecisionService，这里只是管理 CRUD。
 */
@Tag(name = "管理后台 - 授权配置")
@RestController
@RequestMapping("/system/grant")
@Validated
public class GrantAdminController {

    @Resource
    private GrantMapper grantMapper;
    @Resource
    private ResourceMapper resourceMapper;
    @Resource
    private RuleMapper ruleMapper;
    @Resource
    private PermissionApiImpl permissionApi;
    /**
     * 写操作全部走它：upsert 判重、收敛守卫、批量删除的全有或全无校验都在 Service 层，
     * 那里才有 {@code @DSTransactional} 与缓存驱逐。
     * 本类只保留读路径直查 Mapper —— 别把查询也搬进 Service，否则会与
     * {@code GrantDecisionService} 形成第三份口径
     */
    @Resource
    private GrantAdminService grantAdminService;
    /**
     * 生效权限树（智能查看）的判定入口
     *
     * 🔴 这是本类里**唯一**委托给 Service 的读路径，而且是必须的：它要的是
     * 「引擎判出来是什么」，不是「台账上写了什么」。类注释禁的是把查询搬进
     * {@code GrantAdminService} 从而造出**第三份**口径 —— 而这里恰恰相反，
     * 它复用的就是那份权威口径。在 Controller 里自己拼一遍判定才是真正的第三份
     */
    @Resource
    private GrantDecisionService grantDecisionService;
    /**
     * 授权主体的展示名。单独一个组件而不是塞进 {@code GrantAdminService}：
     * 它只服务展示，不参与任何判定，放进来不会造出第三份鉴权口径
     */
    @Resource
    private SubjectDisplayNameResolver subjectDisplayNameResolver;

    @GetMapping("/my-field-modes")
    @Operation(summary = "查询【当前登录用户】对某数据集的字段模式",
            description = "给前端按字段权限隐藏列/置灰用。不挂权限点 —— 它返回的是调用者自己的权限，"
                    + "挂了反而会让没有授权管理权限的普通用户拿不到自己的字段权限")
    @Parameter(name = "datasetPermission", description = "数据集权限标识，如 system:user", required = true)
    @Parameter(name = "fields", description = "要判定的字段名，逗号分隔；留空表示返回该数据集下已配置过的全部字段")
    @Parameter(name = "column", description = "true=列表列(:col:)，false=详情字段(:fld:)。默认 false")
    public CommonResult<Map<String, String>> getMyFieldModes(
            @RequestParam("datasetPermission") String datasetPermission,
            @RequestParam(value = "fields", required = false) String fields,
            @RequestParam(value = "column", required = false, defaultValue = "false") Boolean column) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        if (userId == null) {
            return success(Map.of());
        }
        // 字段清单：调用方给了就用，没给就从资源表反查该数据集下已登记的字段资源。
        // 反查是为了让前端不必硬编码字段名 —— 硬编码的话，后端加一个受控字段
        // 前端不同步就会漏掉，而漏掉的表现是「配了隐藏但那一列还在」
        Collection<String> fieldKeys;
        if (StrUtil.isNotBlank(fields)) {
            fieldKeys = Arrays.stream(fields.split(",")).map(String::trim)
                    .filter(StrUtil::isNotBlank).collect(Collectors.toList());
        } else {
            String prefix = datasetPermission + (Boolean.TRUE.equals(column) ? ":col:" : ":fld:");
            fieldKeys = resourceMapper.selectList(new LambdaQueryWrapperX<ResourceDO>()
                            .likeRight(ResourceDO::getPermission, prefix)).stream()
                    .map(r -> r.getPermission().substring(prefix.length()))
                    .distinct().collect(Collectors.toList());
        }
        if (fieldKeys.isEmpty()) {
            return success(Map.of());
        }
        DatasetFieldModeRespDTO dto = permissionApi.getFieldModes(
                userId, datasetPermission, fieldKeys, Boolean.TRUE.equals(column));
        return success(dto == null || dto.getModes() == null ? Map.of() : dto.getModes());
    }

    @GetMapping("/page")
    @Operation(summary = "分页查询某个主体的授权",
            description = "授权配置页列表用：list-by-subject 不分页、授权行一多前端就卡死，这个是它的分页版")
    @PreAuthorize("@ss.hasPermission('system:grant:query')")
    public CommonResult<PageResult<GrantRespVO>> getGrantPage(@Valid GrantPageReqVO reqVO) {
        // 资源名称 / 权限标识在 sys_resource 上，先解析成资源 id 集合再过滤授权行。
        // 匹配不到任何资源时直接返回空页，避免 in () 空集合语义出错
        Collection<Long> resourceIds = null;
        if (StrUtil.isNotBlank(reqVO.getResourceName())
                || StrUtil.isNotBlank(reqVO.getResourcePermission())) {
            resourceIds = resourceMapper.selectList(new LambdaQueryWrapperX<ResourceDO>()
                            .likeIfPresent(ResourceDO::getName, reqVO.getResourceName())
                            .likeIfPresent(ResourceDO::getPermission, reqVO.getResourcePermission()))
                    .stream().map(ResourceDO::getId).collect(Collectors.toSet());
            if (resourceIds.isEmpty()) {
                return success(new PageResult<>(List.of(), 0L));
            }
        }
        PageResult<GrantDO> pageResult = grantMapper.selectPage(reqVO, resourceIds);
        return success(new PageResult<>(toRespVOs(pageResult.getList()), pageResult.getTotal()));
    }

    @GetMapping("/list-by-resource")
    @Operation(summary = "查询资源上的授权行",
            description = "按资源 id 直接查，比 list-by-subject 更适合「资源详情页展示所有授权」场景")
    @Parameter(name = "resourceId", description = "资源编号", required = true)
    @PreAuthorize("@ss.hasPermission('system:grant:query')")
    public CommonResult<List<GrantRespVO>> listByResource(@RequestParam("resourceId") Long resourceId) {
        List<GrantDO> list = grantMapper.selectList(
                new LambdaQueryWrapperX<GrantDO>().eq(GrantDO::getResourceId, resourceId));
        if (list.isEmpty()) {
            return success(List.of());
        }
        // 批量填充资源名和规则名
        Map<Long, ResourceDO> resourceMap = resourceMapper.selectBatchIds(
                list.stream().map(GrantDO::getResourceId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(ResourceDO::getId, r -> r));
        Set<Long> ruleIds = list.stream().map(GrantDO::getRuleId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> ruleNameMap = ruleIds.isEmpty() ? Map.of()
                : ruleMapper.selectBatchIds(ruleIds).stream()
                .collect(Collectors.toMap(RuleDO::getId, RuleDO::getName));
        List<GrantRespVO> vos = list.stream().map(g -> {
            GrantRespVO vo = BeanUtils.toBean(g, GrantRespVO.class);
            ResourceDO res = resourceMap.get(g.getResourceId());
            if (res != null) {
                vo.setResourceName(res.getName());
                vo.setResourcePermission(res.getPermission());
                vo.setResourceType(res.getType());
            }
            if (g.getRuleId() != null) {
                vo.setRuleName(ruleNameMap.get(g.getRuleId()));
            }
            vo.setExpired(!g.isActive());
            return vo;
        }).collect(Collectors.toList());
        // 资源明细面板的「授权列表」页签走的就是这个端点，主体名在这里补齐
        fillSubjectNames(vos);
        return success(vos);
    }

    @GetMapping("/list-by-subject")
    @Operation(summary = "查询某个主体的全部授权",
            description = "授权配置页：左边选角色/用户，右边展示它的授权清单。"
                    + "⚠️ 寻址参数不全时**报错**而不是返回空列表：空列表会被读成「这个主体没有任何授权」，"
                    + "而真相只是参数写错了")
    @Parameter(name = "subjectType", description = "主体类型，如 ROLE/USER/DEPT", required = true)
    @Parameter(name = "subjectRefId", description = "主体业务 id，SYSTEM/ALL 类型不填")
    @Parameter(name = "subjectCode", description = "主体编码，仅 SYSTEM/ALL 类型填")
    @PreAuthorize("@ss.hasPermission('system:grant:query')")
    public CommonResult<List<GrantRespVO>> listBySubject(
            @RequestParam("subjectType") String subjectType,
            @RequestParam(value = "subjectRefId", required = false) Long subjectRefId,
            @RequestParam(value = "subjectCode", required = false) String subjectCode) {
        // 🔴 与 /effective-tree 共用 resolveSubject，不再自己内联一套解析。
        // 原先这里两条分支各错一种：类型解析不出来就 return 空列表（把参数错误伪装成
        // 「没有授权」这个合法业务结论），类型对但少给标识就直接 new SubjectKey(...)，
        // 撞上 record 紧凑构造器的 IllegalArgumentException（缺 code）或
        // Objects.requireNonNull 的 NullPointerException（缺 refId）。
        // 两者都没有全局 handler，实测各得到一条 {"code":500,"msg":"系统异常"} + 3010 字节堆栈
        List<GrantDO> grants = grantMapper.selectListBySubject(
                resolveSubject(subjectType, subjectRefId, subjectCode));
        return success(toRespVOs(grants));
    }

    @GetMapping("/effective-tree")
    @Operation(summary = "查询某个主体的生效权限树（角色权限地图，只读）",
            description = "「切换智能查看」用。返回的不是台账上的授权行，而是把祖先继承、DENY 覆盖 ALLOW、"
                    + "过期失效、默认放行这四层语义算清楚之后的生效结果，每一项都带「从哪来」。"
                    + "判定全部在后端由 GrantDecisionEngine#explain 给出 —— 前端一行判定逻辑都不写，"
                    + "否则就是第三份会漂移的口径，而这是一张权限地图：它说「拥有」而引擎判 DENY，"
                    + "比没有这张图更糟。口径与多租户影响见 GrantDecisionService#listEffectiveTree")
    @Parameter(name = "subjectType", description = "主体类型，角色权限地图传 ROLE", required = true)
    @Parameter(name = "subjectRefId", description = "主体业务 id，SYSTEM/ALL 类型不填")
    @Parameter(name = "subjectCode", description = "主体编码，仅 SYSTEM/ALL 类型填")
    @Parameter(name = "includeFieldLevel", description = "是否包含 COLUMN/FIELD 字段级资源，默认 false")
    @Parameter(name = "includeAllSubject", description = "是否并入全员主体，默认 true。"
            + "关掉会漏掉配在 all-users 上的那批授权")
    @Parameter(name = "includeDefaultAllow", description = "是否包含「纯默认放行」的节点，默认 false。"
            + "这类节点无人配置、仅因资源的 default_effect=ALLOW 而有权限，数量很大")
    // 🔴 沿用本 Controller 全部读接口共用的权限串，角色授权页已有的权限点直接够用 ——
    // 不需要新增菜单/权限串、不需要动数据库。
    // （PermissionDiagController 用独立的 system:permission-diag:query，那是唯一例外，不要抄）
    @PreAuthorize("@ss.hasPermission('system:grant:query')")
    public CommonResult<EffectiveTreeRespVO> getEffectiveTree(
            @RequestParam("subjectType") String subjectType,
            @RequestParam(value = "subjectRefId", required = false) Long subjectRefId,
            @RequestParam(value = "subjectCode", required = false) String subjectCode,
            @RequestParam(value = "includeFieldLevel", required = false,
                    defaultValue = "false") Boolean includeFieldLevel,
            @RequestParam(value = "includeAllSubject", required = false,
                    defaultValue = "true") Boolean includeAllSubject,
            @RequestParam(value = "includeDefaultAllow", required = false,
                    defaultValue = "false") Boolean includeDefaultAllow) {
        return success(grantDecisionService.listEffectiveTree(
                resolveSubject(subjectType, subjectRefId, subjectCode),
                Boolean.TRUE.equals(includeFieldLevel),
                Boolean.TRUE.equals(includeAllSubject),
                Boolean.TRUE.equals(includeDefaultAllow)));
    }

    /**
     * 把寻址参数解析成 {@link SubjectKey}，解析不出来就<b>报错</b>
     *
     * <p>🔴 两个读端点（{@link #listBySubject} 与 {@code /effective-tree}）共用这一份解析，
     * 因为「解析不出来就凑合」的两种写法都比报错更坏：
     * <ul>
     *   <li>返回空列表 ⟹ 一次参数失误被伪装成「这个主体没有任何授权」这个<b>合法业务结论</b>。
     *       在权限地图上它读作「这个角色什么权限都没有」，比没有那张图更糟</li>
     *   <li>直接把参数塞进 {@code new SubjectKey(...)} ⟹ 撞上 record 紧凑构造器里的
     *       {@code IllegalArgumentException}（缺 code）与 {@code Objects.requireNonNull}
     *       的 {@code NullPointerException}（缺 refId）。两者都没有对应的全局 handler，
     *       于是落到兜底分支：用户读到「系统异常」，{@code infra_api_error_log} 里多一条
     *       3010 字节的堆栈。这两条都是实测复现出来的，不是推测</li>
     * </ul>
     * 报错分两个码：类型认不出来是 {@code GRANT_SUBJECT_TYPE_INVALID}，
     * 类型对但少给标识是 {@code GRANT_SUBJECT_IDENTIFIER_REQUIRED}（会点名缺哪个参数）。
     * 合成一条的话模板套不上两种情况，实测拼出来是
     * 「类型 SYSTEM 缺少 subjectCode 不可识别，或缺少……」—— 一句话里同时说「缺少」
     * 和「不可识别」，用户读完仍然不知道自己哪里错了
     */
    private SubjectKey resolveSubject(String subjectType, Long subjectRefId, String subjectCode) {
        SubjectTypeEnum typeEnum = SubjectTypeEnum.parse(subjectType);
        if (typeEnum == null) {
            throw exception(GRANT_SUBJECT_TYPE_INVALID, subjectType);
        }
        if (typeEnum.isRefBased()) {
            if (subjectRefId == null) {
                throw exception(GRANT_SUBJECT_IDENTIFIER_REQUIRED, subjectType, "subjectRefId");
            }
            return SubjectKey.of(typeEnum, subjectRefId);
        }
        if (StrUtil.isBlank(subjectCode)) {
            throw exception(GRANT_SUBJECT_IDENTIFIER_REQUIRED, subjectType, "subjectCode");
        }
        return new SubjectKey(typeEnum, null, subjectCode);
    }

    @PostMapping("/create")
    @Operation(summary = "新增授权行（upsert 语义）",
            description = "同「主体+资源+动作+上下文」已有行时覆盖该行，而不是再插一行。"
                    + "返回新插入行的 id，或被覆盖的存活行 id")
    @PreAuthorize("@ss.hasPermission('system:grant:create')")
    public CommonResult<Long> createGrant(@Valid @RequestBody GrantSaveReqVO reqVO) {
        // 缓存驱逐随业务逻辑一起下移到 GrantAdminServiceImpl，避免 controller/service 双份驱逐
        return success(grantAdminService.upsertGrant(reqVO));
    }

    @PostMapping("/create-list")
    @Operation(summary = "批量新增授权行（逐项 upsert）",
            description = "全有或全无：任意一项的资源不存在、批内出现重复键、或撞上同键但内容不一致的"
                    + "历史多行，整批都不落库。每一项与单条 /create 完全同构，走同一套校验")
    @PreAuthorize("@ss.hasPermission('system:grant:create')")
    public CommonResult<GrantBatchSaveRespVO> createGrantList(
            @Valid @RequestBody GrantBatchSaveReqVO reqVO) {
        return success(grantAdminService.createGrantList(reqVO.getItems()));
    }

    @PutMapping("/update")
    @Operation(summary = "修改授权行（通常只改 effect/ruleId/fieldMode/expireTime）")
    @PreAuthorize("@ss.hasPermission('system:grant:update')")
    public CommonResult<Boolean> updateGrant(@Valid @RequestBody GrantSaveReqVO reqVO) {
        grantAdminService.updateGrant(reqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除授权行")
    @Parameter(name = "id", description = "授权编号", required = true)
    @PreAuthorize("@ss.hasPermission('system:grant:delete')")
    @Caching(evict = {
            @CacheEvict(value = RedisKeyConstants.GRANT_USER_PERMISSIONS, allEntries = true),
            @CacheEvict(value = RedisKeyConstants.GRANT_USER_MENU_IDS, allEntries = true)
    })
    public CommonResult<Boolean> deleteGrant(@RequestParam("id") Long id) {
        grantMapper.deleteById(id);
        return success(true);
    }

    @DeleteMapping("/delete-list")
    @Operation(summary = "批量删除授权行",
            description = "全有或全无：只要有一个 id 不存在，整批都不删。空列表幂等成功")
    @Parameter(name = "ids", description = "授权编号列表", required = true)
    @PreAuthorize("@ss.hasPermission('system:grant:delete')")
    public CommonResult<Boolean> deleteGrantList(@RequestParam("ids") List<Long> ids) {
        grantAdminService.deleteGrantList(ids);
        return success(true);
    }

    // -------- 内部辅助：填充资源名称和规则名称 --------

    private List<GrantRespVO> toRespVOs(List<GrantDO> grants) {
        if (grants == null || grants.isEmpty()) {
            return List.of();
        }

        // 批量加载资源名称（减少 N 次查询）
        List<Long> resourceIds = grants.stream()
                .map(GrantDO::getResourceId).distinct().collect(Collectors.toList());
        Map<Long, ResourceDO> resourceMap = resourceMapper.selectBatchIds(resourceIds)
                .stream().collect(Collectors.toMap(ResourceDO::getId, r -> r));

        // 批量加载规则名称
        List<Long> ruleIds = grants.stream()
                .map(GrantDO::getRuleId).filter(id -> id != null).distinct().collect(Collectors.toList());
        Map<Long, RuleDO> ruleMap = ruleIds.isEmpty() ? Map.of()
                : ruleMapper.selectBatchIds(ruleIds).stream().collect(Collectors.toMap(RuleDO::getId, r -> r));

        List<GrantRespVO> vos = new ArrayList<>(grants.size());
        for (GrantDO g : grants) {
            GrantRespVO vo = BeanUtils.toBean(g, GrantRespVO.class);
            ResourceDO resource = resourceMap.get(g.getResourceId());
            if (resource != null) {
                vo.setResourcePermission(resource.getPermission());
                vo.setResourceName(resource.getName());
                vo.setResourceType(resource.getType());
            }
            if (g.getRuleId() != null) {
                RuleDO rule = ruleMap.get(g.getRuleId());
                if (rule != null) {
                    vo.setRuleName(rule.getName());
                }
            }
            // 界面直接展示「已过期」而不用前端自己算
            vo.setExpired(g.getExpireTime() != null && LocalDateTime.now().isAfter(g.getExpireTime()));
            vos.add(vo);
        }
        fillSubjectNames(vos);
        return vos;
    }

    /**
     * 批量填充 {@code subjectName}（授权主体的展示名）
     *
     * <p>key 直接从 VO 上取 —— {@code BeanUtils.toBean} 已经把
     * subjectType / subjectRefId / subjectCode 三个字段拷过来了，
     * 所以两个调用点都只需要传这一份 vos，不必回头再读一遍 {@code GrantDO}。
     *
     * <p>🔴 这里<b>不复用</b> {@link #resolveSubject}：那个方法解析不出来会抛异常，
     * 而这里读的是<b>库里已有的行</b> —— 一行脏数据（比如被清理过的主体类型）
     * 不该让整个授权列表 500。所以本方法对解析不出来的 key 静默跳过，
     * 那一行的 subjectName 留 null，由前端回落到显示 id 本身。
     */
    private void fillSubjectNames(List<GrantRespVO> vos) {
        if (vos == null || vos.isEmpty()) {
            return;
        }
        Set<SubjectKey> keys = new HashSet<>();
        for (GrantRespVO vo : vos) {
            SubjectKey key = lenientSubjectKeyOf(vo);
            if (key != null) {
                keys.add(key);
            }
        }
        Map<SubjectKey, String> names = subjectDisplayNameResolver.resolve(keys);
        for (GrantRespVO vo : vos) {
            SubjectKey key = lenientSubjectKeyOf(vo);
            if (key == null) {
                continue;
            }
            String name = names.get(key);
            // 解析不到也要给一句话，绝不留空：这一列是管理员核对「权限授给了谁」的唯一依据，
            // 空白会被读成「没有主体」，而真相是「有一个主体，只是我认不出它」
            vo.setSubjectName(name != null ? name : subjectDisplayNameResolver.describeUnknown(key));
        }
    }

    /** 宽松版的 {@link #resolveSubject}：解析不出来返回 null 而不是抛异常 */
    private SubjectKey lenientSubjectKeyOf(GrantRespVO vo) {
        SubjectTypeEnum typeEnum = SubjectTypeEnum.parse(vo.getSubjectType());
        if (typeEnum == null) {
            return null;
        }
        return typeEnum.isRefBased()
                ? (vo.getSubjectRefId() == null ? null : SubjectKey.of(typeEnum, vo.getSubjectRefId()))
                : (StrUtil.isBlank(vo.getSubjectCode()) ? null
                        : new SubjectKey(typeEnum, null, vo.getSubjectCode()));
    }

}
