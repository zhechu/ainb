package cn.enlev.ainb.module.bpm.framework.flowable.core.enums;

import org.flowable.engine.runtime.ProcessInstance;

/**
 * BPM Variable 通用常量
 *
 * @author 芋道源码
 */
public class BpmnVariableConstants {

    /**
     * 流程实例的变量 - 状态
     *
     * @see ProcessInstance#getProcessVariables()
     */
    public static final String PROCESS_INSTANCE_VARIABLE_STATUS = "PROCESS_STATUS";
    /**
     * 流程实例的变量 - 理由
     *
     * 例如说：审批不通过的理由（目前审核通过暂时不会记录）
     *
     * @see ProcessInstance#getProcessVariables()
     */
    public static final String PROCESS_INSTANCE_VARIABLE_REASON = "PROCESS_REASON";
    /**
     * 流程实例的变量 - 发起用户选择的审批人 Map
     *
     * @see ProcessInstance#getProcessVariables()
     * @see BpmTaskCandidateStrategyEnum#START_USER_SELECT
     */
    public static final String PROCESS_INSTANCE_VARIABLE_START_USER_SELECT_ASSIGNEES = "PROCESS_START_USER_SELECT_ASSIGNEES";
    /**
     * 流程实例的变量 - 审批人选择的审批人 Map
     *
     * @see ProcessInstance#getProcessVariables()
     * @see BpmTaskCandidateStrategyEnum#APPROVE_USER_SELECT
     */
    public static final String PROCESS_INSTANCE_VARIABLE_APPROVE_USER_SELECT_ASSIGNEES = "PROCESS_APPROVE_USER_SELECT_ASSIGNEES";
    /**
     * 流程实例的变量 - 发起用户 ID
     *
     * @see ProcessInstance#getProcessVariables()
     */
    public static final String PROCESS_INSTANCE_VARIABLE_START_USER_ID = "PROCESS_START_USER_ID";

    /**
     * 流程实例的变量 - 发起人【发起时】所属部门编号（发起组织快照，v9 §7.2）
     *
     * <h3>为什么要快照，而不是每次查当前部门</h3>
     *
     * 「发起人部门负责人审批」这类策略原本每次求值都查发起人的<b>当前</b>部门。
     * 于是：张三在研发部提了请假单，走到审批节点时他已调去市场部 ——
     * 单子就派给了<b>市场部</b>负责人。
     *
     * 而这张假是他在研发部休的、假期归研发部管，理应由研发部负责人批。
     * 现在的表现是「市场部负责人收到一张跟自己部门毫无关系的旧单子」，
     * 且全程不报错。
     *
     * <h3>⚠️ 快照锁的是「他当时属于哪个部门」，不是「那个部门归谁管」</h3>
     *
     * 部门<b>负责人是谁</b>仍然实时查 —— 任务是现在要办的，
     * 就该由现在在位的负责人办。派给一个已经不在位（甚至已离职）的人是错的。
     *
     * 这个区分是整件事的关键：一个锁住、一个不锁。
     */
    public static final String PROCESS_INSTANCE_VARIABLE_START_USER_DEPT_ID = "PROCESS_START_USER_DEPT_ID";

    /**
     * 流程实例的变量 - 用于判断流程实例变量节点是否驳回：格式 RETURN_FLAG_{节点 id}
     *
     * 目的是：退回到发起节点时，因为审批人与发起人相同，所以被自动通过。但是，此时还是希望不要自动通过
     *
     * @see ProcessInstance#getProcessVariables()
     */
    public static final String PROCESS_INSTANCE_VARIABLE_RETURN_FLAG = "RETURN_FLAG_%s";

    /**
     * 流程实例的变量 - 用于退回操作，记录需要预测的节点 ids, 变量值类型为 Set
     *
     * 目的是：退回操作，预测节点会不准，在流程变量中记录需要预测的节点，来辅助预测
     */
    public static final String PROCESS_INSTANCE_VARIABLE_NEED_SIMULATE_TASK_IDS = "NEED_SIMULATE_TASK_IDS";

    /**
     * 流程实例的变量 - 在线表单的业务主键：格式 ONLINE_FORM_PK_{表单编码}
     *
     * 为什么把主键放流程变量，而不是按 process_instance_id 反查业务表？
     * 因为 process_instance_id 是系统列、不在表单设计的 fieldMapping 里，
     * 无法作为 FormDesignRuntimeService 的查询条件；而流程变量零新增表、随流程实例天然隔离、
     * 与流程同事务，且不存在反查命中多行的隐患。
     *
     * 主表单的主键即流程的 businessKey，这里主要用于节点独立表单绑定了其它表的场景。
     *
     * @see ProcessInstance#getProcessVariables()
     */
    public static final String PROCESS_INSTANCE_VARIABLE_ONLINE_FORM_PK = "ONLINE_FORM_PK_%s";

    /**
     * 在线表单业务主键变量的前缀，用于在表单展示时过滤掉这些系统变量
     */
    public static final String PROCESS_INSTANCE_VARIABLE_ONLINE_FORM_PK_PREFIX = "ONLINE_FORM_PK_";

    /**
     * 流程实例的变量 - 是否跳过表达式
     *
     * @see ProcessInstance#getProcessVariables()
     * @see <a href="https://blog.csdn.net/weixin_42065235/article/details/126039993">Flowable/Activiti之SkipExpression 完成自动审批</a>
     */
    public static final String PROCESS_INSTANCE_SKIP_EXPRESSION_ENABLED = "_FLOWABLE_SKIP_EXPRESSION_ENABLED";

    /**
     * 流程实例的变量 - 用于判断流程是否需要跳过发起人节点
     *
     * @see ProcessInstance#getProcessVariables()
     */
    public static final String PROCESS_INSTANCE_VARIABLE_SKIP_START_USER_NODE = "PROCESS_SKIP_START_USER_NODE";

    /**
     * 流程实例的变量 - 流程开始时间
     *
     * 【非存储变量】用于部分需要 format 的场景，例如说：流程实例的自定义标题
     */
    public static final String PROCESS_START_TIME = "PROCESS_START_TIME";
    /**
     * 流程实例的变量 - 流程定义名称
     */
    public static final String PROCESS_DEFINITION_NAME = "PROCESS_DEFINITION_NAME";

    /**
     * 任务的变量 - 状态
     *
     * @see org.flowable.task.api.Task#getTaskLocalVariables()
     */
    public static final String TASK_VARIABLE_STATUS = "TASK_STATUS";
    /**
     * 任务的变量 - 理由
     *
     * 例如说：审批通过、不通过的理由
     *
     * @see org.flowable.task.api.Task#getTaskLocalVariables()
     */
    public static final String TASK_VARIABLE_REASON = "TASK_REASON";
    /**
     * 任务变量 - 签名图片 URL
     */
    public static final String TASK_SIGN_PIC_URL = "TASK_SIGN_PIC_URL";

}
