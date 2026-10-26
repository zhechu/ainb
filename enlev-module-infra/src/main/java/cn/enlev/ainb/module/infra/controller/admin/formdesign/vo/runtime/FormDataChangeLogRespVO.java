package cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.runtime;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表单数据变更日志 Response VO
 *
 * 一条记录 = 一次提交里的全部字段变更。前端把它渲染成时间线上的一项：
 * 「谁 在什么时候 把哪几个字段从什么改成了什么」。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - 表单数据变更日志 Response VO")
@Data
public class FormDataChangeLogRespVO {

    @Schema(description = "编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "变更内容 JSON 数组",
            example = "[{\"field\":\"amount\",\"title\":\"金额\",\"oldValue\":\"10\",\"newValue\":\"20\"}]")
    private String changes;

    @Schema(description = "改动的人", example = "1")
    private String creator;

    @Schema(description = "改动的时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createTime;

}
