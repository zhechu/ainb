package cn.enlev.ainb.module.system.service.permission;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.system.dal.dataobject.permission.GrantDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.GrantMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper;
import cn.enlev.ainb.module.system.enums.permission.FieldModeEnum;
import cn.enlev.ainb.module.system.enums.permission.GrantActionEnum;
import cn.enlev.ainb.module.system.framework.permission.config.PermissionProperties;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectTypeEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link GrantDecisionService} 的单元测试
 *
 * 重点覆盖<b>物化路径上溯祖先</b>这一段。它原先有一个错位一级的缺陷：
 * 判据写成了 {@code 截断后的path + candidate.permission + "/"}，
 * 而物化路径的最后一段本来就是节点自己的 permission，
 * 于是匹配到的是「本节点的兄弟」而不是「本节点的父亲」。
 *
 * 后果是最顶层祖先进不了链（授权不被继承）、兄弟资源混进链（授权互相误伤）。
 * 这两条正是下面两个用例要挡住的。
 *
 * @author 走进新时代
 */
public class GrantDecisionServiceTest extends BaseMockitoUnitTest {

    private static final Long USER_ID = 1L;
    private static final Long ROLE_ID = 2L;

    /** /root/infra/ —— 顶层节点 */
    private static final String PATH_TOP = "/root/infra/";
    /** /root/infra/ds/ —— 数据集节点 */
    private static final String PATH_DATASET = "/root/infra/ds/";
    /** /root/infra/ds/f/ —— 字段节点本身 */
    private static final String PATH_FIELD = "/root/infra/ds/f/";
    /** /root/infra/ds/sib/ —— 同一数据集下的另一个字段，本节点的兄弟 */
    private static final String PATH_SIBLING = "/root/infra/ds/sib/";

    private static final String PERM_FIELD = "ds:fld:mobile";

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

    private ResourceDO field;
    private ResourceDO dataset;
    private ResourceDO top;
    private ResourceDO sibling;

    @BeforeEach
    void setUp() {
        field = resource(100L, PERM_FIELD, PATH_FIELD, "ALLOW");
        dataset = resource(200L, "ds", PATH_DATASET, "ALLOW");
        top = resource(300L, "infra", PATH_TOP, "ALLOW");
        sibling = resource(999L, "ds:fld:idcard", PATH_SIBLING, "ALLOW");

        // lenient：下面这几个桩各自只有部分用例用得上，严格模式会把没用到的判「多余」——
        //  · getSubjectKeysByUserId：hasExplicitDeny 不走主体闭包（它按调用方传进来的主体查）
        //  · selectListByPermissions：塌缩那几条用例查的是身份行，走的是单数的 selectListByPermission
        //  · selectListByPaths/selectListByPath：资源行不存在的那条用例压根走不到上溯
        lenient().when(subjectClosureService.getSubjectKeysByUserId(eq(USER_ID)))
                .thenReturn(Set.of(SubjectKey.ofRole(ROLE_ID)));
        lenient().when(resourceMapper.selectListByPermissions(any())).thenReturn(List.of(field));
        // S9 优化：buildAncestors 现在用批量查 selectListByPaths 替代逐层 selectListByPath
        // 入参是祖先路径的集合，顺序不定；返回对应的资源行
        lenient().when(resourceMapper.selectListByPaths(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            java.util.Collection<String> paths = (java.util.Collection<String>) inv.getArgument(0);
            java.util.List<ResourceDO> result = new java.util.ArrayList<>();
            for (String p : paths) {
                if (PATH_DATASET.equals(p)) {
                    result.add(dataset);
                } else if (PATH_TOP.equals(p)) {
                    result.add(top);
                }
            }
            return result;
        });
        // 保留单路径桩，避免其他调用链还在用它
        lenient().when(resourceMapper.selectListByPath(eq(PATH_DATASET))).thenReturn(List.of(dataset));
        lenient().when(resourceMapper.selectListByPath(eq(PATH_TOP))).thenReturn(List.of(top));
        // 陷阱：正确实现不该调前缀查询（调了就会把兄弟节点混进来）
        lenient().when(resourceMapper.selectDescendantsByPathPrefix(any()))
                .thenReturn(List.of(field, sibling, dataset, top));
    }

