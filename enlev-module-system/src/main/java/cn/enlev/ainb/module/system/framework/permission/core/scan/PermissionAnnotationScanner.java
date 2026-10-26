package cn.enlev.ainb.module.system.framework.permission.core.scan;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 Spring 容器里扫描所有 @PreAuthorize("@ss.hasPermission('...')") 注解，
 * 提取权限串集合
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class PermissionAnnotationScanner {

    /**
     * 匹配 @ss.hasPermission('xxx') 或 @ss.hasPermission("xxx") 里的权限串
     */
    private static final Pattern PERMISSION_PATTERN =
            Pattern.compile("@ss\\.hasPermission\\(['\"]([^'\"]+)['\"]\\)");

    @Resource
    private ApplicationContext applicationContext;

    /**
     * 扫描 Spring 容器里所有 Controller Bean 的方法，提取权限串
     */
    public Set<String> scan() {
        Set<String> permissions = new LinkedHashSet<>();
        // 只扫描有 @RestController 或 @RequestMapping 的 Bean，减少无效遍历
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(RestController.class);
        beans.putAll(applicationContext.getBeansWithAnnotation(RequestMapping.class));

        for (Object bean : beans.values()) {
            Class<?> clazz = bean.getClass();
            // 剥掉 CGLIB 代理
            if (clazz.getName().contains("$$")) {
                clazz = clazz.getSuperclass();
            }
            for (Method method : clazz.getDeclaredMethods()) {
                PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
                if (preAuthorize == null) {
                    continue;
                }
                String value = preAuthorize.value();
                Matcher matcher = PERMISSION_PATTERN.matcher(value);
                while (matcher.find()) {
                    String permission = matcher.group(1).trim();
                    if (StrUtil.isNotBlank(permission)) {
                        permissions.add(permission);
                    }
                }
            }
        }
        log.debug("[PermissionAnnotationScanner][扫描完成，共发现 {} 个权限串]", permissions.size());
        return permissions;
    }

}
