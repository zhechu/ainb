package cn.enlev.ainb.module.agent.tool.impl.formdata;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDataPageReqDTO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_TOOL_PARAM_INVALID;

/**
 * {@code form_data_*} 工具的入参解析：把模型/workflow 给的参数解析成调用 {@code FormDesignApi} 所需的形状
 *
 * <h3>🔴 为什么单独抽一个类</h3>
 * 五个工具都要解析 code / pk / data，分页工具还要解析 conditions。若各工具自己解析，
 * 同一个「缺少 code」会写出五种措辞，模型自修正时无所适从；更要命的是
 * {@code data} 的两种入参形态（workflow 直传的对象 vs 模型序列化的 JSON 串）
 * 只在一处兼容才不会漏。解析逻辑只有一份，在这里。
 *
 * <h3>与 {@code DataModelParamResolver} 的分工差异</h3>
 * 那个解析器要注入 {@code DataModelApi} 去「取回现有结构再合并」（因为改表是全量同步语义）；
 * 本解析器<b>不碰任何 api</b>，只做纯粹的入参解析与形态兼容 —— 读-合-写是
 * {@code FormDataUpdateTool} 的职责（它才需要注入 api），不在这里。
 *
 * @author 走进新时代
 */
@Component
public class FormDataParamResolver {

    // ==================== 参数名 ====================

    /** 表单编码 */
    public static final String PARAM_CODE = "code";
    /** 记录主键（规范名）。别名 {@link #PARAM_ID} 也接受 */
    public static final String PARAM_PK = "pk";
    /** 记录主键的别名 —— 与 Web UI 的 {@code id} 一致，模型更习惯用它 */
    public static final String PARAM_ID = "id";
    /** 字段名 → 值 的对象 */
    public static final String PARAM_DATA = "data";
    /** 过滤条件数组 */
    public static final String PARAM_CONDITIONS = "conditions";
    /** 排序字段 */
    public static final String PARAM_SORT_FIELD = "sortField";
    /** 排序方向 */
    public static final String PARAM_SORT_ORDER = "sortOrder";
    /** 页码 */
    public static final String PARAM_PAGE_NO = "pageNo";
    /** 每页条数 */
    public static final String PARAM_PAGE_SIZE = "pageSize";

    // ==================== 参数说明（写进 prompt） ====================

    /**
     * 与解析逻辑放在一起，理由同 {@code AgentToolFunction.paramsSchema} 的注释：
     * 说明里写了而解析不认的字段，模型会一直传、一直被忽略，且没有任何提示。
     */
    public static final String GET_SCHEMA = """
            code（必填，字符串）：表单编码，从 form_list 拿
            pk（必填，字符串或数字）：要读取的记录主键（即该行的 id）。也接受别名 id""";

    public static final String PAGE_SCHEMA = """
            code（必填，字符串）：表单编码
            pageNo（可选，整数，默认 1）：页码，从 1 开始
            pageSize（可选，整数，默认 10）：每页条数，上限由服务端约束
            sortField（可选，字符串）：排序字段（表单里的 field 名，不是物理列名）
            sortOrder（可选，字符串）：排序方向，只能是 ASC 或 DESC
            conditions（可选，数组）：过滤条件，每项：
              field    必填，字段名（表单里的 field，不是物理列名）
              operator 必填，取值：EQ/NE/GT/GE/LT/LE/LIKE/LEFT_LIKE/RIGHT_LIKE/IN/BETWEEN/IS_NULL/IS_NOT_NULL
              value    多数操作符必填；IS_NULL/IS_NOT_NULL 不需要；IN 传数组；BETWEEN 传下界
              value2   仅 BETWEEN 用，传上界
            🔴 不知道这张表单有哪些字段、字段叫什么，先用 form_get 看结构，别猜字段名""";

    public static final String CREATE_SCHEMA = """
            code（必填，字符串）：表单编码
            data（必填，对象）：字段名 → 值 的映射，字段名用表单里的 field（不是物理列名）。
              只给要写入的字段即可；缺必填字段、撞唯一约束时，服务端会报错并说明原因。
              子表（一对多明细）用「字段名 → 数组」的形式，每项是一条明细。
            🔴 不知道字段名、哪些必填，先用 form_get 看结构，别猜""";

