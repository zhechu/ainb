package cn.enlev.ainb.module.infra.framework.formdesign.core.permission;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.biz.system.permission.PermissionCommonApi;
import cn.enlev.ainb.framework.common.biz.system.permission.dto.DatasetFieldModeRespDTO;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormResourceDescriptorDTO;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在线表单的列/字段级裁剪
 *
 * <h3>三个出口必须一起裁</h3>
 *
 * {@code config}（渲染配置）、{@code get}（详情）、{@code page}（列表）任缺其一都是漏洞：
 * 只裁 config 的话前端确实不渲染，但 {@code get}/{@code page} 照常下发，
 * <b>抓包就能看到明文</b> —— 那是安全假象，不是安全边界。
 *
 * 裁剪的出口与 {@code FormDataMasker} 的出口刻意完全重合，
 * 漏一个就是一扇后门。
 *
 * <h3>列与字段是两套资源</h3>
 *
 * 列表列走 COLUMN、详情字段走 FIELD，两者分开判定 ——
 * 「列表里隐掉手机号」不该顺带把详情里的也隐掉。
 *
 * <h3>查不到资源行 = 放行</h3>
 *
 * v9 §5.2 的按需创建：绝大多数字段本来就不落 {@code sys_resource} 行。
 * 写反的表现是「所有表单的所有字段全部消失」。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class FormFieldCropper {

    private static final String SUB_FIELD_SEPARATOR = FormResourceDescriptorDTO.SUB_FIELD_SEPARATOR;

    /**
     * 只在第一次缺失时告警，避免刷屏
     */
    private final AtomicBoolean missingWarned = new AtomicBoolean(false);
    /**
     * 无登录上下文的告警只打一次 —— 定时任务会反复走到，打多了就没人看了
     */
    private final AtomicBoolean noLoginWarned = new AtomicBoolean(false);

    /**
     * 用 ObjectProvider 的理由同 {@link FormRowFilter}：本模块不依赖 system 模块
     */
    private final ObjectProvider<PermissionCommonApi> permissionApiProvider;

    public FormFieldCropper(ObjectProvider<PermissionCommonApi> permissionApiProvider) {
        this.permissionApiProvider = permissionApiProvider;
    }

    // ==================== 判定 ====================

    /**
     * 解析一批字段的显示模式
     *
     * @param column true = 列表列（COLUMN），false = 详情字段（FIELD）
     */
    public DatasetFieldModeRespDTO resolve(String formCode, Collection<String> fieldKeys, boolean column) {
        if (StrUtil.isBlank(formCode) || fieldKeys == null || fieldKeys.isEmpty()) {
            return DatasetFieldModeRespDTO.unrestricted();
        }
        PermissionCommonApi api = permissionApiProvider.getIfAvailable();
        if (api == null) {
            // 真实部署里不会走到这里；走到了说明权限模块没起来。
            // 此时字段权限是关着的 —— 必须留痕，否则又是一次「静默失效」
            if (missingWarned.compareAndSet(false, true)) {
                log.error("[resolve][PermissionCommonApi 不可用，在线表单的字段级权限当前不生效！"
                        + "若非单元测试环境，请检查 enlev-module-system 是否已装配]");
            }
            return DatasetFieldModeRespDTO.unrestricted();
        }
        try {
            Long loginUserId = SecurityFrameworkUtils.getLoginUserId();
            if (loginUserId == null) {
                // ⚠️ 无登录上下文 —— 不是「未知用户」，是【根本没有用户】。
                //
                // 走到这里的只有内部调用：流程引擎回写（超时自动通过、定时触发）、
                // 定时任务、MQ 消费者。真正的匿名 HTTP 请求在 Spring Security 那一层
                // 就被挡掉了，到不了这里。
                //
                // 按本项目的判据（见 FormRowFilter 的类注释）——
                // 「这次查询的结果会不会流向用户」——答案是不会，所以不限制。
                //
                // 🔴 更要紧的是：判成 HIDDEN 会让写路径的 stripNonWritable 把提交值
                // 【全部丢光】，表现是「超时自动通过的流程，回写的业务数据是空的」，
                // 且接口全程 200、没有任何日志。这与 FormRowFilter 在同一条件下
                // 返回 pass() 也是方向相反的 —— 两条横切能力必须一致，
                // 否则就是「行能改、字段全空」这种没人能反查的状态。
                if (noLoginWarned.compareAndSet(false, true)) {
                    log.warn("[resolve][无登录上下文，表单({})的字段级权限本次不生效。"
                            + "这对流程引擎回写/定时任务是预期行为；若是用户请求走到这里，"
                            + "说明鉴权链路有问题]", formCode);
                }
                return DatasetFieldModeRespDTO.unrestricted();
            }
            DatasetFieldModeRespDTO result = api.getDatasetFieldModes(
                    loginUserId, formCode, fieldKeys, column);
            return result == null ? DatasetFieldModeRespDTO.unrestricted() : result;
        } catch (Exception e) {
            // 算不出模式就全隐，不能放行 —— 放行等于把一次故障变成一次泄露。
            // 与 FormRowFilter 的 denyAll 同一口径（S4b §3.6）
            log.error("[resolve][解析表单({})的字段权限失败，按全部隐藏处理]", formCode, e);
            return denyAll(fieldKeys);
        }
    }

    private DatasetFieldModeRespDTO denyAll(Collection<String> fieldKeys) {
        DatasetFieldModeRespDTO dto = new DatasetFieldModeRespDTO();
        Map<String, String> modes = new LinkedHashMap<>();
        for (String key : fieldKeys) {
            modes.put(key, DatasetFieldModeRespDTO.HIDDEN);
        }
        dto.setModes(modes);
        return dto;
    }

    // ==================== config 裁剪 ====================

    /**
     * 裁剪渲染配置里的 formFields
     *
     * <p>⚠️ {@code formFields} 是<b>逐条序列化的规则字符串</b>数组，不是对象数组，
     * 所以要先逐条解析成 Map、过滤、再写回字符串。
     *
     * @param formFields 规则字符串列表（{@code FormFieldsCodec.read} 的产出）
     * @return 裁剪后的规则字符串列表
     */
    public List<String> cropFormFields(String formCode, List<String> formFields) {
        if (formFields == null || formFields.isEmpty()) {
            return formFields;
        }
        List<Map<String, Object>> nodes = new ArrayList<>(formFields.size());
        for (String rule : formFields) {
            nodes.add(JsonUtils.parseObjectQuietly(rule, new TypeReference<Map<String, Object>>() {}));
        }
        Set<String> keys = new LinkedHashSet<>();
        for (Map<String, Object> node : nodes) {
            if (node != null) {
                collectKeys(node, null, keys);
            }
        }
        DatasetFieldModeRespDTO modes = resolve(formCode, keys, false);
        if (modes.getModes() == null || modes.getModes().isEmpty()) {
            return formFields;
        }

        List<String> result = new ArrayList<>(formFields.size());
        for (int i = 0; i < nodes.size(); i++) {
            Map<String, Object> node = nodes.get(i);
            if (node == null) {
                // 解析不出来的规则原样保留：历史脏数据不该让整张表单打不开
                result.add(formFields.get(i));
                continue;
            }
            if (applyToNode(node, null, modes)) {
                continue; // 整条被裁掉
            }
            result.add(JsonUtils.toJsonString(node));
        }
        return result;
    }

    /**
     * 裁剪列表配置里的 columns
     *
     * 列走 COLUMN 资源，与详情字段分开判定
     */
    @SuppressWarnings("unchecked")
    public String cropListConf(String formCode, String listConfJson) {
        if (StrUtil.isBlank(listConfJson)) {
            return listConfJson;
        }
        Map<String, Object> listConf = JsonUtils.parseObjectQuietly(listConfJson,
                new TypeReference<Map<String, Object>>() {});
        if (listConf == null || !(listConf.get("columns") instanceof List<?> columns)) {
            return listConfJson;
        }
        Set<String> keys = new LinkedHashSet<>();
        for (Object item : columns) {
            String key = columnKey(item);
            if (key != null) {
                keys.add(key);
            }
        }
        DatasetFieldModeRespDTO modes = resolve(formCode, keys, true);
        if (modes.getModes() == null || modes.getModes().isEmpty()) {
            return listConfJson;
        }
        List<Object> kept = new ArrayList<>();
        for (Object item : columns) {
            String key = columnKey(item);
            if (key != null && modes.isHidden(key)) {
                continue;
            }
            kept.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>(listConf);
        result.put("columns", kept);
        return JsonUtils.toJsonString(result);
    }

    @SuppressWarnings("unchecked")
    private String columnKey(Object item) {
        if (!(item instanceof Map<?, ?> colMap)) {
            return null;
        }
        Map<String, Object> col = (Map<String, Object>) colMap;
        if (!(col.get("field") instanceof String field) || StrUtil.isBlank(field)) {
            return null;
        }
        String subField = col.get("subField") instanceof String s && StrUtil.isNotBlank(s) ? s : null;
        return buildKey(subField, field);
    }

    // ==================== 数据裁剪 ====================

    /**
     * 裁剪单行数据（详情用 FIELD，列表用 COLUMN）
     *
     * <p>入参的行必须是<b>按表单字段名</b>作键的 —— 主表列在 SQL 里已经
     * {@code AS} 成字段名，子表行由 {@code SubTableRowMapper} 转过。
     *
     * @param fieldKeys 该表单的全部字段标识
     */
    public Map<String, Object> cropRow(String formCode, Map<String, Object> row,
                                       Collection<String> fieldKeys, boolean column) {
        if (row == null || row.isEmpty() || fieldKeys == null || fieldKeys.isEmpty()) {
            return row;
        }
        DatasetFieldModeRespDTO modes = resolve(formCode, fieldKeys, column);
        return applyToRow(row, modes);
    }

    /**
     * 裁剪多行数据。模式只解析一次，逐行套用
     */
    public List<Map<String, Object>> cropRows(String formCode, List<Map<String, Object>> rows,
                                              Collection<String> fieldKeys, boolean column) {
        if (rows == null || rows.isEmpty() || fieldKeys == null || fieldKeys.isEmpty()) {
            return rows;
        }
        DatasetFieldModeRespDTO modes = resolve(formCode, fieldKeys, column);
        if (modes.getModes() == null || modes.getModes().isEmpty()) {
            return rows;
        }
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            result.add(row == null ? null : applyToRow(row, modes));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> applyToRow(Map<String, Object> row, DatasetFieldModeRespDTO modes) {
        if (modes.getModes() == null || modes.getModes().isEmpty()) {
            return row;
        }
        Set<String> toRemove = new LinkedHashSet<>();
        modes.getModes().forEach((key, mode) -> {
            if (DatasetFieldModeRespDTO.HIDDEN.equals(mode)) {
                toRemove.add(key);
            }
        });
        return removeKeys(row, toRemove, null);
    }

    /**
     * 从一行数据里删掉指定的字段（含子表明细列）
     *
     * 读路径（裁掉 HIDDEN）与写路径（丢掉 HIDDEN + READONLY 的提交值）共用这一段 ——
     * 各写一份的话，「详情里隐掉了、提交时却没丢」这种半拉子状态迟早出现
     *
     * @param removed 非 null 时，记录实际删掉了哪些键
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> removeKeys(Map<String, Object> row, Set<String> keys,
                                           List<String> removed) {
        if (keys.isEmpty()) {
            return row;
        }
        Map<String, Object> result = new LinkedHashMap<>(row);
        for (String key : keys) {
            int sep = key.indexOf(SUB_FIELD_SEPARATOR);
            if (sep < 0) {
                if (result.containsKey(key)) {
                    result.remove(key);
                    if (removed != null) {
                        removed.add(key);
                    }
                }
                continue;
            }
            // 子表明细列：要进到每一行明细里删，参照 FormDataMasker.maskSubRows
            String subField = key.substring(0, sep);
            String detailField = key.substring(sep + SUB_FIELD_SEPARATOR.length());
            if (!(result.get(subField) instanceof List<?> details)) {
                continue;
            }
            boolean hit = false;
            List<Object> kept = new ArrayList<>(details.size());
            for (Object detail : details) {
                if (detail instanceof Map<?, ?> detailMap) {
                    Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) detailMap);
                    hit |= copy.remove(detailField) != null || detailMap.containsKey(detailField);
                    kept.add(copy);
                } else {
                    kept.add(detail);
                }
            }
            result.put(subField, kept);
            if (hit && removed != null) {
                removed.add(key);
            }
        }
        return result;
    }

    // ==================== 写路径 ====================

    /**
     * 丢弃提交数据里「不该由用户写入」的字段值
     *
     * <h3>为什么读路径裁了还要在写路径再拦一次</h3>
     *
     * 只在读路径裁剪的话，用户虽然<b>看不见</b>这个字段，却可以自己构造一个请求
     * 在提交时塞一个值进去 —— 前端的 {@code disabled} 从来不是安全边界。
     *
     * <h3>为什么是「丢弃」而不是「报错」</h3>
     *
     * 与 {@code FormLogicGuard} 对不可写字段的处理保持一致：前端把整个表单对象
     * 原样回传是常态，对此报错会让正常操作失败。丢弃既安全又不打扰用户。
     *
     * @return 净化后的数据与被丢弃的字段名
     */
    public StripResult stripNonWritable(String formCode, String formFieldsJson,
                                        Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return new StripResult(data, List.of());
        }
        Set<String> fieldKeys = collectFieldKeys(formFieldsJson);
        if (fieldKeys.isEmpty()) {
            return new StripResult(data, List.of());
        }
        // 写路径按 FIELD 判定：能不能改是详情字段的属性，与列表列无关
        DatasetFieldModeRespDTO modes = resolve(formCode, fieldKeys, false);
        if (modes.getModes() == null || modes.getModes().isEmpty()) {
            return new StripResult(data, List.of());
        }
        Set<String> locked = new LinkedHashSet<>();
        modes.getModes().forEach((key, mode) -> {
            // HIDDEN 与 READONLY 都不接受提交值。EDITABLE 之外的一律拦 ——
            // 用白名单而不是黑名单，以后枚举加值时默认是安全的那一侧
            if (!DatasetFieldModeRespDTO.EDITABLE.equals(mode)) {
                locked.add(key);
            }
        });
        List<String> removed = new ArrayList<>();
        Map<String, Object> sanitized = removeKeys(data, locked, removed);
        return new StripResult(sanitized, removed);
    }

    /**
     * {@link #stripNonWritable} 的结果
     *
     * @param data     净化后的数据
     * @param discarded 被丢弃的字段名，供调用方并进同一条「丢弃」日志
     */
    public record StripResult(Map<String, Object> data, List<String> discarded) {
    }

    // ==================== 规则树遍历 ====================

    /**
     * 收集一张表单的全部字段标识
     *
     * 给 {@code get} / {@code page} 用：它们拿到的是数据行，需要先知道
     * 「这张表单有哪些字段」才能去问权限。
     */
    public Set<String> collectFieldKeys(String formFieldsJson) {
        Set<String> keys = new LinkedHashSet<>();
        for (String rule : FormFieldsCodec.read(formFieldsJson)) {
            Map<String, Object> node = JsonUtils.parseObjectQuietly(rule,
                    new TypeReference<Map<String, Object>>() {});
            if (node != null) {
                collectKeys(node, null, keys);
            }
        }
        return keys;
    }

    /**
     * 收集规则树上的字段标识
     *
     * 走法与 {@code FormResourceDescriptorBuilder.collect} 和
     * {@code FormDataMasker.collect} 同构 —— 三处在走同一棵树，
     * 走法不一致就会出现「资源登记认得这个字段、裁剪不认得」，而那正是一个泄露口
     */
    @SuppressWarnings("unchecked")
    private void collectKeys(Map<String, Object> node, String subField, Set<String> keys) {
        if (node.get("field") instanceof String field && StrUtil.isNotBlank(field)) {
            keys.add(buildKey(subField, field));
            Map<String, Object> props = node.get("props") instanceof Map<?, ?> p
                    ? (Map<String, Object>) p : Map.of();
            if (props.get("rule") instanceof List<?> innerRules) {
                for (Object inner : innerRules) {
                    if (inner instanceof Map<?, ?> innerMap) {
                        collectKeys((Map<String, Object>) innerMap, field, keys);
                    }
                }
            }
        }
        if (node.get("children") instanceof List<?> children) {
            for (Object child : children) {
                if (child instanceof Map<?, ?> childMap) {
                    collectKeys((Map<String, Object>) childMap, subField, keys);
                }
            }
        }
    }

    /**
     * 对一个规则节点套用字段模式
     *
     * @return true 表示这条规则要整条剔除
     */
    @SuppressWarnings("unchecked")
    private boolean applyToNode(Map<String, Object> node, String subField,
                                DatasetFieldModeRespDTO modes) {
        if (node.get("field") instanceof String field && StrUtil.isNotBlank(field)) {
            String key = buildKey(subField, field);
            if (modes.isHidden(key)) {
                return true;
            }
            if (modes.isReadonly(key)) {
                // 注入 disabled。⚠️ 这只是界面效果，不是安全边界 ——
                // 服务端在提交时还要丢弃只读字段的值，见 FormSubmitProcessor
                Map<String, Object> props = node.get("props") instanceof Map<?, ?> p
                        ? new LinkedHashMap<>((Map<String, Object>) p) : new LinkedHashMap<>();
                props.put("disabled", true);
                node.put("props", props);
            }
            Map<String, Object> props = node.get("props") instanceof Map<?, ?> p
                    ? (Map<String, Object>) p : null;
            if (props != null && props.get("rule") instanceof List<?> innerRules) {
                props.put("rule", filterChildren(innerRules, field, modes));
            }
        }
        if (node.get("children") instanceof List<?> children) {
            node.put("children", filterChildren(children, subField, modes));
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private List<Object> filterChildren(List<?> children, String subField,
                                        DatasetFieldModeRespDTO modes) {
        List<Object> kept = new ArrayList<>(children.size());
        for (Object child : children) {
            if (!(child instanceof Map<?, ?> childMap)) {
                kept.add(child);
                continue;
            }
            Map<String, Object> node = (Map<String, Object>) childMap;
            if (!applyToNode(node, subField, modes)) {
                kept.add(node);
            }
        }
        return kept;
    }

    private String buildKey(String subField, String field) {
        return StrUtil.isBlank(subField) ? field : subField + SUB_FIELD_SEPARATOR + field;
    }

}
