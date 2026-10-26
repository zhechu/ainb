package cn.enlev.ainb.module.system.framework.permission.core.scan;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.module.system.dal.dataobject.permission.GrantDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.enums.permission.ResourceTypeEnum;
import cn.enlev.ainb.module.system.framework.permission.core.scan.ResourceCollapseApplier.CollapsedWorld;
import cn.enlev.ainb.module.system.framework.permission.core.scan.ResourceCollapseApplier.IdentityIdAllocator;
import cn.enlev.ainb.module.system.framework.permission.core.scan.ResourceCollapsePlanner.CollapsePlan;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResourceCollapseApplier} 的单元测试类
 *
 * <h3>这一层测的不是「计划对不对」，是「计划有没有被如实执行」</h3>
 *
 * 决策规则由 {@link ResourceCollapsePlannerTest} 那 27 个用例钉住。这里只钉一件事：
 * 把同一份计划应用到快照上，得到的那个「迁移后的世界」是不是计划说的那个样子。
 *
 * 之所以值得单独一层：阶段 2 的成败判据（自校验）要拿这个世界去跑判定引擎，
 * 与迁移前的世界比差集。如果 applier 少搬一条授权、或者<b>就地</b>改了入参
 * （于是「前」与「后」是同一份数据），差集会恒为空 —— 校验全绿，而库里已经改坏了。
 * 这类失败没有任何症状，所以它必须有测试。
 *
 * <h3>变异探针（基线 14 个用例全绿，逐个改坏 applier 后实测的失败用例）</h3>
 *
 * 「测试是绿的」与「测试能发现问题」是两件事。下面每一条都真跑过
 * （{@code _scratch/applier_mutation_probe.py} 跑 A1–A14、{@code _scratch/a15_probe.py} 跑 A15，
 * 跑完把源文件恢复成逐字节一致的基线）：
 *
 * <table border="1">
 *   <caption>十五次变异与其被捕获情况</caption>
 *   <tr><th>#</th><th>改坏的地方</th><th>失败数</th></tr>
 *   <tr><td>A1</td><td>资源行不复制，就地改入参（自校验会恒绿）</td><td>1</td></tr>
 *   <tr><td>A2</td><td>授权行不复制，就地改入参</td><td>1</td></tr>
 *   <tr><td>A3</td><td>不应用 menuIdUpdates（R1 的结果被算出来又丢掉）</td><td>2</td></tr>
 *   <tr><td>A4</td><td>不跳过已退役的资源行</td><td>4</td></tr>
 *   <tr><td>A5</td><td>不跳过 R10 判为重复的授权</td><td>1</td></tr>
 *   <tr><td>A6</td><td>改写只搬 resource_id，不改动作（R9 被拆散）</td><td>2</td></tr>
 *   <tr><td>A7</td><td>改写只换动作，不搬 resource_id</td><td>5</td></tr>
 *   <tr><td>A8</td><td>新建身份行的 id 不进 identityToResourceId</td><td>3</td></tr>
 *   <tr><td>A9</td><td>身份表不从计划器的 identityOf 播种（已有锚点全丢）</td><td>8</td></tr>
 *   <tr><td>A10</td><td>去掉 parent_id 悬空自检</td><td>1</td></tr>
 *   <tr><td>A11</td><td>去掉「授权不在任何一类里」那道抛</td><td>1</td></tr>
 *   <tr><td>A12</td><td>去掉分配器返回 null 的那道抛</td><td>1（第一版<b>逃过</b>，见下）</td></tr>
 *   <tr><td>A13</td><td>交出去的三个集合不封口</td><td>1</td></tr>
 *   <tr><td>A14</td><td>sequentialFrom 差一（从 max 而不是 max+1 开始）</td><td>4</td></tr>
 *   <tr><td>A15</td><td>不应用 defaultEffectUpdates（合并后不取最严默认效果）</td><td>1</td></tr>
 * </table>
 *
 * 🔴 A15 是<b>补跑</b>的，前十四次跑的时候这一行还没被打过，而它恰恰是本表里后果最贵的一条：
 * 漏掉它不会报错、不会异常、不会让任何一次调用返回 4xx —— 表现是那个身份上所有
 * <b>未被显式授权</b>的动作从「默认拒绝」变成「默认放行」。
 * 计划器算出来的 {@code defaultEffectUpdates} 今天有 7 行，
 * 自校验实测这 7 行覆盖 464 个决策点，全部落在「迁移后收紧」那一个方向
 * （即：应用了才收紧，不应用就是放开）。只有一个用例守着它
 * （{@code testApply_跨类型合并后默认效果收紧}），改这个用例之前先重跑 A15
 *
 * 🔴 <b>A12 第一轮是真的没被抓到</b>：去掉发号处那道抛之后，null 一路走到授权改写，
 * 在「目标身份没有对应资源行」那里抛出一个<b>同样是 IllegalStateException、
 * 同样带着身份串</b>的异常 —— 而当时那条用例只断言了异常类型加「消息里含 system:user」，
 * 于是两次抛法都算通过。改成断言消息里必须含「没拿到主键」之后才抓到。
 * 这条留在这里当提醒：<b>断言异常类型几乎等于没断言</b>，一条路径上往往有好几处会抛同一个类型。
 *
 * <p>另有一条没写进表里，因为它<b>结构上不可达</b>，写了就是永远测不到的死分支：
 * 「迁移后仍有授权指向已退役的资源」。计划器第 2 步把 {@code resourceIdRemap} 与
 * {@code retiredResourceIds} 在同一个循环里成对写入（键集恒等），
 * 第 3 步又对每条命中 remap 的授权强制二选一（改写 / 判为重复），所以这种形态造不出来。
 * 与之相对，{@code parent_id} 悬空是<b>数据决定的</b>（今天实测 0 例，但换一份库就可能有），
 * 所以那一处保留了自检并且有用例。
 *
 * <p>🔴 删掉或放宽任何一个用例之前，先把它对应的那一行重跑一遍。
 *
 * @author 走进新时代
 */
