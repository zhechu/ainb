package cn.enlev.ainb.module.agent.framework.agent.core.validate;

import cn.enlev.ainb.module.agent.tool.impl.datainterface.DataInterfaceInvokeTool;
import cn.enlev.ainb.module.agent.tool.impl.datainterface.DataInterfaceParamResolver;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@code data_interface_invoke} 的 plan 期参数预检
 *
 * <p>只做<b>结构性</b>预检（code 非空、params 若给了必须是合法对象形态），
 * 让用户在 Plan 卡片上看到的写步骤都是「能执行成功」的（见 {@link AgentToolParamValidator} 的纪律）。
 * <b>不做入参级深校验</b> —— 交给 infra 的 ParamValidator 执行时兜，避免与接口定义漂移。
 *
 * <p>权限（infra:data-interface:invoke）不在这里校验 —— 那是网关在执行期的职责，
 * plan 期拿不到也不该模拟执行身份。
 *
 * @author 走进新时代
 */
@Component
public class DataInterfaceInvokeParamValidator implements AgentToolParamValidator {

    @Override
    public boolean supports(String toolName) {
        return DataInterfaceInvokeTool.NAME.equals(toolName);
    }

    @Override
    public AgentValidateResult validate(Map<String, Object> params) {
        AgentValidateResult result = new AgentValidateResult();
        if (!DataInterfaceParamResolver.hasText(params, DataInterfaceParamResolver.PARAM_CODE)) {
            result.addError("缺少 code（数据接口编码）。先用 data_interface_list 查到准确的编码，不要猜");
        }
        if (!DataInterfaceParamResolver.hasValidParamsShape(params)) {
            result.addError("params 不是合法的入参对象（应为「参数名 → 值」的对象或其 JSON 串）；"
                    + "入参签名看 data_interface_list 返回的 params 定义");
        }
        return result;
    }

}
