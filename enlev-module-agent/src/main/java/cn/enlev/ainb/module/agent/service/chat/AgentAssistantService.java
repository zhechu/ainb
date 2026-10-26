package cn.enlev.ainb.module.agent.service.chat;

import cn.enlev.ainb.module.agent.framework.agent.core.chat.AgentAnswerStreamSplitter.Suggestion;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ChangeManifest;
import cn.enlev.ainb.module.ai.api.rag.dto.AiRagSegmentRespDTO;
import lombok.Data;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * AI 助手问答 Service
 *
 * <h3>为什么是流式</h3>
 * 实测一次 RAG 问答耗时 7~22 秒（第一条 21909ms、第二条 7360ms）。
 * 非流式意味着用户要对着转圈等 20 秒才看到第一个字 —— 那基本没法用。
 *
 * <h3>🔴 流式带来的三个必须</h3>
 * <ol>
 *   <li><b>计量要在三处落库</b>：{@code doOnComplete} / {@code doOnError} / {@code doOnCancel}。
 *       只记完成的话，用户中途关掉页面就不计费，而 token 早已消耗 —— 那是可以被刷的</li>
 *   <li><b>要显式超时</b>：厂商连接挂起（连接在、不发数据）时三条回调<b>一条都不触发</b>，
 *       界面会一直转圈，连接也一直占着</li>
 *   <li><b>身份要用快照</b>：流的回调跑在 Reactor 线程上，ThreadLocal 里可能什么都没有</li>
 * </ol>
 *
 * @author 走进新时代
 */
public interface AgentAssistantService {

    /**
     * 发起一次问答
     *
     * @param reqBO   请求
     * @param context 在 <b>Servlet 线程</b>上捕获的上下文快照。
     *                晚一步捕获就可能是 null —— 见 {@code AgentExecutionContext} 的类注释
     * @return 事件流
     */
    Flux<AgentChatEvent> chat(AgentChatReqBO reqBO, AgentExecutionContext context);

    /**
     * 在聊天里<b>首跑</b>一个已发布的工作流（A1）
     *
     * <p>起因是一个具体故障：助手给出工作流草稿卡后，用户点「确认并运行」走的是
     * {@code /agent/workflow/run-stream} —— 那是「按 code 起一条执行」的通用通道，
     * 它只写 {@code agent_workflow_execution}，全程不碰 {@code agent_message}。
     * 于是运行结果既不进会话历史（刷新即消失），LLM 下一轮也读不到（历史与记忆都来自
     * {@code agent_message}）—— 用户原话是「连个记忆功能都没有吗」。
     *
     * <p>🔴 修法是<b>加入口而不是改旧通道</b>：{@code /agent/workflow/run-stream} 保持原样 ——
     * 它是带自己权限点（{@code agent:workflow:run}）的通用 REST 入口，本来就不属于任何会话，
     * 不该被塞进「落 agent_message」的逻辑。
     * 本方法与 {@link #resumeWorkflowInChat} 共用同一套桥接（{@code bridgeWorkflowEvent} +
     * settle + timeout + doOnError/doOnCancel + OutputModerator），因此<b>不新增任何落库逻辑</b>。
     *
     * <p>🔴 两条刻意不做的事：
     * <ul>
     *   <li><b>不补落 user 消息</b>：这次点击没有新的用户输入，触发规划的那条提问早已落库，
     *       再补一条会把同一句话在历史里显示两遍</li>
     *   <li><b>不记 CHAT 计量</b>（DD8 同款）：工作流各节点自己记计量，这里只落 {@code agent_message}，
     *       否则同一次调用被计两遍</li>
     * </ul>
     *
     * @param code           已发布的工作流定义 code（草稿卡上带着的那个）
     * @param input          初始变量，写进执行上下文；触发器输入变量名见 DSL {@code trigger.inputVar}
     * @param conversationId 会话编号（运行产出的回复落进这个会话）
     * @param context        Servlet 线程上捕获的身份快照
     * @return 事件流
     */
    Flux<AgentChatEvent> runWorkflowInChat(String code, Map<String, Object> input,
                                           Long conversationId, AgentExecutionContext context);

