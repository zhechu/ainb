package cn.enlev.ainb.module.agent.tool.impl.datainterface;

import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.infra.api.datainterface.DataInterfaceApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 工具：<b>只读</b>执行一个数据接口，取回业务数据
 *
 * <h3>🔴 只读约束由 infra 强制，不在本工具重复</h3>
 * 委托 {@code DataInterfaceApi.queryData} → {@code DataInterfaceService.invokeReadOnly}：
 * SQL 仅 SELECT、HTTP 仅 GET/HEAD、静态 JSON 放行；对写型接口调用会被 infra 拒绝
 * （中文 ServiceException 冒泡，AI 可自修正 —— 改用 {@code data_interface_invoke} 或换接口）。
 *
 * <h3>权限口径（与 5b 表单数据工具的动态门不同，这里是纯静态）</h3>
 * {@code agent:assistant:use}（网关校验）。数据接口的权限模型本就是「一个静态权限点管所有接口」，
 * 没有按 code 的动态维度；且 infra {@code /options} 端点（运行时取数）<b>登录即可</b>调
 * {@code invokeReadOnly} —— 任何登录用户今天就有同等数据面，本工具口径与之逐字一致，不扩大暴露。
 *
 * <h3>结果原样透传</h3>
 * infra 已整形（查询 {@code {code,msg,data}} 或 {@code {total,list}}、HTTP 解析后的 JSON 等），
 * 这里不包摘要壳；截断交给 {@code @AgentTool.truncateResult} 默认策略防大结果撑爆上下文。
 *
 * @author 走进新时代
 */
@Component
@AgentTool(
        name = DataInterfaceQueryTool.NAME,
        displayName = "只读执行数据接口",
        groupName = "数据接口",
        description = "只读执行一个数据接口（平台已治理的 SQL 查询/HTTP GET/静态 JSON 接口），取回业务数据。"
                + "code 与入参签名先用 data_interface_list 查，不要猜。"
                + "⚠️ 仅支持读取型接口，对写型接口调用会被服务端拒绝；确需执行写接口才用 data_interface_invoke",
        permission = "agent:assistant:use",
        risk = RiskLevel.READ_ONLY)
public class DataInterfaceQueryTool implements AgentToolFunction {

    public static final String NAME = "data_interface_query";

    @Resource
    private DataInterfaceApi dataInterfaceApi;
    @Resource
    private DataInterfaceParamResolver resolver;

    @Override
    public Object execute(Map<String, Object> params) {
        String code = resolver.resolveCode(params, NAME);
        Map<String, Object> args = resolver.resolveArgs(params, NAME);
        // 接口不存在/未启用/缺必填参数/只读约束拦截，都是 infra 的中文 ServiceException，
        // 原样冒泡给模型自修正 —— 不要在这里包一层，那会丢掉 infra 的具体信息
        return dataInterfaceApi.queryData(code, args);
    }

    @Override
    public String paramsSchema() {
        return DataInterfaceParamResolver.QUERY_SCHEMA;
    }

    /**
     * 🔴 本工具的返回形状<b>不可静态声明</b>，尽管它有一个「常见形状」
     *
     * <p>类注释「结果原样透传」那段列了两种：{@code {total,list}} 与 {@code {code,msg,data}}。
     * 前者是 SQL 查询型接口的常态，看着声明一个 {@code list} 就够了 ——
     * 但那样它会被渲染进「不点名也对」那一栏，而 HTTP GET 型接口走的是后一条，
     * 一个数组都不回，模型照那一栏写出的 {@code pickRows(state.x)} 会在运行期抛
     * 「猜不出该取哪个数组，候选：」（候选还是空的，因为压根没有数组）。
     *
     * <p>🔴 这就是「按分支变的形状必须声明并集」推到底的样子：并集里<b>有一条分支是空</b>时，
     * 任何非空声明都是假的。此时正确的表达不是 {@code resultArrayKeys()}，而是本方法 ——
     * 它让提示词改口成「要解析就先用 script 把键名打出来看一眼」，多花一个节点，
     * 但不会给模型一句在某些接口上为假的确定指引。
     *
     * <p>代价如实记在这里：SQL 查询型（多数）因此也要多绕一步。
     * 换回来的是「提示词对本工具从不说假话」—— 而校验器 V26 拿声明当判据，
     * 声明一旦偏乐观，它就会放行一段运行期必抛的脚本。
     */
    @Override
    public boolean resultShapeKnown() {
        return false;
    }

}
