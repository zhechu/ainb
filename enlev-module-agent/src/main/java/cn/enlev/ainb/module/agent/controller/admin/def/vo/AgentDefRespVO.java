package cn.enlev.ainb.module.agent.controller.admin.def.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理后台 - Agent 定义 Response VO（P5 Agent Builder 第一刀）
 *
 * <p>🔴 {@link #persona} 是人设（系统提示词）原文，可能几 KB。分页列表端点会把它置 null 再返回
 * （列表只需元信息），只有 {@code GET /get?id} 才带完整 persona 供编辑/查看——与 workflow 分页置空 dsl 同理。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - Agent 定义 Response VO")
@Data
public class AgentDefRespVO {

    @Schema(description = "编号", example = "1")
    private Long id;

    @Schema(description = "业务编码，同一 code 多版本共存", example = "order_assistant")
    private String code;

    /**
     * 是否系统内置（需求10）
     *
     * <p>当前只有「默认平台助手」一行为 true。前端据此：① 打「系统内置」标签；
     * ② 隐藏 删除 / 停用 / 发布 按钮（后端也会拒，隐藏只是不让人点了才吃错误）；
     * ③ 编辑时只渲染 名称 / 人设 / 描述 三项（其余列在「未选 Agent」那条路上不被读取，
     * 渲染出来就是「配了不生效」）；④ 把它从聊天入口的 Agent 选择器里排除——
     * 选择器另有一个 {@code value=0} 的「默认平台助手」哨兵项，两者会重复。
     */
    @Schema(description = "是否系统内置智能体（内置行只能就地改人设/名称/描述）", example = "false")
    private Boolean builtin;

    @Schema(description = "名称", example = "订单客服")
    private String name;

    @Schema(description = "人设（系统提示词）原文；列表端点为 null，get?id 才返回")
    private String persona;

    @Schema(description = "绑定的问答模型编号，为空表示用平台动态默认模型", example = "1")
    private Long modelId;

    @Schema(description = "绑定的知识库编号，为空表示该 Agent 不做知识库检索", example = "7")
    private Long knowledgeId;

    @Schema(description = "绑定的只读工具键，逗号分隔；为空表示不启用 agentic 工具循环",
            example = "data_interface_list,datamodel_list")
    private String toolKeys;

    /**
     * 是否「由工作流驱动」（增量12 / slice-3c 批次 B3，只读派生字段，不落库）
     *
     * <p>由 {@code AgentDefController} 反查「是否存在一条已发布 chatflow 的 trigger.chat.config.agentId = 本 Agent 的 code」
     * 得出（{@code AgentWorkflowDefService.getPublishedChatTriggerByAgentCode}）。true = 选中该 Agent 对话时跑那条工作流，
     * 而非直连 LLM / 工具循环。给前端 Agent 列表打「工作流驱动」标识用（discoverability，计划 RK2 缓解）。
     */
    @Schema(description = "是否由已发布 chatflow 驱动（只读派生，不落库）", example = "false")
    private Boolean workflowDriven;

    @Schema(description = "描述", example = "处理订单咨询与售后")
    private String description;

    @Schema(description = "版本号，单调递增", example = "1")
    private Integer version;

    @Schema(description = "状态：DRAFT / PUBLISHED / DISABLED", example = "PUBLISHED")
    private String status;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

}
