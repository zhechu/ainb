package cn.enlev.ainb.module.agent.tool.impl.datainterface;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_TOOL_PARAM_INVALID;

/**
 * {@code data_interface_*} 工具的入参解析：把模型/workflow 给的参数解析成调用 {@code DataInterfaceApi} 所需的形状
 *
 * <h3>🔴 为什么单独抽一个类</h3>
 * 与 {@code FormDataParamResolver} 同一理由：query/invoke 都要解析 code 与 params 对象，
 * 解析逻辑只有一份在这里，报错措辞才一致（模型自修正时不会无所适从）；
 * {@code params} 的两种入参形态（workflow 直传的对象 vs 模型序列化的 JSON 串）
 * 也只在一处兼容才不会漏。
 *
 * <h3>与 5b 解析器的差异：params 可缺省</h3>
 * 表单数据的 data 是必填（没有字段就没意义）；数据接口的入参由接口自己定义，
 * <b>很多接口无参</b>，故 {@link #resolveArgs} 缺省返回空 Map 而不抛。
 * 参数必填/类型校验交给 infra 的 ParamValidator 执行时兜（中文报错冒泡），此处不重复。
 *
 * @author 走进新时代
 */
@Component
public class DataInterfaceParamResolver {

    // ==================== 参数名 ====================

    /** 数据接口编码 */
    public static final String PARAM_CODE = "code";
    /** 接口入参对象（键名按接口的入参定义） */
    public static final String PARAM_PARAMS = "params";
    /** list：关键字，模糊匹配 code/name */
    public static final String PARAM_KEYWORD = "keyword";
    /** list：是否只要读取型接口 */
    public static final String PARAM_ONLY_READ_ONLY = "onlyReadOnly";
    /** list：最多返回条数 */
    public static final String PARAM_LIMIT = "limit";

    /**
     * limit 缺省值与上限。目录现网 380+ 条（含大量测试数据），
     * 不钳制就是一次全量列出 = token 炸弹（infra Impl 侧还有同一套钳制，双保险）
     */
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;

    // ==================== 参数说明（写进 prompt） ====================

    /**
     * 与解析逻辑放在一起，理由同 {@code AgentToolFunction.paramsSchema} 的注释：
     * 说明里写了而解析不认的字段，模型会一直传、一直被忽略，且没有任何提示。
     */
    public static final String LIST_SCHEMA = """
            keyword（可选，字符串）：关键字，模糊匹配接口编码/名称（忽略大小写）。目录可能很大，尽量给
            onlyReadOnly（可选，布尔，默认 false）：true 时只列读取型接口（SQL 查询/HTTP GET/静态 JSON）
            limit（可选，整数，默认 20，上限 50）：最多返回条数""";

    public static final String QUERY_SCHEMA = """
            code（必填，字符串）：数据接口编码，从 data_interface_list 拿，不要猜
            params（可选，对象）：接口入参，键名与取值范围看 list 返回的 params 入参定义。无参接口不用传
            🔴 只读执行：仅支持读取型接口（SQL 查询/HTTP GET/静态 JSON），对写型接口调用会被服务端拒绝。
            只读接口取数就用本工具；确需执行写接口才用 data_interface_invoke""";

    public static final String INVOKE_SCHEMA = """
            code（必填，字符串）：数据接口编码，从 data_interface_list 拿，不要猜
            params（可选，对象）：接口入参，键名与取值范围看 list 返回的 params 入参定义。无参接口不用传
            ⚠️ 全能力执行：写型接口（INSERT/UPDATE/DELETE、HTTP POST 等）会产生真实副作用，
            需要 infra:data-interface:invoke 权限，且会进 Plan 等用户确认。只读取数请优先用 data_interface_query""";

    // ==================== 解析 ====================

