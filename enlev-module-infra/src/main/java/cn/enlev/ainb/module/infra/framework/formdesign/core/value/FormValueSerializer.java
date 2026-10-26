package cn.enlev.ainb.module.infra.framework.formdesign.core.value;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.FieldMapping;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.SubTableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.TableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.contract.JdbcTypeFamily;
import cn.enlev.ainb.module.infra.framework.formdesign.core.contract.WidgetContract;
import cn.enlev.ainb.module.infra.framework.formdesign.core.contract.WidgetContractRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.FORM_DESIGN_VALUE_SEPARATOR_CONFLICT;

/**
 * 按控件契约把表单值在「逻辑形态」与「落库形态」之间转换
 *
 * <h3>不做这件事会发生什么</h3>
 *
 * 契约里每个控件都声明了 {@code storage}（scalar / json / delimited / subTable / none），
 * 但在本类之前，这一维【整个没有实现】—— 写入路径是
 * {@code params.add(data.get(field))}，值原样交给 JDBC。
 *
 * 于是一个多选控件映射到 {@code varchar(255)} 时，提交 {@code ["a","b"]} 的结果是：
 *
 * <pre>
 * Incorrect string value: '\xAC\xED\x00\x05sr...' for column 'reason'
 * </pre>
 *
 * {@code \xAC\xED\x00\x05} 是 Java 序列化的魔数 —— MySQL 驱动对不认识的类型会走
 * {@code setSerializableObject} 兜底，把 {@code ArrayList} 序列化成字节流往列里写。
 * 字符列上它报错（以 500 的形态抛给用户，看不出是什么问题）；
 * 能接受任意字节的列上它【静默写进去】，留下一段读出来是乱码的字节流。
 *
 * <h3>读写必须成对</h3>
 *
 * 只做写不做读，提交会成功但回显是一串原始字符串 —— 从「报 500」变成
 * 「看起来存进去了，打开却是乱的」，比之前更难发现。所以本类同时提供两个方向，
 * 且除 {@link Mode#FIRST_URL} 外互为逆运算。
 *
 * <h3>为什么不放进提交流水线</h3>
 *
 * 序列化后的值是给数据库看的，不是给业务逻辑看的。服务端扩展点、公式、联动
 * 都应当看到数组本身；把它们排在序列化之后，等于让每个扩展点作者自己去拆串。
 * 因此本类由运行时服务在【紧挨写库之前】调用，`data` 本身保持逻辑形态。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class FormValueSerializer {

    @Resource
    private WidgetContractRegistry contractRegistry;

    /**
     * 落库形态：JSON 串
     */
    private static final String STORAGE_JSON = "json";
    /**
     * 落库形态：分隔串
     */
    private static final String STORAGE_DELIMITED = "delimited";
    /**
     * 落库形态：单值
     */
    private static final String STORAGE_SCALAR = "scalar";

    // ==================== 写方向 ====================

    /**
     * 把提交数据转成可直接交给 JDBC 的形态
     *
     * 同时处理主表字段与子表明细列 —— 子表行此刻还挂在数据里（走独立明细表的写入
     * 也是从这份数据里取的），一次转完，写库的三条路径（主表 / 副表 / 子表）就都是干净的。
     *
     * @param formFieldsJson 表单字段规则 JSON
     * @param data           提交数据。本方法不修改入参
     * @return 落库形态的数据
     */
    public Map<String, Object> serialize(String formFieldsJson, Map<String, Object> data) {
        return serialize(formFieldsJson, data, null);
    }

    /**
     * 把提交数据转成可直接交给 JDBC 的形态（带表绑定）
     *
     * 绑定里的 {@code jdbcType} 用于修正契约的落库形态：多选这类 delimited 控件
     * 映射到 JSON 列时必须改存 JSON 串，见 {@link #resolveMode}。运行时写库路径
     * 一律走本重载；两参版本只是拿不到绑定时的便捷入口 —— 给写库路径写单测时，
     * 要桩的仍是<b>本重载</b>（桩错重载会被 STRICT_STUBS 判成 PotentialStubbingProblem）。
     *
     * @param formFieldsJson 表单字段规则 JSON
     * @param data           提交数据。本方法不修改入参
     * @param binding        表绑定配置，可为 null
     * @return 落库形态的数据
     */
    public Map<String, Object> serialize(String formFieldsJson, Map<String, Object> data,
                                         TableBinding binding) {
        return convert(formFieldsJson, data, true, binding);
    }

    // ==================== 读方向 ====================

    /**
     * 把一行落库数据还原成前端要的形态（详情用）
     *
     * @param row 数据行。本方法不修改入参
     */
    public Map<String, Object> deserialize(String formFieldsJson, Map<String, Object> row) {
        return deserialize(formFieldsJson, row, null);
    }

    /**
     * 把一行落库数据还原成前端要的形态（详情用，带表绑定）
     *
     * @param row 数据行。本方法不修改入参
     */
    public Map<String, Object> deserialize(String formFieldsJson, Map<String, Object> row,
                                           TableBinding binding) {
        return convert(formFieldsJson, row, false, binding);
    }

    /**
     * 把多行落库数据还原成前端要的形态（列表与导出用）
     */
    public List<Map<String, Object>> deserializeAll(String formFieldsJson,
                                                    List<Map<String, Object>> rows) {
        return deserializeAll(formFieldsJson, rows, null);
    }

    /**
     * 把多行落库数据还原成前端要的形态（列表与导出用，带表绑定）
     */
    public List<Map<String, Object>> deserializeAll(String formFieldsJson,
                                                    List<Map<String, Object>> rows,
                                                    TableBinding binding) {
        if (rows == null || rows.isEmpty()) {
            return rows;
        }
        List<StoredField> fields = parseStoredFields(formFieldsJson, binding);
        if (fields.isEmpty()) {
            return rows;
        }
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            result.add(row == null ? null : convertRow(row, fields, false));
        }
        return result;
    }

    // ==================== 转换 ====================

    private Map<String, Object> convert(String formFieldsJson, Map<String, Object> data,
                                        boolean toDatabase, TableBinding binding) {
        if (data == null) {
            return null;
        }
        List<StoredField> fields = parseStoredFields(formFieldsJson, binding);
        if (fields.isEmpty()) {
            return data;
        }
        return convertRow(data, fields, toDatabase);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> convertRow(Map<String, Object> row, List<StoredField> fields,
                                           boolean toDatabase) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        for (StoredField field : fields) {
            if (field.getSubField() == null) {
                if (!result.containsKey(field.getField())) {
                    continue;
                }
                result.put(field.getField(),
                        convertValue(field, result.get(field.getField()), toDatabase));
                continue;
            }
            // 子表明细列：逐行转。只转主表字段的话，明细里的多选、上传列
            // 会原样把 ArrayList 交给 JDBC，症状与改造前完全一样
            Object raw = result.get(field.getSubField());
            if (!(raw instanceof Collection<?> collection)) {
                continue;
            }
            List<Object> rows = new ArrayList<>(collection.size());
            for (Object item : collection) {
                if (!(item instanceof Map<?, ?> map)) {
                    rows.add(item);
                    continue;
                }
                Map<String, Object> detail = new LinkedHashMap<>((Map<String, Object>) map);
                if (detail.containsKey(field.getField())) {
                    detail.put(field.getField(),
                            convertValue(field, detail.get(field.getField()), toDatabase));
                }
                rows.add(detail);
            }
            result.put(field.getSubField(), rows);
        }
        return result;
    }

    private Object convertValue(StoredField field, Object value, boolean toDatabase) {
        if (value == null) {
            return null;
        }
        return toDatabase ? toDatabase(field, value) : fromDatabase(field, value);
    }

    /**
     * 逻辑形态 → 落库形态
     */
    private Object toDatabase(StoredField field, Object value) {
        return switch (field.getMode()) {
            case JSON -> toJson(field, value);
            case DELIMITED -> value instanceof Collection<?> collection
                    ? join(field, collection) : value;
            // 单图上传这类「值是数组但只存一个」的控件：取首个。
            // 统一值契约下前端提交的是 [{name,url,size,type}]，落库只留 url
            case FIRST_URL -> firstUrl(value);
        };
    }

    /**
     * 转成 JSON 串
     *
     * 文件型字段的裸字符串要先包成单元素数组：文件上传控件在「数量限制 = 1」时
     * 发出的是一个裸 URL 而不是数组（见 UploadFile.vue），不包的话同一个字段
     * 会时而存 {@code ["u"]}、时而存 {@code u} —— 读的时候前者解析成数组、
     * 后者解析失败按原样返回，于是每次读都记一条「列值不是合法 JSON」的告警，
     * 而这正是「落库值形态不统一」这条技术债的最后一处。
     */
    private Object toJson(StoredField field, Object value) {
        if (value instanceof Collection<?> || value instanceof Map<?, ?>) {
            return JsonUtils.toJsonString(value);
        }
        if (field.isFile() && value instanceof String text && StrUtil.isNotBlank(text)) {
            return JsonUtils.toJsonString(List.of(text));
        }
        return value;
    }

    /**
     * 落库形态 → 逻辑形态
     */
    private Object fromDatabase(StoredField field, Object value) {
        if (!(value instanceof String text)) {
            // 不是字符串说明列本来就存的别的类型（比如 JSON 列驱动直接返回对象），
            // 或者已经是还原过的形态。不动它
            return value;
        }
        return switch (field.getMode()) {
            case JSON -> parseJson(field, text);
            case DELIMITED -> split(field, text);
            // 单图上传落库就是一个 URL 字符串，读回来还是它。
            // 前端的上传适配层负责把它包成长度 1 的数组 —— 这一步刻意不做，
            // 否则「落库形态」与「逻辑形态」在这个控件上就不是一一对应了
            case FIRST_URL -> value;
        };
    }

    private String join(StoredField field, Collection<?> collection) {
        String separator = separator();
        List<String> parts = new ArrayList<>(collection.size());
        for (Object item : collection) {
            String text = item == null ? "" : String.valueOf(item);
            if (text.contains(separator)) {
                // 挡在这里而不是照拼：拼进去的话读回来会比存进去多出几项，
                // 而且不报错 —— 这正是本模块反复在避免的那类静默错误
                throw exception(FORM_DESIGN_VALUE_SEPARATOR_CONFLICT, field.label(), separator);
            }
            parts.add(text);
        }
        return String.join(separator, parts);
    }

    private Object split(StoredField field, String text) {
        if (text.isEmpty()) {
            // 空串还原成空数组而不是 [""]：前端拿到 [""] 会在多选框里勾中一个空选项
            return Collections.emptyList();
        }
        // -1：保留尾部空串。"a,," 是三项，丢掉尾部空项会让「存进去 3 项、读回来 2 项」
        return List.of(text.split(Pattern.quote(separator()), -1));
    }

    private Object parseJson(StoredField field, String text) {
        if (StrUtil.isBlank(text)) {
            return text;
        }
        Object parsed = JsonUtils.parseObjectQuietly(text, new TypeReference<Object>() {});
        if (parsed == null) {
            // 列里存的不是 JSON。可能是历史数据，也可能是别的程序写的 ——
            // 原样返回而不是抛异常：一条脏数据不该让整个列表打不开。
            // 但要留下痕迹，否则「这个字段回显不对」查不到任何线索
            log.warn("[parseJson][字段({})的列值不是合法 JSON，原样返回：{}]",
                    field.label(), StrUtil.maxLength(text, 64));
            return text;
        }
        return parsed;
    }

    /**
     * 取文件值里的第一个 URL
     *
     * 三种形态都要认：裸 URL 字符串、URL 数组、文件对象数组。
     */
    private Object firstUrl(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.isEmpty() ? null : firstUrl(collection.iterator().next());
        }
        if (value instanceof Map<?, ?> map) {
            Object url = map.get("url");
            return url instanceof String ? url : null;
        }
        return value;
    }

    /**
     * storage=delimited 的列落库时用的分隔符，契约没声明时退回逗号
     *
     * <p>🔴 写入侧（拼串）、读取侧（拆数组）与<b>查询侧</b>必须用同一个值。
     * 查询侧要用它是因为 {@code FIND_IN_SET} 只认逗号 ——
     * 分隔符不是逗号时 {@code SqlBuilderSupport} 会换成边界匹配的写法。
     *
     * <p>所以这个方法是 public 的：让「退回逗号」这条兜底只存在于这一处。
     * 查询侧再写一份 {@code isEmpty(sep) ? "," : sep} 的话，
     * 将来改契约默认值就会只改到一半 —— 而写读两侧分隔符不一致的表现是
     * 「存进去 3 项、读回来 2 项」，查询侧不一致的表现是「明明勾了却查不出来」。
     */
    public String separator() {
        String separator = contractRegistry.getDelimitedSeparator();
        // 契约里没声明时退回逗号：这是这类值的通行写法，也与 FIND_IN_SET 一致。
        // 真正的来源仍是契约，这里只是不让一个缺失的声明把写入整条路径卡死
        return StrUtil.isEmpty(separator) ? "," : separator;
    }

    // ==================== 字段解析 ====================

    /**
     * 从表单字段规则里提取需要转换的字段
     *
     * 只收三类，其余原样落库：
     *
     * <ul>
     *   <li>{@code storage = json}：值是数组或对象，存 JSON 串</li>
     *   <li>{@code storage = delimited}：值是数组，存分隔串</li>
     *   <li>{@code storage = scalar} 且值类型是文件：值可能是数组，只存首个 URL</li>
     * </ul>
     *
     * 其它 scalar 控件收到数组是客户端的问题，不在这里替它兜 ——
     * 兜了会把「前端传错了类型」变成一个看不见的自动修正。
     */
    public List<StoredField> parseStoredFields(String formFieldsJson) {
        return parseStoredFields(formFieldsJson, null);
    }

    /**
     * 从表单字段规则里提取需要转换的字段（带表绑定）
     *
     * 绑定提供每个字段实际映射到的列类型族 —— 契约里的 storage 只是控件的
     * 【默认】落库形态，同一个控件映射到不同族的列时形态可以不同，见 {@link #resolveMode}。
     */
    public List<StoredField> parseStoredFields(String formFieldsJson, TableBinding binding) {
        Map<String, JdbcTypeFamily> columnFamilies = buildColumnFamilies(binding);
        List<StoredField> result = new ArrayList<>();
        for (String rule : FormFieldsCodec.read(formFieldsJson)) {
            Map<String, Object> node = JsonUtils.parseObjectQuietly(rule,
                    new TypeReference<Map<String, Object>>() {});
            if (node != null) {
                collect(node, result, null, columnFamilies);
            }
        }
        return result;
    }

    /**
     * 从表绑定里收集「字段 → 列类型族」
     *
     * 键带子表前缀：主表与子表可能出现同名 field，而它们的列类型各自独立。
     * 绑定为 null（拿不到绑定的场景）时返回空表 —— 一律按契约默认形态处理。
     */
    private Map<String, JdbcTypeFamily> buildColumnFamilies(TableBinding binding) {
        if (binding == null) {
            return Collections.emptyMap();
        }
        Map<String, JdbcTypeFamily> families = new HashMap<>();
        if (binding.getFieldMapping() != null) {
            binding.getFieldMapping().forEach((field, mapping) -> {
                JdbcTypeFamily family = familyOf(mapping);
                if (family != null) {
                    families.put(columnKey(null, field), family);
                }
            });
        }
        if (binding.getSubTables() != null) {
            for (SubTableBinding subTable : binding.getSubTables()) {
                if (subTable.getField() == null || subTable.getFieldMapping() == null) {
                    continue;
                }
                subTable.getFieldMapping().forEach((field, mapping) -> {
                    JdbcTypeFamily family = familyOf(mapping);
                    if (family != null) {
                        families.put(columnKey(subTable.getField(), field), family);
                    }
                });
            }
        }
        return families;
    }

    private JdbcTypeFamily familyOf(FieldMapping mapping) {
        return mapping == null ? null
                : contractRegistry.resolveJdbcTypeFamily(mapping.getJdbcType());
    }

    private static String columnKey(String subField, String field) {
        return (subField == null ? "" : subField) + "\0" + field;
    }

    @SuppressWarnings("unchecked")
    private void collect(Map<String, Object> node, List<StoredField> result, String subField,
                         Map<String, JdbcTypeFamily> columnFamilies) {
        String type = node.get("type") instanceof String t ? t : null;
        String field = node.get("field") instanceof String f && StrUtil.isNotBlank(f) ? f : null;
        Mode mode = resolveMode(type, columnFamilies.get(columnKey(subField, field)));
        if (field != null && mode != null) {
            StoredField stored = new StoredField();
            stored.setField(field);
            stored.setTitle(node.get("title") instanceof String t ? t : null);
            stored.setSubField(subField);
            stored.setMode(mode);
            stored.setFile(contractRegistry.isFileWidget(type));
            result.add(stored);
        }
        // 子表控件：内部规则挂在 props.rule 下，它们的归属子表就是本控件
        if (field != null && node.get("props") instanceof Map<?, ?> propsMap
                && ((Map<String, Object>) propsMap).get("rule") instanceof List<?> innerRules) {
            for (Object inner : innerRules) {
                if (inner instanceof Map<?, ?> innerMap) {
                    collect((Map<String, Object>) innerMap, result, field, columnFamilies);
                }
            }
        }
        if (node.get("children") instanceof List<?> list) {
            for (Object child : list) {
                if (child instanceof Map<?, ?> childMap) {
                    // 布局容器不改变归属，原样把子表信息传下去
                    collect((Map<String, Object>) childMap, result, subField, columnFamilies);
                }
            }
        }
    }

    /**
     * 按控件契约与实际映射到的列类型族决定转换方式
     *
     * <p>🔴 delimited → JSON 的升级修的是一处实测 bug：多选拼出的逗号串永远不是合法 JSON，
     * 这类控件映射到 JSON 列时写入必被数据库拒绝（{@code Invalid JSON text}，对外是无从
     * 下手的 500）；读方向按逗号切 JSON 串又会把值切碎成 {@code ["[\"a\"", " \"b\"]"]}。
     * JSON 列装得下任何数组，故该组合下改存 JSON 串是唯一正确形态；
     * STRING / TEXT 列保持分隔串，存量数据不受影响。
     *
     * @return 不需要转换时返回 null
     */
    private Mode resolveMode(String type, JdbcTypeFamily family) {
        WidgetContract contract = contractRegistry.get(type);
        if (contract == null || contract.getStorage() == null) {
            return null;
        }
        return switch (contract.getStorage()) {
            case STORAGE_JSON -> Mode.JSON;
            case STORAGE_DELIMITED -> family == JdbcTypeFamily.JSON ? Mode.JSON : Mode.DELIMITED;
            case STORAGE_SCALAR -> contractRegistry.isFileWidget(type) ? Mode.FIRST_URL : null;
            default -> null;
        };
    }

    /**
     * 转换方式
     */
    public enum Mode {
        /**
         * 数组/对象 ↔ JSON 串
         */
        JSON,
        /**
         * 数组 ↔ 分隔串
         */
        DELIMITED,
        /**
         * 文件值 → 首个 URL。单向，见 {@link #fromDatabase}
         */
        FIRST_URL
    }

    /**
     * 一个需要转换的字段
     */
    @Data
    public static class StoredField {

        /**
         * 字段名
         */
        private String field;
        /**
         * 字段标题，报错时用它而不是字段 ID
         */
        private String title;
        /**
         * 所属子表控件的字段名，主表字段为 null
         */
        private String subField;
        /**
         * 转换方式
         */
        private Mode mode;
        /**
         * 值是否为文件。文件型的裸字符串在 json 存储下要包成单元素数组
         */
        private boolean file;

        public String label() {
            return StrUtil.isNotBlank(title) ? title : field;
        }

    }

}
