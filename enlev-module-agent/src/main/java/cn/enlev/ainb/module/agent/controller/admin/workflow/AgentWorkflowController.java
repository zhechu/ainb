package cn.enlev.ainb.module.agent.controller.admin.workflow;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentNodeExecutionRespVO;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowEventReqVO;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowExecutionPageReqVO;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowExecutionRespVO;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowResumeReqVO;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowRunReqVO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentNodeExecutionDO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowExecutionDO;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.service.workflow.AgentWorkflowExecutionService;
import cn.enlev.ainb.module.agent.service.workflow.AgentWorkflowExecutionService.WorkflowEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

import static cn.enlev.ainb.framework.common.exception.enums.GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR;
import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;

/**
 * Workflow 执行接口
 *
 * <h3>权限：副作用面 {@code agent:workflow:run} / 只读面 {@code agent:workflow:query}（增量14a 拆分）</h3>
 * {@code run-stream/resume-stream/event} 会真实启动/推进执行、消耗 LLM token、落执行与计量记录，
 * 属副作用面，统一 {@code agent:workflow:run}；{@code execution/get、execution/page、node-trace/list}
 * 只读查询，保留 {@code agent:workflow:query}。如此「能看执行历史」与「能发起执行」分离——运维排障角色
 * 可只给 query 而不给 run。租户隔离仍由框架（{@code AgentExecutionContext} + 拦截器）兜底。
 * 对应 type=3 菜单按钮行 + 角色授权见 {@code sql/mysql/xpy-2026-09-27-agent-fine-grained-perms.sql}。
 *
 * <h3>🔴 {@code cancel} 为什么单开一档权限、而不并进 {@code run}</h3>
 * 取消与发起是<b>两个方向</b>的权力：发起会花 token、造数据；取消会<b>掐掉别人正在跑的东西</b>。
 * 排障角色典型形态是「能看、能停，但不该随手起流程」，把 cancel 塞进 run 就逼着管理员二选一
 * （要么给 run 让人能起流程，要么不给、连带没人能停）。故单列 {@code agent:workflow:cancel}，
 * 授权脚本见 {@code sql/mysql/xpy-2026-10-07-agent-workflow-cancel-perm.sql}。
 *
 * <h3>🔴 SSE 接口的异常必须在 Controller 接住转成流元素</h3>
 * 完全照搬 AgentAssistantController#sendStream 的处理与理由：客户端 Accept 是 text/event-stream，
 * 同步段抛出的异常若冒到全局处理器会因内容协商失败落到错误页，用户看到「账号未登录」而真实原因全丢。
 *
 * @author 走进新时代
 */
@Tag(name = "管理后台 - Workflow 执行")
@Slf4j
@RestController
@RequestMapping("/agent/workflow")
@Validated
public class AgentWorkflowController {

    @Resource
    private AgentWorkflowExecutionService executionService;

    @Operation(summary = "启动 Workflow 执行（流式）")
    @PostMapping(value = "/run-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@ss.hasPermission('agent:workflow:run')")
    public Flux<CommonResult<WorkflowEvent>> runStream(@Valid @RequestBody AgentWorkflowRunReqVO reqVO) {
        // 🔴 快照必须在 Servlet 线程上捕获（此刻 ThreadLocal 里有身份），晚一步就可能是 null
        AgentExecutionContext context = AgentExecutionContext.snapshot();
        try {
            return executionService.run(reqVO.getCode(), reqVO.getInput(), context)
                    .map(CommonResult::success);
        } catch (ServiceException ex) {
            log.info("[runStream][请求被拒绝，错误码({}) 原因({})]", ex.getCode(), ex.getMessage());
            return Flux.just(CommonResult.error(ex.getCode(), ex.getMessage()));
        } catch (Exception ex) {
            log.error("[runStream][请求处理异常]", ex);
            return Flux.just(CommonResult.error(INTERNAL_SERVER_ERROR.getCode(),
                    "Workflow 启动失败：" + ex.getMessage()));
        }
    }

