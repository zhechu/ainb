package cn.enlev.ainb.module.system.framework.permission.core.scan;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.module.system.dal.dataobject.permission.GrantDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.enums.permission.GrantActionEnum;
import cn.enlev.ainb.module.system.enums.permission.ResourceTypeEnum;
import cn.enlev.ainb.module.system.framework.permission.core.scan.ResourceCollapsePlanner.ActionNarrowing;
import cn.enlev.ainb.module.system.framework.permission.core.scan.ResourceCollapsePlanner.CollapsePlan;
import cn.enlev.ainb.module.system.framework.permission.core.scan.ResourceCollapsePlanner.GrantRewrite;
import cn.enlev.ainb.module.system.framework.permission.core.scan.ResourceCollapsePlanner.Kind;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResourceCollapsePlanner} 的单元测试类
 *
 * <h3>为什么这个测试类的分量比一般单测重</h3>
 *
 * 它钉的是一次<b>不可回滚</b>迁移的决策规则。计划器本身没有写库能力，
 * 所以「规则对不对」这件事只能在计划这一层验证 —— 一旦落库，
 * 错的那一份就成了既成事实，而它的症状全是静默的（权限少了没人能查、权限多了没人会报）。
 *
 * 用例的形态大量取自《阶段2前置勘察报告》里的实测数据：
 * {@code agent:usage} 的同串两行（R1）、{@code infra:build:list} 与
 * {@code infra:codegen:query} 的停用行（R4）、{@code system:user} 上的
 * {@code DENY *}（R7）、119 行空权限叶子（R3）。
 * 换掉这些形态就等于换掉被测的事实。
 *
 * <h3>变异探针（基线 32 个用例全绿，逐个改坏规则后实测的失败数）</h3>
 *
 * 「测试是绿的」与「测试能发现问题」是两件事。下面每一条都真跑过，
 * 跑完把源文件恢复成逐字节一致的基线：
 *
 * <table border="1">
 *   <caption>十六次变异与其被捕获情况</caption>
 *   <tr><th>#</th><th>改坏的地方</th><th>失败数</th></tr>
 *   <tr><td>1</td><td>R9 不改写动作，原样返回 {@code g.getAction()}</td><td>5</td></tr>
 *   <tr><td>2</td><td>R4 的停用判定挪到解析之后（停用行也参与塌缩）</td><td>3</td></tr>
 *   <tr><td>3</td><td>R1 退回 {@code min(menu_id)}，不优先导航菜单</td><td>1</td></tr>
 *   <tr><td>4</td><td>R7 只收窄 DENY，放过 ALLOW</td><td>8</td></tr>
 *   <tr><td>5</td><td>R2 改回按规范后缀反推（造出库中不存在的串）</td><td>2</td></tr>
 *   <tr><td>6</td><td>去重时 keeper 不排序，退回入参顺序</td><td>1</td></tr>
 *   <tr><td>7</td><td>{@code freeze()} 不封反向展开表的值集合</td><td>1</td></tr>
 *   <tr><td>8</td><td>R11 的存活后代不减去「本次要退役的成员行」</td><td>3</td></tr>
 *   <tr><td>9</td><td>R11 整个不生效（第 1.5 步不调用 {@code blockedIdentities}）</td><td>2</td></tr>
 *   <tr><td>10</td><td>R11 只走 {@code parent_id} 口径，删掉物化路径口径</td><td>1</td></tr>
 *   <tr><td>11</td><td>R11 的物化路径口径不看 status（停用的后代也拦）</td><td>1</td></tr>
 *   <tr><td>12</td><td>R11 的 {@code parent_id} 口径不看 status</td><td>1</td></tr>
 *   <tr><td>13</td><td>R11 拦下的族不把定性改成 {@code BLOCKED}</td><td>1</td></tr>
 *   <tr><td>14</td><td>R11 拦下的族不从 {@code groups} 里摘掉（声明了拦下、实际照样塌）</td><td>2</td></tr>
 *   <tr><td>15</td><td>R11 不写进 {@code plan.blockedIdentities}（评审看不见哪些族没塌）</td><td>2</td></tr>
 *   <tr><td>16</td><td>{@code freeze()} 不封 {@code blockedIdentities}</td><td>1</td></tr>
 * </table>
 *
 * 🔴 第 8 条是本文件里最该盯的一行：它的失败方向不是「权限算错」，而是
 * <b>计划器一行都不改</b> —— 真实数据里成员行就是锚点的子节点，少了那一步减法，
 * 117 个族会全部被 R11 拦下，于是所有断言「差异为 0」的用例<b>照样全绿</b>。
 * 迁移静默地什么都没做，而 32 个用例里只有 3 个能发现。
 *
 * <p>另外两条变异（判定侧不镜像 R11：{@code lookupOf} 去掉守卫、
 * {@code listAllowedPermissions} 去掉 {@code isBlockedFamily} 判断）改的是
 * {@code GrantDecisionService}，由 {@code CollapseMigrationSelfCheckTest} 抓到，
 * 探针与结果记在那个类的 javadoc 里 —— 计划这一层看不见判定侧的镜像
 *
 * 🔴 删掉或放宽任何一个用例之前，先把它对应的那一行重跑一遍。
 * 这些规则的错误方向全是静默的（权限少了没人能查、权限多了没人会报），
 * 单测是它们唯一的一道网。
 *
 * @author 走进新时代
 */
public class ResourceCollapsePlannerTest {

    private final ResourceCollapsePlanner planner =
            new ResourceCollapsePlanner(new PermissionStringResolver());

    private static final Integer ENABLE = CommonStatusEnum.ENABLE.getStatus();
    private static final Integer DISABLE = CommonStatusEnum.DISABLE.getStatus();

    // ==================== R9：动作改写 ====================

    /**
     * 🔴 这一条是整个塌缩的承重墙
     *
     * 塌缩把动词从资源身份串挪进 {@code sys_grant.action}，所以 action <b>必须</b>跟着改。
     * 留着 VIEW 的话，判定侧解析出 CREATE、库里只有 VIEW ——
     * 新增/修改/删除/导出/导入按钮全部静默消失，全程 200、无报错。
     */
    @Test
    public void testPlan_R9_塌缩行的授权动作改写成源串解析出的动作() {
        List<ResourceDO> resources = List.of(
                anchor(9L, "system:user"),
                action(10L, "system:user:query", 1L),
                action(11L, "system:user:create", 2L),
                action(12L, "system:user:update", 3L),
                action(13L, "system:user:delete", 4L),
                action(14L, "system:user:export", 5L),
                action(15L, "system:user:import", 6L));
        List<GrantDO> grants = List.of(
                grant(100L, 10L, "VIEW", "ALLOW"),
                grant(101L, 11L, "VIEW", "ALLOW"),
                grant(102L, 12L, "VIEW", "ALLOW"),
                grant(103L, 13L, "VIEW", "ALLOW"),
                grant(104L, 14L, "VIEW", "ALLOW"),
                grant(105L, 15L, "VIEW", "ALLOW"));

        CollapsePlan plan = plan(resources, grants);

        assertEquals(1, plan.identityCount(), "六个按钮应该塌缩成一个身份");
        assertEquals(Long.valueOf(9L), plan.getIdentityOf().get("system:user"), "锚点行应该活下来");
        assertEquals(Set.of(10L, 11L, 12L, 13L, 14L, 15L), plan.getRetiredResourceIds());

        Map<Long, String> planned = plan.getPlannedAction();
        assertEquals("VIEW", planned.get(100L), "query → VIEW");
        assertEquals("CREATE", planned.get(101L), "create → CREATE，绝不是 VIEW");
        assertEquals("UPDATE", planned.get(102L));
        assertEquals("DELETE", planned.get(103L));
        assertEquals("EXPORT", planned.get(104L));
        assertEquals("IMPORT", planned.get(105L));

        // 变异探针：把 resolveTargetAction 改成「原样返回 g.getAction()」，
        // 下面这一条会同时挂 5 个 —— 它盯的正是「静默收权」那个方向
        for (Map.Entry<Long, String> e : planned.entrySet()) {
            if (Long.valueOf(100L).equals(e.getKey())) {
                continue;
            }
            assertFalse("VIEW".equals(e.getValue()),
                    "grant " + e.getKey() + " 的动作没有被改写，塌缩后这个按钮会静默消失");
        }
        // 每条改写的目标身份都必须是 system:user
        for (GrantRewrite r : plan.getGrantRewrites()) {
            assertEquals("system:user", r.getIdentity());
        }
        assertEquals(6, plan.grantRewriteCount(), "六条授权五元组各不相同，一条都不该被判为重复");
    }

