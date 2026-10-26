package cn.enlev.ainb.module.agent.controller.admin.workflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 管理后台 - 名称目录 Response VO（重构 S1 / L1 名称目录层）
 *
 * <h3>这一份目录解决的是什么问题</h3>
 * DSL 里存的一律是<b>机器键</b>（工具名 {@code datamodel_create}、控件 type {@code DictSelect}、
 * 操作符 {@code LIKE}、子流程 code {@code wf_asset_check}）—— 这对模型精确、对幂等友好，
 * 但直接渲染给业务人员等于一屏密码。名称目录是「机器键 → 中文名」的唯一映射来源，
 * 前端 {@code nameCatalog.ts} 拿它把画布摘要、节点下拉、执行历史、人工确认卡全部翻成人话。
 *
 * <h3>🔴 前端拿不到的才放这里</h3>
 * 大模型与知识库<b>不在</b>本目录：agent 模块在 ai 模块的 api 层没有「列模型 / 列知识库」的出口
 * （{@code AiModelApi} 只有 chat），而前端本来就直接调 ai 的两个 simple-list 接口、
 * 且下拉里显示的一直是名字而非 id。为两处已经正确的展示去新开一条跨模块出口，不划算。
 *
 * <h3>🔴 权限：{@code agent:workflow:query}，不是 {@code agent:config:query}</h3>
 * 这是 G7 的修复点。此前设计器拿工具清单走 {@code GET /agent/config/tool-list}，
 * 而那个端点挂的是「AI 配置管理」的权限 —— 只有编排权、没有 AI 配置权的管理员打开设计器，
 * 工具下拉是<b>空的且没有任何提示</b>（前端 {@code .catch(() => [])} 把 403 吞了）。
 * 本端点挂在 workflow-def 下、用 workflow 自己的读权限，权限模型自洽。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - 名称目录 Response VO")
@Data
public class AgentNameCatalogRespVO {

    @Schema(description = "工具目录：内置（registry）与自定义（agent_tool_def 启用行）合流，冲突时内置优先")
    private List<ToolItem> tools;

    @Schema(description = "控件 type → 中文名（来自 FormDesignApi.getWidgetContracts）")
    private Map<String, String> widgetLabels;

    @Schema(description = "表单数据查询操作符目录（来自 FormDesignApi.getSearchOperatorList）")
    private List<OperatorItem> operators;

    @Schema(description = "已发布流程 code → 中文名（sub_workflow 节点展示用）")
    private List<WorkflowItem> workflows;

    /**
     * 一个工具的展示信息
     *
     * <p>🔴 不含 {@code permissions}：那是「能不能调」的判定材料，属于
     * {@code GET /agent/config/tool-list}（AI 配置管理页）的职责。名称目录只管「叫什么、多危险」，
     * 少给一份权限串的副本，就少一处会漂移的口径。
     *
     * <h4>🔴 风险为什么下发两份（{@code risk} 与 {@code declaredRisk}）</h4>
     * 迭代三把运行期各道闸全部改读 {@code getDeclaredRisk()}（只读校验 V3/V11、Planner 提示词、
     * agent_loop 只读限制、沙箱 mock、发布判定、变更清单），而降险这个在线维护动作<b>改的是生效值</b>。
     * 于是「界面说无需确认、发布时才被拒」成了必然：本目录若只给生效值，设计器就会照着生效值
     * 画出「无需确认」的色点、{@code agent_def} 的工具下拉照着它把工具放进「只读可用」那一档，
     * 而用户点发布时校验读的是声明值 —— 界面不是没帮上忙，是<b>主动指了反方向</b>。
     *
     * <p>两份都给而不是只给声明值，是因为生效值本身仍有用（管理员要能看出自己那次维护改到了什么）。
     * 分工是明确的：<b>凡是用来预测闸门行为的，一律读 {@code declared*}</b>；
     * {@code riskOverridden=true} 时把生效值作为「界面标注已被改过」的提示，而不是当作事实。
     * 自定义工具没有这个分裂（目录行<b>就是</b>它的声明），两份恒等。
     */
    @Schema(description = "工具展示项")
    @Data
    public static class ToolItem {

