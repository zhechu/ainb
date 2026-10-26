package cn.enlev.ainb.module.agent.framework.agent.core.validate;

import cn.enlev.ainb.module.agent.tool.impl.formdata.FormDataDeleteTool;
import cn.enlev.ainb.module.agent.tool.impl.formdata.FormDataParamResolver;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@code form_data_delete} 的 plan 期参数预检
 *
 * <p>结构性预检：code 非空、pk 非空。删除不需要 data。
 *
 * <h3>🔴 为什么 form_data_* 是「一工具一校验器」而非合并成一个</h3>
 * {@link AgentToolParamValidator#validate(Map)} 的入参<b>只有 params、没有 toolName</b>
 * （toolName 只在 {@link #supports(String)} 里出现，且校验器是单例 bean，
 * 把 toolName 暂存到字段里会在并发规划时串号）。因此单个校验器<b>无法安全地</b>
 * 按工具区分必填项：create 要 data 不要 pk、delete 要 pk 不要 data、update 两者都要。
 * 若用「params 里有 pk 就当 delete、有 data 就当 create/update」去反推工具，
 * 会在「create 误带了 pk 却漏了 data」时<b>误判为 delete 而放行</b> —— 恰恰漏掉了最该拦的错。
 * 所以拆成三个，各自 {@code supports} 一个工具，与既有 {@code DataModelCreateParamValidator} 同构。
 *
 * @author 走进新时代
 */
@Component
public class FormDataDeleteParamValidator implements AgentToolParamValidator {

    @Override
    public boolean supports(String toolName) {
        return FormDataDeleteTool.NAME.equals(toolName);
    }

    @Override
    public AgentValidateResult validate(Map<String, Object> params) {
        AgentValidateResult result = new AgentValidateResult();
        if (!FormDataParamResolver.hasText(params, FormDataParamResolver.PARAM_CODE)) {
            result.addError("缺少 code（表单编码）。先用 form_list 查到准确的编码，不要猜");
        }
        if (!FormDataParamResolver.hasPk(params)) {
            result.addError("缺少 pk（要删除的记录主键，即该行的 id）。"
                    + "先用 form_data_page 查到目标记录的 id，不要猜");
        }
        return result;
    }

}
