package cn.enlev.ainb.module.system.enums.permission;

import cn.enlev.ainb.framework.common.util.object.ObjectUtils;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 角色编码枚举
 *
 * 只收录<b>内置</b>角色 —— 它们的 code 是身份，代码里按串比对（见 {@link #isSuperAdmin}），
 * 所以既不许改，也在 {@code RoleServiceImpl#validateRoleForUpdate} 里被挡住不许编辑。
 * 租户管理员每个租户各有一行同 code 的角色，这里只登记 code 本身
 */
@Getter
@AllArgsConstructor
public enum RoleCodeEnum {

    SUPER_ADMIN("super_admin", "超级管理员"),
    TENANT_ADMIN("tenant_admin", "租户管理员"),
    CRM_ADMIN("crm_admin", "CRM 管理员"); // CRM 系统专用
    ;

    /**
     * 角色编码
     */
    private final String code;
    /**
     * 名字
     */
    private final String name;

    public static boolean isSuperAdmin(String code) {
        return ObjectUtils.equalsAny(code, SUPER_ADMIN.getCode());
    }

}
