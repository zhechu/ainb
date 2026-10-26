package cn.enlev.ainb.module.agent.framework.agent.core.chat;

import cn.enlev.ainb.module.agent.enums.AgentIntentEnum;
import cn.enlev.ainb.module.agent.framework.agent.config.AgentIntentProperties;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.service.usage.AgentUsageLogService;
import cn.enlev.ainb.module.ai.api.model.AiModelApi;
import cn.enlev.ainb.module.ai.api.model.dto.AiChatReqDTO;
import cn.enlev.ainb.module.ai.api.model.dto.AiChatRespDTO;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link AgentIntentClassifier} 单测
 *
 * 核心约束：
 *  ① 模型回 BUILD → BUILD
 *  ② 模型回 CHAT → CHAT
 *  ③ 模型回噪声（带额外文字、大小写混合）→ 正确解析
 *  ④ 模型抛异常 → 回落 CHAT（不抛，不中断对话）
 *  ⑤ 总开关关闭 → 无论什么问题都回 CHAT，不调模型
 *  ⑥ 空问题 → CHAT，不调模型
 *
 * 🔴 ⑦~⑩ 钉的是 <b>prompt 本身</b>，不是 parse 逻辑：
 *  ⑦ prompt 里写着第二条界线（从平台里取数据 ≠ 变更）—— id=17/19/21 三条示例唯一的判据
 *  ⑧ prompt 里写着「看主目的」—— ⑦ 的反向保险，缺了它 BUILD 那三条会被翻成 CHAT
 *  ⑨ prompt 里那几对例子的标注不能翻、不能删
 *  ⑩ 末尾的「拿不准就回答 CHAT」与「只回答一个词」不能删
 *
 * <p>①~⑥ 把模型整个 mock 掉了，所以 prompt 改成什么样它们<b>都绿</b> ——
 * 而 prompt 一改，线上每句话的分型都跟着变。⑦~⑩ 就是补这个洞的：
 * 用 ArgumentCaptor 抓真实发出的 {@link AiChatReqDTO}，钉住的是真正生效的那一份，不是副本。
 *
 * <p>⚠️ 但这几条也只证明「写了」，证明不了「模型照做了」。真凭据只能来自真发：
 * 改完重启后端后跑 {@code TPL_MATRIX_ONLY=17,19,21} 探方向，再看全量 T1 的 ⑨ 棘轮。
 *
 * @author 走进新时代
 */
class AgentIntentClassifierTest extends BaseMockitoUnitTest {

    @InjectMocks
    private AgentIntentClassifier classifier;

    @Mock
    private AiModelApi aiModelApi;
    @Mock
    private AgentUsageLogService usageLogService;
    @Mock
    private AgentIntentProperties intentProperties;

    private AgentExecutionContext context;

    @BeforeEach
    void setUp() {
        context = AgentExecutionContext.ofForTest(1L, 1L);
        when(intentProperties.getEnabled()).thenReturn(true);
        // 这两个 stub 只在真正调模型时用到，blank-question 与 disabled 两个用例不调模型。
        // 用 lenient 避免 Mockito strict 在那两个用例上报 UnnecessaryStubbingException，
        // 同时保留其他用例能隐式验证「确实用了这些值」的效果
        lenient().when(intentProperties.getMaxTokens()).thenReturn(8);
        // modelId 留空 = 走 api 层动态默认（启用 + 密钥启用 + sort 最前，故障自动切换）
        lenient().when(intentProperties.getModelId()).thenReturn(null);
    }

    @Test
    void testClassify_build() {
        mockModel("BUILD");
        assertEquals(AgentIntentEnum.BUILD,
                classifier.classify("帮我建一张请假申请表", 1L, context));
    }

    @Test
    void testClassify_chat() {
        mockModel("CHAT");
        assertEquals(AgentIntentEnum.CHAT,
                classifier.classify("为什么联动不生效", 1L, context));
    }

    @Test
    void testClassify_noisyBuild() {
        // 模型可能多写几个字，解析时要容错
        mockModel("根据您的意图，这是一个 BUILD 类型的操作请求。");
        assertEquals(AgentIntentEnum.BUILD,
                classifier.classify("做一个设备报修的功能", 1L, context));
    }

    @Test
    void testClassify_noisyChat() {
        mockModel("这属于 CHAT 类型");
        assertEquals(AgentIntentEnum.CHAT,
                classifier.classify("表单控件有哪些", 1L, context));
    }

    @Test
    void testClassify_unknownOutput_fallsBackToChat() {
        // 拿不准就回 CHAT（§ AgentIntentClassifier：只认 BUILD，其余一律 CHAT）
        mockModel("UNCLEAR");
        assertEquals(AgentIntentEnum.CHAT,
                classifier.classify("...", 1L, context));
    }

    @Test
    void testClassify_modelException_fallsBackToChat() {
        when(aiModelApi.chat(any())).thenThrow(new RuntimeException("模型超时"));
        // 🔴 不能抛出，必须静默降级，否则一次模型抖动就把整条对话链打断
        AgentIntentEnum result = classifier.classify("帮我建一个表", 1L, context);
        assertEquals(AgentIntentEnum.CHAT, result);
        // 计量日志必须记，哪怕调用失败了
        verify(usageLogService).record(any());
    }

    @Test
    void testClassify_disabled_neverCallsModel() {
        when(intentProperties.getEnabled()).thenReturn(false);
        AgentIntentEnum result = classifier.classify("帮我建一张表", 1L, context);
        assertEquals(AgentIntentEnum.CHAT, result);
        // 总开关关着就不应该调模型
        verifyNoInteractions(aiModelApi);
    }

