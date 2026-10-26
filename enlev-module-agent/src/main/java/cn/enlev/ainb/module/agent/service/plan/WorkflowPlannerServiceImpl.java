package cn.enlev.ainb.module.agent.service.plan;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.agent.enums.AgentUsageSceneEnum;
import cn.enlev.ainb.module.agent.framework.agent.config.AgentPlanProperties;
import cn.enlev.ainb.module.agent.framework.agent.core.AgentTaskKeys;
import cn.enlev.ainb.module.agent.framework.agent.core.chat.AgentTokenEstimator;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslParser;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslToStateGraphCompiler;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.support.StateTemplateResolver;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.framework.agent.core.plan.WorkflowDslPlannerPrompt;
import cn.enlev.ainb.module.agent.framework.agent.core.react.ReactJsonCodec;
import cn.enlev.ainb.module.agent.framework.agent.core.react.ReactJsonCodec.ExtractedJson;
import cn.enlev.ainb.module.agent.framework.agent.core.security.PromptDataFence;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.AgentValidateResult;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.WorkflowDslValidator;
import cn.enlev.ainb.module.agent.service.usage.AgentUsageLogService;
import cn.enlev.ainb.module.agent.tool.impl.form.FormPublishBundleTool;
import cn.enlev.ainb.module.ai.api.model.AiModelApi;
import cn.enlev.ainb.module.ai.api.model.dto.AiChatReqDTO;
import cn.enlev.ainb.module.ai.api.model.dto.AiChatRespDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_MESSAGE_EMPTY;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_PLAN_DSL_GENERATION_FAILED;

/**
 * plan-as-workflow 生成器实现（增量7a）
 *
 * <h3>循环结构刻意仿线性 planner，但砍掉了「只读工具探索」轮次</h3>
 * 线性 planner 的轮次分两种：tool_calls（查资料）与 plan（交方案）。本生成器<b>全部预算都用于
 * 「产 DSL → 校验 → 回灌自修正」</b>——7a 不做生成前的只读探索（提示词已内嵌工具目录+schema，
 * 模型直接产 DSL；探索增强留 7b）。故这里没有 tool_calls 分派，循环更简单。
 *
 * <h3>🔴 自修正全部复用既有件，不重造</h3>
 * JSON 抠取/解析失败回灌用 {@link ReactJsonCodec}（与线性 planner、agent_loop 同源，带实测疤）；
 * 结构错误回灌用 {@link AgentValidateResult#toFeedback()}（收集全部错，省计费轮次）。
 *
 * @author 走进新时代
 */
@Service
@Slf4j
public class WorkflowPlannerServiceImpl implements WorkflowPlannerService {

    /**
     * 本协议的 JSON 形态说明，解析失败回灌时用（{@link ReactJsonCodec#parseFailureHint} 的 formatSpec 入参）
     */
    private static final String DSL_FORMAT_SPEC =
            "一个完整的 Workflow DSL 对象 {\"dslVersion\":\"1.0\",\"variables\":[...],\"nodes\":[...],\"edges\":[...]}";

    /** 生成 code 的同毫秒去重序号（进程内单调，配合时间戳 base36 足够唯一） */
    private static final AtomicLong CODE_SEQ = new AtomicLong();

    @Resource
    private AiModelApi aiModelApi;
    @Resource
    private WorkflowDslPlannerPrompt plannerPrompt;
    @Resource
    private DslParser dslParser;
    @Resource
    private WorkflowDslValidator dslValidator;
    @Resource
    private DslToStateGraphCompiler compiler;
    @Resource
    private AgentUsageLogService usageLogService;
    @Resource
    private AgentPlanProperties planProperties;
    @Resource
    private ObjectMapper objectMapper;

