package cn.enlev.ainb.module.infra.framework.datamodel.core;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.module.infra.framework.datamodel.core.model.TableColumnModel;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 列的业务元数据存取
 *
 * <p><b>解决的问题</b>：一个列「配哪个字典」「关联哪张表」此前<b>只存在于列注释的自然语言里</b>：
 * <pre>
 * status       tinyint   '状态（字典：order_status）'
 * customer_id  bigint    '客户编号（关联：biz_customer.id）'
 * </pre>
 * 人读得懂，程序读不了 —— 表单层配字典/关联时还要人再填一遍，两边还会各自漂移。
 *
 * <p><b>刻意不做的两件事</b>：
 * <ol>
 *   <li><b>不改物理表结构</b> —— 这些是业务语义不是存储结构，落在 {@code infra_data_model.column_meta}，
 *       与既有的 {@code list_config}/{@code form_config} 同一种做法</li>
 *   <li><b>不建物理外键</b> —— 全项目零 {@code FOREIGN KEY}，关联关系在应用层维护</li>
 * </ol>
 *
 * <p>存储形态（key 为小写列名，只存有值的列，避免空对象把 JSON 撑大）：
 * <pre>
 * {"status": {"dictType": "order_status"},
 *  "customer_id": {"refTable": "biz_customer", "refColumn": "id"}}
 * </pre>
 *
 * @author 走进新时代
 */
public class ColumnMetaStore {

    private static final TypeReference<Map<String, ColumnMeta>> TYPE = new TypeReference<>() {
    };

    private ColumnMetaStore() {
    }