public class ResourceCollapseApplierTest {

    private final ResourceCollapsePlanner planner =
            new ResourceCollapsePlanner(new PermissionStringResolver());

    private static final Integer ENABLE = CommonStatusEnum.ENABLE.getStatus();

    // ==================== 整体形状 ====================

    /**
     * 六个动词按钮塌缩到一个已有锚点上：退役行消失、锚点的 menu_id 被改写、
     * 六条授权全部搬到锚点并各自换成解析出的动作
     */
    @Test
    public void testApply_六动词世界塌到已有锚点() {
        List<ResourceDO> resources = List.of(
                anchor(9L, "system:user"),
                action(10L, "system:user:query", 1L),
                action(11L, "system:user:create", 2L),
                action(12L, "system:user:update", 3L),
                action(13L, "system:user:delete", 4L),
                action(14L, "system:user:export", 5L),
                action(15L, "system:user:import", 6L));
        List<GrantDO> grants = List.of(
                grant(100L, 10L), grant(101L, 11L), grant(102L, 12L),
                grant(103L, 13L), grant(104L, 14L), grant(105L, 15L));

        CollapsedWorld world = apply(resources, grants);

        // 资源：只剩锚点
        assertEquals(1, world.resources().size(), "实际：" + dump(world.resources()));
        ResourceDO survivor = world.resources().get(0);
        assertEquals(9L, survivor.getId());
        assertEquals("system:user", survivor.getPermission());
        // R1：锚点原本 menu_id=NULL，计划挑了组内最小的 1 挂上去
        assertEquals(Long.valueOf(1L), survivor.getMenuId(),
                "menuIdUpdates 没被应用 ⟹ 这个页面从导航里消失");
        assertEquals(Set.of("system:user"), world.identityToResourceId().keySet());
        assertEquals(Long.valueOf(9L), world.identityToResourceId().get("system:user"));

        // 授权：六条全在，全部指向锚点，动作各就各位
        assertEquals(6, world.grants().size(), "实际：" + dump(world.grants()));
        for (GrantDO g : world.grants()) {
            assertEquals(9L, g.getResourceId(), "grant " + g.getId() + " 没搬到身份行上");
        }
        assertEquals("VIEW", actionOf(world, 100L));
        assertEquals("CREATE", actionOf(world, 101L));
        assertEquals("UPDATE", actionOf(world, 102L));
        assertEquals("DELETE", actionOf(world, 103L));
        assertEquals("EXPORT", actionOf(world, 104L));
        assertEquals("IMPORT", actionOf(world, 105L));
    }

