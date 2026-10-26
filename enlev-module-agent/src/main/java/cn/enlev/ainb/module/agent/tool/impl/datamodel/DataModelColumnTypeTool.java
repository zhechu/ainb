package cn.enlev.ainb.module.agent.tool.impl.datamodel;

import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.infra.api.datamodel.DataModelApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具：列出受支持的列类型
 *
 * 列类型是<b>受控枚举</b>而非自由填写的 MySQL 类型，AI 只能从这个集合里选。
 * 不给这个工具的话，LLM 很容易产出 {@code VARCHAR(255)}、{@code DOUBLE}、
 * {@code ENUM(...)} 这类看着合理但不被支持的写法，到建表时才报错。
 *
 * <p>返回值里带上选型指导 —— 类型合法只是底线，选对才是目的：
 * 金额用 {@code DOUBLE} 不会报错，但会在对账时慢慢暴露精度问题。
 *
 * @author 走进新时代
 */
@Component
@AgentTool(
        name = "datamodel_column_types",
        displayName = "查询可用字段类型",
        groupName = "数据建模",
        description = "列出建表时可用的字段类型及其选型指导。建表前调它，避免产出不被支持的类型",
        permission = "infra:data-model:query",
        risk = RiskLevel.READ_ONLY)
public class DataModelColumnTypeTool implements AgentToolFunction {

    /**
     * 选型指导
     *
     * 内容与 {@code docs/数据建模规范.md} §3 保持一致 —— 那份文档是这几条的唯一来源，
     * 两边不一致时以文档为准并回来改这里。
     */
    private static final List<String> GUIDELINES = List.of(
            "金额一律用 DECIMAL，绝不用浮点类型（受支持类型里刻意没有 DOUBLE/FLOAT，正是为了防这个）",
            "状态、类型一律用 TINYINT 并配字典，不要用 STRING 存「已完成」这类中文",
            "时间一律用 DATETIME，不要用 STRING 存 \"2026-09-21\"",
            "DECIMAL 的 scale 必须严格小于 length：decimal(10,2) 合法，decimal(2,2) 不合法",
            "STRING 长度范围 1..16383，不传默认 255；DECIMAL 不传默认 (10,2)",
            "TEXT / LONG_TEXT / JSON 不支持默认值，传了会被静默丢弃",
            "JSON 只用于结构确实不固定的文档（如第三方回调原文、可配置扩展属性）；"
                    + "能列出字段就用具体类型 —— JSON 里的键既建不了索引，也进不了列表页的搜索条件",
            "🔴 TEXT / LONG_TEXT / JSON 不能做主键或索引列，写了会在建表之前就被拒"
                    + "（TEXT/LONG_TEXT 撞 MySQL 1170，JSON 撞 3152，前缀长度对 JSON 也没用）。"
                    + "要按它查就另建一个 STRING/INT/LONG/DATETIME 的列并在那一列上建索引");

    @Resource
    private DataModelApi dataModelApi;

    @Override
    public Object execute(Map<String, Object> params) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("types", dataModelApi.getColumnTypeList());
        result.put("选型指导", GUIDELINES);
        return result;
    }


    /** 见 {@link AgentToolFunction#resultArrayKeys}：本工具全部返回分支里数组值的键（并集） */
    @Override
    public List<String> resultArrayKeys() {
        return List.of("types", "选型指导");
    }

    @Override
    public String paramsSchema() {
        return "无参数";
    }

}
