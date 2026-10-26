package cn.enlev.ainb.module.system.dal.dataobject.permission;

import cn.enlev.ainb.framework.mybatis.core.dataobject.BaseDO;
import cn.enlev.ainb.framework.tenant.core.aop.TenantIgnore;
import cn.enlev.ainb.module.system.enums.permission.RuleTemplateEnum;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 行过滤规则
 *
 * 一条规则 = 一个模板 + 模板参数（JSON）。规则通过 sys_grant.rule_id 挂在授权行上。
 *
 * 规则作用于语义槽位（如 {owner}），不直接作用于真实列名。
 * 语义槽位 → 真实列名的映射在 sys_slot_mapping 里。
 * 这是防 SQL 注入的关键：列名来自映射表（可审计），不来自用户输入。
 *
 * ## 全局表，不分租户
 *
 * 规则是「一段可复用的行过滤定义」，内置模板更是全系统一份。
 * 跟 {@link ResourceDO} 同样的理由：租户差异体现在 {@code sys_grant}
 * ——「哪个租户的哪个角色，套用了哪条规则」。
 *
 * @author 走进新时代
 */
@TableName("sys_rule")
@KeySequence("sys_rule_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@TenantIgnore
public class RuleDO extends BaseDO {

    @TableId
    private Long id;

    /** 规则名称，便于在授权页面的下拉框里选择 */
    private String name;

    /**
     * 规则模板代码，见 {@link RuleTemplateEnum}
     * 内置模板用 BUILT_IN_ 前缀；自定义模板没有前缀限制
     */
    private String template;

    /**
     * 模板参数，JSON 对象。具体字段由模板定义
     *
     * 例如 BUILT_IN_STATUS_IN 模板的参数：
     * {"allowedStatuses": [1, 2, 3]}
     *
     * S3b 建条件树 DSL 后，BUILT_IN_CUSTOM_TREE 的参数包含完整的条件树 JSON
     */
    private String params;

    /** 规则描述，展示给管理员 */
    private String description;

    /** 状态，见 CommonStatusEnum */
    private Integer status;


    /**
     * 是否系统内置
     *
     * 内置规则由种子 SQL 初始化，不允许删除（可以停用）。
     * ⚠️ 判据是这个字段，不是 {@code template} 以 BUILT_IN_ 开头 ——
     * 管理员自己建的规则同样可以选内置模板，拿模板判会把它们全误判成内置
     */
    private Boolean builtIn;

}