    public static final String UPDATE_SCHEMA = """
            code（必填，字符串）：表单编码
            pk（必填，字符串或数字）：要更新的记录主键（即该行的 id）。也接受别名 id
            data（必填，对象）：只写要改的字段（字段名 → 值）。
              🔴 这是 PATCH 语义：没写到的字段保持原值不变，绝不会被清空。
              （工具内部先读回整行、再把 data 覆盖上去，所以你不必、也不应传完整行）
            🔴 不知道字段名，先用 form_get / form_data_get 看，别猜""";

    public static final String DELETE_SCHEMA = """
            code（必填，字符串）：表单编码
            pk（必填，字符串或数字）：要删除的记录主键（即该行的 id）。也接受别名 id
            删除是逻辑删除（数据仍在库里、标记为已删除），但仍属高风险写操作，会进 Plan 等确认""";

    // ==================== 解析 ====================

    /**
     * 解析表单编码
     *
     * @param params   工具入参
     * @param toolName 工具名，只用于报错时说清楚是谁拒的
     * @return 非空的 code
     */
    public String resolveCode(Map<String, Object> params, String toolName) {
        return requireString(params, PARAM_CODE, "表单编码", toolName);
    }

    /**
     * 解析记录主键
     *
     * <p>规范名是 {@code pk}，但<b>也接受 {@code id}</b>：Web UI 与 create 的返回值用的都是 id，
     * 模型（以及 workflow 里的 {@code {{$.createOut.id}}}）自然会用 id。两个都认，省一轮纠错。
     *
     * @return 非空的主键，按字符串返回（infra/JDBC 对 BIGINT 列会自动 coerce）
     */
    public String resolvePk(Map<String, Object> params, String toolName) {
        Object value = params == null ? null : params.get(PARAM_PK);
        if (value == null || StrUtil.isBlank(value.toString())) {
            value = params == null ? null : params.get(PARAM_ID);
        }
        if (value == null || StrUtil.isBlank(value.toString())) {
            throw exception(AGENT_TOOL_PARAM_INVALID, toolName, "缺少 pk（记录主键，即该行的 id）");
        }
        return value.toString().trim();
    }

    /**
     * 解析「字段名 → 值」对象
     *
     * <p>兼容两种入参形态：workflow 的 tool 节点会把嵌套对象<b>原样透传</b>成 {@link Map}；
     * 而助手聊天里模型有时会把 data 序列化成一个 JSON <b>字符串</b>。两种都要认，
     * 否则后者会撞「data 不是合法对象」，白白烧一轮。
     *
     * @return 非空的 data（保留嵌套结构，子表明细原样带下去）
     */
    public Map<String, Object> resolveData(Map<String, Object> params, String toolName) {
        Object raw = params == null ? null : params.get(PARAM_DATA);
        if (raw == null) {
            throw exception(AGENT_TOOL_PARAM_INVALID, toolName, "缺少 data（字段名 → 值 的对象）");
        }
        Map<String, Object> data;
        if (raw instanceof Map<?, ?> map) {
            data = new LinkedHashMap<>();
            map.forEach((k, v) -> data.put(String.valueOf(k), v));
        } else {
            try {
                data = JSONUtil.parseObj(raw.toString());
            } catch (Exception ex) {
                throw exception(AGENT_TOOL_PARAM_INVALID, toolName,
                        "data 不是合法的「字段名 → 值」对象：" + ex.getMessage());
            }
        }
        if (data.isEmpty()) {
            throw exception(AGENT_TOOL_PARAM_INVALID, toolName, "data 不能为空（至少给一个字段）");
        }
        return data;
    }