    /**
     * R1 的优先级要真的落到 menu_id 列上：导航菜单（type≠按钮）压过更小的 id
     *
     * <p>计划器那侧已经钉了「挑哪个」，这里钉的是「挑出来的那个有没有被写进去」——
     * 两件事分开错：applier 完全可以把 {@code menuIdUpdates} 读出来又忘了 set
     */
    @Test
    public void testApply_导航菜单优先于更小的menuId() {
        List<ResourceDO> resources = List.of(
                anchor(9L, "system:user"),
                action(10L, "system:user:query", 1L),   // id 更小，但是按钮
                action(11L, "system:user:create", 3L));  // 导航菜单
        List<GrantDO> grants = List.of(grant(100L, 10L), grant(101L, 11L));

        CollapsePlan plan = planner.plan(resources, grants, Set.of(), Set.of(3L));
        CollapsedWorld world = ResourceCollapseApplier.apply(resources, grants, plan,
                ResourceCollapseApplier.sequentialFrom(resources));

        assertEquals(Long.valueOf(3L), world.resources().get(0).getMenuId(),
                "实际：" + world.resources().get(0).getMenuId());
    }

    /**
     * 🔴 计划算出的 {@code default_effect} 改写要真的落到那一列上
     *
     * <p>这一条与 menu_id 那条同等重要，但后果重得多：漏应用的表现不是「页面少一个」，
     * 而是那批成员串<b>静默从默认拒绝变成默认放行</b>，全程 HTTP 200、没有任何异常。
     *
     * <p>{@code CollapseMigrationSelfCheckTest} 量到的数字：这张表<b>7 行</b>
     * （1 个 {@code system:dept} + 6 个 {@code infra:form-design-run:*}），
     * 应用之后共 <b>464 个决策点</b>落在「迁移后收紧」那一个方向上、涉及 7 个身份串，
     * 未登记的差异 0 个。反过来（不应用）就是这 464 个点不再收紧，
     * 同时成员串那一批改走锚点的 ALLOW 回落 —— 那是越权方向。
     *
     * <p>⚠️ 本注释早期写的是「660 个决策点 / 9 个跨类型身份 / 11 个用户 × 9 个成员数」，
     * 三个数都错（那个 11×9 连 660 都不等于）。660 确实在同一份报告里，
     * 但它是 {@code [计划] …原样 660}，指<b>一条都没改写的授权行数</b>，与决策点无关。
     * 权威口径只有报告里那两行：{@code [计划] default_effect 改写 7 行} 与
     * {@code [hasPermission] …差异 617 个（迁移后收紧 464 / 迁移后放开 153）}
     *
     * <p>形态取自真实数据：{@code infra:form-design-run:*} 这一族的锚点是 DATASET（默认 ALLOW），
     * 并进来的遗留 ACTION 行全是 DENY
     */
    @Test
    public void testApply_跨类型合并后默认效果收紧() {
        List<ResourceDO> resources = List.of(
                dataset(9L, "infra:form-design-run:x"),
                action(10L, "infra:form-design-run:x:query", 1L),
                action(11L, "infra:form-design-run:x:create", 2L));
        List<GrantDO> grants = List.of(grant(100L, 10L), grant(101L, 11L));
        String before = dump(resources);

        CollapsedWorld world = apply(resources, grants);

        assertEquals(1, world.resources().size(), "实际：" + dump(world.resources()));
        assertEquals("DENY", world.resources().get(0).getDefaultEffect(),
                "defaultEffectUpdates 没被应用 ⟹ 这个身份上所有未授权的动作从「默认拒绝」变成「默认放行」");
        assertEquals(before, dump(resources), "改的是复制出来的那一行，入参不许动");
    }

