package cn.enlev.ainb.module.agent.framework.agent.core.security;

import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ChangeManifest;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * LLM 产物投递到下游前的输出审核钩子（方案 §X2 / G26 / D12）
 *
 * <h3>它与 {@link UntrustedInputSanitizer} 是输入/输出两半</h3>
 * 输入侧要防的是「外部内容混进 prompt 指令段」；本类管输出侧——LLM（或 AI 生成的 DSL）产出的内容
 * 会被投递给<b>人</b>看（BPM 审批表单、聊天确认卡……），而审批人正是 D2 里唯一有权拦不可逆动作的人。
 * 未经审核的产物直接出现在审批人眼前，等于把「被操纵的变更说明」送到决策者面前（G26）。
 *
 * <p>🟢 <b>2026-10-07（B2-1）：上面那个 {@code @code} 换成了 {@code @link}。</b>
 * 从 S6 写下这句话到本轮之前，{@code UntrustedInputSanitizer} <b>这个类一直不存在</b>
 * （仓里的 {@code UntrustedInputTracer} 是同名诱饵：它做变更清单的<b>静态</b>污点追踪，一个字符都不改），
 * 所以它是一句<b>指向空气的承诺</b>。现在那半边落地了，承诺成真。
 *
 * <p>🔴 <b>但两半的策略刻意<b>不</b>相同，别把它们合并成一份共用工具方法</b>：
 * 本类（输出侧）刻意保留 ZWNJ/ZWJ、把截断后缀<b>写进值里</b>、上限恒为 {@link #MAX_STRING_LENGTH}；
 * 输入侧那份连 ZWNJ/ZWJ 一起剥（否则 B2-3 的定界符字面中和不可证明完备）、
 * <b>绝不</b>往值里写任何东西（那些值还要去当工具入参、去写业务库）、上限按源分五档走配置。
 * 它们长得像是因为都在处理不可见字符，<b>策略不同是因为值的去处不同</b>——
 * 合并之后任何一侧想调策略都会连带改掉另一侧，差异就在没人注意的时候被抹平了。
 *
 * <h3>投递点接入进度（🟢 2026-10-07 · B2-2 更新，此前这里写的是「本刀只接 formVars 一个下游」）</h3>
 * S6 那一刀只接了最高优先级的 {@code hitl.bpm_approval.formVars}，并把其余下游推给「S12 逐个接入」，
 * 原话是「其余 3 个下游（聊天确认卡<b>等</b>）在 S12 逐个接入」—— 🔴 那个「等」字后面是什么
 * <b>从来没被写下</b>，于是 S12 一直没人知道要接哪几个，本类的生产调用点在整整若干轮里停在 <b>1 个</b>（台账 N24）。
 * B2-2 把口径重新量过并逐个接上：<b>口径 A</b>（四个人可见投递面）4/4，
 * <b>口径 B</b>（全量人可见面清点）8 个面里 7 个已审核、1 个（裸 state SSE）经裁定登记为不审核（总账 N31）。
 * 逐面落点见《流程编排模块重构方案-迭代七-03》§3.5 的 R-10 ②表。
 *
 * <p>🔴 本类的方法一律<b>无状态</b>，可直接复用；新增投递点时不要在这里存调用方信息。
 *
 * <h3>审核做什么（打标，不拦截）</h3>
 * 对 formVars 里的<b>字符串值</b>做三件事，其余类型（数字/布尔）原样透传：
 * <ol>
 *   <li><b>剥不可见字符</b>：C0/C1 控制符、Unicode 双向控制符、零宽空格、行/段分隔符一律删掉。
 *       它们从来不是合法表单值，只用于视觉欺骗（覆盖、换行伪造、双向文本重排）。</li>
 *   <li><b>注入标记</b>：命中 {@link #INJECTION_PATTERNS} 任一模式的前缀 {@link #SUSPICIOUS_PREFIX}。
 *       🔴 是<b>标记</b>不是拦截：拦截会把工作流卡死在挂起态，而审批人正是这道闸口——
 *       标记把「这段文字疑似在操纵你的决定」明明白白摆在审批人眼前，由人来做最终判断。</li>
 *   <li><b>限长</b>：超过 {@link #MAX_STRING_LENGTH} 截断并补「…（已截断）」。审批表单里放一篇
 *       万字长文没有正当用途，却能把真正要审的字段挤出视野。</li>
 * </ol>
 *
 * <p>🔴 **顺序是「剥 → 标记 → 截断」，不是反过来**（S6 复核修正）。截断放在标记之后有两层理由：
 * ① 比对必须发生在剥完不可见字符之后，否则攻击者插一个零宽空格就能把「忽略以上指令」拆成
 * 两段、绕过 {@code contains}；② 比对必须发生在截断之前，否则把注入串放到第 2001 个字符之后
 * 就能既逃过标记、又不会被审批人看到——两个 bug 是同一个顺序错误的两面。
 * 代价是「标记了但载荷已被截掉」这种情况会出现，那是**好的**结果：审批人得到了警告，
 * 载荷却没送到他眼前。
 *
 * <p>🔴 返回值是<b>新建的 map</b>：入参可能是 DSL 里那个活 map（{@code NodeDTO.configMap} 直接返回），
 * 原地改会写回解析后的 DSL（台账 #89）。本类第一行就 {@code new LinkedHashMap}，结构上杜绝这件事。
 * 嵌套的 map/list 同样逐个新建，故入参树在任何深度上都不会被改写。
 *
 * @author 走进新时代
 */
public final class OutputModerator {

    private static final Logger log = LoggerFactory.getLogger(OutputModerator.class);

    /** formVars 字符串值上限；超出截断并显式标记，绝不静默裁掉 */
    public static final int MAX_STRING_LENGTH = 2000;

    /** 疑似注入标记前缀：审批人看到这个前缀，就该把这段文字当「不可信内容」而非流程说明 */
    public static final String SUSPICIOUS_PREFIX = "⚠️【疑似注入】";

    /** 截断标记 */
    public static final String TRUNCATED_SUFFIX = "…（已截断）";

    /** 嵌套结构（map/list/数组）的最大递归深度 */
    public static final int MAX_DEPTH = 8;

    /**
     * 嵌套超过 {@link #MAX_DEPTH} 时补的后缀。
     *
     * <p>为什么要限深：formVars 的作者可能是 AI，深度不受我们控制，无界递归会在
     * 图执行线程里抛 {@code StackOverflowError}——那不是 {@code Exception}，
     * {@code AgentWorkflowExecutionServiceImpl#buildFrozenManifestTree} 那层的 catch 拦不住。
     * 超限的部分保留原值并显式标记，绝不静默丢掉（丢掉就等于「审核过了」的假象）。
     */
    public static final String DEPTH_SUFFIX = "…（嵌套过深，未审核）";

    /**
     * 注入模式。🔴 **每一条都必须能在审批表单里做到近乎零误报**，理由见下面「误报为什么是安全问题」。
     *
     * <h4>误报为什么是安全问题，不只是噪声</h4>
     * 本类是「打标不拦截」，所以一次误报不会卡住流程——但它会把 {@link #SUSPICIOUS_PREFIX}
     * 这个信号贬值。审批人天天看到正常文案被标成「疑似注入」，学会的是**直接无视这个前缀**；
     * 等真注入来的时候，那道唯一的人工闸口已经瞎了。⟹ 收紧规则不是保守，是保住信号的价值。
     *
     * <h4>S6 复核删掉/改掉的两条（连同理由）</h4>
     * 原实现用 {@code contains} 收了裸的 {@code "你现在是"} 与 {@code "you are now"}，
     * 而这两条在**本产品自己的合法文案**里就会命中：审批表单里写「你现在是审批人，请核对金额」
     * 是完全正常的一句话，英文同理（"you are now able to export the report"）。
     * 现在改成「角色覆盖」形状：必须在一个**小句内**落到机器角色词上才算命中，真注入照抓、合法文案放过。
     * 同理删掉裸的 {@code "不要遵守"}（政策类文本里「不要遵守旧版流程」是正常话）。
     *
     * <h4>角色词表刻意不收「系统 / 模型 / 助手」</h4>
     * 这三个词在本产品里描述的是**人**或**业务对象**：「你现在是系统管理员」「数据模型」「AI 助手」
     * 都是正常文案。收了它们就是拿误报换一点微不足道的召回。同理英文侧一律加 {@code \b} 词边界——
     * 不加的话 {@code AI} 会命中 "det<b>ai</b>ls"、"m<b>ai</b>ntain" 这类普通单词。
     *
     * <h4>窗口为什么限定在一个小句内</h4>
     * {@code [^。；！？，\n]{0,12}} 这种「不许跨标点」的窗口是必要的：不限定的话
     * 「你现在是审批人，请核对金额，机器人会帮你」会在 12 字后摸到「机器人」而误报。
     */
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            // ① 指令覆盖：中文。审批表单里不存在正当用途
            Pattern.compile("忽略(以上|之前|上述|前面)(所有)?(的)?指令"),
            Pattern.compile("无视(以上|之前|上述|前面)"),
            Pattern.compile("忘掉(以上|之前|上述)(所有)?(指令|规则|设定)"),
            // ② 指令覆盖：英文（大小写不敏感）
            Pattern.compile("ignore\\s+(all\\s+|any\\s+)?(previous|prior|above|earlier)\\s+instructions",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("disregard\\s+(all\\s+)?(previous|prior|above|earlier)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("forget\\s+(all\\s+)?(previous|prior|above)\\s+(instructions|rules)",
                    Pattern.CASE_INSENSITIVE),
            // ③ 角色覆盖：必须在同一小句内落到**机器**角色词上。
            //    🔴 「你现在是审批人」不命中——那是本产品对审批人说的合法话，见上面「删掉/改掉的两条」
            Pattern.compile("你现在是[^。；！？，\\n]{0,12}?"
                    + "(人工智能|机器人|大模型|语言模型|\\bAI\\b|\\bGPT\\b|\\bChatGPT\\b|\\bClaude\\b|\\bDAN\\b)",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("you\\s+are\\s+now[^.!?;,\\n]{0,24}?"
                    + "(\\bAI\\b|\\bbot\\b|\\brobot\\b|\\bLLM\\b|\\bGPT\\b|\\bChatGPT\\b|\\bClaude\\b|\\bDAN\\b"
                    + "|language\\s+model)",
                    Pattern.CASE_INSENSITIVE),
            // ④ 系统提示词被改写：BPM 审批表单里不存在正当理由
            Pattern.compile("system\\s*prompt", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(系统提示词|系统提示|系统指令)(已|被)?(更新|修改|覆盖|变更)"),
            Pattern.compile("新的(系统提示词|系统指令)"),
            // ⑤ 对话模板特殊记号：伪造角色边界。任何合法表单值都不会含它们
            //    （[INST]/<<SYS>> 刻意区分大小写：那是 Llama 的字面记号，小写形式不是）
            Pattern.compile("<\\|im_(start|end)\\|>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\[/?INST]"),
            Pattern.compile("<<SYS>>|<</SYS>>"));

    private OutputModerator() {
    }

    /**
     * 审核一组 formVars，返回新 map（嵌套结构逐层新建，入参在任何深度上都不被修改）。
     *
     * @param formVars 节点 config.formVars（可为 null）；<b>不会被修改</b>
     * @return 审核结果：新 map + 被标记/截断的**路径**清单（供调用方记日志）。
     *         路径形如 {@code note} / {@code items[2].desc}；🔴 只报路径不改键名——
     *         formVars 的键会绑定到 BPM 表单字段，改写键名等于让那个字段填不进值。
     */
    public static Moderation moderateFormVars(Map<String, Object> formVars) {
        List<String> flagged = new ArrayList<>();
        Map<String, Object> out = moderateMap(formVars, flagged, "", 0);
        return new Moderation(out, flagged);
    }

    /**
     * 审核一个字符串值：剥不可见字符 → 注入标记 → 限长（顺序的理由见类注释）。
     *
     * <p>公开是给单测与各个人可见投递点直接复用；集合入口请用 {@link #moderateFormVars}，
     * 清单入口请用 {@link #moderateManifest}。
     *
     * <p>🔴 <b>刻意不幂等，别拿它「保险起见再过一遍」</b>：命中注入模式的串每过一次就在最前面
     * 多叠一个 {@link #SUSPICIOUS_PREFIX}。同一个值有多个投递通道时（例如聊天回答既要走 SSE、
     * 又要落 {@code agent_message.content}），正确做法是<b>每个通道各自对原文审一次</b> ——
     * 两次独立的单遍审核产出同样的字符串，一遍套一遍则产出两个前缀。
     * 🔴 也别把它改成「已带前缀就跳过」来图省事：那样一个自己以该前缀开头的载荷
     * 会连带逃过<b>剥不可见字符</b>与<b>限长</b>，是拿一个真窟窿换一个假幂等。
     *
     * @param value 待审核的<b>原文</b>，可为 null（原样返回 null）
     */
    public static String moderateString(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = stripInvisibleChars(value);
        boolean suspicious = isSuspicious(cleaned);
        String capped = cleaned.length() > MAX_STRING_LENGTH
                ? cleaned.substring(0, MAX_STRING_LENGTH) + TRUNCATED_SUFFIX
                : cleaned;
        return suspicious ? SUSPICIOUS_PREFIX + capped : capped;
    }

    /** 剥完不可见字符之后，是否命中任一注入模式。公开供单测直接钉规则本身 */
    public static boolean isSuspicious(String cleaned) {
        for (Pattern p : INJECTION_PATTERNS) {
            if (p.matcher(cleaned).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 剥掉不可见字符：C0（U+0000~U+001F，保留 \t\n\r）、DEL+C1（U+007F~U+009F）、
     * Unicode 双向控制符、零宽空格/BOM、行/段分隔符。
     *
     * <p>🔴 **刻意保留 ZWNJ（U+200C）与 ZWJ（U+200D）**：它们在波斯语/印地语系正字法里是有意义的
     * 字符，也是 emoji 组合序列（👨‍👩‍👧）的连接符，剥掉会把合法文本改坏。
     * 双向控制符与零宽空格没有这层正当性，一律剥。S6 原实现只剥 C0/C1，
     * 而它的 javadoc 明写着要防「覆盖、双向文本」——那是**注释承诺了代码没做**，本次补齐。
     */
    private static String stripInvisibleChars(String s) {
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isInvisible(c)) {
                if (sb == null) {
                    sb = new StringBuilder(s.length());
                    sb.append(s, 0, i);
                }
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb == null ? s : sb.toString();
    }

    private static boolean isInvisible(char c) {
        return c < 0x20 && c != '\t' && c != '\n' && c != '\r'   // C0
                || c >= 0x7F && c <= 0x9F                        // DEL + C1
                || c == 0x061C                                   // ARABIC LETTER MARK
                || c == 0x200B                                   // 零宽空格
                || c == 0x200E || c == 0x200F                     // LRM / RLM
                || c >= 0x202A && c <= 0x202E                     // LRE RLE PDF LRO RLO
                || c == 0x2028 || c == 0x2029                     // 行分隔符 / 段分隔符
                || c >= 0x2066 && c <= 0x2069                     // LRI RLI FSI PDI
                || c == 0xFEFF;                                  // BOM / 零宽不换行空格
    }

    /** 递归审核一个 map。返回新 map；被改动的键路径追加进 {@code flagged} */
    private static Map<String, Object> moderateMap(Map<String, Object> in, List<String> flagged,
                                                   String path, int depth) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (in == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : in.entrySet()) {
            String childPath = path.isEmpty() ? e.getKey() : path + "." + e.getKey();
            out.put(e.getKey(), moderateValue(e.getValue(), flagged, childPath, depth));
        }
        return out;
    }

    /**
     * 递归审核一个值。
     *
     * <p>🔴 **必须递归**（S6 复核补的）：{@code formVars} 的契约类型是 {@code T_OBJECT}，
     * 完全可以嵌 map/list。原实现只审顶层 String、其余原样透传 ⟹ 下沉一层的注入串
     * 一次都不经过审核，而 G26 的立论恰恰是「审批人眼前的值不许是未经审核的 LLM 产物」。
     * 浅审等于把部分保证说成了完整保证。
     */
    private static Object moderateValue(Object value, List<String> flagged, String path, int depth) {
        if (value instanceof String s) {
            String moderated = moderateString(s);
            if (!moderated.equals(s)) {
                flagged.add(path);
            }
            return moderated;
        }
        boolean container = value instanceof Map<?, ?>
                || value instanceof Collection<?>
                || value instanceof Object[];
        if (!container) {
            // 数字/布尔/null：注入只能藏在文本里，标量不可能是注入载体 ⟹ 与深度无关，一律原样透传
            return value;
        }
        if (depth >= MAX_DEPTH) {
            // 保留原值 + 显式上报「这一层没审」，绝不静默丢（丢了的看起来像「审核过了」）。
            // 🔴 这里返回的是**原引用**、没有拷贝：能走到这一步说明结构已经嵌套 8 层，
            //    继续拷下去的唯一动机是防御自引用环，而 formVars 来自 JSON 解析（环表达不出来），
            //    所以省掉这次拷贝；#89 要保证的是「我们不改入参」，透传引用并不违反它。
            flagged.add(path + DEPTH_SUFFIX);
            return value;
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String key = e.getKey() == null ? "null" : String.valueOf(e.getKey());
                out.put(key, moderateValue(e.getValue(), flagged, path + "." + key, depth + 1));
            }
            return out;
        }
        if (value instanceof Collection<?> coll) {
            List<Object> out = new ArrayList<>(coll.size());
            int i = 0;
            for (Object o : coll) {
                out.add(moderateValue(o, flagged, path + "[" + i + "]", depth + 1));
                i++;
            }
            return out;
        }
        Object[] arr = (Object[]) value;
        List<Object> out = new ArrayList<>(arr.length);
        for (int i = 0; i < arr.length; i++) {
            out.add(moderateValue(arr[i], flagged, path + "[" + i + "]", depth + 1));
        }
        return out;
    }

    /**
     * 审核结果：新 map + 被改动的**路径**（截断、标记或嵌套过深，供日志）。
     *
     * <p>路径而非键名：递归之后一个改动可能发生在 {@code items[2].desc}，
     * 只报顶层键 {@code items} 会让人以为整条列表都有问题。
     */
    public record Moderation(Map<String, Object> variables, List<String> flaggedKeys) {
    }

    /**
     * 审核一份变更清单（manifest），返回<b>审核过的副本</b>；入参对象在任何深度上都不被修改。
     *
     * <h3>🔴 为什么必须返回副本，而不是原地改</h3>
     * 因为 {@code /workflow-def/validate} 那条通道上，<b>同一个 manifest 实例既要交给校验、又要放进响应</b>
     * （{@code AgentWorkflowDefController#validateDsl} 里先 {@code buildManifestOrNull} 拿到它，
     * 传给 {@code validateDsl}，再 {@code respVO.setManifest(...)}）。原地改会让校验读到被审核过的文字。
     *
     * <h3>🔴 为什么审核点必须在「投递边界」，不能在 manifest 的构建处</h3>
     * 构建处（{@code AgentWorkflowDefServiceImpl#buildManifest}）看着是更漂亮的单点收口，
     * 但它是<b>发布闸门的输入</b>：{@code WorkflowDslValidator#foldUntrustedWrites} 会拿
     * {@code toolArgSink.equals(flow.getSinkKind())} 做<b>字符串相等比较</b>、
     * 拿 {@code nodeById.get(flow.getSinkNodeId())} 做<b>map 键查找</b>。
     * 审核会剥不可见字符、会加 {@link #SUSPICIOUS_PREFIX}、会在 2000 字符处截断 ——
     * 任何一样落到这两个字段上，比较就<b>静默失配</b>，那条校验接着 {@code continue} 跳过，
     * 结果是<b>本该拒的发布被放行</b>。那是 fail-open，比不审核更糟。
     * 故规则是：<b>校验永远读原始 manifest，只有交给人看的那一份才审核。</b>
     *
     * <p>实现走 JSON 往返（POJO → Map → 审核 → POJO），而不是逐字段手抄：
     * {@code ChangeManifest} 有 8 个嵌套类、四十来个字段，手抄一份副本等于造出第二份会随字段演进而漂移的拷贝。
     * 往返的代价是多两次序列化，只发生在给人看的端点上，可接受。
     * 🔴 「干净的清单必须原样往返」这条性质由单测钉住（见 {@code OutputModeratorTest}）——
     * 往返若会丢字段，那条测试会红，而不是等到线上少了一栏才发现。
     *
     * @param manifest 原始清单，可为 null（{@code buildManifestOrNull} 建不出来时就是 null）
     * @return 审核结果：副本 + 被改动的路径清单（供调用方记 warn）；入参为 null 时两项都为 null/空
     */
    public static ModeratedManifest moderateManifest(ChangeManifest manifest) {
        if (manifest == null) {
            return new ModeratedManifest(null, List.of());
        }
        Map<String, Object> tree = JsonUtils.parseObject(JsonUtils.toJsonString(manifest),
                new TypeReference<Map<String, Object>>() {
                });
        Moderation moderated = moderateFormVars(tree);
        ChangeManifest copy = JsonUtils.parseObject(
                JsonUtils.toJsonString(moderated.variables()), ChangeManifest.class);
        return new ModeratedManifest(copy, moderated.flaggedKeys());
    }

    /**
     * 清单审核结果：副本 + 被改动的路径（同 {@link Moderation} 的语义，只是载体是 POJO 而非 Map）。
     */
    public record ModeratedManifest(ChangeManifest manifest, List<String> flaggedKeys) {
    }

    /**
     * {@link #moderateManifest} 的便捷版：审核 + 有改动就自己记一条 warn，返回副本。
     *
     * <p>🔴 为什么日志记在本类而不是各个调用方：清单的<b>人可见投递通道有 4 条</b>
     * （BPM 审批注入、聊天确认卡、{@code /planner/workflow-draft}、{@code /workflow-def/manifest}
     * 与 {@code /validate}），每条都要「审核 + 有标记就 warn」。
     * 四个调用方各抄一遍这六行，是本仓反复出现的那种漂移源 —— 少抄一句 warn，
     * 那条通道上的审核就变成了<b>发生了但没人知道</b>，事后无法区分「没触发」与「触发了没记」。
     * 收进本类的代价是日志按 {@code OutputModerator} 归类、不带调用方类名；
     * 换来的是四条通道<b>结构上不可能</b>漏记。定位具体请求靠 {@code flaggedKeys} 里的路径 + traceId。
     *
     * <p>🔴 只记路径、<b>绝不记内容</b>：清单文字本身就是 AI 产物，把它整条打进日志
     * 等于在日志里再造一个投递面（N32 那笔账就是这个形状：拦住了流程，没拦住日志）。
     *
     * @param manifest 原始清单，可为 null（{@code buildManifestOrNull} 建不出来时就是 null）
     * @return 审核后的副本；入参为 null 时返回 null
     */
    public static ChangeManifest moderateManifestForDisplay(ChangeManifest manifest) {
        ModeratedManifest moderated = moderateManifest(manifest);
        if (!moderated.flaggedKeys().isEmpty()) {
            log.warn("[moderateManifestForDisplay][变更清单经输出审核被标记/截断，交给人看的是审核后的副本 paths({})]",
                    moderated.flaggedKeys());
        }
        return moderated.manifest();
    }

}
