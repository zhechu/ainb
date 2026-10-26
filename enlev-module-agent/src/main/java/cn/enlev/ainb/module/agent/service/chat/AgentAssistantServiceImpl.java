package cn.enlev.ainb.module.agent.service.chat;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.module.agent.dal.dataobject.conversation.AgentConversationDO;
import cn.enlev.ainb.module.agent.dal.dataobject.conversation.AgentMessageDO;
import cn.enlev.ainb.module.agent.dal.dataobject.def.AgentDefDO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowExecutionDO;
import cn.enlev.ainb.module.agent.enums.AgentIntentEnum;
import cn.enlev.ainb.module.agent.enums.AgentUsageSceneEnum;
import cn.enlev.ainb.module.agent.enums.WorkflowDefStatusEnum;
import cn.enlev.ainb.module.agent.framework.agent.config.AgentChatProperties;
import cn.enlev.ainb.module.agent.framework.agent.config.AgentPlanProperties;
import cn.enlev.ainb.module.agent.framework.agent.core.chat.AgentAnswerStreamSplitter;
import cn.enlev.ainb.module.agent.framework.agent.core.chat.AgentAnswerStreamSplitter.Suggestion;
import cn.enlev.ainb.module.agent.framework.agent.core.chat.AgentChatToolLoop;
import cn.enlev.ainb.module.agent.framework.agent.core.chat.AgentIntentClassifier;
import cn.enlev.ainb.module.agent.framework.agent.core.chat.AgentPromptBuilder;
import cn.enlev.ainb.module.agent.framework.agent.core.chat.AgentSuggestionParser;
import cn.enlev.ainb.module.agent.framework.agent.core.chat.AgentTextUtils;
import cn.enlev.ainb.module.agent.framework.agent.core.chat.AgentTokenEstimator;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslParser;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.TriggerDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.HitlConfirmNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.service.quota.AgentQuotaService;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentSecurityGateway;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ChangeManifest;
import cn.enlev.ainb.module.agent.framework.agent.core.security.OutputModerator;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolExecutor;
import cn.enlev.ainb.module.agent.service.conversation.AgentConversationService;
import cn.enlev.ainb.module.agent.service.def.AgentDefService;
import cn.enlev.ainb.module.agent.service.plan.WorkflowDraftBO;
import cn.enlev.ainb.module.agent.service.plan.WorkflowPlannerService;
import cn.enlev.ainb.module.agent.service.usage.AgentUsageLogService;
import cn.enlev.ainb.module.agent.service.workflow.AgentPlannerAdoptionService;
import cn.enlev.ainb.module.agent.service.workflow.AgentWorkflowDefService;
import cn.enlev.ainb.module.agent.service.workflow.AgentWorkflowExecutionService;
import cn.enlev.ainb.module.agent.service.workflow.AgentWorkflowExecutionService.WorkflowEvent;
import cn.enlev.ainb.module.agent.tool.impl.doc.DocSearchTool;
import cn.enlev.ainb.module.ai.api.model.AiModelApi;
import cn.enlev.ainb.module.ai.api.model.dto.AiChatReqDTO;
import cn.enlev.ainb.module.ai.api.model.dto.AiChatRespDTO;
import cn.enlev.ainb.module.ai.api.rag.AiRagApi;
import cn.enlev.ainb.module.ai.api.rag.dto.AiRagSegmentRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_MESSAGE_EMPTY;

/**
 * AI 助手问答 Service 实现
 *
 * @author 走进新时代
 */
@Service
@Slf4j
public class AgentAssistantServiceImpl implements AgentAssistantService {

    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILURE = "FAILURE";

    /**
     * 中断状态
     *
     * 🔴 与 FAILURE 分开：中断是<b>用户主动关掉</b>（或网络断了），
     * 失败是<b>系统没做到</b>。混成一个状态，运维看到一堆 FAILURE 会去查根本不存在的故障，
     * 而真正的故障淹没在里面。计费上两者都要记，但原因必须分得清。
     */
    private static final String STATUS_CANCELLED = "CANCELLED";

    /** 与 {@code agent_message.error_msg} 的列宽一致 */
    private static final int ERROR_MSG_MAX_LENGTH = 500;

    /**
     * 工作流驱动聊天（增量12 / slice-3c）的输入变量名<b>回落值</b>。
     *
     * <p>🔴 这里原先写着「固定约定、不解析 DSL 取 inputVar」—— 那句话从批次 B1 起就是<b>假的</b>：
     * {@link #resolveChatVars} 正是去解析 DSL、取 {@code trigger.inputVar()} 的，
     * 本常量只在「DSL 里没有 trigger 块」或「解析抛异常」这两种 fail-soft 分支上被用到。
     * 注释比代码更容易过期，而一条说反了的注释会让人以为 per-workflow 可配是没接上的。
     *
     * <p>🔴 值<b>引用</b> {@link TriggerDTO#DEFAULT_INPUT_VAR} 而不是自己抄一个字面量：
     * 两边问的是同一个缺省名，抄两份的后果是改一边不改另一边 —— 而这一份恰好在最少被跑到的
     * 回落分支上，漂了也不会被任何一次正常对话暴露出来。
     */
    private static final String WORKFLOW_INPUT_VAR = TriggerDTO.DEFAULT_INPUT_VAR;

    /**
     * 工作流回复的输出变量名<b>回落值</b>（DD6：spec 只固定了 input，output 是本刀对称定义）。
     * DONE 事件的 state 里取这个键作为聊天回复；缺失则 fail-soft 兜底文案（见 {@link #WORKFLOW_NO_OUTPUT_HINT}）。
     *
     * <p>同 {@link #WORKFLOW_INPUT_VAR}：正常路径走 {@code trigger.outputVar()}（B1 起 per-workflow 可配），
     * 本常量只是回落，故也<b>引用</b> {@link TriggerDTO#DEFAULT_OUTPUT_VAR}。
     * {@code WorkflowDslValidator} V20 判「这条腿有没有人写回复键」时用的是同一个符号 ——
     * 校验器若按另一份字面量判，就会出现「校验说写了、聊天说没写」这种两边都对不上的局面。
     */
    private static final String WORKFLOW_OUTPUT_VAR = TriggerDTO.DEFAULT_OUTPUT_VAR;

    /**
     * 工作流引用出处的固定约定键（增量16C）
     *
     * <p>{@code knowledge_retrieval} 节点把 {@code citationsVar} 配成这个键时，DONE 的 state 里就带上了
     * 命中段落的出处（documentName/score…），聊天侧据此渲染「参考资料」。与
     * {@code KnowledgeRetrievalNodeExecutor.CITATIONS_VAR} 保持一致。
     */
    private static final String WORKFLOW_CITATIONS_KEY = "citations";

    /** 流程跑完但没往 {@code output} 写文本时的兜底文案（fail-soft，不报错，DD6） */
    private static final String WORKFLOW_NO_OUTPUT_HINT =
            "（流程已执行完成，但未产出文本回复；请让流程把结果写入 output 变量）";

    /**
     * 绑定的 chatflow 在 hitl.confirm 节点挂起时，确认卡片的提示文案（增量13：可在聊天内直接确认/拒绝）
     *
     * <p>取代 slice-3c 时代「请到工作台恢复」的死路兜底（plan RK5）。它既是 WORKFLOW_CONFIRM 事件的 content，
     * 也落进那条 assistant 消息 —— 前端渲染确认卡片失败/刷新退化时，至少还能看到这句话。
     */
    private static final String WORKFLOW_INTERRUPTED_HINT =
            "该流程走到人工确认闸口，已暂停等待您的决定。";

    /**
     * 聊天内恢复时写进 checkpoint 的人工决定变量名（增量13）
     *
     * <p>🔴 直接引 {@code HitlConfirmNodeExecutor.DEFAULT_APPROVE_VAR}，不抄字面量：hitl.confirm 节点恢复后读的
     * 正是这个键，而这里<b>拿不到节点 config</b>（决定是在聊天线程上给出的，此时只有 executionId），只能写默认名。
     * 自定义了 {@code config.approveVar} 的闸口（编排器生成的流程很常见，如 {@code confirm_result}）也认这个键 ——
     * 执行器读不到自己的 approveVar 时回退读它，再把归一化后的布尔写回 approveVar，下游 condition.rule 照常判。
     *
     * <p>🔴 这个键还得在 {@code KeyStrategyFactoryGenerator} 里无条件注册：SAA resume 走
     * {@code OverAllState.input()} 重载状态，只保留注册过 KeyStrategy 的键，DSL 没声明就会被丢掉
     * （丢掉的表现是「点了同意却按拒绝走完、执行状态还报 SUCCEEDED」）。
     * 见 {@code HitlConfirmResumeRuntimeTest}。那处引的也是同一个常量。
     *
     * <p>⚠️ 这里原先抄的是字面量 {@code hitl_approved}，靠上面那句「必须与执行器一致」的注释当链接 ——
     * 与 {@code NodeIoContract} 那三个 hitl 默认名是同一种烂法（台账 #98）。改成引用之后，
     * 「改执行器默认名 → 恢复时写错键 → 用户点了同意却按拒绝走完」这条路在编译期就断了。
     * 强制点是 {@code NodeIoContractTest#mainSourcesHaveNoDefaultNameLiterals}：它扫 src/main 全部源码，
     * 只放过执行器的常量声明行（行数由断言钉住 = 默认名个数），别处出现带引号的默认名一律红。
     *
     * <p>⚠️ 本段 javadoc 点名这个键时<b>不带引号</b>，是扫描口径逼出来的：扫的是文本行，分不清注释与代码，
     * 给它套上双引号就会被判成字面量。写这条注释的人当场踩了一次 —— 上一版正是「解释这条规矩」的那句话
     * 里带着引号，被自己新写的扫描扫红了。规矩写下来的同时就有人违反，说明它需要强制点，不需要更多散文。
     */
    private static final String HITL_APPROVE_VAR = HitlConfirmNodeExecutor.DEFAULT_APPROVE_VAR;

    /** 工具循环收敛出空 answer 时的兜底文案（fail-soft，不报错，与 {@link #WORKFLOW_NO_OUTPUT_HINT} 同哲学） */
    private static final String TOOL_LOOP_EMPTY_ANSWER_HINT =
            "（工具循环已结束，但模型未给出文本结论；请换个问法或缩小问题范围后重试）";

    @Resource
    private AgentSecurityGateway gateway;
    @Resource
    private AgentToolExecutor toolExecutor;
    @Resource
    private AgentConversationService conversationService;
    @Resource
    private AgentPromptBuilder promptBuilder;
    @Resource
    private AgentSuggestionParser suggestionParser;
    @Resource
    private AgentUsageLogService usageLogService;
    @Resource
    private AiModelApi aiModelApi;
    /** ai 模块 RAG 检索 API（增量11 / slice-3a）：自定义 Agent 绑定了知识库时，按 knowledgeId 检索其专属 KB */
    @Resource
    private AiRagApi aiRagApi;
    @Resource
    private AgentChatProperties chatProperties;
    @Resource
    private AgentPlanProperties planProperties;
    @Resource
    private AgentIntentClassifier intentClassifier;
    @Resource
    private WorkflowPlannerService workflowPlannerService;
    @Resource
    private AgentWorkflowDefService defService;
    /**
     * 规划采纳率埋点（迭代七 B5-3）：AI 起草 Workflow 草稿时冻结基线 DSL，供「确认并运行」时比对是否手改。
     * best-effort，内部 try/catch，绝不影响生成主流程。
     */
    @Resource
    private AgentPlannerAdoptionService adoptionService;
    /** Agent 定义目录（P5）：聊天入口按 Agent 路由时取其 persona/modelId。与上面的 workflow defService 区分命名 */
    @Resource
    private AgentDefService agentDefService;
    @Resource
    private AgentQuotaService quotaService;
    /**
     * 工作流执行 Service（增量12 / slice-3c）：当选中 Agent 被一条已发布 chatflow 绑定时，
     * 本轮消息改跑工作流（{@link #workflowRunStream}），而非直连 LLM。
     */
    @Resource
    private AgentWorkflowExecutionService workflowExecutionService;

    /**
     * DSL 解析器（增量12 / slice-3c 批次 B1）：工作流驱动聊天时，从绑定 chatflow 的 DSL 解析
     * {@code trigger.chat.config} 的 {@code inputVar}/{@code outputVar}（缺省回落 input/output），
     * 让「会话输入写哪个变量、回复取哪个变量」可按流程配置，而非写死。
     */
    @Resource
    private DslParser dslParser;

    /**
     * 聊天 agentic 工具循环（增量12 / slice-3b 批次 B4b）：选中 Agent 绑定了只读工具时，
     * 跑「思考→调只读工具→观察→再思考」ReAct 循环到收敛出 answer（见 {@link #toolLoopStream}）。
     */
    @Resource
    private AgentChatToolLoop chatToolLoop;

