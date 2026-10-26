package cn.enlev.ainb.framework.datapermission.config;

import cn.enlev.ainb.framework.common.biz.system.permission.PermissionCommonApi;
import cn.enlev.ainb.framework.datapermission.core.aop.DataPermissionAnnotationAdvisor;
import cn.enlev.ainb.framework.datapermission.core.db.DataPermissionRuleHandler;
import cn.enlev.ainb.framework.datapermission.core.field.PermissionFieldCropAspect;
import cn.enlev.ainb.framework.datapermission.core.field.PermissionFieldHelper;
import cn.enlev.ainb.framework.datapermission.core.rule.DataPermissionRule;
import cn.enlev.ainb.framework.datapermission.core.rule.DataPermissionRuleFactory;
import cn.enlev.ainb.framework.datapermission.core.rule.DataPermissionRuleFactoryImpl;
import cn.enlev.ainb.framework.mybatis.core.util.MyBatisUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * 数据权限的自动配置类
 *
 * @author 芋道源码
 */
@AutoConfiguration
public class EnlevDataPermissionAutoConfiguration {

    @Bean
    public DataPermissionRuleFactory dataPermissionRuleFactory(List<DataPermissionRule> rules) {
        return new DataPermissionRuleFactoryImpl(rules);
    }

    @Bean
    public DataPermissionRuleHandler dataPermissionRuleHandler(MybatisPlusInterceptor interceptor,
                                                               DataPermissionRuleFactory ruleFactory) {
        // 创建 DataPermissionInterceptor 拦截器
        DataPermissionRuleHandler handler = new DataPermissionRuleHandler(ruleFactory);
        DataPermissionInterceptor inner = new DataPermissionInterceptor(handler);
        // 添加到 interceptor 中
        // 需要加在首个，主要是为了在分页插件前面。这个是 MyBatis Plus 的规定
        MyBatisUtils.addInterceptor(interceptor, inner, 0);
        return handler;
    }

    @Bean
    public DataPermissionAnnotationAdvisor dataPermissionAnnotationAdvisor() {
        return new DataPermissionAnnotationAdvisor();
    }

    // ========== 字段级权限（S7a：原生模块接入）==========

    /**
     * ⚠️ 用 {@link ObjectProvider} 注入 {@code PermissionCommonApi}
     *
     * 本 starter 被所有业务模块依赖，而权限实现在 {@code enlev-module-system}。
     * 直接注入的话，任何没装配 system 的场景（单测、裁剪过的部署）都会启动失败。
     * 拿不到实现时的行为是「不裁剪 + ERROR 日志」，见 {@code PermissionFieldHelper}
     */
    @Bean
    public PermissionFieldHelper permissionFieldHelper(
            ObjectProvider<PermissionCommonApi> permissionApiProvider) {
        return new PermissionFieldHelper(permissionApiProvider);
    }

    @Bean
    public PermissionFieldCropAspect permissionFieldCropAspect(PermissionFieldHelper fieldHelper) {
        return new PermissionFieldCropAspect(fieldHelper);
    }

}
