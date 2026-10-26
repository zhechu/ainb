package cn.enlev.ainb.module.bpm.controller.admin.portal;

import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.date.DateUtils;
import cn.enlev.ainb.module.bpm.controller.admin.task.vo.instance.BpmProcessInstanceCopyPageReqVO;
import cn.enlev.ainb.module.bpm.controller.admin.task.vo.instance.BpmProcessInstancePageReqVO;
import cn.enlev.ainb.module.bpm.controller.admin.task.vo.task.BpmTaskPageReqVO;
import cn.enlev.ainb.module.bpm.dal.dataobject.task.BpmProcessInstanceCopyDO;
import cn.enlev.ainb.module.bpm.enums.task.BpmProcessInstanceStatusEnum;
import cn.enlev.ainb.module.bpm.service.task.BpmProcessInstanceCopyService;
import cn.enlev.ainb.module.bpm.service.task.BpmProcessInstanceService;
import cn.enlev.ainb.module.bpm.service.task.BpmTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;
import static cn.enlev.ainb.framework.common.util.collection.CollectionUtils.convertSet;
import static cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

@Tag(name = "管理后台 - 门户流程数据")
@RestController
@RequestMapping("/bpm/portal")
@Validated
public class BpmPortalDataController {

    @Resource
    private BpmTaskService taskService;
    @Resource
    private BpmProcessInstanceService processInstanceService;
    @Resource
    private BpmProcessInstanceCopyService processInstanceCopyService;

    @GetMapping("/summary")
    @Operation(summary = "获得门户流程统计")
    @PreAuthorize("@ss.hasPermission('infra:portal:run')")
    public CommonResult<PortalTaskSummaryRespVO> getSummary() {
        Long userId = getLoginUserId();

        BpmTaskPageReqVO todoReq = pageRequest(1);
        BpmProcessInstancePageReqVO runningReq = processPageRequest(1);
        runningReq.setStatus(BpmProcessInstanceStatusEnum.RUNNING.getStatus());
        BpmProcessInstanceCopyPageReqVO copiedReq = new BpmProcessInstanceCopyPageReqVO();
        copiedReq.setPageNo(1);
        copiedReq.setPageSize(1);

        PageResult<Task> todo = taskService.getTaskTodoPage(userId, todoReq);
        PageResult<HistoricProcessInstance> running = processInstanceService.getProcessInstancePage(userId, runningReq);
        PageResult<BpmProcessInstanceCopyDO> copied =
                processInstanceCopyService.getProcessInstanceCopyPage(userId, copiedReq);
        PageResult<org.flowable.task.api.history.HistoricTaskInstance> done =
                taskService.getTaskDonePage(userId, pageRequest(1));

        return success(new PortalTaskSummaryRespVO()
                .setPendingSign(taskService.getTaskPendingSignCount(userId))
                .setPendingTodo(todo.getTotal())
                .setRunning(running.getTotal())
                .setCompleted(done.getTotal())
                .setCopied(copied.getTotal()));
    }

    @GetMapping("/tasks")
    @Operation(summary = "获得门户流程列表")
    @PreAuthorize("@ss.hasPermission('infra:portal:run')")
    public CommonResult<List<PortalTaskItemRespVO>> getTasks(
            @RequestParam(defaultValue = "todo") String view,
            @RequestParam(defaultValue = "7") Integer pageSize) {
        int size = Math.max(1, Math.min(50, pageSize == null ? 7 : pageSize));
        Long userId = getLoginUserId();
        if ("doing".equalsIgnoreCase(view)) {
            BpmProcessInstancePageReqVO req = processPageRequest(size);
            req.setStatus(BpmProcessInstanceStatusEnum.RUNNING.getStatus());
            PageResult<HistoricProcessInstance> page = processInstanceService.getProcessInstancePage(userId, req);
            return success(page.getList().stream()
                    .map(this::toRunningItem)
                    .toList());
        }

        PageResult<Task> page = taskService.getTaskTodoPage(userId, pageRequest(size));
        if (page.getList() == null || page.getList().isEmpty()) {
            return success(List.of());
        }
        Map<String, ProcessInstance> processMap = processInstanceService.getProcessInstanceMap(
                convertSet(page.getList(), Task::getProcessInstanceId));
        return success(page.getList().stream()
                .map(task -> toTodoItem(task, processMap.get(task.getProcessInstanceId())))
                .toList());
    }

    private BpmTaskPageReqVO pageRequest(int pageSize) {
        BpmTaskPageReqVO request = new BpmTaskPageReqVO();
        request.setPageNo(1);
        request.setPageSize(pageSize);
        return request;
    }

    private BpmProcessInstancePageReqVO processPageRequest(int pageSize) {
        BpmProcessInstancePageReqVO request = new BpmProcessInstancePageReqVO();
        request.setPageNo(1);
        request.setPageSize(pageSize);
        return request;
    }

    private PortalTaskItemRespVO toTodoItem(Task task, ProcessInstance processInstance) {
        return new PortalTaskItemRespVO()
                .setId(task.getId())
                .setTaskId(task.getId())
                .setName(task.getName())
                .setProcessInstanceId(task.getProcessInstanceId())
                .setProcessName(processInstance == null ? null : processInstance.getName())
                .setCreateTime(DateUtils.of(task.getCreateTime()));
    }

    private PortalTaskItemRespVO toRunningItem(HistoricProcessInstance processInstance) {
        return new PortalTaskItemRespVO()
                .setId(processInstance.getId())
                .setProcessInstanceId(processInstance.getId())
                .setName(processInstance.getName())
                .setProcessName(processInstance.getName())
                .setCreateTime(DateUtils.of(processInstance.getStartTime()));
    }

}
