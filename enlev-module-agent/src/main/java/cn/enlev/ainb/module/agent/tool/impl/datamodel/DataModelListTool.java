package cn.enlev.ainb.module.agent.tool.impl.datamodel;

import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.infra.api.datamodel.DataModelApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 工具：列出已登记的数据模型
 *
 * 🔴 <b>这是「建表单」需求的第一步，AI 必须先调它。</b>
 *
 * <p>表单设计<b>只绑已存在的表、不建表也不改表</b>，所以「建一个表单」有两条入口，
 * 风险差一个数量级：
 * <ul>
 *   <li><b>表已存在</b> → 查到后直接绑上去：全程无 DDL、完全可逆</li>
 *   <li><b>表不存在</b> → 先建表：含不可逆操作，MySQL 的 DDL 回不去</li>
 * </ul>
 * 所以硬规则是：<b>接到建表单需求，必须先查库里有没有可复用的表</b>，
 * 查到候选就在 Plan 里把「用这张现成的表」作为首选项呈现。不得张口就建新表。
 *
 * @author 走进新时代
 */
@Component
@AgentTool(
        name = "datamodel_list",
        displayName = "查询数据模型列表",
        groupName = "数据建模",
        description = "列出当前租户已登记的数据模型（表）。建表单前必须先调它查有没有可复用的表",
        permission = "infra:data-model:query",
        risk = RiskLevel.READ_ONLY)
public class DataModelListTool implements AgentToolFunction {

    @Resource
    private DataModelApi dataModelApi;

    @Override
    public Object execute(Map<String, Object> params) {
        List<DataModelApi.DataModelSimpleRespDTO> list = dataModelApi.getDataModelList();
        // 支持按关键字过滤：库里有近 300 张表，全量回灌给 LLM 既费 token 又稀释注意力
        String keyword = params.get("keyword") == null ? null : params.get("keyword").toString().trim();
        if (keyword != null && !keyword.isEmpty()) {
            String lower = keyword.toLowerCase();
            list = list.stream().filter(m ->
                    contains(m.getTableName(), lower)
                            || contains(m.getTableComment(), lower)
                            || contains(m.getModelName(), lower)).toList();
        }
        return Map.of("total", list.size(), "models", list);
    }

    private boolean contains(String value, String lowerKeyword) {
        return value != null && value.toLowerCase().contains(lowerKeyword);
    }


    /** 见 {@link AgentToolFunction#resultArrayKeys}：本工具全部返回分支里数组值的键（并集） */
    @Override
    public List<String> resultArrayKeys() {
        return List.of("models");
    }

    @Override
    public String paramsSchema() {
        return "keyword（可选，字符串）：按表名或表描述模糊过滤。不传则返回全部";
    }

}
