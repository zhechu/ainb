package cn.enlev.ainb.module.agent.tool.impl.datamodel;

import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.AgentValidateResult;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.DataModelUpdateValidator;
import cn.enlev.ainb.module.infra.api.datamodel.DataModelApi;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_TOOL_PARAM_INVALID;

/**
 * 工具：修改已有数据表（方案 §5.4 —— 整套工具里最高危的一个）
 *
 * <h3>🔴 为什么这个工具不接受「完整列集合」</h3>
 * 方案原本的设计是「强制模型先取回完整结构，改完整体回传」。
 * 那样仍然留着一个致命的失败模式：<b>模型漏传一列，那一列就被删掉</b>。
 * 因为 {@code updateDataModel} 是全量同步语义 ——
 * 真实表里有、入参里没有的列，一律进 {@code dropColumns}。
 *
 * <p>而「漏传」恰恰是最可能发生的：列一多，模型复述时丢一两个是常态。
 * 后端对系统列有硬保护，对业务列则是「有数据才拦」—— 于是<b>空表上的误删是静默的</b>。
 *
 * <p>所以这里换了个思路：工具<b>只接受变更意图</b>（加哪些、改哪些、删哪些），
 * 现有结构由 {@link DataModelParamResolver} 去取、去组装。
 * 这样「漏传某一列」在结构上不可能发生 ——
 * 不是靠模型自觉，是靠它根本没有机会犯这个错。
 *
 * <h3>删列的规则（B4-3 之后：AI 一律不能删列）</h3>
 * <ul>
 *   <li><b>系统列</b>：无论表有无数据，一律拒绝。删 {@code tenant_id} 会跨租户泄漏，
 *       删 {@code deleted} 会让逻辑删除变成物理删除</li>
 *   <li><b>业务列</b>：后端要 {@code confirmDropColumns=true}，而那个标记
 *       <b>不许由 AI 设置</b> ⟹ AI 删不了列，<b>空表也不行</b></li>
 * </ul>
 *
 * <p>🔴 本工具<b>永远不传</b> {@code riskConfirmed} 与 {@code confirmDropColumns}。
 * 那两个标记的语义分别是「人已经看过这张表的数据，确认这些列可以丢」与
 * 「人确实要删掉这几列」，而用户在 Plan 卡片上点的是「同意执行这个计划」——
 * 三件事不能混。要删列，请到数据模型界面操作，那边有二次确认弹窗。
 *
 * <h3>🔴 两条基线由工具自己带，模型碰不到（B4-2 / B4-4）</h3>
 * {@code DataModelParamResolver#resolveUpdate} 从<b>同一次</b> {@code getStructure} 取回
 * {@code version}（登记行的乐观锁版本）与 {@code schemaFingerprint}（物理表结构指纹）一起提交：
 * <ul>
 *   <li>{@code version} 挡的是<b>另一个窗口</b>：从生成 Plan 到用户点确认这段时间里，
 *       别人可能已经改过这张表。撞上了后端报「请刷新后重做」，而不是把对方的改动悄悄抹掉</li>
 *   <li>{@code schemaFingerprint} 挡的是<b>绕过平台的那条路</b>：DBA 直接执行 ALTER、
 *       迁移脚本、别的工具 —— 它们<b>不经过</b>登记行，压根不会推高 version，
 *       光靠乐观锁看不见。指纹对不上时后端在<b>执行任何 DDL 之前</b>就中止</li>
 * </ul>
 * 让模型自己填这两个值等于让它给自己发通行证，所以入参里没有它们。
 *
 * <p>拿不准这次改动会生成什么 ALTER、能不能过，先用 {@code datamodel_preview_ddl}
 * 预览 —— 它与本工具共用同一套入参组装与校验，不产生任何副作用。
 *
 * @author 走进新时代
 */
@Component
@AgentTool(
        name = DataModelUpdateTool.NAME,
        displayName = "修改数据表结构",
        groupName = "数据建模",
        description = "修改已有的数据表：加字段、改字段。"
                + "⚠️ 不可逆，执行后无法回滚。"
                + "只需要描述【要变什么】，不要也不需要传完整的列清单 —— "
                + "现有结构由工具自己取回并合并。"
                + "🔴 不支持删字段：删列不可回滚，必须由人在数据模型界面做（那边有二次确认），"
                + "传 dropColumns 会被直接拒。系统列本来就一律删不掉",
        permission = "infra:data-model:update",
        risk = RiskLevel.IRREVERSIBLE)
@Slf4j
public class DataModelUpdateTool implements AgentToolFunction {

    /**
     * 工具键
     *
     * <p>🔴 上面注解的 {@code name} 与变更清单抽取器的 {@code toolId()} 都必须引用它。
     * 三处各写一遍字面量时，改名不会报错 —— 只会让清单静默退回兜底解读，
     * 表现是「修改表结构」在确认框里显示成「调用工具」。
     */
    public static final String NAME = "datamodel_update";

    @Resource
    private DataModelApi dataModelApi;
    @Resource
    private DataModelUpdateValidator validator;
    @Resource
    private DataModelParamResolver paramResolver;

    @Override
    public Object execute(Map<String, Object> params) {
        DataModelParamResolver.UpdateResolution resolution =
                paramResolver.resolveUpdate(params, NAME);

        AgentValidateResult result = validator.validate(resolution.reqDTO(), resolution.current());
        if (!result.isPassed()) {
            throw exception(AGENT_TOOL_PARAM_INVALID, NAME, result.toFeedback());
        }

        dataModelApi.updateDataModel(resolution.reqDTO());
        String tableName = resolution.reqDTO().getTableName();
        log.info("[execute][AI 改表完成 表名({}) 增({}) 改({}) 删({})]",
                tableName, resolution.added(), resolution.modified(), resolution.dropped());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("tableName", tableName);
        resp.put("addedColumns", resolution.added());
        resp.put("modifiedColumns", resolution.modified());
        resp.put("droppedColumns", resolution.dropped());
        resp.put("totalColumns", resolution.reqDTO().getColumns().size());
        if (!result.getWarnings().isEmpty()) {
            resp.put("warnings", result.getWarnings());
        }
        resp.put("message", "表 " + tableName + " 已修改。"
                + "⚠️ 改表已直接作用于数据库，无法回滚");
        return resp;
    }

    /** 见 {@link AgentToolFunction#resultArrayKeys}：本工具全部返回分支里数组值的键（并集） */
    @Override
    public List<String> resultArrayKeys() {
        return List.of("addedColumns", "modifiedColumns", "droppedColumns", "warnings");
    }

    @Override
    public String paramsSchema() {
        return DataModelParamResolver.UPDATE_PARAMS_SCHEMA;
    }

}
