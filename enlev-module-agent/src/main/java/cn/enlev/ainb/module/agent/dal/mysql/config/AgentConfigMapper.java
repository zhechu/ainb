package cn.enlev.ainb.module.agent.dal.mysql.config;

import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.module.agent.dal.dataobject.config.AgentConfigDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentConfigMapper extends BaseMapperX<AgentConfigDO> {

    /**
     * 查当前租户的配置
     *
     * 不用传 tenantId：多租户拦截器会自动拼上，这也是为什么 DO 必须继承 TenantBaseDO。
     */
    default AgentConfigDO selectCurrent() {
        return selectOne(AgentConfigDO::getEnabled, true);
    }

}
