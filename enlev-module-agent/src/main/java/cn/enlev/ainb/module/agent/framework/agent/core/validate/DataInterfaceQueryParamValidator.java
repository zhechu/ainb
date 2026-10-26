package cn.enlev.ainb.module.agent.framework.agent.core.validate;

import cn.enlev.ainb.module.agent.tool.impl.datainterface.DataInterfaceParamResolver;
import cn.enlev.ainb.module.agent.tool.impl.datainterface.DataInterfaceQueryTool;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@code data_interface_query} 的 plan 期参数预检
 *
 * <p>只做<b>结构性</b>预检（code 非空、params 若给了必须是合法对象形态）。
 * <b>不做入参级深校验</b>（接口定义了哪些参数、哪些必填、类型是否匹配）——
 * 那些交给 infra 的 ParamValidator 执行时兜，在这里重复校验只会与接口定义漂移。
 *
 * <p>为什么一工具一校验器：{@link AgentToolParamValidator#validate(Map)} 拿不到 toolName，
 * 无法在一个校验器里安全地区分 query/invoke（详见 {@code FormDataDeleteParamValidator} 的同款说明）。
 *
 * @author 走进新时代
 */
@Component
public class DataInterfaceQueryParamValidator implements AgentToolParamValidator {

    @Override
    public boolean supports(String toolName) {
        return DataInterfaceQueryTool.NAME.equals(toolName);
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
