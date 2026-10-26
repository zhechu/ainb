package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.EdgeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.GraphReachability;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 抽取器单测共用的夹具：造节点、造上下文
 *
 * <h3>为什么抽取器测试要造一整份 DSL，而不是直接 new 一个 {@code NodeDTO}</h3>
 * 因为 {@link ManifestContext} 的四个成员里有两个是「按整份 DSL 才答得出」的：
 * {@code graph}（可达性）与 {@code nodeById}。{@code FormUpdateChangeExtractor} 要顺着
 * 可达性看「后面还有没有发布节点」，给它一个 null 图就等于没测到那条最重要的分支。
 *
 * <p>上下文一律用<b>与 builder 相同的构造方式</b>（{@code GraphReachability.of} +
 * 按 id 建索引 + {@code new ManifestLabels(api)}），这样测出来的行为与线上跑的一致；
 * 各测各造的话，夹具与真实装配一旦漂移，测试绿着而线上坏了。
 *
 * <p>⚠️ 测试夹具，包级可见。
 *
 * @author 走进新时代
 */
final class ManifestTestFixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 保留端点名。⚠️ 全模块只有 {@code WorkflowDslValidator} 里两个 private 常量与
     * {@code DslToStateGraphCompiler} 的字面量，没有公共出口；{@link GraphReachability}
     * 刻意把它们当普通字符串（它只关心连通性，不关心语义）。夹具这里同样用字面量，
     * 与 builder 测试里手写的 DSL 一致。
     */
    private static final String START = "START";
    private static final String END = "END";

    private ManifestTestFixtures() {
    }

    /**
     * 一个 {@code tool} 节点
     *
     * @param id      节点 id
     * @param toolId  工具键
     * @param argsJson {@code config.args} 的 JSON 原文；传 null 表示这个节点<b>没有</b> args 键
     *                 （与「args 是空对象」是两种不同的退化形态，都值得测）
     */
    static NodeDTO toolNode(String id, String toolId, String argsJson) {
        String config = argsJson == null
                ? "{\"toolId\":\"" + toolId + "\"}"
                : "{\"toolId\":\"" + toolId + "\",\"args\":" + argsJson + "}";
        return read("{\"id\":\"" + id + "\",\"type\":\"tool\",\"name\":\"" + id + "\","
                + "\"config\":" + config + "}", NodeDTO.class);
    }

    /**
     * 同上，但 {@code args} 直接给 Map
     *
     * <p>嵌套 JSON 用字符串写要转义到看不懂（尤其「model 被序列化成一个 JSON 串」那种用例），
     * 给 Map 让 Jackson 去序列化，测试里只剩业务语义。
     * 传 null 同样表示「没有 args 这个键」。
     */
    static NodeDTO toolNode(String id, String toolId, Map<String, Object> args) {
        NodeDTO node = new NodeDTO();
        node.setId(id);
        node.setType("tool");
        node.setName(id);
        Map<String, Object> config = new HashMap<>();
        config.put("toolId", toolId);
        if (args != null) {
            config.put("args", args);
        }
        node.setConfig(config);
        return node;
    }

    /**
     * 一个 {@code hitl.*} 节点（{@code FormUpdateChangeExtractor} 的下游扫描要用它做对照）
     */
    static NodeDTO hitlNode(String id) {
        return read("{\"id\":\"" + id + "\",\"type\":\"hitl.confirm\",\"name\":\"" + id + "\"}",
                NodeDTO.class);
    }

    /**
     * 把这些节点串成 {@code START → n0 → n1 → … → END} 的一条链，配上与之匹配的上下文
     *
     * <p>{@code formDesignApi} 传 null 时，{@link ManifestLabels} 退化成「机器码原样显示」——
     * 那正是它拿不到 api 时该有的行为，也是多数抽取器测试想要的（不关心控件中文名）。
     */
    static ManifestContext context(FormDesignApi formDesignApi, NodeDTO... nodes) {
        WorkflowDefDTO def = new WorkflowDefDTO();
        def.setDslVersion("1.0");
        def.setId("wf_fixture");
        def.setName("夹具流程");
        def.setNodes(List.of(nodes));

        List<EdgeDTO> edges = new ArrayList<>();
        String previous = START;
        for (NodeDTO node : nodes) {
            edges.add(edge(previous, node.getId()));
            previous = node.getId();
        }
        edges.add(edge(previous, END));
        def.setEdges(edges);

        Map<String, NodeDTO> nodeById = new HashMap<>();
        for (NodeDTO node : nodes) {
            nodeById.putIfAbsent(node.getId(), node);
        }
        return new ManifestContext(def, GraphReachability.of(def), Map.copyOf(nodeById),
                new ManifestLabels(formDesignApi));
    }

    static ManifestContext context(NodeDTO... nodes) {
        return context(null, nodes);
    }

    private static EdgeDTO edge(String from, String to) {
        EdgeDTO edge = new EdgeDTO();
        edge.setFrom(from);
        edge.setTo(to);
        return edge;
    }

    private static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception ex) {
            // 夹具自己写坏了要当场炸，不要让 NPE 冒充被测代码的缺陷
            throw new IllegalArgumentException("夹具 DSL 写坏了：" + json, ex);
        }
    }

}
