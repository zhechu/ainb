package cn.enlev.ainb.module.infra.framework.datamodel.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DataModelProperties.class)
public class DataModelConfiguration {
}
