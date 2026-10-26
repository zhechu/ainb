package cn.enlev.ainb.module.agent.tool.impl.form;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDesignSaveReqDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_TOOL_PARAM_INVALID;

/**
 * 工具：查看一张表单的字段构成
 *
 * <h3>刻意只返回字段清单，不返回整份 schema</h3>
 * 一张表单的完整定义包含 form-create 的原始 rule（带 {@code _fc_id}、{@code name}、
 * {@code _fc_drag_tag} 这些内部字段）、联动规则、校验规则、打印模板、前端脚本……
 * 整份灌给模型既烧 token 又没用 —— 它需要的通常只是「这张表单上有哪些字段、
 * 分别是什么控件、映射到哪个列」。
 *
 * <p>要看规则用 {@code explain_form_rule}，要体检用 {@code form_inspect}，
 * 各司其职。真要改表单时，{@code form_update} 自己会取回全量。
 *
 * @author 走进新时代
 */
@Component
@AgentTool(
        name = "form_get",
        displayName = "查询表单字段清单",
        groupName = "表单设计",
        description = "查看一张表单有哪些字段：字段标识、中文标签、控件类型、映射到哪个物理列。"
                + "给表单加字段、配流程节点字段权限、或者要确认某个字段存不存在时用它。"
                + "看联动/校验规则请用 explain_form_rule",
        permission = "infra:form-design:query",
        risk = RiskLevel.READ_ONLY)
public class FormGetTool implements AgentToolFunction {

    public static final String PARAM_CODE = "code";

    @Resource
    private FormDesignApi formDesignApi;

    @Override
    public Object execute(Map<String, Object> params) {
        Object codeParam = params.get(PARAM_CODE);
        if (codeParam == null || StrUtil.isBlank(codeParam.toString())) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "form_get", "缺少 code（表单编码）");
        }
        String code = codeParam.toString().trim();
        FormDesignSaveReqDTO form = formDesignApi.getFormDesignByCode(code);
        if (form == null) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "form_get",
                    "找不到表单「" + code + "」。用 form_list 查一下准确的编码，不要猜");
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        // 🔴 id 必须给：form_update / form_publish_bundle 都只认 id。
        //    模型拿着 code 查到这里，如果看不到 id，就还得再绕回 form_list 去凑 ——
        //    多烧一轮，而轮次是有限的（agent_loop 与 plan-as-workflow 生成循环都有轮次上限）
        resp.put("id", form.getId());
        resp.put("code", form.getCode());
        resp.put("name", form.getName());
        resp.put("mainTable", form.getMainTable());
        resp.put("fields", parseFields(form));
        return resp;
    }

    /**
     * 从 form-create 的 rule 里抽出模型用得上的那几项，并补上列映射
     */
    private List<Map<String, Object>> parseFields(FormDesignSaveReqDTO form) {
        JSONObject fieldMapping = parseFieldMapping(form.getTableBinding());
        List<Map<String, Object>> fields = new ArrayList<>();
        if (form.getFormFields() == null) {
            return fields;
        }
        for (String raw : form.getFormFields()) {
            JSONObject rule = parseObject(raw);
            if (rule == null) {
                continue;
            }
            String field = rule.getStr("field");
            if (StrUtil.isBlank(field)) {
                // 布局类控件（行、卡片、页签）没有 field，它们不是数据字段
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("field", field);
            item.put("title", rule.getStr("title"));
            item.put("widget", rule.getStr("type"));
            item.put("required", rule.getBool("$required", false));
            JSONObject mapping = fieldMapping == null ? null : fieldMapping.getJSONObject(field);
            if (mapping != null) {
                item.put("column", mapping.getStr("column"));
                item.put("jdbcType", mapping.getStr("jdbcType"));
            } else {
                // 没有列映射的字段存不进库 —— 这是个典型的「配了不生效」，要说出来
                item.put("column", null);
                item.put("warning", "这个字段没有映射到物理列，填了也存不进去");
            }
            fields.add(item);
        }
        return fields;
    }

    private JSONObject parseFieldMapping(String tableBinding) {
        JSONObject binding = parseObject(tableBinding);
        return binding == null ? null : binding.getJSONObject("fieldMapping");
    }

    private JSONObject parseObject(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        try {
            return JSONUtil.parseObj(raw);
        } catch (Exception ex) {
            return null;
        }
    }

    /** 见 {@link AgentToolFunction#resultArrayKeys}：本工具全部返回分支里数组值的键（并集） */
    @Override
    public List<String> resultArrayKeys() {
        return List.of("fields");
    }

    @Override
    public String paramsSchema() {
        return "code（必填，字符串）：表单编码，从 form_list 拿";
    }

}