    /**
     * 解析数据接口编码
     *
     * @param params   工具入参
     * @param toolName 工具名，只用于报错时说清楚是谁拒的
     * @return 非空的 code
     */
    public String resolveCode(Map<String, Object> params, String toolName) {
        Object value = params == null ? null : params.get(PARAM_CODE);
        if (value == null || StrUtil.isBlank(value.toString())) {
            throw exception(AGENT_TOOL_PARAM_INVALID, toolName, "缺少 code（数据接口编码）");
        }
        return value.toString().trim();
    }

    /**
     * 解析接口入参对象
     *
     * <p>兼容两种入参形态：workflow 的 tool 节点会把嵌套对象<b>原样透传</b>成 {@link Map}；
     * 而助手聊天里模型有时会把 params 序列化成一个 JSON <b>字符串</b>。两种都要认。
     *
     * <p>缺省返回空 Map 而不抛 —— 很多接口无参；有参接口缺参时由 infra 的
     * ParamValidator 报「缺少必填参数 xx」（中文可读）冒泡，比在这里猜着报更准。
     */
    public Map<String, Object> resolveArgs(Map<String, Object> params, String toolName) {
        Object raw = params == null ? null : params.get(PARAM_PARAMS);
        if (raw == null || StrUtil.isBlank(raw.toString())) {
            return new LinkedHashMap<>();
        }
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> args = new LinkedHashMap<>();
            map.forEach((k, v) -> args.put(String.valueOf(k), v));
            return args;
        }
        try {
            return JSONUtil.parseObj(raw.toString());
        } catch (Exception ex) {
            throw exception(AGENT_TOOL_PARAM_INVALID, toolName,
                    "params 不是合法的入参对象：" + ex.getMessage());
        }
    }

    /**
     * 解析 list 的关键字（可选）
     */
    public String resolveKeyword(Map<String, Object> params) {
        Object value = params == null ? null : params.get(PARAM_KEYWORD);
        return value == null || StrUtil.isBlank(value.toString()) ? null : value.toString().trim();
    }

    /**
     * 解析 list 的「是否只要读取型」（可选，缺省 false）
     */
    public boolean resolveOnlyReadOnly(Map<String, Object> params) {
        Object value = params == null ? null : params.get(PARAM_ONLY_READ_ONLY);
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(value.toString().trim());
    }

    /**
     * 解析 list 的条数上限（可选，缺省 20，钳制在 [1, 50]）
     */
    public int resolveLimit(Map<String, Object> params, String toolName) {
        Object value = params == null ? null : params.get(PARAM_LIMIT);
        if (value == null || StrUtil.isBlank(value.toString())) {
            return DEFAULT_LIMIT;
        }
        int limit;
        if (value instanceof Number number) {
            limit = number.intValue();
        } else {
            try {
                limit = Integer.parseInt(value.toString().trim());
            } catch (NumberFormatException ex) {
                throw exception(AGENT_TOOL_PARAM_INVALID, toolName,
                        "limit 必须是整数，实际收到：" + value);
            }
        }
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    // ==================== 结构性存在检查（供 plan 期校验器用，只判断、不抛异常） ====================

    /**
     * params 里 key 对应的值是否「存在且非空白」（口径与执行期解析一致，供校验器复用）
     */
    public static boolean hasText(Map<String, Object> params, String key) {
        Object value = params == null ? null : params.get(key);
        return value != null && StrUtil.isNotBlank(value.toString());
    }

    /**
     * 入参对象的形态是否合法（供 plan 期校验器用）
     *
     * <p>「没给 params」视为合法（无参接口）；给了则必须是 Map 或能解析成 JSON 对象 ——
     * 非法形态在 plan 期就该拦下回灌，而不是留到执行期抛异常。
     */
    public static boolean hasValidParamsShape(Map<String, Object> params) {
        Object raw = params == null ? null : params.get(PARAM_PARAMS);
        if (raw == null || StrUtil.isBlank(raw.toString())) {
            return true;
        }
        if (raw instanceof Map) {
            return true;
        }
        try {
            JSONUtil.parseObj(raw.toString());
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

}