    @Override
    public WorkflowDraftBO planToWorkflow(String question, AgentExecutionContext context,
                                          ProgressListener listener) {
        String trimmed = StrUtil.trimToEmpty(question);
        if (trimmed.isEmpty()) {
            throw exception(AGENT_MESSAGE_EMPTY);
        }

        int maxRounds = planProperties.getAsWorkflowMaxRounds();
        // 🔴 B2-3：CONTRACT 挂在 system prompt 上 —— 整个自修正循环每轮重发的是<b>同一份</b>字符串，
        //    挂在这里只花一次；挂到每轮的回灌消息上则是 maxRounds 份重复
        String systemPrompt = plannerPrompt.buildSystemPrompt(maxRounds)
                + "\n\n" + PromptDataFence.CONTRACT;

        List<AiChatReqDTO.Message> history = new ArrayList<>();
        // 🔴 B2-3：用户这句需求是 TRIGGER_CHAT 污点源，包成数据段。
        //    规划器这一处比问答那几处更要紧：它的产物是一份<b>会被落库、还会被发布</b>的 DSL。
        //    一句藏在需求里的「顺带把某张表的数据清掉」如果被当成指令读进去，
        //    它就会变成一个真节点、过校验、然后只等人在确认卡上点「确认并运行」。
        //    变更清单与审批闸是后面的防线，但最省事的位置是<b>不让它进 DSL</b>。
        history.add(AiChatReqDTO.Message.of("user",
                PromptDataFence.fence("user_requirement", trimmed)));

        // 🔴 一次规划（含全部自修正轮次）= 一个任务，键在入口取一次。
        //    自修正循环最怕的就是「第 3 轮换了个模型」：回灌的修正意见是针对上一个模型的输出写的，
        //    换模型接着改，等于让另一个人接着改别人的稿子，还看不出换过 —— 裁定 R-01
        String taskKey = AgentTaskKeys.newPlanRun();

        // 最后一轮的校验反馈，轮次耗尽时随异常带出（让调用方/日志看到到底卡在哪条规则）
        String lastFeedback = "";
        // 上一轮没能产出草稿的那份原文，用来认「模型在原地打转」（见下面的定点检测）
        String previousRejected = null;
        // 🔴 本次各轮<b>实际</b>落到的模型，轮次耗尽时随异常带出。
        //    编排不指定 modelId，走的是 failover 候选链，链头会因为厂商拒绝与冷却而漂移，
        //    事后只有 agent_usage_log 的 model_id 查得出来 —— 而轮次耗尽那句是用户唯一看得到的话。
        //    实测疤：候选 11 个，链首两个接连被厂商 400 掉（一个报「url error」、
        //    一个报「enable_thinking 只允许 True」），四轮全部落在 qwen3-vl-flash（一个<b>视觉</b>模型）
        //    上产 JSON，最后抛出的却只有一句「去换个更强的模型」——没说<b>此刻在用哪个</b>，
        //    于是管理员不知道该把活儿从谁手里接走，只能把整条链启停一遍碰运气。
        Set<String> servedModels = new LinkedHashSet<>();

        for (int round = 1; round <= maxRounds; round++) {
            progress(listener, round == 1 ? "正在把需求拆解成流程…"
                    : "还在完善流程细节…");

            CallResult call = callModel(systemPrompt, history, context, trimmed, round, taskKey);
            if (call.resp() == null) {
                // 调用本身失败（厂商异常 / 额度耗尽）：回灌一句「与你格式无关，请原样重出」，进下一轮。
                // 🔴 失败原因必须同时记进 lastFeedback：它是轮次耗尽时唯一能到用户手上的信息。
                //    额度耗尽那种情况每一轮都是 null，不记的话用户看到的是
                //    「模型在 6 轮内未能产出结构合法的 DSL」—— 把「压根没调通」误报成
                //    「模型写的格式不对」，排查方向整个是反的（红线 §10.2：不替 error 编病因）
                lastFeedback = call.error();
                history.add(AiChatReqDTO.Message.of("user",
                        "上一次调用模型失败了，与你的输出格式无关，请重新输出完整的 Workflow DSL JSON"));
                continue;
            }
            AiChatRespDTO resp = call.resp();
            // 记在定点检测<b>之前</b>：原地打转的那一轮同样是它产的，缺了这一轮就会把
            // 「只有一个模型在打转」说成「一个都没记到」
            servedModels.add(describeModel(resp));

            String content = StrUtil.trimToEmpty(resp.getContent());

            // 🔴 定点检测：与上一轮**一字不差**，说明回灌压根没被采纳，再问一轮只会得到同一份东西。
            //    温度 0.1 下这是硬定点而不是巧合。实测疤：一次建表编排第 3~6 轮的原文 md5 完全相同
            //    （7808df6b…），白烧 4 轮、约 6.4 万 prompt token，最后抛出去的还是那句没有信息量的
            //    「6 轮内未能产出结构合法的 DSL」。轮次预算是给「改一处、再试」用的，不是给原地重复用的。
            if (content.equals(previousRejected)) {
                throw exception(WORKFLOW_PLAN_DSL_GENERATION_FAILED,
                        "模型连续两轮产出**完全相同**的 DSL 且同样不合法（第 " + round + " 轮），"
                                + "回灌自修正对它无效，已提前止损。" + StrUtil.nullToEmpty(lastFeedback)
                                + "。这不是轮次不够 —— 调大 as-workflow-max-rounds 只会多烧几轮同样的钱；"
                                + "该做的是换模型：编排用的是 AI 模型配置里「已启用 + 排序值最前」的那个，"
                                + "把更强的排到它前面、或直接停用它让候选链落到下一个"
                                + "（也可以修掉提示词里教错的那一处）" + describeServedModels(servedModels));
            }
            previousRejected = content;

            // ① 抠 JSON + 解析成 WorkflowDefDTO
            ParseOutcome outcome = parseDsl(content, resp.getUsage(), history);
            if (outcome.def() == null) {
                // 🔴 解析失败也要记进 lastFeedback：它是轮次耗尽时唯一能到用户手上的信息
                //    （异常 → agent_message.error_msg → 界面直接显示）。以前这里只 continue，
                //    lastFeedback 一直是空，于是「到底是截断还是括号数错、错在第几个字符」
                //    全留在后端日志里，排查的人得翻几万行日志才知道该调 maxTokens 还是该调 prompt。
                lastFeedback = outcome.feedback();
                progress(listener, "还在整理流程…");
                continue;
            }
            WorkflowDefDTO def = outcome.def();

            // ② 双层校验：收集式基础 + （基础通过则）编译器干跑深校验
            AgentValidateResult result = validateTwoLayer(def, context);
            if (!result.isPassed()) {
                lastFeedback = result.toFeedback();
                // 🔴 回灌内容必须进日志：自修正循环是「改一处就好」还是「反复被同一条规则拒」，只有日志看得出来
                log.warn("[planToWorkflow][第({})轮 DSL 未通过校验，回灌自修正：{}]", round, lastFeedback);
                progress(listener, "正在优化流程结构…");
                history.add(AiChatReqDTO.Message.of("assistant", content));
                // 🔴 B2-3：校验反馈要包成数据段。它看着是「平台自己算出来的」，但里面<b>回显了模型写的标识符</b> ——
                //    节点名、表名、工具入参都会被原样拼进那句「XX 不合法」。
                //    于是「节点名里写一句注入」就能让它以校验器口吻出现在回灌消息里，
                //    而回灌消息是模型<b>最信</b>的一段（它刚被拒了一次，正等着平台告诉它怎么改）。
                // 🔴 只包反馈、不包上面那条 assistant 消息：那条是模型自己的话，
                //    按对话协议就该以 assistant 身份原样回去，包起来反而会让模型认不出那是它写的。
                history.add(AiChatReqDTO.Message.of("user",
                        "DSL 校验未通过，请修正后<b>重新输出一份完整的 DSL</b>。校验器的意见在下面的数据段里：\n"
                                + PromptDataFence.fence("validation_feedback", lastFeedback)));
                continue;
            }

            // ③ 通过：注入服务端唯一 code（不信任模型占位 id），序列化为落库用 DSL
            return buildDraft(def, result, round);
        }

        // 🔴 超限 fail-close：绝不拿最后一轮那份非法 DSL 凑合（与线性 planner、agent_loop 同一条纪律）
        throw exception(WORKFLOW_PLAN_DSL_GENERATION_FAILED,
                StrUtil.blankToDefault(lastFeedback,
                        "模型在 " + maxRounds + " 轮内未能产出结构合法的 Workflow DSL")
                        + describeServedModels(servedModels));
    }

