package cn.enlev.ainb.module.system.service.permission;

import cn.hutool.core.collection.CollUtil;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.datapermission.core.annotation.DataPermission;
import cn.enlev.ainb.framework.datapermission.core.util.DataPermissionUtils;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.grant.EffectiveActionVO;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.grant.EffectiveResourceNodeVO;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.grant.EffectiveTreeRespVO;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.grant.EffectiveTreeSummaryVO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.GrantDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.MenuDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.RuleDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.GrantMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.MenuMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.RuleMapper;
import cn.enlev.ainb.module.system.dal.redis.RedisKeyConstants;
import cn.enlev.ainb.module.system.enums.permission.FieldModeEnum;
import cn.enlev.ainb.module.system.enums.permission.GrantActionEnum;
import cn.enlev.ainb.module.system.enums.permission.ResourceTypeEnum;
import cn.enlev.ainb.module.system.framework.permission.config.PermissionProperties;
import cn.enlev.ainb.module.system.framework.permission.core.decision.GrantDecisionEngine;
import cn.enlev.ainb.module.system.framework.permission.core.decision.GrantDecisionEngine.Decision;
import cn.enlev.ainb.module.system.framework.permission.core.decision.GrantDecisionEngine.DecisionReason;
import cn.enlev.ainb.module.system.framework.permission.core.scan.PermissionStringResolver;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.GRANT_SUBJECT_TYPE_INVALID;

/**
 * 授权决策 Service
 *
 * 胶水层：把 SubjectClosureService / ResourceMapper / GrantMapper / GrantDecisionEngine 粘合起来，
 * 回答「userId 对 permission 是否有 action 权限」。
 *
 * @author 走进新时代
 */
@Service
@Slf4j
public class GrantDecisionService {

    @Resource
    private SubjectClosureService subjectClosureService;
    @Resource
    private ResourceMapper resourceMapper;
    @Resource
    private GrantMapper grantMapper;
    @Resource
    private MenuMapper menuMapper;
    /**
     * 只给生效权限树（{@link #listEffectiveTree}）填 ruleName 用 —— 判定路径压根不读规则。
     *
     * 放在这里而不是让 {@code GrantAdminController} 去遍历嵌套树回填，是为了守住那个
     * Controller 类注释里的硬约束：「本类只保留读路径直查 Mapper —— 别把查询也搬进 Service，
     * 否则会与 GrantDecisionService 形成第三份口径」。回填名称是**这棵树的组装工序**，
     * 不是另一份查询口径
     */
    @Resource
    private RuleMapper ruleMapper;
    /**
     * 超管判定用。与 {@code @ss.hasPermission} 走的是<b>同一个判据</b>
     * （{@code roleService.hasAnySuperAdmin}）—— 各写一份必然漂移
     */
    @Resource
    @Lazy // 延迟，避免与 RoleServiceImpl 之间的循环依赖
    private RoleService roleService;
    @Resource
    @Lazy
    private PermissionService permissionService;
    /**
     * 只为读 {@code collapse-enabled} 这一个开关。判定口径本身全在代码里，
     * 不做成可配的 —— 配错一个布尔值就是一次静默越权，而它没有任何业务上的调整需求
     */
    @Resource
    private PermissionProperties permissionProperties;

    /**
     * 这个用户是不是超管
     *
     * <h3>🔴 为什么清单侧也必须短路</h3>
     *
     * 后端 {@code @ss.hasPermission} 对超管<b>直接放行</b>（`PermissionServiceImpl` 情况一），
     * 而前端的按钮显隐来自 {@link #listAllowedPermissions}。
     * 清单侧不短路的话，两边口径就分叉了：
     * <b>接口能调、按钮却不显示</b>。
     *
     * 这不是假想：超管在旧模型下<b>从来不需要被授权</b> ——
     * 实测 {@code system_role_menu} 里超管只有 274 行，而全库有 1878 个权限点。
     * 于是切到新引擎后，没被授权的那些权限点对应的按钮<b>全部消失</b>，
     * 表现成「我是超管，怎么没有删除菜单的按钮」。
     *
     * ⚠️ {@code hasPermission}（单点判定）<b>不需要</b>加这一条 ——
     * 它的调用方 {@code PermissionServiceImpl#hasAnyPermissions} 已经先判过超管了。
     * 在这里再判一次是重复，而且会让「诊断页展示引擎真实判定路径」这个能力失效。
     */
    private boolean isSuperAdmin(Long userId) {
        if (userId == null) {
            return false;
        }
        try {
            return roleService.hasAnySuperAdmin(permissionService.getUserRoleIdListByUserId(userId));
        } catch (Exception e) {
            // 判不出来时按【非超管】处理：宁可少给权限，也不能因为一次异常把全部权限放出去
            log.warn("[isSuperAdmin][用户({})的超管判定失败，按非超管处理]", userId, e);
            return false;
        }
    }

    private final GrantDecisionEngine engine = new GrantDecisionEngine();

    /**
     * 权限串解析器。与 {@link #engine} 一样直接 new ——
     * 两者都是无状态的纯算法，注进 Spring 只会让 5 个测试类各多一个 {@code @Spy}，
     * 而它们压根没有第二种实现
     */
    private final PermissionStringResolver resolver = new PermissionStringResolver();

    // ========== 塌缩（资源身份 × 动作）==========

    /**
     * {@code enlev.permission.collapse-enabled} 是否打开
     *
     * <p>🔴 打开的前提是 {@code sys_resource} / {@code sys_grant} 已经被迁移重写过。
     * 数据没迁就打开，判定会去查一批还不存在的身份行 ——
     * {@code selectListByPermission} 返回空 → <b>静默判成无权</b>，全程 200、无异常。
     * 见 {@code PermissionProperties#collapseEnabled}
     */
    private boolean collapseEnabled() {
        return Boolean.TRUE.equals(permissionProperties.getCollapseEnabled());
    }

    /**
     * 一次判定实际要用的「查库串 + 动作码」
     *
     * <p>🔴 两者必须<b>成对</b>产出，这是 R9 在判定侧的镜像：
     * 只换串不换动作，判定会去查身份行、却仍拿 VIEW 去比，
     * 而身份行上的授权已经是 CREATE/UPDATE/… —— 结果是<b>所有非查询按钮静默 403</b>。
     * 只换动作不换串则相反：动作对了、资源行找不到，一律无权。
     * 用 record 把两位绑在一起，就是为了让「只改一半」写不出来
     */
    private record Lookup(String permission, String action) {}