    /**
     * 档 B 的动词同样要改写，尤其是 {@code reference}
     *
     * 阶段 0 的第一版映射表漏了 reference，把 39 行误判进档 C（不塌缩）。
     * 这里从计划这一层再钉一次：漏掉的表现是这 39 行的授权进 untouchedGrantIds
     */
    @Test
    public void testPlan_R9_档B动词也改写() {
        List<ResourceDO> resources = List.of(
                anchor(20L, "system:dict-data"),
                action(21L, "system:dict-data:list", 30L),
                action(22L, "system:dict-data:reference", 31L),
                action(23L, "system:dict-data:download", 32L));
        List<GrantDO> grants = List.of(
                grant(200L, 21L, "VIEW", "ALLOW"),
                grant(201L, 22L, "VIEW", "ALLOW"),
                grant(202L, 23L, "VIEW", "ALLOW"));

        CollapsePlan plan = plan(resources, grants);

        assertEquals("VIEW", plan.getPlannedAction().get(200L), "list → VIEW");
        assertEquals("REFERENCE", plan.getPlannedAction().get(201L), "reference → REFERENCE");
        assertEquals("EXPORT", plan.getPlannedAction().get(202L), "download → EXPORT");
        assertTrue(plan.getUntouchedGrantIds().isEmpty(), "档 B 的授权一条都不该被当成「不动」");
    }

    // ==================== R1：menu_id 归属 ====================

    /**
     * 🔴 实测形态：{@code agent:usage}
     *
     * 两个资源行权限串<b>完全相同</b>（都是 {@code agent:usage:query}），
     * 一个 menu_id 指向按钮「AI 助手用量查询」，另一个指向 type=菜单 的真页面「用量看板」。
     * 按 min(id) 会选中按钮 —— 那个页面就从导航里消失了，且不报任何错。
     *
     * 入参刻意把 id 大的那行放前面，用来证明「不是靠输入顺序赢的」
     */
    @Test
    public void testPlan_R1_同串两行一个是页面一个是按钮时必须选页面() {
        ResourceDO anchor = anchor(900L, "agent:usage");
        List<ResourceDO> resources = List.of(
                anchor,
                action(456L, "agent:usage:query", 9000050912L),   // 「用量看板」type=菜单
                action(436L, "agent:usage:query", 9000050906L));   // 「AI 助手用量查询」按钮
        Set<Long> navMenuIds = Set.of(9000050912L);   // 只有「用量看板」是导航菜单

        CollapsePlan plan = plan(resources, List.of(), Set.of(), navMenuIds);

        assertEquals(Long.valueOf(9000050912L), plan.getMenuIdUpdates().get(900L),
                "必须选导航菜单「用量看板」，而不是 id 更小的按钮");
        assertTrue(plan.getWarnings().stream().anyMatch(w -> w.contains("R1 生效")),
                "两条不同页面共用一个权限串，这是要人来看的，必须告警");
    }

    /**
     * 组内全是按钮时退回 min(id)
     *
     * 按钮 menu_id 对导航<b>无害</b>：{@code AuthConvert#buildMenuTree} 第一件事就是移除按钮，
     * 而 {@code completeMenuAncestors} 会沿菜单父链把页面补出来。
     * 有害的只是「本该选页面却选了按钮」那一种
     */
    @Test
    public void testPlan_R1_组内全是按钮时取最小menuId() {
        ResourceDO anchor = anchor(30L, "ai:api-key");
        List<ResourceDO> resources = List.of(
                anchor,
                action(31L, "ai:api-key:query", 795L),
                action(32L, "ai:api-key:create", 796L),
                action(33L, "ai:api-key:update", 797L),
                action(34L, "ai:api-key:delete", 798L));

        CollapsePlan plan = plan(resources, List.of(), Set.of(), Set.of());

        assertEquals(Long.valueOf(795L), plan.getMenuIdUpdates().get(30L));
        assertTrue(plan.getWarnings().stream().noneMatch(w -> w.contains("R1 生效")),
                "全是按钮时没有取舍，不该拿告警淹掉真正需要人看的那些");
    }

    /**
     * 🔴 挑不出 menu_id 时<b>绝不</b>能拿 null 去覆盖一个本来有值的列
     *
     * 锚点自己已有 menu_id 时，{@link CollapsePlan#getMenuIdUpdates()} 里就不该有它
     */
    @Test
    public void testPlan_R1_锚点自己已有menuId时不进更新表() {
        ResourceDO anchor = row(40L, "system:notice", ResourceTypeEnum.MENU.getCode(), ENABLE, 77L);
        List<ResourceDO> resources = List.of(
                anchor,
                action(41L, "system:notice:query", null),
                action(42L, "system:notice:create", null));

        CollapsePlan plan = plan(resources, List.of(), Set.of(), Set.of());

        assertTrue(plan.getMenuIdUpdates().isEmpty(), "锚点的 menu_id 本来就有值，不该被改动");
        assertTrue(plan.getWarnings().stream().noneMatch(w -> w.contains("R1 无解")));
    }

    /**
     * 锚点与全体成员都没有 menu_id ⟹ 必须告警
     *
     * 后果写在 {@code chooseMenuId} 的注释里：{@code filterMenuDerived} 的判据就是
     * {@code menu_id != null}，为 NULL 时套餐降级收不回这批授权，且不报错
     */
    @Test
    public void testPlan_R1_挑不出menuId时必须告警() {
        List<ResourceDO> resources = List.of(
                anchor(50L, "infra:demo"),
                action(51L, "infra:demo:query", null));

        CollapsePlan plan = plan(resources, List.of(), Set.of(), Set.of());

        assertTrue(plan.getMenuIdUpdates().isEmpty(), "挑不出就不许写，拿 null 覆盖会更糟");
        assertTrue(plan.getWarnings().stream().anyMatch(w -> w.contains("R1 无解")),
                "这是一个「套餐降级收不回权限」的隐患，必须让人看见");
    }

    /**
     * 🔴 跨类型合并要把 {@code default_effect} 改成最严的那个，并把这件事登记成「已知差异」
     *
     * <p>DATASET 锚点是 ALLOW，并进来的遗留 ACTION 行是 DENY。而 {@code :query} 解析出的动作
     * 就是 VIEW，与裸身份串问的是同一个 {@code (行, VIEW)} —— 一列装不下两个相反的答案，
     * 必须选一个。选 DENY（收紧）而不是 ALLOW（放开）的实测依据见
     * {@code ResourceCollapsePlanner#applyStrictestDefaultEffect}
     */
    @Test
    public void testPlan_跨类型锚点必须告警() {
        List<ResourceDO> resources = List.of(
                row(800L, "infra:form-design-run:x", ResourceTypeEnum.DATASET.getCode(), ENABLE, null),
                action(801L, "infra:form-design-run:x:query", null));

        CollapsePlan plan = plan(resources, List.of(), Set.of(), Set.of());

        assertEquals(Long.valueOf(800L), plan.getIdentityOf().get("infra:form-design-run:x"));
        assertEquals("DENY", plan.getDefaultEffectUpdates().get(800L),
                "锚点原本 ALLOW、成员是 DENY，不改就是静默放权（这批成员串会全部改走锚点的 ALLOW 回落）");
        assertTrue(plan.getDefaultEffectNarrowings().contains("infra:form-design-run:x"),
                "这是自校验的差异白名单，不登记就等于让那 464 个收紧点变成「未登记的差异」而失败");
        assertTrue(plan.getWarnings().stream().anyMatch(w -> w.contains("跨类型合并")),
                "改了默认效果就必须让人看见，尤其是它同时意味着裸身份串自己从放行变成拒绝");
    }

