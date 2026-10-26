package cn.enlev.ainb.module.infra.controller.admin.billtemplate.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 取号管理 - 取号日志 Response VO")
@Data
public class BillTemplateLogRespVO {

    @Schema(description = "编号", example = "1")
    private Long id;

    @Schema(description = "模板编号", example = "1")
    private Long templateId;

    @Schema(description = "模板编码", example = "purchase_order")
    private String templateCode;

    @Schema(description = "生成的单据号；取号失败时为空", example = "CG202609200001")
    private String billNo;

    @Schema(description = "生成方式快照：1-时间流水 2-随机数 3-UUID", example = "1")
    private Integer genType;

    @Schema(description = "周期标记快照", example = "20260920")
    private String period;

    @Schema(description = "本次消耗的流水序号；随机/UUID 为空", example = "1")
    private Long seq;

    @Schema(description = "取号来源：1-业务出号 2-表单预占", example = "1")
    private Integer source;

    @Schema(description = "是否成功", example = "true")
    private Boolean success;

    @Schema(description = "失败原因摘要")
    private String errorMsg;

    @Schema(description = "取号人编号", example = "1")
    private Long userId;

    @Schema(description = "取号时间")
    private LocalDateTime createTime;

}
