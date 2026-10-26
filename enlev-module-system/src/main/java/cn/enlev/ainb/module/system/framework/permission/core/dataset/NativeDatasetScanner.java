package cn.enlev.ainb.module.system.framework.permission.core.dataset;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.biz.system.permission.annotation.PermissionDataset;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 扫描原生模块上的 {@link PermissionDataset}（S7a）
 *
 * <h3>为什么用 classpath 扫描而不是从 Spring 容器里找</h3>
 *
 * 注解标在 DO 上，而 DO <b>不是 Bean</b>，容器里根本没有它。
 * 要么把注解改标在 Service 上（那样表名与列名就离开了它们的定义处，
 * 迟早和 DO 漂移），要么扫 classpath。选后者。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class NativeDatasetScanner {

    /**
     * 扫描范围。只扫业务模块，不扫框架层 —— 框架层没有业务表
     */
    private static final String BASE_PACKAGE = "cn.enlev.ainb.module";

    /**
     * 扫描出所有原生数据集声明
     *
     * @return 按权限标识去重后的声明列表
     */
    public List<NativeDatasetDescriptor> scan() {
        Map<String, NativeDatasetDescriptor> byPermission = new LinkedHashMap<>();
        // false = 不带 Spring 默认的「必须是 @Component」过滤器，
        // 否则 DO 一个都扫不出来
        ClassPathScanningCandidateComponentProvider provider =
                new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AnnotationTypeFilter(PermissionDataset.class));
        for (BeanDefinition definition : provider.findCandidateComponents(BASE_PACKAGE)) {
            String className = definition.getBeanClassName();
            if (className == null) {
                continue;
            }
            try {
                Class<?> clazz = Class.forName(className);
                NativeDatasetDescriptor descriptor = parse(clazz);
                if (descriptor == null) {
                    continue;
                }
                NativeDatasetDescriptor old = byPermission.putIfAbsent(
                        descriptor.permission(), descriptor);
                if (old != null) {
                    // 两个类声明同一个数据集：后一个会被忽略，而被忽略的那份里
                    // 可能写着不同的槽位列 —— 不告警的话表现是「槽位配了没生效」
                    log.error("[scan][数据集({})被重复声明：{} 与 {}，只采用前者]",
                            descriptor.permission(), old.sourceClass(), descriptor.sourceClass());
                }
            } catch (Throwable ex) {
                // 单个类加载失败不能拖垮整轮扫描，但必须留痕：
                // 静默跳过的表现是「这张表的行级权限从来不生效」
                log.error("[scan][加载类({})失败，其数据集声明本次未登记]", className, ex);
            }
        }
        return new ArrayList<>(byPermission.values());
    }

    /**
     * 解析单个类上的声明
     *
     * @return 缺权限串或表名时返回 null（并告警）
     */
    private NativeDatasetDescriptor parse(Class<?> clazz) {
        PermissionDataset annotation = clazz.getAnnotation(PermissionDataset.class);
        if (annotation == null) {
            return null;
        }
        String permission = StrUtil.trimToNull(annotation.value());
        if (permission == null) {
            log.error("[parse][类({})的 @PermissionDataset 没写权限标识，跳过]", clazz.getName());
            return null;
        }
        // 表名留空时读 @TableName —— 让声明尽量贴着定义处，不必抄一遍表名
        String table = StrUtil.trimToNull(annotation.table());
        if (table == null) {
            TableName tableName = clazz.getAnnotation(TableName.class);
            table = tableName != null ? StrUtil.trimToNull(tableName.value()) : null;
        }
        if (table == null) {
            log.error("[parse][数据集({})既没写 table、类({})上也没有 @TableName，跳过 ——"
                    + "没有表名就注册不进行级规则，配了规则也不会被调用]", permission, clazz.getName());
            return null;
        }
        Map<String, String> slots = new LinkedHashMap<>();
        putSlot(slots, "owner", annotation.owner());
        putSlot(slots, "dept", annotation.dept());
        putSlot(slots, "status", annotation.status());
        // bizKey 有默认值 id，所以这一条几乎总会写进去 —— 见注解上的说明
        putSlot(slots, "bizKey", annotation.bizKey());

        List<String> columns = new ArrayList<>();
        for (String column : annotation.columns()) {
            String trimmedCol = StrUtil.trimToNull(column);
            if (trimmedCol != null && !columns.contains(trimmedCol)) {
                columns.add(trimmedCol);
            }
        }
        List<String> fields = new ArrayList<>();
        for (String field : annotation.fields()) {
            String trimmed = StrUtil.trimToNull(field);
            if (trimmed != null && !fields.contains(trimmed)) {
                fields.add(trimmed);
            }
        }
        Map<String, String> labels = new LinkedHashMap<>();
        for (String entry : annotation.labels()) {
            if (StrUtil.isBlank(entry)) {
                continue;
            }
            int eq = entry.indexOf('=');
            if (eq <= 0 || eq == entry.length() - 1) {
                // 静默忽略的表现是「资源名还是英文」且无从查起，必须留痕
                log.error("[parse][数据集({})的 labels 条目({})格式非法，正确格式为 字段名=中文名，已忽略]",
                        permission, entry);
                continue;
            }
            labels.put(StrUtil.trim(entry.substring(0, eq)), StrUtil.trim(entry.substring(eq + 1)));
        }
        return new NativeDatasetDescriptor(permission,
                StrUtil.blankToDefault(annotation.name(), permission),
                table.toLowerCase(), slots, fields, columns, labels, annotation.sensitive(), clazz.getName());
    }

    private void putSlot(Map<String, String> slots, String slot, String column) {
        String trimmed = StrUtil.trimToNull(column);
        if (trimmed != null) {
            slots.put(slot, trimmed.toLowerCase());
        }
    }

    /**
     * 一条原生数据集声明
     *
     * @param permission  数据集权限标识
     * @param name        展示名
     * @param table       物理表名（小写）
     * @param slots       槽位名 → 列名（小写）
     * @param fields      需要字段级控制的字段
     * @param labels      字段名 → 中文展示名（来自注解的 labels，可为空 Map）
     * @param sensitive   字段是否默认不可见
     * @param sourceClass 声明它的类，仅用于日志与排障
     */
    public record NativeDatasetDescriptor(String permission, String name, String table,
                                          Map<String, String> slots, List<String> fields,
                                          List<String> columns, Map<String, String> labels,
                                          boolean sensitive, String sourceClass) {
    }

}
