package cn.enlev.ainb.module.agent.dal.dataobject.prompttemplate;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 提示词模板 DO（需求7）
 *
 * <h3>它解决的是什么问题</h3>
 * 智能助手面板只有一个空输入框，用户每次都要重新组织一段「你是…，请基于…，输出…」的提问。
 * 本表把这些反复用的提问存成模板，聊天面板一键插入到输入框——<b>插入后可编辑</b>，
 * 所以它是「起手式」而不是「固定指令」，与 Agent 定义里的 {@code persona}（每次都强制生效、用户改不了）
 * 是两回事，两者互补而非重复。
 *
 * <h3>🔴 刻意不做成「模板 → 直接发给模型」</h3>
 * 若点一下就发送，用户看不到最终会发出去什么，模板里一句措辞不当就成了不可见的系统指令；
 * 而占位符（{@code {{订单号}}}）没填就发送，模型会照着字面量回答。插入到输入框让用户过目、
 * 补全占位符后再自己按发送，这两个坑就都不存在了。
 *
 * <h3>🔴 没有 variables 列</h3>
 * 占位符由前端按 {@code {{...}}} 正则从 {@link #content} 现算并渲染成标签。存一份派生列意味着
 * 改内容忘改列时页面提示与实际不符——正是本平台头号缺陷「配了但不生效」的形状。
 *
 * <h3>没有 builtin 列</h3>
 * 种子模板（见 {@code sql/mysql/xpy-2026-09-29-agent-prompt-template.sql}）就是普通行，
 * 管理员可以改可以删。它们不是「系统运行的必要配置」（删光了助手照常工作），
 * 故不需要 {@code agent_def.builtin} 那套「单例、不可删、就地改」的机制。
 *
 * @author 走进新时代
 */
@TableName("agent_prompt_template")
@KeySequence("agent_prompt_template_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentPromptTemplateDO extends TenantBaseDO {

    private Long id;

    /**
     * 模板名称，聊天面板与列表页展示用。
     * <p>不做唯一约束：同名模板只是看着别扭，不影响任何行为，报错拦住反而添麻烦。
     */
    private String name;

    /**
     * 模板正文，插入聊天输入框的内容。支持 {@code {{占位符}}}，由用户插入后自行替换。
     */
    private String content;

    /**
     * 分类（聊天面板按它分栏、管理页按它筛选），可空——留空归入「未分类」。
     * <p>自由文本而非枚举：分类的价值在于贴合各租户自己的业务口径，写死枚举反而逼着用户挑一个不像的。
     */
    private String category;

    /**
     * 用途说明，只在管理页与聊天面板的悬浮提示里出现，不进模型上下文。
     */
    private String description;

    /**
     * 排序，小的在前（同分类内）。
     */
    private Integer sort;

    /**
     * 状态，枚举 {@link CommonStatusEnum}：0 开启 / 1 关闭。
     * <p>🔴 关闭的模板<b>不出现在聊天面板</b>，但仍在管理页可见可改——
     * 「暂时下架一条措辞待定的模板」比「删掉再重建」常见得多。
     */
    private Integer status;

}
