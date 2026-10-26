package cn.enlev.ainb.module.agent.controller.admin.prompttemplate.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理后台 - 提示词模板用例 Response VO（S7 / L5）
 *
 * <h3>🔴 刻意不带 {@code templateName}</h3>
 * 管理页的用例子表本来就挂在某条模板下（模板名已知），聊天面板也已经加载了整份模板列表，
 * 两边都能按 {@link #templateId} 就地取名。多带一个冗余字段就多一处漂移的机会：
 * 模板改了名，用例列表还印着旧名，而两边的接口都是绿的。
 *
 * <p>与管理页/聊天面板共用这一个 VO，理由同 {@code AgentPromptTemplateRespVO}：
 * 用例正文不是敏感信息（它就是要给用户看、给用户照着发的）。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - 提示词模板用例 Response VO")
@Data
public class AgentPromptTemplateCaseRespVO {

    @Schema(description = "编号", example = "1")
    private Long id;

    @Schema(description = "所属模板编号", example = "1")
    private Long templateId;

    @Schema(description = "用例标题", example = "设备报修单，两级审批")
    private String title;

    @Schema(description = "用例正文，插入聊天输入框的内容", example = "我要管理设备报修，需要记录这些信息：…")
    private String content;

    @Schema(description = "期望要点（结构性，不进模型上下文）", example = "要给出数据模型的菜单路径…")
    private String expectHint;

    @Schema(description = "期望分型：CHAT / BUILD；null 表示「两种都算对」", example = "BUILD")
    private String expectIntent;

    @Schema(description = "排序", example = "10")
    private Integer sort;

    @Schema(description = "状态，见 CommonStatusEnum：0 开启 / 1 关闭", example = "0")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
