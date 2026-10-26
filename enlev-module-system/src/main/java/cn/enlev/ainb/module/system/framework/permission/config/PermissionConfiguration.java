package cn.enlev.ainb.module.system.framework.permission.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 权限体系的配置类
 *
 * @author 走进新时代
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PermissionProperties.class)
public class PermissionConfiguration {
}
