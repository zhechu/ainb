package cn.enlev.ainb.module.agent.framework.agent.core.validate;

import cn.enlev.ainb.module.agent.tool.impl.formdata.FormDataCreateTool;
import cn.enlev.ainb.module.agent.tool.impl.formdata.FormDataParamResolver;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@code form_data_create} 的 plan 期参数预检
 *
 * <p>只做<b>结构性</b>预检（code 非空、data 非空），让用户在 Plan 卡片上看到的写步骤
 * 都是「能执行成功」的（见 {@link AgentToolParamValidator} 的纪律）。
 * <b>不做字段级深校验</b>（required/unique/type）—— 那些交给 infra 运行时执行时兜，
 * 在这里重复校验只会与 infra 漂移。
 *
 * <p>为什么一工具一校验器：{@link AgentToolParamValidator#validate(Map)} 拿不到 toolName，
 * 单个校验器无法安全地区分 create/update/delete 各自的必填项（详见 {@code FormDataDeleteParamValidator}）。
 *
 * @author 走进新时代
 */
@Component
public class FormDataCreateParamValidator implements AgentToolParamValidator {

    @Override
    public boolean supports(String toolName) {
        return FormDataCreateTool.NAME.equals(toolName);
    }

    @Override
    public AgentValidateResult validate(Map<String, Object> params) {
        AgentValidateResult result = new AgentValidateResult();
        if (!FormDataParamResolver.hasText(params, FormDataParamResolver.PARAM_CODE)) {
            result.addError("缺少 code（表单编码）。先用 form_list 查到准确的编码，不要猜");
        }
        if (!FormDataParamResolver.hasNonEmptyData(params)) {
            result.addError("缺少 data（字段名 → 值 的对象），或 data 为空。新增记录至少要给一个字段；"
                    + "字段名用表单里的 field，不确定就先 form_get 看结构");
        }
        return result;
    }

}
