package cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl;

import cn.enlev.ainb.module.agent.framework.agent.core.AgentTaskKeys;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.WorkflowContext;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.support.StateTemplateResolver;
import cn.enlev.ainb.module.ai.api.model.AiModelApi;
import cn.enlev.ainb.module.ai.api.model.dto.AiChatReqDTO;
import cn.enlev.ainb.module.ai.api.model.dto.AiChatRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@code llm} 节点执行器：单次模型调用（DSL §4.2）
 *
 * <h3>走 {@link AiModelApi#chat} 同步调用，不自己碰 ChatModel</h3>
 * 两个理由：
 * <ol>
 *   <li><b>复用动态默认 + 故障切换</b>：modelId 留空时 api 层按「启用+密钥启用+sort 最前」选，
 *       <b>瞬时</b>不可用自动切下一个候选（AiModelFailoverService），实际用的模型经 resp.modelId 回传。
 *       节点执行器不该重新实现这套选择逻辑。
 *       🔴 但切换<b>不是</b>无条件的：额度耗尽算本次调用失败、不切换，且一次执行内只用一个模型
 *       （见下面 {@code taskKey} 那行与裁定 R-01）—— 否则一次执行的前后半程可能不是同一个模型，
 *       这一轮的效果既没法归因、也没法和上一轮比。</li>
 *   <li><b>P1 刻意不用流式</b>：流式 token 跑在 Netty/Reactor 线程上，会触发 SPIKE SR-1
 *       的「异步回调里租户丢失」风险，须显式贴回租户。P1 只需「跑通流程 + 逐节点计量」，
 *       同步 chat 直接拿到 content+usage+modelId，最省事也最不易错。
 *       节点内 token 级流式输出是 P2+ 增强</li>
 * </ol>
 *
 * <h3>🔴 token 归因：从 resp.usage 填进 metrics，不裸读 NodeOutput.tokenUsage()</h3>
 * 后者在 GraphRunnerContext 上粘滞（SPIKE SR-2）。这里拿到的是<b>本次调用</b>的真实 usage，
 * 由 NodeTraceRecorder 落到本节点那一行，归因准确。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class LlmNodeExecutor implements NodeExecutor {

    public static final String TYPE = "llm";
    /** 未配 {@code outputVar} 时模型答复落进的 state 键；public 的理由见 {@code ToolNodeExecutor#DEFAULT_OUTPUT_VAR} */
    public static final String DEFAULT_OUTPUT_VAR = "llm_output";

    @Resource
    private AiModelApi aiModelApi;

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public Map<String, Object> execute(NodeDTO node, WorkflowContext ctx) {
        // 🔴 B2-3：用 resolveData 而不是 resolve —— 这两个字符串的去处是<b>模型</b>，
        //    而模板里的 {{...}} 会被 state 里的值替换掉，那些值可能是 webhook body、
        //    上一个 llm 节点的输出、工具返回值（五种污点源里的三种）。
        //    resolveData 把每个替换值包成数据段，模板文字留在指令段。
        //    两个都要换：systemPrompt 也是模板，「你是客服，服务等级 {{$.tier}}」这种写法
        //    一样会把不可信值拼进<b>指令段</b> —— 那比拼进 user 消息更糟，因为系统提示词的权重更高。
        // 🔴 CONTRACT 因此在 system 与 user 两侧各出现一次（约多 300 token/次调用）。
        //    刻意接受这份重复：省掉它要么让调用方自己记得挂声明（迟早有人忘），
        //    要么让 resolveData 去猜「我这次渲染的是 system 还是 user」（它猜不到，也不该猜）。
        String prompt = StateTemplateResolver.resolveData(node.configStr("prompt", ""), ctx);
        String systemPrompt = StateTemplateResolver.resolveData(node.configStr("systemPrompt"), ctx);
        String outputVar = node.configStr("outputVar", DEFAULT_OUTPUT_VAR);

        AiChatReqDTO req = new AiChatReqDTO();
        // modelId 留空 = 动态默认 + 故障切换；DSL 显式指定则用指定的（仍会校验可用性）
        req.setModelId(node.configLong("modelId"));
        // 🔴 一次流程执行内的所有 llm 节点必须用同一个模型：中途额度耗尽时判本次调用失败，
        //    而不是悄悄换个模型把后半程跑完 —— 那样这一轮的效果既没法归因也没法和上一轮比（裁定 R-01）
        req.setTaskKey(AgentTaskKeys.ofExecution(ctx.getExecutionId()));
        req.setSystemPrompt(systemPrompt);
        req.setMessages(List.of(AiChatReqDTO.Message.of("user", prompt)));
        req.setTemperature(node.configDouble("temperature"));
        req.setMaxTokens(node.configInt("maxTokens", null));

        AiChatRespDTO resp = aiModelApi.chat(req);

        // 度量：实际模型 + 真实 usage（可能为 null —— 厂商没回传时按 0 记，来源保持 ESTIMATED）
        AiChatRespDTO.Usage usage = resp.getUsage();
        if (usage != null) {
            ctx.getMetrics().recordUsage(resp.getModelId(), usage.getPromptTokens(),
                    usage.getCompletionTokens(), usage.getTotalTokens(), true);
        } else {
            ctx.getMetrics().setModelId(resp.getModelId());
        }

        String content = resp.getContent() == null ? "" : resp.getContent();
        log.info("[execute][llm 节点({}) 执行({}) 模型({}) token({})]",
                node.getId(), ctx.getExecutionId(), resp.getModelId(),
                usage == null ? "n/a" : usage.getTotalTokens());
        return Map.of(outputVar, content);
    }

}
