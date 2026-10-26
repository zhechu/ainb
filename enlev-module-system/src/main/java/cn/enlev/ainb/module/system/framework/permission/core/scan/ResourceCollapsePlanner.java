package cn.enlev.ainb.module.system.framework.permission.core.scan;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.module.system.dal.dataobject.permission.GrantDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.enums.permission.GrantActionEnum;
import cn.enlev.ainb.module.system.enums.permission.ResourceTypeEnum;
import lombok.Getter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 塌缩迁移的<b>计划器</b>：读入当前的资源行与授权行，算出完整变更集，<b>不写任何东西</b>
 *
 * <h3>为什么它是一个纯函数，而不是一个 Service</h3>
 *
 * 这次迁移要重写 {@code sys_resource} 与 {@code sys_grant} 两张表，DML 不可回滚。
 * 落库时算出的变更集（口径见 {@code _scratch/2d_migration.sql} 的分节标题）：
 * 退役成员行 457、新建身份行 33、{@code menu_id} 改 64 行、{@code default_effect} 改 7 行、
 * 授权改写 1127 条、判重软删 15 条。
 * ⚠️ 本注释早期写「1691 条 sys_grant」，那个数从来没有对应过任何一栏 ——
 * 快照里活授权一共 1802 条，其中 1127 条改写、15 条判重、660 条原样不动。
 *
 * <p>让它可评审的唯一办法是把「决定改什么」与「实际去改」彻底分开：
 * 本类只做前者，输入输出全是普通对象，于是
 *
 * <ul>
 *   <li>它可以在单测里跑，断言变更集的每一条 —— 而「跑一遍迁移再看库」做不到这一点</li>
 *   <li>干跑不是它的一个模式，而是它的<b>唯一形态</b>：它压根没有写库的能力</li>
 *   <li>评审的人读一份变更集就够了，不需要在脑子里模拟 SQL</li>
 * </ul>
 *
 * 落库由 {@code ResourceCollapseService} 负责，它只消费本类的输出。
 *
 * <h3>塌缩在做的事</h3>
 *
 * 动词今天被编码了两次：一次在资源身份串里（{@code system:user:create}），
 * 一次在 {@code sys_grant.action} 里（几乎没用，实测 1802 条活授权中 VIEW 占 1771）。
 * 塌缩把第一次编码挪进第二个维度：
 *
 * <pre>
 *   资源 system:user:create + 授权 action=VIEW
 *     ⟹ 资源 system:user      + 授权 action=CREATE
 * </pre>
 *
 * 🔴 所以<b>授权的 action 必须跟着改写</b>，不能原样保留 VIEW ——
 * 保留 VIEW 的话，判定侧解析出的是 CREATE，而库里只有 VIEW，
 * 结果是<b>每一个新增/修改/删除/导出/导入按钮被静默收走</b>，且全程 200、无报错。
 * 见规则 R9。
 *
 * <h3>本类落实的规则（编号见《阶段2前置勘察报告》）</h3>
 *
 * <table border="1">
 *   <caption>规则与落实位置</caption>
 *   <tr><th>规则</th><th>内容</th><th>不落实的后果</th></tr>
 *   <tr><td>R1</td><td>存活 menu_id 优先取导航菜单（type≠按钮），不按 min(id)</td>
 *       <td>「用量看板」这类真页面从导航消失</td></tr>
 *   <tr><td>R2</td><td>反向展开按「塌缩前实际存在过的串」建表</td>
 *       <td>权限清单里多出库中不存在的假权限点</td></tr>
 *   <tr><td>R3</td><td>空权限行不合并、不铸串，其授权原样留着</td>
 *       <td>无（铸串是过度设计）</td></tr>
 *   <tr><td>R4</td><td>停用行不参与塌缩，授权一条都不搬</td>
 *       <td>🔴 4 个角色静默拿到 3 个已被特意停用的页面</td></tr>
 *   <tr><td>R7</td><td>{@code action='*'} 一律收窄成源串解析出的动作，ALLOW 与 DENY 同办</td>
 *       <td>🔴 DENY 静默收权；ALLOW 静默放权</td></tr>
 *   <tr><td>R9</td><td>塌缩行的授权 action 改写成 {@code resolution.getAction()}</td>
 *       <td>🔴 全部非 VIEW 按钮被静默收走</td></tr>
 *   <tr><td>R10</td><td>合并后 (主体,资源,动作,效果,租户) 完全重复的授权只留一条</td>
 *       <td>授权页出现重复行，且删除时删一半</td></tr>
 *   <tr><td>R11</td><td>身份行下面还有存活后代的族，<b>整族不塌</b>（见 {@code #blockedIdentities}）</td>
 *       <td>🔴 上提的授权顺着树继承给后代：实测 382 个决策点越权 + 100 个决策点被静默收权</td></tr>
 * </table>
 *
 * R5（套餐换算改「菜单 → 身份+动作」）与 R6（读路径改 resolver 感知）是<b>代码</b>改造，
 * 不在本类职责内，但它们必须与本类的输出同批生效 —— 见《阶段2前置勘察报告》「阶段 2 的正确切法」。
 * R11 是阶段 2c 自校验跑出来的，那份报告的 R1–R8 里没有它；它同时要求 R6 那侧配合
 * （{@code GrantDecisionService#lookupOf} 只塌「成员行已经不在库里」的串）。
 *
 * @author 走进新时代
 */
public class ResourceCollapsePlanner {

    /**
     * 遗留族：只有这两种类型的资源行参与塌缩
     *
     * DATASET / COLUMN / FIELD / PROCESS / CATEGORY 已经是目标形态
     * （资源身份不含动词，动作在 {@code sys_grant.action} 上），动它们只会引入回归
     */
    private static final Set<String> LEGACY_TYPES = Set.of(
            ResourceTypeEnum.ACTION.getCode(), ResourceTypeEnum.MENU.getCode());

    private final PermissionStringResolver resolver;

    public ResourceCollapsePlanner(PermissionStringResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * 算出完整变更集
     *
     * @param resources      当前<b>全部</b>资源行（含停用的 —— R4 要靠 status 把它们挑出来）
     * @param grants         当前<b>全部</b>授权行
     * @param menuPermissions {@code system_menu.permission} 的非空集合，反向展开表的来源（R2）
     * @param navMenuIds     {@code system_menu} 里 type≠按钮 的菜单编号集合，R1 的优先级依据
     */
    public CollapsePlan plan(List<ResourceDO> resources, List<GrantDO> grants,
                             Set<String> menuPermissions, Set<Long> navMenuIds) {
        CollapsePlan plan = new CollapsePlan();

        // ---------- 第 1 步：给每个资源行定性 ----------
        Map<Long, ResourceDO> resourceById = new HashMap<>(resources.size());
        for (ResourceDO r : resources) {
            resourceById.put(r.getId(), r);
        }
        // 权限串 -> 已存在的启用行（同串多行时取 id 最小的那个，保证计划可复现）
        // 两个用途：① 身份行的锚点；② 新建身份行时按命名约定找父节点
        Map<String, ResourceDO> anchorByPermission = new HashMap<>();
        for (ResourceDO r : resources) {
            if (StrUtil.isBlank(r.getPermission()) || !isEnabled(r)) {
                continue;
            }
            ResourceDO prev = anchorByPermission.get(r.getPermission());
            if (prev == null || r.getId() < prev.getId()) {
                anchorByPermission.put(r.getPermission(), r);
            }
        }

        // 身份 -> 参与塌缩的行
        Map<String, List<ResourceDO>> groups = new LinkedHashMap<>();
        Map<Long, Classification> classified = new LinkedHashMap<>(resources.size());
        for (ResourceDO r : resources) {
            Classification c = classify(r);
            classified.put(r.getId(), c);
            if (c.kind != Kind.COLLAPSING) {
                continue;
            }
            groups.computeIfAbsent(c.identity, k -> new ArrayList<>()).add(r);
        }

        // ---------- 第 1.5 步：把「一塌就漏」的族整族拦下 ----------
        //
        // 必须在第 2 步之前：那一步会退役成员行、把授权上提到身份行，
        // 而身份行同时是树节点 —— 上提上去的授权会顺着树继承给它<b>其余</b>的存活子节点。
        // 详见 blockedIdentities
        Set<String> blocked = blockedIdentities(groups, anchorByPermission, resources, plan);
        blocked.forEach(groups::remove);
        for (Map.Entry<Long, Classification> e : classified.entrySet()) {
            Classification c = e.getValue();
            if (c.kind == Kind.COLLAPSING && blocked.contains(c.identity)) {
                classified.put(e.getKey(), new Classification(Kind.BLOCKED, c.identity, c.action, c.tier));
            }
        }
        classified.forEach(plan.classification::put);

        // ---------- 第 2 步：逐身份决定「谁活下来、menu_id 归谁」 ----------
        for (Map.Entry<String, List<ResourceDO>> entry : groups.entrySet()) {
            String identity = entry.getKey();
            List<ResourceDO> members = entry.getValue();
            members.sort(Comparator.comparing(ResourceDO::getId));

            // 锚点自己是 IDENTITY 定性（身份串的末段不是动词，解析不出动作），
            // 永远不会出现在 members 里 —— 所以 members 的每一行都无条件退役，
            // 不需要「这行是不是锚点」的分支。第 3 步靠这张表认出哪些授权要搬
            for (ResourceDO m : members) {
                plan.resourceIdRemap.put(m.getId(), identity);
                plan.retiredResourceIds.add(m.getId());
            }

            ResourceDO anchor = anchorByPermission.get(identity);
            if (anchor == null) {
                // 需要新建身份行。id 由落库方在 insert 后回填，所以计划里只用身份串指代，
                // 绝不塞假 id —— 假 id 一旦与真实序列撞上，写进去的就是别人的行，且不报错
                plan.createdIdentities.put(identity,
                        newIdentityTemplate(identity, members, navMenuIds, anchorByPermission, plan));
                continue;
            }

            plan.identityOf.put(identity, anchor.getId());
            applyStrictestDefaultEffect(identity, anchor, members, plan);
            Long chosenMenuId = chooseMenuId(anchor, members, navMenuIds, plan);
            if (chosenMenuId != null && !chosenMenuId.equals(anchor.getMenuId())) {
                plan.menuIdUpdates.put(anchor.getId(), chosenMenuId);
            }
        }

        // ---------- 第 3 步：改写授权 ----------
        // 先按目标五元组分桶，才能认出 R10 的重复行。
        // ⚠️ effect 在键里，所以同 (主体,身份,动作) 上的 ALLOW 与 DENY 各自成桶、都留着 ——
        //    「DENY 优先」不是计划器裁决的，而是判定引擎既有的效果优先级
        //    （全局 DENY > 上下文 DENY > 上下文 ALLOW > 全局 ALLOW）本来就会做的事
        Map<String, List<GrantDO>> buckets = new LinkedHashMap<>();
        for (GrantDO g : grants) {
            String identity = plan.resourceIdRemap.get(g.getResourceId());
            if (identity == null) {
                plan.untouchedGrantIds.add(g.getId());   // R3/R4/已达标族：原样不动
                continue;
            }
            ResourceDO source = resourceById.get(g.getResourceId());
            String action = resolveTargetAction(g, source, identity, plan);
            buckets.computeIfAbsent(grantKey(g, identity, action), k -> new ArrayList<>()).add(g);
            plan.plannedAction.put(g.getId(), action);
        }
        for (Map.Entry<String, List<GrantDO>> entry : buckets.entrySet()) {
            List<GrantDO> same = entry.getValue();
            // 🔴 keeper 必须与入参顺序无关：同样的库、跑两次得到两份不同的计划，
            //    评审就没有意义了（而「评审过的那份 ≠ 执行的那份」正是不可回滚迁移最怕的事）
            same.sort(Comparator.comparing(GrantDO::getId));
            GrantDO keeper = same.get(0);
            String identity = plan.resourceIdRemap.get(keeper.getResourceId());
            plan.grantRewrites.add(new GrantRewrite(keeper.getId(), identity,
                    plan.plannedAction.get(keeper.getId())));
            for (int i = 1; i < same.size(); i++) {
                GrantDO dropped = same.get(i);
                plan.redundantGrantIds.add(dropped.getId());   // R10
                warnIfDroppingAttachments(dropped, keeper, plan);
            }
        }

        // ---------- 第 4 步：反向展开表（R2） ----------
        plan.reverseExpansion = buildReverseExpansion(resources, menuPermissions, plan);

        plan.freeze();
        return plan;
    }

    /**
     * 挑出<b>一塌缩就会把授权漏给别的资源</b>的族，整族拦下
     *
     * <h3>🔴 泄漏是怎么发生的</h3>
     *
     * R9 把成员行上的授权上提到身份行。而身份行<b>不是一根光杆</b> ——
     * 它在资源树上还有别的子节点（档 C 的业务专有动词串、{@code :col:} / {@code :fld:} 列与字段行），
     * 那些子节点不参与塌缩、迁移后仍然活着。判定侧的两个清单口径都会把<b>整条祖先链</b>上的授权
     * 收进来（{@code GrantDecisionService#buildResourceChain} 走 path，
     * {@code #buildChainFromMap} 走 parent_id），所以上提上去的授权会自动继承给它们。
     *
     * <p>结果是<b>双向</b>的，两个方向都实测到了：
     * <ul>
     *   <li>上提的是 ALLOW ⟹ 存活后代凭空拿到权限。实测 19 族、382 个决策点从无权变有权，
     *       其中 26 个串（{@code agent:workflow:publish}、{@code bpm:model:deploy}、
     *       {@code infra:portal:run} 等）<b>后端 {@code @ss.hasPermission} 或前端
     *       {@code v-hasPermi} 真的在比</b> —— 这是越权，不是语义变化</li>
     *   <li>上提的是 DENY ⟹ 存活后代凭空被禁。实测 {@code system:user} 一族：
     *       ROLE 9000000543 在 {@code system:user:create/delete/export/import} 四行上有
     *       {@code action='*'} 的 DENY，塌缩前这四行是那 26 个 {@code :col:} / {@code :fld:} 行的
     *       <b>兄弟</b>，管不到它们；上提到 {@code system:user} 之后就成了它们的<b>父亲</b>，
     *       100 个决策点从有权变无权</li>
     * </ul>
     *
     * <h3>🔴 为什么不是「把存活后代改挂到别处」</h3>
     *
     * 那是最直觉的修法（身份行成了叶子就不会漏），但它在<b>本仓库里修不住</b>：
     * 资源树的父子关系不是数据，是<b>从权限串推导出来的</b> ——
     * {@code PermissionTreeDeriver} 直接取 {@code permission.substring(0, lastIndexOf(':'))} 当父串，
     * {@code DatasetResourceNaming#childOf} 同样按串拼父子。
     * 改了 {@code parent_id} 与 {@code path}，下一次菜单同步或表单发布就会按串把它们挂回去，
     * 而且<b>不报错</b>。要让改挂生效就得改权限串本身，那是换一套命名，不是迁移。
     *
     * <h3>为什么也不能只拦 ALLOW、放过 DENY</h3>
     *
     * 拦不拦取决于「身份行有没有存活后代」，与上提的是哪种效果无关。
     * 今天只拦 ALLOW 的话，明天谁给 {@code agent:workflow:create} 加一条 DENY，
     * {@code agent:workflow:test} 就会跟着被禁 —— 同一条继承边，两个方向都得堵。
     *
     * <h3>代价（实测）</h3>
     *
     * 117 个族里拦下 20 个（33 个新建身份行天然是叶子、64 个锚点没有存活后代，都不用拦），
     * 少退役 83 行成员，迁移后资源行 598 而不是 515。仍然比现状的 1022 行少 41%。
     *
     * <p>被拦下的族在<b>判定侧</b>也要跟着不塌缩，否则成员串的授权已经不上提、
     * 而 {@code lookupOf} 照样把 {@code system:user:query} 解析成 {@code (system:user, VIEW)}，
     * 查的是身份行 —— 那一行上没有这条授权，权限凭空消失。
     * 两侧的判据必须<b>同源</b>：本方法看「成员行还在不在库里」，
     * {@code GrantDecisionService#lookupOf} 也看同一件事，数据本身就是那面旗子。
     *
     * @return 被拦下的身份串集合（同时写进 {@link CollapsePlan#getBlockedIdentities()}）
     */
    private Set<String> blockedIdentities(Map<String, List<ResourceDO>> groups,
                                          Map<String, ResourceDO> anchorByPermission,
                                          List<ResourceDO> resources, CollapsePlan plan) {
        // 「假设全都塌」时的退役集。拦下来的那些行事后仍然活着，
        // 所以这里会把存活后代算少 —— 但一个存活后代如果自己也形状可塌，
        // 它要么与身份行同族（一起被拦）、要么是嵌套族的成员（那一族自己会被单独判一次）。
        // 用同一个集合判所有族，判据才是可复现的：不依赖 families 的遍历顺序
        Set<Long> retiring = new LinkedHashSet<>();
        for (List<ResourceDO> members : groups.values()) {
            for (ResourceDO m : members) {
                retiring.add(m.getId());
            }
        }

        Map<Long, List<ResourceDO>> childrenOf = new HashMap<>();
        for (ResourceDO r : resources) {
            if (isEnabled(r)) {
                childrenOf.computeIfAbsent(r.getParentId(), k -> new ArrayList<>()).add(r);
            }
        }

        Set<String> blocked = new LinkedHashSet<>();
        for (Map.Entry<String, List<ResourceDO>> entry : groups.entrySet()) {
            String identity = entry.getKey();
            ResourceDO anchor = anchorByPermission.get(identity);
            if (anchor == null) {
                continue;   // 新建的身份行是叶子，没有后代可漏
            }
            List<ResourceDO> survivors = survivingDescendants(anchor, childrenOf, resources, retiring);
            if (survivors.isEmpty()) {
                continue;
            }
            blocked.add(identity);
            plan.blockedIdentities.add(identity);
            plan.warnings.add(String.format(
                    "🔴 整族拦下不塌缩：%s（锚点 id=%d, type=%s）下面还有 %d 行存活后代（如 %s）—— "
                            + "把 %d 行成员的授权上提到锚点，会顺着资源树继承给这些后代。"
                            + "已实测：上提 ALLOW 造成 382 个决策点越权（26 个串真的被 "
                            + "@ss.hasPermission / v-hasPermi 比着），上提 DENY 造成 100 个决策点被静默收权。"
                            + "改挂父节点修不住（PermissionTreeDeriver 与 DatasetResourceNaming#childOf "
                            + "都按权限串推导父子，下次同步会挂回去且不报错），所以本族保持原样",
                    identity, anchor.getId(), anchor.getType(), survivors.size(),
                    survivors.get(0).getPermission(), entry.getValue().size()));
        }
        return blocked;
    }

    /**
     * 锚点行下面<b>迁移后仍然活着</b>的后代 —— 即会接收到上提授权的那些行
     *
     * <p>🔴 两个父子口径都要走，取并集：{@code hasPermission} 一侧的祖先链是按
     * <b>物化路径</b>建的（{@code buildResourceChain} → {@code selectListByPaths}），
     * 而 {@code listAllowedPermissions} / {@code listAllowedMenuIds} 一侧是按
     * <b>parent_id</b> 建的（{@code buildChainFromMap}）。
     * 实测本库两个口径完全一致（117 个族逐个比对，0 处不一致），
     * 但 {@code PermissionDriftDetector#repairParents} 的存在说明它们<b>会</b>漂 ——
     * 漂了以后只按一个口径判就会漏拦，而漏拦的表现是静默越权，所以取并集。
     *
     * <p>只看启用行：{@code ResourceMapper#selectListByPermission} 带 {@code status=0} 过滤，
     * {@code listAllowedPermissions} 也只遍历启用行，所以停用的后代两个口径都够不着，
     * 它接不到上提的授权，不构成拦截理由（拦了只是白少塌一族）。
     */
    private static List<ResourceDO> survivingDescendants(ResourceDO anchor,
                                                         Map<Long, List<ResourceDO>> childrenOf,
                                                         List<ResourceDO> resources,
                                                         Set<Long> retiring) {
        Set<Long> ids = new LinkedHashSet<>();
        // 口径一：parent_id
        Deque<Long> stack = new ArrayDeque<>();
        stack.push(anchor.getId());
        while (!stack.isEmpty()) {
            for (ResourceDO child : childrenOf.getOrDefault(stack.pop(), List.of())) {
                if (ids.add(child.getId())) {
                    stack.push(child.getId());
                }
            }
        }
        // 口径二：物化路径前缀。锚点自己的 path 以 '/' 结尾，所以 startsWith 天然是段对齐的
        String anchorPath = anchor.getPath();
        if (StrUtil.isNotBlank(anchorPath)) {
            for (ResourceDO r : resources) {
                if (isEnabled(r) && !r.getId().equals(anchor.getId())
                        && r.getPath() != null && r.getPath().startsWith(anchorPath)) {
                    ids.add(r.getId());
                }
            }
        }
        ids.remove(anchor.getId());
        ids.removeAll(retiring);
        List<ResourceDO> out = new ArrayList<>(ids.size());
        Map<Long, ResourceDO> byId = new HashMap<>(resources.size());
        for (ResourceDO r : resources) {
            byId.put(r.getId(), r);
        }
        for (Long id : ids) {
            ResourceDO r = byId.get(id);
            if (r != null) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparing(ResourceDO::getId));
        return out;
    }

    /**
     * 被去重丢掉的那一行上如果绑了行过滤规则 / 字段模式 / 到期时间，必须喊出来
     *
     * <p>{@code grantKey} 刻意不含这三个字段（含了就认不出重复），
     * 于是「两条授权只有 rule_id 不同」会被判为重复、删掉一条 ——
     * 而删掉的可能是<b>绑了规则的那条</b>，表现为行过滤静默失效：查询照常返回，只是不再过滤。
     * 计划器不替人做这个取舍，它只保证这个取舍是<b>被看见的</b>。
     */
    private static void warnIfDroppingAttachments(GrantDO dropped, GrantDO keeper, CollapsePlan plan) {
        List<String> lost = new ArrayList<>(3);
        if (dropped.getRuleId() != null && !dropped.getRuleId().equals(keeper.getRuleId())) {
            lost.add("rule_id=" + dropped.getRuleId());
        }
        if (StrUtil.isNotBlank(dropped.getFieldMode())
                && !StrUtil.equals(dropped.getFieldMode(), keeper.getFieldMode())) {
            lost.add("field_mode=" + dropped.getFieldMode());
        }
        if (dropped.getExpireTime() != null && !dropped.getExpireTime().equals(keeper.getExpireTime())) {
            lost.add("expire_time=" + dropped.getExpireTime());
        }
        if (lost.isEmpty()) {
            return;
        }
        plan.warnings.add(String.format(
                "🔴 R10 去重会丢掉附属字段：grant_id=%d 与保留的 grant_id=%d 五元组相同，但前者带 %s —— "
                        + "删掉它等于解绑，需人工确认保留哪一条",
                dropped.getId(), keeper.getId(), String.join("、", lost)));
    }

    // ==================== 定性 ====================

    /**
     * 一个资源行在本次迁移里的定性
     *
     * <p>public 是因为它是 {@link CollapsePlan#getClassification()} 的值类型 ——
     * 消费方（测试、评审报告）要能写出这个类型名，否则只能拿 {@code Object} 再反射
     */
    public enum Kind {
        /** 参与塌缩：末段动词能映射到动作维度 */
        COLLAPSING,
        /**
         * 形状上能塌缩，但<b>被继承泄漏拦下了</b>，本族整体不动（成员行不退役、授权不上提）
         *
         * <p>拦下它的判据与后果见 {@code #blockedIdentities}
         */
        BLOCKED,
        /** 空权限行。R3：不合并、不铸串，授权原样留着 */
        BLANK,
        /** 停用行。R4：不参与塌缩 —— 它今天等于不存在，搬动它的授权就是静默放权 */
        DISABLED,
        /** 档 C/D/root：本来就是资源身份 */
        IDENTITY,
        /** DATASET/COLUMN/FIELD/PROCESS/CATEGORY：已是目标形态 */
        OUT_OF_SCOPE
    }

    @Getter
    public static class Classification {
        private final Kind kind;
        /** 仅 {@link Kind#COLLAPSING} / {@link Kind#IDENTITY} 有值 */
        private final String identity;
        /** 仅 {@link Kind#COLLAPSING} / {@link Kind#IDENTITY} 有值 */
        private final GrantActionEnum action;
        private final PermissionStringResolver.Tier tier;

        Classification(Kind kind, String identity, GrantActionEnum action,
                       PermissionStringResolver.Tier tier) {
            this.kind = kind;
            this.identity = identity;
            this.action = action;
            this.tier = tier;
        }

        @Override
        public String toString() {
            if (identity == null) {
                return kind.name();
            }
            // 档 C/D 的 action 可以为 null（末段压根不是动词），这时只打身份串
            return kind + "(" + identity + (action == null ? "" : "+" + action.getCode()) + ")";
        }
    }

    private Classification classify(ResourceDO r) {
        if (r.getType() == null || !LEGACY_TYPES.contains(r.getType())) {
            return new Classification(Kind.OUT_OF_SCOPE, null, null, null);
        }
        if (StrUtil.isBlank(r.getPermission())) {
            return new Classification(Kind.BLANK, null, null, PermissionStringResolver.Tier.BLANK);
        }
        // 🔴 R4 的顺序在解析之前：停用行连「它塌缩到哪」都不该算，
        //    否则它会以身份为名去参与 menu_id 竞争，把导航指到一个停用的菜单上
        if (!isEnabled(r)) {
            return new Classification(Kind.DISABLED, null, null, null);
        }
        PermissionStringResolver.Resolution res = resolver.resolve(r.getPermission());
        if (!PermissionStringResolver.isCollapsing(res.getTier())) {
            // 档 C（业务专有动词）、档 D（两段式名词）、root（单段模块根）本来就<b>是</b>资源身份，
            // 保持原样 —— 这保证了「不塌缩的行，生效权限逐字节不变」这件事是平凡的
            return new Classification(Kind.IDENTITY, res.getResourceKey(), res.getAction(), res.getTier());
        }
        return new Classification(Kind.COLLAPSING, res.getResourceKey(), res.getAction(), res.getTier());
    }

    private static boolean isEnabled(ResourceDO r) {
        return CommonStatusEnum.ENABLE.getStatus().equals(r.getStatus());
    }

    // ==================== R1：menu_id 归属 ====================

    /**
     * 合并后身份行的 {@code default_effect} 取「锚点与全部成员里最严格的那个」
     *
     * <h3>🔴 不做这一步会怎样：464 个决策点从无权变有权</h3>
     *
     * {@code GrantDecisionEngine#explain} 在链上没有任何命中授权时，回落到
     * {@code chain.get(0).getDefaultEffect()} —— 一个<b>每行一个的标量</b>，不看动作。
     * 而塌缩把若干行并成一行，于是一行要替多个原本不同的默认值作答。
     *
     * <p>这一冲突在快照里有 <b>7 例</b>，形态完全一致：锚点是 DATASET 行
     * （{@code default_effect=ALLOW}，如 {@code system:dept} id=2056），
     * 并进来的是若干 ACTION 行（{@code default_effect=DENY}，如 {@code system:dept:query} id=17）。
     * 合并后 {@code system:dept:create} 被 {@code lookupOf} 翻译成
     * {@code (system:dept, CREATE)}，链上没有授权 → 回落到锚点的 ALLOW → <b>放行</b>。
     * 而塌缩前它落到自己那行的 DENY → 拒绝。
     *
     * <h3>🔴 上面三个数字全部来自自校验报告，别再用记忆里的那些</h3>
     *
     * 本注释早期写的是「9 例 / 660 个决策点 / 60 = 那 9 个身份的成员数之和（7+4+7×7）」，
     * 三处都错，而且错得很有迷惑性 —— 660 <b>确实在同一份报告里</b>，
     * 但它是 {@code [计划] …原样 660} 那一栏，指的是<b>一条都没改写的授权行数</b>，与决策点无关。
     * 权威口径是 {@code target/s2c-selfcheck-report.txt} 里这两行：
     *
     * <ul>
     *   <li>{@code [计划] default_effect 改写 7 行}，并逐条列出 7 个身份串：
     *       {@code system:dept} 加 6 个 {@code infra:form-design-run:*}
     *       （{@code test_demo_widget_gallery}、{@code test_leave_bad}、{@code widget_all_test}、
     *       {@code assessment_object_form}、{@code assessment_write}、{@code assessment_writing}）</li>
     *   <li>{@code [hasPermission] 决策点 60445 个，差异 617 个（迁移后收紧 464 / 迁移后放开 153）；
     *       已声明的收紧涉及 7 个身份串 / 计划共声明 7 个}</li>
     * </ul>
     *
     * <p>⚠️ 早期拿 {@code system:user} id=2061 当例子也是错的，而且是<b>方向性</b>的错：
     * 那一族被 R11（存活后代继承泄漏）整族拦下，压根没进 {@code defaultEffectUpdates}，
     * 它的锚点至今仍是 ALLOW。举一个「没被收紧」的行当「必须收紧」的例子，
     * 读者照着去库里核对只会得出「这注释在瞎写」的结论
     *
     * <p>464 与 7 之间没有干净的乘法关系（7 个身份一共并入 46 个成员串、11 个用户，
     * 但只有那些<b>真的靠默认值回落</b>的点和动作维度上被枚举到的点才计入），
     * 所以别再试图把它写成「N 个用户 × M 个串」—— 写出来的等式一旦对不上，
     * 下一个人会以为是校验错了，而不是注释错了
     *
     * <h3>🔴 为什么不能靠「再加一行」绕开</h3>
     *
     * {@code GrantDecisionService#hasPermission} 对同一 permission 的多行是
     * <b>任意一条链通过即通过</b>（OR）。所以留着 DATASET 行、另建一行 DENY 的身份行，
     * 结果是 DATASET 那条链照样把 ALLOW 投出来 —— OR 之后仍是放行。
     * 一行一个标量 + OR 合并，决定了这件事<b>只能在合并前把标量定对</b>。
     *
     * <h3>🔴 为什么「两个答案都要」做不到，以及为什么选 DENY</h3>
     *
     * {@code :query} / {@code :list} 解析出的动作<b>就是 VIEW</b>，与裸身份串问的是
     * 同一个 {@code (行, VIEW)}。塌缩前这两者一个 ALLOW 一个 DENY，塌缩后是同一个问题 ——
     * <b>信息在这里被真的销毁了</b>，任何 {@code default_effect} 取值都无法同时复现两边。
     * 所以必须选，而两个方向的代价完全不对等：
     *
     * <ul>
     *   <li>选 ALLOW ⟹ 那 46 个动词串全部默认放行，<b>越权</b>，且全程 200 无异常</li>
     *   <li>选 DENY ⟹ 只有裸身份串自己掉出 {@code listAllowedPermissions}。
     *       实测<b>全库没有任何一处比的是裸身份串</b>：{@code system_menu.permission} 里
     *       这 7 个串一个都没有，后端 {@code @ss.hasPermission} 与前端 {@code v-hasPermi}
     *       也都查不到 —— 所以这一项掉出去是<b>观察不到的</b>。
     *       ⚠️ 这句话不是随口说的，它是自校验里那 153 个「已核实的放开点」唯一的正当性来源，
     *       核实过程（全仓 grep + 全库扫列）记在
     *       {@code CollapseMigrationSelfCheckTest#isDeclaredGain} 的 javadoc 里</li>
     * </ul>
     *
     * <p>被改掉的那些身份串记进 {@link CollapsePlan#getDefaultEffectNarrowings()}，
     * 自校验拿它当白名单：断言「收紧 ⊆ 白名单」且「放开 = 0」。
     *
     * <h3>判据是<b>值</b>，不是 type</h3>
     *
     * 原来的写法是 {@code !LEGACY_TYPES.contains(anchor.getType())}（锚点不是 MENU/ACTION 就告警）。
     * 那是在拿 type 当 {@code default_effect} 的代理，而引擎读的是<b>列</b>不是枚举 ——
     * 引擎自己的注释里就记着「PROCESS 类型有 18 行 ALLOW、9 行 DENY，按 type 推就会算错」。
     * 这里直接比列值：只要有任何一个成员比锚点严，就收紧。今天两种写法结果相同 ——
     * 快照里启用的 575 个 ACTION 行 {@code default_effect} <b>无一例外</b>是 DENY、
     * 178 个 DATASET 行无一例外是 ALLOW，所以「按 type 推」与「按值比」在这批数据上等价；
     * 但只有比值的这一种在数据变化后仍然正确（PROCESS 那 18+9 行就是现成的反例）
     */
    private void applyStrictestDefaultEffect(String identity, ResourceDO anchor,
                                             List<ResourceDO> members, CollapsePlan plan) {
        String strictest = anchor.getDefaultEffect();
        String strictestFrom = null;
        for (ResourceDO m : members) {
            if (isStricter(m.getDefaultEffect(), strictest)) {
                strictest = m.getDefaultEffect();
                strictestFrom = String.format("%s(id=%d, type=%s)",
                        m.getPermission(), m.getId(), m.getType());
            }
        }
        if (!isStricter(strictest, anchor.getDefaultEffect())) {
            return; // 锚点已经是最严的，不动
        }
        plan.defaultEffectUpdates.put(anchor.getId(), strictest);
        plan.defaultEffectNarrowings.add(identity);
        plan.warnings.add(String.format(
                "🔴 跨类型合并：身份 %s 的锚点是 %s(id=%d, default_effect=%s)，"
                        + "并入的 %d 行里有更严的 %s —— 已把该身份行的 default_effect 改成 %s。"
                        + "代价：裸身份串 %s 自身从「默认放行」变成「默认拒绝」，"
                        + "它会掉出 listAllowedPermissions（实测无任何 hasPermission/hasPermi 比的是它）；"
                        + "收益：%d 个动词串不再默认放行",
                identity, anchor.getType(), anchor.getId(), anchor.getDefaultEffect(),
                members.size(), strictestFrom, strictest, identity, members.size()));
    }

    /**
     * {@code candidate} 是否比 {@code current} 更严
     *
     * <p>🔴 口径必须与 {@code GrantDecisionEngine#explain} 的回落判定<b>逐字节一致</b>：
     * 那边写的是 {@code "ALLOW".equals(self.getDefaultEffect())} ——
     * 也就是说 null、空串、脏值一律<b>等价于 DENY</b>，而不是「未知」。
     * 这里若把 null 当成第三态，就会算出一个引擎根本不会那样解释的默认值
     */
    private static boolean isStricter(String candidate, String current) {
        return !"ALLOW".equals(candidate) && "ALLOW".equals(current);
    }

    /**
     * 挑一个 menu_id 挂到存活的身份行上
     *
     * <h3>🔴 为什么必须挑，而不能让身份行保持 menu_id=NULL</h3>
     *
     * 实测 84 个已存在的身份锚点行（{@code PermissionTreeDeriver} 建出来的中间节点）
     * <b>menu_id 全是 NULL</b>。而 NULL 会让两处判定静默走偏：
     *
     * <ul>
     *   <li>{@code PermissionServiceImpl#filterMenuDerived} 的判据就是 {@code menu_id != null} ——
     *       身份行是 NULL 时，它上面的授权<b>不再被认作「套餐派生」</b>，
     *       于是套餐降级时第 3 步的删除会跳过它们：<b>权限收不回来，且不报错</b>。
     *       那个方法的注释里记着一次同型事故（套餐重算删掉超管的行级授权，
     *       组织管理只显示 1 个部门）</li>
     *   <li>{@code GrantDecisionService#listAllowedMenuIdsInternal} 对 menu_id 为空的行直接
     *       {@code continue} —— 身份行不进导航集合。祖先补全（{@code completeMenuAncestors}）
     *       是从<b>已进集合的</b>菜单往上走的，起点没了就补不出任何东西</li>
     * </ul>
     *
     * <h3>优先级</h3>
     *
     * 锚点行自己的 menu_id（若非空）→ 组内导航菜单（type≠按钮）→ 组内最小 id。
     *
     * 第二条是 R1 的实质：实测 107 个组会丢 menu_id，其中 401 个是按钮、<b>1 个是真页面</b>
     * （{@code agent:usage} 的「用量看板」，与另一行同串但一个是按钮一个是菜单）。
     * 按 min(id) 取会选中按钮、丢掉那个页面。
     *
     * 按钮 menu_id 本身对导航<b>无害</b> —— {@code AuthConvert#buildMenuTree} 第一件事就是
     * 移除按钮，而 {@code completeMenuAncestors} 会沿菜单父链把页面补出来。
     * 有害的只是「本该选页面却选了按钮」那一种，即两条不同页面共用一个权限串的情形。
     */
    private Long chooseMenuId(ResourceDO anchor, List<ResourceDO> members,
                              Set<Long> navMenuIds, CollapsePlan plan) {
        if (anchor.getMenuId() != null) {
            return anchor.getMenuId();
        }
        Long navChoice = null;
        Long fallback = null;
        for (ResourceDO m : members) {
            Long mid = m.getMenuId();
            if (mid == null) {
                continue;
            }
            if (navMenuIds.contains(mid) && navChoice == null) {
                navChoice = mid;
            }
            if (fallback == null || mid < fallback) {
                fallback = mid;
            }
        }
        Long chosen = navChoice != null ? navChoice : fallback;
        if (navChoice != null && fallback != null && !navChoice.equals(fallback)) {
            plan.warnings.add(String.format(
                    "R1 生效：%s 的组内同时有导航菜单(%d)与按钮(%d)，选了导航菜单",
                    anchor.getPermission(), navChoice, fallback));
        }
        if (chosen == null) {
            plan.warnings.add(String.format(
                    "R1 无解：身份行 %s(id=%d) 的 menu_id 为 NULL，且组内 %d 个成员也都没有 menu_id —— "
                            + "它上面的授权将不再被 filterMenuDerived 认作套餐派生，套餐降级收不回",
                    anchor.getPermission(), anchor.getId(), members.size()));
        }
        return chosen;
    }

    /**
     * 为不存在锚点的身份构造新行
     *
     * <h3>type 为什么是 ACTION</h3>
     *
     * 阶段 0 报告第七节留了这个问题（「塌缩后中间节点应该是什么类型」）。
     * 答案取决于它塌缩之后<b>还有没有孩子</b>：本方法建出来的行是若干按钮合并后的落点，
     * 它自己就是授权点，不是容器 —— 容器语义属于 {@code ResourceTypeEnum#MENU}，
     * 而 MENU 在本体系里的既定含义是「树的中间节点」（阶段 0 实测：84 个有 permission 的
     * MENU 行<b>恰好</b>就是全部中间节点，无一例外）。
     *
     * {@code defaultEffect} 上两者没有差别（MENU 与 ACTION 都是 DENY），
     * 所以这个选择纯粹是为了让「类型」这个词继续说实话。
     *
     * <h3>name 取哪个</h3>
     *
     * 优先取 VIEW 那一行的名字（{@code :query} / {@code :list}），它通常就是页面名
     * （「用户查询」而不是「用户删除」）；没有 VIEW 行时取 id 最小的那行。
     */
    private ResourceDO newIdentityTemplate(String identity, List<ResourceDO> members,
                                           Set<Long> navMenuIds,
                                           Map<String, ResourceDO> anchorByPermission,
                                           CollapsePlan plan) {
        ResourceDO template = new ResourceDO();
        template.setPermission(identity);
        template.setType(ResourceTypeEnum.ACTION.getCode());
        template.setStatus(CommonStatusEnum.ENABLE.getStatus());
        // ⚠️ defaultEffect() 返回的就是字符串（"ALLOW"/"DENY"），不是枚举 —— 别再 .name()
        //
        // 🔴 这里写死 DENY 不是「跟着 type 走」，而是<b>算出来的</b>：新建行要替两样东西作答 ——
        // ① 各成员串，塌缩前落到自己那行的 default_effect
        //    （快照里启用的 575 个 ACTION 行全是 DENY，无一例外）；
        // ② 裸身份串自己，塌缩前<b>库里压根没有这一行</b>，
        //    而 GrantDecisionService#hasPermission 对查不到资源的情形直接 return false。
        // ②等价于最严的 DENY，所以「取两者最严」的结果恒为 DENY。
        // 换句话说：这一行不存在需要按数据算的余地，与 applyStrictestDefaultEffect
        // 那 7 例不同（那边锚点是已存在的 ALLOW 行，才真出现了取舍）
        template.setDefaultEffect(ResourceTypeEnum.ACTION.defaultEffect());

        ResourceDO nameDonor = members.stream()
                .filter(m -> GrantActionEnum.VIEW == resolver.resolve(m.getPermission()).getAction())
                .min(Comparator.comparing(ResourceDO::getId))
                .orElse(members.get(0));
        template.setName(nameDonor.getName());
        template.setSort(nameDonor.getSort());

        Long menuId = null;
        Long fallback = null;
        for (ResourceDO m : members) {
            Long mid = m.getMenuId();
            if (mid == null) {
                continue;
            }
            if (navMenuIds.contains(mid) && menuId == null) {
                menuId = mid;
            }
            if (fallback == null || mid < fallback) {
                fallback = mid;
            }
        }
        template.setMenuId(menuId != null ? menuId : fallback);

        // 父节点按命名约定找：a:b:c 的父是 a:b，找不到就挂根下（与 attachToConventionParent 同一口径）。
        // 复用第 1 步建好的 permission -> 行 索引；在这里现扫一遍是 O(n)，
        // 而本方法每个新建身份都要跑一次，35 个身份就是 35 次全表扫
        ResourceDO parent = null;
        int lastColon = identity.lastIndexOf(':');
        if (lastColon > 0) {
            parent = anchorByPermission.get(identity.substring(0, lastColon));
        }
        template.setParentId(parent != null ? parent.getId() : ResourceDO.ID_ROOT);
        String parentPath = parent != null ? parent.getPath() : null;
        template.setPath(ResourceDO.buildPath(parentPath, identity));
        template.setDepth(parent != null && parent.getDepth() != null
                ? (short) (parent.getDepth() + 1) : (short) 1);

        plan.warnings.add(String.format("需要新建身份行 %s（组内 %d 行并入，menu_id=%s，parent=%s）",
                identity, members.size(), template.getMenuId(),
                parent != null ? parent.getPermission() : "ROOT"));
        return template;
    }

    // ==================== R7 / R9：授权动作改写 ====================

    /**
     * 算一条授权塌缩后该写成什么动作
     *
     * <h3>R9：具体动作一律改写成源串解析出的动作</h3>
     *
     * 今天 {@code sys_grant.action} 有 98.3% 是 VIEW（1802 条活授权里 1771 条），
     * 因为 {@code GrantDecisionService#hasPermission} 那个重载把 action 硬编码成 VIEW ——
     * 动词的信息全在资源身份串里，动作维度基本是空的。
     * 塌缩把动词从身份串挪进动作维度，所以这一列<b>必须</b>跟着改：
     * 挂在 {@code system:user:create} 上的 VIEW 授权，塌缩后是挂在 {@code system:user} 上的
     * <b>CREATE</b>。留着 VIEW 的话判定侧解析出 CREATE、库里只有 VIEW，
     * 结果是新增/修改/删除/导出/导入按钮全部静默消失。
     *
     * <h3>R7：{@code action='*'} 收窄，ALLOW 与 DENY 同办</h3>
     *
     * 阶段 0 只提出了 DENY 那一半（不收窄会让 {@code system:user} 上那 4 条
     * {@code DENY *} 连带封掉 UPDATE 与 VIEW —— 静默收权）。
     * 但 ALLOW 那一半同样必须收窄，方向相反而已：
     * 挂在 {@code system:user:create} 上的 {@code ALLOW *} 今天只放行那一个按钮，
     * 塌缩到 {@code system:user} 上之后 {@code *} 会匹配<b>全部</b>动作 ——
     * 一条授权凭空变成「这个资源什么都能干」，<b>静默放权</b>。
     *
     * 放权比收权更难发现：收权会有人来报「我点不了了」，放权没有人会报「我怎么突然能删了」。
     *
     * <p>实测今天 13 条 {@code action='*'} 的授权里，8 条挂在档 D 身份行上（不塌缩，原样保留），
     * 5 条是 DENY（4 条塌缩、1 条 {@code update-password} 是档 C 不塌缩），
     * 所以 ALLOW 那一半目前是空集。规则仍然要写全 ——
     * 下一条 {@code ALLOW *} 被配出来时，它不该取决于有没有人记得这件事。
     */
    private String resolveTargetAction(GrantDO g, ResourceDO source, String identity, CollapsePlan plan) {
        PermissionStringResolver.Resolution res = resolver.resolve(source.getPermission());
        GrantActionEnum target = res.getAction();
        if (GrantActionEnum.WILDCARD.getCode().equals(g.getAction())) {
            plan.denyNarrowings.add(new ActionNarrowing(g.getId(), identity,
                    GrantActionEnum.WILDCARD.getCode(), target.getCode(), g.getEffect()));
            if (!"DENY".equals(g.getEffect())) {
                plan.warnings.add(String.format(
                        "R7 收窄了一条 ALLOW 通配授权：grant_id=%d 在 %s 上是 ALLOW '*'，"
                                + "塌缩到 %s 后收窄为 %s（不收窄就等于凭空放开了该资源的全部动作）",
                        g.getId(), source.getPermission(), identity, target.getCode()));
            }
        }
        return target.getCode();
    }

    /**
     * 合并后的去重键：主体 + 目标身份 + 目标动作 + 效果 + 上下文 + 租户
     *
     * <p>{@code rule_id} / {@code field_mode} / {@code expire_time} <b>刻意不进键</b>：
     * 阶段 0 实测 38 个碰撞组、246 条授权，<b>0 组硬 ALLOW/DENY 冲突</b>，
     * 也就是说重复行之间只有这三个附属字段可能不同。把它们放进键会漏掉重复 ——
     * 于是「同一个人对同一个资源的同一个动作」在授权页上出现两行，删除时只删得掉一行。
     * 排除掉它们的代价是可能丢掉一份规则绑定，所以由
     * {@code warnIfDroppingAttachments} 逐条告警，让人来定这一条留哪个。
     */
    private String grantKey(GrantDO g, String identity, String action) {
        return String.join("\u0000",
                String.valueOf(g.getTenantId()),
                String.valueOf(g.getSubjectType()),
                String.valueOf(g.getSubjectRefId()),
                String.valueOf(g.getSubjectCode()),
                identity,
                String.valueOf(g.getContextResourceId()),
                action,
                String.valueOf(g.getEffect()));
    }

    // ==================== R2：反向展开 ====================

    /**
     * 建「(身份, 动作) → 塌缩前存在过的权限串」表
     *
     * <p>建表本身委托给 {@link PermissionStringResolver#buildExpansionTable}，
     * 那里写着「为什么源必须是实际存在过的串、绝不能按动词映射表反推」。
     * 🔴 委托而不是各建一份，是因为<b>运行时也要用同一张表</b>
     * （{@code GrantDecisionService#listAllowedPermissions}）：
     * 两份实现只要在一档上分叉，迁移就会把数据写成 A 口径、清单按 B 口径还原，
     * 表现为「大部分按钮正常、某一类按钮全没了」，且全程不报错。
     *
     * <p>本方法只负责两件计划器特有的事：
     * <ol>
     *   <li><b>决定喂什么源</b>：资源行自己的 {@code permission} ∪ {@code system_menu.permission}。
     *       计划器跑在迁移<b>之前</b>，塌缩行还在库里，所以第一个源是可用的 ——
     *       它恰好补上了导航表给不出的那部分（表单设计器运行时生成的资源）。
     *       运行时只剩菜单表一个源，实测差集为空，见上面那个方法的注释</li>
     *   <li><b>自检</b>：计划里每一条授权改写都必须能展开回至少一个串</li>
     * </ol>
     */
    private Map<String, Set<String>> buildReverseExpansion(Collection<ResourceDO> resources,
                                                           Set<String> menuPermissions,
                                                           CollapsePlan plan) {
        Set<String> sources = new LinkedHashSet<>(menuPermissions);
        for (ResourceDO r : resources) {
            if (StrUtil.isNotBlank(r.getPermission())) {
                sources.add(r.getPermission());
            }
        }
        Map<String, Set<String>> table = resolver.buildExpansionTable(sources);

        // 自检：走不到这个分支（源里就含资源行自己的串），
        // 但它是「按钮静默消失」的最后一道网 —— 与其相信推理，不如让推理错的时候喊出来
        for (GrantRewrite rewrite : plan.grantRewrites) {
            if (!table.containsKey(PermissionStringResolver.key(rewrite.getIdentity(), rewrite.getAction()))) {
                plan.warnings.add(String.format(
                        "🔴 R2 无法反向展开：身份 %s 的动作 %s 展开不出任何原始串 —— "
                                + "该权限点塌缩后不会出现在权限清单里，前端按钮会消失",
                        rewrite.getIdentity(), rewrite.getAction()));
            }
        }

        // 🔴 上面那个自检在计划器这一侧永远为真，因为 sources 里就有资源行自己的串。
        // 真正会漏的是【运行时那一侧】：GrantDecisionService#runtimeExpansionTable 只有
        // 导航表一个源（迁移后成员行已经不在库里，它没法像这里一样把资源行的串也喂进去）。
        // 于是「被退役 + 不在导航表里」的串，塌缩后就再也还原不出来，会从
        // listAllowedPermissions 里消失 —— 实测 ai:workflow 的 4 个动词串就是这样丢的
        // （那一族已被 R11 整族拦下，成员行留在库里，所以今天不再丢）。
        // 剩下的这些今天不构成丢失（它们塌缩前也进不了清单：自己 default_effect=DENY 且没有授权），
        // 但这是【结构性的洞】而不是【这一批数据碰巧没事】，所以必须喊出来
        List<String> unrestorable = new ArrayList<>();
        for (ResourceDO r : resources) {
            String permission = r.getPermission();
            if (plan.retiredResourceIds.contains(r.getId())
                    && StrUtil.isNotBlank(permission) && !menuPermissions.contains(permission)) {
                unrestorable.add(permission);
            }
        }
        if (!unrestorable.isEmpty()) {
            Collections.sort(unrestorable);
            plan.warnings.add(String.format(
                    "⚠️ R2 运行时还原不出来的串 %d 个（被退役、且不在 system_menu.permission 里）：%s —— "
                            + "runtimeExpansionTable 的源只有导航表，塌缩后 listAllowedPermissions 吐不出它们。"
                            + "今天无一构成丢失（实测这批串塌缩前也进不了清单），"
                            + "但将来谁给其中一个配上授权，那个权限点就会【授权在、清单里没有】。"
                            + "届时要么把串补进导航表，要么把运行时展开表的源放宽",
                    unrestorable.size(), String.join(", ", unrestorable)));
        }
        return table;
    }

    // ==================== 输出 ====================

    /**
     * 变更集
     *
     * <p>{@link #freeze()} 之后所有字段都换成不可修改视图，任何写入尝试都会抛
     * {@code UnsupportedOperationException}。这不是防御性编程的洁癖 ——
     * 落库方与评审方拿到的是同一个对象，如果它能被改，
     * 那「评审过的那份」与「实际执行的那份」就可能不是同一份，而这是一次不可回滚的迁移。
     *
     * <p>⚠️ 一律用 {@code Collections.unmodifiableXxx} 而不是 {@code Map.copyOf}/{@code Set.copyOf}：
     * 后两者<b>不保证迭代顺序</b>，于是同一个库跑两次可能打印出顺序不同的变更集，
     * 人肉评审时看到的就全是假差异；{@code Map.copyOf} 还会对 null 值抛 NPE，
     * 而 {@link #identityOf} 之外的表里 null 是有意义的（见各字段注释）。
     */
    @Getter
    public static class CollapsePlan {

        /** 资源行 id → 塌缩后的身份串。不在表里的行 = 不参与塌缩 */
        private Map<Long, String> resourceIdRemap = new LinkedHashMap<>();
        /** 身份串 → 新建行的模板。key 不在 {@link #identityOf} 里（那时还没有 id） */
        private Map<String, ResourceDO> createdIdentities = new LinkedHashMap<>();
        /** 身份串 → 存活锚点行 id。<b>只含已存在的锚点</b>；新建的在 {@link #createdIdentities} */
        private Map<String, Long> identityOf = new LinkedHashMap<>();
        /**
         * 存活锚点行 id → 塌缩后该挂的 menu_id（R1 的结果）
         *
         * <p><b>只含真的会变的那些</b>：锚点自己已有 menu_id 时不进表，
         * 挑不出 menu_id 时也不进表（绝不能拿 null 去覆盖掉一个本来有值的列）。
         * 新建身份行的 menu_id 在它自己的模板里
         */
        private Map<Long, Long> menuIdUpdates = new LinkedHashMap<>();
        /**
         * 存活锚点行 id → 合并后该行的 {@code default_effect}
         *
         * <p><b>只含真的会变的那些</b>（即锚点原本是 ALLOW、而并入的成员里有 DENY 的）。
         * 为什么必须变，见 {@code #strictestDefaultEffect}
         */
        private Map<Long, String> defaultEffectUpdates = new LinkedHashMap<>();
        /** 被合并掉、应当软删的资源行 id */
        private Set<Long> retiredResourceIds = new LinkedHashSet<>();
        /**
         * 因「身份行下面还有存活后代」而被整族拦下、不参与塌缩的身份串
         *
         * <p>判据与实测后果见 {@code #blockedIdentities}。这张表是<b>判定侧的同源开关</b>：
         * {@code GrantDecisionService#lookupOf} 靠「成员行还在不在库里」得出同一个结论，
         * 两边不需要共享这张表，但两边的答案必须一致 ——
         * 所以本表存在的意义是让评审能看见「哪些族没塌」，以及让测试能钉住这个集合。
         *
         * <p>⚠️ 拦下不等于「这一族永远不能塌」。要让它们也塌，得先让资源树的父子关系
         * 不再由权限串推导（见 {@code #blockedIdentities} 里那段），那是另一件事
         */
        private Set<String> blockedIdentities = new LinkedHashSet<>();

        /** 授权改写：grant id → (目标身份, 目标动作)。R9 */
        private List<GrantRewrite> grantRewrites = new ArrayList<>();
        /** R7 收窄掉的通配授权，单列出来供评审 —— 这是最容易出错的一类 */
        private List<ActionNarrowing> denyNarrowings = new ArrayList<>();
        /**
         * 因 {@link #defaultEffectUpdates} 而<b>由默认放行变成默认拒绝</b>的身份串
         *
         * <h3>🔴 这是本次迁移唯一一处<b>有意改变</b>判定结果的地方，必须单列出来</h3>
         *
         * 自校验（{@code CollapseMigrationSelfCheckTest}）的判据是「迁移前后差集为空」，
         * 而这一批<b>结构上不可能为空</b>：塌缩把 {@code system:dept}（DATASET id=2056，默认放行）与
         * {@code system:dept:query}（ACTION id=17，默认拒绝）并成同一行，而 {@code :query} 解析出的
         * 动作<b>就是 VIEW</b> —— 与裸身份串问的是同一个 {@code (行, VIEW)}。
         * 一个 {@code default_effect} 列装不下两个相反的答案，必须选一个。
         *
         * <p>⚠️ 别把这里的例子换成 {@code system:user}：那一族被 R11（存活后代继承泄漏）
         * 整族拦下，压根没进这张表，它的锚点至今仍是 ALLOW。
         * 本注释早期正是拿它举例的，照着去库里核对只会得出「注释在瞎写」的结论。
         * 真实的 7 个身份串由报告 {@code [计划] default_effect 改写 7 行} 逐条列出。
         *
         * <p>选 DENY 的依据（三条都是实测，见 {@code #applyStrictestDefaultEffect}）：
         * 选 ALLOW 会让 464 个决策点从无权变有权，是越权；
         * 选 DENY 只让裸身份串自己掉出 {@code listAllowedPermissions}，
         * 而<b>全库没有任何 {@code @ss.hasPermission} 或 {@code v-hasPermi} 比的是裸身份串</b>。
         *
         * <p>自校验拿这张表当<b>白名单</b>用：断言「收紧的决策点 ⊆ 本表推出的集合」且
         * 「放开的决策点 = 0」。所以新增任何一处默认值改写都会立刻让自校验变红，
         * 而不是悄悄混进「已知差异」里
         */
        private Set<String> defaultEffectNarrowings = new LinkedHashSet<>();
        /** R10 合并后完全重复、应当软删的授权 id */
        private Set<Long> redundantGrantIds = new LinkedHashSet<>();
        /** 原样不动的授权 id（R3 空权限行 / R4 停用行 / 已达标族） */
        private Set<Long> untouchedGrantIds = new LinkedHashSet<>();

        /** (身份, 动作) → 塌缩前的权限串集合。R2，消费方是 listAllowedPermissions */
        private Map<String, Set<String>> reverseExpansion = new LinkedHashMap<>();

        /** 每个资源行的定性结果，供测试逐行断言 */
        private Map<Long, Classification> classification = new LinkedHashMap<>();
        /** 每条授权塌缩后的目标动作，供 {@link #grantKey} 分桶与测试断言 */
        private Map<Long, String> plannedAction = new LinkedHashMap<>();

        /** 需要人工过目的条目。评审时先读这个，再去读上面那些表 */
        private List<String> warnings = new ArrayList<>();

        /** 参与塌缩的身份数 = 已有锚点的 + 需要新建的 */
        public int identityCount() {
            return identityOf.size() + createdIdentities.size();
        }

        /** 需要改写的授权条数（不含被判为重复而软删的） */
        public int grantRewriteCount() {
            return grantRewrites.size();
        }

        /** 定性统计，供报告与测试断言 */
        public Map<Kind, Integer> classificationSummary() {
            Map<Kind, Integer> out = new LinkedHashMap<>();
            for (Kind k : Kind.values()) {
                out.put(k, 0);
            }
            for (Classification c : classification.values()) {
                out.merge(c.kind, 1, Integer::sum);
            }
            return out;
        }

        private void freeze() {
            resourceIdRemap = Collections.unmodifiableMap(resourceIdRemap);
            createdIdentities = Collections.unmodifiableMap(createdIdentities);
            identityOf = Collections.unmodifiableMap(identityOf);
            menuIdUpdates = Collections.unmodifiableMap(menuIdUpdates);
            defaultEffectUpdates = Collections.unmodifiableMap(defaultEffectUpdates);
            retiredResourceIds = Collections.unmodifiableSet(retiredResourceIds);
            blockedIdentities = Collections.unmodifiableSet(blockedIdentities);
            grantRewrites = Collections.unmodifiableList(grantRewrites);
            denyNarrowings = Collections.unmodifiableList(denyNarrowings);
            defaultEffectNarrowings = Collections.unmodifiableSet(defaultEffectNarrowings);
            redundantGrantIds = Collections.unmodifiableSet(redundantGrantIds);
            untouchedGrantIds = Collections.unmodifiableSet(untouchedGrantIds);
            reverseExpansion = Collections.unmodifiableMap(reverseExpansion);
            classification = Collections.unmodifiableMap(classification);
            plannedAction = Collections.unmodifiableMap(plannedAction);
            warnings = Collections.unmodifiableList(warnings);
        }
    }

    /**
     * 一条授权的改写意图
     *
     * <p>目标是<b>身份串</b>而不是资源 id：新建的身份行在计划阶段还没有 id，
     * 让执行方先 insert 拿到 id、再按身份串回填，比在计划里塞假 id 安全 ——
     * 假 id 一旦与真实序列撞上，写进去的就是别人的行，而且不会报错。
     */
    @Getter
    public static class GrantRewrite {
        private final Long grantId;
        private final String identity;
        private final String action;

        public GrantRewrite(Long grantId, String identity, String action) {
            this.grantId = grantId;
            this.identity = identity;
            this.action = action;
        }

        @Override
        public String toString() {
            return "grant " + grantId + " → " + identity + " + " + action;
        }
    }

    /**
     * 一条被收窄的通配授权（R7）
     *
     * <p>单列出来是因为这一类的错误方向<b>取决于 effect</b>：
     * DENY 不收窄是静默收权，ALLOW 不收窄是静默放权。评审时要一条条看，
     * 混在几千条 {@link GrantRewrite} 里等于没有评审。
     */
    @Getter
    public static class ActionNarrowing {
        private final Long grantId;
        private final String identity;
        private final String fromAction;
        private final String toAction;
        private final String effect;

        public ActionNarrowing(Long grantId, String identity, String fromAction,
                               String toAction, String effect) {
            this.grantId = grantId;
            this.identity = identity;
            this.fromAction = fromAction;
            this.toAction = toAction;
            this.effect = effect;
        }

        @Override
        public String toString() {
            return "grant " + grantId + " [" + effect + "] " + identity
                    + " : " + fromAction + " → " + toAction;
        }
    }

    /**
     * 供测试与落库方按身份批量取用：某个身份下并入的全部源资源行 id
     */
    public static Map<String, List<Long>> membersByIdentity(CollapsePlan plan,
                                                            Collection<ResourceDO> resources) {
        Map<String, List<Long>> out = new LinkedHashMap<>();
        for (ResourceDO r : resources) {
            String identity = plan.getResourceIdRemap().get(r.getId());
            if (identity != null) {
                out.computeIfAbsent(identity, k -> new ArrayList<>()).add(r.getId());
            }
        }
        return out;
    }

    /**
     * 计划里出现的全部身份（含新建的与已有锚点的），按字典序
     *
     * <p>⚠️ 两张表都要收：{@link CollapsePlan#getIdentityOf()} 只有已存在锚点的身份，
     * 新建的那批在 {@link CollapsePlan#getCreatedIdentities()} 里。
     * 只读前者会少掉 35 个身份（实测），而少掉的那批恰好是
     * 「组内没有任何一行能当锚点」的，最容易出问题
     */
    public static List<String> sortedIdentities(CollapsePlan plan) {
        Set<String> all = new LinkedHashSet<>(plan.getIdentityOf().keySet());
        all.addAll(plan.getCreatedIdentities().keySet());
        List<String> out = new ArrayList<>(all);
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /** 供测试断言「反向展开表里不许有库中不存在的串」（R2 的实质） */
    public static Set<String> allExpandedPermissions(CollapsePlan plan) {
        Set<String> out = new LinkedHashSet<>();
        for (Set<String> v : plan.getReverseExpansion().values()) {
            out.addAll(v);
        }
        return out;
    }

}
