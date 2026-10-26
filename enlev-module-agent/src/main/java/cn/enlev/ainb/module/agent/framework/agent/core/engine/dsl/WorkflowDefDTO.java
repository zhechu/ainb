package cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * Workflow DSL v1 顶层结构（Jackson 可解析）
 *
 * <p>对应 {@code AI原生低代码平台-WorkflowDSL-v1.md} §2。这是「Plan 即 Workflow」的唯一执行契约：
 * Planner 自动拆解的产物、业务人员可视化编排的产物，都是同一份 JSON，反序列化成本类。
 *
 * <p>🔴 刻意用宽松的 {@code @JsonIgnoreProperties(ignoreUnknown = true)}：
 * DSL 会随版本演进新增字段，旧引擎读到新 DSL 时应当<b>忽略不认识的字段</b>继续跑，
 * 而不是整份解析失败 —— 否则前端先于后端上线一个新节点字段，所有存量流程都编译不了。
 *
 * @author 走进新时代
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkflowDefDTO {

    /**
     * DSL 版本，如 "1.0"
     */
    private String dslVersion;

    /**
     * 工作流定义 code（租户内唯一），对应 agent_workflow_def.code
     */
    private String id;

    private String name;

    private String description;

    /**
     * 触发器，一份 DSL 恰好一个（§4.1）
     */
    private TriggerDTO trigger;

    /**
     * 变量声明（§3）
     */
    private List<VariableDTO> variables;

    /**
     * 节点（§4）
     */
    private List<NodeDTO> nodes;

    /**
     * 边（§5）
     */
    private List<EdgeDTO> edges;

    /**
     * 全局设置（§2）
     */
    private SettingsDTO settings;

}
