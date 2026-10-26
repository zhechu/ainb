package cn.enlev.ainb.module.agent.service.tooldef;

import cn.enlev.ainb.module.agent.controller.admin.tooldef.vo.AgentRegisteredToolRespVO;
import cn.enlev.ainb.module.agent.controller.admin.tooldef.vo.AgentRegisteredToolUpdateReqVO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 内置（已注册）工具在线维护 Service（2026-09-28 增量）
 *
 * <h3>🔴 管的是「代码工具的运维旋钮」，不是新工具</h3>
 * {@code @AgentTool} 代码工具的执行体与默认值永远在 {@code AgentToolRegistry}（代码即事实）。
 * 本 Service 只维护 {@code agent_tool_override} 覆盖表：改展示名/说明/分组/风险/权限/启用/参数说明，
 * 改完把覆盖值套进注册表的<b>生效值</b>，<b>立即生效、无需重启</b>。「恢复默认」= 删覆盖行 + 生效值退回声明值。
 *
 * <p>🔴 这里刻意<b>不写</b>「共 N 个内置工具」：这个数会随加工具漂移，而漂移的表现是注释里一个
 * 与注册表对不上的数字（本类原注释写的 22，2026-10-05 实测是 30）。要数就查 {@code AgentToolRegistry#list()}。
 *
 * <p>🔴 但「生效值」并非处处生效，别按字面理解：网关的<b>启用状态与权限串</b>读生效值
 * （{@code AgentSecurityGateway#checkTool}），而<b>风险等级</b>的全部运行期闸
 * （DSL 校验 V3/V11、Planner 工具目录、agent_loop 只读限制、发布判定、沙箱 mock 判定、变更清单）
 * 读的都是<b>声明值</b> {@code getDeclaredRisk()}；生效风险值只剩界面在读。
 * 逐处清单见实现类注释与台账 D4-22。
 *
 * <h3>🔴 全字段自由维护的安全取舍（产品决策 + 迭代四 §11 A-Ⅲ 复核）</h3>
 * 允许调低风险、删空权限（放松），裁定是「<b>保留放松能力 + 落库审计 + 二次确认</b>」，
 * 不是「降险即从自主路径消失」。故 {@link #update} 对放松做三件事：算清放松了什么 →
 * 未带 {@code confirmRelax=true} 就<b>拒</b>（此时一个字都不落库）→ 已确认则 upsert 之后插一行
 * {@code agent_tool_override_audit}（与 upsert 同事务）并 {@code log.warn}。
 * 只写日志是不够的：日志会滚会清、生产上没人 grep，而「谁在什么时候把什么从 X 改成了 Y」
 * 恰恰是事后复核安全取舍时唯一要问的那句话。
 *
 * @author 走进新时代
 */
public interface AgentToolOverrideService {

    /**
     * 列出全部内置工具的合并视图（生效值 + 声明值 + 是否维护中），供管理页表格。
     *
     * <p>顺序跟随注册表（{@code LinkedHashMap}，即 bean 扫描序）。不分页（规模=内置工具数，个位数×若干）。
     */
    List<AgentRegisteredToolRespVO> listMerged();

    /**
     * 维护一个内置工具（整份提交覆盖），改完立即生效。
     *
     * @param updateReqVO 覆盖字段；{@code toolName} 必须已注册（否则抛 {@code AGENT_TOOL_OVERRIDE_TOOL_NOT_REGISTERED}）
     * @throws cn.enlev.ainb.framework.common.exception.ServiceException 本次维护放松了安全约束
     *         （风险调低 / 权限删少）却没带 {@code confirmRelax=true} 时抛
     *         {@code AGENT_TOOL_OVERRIDE_RELAX_NEEDS_CONFIRM}，报错文案里带一句说清代价的话。
     *         🔴 抛出时<b>覆盖行与审计行都不写</b> —— 判定在 upsert 之前。
     */
    void update(@Valid AgentRegisteredToolUpdateReqVO updateReqVO);

    /**
     * 恢复默认：删覆盖行 + 生效值退回代码声明，立即生效。
     *
     * <p>覆盖行不存在时静默成功（幂等：结果都是「回到默认」）。
     *
     * @param toolName 工具名；必须已注册
     */
    void reset(String toolName);

}
