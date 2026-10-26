package cn.enlev.ainb.module.agent.service.tooldef;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.module.agent.controller.admin.tooldef.vo.AgentToolDefPageReqVO;
import cn.enlev.ainb.module.agent.controller.admin.tooldef.vo.AgentToolDefSaveReqVO;
import cn.enlev.ainb.module.agent.dal.dataobject.tooldef.AgentToolDefDO;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.infra.api.datainterface.dto.DataInterfaceSimpleRespDTO;

import jakarta.validation.Valid;
import java.util.List;

/**
 * 用户自定义工具目录 Service
 *
 * <h3>🔴 只服务「无 Java 代码」的用户自定义工具，绝不镜像代码工具</h3>
 * {@code @AgentTool} 代码工具由 {@code AgentToolRegistry} 权威登记、只读暴露。本 Service 管的是
 * {@code agent_tool_def} 里的数据接口工具（当前唯一可执行来源）这类「定义即数据」的工具，
 * 见 {@code ToolSourceEnum} 类注释。
 *
 * <h3>解析顺序：registry-first，本目录回落</h3>
 * {@link #resolveForGateway} 与 {@link #buildFunction} 只在 {@code AgentToolRegistry.get(name)} 返回 null 时
 * 被网关/执行器调用——代码工具永远优先，用户工具抢不到代码工具的名字（登记时也会拒重名）。
 *
 * @author 走进新时代
 */
public interface AgentToolDefService {

    /**
     * 网关回落用的最小载体：只暴露「是否启用 + 需要哪些权限」
     *
     * <p>🔴 刻意<b>不</b>把整行 DO / config 交给网关：网关只该判断「能不能调」，
     * 不该看到「怎么调」。权限与启用状态是独立列，config 里的接口绑定与授权无关。
     *
     * @param enabled     是否启用
     * @param permissions 调用所需权限串（AND 语义）；空列表表示登录即可调用
     * @param riskLevel   风险等级；沙箱据此判定「只读工具真跑、写操作 mock」。
     *                    自定义工具的 risk 列缺失或非法时，保守回落为 {@link RiskLevel#HIGH}（当作写操作 mock）
     */
    record GatewayView(boolean enabled, List<String> permissions, RiskLevel riskLevel) {
    }

    /**
     * 创建用户自定义工具
     *
     * @param createReqVO 登记信息
     * @return 编号
     */
    Long createToolDef(@Valid AgentToolDefSaveReqVO createReqVO);

    /**
     * 更新用户自定义工具
     *
     * @param updateReqVO 登记信息（含 id）
     */
    void updateToolDef(@Valid AgentToolDefSaveReqVO updateReqVO);

    /**
     * 删除用户自定义工具
     *
     * @param id 编号
     */
    void deleteToolDef(Long id);

    /**
     * 获得用户自定义工具（不存在抛 {@code AGENT_TOOL_DEF_NOT_EXISTS}）
     *
     * @param id 编号
     */
    AgentToolDefDO getToolDef(Long id);

    /**
     * 分页获得用户自定义工具
     */
    PageResult<AgentToolDefDO> getToolDefPage(AgentToolDefPageReqVO pageReqVO);

    /**
     * 列出当前租户<b>启用中</b>的全部自定义工具（变更清单 / DSL 校验 / planner 提示词用）
     *
     * <p>与 {@link #getToolDefPage} 的区别：那个给管理页（含停用行、要分页），这个只列能被调到的。
     *
     * <p>🔴 <b>不要</b>拿本方法给名称目录用，改用 {@link #getToolDefListIncludingDisabled()}。
     * 两者问的是不同的问题：本方法问「这个键会不会真的被调到」（停用行调用必被网关拒，
     * 把它当已知工具会让清单报一项根本不会发生的变更、让 planner 编出一个必然失败的步骤）；
     * 目录问「这个键叫什么名字」，而名字与启用状态无关。D4-27 之前目录误用了本方法，
     * 后果见 {@link #getToolDefListIncludingDisabled()} 的注释。
     *
     * <p>🔴 在调用方的租户上下文里跑，{@code TenantLineInnerInterceptor} 自动拼 {@code tenant_id=?}。
     */
    List<AgentToolDefDO> getEnabledToolDefList();