    // ==================== 🔴 不许就地改入参 ====================

    /**
     * 自校验的有效性完全建立在这一条上
     *
     * <p>校验要跑两遍判定：一遍用迁移前的世界，一遍用迁移后的。
     * 如果 applier 就地改入参，那第二遍拿到的其实是<b>同一份</b>数据，
     * 差集恒为空，校验会对着一次改坏了的迁移报「全绿」。
     *
     * <p>这是本类最该被变异探针打的一条：把 {@code BeanUtils.toBean} 换成直接复用入参对象，
     * 只有这一个用例会挂
     */
    @Test
    public void testApply_入参两个列表一个字节都不许被改() {
        List<ResourceDO> resources = List.of(
                anchor(9L, "system:user"),
                action(10L, "system:user:query", 1L),
                action(11L, "system:user:create", 2L));
        List<GrantDO> grants = List.of(grant(100L, 10L), grant(101L, 11L));
        String resourcesBefore = dump(resources);
        String grantsBefore = dump(grants);

        CollapsedWorld world = apply(resources, grants);

        // 1. 内容没变
        assertEquals(resourcesBefore, dump(resources), "资源入参被就地改了");
        assertEquals(grantsBefore, dump(grants), "授权入参被就地改了");
        // 2. 而且确实是副本，不是同一个对象
        for (ResourceDO copy : world.resources()) {
            for (ResourceDO original : resources) {
                assertNotSame(original, copy, "资源 " + copy.getId() + " 是入参本身，不是副本");
            }
        }
        for (GrantDO copy : world.grants()) {
            for (GrantDO original : grants) {
                assertNotSame(original, copy, "授权 " + copy.getId() + " 是入参本身，不是副本");
            }
        }
        // 3. 副本确实带着新值（否则上面两条只是「什么都没做」的证明）
        assertEquals(9L, world.grants().get(0).getResourceId());
        assertEquals(10L, grants.get(0).getResourceId(), "原始世界里这条授权仍应指向退役前的行");
    }

    // ==================== 新建身份行 ====================

    /**
     * 组内没有任何一行能当锚点时，计划器只给模板、不给 id；id 由分配器发
     *
     * <p>实测这一批有 35 个身份，恰好是最容易出错的那批 ——
     * 它们既没有现成行可以复用，menu_id 也要从组成员里挑
     */
    @Test
    public void testApply_新建身份行拿分配器发的id() {
        List<ResourceDO> resources = List.of(
                action(10L, "system:user:query", 1L),
                action(11L, "system:user:create", 2L));
        List<GrantDO> grants = List.of(grant(100L, 10L), grant(101L, 11L));

        CollapsedWorld world = apply(resources, grants);

        assertEquals(1, world.resources().size(), "实际：" + dump(world.resources()));
        ResourceDO created = world.resources().get(0);
        assertEquals(12L, created.getId(), "sequentialFrom 应该从现存最大 id(11) + 1 开始");
        assertEquals("system:user", created.getPermission());
        assertEquals(ResourceTypeEnum.ACTION.getCode(), created.getType());
        assertEquals(CommonStatusEnum.ENABLE.getStatus(), created.getStatus());
        // ⚠️ defaultEffect() 返回的是字符串本身，不是枚举
        assertEquals(ResourceTypeEnum.ACTION.defaultEffect(), created.getDefaultEffect());
        // 名字取 VIEW 那一行（页面名通常是「用户查询」而不是「用户删除」）
        assertEquals("system:user:query", created.getName());
        assertEquals(Long.valueOf(1L), created.getMenuId(), "组内最小的 menu_id 兜底");
        assertEquals(ResourceDO.ID_ROOT, created.getParentId());
        assertEquals(Long.valueOf(12L), world.identityToResourceId().get("system:user"));

        // 两条授权都搬到新行上，动作各自改写
        assertEquals(2, world.grants().size());
        for (GrantDO g : world.grants()) {
            assertEquals(12L, g.getResourceId());
        }
        assertEquals("VIEW", actionOf(world, 100L));
        assertEquals("CREATE", actionOf(world, 101L));
    }