    /**
     * 把权限串翻译成塌缩后的「查库串 + 动作码」
     *
     * <p>开关关闭、或该串不塌缩（档 C 业务专有动词 / 档 D 两段式身份 / 单段模块根）时，
     * <b>原样返回入参</b> —— 这就是「关闭态与今天逐字节一致」的全部机制，
     * 没有第二条代码路径需要被记住
     */
    private Lookup lookupOf(String permission, String action) {
        if (!collapseEnabled()) {
            return new Lookup(permission, action);
        }
        PermissionStringResolver.Resolution res = resolver.resolve(permission);
        if (!res.isCollapsed()) {
            return new Lookup(permission, action);
        }
        // 🔴 R11 在判定侧的镜像：<b>成员行还在库里</b> ⟹ 这一族被计划器整族拦下了，不能塌。
        //
        // 拦下的理由写在 ResourceCollapsePlanner#blockedIdentities：身份行同时是树节点，
        // 上提上去的授权会顺着树继承给它其余的存活子节点，实测两个方向都出事
        // （382 个决策点越权 + 100 个决策点被静默收权）。
        //
        // 判据必须与计划器<b>同源</b>：那边看「成员行有没有被退役」，这边就看「它还查不查得到」。
        // 两边只要有一边错，表现都是静默的 ——
        // 这边多塌：去查一个从没接收过授权的身份行，权限凭空消失；
        // 这边少塌：去查一个已经被退役的串，同样凭空消失。
        //
        // 用数据当旗子而不是读第二个配置项，是为了不留「配置说有、数据说没有」这种漂移。
        // 代价是这里多一次 chainsOf —— 它本来就是 hasPermission 下一步要调的同一个缓存键，
        // 所以除了迁移刚跑完的第一次，不额外查库
        //
        // 🔴 判据查完之后，<b>合成答案的那一步交给 identityOf</b>，不在这里内联。
        // 写路径（syncFromMenu / 漂移检测）也要回答同一个问题「这个串该由哪一行承载」，
        // 而它们查不了 chainsOf 的缓存。两边各写一份 {@code if (成员行在) 原样 else 取前缀}，
        // 后果是写侧建出一批读侧永远不会去查的行（或反过来），两个方向都静默。
        // 走同一个纯函数之后，分叉就只可能出在「成员行在不在」这一次查询上 ——
        // 而那一次查询两侧用的是同一个过滤条件（status=0），见 CollapsedResourceLocator
        boolean memberRowExists = !chainsOf(permission).isEmpty();
        // ⚠️ 已知缺口（阶段 3 待修）：这个布尔值分不清「成员行被塌缩退役」与
        // 「成员行被管理员从 UI 删掉」—— 两者在数据里都是「查不到」，都会走到塌缩。
        //
        // 后果是<b>删掉一个按钮资源不再表示拒绝</b>：塌缩前该串没有存活资源行，
        // 上面 hasPermission 的 {@code chains.isEmpty()} 直接 return false；
        // 塌缩后它被翻译成身份行 + 动作，而身份行（DATASET）的 default_effect 出厂是 ALLOW，
        // 链上又往往一条授权都没有，于是 GrantDecisionEngine 回落到 ALLOW —— 放行。
        //
        // 实测已发生：12 个 {@code infra:form-design-run:*} 在线表单的 84 个动作资源
        // 被超管在 UI 里删掉后，翻开 collapse-enabled 把这 84 个串从「拒绝」变成「放行」，
        // 而这些串是活的 —— FormDesignRuntimeController#checkPermission 直接拼这个格式来鉴权。
        // 2c 自校验枚举不到它们（runtimeExpansionTable() 只从 system_menu.permission 取）。
        //
        // 处置（已执行，取代早先「把 deleted 置回 0」的做法 —— 那条已被用户的新指令推翻）：
        // 那 84 行连同另外 7 行「菜单已删、资源还在」的残留一起软删，
        // 同时把它们的 12 个身份锚点 default_effect 由 ALLOW 收窄成 DENY。
        // 🔴 收窄不是可选的顺手清理，是删除的<b>安全前提</b>：只删成员行，
        //    本条机制会立刻把这 84 个串翻成放行。
        // 收窄与 ResourceCollapsePlanner#applyStrictestDefaultEffect 对另外 7 个身份做的是同一件事。
        //
        // ⚠️ 严重度要按实测说，别照抄「84 个权限点被越权放行」这种口径：
        // 那 12 张表单<b>一张都没有发布过</b>（status=0、published_version=0，已逐张复核），
        // 运行时校验在权限门之后还要过「表单存在 / 已发布」两道，
        // 实测连超管拿到的都是 {@code FORM_DESIGN_RUNTIME_EXECUTE_FAIL} 与「表单未发布」。
        // 所以那 84 个串是<b>潜伏</b>的，不是当场可利用的 ——
        // 真正被观察到的是 {@code get-permission-info} 里多出 84 个幽灵权限串
        // （前端 v-hasPermi 会照着放行按钮），以及「任何一张发布出去就立刻变成真越权」。
        // 把潜伏写成已发生，会让下一个人对这一类缺口的警戒心用错地方
        //
        // 🔴 但「潜伏」只对那 12 张成立，<b>对这个机制的整个群体不成立</b>。
        // 同一形状（DATASET + default_effect=ALLOW + menu_id 为 NULL + 没有存活子行）
        // 清理后库里还有 <b>157 个</b>，而这 157 个<b>整条链上一条活授权都没有</b>
        // （自身 / 父行 {@code infra:form-design-run} / 祖父行 {@code infra} 全为 0）。
        // 其中 154 个连 {@code infra_form_design} 行都不存在（潜伏），<b>3 个是已发布的</b>：
        // {@code test_leave}、{@code test_leave_audit}、{@code e2e_ld_1789517946540}。
        //
        // 这 3 个<b>已经实测打穿</b>。主体是自定义角色「演示角色」下的非超管用户，
        // 在这 3 条链上零授权：
        //   {@code GET  .../runtime/test_leave/config} → code=0，返回 formConf + formFields（整张表单定义）
        //   {@code POST .../runtime/test_leave/page}   → code=0，total=6（真实业务数据行）
        //   {@code POST .../runtime/test_leave_audit/page} → code=0，total=2
        // 对照组证明这不是「一律放行」的假象：
        //   库里没有资源行的 code（{@code no_such_form_zzz}）→ 正确拒绝；
        //   本轮收窄成 DENY 的 {@code device_manage} → 正确拒绝。
        // 塌缩前这 3 个请求是拒绝的（成员行不存在 ⟹ 直接 return false），
        // 所以这是本次改造引入的回归。FormDesignRuntimeController#checkReferencePermission
        // 的 javadoc 里那句「hasPermission 对<b>资源行不存在</b>直接返回 false」
        // 描述的正是塌缩<b>前</b>的语义，如今已不成立 —— 它是这条缺口的现成文档证据。
        //
        // ⚠️ 这 157 个<b>刻意没有一并收窄</b>，因为它不是机械清理，而是有真实功能代价的决策：
        // 那 3 张已发布表单在资源路径上零授权，今天之所以有人能用，靠的就是这个 ALLOW 回落。
        // 一律收窄成 DENY 等于当场切断所有非超管对它们的访问，
        // 而「谁本该有权限」无法凭空推断 —— 得先配授权。留给决策，不代做。
        //
        // 🔴 这只是把数据摆回安全的一侧，没有修根因：管理员再删一次，缺口就再出现一次。
        // 三条根治路线都查过，<b>今天一条都不能直接动</b>：
        // <ol>
        //   <li>给 {@code sys_resource} 加一列标记「这一行是塌缩目标」。判据最干净，
        //       但本仓库 DDL 不可回滚、也没有草稿态，动表结构必须先取得明确授权</li>
        //   <li>换判据：只在身份行 {@code default_effect=DENY} 时才塌缩。零 DDL，
        //       但有两个代价 —— ① 把「塌缩与否」和「默认放行与否」两个语义压在同一列上，
        //       管理员把某个数据集改回 ALLOW 就会让整族动词串集体失效；
        //       ② 🔴 <b>会让自校验失去意义</b>：{@code CollapseMigrationSelfCheckTest}
        //       是拿计划器去重放<b>迁移前的冻结快照</b>（{@code _scratch/s2c_snapshot.json}），
        //       改判据之后它校验的就是<b>一份从未被执行过的计划</b> ——
        //       「评审过的那份」与「落库的那份」从此不是同一个东西，
        //       而迁移不可重跑，也就没有别的办法把两者重新对齐。
        //       要走这条路，必须同时重建快照基线，那等于把阶段 2c 重做一遍</li>
        //   <li>启动时扫一遍「身份行是 ALLOW 且成员行查不到」的形状并告警/自愈。
        //       看似最轻，但它<b>必须能读到软删行</b>才分得清「被塌缩退役」与「被管理员删掉」——
        //       而 {@code ResourceMapper} 里没有任何一个方法能做到：
        //       全部走 MyBatis-Plus 逻辑删除，连名字里带 IncludeDisabled 的那个
        //       过滤的也是 {@code status} 而不是 {@code deleted}。
        //       绕过去就得写裸 SQL，那是给这一层开一个从此没人守的口子</li>
        // </ol>
        // 所以本轮的选择是：把数据摆回安全侧 + 把缺口和三条路线的阻塞点写在这里，
        // 而不是挑一条看起来能跑的路线先落地
        String identity = resolver.identityOf(permission, memberRowExists);
        if (identity.equals(permission)) {
            return new Lookup(permission, action);
        }
        return new Lookup(identity, res.getAction().getCode());
    }

    /**
     * 取一个权限串的全部资源祖先链（一个串可能对应多行资源，所以是链的列表）
     *
     * <p>抽出来是因为 {@link #hasPermission} 与 {@link #resolveResourceChain}
     * 原本各自内联了一份完全相同的 {@code computeIfAbsent}。两份的坏处不是重复，
     * 而是<b>缓存键</b>：塌缩后两边都必须按「解析出的身份串」而不是原始串入缓存，
     * 漏改一处就会让同一个资源在缓存里存两份、且诊断页显示的链与鉴权用的链不同
     */
    private List<List<ResourceDO>> chainsOf(String permission) {
        // ⚠️ computeIfAbsent 包住了整个查询（含 selectListByPermission），
        // 不能把 selectListByPermission 提到外面再传进来 ——
        // 那样缓存没命中时照样每次查库，缓存只在后续调用生效，等于白加
        return resourceChainCache.computeIfAbsent(permission, k -> {
            List<ResourceDO> found = resourceMapper.selectListByPermission(k);
            List<List<ResourceDO>> built = new ArrayList<>(found.size());
            for (ResourceDO r : found) {
                built.add(buildResourceChain(r));
            }
            return built;
        });
    }

    /**
     * 把一条链上所有节点的授权行收拢到一起，供引擎判定
     */
    private static List<GrantDO> chainGrants(List<ResourceDO> chain,
                                             Map<Long, List<GrantDO>> grantsByResource) {
        List<GrantDO> grants = new ArrayList<>();
        for (ResourceDO node : chain) {
            List<GrantDO> hit = grantsByResource.get(node.getId());
            if (hit != null) {
                grants.addAll(hit);
            }
        }
        return grants;
    }

    /**
     * 塌缩后，一个资源行上<b>值得逐个试一遍</b>的动作集合
     *
     * <h3>🔴 与生效权限树那个 {@link #candidateActions} 刻意不是同一个方法</h3>
     *
     * 那个方法在「链上没有任何授权行」时返回空集（除非 {@code default_effect=ALLOW}），
     * 为的是压掉 205 个零配置 DATASET 节点的假阳性 —— 它服务的是<b>展示</b>，少报是安全的一侧。
     *
     * 本方法服务的是<b>权限清单</b>，少报等于按钮消失，所以 VIEW 必须无条件在候选里：
     * 开关关闭时清单侧本来就是「每个资源试一次 VIEW」，
     * 塌缩后若因为「链上没有 VIEW 授权行」而不试 VIEW，
     * 那些靠 {@code default_effect=ALLOW} 回落放行的资源会从清单里凭空消失。
     *
     * <p>上界是「链上授权行出现过的动作」而不是全部 12 个动作：
     * 实测绝大多数资源只有 1~2 个动作，只有带 {@code *} 的才会展开成 12 个。
     * 全量试的话是 1022 资源 × 12 动作 ≈ 12000 次引擎判定，而这条路径每次登录都要走
     */
    private static Set<String> collapseCandidates(List<GrantDO> grants) {
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(GrantActionEnum.VIEW.getCode());
        for (GrantDO g : grants) {
            if (GrantActionEnum.WILDCARD.getCode().equals(g.getAction())) {
                candidates.addAll(CONCRETE_ACTIONS);
            } else if (g.getAction() != null) {
                candidates.add(g.getAction());
            }
        }
        return candidates;
    }

    /**
     * 运行时的反向展开表：(身份, 动作) → 塌缩前的原始权限串
     *
     * <p>🔴 源只有 {@code system_menu.permission} 一个。计划器用的是
     * 「资源行 ∪ 菜单表」双源，但它跑在迁移<b>之前</b>；运行时塌缩行已经被软删，
     * 第一个源没了。本次迁移<b>不动</b>导航表，所以塌缩前的原始串全都还在菜单表里。
     *
     * <p>实测这一个源就够：建出 513 个键，覆盖迁移后全部 510 个 (身份, 动作) 目标键，
     * <b>差集为空</b>（勘察脚本 {@code _scratch/s2a_expansion.py}）。
     * 差集非空的话表现是「某一类按钮全没了」，所以这个数要在每次迁移前重新量一遍，
     * 不能当常量相信
     */
    private Map<String, Set<String>> runtimeExpansionTable() {
        Set<String> permissions = new LinkedHashSet<>();
        for (MenuDO menu : menuMapper.selectList()) {
            if (menu.getPermission() != null && !menu.getPermission().isEmpty()) {
                permissions.add(menu.getPermission());
            }
        }
        return resolver.buildExpansionTable(permissions);
    }

