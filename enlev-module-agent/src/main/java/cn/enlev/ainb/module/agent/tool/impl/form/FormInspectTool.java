package cn.enlev.ainb.module.agent.tool.impl.form;

import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_TOOL_PARAM_INVALID;

/**
 * 工具：表单体检
 *
 * <h3>🔴 这是 AI 自修正的天然反馈信号</h3>
 * 它与<b>发布走同一份校验</b>，却<b>不改任何数据</b>。
 * 这个组合很难得：意味着 AI 可以「先体检、按结果改、改好再发布」，
 * 而不是「先发布、失败了再改」—— 后者会在库里留下半成品，
 * 而表单的半成品状态（已发布有改动）本身就是个「配了但不生效」的入口。
 *
 * <p>所以这个工具在流程里的位置是固定的：<b>保存草稿之后、进 Plan 之前</b>。
 *
 * @author 走进新时代
 */
@Component
@AgentTool(
        name = "form_inspect",
        displayName = "表单发布体检",
        groupName = "表单设计",
        description = "对表单做体检，返回所有会导致发布失败或运行异常的问题。"
                + "与发布走同一份校验但不改任何数据，所以可以放心反复调用。"
                + "保存表单草稿之后、进入变更计划之前必须调它一次",
        permission = "infra:form-design:query",
        risk = RiskLevel.READ_ONLY)
public class FormInspectTool implements AgentToolFunction {

    public static final String PARAM_ID = "id";

    @Resource
    private FormDesignApi formDesignApi;

    @Override
    public Object execute(Map<String, Object> params) {
        Object id = params.get(PARAM_ID);
        if (id == null) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "form_inspect", "缺少 id（表单编号）");
        }
        long designId;
        try {
            designId = Long.parseLong(id.toString());
        } catch (NumberFormatException ex) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "form_inspect",
                    "id 必须是数字，实际收到：" + id);
        }

        List<String> problems = formDesignApi.inspectFormDesign(designId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", designId);
        result.put("passed", problems.isEmpty());
        result.put("problems", problems);
        // 明确说「通过」而不是只给一个空数组：空数组容易被读成「没查出来」，
        // 而这两件事对下一步动作的含义完全相反
        result.put("conclusion", problems.isEmpty()
                ? "体检通过，可以发布"
                : "发现 " + problems.size() + " 个问题，必须全部修复后才能发布");
        return result;
    }


    /** 见 {@link AgentToolFunction#resultArrayKeys}：本工具全部返回分支里数组值的键（并集） */
    @Override
    public List<String> resultArrayKeys() {
        return List.of("problems");
    }

    /**
     * 见 {@link AgentToolFunction#resultKeys}：本工具顶层键
     *
     * <p>{@code passed} 是布尔、{@code conclusion} 是给人看的一句话 ——
     * 要按体检结果分流，用 condition.rule 判 {@code passed}，别去解析 {@code conclusion} 的文本。
     */
    @Override
    public List<String> resultKeys() {
        return List.of("id", "passed", "problems", "conclusion");
    }

    @Override
    public String paramsSchema() {
        return "id（必填，数字）：表单编号";
    }

}
