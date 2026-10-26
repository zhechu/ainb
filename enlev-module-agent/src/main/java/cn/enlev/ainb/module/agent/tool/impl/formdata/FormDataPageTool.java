package cn.enlev.ainb.module.agent.tool.impl.formdata;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDataPageReqDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具：按条件分页查询某张表单里<b>已提交的记录</b>
 *
 * <h3>🔴 操作的是「数据行」，不是「表单定义」</h3>
 * 见 {@link FormDataGetTool} 的同名说明。本工具是「查这张表单里有哪些记录」，
 * 与 {@code form_list}（查库里有哪些表单）、{@code form_get}（查一张表单的字段结构）分属不同层次。
 *
 * <h3>🔴 两层权限</h3>
 * 第 2 层动态门是 {@code infra:form-design-run:{code}:query} —— 与 {@code form_data_get} 同为 query 动作
 * （infra Controller 的 get/page/distinctValues 都映射到 query）。
 *
 * <h3>回灌精简，控 token</h3>
 * 只返回 {@code {total, pageNo, pageSize, list}}，不把 PageResult 的其它东西带出去。
 * list 里每行是 infra 运行时组装的「主表字段 + 子表嵌套」，字段数由表单决定 ——
 * 真要控体积就靠 pageSize（默认 10），而不是靠截断（截断一份数据列表会让模型误判总量）。
 *
 * @author 走进新时代
 */
@Component
@AgentTool(
        name = FormDataPageTool.NAME,
        displayName = "分页查询表单数据",
        groupName = "表单数据",
        description = "按条件分页查询某张表单里「已提交的记录」，返回总数与当前页的数据行。"
                + "⚠️ 操作的是表单提交进来的数据行，不是表单定义 —— 查库里有哪些表单用 form_list，看一张表单的字段结构用 form_get。"
                + "支持按字段过滤（conditions）、排序、翻页；不知道字段名先用 form_get 看结构，别猜",
        permission = "agent:assistant:use",
        risk = RiskLevel.READ_ONLY)
public class FormDataPageTool implements AgentToolFunction {

    public static final String NAME = "form_data_page";

    @Resource
    private FormDesignApi formDesignApi;
    @Resource
    private FormDataPermissionChecker permissionChecker;
    @Resource
    private FormDataParamResolver resolver;

    @Override
    public Object execute(Map<String, Object> params) {
        String code = resolver.resolveCode(params, NAME);
        // 🔴 第 2 层动态数据权限门，必须在调 api 之前
        permissionChecker.check(code, FormDataPermissionChecker.ACTION_QUERY);

        FormDataPageReqDTO reqDTO = resolver.resolvePageReq(params, NAME);
        PageResult<Map<String, Object>> page = formDesignApi.pageData(code, reqDTO);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("code", code);
        resp.put("total", page == null ? 0L : page.getTotal());
        // pageNo/pageSize 回显「实际生效」的值：入参可能没传（由 Impl 兜默认），
        // 模型需要知道这一页到底是第几页、多大，才能正确翻页
        resp.put("pageNo", reqDTO.getPageNo() == null ? 1 : reqDTO.getPageNo());
        resp.put("pageSize", reqDTO.getPageSize() == null ? 10 : reqDTO.getPageSize());
        resp.put("list", page == null ? Collections.emptyList() : page.getList());
        return resp;
    }

    /** 见 {@link AgentToolFunction#resultArrayKeys}：本工具全部返回分支里数组值的键（并集） */
    @Override
    public List<String> resultArrayKeys() {
        return List.of("list");
    }

    @Override
    public String paramsSchema() {
        return FormDataParamResolver.PAGE_SCHEMA;
    }

}
