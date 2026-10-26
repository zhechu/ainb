package cn.enlev.ainb.module.bpm.api.permission;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.biz.bpm.BpmParticipantCommonApi;
import cn.enlev.ainb.module.bpm.dal.dataobject.task.BpmInstanceParticipantDO;
import cn.enlev.ainb.module.bpm.service.task.BpmInstanceParticipantService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 流程参与关系跨模块查询接口的实现
 *
 * @author 走进新时代
 */
@Service
@Validated
public class BpmParticipantCommonApiImpl implements BpmParticipantCommonApi {

    @Resource
    private BpmInstanceParticipantService instanceParticipantService;

    @Override
    public Set<String> getParticipatedBusinessKeys(Long userId, String formCode) {
        if (userId == null) {
            return Set.of();
        }
        List<BpmInstanceParticipantDO> participants =
                instanceParticipantService.getParticipantsByUserIdAndFormCode(userId, formCode);
        Set<String> keys = new LinkedHashSet<>();
        for (BpmInstanceParticipantDO participant : participants) {
            // business_key 允许为空：流程不一定挂在线表单上（formType=20 的原生表单就没有）。
            // 空值不能进集合 —— 进了会让 IN (...) 里多出一个 NULL，
            // 而 SQL 里 `col IN (NULL)` 恒不成立，反而把本该可见的行也挡掉
            if (StrUtil.isNotBlank(participant.getBusinessKey())) {
                keys.add(participant.getBusinessKey());
            }
        }
        return keys;
    }

}
