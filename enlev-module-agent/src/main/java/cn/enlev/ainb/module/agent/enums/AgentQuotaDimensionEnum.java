package cn.enlev.ainb.module.agent.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 配额维度（P7 治理 / 待办 #6b：配额按 Agent、工作流维度）
 *
 * <h3>为什么要有这一列，而不是继续靠 {@code user_id} 是否为空来区分</h3>
 * 原本只有「租户级 / 用户级」两档，用 {@code user_id IS NULL} 就能区分。
 * 但 Agent 维度与工作流维度<b>都不限定用户</b>（user_id 同为 NULL），
 * 光靠 user_id 无法把「给某个 Agent 设的限额」和「给某条工作流设的限额」分开 ——
 * 两者的 target 都指向一个定义，必须有一个显式的判别列。
 *
 * <h3>🔴 AGENT / WORKFLOW 的 target 存的是 {@code code} 而不是 numeric id</h3>
 * agent_def 与 agent_workflow_def 都是<b>版本化</b>的：同一个 code 有多行（每版一行），
 * numeric id 指向单一版本行。若配额绑 id，作者一发新版（新 id），旧配额就<b>静默失效</b> ——
 * 正是本平台头号缺陷「配置保存成功但不生效且无提示」。故与 chat 触发器绑定（trigger_key 存 code）
 * 同口径，配额 target 也存 code，跨版本稳定，且能把同一 code 各版本的用量<b>合并</b>计入本周期。
 *
 * <h3>🔴 WORKFLOW 不支持金额上限</h3>
 * 工作流用量从 {@code agent_node_execution}（节点级 trace）汇总，那张表<b>只有 token、没有 cost 列</b>
 * （成本只在计费表 {@code agent_usage_log} 上算）。按用户选定「不改计费语义」，本维度<b>不</b>新增
 * 工作流的计费写入口径，故 WORKFLOW 配额只能限 token；金额上限对它是死配置，前后端都须显式禁用。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum AgentQuotaDimensionEnum {

    /** 整个租户（user_id、target_key 均空） */
    TENANT("TENANT", "整个租户", true),
    /** 指定用户（user_id 非空） */
    USER("USER", "指定用户", true),
    /** 指定 Agent（target_key = agent_def.code） */
    AGENT("AGENT", "指定 Agent", true),
    /** 指定工作流（target_key = agent_workflow_def.code）；🔴 只支持 token 上限 */
    WORKFLOW("WORKFLOW", "指定工作流", false),
    ;

    private final String dimension;
    private final String name;
    /**
     * 是否支持金额上限。WORKFLOW 为 false —— 见类注释（trace 表无 cost 列，且不改计费语义）。
     */
    private final boolean costSupported;

    /**
     * 解析维度；认不出来返回 {@code null}（<b>不</b>默认兜底）。
     *
     * <p>刻意不像 {@link AgentQuotaPeriodEnum#parse} 那样兜底：周期认错顶多是宽严之差，
     * 而维度认错会把「给 A 的限额」套到「B」头上，是<b>管钱</b>的错配，宁可返回 null 让调用方显式处理。
     */
    public static AgentQuotaDimensionEnum parse(String dimension) {
        return Arrays.stream(values())
                .filter(item -> item.getDimension().equalsIgnoreCase(dimension))
                .findFirst()
                .orElse(null);
    }

}