    /**
     * 在聊天里恢复一个挂起（INTERRUPTED）的工作流执行（增量13 / 收口 slice-3c 的 RK5）
     *
     * <p>工作流驱动的 Agent 绑定的 chatflow 若在 {@code hitl.confirm} 节点挂起，用户点聊天里的确认卡片
     * 「同意/拒绝」后走这里：把决定经 {@code resume} 的 feedback 写进 checkpoint，从断点续跑，
     * 再把续跑的 {@code WorkflowEvent} 桥成 {@code AgentChatEvent} 回推（与首跑共用
     * {@code bridgeWorkflowEvent}）。续跑若又撞上另一个 hitl 节点，会再发一张 WORKFLOW_CONFIRM 卡片（可递归）。
     *
     * <p>🔴 与 {@code /agent/workflow/resume-stream}（工作台用、回推裸 WorkflowEvent、发布者/管理员视角）不同：
     * 这里回推的是<b>聊天事件</b>、执行归属<b>聊天用户</b>、结果落成一条 assistant 消息进会话，用户不离开聊天窗。
     *
     * @param executionId    挂起的执行实例编号（前端从 WORKFLOW_CONFIRM 事件 / 刷新重建时取得）
     * @param approved       人工决定：true=同意续跑，false=拒绝（写进 {@code hitl_approved} 变量）
     * @param conversationId 会话编号（续跑产出的回复落进这个会话）
     * @param context        Servlet 线程上捕获的身份快照
     * @return 事件流
     */
    Flux<AgentChatEvent> resumeWorkflowInChat(Long executionId, boolean approved,
                                              Long conversationId, AgentExecutionContext context);

    /**
     * 问答请求
     */
    @Data
    class AgentChatReqBO {

        /**
         * 会话编号。为空则新建一个会话
         */
        private Long conversationId;

        /**
         * 指定用哪个已发布 Agent 对话；为空 = 默认平台助手。
         * 仅新建会话时生效（既有会话以 agent_conversation.agent_def_id 为准）
         */
        private Long agentDefId;

        /**
         * 问题
         */
        private String content;
    }

    /**
     * 事件类型
     *
     * <p>用「类型 + 数据」而不是给每种事件单开一个 SSE event name：
     * 前端的 {@code @microsoft/fetch-event-source} 按 event name 分发时，
     * 漏注册一种就会静默丢弃那类事件 —— 而漏掉哪种取决于后端什么时候新增，
     * 前端不会有任何报错。统一走一个通道，前端 switch 的 default 分支能兜住未知类型。
     */
    enum EventType {

        /**
         * 会话与消息的元信息，<b>流的第一个事件</b>
         *
         * 必须先发：新建会话时前端要靠它拿到 conversationId，否则用户刷新页面就找不回这次对话
         */
        META,

        /**
         * 正文增量
         */
        MESSAGE,

        /**
         * 思维链增量（推理模型才有）
         */
        REASONING,

        /**
         * 对话后建议，正文结束后发一次
         */
        SUGGESTIONS,

        /**
         * 规划进度，走规划路径时反复发
         *
         * 🔴 这不是装饰。规划要 1~5 轮模型调用、十几到二十几秒，
         * 而用户判定「卡住了」的阈值远低于二十秒 —— 没有进度他会刷新页面重发一遍，
         * 于是再烧一轮 token、再等二十秒。
         *
         * <p>与 {@link #MESSAGE} 分开是有意的：进度是<b>会被后续内容取代</b>的过程信息，
         * 不该像正文那样累积着留在气泡里。前端应当原地替换，不是追加。
         */
        PLANNING,

