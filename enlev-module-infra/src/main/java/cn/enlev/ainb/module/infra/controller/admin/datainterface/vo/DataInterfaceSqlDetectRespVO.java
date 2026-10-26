package cn.enlev.ainb.module.infra.controller.admin.datainterface.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * SQL 语句探测结果 VO
 *
 * 配置界面用它做「你选的动作类型和 SQL 对不上」的即时提示。
 * 注意这只是**辅助提示**：动作类型最终以用户选的为准，
 * 因为多条 SQL 组合起来算什么动作，只有用户自己清楚。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - SQL 语句探测结果 Response VO")
@Data
public class DataInterfaceSqlDetectRespVO {

    @Schema(description = "是否解析成功", requiredMode = Schema.RequiredMode.REQUIRED, example = "true")
    private Boolean success;

    @Schema(description = "解析失败原因。success=false 时有值", example = "SQL 语法解析失败：...")
    private String errorMsg;

    @Schema(description = "SQL 里的语句条数", example = "2")
    private Integer statementCount;

    @Schema(description = "逐条语句的类型，顺序与 SQL 中一致", example = "[\"INSERT\", \"SELECT\"]")
    private List<String> statementTypes;

    @Schema(description = "去重后检出的写语句类型。为空表示 SQL 全是查询", example = "[\"INSERT\"]")
    private List<String> writeStatementTypes;

    @Schema(description = "是否含写操作", example = "true")
    private Boolean hasWriteOperation;

    @Schema(description = "建议的动作类型。首条写语句的类型，全是查询时为 SELECT", example = "INSERT")
    private String suggestedStatementType;

}
