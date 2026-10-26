package cn.enlev.ainb.module.infra.controller.admin.datainterface.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * SQL 语句探测请求 VO
 *
 * 用 body 传 SQL 而不是 query 参数：SQL 可能很长且含换行、特殊字符，
 * 放 URL 里既容易超长也容易被中间层截断。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - SQL 语句探测 Request VO")
@Data
public class DataInterfaceSqlDetectReqVO {

    @Schema(description = "待探测的 SQL，可含 #{xx} 占位符",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "INSERT INTO biz_order (order_no) VALUES (#{orderNo})")
    private String sql;

}
