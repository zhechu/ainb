package cn.enlev.ainb.module.agent.framework.agent.core.engine.executor;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_NODE_EXECUTOR_NOT_FOUND;

/**
 * 节点执行器注册表
 *
 * <p>启动时把所有 {@link NodeExecutor} bean 按 {@link NodeExecutor#getType()} 收进一张表。
 * 用「Spring 注入 List + 建 Map」而不是手写注册：新增一种节点类型只要加一个 bean，
 * 注册表自动收录，不用回来改这里 —— 少一处「加了执行器忘了注册」的静默缺口。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class NodeExecutorRegistry {

    private final Map<String, NodeExecutor> executors = new HashMap<>();

    @Resource
    public void setExecutors(List<NodeExecutor> executorList) {
        for (NodeExecutor executor : executorList) {
            NodeExecutor previous = executors.put(executor.getType(), executor);
            if (previous != null) {
                // 两个 bean 声明同一个 type 是编码错误，启动即暴露而不是等运行时随机选一个
                log.warn("[setExecutors][节点类型({}) 被重复注册：{} 覆盖 {}]",
                        executor.getType(), executor.getClass().getSimpleName(),
                        previous.getClass().getSimpleName());
            }
        }
        log.info("[setExecutors][Workflow 节点执行器注册完成，共 {} 种：{}]",
                executors.size(), executors.keySet());
    }

    /**
     * 按类型取执行器，没有则抛业务异常
     *
     * 🔴 抛 {@code WORKFLOW_NODE_EXECUTOR_NOT_FOUND} 而不是返回 null：
     * DSL 里写了一个引擎没实现的 type（多半是 P2 才做的节点），是<b>部署/版本</b>问题，
     * 要在编译期就明确报出来，而不是让 null 在运行时某处炸成 NPE。
     */
    public NodeExecutor get(String type) {
        NodeExecutor executor = executors.get(type);
        if (executor == null) {
            throw exception(WORKFLOW_NODE_EXECUTOR_NOT_FOUND, type);
        }
        return executor;
    }

    public boolean supports(String type) {
        return executors.containsKey(type);
    }

}
