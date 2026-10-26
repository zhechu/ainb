package cn.enlev.ainb.module.agent.framework.agent.core.tool.datainterface;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.infra.api.datainterface.DataInterfaceApi;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据接口工具的动态执行器（{@code source=DATA_INTERFACE}）
 *
 * <p>由 {@code AgentToolDefService.buildFunction} 按一行 {@code agent_tool_def} 现场 {@code new} 出来
 * （每个工具绑一个接口编码），<b>不是</b> Spring bean。{@code DataInterfaceApi} 由构造方注入。
 *
 * <h3>🔴 与内置 {@code data_interface_query/invoke} 的分工</h3>
 * 那两个是<b>通用</b>工具：模型自己挑 code、自己拼入参，故要带 {@code data_interface_list} 先查目录，
 * 一来一回多两轮 token，且模型可能挑错接口。本类是<b>定向</b>工具：接口在登记时就钉死了，
 * 模型只需要按 {@code paramsSchema} 填入参。适合「把某个常用接口做成一个有业务名字的能力」，
 * 例如把 {@code order_stat_daily} 包成 {@code sales_daily_report}（展示名「销售日报」）。
 *
 * <h3>🔴 权限与写门仍由网关把守</h3>
 * 遵守 {@link AgentToolFunction} 契约，本类不做任何权限校验。登记行上的 {@code permissions}
 * 由 {@code AgentSecurityGateway} 在调用前逐个 {@code hasPermission}。
 * 因此绑定 {@code mode=INVOKE}（含写）的工具，登记时<b>必须</b>把 {@code infra:data-interface:invoke}
 * 写进 permissions —— 服务端校验在 {@code AgentToolDefServiceImpl.validateForSave}，不靠管理员自觉。
 *
 * @author 走进新时代
 */
public class DataInterfaceToolFunction implements AgentToolFunction {

    /** 模型/workflow 偶尔会把入参再包一层 {@code params}（与通用数据接口工具的形状一致），认这一种形态 */
    private static final String NESTED_PARAMS_KEY = "params";

    private final DataInterfaceToolConfig config;
    private final DataInterfaceApi dataInterfaceApi;
    private final String toolKey;
    /** DO 的 params_schema（自由文本）；登记时若为空已由服务端按接口入参定义自动补全 */
    private final String paramsSchema;

    public DataInterfaceToolFunction(DataInterfaceToolConfig config, DataInterfaceApi dataInterfaceApi,
                                     String toolKey, String paramsSchema) {
        this.config = config;
        this.dataInterfaceApi = dataInterfaceApi;
        this.toolKey = toolKey;
        this.paramsSchema = paramsSchema;
    }

    @Override
    public Object execute(Map<String, Object> params) {
        Map<String, Object> args = normalizeArgs(params);
        // 接口不存在/未启用/缺必填参数/只读约束拦截，都由 infra 抛中文 ServiceException 原样冒泡，
        // AI 可据此自修正；本类不包新错误码，否则真实原因会被吞掉
        return config.isInvoke()
                ? dataInterfaceApi.invokeData(config.getCode(), args)
                : dataInterfaceApi.queryData(config.getCode(), args);
    }

    /**
     * 归一化入参：整个 map 就是接口入参；仅当外面多包了一层 {@code params} 时脱掉那层
     *
     * <p>不做更多猜测（比如挑出「看起来像入参」的键）——猜错的代价是静默传错参数，
     * 而缺参时 infra 的 ParamValidator 会明确报「缺少必填参数 xx」，模型能自修正。
     */
    private Map<String, Object> normalizeArgs(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return new LinkedHashMap<>();
        }
        if (params.size() == 1 && params.containsKey(NESTED_PARAMS_KEY)) {
            Object nested = params.get(NESTED_PARAMS_KEY);
            Map<String, Object> unwrapped = tryAsMap(nested);
            if (unwrapped != null) {
                return unwrapped;
            }
        }
        return new LinkedHashMap<>(params);
    }

    /** 值能当对象看（Map 或 JSON 对象串）就展开，否则返回 null 交回上层按原样处理 */
    private Map<String, Object> tryAsMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> args = new LinkedHashMap<>();
            map.forEach((k, v) -> args.put(String.valueOf(k), v));
            return args;
        }
        if (value == null || StrUtil.isBlank(value.toString())) {
            return new LinkedHashMap<>();
        }
        try {
            return JSONUtil.parseObj(value.toString());
        } catch (Exception ex) {
            return null;
        }
    }

    @Override
    public String paramsSchema() {
        if (StrUtil.isNotBlank(paramsSchema)) {
            return paramsSchema;
        }
        return "入参按数据接口 " + config.getCode() + " 的入参定义传；无参接口不用传参数";
    }

    /** 供测试与日志确认绑定关系；toolKey 只是报错时的标识，不参与执行 */
    public String getToolKey() {
        return toolKey;
    }

}
