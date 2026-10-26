package cn.enlev.ainb.module.agent.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 用户自定义工具的来源类型（{@code agent_tool_def.source}）
 *
 * <h3>🔴 为什么代码工具不在这张表里</h3>
 * {@code @AgentTool} 代码工具的<b>身份与执行体</b>由 {@code AgentToolRegistry} 权威登记，
 * 不可经数据新建或删除 —— 本枚举只描述<b>无 Java 代码</b>、必须由数据承载定义的用户自定义工具。
 *
 * <p>🔴 上面这句话在 2026-09-29 之前还带着半句「其权限串/风险等级<b>刻意不做成可运行时改写的数据</b>——
 * 把安全约束交给数据意味着改一行库就能让 {@code datamodel_create} 不再要确认」。
 * <b>那半句已经不成立了，别照着它推理</b>：2026-09-28 的产品决策推翻了这条底线，
 * {@code agent_tool_override} 让内置工具的展示名/说明/分组/<b>风险等级</b>/<b>权限串</b>/启用状态
 * 全部可按租户在线改写、改完立即生效（见 {@code AgentToolOverrideService}）。
 * 于是「安全约束交给数据」这件事真的发生了，替代它的防线是另外三条，都不在本枚举里：
 * <ol>
 *   <li>运行期各道闸一律读<b>声明</b>风险级 {@code getDeclaredRisk()}，不读生效值 ——
 *       一行覆盖数据压不动 DSL 校验、agent_loop 只读限制、发布判定与沙箱 mock（§3.1 第 5 条红线）；</li>
 *   <li>放松（降风险 / 删权限）要<b>二次确认</b>且<b>落库审计</b>，见 {@code agent_tool_override_audit}；</li>
 *   <li>{@code AgentArchitectureTest} 用源码扫描守住第 1 条，防止有人「照着隔壁抄」把闸改回读生效值。</li>
 * </ol>
 * 注意第 1 条只覆盖<b>风险</b>：网关判权限时读的仍是<b>生效</b>权限串，故删空权限是实打实的放松，
 * 靠第 2 条留痕、不靠第 1 条兜住。
 *
 * <h3>🔴 为什么只剩「数据接口」一种来源（2026-09-29）</h3>
 * 此前还放行 {@code HTTP}：管理员在这里填 method/url/headers/鉴权凭据，自己发一个出站请求。
 * 它与数据接口子系统<b>是同一件事配两处</b>——那边已经把 SQL / HTTP 转发 / 静态 JSON 三种来源、
 * 入参定义、只读判定、租户注入、系统表保护、版本快照与调用日志全做完了，而且 HTTP 出站还额外要求
 * 一套 SSRF 守卫与凭据脱敏。留着它，管理员改了数据接口的地址而这边仍指着旧的，且这边绕开了全部治理。
 * 故 HTTP 来源连同 {@code HttpToolConfig/HttpToolFunction/HttpToolSsrfGuard} 一并删除，
 * 自定义工具收敛成「数据接口的 AI 包装层」：只补 AI 需要而数据接口不提供的语义
 * （叫什么、何时调、多危险、要什么权限），见 {@code DataInterfaceToolConfig}。
 *
 * <p>{@code FORM} 与 {@code SCRIPT} 仍是预留位：{@code AgentToolDefService.buildFunction}
 * 遇到它们抛 {@code AGENT_TOOL_SOURCE_UNSUPPORTED}。表单数据的 CRUD 已由 {@code form_data_*}
 * 代码工具覆盖，故 FORM 短期内不会接；SCRIPT 待沙箱能力就位后再说。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum ToolSourceEnum {

    /** 数据接口工具：关联 {@code infra_data_interface} 里一个已治理的接口（当前唯一可执行的来源） */
    DATA_INTERFACE("DATA_INTERFACE", "数据接口工具"),

    /** 表单数据工具：选已发布 formdesign 自动出的 CRUD（预留，能力已由 form_data_* 代码工具覆盖） */
    FORM("FORM", "表单数据工具"),

    /** 脚本工具：沙箱执行（预留，设计方案列 P7+） */
    SCRIPT("SCRIPT", "脚本工具"),
    ;

    private final String source;
    private final String description;

    /** 当前唯一可执行的来源 */
    public static boolean isDataInterface(String source) {
        return DATA_INTERFACE.source.equals(source);
    }

}
