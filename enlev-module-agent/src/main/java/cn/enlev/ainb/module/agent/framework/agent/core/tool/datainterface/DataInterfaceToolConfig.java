package cn.enlev.ainb.module.agent.framework.agent.core.tool.datainterface;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_TOOL_DEF_INTERFACE_INVALID;

/**
 * 数据接口工具（{@code agent_tool_def.source=DATA_INTERFACE}）的 config 载体：{@code {code, mode}}
 *
 * <h3>🔴 自定义工具不再自己发 HTTP，而是「包一层 AI 语义」挂到已治理的数据接口上</h3>
 * 平台的数据接口子系统（{@code infra_data_interface}）已经把 SQL / HTTP 转发 / 静态 JSON 三种来源、
 * 入参定义、只读判定、租户注入、系统表保护、版本与调用日志全做完了。此前的自定义工具让管理员在这里
 * <b>再填一遍</b> method/url/headers/鉴权凭据，等于同一件事配两处：数据接口那边改了地址、这边还指着旧的，
 * 而且这边绕开了数据接口的全部治理（没有只读判定、没有日志、凭据明文落在 agent_tool_def 里）。
 *
 * <p>改成关联后，自定义工具只负责数据接口<b>不提供</b>的那部分——让 AI 认识这个能力：
 * 叫什么（{@code displayName}）、什么时候该调（{@code description} 进 prompt）、
 * 有多危险（{@code riskLevel} 决定是否进 Plan 确认）、需要什么权限（{@code permissions}）。
 * config 因此只剩两个键，也<b>不再有凭据</b>，脱敏与回填那套机制随之整体删除。
 *
 * @author 走进新时代
 */
public class DataInterfaceToolConfig {

    /** 只读执行（{@code DataInterfaceApi.queryData}）：写型接口会被服务端拒绝 */
    public static final String MODE_QUERY = "QUERY";
    /** 全能力执行（{@code DataInterfaceApi.invokeData}）：含写，需要 {@code infra:data-interface:invoke} */
    public static final String MODE_INVOKE = "INVOKE";

    private final String code;
    private final String mode;

    public DataInterfaceToolConfig(String code, String mode) {
        this.code = code;
        this.mode = StrUtil.blankToDefault(mode, MODE_QUERY).trim().toUpperCase();
    }

    public String getCode() {
        return code;
    }

    public String getMode() {
        return mode;
    }

    public boolean isInvoke() {
        return MODE_INVOKE.equals(mode);
    }

    /**
     * 解析 config JSON
     *
     * @param json    {@code {"code":"xxx","mode":"QUERY"}}
     * @param toolKey 只用于报错时说清是哪个工具配错了
     */
    public static DataInterfaceToolConfig parse(String json, String toolKey) {
        if (StrUtil.isBlank(json)) {
            throw exception(AGENT_TOOL_DEF_INTERFACE_INVALID, toolKey, "config 为空，缺少要关联的数据接口编码");
        }
        JSONObject obj;
        try {
            obj = JSONUtil.parseObj(json);
        } catch (Exception ex) {
            throw exception(AGENT_TOOL_DEF_INTERFACE_INVALID, toolKey, "config 不是合法的 JSON：" + ex.getMessage());
        }
        String code = obj.getStr("code");
        if (StrUtil.isBlank(code)) {
            throw exception(AGENT_TOOL_DEF_INTERFACE_INVALID, toolKey, "缺少 code（要关联的数据接口编码）");
        }
        String mode = StrUtil.blankToDefault(obj.getStr("mode"), MODE_QUERY).trim().toUpperCase();
        if (!MODE_QUERY.equals(mode) && !MODE_INVOKE.equals(mode)) {
            throw exception(AGENT_TOOL_DEF_INTERFACE_INVALID, toolKey,
                    "mode 必须是 QUERY（只读）或 INVOKE（全能力），实际收到：" + mode);
        }
        return new DataInterfaceToolConfig(code.trim(), mode);
    }

    /** 序列化成落库的 config JSON（顺序固定，便于人工核对与 diff） */
    public static String toJson(String code, String mode) {
        return new JSONObject(true)
                .set("code", StrUtil.trimToEmpty(code))
                .set("mode", StrUtil.blankToDefault(mode, MODE_QUERY).trim().toUpperCase())
                .toString();
    }

}
