package cn.enlev.ainb.module.agent.tool.impl.explain;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.module.agent.framework.agent.core.explain.ProcessNodeTreeExplainer;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.bpm.api.definition.BpmModelApi;
import cn.enlev.ainb.module.bpm.api.definition.dto.BpmModelRespDTO;
import cn.enlev.ainb.module.bpm.api.definition.dto.BpmProcessConstraintsRespDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_TOOL_PARAM_INVALID;

/**
 * 工具：解释流程走向
 *
 * <h3>为什么需要它</h3>
 * 流程配置是一棵嵌套的 JSON 树，节点里全是数字编码（{@code type=11}、
 * {@code candidateStrategy=37}、{@code approveMethod=4}）。
 * 想知道「这个流程到底谁审批、什么条件走哪个分支」，
 * 要么在设计器里一个节点一个节点点开看，要么去读那棵 JSON。
 * 两条路都很费劲，而这恰恰是用户最常问的问题。
 *
 * <p>这个工具把那棵树翻译成一句一句的中文。
 *
 * <h3>数字编码的翻译来自哪里</h3>
 * 一律取 {@code BpmModelApi.getProcessConstraints()} ——
 * 与校验器、prompt 同源。自己抄一份对照表的话，
 * 上游加一种候选人策略，这里就会把它解释成「未知」，
 * 而用户看到「未知」比看到数字更糟：数字至少能自己去查。
 *
 * <h3>翻译逻辑不在本类里</h3>
 * 树 → 中文那一段已经搬到 {@link ProcessNodeTreeExplainer}，因为变更清单也要用同一套说法
 * （见那个类的类注释：抄第二份的代价是「同一条流程两处给出两种解释」）。
 * 本类剩下的职责只有一件：<b>做 I/O</b> —— 查流程、查节点树、查对照表，
 * 然后把查到的东西交给那个纯函数。搬动前后的输出逐字一致，由
 * {@code ProcessNodeTreeExplainerTest} 的黄金快照钉住。
 *
 * @author 走进新时代
 */
@Component
@AgentTool(
        name = "explain_process",
        displayName = "解读流程走向",
        groupName = "诊断排错",
        description = "用中文解释一条流程的走向：有哪些节点、每个节点谁审批、"
                + "怎么审批（一人通过还是会签）、什么条件走哪个分支。"
                + "用户问「这个流程是怎么走的」「谁能审批」「什么情况下会走到总经理」时用它",
        permission = "bpm:model:query",
        risk = RiskLevel.READ_ONLY)
public class ExplainProcessTool implements AgentToolFunction {

    public static final String PARAM_TARGET = "target";

    @Resource
    private BpmModelApi bpmModelApi;

    @Override
    public Object execute(Map<String, Object> params) {
        Object target = params.get(PARAM_TARGET);
        if (target == null || StrUtil.isBlank(target.toString())) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "explain_process",
                    "缺少 target（流程标识 key 或流程名称）");
        }
        String key = target.toString().trim();
        BpmModelRespDTO model = bpmModelApi.getModelList(null).stream()
                .filter(item -> Objects.equals(item.getKey(), key)
                        || Objects.equals(item.getName(), key))
                .findFirst().orElse(null);
        if (model == null) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "explain_process",
                    "找不到流程「" + key + "」，先用 process_list 确认准确的标识");
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("key", model.getKey());
        resp.put("name", model.getName());
        resp.put("deployed", model.getDeployed());
        if (!Boolean.TRUE.equals(model.getDeployed())) {
            resp.put("warning", "这个流程还没有部署过，现在发起不了，也不出现在用户的发起列表里");
        }

        String simpleJson = bpmModelApi.getModelSimpleJson(model.getId());
        if (StrUtil.isBlank(simpleJson)) {
            resp.put("explanation", "这个流程没有仿钉钉设计器的节点树 —— "
                    + "它可能是用 BPMN 设计器画的，本工具解释不了，请在设计器里查看");
            return resp;
        }

        BpmProcessConstraintsRespDTO constraints = bpmModelApi.getProcessConstraints();
        List<String> lines = ProcessNodeTreeExplainer.explain(JSONUtil.parseObj(simpleJson), constraints);
        resp.put("explanation", String.join("\n", lines));
        resp.put("nodeCount", lines.size());
        return resp;
    }

    @Override
    public String paramsSchema() {
        return "target（必填，字符串）：流程标识 key 或流程名称，可从 process_list 拿";
    }

}
