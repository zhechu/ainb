package cn.enlev.ainb.module.bpm.framework.flowable.core.candidate.strategy.dept;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.enlev.ainb.module.bpm.framework.flowable.core.candidate.BpmTaskCandidateStrategy;
import cn.enlev.ainb.module.system.api.dept.DeptApi;
import cn.enlev.ainb.framework.common.util.number.NumberUtils;
import cn.enlev.ainb.module.bpm.framework.flowable.core.enums.BpmnVariableConstants;
import cn.enlev.ainb.module.system.api.dept.dto.DeptRespDTO;

import java.util.Map;
import cn.enlev.ainb.module.system.api.user.AdminUserApi;
import cn.enlev.ainb.module.system.api.user.dto.AdminUserRespDTO;
import jakarta.annotation.Resource;

import java.util.*;

/**
 * 部门的负责人 {@link BpmTaskCandidateStrategy} 抽象类
 *
 * @author jason
 */
@lombok.extern.slf4j.Slf4j
public abstract class AbstractBpmTaskCandidateDeptLeaderStrategy implements BpmTaskCandidateStrategy {

    @Resource
    protected DeptApi deptApi;
    @Resource
    protected AdminUserApi adminUserApi;

    /**
     * 获得指定层级的部门负责人，只有第 level 的负责人
     *
     * @param dept 指定部门
     * @param level 第几级
     * @return 部门负责人的编号
     */
    protected Long getAssignLevelDeptLeaderId(DeptRespDTO dept, Integer level) {
        Assert.isTrue(level > 0, "level 必须大于 0");
        if (dept == null) {
            return null;
        }
        DeptRespDTO currentDept = dept;
        for (int i = 1; i < level; i++) {
            DeptRespDTO parentDept = deptApi.getDept(currentDept.getParentId());
            if (parentDept == null) { // 找不到父级部门，到了最高级。返回最高级的部门负责人
                break;
            }
            currentDept = parentDept;
        }
        return currentDept.getLeaderUserId();
    }

    /**
     * 获得连续层级的部门负责人，包含 [1, level] 的负责人
     *
     * @param deptIds 指定部门编号数组
     * @param level 最大层级
     * @return 连续部门负责人 Id
     */
    protected Set<Long> getMultiLevelDeptLeaderIds(List<Long> deptIds, Integer level) {
        Assert.isTrue(level > 0, "level 必须大于 0");
        if (CollUtil.isEmpty(deptIds)) {
            return new HashSet<>();
        }
        Set<Long> deptLeaderIds = new LinkedHashSet<>(); // 保证有序
        for (Long deptId : deptIds) {
            DeptRespDTO dept = deptApi.getDept(deptId);
            for (int i = 0; i < level; i++) {
                if (dept.getLeaderUserId() != null) {
                    deptLeaderIds.add(dept.getLeaderUserId());
                }
                DeptRespDTO parentDept = deptApi.getDept(dept.getParentId());
                if (parentDept == null) { // 找不到父级部门. 已经到了最高层级了
                    break;
                }
                dept = parentDept;
            }
        }
        return deptLeaderIds;
    }

    /**
     * 获取发起人的部门
     *
     * ⚠️ 取的是发起人<b>当前</b>部门。中途调岗的场景请用
     * {@link #getStartUserDept(Long, java.util.Map)} —— 它优先读发起组织快照
     *
     * @param startUserId 发起人 Id
     */
    protected DeptRespDTO getStartUserDept(Long startUserId) {
        AdminUserRespDTO startUser = adminUserApi.getUser(startUserId);
        if (startUser == null || startUser.getDeptId() == null) { // 找不到部门
            return null;
        }
        return deptApi.getDept(startUser.getDeptId());
    }

    /**
     * 获取发起人<b>发起时</b>所属的部门（发起组织快照，v9 §7.2）
     *
     * <h3>为什么要锁住发起时的部门</h3>
     *
     * 张三在研发部提了请假单，走到「发起人部门负责人审批」节点时他已调去市场部。
     * 查当前部门的话，单子就派给了<b>市场部</b>负责人 —— 而这张假是他在研发部休的、
     * 假期归研发部管，理应由研发部负责人批。
     * 现在的表现是「市场部负责人收到一张跟自己部门毫无关系的旧单子」，且不报错。
     *
     * <h3>⚠️ 锁的是「他当时属于哪个部门」，不锁「那个部门现在归谁管」</h3>
     *
     * 部门负责人是谁仍由调用方实时查 —— 任务是<b>现在</b>要办的，
     * 就该由<b>现在</b>在位的负责人办。派给一个已经不在位（甚至已离职）的人是错的。
     *
     * <h3>⚠️ 读不到快照时回落查当前部门</h3>
     *
     * 本快照是 S6d 之后才写入的，<b>存量流程实例的变量里没有它</b>。
     * 回落保证那些实例的行为与升级前完全一致 —— 直接判空的话，
     * 所有在办的老流程会在升级瞬间找不到审批人
     *
     * @param startUserId      发起人 Id
     * @param processVariables 流程变量；为空时直接回落
     */
    protected DeptRespDTO getStartUserDept(Long startUserId, Map<String, Object> processVariables) {
        Object snapshotDeptId = processVariables == null ? null
                : processVariables.get(BpmnVariableConstants.PROCESS_INSTANCE_VARIABLE_START_USER_DEPT_ID);
        // ⚠️ 不能写成 NumberUtils.parseLong(String.valueOf(snapshotDeptId))——
        // String.valueOf(null) 得到的是字面量 "null"，parseLong 会抛 NumberFormatException，
        // 于是【没有快照】这个最常见的情形（所有存量实例）反而直接炸掉
        Long deptId = snapshotDeptId == null ? null
                : NumberUtils.parseLong(snapshotDeptId.toString());
        if (deptId != null) {
            DeptRespDTO dept = deptApi.getDept(deptId);
            if (dept != null) {
                return dept;
            }
            // 快照里的部门被删了 —— 回落查当前，总比找不到审批人强
            log.warn("[getStartUserDept][发起组织快照里的部门({})已不存在，回落查当前部门]", deptId);
        }
        return getStartUserDept(startUserId);
    }

}
