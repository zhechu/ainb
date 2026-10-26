package cn.enlev.ainb.module.agent.dal.dataobject.tooldef;

import cn.enlev.ainb.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 内置（已注册）工具的在线维护覆盖值 DO（2026-09-28 增量）
 *
 * <h3>🔴 这张表【只】存覆盖值，不存执行体</h3>
 * {@code @AgentTool} 代码工具的执行体与默认值永远在代码里（{@code AgentToolRegistry}）；
 * 本表每行表示「某个内置工具被在线维护过」，逐字段覆盖默认值。<b>行不存在 = 纯代码默认</b>，
 * 故「恢复默认」= 删行。内置工具不可经数据新建/删除——身份属于代码。
 * （原注释在这里写了「22 个」，2026-10-05 实测注册表里是 30 个；数字会随加工具漂移，故删掉不再写死。）
 *
 * <h3>🔴 覆盖如何生效（改完无需重启）</h3>
 * {@code AgentToolOverrideService} 在启动与每次 update/reset 时把覆盖值套进注册表的「生效值」，
 * 网关的<b>启用状态与权限串</b>读的就是生效值，故改完立即生效、按租户隔离。
 *
 * <p>🔴 但<b>风险等级</b>是个例外，别按「一律生效值」理解：运行期各道闸（DSL 校验 V3/V11、
 * Planner 工具目录、agent_loop 只读限制、发布判定、沙箱 mock 判定、变更清单）读的都是
 * <b>声明值</b> {@code getDeclaredRisk()}，生效风险值只剩界面在读（管理页 / 设计器目录 / 诊断端点）。
 * 所以改这一列不会让工具绕过任何判定，只会改它在界面上的风险标注 —— 见实现类注释与台账 D4-22。
 *
 * <p>产品决策允许全字段自由维护（含调低风险、删空权限）。放松不拦截，但要<b>二次确认</b>并落一行
 * {@code agent_tool_override_audit}（B1 / S11，2026-10-05）；此前只有 {@code log.warn}，
 * 日志会滚会清，等于没留痕。
 *
 * @author 走进新时代
 */
@TableName("agent_tool_override")
@KeySequence("agent_tool_override_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentToolOverrideDO extends TenantBaseDO {

    private Long id;

    /**
     * 内置工具名（= {@code @AgentTool#name()} / 注册表键）。覆盖谁就写谁；
     * Service 校验其必须存在于注册表，避免存出孤儿覆盖。
     */
    private String toolName;

    /**
     * 覆盖的展示名；空串=用代码默认（工具名）
     */
    private String displayName;

    /**
     * 覆盖的工具说明（进 LLM prompt 与管理页）；null=不覆盖
     */
    private String description;

    /**
     * 覆盖的分组（管理页归类用）
     */
    private String groupName;

    /**
     * 覆盖的风险等级，枚举 {@link cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel}；
     * 空串=不覆盖。🔴 可低于代码声明（产品决策），但低于声明时要<b>二次确认</b>，
     * 且落一行 {@code agent_tool_override_audit}。
     */
    private String riskLevel;

    /**
     * 覆盖的权限串，逗号分隔（AND 语义）；空串=调用只需登录（即删空代码声明的权限）。
     *
     * <p>🔴 这一列与 {@link #riskLevel} 不同：网关判权限时读的<b>就是</b>它，
     * 故删空是立即生效的真放松（不是只改界面标注）。同样要二次确认 + 落审计行。
     */
    private String permissions;

    /**
     * 覆盖的启用状态；停用后网关直接拒（AGENT_TOOL_DISABLED）
     */
    private Boolean enabled;

    /**
     * 覆盖的参数说明（进 prompt）；null=不覆盖
     */
    private String paramsSchema;

    /**
     * 备注（记录为何覆盖）
     */
    private String remark;

}
