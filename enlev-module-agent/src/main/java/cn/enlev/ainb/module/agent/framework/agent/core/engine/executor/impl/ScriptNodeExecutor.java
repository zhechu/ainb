package cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.WorkflowContext;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.support.ScriptSandbox;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DSL_INVALID;

/**
 * {@code script} 节点执行器：在 GraalJS 沙箱里跑一小段作者脚本对 state 做加工（DSL §4.4，P7 收口）
 *
 * <h3>定位：确定性的「胶水计算」节点，不是第二个 llm 节点</h3>
 * 用于「把上游结果改个字段名 / 拼接 / 过滤 / 算个数值 / 组装下游入参」这类<b>无需模型、要确定性</b>的加工。
 * 脚本内以 {@code state} 只读对象访问工作流状态（如 {@code state.input}、{@code state.tool_result}），
 * {@code return} 的值写进 {@code outputVar}。对标 Dify 的 Code 节点 / Coze 的代码节点。
 *
 * <h3>🔴 安全边界全在 {@link ScriptSandbox}，本执行器只做「读 config → 传 state → 写 delta」</h3>
 * 执行任意脚本的风险面（宿主访问、IO、线程、超时、内存）由沙箱逐项封死，本类不重复也不绕过。
 * config 侧再补两道 fail-close：{@code code} 必填、{@code language} 目前只认 {@code javascript}
 * （将来加语言要显式扩，绝不默认放行未知语言）。
 *
 * <h3>本节点不调 LLM，token 恒为 0</h3>
 * 与 {@code tool}/{@code hitl.*}/{@code knowledge_retrieval} 同理，不碰 metrics.usage（规避 SR-2 tokenUsage 粘滞）。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class ScriptNodeExecutor implements NodeExecutor {

    public static final String TYPE = "script";
    /** 未配 {@code outputVar} 时脚本返回值落进的 state 键；public 的理由见 {@code ToolNodeExecutor#DEFAULT_OUTPUT_VAR} */
    public static final String DEFAULT_OUTPUT_VAR = "script_result";
    /** 目前唯一支持的脚本语言；GraalJS 引擎。加语言要显式扩此处 + 沙箱，绝不默认放行 */
    private static final String LANG_JAVASCRIPT = "javascript";

    @Resource
    private ScriptSandbox scriptSandbox;

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public Map<String, Object> execute(NodeDTO node, WorkflowContext ctx) {
        String code = node.configStr("code");
        if (StrUtil.isBlank(code)) {
            throw exception(WORKFLOW_DSL_INVALID, "script 节点(" + node.getId() + ") 缺少 code");
        }
        String language = node.configStr("language", LANG_JAVASCRIPT);
        if (!LANG_JAVASCRIPT.equalsIgnoreCase(language)) {
            throw exception(WORKFLOW_DSL_INVALID,
                    "script 节点(" + node.getId() + ") 暂不支持语言(" + language + ")，目前仅 javascript");
        }
        String outputVar = node.configStr("outputVar", DEFAULT_OUTPUT_VAR);
        long timeoutMs = resolveTimeoutMs(node);

        Object result = scriptSandbox.eval(code, ctx.data(), timeoutMs);
        log.info("[execute][script 节点({}) 执行({}) 语言({}) 超时({}ms)]",
                node.getId(), ctx.getExecutionId(), language, timeoutMs);

        // 结果已是纯 Java 值（沙箱 convert 保证），满足 DSL §3.2「state 只放 JSON 可序列化值」
        Map<String, Object> delta = new HashMap<>();
        delta.put(outputVar, result);
        return delta;
    }

    /**
     * 解析并夹逼超时：缺省 {@link ScriptSandbox#DEFAULT_TIMEOUT_MS}，作者可上调但不得越过
     * {@link ScriptSandbox#MAX_TIMEOUT_MS}（长时间占住 boundedElastic 线程会拖垮并发）。非法值回落缺省。
     */
    private long resolveTimeoutMs(NodeDTO node) {
        Integer configured = node.configInt("timeoutMs", null);
        if (configured == null || configured <= 0) {
            return ScriptSandbox.DEFAULT_TIMEOUT_MS;
        }
        return Math.min(configured, ScriptSandbox.MAX_TIMEOUT_MS);
    }

}