    /**
     * 单个列的业务元数据
     *
     * ⚠️ 两个 Jackson 注解都不是可选的，实测踩过：
     * <ul>
     *   <li>{@code @JsonIgnore} —— 不加的话 {@code isEmpty()} 会被当成 getter，
     *       每个列都多存一个无用的 {@code "empty":false}</li>
     *   <li>{@code @JsonInclude(NON_NULL)} —— 不加的话只配了字典的列也会存下
     *       {@code "refTable":null,"refColumn":null}</li>
     * </ul>
     * 两者叠加会让这一列的 JSON 膨胀一倍有余，而它是要随表结构反复读写的。
     */
    @Data
    @Accessors(chain = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ColumnMeta {

        /**
         * 字典类型，对应 system_dict_data.dict_type
         */
        private String dictType;
        /**
         * 关联的表名
         */
        private String refTable;
        /**
         * 关联的列名
         */
        private String refColumn;

        @JsonIgnore
        public boolean isEmpty() {
            return StrUtil.isBlank(dictType) && StrUtil.isBlank(refTable) && StrUtil.isBlank(refColumn);
        }
    }

    /**
     * 从字段列表抽取元数据，序列化成 JSON
     *
     * @return JSON 字符串；没有任何元数据时返回 null（而不是 "{}"，让库里保持干净）
     */
    public static String serialize(List<TableColumnModel> columns) {
        Map<String, ColumnMeta> map = new LinkedHashMap<>();
        for (TableColumnModel column : CollUtil.emptyIfNull(columns)) {
            if (StrUtil.isBlank(column.getColumnName())) {
                continue;
            }
            ColumnMeta meta = new ColumnMeta()
                    .setDictType(StrUtil.trimToNull(column.getDictType()))
                    .setRefTable(StrUtil.trimToNull(column.getRefTable()))
                    .setRefColumn(StrUtil.trimToNull(column.getRefColumn()));
            if (!meta.isEmpty()) {
                map.put(column.getColumnName().toLowerCase(), meta);
            }
        }
        return map.isEmpty() ? null : JsonUtils.toJsonString(map);
    }

    /**
     * 反序列化，解析失败返回空 Map
     *
     * 刻意「解析失败不抛错」：这一列是<b>锦上添花的业务语义</b>，
     * 不该因为它坏了就让整张表的结构都读不出来。
     */
    public static Map<String, ColumnMeta> deserialize(String json) {
        if (StrUtil.isBlank(json)) {
            return Map.of();
        }
        Map<String, ColumnMeta> map = JsonUtils.parseObjectQuietly(json, TYPE);
        return map == null ? Map.of() : map;
    }

    /**
     * 把元数据合并回字段列表（就地填充）
     *
     * 读表结构时用：物理结构来自 information_schema，业务语义来自本存储，两者在这里汇合。
     */
    public static void merge(List<TableColumnModel> columns, String json) {
        Map<String, ColumnMeta> map = deserialize(json);
        if (map.isEmpty()) {
            return;
        }
        for (TableColumnModel column : CollUtil.emptyIfNull(columns)) {
            ColumnMeta meta = map.get(StrUtil.emptyIfNull(column.getColumnName()).toLowerCase());
            if (meta != null) {
                column.setDictType(meta.getDictType())
                        .setRefTable(meta.getRefTable())
                        .setRefColumn(meta.getRefColumn());
            }
        }
    }

    /**
     * 按改表后的实际列名重建元数据
     *
     * 🔴 <b>这一步不能省。</b>改表时列可能被<b>改名</b>或<b>删除</b>：
     * <ul>
     *   <li>改名后若还按旧名存，这列的字典/关联配置就再也匹配不上了（静默丢失）</li>
     *   <li>删除后若不清理，元数据会一直留着 —— 等到有人建一个<b>同名新列</b>，
     *       旧配置会凭空「复活」附到新列上</li>
     * </ul>
     *
     * <p>做法是直接<b>以提交上来的字段列表为准重建</b>：它本来就是改表后的完整结构
     * （改表是全量同步语义），所以重建天然完成了改名跟随与删除清理。
     *
     * @param targetColumns 改表提交的完整字段列表（其中改名的列带 oldColumnName）
     * @param currentJson   改表前的元数据
     * @return 新的 JSON
     */
    public static String rebuild(List<TableColumnModel> targetColumns, String currentJson) {
        Map<String, ColumnMeta> old = deserialize(currentJson);
        Map<String, ColumnMeta> result = new LinkedHashMap<>();
        for (TableColumnModel column : CollUtil.emptyIfNull(targetColumns)) {
            if (StrUtil.isBlank(column.getColumnName())) {
                continue;
            }
            String name = column.getColumnName().toLowerCase();
            // 本次提交里带了元数据 → 以提交的为准
            ColumnMeta submitted = new ColumnMeta()
                    .setDictType(StrUtil.trimToNull(column.getDictType()))
                    .setRefTable(StrUtil.trimToNull(column.getRefTable()))
                    .setRefColumn(StrUtil.trimToNull(column.getRefColumn()));
            if (!submitted.isEmpty()) {
                result.put(name, submitted);
                continue;
            }
            // 没带 → 沿用旧的，并跟随改名。
            // 注意是「沿用」而不是「清空」：前端只在配置弹窗里编辑这几项，
            // 普通的改表提交（改个长度、加个字段）不会带上它们。
            String lookupKey = StrUtil.isNotBlank(column.getOldColumnName())
                    ? column.getOldColumnName().toLowerCase() : name;
            ColumnMeta inherited = old.get(lookupKey);
            if (inherited != null && !inherited.isEmpty()) {
                result.put(name, inherited);
            }
        }
        return result.isEmpty() ? null : JsonUtils.toJsonString(result);
    }

    /**
     * 清理掉已不存在的列的元数据
     *
     * 给「元数据与真实表结构对不上」的存量数据做修复用，正常路径走 {@link #rebuild}。
     */
    public static String prune(String json, Set<String> existingColumnNames) {
        Map<String, ColumnMeta> map = deserialize(json);
        if (map.isEmpty()) {
            return null;
        }
        Set<String> lower = CollUtil.emptyIfNull(existingColumnNames).stream()
                .map(name -> StrUtil.emptyIfNull(name).toLowerCase()).collect(Collectors.toSet());
        Map<String, ColumnMeta> result = map.entrySet().stream()
                .filter(entry -> lower.contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
        return result.isEmpty() ? null : JsonUtils.toJsonString(result);
    }

}
