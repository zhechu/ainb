package cn.enlev.ainb.module.agent.tool.impl.formdata;

import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具：删除某张表单里<b>一条已提交的记录</b>
 *
 * <h3>🔴 操作的是「数据行」，不是「表单定义」</h3>
 * 见 {@link FormDataGetTool} 的同名说明。删的是一条业务数据，不是删表单。
 *
 * <h3>🔴 两层权限</h3>
 * 第 2 层动态门 {@code infra:form-design-run:{code}:delete}。
 *
 * <h3>逻辑删除，但仍列为 HIGH</h3>
 * infra 运行时走的是逻辑删除（{@code deleted=1}，数据仍在库里、可恢复），并非物理抹除。
 * 即便如此，删除对最终用户是「记录不见了」的高影响动作，风险等级仍定 {@link RiskLevel#HIGH}，
 * 必须进 Plan 等用户确认 —— 铁律是「用户确认前不执行任何写操作」，可逆与否只影响标注、不影响是否确认。
 *
 * @author 走进新时代
 */
@Component
@AgentTool(
        name = FormDataDeleteTool.NAME,
        displayName = "删除表单数据",
        groupName = "表单数据",
        description = "删除某张表单里的一条已提交记录（逻辑删除，数据仍在库里、标记为已删除，可恢复）。"
                + "⚠️ 操作的是表单提交进来的数据行，不是表单定义 —— 删表单结构/字段请用 form_update。"
                + "按主键 pk 定位要删的记录；这是高风险写操作，会进 Plan 等用户确认",
        permission = "agent:assistant:use",
        risk = RiskLevel.HIGH)
@Slf4j
public class FormDataDeleteTool implements AgentToolFunction {

    public static final String NAME = "form_data_delete";

    @Resource
    private FormDesignApi formDesignApi;
    @Resource
    private FormDataPermissionChecker permissionChecker;
    @Resource
    private FormDataParamResolver resolver;

    @Override
    public Object execute(Map<String, Object> params) {
        String code = resolver.resolveCode(params, NAME);
        String pk = resolver.resolvePk(params, NAME);
        // 🔴 第 2 层动态数据权限门，必须在调 api 之前
        permissionChecker.check(code, FormDataPermissionChecker.ACTION_DELETE);

        formDesignApi.deleteData(code, pk);
        log.info("[execute][AI 删除表单({}) 记录({})]", code, pk);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", pk);
        resp.put("message", "已删除表单 " + code + " 中的记录 " + pk + "（逻辑删除）");
        return resp;
    }

    @Override
    public String paramsSchema() {
        return FormDataParamResolver.DELETE_SCHEMA;
    }

    /**
     * 见 {@link AgentToolFunction#resultArrayKeys}：结果是 {@code {id,message}}，两个键都不是数组
     *
     * <p>🔴 显式写出来而不是吃默认值：默认的空列表会被渲染成「压根不回数组，别对它用 pickRows」
     * —— 那是一句<b>断言</b>。吃默认值等于让一句没人核过的话进提示词。
     */
    @Override
    public List<String> resultArrayKeys() {
        return List.of();
    }

}
