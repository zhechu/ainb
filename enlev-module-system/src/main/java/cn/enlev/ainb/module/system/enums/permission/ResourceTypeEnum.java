package cn.enlev.ainb.module.system.enums.permission;

import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 资源类型枚举
 *
 * 与 MenuTypeEnum 不同：这里表达的是「这个权限点是什么东西」，而不是它在导航树里的位置。
 * 绝大多数 system_menu 里的「按钮」行对应 ACTION，而非全部。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum ResourceTypeEnum {

    /**
     * 导航菜单（目录 / 菜单，有 path/component 的那层）
     * 通常不直接授权，通过它的子 ACTION 控制
     */
    MENU("MENU"),
    /**
     * 操作权限（对应原 system_menu type=BUTTON，有 permission 字符串的那层）
     * 例如：system:user:query / system:user:create
     */
    ACTION("ACTION"),
    /**
     * 流程定义（BPM 流程）
     */
    PROCESS("PROCESS"),
    /**
     * 流程分类（BPM 分类）
     *
     * 它是 {@link #PROCESS} 的父资源。分类继承就是靠资源树实现的 ——
     * 给分类授权，其下所有流程自动继承（祖先链在 GrantDecisionService 里已经支持），
     * 不需要为「分类继承」单独写一套逻辑
     */
    CATEGORY("CATEGORY"),
    /**
     * 数据集（在线表单的整张表）
     *
     * 默认效果 ALLOW —— 它不是访问控制点，见 {@link #defaultEffect()}
     */
    DATASET("DATASET"),
    /**
     * 列（在线表单列表里的一列）。默认效果 ALLOW，按需落行
     */
    COLUMN("COLUMN"),
    /**
     * 字段（在线表单详情里的一个字段）。默认效果 ALLOW，按需落行
     */
    FIELD("FIELD");

    private final String code;

    /**
     * 该类型的默认效果：DATASET/COLUMN/FIELD 为 ALLOW，其余为 DENY
     *
     * v9 §4.1：「加字段忘配权限 → 线上看不见且不报错」是最贵的故障形态，
     * 所以 COLUMN/FIELD 的默认是 ALLOW（让它显示），
     * 而 ACTION/MENU/PROCESS 的默认是 DENY（必须显式授权才能用）。
     *
     * DATASET 也是 ALLOW，理由不同：它**不是访问控制点**。
     * 「能不能进这张表单」由它下面的六个 ACTION 资源
     * （{@code infra:form-design-run:{code}:query} 等）承担，那些是 DENY 的；
     * DATASET 只是列/字段资源的挂载点，以及行过滤规则与语义槽位的锚。
     *
     * 若把 DATASET 设成 DENY，零配置环境下所有在线表单会被全面拒访 ——
     * 而授权页要到 S8a 才有，那期间没有任何人能把它配回来。
     */
    public String defaultEffect() {
        return (this == DATASET || this == COLUMN || this == FIELD) ? "ALLOW" : "DENY";
    }

    public static ResourceTypeEnum parse(String code) {
        if (StrUtil.isBlank(code)) {
            return null;
        }
        for (ResourceTypeEnum type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }

}
