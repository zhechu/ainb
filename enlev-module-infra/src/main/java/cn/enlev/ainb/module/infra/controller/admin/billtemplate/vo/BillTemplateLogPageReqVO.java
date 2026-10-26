package cn.enlev.ainb.module.infra.controller.admin.billtemplate.vo;

import cn.enlev.ainb.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

import static cn.enlev.ainb.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

@Schema(description = "管理后台 - 取号管理 - 取号日志分页 Request VO")
@Data
public class BillTemplateLogPageReqVO extends PageParam {

    @Schema(description = "模板编号", example = "1")
    private Long templateId;

    @Schema(description = "单据编号，模糊匹配", example = "CG2026")
    private String billNo;

    @Schema(description = "取号来源：1-业务出号 2-表单预占", example = "1")
    private Integer source;

    @Schema(description = "是否成功", example = "true")
    private Boolean success;

    @Schema(description = "取号时间", example = "[2026-09-13 00:00:00,2026-09-20 23:59:59]")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] createTime;

}
