package cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.WorkflowContext;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code wait} 节点执行器（DSL §4.3 L120，增量4a 做 {@code waitType=duration}、增量4b 做 {@code waitType=event}）
 *
 * <h3>🔴 本执行器「不等待」—— 等待是 checkpoint 挂起天然实现的</h3>
 * 编译器对每个 wait 节点登记 {@code interruptBefore(id)}：SAA 在执行到该节点<b>之前</b>挂起、checkpoint 落库、
 * stream 正常结束（末元素是 {@code InterruptionMetadata}）。挂起期<b>不占线程</b>、可跨进程跨重启都在
 * （与 {@code hitl.confirm} 同机制）。<b>谁来触发 resume 取决于 waitType</b>：duration 由 Service 算 {@code resume_at}
 * 落库、{@code WorkflowWaitResumeScheduler} 扫 {@code resume_at<=now} 到点自动 resume；event 由 Service 落 {@code event_key}、
 * 外部系统 {@code POST /agent/workflow/event} 按 key 匹配后 resume。resume 之后本执行器才被调用，
 * 它只把「等过了」记一笔标记后放行下游。<b>所以这里没有任何 sleep / 轮询，且与 waitType 无关。</b>
 *
 * <h3>范围</h3>
 * {@code waitType=duration}（定时，增量4a）+ {@code waitType=event}（外部事件，增量4b）均已支持。
 * {@code hitl}（人工唤醒）不做——它等价 {@code hitl.confirm} 节点，编译期 {@code validateWaitConfig} 明确拒。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class WaitNodeExecutor implements NodeExecutor {

    public static final String TYPE = "wait";

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public Map<String, Object> execute(NodeDTO node, WorkflowContext ctx) {
        // 恢复后才跑到这里：等待已由「interruptBefore 挂起 + 轮询器到点/外部事件 resume」完成，本执行器只记标记 + 放行。
        String waitType = node.configStr("waitType");
        log.info("[execute][wait 节点({}) 执行({}) 已唤醒(waitType={})]",
                node.getId(), ctx.getExecutionId(), waitType);

        Map<String, Object> delta = new HashMap<>();
        delta.put(node.getId() + "_waited", true);
        delta.put(node.getId() + "_resumed_at", LocalDateTime.now().toString());
        return delta;
    }

}
