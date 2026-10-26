package cn.enlev.ainb.module.system.framework.permission.runner;

import cn.enlev.ainb.module.system.dal.dataobject.permission.RuleDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.RuleMapper;
import cn.enlev.ainb.module.system.enums.permission.RuleTemplateEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 内置规则模板种子 Runner（S3c）
 *
 * 应用启动时把 8 个内置模板的描述行写进 sys_rule，
 * 这样管理员在授权页面的规则下拉框里能直接看到和选择内置模板。
 * 幂等：按 template 字段判重，已存在的跳过。
 *
 * @author 走进新时代
 */
@Component
@Order(4)
@Slf4j
public class BuiltInRuleSeedRunner implements ApplicationRunner {

    @Resource
    private RuleMapper ruleMapper;

    @Override
    public void run(ApplicationArguments args) {
        log.info("[BuiltInRuleSeedRunner][开始写入内置规则模板种子]");
        int created = 0;
        for (RuleTemplateEnum template : RuleTemplateEnum.values()) {
            if (!template.isBuiltIn()) {
                continue;
            }
            List<RuleDO> existing = ruleMapper.selectListByTemplate(template.getCode());
            if (!existing.isEmpty()) {
                continue;
            }
            RuleDO rule = new RuleDO();
            rule.setName(template.getDisplayName());
            rule.setTemplate(template.getCode());
            rule.setDescription(template.getExpression());
            rule.setStatus(0);
            ruleMapper.insert(rule);
            created++;
        }
        log.info("[BuiltInRuleSeedRunner][完成，新建 {} 条内置模板种子]", created);
    }

}