    private ResourceDO resource(Long id, String permission, String path, String defaultEffect) {
        ResourceDO resource = new ResourceDO();
        resource.setId(id);
        resource.setPermission(permission);
        resource.setPath(path);
        resource.setDefaultEffect(defaultEffect);
        return resource;
    }

    private GrantDO grant(Long resourceId, String effect) {
        return grant(resourceId, "VIEW", effect);
    }

    private GrantDO grant(Long resourceId, String action, String effect) {
        GrantDO grant = new GrantDO();
        grant.setSubjectType("ROLE");
        grant.setSubjectRefId(ROLE_ID);
        grant.setResourceId(resourceId);
        grant.setAction(action);
        grant.setEffect(effect);
        return grant;
    }

    private FieldModeEnum resolveOne() {
        Map<String, FieldModeEnum> modes =
                grantDecisionService.resolveFieldModes(USER_ID, List.of(PERM_FIELD), null);
        return modes.get(PERM_FIELD);
    }

    /**
     * 布出塌缩<b>之后</b>的世界：成员行已退役，只有身份行还查得到
     *
     * <p>🔴 塌缩类用例必须显式桩成员行为空，因为 R11 在判定侧的镜像
     * 拿「原始串还查不查得到」当旗子（见 {@code GrantDecisionService#lookupOf}）。
     * 三种写法的后果完全不同，而其中两种是静默的：
     * <ul>
     *   <li>压根不桩 ⟹ Mockito 严格模式报 {@code PotentialStubbingProblem}（实测过，
     *       两条用例就是这么红的）。吵，但至少不会骗人</li>
     *   <li>用 {@code any()} 兜底返回身份行 ⟹ 原始串也「查得到」，
     *       R11 判成整族被拦下、走原串判定，于是用例验的是迁移<b>之前</b>的世界，
     *       断言却写着迁移之后 —— <b>全绿，但一个字都没验到</b></li>
     *   <li>显式桩成空 ⟹ 唯一正确的写法，也就是本方法</li>
     * </ul>
     *
     * <p>不用 {@code any()} 兜空、而是逐个列出退役的串，是刻意的：
     * 将来谁在这里加一条新断言，严格模式会逼他说清楚「那个串迁移后还在不在库里」，
     * 而不是让兜底桩替他默认一个答案
     *
     * <p>⚠️ 这些成员行桩<b>只有 R11 那个守卫会去查</b>，所以守卫被删掉时它们会以
     * {@code UnnecessaryStubbingException} 报错。那是有意的第二道网，
     * 别为了让报错好看而加 {@code lenient()} —— 理由见
     * {@link #testHasPermission_塌缩打开但族被R11拦下时不翻译} 的变异探针那一段
     */
    private void stubCollapsedWorld(ResourceDO identity, String... retiredMembers) {
        when(resourceMapper.selectListByPermission(eq(identity.getPermission())))
                .thenReturn(List.of(identity));
        for (String member : retiredMembers) {
            when(resourceMapper.selectListByPermission(eq(member))).thenReturn(List.of());
        }
    }

    @Test
    @DisplayName("顶层祖先上的 DENY 要被继承下来")
    void testTopLevelAncestorDenyIsInherited() {
        // 授权挂在最顶层的 /root/infra/ 上
        when(grantMapper.selectListByResourceIds(any())).thenReturn(List.of(grant(300L, "DENY")));

        assertEquals(FieldModeEnum.HIDDEN, resolveOne(),
                "最顶层祖先必须进祖先链。原先的上溯错位一级，顶层节点永远进不了链，"
                        + "表现是「给整个模块配了拒绝，字段上却依然看得见」");
    }

    @Test
    @DisplayName("数据集节点上的 DENY 要被继承下来")
    void testDatasetAncestorDenyIsInherited() {
        when(grantMapper.selectListByResourceIds(any())).thenReturn(List.of(grant(200L, "DENY")));

        assertEquals(FieldModeEnum.HIDDEN, resolveOne(),
                "「给整张表单配拒绝」要对表单里的字段生效");
    }

