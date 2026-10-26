package cn.enlev.ainb.framework.datapermission.core.field;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.biz.system.permission.PermissionCommonApi;
import cn.enlev.ainb.framework.common.biz.system.permission.dto.DatasetFieldModeRespDTO;
import cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 原生模块的字段级权限 helper（S7a）
 *
 * <h3>三个口径与在线表单保持一致，不能各写一套</h3>
 *
 * <ol>
 *   <li><b>查不到资源行 = 放行</b>。字段资源是按需创建的，绝大多数字段本来就没有行。
 *       写反的表现是「所有接口的所有字段全部清空」</li>
 *   <li><b>权限模块不可用 = 放行 + ERROR 日志</b>。此时字段权限是关着的，
 *       必须留痕，否则又是一次静默失效</li>
 *   <li><b>无登录上下文 = 不限制</b>。走到这里的只有内部调用（定时任务、MQ、流程引擎回写），
 *       结果不会流向用户；真正的匿名请求在 Spring Security 那层已被挡住。
 *       判成 HIDDEN 的话，写路径会把提交值全丢光 —— 这个坑在
 *       {@code FormFieldCropper} 上真实发生过（TODO #23）</li>
 * </ol>
 *
 * @author 走进新时代
 */
@Slf4j
public class PermissionFieldHelper {

    private final ObjectProvider<PermissionCommonApi> permissionApiProvider;

    private final AtomicBoolean missingWarned = new AtomicBoolean(false);
    private final AtomicBoolean noLoginWarned = new AtomicBoolean(false);

    /**
     * 字段缓存：同一个 VO 类会被反复裁，每次都遍历一遍类层次太浪费
     */
    private final Map<Class<?>, Map<String, Field>> fieldCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    public PermissionFieldHelper(ObjectProvider<PermissionCommonApi> permissionApiProvider) {
        this.permissionApiProvider = permissionApiProvider;
    }

    // ==================== 判定 ====================

    /**
     * 解析一批字段的显示模式
     *
     * @param datasetPermission 数据集权限标识，如 {@code system:user}
     * @param column            true = 列表列，false = 详情字段
     */
    public DatasetFieldModeRespDTO resolve(String datasetPermission, Collection<String> fieldKeys,
                                           boolean column) {
        if (StrUtil.isBlank(datasetPermission) || fieldKeys == null || fieldKeys.isEmpty()) {
            return DatasetFieldModeRespDTO.unrestricted();
        }
        PermissionCommonApi api = permissionApiProvider.getIfAvailable();
        if (api == null) {
            if (missingWarned.compareAndSet(false, true)) {
                log.error("[resolve][PermissionCommonApi 不可用，原生模块的字段级权限当前不生效！"
                        + "若非单元测试环境，请检查 enlev-module-system 是否已装配]");
            }
            return DatasetFieldModeRespDTO.unrestricted();
        }
        try {
            Long loginUserId = SecurityFrameworkUtils.getLoginUserId();
            if (loginUserId == null) {
                if (noLoginWarned.compareAndSet(false, true)) {
                    log.warn("[resolve][无登录上下文，数据集({})的字段级权限本次不生效。"
                            + "这对定时任务/内部调用是预期行为]", datasetPermission);
                }
                return DatasetFieldModeRespDTO.unrestricted();
            }
            DatasetFieldModeRespDTO result = api.getFieldModes(
                    loginUserId, datasetPermission, fieldKeys, column);
            return result == null ? DatasetFieldModeRespDTO.unrestricted() : result;
        } catch (Exception ex) {
            // 判定出错按放行处理：把异常变成「全部字段清空」会让一次偶发故障
            // 表现成大面积数据丢失，那比少裁一次严重得多
            log.error("[resolve][数据集({})的字段模式解析失败，本次不裁剪]", datasetPermission, ex);
            return DatasetFieldModeRespDTO.unrestricted();
        }
    }

    // ==================== 裁剪 ====================

    /**
     * 裁剪一个返回对象（支持单对象、集合、{@code PageResult}）
     *
     * @param data              返回的数据
     * @param datasetPermission 数据集权限标识
     * @param fields            参与判定的字段名，空表示取对象的全部属性
     */
    public void crop(Object data, String datasetPermission, Collection<String> fields) {
        crop(data, datasetPermission, fields, false);
    }

    /**
     * @param column true=按列级(:col:)裁剪（列表接口），false=按字段级(:fld:)裁剪（详情接口）
     */
    public void crop(Object data, String datasetPermission, Collection<String> fields, boolean column) {
        List<Object> items = flatten(data);
        if (items.isEmpty()) {
            return;
        }
        Set<String> fieldKeys = new LinkedHashSet<>();
        if (fields != null && !fields.isEmpty()) {
            fieldKeys.addAll(fields);
        } else {
            // 未指定时取第一个元素的属性名。取「第一个」而不是逐个取并集 ——
            // 同一个接口返回的元素是同一个类，并集与它相等，而逐个取是 O(n) 次内省
            fieldKeys.addAll(fields(items.get(0).getClass()).keySet());
        }
        DatasetFieldModeRespDTO modes = resolve(datasetPermission, fieldKeys, column);
        Set<String> hidden = new LinkedHashSet<>();
        for (String fieldKey : fieldKeys) {
            if (modes.isHidden(fieldKey)) {
                hidden.add(fieldKey);
            }
        }
        if (hidden.isEmpty()) {
            return;
        }
        for (Object item : items) {
            clear(item, hidden);
        }
    }

