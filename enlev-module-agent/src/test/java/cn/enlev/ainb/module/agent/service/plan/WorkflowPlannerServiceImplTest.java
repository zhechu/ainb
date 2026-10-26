package cn.enlev.ainb.module.agent.service.plan;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.module.agent.enums.AgentUsageSceneEnum;
import cn.enlev.ainb.module.agent.framework.agent.config.AgentPlanProperties;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslParser;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslToStateGraphCompiler;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.WorkflowContext;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutorRegistry;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.framework.agent.core.plan.WorkflowDslPlannerPrompt;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolRegistry;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.AgentValidateResult;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.WorkflowDslValidator;
import cn.enlev.ainb.module.agent.service.tooldef.AgentToolDefService;
import cn.enlev.ainb.module.agent.service.usage.AgentUsageLogService;
import cn.enlev.ainb.module.ai.api.model.AiModelApi;
import cn.enlev.ainb.module.ai.api.model.dto.AiChatReqDTO;
import cn.enlev.ainb.module.ai.api.model.dto.AiChatRespDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_PLAN_DSL_GENERATION_FAILED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WorkflowPlannerServiceImpl} 单测（增量7a，T3）
 *
 * <p>🔴 <b>全 mock {@link AiModelApi}，0 token</b>：用预置的模型回复钉死「生成-校验-自修正」循环的
 * 每条分支（首轮即对 / JSON 畸形回灌 / 结构非法回灌 / 轮次耗尽 / 计量 scene）。真实 LLM 只在最后一次 e2e 跑。
 *
 * <p>{@link DslParser} 与 {@link WorkflowDslValidator} 用<b>真</b>的（它们是本循环的核心、且 0 token），
 * {@link DslToStateGraphCompiler#dryRunValidate} 用 mock（深层校验另有 T2 专测，这里只关心循环编排）。
 *
 * @author 走进新时代
 */
class WorkflowPlannerServiceImplTest {

    private WorkflowPlannerServiceImpl service;
    private AiModelApi aiModelApi;
    private DslToStateGraphCompiler compiler;
    private AgentUsageLogService usageLogService;
    private AgentPlanProperties properties;
    /**
     * 提成字段是为了 {@link #planToWorkflow_aiDraftedWebhookWriteFlow_reachesTheModelAsFeedback}（台账 C9）：
     * 那条用例要在「一律只读」的默认桩之上再补一个<b>写</b>工具。
     * ⚠️ 默认桩不能改成「一律写」—— 预置的 {@link #VALID_DSL} 靠 {@code data_interface_list} 是只读才不被
     * V3/V16 说事，改了它，「首轮即合法」那几条会因为另一个原因变红（测 A 却因为 B 红）。
     */
    private AgentToolRegistry toolRegistry;

    /** 一份结构合法的最小 DSL（START→tool→END），作为「模型最终产对了」的预置回复 */
    private static final String VALID_DSL = """
            {"dslVersion":"1.0","id":"placeholder_id","name":"查询数据接口","description":"按关键词列出数据接口",
             "variables":[{"key":"input","type":"object"},{"key":"listOut","type":"object"}],
             "nodes":[{"id":"list","type":"tool","name":"查数据接口",
                       "config":{"toolId":"data_interface_list","args":{"keyword":"test_demo","limit":5},"outputVar":"listOut"}}],
             "edges":[{"from":"START","to":"list"},{"from":"list","to":"END"}]}
            """;

    /** 预置回复里的模型编号，用来钉「PLAN_DSL 的计量行必须记下 model_id」 */
    private static final Long MODEL_ID = 69L;

    /**
     * 🔴 实测那份坏 DSL 的最小合法形态：args 里的占位符<b>裸写在字符串外</b>。
     * 与 {@code ReactJsonCodecTest#BARE_PLACEHOLDER} 是同一次实测产出的两种复现
     * （那份原文里 form_inspect 与 form_publish_bundle 两处都这么写）。
     *
     * <p>除引号之外处处合法，且引用的 {@code input} 已在 variables 里声明 ——
     * 为的是让「补引号」成为通过与否的<b>唯一</b>变量。
     */
    private static final String BARE_PLACEHOLDER_DSL = """
            {"dslVersion":"1.0","id":"placeholder_id","name":"查数据接口","description":"按关键词列出数据接口",
             "variables":[{"key":"input","type":"object"},{"key":"listOut","type":"object"}],
             "nodes":[{"id":"list","type":"tool","name":"查数据接口",
                       "config":{"toolId":"data_interface_list","args":{"keyword":{{$.input.keyword}},"limit":5},"outputVar":"listOut"}}],
             "edges":[{"from":"START","to":"list"},{"from":"list","to":"END"}]}
            """;

    private static class StubExecutor implements NodeExecutor {
        private final String type;

        StubExecutor(String type) {
            this.type = type;
        }

        @Override
        public String getType() {
            return type;
        }

        @Override
        public Map<String, Object> execute(NodeDTO node, WorkflowContext ctx) {
            return Map.of();
        }
    }

    /** 桩一个「存在、启用、只读」的内置工具定义：声明与生效风险级都给，免得兜底成 HIGH 被当写工具 */
    private static AgentToolRegistry.ToolDefinition readOnlyTool(String name) {
        AgentToolRegistry.ToolDefinition def = mock(AgentToolRegistry.ToolDefinition.class);
        when(def.getName()).thenReturn(name);
        when(def.getDisplayName()).thenReturn(name);
        when(def.isEnabled()).thenReturn(true);
        when(def.getDeclaredRisk()).thenReturn(RiskLevel.READ_ONLY);
        when(def.getRisk()).thenReturn(RiskLevel.READ_ONLY);
        when(def.isRequireConfirm()).thenReturn(false);
        return def;
    }

    /**
     * 同上，但<b>声明</b>风险级是 HIGH —— V16 只认声明值（{@code declaredRiskOf}）、且只放过 READ_ONLY，
     * 故这一个字段就足以让它把「外部数据 → 写工具入参」判成一处没人过目的流向。
     *
     * <p>🔴 刻意<b>不</b>用 IRREVERSIBLE：V11（{@code validateTriggerRiskGates}）对 IRREVERSIBLE 给的是
     * <b>error</b>，于是「无头触发 + 不可逆工具 + 上游没闸口」的 DSL 压根出不了草稿，
     * 「过的时候带不带那句警告」这条用例就无从测起。HIGH 在 V11 那里只落成一条 warning，
     * 草稿照样产出。代价是 {@code draft.getWarnings()} 里有两条（V11 + V16），
     * 故断言按内容挑、不按下标 —— 见 {@code planToWorkflow_aiDraftedWebhookWriteFlow_carriesTheTaintWarning}。
     *
     * <p>⚠️ 别为了「只剩一条警告」去 DSL 里补 {@code hitl.bpm_approval}：V7 要查那个流程部署了没有，
     * 而本测试没给它打桩 —— 换来的是另一条 error，比多一条 warning 更糟。
     */
    private static AgentToolRegistry.ToolDefinition writeTool(String name) {
        AgentToolRegistry.ToolDefinition def = mock(AgentToolRegistry.ToolDefinition.class);
        when(def.getName()).thenReturn(name);
        when(def.getDisplayName()).thenReturn(name);
        when(def.isEnabled()).thenReturn(true);
        when(def.getDeclaredRisk()).thenReturn(RiskLevel.HIGH);
        when(def.getRisk()).thenReturn(RiskLevel.HIGH);
        when(def.isRequireConfirm()).thenReturn(true);
        return def;
    }

    @BeforeEach
    void setUp() {
        aiModelApi = mock(AiModelApi.class);
        compiler = mock(DslToStateGraphCompiler.class);
        usageLogService = mock(AgentUsageLogService.class);
        WorkflowDslPlannerPrompt prompt = mock(WorkflowDslPlannerPrompt.class);
        when(prompt.buildSystemPrompt(anyInt())).thenReturn("SYSTEM_PROMPT");
        // 深层校验默认放行（T2 专测干跑本身）
        when(compiler.dryRunValidate(any(), any())).thenReturn(new AgentValidateResult());

        DslParser parser = new DslParser();
        ObjectMapper mapper = new ObjectMapper();
        ReflectionTestUtils.setField(parser, "objectMapper", mapper);

        NodeExecutorRegistry registry = new NodeExecutorRegistry();
        registry.setExecutors(List.of(new StubExecutor("tool"), new StubExecutor("llm")));
        WorkflowDslValidator validator = new WorkflowDslValidator();
        ReflectionTestUtils.setField(validator, "nodeExecutorRegistry", registry);
        // 校验器还要查工具注册表（V1 存在 / V2 启用 / V3 agent_loop 只读）。
        // 一律答「存在、启用、只读」：本测试考的是「生成-校验-自修正」的循环编排，
        // 不是工具解析 —— 不桩的话，预置 DSL 里的 data_interface_list 会被判「工具不存在」，
        // 于是「首轮即合法」那条用例变成结构非法（测 A 却因为 B 变红）。
        AgentToolRegistry toolRegistry = mock(AgentToolRegistry.class);
        when(toolRegistry.get(anyString())).thenAnswer(inv -> readOnlyTool(inv.getArgument(0)));
        ReflectionTestUtils.setField(validator, "toolRegistry", toolRegistry);
        this.toolRegistry = toolRegistry;
        ReflectionTestUtils.setField(validator, "toolDefService", mock(AgentToolDefService.class));

        properties = new AgentPlanProperties();
        properties.setAsWorkflowMaxRounds(6);
        properties.setMaxTokens(8000);

        service = new WorkflowPlannerServiceImpl();
        ReflectionTestUtils.setField(service, "aiModelApi", aiModelApi);
        ReflectionTestUtils.setField(service, "plannerPrompt", prompt);
        ReflectionTestUtils.setField(service, "dslParser", parser);
        ReflectionTestUtils.setField(service, "dslValidator", validator);
        ReflectionTestUtils.setField(service, "compiler", compiler);
        ReflectionTestUtils.setField(service, "usageLogService", usageLogService);
        ReflectionTestUtils.setField(service, "planProperties", properties);
        ReflectionTestUtils.setField(service, "objectMapper", mapper);
    }

    private static AiChatRespDTO resp(String content) {
        AiChatRespDTO r = new AiChatRespDTO();
        r.setContent(content);
        r.setModelId(MODEL_ID);
        r.setModel("test-model");
        r.setPlatform("Test");
        r.setUsage(AiChatRespDTO.Usage.of(100, 200, 300));
        return r;
    }

    private AgentExecutionContext ctx() {
        return AgentExecutionContext.ofForTest(1L, 1L);
    }

    // ==================== ① 首轮即合法 ====================

    @Test
    void planToWorkflow_firstRoundValid_returnsDraft() {
        when(aiModelApi.chat(any())).thenReturn(resp(VALID_DSL));

        WorkflowDraftBO draft = service.planToWorkflow("列出 test_demo 数据接口", ctx(), t -> {
        });

        assertNotNull(draft);
        verify(aiModelApi, times(1)).chat(any());
        // code 服务端生成、唯一、匹配命名规范，且已注入 dsl 的 id
        assertTrue(draft.getCode().startsWith("wf_plan_"), draft.getCode());
        assertTrue(draft.getCode().matches("^[a-z][a-z0-9_]{0,62}$"), draft.getCode());
        assertTrue(draft.getDsl().contains("\"id\":\"" + draft.getCode() + "\""),
                "服务端 code 应覆盖模型占位 id：" + draft.getDsl());
        assertEquals(1, draft.getRounds());
        // 模型占位 id 不应残留
        assertTrue(!draft.getCode().equals("placeholder_id"));
    }

    /**
     * 🔴 裸占位符必须<b>当轮就修掉</b>，不许烧一轮自修正
     *
     * <p>这条钉的不是「解析成功」，而是 {@code rounds == 1}：补引号是确定性修复，
     * 走回灌的话模型收到的是「括号没配平、请重数嵌套层级」—— 与真实错因毫不相干，
     * 实测它一字不差重出同一份，一路撞到定点检测才止损，用户看到「轮次耗尽」。
     * 断言轮数才能钉住「修掉了」而不是「碰巧某一轮模型自己写对了」。
     */
    @Test
    void planToWorkflow_barePlaceholderInArgs_fixedWithoutExtraRound() {
        when(aiModelApi.chat(any())).thenReturn(resp(BARE_PLACEHOLDER_DSL));

        WorkflowDraftBO draft = service.planToWorkflow("按关键词列出数据接口", ctx(), t -> {
        });

        assertNotNull(draft, "补引号后应当首轮即通过");
        verify(aiModelApi, times(1)).chat(any());
        assertEquals(1, draft.getRounds());
        assertTrue(draft.getDsl().contains("\"keyword\":\"{{$.input.keyword}}\""),
                "占位符要原样保留成字符串值，交给运行期 StateTemplateResolver 替换：" + draft.getDsl());
    }

    // ==================== ② 首轮 JSON 畸形 → 回灌 → 次轮合法 ====================

    @Test
    void planToWorkflow_malformedJsonThenValid_selfCorrects() {
        when(aiModelApi.chat(any()))
                .thenReturn(resp("{ 这不是合法的 JSON "))
                .thenReturn(resp(VALID_DSL));

        WorkflowDraftBO draft = service.planToWorkflow("列出 test_demo 数据接口", ctx(), t -> {
        });

        assertNotNull(draft);
        verify(aiModelApi, times(2)).chat(any());
        assertEquals(2, draft.getRounds());

        // 第 2 轮请求的 history 里应含「解析失败回灌」提示
        ArgumentCaptor<AiChatReqDTO> captor = ArgumentCaptor.forClass(AiChatReqDTO.class);
        verify(aiModelApi, times(2)).chat(captor.capture());
        List<AiChatReqDTO> sent = captor.getAllValues();
        List<AiChatReqDTO.Message> secondCallMessages = sent.get(1).getMessages();
        assertTrue(secondCallMessages.stream()
                        .anyMatch(m -> m.getContent() != null && m.getContent().contains("不是合法的 JSON")),
                "次轮应回灌解析失败提示");

        // 🔴 一次规划 = 一个任务：含自修正回灌的所有轮次必须带**同一个** taskKey（裁定 R-01 / 缺陷 D4-28）。
        //    守的失效形态是静默的：把 AgentTaskKeys.newPlanRun() 从 planToWorkflow 入口挪进 callModel，
        //    每轮各取一个新键 ⟹ 粘滞一次都不生效、不报错、上面那些回灌断言照样全绿，
        //    而第 1 轮额度耗尽后第 2 轮就会换到另一个模型上接着自修正 —— 前后半程不是同一个模型。
        assertNotNull(sent.get(0).getTaskKey(), "taskKey 必须非空，空 = 不做粘滞");
        assertEquals(sent.get(0).getTaskKey(), sent.get(1).getTaskKey(), "同一次规划的两轮必须共用一个 taskKey");
        assertTrue(sent.get(0).getTaskKey().startsWith("PLAN:"), "键形如 PLAN:<uuid>");
    }

    // ==================== ③ 首轮结构非法 → 回灌 toFeedback → 次轮合法 ====================

    @Test
    void planToWorkflow_structurallyInvalidThenValid_selfCorrects() {
        // 能解析成 JSON，但结构非法：边指向不存在的节点 + 无 END
        String badDsl = """
                {"id":"wf","nodes":[{"id":"a","type":"tool","config":{"toolId":"x"}}],
                 "edges":[{"from":"START","to":"ghost"}]}
                """;
        when(aiModelApi.chat(any()))
                .thenReturn(resp(badDsl))
                .thenReturn(resp(VALID_DSL));

        WorkflowDraftBO draft = service.planToWorkflow("列出 test_demo 数据接口", ctx(), t -> {
        });

        assertNotNull(draft);
        verify(aiModelApi, times(2)).chat(any());
        assertEquals(2, draft.getRounds());

        ArgumentCaptor<AiChatReqDTO> captor = ArgumentCaptor.forClass(AiChatReqDTO.class);
        verify(aiModelApi, times(2)).chat(captor.capture());
        List<AiChatReqDTO.Message> secondCallMessages = captor.getAllValues().get(1).getMessages();
        assertTrue(secondCallMessages.stream()
                        .anyMatch(m -> m.getContent() != null && m.getContent().contains("校验未通过")),
                "次轮应回灌结构校验反馈");
    }

    // ==================== ④ 轮次耗尽 / 原地打转 → fail-close 抛错 ====================

    /**
     * 🔴 两条分支<b>必须</b>分开测，因为它们的成因与处方完全不同：
     * <ul>
     *   <li>「轮次耗尽」= 每轮都在改，只是没改对 → 处方是换更强的模型 / 修提示词；</li>
     *   <li>「原地打转」= 连续两轮一字不差 → 回灌压根没被采纳，再给轮次也是白烧钱。</li>
     * </ul>
     * 定点检测（{@code content.equals(previousRejected)}）排在解析<b>之前</b>，
     * 所以「每轮返回同一份畸形 JSON」的夹具打到的是<b>打转</b>那条 ——
     * 本文件原先那两个 {@code roundsExhausted_*} 用例正是这个形状，名字与行为不符，
     * 而名字错了的用例会让人以为耗尽路径已被覆盖（其实一行都没跑到）。
     */
    @Test
    void planToWorkflow_roundsExhausted_throws() {
        properties.setAsWorkflowMaxRounds(2);
        // 🔴 每轮内容必须<b>不同</b>，否则先撞上定点检测、根本到不了耗尽那一抛
        when(aiModelApi.chat(any())).thenReturn(resp("{ 畸形 A "), resp("{ 畸形 B "));

        ServiceException ex = assertThrows(ServiceException.class, () ->
                service.planToWorkflow("列出 test_demo 数据接口", ctx(), t -> {
                }));
        assertEquals(WORKFLOW_PLAN_DSL_GENERATION_FAILED.getCode(), ex.getCode());
        verify(aiModelApi, times(2)).chat(any());
    }

    /**
     * 🔴 轮次耗尽那句话必须点名<b>此刻在用哪个模型</b>，而且点的是<b>模型标识</b>不是自增号
     *
     * <p>实测疤：候选链 11 个，链首两个接连被厂商 400 掉
     * （一个报「url error」、一个报「enable_thinking 只允许 True」），四轮全部落在
     * {@code qwen3-vl-flash} —— 一个<b>视觉</b>模型 —— 上产 DSL JSON。抛出去的话只说
     * 「去换个更强的模型」，却没说该从谁手里把活儿接走，
     * 管理员只能把整条链启停一遍碰运气。
     *
     * <p>{@code model_id} 在 {@code agent_usage_log} 里查得到，但那是事后翻库；
     * 用户当场看得到的只有这句异常消息（它经 {@code agent_message.error_msg} 直接上界面）。
     *
     * <p>🔴 编号不许出现在这句话里：它给的处置是「去 AI 模型配置改排序/停用」，
     * 而那个列表页上的四列是模型名字、模型标识、API 秘钥、排序 —— 一个数据库自增号
     * 对不上其中任何一列，人还得先想办法把 69 翻译成型号，等于在已经撞上一次编排失败之后
     * 又加一道排查工序。编号属于追溯（failover 的 warn 日志与 agent_usage_log 都留着它）。
     */
    @Test
    void planToWorkflow_roundsExhausted_messageNamesServingModel() {
        properties.setAsWorkflowMaxRounds(2);
        when(aiModelApi.chat(any())).thenReturn(resp("{ 畸形 A "), resp("{ 畸形 B "));

        ServiceException ex = assertThrows(ServiceException.class, () ->
                service.planToWorkflow("列出 test_demo 数据接口", ctx(), t -> {
                }));

        assertNamesModelByKeyNotId(ex.getMessage());
    }

    /**
     * 🔴 原地打转那句话给的处方必须<b>当下可执行</b>：指向 AI 模型配置列表，
     * 而不是一个已经不存在的配置项
     *
     * <p>{@code enlev.agent.plan.planner-model-id} 已删除（编排用哪个模型一律由模型配置列表的
     * 「启用状态 + 排序值」决定）。删掉一个配置项时最容易漏的就是<b>异常文案里那句指引</b>：
     * 代码编译得过、测试也全绿，只有真撞上打转的管理员会照着它去 yaml 里找一个
     * 加上去也不生效的键 —— 而「配了没用」比「没这条指引」更难排查。
     */
    @Test
    void planToWorkflow_stalledOnIdenticalDraft_advicePointsAtModelConfig() {
        properties.setAsWorkflowMaxRounds(2);
        // 每轮同一份内容：温度 0.1 下这是硬定点而不是巧合
        when(aiModelApi.chat(any())).thenReturn(resp("{ 永远畸形 "));

        ServiceException ex = assertThrows(ServiceException.class, () ->
                service.planToWorkflow("列出 test_demo 数据接口", ctx(), t -> {
                }));

        assertEquals(WORKFLOW_PLAN_DSL_GENERATION_FAILED.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("完全相同"), "打转与耗尽是两种病，文案要能区分：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("AI 模型配置"),
                "换模型的指引要指向模型配置列表：" + ex.getMessage());
        assertTrue(!ex.getMessage().contains("planner-model-id"),
                "已删除的配置项不许再出现在给用户的指引里：" + ex.getMessage());
        assertNamesModelByKeyNotId(ex.getMessage());
    }

    /**
     * 断言那句话<b>点名了模型标识、且没把数据库自增号塞给用户</b>
     *
     * <p>🔴 两半都得断：只断「含 test-model」的话，把编号加回去（{@code 69/test-model}）
     * 照样通过 —— 而这句话的毛病恰恰是那个编号，不是缺型号。
     * 断言键在完整片段「模型标识：test-model」上，于是任何形式的 id 前缀都会让它变红。
     */
    private static void assertNamesModelByKeyNotId(String message) {
        assertTrue(message.contains("模型标识：test-model"),
                "要点名模型标识，管理员才拿它去 AI 模型配置的「模型标识」列对得上：" + message);
        assertTrue(!message.contains(MODEL_ID + "/"),
                "给用户的处置指引里不许出现数据库自增号（谁看得懂 " + MODEL_ID + " 是哪个模型）：" + message);
    }

    /**
     * 🔴 标识取不到时才许把编号搬出来 —— 那时候它是唯一的把手
     *
     * <p>这条钉的是兜底分支：厂商没回 model 字段时，只写「未知型号」等于什么都没说，
     * 用户连去 AI 模型配置里对哪一行都不知道。也正因如此，上面那条「不许出现编号」
     * 与本条并不矛盾：编号是<b>没有标识时</b>的替身，不是并列信息。
     */
    @Test
    void planToWorkflow_modelKeyMissing_fallsBackToId() {
        properties.setAsWorkflowMaxRounds(1);
        AiChatRespDTO noKey = resp("{ 畸形 ");
        noKey.setModel(null);
        when(aiModelApi.chat(any())).thenReturn(noKey);

        ServiceException ex = assertThrows(ServiceException.class, () ->
                service.planToWorkflow("列出 test_demo 数据接口", ctx(), t -> {
                }));

        assertTrue(ex.getMessage().contains("未知型号（模型编号 " + MODEL_ID + "）"),
                "标识缺失时要退回带编号的说法，否则用户拿不到任何可对账的把手：" + ex.getMessage());
    }

    /**
     * 🔴 一轮都没调通时，那句话<b>不许</b>补「实际产出 DSL 的模型」
     *
     * <p>此时 {@code lastFeedback} 装的已经是厂商原文（额度耗尽 / 密钥没填 / 400），
     * 再补一句「没有模型产出过」只是把真病因盖掉一层 —— 与「不替 error 编病因」同一条纪律。
     * 这条钉的正是空集合那个分支：它一旦被写成「至少报一句『未知模型』」占位，
     * 用户拿到的就是两句互相矛盾的话，而其中一句还是编的。
     */
    @Test
    void planToWorkflow_everyCallFailed_messageCarriesNoModelClaim() {
        properties.setAsWorkflowMaxRounds(2);
        when(aiModelApi.chat(any())).thenThrow(new RuntimeException("400 - 模型额度耗尽"));

        ServiceException ex = assertThrows(ServiceException.class, () ->
                service.planToWorkflow("列出 test_demo 数据接口", ctx(), t -> {
                }));

        assertTrue(ex.getMessage().contains("额度耗尽"), "厂商原文要能到用户手上：" + ex.getMessage());
        assertTrue(!ex.getMessage().contains("实际产出 DSL 的模型"),
                "一轮都没调通就不该声称有模型产出过：" + ex.getMessage());
    }

    // ==================== ⑤ 计量 scene = PLAN_DSL ====================

    @Test
    void planToWorkflow_recordsUsageWithPlanDslScene() {
        when(aiModelApi.chat(any())).thenReturn(resp(VALID_DSL));

        service.planToWorkflow("列出 test_demo 数据接口", ctx(), t -> {
        });

        ArgumentCaptor<AgentUsageLogService.UsageRecord> captor =
                ArgumentCaptor.forClass(AgentUsageLogService.UsageRecord.class);
        verify(usageLogService).record(captor.capture());
        assertEquals(AgentUsageSceneEnum.PLAN_DSL, captor.getValue().getScene());
        assertEquals("SUCCESS", captor.getValue().getStatus());
        // 🔴 钉住 model_id：编排不指定 modelId，走的是 failover 候选链，链头会因冷却而漂移，
        //    「那 N 轮是同一个模型连错、还是每轮换了个模型」只能靠这一列回答。
        //    CHAT 与 INTENT 两条路一直都记了，只有 PLAN_DSL 漏 —— 于是排查编排故障时唯一缺的就是它。
        //    modelName 顶不上：不同的 AiModelDO 行可以指向同一个厂商型号。
        assertEquals(MODEL_ID, captor.getValue().getModelId(),
                "PLAN_DSL 的计量行必须带上 model_id，否则事后查不出编排落在哪个候选模型上");
    }

    // ==================== ⑥ 空需求 → AGENT_MESSAGE_EMPTY ====================

    @Test
    void planToWorkflow_blankQuestion_throws() {
        assertThrows(ServiceException.class, () ->
                service.planToWorkflow("   ", ctx(), t -> {
                }));
    }

    // ==================== ⑦ 台账 C9：AI 自修正回路上的污点追踪 ====================

    /**
     * webhook 触发（= 无头）+ 一个<b>写</b>工具，入参取自请求体。
     * 结构合法，故第一轮就该出草稿 —— 本组要看的不是它能不能过，而是<b>过的时候带不带那句警告</b>。
     *
     * <p>⚠️ 工具键刻意用 {@code generic_write} 这种不在任何专用规则里的名字：用 {@code datamodel_create}
     * 会顺带踩到数据模型那一档的探测（本测试没给 {@code dataModelApi} 打桩），
     * 于是「V16 有没有说话」会和「某个 null 协作者炸没炸」缠在一起，红一次要查两件事。
     */
    private static final String WEBHOOK_WRITE_DSL = """
            {"dslVersion":"1.0","id":"placeholder_id","name":"外部下单建表","description":"webhook 收到什么就建什么表",
             "trigger":{"type":"trigger.webhook","config":{"path":"order_hook","authType":"hmac","secret":"s3cr3t"}},
             "variables":[{"key":"input","type":"object"},{"key":"created","type":"object"}],
             "nodes":[{"id":"act","type":"tool","name":"建表",
                       "config":{"toolId":"generic_write","args":{"tableName":"{{$.input.name}}"},"outputVar":"created"}}],
             "edges":[{"from":"START","to":"act"},{"from":"act","to":"END"}]}
            """;

    /** 同上，但边指向一个不存在的节点 ⟹ 基础层必然有 error，于是 {@code toFeedback()} 会被回灌给模型 */
    private static final String WEBHOOK_WRITE_DSL_BROKEN_EDGE = """
            {"dslVersion":"1.0","id":"placeholder_id","name":"外部下单建表","description":"webhook 收到什么就建什么表",
             "trigger":{"type":"trigger.webhook","config":{"path":"order_hook","authType":"hmac","secret":"s3cr3t"}},
             "variables":[{"key":"input","type":"object"},{"key":"created","type":"object"}],
             "nodes":[{"id":"act","type":"tool","name":"建表",
                       "config":{"toolId":"generic_write","args":{"tableName":"{{$.input.name}}"},"outputVar":"created"}}],
             "edges":[{"from":"START","to":"ghost"}]}
            """;

    /** V16 那句话里最不可能与别的规则撞车的两个片段：不可信源的名字 + 那个入参的名字 */
    private static void assertTaintWarning(String text) {
        assertTrue(text.contains("webhook 请求体"), "该点名不可信源是 webhook 请求体：" + text);
        assertTrue(text.contains("args.tableName"), "该点名那个入参：" + text);
        assertTrue(text.contains("hitl.bpm_approval"), "该给出能照做的出路：" + text);
    }

    /**
     * 把 {@code generic_write} 桩成写工具。
     *
     * <p>🔴 必须先造好再 {@code thenReturn}，不能写成 {@code thenReturn(writeTool(...))}：
     * 后者会在一次 stubbing 还没收尾时又开一次 {@code when(...)}（{@code writeTool} 内部全是 {@code when}），
     * Mockito 直接抛 {@code UnfinishedStubbing}（2026-10-07 第一版就是这么写的，两条用例一起炸）。
     * {@code @BeforeEach} 里那个默认桩用的是 {@code thenAnswer(inv -> readOnlyTool(...))} —— lambda 把求值推迟到
     * 调用时，所以它没踩到；本方法要的是一次性覆盖，用局部变量更直白。
     */
    private void stubWriteTool(String name) {
        AgentToolRegistry.ToolDefinition write = writeTool(name);
        when(toolRegistry.get(name)).thenReturn(write);
    }

    /**
     * 🔴 C9 的行为层判据①：AI 生成的草稿<b>当场</b>带上 V16 的警告
     *
     * <p>改之前这条路径上 V16 一句话都不说：{@code validateTwoLayer} 压根不调 {@code foldUntrustedWrites}，
     * 而 {@code AgentWorkflowDefServiceImpl} 那条路虽然调了、却传的是「调用方建好的清单」，
     * 生成器手上没有清单 ⟹ 折叠器直接 return。于是用户要等到<b>发布闸门</b>才第一次看见
     * 「这份流程是远程改数据的形状」，而那时草稿早就落库、code 也早分配完了。
     *
     * <p>⚠️ 这条用例同时是「不建清单也能溯源」的<b>端到端</b>证明：本测试的 {@code service} 拿不到任何
     * 清单构建器（{@code WorkflowChangeManifestBuilder} 根本没被装进来），警告只可能来自
     * {@code UntrustedInputTracer.trace(def)} 那个新入口。
     */
    @Test
    void planToWorkflow_aiDraftedWebhookWriteFlow_carriesTheTaintWarning() {
        stubWriteTool("generic_write");
        when(aiModelApi.chat(any())).thenReturn(resp(WEBHOOK_WRITE_DSL));

        WorkflowDraftBO draft = service.planToWorkflow("webhook 收到订单就建一张表", ctx(), t -> {
        });

        assertNotNull(draft);
        assertEquals(1, draft.getRounds(), "结构合法，第一轮就该出草稿：" + draft.getWarnings());
        // 🔴 按内容挑出 V16 那一条，不按下标：warnings 的顺序会随规则增删静默漂移，
        //    而这里另有一条 V11 的警告（写节点上游没有人工闸口）是<b>预期内</b>的邻居 —— 理由见 writeTool 的 javadoc
        List<String> taintWarnings = draft.getWarnings().stream()
                .filter(w -> w.contains("args.tableName"))
                .toList();
        assertEquals(1, taintWarnings.size(),
                "点名那个入参的警告本该只有 V16 这一条，实际：" + draft.getWarnings());
        assertTaintWarning(taintWarnings.get(0));
        assertEquals(2, draft.getWarnings().size(),
                "本该只有 V11 + V16 两条警告，多出来的说明这份 DSL 还踩了别的规则：" + draft.getWarnings());
    }

    /**
     * 🔴 C9 的行为层判据②：DSL 还有别的错时，V16 那句<b>跟着回灌给模型</b>
     *
     * <p>这一条与上一条测的是两个不同的出口，缺一不可：上一条走 {@code draft.getWarnings()}（给人看），
     * 本条走 {@code toFeedback()}（给模型看）。只有前者时，模型在 6 轮自修正里仍然不知道
     * 自己写的是一份「远程改数据」的流程 —— 而它恰恰是唯一能在<b>生成阶段</b>改掉这个形状的人
     * （比如在写节点上游补一个 {@code hitl.bpm_approval}）。
     *
     * <p>🔴 V16 是 warning、不阻断，所以它进 {@code toFeedback()} 靠的是「warnings 一并回灌」那半；
     * 本条断的是它<b>真的</b>在那段文字里，不是「errors 里顺带提到了工具」。
     */
    @Test
    void planToWorkflow_taintWarningIsFedBackToTheModel() {
        stubWriteTool("generic_write");
        when(aiModelApi.chat(any()))
                .thenReturn(resp(WEBHOOK_WRITE_DSL_BROKEN_EDGE))
                .thenReturn(resp(VALID_DSL));

        WorkflowDraftBO draft = service.planToWorkflow("webhook 收到订单就建一张表", ctx(), t -> {
        });

        assertNotNull(draft);
        assertEquals(2, draft.getRounds(), draft.getWarnings().toString());

        ArgumentCaptor<AiChatReqDTO> captor = ArgumentCaptor.forClass(AiChatReqDTO.class);
        verify(aiModelApi, times(2)).chat(captor.capture());
        String feedback = captor.getAllValues().get(1).getMessages().stream()
                .map(AiChatReqDTO.Message::getContent)
                .filter(java.util.Objects::nonNull)
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(feedback.contains("校验未通过"), "次轮该回灌校验反馈：" + feedback);
        assertTaintWarning(feedback);
    }

    // ==================== ④ 摘要里的目录必须与 DSL 实际用的那个对得上 ====================

    /**
     * 带一个 {@code form_publish_bundle} 的最小合法 DSL；散文与目录名都从入参灌，
     * 好让「散文有没有提到实际目录」成为通过与否的<b>唯一</b>变量。
     *
     * <p>⚠️ 上游那个 {@code compute} 节点是给占位符那条用例准备的（{@code {{$.prepared.dir}}}
     * 得有人写 {@code prepared}，否则 V7/V8 会报「引用了没人写的键」，那条用例就测不到
     * 「占位符跳过」而是测到「解析失败」）。另两条用例多这一个节点无副作用。
     */
    private static String publishDsl(String description, String publishArgsJson) {
        return """
                {"dslVersion":"1.0","id":"placeholder_id","name":"固定资产领用登记表创建与发布",
                 "description":"%s",
                 "variables":[{"key":"input","type":"object"},{"key":"prepared","type":"object"},
                              {"key":"publishResult","type":"object"},{"key":"output","type":"string"}],
                 "nodes":[{"id":"compute","type":"llm","name":"算发布参数",
                           "config":{"prompt":"算出要挂到哪个目录","outputVar":"prepared"}},
                          {"id":"publish_bundle","type":"tool","name":"发布表单",
                           "config":{"toolId":"form_publish_bundle","args":%s,"outputVar":"publishResult"}},
                          {"id":"report","type":"llm","name":"汇报",
                           "config":{"prompt":"说明结果","outputVar":"output"}}],
                 "edges":[{"from":"START","to":"compute"},{"from":"compute","to":"publish_bundle"},
                          {"from":"publish_bundle","to":"report"},{"from":"report","to":"END"}]}
                """.formatted(description, publishArgsJson);
    }

    /**
     * 🔴 复现的形状（两次实跑都是它）：散文说挂到「行政管理」，DSL 实际挂到「数据管理」
     *
     * <p>成因是 V27 把 {@code parentMenuName} 从不存在的目录逼成了真实目录，模型改了那一格
     * 却没改顶层 {@code description} —— 而 description 正是规划卡摘要的原文。
     * 断言分三截：① 模型散文<b>原样保留</b>（服务端不改 DSL，改了就是把证据抹掉）；
     * ② 权威事实被<b>补在末尾</b>；③ 首轮即合法（否则测的是回灌而不是摘要）。
     */
    @Test
    void planToWorkflow_staleMenuProse_summaryCarriesActualDirectory() {
        when(aiModelApi.chat(any())).thenReturn(resp(publishDsl(
                "根据用户需求创建固定资产领用登记表并发布至行政管理菜单",
                "{\"parentMenuName\":\"数据管理\"}")));

        WorkflowDraftBO draft = service.planToWorkflow("做一张固定资产领用登记表，发布到行政管理下", ctx(), t -> {
        });

        assertNotNull(draft);
        assertEquals(1, draft.getRounds(), draft.getWarnings().toString());
        String summary = draft.getSummary();
        assertTrue(summary.startsWith("根据用户需求创建固定资产领用登记表并发布至行政管理菜单"),
                "模型散文要原样在前：" + summary);
        assertTrue(summary.contains("实际发布到目录：数据管理"),
                "🔴 卡片标题说挂 A、流程实际挂 B —— 权威事实必须补上去：" + summary);
        assertTrue(draft.getDsl().contains("发布至行政管理菜单"),
                "刻意不改 DSL：落库的 description 仍是模型原文，本方法只动摘要：" + draft.getDsl());
    }

    /** 肯定式对照（§5.3）：散文本来就点了实际目录 ⟹ 一个字都不该补 */
    @Test
    void planToWorkflow_proseAlreadyNamesDirectory_summaryUnchanged() {
        when(aiModelApi.chat(any())).thenReturn(resp(publishDsl(
                "创建固定资产领用登记表并发布至数据管理菜单",
                "{\"parentMenuName\":\"数据管理\"}")));

        WorkflowDraftBO draft = service.planToWorkflow("做一张固定资产领用登记表，发布到数据管理下", ctx(), t -> {
        });

        assertNotNull(draft);
        assertEquals("创建固定资产领用登记表并发布至数据管理菜单", draft.getSummary());
    }

    /**
     * 占位符那一格运行期才替换，此刻无从知道会解析成哪个目录 ⟹ 不补
     *
     * <p>补了就是编一句可能为假的话（「实际发布到目录：{{$.prepared.dir}}」），
     * 比不补更糟。谓词与 V27 读同一格时用的那个逐字一致。
     */
    @Test
    void planToWorkflow_placeholderMenu_summaryUnchanged() {
        when(aiModelApi.chat(any())).thenReturn(resp(publishDsl(
                "创建固定资产领用登记表并按算出来的目录发布",
                "{\"parentMenuName\":\"{{$.prepared.dir}}\"}")));

        WorkflowDraftBO draft = service.planToWorkflow("做一张固定资产领用登记表", ctx(), t -> {
        });

        assertNotNull(draft);
        assertEquals(1, draft.getRounds(), draft.getWarnings().toString());
        assertEquals("创建固定资产领用登记表并按算出来的目录发布", draft.getSummary());
    }

    /** 没有 publish 节点的流程（占绝大多数）⟹ 摘要与改动前逐字一致，这条钉住「没多嘴」 */
    @Test
    void planToWorkflow_noPublishNode_summaryUnchanged() {
        when(aiModelApi.chat(any())).thenReturn(resp(VALID_DSL));

        WorkflowDraftBO draft = service.planToWorkflow("列出 test_demo 数据接口", ctx(), t -> {
        });

        assertNotNull(draft);
        assertEquals("按关键词列出数据接口", draft.getSummary());
    }

}