    /**
     * 解析分页查询入参，组装成 {@link FormDataPageReqDTO}
     *
     * <p>conditions 只搬 field/operator/value/value2，<b>不校验 operator</b> ——
     * 合法操作符由 infra 的 {@code OperatorEnum} 白名单在拼 SQL 时兜底（防注入的唯一收口），
     * 在这里重复校验只会两边漂移。pageNo/pageSize 缺省留 null，由 {@code FormDesignApiImpl} 兜默认。
     */
    public FormDataPageReqDTO resolvePageReq(Map<String, Object> params, String toolName) {
        FormDataPageReqDTO reqDTO = new FormDataPageReqDTO();
        Object rawConditions = params == null ? null : params.get(PARAM_CONDITIONS);
        if (rawConditions != null) {
            reqDTO.setConditions(parseConditions(rawConditions, toolName));
        }
        reqDTO.setSortField(optionalString(params, PARAM_SORT_FIELD));
        reqDTO.setSortOrder(optionalString(params, PARAM_SORT_ORDER));
        reqDTO.setPageNo(optionalInt(params, PARAM_PAGE_NO, toolName));
        reqDTO.setPageSize(optionalInt(params, PARAM_PAGE_SIZE, toolName));
        return reqDTO;
    }

    /**
     * 解析 conditions 数组，兼容 workflow 直传的 {@link List} 与模型给的 JSON 串
     */
    private List<FormDataPageReqDTO.Condition> parseConditions(Object raw, String toolName) {
        JSONArray array;
        try {
            array = JSONUtil.parseArray(raw);
        } catch (Exception ex) {
            throw exception(AGENT_TOOL_PARAM_INVALID, toolName,
                    "conditions 不是合法的数组：" + ex.getMessage());
        }
        List<FormDataPageReqDTO.Condition> result = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JSONObject obj = array.getJSONObject(i);
            if (obj == null) {
                continue;
            }
            FormDataPageReqDTO.Condition condition = new FormDataPageReqDTO.Condition();
            condition.setField(obj.getStr("field"));
            condition.setOperator(obj.getStr("operator"));
            // value 可能是标量、数组（IN）或 null（IS_NULL）；原样带下去，交给 infra 按 operator 解释
            condition.setValue(obj.get("value"));
            condition.setValue2(obj.get("value2"));
            result.add(condition);
        }
        return result;
    }

    // ==================== 结构性存在检查（供 plan 期校验器用，只判断、不抛异常） ====================

    /**
     * params 里 key 对应的值是否「存在且非空白」
     *
     * <p>与 {@link #requireString} 的区别：那个缺了就抛业务异常（执行期）；
     * 这个只返回布尔，供 plan 期校验器把问题<b>收集</b>进 {@code AgentValidateResult} 回灌给模型，
     * 而不是在规划阶段就抛。两者判空口径必须一致，故都收在这里。
     */
    public static boolean hasText(Map<String, Object> params, String key) {
        Object value = params == null ? null : params.get(key);
        return value != null && StrUtil.isNotBlank(value.toString());
    }

    /**
     * 是否给了记录主键（{@code pk} 或别名 {@code id} 任一非空即可）
     */
    public static boolean hasPk(Map<String, Object> params) {
        return hasText(params, PARAM_PK) || hasText(params, PARAM_ID);
    }

    /**
     * 是否给了非空的 data
     *
     * <p>非法形态（既不是 Map 也解析不出 JSON 对象）一律视为「缺失」——
     * plan 期就该把它拦下回灌，而不是留到执行期抛异常。
     */
    public static boolean hasNonEmptyData(Map<String, Object> params) {
        Object raw = params == null ? null : params.get(PARAM_DATA);
        if (raw == null) {
            return false;
        }
        if (raw instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        try {
            return !JSONUtil.parseObj(raw.toString()).isEmpty();
        } catch (Exception ex) {
            return false;
        }
    }

    private String requireString(Map<String, Object> params, String key, String label, String toolName) {
        Object value = params == null ? null : params.get(key);
        if (value == null || StrUtil.isBlank(value.toString())) {
            throw exception(AGENT_TOOL_PARAM_INVALID, toolName, "缺少 " + key + "（" + label + "）");
        }
        return value.toString().trim();
    }

    private String optionalString(Map<String, Object> params, String key) {
        Object value = params == null ? null : params.get(key);
        return value == null || StrUtil.isBlank(value.toString()) ? null : value.toString().trim();
    }

    private Integer optionalInt(Map<String, Object> params, String key, String toolName) {
        Object value = params == null ? null : params.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException ex) {
            throw exception(AGENT_TOOL_PARAM_INVALID, toolName,
                    key + " 必须是整数，实际收到：" + text);
        }
    }

}
