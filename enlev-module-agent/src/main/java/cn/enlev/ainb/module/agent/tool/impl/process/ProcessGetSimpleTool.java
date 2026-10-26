package cn.enlev.ainb.module.agent.tool.impl.process;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.bpm.api.definition.BpmModelApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_TOOL_PARAM_INVALID;

/**
 * 工具：取某个流程的节点树
 *
 * <p>「改流程」这条路径的第一步。因为没有独立的改流程工具
 *（普通账号改不了未部署的模型，见 {@code BpmModelApi} 的说明），
 * 改流程实际是「取回节点树 → 改 → 用同一个 key 重新创建部署」。
 *
 * @author 走进新时代
 */
@Component
@AgentTool(
        name = "process_get_simple",
        displayName = "查询流程节点树",
        groupName = "流程审批",
        description = "取某个流程的节点树（JSON）。要修改已有流程时，先用它取回完整的树，"
                + "在树上做局部修改，再用同一个 key 调 process_create_and_deploy 重新部署。"
                + "不要凭记忆重写整棵树 —— 漏掉的节点就是被删掉的节点",
        permission = "bpm:model:query",
        risk = RiskLevel.READ_ONLY)
public class ProcessGetSimpleTool implements AgentToolFunction {

    public static final String PARAM_ID = "id";

    @Resource
    private BpmModelApi bpmModelApi;

    @Override
    public Object execute(Map<String, Object> params) {
        Object id = params.get(PARAM_ID);
        if (id == null || StrUtil.isBlank(id.toString())) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "process_get_simple",
                    "缺少 id（流程模型编号，从 process_list 拿）");
        }
        String json = bpmModelApi.getModelSimpleJson(id.toString().trim());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", id);
        if (StrUtil.isBlank(json)) {
            resp.put("simpleModel", null);
            resp.put("message", "这个流程没有节点树 —— 它可能不存在，"
                    + "或者是用 BPMN 设计器画的（那种只有 XML，不支持用这套工具改）");
            return resp;
        }
        resp.put("simpleModel", json);
        return resp;
    }

    @Override
    public String paramsSchema() {
        return "id（必填，字符串）：流程模型编号，从 process_list 的结果里取";
    }

}