    /**
     * 反向情形：锚点本来就是最严的，一行都不许动
     *
     * <p>🔴 这条守卫的意义在于 {@code defaultEffectUpdates} 是「只含真的会变的那些」——
     * 与 {@code menuIdUpdates} 同一个口径。装进不需要的条目，会让落库脚本多写一遍列，
     * 也让自校验的差异预算对不上账
     */
    @Test
    public void testPlan_锚点已最严则不改默认效果() {
        List<ResourceDO> resources = List.of(
                row(810L, "infra:form-design-run:y", ResourceTypeEnum.DATASET.getCode(), ENABLE, null),
                action(811L, "infra:form-design-run:y:query", null));
        resources.get(0).setDefaultEffect("DENY");

        CollapsePlan plan = plan(resources, List.of(), Set.of(), Set.of());

        assertEquals(Long.valueOf(810L), plan.getIdentityOf().get("infra:form-design-run:y"));
        assertTrue(plan.getDefaultEffectUpdates().isEmpty(), "锚点已经是 DENY，没有更严的成员");
        assertTrue(plan.getDefaultEffectNarrowings().isEmpty());
        assertTrue(plan.getWarnings().stream().noneMatch(w -> w.contains("跨类型合并")),
                "没改就不该告警，否则告警会淹没在噪声里");
    }

    // ==================== R3：空权限行 ====================

    /**
     * 119 行空权限资源<b>全是叶子</b>，挂着 160 条活授权（3 个主体，全是 ALLOW+VIEW），
     * 它们的 menu_id 全是 type=菜单 —— 存在的唯一目的就是承载页面可见性。
     *
     * 阶段 0 曾计划「先为它们铸出资源级权限串再迁移」，勘察之后确认那是过度设计：
     * 没有任何 {@code v-hasPermi} 或 {@code @ss.hasPermission} 会与它们比对。
     * 所以规则是<b>原样不动</b>
     */
    @Test
    public void testPlan_R3_空权限行不合并其授权原样不动() {
        ResourceDO blank1 = named(row(500L, "", ResourceTypeEnum.MENU.getCode(), ENABLE, 15L), "MySQL 监控");
        ResourceDO blank2 = named(row(501L, "", ResourceTypeEnum.MENU.getCode(), ENABLE, 5L), "角色管理");
        List<ResourceDO> resources = List.of(blank1, blank2);
        List<GrantDO> grants = List.of(
                grant(600L, 500L, "VIEW", "ALLOW"),
                grant(601L, 501L, "VIEW", "ALLOW"));

        CollapsePlan plan = plan(resources, grants);

        assertEquals(Kind.BLANK, plan.getClassification().get(500L).getKind());
        assertEquals(Kind.BLANK, plan.getClassification().get(501L).getKind());
        assertFalse(plan.getRetiredResourceIds().contains(500L), "空权限行不许被合并掉");
        assertFalse(plan.getRetiredResourceIds().contains(501L));
        assertTrue(plan.getResourceIdRemap().isEmpty(), "空权限行压根不进重映射表");
        assertEquals(Set.of(600L, 601L), plan.getUntouchedGrantIds(),
                "空权限行上的授权一条都不许搬 —— 搬错就等于抹掉三个角色的菜单可见性");
        assertEquals(0, plan.identityCount());
    }

    // ==================== R4：停用行 ====================

    /**
     * 🔴 实测形态：{@code infra:codegen}
     *
     * {@code infra:codegen:query}(id=2124) 是<b>停用</b>的（对应 system_menu id=19，
     * 「代码生成」页被特意关掉），而 {@code infra:codegen}(id=2069) 是<b>启用</b>的锚点。
     * 它身上有 3 条活授权（分属 4 个角色，共 12 条）。
     *
     * 今天 {@code selectListByPermission} 过滤 {@code status=0}，所以这些授权是惰性的。
     * 一旦被并进启用的锚点，4 个角色就静默拿到了一个被特意停用的页面 —— 这是放权，不是收权，
     * 而放权没有人会来报
     */
    @Test
    public void testPlan_R4_停用行不参与塌缩其授权一条都不搬() {
        ResourceDO anchor = anchor(2069L, "infra:codegen");
        List<ResourceDO> resources = List.of(
                anchor,
                // 停用行的 menu_id 最小、且是导航菜单：R4 的定性顺序若排在解析之后，
                // 它就会以身份为名去抢 menu_id，把导航指到一个停用的页面上
                row(2124L, "infra:codegen:query", ResourceTypeEnum.ACTION.getCode(), DISABLE, 19L),
                action(2126L, "infra:codegen:create", 100L),
                action(2127L, "infra:codegen:update", 101L));
        List<GrantDO> grants = List.of(
                grant(700L, 2124L, "VIEW", "ALLOW"),   // 挂在停用行上
                grant(701L, 2126L, "VIEW", "ALLOW"),
                grant(702L, 2127L, "VIEW", "ALLOW"));

        CollapsePlan plan = plan(resources, grants, Set.of(), Set.of(19L, 100L, 101L));

        assertEquals(Kind.DISABLED, plan.getClassification().get(2124L).getKind());
        assertFalse(plan.getResourceIdRemap().containsKey(2124L), "停用行不许进重映射表");
        assertFalse(plan.getRetiredResourceIds().contains(2124L), "停用行原样留着，连软删都不许");
        assertEquals(Set.of(700L), plan.getUntouchedGrantIds(), "停用行上的授权不许搬 —— 搬了就是静默放权");
        assertEquals(2, plan.grantRewriteCount(), "只有两条启用的授权参与改写");
        assertEquals(Set.of(2126L, 2127L), plan.getRetiredResourceIds());
        assertEquals(Long.valueOf(100L), plan.getMenuIdUpdates().get(2069L),
                "menu_id 必须在启用的成员里挑，不能落到停用行那个 19 上");
    }

    /**
     * 🔴 实测形态：{@code infra:build:list}(id=2123) 与 {@code infra:swagger:list}(id=2125)
     *
     * 这两个组里<b>只有停用行</b>，没有任何启用的同伴。
     * 若停用行也参与塌缩，就会新建出 {@code infra:build} 身份行、
     * 把那 3 条授权搬上去 —— 「配了但停用」变成「配了且生效」，同样不报错
     */
    @Test
    public void testPlan_R4_只有停用行的组不产生身份() {
        List<ResourceDO> resources = List.of(
                row(2123L, "infra:build:list", ResourceTypeEnum.ACTION.getCode(), DISABLE, 18L),
                row(2125L, "infra:swagger:list", ResourceTypeEnum.ACTION.getCode(), DISABLE, 20L));
        List<GrantDO> grants = List.of(
                grant(800L, 2123L, "VIEW", "ALLOW"),
                grant(801L, 2123L, "VIEW", "DENY"),
                grant(802L, 2125L, "VIEW", "ALLOW"));

        CollapsePlan plan = plan(resources, grants);

        assertEquals(0, plan.identityCount(), "一个身份都不该产生");
        assertTrue(plan.getCreatedIdentities().isEmpty(), "更不该新建身份行");
        assertTrue(plan.getRetiredResourceIds().isEmpty());
        assertEquals(Set.of(800L, 801L, 802L), plan.getUntouchedGrantIds());
        assertEquals(0, plan.grantRewriteCount());
    }

    // ==================== R7：通配授权收窄 ====================

