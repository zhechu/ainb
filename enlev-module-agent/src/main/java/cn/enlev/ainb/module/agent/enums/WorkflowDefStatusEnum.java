package cn.enlev.ainb.module.agent.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Workflow 定义状态
 *
 * <pre>
 *   DRAFT ──(发布)──→ PUBLISHED ──(停用)──→ DISABLED
 *     ↑                                          │
 *     └──────────────(重新编辑出新版本)──────────┘
 * </pre>
 *
 * <p>只有 PUBLISHED 的定义能被执行入口选中。DRAFT 是编排中的草稿，
 * DISABLED 是「曾经发布过、现在不让新执行用」—— 但已绑定该版本的在途实例
 * 仍能按快照跑完（见 agent_workflow_execution.workflow_version）。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum WorkflowDefStatusEnum {

    /** 草稿：编排中，不可执行 */
    DRAFT("DRAFT", "草稿"),

    /** 已发布：可被执行入口选中 */
    PUBLISHED("PUBLISHED", "已发布"),

    /** 已停用：不再接受新执行，在途实例不受影响 */
    DISABLED("DISABLED", "已停用"),
    ;

    private final String status;
    private final String description;

    /**
     * 是不是「可被执行」的状态
     */
    public static boolean isExecutable(String status) {
        return PUBLISHED.status.equals(status);
    }

    /**
     * 是不是「草稿」档 —— 也就是<b>可就地编辑</b>的那一档
     *
     * <h3>🔴 这是「这一行改不改得动」判据的唯一出处，两个消费者都必须走这里</h3>
     * <ul>
     *   <li><b>强制点</b>：{@code AgentWorkflowDefServiceImpl#updateDef}
     *       （非草稿抛 {@code WORKFLOW_DEF_NOT_DRAFT}）</li>
     *   <li><b>广告位</b>：{@code AgentWorkflowDefRespVO#getEditable()}（下发给前端决定按钮态与只读横幅）</li>
     * </ul>
     * 各写一份就会分岔，表现为「管理页看着能编辑、点保存被后端拒」——本平台头号缺陷
     * （配置保存成功但不生效且无提示）的一个变体，只是方向反过来。方案 §G14 要收敛的正是这条。
     *
     * <h3>⚠️ 删除<b>不</b>走本方法</h3>
     * 可删的状态是「草稿 + 已停用」，比可编辑多一档，判据在 {@link #isDeletable}。
     * 理由：DISABLED 行是「曾发布、现已不让新执行用」，它既不该被就地改（改了就等于偷改历史版本的语义），
     * 也没有任何理由必须留着（不再有在途执行绑定它，见 {@code WORKFLOW_DEF_DELETE_HAS_UNFINISHED} 那道闸门）。
     * 拿「可编辑」当「可删除」用会让停用版永远删不掉，只能靠管理员档强制删除收尾。
     *
     * <h3>⚠️ 服务里另有几处比较 DRAFT 的代码<b>刻意不</b>走本方法，因为它们是别的规矩</h3>
     * {@code getSubWorkflowDef}（pin 的版本还是草稿 ⟹ 子流程不可用；那边 DISABLED 是<b>放行</b>的，
     * 与本判据结论相反）、{@code publishDef} / {@code publishDefById}（只有草稿能发布 —— 可发布性，
     * 不是可编辑性）。把它们并进来会让方法名说谎，也会让「不可重发的停用版」和「不可编辑的停用版」
     * 这两个不同结论共用一个开关。
     */
    public static boolean isDraft(String status) {
        return DRAFT.status.equals(status);
    }

    /**
     * 是不是<b>可删除</b>的状态 —— 草稿与已停用放行，已发布拒删
     *
     * <h3>🔴 唯一出处，与 {@link #isDraft} 一样不许在别处再抄一遍</h3>
     * 消费者同样是「强制点 + 广告位」一对：
     * <ul>
     *   <li><b>强制点</b>：{@code AgentWorkflowDefServiceImpl#assertStatusDeletable}
     *       （单删、批删、以及批删里逐行的那道闸门都走它；不合规抛 {@code WORKFLOW_DEF_NOT_DELETABLE}）</li>
     *   <li><b>广告位</b>：{@code AgentWorkflowDefRespVO#getDeletable()}（决定前端复选框与删除按钮态）</li>
     * </ul>
     *
     * <h3>⚠️ 这一档只是<b>状态</b>闸门，过了它不等于「删得掉」</h3>
     * 后面还有两道要扫<b>别的行</b>的闸门：被活定义的 sub_workflow pin 住
     * （{@code WORKFLOW_DEF_DELETE_PINNED}）、名下还有未终态执行
     * （{@code WORKFLOW_DEF_DELETE_HAS_UNFINISHED}）。权威的拒绝理由是删除接口返回的那句错误，
     * 不是列表接口下发的这个布尔 —— 后者算不起 O(行数 × 全表 DSL 解析) 的代价。
     *
     * <h3>为什么 PUBLISHED 一律拒删、而不是「没有执行史就放行」</h3>
     * PUBLISHED 是<b>正在被选用</b>的那一版：触发入口（webhook / 定时 / 手动）按 code 回落到它。
     * 删掉等于把一个对外承诺过的入口悄悄拔掉 —— 调用方拿到的是「流程不存在」，而运营侧看到的
     * 只是「列表里少了一行」。要删就先停用，让「不再接受新执行」这件事显式发生过一次。
     */
    public static boolean isDeletable(String status) {
        return isDraft(status) || DISABLED.status.equals(status);
    }

}