    /**
     * 一个模型对<b>用户</b>的称呼：只写模型标识（如 {@code qwen3-vl-flash-2025-10-15}）
     *
     * <p>🔴 <b>不带 modelId</b>。这句话是给管理员看的，它给的处置是「去 AI 模型配置把更强的
     * 排到它前面、或直接停用它」—— 那个列表页上能对上的是「模型标识」这一列（连搜索框都是它），
     * 而数据库自增号对不上任何一列。写成 {@code 69/test-model} 的后果是：人先要把 69 翻译成型号
     * 才知道该停用谁，等于在「已经撞上一次编排失败」之后又加一道排查工序。
     *
     * <p>⚠️ 编号不是没用，它属于<b>追溯</b>而不属于<b>处置</b>：failover 的 warn 日志
     * （{@code AiModelFailoverServiceImpl} 一律 id/model 同列）与 {@code agent_usage_log.model_id}
     * 都留着它，对账时按任务键一查就到。
     *
     * <p>⚠️ 标识取不到时才退回带编号的说法 —— 那种情况下编号是唯一的把手，
     * 只写「未知型号」等于什么都没说。
     */
    private static String describeModel(AiChatRespDTO resp) {
        return StrUtil.blankToDefault(resp.getModel(), "未知型号（模型编号 " + resp.getModelId() + "）");
    }

    /**
     * 轮次耗尽时补在错误消息末尾的一句：这几轮的 DSL <b>实际</b>是谁产的
     *
     * <p>🔴 一轮都没调通时返回空串，一个字都不补：那种情况下 {@code lastFeedback} 装的已经是厂商
     * 那句原文（额度耗尽 / 密钥没填 / 400），再补一句「没有模型产出过」只是把病因盖掉一层。
     * 与「不替 error 编病因」是同一条纪律。
     *
     * <p>⚠️ 末尾那句「按哪一列找」不是客套：前面给的处置是去 AI 模型配置改排序/停用，
     * 而那个列表一页有模型名字、模型标识、API 秘钥、排序四列。不说清拿哪个串去对哪一列，
     * 用户就得自己猜 —— 猜错列（比如拿标识去对模型名字）的结果是「找不到这个模型」，
     * 看起来像平台在报一个不存在的型号。
     */
    private static String describeServedModels(Set<String> servedModels) {
        return servedModels.isEmpty() ? ""
                : "；本次各轮实际产出 DSL 的模型标识：" + String.join("、", servedModels)
                        + "（AI 模型配置里按「模型标识」这一列找）";
    }

