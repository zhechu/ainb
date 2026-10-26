package cn.enlev.ainb.module.agent.framework.agent.core.react;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReAct 式 JSON 协议编解码（planner 与 agent_loop 共用）
 *
 * <h3>为什么要抽出来共用</h3>
 * 平台没有原生 function-calling（{@code AiChatRespDTO} 不带 tool_calls），所有「让模型调工具」都靠
 * <b>约定一个 JSON 协议、再从模型的自然语言输出里把 JSON 抠出来</b>。这套抠取 + 解析失败自修正的逻辑
 * 微妙且带实测疤（「少一个 &#125; 连错三轮、烧光预算」），已在线性 planner 的多轮实测里打磨过一遍
 * （该 planner 于增量7b 删除，抠取 + 诊断逻辑沉淀于此，供 plan-as-workflow 生成器与 agent_loop 共用）。
 * agent_loop（P2 增量3）是同一类 ReAct agent，若各写一份必然漂移 —— 故抽到这里，两处共用同一套解析与诊断。
 *
 * <h3>只抽「通用原语」，不抽「协议文案」</h3>
 * 括号配平抠取、出错位置回灌、截断 vs 写坏的诊断，是与具体协议无关的通用能力，放这里。
 * 而「type 可以是哪几种」「plan 的 steps 只能放写操作」这类<b>各调用方自己的协议文案</b>，
 * 由调用方通过 {@code formatSpec}/{@code extraGuidance} 入参传进来 —— planner 有 plan 类型，
 * agent_loop 没有（只 tool_calls/answer），文案不能写死在这里。
 *
 * <h3>不依赖任何 Spring / 跨模块 DTO</h3>
 * 纯静态工具。{@link #diagnose} 只收 {@code Integer completionTokens} 而非 {@code AiChatRespDTO.Usage}，
 * 是为了不把 ai 模块的 DTO 耦进这层通用编解码。
 *
 * @author 走进新时代
 */
public final class ReactJsonCodec {

    private ReactJsonCodec() {
    }

    /**
     * 从 Hutool 的报错里取出「第几个字符」
     *
     * 形如 {@code ... at 1872 [character 1814 line 2]}：
     * {@code at} 后面那个是<b>整段文本</b>的绝对下标，
     * {@code character} 是行内列号 —— 要的是前者。
     */
    private static final Pattern PARSE_POSITION = Pattern.compile("at (\\d+) \\[character");

    /**
     * 从 Jackson 的报错里取出「第几列」——<b>最后的兜底</b>，只在拿不到行号时才用
     *
     * <p>🔴 为什么必须单列一条 Jackson 的：{@code DslParser} 走的是 Jackson（{@code ObjectMapper.readValue}），
     * 而 {@link #PARSE_POSITION} 只认 Hutool 的 {@code at N [character} 写法 —— 两者对不上，
     * 结果 planner 解析失败时永远匹配不到位置，那段「出错点附近原文」从来没回灌给模型，
     * 模型每轮都在同一处把括号写坏、烧光轮次仍出不了草稿（实测疤：项目审核流程连错 6 轮）。
     *
     * <p>🔴 只有<b>单行</b> JSON 时 {@code column} 才恰好等于绝对下标（{@code line: 1}）。
     * 模型换行排版时必须走 {@link #JACKSON_LINE_COLUMN} 按行换算，否则会把行内列号当绝对下标，
     * 指着 JSON 开头说「这里错了」。所以本条排在它后面。
     *
     * <p>🔴 多个匹配时取<b>最后一个</b>，不是第一个。形如
     * {@code Unexpected close marker '}': expected ']' (for Array starting at [...; column: 20])
     * at [...; column: 45]}：前者是「数组从这里开始」的<b>辅助</b>定位，
     * 后面那个 {@code at [...]} 才是 Jackson 附加的出错点。
     */
    private static final Pattern JACKSON_POSITION = Pattern.compile("column: (\\d+)");

    /**
     * 从 Jackson 的报错里同时取出「第几行、第几列」
     *
     * <p>形如 {@code ... was expecting double-quote to start field name at
     * [Source: REDACTED (...); line: 18, column: 3] (through reference chain: ...)}。
     * 逗号写成可选，是因为不同 Jackson 版本这里可能没有逗号（{@code line: 18 column: 3}）。
     *
     * <p>🔴 <b>这才是生产上真正会走到的那条路</b>，而它必须靠行号换算、不能只读列号：
     * {@code StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION} 在本项目是<b>关闭</b>的
     * （报错里写着 {@code Source: REDACTED}），Jackson 因此<b>不会</b>输出 {@code (char N)} 绝对下标 ——
     * 也就是说 {@link #JACKSON_CHAR_OFFSET} 那条分支在真实路径上永远匹配不到。
     * 实测疤（2026-10-04 无头建表编排 run2）：模型第 1 轮产的是<b>带缩进换行</b>的 JSON（36 行），
     * 报错是 {@code line: 18, column: 3}；只读 {@code column} 会把它当成绝对下标 2，
     * 回灌窗口于是截了 JSON 开头 60 个字符（{@code {"dslVersion":"1.0","id":…}），
     * 而真正的出错点在绝对下标 <b>2087</b> —— 差了整整两千个字符，
     * 模型收到一份「指着开头说这里错了」的提示，自然改不到点子上。
     * 按行号走位换算后，2087 精确落在下一个节点（id=approve）开头那个 &#123; 上，
     * 与它第 2 轮压成单行时报的 {@code line: 1, column: 2045} 是<b>同一处错</b>
     * —— 两轮排版不同、错处相同，正好互相印证。
     *
     * <p>🔴 多个匹配时要取<b>最后一个</b>。{@code MismatchedInputException} 的原文形如
     * {@code Unexpected close marker '}': expected ']' (for Array starting at [...; line: 1, column: 20])
     * at [...; line: 1, column: 45]}：前一个是「数组从这里开始」的<b>辅助</b>定位，
     * 后面那个 {@code at [...]} 才是 Jackson 附加的出错点。
     * （{@link #JACKSON_POSITION} 的旧注释把这两者说反了，一并纠正。）
     */
    private static final Pattern JACKSON_LINE_COLUMN =
            Pattern.compile("line: (\\d+),?\\s+column: (\\d+)");

    /**
     * 认「本该写字段名的位置却出现了别的东西」这类报错
     *
     * <p>这类报错的位置有个反直觉的性质：<b>它是解析器察觉的位置，不是出错的位置</b>。
     * 前面某个对象少闭合了一个 {@code &#125;}，解析器以为自己还在对象里，
     * 于是把下一个数组元素的 {@code &#123;} 当成了字段名的开头 —— 报错点在<b>后</b>，
     * 缺的括号在<b>前</b>，两者可以差上千字符。
     */
    private static final Pattern FIELD_NAME_EXPECTED =
            Pattern.compile("expecting (?:double-quote to start field name|a field name)");

    /**
     * 从 Jackson 的报错里取出<b>绝对字符下标</b>
     *
     * <p>形如 {@code Expecting property name ...: line: 18 column: 3 (char 1999)}：
     * 括号里那个 {@code char N} 是整段文本的绝对下标，比行号+列号更省事，所以有它就优先用。
     *
     * <p>🔴 <b>但它在生产上匹配不到</b>，别把它当成多行 JSON 的解法。
     * {@code char N} 只在 {@code StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION} 打开时才输出，
     * 而本项目是<b>关闭</b>的 —— 实测报错原文写着 {@code [Source: REDACTED
     * (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled); line: 18, column: 3]}，
     * 通篇没有 {@code (char}。
     *
     * <p>实测疤（2026-10-04）：曾经以为加上这条就修好了「多行 JSON 把行内列号当绝对下标」，
     * 还配了一条 {@code (char 1999)} 的测试 —— 那条测试钉住的是一条<b>生产上走不到</b>的路径，
     * 真缺陷（只有 {@code line/column}、且是多行）原封不动地留着，第 1 轮照样被指着开头说「这里错了」。
     * 真正修掉它的是 {@link #JACKSON_LINE_COLUMN}（按行号走位换算）。
     * 这条保留是因为「有绝对下标就该用它」本身没错，换厂商/换配置就可能带上；
     * 但它的测试必须和「生产形态」的测试<b>并存</b>，不能只留它一条给人虚假的安全感。
     */
    private static final Pattern JACKSON_CHAR_OFFSET = Pattern.compile("\\(char (\\d+)\\)");

    /**
     * 回灌给出错位置前后各多少个字符
     *
     * 给太少看不出结构（少一个括号往往要看到上一层才看得出来），
     * 给太多等于把整份 JSON 再发一遍 —— 那是每轮都要重付的 token。
     */
    private static final int HINT_WINDOW = 60;

    /** 回灌文本里标记出错点的符号 */
    private static final String ERROR_MARK = " ⟵出错点⟶ ";

    /**
     * 从一段可能夹杂着说明文字的输出里，抠出第一个<b>配平的</b> JSON 对象
     *
     * <p>不能简单地取第一个 {@code &#123;} 到最后一个 {@code &#125;} ——
     * 模型在 JSON 后面再补一句「以上计划请确认」的话，那样会把尾巴一起吞进来。
     * 也不能不管字符串内部：建表注释里出现 {@code &#125;} 是完全可能的。
     *
     * @return 抠出的 JSON 与它是否配平；连 {@code &#123;} 都找不到时返回 null。
     *         不配平时把从 {@code &#123;} 到结尾的部分一并带回，
     *         好让调用方能识别出「这是被截断」而不是「这是乱码」
     */
    public static ExtractedJson extractJsonObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return new ExtractedJson(text.substring(start, i + 1), true);
                }
            }
        }
        // 没配平：多半是被 maxTokens 截断了
        return new ExtractedJson(text.substring(start), false);
    }

    /**
     * 把<b>裸写在字符串外</b>的 {@code {{...}}} 占位符补上引号：{@code "id":{{$.x}}} → {@code "id":"{{$.x}}"}
     *
     * <h3>🔴 这是「修掉」而不是「回灌让模型自己改」，因为回灌救不了它</h3>
     * 实测（同一需求连跑两次、且第二次是故障转移后的弱模型）模型把节点入参写成
     * {@code "args":{"id":{{$.formDraft.id}}}} —— 占位符没加引号，整份 DSL 不是合法 JSON。
     * 而 {@link #diagnose} 只看得见「括号不配平/解析失败」，{@code correctionFor} 于是回灌
     * 「请重数嵌套层级」，那与真实错因<b>毫不相干</b>：模型一字不差重出同一份，
     * 一路撞到定点检测才止损，用户看到「轮次耗尽」。这正是本类注释里记着的那条实测疤
     * （窗口里没有一处看着是错的 ⇒ 自修正变成瞎猜）在另一种错因上的复现。
     *
     * <p>意图没有歧义，所以可以确定性地修：占位符在 JSON 里<b>只能</b>当字符串值，
     * {@code {{...}}} 出现在字符串外恒为非法，补引号不可能把一份本来合法的 JSON 改坏。
     * 补完的形态也正是运行期 {@code StateTemplateResolver} 认的那种（模板替换只作用于字符串值）。
     *
     * <p>⚠️ 必须<b>逐字符跟踪是否在字符串内</b>，不能用正则一把梭：
     * {@code "prompt":"汇总 {{$.out}}"} 这种已经引好的占位符再补一层就变成
     * {@code ""{{$.out}}""}，把本来对的改错。转义（{@code \"}）也要吃掉，否则串尾判断错位。
     *
     * @param json 抠出来的 JSON 文本（见 {@link #extractJsonObject}）
     * @return 补好引号的文本；不含 {@code {{} 时原样返回，不额外分配
     */
    public static String quoteBarePlaceholders(String json) {
        if (json.indexOf("{{") < 0) {
            return json;
        }
        StringBuilder sb = new StringBuilder(json.length() + 32);
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                sb.append(c);
                if (c == '\\' && i + 1 < json.length()) {
                    sb.append(json.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                sb.append(c);
                continue;
            }
            if (c == '{' && i + 1 < json.length() && json.charAt(i + 1) == '{') {
                // 占位符体内不含 } （与 StateTemplateResolver#PLACEHOLDER 的 [^}]+? 同口径），
                // 故第一个 }} 就是收尾；找不到收尾说明这份文本另有毛病，交给解析器报错
                int end = json.indexOf("}}", i + 2);
                if (end > 0) {
                    sb.append('"').append(json, i, end + 2).append('"');
                    i = end + 1;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 解析失败时回灌给模型的提示：带上出错位置附近的那一小段原文
     *
     * <h3>🔴 只说「无法解析」等于什么都没说</h3>
     * 实测连续三轮都是<b>同一个位置少一个 &#125;</b>：模型把
     * {@code "params":{"model":{…}}} 写成了 {@code "params":{"model":{…}}，
     * 于是后一个 step 变成了嵌套对象。每轮回灌的都只是
     * 「你刚才的回复无法解析，请只输出 JSON」，它就照原样再错一遍，
     * 预算烧光，用户看到「AI 尝试了 N 轮仍未能给出可执行的方案」。
     *
     * <p>解析器本来就知道错在第几个字符（{@code at 1872 [character 1814 line 2]}），
     * 把这个位置附近的原文摆给它，它一眼就能看出少的是哪个括号。
     *
     * @param formatSpec    本协议的 JSON 形态说明，如 {@code {"type":"tool_calls"|"answer", ...}}
     * @param extraGuidance 调用方追加的协议专属提醒（可空）；如 planner 的「plan 的 steps 只能放写操作」
     */
    public static String parseFailureHint(String text, Exception ex, String formatSpec, String extraGuidance) {
        String message = StrUtil.nullToEmpty(ex.getMessage());
        StringBuilder hint = new StringBuilder("你刚才的回复不是合法的 JSON：").append(message).append('。');
        Integer pos = locateErrorPosition(message, text);
        if (pos != null) {
            int from = Math.max(0, pos - HINT_WINDOW);
            int to = Math.min(text.length(), pos + HINT_WINDOW);
            hint.append("出错位置附近是：…").append(text, from, pos)
                    .append(ERROR_MARK).append(text, pos, to).append("…。");
            hint.append(missingCloserHint(message, text, pos));
        }
        hint.append("请<b>只</b>输出一个 JSON 对象，不要任何解释文字、不要代码围栏，")
                .append("格式为 ").append(formatSpec).append('。');
        if (StrUtil.isNotBlank(extraGuidance)) {
            hint.append(extraGuidance);
        }
        return hint.toString();
    }

    /**
     * 「该写字段名的位置却出现了 &#123;」这类报错的专用补充：说清缺的括号在出错点<b>之前</b>
     *
     * <p>🔴 对这类错，光给出错点附近 60 字符是<b>无效回灌</b>。
     * 实测疤（2026-10-04 无头建表编排 run2）：模型把 tool 节点的
     * config→args→model→columns[] 四层嵌套少数了一个 &#125;
     * （闭合了 model、args、config，唯独漏了节点自己），
     * 而 Jackson 要到 <b>900 字符之后</b>才察觉 —— 报错点是下一个节点开头那个 &#123;。
     * 于是回灌窗口给模型看到的是：
     * <pre>…false&#125;]&#125;,"outputVar":"preview_result"&#125;&#125;, ⟵出错点⟶ &#123;"id":"approve","type":"hitl.bpm_approval"…</pre>
     * 窗口里<b>没有一处看起来是错的</b>：左边是个正常的节点结尾，右边是个完美的新节点。
     * 模型无从下手，第 3 轮一字不差地重出同一份（4038 字符全等），一路撞到定点检测才止损。
     *
     * <p>补的不是「更宽的窗口」—— 那要每轮多烧上千 token，而且未必框得准。
     * 补的是<b>一个模型能自己核对的数</b>：出错点之前未闭合的 &#123; 与 [ 各有几个。
     * 在数组里开始一个新元素时，未闭合的 &#123; 本该恰好等于它的对象父层数
     * （根级数组如 nodes 就是 1 个：只有根对象）；多出来的就是前面漏掉的 &#125;。
     * 实测这份坏 JSON 在出错点之前是「未闭合 &#123; = 2、[ = 1」，正好指出多了一个。
     *
     * <p>拿不到位置、或数不出多开的花括号时返回空串 —— 宁可不补，
     * 也不给一个假的定位（先前 790/8000 那桩假案的教训：错诊断比没诊断更害人）。
     */
    private static String missingCloserHint(String message, String text, int pos) {
        if (!FIELD_NAME_EXPECTED.matcher(message).find()) {
            return "";
        }
        String prefix = text.substring(0, Math.min(pos, text.length()));
        int braces = countChar(prefix, '{') - countChar(prefix, '}');
        if (braces <= 1) {
            return "";
        }
        int brackets = countChar(prefix, '[') - countChar(prefix, ']');
        return "🔴 但这个位置是解析器**察觉**到不对的地方，**不是**缺括号的地方："
                + "「该写字段名的位置却出现了 {」几乎总是**前面某个对象少闭合了一个 }**，"
                + "解析器以为自己还在对象里，就把下一个元素的 { 当成了字段名的开头。"
                + "数一下出错点**之前**的括号：未闭合的 { 有 " + braces + " 个、[ 有 " + brackets + " 个。"
                + "你在此处是要开始一个新的数组元素，未闭合的 { 本该恰好等于它的对象父层数"
                + "（根级数组如 nodes 就是 1 个），所以前面漏掉了 " + (braces - 1) + " 个 }。"
                + "请回头逐个核对出错点**之前**每个刚结束的嵌套结构闭合了几层"
                + "（config→args→model→columns[] 这种四层嵌套最容易少数一层），"
                + "别去改出错点附近那一段 —— 那一段本身是对的。";
    }

    private static int countChar(String text, char target) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == target) {
                count++;
            }
        }
        return count;
    }

    /**
     * 从解析异常里定位出错字符下标，供 {@link #parseFailureHint} 截取「出错点附近原文」
     *
     * <p>依次试四种写法，从最权威到最退化：
     * <ol>
     *   <li>Hutool 的 {@code at N [character}——N 就是绝对下标；</li>
     *   <li>Jackson 的 {@code (char N)}——绝对下标，但本项目 {@code INCLUDE_SOURCE_IN_LOCATION}
     *       是关的，生产上匹配不到（见 {@link #JACKSON_CHAR_OFFSET}）；</li>
     *   <li>Jackson 的 {@code line: L, column: C}——<b>生产上真正走到的那条</b>，
     *       按行号走位换算成绝对下标；</li>
     *   <li>光秃秃的 {@code column: N}——只在单行 JSON 时才等于绝对下标，最后的兜底。</li>
     * </ol>
     *
     * <p>四种都匹配不到（厂商换了报错格式、或异常没带位置）时返回 {@code null}，
     * 调用方就退化成不带定位的通用提示 —— 不阻断，只是少给模型一个锚点。
     *
     * <p>Jackson 的行列号是<b>基于抠出来的那段 JSON</b>，而回灌窗口截的是<b>整段原文</b>，
     * 两者起点可能差几个字符（模型在 JSON 前面写了解释文字）。列号上这点偏移有
     * {@link #HINT_WINDOW} 的 60 字符冗余兜着；行号上则可能整体错位，
     * 但 {@link #lineColumnToOffset} 在行号超出原文行数时会返回 {@code null} 交回退化路径，
     * 不会算出一个离谱的下标。
     */
    private static Integer locateErrorPosition(String message, String text) {
        Matcher matcher = PARSE_POSITION.matcher(message);
        if (matcher.find()) {
            return clampOffset(Integer.parseInt(matcher.group(1)), text.length());
        }
        matcher = JACKSON_CHAR_OFFSET.matcher(message);
        if (matcher.find()) {
            return clampOffset(Integer.parseInt(matcher.group(1)), text.length());
        }
        int[] lineColumn = findLastLineColumn(message);
        if (lineColumn != null) {
            Integer offset = lineColumnToOffset(text, lineColumn[0], lineColumn[1]);
            if (offset != null) {
                return offset;
            }
        }
        Integer column = findLastColumn(message);
        if (column != null) {
            // column 是 1-based，转成 0-based 下标
            return clampOffset(column - 1, text.length());
        }
        return null;
    }

    /**
     * 取报错里<b>最后一个</b> {@code line: L, column: C}
     *
     * <p>最后一个才是出错点：{@code MismatchedInputException} 会在原文里先带一句
     * 「(for Array starting at [...; line: A, column: B])」作为辅助定位，
     * 然后才是 Jackson 附加的 {@code at [...; line: L, column: C]}。
     */
    private static int[] findLastLineColumn(String message) {
        Matcher matcher = JACKSON_LINE_COLUMN.matcher(message);
        int[] last = null;
        while (matcher.find()) {
            last = new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
        }
        return last;
    }

    /** 取报错里<b>最后一个</b> {@code column: N}，理由同 {@link #findLastLineColumn} */
    private static Integer findLastColumn(String message) {
        Matcher matcher = JACKSON_POSITION.matcher(message);
        Integer last = null;
        while (matcher.find()) {
            last = Integer.parseInt(matcher.group(1));
        }
        return last;
    }

    /**
     * 把「第 line 行、第 column 列」换算成整段文本的绝对下标（都是 1-based）
     *
     * @return 换算结果；行号超出原文行数时返回 {@code null}，表示这个位置不可信、请走退化路径
     */
    private static Integer lineColumnToOffset(String text, int line, int column) {
        if (line <= 1) {
            return clampOffset(column - 1, text.length());
        }
        int offset = 0;
        for (int seen = 1; seen < line; seen++) {
            int newline = text.indexOf('\n', offset);
            if (newline < 0) {
                return null;
            }
            offset = newline + 1;
        }
        return clampOffset(offset + column - 1, text.length());
    }

    private static int clampOffset(int offset, int textLength) {
        return Math.max(0, Math.min(offset, textLength));
    }

    /**
     * 判断解析失败到底是「被截断」还是「模型自己写坏了」
     *
     * <h3>🔴 判据只能是 completionTokens 有没有顶到 maxTokens，不能靠猜</h3>
     * 两者都表现为「解析失败」，但处理方式完全相反：
     * 截断要调大 maxTokens，写坏了才该去调 prompt。
     *
     * <p>先前用「大括号配平了吗」推断截断，实测报了假案：
     * 一份 2080 字符的输出被判定成「maxTokens(8000) 不够」——
     * 那次 completionTokens 只有 790，离上限远得很，
     * 真实原因是模型少写了一个 {@code &#125;}。
     * 照着假诊断去调大 maxTokens，调多大都不会好；
     * 更糟的是它把注意力从真问题上引开了。
     *
     * <p>配平与否仍然有用，但只作为<b>佐证</b>写进日志，不作为结论。
     *
     * <h3>🔴 但这个判据在思考模型上<b>本身就不成立</b>，读它的结论要留神</h3>
     * 思考模式下 DashScope 的 {@code max_tokens} 只约束<b>回复</b>、思考另有 budget，
     * 回传的 {@code completion_tokens = 思考 + 回复}。于是这个数<b>可以超过 maxTokens</b>。
     * 实测（2026-10-04 无头建表编排 run2）：同一轮编排里三轮 completion 是 7977 / 3783 / 12315，
     * 而 maxTokens=8000 —— 12315 &gt; 8000 就是铁证；
     * 另一条独立证据是定点检测抓到第 2、3 轮内容<b>一字不差</b>，completion 却是 3783 与 12315，
     * 答案相同则 token 必相同，差值只能来自思考。
     *
     * <p>🔴 那批数是在「{@code AiUtils.buildChatOptions} 对通义硬开 {@code enableThinking(true)}」
     * 的年代采的。该硬开已改成可配置（agent 的 CHAT / INTENT / PLAN 三条路径显式传 false），
     * 但<b>本节的结论不要跟着删</b>，两个理由：
     * ① 本 codec 的另两个调用方 {@code AgentChatToolLoop}（TOOL_LOOP）与
     * {@code AgentLoopGraphBuilder}（ReAct 循环）<b>没有</b>关思考，仍走 ai 模块的按模型默认档，
     * 命中多模态模型时思考照开；② 上面那批实测数不会重新采，重读它们时仍需知道口径。
     * 也就是说：判据在<b>关了思考的路径上</b>恢复成立，在<b>没关的路径上</b>依旧不成立 ——
     * 用之前先确认调用方是哪一档。
     *
     * <p>所以两个方向都会误判：
     * <ul>
     *   <li><b>假阳性</b>：思考占掉一万多、回复完整写完，总数照样 &ge; maxTokens，被判成「截断」；</li>
     *   <li><b>假阴性</b>：回复真被掐在 8000，但若思考很少、总数正好差一点点，被判成「写坏了」。
     *       实测第 1 轮就是 7977/8000（差 0.29%）+ 大括号不配平 —— 到底是哪种，
     *       <b>用现有数据无法区分</b>。</li>
     * </ul>
     *
     * <p>曾经照着 7977 那个数把判据放宽成「达到上限 95% 即算截断」，
     * 后来发现 12315 &gt; 8000 说明这个数根本不受上限约束，逼近上限没有意义，改动已回退。
     *
     * <p><b>真正的修法是拿到 {@code finish_reason}</b>：厂商已经给了权威信号
     * （{@code length} = 截断、{@code stop} = 写完了），而 {@code AiChatRespDTO} 压根没有这个字段
     * —— 全 ai 模块搜不到 {@code finishReason}。把它透传上来之前，
     * 这里的结论只能当启发式看，必须连同大括号配平一起读，不能当事实。
     *
     * @param completionTokens 本轮厂商回传的补全 token 数（含思考），可为 null（厂商没回传）
     * @param maxTokens        本次请求给模型的上限（planner/loop 各自的配置）
     */
    public static String diagnose(ExtractedJson extracted, Integer completionTokens, int maxTokens) {
        if (completionTokens != null && completionTokens >= maxTokens) {
            return "completionTokens(" + completionTokens + ") 已顶到 maxTokens(" + maxTokens
                    + ")，输出确实是被截断的，应调大 max-tokens 配置"
                    + "（注意：通义硬开了 thinking，这个计数含思考 token，可能虚高 —— 见 diagnose 的类内说明）";
        }
        return "completionTokens(" + completionTokens + ") 未顶到 maxTokens(" + maxTokens + ")，"
                + "所以不是截断；大括号" + (extracted.balanced() ? "是配平的" : "不配平")
                + "，属于模型把 JSON 写坏了";
    }

    /**
     * @param json     抠出来的 JSON 文本
     * @param balanced 花括号是否配平。不配平<b>不一定</b>是被截断 ——
     *                 模型少写一个 {@code &#125;} 也一样，
     *                 所以它只作为日志里的佐证，真正的判据看 {@link #diagnose}
     */
    public record ExtractedJson(String json, boolean balanced) {
    }

    /**
     * 一轮模型调用的解析结果
     *
     * <h3>🔴 为什么失败时要把 {@code retryHint} 一起带出来，而不是只返回 null</h3>
     * 只说「解析失败」的话，模型下一轮往往<b>在同一个位置照原样再错一遍</b>。
     * 实测连续三轮都是同一个地方少一个 {@code &#125;}，
     * 预算就这么烧光 —— 而这个失败本来一轮就能自愈。
     *
     * @param json      解析成功的 JSON；失败时为 null
     * @param retryHint 失败时回灌给模型的提示，要说清错在哪；成功时为 null
     */
    public record ModelReply(JSONObject json, String retryHint) {

        public static ModelReply of(JSONObject json) {
            return new ModelReply(json, null);
        }

        public static ModelReply failed(String retryHint) {
            return new ModelReply(null, retryHint);
        }
    }

}
