package cn.enlev.ainb.module.agent.framework.agent.core.chat;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.agent.enums.AgentIntentEnum;
import cn.enlev.ainb.module.agent.enums.AgentUsageSceneEnum;
import cn.enlev.ainb.module.agent.framework.agent.config.AgentIntentProperties;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.framework.agent.core.security.PromptDataFence;
import cn.enlev.ainb.module.agent.service.usage.AgentUsageLogService;
import cn.enlev.ainb.module.ai.api.model.AiModelApi;
import cn.enlev.ainb.module.ai.api.model.dto.AiChatReqDTO;
import cn.enlev.ainb.module.ai.api.model.dto.AiChatRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 意图分类：这句话该走问答，还是该走规划
 *
 * <h3>为什么需要它</h3>
 * 助手有两条路径，成本与体验差了一个数量级：
 * <ul>
 *   <li><b>问答</b>：流式，几秒内开始吐字，带手册出处</li>
 *   <li><b>规划</b>：Agent 循环，1~5 轮模型调用，十几二十秒，产出待确认的计划卡片</li>
 * </ul>
 * 全走规划的话，问一句「联动怎么配」也要对着转圈等二十秒，而且规划路径不带 RAG，
 * 「回答依据平台手册」这条产品承诺会一起丢掉。所以入口必须分流。
 *
 * <h3>🔴 回落方向是有意的：拿不准就走问答</h3>
 * 分类失败、模型没配好、输出看不懂 —— 一律返回 {@link AgentIntentEnum#CHAT}。
 * 两个方向的误判代价完全不对等：
 * <ul>
 *   <li>误判成问答：用手册答了一句不那么贴题的话，用户再说一遍「帮我建」就好了</li>
 *   <li>误判成变更：用户面前突然出现一张他没要求过的「建表」卡片</li>
 * </ul>
 * 后者虽然也不会真的写库（确认前零写入的铁律在 Plan 那一层兜着），
 * 但它消耗了几倍的 token、让用户等了二十秒，还得由他自己去判断「这张卡片是不是该点」。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class AgentIntentClassifier {

    /**
     * 分类用的 system prompt
     *
     * <h3>🔴 全部重量压在「问怎么做」与「让你去做」这一条界线上</h3>
     * 这是实践中唯一真正容易错的地方，而且两边的表达长得几乎一样：
     * 「怎么建一张请假表」和「帮我建一张请假表」只差两个字，意图却完全相反。
     * 光给一句抽象定义，模型会盯着「建表」这个词把两句都判成 BUILD。
     * 所以直接给成对的例子 —— 让它看到差别在哪，比向它解释差别是什么有效。
     *
     * <p>同理，末尾那句「拿不准就答 CHAT」不是客套：没有这句时模型倾向于
     * 在模糊输入上选那个「听起来更有行动力」的答案。
     *
     * <h3>🔴 实测疤（2026-10-04 T1 全量真跑）：原来的两档<b>装不下「要数据」</b>，于是补了第二条界线</h3>
     * 原 prompt 把 CHAT 定义成「用户在<b>问问题</b>：想知道怎么做、为什么不生效、某个功能在哪、
     * 某个概念是什么意思」，把 BUILD 定义成「用户要求你<b>去做变更</b>」。
     * 而库里 id=17/19/21 三条示例（人工标定 expect_intent 全是 CHAT）都不是这两档里的任何一个：
     * <ul>
     *   <li>id=17「请<b>查询</b>设备报修工单中满足…的记录，<b>列出</b>报修单号、报修设备…」</li>
     *   <li>id=19「请<b>按月份统计</b>设备报修工单的数量和维修金额…<b>输出成表格并给出合计行</b>」</li>
     *   <li>id=21「我要把设备报修工单和设备台账<b>按设备编号关联起来查</b>…请先确认这两张表都存在」</li>
     * </ul>
     * 它们不是「问怎么做」（要的是数据本身，不是做法），也不是「变更平台」（跑完平台里一样东西都没变）。
     * 落在两档之间的空档里，模型只能靠表面线索猜 —— 而这三条的表面线索全在把它推向 BUILD：
     * <b>命令句</b>（「请统计」「我要把…关联起来查」）+ <b>点了表名</b>（设备报修工单、设备台账）。
     * 于是 id=19/21 被拉去编排流程，用户面前凭空多出一张他没要过的草稿卡
     * （10-04 00:07 那轮真漏下了 def #426「设备报修与台账关联查询分析」）。
     *
     * <p>补法不是往 CHAT 那一档里塞几个例子就完事，而是<b>把界线本身改成两条</b>：
     * 除了原来的「问怎么做 / 让你去做」，再加一条「改平台里的东西 / 从平台里取数据」。
     * 第二条才是这三条示例真正的判据 —— 它们都是命令句，第一条界线对它们<b>失效</b>。
     *
     * <p>⚠️ 加这条界线有反向风险，所以同时钉了「看主目的，别看附带步骤」：
     * BUILD 那三条示例（id=55/57「我要做一张…登记表」）正文里也写着
     * 「1. <b>查一下</b>有没有能复用的表」，id=59 写着「改完告诉我…<b>已有的数据行这个字段是什么值</b>」。
     * 只教「出现查/统计就判 CHAT」会把它们整个翻过去 —— 那就是拿一个误判换三个。
     * 主目的这一句就是拦这个的，末尾还配了一条
     * 「做一张会议室预订登记表，先查一下有没有能复用的表 → BUILD」当阳性对照。
     *
     * <p>🔴 这一改<b>无法被单测证明</b>：判据在模型脑子里，单测只能钉住「prompt 里确实写了这两条界线
     * 和这几对例子」（见 {@code AgentIntentClassifierTest} 用 ArgumentCaptor 抓 systemPrompt 那几条），
     * 钉不住「模型照做了」。真凭据只能来自真发：
     * 改完重启后端后跑 {@code TPL_MATRIX_ONLY=17,19,21} 子集探一次方向，
     * 再看下一轮全量 T1 的 ⑨ 棘轮。子集跑<b>不算</b>复现结论（spec 里写死了这条前提）。
     */
    private static final String SYSTEM_PROMPT = """
            你是一个意图分类器。判断用户这句话属于哪一类，只回答一个词，不要任何解释。

            CHAT —— 用户在问问题，或者在要一份数据结果：想知道怎么做、为什么不生效、某个功能在哪、某个概念是什么意思；也包括让你去查、去统计、去关联已有的业务数据，把结果（一个数、一份清单、一张表格）直接在聊天里给他。
            BUILD —— 用户要求你去变更平台本身：建表、建表单、建流程、加字段，或者改已有的配置。

            界线有两条，都要看：
            一、「问怎么做」还是「让你去做」，不在于有没有出现"建""表""表单"这些词；
            二、要你做的是改平台里的东西，还是从平台里取数据。取数据哪怕写成命令句、哪怕点了表名，也还是 CHAT —— 它跑完之后平台里一样东西都没变；要一个能反复用的物件（一张表、一个表单、一条流程、一个字段）被建出来或改掉，才是 BUILD。
            看主目的，别看附带步骤：主目的是建或改物件时，顺带要求「先查一下有没有能复用的表」，仍然是 BUILD；主目的是要数据时，顺带要求「先确认这两张表都存在」「别新写 SQL」，仍然是 CHAT。

            怎么建一张请假表        → CHAT
            帮我建一张请假表        → BUILD
            主子表怎么配            → CHAT
            给这个表单加个子表      → BUILD
            为什么我配的联动不生效  → CHAT
            我想要个地方记会议室预订 → BUILD
            表单发布后菜单看不到怎么办 → CHAT
            做一个设备报修的功能    → BUILD
            查本月还没闭环的报修工单，列出单号、设备、报修人 → CHAT
            按月份统计报修工单数量和维修金额，输出成表格并给出合计行 → CHAT
            把报修工单和设备台账按设备编号关联起来查，要看保修到期日 → CHAT
            给报修工单加一个「维修金额」字段 → BUILD
            做一张会议室预订登记表，先查一下有没有能复用的表 → BUILD

            拿不准就回答 CHAT。
            """;

    @Resource
    private AiModelApi aiModelApi;
    @Resource
    private AgentUsageLogService usageLogService;
    @Resource
    private AgentIntentProperties intentProperties;

    /**
     * 判断一句话的意图
     *
     * @param question       用户的话
     * @param conversationId 会话编号，计量用
     * @param context        执行上下文快照
     * @return 意图；任何异常都返回 {@link AgentIntentEnum#CHAT}，不会抛出
     */
    public AgentIntentEnum classify(String question, Long conversationId,
                                    AgentExecutionContext context) {
        if (!Boolean.TRUE.equals(intentProperties.getEnabled())) {
            // 总闸关着：整条写路径不存在，一切都是问答
            return AgentIntentEnum.CHAT;
        }
        if (StrUtil.isBlank(question)) {
            return AgentIntentEnum.CHAT;
        }

        AiChatReqDTO reqDTO = new AiChatReqDTO();
        // 🔴 B2-3：CONTRACT 用<b>追加</b>而不是去改写 SYSTEM_PROMPT 那个文本块。
        //    那块提示词有五条逐字断言钉着（两条界线措辞、「看主目的」、成对示例的标注、末尾那句兜底），
        //    它们钉的是<b>分型判据本身</b>，与包裹格式无关；追加能让这批断言继续守它们本来守的东西，
        //    而不是被一次安全加固顺手削掉 —— 削掉之后 prompt 就重新变成「完全没人盯」的状态。
        reqDTO.setSystemPrompt(SYSTEM_PROMPT + "\n" + PromptDataFence.CONTRACT);
        // 🔴 B2-3：用户这句话是 TRIGGER_CHAT 污点源，包成数据段。
        //    分型这一步尤其要包：它的产物是一个<b>开关</b>（CHAT / BUILD），
        //    一句话把分型从 CHAT 翻成 BUILD 就等于拿到了写路径的入口 ——
        //    注入在这里的收益比在普通问答里大得多，而现状是这句话原样躺在指令段外面、没有任何标记。
        reqDTO.setMessages(List.of(AiChatReqDTO.Message.of("user",
                PromptDataFence.fence("user_question", question))));
        // 分类要的是稳定：同一句话今天判 CHAT、明天判 BUILD，比判错更让人没法信任
        reqDTO.setTemperature(0.0);
        reqDTO.setMaxTokens(intentProperties.getMaxTokens());
        // 未配置专用小模型时留空：api 层按「启用 + 密钥启用 + sort 最前」动态取默认，故障自动切换
        reqDTO.setModelId(intentProperties.getModelId());
        // 分型只吐一个枚举值，思考是纯浪费；且这条在每轮对话的最前面，它慢就整体都慢
        reqDTO.setEnableThinking(false);

        LocalDateTime begin = LocalDateTime.now();
        AiChatRespDTO resp;
        try {
            resp = aiModelApi.chat(reqDTO);
        } catch (Exception ex) {
            // 🔴 分类挂了不能让整次对话跟着挂。模型没配好、密钥过期、厂商抖动，
            //    这些都不该表现成「助手坏了」—— 退回问答，用户至少还能得到一个回答
            recordUsage(context, conversationId, question, begin, null, "FAILURE", ex.getMessage());
            log.warn("[classify][意图分类失败，本轮按问答处理：{}]", ex.getMessage());
            return AgentIntentEnum.CHAT;
        }
        recordUsage(context, conversationId, question, begin, resp, "SUCCESS", null);

        AgentIntentEnum intent = parse(resp.getContent());
        log.debug("[classify][「{}」→ {}]", StrUtil.maxLength(question, 40), intent.getIntent());
        return intent;
    }

    /**
     * 从模型输出里认出意图
     *
     * <p>不做严格相等比较：模型偶尔会回「BUILD」带个句号，或者中英文混着说。
     * 为一个标点把结果丢进回落分支不值得。
     *
     * <p>只认 BUILD、其余一律 CHAT —— 因为 CHAT 是安全的那一边，
     * 「没认出来」和「确实是 CHAT」应当走向同一个结果。
     */
    private AgentIntentEnum parse(String content) {
        String text = StrUtil.trimToEmpty(content).toUpperCase();
        return text.contains(AgentIntentEnum.BUILD.getIntent())
                ? AgentIntentEnum.BUILD : AgentIntentEnum.CHAT;
    }

    /**
     * 计量
     *
     * <p>与另外两条路径同一条纪律（见 {@code AgentAssistantServiceImpl.recordUsage}）：
     * 一个响应都没拿到就是请求根本没发出去，厂商那边零消耗，必须记 0 而不是估一个数。
     */
    private void recordUsage(AgentExecutionContext context, Long conversationId, String question,
                             LocalDateTime begin, AiChatRespDTO resp, String status,
                             String errorMsg) {
        AiChatRespDTO.Usage usage = resp == null ? null : resp.getUsage();
        boolean fromProvider = usage != null;
        int prompt = 0;
        int completion = 0;
        int total = 0;
        if (resp != null) {
            prompt = fromProvider && usage.getPromptTokens() != null
                    ? usage.getPromptTokens() : AgentTokenEstimator.estimate(SYSTEM_PROMPT + question);
            completion = fromProvider && usage.getCompletionTokens() != null
                    ? usage.getCompletionTokens() : AgentTokenEstimator.estimate(resp.getContent());
            total = fromProvider && usage.getTotalTokens() != null
                    ? usage.getTotalTokens() : prompt + completion;
        }
        usageLogService.record(AgentUsageLogService.UsageRecord.builder()
                .userId(context.getUserId())
                .tenantId(context.getTenantId())
                .conversationId(conversationId)
                .platform(resp == null ? null : resp.getPlatform())
                .modelName(resp == null ? null : resp.getModel())
                .modelId(resp == null ? null : resp.getModelId())
                .scene(AgentUsageSceneEnum.INTENT)
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

}
