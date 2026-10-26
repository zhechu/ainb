package cn.enlev.ainb.module.agent.controller.admin.prompttemplate.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理后台 - 提示词模板 新增/修改 Request VO（需求7）
 *
 * <h3>🔴 没有 variables / 占位符字段</h3>
 * 占位符是 {@link #content} 的派生信息，由前端按 {@code {{...}}} 正则现算。存一份就会与正文分叉，
 * 表现为「页面提示要填 3 个占位符、正文里其实只有 2 个」——见 {@code AgentPromptTemplateDO} 类注释。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - 提示词模板 新增/修改 Request VO")
@Data
public class AgentPromptTemplateSaveReqVO {

    @Schema(description = "编号（新增时为空，修改时必填）", example = "1")
    private Long id;

    @Schema(description = "模板名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "追问某条数据的来龙去脉")
    @NotBlank(message = "模板名称不能为空")
    @Size(max = 128, message = "模板名称长度不能超过 128")
    private String name;

    @Schema(description = "模板正文，支持 {{占位符}}；点选后插入聊天输入框，由用户补全再发送",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "请查一下订单 {{订单号}} 为什么还没发货，并说明卡在哪一步")
    @NotBlank(message = "模板内容不能为空")
    @Size(max = 8000, message = "模板内容长度不能超过 8000")
    private String content;

    @Schema(description = "分类，留空归入「未分类」", example = "数据查询")
    @Size(max = 64, message = "分类长度不能超过 64")
    private String category;

    @Schema(description = "用途说明（只在管理页与悬浮提示出现，不进模型上下文）", example = "追查单笔订单的履约进度")
    @Size(max = 500, message = "用途说明长度不能超过 500")
    private String description;

    @Schema(description = "排序，小的在前", example = "10")
    private Integer sort;

    @Schema(description = "状态，见 CommonStatusEnum：0 开启 / 1 关闭。关闭后不出现在聊天面板", example = "0")
    private Integer status;

}