    /**
     * 🔴 DENY {@code *} 不收窄 = 静默收权
     *
     * 实测 {@code system:user} 上有 4 条 {@code DENY *}，分别挂在
     * {@code create/delete/export/import} 四个兄弟资源上。合并到同一个身份之后，
     * {@code *} 的作用域会从「一个动词」放大成「该资源的一切动作」，
     * 把原本放行的 VIEW 与 UPDATE 一起吞掉
     */
    @Test
    public void testPlan_R7_DENY通配必须收窄() {
        List<ResourceDO> resources = List.of(
                anchor(9L, "system:user"),
                action(11L, "system:user:create", 2L),
                action(13L, "system:user:delete", 4L));
        List<GrantDO> grants = List.of(
                grant(900L, 11L, GrantActionEnum.WILDCARD.getCode(), "DENY"),
                grant(901L, 13L, GrantActionEnum.WILDCARD.getCode(), "DENY"),
                grant(902L, 11L, "VIEW", "ALLOW"));

        CollapsePlan plan = plan(resources, grants);

        assertEquals(2, plan.getDenyNarrowings().size());
        ActionNarrowing first = plan.getDenyNarrowings().get(0);
        assertEquals(Long.valueOf(900L), first.getGrantId());
        assertEquals("system:user", first.getIdentity());
        assertEquals("*", first.getFromAction());
        assertEquals("CREATE", first.getToAction(), "不收窄就会连带封掉 VIEW 与 UPDATE");
        assertEquals("DENY", first.getEffect());
        assertEquals("DELETE", plan.getDenyNarrowings().get(1).getToAction());

        assertEquals("CREATE", plan.getPlannedAction().get(900L));
        // 🔴 挂在 {@code :create} 上那条「ALLOW VIEW」同样变成 CREATE ——
        //    目标动作来自<b>资源身份串</b>，不是来自授权行自己那个几乎恒为 VIEW 的 action 列。
        //    于是它与上面那条 DENY 在 (system:user, CREATE) 上正面相遇：
        //    两条都留着，谁赢由引擎既有的效果优先级（DENY 在前）决定，计划器不越权裁决
        assertEquals("CREATE", plan.getPlannedAction().get(902L));
        assertEquals(3, plan.grantRewriteCount());
        assertTrue(plan.getRedundantGrantIds().isEmpty(),
                "ALLOW 与 DENY 不是重复，去重键里含着 effect");
        assertTrue(plan.getWarnings().stream().noneMatch(w -> w.contains("ALLOW 通配授权")),
                "DENY 收窄是预期内的，不该告警 —— 告警一多，真正要看的那条就被淹了");
    }

    /**
     * 🔴 ALLOW {@code *} 不收窄 = 静默放权，方向相反而已
     *
     * 挂在 {@code system:user:create} 上的 {@code ALLOW *} 今天只放行那一个按钮，
     * 塌缩到 {@code system:user} 之后 {@code *} 会匹配全部动作 ——
     * 一条授权凭空变成「这个资源什么都能干」。
     *
     * 放权比收权更难发现：收权会有人来报「我点不了了」，放权没有人会报「我怎么突然能删了」。
     * 实测今天 ALLOW {@code *} 是空集，但规则必须写全 ——
     * 下一条被配出来时，它不该取决于有没有人记得这件事
     */
    @Test
    public void testPlan_R7_ALLOW通配同样收窄并告警() {
        List<ResourceDO> resources = List.of(
                anchor(9L, "system:user"),
                action(11L, "system:user:create", 2L));
        List<GrantDO> grants = List.of(grant(910L, 11L, GrantActionEnum.WILDCARD.getCode(), "ALLOW"));

        CollapsePlan plan = plan(resources, grants);

        assertEquals(1, plan.getDenyNarrowings().size(), "字段名叫 denyNarrowings，但 ALLOW 也要进这张表");
        assertEquals("ALLOW", plan.getDenyNarrowings().get(0).getEffect());
        assertEquals("CREATE", plan.getPlannedAction().get(910L));
        assertTrue(plan.getWarnings().stream().anyMatch(w -> w.contains("ALLOW 通配授权")),
                "静默放权必须喊出来");
    }

    /**
     * 档 C/D 上的 {@code *} 原样保留
     *
     * 实测 13 条 {@code action='*'} 里有 8 条挂在档 D 身份行上 ——
     * 它们的资源身份本来就<b>是</b> {@code *} 的作用域，收窄反而会改变语义
     */
    @Test
    public void testPlan_R7_不塌缩的行上的通配不动() {
        List<ResourceDO> resources = List.of(
                action(60L, "system:tenant-package:assign-menu", null),   // 档 C
                action(61L, "system:tenant", null));                    // 档 D
        List<GrantDO> grants = List.of(
                grant(920L, 60L, "*", "ALLOW"),
                grant(921L, 61L, "*", "DENY"));

        CollapsePlan plan = plan(resources, grants);

        assertTrue(plan.getDenyNarrowings().isEmpty(), "不塌缩就没有作用域放大，不该收窄");
        assertEquals(Set.of(920L, 921L), plan.getUntouchedGrantIds());
        assertEquals(Kind.IDENTITY, plan.getClassification().get(60L).getKind());
        assertEquals(Kind.IDENTITY, plan.getClassification().get(61L).getKind());
    }

    /**
     * ALLOW 与 DENY 落在同一个 (身份, 动作) 上时<b>都留着</b>
     *
     * 「DENY 优先」不是计划器裁决的，判定引擎既有的效果优先级
     * （全局 DENY > 上下文 DENY > 上下文 ALLOW > 全局 ALLOW）本来就会做这件事。
     * 计划器去重一次就等于提前把 ALLOW 删了 —— 而那是数据，不是冗余
     */
    @Test
    public void testPlan_同身份同动作的ALLOW与DENY不互相去重() {
        List<ResourceDO> resources = List.of(
                anchor(9L, "system:user"),
                action(10L, "system:user:query", 1L),
                action(16L, "system:user:list", 7L));
        List<GrantDO> grants = List.of(
                grant(930L, 10L, "VIEW", "ALLOW"),
                grant(931L, 16L, "VIEW", "DENY"));

        CollapsePlan plan = plan(resources, grants);

        assertEquals(2, plan.grantRewriteCount(), "两条都得活着，由引擎按效果优先级裁决");
        assertTrue(plan.getRedundantGrantIds().isEmpty());
    }

    // ==================== R10：去重 ====================

    /**
     * 实测 {@code system:user + VIEW} 是全库<b>唯一</b>的一对多：
     * {@code system:user:list} 与 {@code system:user:query} 塌缩后是同一个 (身份, 动作)。
     * 同一个主体在两者上都有授权时，合并后就是完全重复的两行
     */
    @Test
    public void testPlan_R10_合并后完全重复的授权只留一条() {
        List<ResourceDO> resources = List.of(
                anchor(9L, "system:user"),
                action(10L, "system:user:query", 1L),
                action(16L, "system:user:list", 7L));
        List<GrantDO> grants = List.of(
                grant(940L, 10L, "VIEW", "ALLOW"),
                grant(941L, 16L, "VIEW", "ALLOW"));

        CollapsePlan plan = plan(resources, grants);

        assertEquals(1, plan.grantRewriteCount());
        assertEquals(Long.valueOf(940L), plan.getGrantRewrites().get(0).getGrantId(), "保留 id 小的那条");
        assertEquals(Set.of(941L), plan.getRedundantGrantIds());
        assertTrue(plan.getWarnings().stream().noneMatch(w -> w.contains("附属字段")),
                "两条都是干净的授权，没有取舍要人来做");
    }

    /**
     * 🔴 keeper 必须与入参顺序无关
     *
     * 同样的库跑两次得到两份不同的计划，评审就没有意义了 ——
     * 而「评审过的那份 ≠ 执行的那份」正是不可回滚迁移最怕的事
     */
    @Test
    public void testPlan_计划与入参顺序无关() {
        ResourceDO anchor = anchor(9L, "system:user");
        ResourceDO query = action(10L, "system:user:query", 1L);
        ResourceDO list = action(16L, "system:user:list", 7L);
        GrantDO g1 = grant(940L, 10L, "VIEW", "ALLOW");
        GrantDO g2 = grant(941L, 16L, "VIEW", "ALLOW");

        CollapsePlan forward = plan(List.of(anchor, query, list), List.of(g1, g2),
                Set.of("system:user:query", "system:user:list"), Set.of(1L, 7L));
        CollapsePlan backward = plan(List.of(list, query, anchor), List.of(g2, g1),
                Set.of("system:user:list", "system:user:query"), Set.of(7L, 1L));

        assertEquals(render(forward), render(backward),
                "入参顺序变了，计划必须逐字节相同");
    }

