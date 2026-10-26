package cn.enlev.ainb.module.agent.service.workflow;

import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentNodeExecutionDO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowExecutionDO;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowExecutionPageReqVO;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import lombok.Data;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * Workflow 执行 Service
 *
 * <h3>为什么是流式（SSE）</h3>
 * 一个流程可能跑几秒到几分钟（含 llm 调用、hitl 等待）。非流式意味着用户对着转圈干等，
 * 看不到「现在跑到哪个节点了」。逐节点推进度，与助手聊天走同一套 SSE 形态
 *（{@code Flux<CommonResult<Event>>} + {@code Sinks} + {@code boundedElastic}，
 * 见 AgentAssistantServiceImpl 的类注释，本 Service 复用同一条投递纪律）。
 *
 * <h3>🔴 身份快照必须在 Servlet 线程捕获</h3>
 * 与助手一致：Controller 在还在请求线程上时 {@code AgentExecutionContext.snapshot()}，
 * 传进来。节点执行、trace 落库、状态回写都靠它贴回租户身份。
 *
 * @author 走进新时代
 */
public interface AgentWorkflowExecutionService {

    /**
     * 按已发布定义的 code 启动一次执行
     *
     * @param code    定义 code
     * @param input   初始变量（会写进 state；触发器输入变量名见 DSL trigger.inputVar）
     * @param context Servlet 线程上捕获的身份快照
     * @return 事件流
     */
    Flux<WorkflowEvent> run(String code, Map<String, Object> input, AgentExecutionContext context);

    /**
     * 恢复一个处于 INTERRUPTED 的执行（hitl 确认后）
     *
     * @param executionId 执行实例 id
     * @param feedback    人工反馈（如 {@code {hitl_approved: true}}），经 updateState 写进 checkpoint
     * @param context     身份快照
     * @return 事件流（从断点继续推进度）
     */
    Flux<WorkflowEvent> resume(Long executionId, Map<String, Object> feedback, AgentExecutionContext context);

    /**
     * 中止一条正在跑（或挂起）的执行，落终态 {@code CANCELLED}（迭代七 B3-2）
     *
     * <h3>🔴 这是「账面语义」，不是线程中断 —— 必须先说清，否则调用方会误判副作用</h3>
     * 图跑在 {@code Schedulers.boundedElastic().schedule(...)} 里、内部又 {@code blockLast()} 阻塞，
     * 那个 Disposable 在 {@code buildEventFlux} 中<b>没有被留下</b>（见该方法注释）。所以本方法
     * <b>停不下正在执行的那个节点</b>：它会自然跑完，只是它跑完后的收尾被
     * {@code AgentWorkflowExecutionMapper#updateByIdUnlessCancelled} 挡在 {@code CANCELLED} 之外，
     * 库里那一行不会被改回 SUCCEEDED/FAILED。
     *
     * <p>⚠️ 后果：已经发出去的<b>外部副作用收不回来</b>（llm 已扣费、写库已落、审批已发）。
     * 本方法<b>刻意不触发补偿</b>—— 补偿是「失败后反做」，而用户主动中止不是失败
     * （触发补偿等于把用户想停的东西再跑一遍，正是 B3-1 要修的行为错）。
     * 需要反做请另走人工处置，别指望 cancel 顺手做。
     *
     * <h3>🔴 为什么不能取消 COMPENSATING</h3>
     * 那一档正在逆序调反做工具，中途判它「已停」等于宣称撤销完成而其实撤销做了一半 ——
     * 半撤销比卡死更难查（卡死看得见，半撤销看着像成功）。故 {@code COMPENSATING} 不在可取消集合里，
     * 撞上来抛 {@code WORKFLOW_EXECUTION_STATE_INVALID}，把真实状态告诉调用方。
     *
     * <h3>🔴 与 INTERRUPTED 的区别：本状态<b>不可恢复</b></h3>
     * {@code resume} 的 {@code isResumable} 守卫只认 INTERRUPTED，{@code CANCELLED} 不在其中。
     * 裁定 A-Ⅰ：本轮<b>只做</b>「取消不再判 FAILED、不再触发补偿、不再进告警面」，
     * <b>不做</b>「取消转可恢复」。要重跑得从头 {@code run} 一次（checkpoint 里的 state 仍在，
     * 但没有对外暴露「从 CANCELLED 续跑」的入口）。
     *
     * @param executionId 执行实例 id
     * @param context     Servlet 线程捕获的身份快照（用于 requireIdentity 守卫与租户隔离）
     * @throws cn.enlev.ainb.framework.common.exception.ServiceException 执行不存在
     *         （{@code WORKFLOW_EXECUTION_NOT_EXISTS}）；或已是终态 / 正在补偿
     *         （{@code WORKFLOW_EXECUTION_STATE_INVALID}，消息里带<b>当时真实</b>的状态，不猜）
     */
    void cancel(Long executionId, AgentExecutionContext context);