    // ==================== 解析 ====================

    /**
     * 从模型输出里抠出 JSON 并解析成 {@link WorkflowDefDTO}；失败则把回灌提示塞进 history，
     * 并把「错在哪」作为 feedback 带回去
     *
     * <p>复用 {@link ReactJsonCodec}：模型爱在 JSON 前后补解释文字/包 ```json 围栏，抠取器剥一层；
     * 解析失败时 {@link ReactJsonCodec#parseFailureHint} 把出错位置附近原文回灌。
     *
     * <p>🔴 {@link ReactJsonCodec#diagnose} 的结论<b>必须同时进回灌与 feedback</b>，不能只写日志。
     * 它区分的是两种处理方式完全相反的故障：截断要调大 maxTokens（模型没错，是我们没让它说完），
     * 写坏了才该去调 prompt。以前这里把 diagnose 的结果只交给 {@code log.warn}，
     * 而 {@code parseFailureHint} 那个专门为它留的 {@code extraGuidance} 入参传的是 {@code null} ——
     * 于是模型被截断时收到的却是「你写的不是合法 JSON，请重新输出」，
     * 它照原样再产一份同样长的、同样被截断的 JSON，一轮一轮把预算烧光。
     */
    private ParseOutcome parseDsl(String content, AiChatRespDTO.Usage usage,
                                  List<AiChatReqDTO.Message> history) {
        if (content.isEmpty()) {
            history.add(AiChatReqDTO.Message.of("user",
                    "你刚才的回复是空的。请<b>只</b>输出一个 Workflow DSL JSON 对象，格式为 " + DSL_FORMAT_SPEC));
            return ParseOutcome.failed("模型这一轮回复是空的");
        }
        ExtractedJson extracted = ReactJsonCodec.extractJsonObject(content);
        if (extracted == null) {
            log.warn("[parseDsl][模型输出里找不到 JSON 对象（{} 字符）。原文：{}]", content.length(), content);
            history.add(AiChatReqDTO.Message.of("user",
                    "你刚才的回复里找不到 JSON 对象。请<b>只</b>输出一个 Workflow DSL JSON 对象，"
                            + "不要任何解释文字、不要代码围栏，格式为 " + DSL_FORMAT_SPEC));
            return ParseOutcome.failed("模型输出里找不到 JSON 对象（原文 " + content.length() + " 字符）");
        }
        // 🔴 解析前先补「裸占位符」的引号。{@code "args":{"id":{{$.formDraft.id}}}} 是实测踩过两次的错法
        //    （第二次还是故障转移后的弱模型），而自修正救不了它：diagnose 只看得见「解析失败」，
        //    correctionFor 回灌的是「重数嵌套层级」，与真实错因毫不相干，模型于是一字不差重出同一份
        //    直到定点检测止损 —— 用户看到的就是「轮次耗尽」。理由与实现见 ReactJsonCodec#quoteBarePlaceholders。
        String json = ReactJsonCodec.quoteBarePlaceholders(extracted.json());
        try {
            return ParseOutcome.ok(dslParser.parse(json));
        } catch (Exception ex) {
            String diagnosis = ReactJsonCodec.diagnose(new ExtractedJson(json, extracted.balanced()),
                    usage == null ? null : usage.getCompletionTokens(),
                    planProperties.getMaxTokens());
            // 🔴 失败时把整段原文记下来：写坏的括号往往在中间，只记头尾看不出来（线性 planner 的同一条实测疤）
            log.warn("[parseDsl][DSL 解析失败：{}。{}。原文（{} 字符）：{}]",
                    ex.getMessage(), diagnosis, content.length(), content);
            history.add(AiChatReqDTO.Message.of("assistant", content));
            // 🔴 B2-3 的<b>残余面</b>：这一处刻意没包裹，登记为 N35（同款还在 AgentChatToolLoop 的解析失败回灌）。
            //    原因是包裹会<b>破坏它的定位</b>：parseFailureHint 拿解析器给出的绝对下标去 content 里
            //    截「出错点附近 ±60 字符」的窗口，而在传入前改写 content（哪怕只是插空格）都会让下标错位。
            //    那个窗口一旦指错地方，自修正就变成瞎猜 —— ReactJsonCodec 的类注释里正记着这样一条实测疤
            //    （窗口里没有一处看着是错的，模型第 3 轮一字不差重出同一份，一路撞到定点检测才止损）。
            //    要正确修，只能让 ReactJsonCodec 自己在窗口两侧加定界符，那会把统一包裹组件的
            //    生产调用点从 7 变成 8、撞方案写死的判据；权衡之后选择<b>不动这条脆弱路径</b>，如实登记。
            //    残余风险的量级：被回显的是模型<b>自己上一轮</b>的输出，攻击者要先把注入串塞进需求、
            //    再指望模型把它抄进 DSL、还要指望解析错误正好落在那附近 —— 三跳，且收益不高于直接写在需求里。
            //
            //    ⚠️ 上面那句「不改写 content」同样管住了 quoteBarePlaceholders：它只作用于送去<b>解析</b>的
            //    副本，回灌窗口仍旧拿原始 content 去截。代价是补过引号之后下标会差 2×占位符数 ——
            //    而这一支只在「补了引号还是解析不了」时才走到，此时另有错因，窗口本就是近似的
            //    （content 与 extracted.json() 在模型加围栏时也已经不同下标）。
            history.add(AiChatReqDTO.Message.of("user",
                    ReactJsonCodec.parseFailureHint(content, ex, DSL_FORMAT_SPEC,
                            correctionFor(diagnosis))));
            return ParseOutcome.failed("DSL 不是合法 JSON：" + ex.getMessage() + "。" + diagnosis);
        }
    }

