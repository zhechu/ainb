package cn.enlev.ainb.module.agent.framework.agent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AgentDocProperties.class, AgentChatProperties.class,
        AgentPlanProperties.class, AgentIntentProperties.class, AgentUsageProperties.class,
        AgentLoopProperties.class, AgentWebhookProperties.class, AgentSanitizeProperties.class})
public class AgentConfiguration {
}