    @Test
    @DisplayName("兄弟资源上的 DENY 不能误伤本字段")
    void testSiblingGrantDoesNotLeak() {
        // 999 是同一个数据集下的另一个字段，与本字段是兄弟关系。
        // 原先的上溯按路径前缀捞一批再挑，会把兄弟节点匹配进祖先链，于是兄弟的 DENY 误伤到这里。
        // setUp 里给 selectDescendantsByPathPrefix 布了桩（会返回 sibling），
        // 所以一旦上溯退回前缀查询，这条断言立刻失败
        when(grantMapper.selectListByResourceIds(any())).thenReturn(List.of(grant(999L, "DENY")));

        assertEquals(FieldModeEnum.EDITABLE, resolveOne(),
                "兄弟字段的授权绝不能进本字段的祖先链 —— "
                        + "那会让「隐掉手机号」顺带把身份证也隐掉");
    }

    @Test
    @DisplayName("资源行不存在时放行，不是拒绝")
    void testMissingResourceFallsBackToEditable() {
        when(resourceMapper.selectListByPermissions(any())).thenReturn(List.of());

        assertEquals(FieldModeEnum.EDITABLE, resolveOne(),
                "v9 §5.2 的按需创建：绝大多数字段本来就不落资源行。"
                        + "写反的表现是「所有表单的所有字段全部消失」");
    }

    @Test
    @DisplayName("没有任何授权时回落到资源自身的 default_effect")
    void testNoGrantFallsBackToDefaultEffect() {
        when(grantMapper.selectListByResourceIds(any())).thenReturn(List.of());

        assertEquals(FieldModeEnum.EDITABLE, resolveOne(),
                "列/字段资源的 default_effect 登记为 ALLOW，没配授权时应当可见");
    }

    @Test
    @DisplayName("field_mode 走第二步三层合并，与「能不能看见」是两套语义")
    void testFieldModeResolvedAfterVisibility() {
        GrantDO readonly = grant(100L, "ALLOW");
        readonly.setFieldMode(FieldModeEnum.READONLY.getCode());
        when(grantMapper.selectListByResourceIds(any())).thenReturn(List.of(readonly));

        assertEquals(FieldModeEnum.READONLY, resolveOne(),
                "effect=ALLOW 决定「看得见」，field_mode 决定「改不改得了」，两步都要走");
    }

    // ==================== 塌缩开关打开后的判定路径（规则 R6） ====================

    /**
     * 🔴 塌缩后判定必须把「资源身份:动词」翻译成「资源身份 + 动作」
     *
     * <p>迁移之后 {@code system:user:create} 那一行已经退役，授权被改写成了
     * 「挂在 {@code system:user} 上的 CREATE」（规则 R9）。判定侧不翻译的话，
     * 查的是退役行的权限串 ⟹ 查不到资源 ⟹ 严格模式下判成无权：
     * <b>接口全 403、按钮全消失，而全程 200、无异常</b>
     *
     * <p>🔴 别把这一条读成「{@code system:user} 会被塌缩」—— 真实的计划里它恰恰是
     * R11 整族拦下的那 20 个之一（身份行下面还挂着 26 行存活后代）。
     * 本用例布的是一个<b>构造出来的</b>「已塌缩」世界，为的只是钉住翻译机制本身；
     * 这一族真实的样子（成员行仍在库里 ⟹ 不翻译）由
     * {@link #testHasPermission_塌缩打开但族被R11拦下时不翻译} 钉住。
     * 两条用例合起来才是 {@code lookupOf} 的完整契约
     */
    @Test
    @DisplayName("塌缩打开：system:user:create 判成「身份 system:user + 动作 CREATE」")
    void testHasPermission_塌缩后按身份加动作判定() {
        permissionProperties.setCollapseEnabled(true);
        ResourceDO identity = resource(400L, "system:user", "/root/system/system:user/", "DENY");
        stubCollapsedWorld(identity, "system:user:create", "system:user:query");
        when(grantMapper.selectListByResourceIds(any()))
                .thenReturn(List.of(grant(400L, GrantActionEnum.CREATE.getCode(), "ALLOW")));

        assertTrue(grantDecisionService.hasPermission(USER_ID, "system:user:create", null),
                "串里的动词必须翻译到动作维度去判");
        // 反向：同一个身份上只授了 CREATE，就不能放行 query —— 动作之间不许串味
        assertFalse(grantDecisionService.hasPermission(USER_ID, "system:user:query", null),
                "塌缩把兄弟资源并成了一个身份，动作维度是它们之间唯一的区分，"
                        + "串味就等于「给了新增权限顺带给了查询/删除」");
    }