    @Override
    public Flux<AgentChatEvent> chat(AgentChatReqBO reqBO, AgentExecutionContext context) {
        // ===================================================================
        // 第一段：还在 Servlet 线程上，同步做完准备工作
        //
        // 放在这里而不是流里面，是因为这一段的失败是「用户能看懂、能自己处理」的
        // ——没输入内容、会话不属于你、助手没开、用量超限——它们应该带着错误码原样抛出。
        //
        // ⚠️ 但<b>别指望全局异常处理器</b>：这是个 SSE 接口，客户端的 Accept 是
        // text/event-stream，而处理器要写 JSON —— 内容协商失败后请求落到错误页，
        // 用户看到的是「账号未登录」，真实原因全丢了（实测确认过）。
        // 所以 Controller 里接住了 ServiceException 并转成流里的一个元素，
        // 见 AgentAssistantController#sendStream。这里照常抛即可。
        // ===================================================================
        gateway.checkChat(context);
        String question = StrUtil.trimToEmpty(reqBO.getContent());
        if (question.isEmpty()) {
            throw exception(AGENT_MESSAGE_EMPTY);
        }
        Long userId = context.getUserId();

        // 🔴 配额检查只能在这里 —— 还在 Servlet 线程上、还没开始烧 token。
        //    方案 §8.5 的要求是「拒绝新请求，不中断进行中的会话」：
        //    一旦放行，这一轮就要让它跑完，哪怕跑完之后正好超线。
        //    放进流里或 Agent 循环里，就会出现「扣了 token 但没拿到结果」，
        //    而那种争议没法自证清白
        quotaService.checkQuota(userId);

        // 会话：没给就新建（新建时用请求里的 agentDefId 绑定）；给了就取既有会话。
        // 🔴 绑定 Agent 的解析（D4）：新会话用请求的 agentDefId；既有会话一律以会话自己那一列为准、
        //    忽略本次请求新带的 agentDefId —— 防一轮对话中途换 Agent 导致历史里混两套人设。
        Long conversationId;
        Long boundAgentDefId;
        if (reqBO.getConversationId() != null) {
            AgentConversationDO conversation =
                    conversationService.getConversation(reqBO.getConversationId(), userId);
            conversationId = conversation.getId();
            boundAgentDefId = conversation.getAgentDefId();
        } else {
            boundAgentDefId = reqBO.getAgentDefId();
            conversationId = conversationService.createConversation(userId, null, boundAgentDefId);
        }

        // ⚠️ 历史要在插入本轮提问<b>之前</b>取。
        // 取晚了，本轮的问题会混进历史，模型会看到同一个问题出现两次
        List<AgentMessageDO> history = conversationService.getMessageList(conversationId, userId);

        // 落用户消息
        AgentMessageDO userMessage = buildMessage(conversationId, userId,
                AgentMessageDO.ROLE_USER, question);
        userMessage.setTokenCount(AgentTokenEstimator.estimate(question));
        conversationService.appendMessage(userMessage);

        // ===================================================================
        // 解析绑定的已发布 Agent（提前到意图分类之前，两条路复用，DD4）
        //
        // 空 = 默认平台助手。getExecutableDef 对不存在/非 PUBLISHED 一律 fail-close 抛 AGENT_DEF_NOT_EXISTS。
        // 在 Servlet 线程同步解析：失败带错误码原样抛出（Controller 接住转成流内 ERROR 事件），与既有准备段一致。
        // 🔴 只解析一次，供「工作流路由」与「问答检索」两条路复用，避免重复 getExecutableDef。
        // ===================================================================
        AgentDefDO agentDef = boundAgentDefId != null
                ? agentDefService.getExecutableDef(boundAgentDefId) : null;

        // ===================================================================
        // Agent 维度配额（P7 / 待办 #6b）：选中了具体 Agent 时，按该 Agent 的 code 查配额。
        //
        // 🔴 位置与上面的 checkQuota(userId) 同理——还在 Servlet 线程、还没开始烧 token；超限抛
        //    AGENT_QUOTA_EXCEEDED，Controller 接住转成流内 ERROR 事件（不断连）。默认助手（agentDef==null）
        //    没有 Agent 维度，跳过。用量按 code 跨版本、跨该 Agent 的所有用户合并计量（JOIN 会话表归因，
        //    不改计费语义）。工作流驱动 / 工具循环 / 直连问答三条路都在此之后，故都被这道闸覆盖。
        // ===================================================================
        if (agentDef != null) {
            quotaService.checkAgentQuota(agentDef.getCode());
        }

        // ===================================================================
        // 工作流驱动路由（增量12 / slice-3c，DD4）：接线 DSL 预留的 trigger.chat
        //
        // 若选中 Agent 被一条<b>已发布</b>的 chatflow 绑定（该 chatflow 的 trigger.chat.config.agentId
        // = 本 Agent 的 code），则本轮消息不走直连 LLM，而是把问题写进工作流的 input 变量跑一遍，
        // 再把 WorkflowEvent 桥成 AgentChatEvent 回推（见 workflowRunStream）。
        //
        // 🔴 命中即 return，<b>跳过意图分类</b>：工作流驱动的 Agent 每条消息都跑工作流，语义确定，
        //    不会被误判成 BUILD 去起草新流程；也省掉分类器那次 LLM 调用（0-token 差分的关键）。
        // 🔴 未命中（无绑定 / 绑定工作流是草稿或已停用 / agentId 填错）→ 反查返回 null，
        //    逐字回落既有问答路径（DD9①：草稿不生效）。工作流绑定优先于 persona/model/KB。
        // ===================================================================
        if (agentDef != null) {
            AgentWorkflowDefDO chatWorkflow =
                    defService.getPublishedChatTriggerByAgentCode(agentDef.getCode());
            if (chatWorkflow != null) {
                return workflowRunStream(chatWorkflow, question, conversationId, context);
            }
        }

        // ===================================================================
        // 分流：这句话是在问问题，还是要求我们去改东西
        //
        // 两条路径的形态完全不同 —— 问答是流式的、带手册出处、几秒出字；
        // 规划是 Agent 循环、十几二十秒、产出一张待确认的卡片。
        // 分类器拿不准时一律回落到问答，理由见 AgentIntentClassifier 的类注释。
        // ===================================================================
        if (AgentIntentEnum.BUILD == intentClassifier.classify(question, conversationId, context)) {
            return workflowDraftStream(question, conversationId, context);
        }

        // ===================================================================
        // agentic 工具循环路由（增量12 / slice-3b，批次 B4b）
        //
        // 选中 Agent 绑定了只读工具（tool_keys 非空）→ 本轮不走「直连 LLM 一问一答」，
        // 而跑「思考→调只读工具→观察→再思考」ReAct 循环到收敛出 answer（见 toolLoopStream）。
        // 🔴 位置：BUILD 分类之后（BUILD 仍去起草工作流）、检索之前——工具循环自带调查能力，
        //    不再叠加 doc_search/KB 检索（那条路服务「无工具的直连问答」）。
        // 🔴 工作流驱动（上面）优先于工具循环：被 chatflow 绑定的 Agent 每条消息都跑工作流，轮不到工具循环。
        // 🔴 只读强制已在发布期校验（AgentDefServiceImpl.validateToolKeys），运行期循环里再拦一道（双闸）。
        // ===================================================================
        if (agentDef != null && hasToolKeys(agentDef.getToolKeys())) {
            return toolLoopStream(agentDef, question, conversationId, context);
        }

        // 检索（三段式路由，DK2）——三条路互斥，各自对应「谁在说话、有没有专属知识」：
        //   ① 默认助手（agentDef==null）：走内置平台手册 doc_search（逐字保持接 RAG 以来的既有行为）。
        //   ② 自定义 Agent 且绑定了知识库（knowledgeId!=null）：按该 knowledgeId 检索其专属 KB
        //      （增量11 / slice-3a 接线，走 ai 模块 AiRagApi.retrieve）。
        //   ③ 自定义 Agent 未绑定知识库：不检索，仅凭 persona 作答（增量10 的 D2 行为不变）。
        // 为什么不复用同一条：内置手册 doc_search 是按名解析平台级手册，与「订单客服」这类专属 KB 语义不同；
        // 而自定义 Agent 的 persona 自带身份/职责/边界，硬塞无关的平台手册片段只会干扰它。
        List<AiRagSegmentRespDTO> segments;
        if (agentDef == null) {
            segments = retrieve(context, question);
        } else if (agentDef.getKnowledgeId() != null) {
            segments = retrieveFromKnowledge(agentDef.getKnowledgeId(), question);
        } else {
            segments = Collections.emptyList();
        }

        // 组装（D1/D3 + 需求10）：自定义 Agent → persona 作系统提示词、user 内容不套手册框、不要建议；
        // 默认助手 → 系统提示词取自 agent_def 里的内置行（可在「智能体管理」在线维护），其余行为逐字不变。
        // 🔴 内置行刻意在这里才读、不在上面解析 agentDef 时读：BUILD 起草、工具循环、工作流驱动三条路都在
        //    到达本行之前就 return 了，提前读等于给这三条路各加一次用不上的查询。
        // 🔴 只借它取 persona，绝不把它当成 agentDef 参与路由：配额、chatflow 反查、工具循环、检索源
        //    这四件事仍按「未选 Agent」走。默认助手的定义性特征就是「检索平台内置手册 + 产出追问建议」，
        //    一旦让它走普通 Agent 那套，检索会从平台手册切到它并不存在的知识库、建议也会消失。
        String platformPersona = null;
        if (agentDef == null) {
            AgentDefDO platformDef = agentDefService.getPlatformAssistant();
            // 查不到（新环境没跑种子迁移）就是 null，AgentPromptBuilder 会回落到代码里的出厂提示词
            platformPersona = platformDef != null ? platformDef.getPersona() : null;
        }
        AiChatReqDTO aiReq = promptBuilder.build(
                agentDef != null ? agentDef.getPersona() : platformPersona,
                agentDef == null,
                history, question, segments,
                chatProperties.getHistoryTokenBudget(),
                agentDef == null && Boolean.TRUE.equals(chatProperties.getSuggestionEnabled()));
        aiReq.setMaxTokens(chatProperties.getMaxTokens());
        // 🔴 钉住采样温度（缺陷 D4-18）：这里原先不设。不设 ≠ 走厂商默认 ——
        //    AiModelApiImpl#buildPrompt 会兜底取 ai_model.temperature，
        //    而 CHAT 用的 model 76 那一行存的是 1（通义区间最上端）。于是「同一条提问 +
        //    同一份检索片段 + 同一个模型」两轮能一次答 609 字、一次只剩 11 字的「手册里没有覆盖这个问题」。
        //    模块里其余路径都钉了（工具循环 0.1 / 意图分型 0.0 / llm 节点取 config），唯独用户问答在满随机采样。
        //    逐条排除检索失败、换模型、知识库变动的实测证据见 AgentChatProperties.temperature 的 javadoc。
        aiReq.setTemperature(chatProperties.getTemperature());
        // 关思考。这条是全模块最贵的一处：实测思考模式下 CHAT 单次平均 284 秒，
        // 思考字数是正文的 100~4500 倍（思考 21k~85k 字，正文 0~683 字），真实吞吐稳定
        // ~210 字/秒 ⟹ 480 秒的流式预算只剩约 18% 余量，任何一次抖动就撞线，
        // 而撞线的表现是「仍未答完」—— 看着像功能缺陷，其实是预算被思考吃光。
        //
        // 🔴 已知反向风险，改这条时必须认下来：本路径是流式，而 CHAT 实测落在
        // qwen3.5-plus-* 这类走多模态端点的模型上；TongYiChatModelTests#testStream_thinking
        // 在「qwen3.6-plus + multiModel(true) + 流式」上注着 enableThinking「必须设置，
        // 否则会报错」。也就是说「多模态 + 流式 + 关思考」这个组合有被厂商直接拒的可能，
        // 且该模型是候选链末位、无处可切。真跑第一轮若报 InvalidParameter，退路有两条，
        // 都不是把 true 加回去：① 保持 enableThinking=true、改用 thinkingBudget 掐住思考预算
        // （DashScopeChatOptions 有这个字段，思考与回复分开计账，正好绕开 max-tokens 定不了尺寸的难题）；
        // ② 这里改传 null —— 但 null 在多模态档等于开思考，也就是认下 284 秒，不算修好。
        aiReq.setEnableThinking(false);
        // 模型（D6）：Agent 绑定了模型就固定它（ai 模块把它置顶候选链、不可用自动 failover 到下一个）；
        // 否则 modelId 刻意留空 —— api 层按「启用 + 密钥启用 + sort 最前」动态取默认模型，
        // 实际使用模型经 AiChatRespDTO.modelId 回传并落计量日志
        if (agentDef != null && agentDef.getModelId() != null) {
            aiReq.setModelId(agentDef.getModelId());
        }
        return stream(aiReq, context, conversationId, question, segments);
    }

