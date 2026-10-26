package cn.enlev.ainb.module.agent.framework.agent.core.engine.trace;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentNodeExecutionDO;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentNodeExecutionMapper;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.WorkflowContext;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * {@link NodeTraceRecorder} 的单元测试
 *
 * <h3>这里守的是一条把证据吃掉的线：报错信息越长越写不进去</h3>
 * {@code agent_node_execution.error_msg} 是 {@code varchar(1000)}，
 * 而原先用的是 {@code StrUtil.maxLength(msg, 1000)} —— 它的语义是「正文截到 1000，再补三个点」，
 * 返回值实际 1003 字符，写库直接 Data truncation。
 *
 * <p>实测后果不是「少三个点」：{@code save_draft} 校验失败时错误文本很长，
 * 这条 UPDATE 撞库抛出 {@code DataIntegrityViolationException}，
 * 整个执行被这条数据库异常收尾，<b>真正的失败原因（哪个控件不合法）一个字都没留下</b>，
 * 排查的人只能看到一句「Data too long for column 'error_msg'」。
 *
 * <h3>另一半：begin 落的三列（迭代六 §13.1 合流对②）</h3>
 * {@code tool_id} / {@code node_name} / {@code parent_node_id} 是本迭代新加的列，
 * 前两条守「事实真的被记下来了」（此前 tool 节点执行时把 toolId 传给了工具执行器、却没落库），
 * 第三条守 {@code parent_node_id} 记的是<b>裸</b>父 id —— 带尾分隔符就等于把裁定 A-Ⅱ 要废掉的
 * 字符串命名空间原样搬进了新列。三列都是自由文本，故同时守列宽截断（与 error_msg 同族的坑）。
 *
 * @author 走进新时代
 */
public class NodeTraceRecorderTest extends BaseMockitoUnitTest {

    /** 与 {@code agent_node_execution.error_msg} 的列宽一致 */
    private static final int ERROR_MSG_COLUMN_LENGTH = 1000;

    /** 与 {@code agent_node_execution.node_name} 的列宽一致 */
    private static final int NODE_NAME_COLUMN_LENGTH = 128;

    /** 与 {@code agent_node_execution.tool_id} 的列宽一致 */
    private static final int TOOL_ID_COLUMN_LENGTH = 128;

    @InjectMocks
    private NodeTraceRecorder recorder;

    @Mock
    private AgentNodeExecutionMapper nodeExecutionMapper;

    private WorkflowContext ctx() {
        // failure() 只用到身份上下文（贴租户写库），state 传 null 即可
        return new WorkflowContext(null, 1L, "thread-test",
                AgentExecutionContext.ofForTest(1L, 1L));
    }

    private String capturedErrorMsg() {
        ArgumentCaptor<AgentNodeExecutionDO> captor = ArgumentCaptor.forClass(AgentNodeExecutionDO.class);
        verify(nodeExecutionMapper).updateById(captor.capture());
        AgentNodeExecutionDO update = captor.getValue();
        assertEquals(NodeTraceRecorder.STATUS_FAILURE, update.getStatus());
        return update.getErrorMsg();
    }

    /**
     * 🔴 这条就是 Data truncation 的回归测试
     */
    @Test
    @DisplayName("🔴 超长错误信息写库前被截到列宽内，不再撞 Data truncation")
    public void testFailureTruncatesLongErrorMsg() {
        String message = "控件类型 upload 已停用，不能使用".repeat(200);
        assertTrue(message.length() > ERROR_MSG_COLUMN_LENGTH, "夹具本身得超长才有意义");

        recorder.failure(9L, ctx(), 12L, new RuntimeException(message));

        String written = capturedErrorMsg();
        assertTrue(written.length() <= ERROR_MSG_COLUMN_LENGTH,
                "写库长度必须 <= 列宽 " + ERROR_MSG_COLUMN_LENGTH + "，实际 " + written.length());
        // 截断是为了写得进去，不是为了把原因抹掉：开头必须还是真正的失败原因
        assertTrue(written.startsWith("控件类型 upload 已停用"), "得保住原因的前半段：" + written);
    }

    @Test
    @DisplayName("未超列宽的错误信息原样写入，不被无谓截断")
    public void testFailureKeepsShortErrorMsg() {
        recorder.failure(9L, ctx(), 12L, new RuntimeException("字段 device_name 重复"));

        assertEquals("字段 device_name 重复", capturedErrorMsg());
    }