    /**
     * 资源链进程级缓存：key = permission 串，value = 该权限点的祖先链（含自身，越靠前越具体）
     *
     * <h3>为什么用进程内缓存而不是 Redis</h3>
     *
     * 资源树在两次部署之间是完全静态的 —— 发布一张表单或一个菜单才会改变。
     * Redis 多了一次网络往返；进程内 {@link ConcurrentHashMap} 是纳秒级。
     *
     * <h3>⚠️ 缓存失效的唯一触发点</h3>
     *
     * 表单发布、菜单新增/删除、资源迁移 —— 这些都会改变资源树，必须调
     * {@link #evictResourceChainCache()} 清掉这个缓存；否则旧的祖先链会一直生效，
     * 新配的授权对已经在线的应用实例不生效（重启才生效）。
     * 这是一个比 Redis 更隐蔽的失效风险：Redis key 的 TTL 到了自然失效，
     * 这里不会。
     *
     * <p>🔴 全部写路径收口在 {@code ResourceServiceImpl} 的 5 个写方法里统一驱逐
     *（create / update / delete / delete-list / syncFromMenu，见其 {@code evictChainCache}）：
     * 表单发布（{@code DatasetResourceServiceImpl}）、菜单 CRUD（{@code MenuServiceImpl}）、
     * BPM 部署钩子（{@code ProcessResourceApiImpl#registerProcess}）建资源行走的都是这 5 个方法。
     * <b>曾经这里注释写着「各自 Service 里调用」，但全仓库一个调用方都没有</b> ——
     * 实测后果：BPM 发布时部署钩子在 registerProcess 之前若按新 key 判过一次权限，
     * 空链入缓存且永不失效，该流程对所有人「谁都发不了」，只有重启后端才恢复。
     * 新增任何直接写 {@code resourceMapper} 的代码都必须补一次驱逐。
     *
     * 压测前：每次链查询 = 1 次批量 path IN + N 次祖先路径 = ~100ms（idx 缺失导致全扫）
     * 压测后（缓存命中）：~0ms
     */
    private final ConcurrentHashMap<String, List<List<ResourceDO>>> resourceChainCache =
            new ConcurrentHashMap<>(1024);

    /**
     * 清空资源链进程级缓存
     *
     * ⚠️ 必须在资源树发生变化时调用 —— 见 {@link #resourceChainCache} 的注释。
     * 从 Spring 上下文外部（定时任务、部署钩子）也可以直接调
     */
    public void evictResourceChainCache() {
        resourceChainCache.clear();
        log.info("[evictResourceChainCache][资源链缓存已清空，下次访问将重建]");
    }

    /**
     * 判断用户是否对指定 permission 有指定 action 的权限
     *
     * 对应 @ss.hasPermission 场景，action 固定是 VIEW（按钮层默认 VIEW 代表「能点」）。
     * S2b 接入上下文后，contextId 从 RequestAttributes 里取；S2a 阶段传 null
     */
    @DataPermission(enable = false) // 关掉数据权限，否则「算权限」本身又要先算一次权限
    public boolean hasPermission(Long userId, String permission, Long contextId) {
        return hasPermission(userId, permission, contextId, GrantActionEnum.VIEW.getCode());
    }