    /**
     * 已有锚点与新建身份同时存在时，{@code identityToResourceId} 要合起来给全
     *
     * <p>只读计划器的 {@code identityOf} 会少掉新建那批（实测少 35 个），
     * 于是那 35 个身份上的授权在 applier 里查不到目标 id
     */
    @Test
    public void testApply_身份表同时含已有锚点与新建行() {
        List<ResourceDO> resources = List.of(
                anchor(9L, "system:user"),
                action(10L, "system:user:query", 1L),
                // system:role 这一组没有锚点行
                action(20L, "system:role:query", 7L),
                action(21L, "system:role:delete", 8L));
        List<GrantDO> grants = List.of(
                grant(100L, 10L), grant(101L, 20L), grant(102L, 21L));

        CollapsedWorld world = apply(resources, grants);

        assertEquals(Set.of("system:user", "system:role"), world.identityToResourceId().keySet());
        assertEquals(Long.valueOf(9L), world.identityToResourceId().get("system:user"));
        assertEquals(Long.valueOf(22L), world.identityToResourceId().get("system:role"));
        assertEquals(9L, byId(world, 100L).getResourceId());
        assertEquals(22L, byId(world, 101L).getResourceId());
        assertEquals(22L, byId(world, 102L).getResourceId());
    }

    // ==================== R10 去重 / 原样不动 ====================

    /**
     * 塌缩后五元组完全相同的两条授权只留一条，另一条从世界里消失
     *
     * <p>{@code query} 与 {@code list} 都解析成 VIEW，所以这两条会撞进同一个桶。
     * 留着两条不会算错（判定引擎对同一五元组只命中一次），但库里会永久多一倍脏行
     */
    @Test
    public void testApply_重复授权被丢弃() {
        List<ResourceDO> resources = List.of(
                anchor(9L, "system:user"),
                action(10L, "system:user:query", 1L),
                action(11L, "system:user:list", 2L));
        List<GrantDO> grants = List.of(grant(100L, 10L), grant(101L, 11L));

        CollapsePlan plan = planner.plan(resources, grants, Set.of(), Set.of());
        assertEquals(Set.of(101L), plan.getRedundantGrantIds(), "前置：计划器应判 101 重复");

        CollapsedWorld world = ResourceCollapseApplier.apply(resources, grants, plan,
                ResourceCollapseApplier.sequentialFrom(resources));

        assertEquals(1, world.grants().size(), "实际：" + dump(world.grants()));
        assertEquals(100L, world.grants().get(0).getId(), "keeper 必须是 id 最小的那条");
        assertEquals(9L, world.grants().get(0).getResourceId());
        assertEquals("VIEW", world.grants().get(0).getAction());
    }

