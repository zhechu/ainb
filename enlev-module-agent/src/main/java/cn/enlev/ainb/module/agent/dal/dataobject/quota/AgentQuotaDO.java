package cn.enlev.ainb.module.agent.dal.dataobject.quota;

import cn.enlev.ainb.framework.mybatis.core.dataobject.BaseDO;
import cn.enlev.ainb.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * AI 助手配额 DO
 *
 * @author 走进新时代
 */
@TableName("agent_quota")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentQuotaDO extends TenantBaseDO {

    @TableId
    private Long id;

    /**
     * 用户编号，{@code null} 表示不限定用户（租户级 / Agent 级 / 工作流级都为 null）
     *
     * 🔴 刻意用 null 而不是 0 表达「不限定用户」：0 是个合法的用户编号取值，
     * 拿它当哨兵值，将来一定会有人写出 {@code user_id = 0} 的查询，
     * 然后查出一条本不属于任何用户的租户级配额。
     *
     * <p>⚠️ 自 P7（待办 #6b）起，「是不是用户级」不再单看这一列是否为空 ——
     * Agent 级、工作流级配额的 user_id 同样是 null。判别一律以 {@link #dimension} 为准。
     */
    private Long userId;

    /**
     * 配额维度：TENANT / USER / AGENT / WORKFLOW
     *
     * <p>枚举 {@link cn.enlev.ainb.module.agent.enums.AgentQuotaDimensionEnum}。
     * 🔴 这是判别配额归属的<b>权威列</b>：TENANT 看全租户、USER 看 {@link #userId}、
     * AGENT / WORKFLOW 看 {@link #targetKey}。历史行由迁移脚本回填（user_id 空→TENANT，非空→USER）。
     */
    private String dimension;

    /**
     * 维度目标键，仅 AGENT / WORKFLOW 用：存对应定义的 {@code code}（非 numeric id）。
     *
     * <p>🔴 存 code 而不是 id：两类定义都<b>版本化</b>（同 code 多行），id 指向单一版本，
     * 绑 id 的话作者一发新版配额就静默失效（平台头号缺陷「配了不生效」）。存 code 跨版本稳定，
     * 且能把同 code 各版本用量合并计入本周期。与 chat 触发器的 {@code trigger_key} 同口径。
     * TENANT / USER 维度此列为 null。
     */
    private String targetKey;

    /**
     * 周期：DAY / MONTH
     */
    private String period;

    /**
     * token 上限，{@code null} 表示该维度不限
     */
    private Long tokenLimit;

    /**
     * 金额上限，{@code null} 表示该维度不限
     */
    private BigDecimal costLimit;

    /**
     * 预警比例，达到后通知管理员
     */
    private BigDecimal warnRatio;

    /**
     * 已发过预警的周期标识，如 {@code 2026-09}
     *
     * 用来去重。不记的话，越过预警线之后<b>每一次请求都会发一条</b>，
     * 管理员一天收几百条，结果是他把通知关掉 —— 于是真正触顶时也没人知道。
     */
    private String warnedPeriod;

    /**
     * 状态，见 {@link cn.enlev.ainb.framework.common.enums.CommonStatusEnum}
     *
     * ⚠️ 0 是开启、1 是关闭，与直觉相反。
     */
    private Integer status;

}