        /**
         * 产出了 Workflow DSL 草稿，规划路径的终点之一
         *
         * <p>🔴 <b>plan-as-workflow</b>：规划器产出的不再是一份线性 step 列表，而是一段
         * Workflow DSL（DAG）。此事件<b>只带 workflowDefId / code / name 与摘要</b>，
         * 不含 DSL 正文 —— 完整的节点预览由前端另外去 {@code /agent/planner/workflow-draft} 取。
         *
         * <p>不把 DSL 塞进 SSE 事件的理由和当初 PLAN 一样：刷新页面之后前端<b>照样</b>要能把
         * 卡片重建出来，那条路径（按 workflowDefId 去查）无论如何都得存在。
         * 在流里再塞一份 DSL，等于同一个结构维护两条组装逻辑，迟早不一致 —— <b>单一装配路径</b>。
         */
        WORKFLOW_DRAFT,

        /**
         * 绑定的 chatflow 在 {@code hitl.confirm} 节点挂起，请用户在聊天里确认/拒绝（增量13）
         *
         * <p>🔴 与 {@link #WORKFLOW_DRAFT} 同属「结构化可交互卡片」事件，但语义不同：草稿卡片是「要不要发布并运行
         * 这份新流程」，确认卡片是「这条<b>已在跑</b>的流程走到人工闸口，同意还是拒绝」。此事件带
         * {@code executionId}（前端点确认时回传给 {@code resume-workflow-stream}）+ {@code nodeId}（挂起的节点）
         * + {@code content}（卡片提示文案）。
         *
         * <p>取代 slice-3c 时代 INTERRUPTED 只发一句「请到工作台恢复」的死路兜底文案（plan RK5）——
         * 现在确认就在聊天里完成，用户不离开会话窗。刷新页面后前端凭落库的 {@code agent_message.execution_id}
         * 重建这张卡片（见 {@link #WORKFLOW_DRAFT} 同款「单一装配路径」理由）。
         */
        WORKFLOW_CONFIRM,

        /**
         * 正常结束
         */
        DONE,

        /**
         * 出错
         *
         * 刻意做成一个<b>正常事件</b>而不是让 SSE 连接直接断开：
         * 连接断开时前端只能拿到一个没有信息量的网络错误，
         * 用户看到的是「聊着聊着就没反应了」。发一个 ERROR 事件才能把
         * 「您没有权限」「模型没配好」这类<b>可操作</b>的原因显示出来。
         */
        ERROR
    }

    /**
     * 一个 SSE 事件
     */
    @Data
    class AgentChatEvent {

        private EventType type;

        /**
         * 正文 / 思维链增量
         */
        private String content;

        private Long conversationId;

        private Long messageId;

        /**
         * 本轮检索到的手册片段，随 META 下发，供前端渲染引用角标
         */
        private List<AiRagSegmentRespDTO> references;

        /**
         * 对话后建议
         */
        private List<Suggestion> suggestions;

        /**
         * 出错时的可读原因
         */
        private String errorMsg;

        /**
         * Workflow 定义编号，随 WORKFLOW_DRAFT 下发
         *
         * <p>前端凭它去 {@code /agent/planner/workflow-draft} 取 DSL 渲染节点预览；
         * 刷新页面后也凭它（经 {@code agent_message.workflow_def_id}）重建卡片。
         */
        private Long workflowDefId;

        /**
         * Workflow 唯一编码，随 WORKFLOW_DRAFT 下发
         *
         * <p>确认并运行时用它去 {@code publish-workflow} / {@code run-stream} ——
         * 这两个端点都以 code 为准（与既有 workflow 执行端点一致）。
         */
        private String workflowCode;

        /**
         * Workflow 名称，随 WORKFLOW_DRAFT 下发，供卡片标题显示
         */
        private String workflowName;

        /**
         * 草稿摘要，随 WORKFLOW_DRAFT 下发
         */
        private String summary;

        /**
         * 校验器提出的警告，随 WORKFLOW_DRAFT 下发
         *
         * 这些是后端<b>不会报错</b>的静默改写（§5.5 那 8 条）。
         * 不在卡片上说出来就没有第二次机会了 —— 执行成功之后，
         * 用户只会看到一张「建好了」的绿卡片，而某个字段被悄悄改成了别的类型。
         */
        private List<String> warnings;

