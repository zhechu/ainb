package cn.enlev.ainb.module.agent.service.workflow;

import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentNodeExecutionDO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowExecutionDO;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentNodeExecutionMapper;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowExecutionMapper;
import cn.enlev.ainb.module.agent.enums.WorkflowExecutionStatusEnum;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentWorkflowCompensator} 单测（待办 #5 / Saga 逆序补偿，纯 mock，0 token）
 *
 * <p>钉住 Saga 的命门：① <b>逆序</b>逐个调 compensate_ref 工具；② 只撤 SUCCESS 且声明了 compensate_ref 的节点
 * （这条由 mapper 查询保证，本测 stub 其返回）；③ before_image 对象按 {@code {{...}}} 模板对着 state 解析成工具入参；
 * ④ idempotency_key 解析后经保留参数透传；⑤ <b>尽力而为</b>——单个补偿失败不中断，继续撤后面的，终态 COMPENSATION_FAILED；
 * ⑥ 全成功终态 COMPENSATED；⑦ 无可补偿节点直接 COMPENSATED、不碰工具；⑧ 状态机 FAILED→COMPENSATING→终态 落库。
 *
 * @author 走进新时代
 */
class AgentWorkflowCompensatorTest {

    private AgentWorkflowCompensator compensator;
    private AgentToolExecutor toolExecutor;
    private AgentNodeExecutionMapper nodeExecutionMapper;
    private AgentWorkflowExecutionMapper executionMapper;

    private final AgentExecutionContext context = AgentExecutionContext.ofForTest(9L, 1L);

    @BeforeEach
    void setUp() {
        toolExecutor = mock(AgentToolExecutor.class);
        nodeExecutionMapper = mock(AgentNodeExecutionMapper.class);
        executionMapper = mock(AgentWorkflowExecutionMapper.class);
        compensator = new AgentWorkflowCompensator();
        ReflectionTestUtils.setField(compensator, "toolExecutor", toolExecutor);
        ReflectionTestUtils.setField(compensator, "nodeExecutionMapper", nodeExecutionMapper);
        ReflectionTestUtils.setField(compensator, "executionMapper", executionMapper);
    }

    private static AgentWorkflowExecutionDO exec(Long id, String recovery, String variableSnapshot) {
        AgentWorkflowExecutionDO e = new AgentWorkflowExecutionDO();
        e.setId(id);
        e.setUserId(9L);
        e.setTenantId(1L);
        e.setRecovery(recovery);
        e.setStatus(WorkflowExecutionStatusEnum.FAILED.getStatus());
        e.setVariableSnapshot(variableSnapshot);
        return e;
    }

    private static AgentNodeExecutionDO node(Long id, String nodeId, String compensateRef,
                                             String beforeImage, String idempotencyKey, String outputSnapshot) {
        AgentNodeExecutionDO n = new AgentNodeExecutionDO();
        n.setId(id);
        n.setNodeId(nodeId);
        n.setStatus("SUCCESS");
        n.setCompensateRef(compensateRef);
        n.setBeforeImage(beforeImage);
        n.setIdempotencyKey(idempotencyKey);
        n.setOutputSnapshot(outputSnapshot);
        return n;
    }

