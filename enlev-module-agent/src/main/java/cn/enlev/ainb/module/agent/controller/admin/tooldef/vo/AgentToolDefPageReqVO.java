package cn.enlev.ainb.module.agent.controller.admin.tooldef.vo;

import cn.enlev.ainb.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 管理后台 - 用户自定义工具分页 Request VO（增量5a）
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - 用户自定义工具分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentToolDefPageReqVO extends PageParam {

    @Schema(description = "工具键，模糊匹配", example = "health")
    private String toolKey;

    @Schema(description = "来源", example = "HTTP")
    private String source;

    @Schema(description = "分组", example = "monitor")
    private String groupName;

    @Schema(description = "是否启用", example = "true")
    private Boolean enabled;

}
