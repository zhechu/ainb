package cn.enlev.ainb.module.agent.tool.impl.datainterface;

import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.infra.api.datainterface.DataInterfaceApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 工具：<b>全能力</b>执行一个数据接口（含写：INSERT/UPDATE/DELETE、HTTP POST 等）
 *
 * <h3>🔴 写门在网关，工具内不再校验（与 5b 表单数据工具相反，且刻意为之）</h3>
 * 声明 {@code permission = "infra:data-interface:invoke"} —— 与 infra {@code /invoke} 端点的
 * {@code @PreAuthorize} 是<b>同一个权限串</b>，由 {@code AgentSecurityGateway.checkTool}
 * 逐字校验（registry 命中代码工具后循环 hasPermission，缺则抛 AGENT_PERMISSION_DENIED）。
 * 数据接口的权限模型本就是纯静态单权限点（没有按 code 的动态维度），网关完全管得住，
 * 故这里遵守 {@code AgentToolFunction} 契约「实现里不做权限校验」—— 5b 之所以要在工具内
 * 补动态门，是因为表单数据权限含运行时 code，网关结构上表达不了；此处不存在该问题。
 * 该契约由 {@code DataInterfaceToolContractTest} 反射钉死，防将来被「顺手放宽」。
 *
 * <h3>🔴 HIGH 风险</h3>
 * 写型接口产生真实副作用（改库、调外部 HTTP）。助手聊天路径 requireConfirm=true，
 * 只能进 Plan 等用户确认；workflow 路径直执行（既有语义：「确认」= 作者发布了这条 workflow）。
 *
 * @author 走进新时代
 */
@Component
@AgentTool(
        name = DataInterfaceInvokeTool.NAME,
        displayName = "全能力执行数据接口",
        groupName = "数据接口",
        description = "全能力执行一个数据接口（平台已治理的 SQL/HTTP/静态 JSON 接口），可执行写操作"
                + "（INSERT/UPDATE/DELETE、HTTP POST 等），会产生真实副作用，需要 infra:data-interface:invoke 权限。"
                + "code 与入参签名先用 data_interface_list 查，不要猜。"
                + "⚠️ 只读取数请优先用 data_interface_query；仅在明确需要执行写型接口时用本工具",
        permission = "infra:data-interface:invoke",
        risk = RiskLevel.HIGH)
public class DataInterfaceInvokeTool implements AgentToolFunction {

    public static final String NAME = "data_interface_invoke";

    @Resource
    private DataInterfaceApi dataInterfaceApi;
    @Resource
    private DataInterfaceParamResolver resolver;

    @Override
    public Object execute(Map<String, Object> params) {
        String code = resolver.resolveCode(params, NAME);
        Map<String, Object> args = resolver.resolveArgs(params, NAME);
        // 权限已由网关校验（见类注释）；接口不存在/未启用/缺参等 infra 中文异常原样冒泡
        return dataInterfaceApi.invokeData(code, args);
    }

    @Override
    public String paramsSchema() {
        return DataInterfaceParamResolver.INVOKE_SCHEMA;
    }

    /**
     * 🔴 本工具的返回形状<b>不可静态声明</b>
     *
     * <p>{@code DataInterfaceApi#invokeData} 的 javadoc 自己写着「形状由 service 决定」：
     * 写型接口回影响行数、HTTP 型回解析后的 JSON，装什么全看那个接口怎么配的。
     *
     * <p>为什么不干脆声明成「没有数组」：那样它会被渲染进「压根不回数组，别对它用 pickRows」那一栏 ——
     * 一句<b>确定的</b>指引，而它对某些接口是假的（HTTP 型完全可能回数组）。
     * 说「不知道」会让提示词改口成「要解析就先用 script 把键名打出来看一眼」，
     * 多花一个节点，但不会把模型推进一个运行期才炸的写法。
     *
     * <p>后果照旧说清：script 节点对它取值时，规划期谁都给不出键名，校验器 V26 也拦不住。
     * 最省的写法是让流程<b>不要</b>在 script 里解析本工具的结果，
     * 直接把 {@code {{$.变量}} 交给 llm 节点去说人话。
     */
    @Override
    public boolean resultShapeKnown() {
        return false;
    }

}
