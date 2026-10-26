package cn.enlev.ainb.module.infra.framework.formdesign.core.binding;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 乐观并发锁配置
 *
 * <h3>它解决的是「后写覆盖」</h3>
 *
 * 两个人同时打开同一条记录，甲改了金额、乙改了备注，谁后点保存谁的整份数据落库 ——
 * 先保存那个人的修改被静默覆盖，双方都看到「保存成功」。没有任何一方会发现。
 *
 * 做法是给主表配一个版本列：更新时带上「我读到的版本号」作为条件，
 * 同时把版本号 +1。别人已经改过的话，条件不成立、影响行数为 0，此时报错而不是当成成功。
 *
 * <h3>为什么做成可配置而不是默认开</h3>
 *
 * 与逻辑删除同一个理由：绑定的是**已有表**，多数业务表没有版本列，
 * 而加一列是 DDL。开关默认关，启用时由发布校验强制要求那一列真的存在。
 *
 * @author 走进新时代
 */
@Data
public class OptimisticLockConfig {

    @Schema(description = "是否启用乐观锁", example = "true")
    private Boolean enabled = false;

    /**
     * 版本列名
     *
     * 必须是整数列。启用后：
     * <ul>
     *   <li>查询会把它一并带出来（前端要拿它回传）</li>
     *   <li>更新时 {@code SET 版本列 = 版本列 + 1 WHERE ... AND 版本列 = ?}</li>
     *   <li>它不允许被表单字段映射 —— 表单值和自增会互相打架</li>
     * </ul>
     */
    @Schema(description = "版本列名", example = "version")
    private String column;

    /**
     * 是否启用且列名完整
     *
     * 单独一个方法而不是让调用方各写一遍 {@code enabled && isNotBlank(column)}：
     * 漏判 column 的表现是拼出 {@code SET  = } 这种语法错的 SQL。
     */
    public boolean isUsable() {
        return Boolean.TRUE.equals(enabled) && column != null && !column.trim().isEmpty();
    }

}
