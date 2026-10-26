package cn.enlev.ainb.module.agent.controller.admin.prompttemplate.vo;

import cn.enlev.ainb.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 管理后台 - 提示词模板用例分页 Request VO（S7 / L5）
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - 提示词模板用例分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentPromptTemplateCasePageReqVO extends PageParam {

    @Schema(description = "所属模板编号，精确匹配（管理页的用例子表就靠它）", example = "1")
    private Long templateId;

    @Schema(description = "用例标题，模糊匹配", example = "报修")
    private String title;

    @Schema(description = "用例正文，模糊匹配（找「哪条用例里提到过 xx」）", example = "DDL 预览")
    private String content;

    @Schema(description = "期望分型，精确匹配：CHAT / BUILD。留空是「两种都算对」，与不筛选不是一回事",
            example = "BUILD")
    private String expectIntent;

    @Schema(description = "状态，见 CommonStatusEnum：0 开启 / 1 关闭", example = "0")
    private Integer status;

}
