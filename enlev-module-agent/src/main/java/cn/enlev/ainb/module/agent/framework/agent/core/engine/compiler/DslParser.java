package cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DSL_INVALID;

/**
 * DSL JSON 字符串 → {@link WorkflowDefDTO}
 *
 * <p>解析失败一律转成 {@code WORKFLOW_DSL_INVALID} 业务异常，带上 Jackson 的原始原因 ——
 * 编排者要能看到「哪一行 JSON 格式错了」，而不是一个笼统的「系统异常」。
 *
 * @author 走进新时代
 */
@Component
public class DslParser {

    @Resource
    private ObjectMapper objectMapper;

    public WorkflowDefDTO parse(String dslJson) {
        if (dslJson == null || dslJson.isBlank()) {
            throw exception(WORKFLOW_DSL_INVALID, "DSL 内容为空");
        }
        try {
            return objectMapper.readValue(dslJson, WorkflowDefDTO.class);
        } catch (Exception ex) {
            throw exception(WORKFLOW_DSL_INVALID, "JSON 解析失败：" + ex.getMessage());
        }
    }

    /**
     * DSL JSON 字符串 → Jackson <b>原始树</b>（不建模），供「必须原样进出」的调用方用
     *
     * <p>🔴 与 {@link #parse} 并存不是冗余，两者语义相反：{@code WorkflowDefDTO} / {@code NodeDTO}
     * 都挂着 {@code @JsonIgnoreProperties(ignoreUnknown = true)}（那是<b>刻意的</b>——DSL 会随版本演进新增字段，
     * 旧引擎读到新 DSL 应当忽略不认识的字段继续跑），代价是<b>反序列化再序列化会静默丢掉所有未建模字段</b>。
     * 对执行无所谓（引擎本来就只读它认识的），对「复制一份 DSL」是致命的：复制的承诺是
     * 「与源一致，除了我告诉你改了的那些」，悄悄少几个字段正是本平台头号缺陷的形状。
     * 故 {@code WorkflowCopyRewriter} 走这一支。
     *
     * <p>解析失败的错误码与文案与 {@link #parse} <b>同源</b>（都在本类）：调用方不该因为走了哪一支
     * 而看到两种「DSL 解析不了」的说法。
     *
     * @return 顶层对象节点（就地改完 {@code toString()} 即可吐回原文）
     * @throws cn.enlev.ainb.framework.common.exception.ServiceException 空 / 非法 JSON / 顶层不是对象
     *         → {@code WORKFLOW_DSL_INVALID}
     */
    public ObjectNode parseTree(String dslJson) {
        if (dslJson == null || dslJson.isBlank()) {
            throw exception(WORKFLOW_DSL_INVALID, "DSL 内容为空");
        }
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(dslJson);
        } catch (Exception ex) {
            throw exception(WORKFLOW_DSL_INVALID, "JSON 解析失败：" + ex.getMessage());
        }
        if (!(parsed instanceof ObjectNode root)) {
            throw exception(WORKFLOW_DSL_INVALID, "DSL 顶层必须是 JSON 对象");
        }
        return root;
    }

}
