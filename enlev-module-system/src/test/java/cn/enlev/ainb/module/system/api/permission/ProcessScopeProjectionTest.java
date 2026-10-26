package cn.enlev.ainb.module.system.api.permission;

import cn.enlev.ainb.framework.test.core.ut.BaseDbUnitTest;
import cn.enlev.ainb.module.system.dal.dataobject.permission.GrantDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.GrantMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper;
import cn.enlev.ainb.module.system.enums.permission.ResourceTypeEnum;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;
import cn.enlev.ainb.module.system.service.permission.GrantDecisionService;
import cn.enlev.ainb.module.system.service.permission.ResourceServiceImpl;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import cn.enlev.ainb.module.system.framework.permission.config.PermissionConfiguration;
import cn.enlev.ainb.module.system.framework.permission.core.scan.CollapsedResourceLocator;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 三个 ID 列表 → 授权的<b>投影</b>（S7b / TODO #30）
 *
 * <h3>为什么这组用例是「摘掉回落」的安全带</h3>
 *
 * 判定处的「授权 OR 旧列表」被摘掉之后，{@code startUserIds} /
 * {@code startDeptIds} / {@code managerUserIds} 就<b>只剩这一条路</b>能生效。
 * 这里漏一条，表现就是「在流程设计器里配了发起范围，保存成功，配了不生效」，
 * 而且界面与日志都不会有任何提示。
 *
 * <h3>最容易漏、代价也最大的一条</h3>
 *
 * 「两个发起范围都为空 = 所有人都可以发起」必须投影成一条<b>显式授权</b>。
 * 流程资源的 {@code default_effect} 是 DENY（S5d §2.2），
 * 靠「没有授权」表达不出「人人可发起」—— 只会变成<b>谁都发不了</b>。
 * 开发库里 7 个流程全都属于这一类，漏了就是全部流程停摆。
 *
 * @author 走进新时代
 */
@Import({ProcessResourceApiImpl.class, ResourceServiceImpl.class,
        CollapsedResourceLocator.class, PermissionConfiguration.class})
public class ProcessScopeProjectionTest extends BaseDbUnitTest {

    private static final String PROCESS_KEY = "leave";
    private static final String PROCESS_PERMISSION = "bpm:process:" + PROCESS_KEY;

    @Resource
    private ProcessResourceApiImpl api;
    @Resource
    private ResourceMapper resourceMapper;
    @Resource
    private GrantMapper grantMapper;
    /** 只为把 ResourceServiceImpl 装进上下文（它的写方法要驱逐资源链缓存），本类不验驱逐 */
    @MockitoBean
    private GrantDecisionService grantDecisionService;

    private Long givenProcessResource() {
        ResourceDO resource = new ResourceDO();
        resource.setPermission(PROCESS_PERMISSION);
        resource.setName("请假");
        resource.setType(ResourceTypeEnum.PROCESS.getCode());
        resource.setParentId(ResourceDO.ID_ROOT);
        resource.setSort(0);
        resource.setDefaultEffect("DENY");
        resource.setStatus(0);
        resource.setPath("/root/" + PROCESS_PERMISSION + "/");
        resourceMapper.insert(resource);
        return resource.getId();
    }

    private List<GrantDO> grants() {
        return grantMapper.selectList();
    }

    @Test
    @DisplayName("🔴 两个发起范围都为空 → 给 ALL:all-users 一条 START 授权（「人人可发起」）")
    void testEmptyScopeProjectsAllUsersGrant() {
        Long resourceId = givenProcessResource();

        api.syncProcessAuthorization(PROCESS_KEY, null, null, null);

        List<GrantDO> list = grants();
        assertEquals(1, list.size());
        GrantDO grant = list.get(0);
        assertEquals("ALL", grant.getSubjectType());
        assertEquals(SubjectKey.ALL_USERS_CODE, grant.getSubjectCode());
        assertNull(grant.getSubjectRefId());
        assertEquals(resourceId, grant.getResourceId());
        assertEquals("START", grant.getAction());
        assertEquals("ALLOW", grant.getEffect());
    }

    @Test
    @DisplayName("startUserIds → USER 主体的 START 授权")
    void testStartUserIdsProjected() {
        givenProcessResource();

        api.syncProcessAuthorization(PROCESS_KEY, List.of(1L, 2L), null, null);

        List<GrantDO> list = grants();
        assertEquals(2, list.size());
        assertTrue(list.stream().allMatch(g -> "USER".equals(g.getSubjectType())
                && "START".equals(g.getAction())));
        assertEquals(List.of(1L, 2L), list.stream().map(GrantDO::getSubjectRefId).sorted().toList());
        // 限定了范围就不该再有「人人可发起」那条
        assertTrue(list.stream().noneMatch(g -> "ALL".equals(g.getSubjectType())));
    }

