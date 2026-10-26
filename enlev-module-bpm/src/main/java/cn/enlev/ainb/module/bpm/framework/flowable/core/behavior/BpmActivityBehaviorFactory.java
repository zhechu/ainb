package cn.enlev.ainb.module.bpm.framework.flowable.core.behavior;

import cn.enlev.ainb.module.bpm.framework.flowable.core.candidate.BpmTaskCandidateInvoker;
import cn.enlev.ainb.module.bpm.framework.permission.BpmSubProcessCallChecker;
import lombok.Setter;
import org.flowable.bpmn.model.Activity;
import org.flowable.bpmn.model.CallActivity;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.impl.bpmn.behavior.AbstractBpmnActivityBehavior;
import org.flowable.engine.impl.bpmn.behavior.CallActivityBehavior;
import org.flowable.engine.impl.bpmn.behavior.ParallelMultiInstanceBehavior;
import org.flowable.engine.impl.bpmn.behavior.SequentialMultiInstanceBehavior;
import org.flowable.engine.impl.bpmn.behavior.UserTaskActivityBehavior;
import org.flowable.engine.impl.bpmn.parser.factory.DefaultActivityBehaviorFactory;

/**
 * 自定义的 ActivityBehaviorFactory 实现类，目的如下：
 * 1. 自定义 {@link #createUserTaskActivityBehavior(UserTask)}：实现自定义的流程任务的 assignee 负责人的分配
 * 2. 自定义 {@link #createCallActivityBehavior(CallActivity)}：子流程调起授权（见 {@link BpmCallActivityBehavior}）
 *
 * @author 芋道源码
 */
@Setter
public class BpmActivityBehaviorFactory extends DefaultActivityBehaviorFactory {

    private BpmTaskCandidateInvoker taskCandidateInvoker;

    /**
     * 子流程调起授权校验器
     *
     * ⚠️ 必须在这一层挂，不能只挂在 {@code BpmCallActivityListener} 上 ——
     * 那个监听器只有钉钉式设计器生成的子流程节点才有，
     * 原生 BPMN 的 callActivity 走不到它（TODO #37）
     */
    private BpmSubProcessCallChecker subProcessCallChecker;

    @Override
    public UserTaskActivityBehavior createUserTaskActivityBehavior(UserTask userTask) {
        return new BpmUserTaskActivityBehavior(userTask)
                .setTaskCandidateInvoker(taskCandidateInvoker);
    }

    @Override
    public ParallelMultiInstanceBehavior createParallelMultiInstanceBehavior(Activity activity,
                                                                             AbstractBpmnActivityBehavior behavior) {
        return new BpmParallelMultiInstanceBehavior(activity, behavior)
                .setTaskCandidateInvoker(taskCandidateInvoker);
    }

    @Override
    public SequentialMultiInstanceBehavior createSequentialMultiInstanceBehavior(Activity activity,
                                                                                 AbstractBpmnActivityBehavior behavior) {
        return new BpmSequentialMultiInstanceBehavior(activity, behavior)
                .setTaskCandidateInvoker(taskCandidateInvoker);
    }

    @Override
    public CallActivityBehavior createCallActivityBehavior(CallActivity callActivity) {
        return new BpmCallActivityBehavior(callActivity, subProcessCallChecker);
    }

}