    // ==================== 规划路径（plan-as-workflow） ====================

    /**
     * 走规划：跑「生成-校验-自修正」循环产出一份 Workflow DSL 草稿，落 DRAFT 定义，
     * 产出一张待确认的草稿卡片
     *
     * <h3>🔴 为什么要跑在 boundedElastic 上，以及为什么必须 context.execute</h3>
     * {@code workflowPlannerService.planToWorkflow} 是<b>阻塞</b>的（内部是个同步的多轮循环）。
     * 直接在订阅线程上跑会把它占死几十秒。挪到 {@code boundedElastic} 之后，
     * 那个线程上的 ThreadLocal 里<b>什么都没有</b> —— 而收尾时
     * {@code createDef} 要写 {@code agent_workflow_def}（带 tenant_id）。
     * 不显式贴回身份，写进去的就是脏数据，<b>而且不报错</b>。
     *
     * <h3>为什么这条路径不记 CHAT 计量</h3>
     * 生成器已经<b>按轮</b>记了 {@code PLAN_DSL}，分类器记了 {@code INTENT}。
     * 这里再记一条 CHAT，账面上就会凭空多出一次从未发生的调用 ——
     * 而这张表的定位是「对租户出示收费证据」（§8），多算比少算更难解释。
     */
    private Flux<AgentChatEvent> workflowDraftStream(String question, Long conversationId,
                                                     AgentExecutionContext context) {
        // 规划路径没有检索，references 给空列表而不是 null —— 前端会直接 .length
        Flux<AgentChatEvent> head = Flux.just(
                AgentChatEvent.meta(conversationId, null, Collections.emptyList()));

        // 🔴 这里绝不能用 Flux.create(sink -> {阻塞规划}).subscribeOn(boundedElastic)。
        //
        // subscribeOn 的预取（prefetch=32）不是直接调 request，而是
        // <b>worker.schedule(() -> s.request(n))</b> —— 派到那个唯一的 worker 线程上去执行。
        // 而 create 的 lambda 也跑在同一个 worker 上，一进去就是五分钟的阻塞规划。
        // 于是 request 排在队列里永远轮不到执行，下游的「需求」补不上来，
        // FluxCreate 只能把事件一直攒在缓冲区里，直到规划结束、worker 空出来才一次性涌出。
        //
        // 实测就是这个形态：客户端收到 META 和一条「正在理解需求…」之后<b>静默 4 分 54 秒</b>，
        // 最后 1 秒里 8915 字节全部灌出来。而同一时刻后端日志显示规划一直在正常推进
        // （第 2、3 轮在查表、查控件契约、校验、自修正）。对照实验里问答路径
        // 32KB / 436 个事件在 20 秒内持续增量到达 —— 说明 Servlet 层逐事件 flush 是好的，
        // 堵点只在 Reactor 这一层。
        //
        // 用户看到的就是「输入完没反应」，因为界面上那句「正在理解需求…」一动不动地挂五分钟。
        //
        // 换成 Sinks：下游的 request 直达 sink，由 sink 自己把缓冲的事件推出去，
        // 中间不隔任何调度器；阻塞规划单独 schedule 到<b>另一个</b> worker 上跑。
        Sinks.Many<AgentChatEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        // 规划成功落库、失败落库、中断落库、超时落库 —— 四处只能有一处生效，
        // 否则一次问答会在会话里留下两条助手消息
        AtomicBoolean settled = new AtomicBoolean(false);

        // doOnSubscribe 里才派发：Flux.concat 会等 head 走完才订阅 body，
        // 这样「建了流但没人订阅」时不会白跑一趟规划、也不会留下一张没人认领的计划
        Flux<AgentChatEvent> body = sink.asFlux()
                .doOnSubscribe(subscription -> Schedulers.boundedElastic()
                        .schedule(() -> runWorkflowPlanner(sink, settled, question, conversationId, context)));

        return Flux.concat(head, body)
                // ⚠️ timeout 是<b>相邻两个事件之间</b>的间隔上限，不是总时长。
                // 修好投递之后它才真正有意义：规划每轮都会发 PLANNING，
                // 五分钟没有任何进度 = 厂商连接挂起了（那种挂起一条回调都不触发，
                // 而这里被占住的是一个 boundedElastic 线程，只能靠超时把它甩掉）
                .timeout(Duration.ofSeconds(planProperties.getTimeoutSeconds()))
                .doOnError(error -> settleDraft(settled, context, conversationId,
                        STATUS_FAILURE, toReadableMessage(error)))
                // 用户点了停止 / 关了页面。规划线程还在跑，但这一轮对用户已经结束，
                // 必须留一条消息 —— 否则刷新后只看到自己的提问悬在那儿（见 settleDraft）
                .doOnCancel(() -> settleDraft(settled, context, conversationId,
                        STATUS_CANCELLED, "用户中断"))
                .onErrorResume(error -> {
                    log.warn("[workflowDraftStream][会话({})规划中断：{}]", conversationId, error.getMessage());
                    return Flux.just(AgentChatEvent.error(toReadableMessage(error)));
                });
    }

    /**
     * 在 boundedElastic 上跑阻塞的「生成-校验-自修正」循环，把进度与结果推进 sink
     *
     * <p>异常一律转成流里的 ERROR 事件而不是让它冒出去：
     * 冒出去只会让 SSE 连接直接断开，前端拿到一个没有信息量的网络错误。
     */
    private void runWorkflowPlanner(Sinks.Many<AgentChatEvent> sink, AtomicBoolean settled,
                                    String question, Long conversationId, AgentExecutionContext context) {
        try {
            WorkflowDraftBO draft = context.execute(() ->
                    workflowPlannerService.planToWorkflow(question, context,
                            text -> emit(sink, AgentChatEvent.planning(text))));
            context.execute(() -> emitWorkflowDraft(draft, sink, settled, conversationId, context));
        } catch (Exception ex) {
            log.warn("[workflowDraftStream][会话({})规划失败：{}]", conversationId, ex.getMessage());
            // 规划失败也要留下一条助手消息，否则用户刷新页面只看到自己的问题悬在那儿
            String readable = toReadableMessage(ex);
            if (settled.compareAndSet(false, true)) {
                context.execute(() -> appendDraftMessage(conversationId, context.getUserId(),
                        readable, null, STATUS_FAILURE, readable));
            }
            emit(sink, AgentChatEvent.error(readable));
        }
        sink.tryEmitComplete();
    }

    /**
     * 往 sink 里推一个事件
     *
     * <p>用 {@code tryEmitNext} 而不是 {@code emitNext}：后者在失败时会重试甚至抛异常，
     * 而这个 sink 跑在阻塞的规划线程上 —— 用户已经点了停止、sink 早就终止了，
     * 此时最该做的事是安静地丢掉，让规划自己走完，绝不能把异常抛回规划循环里
     * （那会把一次「用户主动中断」记成「系统故障」）。
     */
    private void emit(Sinks.Many<AgentChatEvent> sink, AgentChatEvent event) {
        Sinks.EmitResult result = sink.tryEmitNext(event);
        if (result.isFailure()) {
            log.debug("[emit][事件({})未发出，下游已终止：{}]", event.getType(), result);
        }
    }

    /**
     * 规划这一轮的收尾落库，只允许生效一次
     *
     * <p>🔴 为什么中断也要落一条消息：用户在那五分钟静默里最可能的动作就是点「停止」。
     * 中断之后什么都不写，刷新页面看到的就是<b>自己的提问孤零零挂着、没有任何回复</b> ——
     * 他会认定系统又坏了，而这恰恰是本次故障最让人抓狂的部分。
     * 写一条 CANCELLED，至少把「这轮是被中断的」说清楚。
     */
    private void settleDraft(AtomicBoolean settled, AgentExecutionContext context,
                             Long conversationId, String status, String errorMsg) {
        if (!settled.compareAndSet(false, true)) {
            return;
        }
        String content = STATUS_CANCELLED.equals(status)
                ? "本次规划已中断，可以重新提问" : StrUtil.blankToDefault(errorMsg, "规划失败");
        context.execute(() -> appendDraftMessage(conversationId, context.getUserId(),
                content, null, status, errorMsg));
    }

    /**
     * 把生成好的 DSL 草稿落成一个 DRAFT 定义 + 发 WORKFLOW_DRAFT 事件 + 落一条助手消息
     *
     * <p>落消息是为了刷新页面之后这一轮还在：草稿卡片靠 {@code workflow_def_id} 重建
     * （前端拿它去 {@code /agent/planner/workflow-draft} 取 DSL 渲染节点预览）。
     *
     * <p>🔴 <b>与线性 planner 的行为差异</b>：{@code planToWorkflow} 只会产出 DSL 草稿、
     * 或在轮次耗尽时<b>抛错</b>（走 {@link #runWorkflowPlanner} 的 catch 分支落成 FAILURE 消息）。
     * 它没有线性 planner 那个「模型判定不需要变更、直接答」的 ANSWER 逃生口 ——
     * BUILD 意图既已由分类器把关，生成不出来就如实报错（§7：暴露问题而不是硬猜），不硬凑一个答案。
     */
    private Long emitWorkflowDraft(WorkflowDraftBO draft,
                                   Sinks.Many<AgentChatEvent> sink, AtomicBoolean settled,
                                   Long conversationId, AgentExecutionContext context) {
        Long userId = context.getUserId();

        // 🔴 已经收尾过（用户点了停止、或已超时）就别再落第二条消息了。
        //
        // 中断之后生成线程并不会停下来 —— 它还在 boundedElastic 上跑，
        // 完全可能在几分钟后交出一个正常结果。这时候会话里已经有一条 CANCELLED，
        // 再补一条 SUCCESS，用户刷新页面会看到同一个问题<b>有两个答案</b>，
        // 而且那个「答案」对应的草稿卡片他从来没确认过。
        //
        // DRAFT 定义本身留着不管：它不可执行、也不占用户视野，
        // 比在这里写一条会让用户困惑的消息代价小得多。
        if (settled.get()) {
            log.warn("[emitWorkflowDraft][会话({})已收尾（中断/超时），草稿不再落库：code({})]",
                    conversationId, draft.getCode());
            return null;
        }

        // 落 DRAFT 定义（写 agent_workflow_def，带 tenant_id，故本方法整体在 context.execute 内跑）
        // 🔴 createDef 拿的必须是<b>原文</b> name：它是要落库的，审核过的前缀会被永久写进
        //    workflow_def.name，之后 getWorkflowDraft 再读出来审一次就叠成两个前缀。
        Long defId = defService.createDef(draft.getCode(), draft.getName(), draft.getDsl(),
                WorkflowDefStatusEnum.DRAFT.getStatus());

        // 🔴 B5-3：AI 起草即冻结采纳率基线。此刻 draft.getDsl() 就是 AI 原文，一旦用户在确认前
        //    用 updateDef 就地手改，原文就没了 —— 基线只能在这一刻存。本方法整体跑在 context.execute 内，
        //    ambient 租户已就位，故 recordGenerated 直接写带 tenant_id 的行，无需再套 TenantUtils。
        //    best-effort：内部 try/catch，埋点失败绝不影响「生成草稿」。
        adoptionService.recordGenerated(draft.getCode(), defId, draft.getDsl());

        // 正文写摘要而不是留空：会话列表与历史记录里，
        // 一条没有正文的助手消息看起来就像「它什么都没说」
        // 🟢 这条落库通道<b>不需要</b>在这里审核：appendDraftMessage 走 buildMessage，
        //    而 buildMessage 对 ROLE_ASSISTANT 已经审过一次（口径 A 第 3 项的收口点）。
        Long messageId = appendDraftMessage(conversationId, userId,
                StrUtil.blankToDefault(draft.getSummary(), "已生成流程草稿，请确认"),
                defId, STATUS_SUCCESS, null);
        settled.set(true);
        // 单一装配路径：事件只带 defId/code/name/summary/warnings/rounds，不带 DSL。
        // 节点预览所需的 DSL 无论直播还是刷新都走 GET workflow-draft?defId=（见 EventType.WORKFLOW_DRAFT）
        // 🔴 【B2-2 · 口径 A 第 4 项】name 与 summary 都是模型产出并解析的 DSL 里的文字，
        //    而这张草稿卡是「确认并运行」的决策面，故投递前审。审核点刻意放在<b>投递处</b>
        //    而不是 WorkflowPlannerServiceImpl#buildDraft：那里的 draft.getName() 同时是
        //    上面 createDef 的<b>落库入参</b>，在源头审等于把前缀写进库（理由见上一条注释）。
        // 🔴 code 不审：它是服务端生成的唯一键（wf_plan_*），也是前端回传 publish-workflow 用的机器键。
        emit(sink, AgentChatEvent.workflowDraft(defId, draft.getCode(),
                OutputModerator.moderateString(draft.getName()),
                OutputModerator.moderateString(draft.getSummary()),
                draft.getWarnings(), draft.getRounds()));
        emit(sink, AgentChatEvent.done(conversationId, messageId));
        return messageId;
    }

