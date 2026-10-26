package cn.enlev.ainb.module.system.framework.permission.core.subject;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.core.ArrayValuable;

import java.util.Arrays;

/**
 * 授权主体的类型
 *
 * 「主体」= 权限授予的对象，回答「谁」。七种类型统一寻址，见 {@link SubjectKey}。
 *
 * ⚠️ 枚举名直接落库（varchar），不要改名。
 * 用 varchar 而不是 tinyint 是有意的：主体表与成员表是低频的配置数据，量级在千行以内，
 * 省那几个字节没有意义，而排查权限问题时能直接 select 出 ROLE/DEPT 而不是 2/3，价值很大。
 *
 * <h3>实现 ArrayValuable 是为了 {@code GrantSaveReqVO#subjectType} 上那条 {@code @InEnum}</h3>
 *
 * 🔴 {@link #parse} 对枚举外的值<b>返回 null 而不是抛异常</b>，那是给「读库存量数据」的路径
 * 用的（一行脏数据不该让整个权限求值炸掉）。但同一个宽容度用在<b>写入</b>路径上就是缺陷：
 * 存进去一个 {@code ROLES}（复数，最常见的笔误）或 {@code role}（小写），
 * 授权行建得出来、列表里看得见、也不报错，而闭包永远算不出这个主体 ——
 * <b>这一行是死的</b>。如果它是一条 DENY，配的人以为封住了，其实什么都没封。
 * 与 {@code GrantActionEnum} / {@code GrantEffectEnum} / {@code FieldModeEnum} 完全同形，
 * 那三个早就有 {@code @InEnum} 拦着了，subjectType 是漏网的最后一个。
 *
 * @author 走进新时代
 */
public enum SubjectTypeEnum implements ArrayValuable<String> {

    /**
     * 用户。ref_id = system_users.id
     *
     * ⚠️ 用户【不会】在 sys_subject 里落行（见 SubjectService 的类注释），
     * 只作为寻址串与成员关系的一端出现
     */
    USER,
    /**
     * 角色。ref_id = system_role.id
     */
    ROLE,
    /**
     * 部门。ref_id = system_dept.id
     *
     * 默认不作为授权主体参与闭包，由 enlev.permission.subject.dept-enabled 开关控制
     */
    DEPT,
    /**
     * 岗位。ref_id = system_post.id
     */
    POST,
    /**
     * 用户组。ref_id = sys_subject.id 自身
     *
     * 与其它类型不同，用户组没有独立的业务表 —— 它的成员关系直接落 sys_subject_member。
     * 过渡期内 bpm_user_group 仍然存在，合并进来是 S6a 的事
     */
    GROUP,
    /**
     * 系统身份。不指向任何业务表，用 code 寻址，如 SYSTEM:bpm-engine
     *
     * 触发器、监听器、引擎回写状态不是人的行为，现状靠 @DataPermission(enable = false) 绕过，
     * 等于在模型上开了个无主体的口子。变成显式主体后同样走决策、同样可授权、同样进审计
     */
    SYSTEM,
    /**
     * 所有登录用户。不指向任何业务表，固定 code {@code all-users}
     *
     * <h3>为什么需要它</h3>
     *
     * 「不限制」在授权模型里必须是<b>一条显式的授权</b>，不能是「没有授权」。
     * 流程资源的 {@code default_effect} 是 DENY（S5d §2.2），
     * 所以「这个流程谁都能发起」表达不出来 —— 只能靠 {@code startUserIds}
     * 两个范围都为空时的那条回落分支兜着。
     *
     * 回落一摘，所有没配 START 授权的流程立刻变成<b>谁都发不了</b>。
     * 有了 ALL 主体，「不限制」就成了一条看得见、可审计、可在授权界面上撤销的授权行，
     * 而不是「缺少某行」这种没法展示也没法解释的状态（S7b / TODO #30）。
     *
     * ⚠️ 它<b>永远在每个登录用户的主体闭包里</b>（见 {@code SubjectClosureServiceImpl}）。
     * 所以给 ALL 配 DENY 等于对所有人关闭 —— 这是刻意的，
     * 「先全关再逐个开」是常见的收紧手法
     */
    ALL;

    /**
     * 全部合法的类型名，供 {@code @InEnum} 渲染提示语
     *
     * ⚠️ 与 {@link #parse} 必须同源：那边逐个比对 {@code name()}，这边也是 {@code name()}，
     * 所以不会出现「校验放行、解析返回 null」的缝
     */
    public static final String[] ARRAYS = Arrays.stream(values())
            .map(Enum::name).toArray(String[]::new);

    @Override
    public String[] array() {
        return ARRAYS;
    }

    /**
     * 是否指向业务表主键（即用 ref_id 寻址）
     *
     * SYSTEM 与 ALL 不是 —— 它们没有业务实体，用 code 寻址
     */
    public boolean isRefBased() {
        return this != SYSTEM && this != ALL;
    }

    /**
     * 解析类型名，解析不出来返回 null（而不是抛异常）
     *
     * 返回 null 而不是抛异常，是因为它的调用方是「读库存量数据」的路径：
     * 库里出现一个不认识的类型（比如回滚了一半的版本），应该是跳过这一行继续算，
     * 而不是让整个权限求值炸掉 —— 后者会让所有人都登不上来
     */
    public static SubjectTypeEnum parse(String name) {
        if (StrUtil.isBlank(name)) {
            return null;
        }
        for (SubjectTypeEnum type : values()) {
            if (type.name().equals(name)) {
                return type;
            }
        }
        return null;
    }

}
