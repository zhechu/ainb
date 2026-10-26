package cn.enlev.ainb.module.agent.controller.admin.prompttemplate.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理后台 - 提示词模板 Response VO（需求7）
 *
 * <p>管理页与聊天面板共用这一个 VO：模板正文不是敏感信息（它就是要给用户看、给用户用的），
 * 拆一个「精简版」只会多一处字段漂移的机会。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - 提示词模板 Response VO")
@Data
public class AgentPromptTemplateRespVO {

    @Schema(description = "编号", example = "1")
    private Long id;

    @Schema(description = "模板名称", example = "追问某条数据的来龙去脉")
    private String name;

    @Schema(description = "模板正文，支持 {{占位符}}", example = "请查一下订单 {{订单号}} 为什么还没发货")
    private String content;

    @Schema(description = "分类，空表示未分类", example = "数据查询")
    private String category;

    @Schema(description = "用途说明", example = "追查单笔订单的履约进度")
    private String description;

    @Schema(description = "排序", example = "10")
    private Integer sort;

    @Schema(description = "状态，见 CommonStatusEnum：0 开启 / 1 关闭", example = "0")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
