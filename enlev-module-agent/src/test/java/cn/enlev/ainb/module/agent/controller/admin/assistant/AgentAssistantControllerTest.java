package cn.enlev.ainb.module.agent.controller.admin.assistant;

import cn.enlev.ainb.module.agent.controller.admin.assistant.vo.AgentMessageRespVO;
import cn.enlev.ainb.module.agent.dal.dataobject.conversation.AgentMessageDO;
import cn.enlev.ainb.module.agent.framework.agent.core.chat.AgentAnswerStreamSplitter.Suggestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentAssistantController#toMessageVO} 的单元测试
 *
 * <p>守一个曾经真实发生、且极难定位的缺陷：{@code suggestions} 在 DO 里是 JSON 字符串、
 * 在 VO 里是 {@code List<Suggestion>}（record），两者<b>同名</b>。早先用
 * {@code BeanUtils.toBean} 整体拷贝时，Hutool 会尝试把字符串直接转成 record 列表并抛
 * {@code ConvertException}，导致<b>凡是带对话后建议的历史会话，message/list 一律 500</b>，
 * 而没有建议的会话却正常 —— 表现为「历史记录点不开」却查不到原因。
 *
 * <p>这里直接对拍映射结果，确保：带建议能正确解析、不带建议不报错、record 的字段都对得上。
 *
 * @author 走进新时代
 */
public class AgentAssistantControllerTest {

    @Test
    @DisplayName("带对话后建议的助手消息：能正确解析成 record 列表，不再 500")
    public void testToMessageVO_withSuggestions() {
        AgentMessageDO message = new AgentMessageDO();
        message.setId(1024L);
        message.setConversationId(2048L);
        message.setRole(AgentMessageDO.ROLE_ASSISTANT);
        message.setContent("主子表配置分三步……");
        message.setStatus("SUCCESS");
        // 一条带 tool、一条不带（tool 为 null 时序列化会省略该字段）
        message.setSuggestions(
                "[{\"text\":\"怎么排查子表的联动\",\"tool\":\"form_design\"},{\"text\":\"字段权限默认是什么效果\"}]");

        AgentMessageRespVO vo = AgentAssistantController.toMessageVO(message);

        assertEquals(1024L, vo.getId());
        assertEquals(2048L, vo.getConversationId());
        assertEquals("主子表配置分三步……", vo.getContent());
        List<Suggestion> suggestions = vo.getSuggestions();
        assertEquals(2, suggestions.size());
        assertEquals("怎么排查子表的联动", suggestions.get(0).text());
        assertEquals("form_design", suggestions.get(0).tool());
        assertEquals("字段权限默认是什么效果", suggestions.get(1).text());
        assertNull(suggestions.get(1).tool());
    }

    @Test
    @DisplayName("没有建议 / 建议为空：返回空列表而不是 null，也不报错")
    public void testToMessageVO_blankSuggestions() {
        AgentMessageDO nullCase = new AgentMessageDO();
        nullCase.setRole(AgentMessageDO.ROLE_USER);
        nullCase.setContent("为什么我配的联动不生效");
        nullCase.setSuggestions(null);
        assertTrue(AgentAssistantController.toMessageVO(nullCase).getSuggestions().isEmpty());

        AgentMessageDO emptyCase = new AgentMessageDO();
        emptyCase.setRole(AgentMessageDO.ROLE_ASSISTANT);
        emptyCase.setSuggestions("");
        assertTrue(AgentAssistantController.toMessageVO(emptyCase).getSuggestions().isEmpty());
    }

    @Test
    @DisplayName("建议 JSON 损坏：降级成空列表，绝不把整个消息列表接口拖垮")
    public void testToMessageVO_malformedSuggestions() {
        AgentMessageDO message = new AgentMessageDO();
        message.setRole(AgentMessageDO.ROLE_ASSISTANT);
        message.setContent("正文");
        message.setSuggestions("[{\"text\":");  // 半截 JSON

        AgentMessageRespVO vo = AgentAssistantController.toMessageVO(message);
        assertEquals("正文", vo.getContent());
        assertTrue(vo.getSuggestions().isEmpty());
    }
}