    @Test
    @DisplayName("异常没有 message 时退回类名，不写 null")
    public void testFailureFallsBackToExceptionClass() {
        recorder.failure(9L, ctx(), 12L, new IllegalStateException());

        assertEquals("IllegalStateException", capturedErrorMsg());
    }

    // ==================== begin：迭代六 合流对② 新加的三列（tool_id / node_name / parent_node_id） ====================

    /** begin 要读 ctx.data() 落 input_snapshot，所以这里必须给真 state（failure 那几条不需要） */
    private WorkflowContext ctxWithState() {
        return new WorkflowContext(new OverAllState(new HashMap<>()), 1L, "thread-test",
                AgentExecutionContext.ofForTest(1L, 1L));
    }

    private static NodeDTO node(String type, String name, Map<String, Object> config) {
        NodeDTO node = new NodeDTO();
        node.setId("n1");
        node.setType(type);
        node.setName(name);
        node.setConfig(config);
        return node;
    }

    private AgentNodeExecutionDO capturedInsert() {
        ArgumentCaptor<AgentNodeExecutionDO> captor = ArgumentCaptor.forClass(AgentNodeExecutionDO.class);
        verify(nodeExecutionMapper).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("tool 节点：tool_id 与 node_name 都落库（迭代六 D2 —— 以前这两样一个字都没记）")
    public void testBeginRecordsToolIdAndNodeName() {
        recorder.begin(ctxWithState(), node("tool", "更新表单", Map.of("toolId", "form_update")));

        AgentNodeExecutionDO written = capturedInsert();
        assertEquals("form_update", written.getToolId(),
                "tool 节点执行时确实把 toolId 传给了工具执行器，trace 行必须也留下它");
        assertEquals("更新表单", written.getNodeName());
        assertEquals("tool", written.getNodeType());
    }

    @Test
    @DisplayName("非 tool 节点：tool_id 留 null，不写空串（空串会让「本来没有」和「记成了空」在库里同形）")
    public void testBeginLeavesToolIdNullForNonToolNode() {
        recorder.begin(ctxWithState(), node("llm", "意图分类", Map.of("outputVar", "intent")));

        assertNull(capturedInsert().getToolId());
    }

    @Test
    @DisplayName("🔴 parent_node_id 由调用方显式传：子图节点记裸父 id，顶层节点记 null")
    public void testBeginRecordsParentNodeIdOnlyForSubGraphNodes() {
        recorder.begin(ctxWithState(), node("tool", "取结构", Map.of("toolId", "datamodel_structure")),
                "iter-describe", "iter");

        AgentNodeExecutionDO child = capturedInsert();
        assertEquals("iter-describe", child.getNodeId());
        assertEquals("iter", child.getParentNodeId(),
                "必须是裸父 id：带尾分隔符（'iter-'）就等于把字符串命名空间又搬进了新列");

        reset(nodeExecutionMapper);
        recorder.begin(ctxWithState(), node("trigger.chat", "入口", Map.of()));
        assertNull(capturedInsert().getParentNodeId(), "顶层节点不属于任何子图，parent_node_id 必须是 null");
    }

    @Test
    @DisplayName("🔴 超列宽的 node_name / tool_id 被截到列宽内，且不像 error_msg 那样撞 Data truncation")
    public void testBeginTruncatesOverlongNameAndToolId() {
        String longName = "这是一个长得离谱的节点显示名".repeat(20);
        String longToolId = "t".repeat(300);
        assertTrue(longName.length() > NODE_NAME_COLUMN_LENGTH, "夹具本身得超长才有意义");

        recorder.begin(ctxWithState(), node("tool", longName, Map.of("toolId", longToolId)));

        AgentNodeExecutionDO written = capturedInsert();
        assertTrue(written.getNodeName().length() <= NODE_NAME_COLUMN_LENGTH,
                "node_name 写库长度必须 <= 列宽 " + NODE_NAME_COLUMN_LENGTH + "，实际 " + written.getNodeName().length());
        assertTrue(written.getToolId().length() <= TOOL_ID_COLUMN_LENGTH,
                "tool_id 写库长度必须 <= 列宽 " + TOOL_ID_COLUMN_LENGTH + "，实际 " + written.getToolId().length());
    }

}
