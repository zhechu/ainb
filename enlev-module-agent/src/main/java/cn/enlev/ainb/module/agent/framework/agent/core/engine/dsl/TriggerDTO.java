package cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.Map;

/**
 * DSL 触发器（§4.1）
 *
 * <p>触发器不是 SAA 图内节点，而是「谁来调用 stream() + 初始 state 是什么」。
 * P1 只落地 {@code trigger.chat}（会话入口，把输入写进 {@code input} 变量）；
 * form_event / schedule / webhook 的实际触发器在 P6 接。
 *
 * @author 走进新时代
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TriggerDTO {

    /**
     * 缺省入口变量名（会话入口把会话输入写进这个键，DSL §4.1）
     *
     * <p>🔴 提到常量是为了让<b>消费方引用而不是各自抄一份字面量</b>：
     * {@code AgentAssistantServiceImpl} 的 fail-soft 回落、{@code WorkflowDslValidator} V20 的
     * 「没有 trigger 块」那一档，问的都是同一个缺省名。抄字面量的后果是改这里不改那里 ——
     * 而这两处都在<b>取不到 trigger</b> 的分支上，恰恰是最少被跑到的路径，漂了也没人发现。
     */
    public static final String DEFAULT_INPUT_VAR = "input";

    /** 缺省出口变量名（会话入口从最终 state 取这个键当聊天回复）。理由同 {@link #DEFAULT_INPUT_VAR} */
    public static final String DEFAULT_OUTPUT_VAR = "output";

    /**
     * 触发类型：trigger.chat / trigger.form_event / trigger.schedule / trigger.webhook
     */
    private String type;

    /**
     * 类型相关配置（agentId / formCode / cron / path ...）
     */
    private Map<String, Object> config;

    /**
     * 入口变量名，触发时把输入写进这个变量。默认 "input"
     */
    public String inputVar() {
        Object v = config == null ? null : config.get("inputVar");
        return v == null ? DEFAULT_INPUT_VAR : String.valueOf(v);
    }

    /**
     * 出口变量名，会话入口从最终 state 取这个键作为聊天回复。默认 "output"
     *
     * <p>🔴 与 {@link #inputVar()} 对称，但 spec §4.1 只固定了 input，output 是增量12（slice-3c）的本刀约定
     * （见计划 DD6/RK1）。批次 B1 起改为「可从 {@code trigger.chat.config.outputVar} 覆盖」：
     * 作者显式配了就用配的，没配回落 {@link #DEFAULT_OUTPUT_VAR}。
     * 聊天路径 {@code AgentAssistantServiceImpl.extractReply} 消费它。
     */
    public String outputVar() {
        Object v = config == null ? null : config.get("outputVar");
        return v == null ? DEFAULT_OUTPUT_VAR : String.valueOf(v);
    }

}
