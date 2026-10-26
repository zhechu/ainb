package cn.enlev.ainb.module.agent.service.workflow;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowExecutionPageReqVO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowExecutionDO;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowExecutionMapper;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentWorkflowExecutionServiceImpl#getExecutionPage} 单测（增量8 / P4 第一刀）
 *
 * <p>断言：管理端执行分页原样委派 {@code executionMapper.selectPage(reqVO)}、service 不再加工。
 * 与 user-scoped 的 {@code selectPageByUserId}（故意 eq(userId) 防越权）区分：本端点是「管理员看本租户全部」，
 * 由 {@code agent:workflow:query} 把关、租户隔离自动，故不按 userId 过滤。
 *
 * <p>🔴 Impl 依赖较重（多个 @Resource），照 {@code AgentWorkflowExecutionServiceImplEventTest} 用 spy 真 Service +
 * {@code ReflectionTestUtils} 注入 mock mapper，隔离「分页委派」与其它依赖。发布者/查询者身份用 {@code ofForTest}
 * （complete，过 requireIdentity）。
 *
 * @author 走进新时代
 */
class AgentWorkflowExecutionServicePageTest {

    private AgentWorkflowExecutionServiceImpl service;
    private AgentWorkflowExecutionMapper executionMapper;

    private final AgentExecutionContext context = AgentExecutionContext.ofForTest(1L, 1L);

    @BeforeEach
    void setUp() {
        executionMapper = mock(AgentWorkflowExecutionMapper.class);
        service = new AgentWorkflowExecutionServiceImpl();
        ReflectionTestUtils.setField(service, "executionMapper", executionMapper);
    }

    @Test
    void getExecutionPage_delegatesToMapper() {
        AgentWorkflowExecutionPageReqVO reqVO = new AgentWorkflowExecutionPageReqVO();
        reqVO.setWorkflowDefId(100L);
        reqVO.setStatus("SUCCEEDED");
        PageResult<AgentWorkflowExecutionDO> expected = new PageResult<>(List.of(), 0L);
        when(executionMapper.selectPage(reqVO)).thenReturn(expected);

        PageResult<AgentWorkflowExecutionDO> actual = service.getExecutionPage(reqVO, context);

        assertSame(expected, actual, "分页原样返回 mapper 结果");
        verify(executionMapper).selectPage(reqVO);
    }

}
