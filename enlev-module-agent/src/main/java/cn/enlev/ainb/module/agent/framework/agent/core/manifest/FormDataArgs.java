package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.agent.tool.impl.formdata.FormDataParamResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code form_data_create / update / delete} 三个抽取器共用的入参读法
 *
 * <h3>为什么不各写各的</h3>
 * 这三个工具的入参形状由 {@link FormDataParamResolver} 定义，其中两条容忍规则<b>不看代码猜不到</b>：
 * <ul>
 *   <li>主键的规范名是 {@code pk}，但<b>也接受别名 {@code id}</b>
 *       （{@link FormDataParamResolver#resolvePk}：Web UI 与 create 的返回值用的都是 id，
 *       workflow 里写 {@code {{$.createOut.id}}} 时自然会传 id）</li>
 *   <li>{@code data} 既可以是对象，也可以是<b>模型序列化好的 JSON 字符串</b>
 *       （{@link FormDataParamResolver#resolveData} 两种都收）</li>
 * </ul>
 * 三个抽取器各抄一遍这两条，漏掉任何一条的表现都是
 * 「清单说没认出主键/没认出字段，运行期却照样写进去了」——
 * 清单比实际保守不算致命，但它会让审批人以为这一项没有作用对象，从而看不出改的是哪一行。
 * 收敛成一份，与解析器同源。
 *
 * <h3>🔴 与解析器的区别：这里一律不抛</h3>
 * 解析器缺参数就抛 {@code AGENT_TOOL_PARAM_INVALID}（执行期语义）；
 * 清单在图跑之前生成，DSL 有问题由校验器报错，清单照出（见
 * {@link WorkflowChangeManifestBuilder#build} 的契约）。故这里认不出来就返回空，
 * 让抽取器把「没认出来」这件事本身写进 summary。
 *
 * <p>⚠️ 包级可见：只服务于那三个抽取器。
 *
 * @author 走进新时代
 */
final class FormDataArgs {

    /**
     * summary 里最多列几个字段名
     *
     * <p>summary 是聊天确认卡与审批详情里的一行，列全 20 个字段名就没法读了；
     * 完整列表在 {@code detail.fields} 里，两处各有用途。
     */
    static final int MAX_NAMES_IN_SUMMARY = 5;

    private FormDataArgs() {
    }

    /**
     * 表单编码
     */
    static String code(Map<String, Object> args) {
        return ManifestArgs.str(args, FormDataParamResolver.PARAM_CODE);
    }

    /**
     * summary 里放编码的那个槽位：有编码就是 {@code " employee "}，没有就是 {@code "（编码未填）"}
     *
     * <p>留白规则与 {@code FormSaveDraftChangeExtractor#summary} 同一条：
     * ASCII 两侧留空格，全角括号不留。写成 {@code "在表单 " + (code == null ? "（编码未填）" : code) + " 新增"}
     * 的话，缺编码这条分支会渲染成「在表单 （编码未填） 新增」——
     * 一个带着模板洞的句子，而它出现的时机恰恰是模型把参数写错、审批人正在逐字读的时候。
     *
     * <p>🔴 这条规则的<b>强制点</b>是 {@code SummarySpacingTest}，不是本注释。
     * 它原先只写在两处 javadoc 里，结果另外三个抽取器照样带着同一个洞提交上来，
     * 而且各自的逐字断言是<b>照着错输出</b>写的，所以全绿。
     */
    static String codeSlot(String code) {
        return code == null ? "（编码未填）" : " " + code + " ";
    }

    /**
     * 记录主键，认 {@code pk} 与别名 {@code id}；两个都没有返回 null
     */
    static String pk(Map<String, Object> args) {
        String pk = ManifestArgs.str(args, FormDataParamResolver.PARAM_PK);
        return pk != null ? pk : ManifestArgs.str(args, FormDataParamResolver.PARAM_ID);
    }

    /**
     * 「字段名 → 值」对象；缺失、形态不对、JSON 解析不出来都返回空 Map
     *
     * <p>容忍「对象」与「模型序列化好的 JSON 串」两种形态，与
     * {@link FormDataParamResolver#resolveData} 同口径（实现见 {@link ManifestArgs#map}）。
     */
    static Map<String, Object> data(Map<String, Object> args) {
        return ManifestArgs.map(args, FormDataParamResolver.PARAM_DATA);
    }

    /**
     * data 里的字段名（保持 DSL 里的书写顺序）
     *
     * <p>🔴 <b>只给名字，不给值</b>。清单会被冻结进 BPM 流程变量、显示给审批人，
     * 而 data 装的正是用户提交的业务数据（可能有金额、身份证、手机号）。
     * 审批人需要知道的是「改了哪几个字段」，不是「改成了什么」——
     * 后者属于业务数据，不该顺着清单流到流程变量和审批详情里去。
     */
    static List<String> fieldNames(Map<String, Object> data) {
        return new ArrayList<>(data.keySet());
    }

    /**
     * data 里值是子表（一对多明细）的字段名
     *
     * <p>{@link FormDataParamResolver#CREATE_SCHEMA} 约定子表用「字段名 → 数组」。
     * 值得单独说出来：删/改一行主记录时连带多少条明细，量级完全不同。
     */
    static List<String> subTableFields(Map<String, Object> data) {
        List<String> result = new ArrayList<>();
        data.forEach((field, value) -> {
            if (value instanceof List<?>) {
                result.add(field);
            }
        });
        return result;
    }

    /**
     * data 里值写成了 {@code {{...}}} 占位符的字段名
     *
     * <h3>🔴 这一条抓的是一个会把脏数据写进库的真实陷阱</h3>
     * {@code ToolNodeExecutor#execute} 解析入参时<b>只替换顶层字符串</b>：
     * {@code args} 的每个值里，只有 {@code v instanceof String} 才过一遍
     * {@code StateTemplateResolver#resolve}，Map/List 原样透传。
     * 而 {@code data} 恰恰是个 Map ⟹ 嵌在里面的 {@code {{$.x.y}}} <b>永远不会被替换</b>，
     * 会被当成普通文字原样写进业务表。
     *
     * <p>从现象几乎不可能反推到原因：用户看到某行数据的姓名列写着 {@code {{$.start.name}}}，
     * 只会以为「AI 填错了」。清单是唯一能在写入之前就说出这件事的地方。
     *
     * <p>⚠️ 顶层的 {@code code} / {@code pk} 是字符串，<b>会</b>被正常替换，
     * 它们「运行期才确定」是正常状态，由 {@link ManifestArgs#isPending} 单独表述，
     * 不要与本方法混为一谈。
     */
    static List<String> nestedPlaceholderFields(Map<String, Object> data) {
        List<String> result = new ArrayList<>();
        data.forEach((field, value) -> {
            if (containsPlaceholderDeep(value)) {
                result.add(field);
            }
        });
        return result;
    }

    private static boolean containsPlaceholderDeep(Object value) {
        if (value instanceof String s) {
            return ManifestArgs.isPending(s);
        }
        if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                if (containsPlaceholderDeep(item)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (containsPlaceholderDeep(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * {@link #nestedPlaceholderFields} 非空时挂在 summary 末尾的那句警告
     *
     * <p>create 与 update 两个抽取器共用，故措辞只有一份。
     * 面向<b>审批人</b>，只说清后果、不给修法：怎么改是模型的事，
     * 那句面向模型的提示归 S4a 的校验（拟为「运行期只替换顶层字符串参数，
     * 请把它提到顶层或写字面值」），两处受众不同，不该共用一句话。
     *
     * @return 没有这类字段时返回 null，调用方直接跳过
     */
    static String nestedPlaceholderWarning(List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        return "⚠️ 字段 " + String.join("、", fields) + " 的值还是 {{…}} 占位符，"
                + "运行期不会被替换，会当成普通文字原样写进库";
    }

    /**
     * 把主键渲染成人话：具体值照写，占位符照字面写出来再补一句「运行期才确定」
     *
     * <p>三个抽取器都要用同一句话，故收在这里 ——
     * 「记录 1024」与「记录 {{$.createOut.id}}（运行期才确定）」对审批人是两件完全不同的事：
     * 前者是一条已存在的、能去查的数据，后者是本次流程里刚建出来的那条。
     *
     * <p>限定语放在<b>后面</b>：先让人看见 DSL 里写的原文，再说它还没定。
     * 反过来（「运行期才确定（{{…}}）」）读起来像是清单在解释自己，而它该解释的是那一行数据。
     */
    static String describePk(String pk) {
        if (StrUtil.isBlank(pk)) {
            return null;
        }
        return ManifestArgs.isPending(pk) ? pk + "（运行期才确定）" : pk;
    }

    /**
     * 顿号连接，超出 {@link #MAX_NAMES_IN_SUMMARY} 的部分折成「等 N 个」
     */
    static String joinLimited(List<String> names) {
        if (names == null || names.isEmpty()) {
            return "";
        }
        if (names.size() <= MAX_NAMES_IN_SUMMARY) {
            return String.join("、", names);
        }
        return String.join("、", names.subList(0, MAX_NAMES_IN_SUMMARY))
                + " 等 " + names.size() + " 个";
    }

}