    /**
     * 列出当前租户的<b>全部</b>自定义工具（含停用行），仅 {@code AgentNameCatalogService} 合流用
     *
     * <p>🔴 为什么目录必须看到停用行（D4-27）：目录的职责是「机器键 → 人能读的名字」。
     * 此前目录用 {@link #getEnabledToolDefList()}，于是管理员一停用某个自定义工具，
     * 它就从目录里<b>整个消失</b>：
     * <ul>
     *   <li>存量 DSL 里那个键翻不出名字，前端 {@code el-select} 的 {@code getOption} 回落到
     *       {@code currentLabel = value} —— 画布上从「销售日报」退化成一串裸机器键，
     *       看上去像「这工具不存在」，而它只是被关了；</li>
     *   <li>下拉是 {@code allow-create} 的，用户还能把那个键重新敲回去，敲回去之后<b>一个警告都没有</b>。</li>
     * </ul>
     * 而<b>内置</b>工具走的是另一条路（{@code AgentToolRegistry.list()} 本来就返回全部，
     * 由 {@code enabled} 字段让前端置灰）。同一个「停用」动作在两类工具上处置不一致 ——
     * 内置的置灰、自定义的消失 —— 这正是要修的东西。修完两类统一成「都下发、都置灰」。
     *
     * <p>🔴 前端<b>不需要</b>任何改动就已经会置灰：{@code nameCatalog.disabledToolBlock} 读的是
     * {@code t.enabled} 且与 {@code source} 无关，而 {@code AgentNameCatalogServiceImpl.toCustomItem}
     * 早在 D4-26 就改成读真实列值（{@code Boolean.TRUE.equals(row.getEnabled())}）而不是写死 true ——
     * 那一笔当时钉的是**契约**，本方法让它变成可达的形状。
     *
     * <p>🔴 在调用方的租户上下文里跑，{@code TenantLineInnerInterceptor} 自动拼 {@code tenant_id=?}。
     */
    List<AgentToolDefDO> getToolDefListIncludingDisabled();

    /**
     * 列出可被自定义工具关联的数据接口（管理页的「选择数据接口」下拉用）
     *
     * <p>🔴 为什么不由前端直接调 infra 的数据接口列表：那是 {@code infra:data-interface:query}
     * 权限点，而配工具的管理员未必有它——没有就会出现「工具页打得开、下拉是空的且无提示」。
     * 这里走 agent 自己的 {@code agent:tool-def:query}，权限模型自洽。
     * 且只经 infra 的 api 层（{@code AgentArchitectureTest#testOnlyDependOnApiLayer} 钉死）。
     *
     * @param keyword 关键字，模糊匹配接口编码/名称；空则不过滤
     * @return 启用中的接口摘要（含入参定义，前端据此展示「这个接口要传什么」）
     */
    List<DataInterfaceSimpleRespDTO> listBindableInterfaces(String keyword);

    /**
     * 网关回落：按 toolKey 取当前租户的「启用状态 + 权限串」
     *
     * <p>由 {@code AgentSecurityGateway.checkTool} 在 registry 查不到时调用。
     * 查不到返回 {@code null}（网关据此抛 {@code AGENT_TOOL_NOT_FOUND}）。
     * 🔴 本方法在调用方的租户上下文里跑，{@code TenantLineInnerInterceptor} 自动拼 {@code tenant_id=?}，
     * 故 A 租户的工具绝不会被 B 租户解析到。
     *
     * @param toolKey 工具键
     */
    GatewayView resolveForGateway(String toolKey);

    /**
     * 执行器回落：按 toolKey 现场构造该工具的执行体
     *
     * <p>由 {@code AgentToolExecutor.execute} 在 registry 查不到时调用（已过网关）。
     * <ul>
     *     <li>source=DATA_INTERFACE → {@code new DataInterfaceToolFunction(config, dataInterfaceApi, ...)}</li>
     *     <li>source=FORM/SCRIPT → 抛 {@code AGENT_TOOL_SOURCE_UNSUPPORTED}（预留位）</li>
     *     <li>查不到 → {@code null}（执行器据此抛 {@code AGENT_TOOL_NOT_FOUND}）</li>
     * </ul>
     * 🔴 返回的 {@link AgentToolFunction} <b>不做</b>权限校验（见接口契约），校验已在网关完成。
     *
     * @param toolKey 工具键
     */
    AgentToolFunction buildFunction(String toolKey);

}
