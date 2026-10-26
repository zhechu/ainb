package cn.enlev.ainb.module.bpm.service.definition;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.bpm.dal.dataobject.definition.BpmDelegationDO;
import cn.enlev.ainb.module.bpm.dal.mysql.definition.BpmDelegationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 流程委托（晚绑定）
 *
 * <h3>⚠️ v10 点名要求的用例：委托前已存在的待办要能转</h3>
 *
 * 这条是区分「晚绑定」与「早绑定」的试金石。早绑定在建委托时把 assignee
 * 改成受托人，于是<b>建委托之前</b>就已经派下来的任务要靠额外扫一遍去改 ——
 * 漏了不报错，表现是「他休假了，可单子还堆在他那儿没人能办」。
 *
 * 晚绑定压根不改任务，查待办时按「自己 + 委托人」一起查，
 * 所以委托何时建立与任务何时派发<b>完全无关</b>。
 *
 * 本类验的是委托服务这一侧：它必须如实告诉调用方「谁委托给了我」，
 * 而与任何任务的创建时间无关 —— 服务里根本没有「任务」这个概念，
 * 这本身就是晚绑定成立的结构性保证。
 *
 * @author 走进新时代
 */
@MockitoSettings(strictness = Strictness.LENIENT)
public class BpmDelegationServiceImplTest extends BaseMockitoUnitTest {

    private static final Long DELEGATOR = 1000L; // 委托人（休假的人）
    private static final Long DELEGATE = 2000L;  // 受托人（代办的人）
    private static final Long OTHER = 3000L;

    @Mock
    private BpmDelegationMapper delegationMapper;

    private BpmDelegationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BpmDelegationServiceImpl();
        ReflectionTestUtils.setField(service, "delegationMapper", delegationMapper);
    }

    private BpmDelegationDO delegation(String processKeys) {
        return BpmDelegationDO.builder()
                .id(1L).delegatorUserId(DELEGATOR).delegateUserId(DELEGATE)
                .startTime(LocalDateTime.now().minusDays(1))
                .endTime(LocalDateTime.now().plusDays(1))
                .processDefinitionKeys(processKeys)
                .status(0)
                .build();
    }

    @Test
    @DisplayName("⚠️ 委托前已存在的待办要能转 —— 服务里没有「任务」概念，与任务创建时间无关")
    void testPreExistingTasksAreCovered() {
        // 委托是「今天」建的，而任务可能是上周派下来的。
        // 晚绑定下这两件事毫无关系：查待办时按「自己 + 委托人」一起查即可
        when(delegationMapper.selectActiveListByDelegateUserId(eq(DELEGATE), any()))
                .thenReturn(List.of(delegation(null)));

        Set<Long> delegators = service.getDelegatorUserIds(DELEGATE, null);

        assertEquals(Set.of(DELEGATOR), delegators,
                "委托人没被返回的话，他名下的待办（不论何时派发）都到不了受托人手上");
    }

    @Test
    @DisplayName("⚠️ 有效期判定下推到 SQL —— 查待办是热路径")
    void testActiveFilterIsPushedDownToSql() {
        when(delegationMapper.selectActiveListByDelegateUserId(any(), any())).thenReturn(List.of());

        service.getDelegatorUserIds(DELEGATE, null);

        // 把失效的行捞回内存再扔掉是白花的 IO，而这条路每次查待办都要走
        verify(delegationMapper).selectActiveListByDelegateUserId(eq(DELEGATE), any(LocalDateTime.class));
        verify(delegationMapper, never()).selectListByDelegatorUserId(any());
    }

    @Test
    @DisplayName("限定了流程的委托：key 匹配才算数")
    void testProcessScopeMatches() {
        when(delegationMapper.selectActiveListByDelegateUserId(eq(DELEGATE), any()))
                .thenReturn(List.of(delegation("leave,oa_leave")));

        assertTrue(service.isDelegatedBy(DELEGATE, DELEGATOR, "leave"));
        assertTrue(service.isDelegatedBy(DELEGATE, DELEGATOR, "oa_leave"));
        assertFalse(service.isDelegatedBy(DELEGATE, DELEGATOR, "expense"),
                "限定之外的流程不能办 —— 否则「只帮我看请假」变成了「什么都能替我批」");
    }

    @Test
    @DisplayName("⚠️ 流程 key 为空时放行 —— 待办列表一次查多个流程，没有单一 key")
    void testBlankProcessKeyPasses() {
        when(delegationMapper.selectActiveListByDelegateUserId(eq(DELEGATE), any()))
                .thenReturn(List.of(delegation("leave")));

        assertEquals(Set.of(DELEGATOR), service.getDelegatorUserIds(DELEGATE, null),
                "这里判否的话，限定了流程的委托在待办列表里一条都不生效，"
                        + "而用户完全看不出为什么。精确判定交给审批鉴权那一步");
    }

    @Test
    @DisplayName("不限流程的委托覆盖所有流程")
    void testUnscopedCoversAll() {
        when(delegationMapper.selectActiveListByDelegateUserId(eq(DELEGATE), any()))
                .thenReturn(List.of(delegation(null)));

        assertTrue(service.isDelegatedBy(DELEGATE, DELEGATOR, "anything"));
    }

    @Test
    @DisplayName("没有委托关系的人拿不到任何委托人")
    void testNoDelegation() {
        when(delegationMapper.selectActiveListByDelegateUserId(eq(OTHER), any())).thenReturn(List.of());

        assertTrue(service.getDelegatorUserIds(OTHER, null).isEmpty());
        assertFalse(service.isDelegatedBy(OTHER, DELEGATOR, "leave"));
    }

    @Test
    @DisplayName("受托人为空时返回空，不查库")
    void testNullUser() {
        assertTrue(service.getDelegatorUserIds(null, null).isEmpty());
        assertFalse(service.isDelegatedBy(null, DELEGATOR, "leave"));
        verify(delegationMapper, never()).selectActiveListByDelegateUserId(any(), any());
    }

    @Test
    @DisplayName("⚠️ 取消委托是改状态，不是删任务归属 —— 任务本来就没被改过")
    void testCancelOnlyChangesStatus() {
        service.cancelDelegation(1L);

        // 晚绑定的好处正在于此：没有任何「扫一遍改回来」的动作
        org.mockito.ArgumentCaptor<BpmDelegationDO> captor =
                org.mockito.ArgumentCaptor.forClass(BpmDelegationDO.class);
        verify(delegationMapper).updateById(captor.capture());
        assertEquals(1, captor.getValue().getStatus());
        assertEquals(1L, captor.getValue().getId());
    }

}