    @Test
    @DisplayName("⚠️ startDeptIds 投成 DEPT 主体，【不】展开成部门当前的成员")
    void testStartDeptIdsProjectedAsDeptSubject() {
        givenProcessResource();

        api.syncProcessAuthorization(PROCESS_KEY, null, List.of(100L), null);

        List<GrantDO> list = grants();
        assertEquals(1, list.size());
        assertEquals("DEPT", list.get(0).getSubjectType());
        assertEquals(100L, list.get(0).getSubjectRefId());
        // 展开成成员的话，之后调进这个部门的人不会自动获得发起权，
        // 而配置界面上部门名还好端端写着 —— 又一次「配了不生效」
    }

    @Test
    @DisplayName("⚠️ 两个范围同时配时【两个都投】—— 这是 S0 修过的那个缺陷的新落点")
    void testBothScopesProjected() {
        givenProcessResource();

        api.syncProcessAuthorization(PROCESS_KEY, List.of(1L), List.of(100L), null);

        List<GrantDO> list = grants();
        assertEquals(2, list.size());
        assertTrue(list.stream().anyMatch(g -> "USER".equals(g.getSubjectType())));
        assertTrue(list.stream().anyMatch(g -> "DEPT".equals(g.getSubjectType())),
                "旧代码里「有用户范围就直接 return」，部门范围永远走不到；"
                        + "投影这一侧绝不能重蹈覆辙");
    }

    @Test
    @DisplayName("managerUserIds → USER 主体的 MANAGE 授权，与 START 并存")
    void testManagerUserIdsProjected() {
        givenProcessResource();

        api.syncProcessAuthorization(PROCESS_KEY, List.of(1L), null, List.of(9L));

        List<GrantDO> list = grants();
        assertEquals(2, list.size());
        assertTrue(list.stream().anyMatch(g -> "START".equals(g.getAction())
                && Long.valueOf(1L).equals(g.getSubjectRefId())));
        assertTrue(list.stream().anyMatch(g -> "MANAGE".equals(g.getAction())
                && Long.valueOf(9L).equals(g.getSubjectRefId())));
    }

    @Test
    @DisplayName("幂等：反复部署收敛到同一份授权，不会越积越多")
    void testIdempotent() {
        givenProcessResource();

        api.syncProcessAuthorization(PROCESS_KEY, List.of(1L, 2L), null, null);
        api.syncProcessAuthorization(PROCESS_KEY, List.of(1L, 2L), null, null);
        api.syncProcessAuthorization(PROCESS_KEY, List.of(1L, 2L), null, null);

        assertEquals(2, grants().size());
    }

    @Test
    @DisplayName("范围改小了要跟着收：上一轮投的行会被清掉")
    void testShrinkingScopeRemovesOldGrants() {
        givenProcessResource();
        api.syncProcessAuthorization(PROCESS_KEY, List.of(1L, 2L, 3L), null, null);

        api.syncProcessAuthorization(PROCESS_KEY, List.of(1L), null, null);

        List<GrantDO> list = grants();
        assertEquals(1, list.size());
        assertEquals(1L, list.get(0).getSubjectRefId());
    }

    @Test
    @DisplayName("🔴 只覆盖自己写的行 —— 管理员在授权界面手工配的一律不动")
    void testDoesNotTouchManualGrants() {
        Long resourceId = givenProcessResource();
        GrantDO manual = new GrantDO();
        manual.setSubjectType("ROLE");
        manual.setSubjectRefId(7L);
        manual.setResourceId(resourceId);
        manual.setAction("START");
        manual.setEffect("ALLOW");
        manual.setCreator("admin");
        grantMapper.insert(manual);

        api.syncProcessAuthorization(PROCESS_KEY, List.of(1L), null, null);
        api.syncProcessAuthorization(PROCESS_KEY, List.of(2L), null, null);

        List<GrantDO> list = grants();
        assertTrue(list.stream().anyMatch(g -> "ROLE".equals(g.getSubjectType())
                        && Long.valueOf(7L).equals(g.getSubjectRefId())),
                "每次重新部署都把管理员配了半天的授权清空，是一次没有任何提示的配置丢失");
        assertEquals(2, list.size());
    }

    @Test
    @DisplayName("⚠️ 资源行不存在时一条都不写 —— 回落已摘，此时该流程谁都发不了，必须靠 ERROR 被看见")
    void testMissingResourceWritesNothing() {
        int created = api.syncProcessAuthorization("not_registered", null, null, null);

        assertEquals(0, created);
        assertTrue(grants().isEmpty());
    }

    @Test
    @DisplayName("流程 key 为空时直接返回，不炸")
    void testBlankKey() {
        assertEquals(0, api.syncProcessAuthorization("", List.of(1L), null, null));
        assertEquals(0, api.syncProcessAuthorization(null, List.of(1L), null, null));
    }

}