    @Operation(summary = "恢复 Workflow 执行（流式，hitl 确认后）")
    @PostMapping(value = "/resume-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@ss.hasPermission('agent:workflow:run')")
    public Flux<CommonResult<WorkflowEvent>> resumeStream(@Valid @RequestBody AgentWorkflowResumeReqVO reqVO) {
        AgentExecutionContext context = AgentExecutionContext.snapshot();
        try {
            return executionService.resume(reqVO.getExecutionId(), reqVO.getFeedback(), context)
                    .map(CommonResult::success);
        } catch (ServiceException ex) {
            log.info("[resumeStream][请求被拒绝，错误码({}) 原因({})]", ex.getCode(), ex.getMessage());
            return Flux.just(CommonResult.error(ex.getCode(), ex.getMessage()));
        } catch (Exception ex) {
            log.error("[resumeStream][请求处理异常]", ex);
            return Flux.just(CommonResult.error(INTERNAL_SERVER_ERROR.getCode(),
                    "Workflow 恢复失败：" + ex.getMessage()));
        }
    }

    @Operation(summary = "发布外部事件，唤醒等待该事件的 Workflow 执行")
    @PostMapping("/event")
    @PreAuthorize("@ss.hasPermission('agent:workflow:run')")
    public CommonResult<Integer> publishEvent(@Valid @RequestBody AgentWorkflowEventReqVO reqVO) {
        // 普通 JSON 端点（非 SSE）：不套 Flux catch，ServiceException 交全局处理器转 CommonResult.error。
        // 身份在 Servlet 线程捕获，用于租户隔离（事件只唤醒同租户的挂起执行）。
        AgentExecutionContext context = AgentExecutionContext.snapshot();
        return success(executionService.publishEvent(reqVO.getEventKey(), reqVO.getPayload(), context));
    }

    @Operation(summary = "中止 Workflow 执行（落终态 CANCELLED，不反做、不告警）")
    @PostMapping("/cancel")
    @Parameter(name = "executionId", description = "执行实例编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('agent:workflow:cancel')")
    public CommonResult<Boolean> cancelExecution(@RequestParam("executionId") Long executionId) {
        // 普通 JSON 端点（非 SSE）：不套 Flux catch，ServiceException 交全局处理器转 CommonResult.error。
        // 🔴 身份仍在 Servlet 线程捕获：cancel 要在 ambient 租户上下文里查/改那一行，
        //    跨租户的 executionId 由拦截器自然查不到 → WORKFLOW_EXECUTION_NOT_EXISTS（不泄露存在性）。
        AgentExecutionContext context = AgentExecutionContext.snapshot();
        executionService.cancel(executionId, context);
        return success(true);
    }

    @Operation(summary = "获得执行实例")
    @GetMapping("/execution/get")
    @Parameter(name = "id", description = "执行实例编号", required = true)
    @PreAuthorize("@ss.hasPermission('agent:workflow:query')")
    public CommonResult<AgentWorkflowExecutionRespVO> getExecution(@RequestParam("id") Long id) {
        AgentWorkflowExecutionDO exec = executionService.getExecution(id, AgentExecutionContext.snapshot());
        return success(BeanUtils.toBean(exec, AgentWorkflowExecutionRespVO.class));
    }

    @Operation(summary = "获得执行实例分页（管理页执行历史，本租户全量）")
    @GetMapping("/execution/page")
    @PreAuthorize("@ss.hasPermission('agent:workflow:query')")
    public CommonResult<PageResult<AgentWorkflowExecutionRespVO>> getExecutionPage(
            @Valid AgentWorkflowExecutionPageReqVO pageReqVO) {
        PageResult<AgentWorkflowExecutionDO> pageResult =
                executionService.getExecutionPage(pageReqVO, AgentExecutionContext.snapshot());
        return success(BeanUtils.toBean(pageResult, AgentWorkflowExecutionRespVO.class));
    }

    @Operation(summary = "获得执行的节点级 trace 列表")
    @GetMapping("/node-trace/list")
    @Parameter(name = "executionId", description = "执行实例编号", required = true)
    @PreAuthorize("@ss.hasPermission('agent:workflow:query')")
    public CommonResult<List<AgentNodeExecutionRespVO>> getNodeTraceList(
            @RequestParam("executionId") Long executionId) {
        List<AgentNodeExecutionDO> traces =
                executionService.getNodeTraces(executionId, AgentExecutionContext.snapshot());
        return success(BeanUtils.toBean(traces, AgentNodeExecutionRespVO.class));
    }

}
