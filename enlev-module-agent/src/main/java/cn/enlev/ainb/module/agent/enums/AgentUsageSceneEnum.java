package cn.enlev.ainb.module.agent.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 计量场景
 *
 * 🔴 <b>只统计主对话会让账对不上。</b>
 *
 * <p>一次「帮我建个采购申请表」可能产生 5~10 条计量记录：主对话一条、
 * 规划与重规划各若干条、生成后校验失败的自修正轮次若干条、最后还有建议生成一条。
 * 用户看到账单若只有「对话 1 次」却扣了十几倍 token，会以为被多收了。
 *
 * <p>场景维度就是为了让这些消耗<b>能被解释清楚</b>。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum AgentUsageSceneEnum {

    /**
     * 主对话：用户直接提问、AI 直接回答
     */
    CHAT("CHAT", "对话"),

    /**
     * 对话后建议
     *
     * 方案 §9.2 定的是「与主回答同一次调用返回」，那样不会单独产生记录。
     * 但若某些场景确实需要单独生成，必须用这个场景单独计量 ——
     * 否则用户会发现「我只问了一句，怎么扣了两次」。
     */
    SUGGESTION("SUGGESTION", "建议生成"),

    /**
     * 意图分类：判断这句话该走问答还是走规划
     *
     * <h3>为什么要单列一个场景，而不是并进 CHAT</h3>
     * 它是<b>每条消息都会发生</b>的固定开销，哪怕用户只是问一句「联动怎么配」。
     * 并进 CHAT 的话，账单上看不出这部分成本有多大，
     * 也就无从判断「这个分类器值不值得留着」——
     * 而它恰恰是最该被持续衡量的一次调用：单次极便宜，乘以消息总数就未必。
     */
    INTENT("INTENT", "意图分类"),

    /**
     * Agent 循环里的每一轮 LLM 调用（规划、观察后重规划）
     */
    TOOL_LOOP("TOOL_LOOP", "工具循环"),

    /**
     * 生成后校验失败的自修正轮次
     */
    VALIDATE_RETRY("VALIDATE_RETRY", "校验自修正"),

    /**
     * plan-as-workflow：把复杂需求拆解成 Workflow DSL 草稿的每一轮 LLM 调用（增量7a）
     *
     * <h3>为什么单列一个场景，而不是并进 TOOL_LOOP</h3>
     * 线性 planner（{@code TOOL_LOOP}）产出的是 {@code agent_plan_step} 列表，
     * 而本场景产出的是<b>一整份 Workflow DSL</b>——输出结构更长、单次更贵，
     * 且属「Plan 即 Workflow」这条核心差异链路。单列出来，账单上才能看清
     * 「生成一份可执行流程草稿」这件事到底烧了多少 token、值不值得。
     */
    PLAN_DSL("PLAN_DSL", "流程DSL生成"),

    /**
     * 向量化调用（文档灌库、查询向量化）
     */
    EMBEDDING("EMBEDDING", "向量化"),
    ;

    private final String scene;
    private final String description;

}