    /**
     * 按 diagnose 的结论给出<b>这一种</b>故障才管用的纠正指令
     *
     * <p>两种故障要让模型做的事正好相反，所以不能用同一句「请重新输出合法 JSON」糊过去：
     * 截断时模型写的东西是对的、只是没说完，要它<b>写短一点</b>；
     * 括号数错时才要它<b>重数嵌套层级</b>。
     */
    private String correctionFor(String diagnosis) {
        if (diagnosis.startsWith("completionTokens") && diagnosis.contains("已顶到")) {
            return "🔴 注意：你上一轮<b>不是写错了，是没写完</b> —— 输出被长度上限截断了，"
                    + "所以别再原样重出一份同样长的。这一轮请产出一份<b>更紧凑</b>的 DSL："
                    + "name/description 各写一句话就够、删掉对需求非必需的节点、"
                    + "不要用缩进和换行排版（整份 JSON 挤在一行里最省长度）。";
        }
        return "🔴 你的括号没配平。请重新数一遍嵌套层级，尤其是<b>多层嵌套的对象/数组收尾</b>："
                + "形如 \"args\":{\"model\":{…,\"columns\":[{…},{…}]}} 这里，"
                + "数组的 ] 之后还要有几层 } 才轮到下一个键，把 ] 和 } 写混就会少一个。";
    }

    /**
     * 一轮解析的结果
     *
     * <p>🔴 为什么失败时要把 {@code feedback} 一起带出来，而不是只返回 null：
     * 轮次耗尽时抛出的异常是用户唯一能看到的东西（它进 {@code agent_message.error_msg}，界面直接显示）。
     * 只返回 null 的话 {@code lastFeedback} 永远是空，异常就只剩「模型在 N 轮内未能产出结构合法的
     * Workflow DSL」这句没有信息量的话 —— 到底是截断还是括号数错、错在第几个字符，
     * 全都留在后端日志里。实测疤：一次建表编排连错 6 轮，界面上只有那句废话，
     * 真因（few-shot 样例把 datamodel_create 的 args 形状教错了）是翻了几万行日志才找到的。
     *
     * @param def      解析成功的 DSL；失败时为 null
     * @param feedback 失败原因，要能直接给人看（含 diagnose 的结论）；成功时为 null
     */
    private record ParseOutcome(WorkflowDefDTO def, String feedback) {

        static ParseOutcome ok(WorkflowDefDTO def) {
            return new ParseOutcome(def, null);
        }

        static ParseOutcome failed(String feedback) {
            return new ParseOutcome(null, feedback);
        }
    }

    // ==================== 校验 ====================

    /**
     * 双层校验：基础层（收集式）一次列全常见结构错；基础通过后再跑编译器干跑探深层错
     *
     * <p>🔴 只在基础通过时才干跑：基础结构都不对时，{@code buildStateGraph} 大概率会在同一处 fail-fast，
     * 先让模型把基础错一次改完，再暴露深错，比一上来就给它一条深层异常更省轮次。
     *
     * <p>🔴 V16（污点入写工具）<b>不受</b>上面那道门约束，且刻意在这里补调一次（台账 C9）：
     * 它的判据是纯静态溯源，不打接口、不跑干跑，与 {@code AgentWorkflowDefServiceImpl#doValidateDsl}
     * 里那一条同一口径（那边的注释写的正是「溯源是纯静态的、不打接口，故不受 basic.isPassed() 约束」）。
     * 传 null 清单不是「放弃这条」—— {@code foldUntrustedWrites} 会自己追一遍。
     * 改之前这条路径上 V16 一句话都不说，而 {@code toFeedback()} 是把 warning 一并回灌给模型的，
     * 于是模型在 6 轮里从没听说过「你这份草稿是远程改数据的形状」，用户也要等到发布闸门才第一次看见。
     */
    private AgentValidateResult validateTwoLayer(WorkflowDefDTO def, AgentExecutionContext context) {
        AgentValidateResult merged = new AgentValidateResult();
        AgentValidateResult basic = dslValidator.validate(def);
        basic.getErrors().forEach(merged::addError);
        basic.getWarnings().forEach(merged::addWarning);
        if (basic.isPassed()) {
            AgentValidateResult deep = compiler.dryRunValidate(def, context);
            deep.getErrors().forEach(merged::addError);
            deep.getWarnings().forEach(merged::addWarning);
        }
        dslValidator.foldUntrustedWrites(def, null, merged);
        return merged;
    }

