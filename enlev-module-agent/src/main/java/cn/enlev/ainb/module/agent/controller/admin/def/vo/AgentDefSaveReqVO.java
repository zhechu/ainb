package cn.enlev.ainb.module.agent.controller.admin.def.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理后台 - Agent 定义 新增/修改 Request VO（P5 Agent Builder 第一刀）
 *
 * <h3>🔴 就地修改仅对 DRAFT 生效</h3>
 * 已发布/已停用的定义是「不可变版本快照」，就地改其人设会让按该版本路由的对话前后不一致。
 * 要改已发布 Agent，前端「编辑」按钮会先调 {@code POST /agent/def/derive} 派生一个新草稿版本再进编辑器
 * （{@code AgentDefServiceImpl.updateDef} 对非 DRAFT 抛 {@code AGENT_DEF_NOT_DRAFT}）。
 *
 * <h3>🔴 例外：系统内置行（默认平台助手）就地改，且只认 name / persona / description</h3>
 * 见 {@code AgentDefDO#builtin}。本 VO 仍带 modelId / knowledgeId / toolKeys 三个字段（新增普通 Agent 要用），
 * 但对内置行服务端<b>刻意忽略</b>它们——那条路不读这三列，存进去就是「配了不生效」的死配置。
 *
 * <h3>🔴 本 VO 没有 builtin 字段</h3>
 * 「是不是系统内置」是平台事实，不是用户可申报的属性。放进来就等于允许任何人把一个普通 Agent
 * 标成内置（从此删不掉、停不了），或把真内置行的标记抹掉。
 *
 * <h3>字段级校验的边界</h3>
 * persona 是自由散文（系统提示词），无结构可校，故只做「非空 + 长度上限」的 Bean 校验；
 * 其运行期效果由 slice-2 的对话路由消费，本刀不校验语义。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - Agent 定义 新增/修改 Request VO")
@Data
public class AgentDefSaveReqVO {

    @Schema(description = "编号（新增时为空，修改时必填）", example = "1")
    private Long id;

    @Schema(description = "业务编码，租户内同一 code 多版本共存",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "order_assistant")
    @NotBlank(message = "业务编码不能为空")
    @Pattern(regexp = "^[a-z][a-z0-9_]{1,63}$",
            message = "业务编码必须以小写字母开头，仅含小写字母/数字/下划线，长度 2~64")
    private String code;

    @Schema(description = "名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "订单客服")
    @NotBlank(message = "名称不能为空")
    @Size(max = 128, message = "名称长度不能超过 128")
    private String name;

    @Schema(description = "人设（系统提示词）原文", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "人设不能为空")
    @Size(max = 8000, message = "人设长度不能超过 8000")
    private String persona;

    @Schema(description = "绑定的问答模型编号，为空则用平台动态默认模型", example = "1")
    private Long modelId;

    @Schema(description = "绑定的知识库编号（ai_knowledge.id），为空则该 Agent 不做知识库检索", example = "7")
    private Long knowledgeId;

    @Schema(description = "绑定的只读工具键，逗号分隔；为空则不启用 agentic 工具循环",
            example = "data_interface_list,datamodel_list")
    @Size(max = 1000, message = "工具键长度不能超过 1000")
    private String toolKeys;

    @Schema(description = "描述", example = "处理订单咨询与售后")
    @Size(max = 500, message = "描述长度不能超过 500")
    private String description;

}