    /**
     * 🔴 R11 在判定侧的镜像：塌缩开关<b>开着</b>，但这一族的成员行还在库里 ⟹ 不许翻译
     *
     * <p>开关是全局的，而塌缩是<b>逐族</b>的：计划器把有存活后代的 20 个族整族拦下，
     * 落库之后它们的成员行照旧存在、授权也没被上提。判定侧只看开关的话，
     * 就会拿着身份串去查一个<b>从没接收过授权</b>的行 ⟹ 那 20 个族整片失权，
     * 而且不报错（全程 200、无异常，只是按钮没了、接口 403）。
     * 所以 {@code lookupOf} 用「原始串还查不查得到」当旗子，与计划器同源。
     *
     * <p>这一条与 {@link #testHasPermission_塌缩后按身份加动作判定} 是同一个开关下的两个方向，
     * 缺一个另一半就是空话：只钉「翻译」会让人以为开关一开就全塌；
     * 只钉「不翻译」会让人以为塌缩压根没生效
     *
     * <h3>变异探针（实测过，跑完逐字节恢复）</h3>
     *
     * 删掉 {@code lookupOf} 里那个 {@code chainsOf(permission).isEmpty()} 守卫，本类 10 个用例
     * 挂 3 个，但<b>挂法不一样，这个区别值得记下来</b>：
     * <ul>
     *   <li>本用例挂在<b>行为断言</b>上（{@code expected: <false> but was: <true>}）——
     *       这是唯一一条能说明「多塌了一族」的信号</li>
     *   <li>另外两条塌缩用例挂在 {@code UnnecessaryStubbingException} 上：
     *       它们经 {@link #stubCollapsedWorld} 桩的成员行<b>只有守卫会去查</b>，
     *       守卫没了桩就成了多余。红是红了，但报错指向「桩写错了」，
     *       排查的人会去改桩 —— 而真正被删掉的是生产代码里的一行</li>
     * </ul>
     *
     * 🔴 所以别为了让那两条的报错好看而给成员行桩加 {@code lenient()}：
     * 加了之后守卫被删就只剩本用例一条网。而在本用例存在之前，
     * 唯一的行为级信号在 {@code CollapseMigrationSelfCheckTest} 里 ——
     * 那个类<b>没有快照就整类跳过</b>，CI 上等于没有网
     */
    @Test
    @DisplayName("塌缩打开：族被 R11 拦下（成员行仍在库里）时不翻译，按原串判定")
    void testHasPermission_塌缩打开但族被R11拦下时不翻译() {
        permissionProperties.setCollapseEnabled(true);
        ResourceDO identity = resource(400L, "system:user", "/root/system/system:user/", "DENY");
        ResourceDO member = resource(401L, "system:user:create",
                "/root/system/system:user/system:user:create/", "DENY");
        member.setParentId(400L);
        when(resourceMapper.selectListByPermission(eq("system:user")))
                .thenReturn(List.of(identity));
        // 与上一条用例的唯一区别就在这：成员行没被退役，还查得到
        when(resourceMapper.selectListByPermission(eq("system:user:create")))
                .thenReturn(List.of(member));
        // 授权留在身份行上、动作是 CREATE。翻译了就会命中它 ⟹ true；没翻译就查成员行自己
        when(grantMapper.selectListByResourceIds(any()))
                .thenReturn(List.of(grant(400L, GrantActionEnum.CREATE.getCode(), "ALLOW")));

        assertFalse(grantDecisionService.hasPermission(USER_ID, "system:user:create", null),
                "🔴 返回 true 说明判定侧多塌了一族：它去查了身份行上的 CREATE 授权，"
                        + "而这一族的授权压根没被上提过。正确行为是按原串查成员行自己 —— "
                        + "无授权 ⟹ 回落成员行的 default_effect=DENY");
        // 反向防空转：身份行上那条 CREATE 授权本身仍然是可用的，
        // 所以上面那个 false 是「没翻译」造成的，不是「这个桩什么都不放行」造成的
        assertTrue(grantDecisionService.hasPermission(USER_ID, "system:user", null,
                        GrantActionEnum.CREATE.getCode()),
                "身份行自己的授权要照常生效，否则上一条断言就成了恒假的空转");
    }