    @Test
    @DisplayName("无可补偿节点 → 直接 COMPENSATED，不碰工具")
    void compensate_noTargets_compensated() {
        AgentWorkflowExecutionDO exec = exec(1L, "COMPENSATE", "{}");
        when(executionMapper.selectById(1L)).thenReturn(exec);
        when(nodeExecutionMapper.selectCompensableDesc(1L)).thenReturn(List.of());

        AgentWorkflowCompensator.CompensationResult result = compensator.compensate(exec, context);

        assertEquals(0, result.total());
        assertEquals(WorkflowExecutionStatusEnum.COMPENSATED.getStatus(), result.status());
        verify(toolExecutor, never()).execute(any(), anyString(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("逆序补偿：按 mapper 返回顺序（id DESC）逐个调 compensate_ref 工具，全成功 → COMPENSATED")
    void compensate_reverseOrder_allSuccess() {
        AgentWorkflowExecutionDO exec = exec(2L, "COMPENSATE", "{\"contract\":{\"recordId\":123}}");
        when(executionMapper.selectById(2L)).thenReturn(exec);
        // mapper 已按 id DESC 返回：先撤后做的 node2，再撤 node1
        when(nodeExecutionMapper.selectCompensableDesc(2L)).thenReturn(List.of(
                node(20L, "node2", "tool_delete_b", "{\"bId\":\"{{$.contract.recordId}}\"}", null, null),
                node(10L, "node1", "tool_delete_a", "{\"aId\":\"{{$.contract.recordId}}\"}", null, null)
        ));

        AgentWorkflowCompensator.CompensationResult result = compensator.compensate(exec, context);

        assertEquals(2, result.total());
        assertEquals(2, result.compensated());
        assertEquals(0, result.failed());
        assertEquals(WorkflowExecutionStatusEnum.COMPENSATED.getStatus(), result.status());
        assertNull(result.firstError());

        // 逆序：第一次调 tool_delete_b（node2），第二次 tool_delete_a（node1）
        ArgumentCaptor<String> toolCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(toolExecutor, times(2)).execute(eq(context), toolCaptor.capture(), paramsCaptor.capture());
        assertEquals(List.of("tool_delete_b", "tool_delete_a"), toolCaptor.getAllValues(), "必须逆序撤销");
        // before_image 模板对着 variableSnapshot 解析：$.contract.recordId → "123"（模板解析统一 stringify）
        assertEquals("123", paramsCaptor.getAllValues().get(0).get("bId"));
        assertEquals("123", paramsCaptor.getAllValues().get(1).get("aId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("idempotency_key 解析后经保留参数 __idempotencyKey 透传给工具")
    void compensate_passesIdempotencyKey() {
        AgentWorkflowExecutionDO exec = exec(3L, "COMPENSATE", "{\"contract\":{\"recordId\":7}}");
        when(executionMapper.selectById(3L)).thenReturn(exec);
        when(nodeExecutionMapper.selectCompensableDesc(3L)).thenReturn(List.of(
                node(30L, "n", "tool_del", "{\"id\":\"{{$.contract.recordId}}\"}",
                        "wf3:{{$.contract.recordId}}", null)
        ));

        compensator.compensate(exec, context);

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(toolExecutor).execute(eq(context), eq("tool_del"), paramsCaptor.capture());
        Map<String, Object> params = paramsCaptor.getValue();
        assertEquals("7", params.get("id"));
        assertEquals("wf3:7", params.get(AgentWorkflowCompensator.IDEMPOTENCY_PARAM),
                "幂等键应按 state 解析后透传，供工具去重重放");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("尽力而为：首个补偿工具抛错不中断，继续撤后面的，终态 COMPENSATION_FAILED")
    void compensate_bestEffort_oneFails() {
        AgentWorkflowExecutionDO exec = exec(4L, "COMPENSATE", "{}");
        when(executionMapper.selectById(4L)).thenReturn(exec);
        when(nodeExecutionMapper.selectCompensableDesc(4L)).thenReturn(List.of(
                node(41L, "n2", "tool_b", null, null, null),
                node(40L, "n1", "tool_a", null, null, null)
        ));
        // 逆序第一个（tool_b）抛错，第二个（tool_a）正常
        when(toolExecutor.execute(eq(context), eq("tool_b"), any())).thenThrow(new RuntimeException("外部接口 500"));

        AgentWorkflowCompensator.CompensationResult result = compensator.compensate(exec, context);

        assertEquals(2, result.total());
        assertEquals(1, result.compensated());
        assertEquals(1, result.failed());
        assertEquals(WorkflowExecutionStatusEnum.COMPENSATION_FAILED.getStatus(), result.status());
        assertTrue(result.firstError().contains("tool_b") || result.firstError().contains("n2"),
                "首个失败原因应可定位到节点");
        // 关键：tool_b 失败后仍尝试了 tool_a（尽力而为，不中途放弃）
        verify(toolExecutor, times(1)).execute(eq(context), eq("tool_a"), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("before_image 非标量对象（裸字符串）→ 按空参数处理，仍调用工具（不静默跳过）")
    void compensate_beforeImageScalar_emptyParams() {
        AgentWorkflowExecutionDO exec = exec(5L, "COMPENSATE", "{}");
        when(executionMapper.selectById(5L)).thenReturn(exec);
        when(nodeExecutionMapper.selectCompensableDesc(5L)).thenReturn(List.of(
                node(50L, "n", "tool_x", "\"justAString\"", null, null)
        ));

        compensator.compensate(exec, context);

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(toolExecutor).execute(eq(context), eq("tool_x"), paramsCaptor.capture());
        assertTrue(paramsCaptor.getValue().isEmpty(), "标量 before_image 无法映射成具名参数，按空参数交给工具");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("节点 output 快照覆盖全量态：前像只在 delta 里也能解析到")
    void compensate_outputSnapshotOverlaysBase() {
        // 全量态没有 createdId，只有该节点 output delta 里有
        AgentWorkflowExecutionDO exec = exec(6L, "COMPENSATE", "{\"unrelated\":1}");
        when(executionMapper.selectById(6L)).thenReturn(exec);
        when(nodeExecutionMapper.selectCompensableDesc(6L)).thenReturn(List.of(
                node(60L, "n", "tool_undo", "{\"createdId\":\"{{$.createdId}}\"}", null,
                        "{\"createdId\":\"abc\"}")
        ));

        compensator.compensate(exec, context);

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(toolExecutor).execute(eq(context), eq("tool_undo"), paramsCaptor.capture());
        assertEquals("abc", paramsCaptor.getValue().get("createdId"),
                "output 快照应叠加到全量态上，使只存在于 delta 的前像也能被解析");
    }

    @Test
    @DisplayName("状态机：FAILED → COMPENSATING → COMPENSATED 依次落库")
    void compensate_statusTransitions() {
        AgentWorkflowExecutionDO exec = exec(7L, "COMPENSATE", "{}");
        when(executionMapper.selectById(7L)).thenReturn(exec);
        when(nodeExecutionMapper.selectCompensableDesc(7L)).thenReturn(List.of(
                node(70L, "n", "tool_z", null, null, null)
        ));

        compensator.compensate(exec, context);

        ArgumentCaptor<AgentWorkflowExecutionDO> updateCaptor = ArgumentCaptor.forClass(AgentWorkflowExecutionDO.class);
        verify(executionMapper, times(2)).updateById(updateCaptor.capture());
        List<String> statuses = updateCaptor.getAllValues().stream()
                .map(AgentWorkflowExecutionDO::getStatus).toList();
        assertEquals(List.of(WorkflowExecutionStatusEnum.COMPENSATING.getStatus(),
                WorkflowExecutionStatusEnum.COMPENSATED.getStatus()), statuses);
    }

    @Test
    @DisplayName("shouldCompensate：仅 recovery==COMPENSATE 为真；FORWARD/null/空执行均为假")
    void shouldCompensate_onlyForCompensateRecovery() {
        assertTrue(AgentWorkflowCompensator.shouldCompensate(exec(1L, "COMPENSATE", null)));
        assertFalse(AgentWorkflowCompensator.shouldCompensate(exec(1L, "FORWARD", null)));
        assertFalse(AgentWorkflowCompensator.shouldCompensate(exec(1L, null, null)));
        assertFalse(AgentWorkflowCompensator.shouldCompensate(null));
    }

}
