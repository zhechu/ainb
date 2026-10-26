package cn.enlev.ainb.module.infra.controller.admin.datainterface.vo;

import cn.enlev.ainb.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

import static cn.enlev.ainb.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

@Schema(description = "管理后台 - 接口管理 - 调用日志分页 Request VO")
@Data
public class DataInterfaceLogPageReqVO extends PageParam {

    @Schema(description = "接口编号", example = "1")
    private Long interfaceId;

    @Schema(description = "接口编码，模糊匹配", example = "order")
    private String interfaceCode;

    @Schema(description = "调用方式：1-在线预览 2-接口调用", example = "1")
    private Integer invokeType;

    @Schema(description = "是否成功", example = "true")
    private Boolean success;

    @Schema(description = "创建时间", example = "[2026-08-01 00:00:00,2026-08-31 23:59:59]")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] createTime;

}
