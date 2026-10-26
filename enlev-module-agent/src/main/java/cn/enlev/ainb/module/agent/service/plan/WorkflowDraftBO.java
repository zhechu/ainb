package cn.enlev.ainb.module.agent.service.plan;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * plan-as-workflow 生成器的产物：一份<b>结构合法、可被引擎执行</b>的 Workflow DSL 草稿（增量7a）
 *
 * <p>这是「复杂需求 → 合法 DSL」这条核心差异链路的输出载体。它<b>尚未落库</b>——
 * 落库（{@code createDef} DRAFT）、发布（{@code publishDef}）由调用方（REST 端点 / 7b 的聊天确认卡）决定。
 *
 * @author 走进新时代
 */
@Data
public class WorkflowDraftBO {

    /**
     * 服务端生成的唯一流程 code（已注入 {@link #dsl} 的 {@code id} 字段，不信任模型给的占位 id）
     */
    private String code;

    /**
     * 完整的 Workflow DSL JSON 原文（{@code WorkflowDefDTO} 序列化，{@code id} 已置为 {@link #code}）
     */
    private String dsl;

    /**
     * 流程名（取模型给的 name，缺省回落为 code）
     */
    private String name;

    /**
     * 一句话摘要（取模型给的 description，缺省回落为 name）
     */
    private String summary;

    /**
     * 校验器提出的<b>警告</b>（不阻断，但要让调用方/用户看到）
     *
     * <p>典型是「outputVar 未在 variables[] 声明」这类后端不报错、但可能不按预期合并的写法。
     * 与 {@code AgentValidateResult.warnings} 同源。
     */
    private List<String> warnings = new ArrayList<>();

    /**
     * 生成-校验-自修正循环实际跑了几轮（1 = 首轮即合法）
     */
    private Integer rounds;

}
