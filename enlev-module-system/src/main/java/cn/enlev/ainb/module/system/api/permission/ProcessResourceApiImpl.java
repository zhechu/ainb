package cn.enlev.ainb.module.system.api.permission;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.biz.system.permission.ProcessResourceCommonApi;
import cn.enlev.ainb.framework.datapermission.core.annotation.DataPermission;
import cn.enlev.ainb.module.system.dal.dataobject.permission.GrantDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.GrantMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper;
import cn.enlev.ainb.module.system.enums.permission.GrantActionEnum;
import cn.enlev.ainb.module.system.enums.permission.ResourceTypeEnum;
import cn.enlev.ainb.module.system.framework.permission.core.dataset.ProcessResourceNaming;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectTypeEnum;
import cn.enlev.ainb.module.system.service.permission.ResourceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * 流程资源登记的实现
 *
 * @author 走进新时代
 */
@Service
@Slf4j
public class ProcessResourceApiImpl implements ProcessResourceCommonApi {

    /**
     * 节点资源的权限串前缀：{@code bpm:node:<processKey>:<nodeKey>}
     *
     * ⚠️ 必须与 {@code BpmTaskServiceImpl#buildNodeContextPermission} 拼出来的完全一致 ——
     * 两处各拼一份是跨模块不依赖内部实现的代价，改格式时必须同步改，
     * 否则表现为「节点权限配了不生效」且极难反查。
     *
     * 🔴 这一处与子表分隔符那处<b>不再同理</b>（台账 #101(b) 之后）：分隔符那份已收成引用，
     * 因为被引的常量在 infra 的 {@code api/formdesign/dto} 包里（api 包就是给外面引的）；
     * 而 {@code buildNodeContextPermission} 在 bpm 的 {@code service/task/} 下、不在 api 包 ⟹ 本处引不了，
     * 仍是真副本。判据是「被引的符号在不在 api 包」，不是「跨不跨模块」。
     */
    private static final String NODE_PREFIX = "bpm:node:";

    @Resource
    private ResourceService resourceService;
    @Resource
    private ResourceMapper resourceMapper;
    @Resource
    private GrantMapper grantMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    @DataPermission(enable = false) // 登记资源这件事本身不该被数据权限过滤
    public int registerProcess(String processDefinitionKey, String processName, String categoryCode,
                               Collection<NodeDescriptor> nodes) {
        if (StrUtil.isBlank(processDefinitionKey)) {
            return 0;
        }
        int created = 0;

        // 1. 分类资源（流程的父）。分类继承靠资源树实现 ——
        //    GrantDecisionService 本来就沿祖先链找授权，不需要额外逻辑
        Long categoryResourceId = 0L;
        if (StrUtil.isNotBlank(categoryCode)) {
            String categoryPermission = ProcessResourceNaming.category(categoryCode);
            ResourceDO category = findByPermission(categoryPermission);
            if (category == null) {
                // 分类本身不是访问控制点，默认放行。配成 DENY 的话，
                // 没给分类授权的人连它下面本来能发起的流程都看不见了
                category = buildResource(categoryPermission, categoryCode,
                        ResourceTypeEnum.CATEGORY.getCode(), 0L, "ALLOW");
                resourceService.createResource(category);
                created++;
            }
            categoryResourceId = category.getId();
        }

        // 2. 流程资源
        String processPermission = ProcessResourceNaming.process(processDefinitionKey);
        ResourceDO process = findByPermission(processPermission);
        if (process == null) {
            // ⚠️ DENY。配成 ALLOW 的话「没有任何授权」会回落到 default_effect=ALLOW，
            // START 判定对所有人恒真，把旧的 startUserIds 限制彻底架空
            // （S5d §2.2 踩过这个坑，是端到端才抓出来的）
            process = buildResource(processPermission, processName, ResourceTypeEnum.PROCESS.getCode(),
                    categoryResourceId, "DENY");
            resourceService.createResource(process);
            created++;
        }

        // 3. 审批节点资源，挂在流程底下
        if (CollUtil.isNotEmpty(nodes)) {
            for (NodeDescriptor node : nodes) {
                if (node == null || StrUtil.isBlank(node.nodeKey())) {
                    continue;
                }
                String nodePermission = NODE_PREFIX + processDefinitionKey + ":" + node.nodeKey();
                if (findByPermission(nodePermission) != null) {
                    continue; // 幂等：反复发布不重复建
                }
                // ⚠️ 节点资源默认 ALLOW。它不是访问控制点 ——
                // 它存在的意义是给字段模式当【上下文】用（v9 §7.1 第 7 步）。
                // 配成 DENY 的话，凡是登记了节点资源的流程，
                // 审批页会立刻变成「什么都点不了」
                ResourceDO nodeResource = buildResource(nodePermission, node.nodeName(),
                        ResourceTypeEnum.PROCESS.getCode(), process.getId(), "ALLOW");
                resourceService.createResource(nodeResource);
                created++;
            }
        }

        if (created > 0) {
            log.info("[registerProcess][流程({})登记完成，新建 {} 个资源]", processDefinitionKey, created);
        }
        return created;
    }

    private ResourceDO findByPermission(String permission) {
        List<ResourceDO> list = resourceMapper.selectListByPermission(permission);
        return CollUtil.isEmpty(list) ? null : list.get(0);
    }

