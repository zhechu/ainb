package cn.enlev.ainb.module.agent.controller.admin.prompttemplate.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理后台 - 提示词模板用例 新增/修改 Request VO（S7 / L5）
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - 提示词模板用例 新增/修改 Request VO")
@Data
public class AgentPromptTemplateCaseSaveReqVO {

    @Schema(description = "编号（新增时为空，修改时必填）", example = "1")
    private Long id;

    @Schema(description = "所属模板编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "所属模板不能为空")
    private Long templateId;

    @Schema(description = "用例标题，列表里显示的那一行字", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "设备报修单，两级审批")
    @NotBlank(message = "用例标题不能为空")
    @Size(max = 128, message = "用例标题长度不能超过 128")
    private String title;

    @Schema(description = "用例正文，点选后原样插入聊天输入框（模板填好之后的样子）",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "我要管理设备报修，需要记录这些信息：报修单号、报修设备、故障描述、报修人、期望完成时间。")
    @NotBlank(message = "用例正文不能为空")
    @Size(max = 8000, message = "用例正文长度不能超过 8000")
    private String content;

    @Schema(description = "期望要点：一次合格回答结构上该出现什么（只在管理页展示，不进模型上下文）",
            example = "要给出数据模型的菜单路径，并逐字段说明类型与是否必填；建表前必须先给 DDL 预览等确认")
    @Size(max = 500, message = "期望要点长度不能超过 500")
    private String expectHint;

    @Schema(description = "期望分型：CHAT / BUILD，留空表示「这条正压在边界上，两种都算对，别拿它做断言」",
            example = "BUILD")
    @Size(max = 8, message = "期望分型长度不能超过 8")
    private String expectIntent;

    @Schema(description = "排序，小的在前（同一条模板内部）", example = "10")
    private Integer sort;

    @Schema(description = "状态，见 CommonStatusEnum：0 开启 / 1 关闭。关闭后不出现在聊天面板", example = "0")
    private Integer status;

}
