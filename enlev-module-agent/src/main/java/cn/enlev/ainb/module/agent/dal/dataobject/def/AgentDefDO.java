package cn.enlev.ainb.module.agent.dal.dataobject.def;

import cn.enlev.ainb.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Agent 定义 DO（版本化）—— P5 Agent Builder 第一刀
 *
 * <h3>这张表是什么</h3>
 * 「智能体（Agent）」的元数据目录：业务人员在此定义一个个可复用的 Agent（当前以<b>人设 persona</b> 为核心），
 * 走与 {@code agent_workflow_def} 完全一致的<b>版本化 + 草稿/发布/停用</b>生命周期。
 *
 * <h3>🔴 为什么 persona 落一整列 text，而不是拆结构</h3>
 * persona 是 Agent 的「系统提示词」原文——一段自由散文（身份/语气/职责/边界），本身就是一段整体文本，
 * 没有可结构化的子字段。存原文、消费时整段取用，最简单也最不易错（与 workflow 存整份 DSL 同理）。
 *
 * <h3>🔴 分刀：哪些绑定已接线、哪些顺延</h3>
 * 设计方案 P5 = 「Agent 定义元数据（人设/模型/工具集/知识库/绑定工作流/触发器）、版本化与发布、<b>聊天入口按 Agent 路由</b>」。
 * 第一刀只交付<b>管理目录</b>；第二刀（增量10）接线<b>persona</b>（→系统提示词）与<b>modelId</b>（→固定问答模型）；
 * 第三刀（增量11 / slice-3a）接线<b>knowledgeId</b>——聊天检索从「硬编码内置手册 {@code doc_search}」参数化为
 * 「按 Agent 绑定的知识库走 {@code AiRagApi.retrieve}」（见 {@code AgentAssistantServiceImpl.retrieveFromKnowledge}）。
 * <p>仍顺延的是<b>工具集</b>（聊天路径当前<b>没有</b> function-calling 工具循环，需先建 agentic 工具循环 = slice-3b）
 * 与<b>绑定工作流</b>（对话触发跑指定 workflow = slice-3c）——它们的消费方尚不存在，按 YAGNI 待消费方建好再随迁移加列，
 * 否则会造出「落库却无人读取」的死配置（本平台头号缺陷「配置保存成功但不生效且无提示」的翻版）。
 *
 * <h3>版本不变式（照 workflow_def）</h3>
 * 同一 {@code code} 多版本共存，{@code version} 单调递增。已发布版本视为不可变快照：
 * 就地改一个 PUBLISHED 定义会破坏「在途消费按发布时的定义走」的语义，故编辑/删除只放行 DRAFT
 * （见 {@code AgentDefServiceImpl}），要改已发布 Agent 请基于它新建一个版本。
 * <p>唯一例外是 {@link #builtin} 行（默认平台助手）：它是不参与版本化的单例配置，就地改、不可删不可停用。
 *
 * @author 走进新时代
 */
@TableName("agent_def")
@KeySequence("agent_def_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentDefDO extends TenantBaseDO {

    /**
     * 系统内置「默认平台助手」的业务编码
     *
     * <p>需求10：聊天入口不选 Agent 时用的那个助手，此前它的人设硬编码在
     * {@code AgentPromptBuilder.BASE_PROMPT} 里，管理员看得见却改不了。现在它是本表一行
     * {@code builtin=1} 的记录，人设可在「智能体管理」在线维护。编码固定成常量而不是靠
     * {@code builtin} 标记去找，是为了让「哪一行是平台助手」在代码里只有一处真相。
     */
    public static final String CODE_PLATFORM_ASSISTANT = "platform_assistant";

    private Long id;

    /**
     * 业务编码，同一 code 多版本共存（如 {@code order_assistant}）
     */
    private String code;

    /**
     * 是否系统内置（当前只有「默认平台助手」一行为 true）
     *
     * <h3>🔴 内置行不参与版本化，是「单例配置」而非「业务 Agent」</h3>
     * 普通 Agent 的编辑要派生新 DRAFT 版本、再发布（已发布版本是不可变快照，在途对话按发布时的定义走）。
     * 内置助手没有这个语义：它不是被谁「选用」的一个版本，而是「没选 Agent 时用哪个提示词」的平台设置，
     * 改完就该立即对下一句话生效。所以它<b>就地改</b>、不出新版本，也因此不允许停用/删除
     * （见 {@code AgentDefServiceImpl}）——停用平台助手等于让所有未选 Agent 的对话失去系统提示词。
     *
     * <h3>🔴 只有 persona / name / description 三列对内置行有效</h3>
     * modelId / knowledgeId / toolKeys 在「未选 Agent」这条路上<b>不被读取</b>：默认助手的定义性特征就是
     * 「检索平台内置手册 + 产出追问建议 + 用动态默认模型」，读这三列等于把它变成一个普通 Agent。
     * 所以编辑表单对内置行根本不渲染这三项——留着一个填了不生效的框，正是本平台头号缺陷
     * 「配置保存成功但不生效且无提示」的形状。
     */
    private Boolean builtin;

    /**
     * 展示名
     */
    private String name;

    /**
     * 人设 = Agent 的系统提示词原文（身份/语气/职责/边界）。text 列，见类注释。
     * 🔴 增量10 起已接线：选中此 Agent 对话时，persona 作为系统提示词注入（见 {@code AgentPromptBuilder}）。
     */
    private String persona;

    /**
     * 绑定的问答模型编号（ai 模块 {@code ai_model.id}）。可空：
     * 空 = 用平台动态默认模型（启用+密钥启用+sort 最前，不可用自动 failover）；
     * 非空 = 为该 Agent 固定模型，注入 {@code AiChatReqDTO.modelId} 置顶候选链（仍带 failover）。
     */
    private Long modelId;

    /**
     * 绑定的知识库编号（ai 模块 {@code ai_knowledge.id}）。可空：
     * 空 = 该 Agent 不做知识库检索（仅凭 persona 作答）；
     * 非空 = 聊天时从该知识库检索（{@code AiRagApi.retrieve}）并把命中片段注入 prompt。
     * 🔴 增量11（slice-3a）起已接线，见 {@code AgentAssistantServiceImpl.retrieveFromKnowledge}；
     * 默认平台助手（未选 Agent）仍走内置手册 {@code doc_search}，与此列无关。
     */
    private Long knowledgeId;

    /**
     * 绑定的只读工具键，逗号分隔（如 {@code data_interface_list,datamodel_list}）。可空：
     * 空 = 该 Agent 不启用 agentic 工具循环（走直连 LLM 一问一答）；
     * 非空 = 聊天时跑「思考→调只读工具→观察→再思考」ReAct 循环到收敛出 answer
     * （见 {@code AgentChatToolLoop} / {@code AgentAssistantServiceImpl.toolLoopStream}）。
     * 🔴 增量12（slice-3b）起已接线。只读强制：发布时校验每个键都已登记且 {@code isRequireConfirm()==false}
     * （含写工具/未登记工具拒发布，{@code AGENT_DEF_TOOL_INVALID}）——聊天循环无人工确认，绝不自动执行写操作。
     */
    private String toolKeys;

    private String description;

    /**
     * 版本号，单调递增。同 code 每次新建 = 现有最大 + 1
     */
    private Integer version;

    /**
     * 状态，枚举 {@link cn.enlev.ainb.module.agent.enums.AgentDefStatusEnum}
     */
    private String status;

}
