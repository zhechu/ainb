package cn.enlev.ainb.module.bpm.controller.admin.task;

import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.module.bpm.service.task.BpmTaskCandidateSnapshotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;

/**
 * 任务候选人快照与离职交接
 *
 * @author 走进新时代
 */
@Tag(name = "管理后台 - 流程任务候选人快照")
@RestController
@RequestMapping("/bpm/task-candidate")
@Validated
public class BpmTaskCandidateSnapshotController {

    @Resource
    private BpmTaskCandidateSnapshotService candidateSnapshotService;

    @GetMapping("/recalculate")
    @Operation(summary = "离职交接：重算某用户名下的候选人身份",
            description = "⚠️ 只返回建议，不会自动改动任何任务。快照是既成事实，"
                    + "因为组织变了就悄悄把别人的待办挪走，表现是「我昨天看到的单子今天不见了」，"
                    + "比不处理更糟。真正的转办请人确认后走 /bpm/task/transfer")
    @Parameter(name = "userId", description = "离职/交接的用户编号", required = true)
    @PreAuthorize("@ss.hasPermission('bpm:task:manager-query')")
    public CommonResult<List<RecalculateRespVO>> recalculate(@RequestParam("userId") Long userId) {
        List<BpmTaskCandidateSnapshotService.RecalculateItem> items =
                candidateSnapshotService.recalculateByUser(userId);
        List<RecalculateRespVO> result = items.stream().map(item -> {
            RecalculateRespVO vo = new RecalculateRespVO();
            vo.setProcessInstanceId(item.snapshot().getProcessInstanceId());
            vo.setTaskId(item.snapshot().getTaskId());
            vo.setActivityId(item.snapshot().getActivityId());
            vo.setStrategy(item.snapshot().getStrategy());
            vo.setSubjectType(item.snapshot().getSubjectType());
            vo.setRecalculable(item.recalculable());
            vo.setSuggestedUserIds(item.suggestedUsers());
            vo.setReason(item.reason());
            return vo;
        }).toList();
        return success(result);
    }

    @Data
    @io.swagger.v3.oas.annotations.media.Schema(description = "管理后台 - 离职交接重算结果")
    public static class RecalculateRespVO {

        private String processInstanceId;
        private String taskId;
        private String activityId;
        /**
         * 候选策略，说明「当初按什么规则算的」
         */
        private Integer strategy;
        /**
         * 主体类型，说明「他为什么是审批人」
         */
        private String subjectType;
        /**
         * 能否自动算出接手人
         *
         * false 时 {@link #suggestedUserIds} 必为空，必须人工指定
         */
        private Boolean recalculable;
        private List<Long> suggestedUserIds;
        /**
         * 给人看的说明，解释为什么可以/不可以自动重算
         */
        private String reason;

    }

}
