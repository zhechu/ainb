package cn.enlev.ainb.module.agent.tool.impl.formdata;

import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具：读取某张表单里<b>一条已提交的记录</b>
 *
 * <h3>🔴 操作的是「数据行」，不是「表单定义」</h3>
 * 与 {@code form_get}（看表单有哪些字段/结构）、{@code form_update}（改表单定义）严格区分：
 * 本工具动的是用户在这张表单里<b>提交进来的业务数据</b>。名字相近但语义完全不同，
 * 选错工具的后果是「本想读一条记录，结果把整张表单的配置改了」。description 开头已点明。
 *
 * <h3>🔴 两层权限</h3>
 * 第 1 层静态门 {@code agent:assistant:use}（网关校验）；第 2 层动态门
 * {@code infra:form-design-run:{code}:query}（{@code execute} 首行由
 * {@link FormDataPermissionChecker} 校验）—— 因为 {@code FormDesignApi} 自己<b>不做任何权限校验</b>，
 * 不在这里补上就是越权读任意表单的数据。
 *
 * @author 走进新时代
 */
@Component
@AgentTool(
        name = FormDataGetTool.NAME,
        displayName = "查询单条表单数据",
        groupName = "表单数据",
        description = "读取某张表单里「一条已提交的记录」的完整内容（主表字段 + 子表明细），按主键 pk 精确取一行。"
                + "⚠️ 操作的是表单提交进来的数据行，不是表单定义 —— 看表单有哪些字段用 form_get，改表单结构用 form_update。"
                + "要按条件查多行用 form_data_page",
        permission = "agent:assistant:use",
        risk = RiskLevel.READ_ONLY)
public class FormDataGetTool implements AgentToolFunction {

    public static final String NAME = "form_data_get";

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
        permissionChecker.check(code, FormDataPermissionChecker.ACTION_QUERY);

        // getData 对不存在的行抛 FORM_DESIGN_RUNTIME_DATA_NOT_EXISTS（中文可读），
        // 原样冒泡给模型自修正 —— 不要在这里包一层，那会丢掉 infra 的具体信息
        Map<String, Object> data = formDesignApi.getData(code, pk);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("code", code);
        resp.put("id", pk);
        resp.put("data", data);
        return resp;
    }

    @Override
    public String paramsSchema() {
        return FormDataParamResolver.GET_SCHEMA;
    }

    /**
     * 见 {@link AgentToolFunction#resultArrayKeys}：结果是 {@code {code,id,data}}，顶层没有数组值的键
     *
     * <p>🔴 但 {@code data} <b>里面</b>可能有数组（子表字段的值就是行数组）。
     * {@code pickRows} 只看<b>顶层</b>键，所以本工具落进「别对它用 pickRows」那一栏是准确的；
     * 要取子表行，写法是 {@code pickRows(state.get_out.data.子表字段名)} —— 那一步取的已经是
     * {@code data} 的字段，形状由那张表单自己决定，声明不到这里来。
     */
    @Override
    public List<String> resultArrayKeys() {
        return List.of();
    }

}
