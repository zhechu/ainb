package cn.enlev.ainb.module.agent.tool.impl.process;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.bpm.api.definition.BpmModelApi;
import cn.enlev.ainb.module.bpm.api.definition.dto.BpmModelRespDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具：查询流程列表
 *
 * <p>与 {@code datamodel_list} 同样的作用 —— 让模型在动手建之前先看看有没有现成的。
 * 流程比表更容易重复建：用户说「加个请假审批」，库里很可能已经有一个了。
 *
 * @author 走进新时代
 */
@Component
@AgentTool(
        name = "process_list",
        displayName = "查询流程模型列表",
        groupName = "流程审批",
        description = "查询已有的流程列表。要建审批流之前先用它看看有没有同类的流程 —— "
                + "改已有流程（用同一个 key 重新创建部署）通常比新建一条更合适",
        permission = "bpm:model:query",
        risk = RiskLevel.READ_ONLY)
public class ProcessListTool implements AgentToolFunction {

    public static final String PARAM_NAME = "name";

    @Resource
    private BpmModelApi bpmModelApi;

    @Override
    public Object execute(Map<String, Object> params) {
        Object name = params.get(PARAM_NAME);
        List<BpmModelRespDTO> models =
                bpmModelApi.getModelList(name == null ? null : StrUtil.trimToNull(name.toString()));

        List<Map<String, Object>> items = new ArrayList<>(models.size());
        for (BpmModelRespDTO model : models) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", model.getId());
            item.put("key", model.getKey());
            item.put("name", model.getName());
            item.put("deployed", model.getDeployed());
            item.put("formType", model.getFormType());
            if (StrUtil.isNotBlank(model.getFormDesignCode())) {
                item.put("formDesignCode", model.getFormDesignCode());
            }
            items.add(item);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", items.size());
        resp.put("items", items);
        resp.put("message", "deployed 为 false 的流程还没部署过，发起不了，也不出现在用户的发起列表里");
        return resp;
    }

    /** 见 {@link AgentToolFunction#resultArrayKeys}：本工具全部返回分支里数组值的键（并集） */
    @Override
    public List<String> resultArrayKeys() {
        return List.of("items");
    }

    @Override
    public String paramsSchema() {
        return "name（可选，字符串）：按流程名称模糊查询，不传则返回全部";
    }

}
