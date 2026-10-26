package cn.enlev.ainb.module.system.controller.admin.user.vo.user;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 用户导出 Request VO
 *
 * <h3>为什么单开一个 VO 而不是复用 UserPageReqVO</h3>
 *
 * 导出比分页多一件事：<b>范围</b>。而「范围」不是查询条件 ——
 * 把它塞进分页 VO 的话，列表查询那条路径上会多出一个永远用不到的字段，
 * 而看到它的人会去猜它对查询有没有影响。
 *
 * @author 芋道源码
 */
@Schema(description = "管理后台 - 用户导出 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class UserExportReqVO extends UserPageReqVO {

    /**
     * 导出范围
     */
    public enum Scope {
        /**
         * 当前这一页 —— 他正看着的那些行
         */
        CURRENT_PAGE,
        /**
         * 按当前查询条件的全部（受导出上限约束）
         */
        ALL
    }

    @Schema(description = "导出范围：CURRENT_PAGE-当前页 / ALL-全部。默认 ALL", example = "ALL")
    private String scope;

    /**
     * 解析范围
     *
     * 认不出的值按「全部」处理，不抛异常 —— 导出是个读操作，
     * 为一个拼错的枚举值让用户导不出来不值得。反过来（认不出就抛）会让
     * 前端发了个新值、后端还没部署时，导出整体不可用。
     */
    public Scope resolveScope() {
        if (scope == null || scope.isBlank()) {
            return Scope.ALL;
        }
        try {
            return Scope.valueOf(scope.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Scope.ALL;
        }
    }

    public boolean isCurrentPage() {
        return resolveScope() == Scope.CURRENT_PAGE;
    }

}
