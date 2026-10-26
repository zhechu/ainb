package cn.enlev.ainb.module.agent.service.config;

import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.agent.dal.dataobject.config.AgentConfigDO;
import cn.enlev.ainb.module.agent.dal.mysql.config.AgentConfigMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AI 助手租户级配置 Service 实现类
 *
 * @author 走进新时代
 */
@Service
@Slf4j
public class AgentConfigServiceImpl implements AgentConfigService {

    @Resource
    private AgentConfigMapper agentConfigMapper;

    @Override
    public AgentConfigDO getConfig() {
        // ⚠️ 不能用 AgentConfigMapper#selectCurrent —— 那个方法带
        //    enabled = true 的过滤条件（它服务的是「能不能用」的判定）。
        //    配置页要的是「当前配成了什么」，关着的时候也得读得出来，
        //    否则一旦关掉，页面上就再也看不到备注、也不知道是谁关的
        List<AgentConfigDO> list = agentConfigMapper.selectList(
                new LambdaQueryWrapperX<AgentConfigDO>().orderByAsc(AgentConfigDO::getId));
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public void updateEnabled(Boolean enabled, String remark) {
        AgentConfigDO config = getConfig();
        if (config == null) {
            config = new AgentConfigDO();
            config.setEnabled(Boolean.TRUE.equals(enabled));
            config.setRemark(remark);
            agentConfigMapper.insert(config);
            log.info("[updateEnabled][AI 助手首次配置，启用({})]", enabled);
            return;
        }
        AgentConfigDO update = new AgentConfigDO();
        update.setId(config.getId());
        update.setEnabled(Boolean.TRUE.equals(enabled));
        update.setRemark(remark);
        agentConfigMapper.updateById(update);
        log.info("[updateEnabled][AI 助手开关变更为({})]", enabled);
    }

}
