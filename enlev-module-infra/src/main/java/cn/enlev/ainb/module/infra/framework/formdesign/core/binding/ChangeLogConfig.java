package cn.enlev.ainb.module.infra.framework.formdesign.core.binding;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 数据变更日志配置
 *
 * 启用后，每次更新都对比新旧值，把「谁在什么时候把哪个字段从什么改成了什么」
 * 记成一条时间线记录。
 *
 * <h3>为什么配在 tableBinding 里</h3>
 *
 * 与逻辑删除、乐观锁并列：它们都是「这张表的数据怎么写」的约定，
 * 都在同一份绑定配置里，加它不需要给 infra_form_design 再开一列。
 *
 * @author 走进新时代
 */
@Data
public class ChangeLogConfig {

    @Schema(description = "是否记录数据变更日志", example = "true")
    private Boolean enabled = false;

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

}
