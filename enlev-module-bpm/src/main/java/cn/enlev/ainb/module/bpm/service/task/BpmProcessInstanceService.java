package cn.enlev.ainb.module.bpm.service.task;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.module.bpm.api.task.dto.BpmProcessInstanceCreateReqDTO;
import cn.enlev.ainb.module.bpm.controller.admin.task.vo.instance.*;
import jakarta.validation.Valid;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.enlev.ainb.framework.common.util.collection.CollectionUtils.convertMap;

/**
 * 流程实例 Service 接口
 *
 * @author 芋道源码
 */
public interface BpmProcessInstanceService {

    // ========== Query 查询相关方法 ==========

    /**
     * 获得流程实例
     *
     * @param id 流程实例的编号
     * @return 流程实例
     */
    ProcessInstance getProcessInstance(String id);

    /**
     * 获得流程实例列表
     *
     * @param ids 流程实例的编号集合
     * @return 流程实例列表
     */
    List<ProcessInstance> getProcessInstances(Set<String> ids);

    /**
     * 获得流程实例 Map
     *
     * @param ids 流程实例的编号集合
     * @return 流程实例列表 Map
     */
    default Map<String, ProcessInstance> getProcessInstanceMap(Set<String> ids) {
        return convertMap(getProcessInstances(ids), ProcessInstance::getProcessInstanceId);
    }

    /**
     * 获得历史的流程实例
     *
     * @param id 流程实例的编号
     * @return 历史的流程实例
     */
    HistoricProcessInstance getHistoricProcessInstance(String id);

    /**
     * 获得历史的流程实例列表
     *
     * @param ids 流程实例的编号集合
     * @return 历史的流程实例列表
     */
    List<HistoricProcessInstance> getHistoricProcessInstances(Set<String> ids);

    /**
     * 获得历史的流程实例 Map
     *
     * @param ids 流程实例的编号集合
     * @return 历史的流程实例列表 Map
     */
    default Map<String, HistoricProcessInstance> getHistoricProcessInstanceMap(Set<String> ids) {
        return convertMap(getHistoricProcessInstances(ids), HistoricProcessInstance::getId);
    }

    /**
     * 获得流程实例的分页
     *
     * @param userId    用户编号
     * @param pageReqVO 分页请求
     * @return 流程实例的分页
     */
    PageResult<HistoricProcessInstance> getProcessInstancePage(Long userId,
                                                               @Valid BpmProcessInstancePageReqVO pageReqVO);

    /**
     * 获取审批详情。
     * <p>
     * 可以是准备发起的流程、进行中的流程、已经结束的流程
     *
     * @param loginUserId  登录人的用户编号
     * @param reqVO 请求信息
     * @return 流程实例的进度
     */
    BpmApprovalDetailRespVO getApprovalDetail(Long loginUserId, @Valid BpmApprovalDetailReqVO reqVO);

    /**
     * 获取下一个执行节点信息
     *
     * @param loginUserId 登录人的用户编号
     * @param reqVO 请求信息
     * @return 下一个执行节点信息
     */
    List<BpmApprovalDetailRespVO.ActivityNode> getNextApprovalNodes(Long loginUserId, @Valid BpmApprovalDetailReqVO reqVO);

    /**
     * 获取流程实例的 BPMN 模型视图
     *
     * @param id 流程实例的编号
     * @return BPMN 模型视图
     */
    BpmProcessInstanceBpmnModelViewRespVO getProcessInstanceBpmnModelView(String id);

    /**
     * 校验当前用户能否查看该流程实例，不能则抛出异常
     *
     * <h3>为什么必须有</h3>
     *
     * 查看类端点此前只有 {@code @PreAuthorize("@ss.hasPermission('bpm:process-instance:query')")}
     * 这一道静态权限串，<b>没有任何「你是否与这条实例相关」的判定</b>。
     * 而这个权限串是每个普通用户都必须有的（否则看不到自己提交的单子）——
     * 于是任何登录用户换一个 id，就能读到别人的请假、报销、薪资审批，含完整表单数据。
     *
     * <h3>⚠️ 这是临时实现</h3>
     *
     * 权限重构的 S5b 阶段会建统一的「流程参与人表」，届时本方法改为查那张表，
     * 并把「参与」这件事变成可诊断、可派生数据权限的一等公民。
     * 在那之前，这里逐项查 Flowable 的历史任务与抄送表，查询次数偏多但正确。
     * 详见 {@code docs/permission-rebuild/S0-交接.md}。
     *
     * @param userId            用户编号
     * @param processInstanceId 流程实例编号。为空时直接放行（未发起的场景）
     */
    void validateProcessInstanceViewable(Long userId, String processInstanceId);

    /**
     * 当前用户能否查看该流程实例
     *
     * 判定口径见 {@link #validateProcessInstanceViewable(Long, String)}。
     *
     * @param userId            用户编号
     * @param processInstanceId 流程实例编号
     * @return 是否可见
     */
    boolean canUserViewProcessInstance(Long userId, String processInstanceId);

    // ========== Update 写入相关方法 ==========

    /**
     * 创建流程实例（提供给前端）
     *
     * @param userId      用户编号
     * @param createReqVO 创建信息
     * @return 实例的编号
     */
    String createProcessInstance(Long userId, @Valid BpmProcessInstanceCreateReqVO createReqVO);

    /**
     * 创建流程实例（提供给内部）
     *
     * @param userId       用户编号
     * @param createReqDTO 创建信息
     * @return 实例的编号
     */
    String createProcessInstance(Long userId, @Valid BpmProcessInstanceCreateReqDTO createReqDTO);

    /**
     * 发起人取消流程实例
     *
     * @param userId      用户编号
     * @param cancelReqVO 取消信息
     */
    void cancelProcessInstanceByStartUser(Long userId, @Valid BpmProcessInstanceCancelReqVO cancelReqVO);

    /**
     * 管理员取消流程实例
     *
     * @param userId      用户编号
     * @param cancelReqVO 取消信息
     */
    void cancelProcessInstanceByAdmin(Long userId, BpmProcessInstanceCancelReqVO cancelReqVO);

    /**
     * 更新 ProcessInstance 为不通过
     *
     * @param processInstance 流程实例
     * @param reason          理由。例如说，审批不通过时，需要传递该值
     */
    void updateProcessInstanceReject(ProcessInstance processInstance, String reason);

    /**
     * 更新 ProcessInstance 的变量
     *
     * @param id 流程编号
     * @param variables 流程变量
     */
    void updateProcessInstanceVariables(String id, Map<String, Object> variables);

    /**
     * 删除 ProcessInstance 的变量
     *
     * @param id  流程编号
     * @param variableNames 流程变量名
     */
    void removeProcessInstanceVariables(String id, Collection<String> variableNames);

    // ========== Event 事件相关方法 ==========

    /**
     * 处理 ProcessInstance 完成事件，例如说：审批通过、不通过、取消
     *
     * @param instance 流程任务
     */
    void processProcessInstanceCompleted(ProcessInstance instance);

    /**
     * 处理 ProcessInstance 开始事件，例如说：流程前置通知
     *
     * @param instance 流程任务
     */
    void processProcessInstanceCreated(ProcessInstance instance);
}
