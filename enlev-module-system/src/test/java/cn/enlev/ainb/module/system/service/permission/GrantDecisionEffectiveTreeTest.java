package cn.enlev.ainb.module.system.service.permission;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.grant.EffectiveActionVO;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.grant.EffectiveResourceNodeVO;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.grant.EffectiveTreeRespVO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.GrantDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.RuleDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.GrantMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.MenuMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.RuleMapper;
import cn.enlev.ainb.module.system.enums.permission.GrantActionEnum;
import cn.enlev.ainb.module.system.framework.permission.config.PermissionProperties;
import cn.enlev.ainb.module.system.framework.permission.core.decision.GrantDecisionEngine;
import cn.enlev.ainb.module.system.framework.permission.core.decision.GrantDecisionEngine.Decision;
import cn.enlev.ainb.module.system.framework.permission.core.decision.GrantDecisionEngine.DecisionReason;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GrantDecisionService#listEffectiveTree} 的单元测试
 *
 * <h3>这个方法为什么存在</h3>
 *
 * 角色授权页的「切换智能查看」要的是一张<b>权限地图</b>，不是台账的行清单：
 * 同一资源上 4 行授权要合并成 1 个节点、父菜单的 ALLOW 要让 20 个子按钮显示为生效、
 * 祖先的 DENY 要否决掉后代自己的 ALLOW、过期的行要消失但要在汇总里报数。
 * 这些全都是<b>判定</b>，而判定只能有一份 —— 所以地图与鉴权共用
 * {@link GrantDecisionEngine#explain}，{@code isAllowed} 已退化成 {@code explain(...).allowed()}。
 *
 * 下面的用例分三类：
 * <ol>
 *   <li>反漂移守卫：{@code explain} 与 {@code isAllowed} 必须逐分支一致</li>
 *   <li>合并语义：继承、否决、过期、通配、默认回落、全员主体</li>
 *   <li>树的形状：structural 标记、剪枝、重复权限串、成环防护</li>
 * </ol>
 *
 * @author 走进新时代
 */
public class GrantDecisionEffectiveTreeTest extends BaseMockitoUnitTest {

    private static final Long ROLE_ID = 2L;
    private static final SubjectKey ROLE = SubjectKey.ofRole(ROLE_ID);

    /** 除通配符外的全部具体动作，与被测代码里的 CONCRETE_ACTIONS 同源 */
    private static final List<String> CONCRETE_ACTIONS = Arrays.stream(GrantActionEnum.values())
            .filter(action -> !action.isWildcard())
            .map(GrantActionEnum::getCode)
            .toList();

    @InjectMocks
    private GrantDecisionService grantDecisionService;

    /** 真对象而非 @Mock：塌缩开关的默认值必须来自 PermissionProperties 自己，
     *  而不是 mock 返回 null 的副作用 */
    @Spy
    private PermissionProperties permissionProperties = new PermissionProperties();

    @Mock
    private SubjectClosureService subjectClosureService;
    @Mock
    private ResourceMapper resourceMapper;
    @Mock
    private GrantMapper grantMapper;
    @Mock
    private MenuMapper menuMapper;
    @Mock
    private RuleMapper ruleMapper;
    @Mock
    private RoleService roleService;
    @Mock
    private PermissionService permissionService;

    /** 目录层：default_effect=DENY，本身不是权限点 */
    private ResourceDO menu;
    /** 按钮层：default_effect=DENY，必须显式授权才有 */
    private ResourceDO actionQuery;
    private ResourceDO actionCreate;
    /** 数据集：default_effect=ALLOW，零配置也「有权限」—— 本轮最容易挨投诉的假阳性来源 */
    private ResourceDO dataset;
    private ResourceDO column;
    /**
     * 🔴 type=PROCESS 但 default_effect=ALLOW
     *
     * 实测库里就有 18 行这种东西，而 {@code ResourceTypeEnum#defaultEffect()} 说 PROCESS 该是 DENY。
     * 按 type 推的话这 18 个节点会全算错，所以单独造一个钉住「读列不读枚举」
     */
    private ResourceDO processAllowByColumn;

    @BeforeEach
    void setUp() {
        menu = resource(10L, null, "角色管理", "MENU", ResourceDO.ID_ROOT, "DENY", (short) 1, 1);
        actionQuery = resource(11L, "system:grant:query", "授权查询", "ACTION", 10L, "DENY", (short) 2, 1);
        actionCreate = resource(12L, "system:grant:create", "授权新增", "ACTION", 10L, "DENY", (short) 2, 2);
        dataset = resource(20L, "system:user", "用户数据集", "DATASET", ResourceDO.ID_ROOT, "ALLOW", (short) 1, 2);
        column = resource(21L, "system:user:col:name", "姓名", "COLUMN", 20L, "ALLOW", (short) 2, 1);
        processAllowByColumn = resource(30L, "bpm:leave", "请假流程", "PROCESS",
                ResourceDO.ID_ROOT, "ALLOW", (short) 1, 3);

        lenient().when(resourceMapper.selectList(any())).thenReturn(List.of(
                menu, actionQuery, actionCreate, dataset, column, processAllowByColumn));
        lenient().when(grantMapper.selectListBySubjects(any())).thenReturn(List.of());
        lenient().when(subjectClosureService.getAncestorKeys(any())).thenReturn(Set.of());
    }

    // ========== ① 反漂移守卫：explain 与 isAllowed 必须逐分支一致 ==========

    /**
     * 🔴 这条是整个方案的机械保证
     *
     * 权限地图要的「来源」和鉴权要的「判决」必须出自同一段代码。
     * {@code isAllowed} 已退化成 {@code explain(...).allowed()}，所以两者<b>在构造上</b>
     * 不可能不一致 —— 但「构造上不可能」这句话本身也需要一条测试钉住，
     * 否则下一个人把 {@code isAllowed} 改回独立实现时不会有任何东西报警。
     *
     * 夹具要扫遍全部优先级分支，包括两条早退（它们<b>不</b>回落 default_effect）
     * 和「脏 effect 静默拒绝」那条
     */
    @Test
    @DisplayName("🔴 explain 的判决与 isAllowed 逐分支一致（反漂移守卫）")
    void explain与isAllowed逐分支一致() {
        GrantDecisionEngine engine = new GrantDecisionEngine();
        List<ResourceDO> chain = List.of(actionQuery, menu); // 越靠前越具体
        Set<SubjectKey> subjects = Set.of(ROLE);

        // 每条夹具：{名称, 授权行, contextId, 期望理由, 期望判决}
        record Case(String name, List<GrantDO> grants, Long contextId,
                    DecisionReason reason, boolean allowed) {
        }
        List<Case> cases = List.of(
                new Case("链上无任何行 → 回落最具体资源的 default_effect=DENY",
                        List.of(), null, DecisionReason.DEFAULT_EFFECT, false),
                new Case("祖先一条 ALLOW → 继承放行",
                        List.of(grant(1L, menu.getId(), "VIEW", "ALLOW", null, ROLE)),
                        null, DecisionReason.GLOBAL_ALLOW, true),
                new Case("祖先 ALLOW + 自身 DENY → 全局 DENY 压过一切",
                        List.of(grant(1L, menu.getId(), "VIEW", "ALLOW", null, ROLE),
                                grant(2L, actionQuery.getId(), "VIEW", "DENY", null, ROLE)),
                        null, DecisionReason.GLOBAL_DENY, false),
                new Case("上下文 ALLOW 压过全局 ALLOW（但压不过全局 DENY）",
                        List.of(ctxGrant(3L, actionQuery.getId(), "VIEW", "ALLOW", 99L, ROLE)),
                        99L, DecisionReason.CONTEXT_ALLOW, true),
                new Case("上下文 DENY 压过全局 ALLOW",
                        List.of(grant(4L, menu.getId(), "VIEW", "ALLOW", null, ROLE),
                                ctxGrant(5L, actionQuery.getId(), "VIEW", "DENY", 99L, ROLE)),
                        99L, DecisionReason.CONTEXT_DENY, false),
                new Case("🔴 带上下文的授权在【其它】上下文里什么都没说 → 照常回落 default_effect",
                        List.of(ctxGrant(6L, actionQuery.getId(), "VIEW", "ALLOW", 99L, ROLE)),
                        null, DecisionReason.DEFAULT_EFFECT, false),
                new Case("脏 effect（既非 ALLOW 也非 DENY）→ 静默拒绝，不回落",
                        List.of(grant(7L, actionQuery.getId(), "VIEW", "MAYBE", null, ROLE)),
                        null, DecisionReason.NO_MATCHING_EFFECT, false),
                new Case("过期的 ALLOW → 不参与判定，回落 default_effect",
                        List.of(grant(8L, actionQuery.getId(), "VIEW", "ALLOW",
                                LocalDateTime.now().minusDays(1), ROLE)),
                        null, DecisionReason.DEFAULT_EFFECT, false),
                new Case("动作不匹配 → 该行什么都没说，回落 default_effect",
                        List.of(grant(9L, actionQuery.getId(), "DELETE", "ALLOW", null, ROLE)),
                        null, DecisionReason.DEFAULT_EFFECT, false),
                new Case("主体不匹配 → 回落 default_effect",
                        List.of(grant(10L, actionQuery.getId(), "VIEW", "ALLOW", null,
                                SubjectKey.ofRole(999L))),
                        null, DecisionReason.DEFAULT_EFFECT, false));

        for (Case c : cases) {
            Decision decision = engine.explain(subjects, chain, c.grants(), "VIEW", c.contextId());
            boolean allowed = engine.isAllowed(subjects, chain, c.grants(), "VIEW", c.contextId());
            assertEquals(c.allowed(), decision.allowed(), c.name());
            assertEquals(c.allowed(), allowed, c.name());
            assertEquals(decision.allowed(), allowed, "🔴 explain 与 isAllowed 分叉了：" + c.name());
            assertEquals(c.reason(), decision.reason(), c.name());
        }

        // 两条早退：🔴 都**不**回落 default_effect。没有身份 / 没有资源，就没有权限可言
        assertEquals(DecisionReason.NO_SUBJECT,
                engine.explain(Set.of(), chain, List.of(), "VIEW", null).reason());
        assertFalse(engine.isAllowed(Set.of(), chain, List.of(), "VIEW", null));
        assertEquals(DecisionReason.EMPTY_CHAIN,
                engine.explain(subjects, List.of(), List.of(), "VIEW", null).reason());
        assertFalse(engine.isAllowed(subjects, List.of(), List.of(), "VIEW", null));
    }

    // ========== ② 合并语义 ==========

    @Test
    @DisplayName("祖先的 ALLOW 让没有自己行的后代生效，来源标 INHERITED 并指回祖先")
    void 祖先授权被继承且标注来源() {
        when(grantMapper.selectListBySubjects(any()))
                .thenReturn(List.of(grant(1L, menu.getId(), "VIEW", "ALLOW", null, ROLE)));

        EffectiveTreeRespVO resp = tree();

        // 🔴 这一条是扁平列表永远展示不出来的信息：列表页只看得到 menu 那一行（而且它没有 permission，
        // 在列表页上连名字都对不上），完全看不出它其实放行了两个子按钮
        EffectiveResourceNodeVO query = findNode(resp, 11L);
        assertNotNull(query, "继承放行的子节点必须出现在树里");
        assertEquals(1, query.getActions().size());
        EffectiveActionVO action = query.getActions().get(0);
        assertEquals("VIEW", action.getAction());
        assertEquals(EffectiveActionVO.SOURCE_INHERITED, action.getSource());
        assertEquals(menu.getId(), action.getSourceResourceId());
        assertEquals(menu.getName(), action.getSourceResourceName(), "前端要显示「继承自「X」」");
        assertEquals(1L, action.getGrantId(), "要能跳回列表页定位那一行");
        assertNotNull(findNode(resp, 12L), "同一个祖先下的另一个后代也要继承");
        assertEquals(2, resp.getSummary().getInheritedCount());
    }

    @Test
    @DisplayName("🔴 自身的 DENY 否决祖先的 ALLOW，兄弟节点照常继承")
    void 祖先DENY否决后代ALLOW() {
        when(grantMapper.selectListBySubjects(any())).thenReturn(List.of(
                grant(1L, menu.getId(), "VIEW", "ALLOW", null, ROLE),
                grant(2L, actionQuery.getId(), "VIEW", "DENY", null, ROLE)));

        EffectiveTreeRespVO resp = tree();

        // 🔴 这条是「先全开再逐个关」的配法能不能用的判据：上级放行、本级收紧。
        // 扁平列表上这两行是并列的，完全看不出谁压过谁
        assertNull(findNode(resp, 11L), "被自己的 DENY 否决的动作不该作为权限出现");
        assertNotNull(findNode(resp, 12L), "同一个祖先下的兄弟节点不受影响");
        assertEquals(1, resp.getSummary().getDeniedCount(),
                "被否决的项要报数，否则「图比列表少」无从解释");
        assertEquals(1, resp.getSummary().getInheritedCount());
        assertEquals(2, resp.getSummary().getNodeCount(), "menu 自己有 ALLOW，不是路径节点");
        assertFalse(resp.getTree().get(0).getStructural());
    }

    @Test
    @DisplayName("过期行不计入判定，但 expiredSkippedCount 要报出来")
    void 过期行不计入但报数() {
        when(grantMapper.selectListBySubjects(any())).thenReturn(List.of(
                grant(1L, actionQuery.getId(), "VIEW", "ALLOW",
                        LocalDateTime.now().minusDays(1), ROLE),
                grant(2L, actionCreate.getId(), "VIEW", "ALLOW", null, ROLE)));

        EffectiveTreeRespVO resp = tree();

        // 智能合并的核心之一：过期就是没有。不报数的话树形节点数和列表页行数对不上，会被当成 bug
        assertNull(findNode(resp, 11L), "过期的授权不该作为权限出现");
        assertNotNull(findNode(resp, 12L), "未过期的照常生效");
        assertEquals(1, resp.getSummary().getExpiredSkippedCount());
    }

    @Test
    @DisplayName("🔴 空匹配回落的是 default_effect【列】，不是 ResourceTypeEnum 推出来的值")
    void 空匹配回落default_effect列而非枚举() {
        when(grantMapper.selectListBySubjects(any())).thenReturn(List.of());

        EffectiveTreeRespVO resp = tree(true, true, true);

        // 🔴 processAllowByColumn 的 type=PROCESS 而 default_effect=ALLOW。
        // 枚举 ResourceTypeEnum#defaultEffect() 说 PROCESS 该是 DENY ——
        // 按 type 推就会把这个节点判成「没有权限」，实测库里有 18 行会一起算错
        EffectiveResourceNodeVO process = findNode(resp, 30L);
        assertNotNull(process, "default_effect=ALLOW 的 PROCESS 资源必须算作有权限");
        assertEquals(1, process.getActions().size());
        EffectiveActionVO action = process.getActions().get(0);
        assertEquals(GrantActionEnum.WILDCARD.getCode(), action.getAction(),
                "无人配置时用单个 * 伪动作表示「全部动作（默认放行）」");
        assertEquals(EffectiveActionVO.SOURCE_DEFAULT, action.getSource());
        assertNull(action.getGrantId(), "回落默认值时压根没有授权行");
        assertEquals("ALLOW", action.getEffect());
        assertEquals(processAllowByColumn.getId(), action.getSourceResourceId());
    }

    @Test
    @DisplayName("一条 action='*' 的授权放行全部具体动作，并折叠成单个 *")
    void 通配符放行全部动作并折叠() {
        when(grantMapper.selectListBySubjects(any()))
                .thenReturn(List.of(grant(1L, actionQuery.getId(), "*", "ALLOW", null, ROLE)));

        EffectiveTreeRespVO resp = tree();

        EffectiveResourceNodeVO query = findNode(resp, 11L);
        assertNotNull(query);
        // 不折叠的话要在 220px 的列里铺 12 个 chip
        assertEquals(1, query.getActions().size(), "全部动作放行时要折叠成一个 *");
        assertEquals(GrantActionEnum.WILDCARD.getCode(), query.getActions().get(0).getAction());
        assertEquals(EffectiveActionVO.SOURCE_EXPLICIT, query.getActions().get(0).getSource());
        // 🔴 折叠后汇总里也只剩 1 项，但候选动作集是按 * 展开成 12 个逐个判的 ——
        // 下面那条「一个 DENY 就折不起来」的用例才是这个展开真实发生的证据
        assertEquals(1, resp.getSummary().getActionCount());
        assertEquals(2, resp.getSummary().getNodeCount(), "祖先目录作为路径节点一并保留");

        // 再压一条 DELETE 的 DENY：全局 DENY 优先级最高，于是少一个动作 → 折不成 *
        when(grantMapper.selectListBySubjects(any())).thenReturn(List.of(
                grant(1L, actionQuery.getId(), "*", "ALLOW", null, ROLE),
                grant(2L, actionQuery.getId(), "DELETE", "DENY", null, ROLE)));

        EffectiveTreeRespVO resp2 = tree();

        EffectiveResourceNodeVO query2 = findNode(resp2, 11L);
        assertNotNull(query2);
        assertEquals(CONCRETE_ACTIONS.size() - 1, query2.getActions().size(),
                "🔴 12 个具体动作被逐个判过，DELETE 那一个被否决");
        assertTrue(query2.getActions().stream().noneMatch(a -> "DELETE".equals(a.getAction())),
                "被 DENY 的动作不该出现");
        assertEquals(CONCRETE_ACTIONS.size() - 1, resp2.getSummary().getActionCount(),
                "折不起来时汇总要如实反映动作数");
        assertEquals(1, resp2.getSummary().getDeniedCount());
    }

    @Test
    @DisplayName("全员主体：includeAllSubject=true 时命中并标 ALL_USERS，false 时压根不参与")
    void 全员主体开关() {
        GrantDO allUsersGrant = grant(1L, actionQuery.getId(), "VIEW", "ALLOW", null,
                SubjectKey.ofAllUsers());
        when(grantMapper.selectListBySubjects(any())).thenReturn(List.of(allUsersGrant));

        // 🔴 关掉会漏掉实测那 9 条 ALL/ALLOW 授权 —— 图会少东西，而少东西是会被当成漏权限报上来的
        EffectiveTreeRespVO with = tree(false, true, false);
        EffectiveResourceNodeVO node = findNode(with, 11L);
        assertNotNull(node, "并入全员主体后应当命中");
        assertEquals(EffectiveActionVO.SOURCE_ALL_USERS, node.getActions().get(0).getSource());
        assertEquals(1, with.getSummary().getAllUsersCount());
        assertTrue(with.getSubjects().contains(SubjectKey.ofAllUsers().asString()),
                "实际算了哪些主体要回给前端显示");

        EffectiveTreeRespVO without = tree(false, false, false);
        // ⚠️ selectListBySubjects 是按传入主体查的，这里桩返回了固定值，
        // 所以要断言的是「subjects 清单里没有全员主体」—— 真实调用下它压根查不回来
        assertFalse(without.getSubjects().contains(SubjectKey.ofAllUsers().asString()));
        assertEquals(1, without.getSubjects().size(), "只剩 ROLE 自己");
    }

    @Test
    @DisplayName("行过滤规则名从 RuleMapper 批量回填，且只查一次")
    void 规则名批量回填() {
        GrantDO g = grant(1L, dataset.getId(), "VIEW", "ALLOW", null, ROLE);
        g.setRuleId(77L);
        g.setFieldMode("READONLY");
        when(grantMapper.selectListBySubjects(any())).thenReturn(List.of(g));
        when(ruleMapper.selectBatchIds(any())).thenReturn(List.of(rule(77L, "只看本部门")));

        EffectiveTreeRespVO resp = tree(false, true, true);

        EffectiveActionVO action = findNode(resp, 20L).getActions().get(0);
        assertEquals(77L, action.getRuleId());
        assertEquals("只看本部门", action.getRuleName());
        assertEquals("READONLY", action.getFieldMode());
        verify(ruleMapper, times(1)).selectBatchIds(any());
    }

    // ========== ③ 树的形状 ==========

    @Test
    @DisplayName("自身无生效权限、仅为撑起树而保留的祖先要标 structural")
    void 路径节点标structural() {
        when(grantMapper.selectListBySubjects(any()))
                .thenReturn(List.of(grant(1L, actionQuery.getId(), "VIEW", "ALLOW", null, ROLE)));

        EffectiveTreeRespVO resp = tree();

        assertEquals(1, resp.getTree().size(), "根层只有「角色管理」这个目录");
        EffectiveResourceNodeVO root = resp.getTree().get(0);
        assertEquals(menu.getId(), root.getId());
        // 不标出来的话，前端会把这个目录渲染成「有权限」，而它其实什么权限都没有
        assertTrue(root.getStructural(), "目录自身没有任何生效动作");
        assertTrue(root.getActions().isEmpty());
        assertEquals(1, root.getChildren().size());
        assertFalse(root.getChildren().get(0).getStructural(), "真正有权限的节点不是路径节点");
        assertEquals(2, resp.getSummary().getNodeCount(), "nodeCount 含 structural 路径节点");
        assertEquals(1, resp.getSummary().getActionCount(), "actionCount 只数生效动作");
    }

    @Test
    @DisplayName("includeDefaultAllow=false 时纯默认放行的节点被剪掉，但数量照样报出来")
    void 纯默认放行节点默认收起() {
        when(grantMapper.selectListBySubjects(any()))
                .thenReturn(List.of(grant(1L, actionQuery.getId(), "VIEW", "ALLOW", null, ROLE)));

        // 🔴 dataset / column / processAllowByColumn 三个都是 default_effect=ALLOW 且无人配置。
        // 实测超管角色有 205 个这种节点，全渲染出来会淹掉真正配过的那些
        EffectiveTreeRespVO hidden = tree(true, true, false);
        assertNull(findNode(hidden, 20L), "默认收起");
        assertNull(findNode(hidden, 30L), "默认收起");
        assertNotNull(findNode(hidden, 11L), "真配过的照常显示");
        assertEquals(3, hidden.getSummary().getDefaultAllowCount(),
                "🔴 收起了多少个必须报数，否则「图比列表少」会被当成 bug");

        EffectiveTreeRespVO shown = tree(true, true, true);
        assertNotNull(findNode(shown, 20L), "打开开关后保留");
        assertNotNull(findNode(shown, 30L));
        assertEquals(3, shown.getSummary().getDefaultAllowCount(),
                "这个数与开关无关，两种模式下含义一致");
        assertTrue(shown.getSummary().getNodeCount() > hidden.getSummary().getNodeCount());
    }

    @Test
    @DisplayName("includeFieldLevel=false 时 COLUMN/FIELD 不进树")
    void 字段级资源开关() {
        when(grantMapper.selectListBySubjects(any()))
                .thenReturn(List.of(grant(1L, column.getId(), "VIEW", "ALLOW", null, ROLE)));

        // ⚠️ 两处都用 includeDefaultAllow=false，好让「dataset 只因 column 才被保留」这件事看得出来：
        // 打开默认放行时 dataset 自己就是个纯默认节点，无论有没有 column 它都在树里
        EffectiveTreeRespVO with = tree(true, true, false);
        EffectiveResourceNodeVO node = findNode(with, 21L);
        assertNotNull(node, "打开时字段级资源在树里");
        assertEquals(EffectiveActionVO.SOURCE_EXPLICIT, node.getActions().get(0).getSource());
        assertNotNull(findNode(with, 20L), "它撑起的祖先 dataset 要作为路径节点保留，否则树断链");
        assertTrue(findNode(with, 20L).getStructural());

        EffectiveTreeRespVO without = tree(false, true, false);
        assertNull(findNode(without, 21L), "关掉时不进树");
        // 该列是 dataset 的唯一后代；不展示它，也就不该因它而把 dataset 留下来
        assertNull(findNode(without, 20L));
    }

    @Test
    @DisplayName("🔴 重复权限串的两行资源都要遍历，挂在大 id 那行上的授权不能凭空消失")
    void 重复权限串两行都遍历() {
        ResourceDO dupKeep = resource(13L, "infra:job:update", "任务修改(保留行)", "ACTION",
                menu.getId(), "DENY", (short) 2, 3);
        ResourceDO dupDropped = resource(14L, "infra:job:update", "任务修改(会被 dedupe 丢掉)",
                "ACTION", menu.getId(), "DENY", (short) 2, 4);
        when(resourceMapper.selectList(any())).thenReturn(List.of(
                menu, actionQuery, actionCreate, dataset, column, processAllowByColumn,
                dupKeep, dupDropped));
        // 🔴 授权挂在【id 较大】的那行上 —— ResourceAdminController#dedupeByPermission 保留的是 min(id)，
        // 用 dedupe 后的集合算会让实测那 12 条授权凭空消失
        when(grantMapper.selectListBySubjects(any()))
                .thenReturn(List.of(grant(1L, dupDropped.getId(), "UPDATE", "ALLOW", null, ROLE)));

        EffectiveTreeRespVO resp = tree();

        assertNotNull(findNode(resp, 14L), "挂在非最小 id 上的授权必须照样算出来");
        assertNull(findNode(resp, 13L), "保留行上没有任何授权，不该出现");
    }

    @Test
    @DisplayName("主体为 null 时报错，而不是返回一棵空树")
    void 主体为空报错() {
        // 🔴 空树在这张图上的读法是「这个角色什么权限都没有」，而真实原因只是参数写错了。
        // 把参数错误伪装成一个合法的业务结论，比直接报错危险得多
        assertThrows(RuntimeException.class,
                () -> grantDecisionService.listEffectiveTree(null, false, true, false));
    }

    @Test
    @DisplayName("资源表为空时返回空树，且汇总计数全部为 0")
    void 资源表为空返回空树() {
        when(resourceMapper.selectList(any())).thenReturn(List.of());

        EffectiveTreeRespVO resp = tree();

        assertTrue(resp.getTree().isEmpty());
        assertEquals(0, resp.getSummary().getNodeCount());
        assertEquals(0, resp.getSummary().getActionCount());
        assertFalse(resp.getSubjects().isEmpty(), "主体清单与有没有资源无关，照样要回给前端");
    }

    @Test
    @DisplayName("🔴 parent_id 成环时不死循环、不产生循环对象图，且环上节点不静默消失")
    void parentId成环不死循环() {
        // buildChainFromMap 有 32 层上限，管得住上溯；但【组装】是沿 parentId 挂子节点的，
        // 自底向上挂会造出循环对象图 → Jackson 序列化时 StackOverflowError
        ResourceDO a = resource(40L, "cycle:a", "环A", "ACTION", 41L, "DENY", (short) 1, 1);
        ResourceDO b = resource(41L, "cycle:b", "环B", "ACTION", 40L, "DENY", (short) 1, 2);
        when(resourceMapper.selectList(any())).thenReturn(List.of(a, b));
        when(grantMapper.selectListBySubjects(any()))
                .thenReturn(List.of(grant(1L, 40L, "VIEW", "ALLOW", null, ROLE)));

        EffectiveTreeRespVO resp = tree();

        List<EffectiveResourceNodeVO> flat = flatten(resp.getTree());
        assertEquals(2, flat.size(), "环上两个节点都要在树里，不能静默消失");
        // 🔴 41 的祖先链（受 32 层上限约束）里也含 40，所以它继承到了那一条 VIEW ——
        // 两个节点都有生效动作，nodeCount 因此是 2 而不是 1
        assertEquals(flat.size(), resp.getSummary().getNodeCount());
        assertEquals(2, resp.getSummary().getActionCount());
        assertEquals(1, resp.getSummary().getInheritedCount());
        // 每个节点只能被挂一次，否则对象图有环
        assertEquals(flat.size(), flat.stream().map(EffectiveResourceNodeVO::getId).distinct().count());
    }

    @Test
    @DisplayName("超管角色要在汇总里标出来（真实鉴权旁路台账）")
    void 超管角色标记() {
        when(roleService.hasAnySuperAdmin(any())).thenReturn(true);

        EffectiveTreeRespVO resp = tree();

        // 🔴 真实的用户鉴权对超管是旁路授权台账的，而这张图画的恰恰是台账。
        // 不标出来的话，超管角色打开时图上看到的和实际能做的不是一回事，且没人知道为什么
        assertTrue(resp.getSummary().getSuperAdminRole());
    }

    // -------- 调用与断言辅助 --------

    private EffectiveTreeRespVO tree() {
        return tree(false, true, false);
    }

    private EffectiveTreeRespVO tree(boolean includeFieldLevel, boolean includeAllSubject,
                                     boolean includeDefaultAllow) {
        return grantDecisionService.listEffectiveTree(
                ROLE, includeFieldLevel, includeAllSubject, includeDefaultAllow);
    }

    private static EffectiveResourceNodeVO findNode(EffectiveTreeRespVO resp, Long id) {
        return findNode(resp.getTree(), id);
    }

    private static EffectiveResourceNodeVO findNode(List<EffectiveResourceNodeVO> nodes, Long id) {
        for (EffectiveResourceNodeVO node : nodes) {
            if (id.equals(node.getId())) {
                return node;
            }
            EffectiveResourceNodeVO hit = findNode(node.getChildren(), id);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static List<EffectiveResourceNodeVO> flatten(List<EffectiveResourceNodeVO> nodes) {
        List<EffectiveResourceNodeVO> result = new ArrayList<>();
        for (EffectiveResourceNodeVO node : nodes) {
            result.add(node);
            result.addAll(flatten(node.getChildren()));
        }
        return result;
    }

    // -------- 造数 --------

    private static ResourceDO resource(Long id, String permission, String name, String type,
                                       Long parentId, String defaultEffect, Short depth, Integer sort) {
        ResourceDO r = new ResourceDO();
        r.setId(id);
        r.setPermission(permission);
        r.setName(name);
        r.setType(type);
        r.setParentId(parentId);
        r.setDefaultEffect(defaultEffect);
        r.setDepth(depth);
        r.setSort(sort);
        r.setStatus(0);
        return r;
    }

    private static GrantDO grant(Long id, Long resourceId, String action, String effect,
                                 LocalDateTime expireTime, SubjectKey subject) {
        GrantDO g = new GrantDO();
        g.setId(id);
        g.setResourceId(resourceId);
        g.setAction(action);
        g.setEffect(effect);
        g.setExpireTime(expireTime);
        g.setSubjectType(subject.type().name());
        if (subject.type().isRefBased()) {
            g.setSubjectRefId(subject.refId());
        } else {
            g.setSubjectCode(subject.code());
        }
        return g;
    }

    /** 带上下文的授权行：{@code context_resource_id} 非 NULL，只在匹配的上下文里说话 */
    private static GrantDO ctxGrant(Long id, Long resourceId, String action, String effect,
                                    Long contextResourceId, SubjectKey subject) {
        GrantDO g = grant(id, resourceId, action, effect, null, subject);
        g.setContextResourceId(contextResourceId);
        return g;
    }

    private static RuleDO rule(Long id, String name) {
        RuleDO r = new RuleDO();
        r.setId(id);
        r.setName(name);
        return r;
    }

}
