package cn.enlev.ainb.module.agent.service.config;

import cn.enlev.ainb.module.agent.dal.dataobject.config.AgentConfigDO;

/**
 * AI 助手租户级配置 Service
 *
 * @author 走进新时代
 */
public interface AgentConfigService {

    /**
     * 取当前租户的配置
     *
     * @return 配置；从未配置过时返回 null（等同于未启用）
     */
    AgentConfigDO getConfig();

    /**
     * 开启或关闭 AI 助手
     *
     * <h3>为什么是 upsert 而不是 update</h3>
     * 「从未配置过」与「配置过但关着」在判定上是同一件事（都不放行），
     * 但在操作上不是：管理员第一次打开开关时，库里根本没有那一行。
     * 要求他先「新增配置」再「启用」是没有意义的仪式。
     *
     * @param enabled 是否启用
     * @param remark  备注，记录开启的缘由与范围
     */
    void updateEnabled(Boolean enabled, String remark);

}
