package cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DSL_INVALID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DslParser} + {@link NodeDTO} config 取值器单测
 *
 * 重点不在「能不能解析 JSON」，而在 NodeDTO 那组 configXxx 取值器的类型转换与缺省语义 ——
 * 它们是各 NodeExecutor 读配置的唯一入口，转换错了就是「配了不生效」的根源。
 *
 * @author 走进新时代
 */
class DslParserTest {

    private DslParser parser;

    @BeforeEach
    void setUp() {
        parser = new DslParser();
        ReflectionTestUtils.setField(parser, "objectMapper", new ObjectMapper());
    }

    @Test
    void parse_fullDsl_mapsStructure() {
        String json = """
                {"dslVersion":"1.0","id":"wf_demo","name":"示例",
                 "variables":[{"key":"input","type":"string","merge":"replace","required":true}],
                 "nodes":[{"id":"n1","type":"llm","name":"起草","config":{"prompt":"hi"}}],
                 "edges":[{"from":"START","to":"n1"},{"from":"n1","type":"conditional",
                            "cases":{"a":"n2"},"default":"END"}],
                 "settings":{"recovery":"forward","executionTimeoutSeconds":300}}
                """;

        WorkflowDefDTO def = parser.parse(json);

        assertEquals("1.0", def.getDslVersion());
        assertEquals("wf_demo", def.getId());
        assertEquals(1, def.getVariables().size());
        assertEquals("input", def.getVariables().get(0).getKey());
        assertEquals(1, def.getNodes().size());
        assertEquals("llm", def.getNodes().get(0).getType());
        assertEquals(2, def.getEdges().size());
        assertTrue(def.getEdges().get(1).isConditional());
        assertEquals("END", def.getEdges().get(1).getDefaultTarget());
        assertEquals(300, def.getSettings().getExecutionTimeoutSeconds().intValue());
    }

    @Test
    void parse_blank_throwsDslInvalid() {
        ServiceException ex = assertThrows(ServiceException.class, () -> parser.parse("  "));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void parse_malformedJson_throwsDslInvalid() {
        ServiceException ex = assertThrows(ServiceException.class, () -> parser.parse("{not json"));
        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
    }

    @Test
    void nodeConfigAccessors_convertAndDefault() {
        NodeDTO node = new NodeDTO();
        node.setConfig(Map.of(
                "str", "hello",
                "intNum", 42,
                "intStr", "7",
                "boolTrue", true,
                "boolStr", "false",
                "dbl", "1.5",
                "lng", 9999999999L,
                "list", List.of("a", "b"),
                "map", Map.of("k", "v")));

        assertEquals("hello", node.configStr("str"));
        assertEquals("def", node.configStr("missing", "def"));
        assertEquals(42, node.configInt("intNum", 0).intValue());
        assertEquals(7, node.configInt("intStr", 0).intValue());
        assertEquals(-1, node.configInt("missing", -1).intValue());
        assertTrue(node.configBool("boolTrue", false));
        // 字符串 "false" 要解析成 false，而不是因为非空就判 true
        assertFalse(node.configBool("boolStr", true));
        assertTrue(node.configBool("missing", true));
        assertEquals(1.5, node.configDouble("dbl"), 1e-9);
        assertEquals(9999999999L, node.configLong("lng").longValue());
        assertEquals(List.of("a", "b"), node.configList("list"));
        assertEquals("v", node.configMap("map").get("k"));
        // 缺失的 list/map 返回空集合/null，不抛
        assertEquals(List.of(), node.configList("missing"));
        assertNull(node.configMap("missing"));
    }

}