    /**
     * 判断用户是否对指定 permission 有指定 action 的权限
     *
     * <h3>为什么需要显式带 action</h3>
     *
     * 菜单/按钮只有「能不能点」一种语义，所以上面那个重载把 action 固定成 VIEW。
     * 但流程资源有四种互不相同的动作（v9 §7.2）：
     * {@code START} 发起 / {@code DESIGN} 设计 / {@code MANAGE} 管理 /
     * {@code VIEW_ALL} 看全部实例 —— 拿 VIEW 一把抓的话，
     * 「能看到这个流程」就等于「能发起、能改设计」，等于没有区分。
     *
     * <h3>🔴 塌缩开关打开时，入参 action 可能被<b>覆盖</b></h3>
     *
     * 串里的动词比调用方传的动作更权威 —— 塌缩的语义本身就是
     * 「把编码在身份串里的动词挪进动作维度」（见 {@link #lookupOf}）。
     *
     * <p>实践中不会冲突：唯一传非 VIEW 动作的调用方是
     * {@code PermissionApiImpl#hasResourceAction}（BPM 流程族，串形如
     * {@code bpm:process:leave}），末段是业务专有动词、属档 C，
     * {@code isCollapsed()} 恒为 false，压根进不到覆盖那一支。
     * 这句话是<b>实测调用面</b>得出的，不是设计保证 ——
     * 将来若有人拿一个塌缩串去调本重载并传非 VIEW 动作，覆盖会发生，且不会有警告
     *
     * @param action 动作码，见 {@code GrantActionEnum}
     */
    @DataPermission(enable = false) // 算权限这件事本身不该再被权限过滤一遍
    public boolean hasPermission(Long userId, String permission, Long contextId, String action) {
        // 1. 主体闭包
        Set<SubjectKey> subjects = subjectClosureService.getSubjectKeysByUserId(userId);
        if (CollUtil.isEmpty(subjects)) {
            return false;
        }

        // 2. 塌缩开关打开时，把「资源身份:动词」翻译成「资源身份 + 动作」
        Lookup lookup = lookupOf(permission, action);

        // 3. 资源链。⚠️ 同一个 permission 可能对应【多行】资源，必须每行各算一条链
        List<List<ResourceDO>> chains = chainsOf(lookup.permission());
        if (chains.isEmpty()) {
            // 资源表里没有该 permission，严格模式下视为没有权限
            return false;
        }

        // 4. 查授权行：一次把所有链上的资源 id 查回来，避免 N 条链 N 次查询。
        //    引擎内部会按各自链上的 id 过滤，互不串味
        Set<Long> chainIds = new LinkedHashSet<>();
        for (List<ResourceDO> chain : chains) {
            for (ResourceDO r : chain) {
                chainIds.add(r.getId());
            }
        }
        List<GrantDO> grants = grantMapper.selectListByResourceIds(chainIds);

        // 5. 决策：任意一条链判定通过即通过
        //
        // <h3>🔴 为什么不能只看 found.get(0)</h3>
        //
        // 🔴 同一权限串对应多行资源的情形一直存在，但<b>条数一直在降</b>：
        // 这条注释最早写「76 个」，阶段 0 复测是 4 个串 / 8 行，塌缩落库后只剩 2 个串
        // （{@code agent:usage:query}、{@code infra:job:update}）；含软删行则始终有 12 个串。
        // 76 是含软删、且早于几轮清理的历史口径。
        //
        // ⚠️ 所以别把任何一个数字当常量用：这里的代码必须按「N 行」写，
        //    而不能因为「反正只剩两行」就退化成 get(0) —— 同一权限挂在多个菜单上
        //    是上游的常见做法，新增一个就会把缺陷带回来。
        //
        // 原实现只用 {@code get(0)} 建链，而清单侧 {@link #listAllowedPermissions}
        // 是<b>遍历全部资源、命中即收</b> —— 两者读的不是同一行，于是：
        // <b>把授权配在非 get(0) 的那行上，前端把按钮显示出来了，点下去后端 403</b>。
        // 已用实验复现（授权只配在 id=1368 上 → 清单里有该权限、接口返回
        // 「没有该操作权限」）。这正是本项目反复出现的那类「配了、看着生效了、就是不生效」。
        //
        // 口径与清单侧对齐：<b>任意一行资源判定通过就算通过</b>（OR），
        // 而不是把多条链的授权混在一起算 —— 混算会让某一行上的 DENY 误伤其它行
        for (List<ResourceDO> chain : chains) {
            if (engine.isAllowed(subjects, chain, grants, lookup.action(), contextId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否存在针对指定主体的<b>显式 DENY</b> 授权
     *
     * <h3>与 {@link #hasPermission} 的区别：不看 default_effect</h3>
     *
     * {@code hasPermission} 回答「最终允不允许」，没有命中授权时会回落到资源的
     * {@code default_effect}。而有些控制点的基线是<b>放行</b> ——
     * 典型是子流程调起：现存的父子编排必须继续能跑，只有管理员明确禁止时才拦。
     *
     * 那种场景下问「最终允不允许」会得到错误答案：流程资源的 default_effect 是 DENY
     * （S5d §2.2 的设计），一问就全被拦住。所以这里<b>只看有没有人明确说「不」</b>。
     *
     * ⚠️ 只查资源自身，不上溯祖先链。DENY 是精确的禁止令，
     * 从分类继承一条「禁止调用」下来会误伤同分类的其它流程
     *
     * <h3>🔴 「资源行不存在」返回 false 是<b>本方法的契约</b>，不是 fail-open 缺陷</h3>
     *
     * 阶段 2 前置勘察把它标成了「与『没有身份 ⇒ 拒绝』相矛盾」，这个判断是错的，
     * 别照着改。理由：本方法回答的是「有没有人明确说不」，不是「允不允许」——
     * 资源都不存在，就确实没有人说过不，false 是唯一诚实的答案。
     * 把它改成 true 会立刻打死全部现存父子编排：唯一的生产调用方
     * {@code BpmSubProcessCallChecker} 的基线就是放行（流程资源 default_effect=DENY，
     * 问「允不允许」会全被拦住，所以它才只问 DENY），
     * 而绝大多数被调流程压根没有登记成资源行。
     *
     * <p>「没有身份 ⇒ 拒绝」那条不变量管的是<b>放行判定</b>
     * （{@code AgentSecurityGateway}、{@link #hasPermission}），
     * 两者的默认方向本来就相反。混淆它们才是真缺陷
     */
    @DataPermission(enable = false)
    public boolean hasExplicitDeny(String subjectType, String subjectCode,
                                   String permission, String action) {
        Lookup lookup = lookupOf(permission, action);
        List<ResourceDO> resources = resourceMapper.selectListByPermission(lookup.permission());
        if (resources.isEmpty()) {
            return false; // 资源都没有，谈不上被禁止
        }
        Set<Long> resourceIds = new LinkedHashSet<>();
        resources.forEach(r -> resourceIds.add(r.getId()));
        for (GrantDO grant : grantMapper.selectListByResourceIds(resourceIds)) {
            if (!"DENY".equals(grant.getEffect())) {
                continue;
            }
            if (!subjectType.equals(grant.getSubjectType())) {
                continue;
            }
            if (subjectCode != null && grant.getSubjectCode() != null
                    && !subjectCode.equals(grant.getSubjectCode())) {
                continue;
            }
            if (lookup.action().equals(grant.getAction())
                    || GrantActionEnum.WILDCARD.getCode().equals(grant.getAction())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 批量解析一批权限标识的字段模式
     *
     * <h3>两步判定，不能合成一步</h3>
     *
     * 资源的 {@code default_effect} 是 ALLOW/DENY，而字段模式是
     * EDITABLE/READONLY/HIDDEN —— <b>两套语义</b>，必须分开算：
     *
     * <pre>
     *   第一步 能不能看见？ engine.isAllowed(..., VIEW)
     *                      资源行不存在 → EDITABLE（放行）
     *                      判定为 DENY  → HIDDEN，到此为止
     *   第二步 能不能改？   engine.resolveFieldMode(...) 的三层合并
     * </pre>
     *
     * <h3>为什么「查不到资源行」是放行而不是拒绝</h3>
     *
     * v9 §5.2 的资源按需创建：绝大多数字段本来就不落 {@code sys_resource} 行，
     * 只有被真正配过权限的才落。写反的表现是「所有表单的所有字段全部消失」。
     *
     * <h3>批量的意义</h3>
     *
     * 主体闭包查一次、授权行查一次、同一张表单各字段的祖先链只算一次。
     * 逐个字段走完整链路的话，一张几十字段的表单每开一次列表就是几十轮查询。
     *
     * @param permissions 列/字段资源的权限标识
     * @return 权限标识 → 字段模式，键与入参一一对应
     */
    @DataPermission(enable = false)
    public Map<String, FieldModeEnum> resolveFieldModes(Long userId, Collection<String> permissions,
                                                        Long contextId) {
        Map<String, FieldModeEnum> result = new LinkedHashMap<>();
        if (CollUtil.isEmpty(permissions)) {
            return result;
        }
        Set<SubjectKey> subjects = subjectClosureService.getSubjectKeysByUserId(userId);

        // 一次查回这批权限标识的资源行。没查到的那些就是「没配过权限」，直接放行
        Map<String, ResourceDO> byPermission = new HashMap<>();
        for (ResourceDO resource : resourceMapper.selectListByPermissions(permissions)) {
            byPermission.putIfAbsent(resource.getPermission(), resource);
        }

        // 祖先链按路径缓存：同一张表单的几十个字段挂在同一个 DATASET 节点下，
        // 祖先完全相同，逐个查等于把同样的几次查询重复几十遍
        Map<String, List<ResourceDO>> ancestorCache = new HashMap<>();
        Map<String, List<ResourceDO>> chains = new LinkedHashMap<>();
        Set<Long> chainIds = new LinkedHashSet<>();
        for (String permission : permissions) {
            ResourceDO resource = byPermission.get(permission);
            if (resource == null) {
                continue;
            }
            List<ResourceDO> chain = new ArrayList<>();
            chain.add(resource);
            chain.addAll(ancestorCache.computeIfAbsent(parentPathOf(resource), this::buildAncestors));
            chains.put(permission, chain);
            for (ResourceDO node : chain) {
                chainIds.add(node.getId());
            }
        }
        // 整批共用一次授权查询
        List<GrantDO> grants = chainIds.isEmpty()
                ? List.of() : grantMapper.selectListByResourceIds(chainIds);

        for (String permission : permissions) {
            List<ResourceDO> chain = chains.get(permission);
            if (chain == null) {
                // 没有资源行 = 没配过权限 = 放行
                result.put(permission, FieldModeEnum.EDITABLE);
                continue;
            }
            // 第一步：看得见吗
            if (!engine.isAllowed(subjects, chain, grants, GrantActionEnum.VIEW.getCode(), contextId)) {
                result.put(permission, FieldModeEnum.HIDDEN);
                continue;
            }
            // 第二步：改得了吗
            FieldModeEnum mode = engine.resolveFieldMode(subjects, chain, grants, contextId,
                    FieldModeEnum.EDITABLE);
            result.put(permission, mode == null ? FieldModeEnum.EDITABLE : mode);
        }
        return result;
    }

    /**
     * 取资源的父路径。{@code /root/a/b/perm/} → {@code /root/a/b/}
     */
    private String parentPathOf(ResourceDO resource) {
        String path = resource.getPath();
        if (path == null || path.isEmpty()) {
            return "";
        }
        int lastSlash = path.lastIndexOf('/', path.length() - 2);
        return lastSlash < 0 ? "" : path.substring(0, lastSlash + 1);
    }

    /**
     * 按物化路径逐级上溯祖先（不含自身，越靠前越具体）
     *
     * <h3>⚠️ 这里原先有一个错位一级的缺陷，改法要看明白再动</h3>
     *
     * 物化路径的格式是 {@code /root/{层1}/…/{本节点permission}/} ——
     * <b>最后一段就是节点自己的 permission</b>。所以把 {@code /root/a/b/p/}
     * 截掉尾巴得到的 {@code /root/a/b/}，已经是父节点 P 的完整 path 了。
     *
     * 原先的判据是 {@code candidate.path == 截断后的path + candidate.permission + "/"}，
     * 代入 P 得到 {@code /root/a/b/b/}，反而匹配不上 P；真正匹配上的是
     * {@code /root/a/b/} 的<b>任意直接子节点</b> —— 也就是本节点自己和它的兄弟。
     *
     * 造成两个后果：
     * <ol>
     *   <li>祖先链整体错位一级，<b>最顶层的祖先永远进不了链</b>，
     *       挂在它上面的授权不被继承</li>
     *   <li>链里会混进一个顺序随意的<b>兄弟资源</b> ——
     *       兄弟字段上的 DENY 会误伤到本字段</li>
     * </ol>
     *
     * 正确判据就是<b>路径精确相等</b>：祖先的 path 本来就是本节点 path 的前缀。
     */
    /**
     * 一次查出所有祖先，而不是每层各查一次（S9 性能优化）
     *
     * <h3>🔴 旧实现 vs 新实现</h3>
     *
     * 旧实现循环剥去物化路径最后一段，每次发一条 {@code SELECT ... WHERE path = ?}。
     * 深度 N 的资源就是 N 次 DB 往返 —— 压测在 5 层资源上量到 494ms，超标 247 倍。
     *
     * 物化路径本身就把所有祖先的路径都编码进去了：
     * {@code /root/A/B/C/D/} 的所有祖先路径是
     * {@code /root/A/}, {@code /root/A/B/}, {@code /root/A/B/C/}。
     * 在 Java 里提取这些前缀是零 I/O 操作，然后一条 {@code IN} 查询取全部行 ——
     * N 次 round-trip → 1 次。
     *
     * 深度 5 层时从 ~500ms 降到 ~1ms；更深的层数同理，不随深度增长
     */
    private List<ResourceDO> buildAncestors(String parentPath) {
        if (parentPath == null || parentPath.length() <= ResourceDO.PATH_ROOT_PREFIX.length()) {
            return List.of();
        }
        // 从物化路径里提取每一个祖先的 path 前缀。
        // e.g. /root/A/B/C/ → [/root/A/, /root/A/B/]（C/ 是 parentPath 本身，不是它的祖先）
        List<String> paths = new ArrayList<>();
        String path = parentPath;
        while (path.length() > ResourceDO.PATH_ROOT_PREFIX.length()) {
            paths.add(path);
            int lastSlash = path.lastIndexOf('/', path.length() - 2);
            if (lastSlash < 0) {
                break;
            }
            path = path.substring(0, lastSlash + 1);
        }
        if (paths.isEmpty()) {
            return List.of();
        }
        // 一次查出全部行，然后按深度降序排（越靠前越具体，与旧行为一致）
        List<ResourceDO> rows = resourceMapper.selectListByPaths(paths);
        rows.sort((a, b) -> {
            int da = a.getDepth() == null ? 0 : a.getDepth();
            int db = b.getDepth() == null ? 0 : b.getDepth();
            return db - da; // 降序：深度大的在前
        });
        return rows;
    }

    /**
     * 按权限标识查它的资源祖先链（含自身，越靠前越具体）
     *
     * <h3>为什么把它开放出来</h3>
     *
     * 「这个权限点继承了哪些祖先的授权」是排查权限问题时的第一个问题，
     * 而祖先链是沿物化路径上溯算出来的、在库里看不到。S8d 的权限诊断页要展示它，
     * S9 的压测要单独测它的耗时（v9 §8.2 给的门槛是 &lt; 2ms）。
     *
     * ⚠️ 这是<b>只读的诊断入口</b>，判定逻辑不要改走这里 ——
     * 判定路径上拿链是和「查授权」一起批量做的（见 {@link #resolveFieldModes}），
     * 逐个调本方法会把一次查询放大成 N 次。
     *
     * <p>🔴 塌缩开关打开后，本方法返回的是<b>身份行</b>的链：
     * 问 {@code system:user:create} 会得到 {@code system:user} 的祖先链。
     * 这是刻意的 —— 诊断页要回答的是「鉴权到底看了哪些行」，
     * 而鉴权看的就是身份行。返回一条库里已经软删的行的链，只会把人引到错误的地方去查。
     *
     * @return 祖先链；资源不存在时返回空列表
     */
    @DataPermission(enable = false)
    public List<ResourceDO> resolveResourceChain(String permission) {
        // 走进程级缓存 —— 与 hasPermission 命中同一份（缓存键也同一个：解析后的身份串），
        // 热点权限点只查一次 DB
        //
        // ⚠️ 动作位只是为了让 Lookup 的两位成对产出，诊断入口不读它。
        //    填 VIEW 与 hasPermission 那个 3 参重载的默认值一致 ——
        //    免得将来有人拿这个 Lookup 直接去判定，得到与鉴权不同的动作
        List<List<ResourceDO>> chains = chainsOf(
                lookupOf(permission, GrantActionEnum.VIEW.getCode()).permission());
        // ⚠️ 一个 permission 可能对应多行资源（本库当前 2 个串如此，口径见 hasPermission 里那条）。
        // 诊断页的返回类型是单条链，这里给第一条；判定侧 hasPermission 是逐条都算的。
        // 排查「授权配了为什么不生效」时要留意：你配的那行未必是这里显示的这条链
        return chains.isEmpty() ? List.of() : chains.get(0);
    }

    /**
     * 列出用户被允许的菜单编号（导航树用）
     *
     * <h3>为什么它和 {@link #listAllowedPermissions} 分开</h3>
     *
     * 两者判定口径完全相同，但返回的东西不同：那个返回权限串（给 {@code v-hasPermi}），
     * 这个返回 {@code system_menu.id}（给导航树渲染）。
     * 资源行上的 {@code menu_id} 是迁移期留下的映射，这里是它<b>唯一正当的用途</b>
     * —— 判定本身绝不读它（见 {@link ResourceDO#getMenuId()} 的注释）。
     *
     * <h3>⚠️ 必须把祖先菜单一起带上</h3>
     *
     * 只返回命中的叶子菜单的话，前端 {@code buildMenuTree} 找不到父节点，
     * 那些菜单会<b>整个消失</b>（挂在一个不存在的 parentId 上）。
     * 表现是「明明授权了，菜单就是不出现」，而且越是配得精确越容易踩到。
     *
     * @param userId 用户编号
     * @return 允许的菜单编号集合（含为渲染树而补齐的祖先）
     */
    @DataPermission(enable = false)
    @Cacheable(value = RedisKeyConstants.GRANT_USER_MENU_IDS + "#30s", key = "#userId")
    @Transactional(readOnly = true)
    public Set<Long> listAllowedMenuIds(Long userId) {
        // ⚠️ setTransactionAware(true) 在 RedisCacheManager 上导致 @Cacheable 写入被推迟到
        // 事务提交回调。加 @Transactional(readOnly=true) 创建真实只读事务，事务提交时
        // 缓存正常写入。@DataPermission 切面通过方法体内的 executeIgnore() 处理。
        return DataPermissionUtils.executeIgnore(() -> listAllowedMenuIdsInternal(userId));
    }

    private Set<Long> listAllowedMenuIdsInternal(Long userId) {
        // 超管：返回全部菜单。与旧路径同口径
        // （PermissionServiceImpl#getRoleMenuListByRoleId 对超管也是 getMenuList 全量）
        if (isSuperAdmin(userId)) {
            Set<Long> all = new LinkedHashSet<>();
            for (MenuDO menu : menuMapper.selectList()) {
                all.add(menu.getId());
            }
            return all;
        }
        Set<SubjectKey> subjects = subjectClosureService.getSubjectKeysByUserId(userId);
        if (CollUtil.isEmpty(subjects)) {
            return Set.of();
        }

        List<ResourceDO> all = resourceMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ResourceDO>()
                        .eq(ResourceDO::getStatus, CommonStatusEnum.ENABLE.getStatus()));
        Map<Long, ResourceDO> byId = new HashMap<>(all.size());
        for (ResourceDO r : all) {
            byId.put(r.getId(), r);
        }

        Map<Long, List<GrantDO>> grantsByResource = new HashMap<>();
        for (GrantDO g : grantMapper.selectListActiveBySubjects(subjects)) {
            grantsByResource.computeIfAbsent(g.getResourceId(), k -> new ArrayList<>()).add(g);
        }

        boolean collapse = collapseEnabled();
        Set<Long> menuIds = new LinkedHashSet<>();
        for (ResourceDO r : all) {
            if (r.getMenuId() == null) {
                continue; // 表单/流程这类资源没有对应菜单，本来就不进导航
            }
            List<ResourceDO> chain = buildChainFromMap(r, byId);
            List<GrantDO> grants = chainGrants(chain, grantsByResource);

            boolean hit;
            if (!collapse) {
                hit = engine.isAllowed(subjects, chain, grants, GrantActionEnum.VIEW.getCode(), null);
            } else {
                // 🔴 塌缩后是「任一动作放行就进导航」，不是「VIEW 放行才进」。
                //
                // 塌缩前这个集合是从 ~620 个动词行收上来的：角色只要在其中【任意一行】
                // 上被判放行（比如只有 system:user:create、没有 :query），
                // 那一行的 menu_id 就进集合，completeMenuAncestors 再沿 system_menu
                // 父链把「用户管理」这个页面补出来。
                // 塌缩后 620 行变成 ~117 个身份行，若仍只试 VIEW，
                // 这类「能写不能读」的角色会【整页从导航里消失】——
                // 页面还在、路由还能直达，只是左侧菜单不显示了，而且不报错
                hit = false;
                for (String action : collapseCandidates(grants)) {
                    if (engine.isAllowed(subjects, chain, grants, action, null)) {
                        hit = true;
                        break;
                    }
                }
            }
            if (hit) {
                menuIds.add(r.getMenuId());
                // 祖先资源对应的菜单一并带上，否则树断链
                for (ResourceDO ancestor : chain) {
                    if (ancestor.getMenuId() != null) {
                        menuIds.add(ancestor.getMenuId());
                    }
                }
            }
        }
        return completeMenuAncestors(menuIds);
    }

    /**
     * 沿 <b>system_menu 的父链</b> 补全祖先菜单
     *
     * <h3>🔴 不补的后果：导航栏整个是空的</h3>
     *
     * 上面那段「祖先资源对应的菜单一并带上」补的是<b>资源链</b>的祖先，但这远远不够：
     *
     * <ul>
     *   <li>{@code migrateFromMenu} 的判据是「有没有 permission」，全库有 <b>466 个菜单行没有
     *       permission</b>（含<b>全部 97 个目录</b>，以及「角色管理」这类 permission 为空的菜单），
     *       它们<b>永远不会有对应的 sys_resource 行</b>，也就永远不可能出现在上面的循环里</li>
     *   <li>迁移时资源全部挂在根下（{@code parentId = ID_ROOT}），实测 1851/1979 个资源在深度 1
     *       —— <b>资源链上压根没有祖先</b>，那个 for 循环绝大多数时候只有自身一个元素</li>
     * </ul>
     *
     * 两条叠加的结果：返回的菜单 id 全是按钮层和带 permission 的菜单层，
     * 它们的 {@code parentId} 指向的目录一个都不在集合里 →
     * 前端 {@code buildMenuTree} 把这些「挂在不存在的父节点上」的行<b>整体丢弃</b> →
     * <b>菜单树返回空数组，登录后导航栏什么都没有</b>，且全程 200、无任何报错。
     *
     * 实测：修复前 {@code permissions} 有 733 条而 {@code menus} 是 {@code []}。
     *
     * 所以这里必须沿 <b>菜单自己的父链</b>（不是资源的）逐级上溯。目录只是容器、本身不承载权限，
     * 子节点可见就必须把它渲染出来，否则子节点等于不存在。
     */
    private Set<Long> completeMenuAncestors(Set<Long> menuIds) {
        if (CollUtil.isEmpty(menuIds)) {
            return menuIds;
        }
        // 一次拉回全部菜单的 id → parentId 映射。菜单量级两千出头，且本方法结果有 30s 缓存
        Map<Long, Long> parentByMenuId = new HashMap<>();
        for (MenuDO menu : menuMapper.selectList()) {
            parentByMenuId.put(menu.getId(), menu.getParentId());
        }
        Set<Long> result = new LinkedHashSet<>(menuIds);
        for (Long menuId : menuIds) {
            Long parent = parentByMenuId.get(menuId);
            int guard = 0; // 防御父子成环，成环时会在这里死循环把登录接口拖死
            while (parent != null && parent != 0L && !result.contains(parent) && guard++ < 32) {
                result.add(parent);
                parent = parentByMenuId.get(parent);
            }
        }
        return result;
    }

    /**
     * 列出用户被允许的<b>全部</b>权限标识（ACTION 类型资源）
     *
     * <h3>为什么必须有这个方法</h3>
     *
     * {@code /system/auth/get-permission-info} 要返回前端一份权限串清单，
     * 前端的 {@code v-hasPermi} 靠它决定按钮显不显示。
     * 原实现取的是 {@code system_role_menu}（旧表），而鉴权早已切到 {@code sys_grant}
     * （{@code grant-enabled: true}）—— 两边一旦分叉，就会出现本项目最典型的那种缺陷：
     * <b>管理员在授权页配好了、保存成功了、后端接口也确实放行，但界面上那个按钮从来不出现</b>。
     * 而且完全不报错，只能靠人工比对两张表才能发现。
     *
     * 尤其是 S8a 的授权配置页 —— 它配出来的东西<b>只写 sys_grant</b>，
     * 不写 system_role_menu。不补这个方法的话，用它配的权限对前端一律无效。
     *
     * <h3>为什么不是循环调 {@link #hasPermission}</h3>
     *
     * 那样每个权限点一次 {@code selectListByResourceIds}，1800+ 个权限点就是 1800+ 次查询。
     * 这里改成：一次查全部资源 + 一次查全部相关授权，其余在内存里算，
     * 但<b>判定本身仍然交给 {@link GrantDecisionEngine#isAllowed}</b> ——
     * 另写一份判定必然与鉴权路径漂移，而漂移的表现是「按钮显示了，点下去报无权限」
     * （或者更糟：按钮藏了，其实有权限）。
     *
     * @param userId 用户编号
     * @return 允许的权限标识集合；用户没有任何主体时返回空集
     */
    @DataPermission(enable = false)
    @Cacheable(value = RedisKeyConstants.GRANT_USER_PERMISSIONS + "#30s", key = "#userId")
    @Transactional(readOnly = true)
    public Set<String> listAllowedPermissions(Long userId) {
        // ⚠️ setTransactionAware(true) 在 RedisCacheManager 上导致 @Cacheable 写入被推迟到
        // 事务提交回调。加 @Transactional(readOnly=true) 创建真实只读事务，事务提交时
        // 缓存正常写入。@DataPermission 通过 executeIgnore() 在方法体内处理。
        return DataPermissionUtils.executeIgnore(() -> listAllowedPermissionsInternal(userId));
    }

    private Set<String> listAllowedPermissionsInternal(Long userId) {
        // 超管：返回全部权限标识。判据与 @ss.hasPermission 一致，两边不能分叉
        //
        // ⚠️ 取的是【菜单表】里的全部权限串，而不是 sys_resource 里的 ——
        // 前端 v-hasPermi 比的就是菜单上配的那个串；而 sys_resource 里还有
        // 列/字段（:col: / :fld:）这类不是按钮权限的资源，放进清单没有意义
        if (isSuperAdmin(userId)) {
            Set<String> all = new LinkedHashSet<>();
            for (MenuDO menu : menuMapper.selectList()) {
                if (menu.getPermission() != null && !menu.getPermission().isEmpty()) {
                    all.add(menu.getPermission());
                }
            }
            return all;
        }
        Set<SubjectKey> subjects = subjectClosureService.getSubjectKeysByUserId(userId);
        if (CollUtil.isEmpty(subjects)) {
            return Set.of();
        }

        // 1. 一次拉回全部启用资源，按 id 建索引，供上溯祖先用（避免逐个 selectListByPath）
        List<ResourceDO> all = resourceMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ResourceDO>()
                        .eq(ResourceDO::getStatus, CommonStatusEnum.ENABLE.getStatus()));
        Map<Long, ResourceDO> byId = new HashMap<>(all.size());
        for (ResourceDO r : all) {
            byId.put(r.getId(), r);
        }

        // 2. 一次拉回【这个用户】的全部有效授权，按资源分组
        //
        // ⚠️ 按主体查，不要按 byId.keySet() 的资源 id 批量查 ——
        // 后者会把 1900+ 个 id 塞进 IN 列表，MySQL 直接放弃索引转全表扫描，
        // 实测单条 36 秒（见 GrantMapper#selectListActiveBySubjects 的注释）
        Map<Long, List<GrantDO>> grantsByResource = new HashMap<>();
        for (GrantDO g : grantMapper.selectListActiveBySubjects(subjects)) {
            grantsByResource.computeIfAbsent(g.getResourceId(), k -> new ArrayList<>()).add(g);
        }

        // 3. 塌缩开关打开时先建反向展开表（见 runtimeExpansionTable 的注释）。
        //    放在超管短路之后：那条路直接吐菜单表，压根用不到这张表，
        //    而建它要多查一次导航表（实测 872 行）
        boolean collapse = collapseEnabled();
        Map<String, Set<String>> expansion = collapse ? runtimeExpansionTable() : Map.of();

        // 🔴 R11 的镜像（与 lookupOf 那条同源、同一件事的清单版）：
        // 被整族拦下的身份行下面<b>还挂着活的成员行</b>，所以「哪些身份仍然有活成员」
        // 直接从第 1 步已经捞回来的 all 里数一遍就行，不用查库、也不用读配置。
        //
        // 这种身份行必须走【不塌缩】那一支。走塌缩那一支会出什么事：
        // 它自己的 VIEW 授权（比如 system:user 这个 DATASET 行上 ROLE 3 那条）
        // 会被当成「:query 塌缩上来的 VIEW」，于是按展开表还原出 system:user:query、
        // system:user:list —— 而 ROLE 3 在那两行上<b>一条授权都没有</b>（实测），
        // 结果是清单里凭空多出两个按钮权限。
        // 拦下的族一条授权都没搬，走原来那一支就是逐字节等价
        Set<String> identitiesWithLiveMembers = new LinkedHashSet<>();
        if (collapse) {
            for (ResourceDO r : all) {
                if (r.getPermission() == null || r.getPermission().isEmpty()) {
                    continue;
                }
                PermissionStringResolver.Resolution res = resolver.resolve(r.getPermission());
                if (res.isCollapsed()) {
                    identitiesWithLiveMembers.add(res.getResourceKey());
                }
            }
        }

        // 4. 逐个权限点判定，判定逻辑仍走同一个引擎
        Set<String> allowed = new LinkedHashSet<>();
        for (ResourceDO r : all) {
            if (r.getPermission() == null || r.getPermission().isEmpty()) {
                continue; // 目录层没有权限标识，不进清单
            }
            List<ResourceDO> chain = buildChainFromMap(r, byId);
            List<GrantDO> grants = chainGrants(chain, grantsByResource);

            if (!collapse || isBlockedFamily(r.getPermission(), identitiesWithLiveMembers)) {
                if (engine.isAllowed(subjects, chain, grants, GrantActionEnum.VIEW.getCode(), null)) {
                    allowed.add(r.getPermission());
                }
                continue;
            }

            // 塌缩后本行是【资源身份】，动词在动作维度上。逐个候选动作判一遍，
            // 通过的按展开表还原成塌缩前的原始串 —— 不还原的话前端 v-hasPermi
            // 与后端 @PreAuthorize 比的都是 :create/:query 这种串，清单里只有身份串，
            // 结果是【全部按钮消失、全部接口 403】，且全程不报错
            for (String action : collapseCandidates(grants)) {
                if (!engine.isAllowed(subjects, chain, grants, action, null)) {
                    continue;
                }
                Set<String> expanded = expansion.get(
                        PermissionStringResolver.key(r.getPermission(), action));
                if (expanded != null) {
                    allowed.addAll(expanded);
                }
                if (GrantActionEnum.VIEW.getCode().equals(action)) {
                    // 身份串自己也进清单：它与开关关闭时那个 add 是同一个东西，
                    // 留着才能让「关闭态 → 打开态」的清单 diff 只剩真正变化的部分
                    allowed.add(r.getPermission());
                }
            }
        }
        return allowed;
    }

    /**
     * 这个权限串属不属于「被 R11 整族拦下」的那一族 —— 身份行自己，或它下面还活着的成员行
     *
     * <p>成员行也要一起认。它塌缩后本该消失，拦下之后它还在库里，
     * 而塌缩那一支会拿<b>它的串</b>当身份去查展开表 —— 展开表的键是解析后的身份串，
     * 所以查不到；除了 VIEW 那一路还能靠「身份串自己也进清单」那一行补回来，
     * 其余动作一条都还原不出来。两边都走不塌缩那一支，才与开关关闭时逐字节一致
     *
     * @param identitiesWithLiveMembers 仍然存在可塌缩成员行的身份串集合
     */
    private boolean isBlockedFamily(String permission, Set<String> identitiesWithLiveMembers) {
        PermissionStringResolver.Resolution res = resolver.resolve(permission);
        return identitiesWithLiveMembers.contains(
                res.isCollapsed() ? res.getResourceKey() : permission);
    }

    // ========== 生效权限树（角色权限地图，只读） ==========

    /**
     * 全部具体动作码（不含通配符 {@code *}），顺序即枚举声明顺序
     *
     * 用途有两个：把一条 {@code action='*'} 的授权展开成候选动作集，
     * 以及判断「是不是全部动作都放行了」从而折叠回一个 {@code *}
     */
    private static final List<String> CONCRETE_ACTIONS = Arrays.stream(GrantActionEnum.values())
            .filter(action -> !action.isWildcard())
            .map(GrantActionEnum::getCode)
            .toList();

    private static final Comparator<EffectiveResourceNodeVO> NODE_ORDER =
            Comparator.comparing(EffectiveResourceNodeVO::getSort,
                            Comparator.nullsLast(Comparator.<Integer>naturalOrder()))
                    .thenComparing(EffectiveResourceNodeVO::getId,
                            Comparator.nullsLast(Comparator.<Long>naturalOrder()));

    /**
     * 求某个主体的<b>生效权限树</b>（角色权限地图）
     *
     * 回答的不是「台账上配了哪些行」，而是「把祖先继承、DENY 覆盖 ALLOW、过期失效、
     * 默认放行这四层语义算清楚之后，这个主体到底拥有哪些权限，每一项又是从哪来的」。
     *
     * <h3>🔴 为什么判定必须由后端算，前端一行判定逻辑都不写</h3>
     *
     * 本类的 {@link #listAllowedPermissions} 注释已经写明「另写一份判定必然与鉴权路径漂移」，
     * 而这套体系<b>已经漂了两处</b>：{@link #buildResourceChain}（沿 path 上溯、不过滤 status）
     * 与 {@link #buildChainFromMap}（沿 parentId 上溯、只含启用资源）在「停用祖先」场景分叉；
     * {@code GrantAdminController#listByResource} 与 {@code #toRespVOs} 两处 expired 口径也不一致。
     * 往 TypeScript 里再抄第三份判定，等于主动制造第三处漂移 ——
     * 而这是一张<b>权限地图</b>：它说「拥有」而引擎判 DENY，比没有这张图更糟。
     *
     * 机械保证在 {@link GrantDecisionEngine#explain}：鉴权走的 {@code isAllowed} 已退化成
     * {@code explain(...).allowed()}，所以「地图上的来源」与「鉴权的判决」出自同一段代码，
     * 构造上不可能不一致。
     *
     * 另一条硬理由：主体闭包前端根本拿不到 —— 全员主体（实测 {@code ALL/ALLOW} 9 行）、
     * 角色之间的继承（{@code sys_subject_member}）、POST/DEPT 开关都在后端。
     *
     * <h3>口径（三条都要向前端交代清楚，否则一定被当成 bug 报上来）</h3>
     *
     * <ol>
     *   <li><b>只认启用资源</b>：走 {@link #buildChainFromMap}，与 {@link #listAllowedPermissions} /
     *       {@link #listAllowedMenuIds} 同口径；与诊断页 {@link #resolveResourceChain} 那条
     *       沿 path 上溯的路径<b>可能不同</b>（停用祖先场景）。这是刻意选择，不是疏漏。
     *       实测有 29 条授权挂在停用资源上、56 条挂在已软删资源上，它们都不会进树</li>
     *   <li><b>多租户</b>：{@code sys_grant} 分租户（{@code GrantDO extends TenantBaseDO}）而
     *       {@code sys_resource} 是全局表，所以<b>同一棵资源树在不同租户下剪枝结果完全不同</b>。
     *       实测租户 1 有 1360 行、121/122/162 各 165/142/135 行。这是正确行为</li>
     *   <li><b>角色维度，不是用户维度</b>：主体集合 = 该主体自身 + 它在主体图上的祖先 +
     *       （可选）全员主体，<b>不含</b> USER / POST / DEPT / GROUP / SYSTEM。
     *       所以这张图<b>少于</b>「拥有该角色的某个用户最终能做的」</li>
     * </ol>
     *
     * <h3>🔴 刻意不加 {@code @Cacheable}</h3>
     *
     * 现有 5 处驱逐点（{@code GrantAdminServiceImpl} 的 upsert / createList / update / deleteList，
     * 以及 {@code GrantAdminController#deleteGrant} —— 单条删除的驱逐在 Controller 层）
     * 都只驱逐 {@code GRANT_USER_PERMISSIONS} / {@code GRANT_USER_MENU_IDS}。
     * 新起一个 cache 名就得去这 5 处各加一条，<b>漏一处就是「保存成功了、智能查看还是旧的」</b>，
     * 而这恰好是本仓库最贵的那类缺陷。何况成本本来就低：3 次查询 + 内存 O(1002×4)，
     * 毫秒级；这不是登录热路径，是管理员点开一次的页面。
     * 也别再造一个「没有 TTL、只靠手工清」的进程内缓存（{@link #resourceChainCache} 已经是了）。
     *
     * @param subject             要看的主体，通常是 {@code SubjectKey.ofRole(roleId)}
     * @param includeFieldLevel   是否包含 COLUMN / FIELD 这两类字段级资源
     * @param includeAllSubject   是否并入全员主体 {@code ALL / all-users}。
     *                            🔴 不并入会漏掉实测那 9 条全员授权，图会少东西
     * @param includeDefaultAllow 是否包含「纯默认放行」的节点（无人配置、
     *                            仅因 {@code default_effect=ALLOW} 而有权限）。
     *                            🔴 实测超管角色有 205 个这种节点（DATASET/COLUMN/FIELD 的
     *                            default_effect 全是 ALLOW），全渲染出来会淹掉真正配过的那些，
     *                            所以默认收起 —— 但收起多少要在 summary 里报数
     * @return 剪枝后的权限树 + 实际参与的主体 + 汇总计数
     */
    @DataPermission(enable = false) // 算权限这件事本身不该再被权限过滤一遍
    @Transactional(readOnly = true)
    public EffectiveTreeRespVO listEffectiveTree(SubjectKey subject, boolean includeFieldLevel,
                                                 boolean includeAllSubject,
                                                 boolean includeDefaultAllow) {
        // 双保险与 listAllowedPermissions 一致：注解关掉数据权限，方法体内再显式忽略一次
        return DataPermissionUtils.executeIgnore(() -> listEffectiveTreeInternal(
                subject, includeFieldLevel, includeAllSubject, includeDefaultAllow));
    }

    private EffectiveTreeRespVO listEffectiveTreeInternal(SubjectKey subject, boolean includeFieldLevel,
                                                          boolean includeAllSubject,
                                                          boolean includeDefaultAllow) {
        if (subject == null) {
            throw exception(GRANT_SUBJECT_TYPE_INVALID, "null");
        }
        EffectiveTreeRespVO resp = new EffectiveTreeRespVO();
        EffectiveTreeSummaryVO summary = new EffectiveTreeSummaryVO();
        summary.setNodeCount(0);
        summary.setActionCount(0);
        summary.setInheritedCount(0);
        summary.setAllUsersCount(0);
        summary.setDefaultAllowCount(0);
        summary.setDeniedCount(0);
        summary.setExpiredSkippedCount(0);
        summary.setSuperAdminRole(isSuperAdminRole(subject));
        resp.setSummary(summary);
        resp.setTree(List.of());

        // 1. 主体集合：自身 + 主体图上的祖先（角色继承）+ 可选的全员主体
        Set<SubjectKey> subjects = new LinkedHashSet<>();
        subjects.add(subject);
        subjects.addAll(subjectClosureService.getAncestorKeys(subject));
        if (includeAllSubject) {
            subjects.add(SubjectKey.ofAllUsers());
        }
        resp.setSubjects(subjects.stream().map(SubjectKey::asString).sorted().toList());

        // 2. 一次拉回全部启用资源，按 id 建索引供上溯祖先
        List<ResourceDO> all = resourceMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ResourceDO>()
                        .eq(ResourceDO::getStatus, CommonStatusEnum.ENABLE.getStatus()));
        if (all.isEmpty()) {
            return resp;
        }
        Map<Long, ResourceDO> byId = new HashMap<>(all.size());
        for (ResourceDO r : all) {
            byId.put(r.getId(), r);
        }

        // 3. 一次拉回这批主体的全部授权行，按资源分组
        //
        // 🔴 用 selectListBySubjects（**不过滤过期**）而不是 selectListActiveBySubjects：
        // 过期与否一律以 GrantDO#isActive() 为准（判定侧口径），顺便才能数出
        // 「另有 Y 条授权已过期，未计入」。SQL 侧那条是 expire_time > now()、
        // 内存侧是 !now.isAfter(expire)，差一个等号 —— 两份口径就是两份漂移的机会
        //
        // ⚠️ 仍然按主体查、不按资源 id 批量查，理由见 GrantMapper#selectListActiveBySubjects 注释
        // （1900+ 个 id 塞进 IN → MySQL 放弃 idx_resource 转全表扫 → 实测单条 36 秒）
        Map<Long, List<GrantDO>> grantsByResource = new HashMap<>();
        int expiredSkipped = 0;
        for (GrantDO g : grantMapper.selectListBySubjects(subjects)) {
            if (!g.isActive()) {
                // 按主体查回来的行 subject_type 必然可解析，所以 isActive()=false 只可能是过期
                expiredSkipped++;
                continue;
            }
            grantsByResource.computeIfAbsent(g.getResourceId(), k -> new ArrayList<>()).add(g);
        }
        summary.setExpiredSkippedCount(expiredSkipped);
        Map<Long, String> ruleNameById = loadRuleNames(grantsByResource);

        // 4. 逐资源 × 逐候选动作判定
        Map<Long, List<EffectiveActionVO>> actionsByResource = new LinkedHashMap<>();
        Set<Long> pureDefaultIds = new LinkedHashSet<>();
        Set<Long> keepIds = new LinkedHashSet<>();
        int deniedCount = 0;
        for (ResourceDO r : all) {
            ResourceTypeEnum type = ResourceTypeEnum.parse(r.getType());
            if (!includeFieldLevel
                    && (type == ResourceTypeEnum.COLUMN || type == ResourceTypeEnum.FIELD)) {
                continue; // 字段级资源本轮不展示，它们撑起的祖先也就不必因它们而保留
            }
            // 🔴 遍历的是【全部】资源行，不是 ResourceAdminController#dedupeByPermission 的结果：
            // 实测有 12 条授权挂在重复权限串的非最小 id 行上，用 dedupe 后的集合算会让它们凭空消失
            List<ResourceDO> chain = buildChainFromMap(r, byId);
            List<GrantDO> grants = chainGrants(chain, grantsByResource);

            Set<String> candidates = candidateActions(r, grants);
            if (candidates.isEmpty()) {
                continue;
            }

            List<EffectiveActionVO> nodeActions = new ArrayList<>(candidates.size());
            boolean allFromDefault = true;
            for (String action : candidates) {
                Decision decision = engine.explain(subjects, chain, grants, action, null);
                if (!decision.allowed()) {
                    // 只数「被谁明确否决」，不数回落 default_effect=DENY 的 ——
                    // 后者是「没人给它配过权限」，在权限地图上本来就不该出现，报数只会制造噪音
                    if (decision.reason() == DecisionReason.GLOBAL_DENY
                            || decision.reason() == DecisionReason.CONTEXT_DENY) {
                        deniedCount++;
                    }
                    continue;
                }
                EffectiveActionVO vo = toActionVO(r, action, decision, ruleNameById);
                nodeActions.add(vo);
                if (!EffectiveActionVO.SOURCE_DEFAULT.equals(vo.getSource())) {
                    allFromDefault = false;
                }
            }
            if (nodeActions.isEmpty()) {
                continue;
            }
            // 全部具体动作都放行、且都出自同一条授权（那就是一条 action='*' 的行）→ 折叠成一个 *，
            // 免得在 220px 的列里铺 12 个 chip。出处不同就不折叠，宁可挤也不丢信息
            nodeActions = collapseWildcard(nodeActions);
            if (allFromDefault) {
                pureDefaultIds.add(r.getId());
                if (!includeDefaultAllow) {
                    continue; // 默认收起。收起了多少个由 summary.defaultAllowCount 报数
                }
            }
            actionsByResource.put(r.getId(), nodeActions);
            // 祖先一并保留，否则树断链（写法同 listAllowedMenuIdsInternal 里那句注释）
            for (ResourceDO node : chain) {
                keepIds.add(node.getId());
            }
        }
        summary.setDeniedCount(deniedCount);
        summary.setDefaultAllowCount(pureDefaultIds.size());

        // 5. 组装嵌套树
        List<EffectiveResourceNodeVO> tree = assembleTree(all, keepIds, actionsByResource);
        resp.setTree(tree);

        // 6. 汇总计数（按最终树里的内容数，被剪掉的不算）
        int nodeCount = 0;
        int actionCount = 0;
        int inheritedCount = 0;
        int allUsersCount = 0;
        for (EffectiveResourceNodeVO node : flatten(tree)) {
            nodeCount++;
            for (EffectiveActionVO action : node.getActions()) {
                actionCount++;
                if (EffectiveActionVO.SOURCE_INHERITED.equals(action.getSource())) {
                    inheritedCount++;
                } else if (EffectiveActionVO.SOURCE_ALL_USERS.equals(action.getSource())) {
                    allUsersCount++;
                }
            }
        }
        summary.setNodeCount(nodeCount);
        summary.setActionCount(actionCount);
        summary.setInheritedCount(inheritedCount);
        summary.setAllUsersCount(allUsersCount);
        return resp;
    }

    /**
     * 该主体是不是超管角色
     *
     * 判据与 {@code @ss.hasPermission} 走的是同一个 {@code roleService.hasAnySuperAdmin}，
     * 不是另写一份。非 ROLE 主体（用户组、部门之类）一律 false —— 超管是角色的属性
     */
    private boolean isSuperAdminRole(SubjectKey subject) {
        if (subject.type() != SubjectTypeEnum.ROLE || subject.refId() == null) {
            return false;
        }
        try {
            return roleService.hasAnySuperAdmin(Set.of(subject.refId()));
        } catch (Exception e) {
            // 与 isSuperAdmin 同口径：判不出来按【非超管】处理。
            // 这里只是一个提示条的开关，猜错的代价是「少弹一句提醒」，不值得为它放大权限
            log.warn("[isSuperAdminRole][角色({})的超管判定失败，按非超管处理]", subject.refId(), e);
            return false;
        }
    }

    /**
     * 一次批量查回这批授权行引用到的规则名
     */
    private Map<Long, String> loadRuleNames(Map<Long, List<GrantDO>> grantsByResource) {
        Set<Long> ruleIds = new LinkedHashSet<>();
        for (List<GrantDO> grants : grantsByResource.values()) {
            for (GrantDO g : grants) {
                if (g.getRuleId() != null) {
                    ruleIds.add(g.getRuleId());
                }
            }
        }
        if (ruleIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> result = new HashMap<>(ruleIds.size());
        for (RuleDO rule : ruleMapper.selectBatchIds(ruleIds)) {
            result.put(rule.getId(), rule.getName());
        }
        return result;
    }

    /**
     * 算一个节点的候选动作集
     *
     * <pre>
     *   链上有命中行 → 这些行的 action 之并集，{@code *} 展开为全部具体动作
     *   链上一行都没有 → 只有 default_effect=ALLOW 时才产出一个 {@code *} 伪动作，
     *                     表示「全部动作（默认放行）」；否则整个节点不进树
     * </pre>
     *
     * <h3>⚠️ 已知的一处刻意少报</h3>
     *
     * 「链上有命中行」时<b>不</b>再把 default_effect=ALLOW 展开成全部具体动作。
     * 于是一个 default_effect=ALLOW 的 DATASET 上配了一条 VIEW ALLOW，
     * 图上只显示 VIEW —— 而实际上它按默认放行<b>什么动作都能做</b>。
     *
     * 这是刻意的：展开的话，实测那 205 个零配置的 DATASET/COLUMN/FIELD 会全部变成
     * 「全部动作（已配置）」，正好是 includeDefaultAllow 想压掉的那批假阳性。
     * 而且少报是<b>安全的那一侧</b> —— 权限地图说「拥有」而引擎判 DENY 才是真的危险。
     * 这条限制要写进前端图例，不能只待在这段注释里
     */
    private Set<String> candidateActions(ResourceDO resource, List<GrantDO> grants) {
        if (grants.isEmpty()) {
            // 🔴 读的是 sys_resource.default_effect **列**，不是 ResourceTypeEnum#defaultEffect()。
            // 那个枚举方法只在 ResourceAdminController#createResource 里当填充默认值 ——
            // 实测 PROCESS 类型有 18 行 default_effect=ALLOW、9 行 DENY，而枚举说 PROCESS 该是 DENY。
            // 按 type 推会算错 18 个节点
            if (!"ALLOW".equals(resource.getDefaultEffect())) {
                return Set.of();
            }
            return Set.of(GrantActionEnum.WILDCARD.getCode());
        }
        Set<String> candidates = new LinkedHashSet<>();
        for (GrantDO g : grants) {
            if (GrantActionEnum.WILDCARD.getCode().equals(g.getAction())) {
                candidates.addAll(CONCRETE_ACTIONS);
            } else if (g.getAction() != null) {
                candidates.add(g.getAction());
            }
        }
        return candidates;
    }

    /**
     * 把一次判决翻译成前端要展示的「这项权限从哪来」
     *
     * 🔴 出处信息全部取自 {@link Decision}，也就是引擎投出的那一票 ——
     * 不在这里重算优先级、不重新猜是哪条行生效
     */
    private EffectiveActionVO toActionVO(ResourceDO self, String action, Decision decision,
                                         Map<Long, String> ruleNameById) {
        EffectiveActionVO vo = new EffectiveActionVO();
        vo.setAction(action);
        GrantDO grant = decision.decidingGrant();
        ResourceDO source = decision.sourceResource();
        if (grant == null) {
            // 回落 default_effect：链上没有任何命中行，没有「谁投的票」可言
            vo.setSource(EffectiveActionVO.SOURCE_DEFAULT);
            vo.setEffect(source.getDefaultEffect());
            vo.setSourceResourceId(source.getId());
            vo.setSourceResourceName(source.getName());
            return vo;
        }
        vo.setEffect(grant.getEffect());
        vo.setGrantId(grant.getId());
        vo.setRuleId(grant.getRuleId());
        vo.setRuleName(grant.getRuleId() == null ? null : ruleNameById.get(grant.getRuleId()));
        vo.setFieldMode(grant.getFieldMode());
        vo.setExpireTime(grant.getExpireTime());
        vo.setSubjectType(grant.getSubjectType());
        vo.setSubjectRefId(grant.getSubjectRefId());
        vo.setSubjectCode(grant.getSubjectCode());
        vo.setSourceResourceId(source.getId());
        vo.setSourceResourceName(source.getName());
        if (SubjectTypeEnum.ALL.name().equals(grant.getSubjectType())) {
            // 全员主体优先于继承：「所有人都有的」比「从上级继承的」更需要被看见
            vo.setSource(EffectiveActionVO.SOURCE_ALL_USERS);
        } else if (source.getId().equals(self.getId())) {
            vo.setSource(EffectiveActionVO.SOURCE_EXPLICIT);
        } else {
            vo.setSource(EffectiveActionVO.SOURCE_INHERITED);
        }
        return vo;
    }

    /**
     * 全部具体动作都放行、且都出自同一条授权行时，折叠成一个 {@code *}
     */
    private List<EffectiveActionVO> collapseWildcard(List<EffectiveActionVO> actions) {
        if (actions.size() != CONCRETE_ACTIONS.size()) {
            return actions;
        }
        Long grantId = actions.get(0).getGrantId();
        if (grantId == null) {
            return actions; // 全部来自 default_effect 回落时本来就只有一个 * 伪动作，走不到这里
        }
        for (EffectiveActionVO action : actions) {
            if (!grantId.equals(action.getGrantId())) {
                return actions; // 出处不同，逐个列出，宁可挤也不丢信息
            }
        }
        EffectiveActionVO wildcard = actions.get(0);
        wildcard.setAction(GrantActionEnum.WILDCARD.getCode());
        return List.of(wildcard);
    }

    /**
     * 把「保留哪些节点」的扁平结果组装成嵌套树
     *
     * <h3>🔴 为什么是「先定根、再自顶向下挂」而不是「逐个挂到父节点上」</h3>
     *
     * 自底向上挂的话，{@code parent_id} 成环（A 的父是 B、B 的父是 A）会造出一个
     * <b>循环对象图</b> —— 序列化时 Jackson 直接 StackOverflowError，
     * 而 {@link #buildChainFromMap} 那个 32 层上限挡不住这种情况（它只管上溯，不管组装）。
     *
     * 自顶向下 + visited 集合能保证每个节点只被挂一次，对象图必然无环。
     * 代价是环上的节点在首轮遍历里到不了，所以最后再兜一遍把它们提成根 ——
     * 权限地图上<b>少一个节点比多一个更危险</b>，不能让它静默消失
     */
    private List<EffectiveResourceNodeVO> assembleTree(List<ResourceDO> all,
                                                       Set<Long> keepIds,
                                                       Map<Long, List<EffectiveActionVO>> actionsByResource) {
        Map<Long, EffectiveResourceNodeVO> nodeById = new LinkedHashMap<>();
        Map<Long, List<EffectiveResourceNodeVO>> childrenByParent = new HashMap<>();
        for (ResourceDO r : all) {
            if (!keepIds.contains(r.getId())) {
                continue;
            }
            EffectiveResourceNodeVO vo = new EffectiveResourceNodeVO();
            vo.setId(r.getId());
            vo.setParentId(r.getParentId());
            vo.setName(r.getName());
            vo.setPermission(r.getPermission());
            vo.setType(r.getType());
            vo.setDepth(r.getDepth());
            vo.setSort(r.getSort());
            vo.setDefaultEffect(r.getDefaultEffect());
            List<EffectiveActionVO> actions = actionsByResource.get(r.getId());
            vo.setActions(actions != null ? actions : List.of());
            // 自身没有任何生效权限、仅为撑起树而保留。不标出来的话前端会把它渲染成「有权限」
            vo.setStructural(actions == null);
            vo.setChildren(new ArrayList<>());
            nodeById.put(r.getId(), vo);
            Long parentId = r.getParentId();
            if (parentId != null) {
                childrenByParent.computeIfAbsent(parentId, k -> new ArrayList<>()).add(vo);
            }
        }

        List<EffectiveResourceNodeVO> roots = new ArrayList<>();
        Set<Long> visited = new LinkedHashSet<>();
        for (EffectiveResourceNodeVO vo : nodeById.values()) {
            Long parentId = vo.getParentId();
            if (parentId == null || ResourceDO.ID_ROOT.equals(parentId) || !nodeById.containsKey(parentId)) {
                // 父节点没被保留（自身无生效权限且不在任何被保留节点的路径上）时，
                // 它就成了这一层的根 —— 与前端 handleTree 的「提升为根」行为一致
                roots.add(vo);
                visited.add(vo.getId());
            }
        }
        for (EffectiveResourceNodeVO root : roots) {
            attachChildren(root, childrenByParent, visited);
        }
        for (EffectiveResourceNodeVO vo : nodeById.values()) {
            if (visited.add(vo.getId())) {
                roots.add(vo); // parent_id 成环的兜底
                attachChildren(vo, childrenByParent, visited);
            }
        }
        sortTree(roots);
        return roots;
    }

    private void attachChildren(EffectiveResourceNodeVO parent,
                                Map<Long, List<EffectiveResourceNodeVO>> childrenByParent,
                                Set<Long> visited) {
        List<EffectiveResourceNodeVO> children = childrenByParent.get(parent.getId());
        if (children == null) {
            return;
        }
        for (EffectiveResourceNodeVO child : children) {
            if (!visited.add(child.getId())) {
                continue; // 已经挂在别处了（parent_id 成环），再挂一次就造出循环对象图
            }
            parent.getChildren().add(child);
            attachChildren(child, childrenByParent, visited);
        }
    }

    private void sortTree(List<EffectiveResourceNodeVO> nodes) {
        nodes.sort(NODE_ORDER);
        for (EffectiveResourceNodeVO node : nodes) {
            if (!node.getChildren().isEmpty()) {
                sortTree(node.getChildren());
            }
        }
    }

    private List<EffectiveResourceNodeVO> flatten(List<EffectiveResourceNodeVO> nodes) {
        List<EffectiveResourceNodeVO> result = new ArrayList<>();
        for (EffectiveResourceNodeVO node : nodes) {
            result.add(node);
            result.addAll(flatten(node.getChildren()));
        }
        return result;
    }

    /**
     * 用已加载的资源表在内存里上溯祖先链（含自身，越靠前越具体）
     *
     * 与 {@link #buildResourceChain} 等价，区别只是数据来源是内存 Map 而不是逐次查库。
     * ⚠️ 加了深度上限：资源表里万一出现环（parent 指回后代），
     * 不设上限会在这里死循环，把整个登录接口拖死
     */
    private List<ResourceDO> buildChainFromMap(ResourceDO resource, Map<Long, ResourceDO> byId) {
        List<ResourceDO> chain = new ArrayList<>();
        ResourceDO cur = resource;
        int guard = 0;
        while (cur != null && guard++ < 32) {
            chain.add(cur);
            Long parentId = cur.getParentId();
            if (parentId == null || ResourceDO.ID_ROOT.equals(parentId)) {
                break;
            }
            cur = byId.get(parentId);
        }
        return chain;
    }

    /**
     * 查一个资源节点的祖先链（含自身，越靠前越具体）
     */
    private List<ResourceDO> buildResourceChain(ResourceDO resource) {
        List<ResourceDO> chain = new ArrayList<>();
        chain.add(resource);
        if (resource.getPath() == null || resource.getPath().isEmpty()) {
            return chain;
        }
        // 与 resolveFieldModes 共用同一份上溯逻辑 ——
        // 各写一份的话，「按钮鉴权认的祖先链」与「字段裁剪认的祖先链」会漂
        chain.addAll(buildAncestors(parentPathOf(resource)));
        return chain;
    }

}
