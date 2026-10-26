package cn.enlev.ainb.module.agent.controller.admin.planner.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * plan-as-workflow 生成请求（增量7a）
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - 复杂需求拆解为 Workflow DSL 请求")
@Data
public class AgentWorkflowPlanReqVO {

    @Schema(description = "自然语言描述的复杂需求", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "做一个工作流：列出关键词为 test_demo 的数据接口，最多返回5条")
    @NotBlank(message = "需求描述不能为空")
    private String question;

    /**
     * 生成后是否直接发布（DRAFT→PUBLISHED）
     *
     * <p>🔴 <b>默认 false（重构 S4b / D8）</b>：7a 那会儿还没有确认卡，「生成→发布→执行」只能靠这个字段一把梭；
     * 7b 落地后发布已经由用户在卡片上点「确认并运行」触发（走 {@code /publish-workflow}），
     * 原注释里「届时本字段可置 false」的条件已经成立 —— 现在就是届时。
     *
     * <p>⚠️ 更要紧的是：S4b 给 {@code publishDef} 加了发布闸门（V1~V19 体检不过就拒），
     * 而 LLM 一次生成的 DSL 恰恰是最可能不过体检的那种。默认发布等于让「拆解需求」这个只读意图
     * 顺带触发一次真实的状态翻转，失败时用户拿到的是一个跟自己提问无关的发布错误码，
     * 而库里已经躺了一行 DRAFT —— 半成功、无提示。默认只落草稿，发布留给一个明确的用户动作。
     *
     * <p>⚠️ 字段保留而不是删掉：它仍是 7a 那条 REST 全链路验证的唯一开关，删了就没有办法
     * 在不点前端卡片的情况下验证「生成→发布→执行」。
     */
    @Schema(description = "生成后是否直接发布，默认只落草稿", example = "false")
    private Boolean publish = false;

}
