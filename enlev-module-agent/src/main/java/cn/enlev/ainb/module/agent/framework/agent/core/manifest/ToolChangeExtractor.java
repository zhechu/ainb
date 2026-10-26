package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;

/**
 * 按 toolId 分派的变更抽取器（策略模式）
 *
 * <p>一个实现负责一个工具：把这个工具的 {@code config.args} 翻译成
 * 「人看得懂的、将会发生的改动」。实现类由 {@link ToolChangeExtractorRegistry} 在启动时自动收集，
 * 新增一种工具只要加一个 bean，不必回来改注册表 —— 与 {@code NodeExecutorRegistry} 同一套路。
 *
 * <h3>🔴 契约：只填一半字段</h3>
 * 见 {@link ChangeItem} 类注释。实现只该设 {@code kind} / {@code target} / {@code detail} / {@code summary}；
 * {@code tool} / {@code toolDisplayName} / {@code riskLevel} / {@code riskDescription} / {@code reversible} /
 * {@code nodeId} / {@code nodeName} 由 builder 用工具注册表的<b>生效值</b>覆盖。
 * 实现里设了也会被覆盖，别设。
 *
 * <h3>🔴 契约：不许返回 null</h3>
 * 返回 null 会让这项变更从清单里凭空消失 —— 而它<b>仍然会在运行期真的执行</b>。
 * 这是「清单少一块」最直接的一条路径，故 builder 收到 null 时不会静默跳过，
 * 而是打一条 warn 并退回 {@link GenericToolChangeExtractor} 的兜底产出。
 * 实现如果确实说不出更多，就返回一个只有 {@code kind} 与 {@code summary} 的项。
 *
 * <h3>参数是「DSL 里写的原文」，不是「运行期会收到的值」</h3>
 * 抽取器拿到的是<b>未解析</b>的 {@code config.args}：里面的 {@code {{$.x.y}}} 占位符
 * 此刻还没有值（清单在保存草稿/挂起确认时生成，那时图还没跑）。
 * 因此抽取器一律<b>原样展示占位符</b>，绝不能假装它已经是具体值 ——
 * 「运行期才确定」本身就是审批人必须知道的信息（见 {@link ManifestArgs#isPending}）。
 * 顺着占位符反查产出它的节点，要等 S2-e 的 {@code untrustedInputs} 把机制建起来（理由见
 * {@link ManifestContext} 的 {@code nodeById} 条目）。
 *
 * @author 走进新时代
 */
public interface ToolChangeExtractor {

    /**
     * 本抽取器负责的工具键，必须与 {@code @AgentTool(name = ...)} 逐字一致
     *
     * <p>🔴 一个抽取器只负责一个工具，不做「多键共用一个实现」：
     * {@code form_data_create/update/delete} 三者风险等级不同（LOW/MEDIUM/HIGH）、
     * 语义不同（新增/补丁式修改/逻辑删除），共用一个实现就得在里面按键分派 ——
     * 那是把注册表的分派职责又抄一份。三个键就写三个 bean，各自十几行。
     */
    String toolId();

    /**
     * 把一个工具节点的参数翻译成变更项
     *
     * @param node DSL 节点，type 必为 {@code tool}，非 null
     * @param ctx  整份 DSL 的公共物，非 null
     * @return 变更项，<b>不得为 null</b>（见类注释）
     */
    ChangeItem extract(NodeDTO node, ManifestContext ctx);

}
