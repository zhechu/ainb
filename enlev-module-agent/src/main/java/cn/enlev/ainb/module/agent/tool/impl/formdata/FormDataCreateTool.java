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
 * 工具：在某张表单里<b>新增一条记录</b>
 *
 * <h3>🔴 操作的是「数据行」，不是「表单定义」</h3>
 * 见 {@link FormDataGetTool} 的同名说明。新增的是用户提交的业务数据，不是给表单加字段。
 *
 * <h3>🔴 两层权限</h3>
 * 第 2 层动态门 {@code infra:form-design-run:{code}:create}。
 *
 * <h3>必填/唯一/类型校验交给 infra 运行时</h3>
 * 写路径（{@code FormDesignRuntimeService.create}）自带 tenant 填充、逻辑删除、乐观锁、
 * 行级权限过滤、唯一校验。撞了必填或唯一约束时 infra 抛中文可读的 {@code ServiceException}，
 * 原样冒泡给模型自修正 —— 本工具<b>不重复做字段级校验</b>，那会与 infra 漂移。
 *
 * @author 走进新时代
 */
@Component
@AgentTool(
        name = FormDataCreateTool.NAME,
        displayName = "新增表单数据",
        groupName = "表单数据",
        description = "在某张表单里新增一条记录（写入用户提交的业务数据），返回新记录的主键 id。"
                + "⚠️ 操作的是表单提交进来的数据行，不是表单定义 —— 给表单加字段用 form_update。"
                + "data 用「字段名 → 值」，字段名是表单里的 field；不知道字段名、哪些必填，先用 form_get 看结构，别猜",
        permission = "agent:assistant:use",
        risk = RiskLevel.LOW)
@Slf4j
public class FormDataCreateTool implements AgentToolFunction {

    public static final String NAME = "form_data_create";

    @Resource
    private FormDesignApi formDesignApi;
    @Resource
    private FormDataPermissionChecker permissionChecker;
    @Resource
    private FormDataParamResolver resolver;

    @Override
    public Object execute(Map<String, Object> params) {
        String code = resolver.resolveCode(params, NAME);
        Map<String, Object> data = resolver.resolveData(params, NAME);
        // 🔴 第 2 层动态数据权限门，必须在调 api 之前
        permissionChecker.check(code, FormDataPermissionChecker.ACTION_CREATE);

        Object pk = formDesignApi.createData(code, data);
        log.info("[execute][AI 在表单({}) 新增记录 主键({})]", code, pk);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", pk);
        resp.put("message", "已在表单 " + code + " 新增一条记录");
        return resp;
    }

    @Override
    public String paramsSchema() {
        return FormDataParamResolver.CREATE_SCHEMA;
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