    /**
     * 被丢掉的那一行上如果绑了行过滤规则，必须喊出来
     *
     * 删掉的可能是<b>绑了规则的那条</b>，表现为行过滤静默失效：查询照常返回，只是不再过滤。
     * 计划器不替人做这个取舍，它只保证这个取舍是被看见的
     */
    @Test
    public void testPlan_R10_丢掉附属字段时告警() {
        List<ResourceDO> resources = List.of(
                anchor(9L, "system:user"),
                action(10L, "system:user:query", 1L),
                action(16L, "system:user:list", 7L));
        GrantDO plain = grant(950L, 10L, "VIEW", "ALLOW");
        GrantDO withRule = grant(951L, 16L, "VIEW", "ALLOW");
        withRule.setRuleId(88L);

        CollapsePlan plan = plan(resources, List.of(plain, withRule));

        assertEquals(Set.of(951L), plan.getRedundantGrantIds());
        assertTrue(plan.getWarnings().stream()
                        .anyMatch(w -> w.contains("附属字段") && w.contains("rule_id=88")),
                "绑了行过滤规则的那条被删掉，必须让人来定保留哪一条");
    }

    // ==================== R2：反向展开 ====================

    /**
     * 展开源是 {@code system_menu.permission}，所以唯一的一对多能被如实还原
     */
    @Test
    public void testPlan_R2_反向展开还原塌缩前的原始串() {
        List<ResourceDO> resources = List.of(
                anchor(9L, "system:user"),
                action(10L, "system:user:query", 1L),
                action(16L, "system:user:list", 7L),
                action(11L, "system:user:create", 2L));
        Set<String> menuPermissions = Set.of(
                "system:user:query", "system:user:list", "system:user:create", "system:user");

        CollapsePlan plan = plan(resources, List.of(), menuPermissions, Set.of());

        Map<String, Set<String>> table = plan.getReverseExpansion();
        assertEquals(Set.of("system:user:list", "system:user:query"),
                table.get(PermissionStringResolver.key("system:user", GrantActionEnum.VIEW)),
                "全库唯一的一对多：两个串都得吐出去，少一个前端就少一个按钮");
        assertEquals(Set.of("system:user:create"),
                table.get(PermissionStringResolver.key("system:user", GrantActionEnum.CREATE)));
        assertEquals(2, table.size(),
                "身份串 system:user 自己是档 D，不塌缩，不该有展开项");
    }

    /**
     * 🔴 不许造出库里从来没有的串
     *
     * 本方法的第一版用「动作 → 规范后缀」反推兜底，于是 {@code infra:build:list}
     * 被展开成 {@code infra:build:query} —— 一个前后端都没人写过的串。
     * 它不会让判定出错，但会让「权限清单 vs 注解面」的对账永久对不平，
     * 更坏的是它盖住了真正的缺口：展开表看着是满的，前端按钮却因为对不上串而消失
     */
    @Test
    public void testPlan_R2_不许造出库中不存在的串() {
        List<ResourceDO> resources = List.of(
                anchor(9L, "system:user"),
                action(10L, "system:user:query", 1L));
        // 菜单表里只有 :query，没有 :list 也没有 :get
        CollapsePlan plan = plan(resources, List.of(grant(960L, 10L, "VIEW", "ALLOW")),
                Set.of("system:user:query"), Set.of());

        Set<String> expanded = ResourceCollapsePlanner.allExpandedPermissions(plan);
        assertEquals(Set.of("system:user:query"), expanded,
                "VIEW 只许展开出实际存在过的那一个串，不许顺手补上 :list 与 :get");
        assertTrue(plan.getWarnings().stream().noneMatch(w -> w.contains("R2 无法反向展开")));
    }

    /**
     * 那 33 个 {@code infra:form-design-run:*} 从不进菜单表，
     * 靠「资源行自己的 permission」这一项就能覆盖 —— 不需要任何兜底猜测
     */
    @Test
    public void testPlan_R2_菜单表里没有的身份靠资源行自己覆盖() {
        List<ResourceDO> resources = List.of(
                action(700L, "infra:form-design-run:e2e_plain_probe:list", null),
                action(701L, "infra:form-design-run:e2e_plain_probe:reference", null));

        // menuPermissions 刻意给空集
        CollapsePlan plan = plan(resources, List.of(grant(970L, 700L, "VIEW", "ALLOW")),
                Set.of(), Set.of());

        String identity = "infra:form-design-run:e2e_plain_probe";
        assertTrue(plan.getCreatedIdentities().containsKey(identity));
        assertEquals(Set.of("infra:form-design-run:e2e_plain_probe:list"),
                plan.getReverseExpansion().get(PermissionStringResolver.key(identity, GrantActionEnum.VIEW)),
                "原串是 :list，展开出来就必须是 :list，不能被「规范」成 :query");
        assertTrue(plan.getWarnings().stream().noneMatch(w -> w.contains("R2 无法反向展开")));
    }

    // ==================== 新建身份行 ====================

    /**
     * 35 个身份在库里没有锚点行，必须新建
     *
     * type=ACTION 而不是 MENU：MENU 在本体系里的既定含义是「树的中间节点」
     * （阶段 0 实测：84 个有 permission 的 MENU 行恰好就是全部中间节点，无一例外），
     * 而新建行自己就是授权点，不是容器
     */
    @Test
    public void testPlan_新建身份行的模板() {
        ResourceDO moduleRoot = row(1L, "infra", ResourceTypeEnum.MENU.getCode(), ENABLE, null);
        moduleRoot.setPath(ResourceDO.buildPath(null, "infra"));
        moduleRoot.setDepth((short) 1);
        List<ResourceDO> resources = List.of(
                moduleRoot,
                named(action(710L, "infra:build:create", 19L), "表单构建新增"),
                named(action(711L, "infra:build:list", 18L), "表单构建查询"));

        CollapsePlan plan = plan(resources, List.of(), Set.of(), Set.of(18L));

        assertEquals(1, plan.getCreatedIdentities().size());
        assertTrue(plan.getIdentityOf().isEmpty(), "新建的身份还没有 id，不许出现在 identityOf 里");
        assertEquals(1, plan.identityCount(), "identityCount 要把新建的算进去");
        assertEquals(List.of("infra:build"), ResourceCollapsePlanner.sortedIdentities(plan));

        ResourceDO t = plan.getCreatedIdentities().get("infra:build");
        assertEquals("infra:build", t.getPermission());
        assertEquals(ResourceTypeEnum.ACTION.getCode(), t.getType());
        assertEquals(ENABLE, t.getStatus());
        assertEquals("DENY", t.getDefaultEffect(), "ACTION 的默认效果是 DENY，别写成枚举名");
        assertEquals(Long.valueOf(1L), t.getParentId(),
                "按命名约定挂到 infra 下，与 attachToConventionParent 同一口径");
        assertEquals("/root/infra/infra:build/", t.getPath());
        assertEquals(Short.valueOf((short) 2), t.getDepth());
        assertEquals("表单构建查询", t.getName(), "名字取 VIEW 那一行，它通常就是页面名");
        assertEquals(Long.valueOf(18L), t.getMenuId(), "R1 在模板里同样生效：优先导航菜单");
        assertEquals(Set.of(710L, 711L), plan.getRetiredResourceIds());
        assertEquals(Map.of(710L, "infra:build", 711L, "infra:build"), plan.getResourceIdRemap());
    }