        /**
         * 生成这份草稿用掉了几轮「生成-校验-自修正」，随 WORKFLOW_DRAFT 下发
         *
         * <p>给用户的一个<b>软</b>信心信号：1 轮就过说明模型一次产对了；逼近上限（如 6 轮里的第 5 轮）
         * 说明它是被校验器反复回灌才勉强撞出来的，节点配置更可能处在「合法但不是本意」的边缘。
         * 这类信息后端本来就有（{@code WorkflowDraftBO#getRounds}），不说出来用户只能看到一张
         * 一律同色的草稿卡，无从判断该不该逐格复核。
         *
         * <p>⚠️ 与 {@link #warnings} 一样<b>不落库</b>：刷新页面后卡片凭 {@code workflow_def_id} 重建，
         * 那时 rounds 是 null，前端必须能不显示它（而不是显示「0 轮」——那会被读成「一轮都没跑」）。
         */
        private Integer rounds;

        /**
         * Workflow 执行实例编号：随 WORKFLOW_CONFIRM 下发（挂起的那一次），也随 PLANNING 下发（正在跑的那一次）
         *
         * <p>前端点「同意/拒绝」时把它回传给 {@code /agent/assistant/resume-workflow-stream}；
         * 刷新页面后也凭它（经 {@code agent_message.execution_id}）查执行状态重建确认卡片。
         * PLANNING 上那一份的用途见 {@link #planning(String, Long, String, String)}。
         */
        private Long executionId;

        /**
         * 节点 id：随 WORKFLOW_CONFIRM 下发（挂起所在的闸口），也随 NODE 桥出来的 PLANNING 下发（刚跑完的节点）
         *
         * <p>🔴 PLANNING 上也带它是为了不让前端丢掉能力：草稿卡「确认并运行」改走助手通道（A1）之后，
         * 事件词表从 {@code WorkflowEvent} 换成 {@code AgentChatEvent}，而卡片上「哪些节点已经跑完」
         * 的绿色标记原先正是靠 NODE 事件的 nodeId 点的。进度句里只有显示名（{@code 正在执行：xx}），
         * 靠名字反查节点在重名与无名回落两种情况下都会点错格子。
         */
        private String nodeId;

        /**
         * 节点显示名：随 WORKFLOW_CONFIRM 下发（S3），也随 NODE 桥出来的 PLANNING 下发
         *
         * <p>裸 nodeId 对审批人没有信息量（{@code confirm_create_table}）。取名逻辑与进度句
         * {@code NODE → 「正在执行：xx」} 完全一致（同一份 nodeNames 映射），无名回落 nodeId。
         */
        private String nodeName;

        /**
         * 「点同意之后将会做什么」的变更清单，随 WORKFLOW_CONFIRM 下发（S3，Req 3 的落点）
         *
         * <p>已按 {@code fromNodeId = 挂起节点} 裁剪过，只含该闸口<b>之后</b>的动作 ——
         * 审批人不该被上游已经发生的写操作干扰。
         *
         * <h3>🔴 为什么随事件下发，而不是让前端另调 /manifest</h3>
         * 刷新后确认卡片是凭 {@code agent_message.execution_id} 重建的，而重建路径<b>刻意不恢复
         * nodeId</b>（{@code AgentWorkflowExecutionRespVO.currentNode} 是「最后一个跑完的节点」，
         * 而 SAA 是在 hitl 节点<b>之前</b>挂起的，拿它当闸口会把用户指错地方）。没有 nodeId 就没有
         * fromNodeId，也就无法重现这份<b>裁剪过的</b>清单。所以它必须搭事件这班车。
         *
         * <h3>🔴 可以为 null，且 null 不等于「没有变更」</h3>
         * 走的是 {@code buildManifestOrNull}（附带版）：清单生成失败不能把用户卡死在一张打不开的
         * 确认卡上。前端必须把 null 渲染成「清单不可用」，<b>绝不能</b>渲染成「本次不产生任何写操作」
         * —— 那等于让人放心点同意。刷新重建时前端会退而取一份<b>未裁剪</b>的全流程清单。
         */
        private ChangeManifest manifest;

