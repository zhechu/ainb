package cn.enlev.ainb.module.agent.tool.impl.datamodel;

import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.infra.api.datamodel.DataModelApi;
import cn.enlev.ainb.module.infra.api.datamodel.dto.DataModelStructureRespDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_TOOL_PARAM_INVALID;

/**
 * 工具：读取表结构
 *
 * 🔴 <b>改表前必须先调它</b>，这不是建议而是硬性前置。
 *
 * <p>改表是<b>全量同步语义</b>：提交的列表里没有的列一律被判为删除，
 * 而改表路径不走系统列注入器。调用方若按「我只想加一个字段」的直觉只提交业务列，
 * 7 个系统列会全部落进待删除 —— 其中 {@code tenant_id} 一丢就是跨租户泄漏、
 * {@code deleted} 一丢逻辑删除变物理删除。
 *
 * <p>所以返回值刻意把列<b>分成两组</b>并给出明确的改表指引，
 * 而不是丢一个平铺列表让 LLM 自己琢磨。
 *
 * @author 走进新时代
 */
@Component
@AgentTool(
        name = "datamodel_structure",
        displayName = "查询数据模型结构",
        groupName = "数据建模",
        description = "读取指定数据模型的完整表结构（字段 + 索引）。改表前必须先调它取回全量列",
        permission = "infra:data-model:query",
        risk = RiskLevel.READ_ONLY)
public class DataModelStructureTool implements AgentToolFunction {

    @Resource
    private DataModelApi dataModelApi;

    @Override
    public Object execute(Map<String, Object> params) {
        Object id = params.get("id");
        if (id == null) {
            // 抛业务异常而非 IllegalArgumentException —— 后者会变成「系统异常」，
            // AI 拿到那个只会原样重试，拿到下面这句才知道该先调 datamodel_list
            throw exception(AGENT_TOOL_PARAM_INVALID, "datamodel_structure",
                    "缺少 id（数据模型编号），可先用 datamodel_list 查到它");
        }
        long modelId;
        try {
            modelId = Long.parseLong(id.toString().trim());
        } catch (NumberFormatException ex) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "datamodel_structure",
                    "id 必须是数字，收到的是：" + id);
        }
        DataModelStructureRespDTO structure = dataModelApi.getStructure(modelId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tableName", structure.getTableName());
        result.put("tableComment", structure.getTableComment());
        // 分组返回：让「哪些列必须原样回传」一目了然，而不是让 LLM 自己按标记筛
        result.put("systemColumns", structure.getColumns().stream()
                .filter(c -> Boolean.TRUE.equals(c.getSystemColumn())).toList());
        result.put("businessColumns", structure.getColumns().stream()
                .filter(c -> !Boolean.TRUE.equals(c.getSystemColumn())).toList());
        result.put("indexes", structure.getIndexes());
        result.put("改表须知", "要改这张表，用 datamodel_update，"
                + "只描述【要变什么】（addColumns / modifyColumns / dropColumns）即可，"
                + "不要传完整列清单 —— 现有结构由工具自己取回合并，"
                + "你不会因为漏写某一列而把它删掉。"
                + "删列规则：系统列一律删不掉；业务列在表里有数据时会被拒绝，空表才能删。");
        return result;
    }


    /** 见 {@link AgentToolFunction#resultArrayKeys}：本工具全部返回分支里数组值的键（并集） */
    @Override
    public List<String> resultArrayKeys() {
        return List.of("systemColumns", "businessColumns", "indexes");
    }

    /** 见 {@link AgentToolFunction#resultKeys}：本工具顶层键（{@code 改表须知} 是真实的中文键名） */
    @Override
    public List<String> resultKeys() {
        return List.of("tableName", "tableComment", "systemColumns", "businessColumns",
                "indexes", "改表须知");
    }

    @Override
    public String paramsSchema() {
        return "id（必填，数字）：数据模型编号，从 datamodel_list 的结果里取";
    }

}