    /**
     * 不参与塌缩的资源（停用行、空权限行、DATASET 之类）上的授权原样留着
     *
     * <p>这一条盯的是「误伤」方向：applier 若把 untouched 的授权一起搬走，
     * 表现是某个本来正常的功能突然没了权限
     */
    @Test
    public void testApply_范围外资源上的授权原样不动() {
        ResourceDO dataset = row(30L, "crm:customer", ResourceTypeEnum.DATASET.getCode(), ENABLE, null);
        ResourceDO disabled = row(31L, "infra:build:list", ResourceTypeEnum.ACTION.getCode(),
                CommonStatusEnum.DISABLE.getStatus(), 4L);
        List<ResourceDO> resources = new ArrayList<>(List.of(
                anchor(9L, "system:user"),
                action(10L, "system:user:query", 1L),
                dataset, disabled));
        List<GrantDO> grants = List.of(
                grant(100L, 10L),
                grant(101L, 30L),   // DATASET：已是目标形态
                grant(102L, 31L));  // 停用行：搬它就是静默放权

        CollapsedWorld world = apply(resources, grants);

        // 三行都活着（退役的只有 10）
        assertEquals(3, world.resources().size(), "实际：" + dump(world.resources()));
        // 范围外的两条授权，resource_id 与 action 一个字节都没变
        GrantDO untouchedDataset = byId(world, 101L);
        assertEquals(30L, untouchedDataset.getResourceId());
        assertEquals("VIEW", untouchedDataset.getAction());
        GrantDO untouchedDisabled = byId(world, 102L);
        assertEquals(31L, untouchedDisabled.getResourceId());
        assertEquals("VIEW", untouchedDisabled.getAction());
    }

    // ==================== 对不上就抛 ====================

