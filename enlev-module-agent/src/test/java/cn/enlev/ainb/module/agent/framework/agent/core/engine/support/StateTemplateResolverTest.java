package cn.enlev.ainb.module.agent.framework.agent.core.engine.support;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.WorkflowContext;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StateTemplateResolver} 单测
 *
 * 约束：{{var}} 顶层键替换；缺失键替成空串（而不是原样留着让模型当正文）；
 * 变量值里的 $ / \ 不能被当成正则反向引用（quoteReplacement）。
 *
 * @author 走进新时代
 */
class StateTemplateResolverTest {

    private static WorkflowContext ctx(Map<String, Object> data) {
        OverAllState state = new OverAllState(new HashMap<>(data));
        return new WorkflowContext(state, 1L, "t1:1", AgentExecutionContext.ofForTest(1L, 1L));
    }

    @Test
    void resolve_replacesTopLevelKeys() {
        WorkflowContext ctx = ctx(Map.of("input", "建模", "name", "张三"));
        assertEquals("请处理 建模 ，负责人 张三",
                StateTemplateResolver.resolve("请处理 {{input}} ，负责人 {{name}}", ctx));
    }

    @Test
    void resolve_missingKey_becomesEmpty() {
        WorkflowContext ctx = ctx(Map.of("input", "x"));
        assertEquals("[x]", StateTemplateResolver.resolve("[{{input}}{{gone}}]", ctx));
    }

    @Test
    void resolve_noPlaceholder_unchanged() {
        WorkflowContext ctx = ctx(Map.of());
        assertEquals("纯文本 no braces", StateTemplateResolver.resolve("纯文本 no braces", ctx));
    }

    @Test
    void resolve_toleratesInnerWhitespace() {
        WorkflowContext ctx = ctx(Map.of("input", "v"));
        assertEquals("v", StateTemplateResolver.resolve("{{  input  }}", ctx));
    }

    @Test
    void resolve_valueWithDollarAndBackslash_notTreatedAsGroupRef() {
        Map<String, Object> data = new HashMap<>();
        data.put("money", "$100\\x");
        WorkflowContext ctx = ctx(data);
        // 若漏了 quoteReplacement，$1 会被当成反向引用抛异常或替错
        assertEquals("价:$100\\x", StateTemplateResolver.resolve("价:{{money}}", ctx));
    }

    @Test
    void resolve_nullOrEmpty_returnsAsIs() {
        WorkflowContext ctx = ctx(Map.of());
        assertNull(StateTemplateResolver.resolve(null, ctx));
        assertEquals("", StateTemplateResolver.resolve("", ctx));
    }

    // ========== P2 增量1：JSONPath-lite 嵌套引用 ==========

    @Test
    void resolve_nestedPath() {
        WorkflowContext ctx = ctx(Map.of(
                "contract", Map.of("partyA", "甲方公司", "amount", 1000)));
        assertEquals("甲方:甲方公司", StateTemplateResolver.resolve("甲方:{{$.contract.partyA}}", ctx));
        assertEquals("金额:1000", StateTemplateResolver.resolve("金额:{{contract.amount}}", ctx));
    }

    @Test
    void resolve_arrayNegativeIndex() {
        WorkflowContext ctx = ctx(Map.of("messages", java.util.List.of("m0", "m1", "m2")));
        assertEquals("最后一条:m2", StateTemplateResolver.resolve("最后一条:{{$.messages[-1]}}", ctx));
    }

    @Test
    void resolve_missingPath_becomesEmpty() {
        WorkflowContext ctx = ctx(Map.of("contract", Map.of("partyA", "甲方")));
        // 缺失路径替成空串，与缺失顶层键口径一致（不留 {{...}} 让模型当正文）
        assertEquals("[]", StateTemplateResolver.resolve("[{{$.contract.gone}}]", ctx));
        assertEquals("[]", StateTemplateResolver.resolve("[{{$.nope.deep}}]", ctx));
    }

    @Test
    void resolve_mixedTopLevelAndPath() {
        WorkflowContext ctx = ctx(Map.of(
                "input", "验收",
                "contract", Map.of("subject", "软件开发")));
        assertEquals("请就 软件开发 做 验收",
                StateTemplateResolver.resolve("请就 {{$.contract.subject}} 做 {{input}}", ctx));
    }

    @Test
    void resolve_collectionValue_becomesJsonString() {
        WorkflowContext ctx = ctx(Map.of("retrieved", java.util.List.of("段A", "段B")));
        String out = StateTemplateResolver.resolve("{{retrieved}}", ctx);
        // 对象/数组落 JSON 串而非 Java toString，便于整块塞进 prompt
        assertTrue(out.startsWith("[") && out.contains("段A") && out.contains("段B"),
                "集合应序列化为 JSON 串，实际:" + out);
    }

}