        @Schema(description = "工具键，DSL 的 toolId 存的就是它", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "datamodel_create")
        private String name;

        @Schema(description = "中文展示名；内置工具未维护时回落代码声明值，再回落工具键",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "创建数据表")
        private String displayName;

        @Schema(description = "工具说明（下拉右侧灰字、提示词同源）", example = "按给定结构新建一张业务表")
        private String description;

        @Schema(description = "分组名，下拉按它分组；空串表示不分组", example = "数据建模")
        private String groupName;

        @Schema(description = "生效风险等级（在线维护后的值）；🔴 各道闸不按它判，预测闸门行为读 declaredRisk",
                example = "IRREVERSIBLE")
        private String risk;

        @Schema(description = "生效风险等级的中文名", example = "不可逆")
        private String riskDescription;

        @Schema(description = "生效值口径的「是否需人工确认」；🔴 各道闸不按它判，读 declaredRequireConfirm",
                example = "true")
        private Boolean requireConfirm;

        @Schema(description = "代码声明的风险等级 —— 运行期各道闸（只读校验、发布判定、agent_loop 只读限制、"
                + "沙箱 mock、变更清单）读的就是它", example = "IRREVERSIBLE")
        private String declaredRisk;

        @Schema(description = "代码声明风险等级的中文名", example = "不可逆")
        private String declaredRiskDescription;

        @Schema(description = "代码声明的「是否必须进 Plan 等用户确认」—— 发布期校验按它拒/放", example = "true")
        private Boolean declaredRequireConfirm;

        @Schema(description = "生效风险 ≠ 声明风险，即风险标注被在线维护改过（只改展示名/分组的不算）",
                example = "false")
        private Boolean riskOverridden;

        @Schema(description = "是否启用（自定义工具只列启用行，故内置工具才可能为 false）", example = "true")
        private Boolean enabled;

        @Schema(description = "是否被当前租户在线维护过", example = "false")
        private Boolean overridden;

        /**
         * 来源：{@link #SOURCE_BUILTIN} 或 {@code agent_tool_def.source} 的取值
         * （{@code DATA_INTERFACE} / {@code FORM} / {@code SCRIPT}）。
         *
         * <p>🔴 刻意<b>不</b>复用 {@code ToolSourceEnum}：那个枚举的 javadoc 明写
         * 「只描述无 Java 代码、必须由数据承载定义的用户自定义工具」，且它绑定的是
         * {@code agent_tool_def.source} 这一列。往里塞一个 BUILTIN 会同时破坏它的语义与它的列取值域。
         */
        @Schema(description = "来源：BUILTIN=代码内置工具，其余取自 agent_tool_def.source", example = "BUILTIN")
        private String source;

    }

    /** 内置（{@code @AgentTool} 注解、由 {@code AgentToolRegistry} 登记）工具的 source 取值 */
    public static final String SOURCE_BUILTIN = "BUILTIN";

    /**
     * 一个查询操作符
     *
     * <p>🔴 与 DSL {@code condition.rule} 的操作符<b>不是一个命名空间</b>：那边是小写的
     * {@code eq/ne/contains/...}（由 {@code ConditionEvaluator} 解释），这边是大写的
     * {@code EQ/LIKE/...}（由 infra 的 {@code DynamicSqlBuilder} 解释、用于 {@code form_data_page}
     * 的查询条件）。两者不可互换，前端也不该拿一份去翻译另一份。
     */
    @Schema(description = "查询操作符项")
    @Data
    public static class OperatorItem {

        @Schema(description = "操作符 code，即 conditions[].operator 该填的值",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "LIKE")
        private String operator;

        @Schema(description = "中文名", requiredMode = Schema.RequiredMode.REQUIRED, example = "包含")
        private String label;

    }

    @Schema(description = "已发布流程项")
    @Data
    public static class WorkflowItem {

        @Schema(description = "业务编码，DSL 的 workflowId 存的就是它",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "wf_asset_check")
        private String code;

        @Schema(description = "中文名", example = "资产盘点")
        private String name;

    }

}