    /**
     * 查执行实例（校验归属）
     */
    AgentWorkflowExecutionDO getExecution(Long executionId, AgentExecutionContext context);

    /**
     * 查某次执行的节点级 trace
     */
    List<AgentNodeExecutionDO> getNodeTraces(Long executionId, AgentExecutionContext context);

    /**
     * 管理页分页查执行历史（增量8 / P4）：按定义/状态过滤，<b>本租户全量</b>
     *
     * <p>🔴 与「用户只看自己」的执行历史是不同信任级的视图：本方法供管理页（权限 {@code agent:workflow:query}）
     * 浏览本租户所有流程的执行，故不按 userId 收窄。租户隔离由 {@code TenantBaseDO} 自动保证——
     * 「全量」指本租户全量，跨租户绝不可见。详见 {@code AgentWorkflowExecutionMapper.selectPage} 注释。
     *
     * @param pageReqVO 过滤条件（workflowDefId / status，皆可空）
     * @param context   Servlet 线程捕获的身份快照（与其它执行读接口一致，用于 requireIdentity 守卫）
     */
    PageResult<AgentWorkflowExecutionDO> getExecutionPage(AgentWorkflowExecutionPageReqVO pageReqVO,
                                                          AgentExecutionContext context);

    /**
     * 发布外部事件，唤醒本租户下所有等待该 eventKey 的挂起执行（增量4b，wait(waitType=event) 的唤醒入口）
     *
     * <p>「广播」语义：匹配 {@code status=INTERRUPTED 且 event_key=eventKey} 的执行（可 0/1/N 条），逐条以
     * <b>行身份</b>（执行所有者）{@code resume}，把 {@code payload} 经 updateState 写进 checkpoint 后续跑。
     * fire-and-forget：返回「命中并派发」的条数即 ack，<b>不等图跑完</b>；要确认结果需另查 {@link #getExecution}。
     *
     * <p>🔴 {@code payload} 里想被恢复后下游节点读到的键，必须在该 def 的 {@code variables[]} 声明
     * （SAA resume 重载只保留已注册 KeyStrategy 的键）。并发/重复发布由 {@code resume} 的 CAS 认领去重。
     *
     * @param eventKey 事件键（匹配 wait(event) 节点的 eventKey）
     * @param payload  事件载荷（作为 resume 的 feedback 写进 checkpoint，可为 null）
     * @param context  发布者身份快照（Servlet 线程捕获，用于租户隔离）
     * @return 命中并派发恢复的执行条数
     */
    int publishEvent(String eventKey, Map<String, Object> payload, AgentExecutionContext context);

    /**
     * 事件类型
     *
     * <p>与助手一样走「类型 + 数据」单通道，前端 switch 的 default 能兜住未知类型。
     */
    enum EventType {

        /** 执行元信息，流的第一个事件（带 executionId，刷新页面后靠它找回执行） */
        META,

        /** 一个节点执行完成（带 nodeId 与当时的 state 投影） */
        NODE,

        /** 流程在 hitl 节点前挂起，等待人工确认 */
        INTERRUPTED,

        /** 正常跑到 END */
        DONE,

        /** 出错（可读原因），刻意做成正常事件而非断连，理由同助手 ERROR */
        ERROR
    }

    /**
     * 一个 SSE 事件
     */
    @Data
    class WorkflowEvent {

        private EventType type;
        private Long executionId;
        private String threadName;
        private String nodeId;

        /**
         * 节点完成时的 state 投影 / DONE 时的最终输出
         */
        private Map<String, Object> state;

        private String errorMsg;

        public static WorkflowEvent meta(Long executionId, String threadName) {
            WorkflowEvent e = new WorkflowEvent();
            e.type = EventType.META;
            e.executionId = executionId;
            e.threadName = threadName;
            return e;
        }

        public static WorkflowEvent node(Long executionId, String nodeId, Map<String, Object> state) {
            WorkflowEvent e = new WorkflowEvent();
            e.type = EventType.NODE;
            e.executionId = executionId;
            e.nodeId = nodeId;
            e.state = state;
            return e;
        }

        public static WorkflowEvent interrupted(Long executionId, String nodeId) {
            WorkflowEvent e = new WorkflowEvent();
            e.type = EventType.INTERRUPTED;
            e.executionId = executionId;
            e.nodeId = nodeId;
            return e;
        }

        public static WorkflowEvent done(Long executionId, Map<String, Object> state) {
            WorkflowEvent e = new WorkflowEvent();
            e.type = EventType.DONE;
            e.executionId = executionId;
            e.state = state;
            return e;
        }

        public static WorkflowEvent error(Long executionId, String errorMsg) {
            WorkflowEvent e = new WorkflowEvent();
            e.type = EventType.ERROR;
            e.executionId = executionId;
            e.errorMsg = errorMsg;
            return e;
        }
    }

}