    // ==================== 产出草稿 ====================

    private WorkflowDraftBO buildDraft(WorkflowDefDTO def, AgentValidateResult result, int round) {
        String code = generateCode();
        // 🔴 服务端生成唯一 code 覆盖模型占位 id：模型给的 id 可能重复/含保留字/不唯一，一律不信任
        def.setId(code);
        String name = StrUtil.blankToDefault(def.getName(), code);
        def.setName(name);

        String dsl;
        try {
            dsl = objectMapper.writeValueAsString(def);
        } catch (Exception ex) {
            // 能解析出来却序列化不回去，属引擎自身问题，按生成失败 fail-close（不落一份坏 DSL）
            throw exception(WORKFLOW_PLAN_DSL_GENERATION_FAILED, "DSL 序列化失败：" + ex.getMessage());
        }

        WorkflowDraftBO draft = new WorkflowDraftBO();
        draft.setCode(code);
        draft.setDsl(dsl);
        // 🔴 【B2-2】name / summary 在这里<b>刻意不过 OutputModerator</b>，尽管它们是模型产出并解析的文字。
        //    《迭代七-03》§3.5 口径 A 第 4 项原本建议「在 :305-306 单点收口，一次盖住三条通道」——
        //    实测推翻：这个 BO 的 name <b>不只是展示值</b>，它同时是两个落库点的入参
        //    （AgentWorkflowPlannerController#planWorkflow 与 AgentAssistantServiceImpl#emitWorkflowDraft
        //    都调 defService.createDef(code, draft.getName(), dsl, …)）。
        //    在这里审等于把「⚠️【疑似注入】」前缀永久写进 workflow_def.name，
        //    而读出来展示时（getWorkflowDraft / planWorkflow 的 RespVO）还要再审一次 ——
        //    moderateString 刻意不幂等，于是叠成两个前缀。
        //    故审核一律落在<b>投递边界</b>：三个展示出口各自审，落库出口一律拿原文。
        //    与「闸门永远读原始 manifest」是同一条规则。
        draft.setName(name);
        draft.setSummary(appendActualPublishMenu(StrUtil.blankToDefault(def.getDescription(), name), def));
        draft.setWarnings(new ArrayList<>(result.getWarnings()));
        draft.setRounds(round);
        log.info("[buildDraft][生成 Workflow DSL 草稿 code({}) name({}) 轮次({}) 警告({})]",
                code, name, round, result.getWarnings().size());
        return draft;
    }