    /**
     * 丢弃不可写字段的提交值（写路径）
     *
     * 与在线表单的 {@code stripNonWritable} 同一件事：前端的 disabled 不是安全边界，
     * 抓包照样能提交。HIDDEN 与 READONLY 的字段一律置空 ——
     * 置空后按「调用方自己读出原值再合并」的语义处理。
     *
     * @return 被丢弃的字段名
     */
    public Set<String> stripNonWritable(Object target, String datasetPermission,
                                        Collection<String> fields) {
        Set<String> stripped = new LinkedHashSet<>();
        if (target == null) {
            return stripped;
        }
        Set<String> fieldKeys = new LinkedHashSet<>();
        if (fields != null && !fields.isEmpty()) {
            fieldKeys.addAll(fields);
        } else {
            fieldKeys.addAll(fields(target.getClass()).keySet());
        }
        // 写保护判的是字段级（:fld:）——「能不能改这个字段」与列表列显隐无关
        DatasetFieldModeRespDTO modes = resolve(datasetPermission, fieldKeys, false);
        for (String fieldKey : fieldKeys) {
            if (modes.isHidden(fieldKey) || modes.isReadonly(fieldKey)) {
                stripped.add(fieldKey);
            }
        }
        if (stripped.isEmpty()) {
            return stripped;
        }
        clear(target, stripped);
        return stripped;
    }

    // ==================== 反射 ====================

    /**
     * 把返回值摊平成「要裁剪的对象列表」
     *
     * {@code CommonResult} 由切面先剥掉，这里只认 {@code PageResult} / 集合 / 单对象
     */
    @SuppressWarnings("unchecked")
    private List<Object> flatten(Object data) {
        List<Object> result = new java.util.ArrayList<>();
        if (data == null) {
            return result;
        }
        if (data instanceof cn.enlev.ainb.framework.common.pojo.PageResult<?> page) {
            if (page.getList() != null) {
                page.getList().forEach(item -> addIfCroppable(result, item));
            }
            return result;
        }
        if (data instanceof Collection<?> collection) {
            collection.forEach(item -> addIfCroppable(result, item));
            return result;
        }
        if (data instanceof Map<?, ?> map) {
            ((Map<String, Object>) map).values().forEach(item -> addIfCroppable(result, item));
            return result;
        }
        addIfCroppable(result, data);
        return result;
    }

    private void addIfCroppable(List<Object> result, Object item) {
        // 基本类型与字符串没有属性可裁，放进去只会白做一次内省
        if (item == null || item instanceof CharSequence || item instanceof Number
                || item instanceof Boolean || item.getClass().isPrimitive()) {
            return;
        }
        result.add(item);
    }

    private void clear(Object target, Collection<String> fieldKeys) {
        Map<String, Field> fields = fields(target.getClass());
        for (String fieldKey : fieldKeys) {
            Field field = fields.get(fieldKey);
            if (field == null) {
                continue;
            }
            if (field.getType().isPrimitive()) {
                // 基本类型置不了 null。留着原值比塞一个 0/false 好 ——
                // 后者是在编造数据，而字段权限该做的是「不给」，不是「给个假的」
                log.warn("[clear][{}.{} 是基本类型，无法置空，本次未裁剪 ——"
                                + "要对它做字段权限请改成包装类型]",
                        target.getClass().getSimpleName(), fieldKey);
                continue;
            }
            try {
                field.set(target, null);
            } catch (Exception ex) {
                log.error("[clear][置空 {}.{} 失败，该字段的值仍会返回]",
                        target.getClass().getSimpleName(), fieldKey, ex);
            }
        }
    }

    /**
     * 取一个类的全部实例字段（含父类），字段名 → Field
     *
     * <h3>🔴 为什么直接反射字段，而不用 {@code PropertyDescriptor.getWriteMethod()}</h3>
     *
     * 本项目根目录的 {@code lombok.config} 里写着 {@code lombok.accessors.chain=true} ——
     * 所有 Lombok 生成的 setter <b>返回 {@code this} 而不是 void</b>。
     * {@code java.beans.Introspector} 按 JavaBeans 规范只认返回 void 的 setter，
     * 于是它对本项目的<b>每一个 VO</b> 都报告「没有写方法」。
     *
     * 用写方法实现的话，表现是：注解标了、判定跑了、日志干净、
     * 而<b>字段一个都没被裁掉</b> —— 又一次「配了、成功了、没生效、没提示」。
     * 这个坑是靠单测撞出来的（{@code PermissionFieldHelperTest}），
     * 改回写方法实现会立刻复现。
     *
     * 直接写字段还顺带对 record 之外的各种风格（手写 setter、Builder、无 setter 的 DTO）都成立。
     */
    private Map<String, Field> fields(Class<?> clazz) {
        return fieldCache.computeIfAbsent(clazz, key -> {
            Map<String, Field> result = new LinkedHashMap<>();
            for (Class<?> current = key; current != null && current != Object.class;
                 current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                        continue;
                    }
                    // 子类字段优先：父类同名字段被遮蔽时，写子类那个才是对的
                    if (result.containsKey(field.getName())) {
                        continue;
                    }
                    try {
                        field.setAccessible(true);
                        result.put(field.getName(), field);
                    } catch (Exception ex) {
                        log.error("[fields][{}.{} 无法访问，该字段的字段权限不生效]",
                                current.getName(), field.getName(), ex);
                    }
                }
            }
            return result;
        });
    }

}
