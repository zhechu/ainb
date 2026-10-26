package cn.enlev.ainb.module.system.framework.permission.core.subject;

import cn.hutool.core.util.StrUtil;

import java.util.Objects;

/**
 * 主体的统一寻址
 *
 * 四张表（用户/角色/部门/岗位）加一张 bpm_user_group 各有各的成员关系，
 * 主体解析要查四次；本类把它们统一成一个值对象，寻址串形如：
 *
 * <pre>
 *     USER:1024          用户 1024
 *     ROLE:5             角色 5
 *     DEPT:12            部门 12
 *     POST:3             岗位 3
 *     GROUP:88           用户组 88
 *     SYSTEM:bpm-engine  流程引擎的系统身份
 * </pre>
 *
 * 寻址串是**对外的唯一表示**：缓存的 key、日志、诊断页、以及 S2a 的 sys_grant
 * 都用它，不要另造一套。
 *
 * ⚠️ 本类是 record，天然具备 equals/hashCode，可以直接做 Set/Map 的 key。
 * 加字段前先想清楚：字段进了 equals，闭包去重的语义就变了。
 *
 * @author 走进新时代
 */
public record SubjectKey(SubjectTypeEnum type, Long refId, String code) {

    /**
     * 寻址串里类型与标识的分隔符
     */
    public static final String SEPARATOR = ":";

    /**
     * 紧凑构造器：三条不变量
     *
     * <p>⚠️ 这里刻意抛 {@code NullPointerException} / {@code IllegalArgumentException}，
     * <b>不</b>换业务错误码。两个理由：本类在 {@code framework} 层，不该认识
     * {@code ErrorCode}；而且这三条说的是「调用方程序员搞错了」，不是「用户填错了」。
     *
     * <p>用户会填错的那一层已经在更外面拦掉了 —— {@code GrantAdminController#resolveSubject}
     * 与 {@code RecordShareController#parseSubject} 都是先校验再构造（缺哪个标识就报哪个），
     * 读库的 {@link #parse} 与 {@code SubjectDO#toKey} 则一律返回 {@code null}，
     * 因为一行脏数据不该让整个权限求值炸掉。所以这三条只可能被程序化误用触发，
     * 而那种时候大声炸掉正是想要的：换成一句温和的业务提示，bug 就藏起来了
     */
    public SubjectKey {
        Objects.requireNonNull(type, "主体类型不能为空");
        if (type.isRefBased()) {
            Objects.requireNonNull(refId, "主体 " + type + " 必须有 refId");
            // code 统一置空，避免出现「refId 相同但 code 不同」的两个 key 不相等
            code = null;
        } else {
            if (StrUtil.isBlank(code)) {
                throw new IllegalArgumentException("主体 " + type + " 必须有 code");
            }
            refId = null;
        }
    }

    public static SubjectKey of(SubjectTypeEnum type, Long refId) {
        return new SubjectKey(type, refId, null);
    }

    public static SubjectKey ofUser(Long userId) {
        return of(SubjectTypeEnum.USER, userId);
    }

    public static SubjectKey ofRole(Long roleId) {
        return of(SubjectTypeEnum.ROLE, roleId);
    }

    public static SubjectKey ofDept(Long deptId) {
        return of(SubjectTypeEnum.DEPT, deptId);
    }

    public static SubjectKey ofPost(Long postId) {
        return of(SubjectTypeEnum.POST, postId);
    }

    public static SubjectKey ofGroup(Long groupId) {
        return of(SubjectTypeEnum.GROUP, groupId);
    }

    public static SubjectKey ofSystem(String code) {
        return new SubjectKey(SubjectTypeEnum.SYSTEM, null, code);
    }

    /**
     * 「所有登录用户」主体的固定 code
     */
    public static final String ALL_USERS_CODE = "all-users";

    /**
     * 「所有登录用户」主体
     *
     * 它永远在每个登录用户的闭包里，用来把「不限制」表达成一条显式授权，
     * 而不是「缺少某行」（理由见 {@link SubjectTypeEnum#ALL}）
     */
    public static SubjectKey ofAllUsers() {
        return new SubjectKey(SubjectTypeEnum.ALL, null, ALL_USERS_CODE);
    }

    /**
     * 解析寻址串，解析不出来返回 null
     *
     * 与 {@link SubjectTypeEnum#parse} 同理：调用方是读缓存/读库的路径，
     * 一行脏数据不应该让整个权限求值炸掉
     */
    public static SubjectKey parse(String text) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        int index = text.indexOf(SEPARATOR);
        if (index <= 0 || index == text.length() - 1) {
            return null;
        }
        SubjectTypeEnum type = SubjectTypeEnum.parse(text.substring(0, index));
        if (type == null) {
            return null;
        }
        String identifier = text.substring(index + 1);
        if (!type.isRefBased()) {
            // SYSTEM 与 ALL 都是 code 寻址，按解析出来的类型还原，不能一律当 SYSTEM
            return new SubjectKey(type, null, identifier);
        }
        try {
            return of(type, Long.parseLong(identifier));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 寻址串。⚠️ 不要改成 toString() 的输出，调用方依赖它做缓存 key
     */
    public String asString() {
        return type.name() + SEPARATOR + (type.isRefBased() ? refId : code);
    }

    @Override
    public String toString() {
        return asString();
    }

}
