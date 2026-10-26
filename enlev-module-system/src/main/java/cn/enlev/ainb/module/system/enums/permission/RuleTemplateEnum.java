package cn.enlev.ainb.module.system.enums.permission;

import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 行过滤规则模板枚举
 *
 * 模板是「条件树的骨架」，参数是运行时填进去的值。
 * 每个模板都作用于语义槽位（如 {owner}），不直接引用真实列名，
 * 这样同一条规则可以跨表单复用，只要目标表单有对应的槽位映射。
 *
 * ## 8 个内置模板（v8 §4.2）
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum RuleTemplateEnum {

    /**
     * 仅自己的数据：{owner} = :currentUser
     * 场景：销售只能看自己创建的客户
     */
    OWNER_ONLY("BUILT_IN_OWNER_ONLY", "仅自己的数据",
            "{owner} = :currentUser"),

    /**
     * 本部门数据：{dept} = :currentDept
     * 场景：部门经理能看本部门所有记录
     */
    DEPT_ONLY("BUILT_IN_DEPT_ONLY", "本部门数据",
            "{dept} = :currentDept"),

    /**
     * 本部门及下级部门：{dept} IN :currentDeptAndChildren
     * 场景：大区经理能看大区下所有部门的记录
     */
    DEPT_AND_CHILDREN("BUILT_IN_DEPT_AND_CHILDREN", "本部门及下级部门",
            "{dept} IN :currentDeptAndChildren"),

    /**
     * 指定状态可见：{status} IN :allowedStatuses
     * 参数：allowedStatuses（允许看到的状态值列表）
     * 场景：只让特定角色看到「已归档」的合同
     */
    STATUS_IN("BUILT_IN_STATUS_IN", "指定状态可见",
            "{status} IN :allowedStatuses"),

    /**
     * 仅自己的数据或本部门数据：{owner} = :currentUser OR {dept} = :currentDept
     * 场景：业务员看自己的，部门经理看本部门的
     */
    OWNER_OR_DEPT("BUILT_IN_OWNER_OR_DEPT", "自己或本部门数据",
            "{owner} = :currentUser OR {dept} = :currentDept"),

    /**
     * 流程参与者可见：派生可见性（来自 bpm_instance_participant）
     * 这个模板不产生 SQL WHERE，而是在行级过滤层走参与表 JOIN 路径
     * S5c 实现，S3a 先登记占位
     */
    BPM_PARTICIPANT("BUILT_IN_BPM_PARTICIPANT", "流程参与者可见",
            "DERIVED:bpm_participant"),

    /**
     * 共享给我的记录：来自 {@code sys_record_share}
     *
     * 与 {@link #BPM_PARTICIPANT} 同一条通道：都是「一串具体的业务主键对某人可见」，
     * 都落在 {@code bizKey} 槽位上，只是主键集合的来源不同
     * （一个来自流程参与事实，一个来自人工分享）。
     *
     * ⚠️ 只放开不收紧 —— 表里没有「不分享给谁」。收紧用 {@code sys_grant} 的 DENY。
     * 共享是一个人对另一个人的日常动作，让它能收紧的话，
     * 一次随手的操作就能把管理员配好的权限盖掉
     */
    RECORD_SHARED("BUILT_IN_RECORD_SHARED", "共享给我的记录",
            "DERIVED:record_share"),

    /**
     * 指定字段值匹配：{field} = :value
     * 参数：field（槽位名），value（期望值）
     * 最通用的单字段过滤模板
     */
    FIELD_EQUALS("BUILT_IN_FIELD_EQUALS", "指定字段等于",
            "{field} = :value"),

    /**
     * 指定部门数据：{dept} IN :ruleDeptIds OR {dept} = :currentDept
     * 参数：deptIds（允许看到的部门编号列表）
     *
     * <h3>对应旧 {@code data_scope} 的 DEPT_CUSTOM（指定部门数据权限）</h3>
     *
     * ⚠️ 表达式里那个 {@code OR {dept} = :currentDept} <b>不是多余的</b>，
     * 它是旧实现里一条必须保留的兜底（见 {@code PermissionServiceImpl#getDeptDataPermission}）：
     * 自定义可见部门时要保证人至少能看到自己所在的部门，否则一些场景会直接坏掉 ——
     * 例如登录时基于 username 查 {@code system_users}，会被 dept_id 条件过滤掉而登录失败。
     *
     * 迁移时漏掉这个 OR，表现就是「配了指定部门，结果那个部门的人登不进来」
     */
    DEPT_IN("BUILT_IN_DEPT_IN", "指定部门数据",
            "{dept} IN :ruleDeptIds OR {dept} = :currentDept"),

    /**
     * 自定义条件树（S3b 实现）
     * 参数：conditionTree（JSON 格式的条件树，S3b 建 DSL 后使用）
     */
    CUSTOM_TREE("BUILT_IN_CUSTOM_TREE", "自定义条件树",
            "CUSTOM:conditionTree");

    /**
     * 模板代码，落库值，内置模板用 BUILT_IN_ 前缀
     */
    private final String code;
    /**
     * 模板显示名称
     */
    private final String displayName;
    /**
     * 模板表达式（说明用，不直接用于 SQL 生成）
     */
    private final String expression;

    public static RuleTemplateEnum parse(String code) {
        if (StrUtil.isBlank(code)) {
            return null;
        }
        for (RuleTemplateEnum t : values()) {
            if (t.code.equals(code)) {
                return t;
            }
        }
        return null;
    }

    public boolean isBuiltIn() {
        return code.startsWith("BUILT_IN_");
    }

    public boolean isDerived() {
        return expression.startsWith("DERIVED:");
    }

    public boolean isCustomTree() {
        return this == CUSTOM_TREE;
    }

}