        public static AgentChatEvent meta(Long conversationId, Long messageId,
                                          List<AiRagSegmentRespDTO> references) {
            AgentChatEvent event = new AgentChatEvent();
            event.type = EventType.META;
            event.conversationId = conversationId;
            event.messageId = messageId;
            event.references = references;
            return event;
        }

        public static AgentChatEvent message(String content) {
            AgentChatEvent event = new AgentChatEvent();
            event.type = EventType.MESSAGE;
            event.content = content;
            return event;
        }

        public static AgentChatEvent reasoning(String content) {
            AgentChatEvent event = new AgentChatEvent();
            event.type = EventType.REASONING;
            event.content = content;
            return event;
        }

        public static AgentChatEvent suggestions(List<Suggestion> suggestions) {
            AgentChatEvent event = new AgentChatEvent();
            event.type = EventType.SUGGESTIONS;
            event.suggestions = suggestions;
            return event;
        }

        public static AgentChatEvent planning(String content) {
            AgentChatEvent event = new AgentChatEvent();
            event.type = EventType.PLANNING;
            event.content = content;
            return event;
        }

        /**
         * 进度事件（PLANNING）的<b>带定位信息</b>版本
         *
         * <p>与 {@link #planning(String)} 的差别只是多带三个字段，让消费方能对上「哪一次执行、哪个节点」：
         * <ul>
         *   <li>{@code executionId}：A1 之后草稿卡「确认并运行」也走聊天通道，而断连时的提示语要能
         *       报出执行编号（B3-8：不报编号 ⟹ 用户以为什么都没发生 ⟹ 重发一次、再计一次费）。
         *       旧通道是前端直接从 {@code WorkflowEvent} 的 META 取的，换了词表就只剩这一班车</li>
         *   <li>{@code nodeId} / {@code nodeName}：草稿卡按 nodeId 点亮「已跑完」的节点格子。
         *       靠显示名反查节点在<b>重名</b>与<b>无名回落</b>两种情况下都会点错格子</li>
         * </ul>
         *
         * <p>🔴 {@code nodeId} 传原始机器键、不过 {@code OutputModerator}：它是前端回查节点用的键，
         * 审核会剥不可见字符、还可能加前缀（与 INTERRUPTED 分支里 gateNodeId 刻意不审是同一条规则）。
         * {@code nodeName} 与既有进度句同口径 —— 那句「正在执行：xx」本来就没审，这里不单独改口径。
         */
        public static AgentChatEvent planning(String content, Long executionId,
                                              String nodeId, String nodeName) {
            AgentChatEvent event = planning(content);
            event.executionId = executionId;
            event.nodeId = nodeId;
            event.nodeName = nodeName;
            return event;
        }

        public static AgentChatEvent workflowDraft(Long workflowDefId, String workflowCode,
                                                   String workflowName, String summary,
                                                   List<String> warnings, Integer rounds) {
            AgentChatEvent event = new AgentChatEvent();
            event.type = EventType.WORKFLOW_DRAFT;
            event.workflowDefId = workflowDefId;
            event.workflowCode = workflowCode;
            event.workflowName = workflowName;
            event.summary = summary;
            event.warnings = warnings;
            event.rounds = rounds;
            return event;
        }

        public static AgentChatEvent workflowConfirm(Long executionId, String nodeId, String nodeName,
                                                     String content, ChangeManifest manifest) {
            AgentChatEvent event = new AgentChatEvent();
            event.type = EventType.WORKFLOW_CONFIRM;
            event.executionId = executionId;
            event.nodeId = nodeId;
            event.nodeName = nodeName;
            event.content = content;
            event.manifest = manifest;
            return event;
        }

        public static AgentChatEvent done(Long conversationId, Long messageId) {
            AgentChatEvent event = new AgentChatEvent();
            event.type = EventType.DONE;
            event.conversationId = conversationId;
            event.messageId = messageId;
            return event;
        }

        public static AgentChatEvent error(String errorMsg) {
            AgentChatEvent event = new AgentChatEvent();
            event.type = EventType.ERROR;
            event.errorMsg = errorMsg;
            return event;
        }
    }

}
