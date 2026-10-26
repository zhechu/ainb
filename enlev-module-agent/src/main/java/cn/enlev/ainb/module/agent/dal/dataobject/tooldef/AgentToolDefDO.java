package cn.enlev.ainb.module.agent.dal.dataobject.tooldef;

import cn.enlev.ainb.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户自定义工具定义 DO
 *
 * <h3>🔴 这张表【只】存「无 Java 代码」的用户自定义工具</h3>
 * {@code @AgentTool} 代码工具由 {@code AgentToolRegistry} 权威登记、只读暴露
 * （{@code GET /agent/config/tool-list}），<b>不落这张表</b>——其权限串/风险等级刻意不做成可运行时
 * 改写的数据（把安全约束交给数据 = 改一行库就能让 {@code datamodel_create} 不再要确认）。
 * 本表存的是数据接口工具（当前唯一可执行来源）/ 表单 / 脚本（预留）这类「定义即数据」的工具，
 * 见 {@link cn.enlev.ainb.module.agent.enums.ToolSourceEnum} 类注释。
 *
 * <h3>解析顺序：registry-first，本表回落</h3>
 * {@code AgentToolExecutor} / {@code AgentSecurityGateway} 先查 {@code AgentToolRegistry}（代码工具），
 * 查不到才回落到本表（按 {@link #toolKey}）。故 {@link #toolKey} 登记时禁止与代码工具重名——
 * 即便漏校验，代码工具也永远优先，用户工具抢不到那个名字。
 *
 * @author 走进新时代
 */
@TableName("agent_tool_def")
@KeySequence("agent_tool_def_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentToolDefDO extends TenantBaseDO {

    private Long id;

    /**
     * 工具键，租户内唯一，workflow {@code tool} 节点的 {@code toolId} 按此引用。
     * 格式 {@code ^[a-z][a-z0-9_]{1,63}$}；不得与代码工具名冲突（见类注释）。
     */
    private String toolKey;

    /**
     * 展示名（管理页/设计器用）
     */
    private String displayName;

    /**
     * 工具说明，进 LLM prompt 与设计器
     */
    private String description;

    /**
     * 来源，枚举 {@link cn.enlev.ainb.module.agent.enums.ToolSourceEnum}
     * 当前仅 DATA_INTERFACE 可执行；FORM/SCRIPT 为预留位。
     */
    private String source;

    /**
     * 分组（管理页与设计器归类用，可空）
     */
    private String groupName;

    /**
     * 风险等级，枚举 {@link cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel}
     * 🔴 登记必填、无默认——会改数据的接口不该被默认当成只读，强制管理员显式选。
     */
    private String riskLevel;

    /**
     * 调用所需权限串，逗号分隔（AND 语义，与代码工具一致）；由网关逐个 hasPermission 校验。
     * 🔴 {@code config.mode=INVOKE}（全能力含写）时必须含 {@code infra:data-interface:invoke}，
     * 因为 {@code DataInterfaceApi} 自己不做任何权限校验，写门完全靠这一列。
     */
    private String permissions;

    /**
     * 是否启用；停用后网关直接拒（AGENT_TOOL_DISABLED）。
     */
    private Boolean enabled;

    /**
     * 数据接口绑定 JSON：{@code {code,mode}}，由 {@code DataInterfaceToolConfig} 解析。
     * 🔴 列名用 {@code config}（非 input/output，避开 jsqlparser/TenantLineInnerInterceptor 的裸词坑）。
     * 🔴 <b>不含凭据</b>：地址/SQL/鉴权全在 {@code infra_data_interface} 那边治理，故读接口无需脱敏。
     */
    private String config;

    /**
     * 参数说明（自由文本，进 prompt）。留空时服务端按绑定接口的入参定义自动生成
     * （见 {@code AgentToolDefServiceImpl.effectiveParamsSchema}），故正常不会是空。
     */
    private String paramsSchema;

    /**
     * 备注
     */
    private String remark;

}