    private Long appendDraftMessage(Long conversationId, Long userId, String content,
                                    Long workflowDefId, String status, String errorMsg) {
        AgentMessageDO message = buildMessage(conversationId, userId,
                AgentMessageDO.ROLE_ASSISTANT, content);
        message.setWorkflowDefId(workflowDefId);
        message.setStatus(status);
        message.setErrorMsg(AgentTextUtils.truncateForColumn(errorMsg, ERROR_MSG_MAX_LENGTH));
        message.setTokenCount(AgentTokenEstimator.estimate(content));
        return conversationService.appendMessage(message);
    }

    // ==================== 工作流驱动聊天（增量12 / slice-3c，接线 trigger.chat） ====================

    /**
     * 跑「驱动本 Agent 的已发布 chatflow」，把 {@link WorkflowEvent} 桥成 {@link AgentChatEvent} 回推
     *
     * <h3>为什么不再套第二层调度器</h3>
     * {@code workflowExecutionService.run} 返回的 Flux <b>自带调度</b>（内部已在 boundedElastic 上推进、
     * 自带 Sinks + settle 落 agent_workflow_execution）。这里只做「事件类型转换」（concatMap），
     * 与 {@link #workflowDraftStream} 那种「阻塞规划要手动 schedule」不同——那边是同步阻塞调用，这边是现成的流。
     *
     * <h3>身份（DD7）</h3>
     * 把聊天的 {@code context} 原样传给 run()——执行归属<b>聊天用户</b>（谁问的谁拥有这次执行与会话），
     * 而非发布者。settle 同样在 {@code context.execute} 内贴回身份（Reactor 线程 ThreadLocal 可能空）。
     */
    private Flux<AgentChatEvent> workflowRunStream(AgentWorkflowDefDO wf, String question,
                                                   Long conversationId, AgentExecutionContext context) {
        // B1：从绑定 chatflow 的 DSL 解析 inputVar/outputVar（缺省回落 input/output），让读写变量可按流程配置
        ChatVars vars = resolveChatVars(wf, conversationId);
        // 节点 id→显示名映射：进度句用「正在执行：<节点名>」而非裸 nodeId（问题3c-2 人话化）
        Map<String, String> nodeNames = resolveNodeNames(wf, conversationId);

        return bridgeWorkflowRun(wf, vars, nodeNames, conversationId, context,
                () -> workflowExecutionService.run(wf.getCode(),
                        Map.of(vars.inputVar(), question), context),
                "workflowRunStream");
    }

    /**
     * 「跑一次工作流并桥成聊天事件」的公共骨架：三个入口共用同一份事件桥接、落库与超时纪律
     *
     * <p>调用方：{@link #workflowRunStream}（Agent 绑定的 chatflow，每条消息都跑）、
     * {@link #resumeWorkflowInChat}（hitl 决定后续跑）、{@link #runWorkflowInChat}（草稿卡「确认并运行」）。
     * 三者只有「怎么起步」（{@code starter}）不同 —— run 还是 resume、input 从哪来 —— 其余逐字相同。
     *
     * <p>🔴 抽出来不是为了少写几行，是因为<b>落库那一段漏一处就是一个「刷新即失忆」的缺陷</b>：
     * 草稿卡运行原先另走 {@code /agent/workflow/run-stream}（只写 agent_workflow_execution、
     * 全程不碰 agent_message），于是回复在刷新后消失、LLM 也读不到 —— 而聊天历史与记忆都来自
     * agent_message。三份拷贝的代价是「记得改三处」，漏掉的那处不报任何错。
     *
     * <h3>🔴 为什么 starter 必须由 defer 包住</h3>
     * {@code run()} / {@code resume()} 都是<b>急切求值</b>的（内部先查定义、落一条 execution）。
     * 直接调用时，组装期异常（竞态下定义刚被停用、执行已被别人认领）会在建流阶段就冒出去，
     * doOnError / doOnCancel / settle 一个都不触发。包上 defer 后这些异常变成流内 onError，
     * 走统一的 settle(FAILURE) + ERROR 事件（镜像 {@link #stream} 的 defer 纪律）。
     *
     * @param starter 起步动作，由 {@code Flux.defer} 包起来（理由见上）
     * @param logTag  日志标签，带上本入口特有的定位信息（如 {@code resumeWorkflowInChat 执行(123)}）。
     *                三条路推给用户的失败文案是同一句，日志里不分清就只能去猜是谁失败的
     */
    private Flux<AgentChatEvent> bridgeWorkflowRun(AgentWorkflowDefDO wf, ChatVars vars,
                                                   Map<String, String> nodeNames, Long conversationId,
                                                   AgentExecutionContext context,
                                                   Supplier<Flux<WorkflowEvent>> starter,
                                                   String logTag) {
        // 成功落库、失败落库、中断落库、超时落库 —— 四处只能有一处生效（同 workflowDraftStream 的 settled 纪律）
        AtomicBoolean settled = new AtomicBoolean(false);
        // 累积「已产出的回复文本」，供 doOnError/doOnCancel 在中断/超时时落一条有意义的助手消息
        AtomicReference<String> reply = new AtomicReference<>("");

        // references 给空列表而非 null：前端会直接 .length（同规划路径）
        Flux<AgentChatEvent> head = Flux.just(
                AgentChatEvent.meta(conversationId, null, Collections.emptyList()));

        Flux<AgentChatEvent> body = Flux
                .defer(starter)
                .concatMap(we -> Flux.fromIterable(
                        bridgeWorkflowEvent(we, conversationId, context, settled, reply, vars.outputVar(),
                                nodeNames, wf.getId())));

        return Flux.concat(head, body)
                // ⚠️ timeout 是相邻两事件间隔上限：工作流每过一个节点发一次 NODE，长时间无进度=某节点挂起
                .timeout(Duration.ofSeconds(chatProperties.getStreamTimeoutSeconds()))
                .doOnError(error -> settleWorkflowAnswer(settled, context, conversationId,
                        reply.get(), STATUS_FAILURE, toReadableMessage(error)))
                // 用户点停止/关页面：run() 的图可能还在跑，但这一轮对用户已结束，必须留一条消息
                .doOnCancel(() -> settleWorkflowAnswer(settled, context, conversationId,
                        reply.get(), STATUS_CANCELLED, "用户中断"))
                // 异常转成正常 ERROR 事件，而非让 SSE 断连（前端只拿到无信息量的网络错误）
                .onErrorResume(error -> {
                    log.warn("[{}][会话({})工作流执行失败：{}]",
                            logTag, conversationId, error.getMessage());
                    return Flux.just(AgentChatEvent.error(toReadableMessage(error)));
                });
    }

    /**
     * 在聊天里恢复一个挂起的工作流执行（增量13 / 收口 slice-3c 的 RK5）
     *
     * <p>与首跑 {@link #workflowRunStream} 完全同构，只把 {@code run(code, input)} 换成
     * {@code resume(executionId, {hitl_approved: approved})}，事件桥接、settle、超时、身份纪律逐字复用 ——
     * 续跑撞上另一个 hitl 会再发 WORKFLOW_CONFIRM（递归），跑到 END 则抽 output 落最终回复。
     *
     * <h3>🔴 同步校验段（还在 Servlet 线程上，失败带错误码原样抛，Controller 转成流内 ERROR）</h3>
     * <ul>
     *   <li>{@code gateway.checkChat}：助手是否可用（与 chat() 一致）</li>
     *   <li>{@code getExecution}：校验执行归属（requireIdentity + 租户隔离；不存在抛 WORKFLOW_EXECUTION_NOT_EXISTS）</li>
     *   <li>{@code getConversation}：校验会话归属，堵住「拿别人 conversationId 往里塞消息」的越权写</li>
     *   <li>{@code resume} 自身还会校验 INTERRUPTED + CAS 认领（并发/重复点击只有一个生效，另一个抛 STATE_INVALID）</li>
     * </ul>
     *
     * <h3>身份（DD7 同款）</h3>
     * 把聊天的 {@code context} 原样传给 {@code resume}——续跑归属<b>聊天用户</b>；settle 在 {@code context.execute}
     * 内贴回身份。<b>不碰 executeIgnore</b>（AgentArchitectureTest 兜底）。计量不双记（DD8）：续跑各节点自记计量，
     * 这里只落 agent_message、不记 CHAT。
     */
    @Override
    public Flux<AgentChatEvent> resumeWorkflowInChat(Long executionId, boolean approved,
                                                     Long conversationId, AgentExecutionContext context) {
        // 第一段：Servlet 线程上同步做完校验与解析（失败原样抛，见方法注释）
        gateway.checkChat(context);
        // 校验执行归属并取回定义（getExecution 内含 requireIdentity + 租户隔离）
        AgentWorkflowExecutionDO exec = workflowExecutionService.getExecution(executionId, context);
        AgentWorkflowDefDO wf = defService.getDef(exec.getWorkflowDefId());
        // 校验会话归属：conversationId 由前端传入，不校验就成了往任意会话塞消息的越权写入口
        conversationService.getConversation(conversationId, context.getUserId());
        // 续跑产出的回复取哪个变量，沿用该流程 DSL 的约定（与首跑一致，缺省 output）
        ChatVars vars = resolveChatVars(wf, conversationId);
        // 节点 id→显示名映射：续跑进度句同样用节点名而非裸 nodeId（与首跑一致）
        Map<String, String> nodeNames = resolveNodeNames(wf, conversationId);

        return bridgeWorkflowRun(wf, vars, nodeNames, conversationId, context,
                () -> workflowExecutionService.resume(executionId,
                        Map.of(HITL_APPROVE_VAR, approved), context),
                "resumeWorkflowInChat 执行(" + executionId + ")");
    }

    /**
     * 在聊天里按 code 直接跑一条<b>已发布</b>的工作流，回复落进会话（A1）
     *
     * <p>修的是草稿卡「确认并运行」那个缺陷：前端原先调 {@code /agent/workflow/run-stream}，
     * 那条通道只写 {@code agent_workflow_execution}、<b>全程不碰 agent_message</b>，
     * 而聊天历史与 LLM 的记忆都来自 agent_message —— 于是回复刷新即消失，
     * 用户接着追问一句，模型完全不知道刚才跑过什么（原话：「连个记忆功能都没有吗」）。
     * 同一击的前端还只把 DONE 里的 {@code state} 整个丢掉，界面上连当次的回复都看不到。
     *
     * <p>🔴 所以这里是<b>加一个入口</b>，不是改 {@code /agent/workflow/run-stream}：
     * 那条通道是「按 code 起一条执行、结果只进执行历史」的通用 REST 入口（有自己的权限点），
     * 本来就不属于任何会话，不该被塞进落 agent_message 的逻辑。
     * 事件桥接与落库全部复用 {@link #bridgeWorkflowRun}，本方法只负责「起步方式」与同步校验。
     *
     * <h3>🔴 同步校验段（还在 Servlet 线程上，失败带错误码原样抛，Controller 转成流内 ERROR）</h3>
     * 与 {@link #resumeWorkflowInChat} 逐条对齐，只把 {@code getExecution}（按执行编号校验归属）
     * 换成 {@code getPublishedByCode}（按 code 取已发布那一版）。
     *
     * <h3>🔴 两件刻意不做的事</h3>
     * <ul>
     *   <li><b>不补落 user 消息</b>：这一击没有新的用户输入 —— 触发规划的那句提问早就落过库了，
     *       再补一条会把同一句话在历史里显示两遍</li>
     *   <li><b>不记 CHAT 计量</b>（DD8 同款）：工作流各节点自己记计量，这里只落 agent_message，
     *       再记一次就是重复计费</li>
     * </ul>
     *
     * @param input 流程输入变量。草稿卡这一击传空 —— 换通道之前前端调 {@code /agent/workflow/run-stream}
     *              时传的也是 {@code {}}，本次只修「回复看不见、刷新即失忆」，不顺手改运行的语义
     */
    @Override
    public Flux<AgentChatEvent> runWorkflowInChat(String code, Map<String, Object> input,
                                                  Long conversationId, AgentExecutionContext context) {
        // 第一段：Servlet 线程上同步做完校验与解析（失败原样抛，见方法注释）
        gateway.checkChat(context);
        // 按 code 取<b>已发布</b>那一版：不存在 / 未发布一律 fail-close 抛错，绝不静默回落草稿
        AgentWorkflowDefDO wf = defService.getPublishedByCode(code);
        // 校验会话归属：conversationId 由前端传入，不校验就成了往任意会话塞消息的越权写入口
        conversationService.getConversation(conversationId, context.getUserId());
        // 回复取哪个变量、进度句用哪个节点名，都沿用该流程 DSL 的约定（与另两个入口一致）
        ChatVars vars = resolveChatVars(wf, conversationId);
        Map<String, String> nodeNames = resolveNodeNames(wf, conversationId);

        // input 允许为空：Map.of() 而不是 null —— run() 会把它原样塞进流程变量表，null 在下游是 NPE
        Map<String, Object> starterInput = input == null ? Map.of() : input;
        return bridgeWorkflowRun(wf, vars, nodeNames, conversationId, context,
                () -> workflowExecutionService.run(code, starterInput, context),
                "runWorkflowInChat code=" + code);
    }

