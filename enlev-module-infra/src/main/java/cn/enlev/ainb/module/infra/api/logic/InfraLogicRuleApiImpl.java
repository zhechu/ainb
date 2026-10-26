package cn.enlev.ainb.module.infra.api.logic;

import cn.enlev.ainb.module.infra.framework.formdesign.core.logic.LogicRule;
import cn.enlev.ainb.module.infra.framework.formdesign.core.logic.LogicRuleEvaluator;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;

/**
 * {@link InfraLogicRuleApi} 实现类
 *
 * <p>对 {@link LogicRuleEvaluator#matches} 的薄委托：把 Map 形态的条件组 convertValue 成
 * {@link LogicRule.ConditionGroup}，再交给那个与前端双端一致性测试绑定的求值器。
 * 求值逻辑本身<b>不在这里重写一份</b> —— 重写就是又造一处会与前端行为漂移的副本。
 *
 * @author 走进新时代
 */
@Service
@Slf4j
public class InfraLogicRuleApiImpl implements InfraLogicRuleApi {

    @Resource
    private LogicRuleEvaluator logicRuleEvaluator;
    @Resource
    private ObjectMapper objectMapper;

    @Override
    public boolean matchesConditionGroups(Map<String, Object> conditionGroups, Map<String, Object> data) {
        // 空条件组恒真，与 infra「when 为空视为恒真」一致
        if (conditionGroups == null || conditionGroups.isEmpty()) {
            return true;
        }
        LogicRule.ConditionGroup group =
                objectMapper.convertValue(conditionGroups, LogicRule.ConditionGroup.class);
        return logicRuleEvaluator.matches(group, data == null ? Collections.emptyMap() : data);
    }

}
