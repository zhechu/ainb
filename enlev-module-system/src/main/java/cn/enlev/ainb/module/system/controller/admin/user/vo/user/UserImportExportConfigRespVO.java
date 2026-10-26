package cn.enlev.ainb.module.system.controller.admin.user.vo.user;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户导入导出的限制 Response VO
 *
 * <h3>为什么要有这个接口</h3>
 *
 * 导出弹窗要在用户点「导出」<b>之前</b>就把上限说清楚 —— 而上限是服务端常量。
 * 前端硬编码一个 2 万，后端哪天调了之后，界面上写的数和实际生效的数就对不上了，
 * 而这种「提示的数字是假的」比根本不提示更糟：用户会照着它规划自己的分批。
 *
 * @author 芋道源码
 */
@Schema(description = "管理后台 - 用户导入导出限制 Response VO")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserImportExportConfigRespVO {

    @Schema(description = "一次最多导出多少条", requiredMode = Schema.RequiredMode.REQUIRED, example = "20000")
    private Integer maxExportRows;

    @Schema(description = "一次最多导入多少行", requiredMode = Schema.RequiredMode.REQUIRED, example = "20000")
    private Integer maxImportRows;

    @Schema(description = "导入文件的大小上限（MB）", requiredMode = Schema.RequiredMode.REQUIRED, example = "10")
    private Integer maxImportFileSizeMb;

}