    /**
     * 把一个 {@link WorkflowEvent} 桥成 0..N 个 {@link AgentChatEvent}（DD5，全部落到既有类型 → 前端聊天渲染零改动）
     *
     * <ul>
     *   <li>META → PLANNING「正在启动流程…」+ executionId（A1 起草稿卡也走这条通道，
     *       断连提示语要能报出执行编号，见 B3-8）</li>
     *   <li>NODE → PLANNING「正在执行：{节点显示名}」+ executionId/nodeId/nodeName
     *       （前端原地替换进度句，非追加；nodeId 供草稿卡点亮「已跑完」的节点；无名回落 nodeId）</li>
     *   <li>DONE → 抽 output（DD6）→ MESSAGE(回复) + settle(SUCCESS) + DONE</li>
     *   <li>INTERRUPTED → WORKFLOW_CONFIRM(确认卡片，带 executionId/nodeId/nodeName/manifest) + settle(SUCCESS，落 execution_id) + DONE
     *       （增量13：不再发「去工作台恢复」的死路文案，改发聊天内可确认/拒绝的卡片，收口 plan RK5；
     *       🔴S3：卡片还要能回答「点同意之后将会做什么」，故随事件带一份按挂起节点裁剪过的变更清单）</li>
     *   <li>ERROR → settle(FAILURE) + ERROR 事件</li>
     * </ul>
     *
     * @param workflowDefId 正在跑的<b>那一版</b>定义编号（来自 {@code getPublishedChatTriggerByAgentCode} /
     *                      resume 时反查的 def），S3 用它派生变更清单。🔴 必须是「实际在跑的版本」而不是
     *                      「该 code 的最新版」—— 清单是给人做同意/拒绝决定用的，指错版本的代价是
     *                      「据一份不是正在跑的那份的清单点了同意」
     */
    private List<AgentChatEvent> bridgeWorkflowEvent(WorkflowEvent we, Long conversationId,
                                                     AgentExecutionContext context,
                                                     AtomicBoolean settled, AtomicReference<String> reply,
                                                     String outputVar, Map<String, String> nodeNames,
                                                     Long workflowDefId) {
        switch (we.getType()) {
            case META:
                // 🔴 executionId 必须带上（A1）：草稿卡「确认并运行」改走聊天通道之后，
                //    断连提示语（B3-8）报编号的唯一来源就是这里 —— 旧通道是前端直接从
                //    WorkflowEvent 的 META 取的，换了事件词表就只剩这一班车。
                //    不带 ⟹ 提示语是空串 ⟹ 用户以为「什么都没发生」⟹ 重发一次、再计一次费。
                return List.of(AgentChatEvent.planning("正在启动流程…",
                        we.getExecutionId(), null, null));
            case NODE: {
                // 🔴 人话化（问题3c）：进度句用节点显示名而非裸 nodeId；无名（合成节点/脏数据）回落 nodeId
                String nodeId = StrUtil.blankToDefault(we.getNodeId(), "节点");
                String display = nodeNames.getOrDefault(nodeId, nodeId);
                // nodeId 另带一份原始值：草稿卡按它点亮「已跑完」的节点格子，靠显示名反查在重名时会点错
                return List.of(AgentChatEvent.planning("正在执行：" + display,
                        we.getExecutionId(), we.getNodeId(), display));
            }
            case INTERRUPTED: {
                // 增量13：不再发纯文案，而发一张聊天内可确认/拒绝的卡片。executionId 双重身份——
                //   既是 WORKFLOW_CONFIRM 事件载荷（前端点确认时回传 resume-workflow-stream），
                //   也落进 agent_message.execution_id（刷新后据此查执行状态重建卡片，镜像 workflow_def_id）。
                // 流程正常挂起（非故障）→ settle SUCCESS；settled CAS 后 doOnCancel/doOnError 不会再重复落库。
                reply.set(WORKFLOW_INTERRUPTED_HINT);
                Long messageId = settleWorkflowAnswer(settled, context, conversationId,
                        WORKFLOW_INTERRUPTED_HINT, STATUS_SUCCESS, null, we.getExecutionId());
                // S3（Req 3）：确认卡片要能回答「点同意之后将会做什么」。
                // 🔴 按挂起节点裁剪 —— 只看闸口<b>之后</b>的动作，上游已经写完的表不该再吓一次审批人。
                // 🔴 用附带版 buildManifestOrNull：清单是这张卡片的附加信息，它生成不出来
                //    不能把用户卡死在一张打不开的确认卡上（严格版会把整个 SSE 变成 ERROR）。
                //    代价是前端必须把 null 渲染成「清单不可用」，而不是「本次不产生任何写操作」。
                String gateNodeId = we.getNodeId();
                // 🔴 【B2-2 · 口径 A 第 2 项 = OutputModerator 类注释里唯一点名的那个「聊天确认卡」】
                //    节点显示名要审：nodeNames 的值来自 DSL 里 AI 起草的 node.name，
                //    而这张卡片正是「点同意之后将会做什么」的决策面（G26 里那个决策者）。
                // 🔴 gateNodeId <b>刻意不审</b>：它是前端回传 resume-workflow-stream 用的机器键，
                //    审核会剥不可见字符、还可能加前缀 —— 改过之后恢复执行就找不到那个节点了。
                //    （与「闸门永远读原始 manifest」是同一条规则的两个面：机器读的一律保持原样。）
                String gateNodeName = StrUtil.isBlank(gateNodeId)
                        ? null : OutputModerator.moderateString(nodeNames.getOrDefault(gateNodeId, gateNodeId));
                // 🔴 【口径 B 第 5 项 · 通道 ②】清单在这里只用于<b>展示</b>（不喂任何校验），
                //    故直接换成审核后的副本即可。审核与 warn 都在 OutputModerator 里，
                //    不在四个通道上各抄一遍 —— 理由见 moderateManifestForDisplay 的注释。
                ChangeManifest manifest = OutputModerator.moderateManifestForDisplay(
                        defService.buildManifestOrNull(null, workflowDefId, gateNodeId));
                return List.of(AgentChatEvent.workflowConfirm(
                                we.getExecutionId(), gateNodeId, gateNodeName,
                                WORKFLOW_INTERRUPTED_HINT, manifest),
                        AgentChatEvent.done(conversationId, messageId));
            }
            case DONE: {
                String answer = extractReply(we.getState(), conversationId, outputVar);
                reply.set(answer);
                // 增量16C：工作流若把知识检索的引用出处写进了约定的 citations 键，这里抽出来当「参考资料」，
                //   既随一条迟到 META 事件下发（前端 META 分支 msg.references=… 直接渲染，零改动），
                //   也落进 agent_message.referenceJson（刷新后仍能重建引用）。没有则为空列表，行为同旧。
                List<AiRagSegmentRespDTO> references = extractReferences(we.getState());
                Long messageId = settleWorkflowAnswer(settled, context, conversationId,
                        answer, STATUS_SUCCESS, null, null, references);
                List<AgentChatEvent> events = new ArrayList<>();
                if (!references.isEmpty()) {
                    events.add(AgentChatEvent.meta(conversationId, messageId, references));
                }
                // 🔴 【B2-2 · 口径 A 第 3 项 · 工作流 DONE 这一支】这里的 answer 来自
                //    LlmNodeExecutor 写进 state 的模型输出，是 LLM 产物，照审。
                // 🔴 对<b>原文</b>审、审完只喂 SSE：上面 settleWorkflowAnswer 拿到的仍是原文，
                //    落库那一份由 buildMessage 自己审一次。两次都是「对原文的单遍审核」，
                //    产出同样的字符串；若把审核结果再传下去就会叠出两个前缀
                //    （moderateString 刻意不幂等，理由写在它的注释里）。
                events.add(AgentChatEvent.message(OutputModerator.moderateString(answer)));
                events.add(AgentChatEvent.done(conversationId, messageId));
                return events;
            }
            case ERROR: {
                String msg = StrUtil.blankToDefault(we.getErrorMsg(), "流程执行失败");
                reply.set(msg);
                settleWorkflowAnswer(settled, context, conversationId, msg, STATUS_FAILURE, msg);
                return List.of(AgentChatEvent.error(msg));
            }
            default:
                return Collections.emptyList();
        }
    }

    /**
     * 从 DONE 的最终 state 抽出聊天回复（DD6：固定约定 {@code output} 变量，fail-soft）
     *
     * <p>spec 只固定了输入变量 {@code input}，没规定输出键 → 本刀对称定义 {@code output}：
     * String 直接用；非 String（对象/数组）序列化成 JSON 文本；缺失/空白 → 兜底文案 + log.warn，<b>不报错</b>
     * （与增量11 KB fail-soft 同哲学：流程跑完了就该给用户一个交代，哪怕只是「没产出文本」）。
     */
    private String extractReply(Map<String, Object> state, Long conversationId, String outputVar) {
        if (state == null || state.isEmpty()) {
            log.warn("[extractReply][会话({})工作流 DONE 但 state 为空，回落兜底文案]", conversationId);
            return WORKFLOW_NO_OUTPUT_HINT;
        }
        Object out = state.get(outputVar);
        if (out == null) {
            log.warn("[extractReply][会话({})工作流 DONE 但未写 {} 变量（DD6/B1 约定），回落兜底文案]",
                    conversationId, outputVar);
            return WORKFLOW_NO_OUTPUT_HINT;
        }
        if (out instanceof String s) {
            return StrUtil.isBlank(s) ? WORKFLOW_NO_OUTPUT_HINT : s;
        }
        return JSONUtil.toJsonStr(out);
    }

