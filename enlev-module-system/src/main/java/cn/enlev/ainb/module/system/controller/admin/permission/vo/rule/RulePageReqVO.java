package cn.enlev.ainb.module.system.controller.admin.permission.vo.rule;

import cn.enlev.ainb.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

import static cn.enlev.ainb.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

@Schema(description = "管理后台 - 行过滤规则分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class RulePageReqVO extends PageParam {

    @Schema(description = "规则名称，模糊匹配", example = "仅自己")
    private String name;

    @Schema(description = "规则模板代码，模糊匹配", example = "BUILT_IN_OWNER_ONLY")
    private String template;

    @Schema(description = "状态：0 启用 1 停用", example = "0")
    private Integer status;

    @Schema(description = "是否系统内置", example = "true")
    private Boolean builtIn;

    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    @Schema(description = "创建时间")
    private LocalDateTime[] createTime;

}
