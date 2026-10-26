package cn.enlev.ainb.module.bpm.service.definition;

import cn.enlev.ainb.module.bpm.dal.dataobject.definition.BpmDelegationDO;

import java.util.List;
import java.util.Set;

/**
 * 流程委托 Service 接口
 *
 * <h3>晚绑定：只在「查待办」与「审批鉴权」两处叠加</h3>
 *
 * 委托<b>不改变任务归属</b>（理由见 {@link BpmDelegationDO} 的类注释）。
 * 所以本服务只提供一个核心能力：
 *
 * <blockquote>
 * 「现在有谁把事情委托给了我」 → {@link #getDelegatorUserIds}
 * </blockquote>
 *
 * 调用方拿到这组委托人后，把他们的待办并进自己的列表、
 * 把他们的任务也算作自己可审批。
 *
 * @author 走进新时代
 */
public interface BpmDelegationService {

    /**
     * 查「谁把事情委托给了我」（当前有效的）
     *
     * @param delegateUserId       受托人（当前登录用户）
     * @param processDefinitionKey 流程定义 key；为空表示不限流程，
     *                             此时限定了流程的委托也会被返回（理由见实现类注释）
     * @return 委托人编号集合
     */
    Set<Long> getDelegatorUserIds(Long delegateUserId, String processDefinitionKey);

    /**
     * 判断某人是否把指定流程的事务委托给了我
     *
     * 审批鉴权用。与 {@link #getDelegatorUserIds} 的区别是这里<b>必须</b>传准 key
     */
    boolean isDelegatedBy(Long delegateUserId, Long delegatorUserId, String processDefinitionKey);

    /**
     * 查我发出的委托
     */
    List<BpmDelegationDO> getDelegationListByDelegator(Long delegatorUserId);

    Long createDelegation(BpmDelegationDO delegation);

    /**
     * 提前终止委托
     *
     * ⚠️ 到期失效<b>不需要</b>调这个 —— 有效期判定在查询时做，到点自动不生效。
     * 这正是晚绑定省掉的那一次「扫一遍改回来」
     */
    void cancelDelegation(Long id);

}
