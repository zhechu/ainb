package cn.enlev.ainb.module.agent.framework.agent.core.engine.support;

import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.WorkflowContext;
import cn.enlev.ainb.module.agent.framework.agent.core.security.PromptDataFence;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 prompt/参数模板里的 {@code {{...}}} 占位符替换为 state 里的值
 *
 * <h3>P2 增量1：从「顶层键」升级到「JSONPath-lite 读导航」</h3>
 * P1 只支持 {@code {{input}}} 这类顶层键；现在还支持 DSL §3.2 的嵌套引用
 * {@code {{$.contract.partyA}}}、{@code {{$.messages[-1]}}} —— 底层委托
 * {@link StatePathNavigator}。<b>裸键与路径统一走导航器</b>（裸键即「从根取一层」），
 * 所以下游 {@code LlmNodeExecutor}/{@code ToolNodeExecutor}/{@code LlmConditionNodeExecutor}
 * 无需改动即自动获得嵌套引用能力。
 *
 * <p>找不到的键/路径替换成空串而不是原样留着 {@code {{xxx}}}：留着会让模型把占位符当正文，
 * 产生「请补充 {{input}}」这类莫名其妙的回答，比空串更难排查。
 *
 * <p>命中的值是对象/数组时落成 JSON 串（而不是 Java 的 {@code toString}），
 * 这样把一整个召回结果数组塞进 prompt 时模型读到的是结构化文本。
 *
 * <h3>🔴 B2-3：本类现在有<b>两个口径</b>，选错一个就是安全洞或者满屏乱码</h3>
 * <ul>
 *   <li>{@link #resolve}：<b>原样替换</b>。产物要给人看（告警文案、进度提示、变更清单里那句人话），
 *       或者产物还要再进一道机器判据。一个字符都不加。</li>
 *   <li>{@link #resolveData}：替换进来的<b>每个值</b>都过 {@link PromptDataFence} 包成数据段。
 *       产物要喂给模型时用这个。</li>
 * </ul>
 *
 * <p>为什么不能在 {@code resolve} 里统一加包裹：模板本身可能就是指令文本，
 * 而 {@code resolve} 的调用方里有一大半拿它去渲染<b>给人看的</b>文案 ——
 * 统一加包裹等于给每条告警短信都套上一对 {@code <<<__XPY_DATA_BEGIN__>>>}。
 * 详细理由见 {@link #resolveData(String, WorkflowContext)} 的方法注释。
 *
 * <p>🔴 这里的 {@code Matcher.quoteReplacement} <b>只是正则反向引用保护，与提示注入无关</b>
 * （它防的是值里含 {@code $1}、{@code \} 时被当成分组引用；其单测也只钉了这两个字符）。
 * 别把它当成「已经有防注入了」—— 本轮开工前实测的 {@code 0/6 达标} 就包括这一处。
 *
 * @author 走进新时代
 */
public final class StateTemplateResolver {

    /**
     * 占位符：{@code {{ expr }}}，expr 允许裸键或路径（含 {@code $ . [ ] -} 与字母数字下划线）。
     * 用 {@code [^}]+?} 惰性捕获到第一个 {@code }} 之前，两侧空白由 {@code \s*} 吃掉。
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^}]+?)\\s*}}");

    private StateTemplateResolver() {
    }

    public static String resolve(String template, WorkflowContext ctx) {
        return resolve(template, ctx == null ? null : ctx.data());
    }

    /**
     * 同 {@link #resolve(String, WorkflowContext)}，但直接对一份裸 state Map 做替换。
     *
     * <p>供 <b>Saga 反做</b>用：补偿发生在流程失败之后，此刻已没有活的 {@link WorkflowContext}，
     * 只有 {@code agent_node_execution} / {@code agent_workflow_execution} 里持久化的 state 快照。
     * 复用同一套 {@code {{...}}} 语法与 {@link StatePathNavigator} 导航，杜绝「执行期与补偿期两套模板口径」。
     */
    public static String resolve(String template, Map<String, Object> state) {
        if (template == null || template.isEmpty()) {
            return template;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String expr = matcher.group(1);
            Object value = StatePathNavigator.navigate(state, expr);
            String text = stringify(value);
            // quoteReplacement：变量值里可能含 $ 或 \，不转义会被当成反向引用
            matcher.appendReplacement(sb, Matcher.quoteReplacement(text));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 同 {@link #resolve(String, WorkflowContext)}，但把<b>替换进来的值</b>包成数据段（B2-3）
     *
     * <h3>🔴 为什么是新开一个变体，而不是在原 {@link #resolve} 里统一加包裹</h3>
     * 因为<b>模板本身可能就是指令文本</b>。{@code resolve} 的调用方里有一大半是拿它去渲染
     * 「告警文案」「进度提示」「变更清单里那句给人看的人话」—— 那些结果的去处是<b>人</b>，
     * 不是模型。在原方法里统一加包裹，等于给每一条告警短信都套上一对
     * {@code <<<__XPY_DATA_BEGIN__>>>}，用户看到的是一堆乱码记号。
     *
     * <p>所以口径是：<b>结果要喂给模型的，调 {@code resolveData}；结果要给人看的，调 {@code resolve}</b>，
     * 由调用方显式选用。这不是「忘了升级」的隐患，而是刻意的分流 ——
     * 两个方法的产物形状差得很远，误用在界面上和日志里都一眼看得出。
     *
     * <h3>包裹的粒度是「每个替换值」，不是「整个渲染结果」</h3>
     * 整段包裹看着省事，但它把<b>模板文字</b>也划进了数据段 —— 而模板是流程作者写的指令，
     * 划进数据段就等于告诉模型「你任务描述里那句话也不用听」。
     * 逐值包裹才真正对上「指令 / 数据分离」这个词：模板留在指令段，只有 state 里的值进数据段。
     *
     * <p>🔴 纯数字与布尔<b>不</b>包裹（判据见 {@link PromptDataFence#needsFence}）：
     * 它们装不下定界符也装不下祈使句，包起来只是每个值白烧约 150 token，
     * 而 {@code llm} 节点的模板里恰恰塞满了这类值（{@code {{$.stats.total}}} 之类）。
     *
     * <p>{@link PromptDataFence#CONTRACT} 在<b>至少真的包了一个值</b>时才前置一次：
     * 一份没有任何占位符的模板（或占位符全解析成数字）不该平白多出一段约定文字。
     *
     * @return 渲染结果；有值被包裹时形如 {@code CONTRACT + "\n\n" + 带数据段的正文}
     */
    public static String resolveData(String template, WorkflowContext ctx) {
        return resolveData(template, ctx == null ? null : ctx.data());
    }

    /**
     * 同 {@link #resolveData(String, WorkflowContext)}，但对一份裸 state Map 做替换。
     * 与 {@link #resolve(String, Map)} 的分工一致（那份给 Saga 反做用，那里没有活的 context）。
     */
    public static String resolveData(String template, Map<String, Object> state) {
        if (template == null || template.isEmpty()) {
            return template;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        boolean fenced = false;
        while (matcher.find()) {
            String expr = matcher.group(1);
            Object value = StatePathNavigator.navigate(state, expr);
            String text = stringify(value);
            String replacement = text;
            if (PromptDataFence.needsFence(text)) {
                // 标签用占位符表达式本身（如 $.contract.partyA）：读 prompt 的人能直接对上是哪个键，
                // 排障时不必回头去猜这一段是从 state 哪儿取的。它来自 DSL（作者写的），
                // 且 fence 对标签同样做中和，故即便将来有人把数据派生的键名写进模板也造不出结构
                replacement = PromptDataFence.fence(expr.trim(), text);
                fenced = true;
            }
            // quoteReplacement：变量值里可能含 $ 或 \，不转义会被当成反向引用。
            // 🔴 包裹之后<b>仍然</b>要转义 —— 定界符本身不含 $ 与 \，但被包在里面的值含，
            //    而 appendReplacement 扫的是整个替换串
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return fenced ? PromptDataFence.CONTRACT + "\n\n" + sb : sb.toString();
    }

    /**
     * 这个字符串里有没有 {@code {{...}}} 占位符
     *
     * <h3>为什么要开这个出口，而不是让调用方自己写正则</h3>
     * 「这个值运行期才知道」是变更清单必须说清的一件事：清单上写着
     * {@code id = {{$.save_draft.id}}} 时，用户看到的是一个<b>还没定的值</b>，
     * 而看到 {@code id = 1024} 时看到的是一张<b>已经存在的表单</b>。两者要改的东西完全不同，
     * 渲染上必须能区分。
     *
     * <p>判定必须与 {@link #resolve} 用的是同一个 {@link #PLACEHOLDER} ——
     * 各写一份正则的后果是「清单说这是字面值、运行期却把它替换掉了」，
     * 而这种分叉从界面上完全看不出来。
     *
     * @param text 待判定的字符串；null 返回 false
     */
    public static boolean containsPlaceholder(String text) {
        return text != null && !text.isEmpty() && PLACEHOLDER.matcher(text).find();
    }

    /**
     * 取出这个模板引用的全部<b>根</b> state 键：{@code {{$.a.b[0]}}} → {@code a}，{@code {{input}}} → {@code input}
     *
     * <h3>为什么开这个出口</h3>
     * 与 {@link #containsPlaceholder} 同一个理由。S2-e 的污点溯源要问「这个模板读了哪些 state 键」，
     * 而它的答案必须与 {@link #resolve} 认<b>同一批</b>占位符：各写一份正则的后果是
     * 「溯源看不见的引用、运行期照样替换」，于是清单说「没有不可信数据流进这个入参」而实际流进来了 ——
     * 那是比不溯源更坏的结果，因为它给了不该给的信心。
     *
     * <p>只回根键是刻意的：{@link StatePathNavigator} 的下钻（{@code .b}）与数组下标（{@code [0]}）
     * 都发生在根键<b>内部</b>，而 state 的写入粒度就是顶层键（{@code outputVar} 恒为顶层，见
     * {@code StatePathNavigator} 类注释「只做读，不做写」）。污点粒度到根键为止，再细就要理解值的结构。
     *
     * <p>⚠️ 与 {@code WorkflowDslValidator.PLACEHOLDER_ROOT}（{@code :63}）<b>有意不同</b>：那份正则要求
     * {@code $.} 前缀，而 {@link #PLACEHOLDER} 也认裸键 —— {@link StatePathNavigator#navigate} 明确支持
     * 「裸键即从根取一层」（{@code $.a} 与 {@code a} 等价）。故本方法看得比校验器<b>多</b>：
     * 写 {@code {{input}}} 的 DSL 会被溯源认成读了 {@code input}，而校验器的引用可达性检查看不见它。
     * 收敛那一份属校验规则改动（会新增 warning/error，撞 Req6 的向后兼容红线），不在 S2 顺带做，
     * 已登记待裁定。方向上是安全的：多看见 = 多报，而报的是运行期真会发生的那条。
     *
     * @param text 模板串；null / 空串返回空集
     * @return 根键，按出现顺序去重（畸形占位符如 {@code {{$.}}} 被跳过，与 {@link StatePathNavigator}
     *         「畸形一律按取不到处理」同口径）
     */
    public static Set<String> referencedRootKeys(String text) {
        if (text == null || text.isEmpty()) {
            return Set.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            String root = rootKeyOf(matcher.group(1));
            if (root != null) {
                keys.add(root);
            }
        }
        return keys;
    }

    /**
     * 路径表达式 → 根键。与 {@link StatePathNavigator#navigate} 的走法一致：
     * 先吃掉开头的 {@code $} 与 {@code .}，再读到第一个 {@code .} 或 {@code [} 为止。
     * 读不出名字（{@code $}、{@code .}、{@code [0]} 这类畸形）返回 null。
     */
    private static String rootKeyOf(String expr) {
        int i = 0;
        int n = expr.length();
        while (i < n && (expr.charAt(i) == '$' || expr.charAt(i) == '.')) {
            i++;
        }
        int start = i;
        while (i < n && expr.charAt(i) != '.' && expr.charAt(i) != '[') {
            i++;
        }
        return i == start ? null : expr.substring(start, i);
    }

    /**
     * 值 → 文本。null 落空串；对象/数组落 JSON 串；其余按 String.valueOf
     */
    private static String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof CharSequence || value instanceof Number
                || value instanceof Boolean || value instanceof Character) {
            return String.valueOf(value);
        }
        if (value instanceof Map || value instanceof Collection || value.getClass().isArray()) {
            return JSONUtil.toJsonStr(value);
        }
        return String.valueOf(value);
    }

}
