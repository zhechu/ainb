package cn.enlev.ainb.module.infra.controller.admin.datainterface.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 接口管理 - 调用日志 Response VO")
@Data
public class DataInterfaceLogRespVO {

    @Schema(description = "编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "接口编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long interfaceId;

    @Schema(description = "接口编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "order_list")
    private String interfaceCode;

    @Schema(description = "调用方式：1-在线预览 2-接口调用", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer invokeType;

    @Schema(description = "请求参数 JSON")
    private String requestParams;

    @Schema(description = "实际执行的 SQL，含自动注入的租户条件")
    private String executeSql;

    @Schema(description = "是否成功", requiredMode = Schema.RequiredMode.REQUIRED, example = "true")
    private Boolean success;

    @Schema(description = "失败原因摘要，最长 500 字符")
    private String errorMsg;

    @Schema(description = "失败原因完整内容，展开行显示")
    private String errorDetail;

    @Schema(description = "返回记录数", example = "10")
    private Integer resultCount;

    @Schema(description = "耗时，单位：毫秒", requiredMode = Schema.RequiredMode.REQUIRED, example = "35")
    private Integer duration;

    @Schema(description = "调用人编号", example = "1")
    private Long userId;

    @Schema(description = "调用 IP", example = "127.0.0.1")
    private String userIp;

    @Schema(description = "调用时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createTime;

}
