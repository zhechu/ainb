package cn.enlev.ainb.module.agent.tool.impl.form;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 工具：查询表单列表
 *
 * <h3>为什么这个看似平凡的工具是必需的</h3>
 * 平台上所有按表单操作的入口都以 <b>code</b> 为准（绑流程、解释规则、发布、改表单），
 * 而用户说的是<b>名字</b>（「给请假申请加个审批」）。没有这个工具，
 * 名字换不成编码，后面所有操作都无从谈起 —— 模型只能猜一个编码然后失败。
 *
 * <p>它也是「先查再做」那条硬规则在表单侧的落点：
 * 要给某张表单加东西之前，先确认它真的存在、且已经发布。
 *
 * @author 走进新时代
 */
@Component
@AgentTool(
        name = "form_list",
        displayName = "查询表单列表",
        groupName = "表单设计",
        description = "查询已有的表单列表，拿到表单编码（code）。"
                + "用户用名字称呼表单时（如「请假申请表」），先用它把名字换成编码 —— "
                + "绑流程、解释规则、改表单都以编码为准。"
                + "也用它确认某张表单是否存在、是否已发布",
        permission = "infra:form-design:query",
        risk = RiskLevel.READ_ONLY)
public class FormListTool implements AgentToolFunction {

    public static final String PARAM_KEYWORD = "keyword";

    /** 状态：已发布 */
    private static final int STATUS_PUBLISHED = 1;

    @Resource
    private FormDesignApi formDesignApi;

    @Override
    public Object execute(Map<String, Object> params) {
        Object keywordParam = params.get(PARAM_KEYWORD);
        String keyword = keywordParam == null ? null
                : StrUtil.trimToNull(keywordParam.toString());

        List<Map<String, Object>> items = new ArrayList<>();
        for (FormDesignApi.FormDesignSimpleRespDTO form : formDesignApi.getFormDesignSimpleList()) {
            if (keyword != null && !matches(form, keyword)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            // 🔴 id 必须给。form_update 与 form_publish_bundle 都<b>只认 id</b>，
            //    而这里是模型唯一能列出表单的地方 —— 不给 id，那两个写工具就成了
            //    「参数无法被发现」的死路：模型查遍所有只读工具也凑不出一个 id，
            //    只能放弃并把锅甩给用户。
            //    实测就是这么失败的：模型明确回了一句
            //    「现有草稿需添加子表字段，但查询结果未返回其编号（id），无法调用 form_update」
            item.put("id", form.getId());
            item.put("code", form.getCode());
            item.put("name", form.getName());
            item.put("mainTable", form.getMainTable());
            // 未发布的表单用户看不到、流程也绑不了 —— 必须让模型知道
            item.put("published", Objects.equals(form.getStatus(), STATUS_PUBLISHED));
            if (StrUtil.isNotBlank(form.getCategory())) {
                item.put("category", form.getCategory());
            }
            items.add(item);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", items.size());
        resp.put("items", items);
        if (items.isEmpty()) {
            resp.put("message", keyword == null
                    ? "库里还没有任何表单"
                    : "没有名称或编码包含「" + keyword + "」的表单。"
                            + "不要凭猜测编一个编码 —— 先不带 keyword 再查一次看看都有哪些");
        } else {
            resp.put("message", "published 为 false 的表单还没发布，"
                    + "用户看不到它，流程也绑不了");
        }
        return resp;
    }

    /**
     * 名称与编码都参与匹配
     *
     * 用户既可能说中文名，也可能直接给编码（比如从别处复制来的）。
     */
    private boolean matches(FormDesignApi.FormDesignSimpleRespDTO form, String keyword) {
        String lower = keyword.toLowerCase(Locale.ROOT);
        return StrUtil.containsIgnoreCase(form.getName(), keyword)
                || (form.getCode() != null && form.getCode().toLowerCase(Locale.ROOT).contains(lower));
    }

    /** 见 {@link AgentToolFunction#resultArrayKeys}：本工具全部返回分支里数组值的键（并集） */
    @Override
    public List<String> resultArrayKeys() {
        return List.of("items");
    }

    @Override
    public String paramsSchema() {
        return "keyword（可选，字符串）：按表单名称或编码模糊查询，不传则返回全部";
    }

}