    // ==================== 三个 ID 列表 → 授权（S7b / TODO #30）====================

    /**
     * 本通道写入的授权的 creator 标记
     *
     * ⚠️ 同步时<b>只删这个标记的行</b>。管理员在授权界面上手工配的一律不动 ——
     * 全删的话，每次重新部署流程就把人家配了半天的授权静默清空了
     */
    public static final String SYNC_CREATOR = "BPM-SCOPE-SYNC";

    @Override
    @Transactional(rollbackFor = Exception.class)
    @DataPermission(enable = false)
    public int syncProcessAuthorization(String processDefinitionKey, Collection<Long> startUserIds,
                                        Collection<Long> startDeptIds, Collection<Long> managerUserIds) {
        if (StrUtil.isBlank(processDefinitionKey)) {
            return 0;
        }
        ResourceDO process = findByPermission(ProcessResourceNaming.process(processDefinitionKey));
        if (process == null) {
            // 正常路径走不到：registerProcess 在同一个钩子里先跑。
            // 走到了说明登记失败过，此时【不能】静默返回 —— 判定处的回落已经摘掉，
            // 没有资源行就意味着这个流程谁都发不了
            log.error("[syncProcessAuthorization][流程({})的资源行不存在，发起范围无法投影成授权。"
                    + "该流程当前【谁都发不了】，请重新发布一次]", processDefinitionKey);
            return 0;
        }

        // 1. 清掉上一轮同步写的行（只清自己的）
        grantMapper.delete(new LambdaQueryWrapper<GrantDO>()
                .eq(GrantDO::getResourceId, process.getId())
                .eq(GrantDO::getCreator, SYNC_CREATOR));

        // 2. 重新投影
        int created = 0;
        boolean restricted = CollUtil.isNotEmpty(startUserIds) || CollUtil.isNotEmpty(startDeptIds);
        if (!restricted) {
            // 🔴 「两个范围都没配 = 所有人都可以发起」必须落成一条显式授权。
            //
            // 流程资源的 default_effect 是 DENY，靠「没有授权」表达不出「人人可发起」——
            // 只会变成「谁都发不了」。这一条是摘掉旧回落的前提
            created += insertGrant(process.getId(), SubjectTypeEnum.ALL, null,
                    SubjectKey.ALL_USERS_CODE, GrantActionEnum.START.getCode());
        } else {
            for (Long userId : nullSafe(startUserIds)) {
                if (userId != null) {
                    created += insertGrant(process.getId(), SubjectTypeEnum.USER, userId, null,
                            GrantActionEnum.START.getCode());
                }
            }
            for (Long deptId : nullSafe(startDeptIds)) {
                if (deptId != null) {
                    // ⚠️ 部门必须落成 DEPT 主体，不能在这里展开成该部门当前的成员。
                    // 展开的话，之后调进这个部门的人不会自动获得发起权 ——
                    // 而配置界面上「研发部」三个字还好端端写着。
                    // 这也是 enlev.permission.subject.dept-enabled 必须打开的原因
                    created += insertGrant(process.getId(), SubjectTypeEnum.DEPT, deptId, null,
                            GrantActionEnum.START.getCode());
                }
            }
        }
        for (Long userId : nullSafe(managerUserIds)) {
            if (userId != null) {
                created += insertGrant(process.getId(), SubjectTypeEnum.USER, userId, null,
                        GrantActionEnum.MANAGE.getCode());
            }
        }
        log.info("[syncProcessAuthorization][流程({})的发起范围已投影成 {} 条授权，限定发起范围={}]",
                processDefinitionKey, created, restricted);
        return created;
    }

    private Collection<Long> nullSafe(Collection<Long> ids) {
        return ids == null ? List.of() : ids;
    }

    private int insertGrant(Long resourceId, SubjectTypeEnum subjectType, Long subjectRefId,
                            String subjectCode, String action) {
        GrantDO grant = new GrantDO();
        grant.setSubjectType(subjectType.name());
        grant.setSubjectRefId(subjectRefId);
        grant.setSubjectCode(subjectCode);
        grant.setResourceId(resourceId);
        grant.setAction(action);
        grant.setEffect("ALLOW");
        grant.setCreator(SYNC_CREATOR);
        grant.setUpdater(SYNC_CREATOR);
        // ⚠️ tenant_id 不手填：GrantDO 是 TenantBaseDO，由多租户拦截器按当前上下文填。
        // 手填的话就要自己回答「主体所属租户是哪个」，而部署请求的租户上下文就是答案
        // （README 里那条「手写 INSERT 必须显式给 tenant_id」说的是【直接写 SQL】的场景）
        grantMapper.insert(grant);
        return 1;
    }
    private ResourceDO buildResource(String permission, String name, String type,
                                     Long parentId, String defaultEffect) {
        ResourceDO resource = new ResourceDO();
        resource.setPermission(permission);
        resource.setName(StrUtil.blankToDefault(name, permission));
        resource.setType(type);
        resource.setParentId(parentId);
        resource.setSort(0);
        resource.setDefaultEffect(defaultEffect);
        resource.setStatus(0);
        return resource;
    }

}
