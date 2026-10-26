package cn.enlev.ainb.module.agent.framework.agent.core.chat;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.module.agent.dal.dataobject.conversation.AgentMessageDO;
import cn.enlev.ainb.module.agent.framework.agent.core.security.PromptDataFence;
import cn.enlev.ainb.module.ai.api.model.dto.AiChatReqDTO;
import cn.enlev.ainb.module.ai.api.rag.dto.AiRagSegmentRespDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 助手对话的上下文组装
 *
 * <p>顺序参照现有 {@code AiChatMessageServiceImpl.buildPrompt}（{@code :325-388}）：
 * System → 历史 → 当前问题 + 手册片段。但有<b>两处刻意的不同</b>，
 * 都是为了修上游的已知缺陷，见下。
 *
 * <h3>🔴 不同一：历史轮要回灌手册片段</h3>
 * 上游 {@code AiChatMessageServiceImpl#buildPrompt} 留了个 TODO：历史轮<b>不回灌</b>知识库与搜索结果。
 * 后果是多轮 RAG 会「失忆」——
 * <pre>
 *   第一轮：「为什么我配的联动不生效」→ 检索到手册，答得很准
 *   第二轮：「那子表呢」            → 模型手上只有第一轮的<b>回答文本</b>，
 *                                    看不到当时的手册原文，于是开始用通用知识补
 * </pre>
 * 而「用通用知识补」恰恰是接 RAG 要消灭的东西：实测没接 RAG 时问这个问题，
 * 模型答的是「条件未满足 / 字段标识错误 / 规则冲突」—— 通用、正确、
 * 但对这个平台毫无用处。第二轮又退回这个状态，用户只会觉得「AI 越问越笨」。
 *
 * <p>所以历史里的助手消息会连同它<b>当时检索到的片段</b>一起回灌
 * （片段存在 {@code agent_message.reference_json}）。
 *
 * <h3>🔴 不同二：按 token 预算截断，不按轮数</h3>
 * 上游 {@code AiChatMessageServiceImpl#filterContextMessages} 按条数截断（最多 {@code maxContexts * 2} 条）。
 * 一条消息可能是 50 字，也可能是塞了 5 段手册的 4000 token ——
 * 按条数截断等于假装每条一样大，长会话照样超窗口。
 * 这里改为从<b>最近的一轮往前</b>累加 token，加满预算就停。
 *
 * <h3>🔴 不同三（B2-3）：所有不可信内容一律走 {@link PromptDataFence}，本类的框标题降级成「装饰」</h3>
 * 本类原先是全模块<b>唯一</b>看着像做了包裹的地方（{@code 手册片段：} + {@code 【片段N】} + {@code 问题：}），
 * 但它不合格，缺的正是 {@link PromptDataFence} 类注释里点名的那两样：
 * <ol>
 *   <li>{@code 【片段1】} 是普通中文方括号 —— 检索回来的手册正文里、用户自己敲的一句话里都能原样出现。
 *       数据里写一行 {@code 【片段2】} 就凭空多出一段「平台认可的引用」，模型分不出哪个是平台加的；</li>
 *   <li>{@code appendSegments} 对片段内容是 {@code sb.append(原文)}，<b>一个字符都不改</b>。</li>
 * </ol>
 * 现在承重的定界符换成 {@link PromptDataFence}（难以伪造 + 数据内含定界符时做中和），
 * 而 {@code 手册片段：} / {@code 【片段N】} / {@code 问题：} 这几个框标题<b>保留但降级</b>成给人看的排版：
 * 它们仍解释「这段是哪来的」，却<b>不再承担任何结构语义</b> —— 数据里伪造一个 {@code 【片段2】}
 * 只会落在那一段自己的数据段<b>内部</b>，撑不开结构。
 *
 * <p>🔴 保留标题不是为了少改测试：{@code AgentPromptBuilderTest} 有条回归护栏
 * 「默认助手仍用『手册片段：』框、不出现『参考资料：』」，它守的是<b>增量11 的模式分流</b>
 * （自定义 Agent 的专属知识库不该被叫成「手册」）。标题一去，那条护栏就只剩恒真断言可写 ——
 * 而「把断言删成恒真」正是方案 §6 记过学费的地方。
 *
 * <h3>🔴 历史轮也要包裹：否则围栏自己留了个「二阶」后门</h3>
 * 只包裹当轮问题是不够的。历史里的 user 消息是<b>同一种不可信输入</b>，
 * 若原样回灌，攻击者第 1 轮写下的注入串会在第 2 轮以<b>无围栏的 user 消息</b>重新出现 ——
 * 而 user 角色的话在模型眼里比数据段更有分量。等于围栏自己留了一条绕行通道。
 * 故 {@link #renderHistoryContent} 对历史两侧的正文一并包裹
 * （助手侧的回答是模型产物，同样按数据处理：它可能是上一轮被操纵后吐出来的）。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class AgentPromptBuilder {

    /**
     * 基础约束 —— 平台助手系统提示词的<b>出厂默认值</b>
     *
     * <h3>🔴 需求10 之后，改这里不等于改线上行为</h3>
     * 生效值取自 {@code agent_def} 里 {@code builtin=1} 那行的 persona（管理员在「智能体管理」在线维护）。
     * 本常量只在两种情况下用到：① 该行查不到（新环境没跑种子迁移）；② 该行 persona 被清空。
     * 所以要调线上措辞，去页面上改；改这里只影响「出厂状态」，已有租户不会跟着变 ——
     * 这是有意的（DB 是活配置，代码是兜底），别把它当 bug 去「同步」。
     *
     * <p>种子脚本 {@code sql/mysql/xpy-2026-09-29-agent-def-builtin-platform-assistant.sql}
     * 插入的 persona 与本常量<b>逐字一致</b>，故迁移完成的那一刻线上行为与需求10 之前完全相同。
     *
     * <h3>这段不是客套话</h3>
     * 实测没接 RAG 时问「联动为什么不生效」，模型答的是
     * 「条件未满足 / 字段标识错误 / 规则冲突」—— 通用、正确、对这个平台毫无用处。
     * 而手册里写的是真正的原因：「条件成立但只配了 then 没配 otherwise，
     * 字段会停在原状态」。
     *
     * <p>不加这段约束，模型会把两者<b>混着说</b>，用户分不清哪句可信 ——
     * 那比答不上来更糟：答不上来他会去翻文档，混着说他会直接照做。
     */
    private static final String BASE_PROMPT = """
            你是这个低代码平台的助手，帮助用户使用平台的表单设计、数据模型与权限配置功能。

            回答必须严格依据下面提供的手册片段，不要用通用知识补充。
            若片段里没有答案，直接说「手册里没有覆盖这个问题」，不要编造。
            回答要简短，并说明依据来自哪一段。""";

    /**
     * 建议部分的输出约定
     *
     * <p>与主回答<b>同一次调用</b>产出（方案 §9.2）：单独再调一次 LLM 会让每次对话的
     * 成本近乎翻倍，而建议的质量提升有限。
     *
     * <p>用分隔标记而不是让模型输出 {@code {"answer":..., "suggestions":...}}：
     * 后者会让用户眼睁睁看着一串 JSON 逐字打出来，打字机效果彻底废掉。
     * 切分逻辑见 {@link AgentAnswerStreamSplitter}。
     */
    private static final String SUGGESTION_PROMPT = """

            回答结束后，另起一行输出 %s，然后输出一个 JSON 数组，给出最多 3 条追问建议。
            格式：[{"text": "建议文本"}]
            要求：
            - 每条不超过 20 字，必须是用户可以直接发送的完整问题
            - 只建议手册里确实覆盖、你有把握答好的方向
            - 不要建议你刚刚已经回答过的内容
            - 这一行之后不要再输出任何其它文字""";

    // ==================== 框标题（B2-3 之前全是内联字面量，现收进常量）====================
    // 🔴 这几条<b>只是排版</b>，不承担结构语义 —— 承重的定界符是 PromptDataFence 那一套。
    //    刻意保持 private：测试要钉的是「字面文案」，若让它引用这里的常量，
    //    断言就变成「常量等于它自己」的恒真式（方案 §6 的学费）。

    /** 平台助手路径的片段框标题 */
    private static final String FRAME_MANUAL = "手册片段：\n";

    /**
     * 自定义 Agent 绑定知识库时的片段框标题
     *
     * <p>与 {@link #FRAME_MANUAL} 分开而非复用：内置手册是<b>平台级文档</b>，
     * 而「订单客服」的专属知识库里是它自己的产品/订单资料，把后者也叫「手册」是语义错位。
     */
    private static final String FRAME_REFERENCE = "参考资料：\n";

    /** 一段都没召回时的明说文案 —— 给个空的「手册片段：」会让模型以为手册是空的，进而更容易自由发挥 */
    private static final String FRAME_NO_SEGMENT = "（本次没有检索到相关的手册片段）\n";

    /** 片段序号的排版前缀（{@code 【片段1】}）。🔴 它<b>不是</b>定界符，见类注释「不同三」 */
    private static final String SEGMENT_HEADING_PREFIX = "【片段";

    /** 片段序号的排版后缀 */
    private static final String SEGMENT_HEADING_SUFFIX = "】\n";

    /** 当轮问题的排版标题 */
    private static final String QUESTION_LABEL = "问题：\n";

    /** 每段前面的来源行前缀。🔴 与 {@link #SEGMENT_HEADING_PREFIX} 同属「排版」，不承担结构语义 */
    private static final String SEGMENT_SOURCE_PREFIX = "【来源】";

    /** 来源行里标题与地址各自的长度上限。正常手册标题二十来字、地址百来字符，超长的一律是脏数据 */
    private static final int SOURCE_MAX_LENGTH = 200;

    /**
     * 引用要求 —— 挂在<b>用户消息</b>里，不挂在系统提示词里
     *
     * <h3>🔴 为什么不写进系统提示词（方案 N8 原先的写法，被否掉了）</h3>
     * 两条理由，第一条是硬的：
     * <ol>
     *   <li><b>系统提示词不是本类能改的</b>。平台助手的 persona 来自 {@code agent_def} 里
     *       {@code builtin=1} 那行，是管理员在「智能体管理」在线维护的<b>租户配置</b>；
     *       自定义 Agent 的 persona 更是租户自己写的人设。往上面叠平台的引用要求，
     *       等于用平台文字压过租户配置 —— 而 {@code AgentPromptBuilderTest} 有一条护栏
     *       逐字钉住 {@code systemPrompt == persona + "\n\n" + CONTRACT}，正是守这件事。</li>
     *   <li><b>没检索到片段时这句话是错的</b>。一段都没召回时要求模型「给出来源链接」，
     *       只会诱导它编一个 —— 那比不给链接坏得多。挂在用户消息里，它就<b>只在真有来源时出现</b>
     *       （见 {@link #hasAnySource}），且紧挨着那些来源，模型不用跨着几千 token 去关联。</li>
     * </ol>
     */
    private static final String CITATION_NOTE = """
            回答要求：上面每段开头的【来源】行标出了它的出处。凡用到某一段的内容，就在那句话后面标出出处 —— \
            【来源】里给了地址的，用 markdown 链接（链接文字用标题、地址用那个地址）；只给了标题的，写出标题即可。\
            同一段被多处引用只标第一次。没有【来源】行的段落不要编造出处，更不要给出任何猜测的地址。""";

    /** 历史轮助手消息后面回灌片段时的说明 */
    private static final String HISTORY_REFERENCE_NOTE =
            "\n\n（上一轮回答所依据的手册片段，供你继续追问时参考）\n";

    // ==================== 数据段标签（进定界符，模型与读日志的人靠它分辨来源）====================

    /** 当轮问题 */
    private static final String LABEL_QUESTION = "question";

    /** 当轮检索到的片段，实际标签带序号（{@code segment_1}） */
    private static final String LABEL_SEGMENT = "segment";

    /** 历史轮里的用户提问 */
    private static final String LABEL_HISTORY_QUESTION = "history_question";

    /** 历史轮里的助手回答 */
    private static final String LABEL_HISTORY_ANSWER = "history_answer";

    /** 历史轮回灌的片段，实际标签带序号（{@code history_segment_1}） */
    private static final String LABEL_HISTORY_SEGMENT = "history_segment";

    /**
     * 组装请求
     *
     * @param persona      系统提示词原文。<b>平台助手</b>=内置定义行（{@code agent_def.builtin=1}）的 persona，
     *                     可在「智能体管理」在线维护，空白时回落 {@link #BASE_PROMPT}；
     *                     <b>自定义 Agent</b>=该行自己的 persona（见下「两种模式」）
     * @param platformAssistant 是否「默认平台助手」模式（聊天入口未选 Agent）。
     *                     🔴 这是一个<b>显式入参</b>，不由 {@code persona} 是否为空推导：
     *                     需求10 之后平台助手的 persona 也来自数据库、同样非空，
     *                     再拿「空/非空」当模式开关会把平台助手误判成自定义 Agent，
     *                     于是一次性丢掉手册片段框与追问建议 —— 而且不报错，只是答得越来越飘。
     * @param history      历史消息（按时间正序，含本轮之前的全部）
     * @param question     本轮问题
     * @param segments     本轮检索到的片段：默认助手=内置手册片段；自定义 Agent=其绑定知识库的片段（未绑定则空）
     * @param tokenBudget  历史部分的 token 预算
     * @param withSuggestion 是否要求模型产出建议（自定义 Agent 模式下调用方传 false，见 D3）
     * @return 请求 DTO
     *
     * <h3>🔴 两种模式（增量10 接线 persona，增量11 补知识库，需求10 让平台助手的人设也可在线维护）</h3>
     * <ul>
     *   <li><b>默认平台助手</b>（{@code platformAssistant=true}）：系统提示词 = persona（+可选 {@link #SUGGESTION_PROMPT}），
     *       user 内容 = 手册片段框 + 问题。persona 空白时用 {@link #BASE_PROMPT} 兜底，
     *       故「种子数据没跑」只会退回出厂提示词，不会让对话失去 grounding。</li>
     *   <li><b>自定义 Agent</b>（{@code platformAssistant=false}）：系统提示词 = <b>persona 原文</b>
     *       （不叠加 BASE_PROMPT 的手册 grounding 措辞——那是平台助手专属，对「订单客服」之类是错的）；
     *       不叠加建议约定（其文案是手册导向）。user 内容分两种：
     *       <b>绑定了知识库</b>（segments 非空）→「参考资料」框 + 问题（增量11）；<b>未绑定</b>（segments 空）→ 纯问题
     *       （增量10 行为，逐字不变）。两条路径都不套「手册片段：」框——那是内置手册专用文案。</li>
     * </ul>
     *
     * <p>🔴 B2-3 之后两条路径的系统提示词<b>都会</b>在末尾追加 {@link PromptDataFence#CONTRACT}，
     * 且 user 内容里的每一段不可信文本都被 {@link PromptDataFence#fence} 包住 ——
     * 包括「自定义 Agent 未绑知识库」那条原本发<b>纯问题</b>的路径（问题本身就是不可信输入，
     * 不因为它没带片段就变干净）。「逐字不变」这句从此只对<b>问题正文</b>成立，不对整条消息成立。
     */
    public AiChatReqDTO build(String persona, boolean platformAssistant, List<AgentMessageDO> history,
                              String question, List<AiRagSegmentRespDTO> segments,
                              int tokenBudget, boolean withSuggestion) {
        AiChatReqDTO reqDTO = new AiChatReqDTO();
        if (platformAssistant) {
            String base = StrUtil.isNotBlank(persona) ? persona : BASE_PROMPT;
            reqDTO.setSystemPrompt(appendContract(withSuggestion
                    ? base + SUGGESTION_PROMPT.formatted(AgentAnswerStreamSplitter.MARKER)
                    : base));
        } else {
            // 自定义 Agent：人设即系统提示词，不叠加平台手册 grounding / 建议约定
            reqDTO.setSystemPrompt(appendContract(persona));
        }

        List<AiChatReqDTO.Message> messages = new ArrayList<>(
                truncateByTokenBudget(history, tokenBudget));
        // user 内容：默认助手套「手册片段」框；自定义 Agent 绑了知识库（segments 非空）才套「参考资料」框，未绑则纯问题
        String userContent;
        if (platformAssistant) {
            userContent = buildUserContent(question, segments);
        } else {
            userContent = CollUtil.isEmpty(segments)
                    ? PromptDataFence.fence(LABEL_QUESTION, question)
                    : buildKnowledgeContent(question, segments);
        }
        messages.add(AiChatReqDTO.Message.of("user", userContent));
        reqDTO.setMessages(messages);
        return reqDTO;
    }

    /**
     * 把 {@link PromptDataFence#CONTRACT} 追加到系统提示词<b>末尾</b>
     *
     * <h3>为什么是末尾，不是开头</h3>
     * 两条理由：① 模型对靠近末尾的指令更敏感（本类 {@link #buildUserContent} 把问题放在片段之后是同一条理由）；
     * ② {@code AgentPromptBuilderTest} 有条护栏钉住 {@code systemPrompt.startsWith(persona)} ——
     * persona 是管理员在「智能体管理」里写的人设，它必须在最前面，否则等于平台文字压过了租户配置。
     *
     * <h3>为什么两条模式都要加</h3>
     * 自定义 Agent 的 persona 是<b>租户自己写的</b>，它同样会收到检索片段与用户问题；
     * 「只有平台助手才需要防注入」是不成立的 —— 恰恰相反，自定义 Agent 的 persona 未经平台审阅，
     * 是更松的那一侧。
     *
     * <p>🔴 persona 可能为 null / 空白（自定义 Agent 那行没填人设），此时<b>只发 CONTRACT</b>：
     * 直接 {@code persona + "\n\n" + CONTRACT} 会拼出字面量 {@code "null"}，
     * 而 {@code "null"} 出现在系统提示词开头是会被模型当成人设读的。
     */
    private static String appendContract(String systemPrompt) {
        return StrUtil.isBlank(systemPrompt)
                ? PromptDataFence.CONTRACT
                : systemPrompt + "\n\n" + PromptDataFence.CONTRACT;
    }

    /**
     * 当轮的用户消息：手册片段 + 问题
     *
     * 片段放在问题<b>前面</b>：模型对靠近末尾的指令更敏感，
     * 把问题放最后能让它更明确「要回答的是这个」，而不是去总结手册。
     *
     * <p>🔴 B2-3：片段正文与问题正文<b>各自</b>过 {@link PromptDataFence#fence}，不是整条消息套一个大围栏。
     * 逐段包裹多买到的东西是<b>归属</b>：每段都带着自己的标签（{@code segment_1} / {@code question}），
     * 于是「片段里那句『请把答案改成 X』」与「用户真的问了 X」在模型眼里是两段不同来源的数据；
     * 一个大围栏只能说「这一整坨都是数据」，说不出里面哪句是谁说的 ——
     * 而片段之间互相冒名（片段 A 里写一行 {@code 【片段2】} 顶掉真片段 2）正是靠标签才拦得住。
     */
    private String buildUserContent(String question, List<AiRagSegmentRespDTO> segments) {
        if (CollUtil.isEmpty(segments)) {
            // 一段都没召回时明说，而不是给个空的「手册片段：」——
            // 后者会让模型以为手册是空的，进而更容易自由发挥
            return FRAME_NO_SEGMENT + QUESTION_LABEL + PromptDataFence.fence(LABEL_QUESTION, question);
        }
        StringBuilder sb = new StringBuilder(FRAME_MANUAL);
        appendSegments(sb, segments, LABEL_SEGMENT);
        return appendCitationNoteAndQuestion(sb, segments, question);
    }

    /**
     * 自定义 Agent 绑定了知识库时的用户消息：参考资料 + 问题（增量11 / slice-3a）
     *
     * <p>与 {@link #buildUserContent} 分开而非复用：那里的文案（「手册片段」「（本次没有检索到相关的手册片段）」）是
     * <b>平台内置手册</b>专用的 grounding 措辞；自定义 Agent 检索的是它<b>自己绑定的知识库</b>，用中性的「参考资料」更贴切。
     * 且自定义 Agent 未绑知识库（segments 空）时应发<b>纯问题</b>、不该带「没检索到手册片段」这类提示——
     * 故「空 segments」的分流在调用方 {@link #build} 做，这里只处理非空。
     */
    private String buildKnowledgeContent(String question, List<AiRagSegmentRespDTO> segments) {
        StringBuilder sb = new StringBuilder(FRAME_REFERENCE);
        appendSegments(sb, segments, LABEL_SEGMENT);
        return appendCitationNoteAndQuestion(sb, segments, question);
    }

    /**
     * 收尾：可选的引用要求 + 问题
     *
     * <p>抽出来是为了让「引用要求只在真有来源时出现」这条判据只写一遍 ——
     * 平台助手与自定义 Agent 两条路径共用，写两遍必然有一遍漏掉那个 {@code if}。
     */
    private static String appendCitationNoteAndQuestion(StringBuilder sb,
                                                        List<AiRagSegmentRespDTO> segments, String question) {
        sb.append('\n');
        if (hasAnySource(segments)) {
            sb.append(CITATION_NOTE).append('\n');
        }
        return sb.append(QUESTION_LABEL)
                .append(PromptDataFence.fence(LABEL_QUESTION, question)).toString();
    }

    /**
     * 这批片段里有没有<b>任何一个</b>能标出来的出处
     *
     * <p>🔴 判据直接复用 {@link #sourceLine}，不另写一条：二者一旦分叉，
     * 就会出现「要求模型标出处、但一段来源行都没给它」—— 那时它只会编一个。
     * 「segments 非空」与「有可标出处」不等价：用 {@code createDocumentByContent} 三参版
     * 灌进自定义 Agent 专属知识库的文档，{@code url} 是 {@code local://<名>}（不是能点开的地址），
     * 标题则从正文首个一级标题现算 —— 所以可能只有标题、也可能什么都没有。
     */
    private static boolean hasAnySource(List<AiRagSegmentRespDTO> segments) {
        for (AiRagSegmentRespDTO segment : segments) {
            if (!sourceLine(segment).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 一段的来源行：{@code 【来源】<标题> <地址>}，两者缺一就只写有的那个，都没有则返回空串
     *
     * <h3>🔴 为什么它<b>不进</b>围栏</h3>
     * 围栏（{@link PromptDataFence}）划的是「这段是不可信数据」。来源行是<b>平台从库里查出来、
     * 拼给模型看的元信息</b>，与 {@code 【片段N】} 同级 —— 放进数据段里反而会读成
     * 「手册正文的一部分」，模型可能把它当内容复述出来。
     *
     * <h3>🔴 但标题与地址来自 DB，所以换行必须剥掉</h3>
     * 本类的框标题<b>全部以 {@code \n} 结尾</b>（{@code 手册片段：\n}、{@code 问题：\n}、{@code 【片段N】\n}）。
     * 剥掉换行与控制字符之后，标题/地址里无论写什么都拼不出一个「独立成行的框标题」，
     * 也就伪造不出结构 —— 这与「{@code 【片段N】} 只是排版」是同一套保证，不是新增的信任假设。
     *
     * <h3>🔴 地址只认 http(s)</h3>
     * 库里存着 {@code local://<文档名>} 这种「内容来自本地」的标记（三参版
     * {@code createDocumentByContent} 写的），它<b>不是用户能点开的地址</b>。
     * 把它递给模型，产出的就是一个点了没反应的死链 —— 比不给链接更坏。
     */
    private static String sourceLine(AiRagSegmentRespDTO segment) {
        if (segment == null) {
            return "";
        }
        String title = flatten(segment.getDocumentTitle());
        String url = flatten(segment.getDocumentUrl());
        if (url != null && !StrUtil.startWithAnyIgnoreCase(url, "http://", "https://")) {
            url = null;
        }
        if (title == null && url == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(SEGMENT_SOURCE_PREFIX);
        if (title != null) {
            sb.append(title);
        }
        if (url != null) {
            if (title != null) {
                sb.append(' ');
            }
            sb.append(url);
        }
        return sb.append('\n').toString();
    }

    /** 压成一行：剥掉换行与其余控制字符，再限长。空白串返回 null，便于「两者缺一」的判断 */
    private static String flatten(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 0x20 && c != 0x7F) {
                sb.append(c);
            }
        }
        String flat = sb.toString().strip();
        return flat.isEmpty() ? null : StrUtil.maxLength(flat, SOURCE_MAX_LENGTH);
    }

    /**
     * 逐段追加：排版标题 + 来源行 + 强围栏
     *
     * <p>🔴 序号同时出现在两处、用途完全不同：
     * {@code 【片段1】} 是给人看的排版（可被数据伪造，但伪造了也只是落在围栏内部，撑不开结构）；
     * 定界符上的 {@code segment_1} 是<b>平台生成</b>的标签，且标签本身也过一遍中和
     * （见 {@link PromptDataFence#fence(String, String)}），所以标签这一侧也造不出结构。
     *
     * <p>来源行放在标题之后、围栏之前，理由见 {@link #sourceLine}。
     *
     * @param labelPrefix 标签前缀；当轮传 {@link #LABEL_SEGMENT}，历史回灌传 {@link #LABEL_HISTORY_SEGMENT}
     *                    —— 分开是为了让读日志的人一眼看出「这段是本轮检索的还是上一轮留下的」
     */
    private void appendSegments(StringBuilder sb, List<AiRagSegmentRespDTO> segments, String labelPrefix) {
        for (int i = 0; i < segments.size(); i++) {
            AiRagSegmentRespDTO segment = segments.get(i);
            sb.append(SEGMENT_HEADING_PREFIX).append(i + 1).append(SEGMENT_HEADING_SUFFIX)
                    .append(sourceLine(segment))
                    .append(PromptDataFence.fence(labelPrefix + "_" + (i + 1), segment.getContent()))
                    .append("\n\n");
        }
    }

    /**
     * 按 token 预算截断历史，并回灌历史轮的手册片段
     *
     * <h3>为什么从后往前</h3>
     * 要保的是<b>最近</b>的上下文 —— 用户的「那子表呢」指的是上一句，不是二十轮之前。
     * 从后往前累加，加满就停，然后反转回正序。
     *
     * <h3>为什么按「轮」而不是按「条」丢弃</h3>
     * 丢到一半可能留下一个孤零零的 assistant 回答而它对应的 user 提问被丢了，
     * 模型会看到一句没头没尾的回答。这里以<b>成对</b>为单位向前推进，
     * 预算不够就整轮不要。
     */
    private List<AiChatReqDTO.Message> truncateByTokenBudget(
            List<AgentMessageDO> history, int tokenBudget) {
        if (CollUtil.isEmpty(history)) {
            return Collections.emptyList();
        }
        List<AiChatReqDTO.Message> reversed = new ArrayList<>();
        int used = 0;
        int dropped = 0;
        // 从最后一条往前走
        for (int i = history.size() - 1; i >= 0; i--) {
            AgentMessageDO message = history.get(i);
            // 失败或被取消的轮次不进上下文：它们的 content 要么为空、要么是半截话，
            // 回灌进去只会干扰模型。但它们仍然留在库里与计量日志里
            if (!StrUtil.equals(message.getStatus(), "SUCCESS")
                    || StrUtil.isBlank(message.getContent())) {
                continue;
            }
            String content = renderHistoryContent(message);
            int cost = AgentTokenEstimator.estimate(content);
            if (used + cost > tokenBudget) {
                dropped = i + 1; // 这条及它之前的全部丢弃
                break;
            }
            used += cost;
            reversed.add(AiChatReqDTO.Message.of(message.getRole(), content));
        }
        if (dropped > 0) {
            log.debug("[truncateByTokenBudget][历史共({})条，按预算({})保留({})条]",
                    history.size(), tokenBudget, reversed.size());
        }
        Collections.reverse(reversed);
        return reversed;
    }

    /**
     * 渲染一条历史消息
     *
     * 🔴 助手消息要把<b>当时检索到的片段</b>一并带上 —— 这就是「不同一」说的回灌。
     * 只回灌助手侧：用户提问本来就不带片段。
     *
     * <p>🔴 B2-3：<b>历史正文也过围栏</b>，理由见类注释「历史轮也要包裹」那一节 ——
     * 只包当轮不包历史，等于给注入串留了一条「先落库、下一轮以无围栏的 user 消息回来」的二阶通道。
     * 助手侧的回答同样按数据包裹：它是模型产物，可能是上一轮被操纵后吐出来的，
     * 回灌时不该重新获得「指令」的分量。
     *
     * <p>🔴 历史里 {@code OutputModerator} 打的标记会<b>留在数据段内部</b>，这是想要的结果：
     * 标记本来就是给下游读者看的，围栏只改变「这段是数据」这个身份，不改变它的内容。
     */
    private String renderHistoryContent(AgentMessageDO message) {
        boolean assistant = AgentMessageDO.ROLE_ASSISTANT.equals(message.getRole());
        if (!assistant || StrUtil.isBlank(message.getReferenceJson())) {
            return PromptDataFence.fence(
                    assistant ? LABEL_HISTORY_ANSWER : LABEL_HISTORY_QUESTION, message.getContent());
        }
        List<AiRagSegmentRespDTO> segments = parseReferences(message.getReferenceJson());
        if (CollUtil.isEmpty(segments)) {
            return PromptDataFence.fence(LABEL_HISTORY_ANSWER, message.getContent());
        }
        StringBuilder sb = new StringBuilder(
                PromptDataFence.fence(LABEL_HISTORY_ANSWER, message.getContent()));
        sb.append(HISTORY_REFERENCE_NOTE);
        appendSegments(sb, segments, LABEL_HISTORY_SEGMENT);
        return sb.toString();
    }

    private List<AiRagSegmentRespDTO> parseReferences(String json) {
        try {
            return JSONUtil.toList(json, AiRagSegmentRespDTO.class);
        } catch (Exception ex) {
            // 解析不了就当没有 —— 回灌失败只是少了点上下文，不该让整次对话失败
            log.warn("[parseReferences][引用片段解析失败，本轮不回灌：{}]",
                    StrUtil.maxLength(json, 200));
            return Collections.emptyList();
        }
    }

}
