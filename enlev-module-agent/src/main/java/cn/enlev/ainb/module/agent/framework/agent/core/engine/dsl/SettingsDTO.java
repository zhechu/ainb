package cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * DSL 全局设置（§2 settings）
 *
 * @author 走进新时代
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SettingsDTO {

    /**
     * 全局默认恢复策略：forward / compensate，可被节点级 recovery 覆盖
     */
    private String recovery;

    /**
     * 并行节点最大分支数（防资源打爆），parallel 节点用（P2）
     */
    private Integer maxParallelism;

    /**
     * iteration（foreach）单节点最大迭代次数（防超大列表打爆 recursionLimit/资源），iteration 节点用（P2）
     *
     * <p>🔴 这是<b>安全上界</b>而非「静默截断阈值」：iteration 的迭代次数 = 列表长度（编译期未知），
     * 运行期 guard 若发现 {@code items.size() > maxIterations} 会<b>fail-close 抛错</b>
     * （{@code WORKFLOW_ITERATION_TOO_MANY_ITEMS}），而不是悄悄只跑前 N 项 —— 后者正是平台头号缺陷
     * 「配了不生效且无提示」的翻版。列表确实很长时，作者应显式调大本值或先过滤列表。
     * 空 = 默认 100（见 {@code IterationGraphBuilder.DEFAULT_MAX_ITERATIONS}）。
     */
    private Integer maxIterations;

    /**
     * 整体兜底超时秒数
     */
    private Integer executionTimeoutSeconds;

    /**
     * 多租户隔离方式，固定 thread_name（§7）
     */
    private String tenantIsolation;

}