    /**
     * 摘要末尾补一句「实际发布到目录：X」—— <b>当且仅当</b>模型写的散文里没提到 X
     *
     * <h3>🔴 实测疤（不是推演，两次实跑都是这个形状）：卡片标题与它自己要发布的目录对不上</h3>
     * 用户说「发布到『行政管理』菜单下面」，而平台没有这个名字的启用目录，V27
     * （{@code WorkflowDslValidator#validatePublishParentMenu}）把 {@code parentMenuName} 逼成了
     * 「数据管理」。模型改了那一格、也改了 report 节点的话术，<b>却没改顶层 {@code description}</b> ——
     * 而 {@code description} 正是这张卡的摘要原文（上面 {@code draft.setSummary} 那行）。
     * 于是用户读到「…并发布至行政管理菜单」、点了确认，表单落在数据管理下面。
     * 摘要与清单（{@code /workflow-draft} 的 manifest）说的不是一件事，而摘要是标题、更显眼。
     *
     * <h3>🔴 为什么<b>不</b>用校验规则去逼模型把散文改对（量过的，不是拍脑袋）</h3>
     * 库里 16 条含 {@code form_publish_bundle} 且 {@code parentMenuName} 是字面量的 def，
     * 有 <b>15 条</b>的散文里不含那个目录名 —— 其中 <b>13 条只是没提</b>目录
     * （「按步骤查询复用表、选型控件、建表、存草稿并体检，最后等待人工确认发布」），
     * 只有 <b>2 条真的提了另一个</b>。所以「散文必须提到实际目录」这条判据会把那 13 条一起打成违规，
     * 而 error 级还会经发布闸门拦下其中 10 条 PUBLISHED 的再次发布 —— 正是 V20 javadoc 点名的
     * 那条向后兼容红线（「报它等于让存量流程集体亮黄」）。
     * 收窄到「散文提了<b>另一个</b>目录才报」就得从中文散文里把目录名抠出来，
     * 误报面收敛不住（宁可漏报也不误报），而一条会误拦发布的规则比它要防的那句话坏得多。
     *
     * <p>故这里反过来做：<b>不判散文对错，只把权威事实补上去</b>。补完两种形状都自洽 ——
     * 散文没提目录 ⟹ 标题多出一条本来就该有的信息（表单挂哪儿是「发布」这个动作最要命的后果）；
     * 散文提了另一个目录 ⟹ 标题当场把矛盾摆给用户看，而不是让他事后在菜单里找不到表单。
     *
     * <p>⚠️ 落库的 {@code workflow_def.description} 仍是模型原文，本方法<b>不改 DSL</b>：
     * 那一列当下压根没被规划路径写（{@code createDef} 不收 description），
     * 列表页读出来是空的，所以摘要这条通道就是这句话唯一的用户可见面。
     *
     * @param summary 模型散文（{@code description}，为空时回落成 name），非 null
     */
    private static String appendActualPublishMenu(String summary, WorkflowDefDTO def) {
        List<NodeDTO> nodes = def.getNodes();
        if (nodes == null) {
            return summary;
        }
        List<String> unmentioned = new ArrayList<>();
        for (NodeDTO node : nodes) {
            if (node == null || !"tool".equals(node.getType())
                    || !FormPublishBundleTool.NAME.equals(node.configStr("toolId"))) {
                continue;
            }
            Map<String, Object> args = node.configMap("args");
            Object raw = args == null ? null : args.get(FormPublishBundleTool.PARAM_PARENT_MENU_NAME);
            // 占位符这一格运行期才替换，此刻无从知道它会解析成哪个目录，故不补
            // （谓词与 V27 读同一格时用的那个逐字一致：这一格<b>会</b>被替换，所以要求成对花括号）
            if (!(raw instanceof String menuName) || StrUtil.isBlank(menuName)
                    || StateTemplateResolver.containsPlaceholder(menuName)) {
                continue;
            }
            String dir = menuName.trim();
            if (!summary.contains(dir) && !unmentioned.contains(dir)) {
                unmentioned.add(dir);
            }
        }
        return unmentioned.isEmpty() ? summary
                : summary + "（实际发布到目录：" + String.join("、", unmentioned) + "）";
    }

    /** 生成唯一 code：{@code wf_plan_<时间戳base36>_<序号base36>}，匹配 ^[a-z][a-z0-9_]{0,62}$ */
    private String generateCode() {
        return "wf_plan_" + Long.toString(System.currentTimeMillis(), 36)
                + "_" + Long.toString(CODE_SEQ.incrementAndGet() % 46656, 36);
    }

    // ==================== 调模型 + 计量 ====================

    /**
     * 调一次模型；失败返回 {@code resp == null} 的结果，并把失败原文带回去（由调用方回灌重试）
     *
     * <p>每轮单独计量，scene={@code PLAN_DSL}——一次生成可能多轮，只记一条会让账对不上（方案 §8.3）。
     *
     * <p>🔴 失败原文同时进 {@code agent_usage_log.error_msg}：这就是「哪个模型在哪一刻额度耗尽」
     * 的落库凭据。刻意<b>不加新列</b> —— ai 模块的 {@code MODEL_QUOTA_EXHAUSTED} 消息里已经带着
     * 「模型编号/厂商标识 + 厂商原文」，现成的 {@code error_msg} 装得下；
     * 而本模块已经有一根死列的前车之鉴（{@code failed_at_node} 存在但全仓无人读写）。
     */
    private CallResult callModel(String systemPrompt, List<AiChatReqDTO.Message> history,
                                 AgentExecutionContext context, String question, int round, String taskKey) {
        AiChatReqDTO reqDTO = new AiChatReqDTO();
        // 🔴 不指定 modelId：用哪个模型一律由 AI 模型配置列表决定 ——
        //    启用状态为「开启」的模型里按排序值取最前的那个（AiChatMapper#selectListByStatusAndType
        //    就是 sort 升序 + id 升序，链首即默认），厂商拒绝或额度耗尽时沿候选链往后切。
        //    原先这里有个 enlev.agent.plan.planner-model-id 的「钉模型」口子，已删除：
        //    两处都能回答「哪个模型更强」时，管理员调了一处、另一处没跟上，
        //    表现出来就是「明明换了强模型还是不稳」，排查方向直接被带偏。
        //    配合下面的 taskKey，一次编排的各轮都粘在首次成功的那个模型上，
        //    不会出现前半程一个模型、后半程另一个（效果没法归因、也没法和上一轮比）。
        reqDTO.setTaskKey(taskKey);
        reqDTO.setSystemPrompt(systemPrompt);
        reqDTO.setMessages(history);
        // 生成要的是稳定而不是创意，温度给低
        reqDTO.setTemperature(0.1);
        // 🔴 必须显式给 maxTokens：一份含多节点的 DSL JSON 很容易顶破模型自身的小上限而被截断（线性 planner 的同一条实测疤）
        reqDTO.setMaxTokens(planProperties.getMaxTokens());
        // 关思考，让上面那个 maxTokens 预算全部留给 DSL 正文。
        // 思考模式下思考另有 budget、且 completion_tokens = 思考 + 回复，实测一轮编排里
        // 出现 12315 > maxTokens=8000 —— 正文被思考挤掉多少，从回传的数上根本看不出来。
        // 这也是「模型在轮次上限内产不出合法 JSON」那条缺陷的第一个杠杆。
        reqDTO.setEnableThinking(false);

        LocalDateTime begin = LocalDateTime.now();
        AiChatRespDTO resp;
        try {
            resp = aiModelApi.chat(reqDTO);
        } catch (Exception ex) {
            String error = StrUtil.blankToDefault(ex.getMessage(), ex.getClass().getName());
            recordUsage(context, question, begin, null, "FAILURE", error);
            log.warn("[callModel][第({})轮调用失败：{}]", round, error);
            return new CallResult(null, error);
        }
        recordUsage(context, question, begin, resp, "SUCCESS", null);
        return new CallResult(resp, null);
    }

