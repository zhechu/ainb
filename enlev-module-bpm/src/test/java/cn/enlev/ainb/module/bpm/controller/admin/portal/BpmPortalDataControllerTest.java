package cn.enlev.ainb.module.bpm.controller.admin.portal;

import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.bpm.controller.admin.task.vo.instance.BpmProcessInstanceCopyPageReqVO;
import cn.enlev.ainb.module.bpm.controller.admin.task.vo.instance.BpmProcessInstancePageReqVO;
import cn.enlev.ainb.module.bpm.controller.admin.task.vo.task.BpmTaskPageReqVO;
import cn.enlev.ainb.module.bpm.dal.dataobject.task.BpmProcessInstanceCopyDO;
import cn.enlev.ainb.module.bpm.enums.task.BpmProcessInstanceStatusEnum;
import cn.enlev.ainb.module.bpm.service.task.BpmProcessInstanceCopyService;
import cn.enlev.ainb.module.bpm.service.task.BpmProcessInstanceService;
import cn.enlev.ainb.module.bpm.service.task.BpmTaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BpmPortalDataControllerTest extends BaseMockitoUnitTest {

    private static final Long USER_ID = 100L;

    @InjectMocks
    private BpmPortalDataController controller;

    @Mock
    private BpmTaskService taskService;
    @Mock
    private BpmProcessInstanceService processInstanceService;
    @Mock
    private BpmProcessInstanceCopyService processInstanceCopyService;

    @Test
    void getTasks_todo_shouldMapTaskAndProcessName() {
        Task task = mock(Task.class);
        when(task.getId()).thenReturn("task-1");
        when(task.getProcessInstanceId()).thenReturn("process-1");
        when(task.getName()).thenReturn("审批任务");
        when(task.getCreateTime()).thenReturn(new Date(1756866600000L));

        ProcessInstance processInstance = mock(ProcessInstance.class);
        when(processInstance.getName()).thenReturn("请假流程");

        when(taskService.getTaskTodoPage(eq(USER_ID), any(BpmTaskPageReqVO.class)))
                .thenReturn(new PageResult<>(List.of(task), 1L));
        when(processInstanceService.getProcessInstanceMap(Set.of("process-1")))
                .thenReturn(Map.of("process-1", processInstance));

        try (MockedStatic<SecurityFrameworkUtils> security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(USER_ID);

            CommonResult<List<PortalTaskItemRespVO>> result = controller.getTasks("todo", 7);

            assertTrue(result.isSuccess());
            assertEquals(1, result.getData().size());
            PortalTaskItemRespVO response = result.getData().get(0);
            assertEquals("task-1", response.getId());
            assertEquals("task-1", response.getTaskId());
            assertEquals("process-1", response.getProcessInstanceId());
            assertEquals("审批任务", response.getName());
            assertEquals("请假流程", response.getProcessName());
            assertEquals("2025-09-03T10:30", response.getCreateTime().toString());
        }
    }

    @Test
    void getTasks_todo_whenNoTasks_shouldReturnEmptyList() {
        when(taskService.getTaskTodoPage(eq(USER_ID), any(BpmTaskPageReqVO.class)))
                .thenReturn(PageResult.empty());

        try (MockedStatic<SecurityFrameworkUtils> security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(USER_ID);

            CommonResult<List<PortalTaskItemRespVO>> result = controller.getTasks("todo", 7);

            assertTrue(result.isSuccess());
            assertTrue(result.getData().isEmpty());
        }
    }

    @Test
    void getTasks_doing_shouldUseRunningStatusAndClampPageSize() {
        HistoricProcessInstance processInstance = mock(HistoricProcessInstance.class);
        when(processInstance.getId()).thenReturn("process-2");
        when(processInstance.getName()).thenReturn("采购流程");
        when(processInstance.getStartTime()).thenReturn(new Date(1756866600000L));
        when(processInstanceService.getProcessInstancePage(eq(USER_ID), any(BpmProcessInstancePageReqVO.class)))
                .thenReturn(new PageResult<>(List.of(processInstance), 1L));

        try (MockedStatic<SecurityFrameworkUtils> security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(USER_ID);

            CommonResult<List<PortalTaskItemRespVO>> result = controller.getTasks("doing", 0);

            assertTrue(result.isSuccess());
            assertEquals("process-2", result.getData().get(0).getProcessInstanceId());
            assertEquals("采购流程", result.getData().get(0).getProcessName());

            ArgumentCaptor<BpmProcessInstancePageReqVO> requestCaptor =
                    ArgumentCaptor.forClass(BpmProcessInstancePageReqVO.class);
            verify(processInstanceService).getProcessInstancePage(eq(USER_ID), requestCaptor.capture());
            assertEquals(1, requestCaptor.getValue().getPageSize());
            assertEquals(BpmProcessInstanceStatusEnum.RUNNING.getStatus(), requestCaptor.getValue().getStatus());
        }
    }

    @Test
    void getSummary_shouldReturnAllPortalCounters() {
        when(taskService.getTaskPendingSignCount(USER_ID)).thenReturn(2L);
        when(taskService.getTaskTodoPage(eq(USER_ID), any(BpmTaskPageReqVO.class)))
                .thenReturn(new PageResult<>(List.of(), 3L));
        when(taskService.getTaskDonePage(eq(USER_ID), any(BpmTaskPageReqVO.class)))
                .thenReturn(new PageResult<HistoricTaskInstance>(List.of(), 4L));
        when(processInstanceService.getProcessInstancePage(eq(USER_ID), any(BpmProcessInstancePageReqVO.class)))
                .thenReturn(new PageResult<HistoricProcessInstance>(List.of(), 5L));
        when(processInstanceCopyService.getProcessInstanceCopyPage(
                eq(USER_ID), any(BpmProcessInstanceCopyPageReqVO.class)))
                .thenReturn(new PageResult<BpmProcessInstanceCopyDO>(List.of(), 6L));

        try (MockedStatic<SecurityFrameworkUtils> security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(USER_ID);

            CommonResult<PortalTaskSummaryRespVO> result = controller.getSummary();

            assertTrue(result.isSuccess());
            assertEquals(2L, result.getData().getPendingSign());
            assertEquals(3L, result.getData().getPendingTodo());
            assertEquals(5L, result.getData().getRunning());
            assertEquals(4L, result.getData().getCompleted());
            assertEquals(6L, result.getData().getCopied());
        }
    }
}
