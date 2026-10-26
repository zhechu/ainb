package cn.enlev.ainb.module.agent.framework.agent.core.engine.context;

import lombok.Data;

/**
 * 单次节点执行的度量（瞬态，不写入 state）
 *
 * <p>🔴 为什么单独一个对象而不是塞进 state：state 会被 SAA checkpoint 序列化，
 * 里面只能放 JSON 可序列化值（DSL §3.2 约束）。度量是「执行完就落 trace 表」的临时数据，
 * 放进 state 既污染 checkpoint、又会被粘滞的 tokenUsage 问题连累（见 SPIKE SR-2）。
 *
 * <p>由 NodeExecutor 在调用模型/工具后填充，NodeTraceRecorder 在节点收尾时读取并落库。
 *
 * @author 走进新时代
 */
@Data
public class NodeMetrics {

    /**
     * llm 节点实际使用的模型编号（故障切换后与请求的 modelId 可能不同）
     */
    private Long modelId;

    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;

    /**
     * token 来源：PROVIDER / ESTIMATED。默认 ESTIMATED，拿到厂商回传时改 PROVIDER
     */
    private String tokenSource = "ESTIMATED";

    /**
     * 记录一次模型用量。usage 为 null（厂商没回传）时保持 ESTIMATED、token 记 0
     */
    public void recordUsage(Long modelId, Integer prompt, Integer completion, Integer total,
                            boolean fromProvider) {
        this.modelId = modelId;
        this.promptTokens = prompt;
        this.completionTokens = completion;
        this.totalTokens = total;
        this.tokenSource = fromProvider ? "PROVIDER" : "ESTIMATED";
    }

}
