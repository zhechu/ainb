package cn.enlev.ainb.module.agent.framework.agent.core.validate;

import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.module.agent.tool.impl.datamodel.DataModelParamResolver;
import cn.enlev.ainb.module.infra.api.datamodel.dto.DataModelSaveReqDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@code datamodel_create} 的参数校验
 *
 * 把 {@link DataModelCreateValidator} 接到规划阶段：LLM 刚产出建表参数就校验，
 * 不通过直接回灌让它改，改好了才进 Plan。
 *
 * @author 走进新时代
 */
@Component
public class DataModelCreateParamValidator implements AgentToolParamValidator {

    @Resource
    private DataModelCreateValidator validator;

    @Override
    public boolean supports(String toolName) {
        return "datamodel_create".equals(toolName);
    }

    @Override
    public AgentValidateResult validate(Map<String, Object> params) {
        AgentValidateResult result = new AgentValidateResult();
        Object model = params == null ? null : params.get(DataModelParamResolver.PARAM_MODEL);
        if (model == null) {
            result.addError("缺少 model（建表定义）");
            return result;
        }
        DataModelSaveReqDTO reqDTO;
        try {
            reqDTO = JSONUtil.toBean(JSONUtil.parseObj(model), DataModelSaveReqDTO.class);
        } catch (Exception ex) {
            result.addError("model 不是合法的建表定义：%s", ex.getMessage());
            return result;
        }
        return validator.validate(reqDTO);
    }

}
