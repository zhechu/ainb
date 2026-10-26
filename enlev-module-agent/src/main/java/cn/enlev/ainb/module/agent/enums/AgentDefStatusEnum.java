package cn.enlev.ainb.module.agent.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Agent 定义状态 —— P5 Agent Builder 第一刀
 *
 * <pre>
 *   DRAFT ──(发布)──→ PUBLISHED ──(停用)──→ DISABLED
 *     ↑                                          │
 *     └──────────────(重新编辑出新版本)──────────┘
 * </pre>
 *
 * <p>与 {@link WorkflowDefStatusEnum} 同构（刻意独立成一个枚举而非复用：Agent 与 Workflow 是两类资源，
 * 共用一个「Workflow」命名的枚举会让语义漂移；两者状态机恰好一致只是当前巧合）。
 *
 * <p>🔴 有界：本刀状态仅供<b>管理目录</b>的草稿/发布/停用流转与展示。PUBLISHED 是否「可被聊天入口选中」
 * 由 slice-2 的路由消费方判定（届时可加 {@code isExecutable} 之类的判定方法），本刀不预埋无人调用的判定。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum AgentDefStatusEnum {

    /** 草稿：定义中，不可用 */
    DRAFT("DRAFT", "草稿"),

    /** 已发布：可被选用（slice-2 起被聊天入口按 Agent 路由选中） */
    PUBLISHED("PUBLISHED", "已发布"),

    /** 已停用：曾发布过、现不再选用 */
    DISABLED("DISABLED", "已停用"),
    ;

    private final String status;
    private final String description;

}