    /**
     * 从 DONE 的最终 state 抽出知识检索的引用出处（增量16C，约定键 {@code citations}）
     *
     * <p>{@code knowledge_retrieval} 节点配了 {@code citationsVar=citations} 时，会把命中段落连同
     * documentName/score 写进这个键（字段名逐字对齐 {@link AiRagSegmentRespDTO}）。这里 fail-soft 反序列化：
     * 键不存在 / 不是列表 / 元素结构不符 → 返回空列表（聊天照常出回复，只是不带参考资料），绝不因引用把整轮搞挂。
     */
    private List<AiRagSegmentRespDTO> extractReferences(Map<String, Object> state) {
        if (state == null) {
            return Collections.emptyList();
        }
        Object raw = state.get(WORKFLOW_CITATIONS_KEY);
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            List<AiRagSegmentRespDTO> refs = new ArrayList<>();
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                AiRagSegmentRespDTO dto = JSONUtil.toBean(JSONUtil.parseObj(item), AiRagSegmentRespDTO.class);
                if (dto != null) {
                    refs.add(dto);
                }
            }
            return refs;
        } catch (Exception ex) {
            log.warn("[extractReferences][citations 反序列化失败，本轮不带参考资料：{}]", ex.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 从绑定 chatflow 的 DSL 解析会话入口的读写变量名（B1：per-workflow 可配，缺省回落 input/output）
     *
     * <p>🔴 fail-soft：DSL 解析失败（理论上不会——能发布就通过了解析守卫）一律回落固定约定 input/output + log.warn，
     * 绝不让「取变量名」这一步把整轮对话搞挂（与 extractReply 的兜底哲学一致）。
     */
    private ChatVars resolveChatVars(AgentWorkflowDefDO wf, Long conversationId) {
        try {
            TriggerDTO trigger = dslParser.parse(wf.getDsl()).getTrigger();
            if (trigger != null) {
                return new ChatVars(trigger.inputVar(), trigger.outputVar());
            }
        } catch (Exception ex) {
            log.warn("[resolveChatVars][会话({})解析工作流({})的 trigger 变量名失败，回落 input/output：{}]",
                    conversationId, wf.getCode(), ex.getMessage());
        }
        return new ChatVars(WORKFLOW_INPUT_VAR, WORKFLOW_OUTPUT_VAR);
    }

    /**
     * 从绑定 chatflow 的 DSL 解析「节点 id → 显示名」映射（问题3c：进度句用人话节点名而非裸 nodeId）
     *
     * <p>🔴 fail-soft：DSL 解析失败一律返回空 map + log.warn，NODE 进度句回落 nodeId，绝不让「取节点名」
     * 把整轮对话搞挂（与 {@link #resolveChatVars} 同哲学）。无名节点不进 map（调用方 getOrDefault 回落 id）。
     */
    private Map<String, String> resolveNodeNames(AgentWorkflowDefDO wf, Long conversationId) {
        try {
            var nodes = dslParser.parse(wf.getDsl()).getNodes();
            if (nodes == null) {
                return Collections.emptyMap();
            }
            Map<String, String> names = new HashMap<>();
            for (var n : nodes) {
                if (n != null && StrUtil.isNotBlank(n.getId()) && StrUtil.isNotBlank(n.getName())) {
                    names.put(n.getId(), n.getName());
                }
            }
            return names;
        } catch (Exception ex) {
            log.warn("[resolveNodeNames][会话({})解析工作流({})的节点名失败，进度句回落 nodeId：{}]",
                    conversationId, wf.getCode(), ex.getMessage());
            return Collections.emptyMap();
        }
    }

    /** 会话入口的读写变量名（B1）。inputVar=把用户消息写进哪个 state 变量；outputVar=从哪个键取回复 */
    private record ChatVars(String inputVar, String outputVar) {
    }

    /**
     * 工作流驱动聊天的收尾落库，只允许生效一次（DD8：<b>只落 agent_message，不记 CHAT 计量</b>）
     *
     * <p>与 LLM 路径的 {@link #settle} 不同：本轮没有发生 chat LLM 调用，工作流各节点已自记计量
     * （agent_node_execution 带 modelId/totalTokens/tokenSource）。这里再记一条 CHAT usage_log
     * 就是凭空多算一次从未发生的调用 —— 那张表是「对租户出示收费证据」（§8），多算比少算更难解释
     * （镜像 {@link #workflowDraftStream} 类注释「为什么这条路径不记 CHAT 计量」的既有决策）。
     *
     * <p>整段包 {@code context.execute}：跑在 Reactor 线程上，ThreadLocal 可能空，不贴回身份则 tenant_id 丢失且不报错。
     *
     * @return 落库的 messageId；已结算过（或落库异常）则返回 null
     */
    private Long settleWorkflowAnswer(AtomicBoolean settled, AgentExecutionContext context,
                                      Long conversationId, String content, String status, String errorMsg) {
        return settleWorkflowAnswer(settled, context, conversationId, content, status, errorMsg, null);
    }

    /**
     * 同上，多一个 {@code executionId}（增量13）：仅 INTERRUPTED 分支传值，落进 {@code agent_message.execution_id}
     * 作确认卡片的刷新重建钥匙（镜像 {@code workflow_def_id}）；其余路径传 {@code null}，消息渲染成普通气泡。
     */
    private Long settleWorkflowAnswer(AtomicBoolean settled, AgentExecutionContext context,
                                      Long conversationId, String content, String status, String errorMsg,
                                      Long executionId) {
        return settleWorkflowAnswer(settled, context, conversationId, content, status, errorMsg, executionId, null);
    }

    /**
     * 同上，再多一个 {@code references}（增量16C）：仅 DONE 分支可能带知识检索的引用出处，
     * 落进 {@code agent_message.reference_json}，供刷新后重建「参考资料」；空/null 则保持 null（普通回复）。
     */
    private Long settleWorkflowAnswer(AtomicBoolean settled, AgentExecutionContext context,
                                      Long conversationId, String content, String status, String errorMsg,
                                      Long executionId, List<AiRagSegmentRespDTO> references) {
        if (!settled.compareAndSet(false, true)) {
            return null; // 已经结算过了
        }
        try {
            return context.execute(() -> {
                AgentMessageDO message = buildMessage(conversationId, context.getUserId(),
                        AgentMessageDO.ROLE_ASSISTANT, content);
                message.setStatus(status);
                message.setErrorMsg(AgentTextUtils.truncateForColumn(errorMsg, ERROR_MSG_MAX_LENGTH));
                message.setTokenCount(AgentTokenEstimator.estimate(content));
                message.setExecutionId(executionId); // 🔴 中断确认卡片的刷新重建钥匙；其余路径 null（普通气泡）
                // tokenSource 由 buildMessage 默认置 ESTIMATED
                // 增量16C：工作流带了知识检索引用则落 reference_json（刷新后重建「参考资料」），否则保持 null
                message.setReferenceJson(CollUtil.isEmpty(references) ? null : JSONUtil.toJsonStr(references));
                return conversationService.appendMessage(message);
            });
        } catch (Exception ex) {
            // 落库失败不再外抛：此刻流已结束，抛出去只会变成 Reactor onErrorDropped 淹没在日志里
            log.error("[settleWorkflowAnswer][会话({})结算失败，账单可能出现空洞] status={}",
                    conversationId, status, ex);
            return null;
        }
    }

    // ==================== agentic 只读工具循环（增量12 / slice-3b，批次 B4b） ====================

    /**
     * tool_keys 是否绑定了至少一个工具（空/空白 = 不绑 → 走既有直连问答，不进工具循环）
     */
    private boolean hasToolKeys(String toolKeys) {
        return !parseToolKeys(toolKeys).isEmpty();
    }

    /**
     * 解析逗号分隔的 tool_keys（trim、丢空、去重、保持顺序）
     *
     * <p>与 {@code AgentDefServiceImpl.normalizeToolKeys} 落库时的规范化同规则：库里存的本就是规范化后的串，
     * 这里再解析一次是防御性的（历史数据 / 手改库可能带空格或重复），保证喂给循环的工具名干净。
     */
    private List<String> parseToolKeys(String toolKeys) {
        if (StrUtil.isBlank(toolKeys)) {
            return Collections.emptyList();
        }
        List<String> keys = new ArrayList<>();
        for (String raw : toolKeys.split(",")) {
            String key = raw.trim();
            if (!key.isEmpty() && !keys.contains(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    /**
     * 跑「选中 Agent 绑定的只读工具循环」，把调查进度与最终结论桥成 {@link AgentChatEvent} 回推（B4b）
     *
     * <h3>为什么套 Sinks + 手动 schedule（而非 concatMap 现成流）</h3>
     * {@link AgentChatToolLoop#run} 是<b>阻塞</b>的同步循环（内部多次 {@code aiModelApi.chat}），与
     * {@link #workflowDraftStream} 的阻塞规划同形态：直接在订阅线程上跑会把它占死几十秒。故用 Sinks——
     * 下游 request 直达 sink，阻塞循环单独 schedule 到另一个 boundedElastic worker 上跑，
     * 进度经回调逐个 {@code tryEmitNext} 上浮（逐事件 flush，不憋到最后一次性涌出）。
     *
     * <h3>身份（DD7 同款）</h3>
     * 循环在 boundedElastic 线程上跑，ThreadLocal 可能空：{@code run()} 内部调工具 / 记账都要租户身份，
     * 故整体包 {@code context.execute}；settle 同样在 {@code context.execute} 内贴回身份。
     * <b>不碰 {@code executeIgnore}</b>（{@code AgentArchitectureTest} 兜底这条红线）。
     *
     * <h3>🔴 计量不双记（DD8 同款）</h3>
     * 循环内每次 LLM 调用已各记一条 TOOL_LOOP（{@code AgentChatToolLoop.recordUsage}）；这里 settle 复用
     * {@link #settleWorkflowAnswer}——只落 {@code agent_message}、<b>不记 CHAT</b>，避免把工具循环的消耗再算一遍。
     */
    private Flux<AgentChatEvent> toolLoopStream(AgentDefDO agentDef, String question,
                                                Long conversationId, AgentExecutionContext context) {
        List<String> toolNames = parseToolKeys(agentDef.getToolKeys());
        // 成功落库、失败落库、中断落库、超时落库 —— 四处只能有一处生效（同 workflowDraftStream 的 settled 纪律）
        AtomicBoolean settled = new AtomicBoolean(false);
        // 累积「已产出的回复文本」，供 doOnError/doOnCancel 在中断/超时时落一条有意义的助手消息
        AtomicReference<String> reply = new AtomicReference<>("");

        // 工具循环无检索，references 给空列表而非 null：前端会直接 .length（同规划 / 工作流路径）
        Flux<AgentChatEvent> head = Flux.just(
                AgentChatEvent.meta(conversationId, null, Collections.emptyList()));

        // doOnSubscribe 里才派发：Flux.concat 会等 head 走完才订阅 body，「建了流但没人订阅」时不会白跑一趟循环
        Sinks.Many<AgentChatEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        Flux<AgentChatEvent> body = sink.asFlux()
                .doOnSubscribe(subscription -> Schedulers.boundedElastic()
                        .schedule(() -> runToolLoop(sink, settled, reply, agentDef, toolNames,
                                question, conversationId, context)));

        return Flux.concat(head, body)
                // ⚠️ timeout 是相邻两事件间隔上限：循环每进入一次工具调查发一句 PLANNING，长时间无进度=模型/厂商挂起
                .timeout(Duration.ofSeconds(chatProperties.getStreamTimeoutSeconds()))
                .doOnError(error -> settleWorkflowAnswer(settled, context, conversationId,
                        reply.get(), STATUS_FAILURE, toReadableMessage(error)))
                // 用户点停止/关页面：循环线程还在跑，但这一轮对用户已结束，必须留一条消息
                .doOnCancel(() -> settleWorkflowAnswer(settled, context, conversationId,
                        reply.get(), STATUS_CANCELLED, "用户中断"))
                // 异常转成正常 ERROR 事件，而非让 SSE 断连（前端只拿到无信息量的网络错误）
                .onErrorResume(error -> {
                    log.warn("[toolLoopStream][会话({})工具循环失败：{}]", conversationId, error.getMessage());
                    return Flux.just(AgentChatEvent.error(toReadableMessage(error)));
                });
    }

    /**
     * 在 boundedElastic 上跑阻塞的工具循环，把进度推进 sink、收敛后落库 + 发 MESSAGE/DONE
     *
     * <p>异常一律转成流里的 ERROR 事件而不是让它冒出去：冒出去只会让 SSE 连接直接断开，
     * 前端拿到一个没有信息量的网络错误（同 {@link #runWorkflowPlanner} 的 catch 纪律）。
     */
    private void runToolLoop(Sinks.Many<AgentChatEvent> sink, AtomicBoolean settled, AtomicReference<String> reply,
                             AgentDefDO agentDef, List<String> toolNames, String question,
                             Long conversationId, AgentExecutionContext context) {
        try {
            String answer = context.execute(() -> chatToolLoop.run(context, question, toolNames,
                    agentDef.getModelId(), agentDef.getPersona(), conversationId,
                    progress -> emit(sink, AgentChatEvent.planning(progress))));
            if (StrUtil.isBlank(answer)) {
                // 收敛出空 answer：fail-soft 兜底，绝不落一条空白助手消息（与 extractReply 的兜底哲学一致）
                log.warn("[runToolLoop][会话({})工具循环收敛出空 answer，回落兜底文案]", conversationId);
                answer = TOOL_LOOP_EMPTY_ANSWER_HINT;
            }
            reply.set(answer);
            Long messageId = settleWorkflowAnswer(settled, context, conversationId,
                    answer, STATUS_SUCCESS, null);
            // 🔴 【B2-2 · 口径 A 第 3 项 · 工具循环这一支】这一支的 answer 风险<b>最高</b>：
            //    它是模型在把 tool_result / 检索片段读进上下文之后收敛出来的，
            //    而那两个源正是 UntrustedInputTracer.SourceKind 里的污点源（TOOL_RESULT / RETRIEVAL）。
            //    同样只对<b>原文</b>审、审完只喂 SSE；落库那份由 buildMessage 各审一次（不叠前缀，理由同上）。
            emit(sink, AgentChatEvent.message(OutputModerator.moderateString(answer)));
            emit(sink, AgentChatEvent.done(conversationId, messageId));
        } catch (Exception ex) {
            log.warn("[runToolLoop][会话({})工具循环失败：{}]", conversationId, ex.getMessage());
            String readable = toReadableMessage(ex);
            reply.set(readable);
            // 失败也落一条助手消息，否则用户刷新页面只看到自己的提问悬在那儿（同 runWorkflowPlanner）
            settleWorkflowAnswer(settled, context, conversationId, readable, STATUS_FAILURE, readable);
            emit(sink, AgentChatEvent.error(readable));
        }
        sink.tryEmitComplete();
    }

    // ==================== 问答路径 ====================

    /**
     * 检索手册片段
     *
     * <h3>检索失败为什么不中断对话</h3>
     * 检索不到（知识库还没灌、向量模型被关了）时，模型仍然可以说
     * 「手册里没有覆盖这个问题」—— 那是个<b>诚实且有用</b>的回答。
     * 而直接报错会让用户以为助手坏了。
     *
     * <p>但要打 warn：「知识库没灌库」是管理员该处理的事，
     * 静默降级成「什么都答不上来」，没人会发现问题出在哪。
     */
    private List<AiRagSegmentRespDTO> retrieve(AgentExecutionContext context, String question) {
        try {
            Object result = toolExecutor.execute(context, "doc_search",
                    Map.of(DocSearchTool.PARAM_QUERY, question));
            if (result instanceof DocSearchTool.DocSearchResult searchResult) {
                return CollUtil.emptyIfNull(searchResult.segments());
            }
            log.warn("[retrieve][doc_search 返回了预期外的类型({})]",
                    result == null ? "null" : result.getClass().getName());
            return Collections.emptyList();
        } catch (Exception ex) {
            log.warn("[retrieve][检索失败，本轮不带手册片段作答：{}]", ex.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 检索自定义 Agent 绑定的专属知识库（增量11 / slice-3a，DK2 的②）
     *
     * <p>直连 ai 模块 {@code AiRagApi.retrieve}，topK/相似度阈值传 {@code null} = 用该知识库自身配置
     * （与 {@code DocSearchTool} 查询期、{@code KnowledgeRetrievalNodeExecutor} 默认一致），
     * 本刀不做「每 Agent 调参」。在 chat() 的 Servlet 线程同步调用，租户上下文天然有效，不碰 {@code executeIgnore}（DK6）。
     *
     * <h3>🔴 为什么绑定了失效/已删的 KB 也不抛错，而是降级（fail-soft，DK7）</h3>
     * 与「def 非 PUBLISHED 就 fail-close 抛 {@code AGENT_DEF_NOT_EXISTS}」不同：def 状态是「这个 Agent 能不能用」的
     * <b>硬门槛</b>，而知识库是「答得准不准」的<b>软增强</b>——KB 没了不该让整个对话失败，退化成「仅凭 persona 作答」
     * 仍是可用回答（同 {@link #retrieve} 的检索失败哲学）。表单只列 ENABLE 的 KB，故这属边界情形；命中时打 warn 便于排查。
     */
    private List<AiRagSegmentRespDTO> retrieveFromKnowledge(Long knowledgeId, String question) {
        try {
            return CollUtil.emptyIfNull(aiRagApi.retrieve(knowledgeId, question, null, null));
        } catch (Exception ex) {
            log.warn("[retrieveFromKnowledge][知识库({})检索失败，本轮不带其片段作答：{}]",
                    knowledgeId, ex.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 组装流
     */
    private Flux<AgentChatEvent> stream(AiChatReqDTO aiReq, AgentExecutionContext context,
                                        Long conversationId, String question,
                                        List<AiRagSegmentRespDTO> segments) {
        AgentAnswerStreamSplitter splitter = new AgentAnswerStreamSplitter();
        StringBuilder reasoning = new StringBuilder();
        AtomicReference<AiChatRespDTO> lastChunk = new AtomicReference<>();
        AtomicReference<AiChatRespDTO.Usage> usage = new AtomicReference<>();
        // 🔴 三处落库点最终只能有一处生效。没有这个闸，
        // 「先 error 后 cancel」之类的组合会写出两条计量记录 —— 那是重复计费
        AtomicBoolean settled = new AtomicBoolean(false);
        LocalDateTime begin = LocalDateTime.now();

        SettleContext settleContext = new SettleContext(context, conversationId, question,
                segments, splitter, reasoning, usage, lastChunk, begin, settled, aiReq);

        Flux<AgentChatEvent> head = Flux.just(
                AgentChatEvent.meta(conversationId, null, segments));

        // 🔴 必须用 defer 包住，不能直接 aiModelApi.chatStream(aiReq)。
        //
        // 那个方法是<b>急切求值</b>的：它在返回 Flux 之前就先解析模型、取 ChatModel、拼 Prompt。
        // 直接调用时，这几步抛出的异常会在<b>组装阶段</b>就冒出去，
        // 整条流压根没建起来 —— doOnError / doOnCancel 一个都不会触发，
        // 于是 settle 不跑、助手消息不落库、计量也没有。
        //
        // 实测过这个形态：模型解析失败后，库里留下一条用户提问、没有任何回答，
        // 用户刷新页面只看到自己的问题悬在那儿，而日志里是一句 GlobalExceptionHandler 的
        // 「系统异常」——「没有可用的聊天模型，请启用一个」这种<b>管理员一分钟能解决</b>的信息
        // 全被吃掉了。
        //
        // 包上 defer 之后，这些异常变成流内的 onError，走统一的错误处理与落库。
        Flux<AgentChatEvent> body = Flux.defer(() -> aiModelApi.chatStream(aiReq))
                .flatMapIterable(chunk -> onChunk(chunk, splitter, reasoning, lastChunk, usage));

        // 正文流完才发建议与 DONE。用 defer 保证它在 body 真正结束后才求值 ——
        // 直接 Flux.concat(body, Flux.just(...)) 会在<b>组装时</b>就把建议算出来，
        // 那时 splitter 里还一个字都没有
        Flux<AgentChatEvent> tail = Flux.defer(() -> Flux.fromIterable(onComplete(settleContext)));

        return Flux.concat(head, body, tail)
                // ⚠️ 必须显式超时：厂商连接挂起时 complete/error/cancel 一条都不触发
                .timeout(Duration.ofSeconds(chatProperties.getStreamTimeoutSeconds()))
                .doOnError(error -> settle(settleContext, STATUS_FAILURE, error.getMessage()))
                // 用户关掉页面 / 点了停止。token 已经消耗，必须计费
                .doOnCancel(() -> settle(settleContext, STATUS_CANCELLED, "用户中断"))
                // 把异常转成一个正常的 ERROR 事件，而不是让 SSE 连接直接断开 ——
                // 断开的话前端只能拿到一个没有信息量的网络错误
                .onErrorResume(error -> {
                    log.warn("[chat][会话({})对话失败：{}]", conversationId, error.getMessage());
                    return Flux.just(AgentChatEvent.error(toReadableMessage(error)));
                });
    }

    /**
     * 处理一块流式增量
     */
    private List<AgentChatEvent> onChunk(AiChatRespDTO chunk, AgentAnswerStreamSplitter splitter,
                                         StringBuilder reasoning,
                                         AtomicReference<AiChatRespDTO> lastChunk,
                                         AtomicReference<AiChatRespDTO.Usage> usage) {
        lastChunk.set(chunk);
        // usage 通常只在末块回传，而且不是每个厂商都给。
        // 每块都覆盖一次，最后留下的就是最后一个带 usage 的
        if (chunk.getUsage() != null) {
            usage.set(chunk.getUsage());
        }
        List<AgentChatEvent> events = new ArrayList<>(2);
        if (StrUtil.isNotEmpty(chunk.getReasoningContent())) {
            reasoning.append(chunk.getReasoningContent());
            events.add(AgentChatEvent.reasoning(chunk.getReasoningContent()));
        }
        // 正文要过切分器：marker 之后的内容是给程序看的建议 JSON，一个字都不能吐给用户
        // 🔴 【B2-2 · 口径 A 第 3 项】切出来之后立刻过输出审核，<b>再</b>判空：
        //    审核会剥零宽空格与双向控制符，一个「只由不可见字符组成」的分片剥完就是空串 ——
        //    先判空后审核会把它当成有内容发出去，前端凭空多一个空气泡。
        // ⚠️ 逐分片审核是<b>尽力而为</b>，不是等价于审核整段：注入串正好被切在分片边界上时
        //    （前一片「忽略以上」、后一片「指令…」），单看哪一片都不命中模式，直播这一路就漏标；
        //    而落库那份走 buildMessage 审核的是<b>完整 answer</b>，会标出来。
        //    ⟹ 用户当场看到的与刷新后看到的可能不一致（多一个前缀）。要消掉这条缝只能扣住整段不发，
        //    那等于放弃流式输出，代价明显更大 —— 故这里选「直播尽力标 + 落库权威标」，缝隙写在明处。
        String visible = OutputModerator.moderateString(splitter.feed(chunk.getContent()));
        if (!visible.isEmpty()) {
            events.add(AgentChatEvent.message(visible));
        }
        return events;
    }

    /**
     * 正常结束：补发扣住的尾巴、解析建议、落库
     */
    private List<AgentChatEvent> onComplete(SettleContext ctx) {
        List<AgentChatEvent> events = new ArrayList<>(3);
        // 切分器可能还扣着几个字符（它们曾经像是 marker 的开头），必须补发，
        // 否则回答会莫名其妙少最后几个字
        // 🔴 【B2-2】补发的这段同样是 LLM 产物，故与 onChunk 同样「先审后判空」。
        // ⚠️【实测口径，不是推测】这一处的审核在<b>当前实现下可证是 no-op</b>：
        //    AgentAnswerStreamSplitter#suffixPrefixLength 的契约是「扣住的必是 MARKER 的某个前缀」，
        //    而 MARKER = "<<<SUGGESTIONS>>>" ⟹ pending 非空时必以 '<' 开头，
        //    既不可能只由不可见字符组成（剥完不会变空），也不可能命中 INJECTION_PATTERNS 里任何一条。
        //    留着它的唯一理由是守不变量：「每一个到人眼前的 LLM 产物都恰好过一遍审核」——
        //    MARKER 的字面值或扣住逻辑一改，这里不必重新论证一遍。
        // 🔴 也正因为它可证 no-op，本点<b>不配变异探针</b>：写不出一条注掉它会变红的断言，
        //    硬写就是方案 §6 的第 1 种假绿（断言与被测行为无关）。探针基数按口径 B 的 7 个面算。
        String rest = OutputModerator.moderateString(ctx.splitter().finish());
        if (!rest.isEmpty()) {
            events.add(AgentChatEvent.message(rest));
        }

        // 🔴 【B2-2 · 口径 B 第 7 项】suggestions 与主回答<b>同一次 LLM 调用</b>产出，同源同险，照审。
        //    审核点不在这几行，而在 AgentSuggestionParser#build —— 那是 Suggestion 记录的
        //    <b>唯一构造口</b>，在那里审一次就同时盖住下面这个 SSE 事件与 doSettle 落进
        //    agent_message.suggestions 的那份（两处用的是同一个 list 对象），不必两头各审一遍。
        List<Suggestion> suggestions = Boolean.TRUE.equals(chatProperties.getSuggestionEnabled())
                ? suggestionParser.parse(ctx.splitter().getSuggestionsRaw(), ctx.context())
                : Collections.emptyList();

        Long messageId = settle(ctx, STATUS_SUCCESS, null, suggestions);
        events.add(AgentChatEvent.suggestions(suggestions));
        events.add(AgentChatEvent.done(ctx.conversationId(), messageId));
        return events;
    }

    private Long settle(SettleContext ctx, String status, String errorMsg) {
        return settle(ctx, status, errorMsg, Collections.emptyList());
    }

    /**
     * 落库：助手消息 + 计量日志
     *
     * <h3>🔴 这是「三处落库点」真正干活的地方</h3>
     * {@code doOnComplete} / {@code doOnError} / {@code doOnCancel} 三条路径都汇到这里，
     * 靠 {@code settled} 这个 CAS 保证只生效一次。
     *
     * <p>为什么中断也要落：token 在中断<b>之前</b>就已经消耗了。
     * 只记完成的对话，等于给出一个刷额度的办法 —— 每次问完就立刻关掉页面。
     *
     * <h3>为什么整段包在 context.execute 里</h3>
     * 这个方法会跑在 <b>Reactor 线程</b>上，那里的 ThreadLocal 可能什么都没有
     * （实测预先创建好的线程池上 userId 与 tenantId 都是 null）。
     * 不显式贴回身份，insert 时拿不到 tenant_id，写进去的就是脏数据 —— 而且不报错。
     */
    private Long settle(SettleContext ctx, String status, String errorMsg,
                        List<Suggestion> suggestions) {
        if (!ctx.settled().compareAndSet(false, true)) {
            return null; // 已经结算过了
        }
        try {
            return ctx.context().execute(() -> doSettle(ctx, status, errorMsg, suggestions));
        } catch (Exception ex) {
            // 落库失败不能再往外抛：此刻流已经结束，抛出去也没人接，
            // 只会变成 Reactor 的 onErrorDropped 淹没在日志里
            log.error("[settle][会话({})结算失败，账单可能出现空洞] status={}",
                    ctx.conversationId(), status, ex);
            return null;
        }
    }

    private Long doSettle(SettleContext ctx, String status, String errorMsg,
                          List<Suggestion> suggestions) {
        AiChatRespDTO last = ctx.lastChunk().get();
        AiChatRespDTO.Usage providerUsage = ctx.usage().get();
        String answer = ctx.splitter().getAnswer();

        // 落助手消息。失败时 answer 可能是半截话，照样存 ——
        // 用户看到的就是那半截，库里却没有的话，刷新页面消息会凭空消失
        AgentMessageDO message = buildMessage(ctx.conversationId(), ctx.context().getUserId(),
                AgentMessageDO.ROLE_ASSISTANT, answer);
        message.setReasoningContent(StrUtil.trimToNull(ctx.reasoning().toString()));
        // 🔴 引用片段落库，这是下一轮能回灌的前提（方案 §7）
        message.setReferenceJson(CollUtil.isEmpty(ctx.segments())
                ? null : JSONUtil.toJsonStr(ctx.segments()));
        message.setSuggestions(CollUtil.isEmpty(suggestions)
                ? null : JSONUtil.toJsonStr(suggestions));
        message.setStatus(status);
        // 用 truncateForColumn 而不是 StrUtil.maxLength —— 后者返回 n+3 个字符，
        // 写进 varchar(500) 会插入失败（详见 AgentTextUtils 的类注释）
        message.setErrorMsg(AgentTextUtils.truncateForColumn(errorMsg, ERROR_MSG_MAX_LENGTH));

        int completionTokens = providerUsage != null && providerUsage.getCompletionTokens() != null
                ? providerUsage.getCompletionTokens()
                : AgentTokenEstimator.estimate(answer);
        message.setTokenCount(completionTokens);
        message.setTokenSource(providerUsage != null ? "PROVIDER" : "ESTIMATED");
        Long messageId = conversationService.appendMessage(message);

        recordUsage(ctx, status, errorMsg, providerUsage, answer, last, messageId);
        return messageId;
    }

    /**
     * 写计量日志
     *
     * <p>厂商没回传 usage 时用估算兜底 —— 但 {@code tokenSource} 必须如实标成
     * {@code ESTIMATED}。账单上要对估算值单独说明，混为一谈的话事后没法区分哪些数字可信。
     */
    private void recordUsage(SettleContext ctx, String status, String errorMsg,
                             AiChatRespDTO.Usage providerUsage, String answer,
                             AiChatRespDTO last, Long messageId) {
        boolean fromProvider = providerUsage != null;

        // 🔴 一个 chunk 都没回来 → 请求根本没发出去（模型被禁用、密钥失效、连不上），
        //    厂商那边<b>零消耗</b>。这种情况必须记 0，不能按 prompt 估一个数。
        //
        //    实测过这个坑：把模型停用后问一句，估算出 2098 token 并写进了计量表。
        //    而这张表的定位是「对租户出示收费证据」—— 凭空多出来的 2098 是拿不出手的，
        //    用户要是较真问「这次我连回答都没看到，凭什么扣钱」，我们无从解释。
        //
        //    注意这与「中途断开」不同：那时 token 已经在厂商那边烧掉了，必须照记。
        //    区别就在于有没有收到过任何一块响应。
        boolean reachedProvider = last != null || fromProvider;

        int promptTokens = 0;
        int completionTokens = 0;
        int totalTokens = 0;
        if (reachedProvider) {
            promptTokens = fromProvider && providerUsage.getPromptTokens() != null
                    ? providerUsage.getPromptTokens() : estimatePromptTokens(ctx.aiReq());
            completionTokens = fromProvider && providerUsage.getCompletionTokens() != null
                    ? providerUsage.getCompletionTokens() : AgentTokenEstimator.estimate(answer);
            totalTokens = fromProvider && providerUsage.getTotalTokens() != null
                    ? providerUsage.getTotalTokens() : promptTokens + completionTokens;
        }

        usageLogService.record(AgentUsageLogService.UsageRecord.builder()
                .userId(ctx.context().getUserId())
                .tenantId(ctx.context().getTenantId())
                .conversationId(ctx.conversationId())
                .messageId(messageId)
                .platform(last == null ? null : last.getPlatform())
                .modelName(last == null ? null : last.getModel())
                // 实际使用的模型编号：故障切换后可能与期望不同，计量必须能追溯
                .modelId(last == null ? null : last.getModelId())
                // 建议与主回答是同一次调用产出的，所以计入 CHAT 而不是 SUGGESTION。
                // SUGGESTION 这个场景留给「确实需要单独调一次」的情况（方案 §8.3）
                .scene(AgentUsageSceneEnum.CHAT)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(totalTokens)
                .tokenSource(fromProvider ? "PROVIDER" : "ESTIMATED")
                .inputText(ctx.question())
                .beginTime(ctx.begin())
                .endTime(LocalDateTime.now())
                .status(status)
                .errorMsg(errorMsg)
                .build());
    }

    /**
     * 估算输入 token：system prompt + 全部消息
     *
     * 只在厂商没回传 usage 时用。注意这会把回灌的手册片段也算进去 ——
     * 那正是要的：实测一次 RAG 提问输入就占了 4076 token，
     * 漏掉它们的话账面上会显得「问一句话只花了几十 token」。
     */
    private int estimatePromptTokens(AiChatReqDTO aiReq) {
        int total = AgentTokenEstimator.estimate(aiReq.getSystemPrompt());
        for (AiChatReqDTO.Message message : CollUtil.emptyIfNull(aiReq.getMessages())) {
            total += AgentTokenEstimator.estimate(message.getContent());
        }
        return total;
    }

    /**
     * 造一条 {@code agent_message}。🔴 <b>这是所有 {@code agent_message.content} 的唯一构造口</b>，
     * 故 B2-2 的落库侧审核收在这里（口径 A 第 3 项）。
     *
     * <h3>🔴 为什么必须按 role 分档，不能一律审</h3>
     * 这个方法同时服务 {@code ROLE_USER}（{@link #chat} 里落用户自己的提问）与
     * {@code ROLE_ASSISTANT}（模型回答、工作流回答、确认卡提示、失败兜底文案）。
     * 用户提问<b>不是 LLM 产物</b>：把它也过一遍审核，会在会话历史里把用户自己打的一句
     * 「忽略以上指令怎么办」标成「⚠️【疑似注入】」，还会在 2000 字符处把长提问截断 ——
     * 而这份历史下一轮要回灌进 prompt，等于我们主动篡改了用户的话。
     * 输出审核的立论是「LLM 产物投递给人看之前要过一道」，用户自己的话不在那个立论里。
     *
     * <h3>🔴 入参必须是<b>原文</b>，不许传审核过的值（否则前缀会叠加）</h3>
     * {@code moderateString} <b>刻意不是幂等的</b>：命中注入模式的串每过一次就在最前面多一个
     * {@code ⚠️【疑似注入】}。而 SSE 投递侧（{@code onChunk} / {@code onComplete} /
     * {@code bridgeWorkflowEvent} DONE / {@code runToolLoop}）也要各审一次 ——
     * 那几处一律对<b>同一个原文</b>各调一次，绝不把审核结果再传进本方法。
     * 「两次独立的单遍审核」得到的是同样的字符串；「一遍套一遍」得到的是两个前缀。
     * 🔴 也别把它改成「已带前缀就跳过」来图省事：那样一个自己以该前缀开头的载荷
     * 会连带逃过<b>剥不可见字符</b>与<b>限长</b>，是拿一个真窟窿换一个假幂等。
     *
     * <p>🔴 连带一条不变量：调用方的 {@code tokenCount} 一律按<b>原文</b>估
     * （{@code doSettle} 与 {@code settleWorkflowAnswer} 都是），<b>不</b>按审核后的 content 估。
     * 计量要对齐「模型真的生成了多少」，而审核是本地后处理 —— 按审核后的串估，
     * 会让用户因为我们的前缀多付配额，也会在截断时把账单算少。
     */
    private AgentMessageDO buildMessage(Long conversationId, Long userId,
                                        String role, String content) {
        AgentMessageDO message = new AgentMessageDO();
        message.setConversationId(conversationId);
        message.setUserId(userId);
        message.setRole(role);
        message.setContent(AgentMessageDO.ROLE_ASSISTANT.equals(role)
                ? OutputModerator.moderateString(content)
                : content);
        message.setStatus(STATUS_SUCCESS);
        message.setTokenSource("ESTIMATED");
        message.setTokenCount(0);
        message.setErrorMsg("");
        return message;
    }

    /**
     * 把异常转成用户看得懂的话
     *
     * {@code ServiceException} 的 message 本来就是写给用户的（「您没有执行该操作的权限：…」），
     * 直接用。其它异常一律给一句通用提示 —— 把 {@code NullPointerException}
     * 之类的原文吐到界面上，对用户没有任何帮助，还可能泄漏内部结构。
     *
     * <h3>🔴 这里<b>刻意没有</b> {@code instanceof IllegalStateException} 那一支（台账 D4-29 删掉的）</h3>
     * 它原先返回 {@code AGENT_NO_CHAT_MODEL}「没有可用的聊天模型，请联系管理员启用一个」，
     * 而那句的<b>原始理由早已不成立</b>：「一个聊天模型都没启用」现在由 ai 侧
     * {@code AiModelApiImpl#buildChatCandidates} 抛 {@code ServiceException(MODEL_ALL_UNAVAILABLE, …)}，
     * 被上面第一支原样返回，指引一个字没丢（那次改造是 b0bdd0933b，删掉了旧的 {@code resolveChatModel}）。
     * 于是这一支剩下的唯一作用是<b>给别的成因贴错标签</b>：模型明明启用着、只是密钥没填，
     * 界面却说「没有可用的聊天模型」，管理员照着去反复启停一个本来就启用着的模型
     * （红线 §10.2 第 7 条：不替 error 编病因）。
     *
     * <p>修在 ai 侧而不是这里，是因为两条硬约束把 agent 侧的路都堵死了：
     * 按<b>码</b>分流要引 {@code ai.enums.ErrorCodeConstants}，红线 §10.1 第 3 条禁止（跨模块只经 api/ 包）；
     * 按<b>异常类型</b>分流就是原来那一支，而类型压根不携带成因。
     * 所以做法是：ai 侧把「密钥为空」升成带码的 {@code ServiceException(API_KEY_BLANK)}，
     * 它自然走上面第一支、原样透传；agent 这一支删掉。
     *
     * <p>⚠️ 代价是「别的 {@code IllegalStateException}」现在落到最后那句通用提示，这是<b>有意</b>的取舍：
     * 一句<b>不指认</b>成因的话比一句<b>指认错</b>成因的话更容易排查 —— 前者让人去翻日志，
     * 后者让人照着错方向改配置。（实测 2026-10-07：本方法能收到的 {@code IllegalStateException}
     * 只可能来自厂商 SDK 内部；bocha 联网搜索那几处由 ai 自己的 {@code AiChatMessageServiceImpl} 消费，
     * 不经 agent 这条路 —— 且它们已改为带码的 {@code ServiceException(WEB_SEARCH_FAIL)}，
     * 连同厂商原文一起走第一支透传，不再以 {@code IllegalStateException} 的形态存在。）
     * 真要让某个成因说清自己，去它自己那一侧升成带码的 {@code ServiceException}，
     * <b>不要</b>回来给这里添 {@code instanceof}。
     */
    private String toReadableMessage(Throwable error) {
        if (error instanceof cn.enlev.ainb.framework.common.exception.ServiceException serviceException) {
            return serviceException.getMessage();
        }
        if (error instanceof java.util.concurrent.TimeoutException) {
            return "模型响应超时（超过 " + chatProperties.getStreamTimeoutSeconds() + " 秒），请重试";
        }
        return "AI 助手暂时无法回答，请稍后重试";
    }

    /**
     * 结算所需的全部状态
     *
     * 收成一个 record 而不是往方法里传十个参数：三处落库点都要用同一套状态，
     * 参数列表散开后，新增一个字段就要改四处签名，漏一处就是行为不一致。
     */
    private record SettleContext(
            AgentExecutionContext context,
            Long conversationId,
            String question,
            List<AiRagSegmentRespDTO> segments,
            AgentAnswerStreamSplitter splitter,
            StringBuilder reasoning,
            AtomicReference<AiChatRespDTO.Usage> usage,
            AtomicReference<AiChatRespDTO> lastChunk,
            LocalDateTime begin,
            AtomicBoolean settled,
            AiChatReqDTO aiReq) {
    }

}
