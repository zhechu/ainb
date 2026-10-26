package cn.enlev.ainb.module.system.service.permission;

import cn.hutool.extra.spring.SpringUtil;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.module.system.dal.dataobject.permission.GrantDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.MenuDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.dataobject.user.AdminUserDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.GrantMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.MenuMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper;
import cn.enlev.ainb.module.system.enums.permission.FieldModeEnum;
import cn.enlev.ainb.module.system.enums.permission.GrantActionEnum;
import cn.enlev.ainb.module.system.enums.permission.MenuTypeEnum;
import cn.enlev.ainb.module.system.enums.permission.RoleCodeEnum;
import cn.enlev.ainb.module.system.framework.permission.config.PermissionProperties;
import cn.enlev.ainb.module.system.framework.permission.core.dataset.DatasetResourceNaming;
import cn.enlev.ainb.module.system.framework.permission.core.scan.PermissionStringResolver;
import cn.enlev.ainb.module.system.framework.permission.core.scan.ResourceCollapseApplier;
import cn.enlev.ainb.module.system.framework.permission.core.scan.ResourceCollapseApplier.CollapsedWorld;
import cn.enlev.ainb.module.system.framework.permission.core.scan.ResourceCollapsePlanner;
import cn.enlev.ainb.module.system.framework.permission.core.scan.ResourceCollapsePlanner.CollapsePlan;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectGraph;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;
import cn.enlev.ainb.module.system.service.dept.DeptService;
import cn.enlev.ainb.module.system.service.dept.PostService;
import cn.enlev.ainb.module.system.service.user.AdminUserService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 塌缩迁移的<b>自校验</b>：同一份判定代码，跑在「迁移前的世界」与「迁移后的世界」上，逐个决策点比对
 *
 * <h3>这是阶段 2 唯一的成败判据</h3>
 *
 * 落库（阶段 2d）是不可回滚的一步：DDL/DML 一旦执行，塌缩前的 {@code sys_resource} 行就成了软删的死行，
 * 授权行的 {@code resource_id} 与 {@code action} 也已经被改写。所以「迁移前后判定不变」这件事
 * <b>必须在落库之前</b>就被证明，而且不能用「迁移之后跑一遍看看有没有人报障」来代替 ——
 * 权限变小的表现是静默的（全程 200、无异常，只是按钮没了、接口 403）。
 *
 * <h3>为什么是「两个世界 + 同一个 Service」而不是「两个 Service」</h3>
 *
 * 被验证的对象是<b>数据迁移</b>，不是判定代码。判定代码在两边必须是同一个实例、同一份字节，
 * 唯一允许变化的只有：① 喂给它的资源/授权行，② {@code collapse-enabled} 这个开关。
 * 这两个恰好就是生产上真实发生的切换 —— 落库改数据、然后翻开关，没有第三种变化。
 *
 * <h3>🔴 差分测试的关键性质：桩是同一份实现</h3>
 *
 * 下面那些 Mapper 桩把 SQL 语义（status 过滤、过期过滤、租户过滤、path 上溯）在内存里复刻了一遍。
 * 复刻就可能复刻错。但<b>两个世界共用同一份桩实现</b>，所以桩里的任何系统性错误
 * 会以完全相同的方式作用于两侧，在差分里<b>相互抵消</b>。
 * 能留在差分里的，只有「与塌缩前/后交互方式不同」的错误 —— 而那正是本测试要找的东西。
 *
 * <p>这条性质也是「不要为了让差分变干净而给某一侧单独加桩」的理由：单侧的桩不再抵消，
 * 它会制造出一个假的差异（或者更糟，掩盖一个真的）
 *
 * <h3>变异探针（改坏<b>被测的生产代码</b>，看这个差分还抓不抓得住）</h3>
 *
 * 差分测试有一个特有的失效模式：两侧桩一致 ⇒ 差异恒为 0 ⇒ 全绿，
 * 而它对任何真实缺陷都同样恒为 0。所以除了「快照不在就跳过」那道假绿灯之外，
 * 还必须证明<b>把生产代码改坏时它真的会红</b>。下面两条都真跑过
 * （改的是 {@code GrantDecisionService}，即 R11 在判定侧的两处镜像），跑完逐字节恢复：
 *
 * <table border="1">
 *   <caption>判定侧镜像的两次变异与其被捕获情况</caption>
 *   <tr><th>#</th><th>改坏的地方</th><th>失败数（共 5 个用例）</th></tr>
 *   <tr><td>17</td><td>{@code lookupOf} 去掉 R11 守卫：成员行还在库里也按塌缩去查身份行</td>
 *       <td>2（{@code hasPermission}、{@code hasExplicitDeny}）</td></tr>
 *   <tr><td>18</td><td>{@code listAllowedPermissionsInternal} 去掉 {@code isBlockedFamily} 判断：
 *       拦下的族照样走塌缩那一支</td><td>1（{@code listAllowedPermissions}）</td></tr>
 * </table>
 *
 * 🔴 这两处镜像<b>不是</b>计划器的重复劳动：落库之后被拦下的那 20 个族的成员行仍然在库里，
 * 判定侧如果按塌缩去解析权限串，就会拿着身份串去查一批<b>压根没被上提过</b>的授权
 * —— 结果是那 20 个族整片失权，而且不报错。计划侧的 R11 只管「不去改数据」，
 * 判定侧的 R11 才管「不去按改过的样子读数据」，缺一个就漏。
 *
 * <p>⚠️ 但本类<b>没有快照就整类跳过</b>，所以上面第 17 条在 CI 上等于没有网。
 * 行为级的快速信号在 {@code GrantDecisionServiceTest#testHasPermission_塌缩打开但族被R11拦下时不翻译}
 * —— 同一个变异在那边挂在行为断言上，不依赖任何仓库外夹具。两处都要留着：
 * 那边验的是「一处判定点上的方向对不对」，这边验的是「117 个族、6 万个决策点上没有第二处漏」
 *
 * <h3>快照不在就整类跳过，不是失败</h3>
 *
 * 夹具来自仓库外的 {@code _scratch/s2c_snapshot.json}（含真实用户/角色/租户 id，不进 git）。
 * CI 上没有那个库，把「没导出快照」判成红灯只会让人学会忽略红灯。
 * 反过来说：<b>这个测试绿了不等于校验过了</b>，得看报告里的决策点数是不是预期的量级
 *
 * @author 走进新时代
 */
class CollapseMigrationSelfCheckTest {

    /** 每个检查最多列出多少条差异样本。全列出来会把报告淹掉，而定位只需要前几条 */
    private static final int MAX_SAMPLES = 20;

    private static final PermissionStringResolver RESOLVER = new PermissionStringResolver();

    /** 全部 13 个动作码（含通配符 {@code *}），顺序即枚举声明顺序 */
    private static final List<String> ACTIONS;

    static {
        List<String> codes = new ArrayList<>();
        for (GrantActionEnum action : GrantActionEnum.values()) {
            codes.add(action.getCode());
        }
        ACTIONS = Collections.unmodifiableList(codes);
    }

    // ==================== 静态装配（@BeforeAll 里建好，全部用例共用）====================

    private static CollapseSnapshot snapshot;
    private static List<AdminUserDO> users;
    /** 待测权限串全集：{@code sys_resource.permission} 的非空去重值 */
    private static List<String> permissions;
    /**
     * 其中被本次迁移<b>完全没碰过</b>的那些
     *
     * <p>判据是实测出来的、不是从计划推的：两个世界里它对应的启用资源行集合、
     * 以及挂在这些行上的授权行签名集合，逐项相同。见 {@link #computeUntouched}
     *
     * @see #userPoints 里对「为什么非 VIEW 动作只跑这一批」的说明
     */
    private static List<String> untouchedPermissions;
    /**
     * 唯一一批<b>允许</b>出现「迁移后收紧」的权限串，直接取自
     * {@link CollapsePlan#getDefaultEffectNarrowings()}
     *
     * <p>🔴 白名单不是「放宽断言」，而是把计划器<b>已经声明过</b>的取舍拿过来对账：
     * 断言仍然是「放开 = 0」外加「收紧的每一个点都落在这张表推出的形状里」，
     * 所以任何一处<b>新增的</b>默认值改写照样会红。理由与代价见
     * {@code ResourceCollapsePlanner#applyStrictestDefaultEffect}
     */
    private static Set<String> expectedNarrowings;
    /**
     * {@code listAllowedPermissions} 迁移后<b>允许</b>多出来的东西：塌缩族的裸身份串，
     * 且<b>不在</b> {@code system_menu.permission} 里。构造见 {@link #computeIdentityGains()}
     */
    private static Set<String> expectedIdentityGains;
    /** 其中属于列/字段资源的那些，{@code resolveFieldModes} 的唯一合法入参形态 */
    private static List<String> fieldPermissions;
    private static List<String[]> subjectTuples;
    private static List<Long> tenantIds;

    private static World before;
    private static World after;
    private static CollapsePlan plan;
    /** 应用器的产出。留一份是为了在报告里对上「身份表有多少项」 */
    private static CollapsedWorld collapsed;

    private static GrantDecisionService decisionService;
    private static PermissionProperties properties;

    /** 当前正在被查的世界。所有 Mapper 桩都读它 —— 一套桩服务两个世界，见类注释 */
    private static World world;
    /** 当前租户。授权侧的桩按它过滤，复刻 {@code TenantDatabaseInterceptor} */
    private static Long tenantId;

    /** 报告行。@AfterAll 一次性打出来，失败信息里也带上 */
    private static final List<String> REPORT = new ArrayList<>();

    @BeforeAll
    static void buildWorlds() {
        CollapseSnapshot loaded = CollapseSnapshot.loadOrNull();
        assumeTrue(loaded != null,
                "没找到 _scratch/s2c_snapshot.json —— 先跑 _scratch/s2c_export.py 导出快照");
        snapshot = loaded;
        // 主体图非空时，下面那个「SubjectGraphLoader 返回空图」的桩就不忠实了，必须停下来重建
        snapshot.assertSubjectGraphEmpty();

        permissions = snapshot.allPermissions();
        fieldPermissions = permissions.stream()
                .filter(p -> p.contains(DatasetResourceNaming.COLUMN_SEGMENT)
                        || p.contains(DatasetResourceNaming.FIELD_SEGMENT))
                .toList();
        subjectTuples = snapshot.grantSubjectTuples();
        tenantIds = distinctGrantTenants(snapshot.grants());

        // 迁移前的世界 = 快照原样；迁移后的世界 = 计划器 + 应用器的产出。
        // 🔴 用【真的】计划器与应用器，不在这儿另拼一份 —— 另拼一份的话，
        // 校验通过证明的只是校验代码自己没错，与真正要执行的那份 DML 无关
        plan = new ResourceCollapsePlanner(RESOLVER).plan(
                snapshot.resources(), snapshot.grants(), snapshot.menuPermissions(), snapshot.navMenuIds());
        CollapsedWorld applied = ResourceCollapseApplier.apply(
                snapshot.resources(), snapshot.grants(), plan,
                ResourceCollapseApplier.sequentialFrom(snapshot.resources()));
        collapsed = applied;

        before = new World("迁移前", false, snapshot.resources(), snapshot.grants());
        after = new World("迁移后", true, applied.resources(), applied.grants());
        // ⚠️ 必须等两个世界都建好再算：判据是「两边逐项相同」，只有一边算不出来
        untouchedPermissions = computeUntouched();
        expectedNarrowings = plan.getDefaultEffectNarrowings();
        expectedIdentityGains = computeIdentityGains();

        users = snapshot.users();
        wireServices();
        reportPlan();
    }

    /**
     * 算出迁移后<b>允许</b>多放行出来的那一批：塌缩族的裸身份串
     *
     * <p>两处消费它，是同一件事的两个读法：
     * {@code listAllowedPermissions} 的清单里多出这一项（{@link #assertSameSetPerUser} 的
     * {@code declaredGains}），以及 {@code hasPermission} 在这个串上由无权变有权
     * （{@link #isDeclaredGain}）。
     *
     * <h3>它为什么会多出来</h3>
     *
     * 塌缩后 {@code GrantDecisionService#listAllowedPermissionsInternal} 对身份行逐个候选动作判定，
     * 其中 VIEW 那一路除了按展开表还原成 {@code :query} 这种原始串，还会把<b>裸身份串自己</b>
     * 也 {@code add} 进清单。而身份行刚刚吸收了全体成员行的授权，
     * 于是「VIEW 通过」这件事对更多主体成立了 —— 裸身份串就出现在了一些迁移前没有它的人的清单里。
     *
     * <h3>🔴 为什么可以放行，以及这条白名单的边界画在哪</h3>
     *
     * 放行「多出来」是很危险的事（多给就是越权），所以边界必须画得比差异本身更窄。
     * 这一批串<b>没有任何消费方在比</b>：前端 {@code v-hasPermi} 与后端
     * {@code @ss.hasPermission} 比的都是导航表里配的那个带动词的串。
     * 这句话的前半截（不在 {@code system_menu.permission} 里）<b>可以在这里机器验证</b>，
     * 所以白名单直接与导航表取了差集 ——
     * 只要多出来的那个身份串还在导航表里，它就落不进白名单，本项立刻变红，
     * 而不是被「反正都是身份串」糊过去。
     *
     * <p>后半截（没有一处代码、也没有一张配置表写着它）机器验不了，
     * 是一次全仓 grep + 全库扫列的结论，<b>核实过程与命中清单一并记在
     * {@link #isDeclaredGain} 的 javadoc 里</b> —— 只记在 commit 里的话，
     * 将来谁读到这份白名单会以为「反正没人比」是随口一说，而它其实是本项唯一的正当性来源
     *
     * <p>不需要额外排除被 R11 整族拦下的身份：那些族的成员行还活着、身份行一条授权也没吸收，
     * 压根不进 {@code plan.getIdentityOf()}，也就不在这张表里
     */
    private static Set<String> computeIdentityGains() {
        Set<String> menuPermissions = snapshot.menuPermissions();
        Set<String> identities = new LinkedHashSet<>(plan.getIdentityOf().keySet());
        identities.addAll(plan.getCreatedIdentities().keySet());
        Set<String> out = new LinkedHashSet<>();
        for (String identity : identities) {
            if (!menuPermissions.contains(identity)) {
                out.add(identity);
            }
        }
        REPORT.add(String.format(
                "[白名单] 塌缩族身份 %d 个，其中不在导航表里（即允许作为「多出来的项」放行）%d 个；"
                        + "在导航表里的 %d 个<b>不放行</b> —— 它们真的会被 v-hasPermi 比到",
                identities.size(), out.size(), identities.size() - out.size()));
        return Collections.unmodifiableSet(out);
    }

    /**
     * 挑出迁移<b>完全没碰过</b>的权限串：两个世界里，它对应的启用资源行 id 集合相同，
     * 且挂在这批行上的授权行签名集合也相同
     *
     * <h3>🔴 为什么非 VIEW 动作只能跑这一批</h3>
     *
     * 判据不是「这个串会不会塌缩」。反例是 {@code system:dept}：它末段不是动词、
     * {@code isCollapsed()} 恒为 false，看起来是「不动的」，但它<b>是身份</b> ——
     * {@code system:dept:query/create/update/delete} 那 4 行被退役，授权全搬到了它身上
     * （落库后实测：4 行 {@code deleted=1}，2056 上挂着 28 条授权、其中 2 条判重软删）。
     * 于是 {@code hasExplicitDeny(ROLE, null, "system:dept", CREATE)} 迁移前查的是
     * DATASET 行 2056（上面一条 DENY 都没有）、迁移后查的是同一行但已经带着搬过来的授权，
     * <b>两个世界回答的根本不是同一个问题</b>。按「不塌缩」筛会把这类串留在动作维度里，
     * 报出一片根本不是缺陷的「差异」——
     * 报假差异的坏处和漏报一样大：人学会忽略红灯之后，真差异也一起被忽略了
     *
     * <p>⚠️ 本注释早期拿 {@code system:user}（2061）举这个例子，<b>如今它是反过来的</b>：
     * 那一族被 R11（存活后代继承泄漏）整族拦下，8 行动词成员至今 {@code deleted=0}、
     * 锚点至今 {@code default_effect=ALLOW}，一条授权也没搬 —— 所以它<b>真的</b>属于
     * {@code untouchedPermissions}。R11 是后加的，例子没跟着换，
     * 照着去库里核对只会得出「这注释在瞎写」的结论
     *
     * <p>🔴 这个例子还说明了一件事：{@code system:dept} 的<b>行集合两边完全相同</b>
     * （都只有 2056 一行、都启用），把它排除掉靠的是<b>授权签名</b>那半条判据。
     * 也就是说签名比对不是防御性的冗余，它今天就承担着全部工作量 ——
     * 只比 id 集合的写法会把 {@code system:dept} 判成「没碰过」，
     * 然后 {@code hasExplicitDeny} 在它身上报出成片假差异
     *
     * <p>「行集合 + 授权签名都相同」这条判据则从构造上保证两边答案必然相等，
     * 所以它筛出来的这批是<b>干净的回归网</b>：绿是因为迁移确实没碰它们，
     * 一旦红了就一定是有东西动了不该动的
     *
     * <p>签名里带上 {@code action} 与 {@code effect} 是防御性的：
     * 只比 id 集合在今天的计划器下也够（被改写的授权一定换到别的行上、
     * 判重的授权一定消失，都不可能留在原行），但那条推理链一旦哪天不成立，
     * 只比 id 的写法会<b>静默</b>把一批动过的串当成没动过的放进来
     */
    private static List<String> computeUntouched() {
        List<String> out = new ArrayList<>();
        for (String permission : permissions) {
            List<ResourceDO> bRows = before.enabledByPermission().getOrDefault(permission, List.of());
            List<ResourceDO> aRows = after.enabledByPermission().getOrDefault(permission, List.of());
            if (!rowIdsOf(bRows).equals(rowIdsOf(aRows))
                    || !grantSignaturesOn(before, bRows).equals(grantSignaturesOn(after, aRows))) {
                continue;
            }
            out.add(permission);
        }
        return Collections.unmodifiableList(out);
    }

    private static Set<Long> rowIdsOf(List<ResourceDO> rows) {
        Set<Long> ids = new LinkedHashSet<>(rows.size());
        for (ResourceDO r : rows) {
            ids.add(r.getId());
        }
        return ids;
    }

    /** 这批资源行上挂着的全部授权，按 (id, resource_id, action, effect) 取签名 */
    private static Set<String> grantSignaturesOn(World target, List<ResourceDO> rows) {
        Set<String> out = new LinkedHashSet<>();
        for (GrantDO g : target.grants()) {
            for (ResourceDO r : rows) {
                if (r.getId().equals(g.getResourceId())) {
                    out.add(g.getId() + "\u0000" + g.getResourceId() + "\u0000"
                            + g.getAction() + "\u0000" + g.getEffect());
                    break;
                }
            }
        }
        return out;
    }

    @AfterAll
    static void printReport() throws IOException {
        // 🔴 写文件而不是只打 stdout：Windows 上 Maven 控制台是 GBK，
        // 而报告里全是中文权限串与中文说明，打到控制台就成了一堆乱码，
        // 而这份报告正是本测试<b>唯一的产出</b> —— 断言只说「有几处不同」，
        // 「哪些不同、是收紧还是放开」全在报告里。读不出来等于没跑
        Path out = Paths.get("target", "s2c-selfcheck-report.txt");
        Files.writeString(out, String.join(System.lineSeparator(), REPORT), StandardCharsets.UTF_8);
        System.out.println("[CollapseMigrationSelfCheckTest] 报告已写入 " + out.toAbsolutePath());
    }

    // ==================== 五个检查 ====================

    @Test
    @DisplayName("hasPermission：逐用户 × 逐权限串，迁移前后判定一致")
    void hasPermission_迁移前后逐点一致() {
        List<Decision> points = userPoints();
        assertSameDecisions("hasPermission", points, Decision::tenantId, Reading.ALLOW,
                i -> {
                    Decision d = points.get(i);
                    return decisionService.hasPermission(d.userId(), d.permission(), null, d.action());
                });
    }

    @Test
    @DisplayName("hasExplicitDeny：逐租户 × 逐主体元组 × 逐权限串，迁移前后判定一致")
    void hasExplicitDeny_迁移前后逐点一致() {
        List<TupleDecision> points = tuplePoints();
        assertSameDecisions("hasExplicitDeny", points, TupleDecision::tenantId, Reading.DENY,
                i -> {
                    TupleDecision d = points.get(i);
                    return decisionService.hasExplicitDeny(
                            d.subjectType(), d.subjectCode(), d.permission(), d.action());
                });
    }

    @Test
    @DisplayName("resolveFieldModes：逐用户批量解析字段模式，迁移前后一致")
    void resolveFieldModes_迁移前后一致() {
        assertFalse(fieldPermissions.isEmpty(),
                "快照里一个列/字段权限串都没有 —— resolveFieldModes 这一项就成了空跑，"
                        + "先确认导出脚本有没有漏掉 COLUMN/FIELD 类型的资源行");
        Map<Long, Map<String, FieldModeEnum>> b = new LinkedHashMap<>();
        Map<Long, Map<String, FieldModeEnum>> a = new LinkedHashMap<>();
        for (AdminUserDO user : users) {
            b.put(user.getId(), runAgainst(before, user.getTenantId(),
                    () -> decisionService.resolveFieldModes(user.getId(), fieldPermissions, null)));
            a.put(user.getId(), runAgainst(after, user.getTenantId(),
                    () -> decisionService.resolveFieldModes(user.getId(), fieldPermissions, null)));
        }
        List<String> diffs = new ArrayList<>();
        for (AdminUserDO user : users) {
            Map<String, FieldModeEnum> x = b.get(user.getId());
            Map<String, FieldModeEnum> y = a.get(user.getId());
            assertEquals(x.keySet(), y.keySet(), "用户 " + user.getId() + " 的字段模式键集不同");
            for (String key : x.keySet()) {
                if (x.get(key) != y.get(key)) {
                    diffs.add(String.format("用户 %d / %s：%s → %s",
                            user.getId(), key, x.get(key), y.get(key)));
                }
            }
        }
        note("resolveFieldModes", users.size() + " 个用户 × " + fieldPermissions.size() + " 个字段串",
                diffs);
        assertEquals(List.of(), sample(diffs), reportTail());
    }

    @Test
    @DisplayName("listAllowedPermissions：逐用户的权限串清单，迁移前后一致")
    void listAllowedPermissions_迁移前后一致() {
        assertSameSetPerUser("listAllowedPermissions", decisionService::listAllowedPermissions,
                String::valueOf, expectedNarrowings, expectedIdentityGains);
    }

    @Test
    @DisplayName("listAllowedMenuIds：逐用户的可见菜单集合，迁移前后一致（按钮与脏 id 除外）")
    void listAllowedMenuIds_迁移前后一致() {
        // 🔴 只比【消费方真能看见的】那些菜单 id，因为唯一的生产消费方会连着丢两类：
        //
        //   AuthController#getPermissionInfo
        //     → menuService.getMenuList(menuIds)     ← 丢掉「表里压根没有」的脏 id
        //     → filterDisableMenus(...)              ← 丢掉停用的
        //     → AuthConvert#buildMenuTree(...)       ← 第一件事 removeIf(type == BUTTON)
        //
        // 拿未过滤的原始集合去比，会把 540 个按钮 menu_id 的消失报成 577 处差异 ——
        // 而它们<b>结构上必然消失</b>（承载它们的那 540 行 ACTION 资源被合并软删了，
        // 一个身份只留一个 menu_id），且对导航树毫无影响。
        // 报一堆结构性的假差异，等于把这一项变成没人读的噪音。
        //
        // ⚠️ 过滤依据全部来自<b>菜单表</b>，而本次迁移不碰菜单表 —— 两个世界的菜单表
        // 逐字节相同，所以按它过滤不会掩盖任何由迁移造成的差异；
        // 被掩盖的只有「消费方本来就看不见的东西」，那正是应该滤掉的
        Set<Long> buttonMenuIds = new LinkedHashSet<>();
        Set<Long> visibleMenuIds = new LinkedHashSet<>();
        Map<Long, MenuDO> menuById = new LinkedHashMap<>();
        for (MenuDO m : snapshot.menus()) {
            menuById.put(m.getId(), m);
            if (MenuTypeEnum.BUTTON.getType().equals(m.getType())) {
                buttonMenuIds.add(m.getId());
            } else {
                visibleMenuIds.add(m.getId());
            }
        }
        assertFalse(buttonMenuIds.isEmpty(),
                "快照里一个按钮菜单都没有 —— 过滤条件写错了，或者导出脚本漏了 type 列");
        assertSameSetPerUser("listAllowedMenuIds",
                userId -> {
                    Set<Long> ids = new LinkedHashSet<>(decisionService.listAllowedMenuIds(userId));
                    // 脏 id 单独报出来再丢：它进不了导航树，但「有一行资源指向一个不存在的菜单」
                    // 是数据问题，该被人看见，只是不该由这一项校验来拦
                    for (Long id : ids) {
                        if (!menuById.containsKey(id)) {
                            REPORT.add(String.format(
                                    "[listAllowedMenuIds] ⚠️ 用户 %d 的结果里有脏 menu_id %d"
                                            + "（system_menu 里没有这一行）—— 消费方 getMenuList 拿不到它，"
                                            + "对导航树无影响，但资源表该修", userId, id));
                        }
                    }
                    ids.retainAll(visibleMenuIds);
                    return ids;
                },
                id -> {
                    MenuDO m = menuById.get(id);
                    return m == null ? id + "(快照里没有这个菜单)"
                            : String.format("%d(%s, type=%d)", id, m.getName(), m.getType());
                },
                // 菜单侧不留白名单，两个方向都不留：计划声明的那批默认值改写针对的是<b>权限串</b>，
                // 而它们对应的锚点行 menu_id 实测全是 NULL（DATASET 行不挂菜单），
                // 所以按理不该在菜单集合上产生任何差异。真出现了就得看，而不是先放行
                Set.of(), Set.of());
    }

    // ==================== 决策点枚举 ====================

    /** 一个「用户 × 权限串 × 动作」的决策点 */
    private record Decision(Long tenantId, Long userId, String permission, String action) {
    }

    /** 一个「租户 × 主体元组 × 权限串 × 动作」的决策点。hasExplicitDeny 没有用户，租户要单独给 */
    private record TupleDecision(Long tenantId, String subjectType, String subjectCode,
                                 String permission, String action) {
    }

    /**
     * 枚举 {@code hasPermission} 的决策点
     *
     * <h3>🔴 非 VIEW 动作只在【迁移完全没碰过】的串上跑</h3>
     *
     * 两条理由，缺一条都会报出成片的假差异：
     *
     * <ul>
     *   <li><b>会塌缩的串</b>：{@code GrantDecisionService#lookupOf} 用串里解析出的动作
     *       <b>覆盖</b>调用方传进来的动作，{@code hasPermission(u, "system:user:create", null, DELETE)}
     *       迁移后判的是「{@code system:user} 上的 CREATE」，与迁移前
     *       「{@code system:user:create} 上的 DELETE」不是同一个问题。
     *       那个调用形状在生产上也不存在：唯一传非 VIEW 动作的调用方是
     *       {@code PermissionApiImpl#hasResourceAction}（BPM 流程族），传的是
     *       {@code bpm:process:leave} 这类业务专有动词串，属档 C，压根不塌缩</li>
     *   <li><b>不塌缩、但作为身份接收了成员的串</b>：见 {@link #computeUntouched} 里的
     *       {@code system:dept} 反例</li>
     * </ul>
     *
     * <p>而塌缩串上的动作迁移并没有漏掉 —— VIEW 那一路就是它的完整检验：
     * {@code hasPermission(u, "system:user:create", null, VIEW)} 迁移后判的是
     * 「{@code system:user} 上的 CREATE」，DENY 行的动作收窄（计划器的
     * {@code denyNarrowings}）也正是被这一路覆盖的。清单那两项
     * （{@code listAllowedPermissions} / {@code listAllowedMenuIds}）在开关打开后
     * 还会把授权里出现过的<b>每一个</b>动作都问一遍，那是动作维度的第二张网
     */
    private static List<Decision> userPoints() {
        List<Decision> points = new ArrayList<>();
        for (AdminUserDO user : users) {
            for (String permission : permissions) {
                points.add(new Decision(user.getTenantId(), user.getId(), permission,
                        GrantActionEnum.VIEW.getCode()));
            }
            for (String permission : untouchedPermissions) {
                for (String action : ACTIONS) {
                    if (GrantActionEnum.VIEW.getCode().equals(action)) {
                        continue;   // 上面那一轮已经跑过
                    }
                    points.add(new Decision(user.getTenantId(), user.getId(), permission, action));
                }
            }
        }
        return points;
    }

    /**
     * 枚举 {@code hasExplicitDeny} 的决策点。动作维度的取舍与 {@link #userPoints} 同一条理由
     *
     * <p>逐租户跑：{@code GrantDO extends TenantBaseDO}，租户隔离是开的，
     * 同一条 DENY 在别的租户下压根查不到。只跑一个租户会漏掉另外三个
     */
    private static List<TupleDecision> tuplePoints() {
        List<TupleDecision> points = new ArrayList<>();
        for (Long tenant : tenantIds) {
            for (String[] tuple : subjectTuples) {
                for (String permission : permissions) {
                    points.add(new TupleDecision(tenant, tuple[0], tuple[1], permission,
                            GrantActionEnum.VIEW.getCode()));
                }
                for (String permission : untouchedPermissions) {
                    for (String action : ACTIONS) {
                        if (GrantActionEnum.VIEW.getCode().equals(action)) {
                            continue;
                        }
                        points.add(new TupleDecision(tenant, tuple[0], tuple[1], permission, action));
                    }
                }
            }
        }
        return points;
    }

    // ==================== 比对 ====================

    /** 一个决策点的求值。下标口径由调用方持有的那份点列表决定 */
    private interface Evaluate {
        boolean at(int i);
    }

    /**
     * 一个布尔检查该怎么读：true/false 怎么念，以及两个差异方向各自意味着什么
     *
     * <p>🔴 必须参数化，不能在 {@link #assertSameDecisions} 里写死「收紧/放开」——
     * {@code hasExplicitDeny} 的 true 是<b>「有人明确说不」</b>，方向与 {@code hasPermission}
     * 正好相反：那边 true→false 是功能丢失，这边 true→false 是<b>少了一条禁止令</b>，即越权。
     * 写死的话报告会告诉人「这只是收紧，无所谓」，而实际是安全事故
     *
     * @param beforeOnly 迁移前为 true、迁移后为 false 的差异该怎么念
     * @param afterOnly  迁移前为 false、迁移后为 true 的差异该怎么念
     */
    private record Reading(String yes, String no, String beforeOnly, String afterOnly) {

        static final Reading ALLOW = new Reading("有权", "无权",
                "迁移后收紧（功能丢失方向）", "迁移后放开（越权方向）");

        static final Reading DENY = new Reading("有 DENY", "无 DENY",
                "迁移后少了一条 DENY（放开方向，即越权）", "迁移后多了一条 DENY（收紧方向）");
    }

    /**
     * 两遍跑，不是逐个决策点翻世界
     *
     * <p>逐个翻的话 {@code resourceChainCache} 每个点都要清一次，而它的存在意义就是
     * 「同一个权限串只查一次库」—— 清掉之后 128k 个点就是 128k 次全量建链，
     * 慢一个数量级不说，还会把「缓存键在塌缩后必须换成身份串」这条性质一起测没了
     */
    private static <P> void assertSameDecisions(String name, List<P> points,
                                                Function<P, Long> tenantOf,
                                                Reading reading, Evaluate evaluate) {
        BitSet b = pass(before, points, tenantOf, evaluate);
        BitSet a = pass(after, points, tenantOf, evaluate);
        // 🔴 空跑守卫。「差异 0 个」有两种成因：迁移真的等价，或者<b>压根没看见任何授权</b>。
        // 两者在报告里长得一模一样，而后者是全绿的最坏形式。
        // 授权桩按 tenantId 过滤（GrantDO extends TenantBaseDO），租户设错就会走到这一步 ——
        // 本类第一版就犯过：pass() 只切世界不设租户，于是 hasPermission 那 47245 个点
        // 全部只看 default_effect 回落，把 14 个业务动词串的越权放行走漏了
        assertTrue(b.cardinality() > 0, "[" + name + "] 迁移前一个 true 都没有 —— "
                + "这一项形同空跑，先查租户上下文与授权桩，别把「没数据」当成「没差异」");
        assertTrue(a.cardinality() > 0, "[" + name + "] 迁移后一个 true 都没有 —— 同上");
        List<String> declared = new ArrayList<>();
        List<String> declaredGainSamples = new ArrayList<>();
        List<String> unlisted = new ArrayList<>();
        Set<String> narrowedPermissions = new LinkedHashSet<>();
        Set<String> loosenedPermissions = new LinkedHashSet<>();
        int declaredLoosened = 0;
        // 未登记差异的完整分布：权限串 → 命中点数 / 涉及的动作。
        // 样本被 MAX_SAMPLES 截断，而定性一条差异要看的是「哪些串、哪些动作、多少个点」，
        // 不是「哪 20 个用户」—— 所以这一份必须完整落进报告文件
        Map<String, int[]> unlistedNarrowed = new TreeMap<>();
        Map<String, int[]> unlistedLoosened = new TreeMap<>();
        Map<String, Set<String>> unlistedActions = new TreeMap<>();
        int beforeOnly = 0;
        int afterOnly = 0;
        for (int i = 0; i < points.size(); i++) {
            if (b.get(i) == a.get(i)) {
                continue;
            }
            String rendered = String.format("%s：%s → %s", describe(points.get(i)),
                    b.get(i) ? reading.yes() : reading.no(), a.get(i) ? reading.yes() : reading.no());
            if (b.get(i)) {
                beforeOnly++;
                if (isDeclaredNarrowing(points.get(i))) {
                    narrowedPermissions.add(permissionOf(points.get(i)));
                    if (declared.size() < MAX_SAMPLES) {
                        declared.add(rendered + "（计划已声明）");
                    }
                    continue;
                }
                tally(unlistedNarrowed, points.get(i), "N");
            } else {
                afterOnly++;
                if (isDeclaredGain(points.get(i), reading)) {
                    loosenedPermissions.add(permissionOf(points.get(i)));
                    declaredLoosened++;
                    if (declaredGainSamples.size() < MAX_SAMPLES) {
                        declaredGainSamples.add(rendered + "（已核实无人比这个串）");
                    }
                    continue;
                }
                tally(unlistedLoosened, points.get(i), "L");
            }
            // 键里带方向：同一个串可能既有收紧点又有放开点，
            // 合成一份动作集会让两边的分布都失真
            unlistedActions.computeIfAbsent((b.get(i) ? "N" : "L") + permissionOf(points.get(i)),
                    k -> new TreeSet<>()).add(actionOf(points.get(i)));
            if (unlisted.size() < MAX_SAMPLES) {
                unlisted.add(rendered);
            }
        }
        REPORT.add(String.format(
                "[%s] 决策点 %d 个，差异 %d 个（%s %d / %s %d）；"
                        + "已声明的收紧涉及 %d 个身份串 / 计划共声明 %d 个；"
                        + "已核实的放开涉及 %d 个裸身份串共 %d 个点 / 白名单共 %d 个",
                name, points.size(), beforeOnly + afterOnly,
                reading.beforeOnly(), beforeOnly, reading.afterOnly(), afterOnly,
                narrowedPermissions.size(), expectedNarrowings.size(),
                loosenedPermissions.size(), declaredLoosened, expectedIdentityGains.size()));
        dumpDistribution(name, reading.beforeOnly(), unlistedNarrowed, unlistedActions);
        dumpDistribution(name, reading.afterOnly(), unlistedLoosened, unlistedActions);
        REPORT.addAll(declared);
        REPORT.addAll(declaredGainSamples);
        REPORT.addAll(unlisted);
        // 🔴 未登记的差异一律零容忍，两个方向都是。
        // 两个方向各有一份白名单，且都不是手写的：收紧那份是计划器算出来的
        // default_effect 翻转集，放开那份是计划器的塌缩族身份串减去导航表（见 isDeclaredGain
        // 与 computeIdentityGains）。任何一份对不上，差异就会落到 unlisted 里让这一项红
        assertEquals(List.of(), unlisted, reportTail() + "\n未登记的差异：\n"
                + String.join(System.lineSeparator(), unlisted));
    }

    private static void tally(Map<String, int[]> sink, Object point, String direction) {
        sink.computeIfAbsent(direction + permissionOf(point), k -> new int[1])[0]++;
    }

    /**
     * 把一个方向的未登记差异按权限串完整列出来（不截断），带上它涉及的动作
     *
     * <p>两张表的键都带一位方向前缀（{@code N}=收紧 / {@code L}=放开），
     * 打印时 {@code substring(1)} 去掉 —— 前缀存在的唯一目的是让
     * 「同一个串既收紧又放开」时两边的动作集不互相污染
     */
    private static void dumpDistribution(String name, String direction,
                                         Map<String, int[]> byPermission,
                                         Map<String, Set<String>> actions) {
        int total = byPermission.values().stream().mapToInt(c -> c[0]).sum();
        REPORT.add(String.format("[%s] %s 的完整分布：涉及 %d 个权限串、共 %d 个点",
                name, direction, byPermission.size(), total));
        byPermission.forEach((key, count) -> REPORT.add(String.format(
                "    %-52s %4d 个点   动作 %s", key.substring(1), count[0], actions.get(key))));
    }

    /**
     * 这个「迁移前为 true、迁移后为 false」的点，是不是计划器<b>事先声明过</b>的那一类
     *
     * <p>认两条：必须是 {@code hasPermission} 的决策点，且权限串在
     * {@link CollapsePlan#getDefaultEffectNarrowings()} 里。
     *
     * <h3>🔴 为什么<b>不</b>限定动作为 VIEW（第一版限定了，于是漏放行 495 - 99 = 396 个点）</h3>
     *
     * 声明的东西是「<b>这一行</b>的 {@code default_effect} 由 ALLOW 改成了 DENY」，
     * 而消费它的那处回落判定是 {@code GrantDecisionEngine#explain} 在
     * {@code matched.isEmpty()} 时返回的 {@code Decision.ofDefault("ALLOW".equals(self.getDefaultEffect()), self)}
     * —— <b>一个行级标量，压根不带动作维度</b>。所以改这一列，
     * 影响的是这个裸身份串上<b>全部 13 个动作</b>，不只是 VIEW。
     * 把白名单写成「只认 VIEW」不是更严格，是<b>错的</b>：
     * 它会让同一个成因的另外 12 个动作全部报成「未登记的差异」，
     * 而真正的越权点会被淹在这 396 条噪声里看不见。
     *
     * <p>白名单窄才有意义，但窄的对象应该是<b>成因</b>而不是<b>形状</b>：
     * 这里卡死的是「串必须在计划声明的那批里」，而那个集合是计划器算出来的、
     * 且被 {@code [计划] default_effect 改写 N 行} 那行报告公开对过账。
     *
     * <p>{@code hasExplicitDeny} 的点一律不认，两个理由：
     * ① 那个方法<b>不看 default_effect</b>（见它自己的 javadoc），默认值改写在那条路上
     * 压根不该产生任何差异，真出现了就是另一回事；
     * ② 它的 true→false 是「少了一条禁止令」，方向是越权，正是最不该被白名单吃掉的那一类
     */
    private static boolean isDeclaredNarrowing(Object point) {
        return point instanceof Decision d && expectedNarrowings.contains(d.permission());
    }

    /**
     * 这个「迁移前为 false、迁移后为 true」的点，是不是<b>塌缩本身的必然结果</b>、
     * 且已经核实过没有人在比它
     *
     * <h3>为什么这类放开躲不掉（不是计划器的疏漏）</h3>
     *
     * 裸身份串（{@code system:role}、{@code infra:file}）迁移前是<b>导航目录行</b>：
     * type=MENU、{@code default_effect=DENY}、身上一条授权都没有（R9 之前授权全挂在
     * {@code :query}/{@code :create} 那些成员行上），所以 {@code hasPermission(它, VIEW)}
     * 迁移前必然为 false。塌缩把成员行的授权上提到它身上之后，
     * 它成了这个资源<b>唯一</b>的一行，同一个调用必然为 true。
     *
     * <p>想让迁移后仍返回 false，就得让身份行不接收上提的授权 —— 而那正是塌缩的全部内容。
     * 所以这不是「改一下计划就能消掉」的差异，它是<b>模型换了口径</b>的直接后果：
     * 「对某资源有 VIEW」这件事，迁移后由身份行承载，而身份串的权限值就是它自己。
     * {@code listAllowedPermissions} 那 72 项「多出来的裸身份串」是同一件事的清单版，
     * 两边用的是同一份 {@link #expectedIdentityGains}。
     *
     * <h3>🔴 认它之前实际核实过什么（三条，缺一不可）</h3>
     *
     * <ol>
     *   <li><b>不在导航表里</b>：{@link #computeIdentityGains()} 构造时就与
     *       {@code system_menu.permission} 取了差集。这一条是机器可查的，
     *       所以每次跑测试都重新查一遍</li>
     *   <li><b>没有任何一处源码用引号逐字节比它</b>：全仓扫了 4851 个
     *       {@code .java}/{@code .vue}/{@code .ts}/{@code .js} 文件，找
     *       {@code 'X'}/{@code "X"}/{@code `X`} 形式的出现。命中 5 处，
     *       <b>全部在测试夹具里</b>（{@code ResourceCollapsePlannerTest}、
     *       {@code ResourceCollapseApplierTest}、{@code PermissionStringResolverTest}、
     *       {@code GrantDecisionListPermissionsTest}、前端 {@code resourceTree.spec.ts}），
     *       生产代码 0 处。⚠️ 必须逐字节比而不是子串比：
     *       {@code 'agent:config:query'} 里也含 {@code agent:config}</li>
     *   <li><b>数据库里也没有别的表存着它</b>：扫了 schema 中全部 7 个含 {@code perm} 的字符列
     *       （{@code system_menu} 872 行、{@code infra_form_design_menu} 415 行、
     *       {@code agent_tool_override} 85 行、{@code agent_tool_def}、
     *       {@code agent_tool_override_audit} 两列、{@code sys_resource}），
     *       精确命中只有 {@code sys_resource.permission} 自己（那正是这些身份行）。
     *       这一条堵的是「权限串存在配置表里、运行时取出来再比」这条路 ——
     *       {@code AgentSecurityGateway#checkTool} 就会去读
     *       {@code agent_tool_def.permissions}，那张表今天 0 条有效行</li>
     * </ol>
     *
     * <p>后两条<b>不是</b>机器可查的（它们要扫全仓与全库），所以记在这里而不是只记在
     * commit 里：将来谁新增一个「拿裸身份串当权限点」的比法，
     * 这份白名单就会从「无害」变成「越权」，而测试<b>不会</b>变红。
     * 那 57 个串是 {@code [hasPermission] 迁移后放开 的完整分布} 那一段列出来的，
     * 新增比法之前先拿它去 grep 一遍。
     *
     * <h3>为什么还要额外卡死动作为 VIEW</h3>
     *
     * 结构上任何动作的放开都是同一个成因（身份行吸收了那个动作的上提授权），
     * 实测也确实只有 VIEW（153 个点全是）。卡死 VIEW 不是因为它更有理，
     * 而是为了把白名单收得比「成因」更窄一点：<b>哪天出现一个非 VIEW 的放开，
     * 它一定来自另一件事</b>（比如某个动作的授权被错误地上提），那时候就该红。
     * 与 {@link #isDeclaredNarrowing} 恰好相反 —— 那边放宽动作维度是对的，
     * 因为 {@code default_effect} 本来就不带动作维度。
     *
     * <p>{@code reading == Reading.ALLOW} 这道闸门是给 {@code hasExplicitDeny} 留的：
     * 它的 afterOnly 方向是「多了一条 DENY」（收紧），语义完全不同，
     * 一份为「多给权限」写的白名单套上去就是灾难。两个检查的点类型本来就不同
     * （{@code Decision} vs {@code TupleDecision}），但那是巧合、不是契约，所以显式判一次
     */
    private static boolean isDeclaredGain(Object point, Reading reading) {
        return reading == Reading.ALLOW
                && point instanceof Decision d
                && GrantActionEnum.VIEW.getCode().equals(d.action())
                && expectedIdentityGains.contains(d.permission());
    }

    private static String permissionOf(Object point) {
        return point instanceof Decision d ? d.permission() : ((TupleDecision) point).permission();
    }

    /** 分布报告里要按动作看：同一个串上「只有 VIEW 放开」与「13 个动作全放开」是两回事 */
    private static String actionOf(Object point) {
        return point instanceof Decision d ? d.action() : ((TupleDecision) point).action();
    }

    private static <P> BitSet pass(World target, List<P> points, Function<P, Long> tenantOf,
                                   Evaluate evaluate) {
        switchTo(target);
        BitSet bits = new BitSet(points.size());
        for (int i = 0; i < points.size(); i++) {
            // 🔴 租户必须【逐点】重设，不能在 switchTo 里设一次了事。
            // 点列表是「用户 × 权限串 × 动作」或「租户 × 主体元组 × 权限串 × 动作」，
            // 一个列表里就横跨 4 个租户；而授权桩是按 tenantId 过滤的，
            // 设错的表现不是报错，是「查不到任何授权」⟹ 全部走 default_effect 回落
            // ⟹ 两个世界答案相同 ⟹ 差分恒为空 ⟹ 校验全绿而库里已经改坏了
            Long tenant = tenantOf.apply(points.get(i));
            assertNotNull(tenant, "第 " + i + " 个点没有租户，无法定位该用哪一批授权行");
            tenantId = tenant;
            if (evaluate.at(i)) {
                bits.set(i);
            }
        }
        return bits;
    }

    /**
     * 逐用户比对一个集合型结果
     *
     * @param describeItem   差异行里怎么称呼一个元素。菜单 id 光打数字没法读
     *                       （一百多个差异里哪一个是真页面、哪一个是按钮，全靠它），
     *                       权限串则原样即可
     * @param declaredLosses 允许「迁移后消失」的元素，取自
     *                       {@link CollapsePlan#getDefaultEffectNarrowings()}
     * @param declaredGains  允许「迁移后多出来」的元素。🔴 这一张表必须<b>窄</b>：
     *                       多给就是越权，所以它只放行一类经过论证的项，
     *                       而且构造时就与导航表取了差集（见 {@link #expectedIdentityGains}）——
     *                       任何一个多出来的项只要还在导航表里，就落不进白名单，本项立刻变红
     */
    private static <T> void assertSameSetPerUser(String name, Function<Long, Set<T>> call,
                                                 Function<T, String> describeItem,
                                                 Set<T> declaredLosses, Set<T> declaredGains) {
        List<String> declared = new ArrayList<>();
        List<String> unlisted = new ArrayList<>();
        // 跨用户去重的完整清单。样本被 MAX_SAMPLES 截断，而定性一条「多出来的项」
        // 要看的是它<b>是什么</b>（拿去与 @ss.hasPermission / v-hasPermi 的实际用量比），
        // 不是它出现在哪个用户身上 —— 所以这一份必须完整落进报告文件
        Set<String> gainedDistinct = new TreeSet<>();
        Set<String> lostDistinct = new TreeSet<>();
        int compared = 0;
        int declaredTotal = 0;
        int unlistedTotal = 0;
        for (AdminUserDO user : users) {
            Set<T> b = runAgainst(before, user.getTenantId(), () -> call.apply(user.getId()));
            Set<T> a = runAgainst(after, user.getTenantId(), () -> call.apply(user.getId()));
            compared += b.size();
            // LinkedHashSet 而不是 TreeSet：清单侧返回的本来就是 LinkedHashSet，
            // 迭代顺序确定，样本因此可复现；换成 TreeSet 要额外给 T 找一个比较器，
            // 而 T 在这里既可能是 String 也可能是 Long，没有共同的 Comparable 上界
            Set<T> lost = new LinkedHashSet<>(b);
            lost.removeAll(a);
            Set<T> gained = new LinkedHashSet<>(a);
            gained.removeAll(b);
            int userDeclared = 0;
            for (T x : lost) {
                String line = String.format("用户 %d 少了 %s", user.getId(), describeItem.apply(x));
                lostDistinct.add(describeItem.apply(x));
                if (declaredLosses.contains(x)) {
                    userDeclared++;
                    addSample(declared, line + "（计划已声明）");
                } else {
                    addSample(unlisted, line);
                }
            }
            int userDeclaredGains = 0;
            for (T x : gained) {
                gainedDistinct.add(describeItem.apply(x));
                String line = String.format("用户 %d 多了 %s", user.getId(), describeItem.apply(x));
                if (declaredGains.contains(x)) {
                    userDeclaredGains++;
                    addSample(declared, line + "（计划已声明：塌缩后身份串自己进清单）");
                } else {
                    addSample(unlisted, line);
                }
            }
            declaredTotal += userDeclared + userDeclaredGains;
            unlistedTotal += lost.size() - userDeclared + gained.size() - userDeclaredGains;
            REPORT.add(String.format(
                    "[%s] 用户 %d（租户 %d）：迁移前 %d 项、迁移后 %d 项，"
                            + "少 %d（其中已声明 %d）多 %d（其中已声明 %d）",
                    name, user.getId(), user.getTenantId(), b.size(), a.size(),
                    lost.size(), userDeclared, gained.size(), userDeclaredGains));
        }
        REPORT.add(String.format("[%s] %d 个用户 / 迁移前合计 %d 项，差异 %d 个（已声明 %d / 未登记 %d）",
                name, users.size(), compared, declaredTotal + unlistedTotal,
                declaredTotal, unlistedTotal));
        // 🔴 「多了」是越权方向，所以这一份清单必须逐条给出定性，
        // 不能靠「样本里看着都像身份串」蒙过去。白名单只放行裸身份串那一类，
        // 且构造时就与导航表取了差集 —— 见 expectedIdentityGains
        REPORT.add(String.format("[%s] 迁移后多出来的完整清单（跨用户去重 %d 项）：%s",
                name, gainedDistinct.size(), gainedDistinct));
        REPORT.add(String.format("[%s] 迁移后少掉的完整清单（跨用户去重 %d 项）：%s",
                name, lostDistinct.size(), lostDistinct));
        REPORT.addAll(declared);
        REPORT.addAll(unlisted);
        assertEquals(List.of(), unlisted, reportTail() + "\n未登记的差异：\n"
                + String.join(System.lineSeparator(), unlisted));
    }

    /** 只留前 {@link #MAX_SAMPLES} 条样本；计数由调用方单独累加，所以截断不会丢掉总数 */
    private static void addSample(List<String> sink, String line) {
        if (sink.size() < MAX_SAMPLES) {
            sink.add(line);
        }
    }

    private static <T> T runAgainst(World target, Long tenant, Supplier<T> call) {
        switchTo(target);
        tenantId = tenant;
        return call.get();
    }

    /**
     * 切到某个世界
     *
     * <p>🔴 必须清资源链缓存。{@code resourceChainCache} 是进程级的、只按权限串做键，
     * 不清的话「迁移后」那一遍会直接复用「迁移前」建好的祖先链 ——
     * 两边读的是同一批 ResourceDO 对象，差分恒为空，校验形同没有，而且全绿
     */
    private static void switchTo(World target) {
        world = target;
        properties.setCollapseEnabled(target.collapse());
        decisionService.evictResourceChainCache();
    }

    /** 往报告里记一行结论 + 若干样本。名字不叫 record：本类里已经有三个 record 类型了 */
    private static void note(String name, String scope, List<String> diffs) {
        REPORT.add(String.format("[%s] %s，差异 %d 个", name, scope, diffs.size()));
        REPORT.addAll(sample(diffs));
    }

    private static List<String> sample(List<String> diffs) {
        return diffs.size() <= MAX_SAMPLES ? diffs : diffs.subList(0, MAX_SAMPLES);
    }

    /**
     * 失败信息末尾那句「去哪儿看全文」
     *
     * <p>🔴 不能写「见标准输出」：Windows 上 Maven 控制台是 GBK，报告里的中文全是乱码，
     * 而 {@code @AfterAll} 已经把它按 UTF-8 落盘了。指错地方的后果是
     * 人盯着一屏乱码猜差异是什么 —— 而这份报告正是本测试唯一的产出
     */
    private static String reportTail() {
        return "完整报告见 enlev-module-system/target/s2c-selfcheck-report.txt（UTF-8）";
    }

    private static String describe(Object point) {
        if (point instanceof Decision d) {
            return String.format("用户 %d / 租户 %d / %s / %s",
                    d.userId(), d.tenantId(), d.permission(), d.action());
        }
        TupleDecision d = (TupleDecision) point;
        return String.format("租户 %d / 主体 %s:%s / %s / %s",
                d.tenantId(), d.subjectType(), d.subjectCode(), d.permission(), d.action());
    }

    // ==================== 装配 ====================

    private static void wireServices() {
        properties = new PermissionProperties();
        // 与生产配置对齐（application-local.yaml / 线上都是 dept-enabled: true）。
        // 主体闭包在两个世界里完全相同，所以这几个开关的取值不影响差分，
        // 但影响【覆盖面】：开着部门主体，闭包里就多一批 DEPT 键，能命中更多授权行
        properties.getSubject().setPostEnabled(true);
        properties.getSubject().setDeptEnabled(true);
        properties.getSubject().setDeptAncestorEnabled(true);

        ResourceMapper resourceMapper = mock(ResourceMapper.class);
        GrantMapper grantMapper = mock(GrantMapper.class);
        MenuMapper menuMapper = mock(MenuMapper.class);
        RoleService roleService = mock(RoleService.class);
        PermissionService permissionService = mock(PermissionService.class);

        // 资源侧：ResourceDO 带 @TenantIgnore，租户过滤是关的（实测 1022 行全是 tenant_id=0）
        when(resourceMapper.selectListByPermission(any())).thenAnswer(inv ->
                world.enabledByPermission().getOrDefault(inv.getArgument(0), List.of()));
        when(resourceMapper.selectListByPermissions(anyCollection())).thenAnswer(inv -> {
            Collection<String> wanted = inv.getArgument(0);
            if (wanted == null || wanted.isEmpty()) {
                return List.of();
            }
            List<ResourceDO> out = new ArrayList<>();
            for (String p : wanted) {
                out.addAll(world.enabledByPermission().getOrDefault(p, List.of()));
            }
            return out;
        });
        // ⚠️ selectListByPaths 与上面两个不同：它【不过滤 status】。
        // 停用的祖先照样进链 —— 这是 buildAncestors 与 buildChainFromMap 两条口径的既有差异，
        // 桩必须照抄，否则「停用祖先上的 DENY 还生效吗」这个问题在两个世界里会被答成一样的
        when(resourceMapper.selectListByPaths(anyCollection())).thenAnswer(inv -> {
            Collection<String> wanted = inv.getArgument(0);
            if (wanted == null || wanted.isEmpty()) {
                return List.of();
            }
            // 🔴 必须返回新列表：buildAncestors 会对返回值就地 sort，
            // 交出去共享的那份会被反复重排（今天无害，但那是靠运气，不是靠设计）
            List<ResourceDO> out = new ArrayList<>();
            for (String p : wanted) {
                out.addAll(world.byPath().getOrDefault(p, List.of()));
            }
            return out;
        });
        when(resourceMapper.selectList(any())).thenAnswer(inv -> world.enabled());

        // 授权侧：GrantDO extends TenantBaseDO，租户过滤是开的
        when(grantMapper.selectListByResourceIds(anyCollection())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            if (ids == null || ids.isEmpty()) {
                return List.of();
            }
            List<GrantDO> out = new ArrayList<>();
            Map<Long, List<GrantDO>> byResource = world.grantsByResourceOfTenant(tenantId);
            for (Long id : ids) {
                List<GrantDO> hit = byResource.get(id);
                if (hit == null) {
                    continue;
                }
                for (GrantDO g : hit) {
                    if (notExpired(g)) {
                        out.add(g);
                    }
                }
            }
            return out;
        });
        when(grantMapper.selectListActiveBySubjects(anyCollection())).thenAnswer(inv -> {
            Collection<SubjectKey> subjects = inv.getArgument(0);
            List<GrantDO> rows = world.grantsOfTenant(tenantId);
            if (subjects == null || subjects.isEmpty() || rows.isEmpty()) {
                // 🔴 空主体集合必须返回空列表。GrantMapper#buildSubjectWrapper 那条注释写得很清楚：
                // 返回一个空 wrapper 等于没有 WHERE，会全表扫出所有主体的授权行，
                // 而判定侧拿到这批行只会得出「什么都有权限」
                return List.of();
            }
            Set<String> refKeys = new HashSet<>();
            Set<String> codeKeys = new HashSet<>();
            for (SubjectKey key : subjects) {
                if (key.type().isRefBased()) {
                    refKeys.add(subjectKeyOf(key.type().name(), key.refId()));
                } else {
                    codeKeys.add(subjectKeyOf(key.type().name(), key.code()));
                }
            }
            List<GrantDO> out = new ArrayList<>();
            for (GrantDO g : rows) {
                if (!notExpired(g)) {
                    continue;
                }
                if (g.getSubjectRefId() != null
                        && refKeys.contains(subjectKeyOf(g.getSubjectType(), g.getSubjectRefId()))) {
                    out.add(g);
                } else if (g.getSubjectCode() != null
                        && codeKeys.contains(subjectKeyOf(g.getSubjectType(), g.getSubjectCode()))) {
                    out.add(g);
                }
            }
            return out;
        });

        // 导航表本次迁移【不动】，所以两个世界共用同一份
        when(menuMapper.selectList()).thenAnswer(inv -> snapshot.menus());

        when(permissionService.getUserRoleIdListByUserId(anyLong()))
                .thenAnswer(inv -> snapshot.roleIdsOf(inv.getArgument(0)));
        when(roleService.hasAnySuperAdmin(anyCollection())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            if (ids == null || ids.isEmpty()) {
                return false;
            }
            for (var role : snapshot.rolesOf(ids)) {
                if (RoleCodeEnum.isSuperAdmin(role.getCode())) {
                    return true;
                }
            }
            return false;
        });

        decisionService = new GrantDecisionService();
        ReflectionTestUtils.setField(decisionService, "permissionProperties", properties);
        ReflectionTestUtils.setField(decisionService, "resourceMapper", resourceMapper);
        ReflectionTestUtils.setField(decisionService, "grantMapper", grantMapper);
        ReflectionTestUtils.setField(decisionService, "menuMapper", menuMapper);
        ReflectionTestUtils.setField(decisionService, "roleService", roleService);
        ReflectionTestUtils.setField(decisionService, "permissionService", permissionService);
        // subjectClosureService 由 warmUpClosures 注入：它要先把真实现跑出来的闭包收好，
        // 在这里先塞一个空 mock 只会留下一行看不出用途的代码

        warmUpClosures();
    }

    /**
     * 用<b>真的</b> {@link SubjectClosureServiceImpl} 把每个用户的主体闭包算一遍，再喂给判定 Service
     *
     * <h3>为什么可以只算一遍、然后缓存下来</h3>
     *
     * 闭包读的是 user_role / role / user / post / dept / 主体图，<b>一张都不在本次迁移的改写范围里</b>
     * （应用器只重写 sys_resource 与 sys_grant）。所以它对两个世界给出的是同一个集合，
     * 缓存下来与每次重算等价 —— 而重算是 128k 次 × 6 个 mock 调用，纯粹是白烧时间。
     *
     * <p>用真实现而不是直接手写一个 {@code Set.of(SubjectKey.ofRole(...))}，
     * 是因为闭包口径里有几条容易漏的规则：只取启用的角色/岗位/部门、
     * {@code ALL} 主体恒在闭包里、部门默认连祖先一起算。手写一份就是第二份口径，
     * 而「校验用的闭包比生产的小」的表现是<b>校验少覆盖一批授权行，然后全绿</b>
     */
    private static void warmUpClosures() {
        SubjectClosureServiceImpl closure = new SubjectClosureServiceImpl();
        SubjectGraphLoader graphLoader = mock(SubjectGraphLoader.class);
        AdminUserService userService = mock(AdminUserService.class);
        PostService postService = mock(PostService.class);
        DeptService deptService = mock(DeptService.class);
        PermissionService permissionService = mock(PermissionService.class);
        RoleService roleService = mock(RoleService.class);

        // 实测 sys_subject / sys_subject_member 都是 0 行（CollapseSnapshot#assertSubjectGraphEmpty
        // 会在这一点变化时直接抛），所以「空图」是忠实的
        when(graphLoader.load(anyBoolean())).thenReturn(new SubjectGraph(List.of()));
        when(permissionService.getUserRoleIdListByUserIdFromCache(anyLong()))
                .thenAnswer(inv -> snapshot.roleIdsOf(inv.getArgument(0)));
        when(roleService.getRoleListFromCache(anyCollection()))
                .thenAnswer(inv -> new ArrayList<>(snapshot.rolesOf(inv.getArgument(0, Collection.class))));
        when(userService.getUser(anyLong())).thenAnswer(inv -> snapshot.userOf(inv.getArgument(0)));
        when(postService.getPostList(anyCollection()))
                .thenAnswer(inv -> new ArrayList<>(snapshot.postsOf(inv.getArgument(0, Collection.class))));
        when(deptService.getDept(anyLong())).thenAnswer(inv -> snapshot.deptOf(inv.getArgument(0)));
        when(deptService.getParentDeptList(anyLong()))
                .thenAnswer(inv -> snapshot.parentDeptsOf(inv.getArgument(0)));

        ReflectionTestUtils.setField(closure, "subjectGraphLoader", graphLoader);
        ReflectionTestUtils.setField(closure, "permissionService", permissionService);
        ReflectionTestUtils.setField(closure, "roleService", roleService);
        ReflectionTestUtils.setField(closure, "postService", postService);
        ReflectionTestUtils.setField(closure, "deptService", deptService);
        ReflectionTestUtils.setField(closure, "userService", userService);
        ReflectionTestUtils.setField(closure, "permissionProperties", properties);

        Map<Long, Set<SubjectKey>> byUser = new LinkedHashMap<>();
        // getSelf() 走 SpringUtil.getBean(getClass())，没有 Spring 上下文就得把它钉住
        try (MockedStatic<SpringUtil> spring = mockStatic(SpringUtil.class)) {
            spring.when(() -> SpringUtil.getBean(SubjectClosureServiceImpl.class)).thenReturn(closure);
            for (AdminUserDO user : users) {
                Set<SubjectKey> keys = closure.getSubjectKeysByUserId(user.getId());
                assertFalse(keys.isEmpty(),
                        "用户 " + user.getId() + " 的主体闭包是空的 —— 那 hasPermission 会在第一步就返回 false，"
                                + "这个用户等于没被校验。先查快照里的 user_role / role.status");
                byUser.put(user.getId(), keys);
                REPORT.add(String.format("[主体闭包] 用户 %d（租户 %d）：%s",
                        user.getId(), user.getTenantId(), keys));
            }
        }

        SubjectClosureService stub = mock(SubjectClosureService.class);
        when(stub.getSubjectKeysByUserId(anyLong()))
                .thenAnswer(inv -> byUser.getOrDefault(inv.getArgument(0), Set.of()));
        ReflectionTestUtils.setField(decisionService, "subjectClosureService", stub);
    }

    private static void reportPlan() {
        REPORT.add(String.format(
                "[计划] 资源 %d 行 → 身份 %d 个（退役 %d 行、新建 %d 行）；授权 %d 条 → 存活 %d 条"
                        + "（改写 %d、判重丢弃 %d、原样 %d）",
                snapshot.resources().size(), plan.identityCount(), plan.getRetiredResourceIds().size(),
                plan.getCreatedIdentities().size(), snapshot.grants().size(),
                collapsed.grants().size(),
                plan.grantRewriteCount(), plan.getRedundantGrantIds().size(),
                plan.getUntouchedGrantIds().size()));
        REPORT.add("[计划] 定性分布 " + new TreeMap<>(plan.classificationSummary()));
        REPORT.add(String.format(
                "[范围] 权限串 %d 个（其中迁移没碰过 %d 个、列/字段 %d 个）；主体元组 %d 个；租户 %s",
                permissions.size(), untouchedPermissions.size(), fieldPermissions.size(),
                subjectTuples.size(), tenantIds));
        // 🔴 这一行是自校验的「已知差异预算」：下面几个检查里被放行的收紧，
        // 数目必须与它对得上。计划器多声明一处或少声明一处，都会在这里现形
        REPORT.add(String.format("[计划] default_effect 改写 %d 行（%s）—— 这是唯一被允许的差异来源",
                plan.getDefaultEffectUpdates().size(), plan.getDefaultEffectNarrowings()));
        // 🔴 警告不是「记下来以后看」的东西：R10 去重会丢掉 rule_id / field_mode / expire_time，
        // R1 可能挑不到 menu_id。这些直接对应下面某个检查的差异，
        // 先看到警告再看差异，才知道差异是「计划早就说过的」还是「计划没料到的」
        REPORT.add("[计划] 警告 " + plan.getWarnings().size() + " 条");
        REPORT.addAll(plan.getWarnings());
        REPORT.add("[世界] " + before);
        REPORT.add("[世界] " + after + "；身份表 " + collapsed.identityToResourceId().size() + " 项");
    }

    // ==================== 世界 ====================

    /**
     * 一整套数据 + 塌缩开关，以及为忠实复刻 SQL 语义而预建的索引
     *
     * <p>索引是必需的，不是优化：{@code hasPermission} 那一路有十几万个决策点，
     * 每个点都对 1022 行资源 / 1802 条授权做线性扫描的话，这个测试要跑几十分钟
     *
     * @param collapse 这个世界的 {@code enlev.permission.collapse-enabled}
     */
    private static final class World {

        private final String label;
        private final boolean collapse;
        private final int resourceCount;
        private final int grantCount;

        /** {@code permission = ? AND status = 0} */
        private final Map<String, List<ResourceDO>> enabledByPermission;
        /** {@code path IN (...)}，不过滤 status */
        private final Map<String, List<ResourceDO>> byPath;
        /** {@code status = 0} 的全部行 */
        private final List<ResourceDO> enabled;
        /** 租户 → 该租户的全部授权行（{@code TenantDatabaseInterceptor} 那一刀） */
        private final Map<Long, List<GrantDO>> byTenant;
        /** 租户 → (resource_id → 授权行)，供 selectListByResourceIds */
        private final Map<Long, Map<Long, List<GrantDO>>> byTenantResource;
        /** 不分租户的全部授权行。{@link #computeUntouched} 要比的是「这个世界里有哪些授权」，与租户无关 */
        private final List<GrantDO> allGrants;

        World(String label, boolean collapse, List<ResourceDO> resources, List<GrantDO> grants) {
            this.label = label;
            this.collapse = collapse;
            this.resourceCount = resources.size();
            this.grantCount = grants.size();
            this.allGrants = List.copyOf(grants);

            Map<String, List<ResourceDO>> byPermission = new HashMap<>();
            List<ResourceDO> enabledRows = new ArrayList<>(resources.size());
            for (ResourceDO r : resources) {
                if (!enabled(r)) {
                    continue;
                }
                enabledRows.add(r);
                if (r.getPermission() != null) {
                    byPermission.computeIfAbsent(r.getPermission(), k -> new ArrayList<>()).add(r);
                }
            }
            this.enabled = Collections.unmodifiableList(enabledRows);
            this.enabledByPermission = Collections.unmodifiableMap(byPermission);

            Map<String, List<ResourceDO>> paths = new HashMap<>();
            for (ResourceDO r : resources) {
                if (r.getPath() != null) {
                    paths.computeIfAbsent(r.getPath(), k -> new ArrayList<>()).add(r);
                }
            }
            this.byPath = Collections.unmodifiableMap(paths);

            Map<Long, List<GrantDO>> tenants = new HashMap<>();
            Map<Long, Map<Long, List<GrantDO>>> tenantResources = new HashMap<>();
            for (GrantDO g : grants) {
                Long tenant = g.getTenantId() == null ? 0L : g.getTenantId();
                tenants.computeIfAbsent(tenant, k -> new ArrayList<>()).add(g);
                tenantResources.computeIfAbsent(tenant, k -> new HashMap<>())
                        .computeIfAbsent(g.getResourceId(), k -> new ArrayList<>()).add(g);
            }
            this.byTenant = Collections.unmodifiableMap(tenants);
            this.byTenantResource = Collections.unmodifiableMap(tenantResources);
        }

        /** 「迁移前 1022 行资源 / 迁移后 482 行」这类对账数字，报告里要用 */
        @Override
        public String toString() {
            return String.format("%s：资源 %d 行（启用 %d）、授权 %d 条、塌缩开关 %s",
                    label, resourceCount, enabled.size(), grantCount, collapse ? "开" : "关");
        }

        boolean collapse() {
            return collapse;
        }

        List<ResourceDO> enabled() {
            return enabled;
        }

        Map<String, List<ResourceDO>> enabledByPermission() {
            return enabledByPermission;
        }

        Map<String, List<ResourceDO>> byPath() {
            return byPath;
        }

        List<GrantDO> grantsOfTenant(Long tenant) {
            return byTenant.getOrDefault(tenant, List.of());
        }

        List<GrantDO> grants() {
            return allGrants;
        }

        Map<Long, List<GrantDO>> grantsByResourceOfTenant(Long tenant) {
            return byTenantResource.getOrDefault(tenant, Map.of());
        }

        /** {@code CommonStatusEnum.ENABLE} 即 status = 0 */
        private static boolean enabled(ResourceDO r) {
            return CommonStatusEnum.ENABLE.getStatus().equals(r.getStatus());
        }
    }

    /** {@code expire_time IS NULL OR expire_time > now()}，与 GrantMapper 那两条 SQL 逐字对齐 */
    private static boolean notExpired(GrantDO g) {
        return g.getExpireTime() == null || g.getExpireTime().isAfter(LocalDateTime.now());
    }

    private static String subjectKeyOf(String type, Object ref) {
        return type + '\u0000' + ref;
    }

    private static List<Long> distinctGrantTenants(List<GrantDO> grants) {
        Set<Long> out = new LinkedHashSet<>();
        for (GrantDO g : grants) {
            out.add(g.getTenantId() == null ? 0L : g.getTenantId());
        }
        List<Long> sorted = new ArrayList<>(out);
        Collections.sort(sorted);
        return sorted;
    }

}