    /**
     * 一次模型调用的结果：成功带 {@code resp}，失败带 {@code error} 原文
     *
     * <p>收成 record 而不是继续用「失败返回 null」：null 把失败原因丢在了 {@code callModel} 内部，
     * 调用方无从得知，于是轮次耗尽时只能报一句与真因无关的「未能产出结构合法的 DSL」——
     * 额度耗尽时每一轮都调不通，那句提示会把人支去调 prompt 和 maxTokens，方向整个是反的。
     */
    private record CallResult(AiChatRespDTO resp, String error) {
    }

    private void recordUsage(AgentExecutionContext context, String question, LocalDateTime begin,
                             AiChatRespDTO resp, String status, String errorMsg) {
        boolean fromProvider = resp != null && resp.getUsage() != null;
        AiChatRespDTO.Usage usage = fromProvider ? resp.getUsage() : null;
        boolean reachedProvider = resp != null;
        int prompt = 0;
        int completion = 0;
        int total = 0;
        if (reachedProvider) {
            prompt = fromProvider && usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
            completion = fromProvider && usage.getCompletionTokens() != null
                    ? usage.getCompletionTokens()
                    : AgentTokenEstimator.estimate(resp.getContent());
            total = fromProvider && usage.getTotalTokens() != null
                    ? usage.getTotalTokens() : prompt + completion;
        }
        usageLogService.record(AgentUsageLogService.UsageRecord.builder()
                .userId(context.getUserId())
                .tenantId(context.getTenantId())
                // 🔴 这一行原先是缺的，于是 agent_usage_log 里 scene=PLAN_DSL 的行 model_id 全为 NULL，
                //    而 CHAT（AgentChatToolLoop）与 INTENT（AgentIntentClassifier）两条路都记了 ——
                //    只有编排这一路查不出「到底落在哪个候选模型上」。
                //    这不是记账瑕疵而是排障盲点：编排不指定 modelId，走的是 failover 候选链，
                //    链头会因冷却而变，事后想知道「那 6 轮是同一个模型连错、还是每轮换了个模型」，
                //    唯一的证据就是 model_id。modelName 顶不上：不同 AiModelDO 行可以指向同一个厂商型号。
                .modelId(resp == null ? null : resp.getModelId())
                .platform(resp == null ? null : resp.getPlatform())
                .modelName(resp == null ? null : resp.getModel())
                .scene(AgentUsageSceneEnum.PLAN_DSL)
                .promptTokens(prompt)
                .completionTokens(completion)
                .totalTokens(total)
                .tokenSource(fromProvider ? "PROVIDER" : "ESTIMATED")
                .inputText(question)
                .beginTime(begin)
                .endTime(LocalDateTime.now())
                .status(status)
                .errorMsg(errorMsg)
                .build());
    }

    /**
     * 报一句进度（包 try-catch：回调可能往已关闭的 SSE 写，那个异常与生成无关，绝不能让生成白跑）
     */
    private void progress(ProgressListener listener, String text) {
        if (listener == null) {
            return;
        }
        try {
            listener.onProgress(text);
        } catch (Exception ex) {
            log.debug("[progress][进度回调失败，忽略：{}]", ex.getMessage());
        }
    }

}
