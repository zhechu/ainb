package cn.enlev.ainb.module.system.dal.dataobject.permission;

import cn.enlev.ainb.framework.tenant.core.db.TenantBaseDO;
import cn.enlev.ainb.module.system.enums.permission.GrantActionEnum;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectTypeEnum;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 权限授权
 *
 * 一行记录 = 「谁（主体）在哪（上下文）对什么（资源）能做什么（动作），效果是什么（ALLOW/DENY）」。
 *
 * ## 效果优先级（v9 §7.1 第 5 步）
 *
 * 全局 DENY > 上下文 DENY > 上下文 ALLOW > 全局 ALLOW > resource.default_effect
 *
 * 所以 DENY 行是「明确禁止」，ALLOW 行是「明确允许」。
 * 一旦存在 DENY 就封死该资源，不受任何 ALLOW 影响 ——
 * 这是「管理员不小心给错了权限」时的安全兜底。
 *
 * ## 过渡期说明
 *
 * 现阶段 system_role_menu 仍然是真实授权的来源。
 * sys_grant 验证 hasAnyPermission 切换正确后，system_role_menu 才退场（见交接文档 §2.1）。
 *
 * ## S2a 暂不用的字段
 *
 * - context_resource_id：S2b 启用，当前一律 NULL（全局）
 * - rule_id：S3a 建 sys_rule 后启用，当前一律 NULL（不过滤行）
 * - field_mode：S2b 实现字段模式三层合并后启用，当前一律 NULL
 *
 * @author 走进新时代
 */
@TableName("sys_grant")
@KeySequence("sys_grant_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增。如果是 MySQL 等数据库，可不写。
@Data
@EqualsAndHashCode(callSuper = true)
public class GrantDO extends TenantBaseDO {

    @TableId
    private Long id;

    /**
     * 主体类型，见 {@link SubjectTypeEnum}
     */
    private String subjectType;

    /**
     * 主体指向的业务表主键；SYSTEM 类型为 null
     */
    private Long subjectRefId;

    /**
     * 主体编码，仅 SYSTEM 类型使用
     */
    private String subjectCode;

    /**
     * 资源编号，关联 {@link ResourceDO#getId()}
     */
    private Long resourceId;

    /**
     * 上下文资源编号，NULL = 全局；目前仅 BPM 节点使用，S2b 启用
     */
    private Long contextResourceId;

    /**
     * 动作，见 {@link GrantActionEnum}
     */
    private String action;

    /**
     * 效果：ALLOW 或 DENY
     */
    private String effect;

    /**
     * 行过滤规则编号，S3a 启用
     *
     * ⚠️ updateStrategy=ALWAYS：这三个字段都「允许被编辑清空」（解绑规则 / 取消字段模式 /
     * 取消到期时间），而 updateById 默认跳过 null 字段 —— 不加这个注解时
     * 「编辑授权把到期时间清掉」会静默不生效，界面上表现为取消不了过期时间
     * （MP 3.5.16 里旧版的 IGNORED 已移除，等价常量是 ALWAYS）
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long ruleId;

    /**
     * 字段显示模式，S2b 启用
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String fieldMode;

    /**
     * 到期时间，NULL = 永不过期
     *
     * <h3>🔴 过期对 ALLOW 和 DENY 一视同仁，这是刻意的</h3>
     *
     * {@link #isActive()} 不区分效果，于是「给某人配一条三天后到期的 DENY」
     * = <b>临时封禁，到期自动解封</b>。
     *
     * 另一种设计是「DENY 永不过期」，但那会让「临时收回权限」变成一件
     * 必须人工记得去撤销的事 —— 而没人会记得，结果是封禁永久化，
     * 且当事人只会说「我突然就没权限了」，查不到任何时间线索。
     *
     * 这一条有回归单测钉着（{@code GrantDecisionEngineTest} 的
     * {@code 过期的DENY也失效_临时封禁到期自动解封}），别当成 bug 改掉。
     *
     * <h3>过滤在两处做，都要保留</h3>
     *
     * <ul>
     *   <li>SQL 侧：{@code GrantMapper#selectListByResourceIds} —— 少传一批行</li>
     *   <li>内存侧：{@link #isActive()}，被决策引擎调用 ——
     *       挡住「调用方从别处拿到 GrantDO」的路径</li>
     * </ul>
     * 只留一处的话，另一条取数路径上的过期授权就会照常生效。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime expireTime;

    /**
     * 主体是否有效（未过期、类型可解析）
     */
    public boolean isActive() {
        if (expireTime != null && LocalDateTime.now().isAfter(expireTime)) {
            return false;
        }
        return SubjectTypeEnum.parse(subjectType) != null;
    }

    /**
     * 是否为全局授权（无上下文）
     */
    public boolean isGlobal() {
        return contextResourceId == null;
    }

    /**
     * 主体是否匹配指定的 SubjectKey
     */
    public boolean subjectMatches(SubjectKey key) {
        if (key == null) {
            return false;
        }
        if (!key.type().name().equals(subjectType)) {
            return false;
        }
        if (key.type().isRefBased()) {
            return key.refId() != null && key.refId().equals(subjectRefId);
        }
        return key.code() != null && key.code().equals(subjectCode);
    }

    /**
     * 动作是否匹配（通配符 '*' 匹配所有）
     */
    public boolean actionMatches(String requestedAction) {
        if (GrantActionEnum.WILDCARD.getCode().equals(action)) {
            return true;
        }
        return action != null && action.equals(requestedAction);
    }

}
