package cn.enlev.ainb.module.agent.framework.agent.core.validate;

import cn.enlev.ainb.module.agent.tool.impl.formdata.FormDataParamResolver;
import cn.enlev.ainb.module.agent.tool.impl.formdata.FormDataUpdateTool;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@code form_data_update} 的 plan 期参数预检
 *
 * <p>结构性预检：code 非空、pk 非空、data 非空。字段级深校验交给 infra 运行时。
 * 注意本工具是 <b>PATCH 语义</b>（读-合-写），data 只需含要改的字段，故这里只校验「非空」，
 * 不校验「是否覆盖全部字段」—— 恰恰相反，只改一个字段才是正确用法。
 *
 * @author 走进新时代
 */
@Component
public class FormDataUpdateParamValidator implements AgentToolParamValidator {

    @Override
    public boolean supports(String toolName) {
        return FormDataUpdateTool.NAME.equals(toolName);
    }

    @Override
    public AgentValidateResult validate(Map<String, Object> params) {
        AgentValidateResult result = new AgentValidateResult();
        if (!FormDataParamResolver.hasText(params, FormDataParamResolver.PARAM_CODE)) {
            result.addError("缺少 code（表单编码）。先用 form_list 查到准确的编码，不要猜");
        }
        if (!FormDataParamResolver.hasPk(params)) {
            result.addError("缺少 pk（要更新的记录主键，即该行的 id）。"
                    + "先用 form_data_page 查到目标记录的 id，不要猜");
        }
        if (!FormDataParamResolver.hasNonEmptyData(params)) {
            result.addError("缺少 data（字段名 → 新值 的对象），或 data 为空。"
                    + "只放要改的字段即可（PATCH 语义，没给的字段保持原值）");
        }
        return result;
    }

}