    /**
     * 找不到约定父节点时退回根下
     *
     * 与 {@code ResourceServiceImpl#attachToConventionParent} 同口径：
     * 父不存在就挂根下，等推导 Runner 下次补齐父节点后再归位
     */
    @Test
    public void testPlan_新建身份行找不到父节点时挂根下() {
        List<ResourceDO> resources = List.of(action(720L, "orphan:thing:query", null));

        CollapsePlan plan = plan(resources, List.of(), Set.of(), Set.of());

        ResourceDO t = plan.getCreatedIdentities().get("orphan:thing");
        assertEquals(ResourceDO.ID_ROOT, t.getParentId());
        assertEquals("/root/orphan:thing/", t.getPath());
        assertEquals(Short.valueOf((short) 1), t.getDepth());
    }

    // ==================== 作用域：谁不参与 ====================

    /**
     * DATASET / COLUMN / FIELD / PROCESS / CATEGORY 已经是目标形态
     * （资源身份不含动词，动作在 {@code sys_grant.action} 上），动它们只会引入回归
     */
    @Test
    public void testPlan_已达标族完全不参与() {
        List<ResourceDO> resources = List.of(
                row(300L, "infra:form-design-run:assessment_object_form",
                        ResourceTypeEnum.DATASET.getCode(), ENABLE, null),
                row(301L, "infra:form-design-run:assessment_object_form:id_card",
                        ResourceTypeEnum.FIELD.getCode(), ENABLE, null),
                row(302L, "oa_leave", ResourceTypeEnum.PROCESS.getCode(), ENABLE, null),
                row(303L, "oa", ResourceTypeEnum.CATEGORY.getCode(), ENABLE, null));
        List<GrantDO> grants = List.of(
                grant(980L, 300L, "VIEW", "ALLOW"),
                grant(981L, 301L, "VIEW", "DENY"),
                grant(982L, 302L, "START", "ALLOW"),
                grant(983L, 303L, "VIEW", "ALLOW"));

        CollapsePlan plan = plan(resources, grants);

        for (Long id : List.of(300L, 301L, 302L, 303L)) {
            assertEquals(Kind.OUT_OF_SCOPE, plan.getClassification().get(id).getKind(),
                    "资源 " + id + " 不该参与塌缩");
        }
        assertEquals(Set.of(980L, 981L, 982L, 983L), plan.getUntouchedGrantIds());
        assertEquals(0, plan.identityCount());
        assertTrue(plan.getReverseExpansion().isEmpty(),
                "已达标族的动作本来就在 action 列上，不需要反向展开");
    }

    /**
     * 档 C（业务专有动词）、档 D（两段式名词）、root（单段模块根）本来就是资源身份
     *
     * 它们保持原样 —— 这保证了「不塌缩的行，生效权限逐字节不变」这件事是平凡的
     */
    @Test
    public void testPlan_档C档D与模块根保持原样() {
        List<ResourceDO> resources = List.of(
                action(400L, "bpm:model:publish", null),        // 档 C
                action(401L, "system:role:assign-role-menu", null),   // 档 C
                action(402L, "system:tenant", null),            // 档 D
                row(403L, "system", ResourceTypeEnum.MENU.getCode(), ENABLE, null),   // root
                action(404L, "system:tenant:query", null));     // 档 A，唯一参与塌缩的
        List<GrantDO> grants = List.of(
                grant(990L, 400L, "VIEW", "ALLOW"),
                grant(991L, 401L, "VIEW", "ALLOW"),
                grant(992L, 402L, "VIEW", "ALLOW"),
                grant(993L, 403L, "VIEW", "ALLOW"),
                grant(994L, 404L, "VIEW", "ALLOW"));

        CollapsePlan plan = plan(resources, grants);

        for (Long id : List.of(400L, 401L, 402L, 403L)) {
            assertEquals(Kind.IDENTITY, plan.getClassification().get(id).getKind());
        }
        assertEquals(Kind.COLLAPSING, plan.getClassification().get(404L).getKind());
        assertEquals(Set.of(990L, 991L, 992L, 993L), plan.getUntouchedGrantIds());
        assertEquals(1, plan.grantRewriteCount());
        assertEquals("VIEW", plan.getPlannedAction().get(994L));
    }

