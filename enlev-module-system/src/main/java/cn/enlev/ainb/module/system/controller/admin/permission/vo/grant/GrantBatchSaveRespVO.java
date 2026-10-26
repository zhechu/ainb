package cn.enlev.ainb.module.system.controller.admin.permission.vo.grant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 授权行批量新增响应 VO
 *
 * <h3>为什么返回计数而不是 Boolean</h3>
 *
 * upsert 语义下（同键行被<b>覆盖</b>而不是跳过、也不是报错），操作者最需要知道的是
 * 「我这次到底新增了几行、又覆盖了几行」。只回一个 true 的话，
 * 「重复提交同一批」与「首次提交」在界面上长得一模一样 —— 而前者恰恰是最容易
 * 让人怀疑「是不是又插出重复行了」的场景。计数让这件事当场可验证。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - 授权行批量新增响应")
@Data
public class GrantBatchSaveRespVO {

    @Schema(description = "新插入的行数", requiredMode = Schema.RequiredMode.REQUIRED, example = "8")
    private Integer insertedCount;

    @Schema(description = "覆盖既有同键行的行数（upsert 命中）", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "4")
    private Integer updatedCount;

    /**
     * 收敛掉的历史重复行数
     *
     * <p>库里同键多行且内容一致时，保留 id 最小的一行、软删其余（见
     * {@code GrantAdminServiceImpl#validateConvergeable}）。实测本库曾有 15 组
     * 这样的三份相同拷贝。单独计一个数并回给前端，是为了让「库里本来就有重复行、
     * 这次顺手清了」这件事可见 —— 否则管理员会看到列表行数莫名变少
     */
    @Schema(description = "收敛掉的历史同键重复行数：保留 id 最小的一行、软删其余",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "30")
    private Integer convergedCount;

}