    /**
     * 计划与快照对不上时必须抛，绝不能返回一份半对的世界
     *
     * <p>什么时候会真的发生：计划是干跑阶段算的，落库是后来才做的 ——
     * 中间有人在页面上又配了一条授权。那条新授权不在计划的任何一类里，
     * applier 无从知道它该搬去哪。静默跳过它就等于静默改了一次权限
     */
    @Test
    public void testApply_授权不在变更集任何一类里时抛() {
        List<ResourceDO> resources = List.of(
                anchor(9L, "system:user"),
                action(10L, "system:user:query", 1L));
        List<GrantDO> planned = List.of(grant(100L, 10L));
        CollapsePlan plan = planner.plan(resources, planned, Set.of(), Set.of());

        // 快照比计划多一条（模拟「算完计划之后库里又多了一笔授权」）
        List<GrantDO> drifted = List.of(grant(100L, 10L), grant(999L, 10L));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ResourceCollapseApplier.apply(resources, drifted, plan,
                        ResourceCollapseApplier.sequentialFrom(resources)));
        assertTrue(e.getMessage().contains("999"), "报错里要带上是哪条授权，实际：" + e.getMessage());
    }

    /**
     * 存活行的父节点被合并掉时必须抛
     *
     * <p>为什么不自动重挂：{@code GrantDecisionService#buildChainFromMap} 沿 parentId 上溯，
     * 找不到父节点就<b>提前停下</b> —— 「从分类继承放行 / 上级否决」的语义会静默改变，
     * 而判定照样返回一个看起来正常的 boolean。重挂还要连带重算整棵子树的 path 与 depth。
     *
     * <p>实测当前库里 0 例（1022 个存活行、540 个退役行，
     * 且 parent_id 指向不存在行的也是 0）。真出现了就该由人决定挂到哪
     */
    @Test
    public void testApply_存活行的父节点被退役时抛() {
        ResourceDO parent = action(10L, "system:user:query", 1L);
        ResourceDO child = row(30L, "system:user:col:mobile",
                ResourceTypeEnum.COLUMN.getCode(), ENABLE, null);
        child.setParentId(10L);
        child.setPath(ResourceDO.buildPath(parent.getPath(), child.getPermission()));
        child.setDepth((short) 2);
        List<ResourceDO> resources = List.of(parent, child);
        List<GrantDO> grants = List.of(grant(100L, 10L));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> apply(resources, grants));
        assertTrue(e.getMessage().contains("parent_id=10"), "实际：" + e.getMessage());
        assertTrue(e.getMessage().contains("system:user:col:mobile"), "实际：" + e.getMessage());
    }

    /**
     * 分配器发不出 id 时必须抛，而且要在<b>发号那一处</b>就抛
     *
     * <p>落库那侧的 id 来自 insert 回填，回填失败（比如唯一键冲突后被吞掉）就是 null。
     *
     * <p>🔴 断言的是消息内容而不只是异常类型 —— 去掉这道抛之后，null 会一路走到授权改写、
     * 在「目标身份没有对应资源行」那里抛出一个<b>同样是 IllegalStateException、
     * 同样带着身份串</b>的异常，只断类型的话这个变异探针抓不到（实测：A12 就是这么逃过去的）。
     * 而报错指错地方是有代价的：排查的人会去查身份表，真正的毛病却在 insert 回填
     */
    @Test
    public void testApply_分配器给不出id时抛() {
        List<ResourceDO> resources = List.of(
                action(10L, "system:user:query", 1L),
                action(11L, "system:user:create", 2L));
        List<GrantDO> grants = List.of(grant(100L, 10L));
        CollapsePlan plan = planner.plan(resources, grants, Set.of(), Set.of());
        IdentityIdAllocator noId = template -> null;

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ResourceCollapseApplier.apply(resources, grants, plan, noId));
        assertTrue(e.getMessage().contains("没拿到主键"), "实际：" + e.getMessage());
        assertTrue(e.getMessage().contains("system:user"), "实际：" + e.getMessage());
    }

    // ==================== 返回值封口 ====================

    /**
     * 交出去的三个集合都不可修改
     *
     * <p>与 {@link CollapsePlan} 同一条理由：评审看到的那份必须就是执行的那份。
     * 这是一次不可回滚的迁移
     */
    @Test
    public void testApply_返回的世界不可修改() {
        List<ResourceDO> resources = List.of(
                anchor(9L, "system:user"),
                action(10L, "system:user:query", 1L),
                action(11L, "system:user:create", 2L));
        List<GrantDO> grants = List.of(grant(100L, 10L), grant(101L, 11L));

        CollapsedWorld world = apply(resources, grants);

        assertThrows(UnsupportedOperationException.class,
                () -> world.resources().add(anchor(999L, "x")));
        assertThrows(UnsupportedOperationException.class,
                () -> world.grants().add(grant(999L, 9L)));
        assertThrows(UnsupportedOperationException.class,
                () -> world.identityToResourceId().put("x", 1L));
        // 上面三条 assertThrows 在「世界是空的」时也会全绿，所以得先钉住里面确实有东西
        assertEquals(1, world.resources().size());
        assertEquals(2, world.grants().size());
        assertEquals(1, world.identityToResourceId().size());
    }

    // ==================== 分配器 ====================

    @Test
    public void testSequentialFrom_从现存最大id加一开始且逐个递增() {
        List<ResourceDO> existing = List.of(action(10L, "a:b:query", 1L), action(7L, "a:c:query", 2L));
        IdentityIdAllocator allocator = ResourceCollapseApplier.sequentialFrom(existing);

        assertEquals(11L, allocator.nextId(null));
        assertEquals(12L, allocator.nextId(null));
        assertEquals(13L, allocator.nextId(null));
    }

    /** 空快照也得能发号（从 1 开始），否则一次全量重建会直接 NPE */
    @Test
    public void testSequentialFrom_空快照从1开始() {
        assertEquals(1L, ResourceCollapseApplier.sequentialFrom(List.of()).nextId(null));
    }

    // ==================== 夹具 ====================

    private CollapsedWorld apply(List<ResourceDO> resources, List<GrantDO> grants) {
        CollapsePlan plan = planner.plan(resources, grants, Set.of(), Set.of());
        return ResourceCollapseApplier.apply(resources, grants, plan,
                ResourceCollapseApplier.sequentialFrom(resources));
    }

    private static GrantDO byId(CollapsedWorld world, Long id) {
        for (GrantDO g : world.grants()) {
            if (id.equals(g.getId())) {
                return g;
            }
        }
        throw new AssertionError("迁移后的世界里没有授权 " + id + "，实际：" + dump(world.grants()));
    }

    private static String actionOf(CollapsedWorld world, Long grantId) {
        return byId(world, grantId).getAction();
    }

    /** 遗留族的启用 ACTION 行，即塌缩的输入形态 */
    private static ResourceDO action(Long id, String permission, Long menuId) {
        return row(id, permission, ResourceTypeEnum.ACTION.getCode(), ENABLE, menuId);
    }

    /** 身份锚点行：{@code PermissionTreeDeriver} 建出来的中间节点，实测 menu_id 全是 NULL */
    private static ResourceDO anchor(Long id, String permission) {
        return row(id, permission, ResourceTypeEnum.MENU.getCode(), ENABLE, null);
    }

    /**
     * 数据集锚点行 —— 与 {@link #anchor} 的区别只在类型，而这个区别正是本类要钉的东西：
     * DATASET 的 {@code default_effect} 是 ALLOW，MENU 与 ACTION 是 DENY
     */
    private static ResourceDO dataset(Long id, String permission) {
        return row(id, permission, ResourceTypeEnum.DATASET.getCode(), ENABLE, null);
    }

    private static ResourceDO row(Long id, String permission, String type, Integer status, Long menuId) {
        ResourceDO r = new ResourceDO();
        r.setId(id);
        r.setPermission(permission);
        r.setName(permission);
        r.setType(type);
        r.setStatus(status);
        r.setMenuId(menuId);
        r.setParentId(ResourceDO.ID_ROOT);
        r.setPath(ResourceDO.buildPath(null, permission));
        r.setDepth((short) 1);
        r.setDefaultEffect(ResourceTypeEnum.parse(type).defaultEffect());
        return r;
    }

    /** 套餐型授权行的形状：ROLE 主体 + ALLOW + VIEW + 无上下文 */
    private static GrantDO grant(Long id, Long resourceId) {
        GrantDO g = new GrantDO();
        g.setId(id);
        g.setSubjectType(SubjectTypeEnum.ROLE.name());
        g.setSubjectRefId(2L);
        g.setResourceId(resourceId);
        g.setAction("VIEW");
        g.setEffect("ALLOW");
        g.setTenantId(1L);
        return g;
    }

    /**
     * 把一份世界渲染成规范文本，供「入参不许被改」那条用例逐字节比对
     *
     * <p>只收会被 applier 碰到的列；{@code sort} 之类的列它压根不读，收进来只会稀释断言
     */
    private static String dump(List<?> rows) {
        StringBuilder sb = new StringBuilder();
        for (Object o : rows) {
            if (o instanceof ResourceDO r) {
                sb.append(String.format("R(%d,%s,%s,menu=%s,parent=%s,path=%s,depth=%s,status=%s,eff=%s)%n",
                        r.getId(), r.getPermission(), r.getType(), r.getMenuId(), r.getParentId(),
                        r.getPath(), r.getDepth(), r.getStatus(), r.getDefaultEffect()));
            } else if (o instanceof GrantDO g) {
                sb.append(String.format("G(%d,%s,%d,res=%d,ctx=%s,%s,%s,rule=%s,tenant=%d)%n",
                        g.getId(), g.getSubjectType(), g.getSubjectRefId(), g.getResourceId(),
                        g.getContextResourceId(), g.getAction(), g.getEffect(), g.getRuleId(),
                        g.getTenantId()));
            } else {
                throw new AssertionError("未知的行类型 " + o.getClass());
            }
        }
        return sb.toString();
    }

}