    /**
     * 塌缩打开时，不塌缩的档必须<b>逐字节不变</b>
     *
     * <p>唯一传非 VIEW 动作的调用方是 {@code PermissionApiImpl#hasResourceAction}
     * （BPM 流程族，串形如 {@code bpm:process:leave}）：末段是业务专有动词、属档 C，
     * {@code isCollapsed()} 恒为 false，所以既不改查哪个资源、也不覆盖调用方传的动作。
     * 这条用例把「不覆盖」两个方向都钉住
     */
    @Test
    @DisplayName("塌缩打开：档 C 仍按原串判定，且入参动作不被覆盖")
    void testHasPermission_塌缩后档C行为不变() {
        permissionProperties.setCollapseEnabled(true);
        ResourceDO leave = resource(500L, "bpm:process:leave",
                "/root/bpm/bpm:process/bpm:process:leave/", "DENY");
        when(resourceMapper.selectListByPermission(eq("bpm:process:leave")))
                .thenReturn(List.of(leave));
        when(grantMapper.selectListByResourceIds(any()))
                .thenReturn(List.of(grant(500L, GrantActionEnum.START.getCode(), "ALLOW")));

        assertTrue(grantDecisionService.hasPermission(USER_ID, "bpm:process:leave", null,
                        GrantActionEnum.START.getCode()),
                "档 C 的动作来自入参，不是从串里解析出来的");
        assertFalse(grantDecisionService.hasPermission(USER_ID, "bpm:process:leave", null),
                "默认重载传 VIEW，而库里只有 START ⟹ 不该放行。"
                        + "写反的表现是「能发起流程」与「能看到流程」变成一回事");
    }

    /**
     * {@code hasExplicitDeny} 同样要过翻译层
     *
     * <p>它的调用方 {@code BpmSubProcessCallChecker} 拿的是<b>原始权限串</b>，
     * 而迁移后 DENY 行挂在身份行上、动作已被 R7 从 {@code *} 收窄成具体动作。
     * 不翻译的话两种错都会出现：查不到身份行 ⟹ 漏掉本该生效的禁止令；
     * 动作对不上 ⟹ 同样漏掉
     *
     * <p>⚠️ 与上面那条一样，这里的 {@code system:user} 是<b>构造出来的</b>已塌缩世界
     * （真实计划里它被 R11 整族拦下，四条 {@code *} DENY 也不会被收窄）。
     * 本用例钉的是「翻译层对 {@code hasExplicitDeny} 同样生效」这一件事，
     * 不表示这一族真的会塌 —— 拦下的那一个方向见
     * {@link #testHasPermission_塌缩打开但族被R11拦下时不翻译}
     */
    @Test
    @DisplayName("塌缩打开：显式 DENY 按翻译后的动作命中，且不越界到别的动作")
    void testHasExplicitDeny_塌缩后按动作命中() {
        permissionProperties.setCollapseEnabled(true);
        ResourceDO identity = resource(400L, "system:user", "/root/system/system:user/", "DENY");
        stubCollapsedWorld(identity, "system:user:delete", "system:user:query");
        GrantDO deny = grant(400L, GrantActionEnum.DELETE.getCode(), "DENY");
        when(grantMapper.selectListByResourceIds(any())).thenReturn(List.of(deny));

        assertTrue(grantDecisionService.hasExplicitDeny(SubjectTypeEnum.ROLE.name(), null,
                        "system:user:delete", GrantActionEnum.VIEW.getCode()),
                "串里的 delete 翻译成动作 DELETE，与库里的 DENY 对上");
        assertFalse(grantDecisionService.hasExplicitDeny(SubjectTypeEnum.ROLE.name(), null,
                        "system:user:query", GrantActionEnum.VIEW.getCode()),
                "🔴 R7 收窄的意义就在这：DENY 只禁掉删除，不能顺带禁掉查询");
    }

}