    @Test
    void testClassify_blankQuestion_fallsBackToChat() {
        AgentIntentEnum result = classifier.classify("   ", 1L, context);
        assertEquals(AgentIntentEnum.CHAT, result);
        verifyNoInteractions(aiModelApi);
    }

    // ---- prompt 本身 ----

    /**
     * 🔴 判据在模型脑子里，单测证明不了「模型照做了」。
     * 但下面这几条能证明**送去模型的那份 prompt 里确实写着这两条界线** ——
     * 用 ArgumentCaptor 抓真实发出的 {@link AiChatReqDTO}，不去读私有常量、也不抄一份副本，
     * 所以钉住的是真正生效的那个物件（抄副本就是 D7，两份必然漂移）。
     *
     * <p>没有这几条的话，SYSTEM_PROMPT 是**完全没人盯**的：本文件其余用例把模型整个 mock 掉了，
     * prompt 改成什么样它们都绿。而它一改，线上每一句话的分型都跟着变。
     */
    private String capturedSystemPrompt() {
        mockModel("CHAT");
        classifier.classify("随便一句", 1L, context);
        ArgumentCaptor<AiChatReqDTO> captor = ArgumentCaptor.forClass(AiChatReqDTO.class);
        verify(aiModelApi).chat(captor.capture());
        return captor.getValue().getSystemPrompt();
    }

    /** 把 prompt 里的成对例子抠成 例子文本 → 标注。对空格填充不敏感，故改排版不会误红 */
    private Map<String, String> examples(String prompt) {
        Map<String, String> m = new LinkedHashMap<>();
        Matcher matcher = Pattern.compile("^(.+?)\\s*→\\s*(CHAT|BUILD)\\s*$", Pattern.MULTILINE)
                .matcher(prompt);
        while (matcher.find()) {
            m.put(matcher.group(1).trim(), matcher.group(2));
        }
        return m;
    }

    @Test
    void testSystemPrompt_hasTheSecondBoundary_dataQueryIsNotAChange() {
        String prompt = capturedSystemPrompt();
        // 第二条界线：改平台里的东西 vs 从平台里取数据。
        // 这是 id=17/19/21 那三条示例唯一的判据 —— 它们都是命令句，第一条界线对它们失效
        assertTrue(prompt.contains("从平台里取数据"),
                "prompt 里必须写明第二条界线（取数据不是变更），否则「请统计…」「请查询…」这类命令句\n"
                        + "只能靠表面线索猜，而命令句 + 表名这两个线索全在把它推向 BUILD：\n" + prompt);
        assertTrue(prompt.contains("平台里一样东西都没变"),
                "要说清为什么取数据算 CHAT —— 给判据不给理由，模型在没见过的句式上就会退回猜词：\n" + prompt);
    }

    @Test
    void testSystemPrompt_hasTheMainPurposeGuard_soBuildCasesDoNotFlip() {
        String prompt = capturedSystemPrompt();
        // 🔴 这一条是上一条的**反向**保险，两者必须同时在：
        // BUILD 那三条示例（id=55/57/59）正文里也写着「先查一下有没有能复用的表」
        // 「已有的数据行这个字段是什么值」。只教「出现查/统计就判 CHAT」会把它们整个翻过去
        assertTrue(prompt.contains("看主目的"),
                "prompt 必须钉住「看主目的、别看附带步骤」，否则加了第二条界线就会把 BUILD 那三条翻成 CHAT —— "
                        + "那是拿一个误判换三个：\n" + prompt);
        assertEquals("BUILD", examples(prompt).get("做一张会议室预订登记表，先查一下有没有能复用的表"),
                "这条是主目的那句的阳性对照：句子后半截在「查」，主目的是「做一张表」，必须仍判 BUILD。\n"
                        + "实际抠到的例子：" + examples(prompt));
    }

    @Test
    void testSystemPrompt_dataQueryExamplesAreLabelledChat() {
        Map<String, String> ex = examples(capturedSystemPrompt());
        // 三条都是库里真实示例（id=17/19/21，expect_intent 人工标定为 CHAT）的缩写。
        // 逐条断言而不是断言条数：条数对上了、标注翻了，是一样的退化
        assertEquals("CHAT", ex.get("查本月还没闭环的报修工单，列出单号、设备、报修人"), "id=17 的形状：" + ex);
        assertEquals("CHAT", ex.get("按月份统计报修工单数量和维修金额，输出成表格并给出合计行"), "id=19 的形状：" + ex);
        assertEquals("CHAT", ex.get("把报修工单和设备台账按设备编号关联起来查，要看保修到期日"), "id=21 的形状：" + ex);
        assertEquals("BUILD", ex.get("给报修工单加一个「维修金额」字段"),
                "与上面三条同一张表，差别只在「读它」还是「改它的结构」—— 这一对是最 sharp 的对照，删了就没人钉住这条界线：" + ex);
    }

    @Test
    void testSystemPrompt_keepsTheChatTieBreakAndSingleWordRule() {
        String prompt = capturedSystemPrompt();
        // 回落方向与 classify() 的 catch 分支同向（都退 CHAT）。删掉这句，模型会在模糊输入上
        // 选那个「听起来更有行动力」的答案 —— 也就是 BUILD，正好是代价大的那一边
        assertTrue(prompt.contains("拿不准就回答 CHAT"), "末尾那句兜底不能删：\n" + prompt);
        // maxTokens 只有 8（见 AgentIntentProperties），模型要是展开解释就会被截断成半句
        assertTrue(prompt.contains("只回答一个词"), "输出长度上限只有 8 token，必须要求它只回一个词：\n" + prompt);
    }

    // ---- helpers ----

    private void mockModel(String content) {
        AiChatRespDTO resp = new AiChatRespDTO();
        resp.setContent(content);
        when(aiModelApi.chat(any())).thenReturn(resp);
    }

}
