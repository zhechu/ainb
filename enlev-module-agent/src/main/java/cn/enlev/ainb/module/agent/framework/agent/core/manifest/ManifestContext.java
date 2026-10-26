package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.GraphReachability;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;

import java.util.Map;

/**
 * 抽取器的工作上下文：一次清单构建里<b>只算一遍</b>的公共物
 *
 * <p>四个成员都是「按整份 DSL 才答得出」的问题，逐个抽取器各算一遍既浪费又会各算出不同答案：
 * <ul>
 *   <li>{@link #def()} —— 抽取器偶尔要看全局（如某工具的参数引用了别处声明的变量）</li>
 *   <li>{@link #graph()} —— 可达性。S2-b 之前全模块有两套语义不一致的遍历，
 *       收敛成 {@link GraphReachability} 之后，这里拿到的就是与编译器同口径的那一份。
 *       {@code FormUpdateChangeExtractor} 用它判「这条流程后面还有没有发布节点」</li>
 *   <li>{@link #nodeById()} —— 按 id 找节点，配合 {@link #graph()} 做跨节点扫描。
 *       ⚠️ 它<b>还做不到</b>「顺着 {@code {{$.save_draft.id}}} 反查是哪个节点产出的」——
 *       {@code ToolNodeExecutor#execute} 把节点结果写进 state 用的键是 {@code outputVar}
 *       （默认 {@code tool_result}）而不是节点 id，故 {@code $.save_draft} 未必对应 id 为
 *       {@code save_draft} 的节点。反查逻辑归 S2-e 的 {@code untrustedInputs} 统一做，
 *       在那之前抽取器一律<b>照字面显示占位符</b>（见 {@link ManifestArgs#isPending}）</li>
 *   <li>{@link #labels()} —— 机器码→中文名的查表。见 {@link ManifestLabels}：
 *       它自带懒加载，故放在上下文里既不浪费也保证「一次构建内最多各查一遍」</li>
 * </ul>
 *
 * @param def      整份 DSL，非 null
 * @param graph    边可达性图，非 null
 * @param nodeById 节点 id → 节点，非 null（不可变）
 * @param labels   机器码翻译表，非 null
 * @author 走进新时代
 */
public record ManifestContext(WorkflowDefDTO def, GraphReachability.Graph graph,
                              Map<String, NodeDTO> nodeById, ManifestLabels labels) {

    /**
     * 按 id 取节点，取不到返回 null（DSL 里的引用可能是坏的，那由校验器报错，清单不重复判一遍）
     */
    public NodeDTO node(String id) {
        return id == null ? null : nodeById.get(id);
    }

}
