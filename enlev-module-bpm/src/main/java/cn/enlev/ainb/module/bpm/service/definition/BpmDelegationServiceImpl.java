package cn.enlev.ainb.module.bpm.service.definition;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.bpm.dal.dataobject.definition.BpmDelegationDO;
import cn.enlev.ainb.module.bpm.dal.mysql.definition.BpmDelegationMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 流程委托 Service 实现
 *
 * @author 走进新时代
 */
@Service
@Slf4j
public class BpmDelegationServiceImpl implements BpmDelegationService {

    @Resource
    private BpmDelegationMapper delegationMapper;

    @Override
    public Set<Long> getDelegatorUserIds(Long delegateUserId, String processDefinitionKey) {
        if (delegateUserId == null) {
            return Set.of();
        }
        List<BpmDelegationDO> delegations =
                delegationMapper.selectActiveListByDelegateUserId(delegateUserId, LocalDateTime.now());
        if (CollUtil.isEmpty(delegations)) {
            return Set.of();
        }
        Set<Long> result = new LinkedHashSet<>();
        for (BpmDelegationDO delegation : delegations) {
            if (matchesProcess(delegation, processDefinitionKey)) {
                result.add(delegation.getDelegatorUserId());
            }
        }
        return result;
    }

    @Override
    public boolean isDelegatedBy(Long delegateUserId, Long delegatorUserId, String processDefinitionKey) {
        if (delegateUserId == null || delegatorUserId == null) {
            return false;
        }
        return getDelegatorUserIds(delegateUserId, processDefinitionKey).contains(delegatorUserId);
    }

    @Override
    public List<BpmDelegationDO> getDelegationListByDelegator(Long delegatorUserId) {
        return delegatorUserId == null ? List.of()
                : delegationMapper.selectListByDelegatorUserId(delegatorUserId);
    }

    @Override
    public Long createDelegation(BpmDelegationDO delegation) {
        delegation.setStatus(0);
        if (delegation.getStartTime() == null) {
            delegation.setStartTime(LocalDateTime.now());
        }
        delegationMapper.insert(delegation);
        return delegation.getId();
    }

    @Override
    public void cancelDelegation(Long id) {
        BpmDelegationDO update = new BpmDelegationDO();
        update.setId(id);
        update.setStatus(1);
        delegationMapper.updateById(update);
    }

    /**
     * 判断某条委托是否覆盖指定流程
     *
     * <h3>⚠️ 流程 key 为空时放行，这一点必须想清楚</h3>
     *
     * 调用方在两种场景下传不出 key：
     * <ul>
     *   <li><b>查待办列表</b> —— 一次查的是多个流程的任务，没有单一 key</li>
     *   <li>拿不到流程定义（数据异常）</li>
     * </ul>
     *
     * 前者是常态，若在这里判否，限定了流程的委托在待办列表里会<b>一条都不生效</b>，
     * 而用户完全看不出为什么。所以这里放行，由调用方在拿得到 key 的地方
     * （审批鉴权）做精确判定 —— 那才是真正的访问控制点。
     *
     * 也就是说：列表可能多显示几条，但<b>点进去办不了</b>会被明确拒绝。
     * 反过来（列表少显示、点进去能办）才是真正难查的问题。
     */
    private boolean matchesProcess(BpmDelegationDO delegation, String processDefinitionKey) {
        if (StrUtil.isBlank(delegation.getProcessDefinitionKeys())) {
            return true; // 不限流程
        }
        if (StrUtil.isBlank(processDefinitionKey)) {
            return true; // 见方法注释
        }
        return StrUtil.split(delegation.getProcessDefinitionKeys(), ',').stream()
                .map(StrUtil::trim)
                .anyMatch(key -> key.equals(processDefinitionKey));
    }

}
