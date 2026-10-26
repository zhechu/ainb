package cn.enlev.ainb.module.system.controller.admin.user.vo.user;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户导入执行 Response VO
 *
 * <h3>为什么新增、更新、跳过分开计数</h3>
 *
 * 只报一个「成功 100 条」的话，用户没法确认自己选的重复策略真的生效了 ——
 * 而「策略没生效」恰恰是这类功能最需要被看出来的事：选了「跳过」却把线上账号
 * 全覆盖成了 Excel 里的值，这件事不会报错，只会安静地发生。
 * 四个数加起来等于实际处理的行数，用户自己就能核对。
 *
 * <h3>失败明细带行号</h3>
 *
 * 改造前失败明细是一个 {@code Map<用户名, 原因>}，用户名为空时退化成「第 N 行」——
 * 而那个 N 是按 List 下标数的，文件里有空行时就对不上。这里的 {@code rowNo}
 * 是从 Excel 里读到的真实行号，能直接拿去定位。
 *
 * @author 芋道源码
 */
@Schema(description = "管理后台 - 用户导入执行 Response VO")
@Data
public class UserImportResultRespVO {

    @Schema(description = "新增成功的条数", example = "98")
    private Integer createCount = 0;

    @Schema(description = "更新成功的条数", example = "15")
    private Integer updateCount = 0;

    @Schema(description = "跳过的条数：用户自己剔掉的、以及策略为 SKIP 时库里已存在的", example = "3")
    private Integer skipCount = 0;

    @Schema(description = "失败的条数", example = "2")
    private Integer failCount = 0;

    @Schema(description = "失败明细")
    private List<Failure> failures = new ArrayList<>();

    @Schema(description = "整批没导的原因。有值时上面四个数都是 0")
    private List<UserImportPreviewRespVO.PreviewError> tableErrors = new ArrayList<>();

    /**
     * 一条失败记录
     */
    @Schema(description = "用户导入失败明细")
    @Data
    public static class Failure {

        @Schema(description = "Excel 里的行号", example = "7")
        private Integer rowNo;

        @Schema(description = "登录名，便于用户在 Excel 里定位", example = "zhangsan")
        private String username;

        @Schema(description = "出错字段的标题", example = "部门编号")
        private String fieldLabel;

        @Schema(description = "失败原因", example = "部门不存在")
        private String message;

        @Schema(description = "单元格原文", example = "999")
        private String rawValue;

    }

}