    /**
     * 定性统计的总数必须守恒 —— 少一行就意味着有资源行被悄悄漏在迁移之外
     */
    @Test
    public void testPlan_定性统计总数守恒() {
        List<ResourceDO> resources = List.of(
                anchor(9L, "system:user"),
                action(10L, "system:user:query", 1L),
                row(500L, "", ResourceTypeEnum.MENU.getCode(), ENABLE, 15L),
                row(2123L, "infra:build:list", ResourceTypeEnum.ACTION.getCode(), DISABLE, 18L),
                action(402L, "system:tenant", null),
                row(300L, "oa_leave", ResourceTypeEnum.PROCESS.getCode(), ENABLE, null));

        CollapsePlan plan = plan(resources, List.of());

        assertEquals(resources.size(), plan.getClassification().size());
        Map<Kind, Integer> summary = plan.classificationSummary();
        assertEquals(resources.size(), summary.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(Integer.valueOf(1), summary.get(Kind.COLLAPSING));
        assertEquals(Integer.valueOf(1), summary.get(Kind.BLANK));
        assertEquals(Integer.valueOf(1), summary.get(Kind.DISABLED));
        assertEquals(Integer.valueOf(2), summary.get(Kind.IDENTITY), "system:user 锚点 + system:tenant");
        assertEquals(Integer.valueOf(1), summary.get(Kind.OUT_OF_SCOPE));
    }

    // ==================== R11：上提的授权会顺着树泄漏，整族拦下 ====================

    /**
     * 形状取自实测数据：{@code system:user} 那一族在库里是
     * 锚点 2061(DATASET) + 7 行动词成员 + 26 行列/字段，全部 {@code parent_id=2061}；
     * 其中 {@code system:user:update-password} 是档 C（末段不是已知动词），塌不掉、必然存活
     *
     * <p>把成员行的授权上提到 2061，就等于把授权挂到了那 26 行列/字段的<b>父节点</b>上，
     * 而两条链口径都会收集整条祖先链的授权 ⟹ 它们白捡。
     * 实测两个方向都出事：上提 ALLOW 造成 382 个决策点越权，
     * 上提 DENY（ROLE 9000000543 在 {@code system:user} 上的 4 条 {@code action='*'} DENY）
     * 造成 100 个决策点被静默收权
     */
    @Test
    public void testPlan_R11_身份行下面有存活后代时整族不塌() {
        // 锚点用 DATASET，与库里一致：sys_resource 2061 是 system:user 的 DATASET 行
        // （default_effect=ALLOW），7 行动词成员与 26 行列/字段全挂在它下面。
        // ⚠️ DATASET 不在 LEGACY_TYPES 里，所以锚点自己的定性是 OUT_OF_SCOPE ——
        // 但 anchorByPermission 是「按权限串找启用行」，不看类型，照样认它当锚点
        ResourceDO identity = row(2061L, "system:user",
                ResourceTypeEnum.DATASET.getCode(), ENABLE, null);
        List<ResourceDO> resources = List.of(
                identity,
                childOf(identity, 1L, "system:user:query", ResourceTypeEnum.ACTION.getCode(), 11L),
                childOf(identity, 2L, "system:user:create", ResourceTypeEnum.ACTION.getCode(), null),
                childOf(identity, 7L, "system:user:update-password",
                        ResourceTypeEnum.ACTION.getCode(), 17L));
        List<GrantDO> grants = List.of(
                grant(1000L, 1L, "VIEW", "ALLOW"),
                grant(1001L, 2L, "CREATE", "ALLOW"));

        CollapsePlan plan = plan(resources, grants);

        assertEquals(Set.of("system:user"), plan.getBlockedIdentities());
        // 整族不动：一行都不退役、一条授权都不搬
        assertTrue(plan.getRetiredResourceIds().isEmpty(), "被拦下的族不许退役任何行");
        assertTrue(plan.getResourceIdRemap().isEmpty(), "被拦下的族不许产生 id 重映射");
        assertTrue(plan.getGrantRewrites().isEmpty(), "被拦下的族不许上提任何授权");
        assertEquals(0, plan.identityCount());
        assertEquals(Set.of(1000L, 1001L), plan.getUntouchedGrantIds(),
                "两条授权必须原样留在原行上，否则就是被搬走了");
        // 🔴 这一族的锚点是 ALLOW、成员是 DENY，正是 applyStrictestDefaultEffect 要处理的
        // 跨类型形态。族被拦下之后<b>一个成员都不会并进来</b>，所以这次翻转也必须不发生 ——
        // 它是自校验里 hasPermission 收紧方向唯一的白名单来源，
        // 多一个少一个都会让那边报「未登记的差异」
        assertTrue(plan.getDefaultEffectUpdates().isEmpty(),
                "整族拦下之后不许再改锚点的 default_effect");
        assertTrue(plan.getDefaultEffectNarrowings().isEmpty());
        // 定性要能看出「形状可塌但被拦下」，不能与「压根不可塌」混成一类
        assertEquals(Kind.BLOCKED, plan.getClassification().get(1L).getKind());
        assertEquals(Kind.BLOCKED, plan.getClassification().get(2L).getKind());
        assertEquals(Kind.OUT_OF_SCOPE, plan.getClassification().get(2061L).getKind());
        assertEquals(Kind.IDENTITY, plan.getClassification().get(7L).getKind(),
                "档 C 的那一行是拦下整族的原因；它本来就<b>是</b>资源身份，"
                        + "定性照旧是 IDENTITY（OUT_OF_SCOPE 只留给类型不在范围内的行）");
        assertTrue(plan.getWarnings().stream().anyMatch(w -> w.contains("整族拦下不塌缩")
                        && w.contains("system:user") && w.contains("system:user:update-password")),
                "告警必须点名身份串与那个存活后代，否则评审看不出是哪一族、为什么");
    }

    /**
     * 🔴 这一条是 R11 唯一的<b>反向</b>网：被退役的成员行自己<b>不算</b>存活后代
     *
     * <p>真实数据里成员行就是锚点的子节点（{@code parent_id=2061}），
     * 所以「锚点下面还有子节点」对<b>每一个</b>族都成立。
     * 少算一次 {@code removeAll(retiring)}，117 个族会全部被拦下、计划器一行都不改，
     * 而所有断言「差异为 0」的测试<b>照样全绿</b> —— 迁移静默地什么都没做。
     * 这是本文件里最容易写错、错了最难发现的一处
     *
     * <p>⚠️ 本类其余用例的夹具全部 {@code parentId=ID_ROOT}（成员行不是锚点的子节点），
     * 所以它们<b>一个都覆盖不到</b>这条减法。真实形状只有这里钉住
     */
    @Test
    public void testPlan_R11_被退役的成员行自己不算存活后代() {
        ResourceDO identity = anchor(2061L, "system:user");
        List<ResourceDO> resources = List.of(
                identity,
                childOf(identity, 1L, "system:user:query", ResourceTypeEnum.ACTION.getCode(), 11L),
                childOf(identity, 2L, "system:user:create", ResourceTypeEnum.ACTION.getCode(), null));

        CollapsePlan plan = plan(resources, List.of(grant(1000L, 1L, "VIEW", "ALLOW")));

        assertTrue(plan.getBlockedIdentities().isEmpty(), "没有存活后代就不该拦");
        assertEquals(Set.of(1L, 2L), plan.getRetiredResourceIds());
        assertEquals(Long.valueOf(2061L), plan.getIdentityOf().get("system:user"));
        assertEquals(1, plan.getGrantRewrites().size(), "授权照常上提");
    }

    /**
     * 停用的后代不拦族
     *
     * <p>判据不是「它长得像后代」，而是「它<b>能不能继承到</b>」。
     * {@code ResourceMapper#selectListByPermission} 过滤 {@code status=0}，
     * 所以停用行既进不了 {@code chainsOf}、也进不了 {@code listAllowedPermissions}
     * —— 它拿不到上提的授权，也就谈不上被泄漏
     */
    @Test
    public void testPlan_R11_停用的后代不算存活() {
        ResourceDO identity = anchor(2061L, "system:user");
        ResourceDO disabled = childOf(identity, 7L, "system:user:update-password",
                ResourceTypeEnum.ACTION.getCode(), 17L);
        disabled.setStatus(DISABLE);

        CollapsePlan plan = plan(List.of(
                identity,
                childOf(identity, 1L, "system:user:query", ResourceTypeEnum.ACTION.getCode(), 11L),
                disabled), List.of(grant(1000L, 1L, "VIEW", "ALLOW")));

        assertTrue(plan.getBlockedIdentities().isEmpty());
        assertEquals(Set.of(1L), plan.getRetiredResourceIds());
        assertEquals(Kind.DISABLED, plan.getClassification().get(7L).getKind());
    }

    /**
     * 只靠物化路径挂着（{@code parent_id} 指向别处）也算存活后代
     *
     * <p>两条口径取并集不是冗余：{@code PermissionDriftDetector#repairParents} 的存在
     * 本身就证明库里 {@code parent_id} 与 {@code path} 会漂移。
     * 只认 {@code parent_id} 的话，一行漂移过的资源会绕过 R11，
     * 而 {@code buildResourceChain} 走的是 {@code path}，照样把上提的授权继承给它。
     * 实测这一族 117 个里两条口径 0 处分歧，但「今天没分歧」不是「可以只查一条」的理由
     */
    @Test
    public void testPlan_R11_只靠物化路径挂着也拦得住() {
        ResourceDO identity = anchor(2061L, "system:user");
        ResourceDO drifted = row(7L, "system:user:update-password",
                ResourceTypeEnum.ACTION.getCode(), ENABLE, 17L);
        drifted.setParentId(ResourceDO.ID_ROOT);
        drifted.setPath(ResourceDO.buildPath(identity.getPath(), drifted.getPermission()));

        CollapsePlan plan = plan(List.of(
                identity,
                childOf(identity, 1L, "system:user:query", ResourceTypeEnum.ACTION.getCode(), 11L),
                drifted), List.of(grant(1000L, 1L, "VIEW", "ALLOW")));

        assertEquals(Set.of("system:user"), plan.getBlockedIdentities());
        assertTrue(plan.getRetiredResourceIds().isEmpty());
    }

    /**
     * 要新建身份行的那一族不受 R11 影响
     *
     * <p>库里没有身份行 ⟹ 新建出来的那一行是<b>叶子</b>，下面不可能挂任何东西 ⟹ 无后代可漏。
     * 实测 33 个族属于这一类（{@code infra:form-design-run:test_demo_*} 那批表单资源：
     * 只有 7 行动词成员，身份串自己一行都没有），它们必须照常塌缩 ——
     * 这一族塌不掉的话，迁移就少做三分之一的活
     *
     * <p>⚠️ 与 {@code testPlan_R11_身份行下面有存活后代时整族不塌} 的区别全在
     * 「库里有没有那一行身份」：{@code infra:form-design-run:assessment_object_form}
     * 有（DATASET id=9000100605），所以它走锚点那条路、会被 R11 判；
     * {@code test_demo_ie_basic} 没有，所以走新建那条路、判不了也不必判
     */
    @Test
    public void testPlan_R11_新建身份行的族不拦() {
        ResourceDO moduleRoot = anchor(2000L, "infra:form-design-run");
        List<ResourceDO> resources = List.of(
                moduleRoot,
                childOf(moduleRoot, 607L, "infra:form-design-run:test_demo_ie_basic:query",
                        ResourceTypeEnum.ACTION.getCode(), 2831L),
                childOf(moduleRoot, 608L, "infra:form-design-run:test_demo_ie_basic:create",
                        ResourceTypeEnum.ACTION.getCode(), null));

        CollapsePlan plan = plan(resources, List.of(grant(1000L, 607L, "VIEW", "ALLOW")));

        assertTrue(plan.getBlockedIdentities().isEmpty());
        assertTrue(plan.getCreatedIdentities()
                        .containsKey("infra:form-design-run:test_demo_ie_basic"),
                "库里没有身份行，必须新建一个");
        assertEquals(Set.of(607L, 608L), plan.getRetiredResourceIds());
        assertEquals(Kind.IDENTITY, plan.getClassification().get(2000L).getKind(),
                "两段式名词是档 D，本来就<b>是</b>资源身份。它是新建身份行要挂上去的<b>祖先</b>，"
                        + "不是「存活后代」—— R11 只往下找，不往上找");
    }

    // ==================== 计划不可变 ====================

    /**
     * 落库方与评审方拿到的是同一个对象。如果它能被改，
     * 那「评审过的那份」与「实际执行的那份」就可能不是同一份 ——
     * 而这是一次不可回滚的迁移
     */
    @Test
    public void testPlan_冻结后不许修改() {
        CollapsePlan plan = plan(List.of(anchor(9L, "system:user"), action(10L, "system:user:query", 1L)),
                List.of(grant(1000L, 10L, "VIEW", "ALLOW")));

        assertThrows(UnsupportedOperationException.class,
                () -> plan.getResourceIdRemap().put(999L, "hacked"));
        assertThrows(UnsupportedOperationException.class,
                () -> plan.getRetiredResourceIds().add(999L));
        assertThrows(UnsupportedOperationException.class,
                () -> plan.getGrantRewrites().add(new GrantRewrite(1L, "x", "VIEW")));
        assertThrows(UnsupportedOperationException.class,
                () -> plan.getUntouchedGrantIds().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.getWarnings().add("事后补一条"));
        assertThrows(UnsupportedOperationException.class,
                () -> plan.getReverseExpansion()
                        .get(PermissionStringResolver.key("system:user", GrantActionEnum.VIEW))
                        .add("system:user:get"));
        assertThrows(UnsupportedOperationException.class,
                () -> plan.getCreatedIdentities().put("x", new ResourceDO()));
        assertThrows(UnsupportedOperationException.class,
                () -> plan.getMenuIdUpdates().put(999L, 1L));
        assertThrows(UnsupportedOperationException.class,
                () -> plan.getDefaultEffectUpdates().put(999L, "ALLOW"),
                "这张表改一个值就是改一条判定结果，冻结必须覆盖到它");
        assertThrows(UnsupportedOperationException.class,
                () -> plan.getDefaultEffectNarrowings().add("system:user"),
                "这张表是自校验的差异白名单，能事后加就等于能事后放行");
        assertThrows(UnsupportedOperationException.class,
                () -> plan.getBlockedIdentities().add("system:user"),
                "这张表记的是「哪些族因为会泄漏而没塌」，事后往里加一个不存在的族，"
                        + "评审就会以为那一族是被有意放过的，而实际上是漏做了");
        assertThrows(UnsupportedOperationException.class,
                () -> plan.getBlockedIdentities().remove("system:user"),
                "反方向同样要封：事后删掉一个真被拦下的族，落库方照旧不会去动它，"
                        + "但报告与计划就对不上了，而这一致性正是评审唯一的抓手");
        assertThrows(UnsupportedOperationException.class,
                () -> plan.getRedundantGrantIds().add(999L));
        assertThrows(UnsupportedOperationException.class,
                () -> plan.getDenyNarrowings().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.getIdentityOf().put("hacked", 1L));
        assertThrows(UnsupportedOperationException.class,
                () -> plan.getClassification().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.getPlannedAction().put(999L, "VIEW"));
    }

    // ==================== 夹具 ====================

    private CollapsePlan plan(List<ResourceDO> resources, List<GrantDO> grants) {
        return plan(resources, grants, Set.of(), Set.of());
    }

    private CollapsePlan plan(List<ResourceDO> resources, List<GrantDO> grants,
                              Set<String> menuPermissions, Set<Long> navMenuIds) {
        return planner.plan(resources, grants, menuPermissions, navMenuIds);
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
     * 挂在 {@code parent} 下面的子行，{@code parent_id} 与物化路径<b>两条口径都指过去</b>
     *
     * <p>这是库里的真实形状（{@code system:user} 那 34 行全部 {@code parent_id=2061}、
     * {@code path} 是 {@code /root/system/system:user/<自己的串>/}），
     * 而 {@link #row} 那个夹具是<b>平的</b> —— 所有行都挂根下、路径只有一段。
     * R11 判的是「有没有后代」，用平夹具就永远判不出后代，
     * 所以凡是要验 R11 的用例都必须用这个（唯一例外见
     * {@code testPlan_R11_只靠物化路径挂着也拦得住}，它刻意只设路径、
     * 为的是单独钉住第二条口径）
     */
    private static ResourceDO childOf(ResourceDO parent, Long id, String permission,
                                      String type, Long menuId) {
        ResourceDO r = row(id, permission, type, ENABLE, menuId);
        r.setParentId(parent.getId());
        r.setPath(ResourceDO.buildPath(parent.getPath(), permission));
        r.setDepth((short) (parent.getDepth() + 1));
        return r;
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

    private static ResourceDO named(ResourceDO r, String name) {
        r.setName(name);
        return r;
    }

    /** 套餐型授权行的形状：ROLE 主体 + ALLOW + 无上下文 */
    private static GrantDO grant(Long id, Long resourceId, String action, String effect) {
        return grant(id, resourceId, action, effect, 2L, 1L);
    }

    private static GrantDO grant(Long id, Long resourceId, String action, String effect,
                                 Long subjectRefId, Long tenantId) {
        GrantDO g = new GrantDO();
        g.setId(id);
        g.setSubjectType(SubjectTypeEnum.ROLE.name());
        g.setSubjectRefId(subjectRefId);
        g.setResourceId(resourceId);
        g.setAction(action);
        g.setEffect(effect);
        g.setTenantId(tenantId);
        return g;
    }

    /**
     * 把计划渲染成一份规范文本，供「与入参顺序无关」那条用例逐字节比对
     *
     * 所有集合都排过序：{@code Set.of} 与 {@code HashMap} 的迭代顺序不由我们决定，
     * 直接 toString 会让这条用例变成一台随机数发生器
     */
    private static String render(CollapsePlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("identities=").append(ResourceCollapsePlanner.sortedIdentities(plan)).append('\n');
        sb.append("identityOf=").append(new TreeMap<>(plan.getIdentityOf())).append('\n');
        sb.append("menuIdUpdates=").append(new TreeMap<>(plan.getMenuIdUpdates())).append('\n');
        sb.append("remap=").append(new TreeMap<>(plan.getResourceIdRemap())).append('\n');
        sb.append("retired=").append(new TreeSet<>(plan.getRetiredResourceIds())).append('\n');
        sb.append("redundant=").append(new TreeSet<>(plan.getRedundantGrantIds())).append('\n');
        sb.append("untouched=").append(new TreeSet<>(plan.getUntouchedGrantIds())).append('\n');
        sb.append("plannedAction=").append(new TreeMap<>(plan.getPlannedAction())).append('\n');

        List<String> rewrites = new ArrayList<>();
        for (GrantRewrite r : plan.getGrantRewrites()) {
            rewrites.add(r.toString());
        }
        Collections.sort(rewrites);
        sb.append("rewrites=").append(rewrites).append('\n');

        List<String> narrowings = new ArrayList<>();
        for (ActionNarrowing n : plan.getDenyNarrowings()) {
            narrowings.add(n.toString());
        }
        Collections.sort(narrowings);
        sb.append("narrowings=").append(narrowings).append('\n');

        Map<String, List<String>> expansion = new TreeMap<>();
        plan.getReverseExpansion().forEach((k, v) -> expansion.put(k, new ArrayList<>(new TreeSet<>(v))));
        sb.append("expansion=").append(expansion).append('\n');

        List<String> warnings = new ArrayList<>(plan.getWarnings());
        Collections.sort(warnings);
        sb.append("warnings=").append(warnings).append('\n');

        Map<String, ResourceDO> created = new LinkedHashMap<>();
        ResourceCollapsePlanner.sortedIdentities(plan).forEach(identity -> {
            ResourceDO t = plan.getCreatedIdentities().get(identity);
            if (t != null) {
                created.put(identity, t);
            }
        });
        sb.append("created=").append(created).append('\n');
        return sb.toString();
    }

}
