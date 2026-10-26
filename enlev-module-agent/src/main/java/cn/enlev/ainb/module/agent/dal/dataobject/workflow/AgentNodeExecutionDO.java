package cn.enlev.ainb.module.agent.dal.dataobject.workflow;

import cn.enlev.ainb.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * Workflow 节点执行 trace DO（一次节点执行一行，含重试）
 *
 * <h3>🔴 token 只在这里落，且只对「真正调模型的节点」落</h3>
 * 不对每个 {@code NodeOutput.tokenUsage()} 裸读 —— 那个字段在 GraphRunnerContext 上
 * <b>粘滞</b>：tool/condition 这些不调模型的节点会继承上一个 llm 节点的 usage，
 * 照抄会把同一笔 token 记到多个节点头上（spike 已实测到这个坑，见 SPIKE-CONCLUSION SR-2）。
 * 正确做法：在 llm 节点执行器里从模型返回的 usage 捕获，写进本行的 token 字段。
 *
 * <h3>重试用新增行，不覆盖</h3>
 * {@link #attempt} 从 1 起。每次尝试独立一行，才能还原「第几次、每次烧了多少 token」。
 *
 * @author 走进新时代
 */
@TableName("agent_node_execution")
@KeySequence("agent_node_execution_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentNodeExecutionDO extends TenantBaseDO {

    private Long id;

    private Long executionId;

    /**
     * DSL 节点 id
     *
     * <p>🔴 子图节点落的是<b>命名空间 id</b> {@code <父id>-<子id>}（sub_workflow / iteration /
     * agent_loop 三种），不是 DSL 里的裸 id。要按所属构造归因请读 {@link #parentNodeId}，
     * 别去切这个字符串 —— 它超 64 字符时是<b>截断</b>的，切不回来。
     */
    private String nodeId;

    /**
     * 节点显示名（执行当时的 {@code NodeDTO.name} 快照）
     *
     * <p>🔴 是<b>快照</b>不是引用：流程后来改了节点名，这里的既有行不回填。
     * 老行（列加上之前落库的）为 NULL。
     */
    private String nodeName;

    /**
     * 所属 sub_workflow / iteration / agent_loop 节点的<b>裸</b> id；NULL = 顶层节点
     *
     * <p>🔴 <b>不是自引用外键，别 join {@link #nodeId}</b>：那三种节点自身走
     * {@code graph.addNode(id, childGraph)}、不经 {@code DslToStateGraphCompiler.wrap}，
     * 所以它们在本表里<b>没有</b>对应行。本列只是「所属构造」的标签。
     *
     * <p>🔴 嵌套 ≥2 层时只有直接父：{@code addSubWorkflowNode} 递归构建子图时，传给子图的命名空间
     * 只由<b>本层</b>的 sw 节点 id 决定，不与外层前缀复合 ⟹ 祖父链无法由本列还原。
     */
    private String parentNodeId;

    /**
     * 节点类型：llm / tool / condition / ...
     */
    private String nodeType;

    /**
     * tool 节点实际调用的工具标识（{@code config.toolId}）；DSL 没声明该配置的节点为 NULL
     *
     * <p>🔴 NULL 有<b>三</b>种含义，本列分不出来，前端也就不能把 NULL 当异常渲染：
     * <ol>
     *   <li>这行本来就不是 tool 节点（llm / condition / …）；</li>
     *   <li>agent_loop 的 {@code act} 行 —— 它 {@code node_type='tool'} 却<b>永远</b>没有 toolId：
     *       调哪些只读工具是模型每一步现选的（见 {@code AgentLoopGraphBuilder.doAct}），
     *       压根不存在一个「这个节点的工具」。这是按构造如此，不是漏记；</li>
     *   <li>本列加上之前的老行（那时确实没记）。</li>
     * </ol>
     * 因为 (2) 的存在，「{@code nodeType=='tool'} 且 toolId 为空」<b>不能</b>判定成记录失败 ——
     * 那样会给每一行 agent_loop 的 act 打上告警标，把真告警淹掉。
     * 见 {@code ExecutionHistoryDrawer.vue} 的「工具」列。
     */
    private String toolId;

    /**
     * 状态：RUNNING / SUCCESS / FAILURE / SKIPPED
     */
    private String status;

    /**
     * 第几次尝试，从 1 起
     */
    private Integer attempt;

    /**
     * llm 节点实际使用的模型编号（故障切换后可追溯真正跑的是哪个）
     */
    private Long modelId;

    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;

    /**
     * token 来源：PROVIDER 厂商回传 / ESTIMATED 本地估算
     */
    private String tokenSource;

    private String inputSnapshot;
    private String outputSnapshot;

    private Integer durationMs;

    private String errorMsg;

    /**
     * Saga 反做引用：指向一个 tool 节点/工具标识
     */
    private String compensateRef;

    /**
     * 反做所需前像 JSON
     */
    private String beforeImage;

    /**
     * 幂等键，反做重放时去重。对应库里的 unique key
     */
    private String idempotencyKey;

    private LocalDateTime beginTime;
    private LocalDateTime endTime;

}
