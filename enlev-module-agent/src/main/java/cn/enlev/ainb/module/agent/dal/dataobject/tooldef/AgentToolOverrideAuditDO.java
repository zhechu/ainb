package cn.enlev.ainb.module.agent.dal.dataobject.tooldef;

import cn.enlev.ainb.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 内置工具「安全放松」审计行 DO（2026-10-05 增量，B1 / S11）
 *
 * <h3>🔴 这张表每一行都是<b>一次放松</b>，不是「一次维护」</h3>
 * 只有把风险档调到<b>低于</b>代码声明、或把权限串删到<b>少于</b>代码声明时才会落一行
 * （判据在 {@code AgentToolOverrideServiceImpl#detectRelaxation}）。改展示名、改说明、
 * 调<b>高</b>风险、加权限都不落行 —— 那些动作没有安全代价，全记只会把真正要复核的几十行
 * 淹进几千行流水里。「谁在什么时候把什么从 X 改成了 Y」这句话里的 X→Y，指的永远是放松。
 *
 * <h3>🔴 只有落库成功的放松才有行；被二次确认挡下的那次没有</h3>
 * 审计行在覆盖行 upsert <b>之后</b>插入，且两者同一个事务。于是：
 * 未确认 ⟹ 抛异常 ⟹ 覆盖行与审计行都不存在；已确认但 upsert 失败 ⟹ 一起回滚。
 * 两种情况下都不会出现「有放松痕迹但没真放松」或「真放松了却没痕迹」的错位。
 *
 * <h3>为什么不复用 {@code creator} / {@code updater}</h3>
 * 本表只插不改，故 {@code updater} / {@code update_time} 恒等于 {@code creator} / {@code create_time}，
 * 没有信息量。而 {@code creator} 是 {@code String} 型的 userId，查「哪个人放松了建表工具」时
 * 还得回 {@code system_users} 关联一次才知道是谁 —— {@link #operatorName} 就是把那次关联省掉：
 * 审计行要能在<b>事后、脱离当次请求上下文</b>时被读懂，这是它相对于 {@code log.warn} 的全部价值。
 *
 * @author 走进新时代
 */
@TableName("agent_tool_override_audit")
@KeySequence("agent_tool_override_audit_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentToolOverrideAuditDO extends TenantBaseDO {

    private Long id;

    /**
     * 被放松的内置工具名（= {@code @AgentTool#name()} / 注册表键）
     */
    private String toolName;

    /**
     * 放松前的风险等级（= 代码声明值 {@code getDeclaredRisk()}）；本次未放松风险时为空串
     */
    private String riskFrom;

    /**
     * 放松后的风险等级（= 本次提交的覆盖值）；本次未放松风险时为空串
     */
    private String riskTo;

    /**
     * 放松前是否需用户确认（由 {@link #riskFrom} 推导）。
     *
     * <p>🔴 这两列记的是<b>风险等级语义上</b>的确认要求变化，不是「系统从此不再拦它」。
     * 迭代三把全部运行期闸改读<b>声明</b>风险级之后，降风险已经不改任何强制行为了
     * （生效值 {@code getRisk()} 只剩界面在读）—— 详见 {@code AgentToolOverrideServiceImpl} 类注释
     * 与台账 D4-22。仍然记下它，是因为审计行要能回答「操作人当时<b>以为</b>自己在放松什么」，
     * 而界面上给他看的就是这个确认要求标注。
     */
    private Boolean requireConfirmFrom;

    /**
     * 放松后是否需用户确认（由 {@link #riskTo} 推导）
     */
    private Boolean requireConfirmTo;

    /**
     * 放松前的权限串（= 代码声明值，逗号分隔）；本次未放松权限时为空串
     */
    private String permissionsFrom;

    /**
     * 放松后的权限串（= 本次提交的覆盖值）；空串在这里<b>有意义</b>：表示「调用只需登录」
     */
    private String permissionsTo;

    /**
     * 操作人编号（= {@code SecurityFrameworkUtils.getLoginUserId()}）
     */
    private Long operatorId;

    /**
     * 操作人昵称。取不到登录用户时为空串（例如定时任务或测试里直接调 Service）——
     * 🔴 取不到<b>不</b>影响落行：痕迹宁可少一个名字，也不能因为拿不到名字就不记这次放松。
     */
    private String operatorName;

    /**
     * 操作人在维护面板「备注」里填的理由，原样抄一份进来。
     *
     * <p>抄而不是关联 {@code agent_tool_override.remark}：覆盖行是<b>会被后续维护整份重写</b>的，
     * 三个月后查这次放松的理由时，那行的 remark 早就被改成别的了。审计行必须自带当时的事实。
     */
    private String remark;

}
