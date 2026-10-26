package cn.enlev.ainb.module.infra.framework.formdesign.core.binding;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.infra.enums.formdesign.AutoFillModeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 系统字段自动填充配置
 *
 * 绑定已有表时，表里的审计列名不一定叫 creator / create_time，
 * 因此做成可配置。值为空表示该项不填充。
 *
 * 每个审计列除了列名，还有一个「填充方式」：
 * <ul>
 *   <li>AUTO：后端填当前登录人/当前时间，表单里不该出现该字段</li>
 *   <li>MANUAL：完全交给表单的字段映射，后端跳过（数据补录、代客户填写场景）</li>
 *   <li>NONE：两边都不管，保持数据库默认值或触发器的结果</li>
 * </ul>
 *
 * ⚠️ AUTO 与字段映射不能同时指向一列，否则 buildInsert 会把该列拼两次，
 * 生成 INSERT INTO t (creator, creator) 这种非法 SQL。发布校验会拦住。
 *
 * @author 走进新时代
 */
@Data
public class AutoFillConfig {

    @Schema(description = "创建人列名", example = "creator")
    private String creatorColumn;

    @Schema(description = "创建人填充方式", example = "AUTO")
    private String creatorMode = "AUTO";

    @Schema(description = "创建时间列名", example = "create_time")
    private String createTimeColumn;

    @Schema(description = "创建时间填充方式", example = "AUTO")
    private String createTimeMode = "AUTO";

    @Schema(description = "更新人列名", example = "updater")
    private String updaterColumn;

    @Schema(description = "更新人填充方式", example = "AUTO")
    private String updaterMode = "AUTO";

    @Schema(description = "更新时间列名", example = "update_time")
    private String updateTimeColumn;

    @Schema(description = "更新时间填充方式", example = "AUTO")
    private String updateTimeMode = "AUTO";

    // ==================== 判定方法 ====================

    /**
     * 创建人列是否由后端自动填充
     *
     * 列名为空时一律不填 —— 该表没有这一列。
     */
    public boolean isCreatorAuto() {
        return isAuto(creatorColumn, creatorMode);
    }

    public boolean isCreateTimeAuto() {
        return isAuto(createTimeColumn, createTimeMode);
    }

    public boolean isUpdaterAuto() {
        return isAuto(updaterColumn, updaterMode);
    }

    public boolean isUpdateTimeAuto() {
        return isAuto(updateTimeColumn, updateTimeMode);
    }

    private boolean isAuto(String column, String mode) {
        return StrUtil.isNotBlank(column) && AutoFillModeEnum.parse(mode) == AutoFillModeEnum.AUTO;
    }

    /**
     * 收集所有 AUTO 模式的列名
     *
     * 供发布校验检测「该列同时被表单字段映射占用」的冲突。
     * 用 LinkedHashSet 保持声明顺序，报错信息里列名的出现顺序才稳定。
     */
    public Set<String> getAutoModeColumns() {
        Set<String> columns = new LinkedHashSet<>();
        if (isCreatorAuto()) {
            columns.add(creatorColumn);
        }
        if (isCreateTimeAuto()) {
            columns.add(createTimeColumn);
        }
        if (isUpdaterAuto()) {
            columns.add(updaterColumn);
        }
        if (isUpdateTimeAuto()) {
            columns.add(updateTimeColumn);
        }
        return columns;
    }

    /**
     * 收集所有已配置列名（不分模式）
     *
     * 供 SQL 白名单使用 —— MANUAL 模式的列也要能出现在 SELECT 里。
     */
    public Set<String> getAllColumns() {
        Set<String> columns = new LinkedHashSet<>();
        for (String column : new String[]{creatorColumn, createTimeColumn, updaterColumn, updateTimeColumn}) {
            if (StrUtil.isNotBlank(column)) {
                columns.add(column);
            }
        }
        return columns;
    }

}
