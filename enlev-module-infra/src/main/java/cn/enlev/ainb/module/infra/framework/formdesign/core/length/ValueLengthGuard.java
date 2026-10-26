package cn.enlev.ainb.module.infra.framework.formdesign.core.length;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormErrorText;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.FieldMapping;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.SideTableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.SubTableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.TableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.contract.JdbcTypeFamily;
import cn.enlev.ainb.module.infra.framework.formdesign.core.contract.WidgetContractRegistry;
import cn.enlev.ainb.module.infra.framework.formdesign.core.meta.TableMetadataCache;
import com.baomidou.mybatisplus.generator.config.po.TableField;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提交期超长值拦截
 *
 * <h3>它防的是什么</h3>
 *
 * 设计方案 §9.3 第 14 条：向 {@code varchar(255)} 列提交 10KB 内容，必须在服务端拦下
 * 并给出可读错误，而不是抛一个数据库层的 500。
 *
 * 不拦的话，用户看到的是
 * {@code Data truncation: Data too long for column 'remark' at row 1} —— 一句英文的
 * 数据库错误，既不告诉他是界面上的哪个字段（列名和字段标题往往不一样），
 * 也不告诉他上限是多少。而这类内容（一段粘贴进来的说明、一份富文本）恰恰很常见。
 *
 * <h3>为什么校验的是【落库形态】而不是逻辑形态</h3>
 *
 * 交接文档第十八节写的位置是「排在富媒体净化之后」。这里比那更靠后一步：
 * 放在 {@code FormValueSerializer.serialize} 产出 {@code dbData} 【之后】、
 * 写库之前。理由是本模块自己的那条原则 —— <b>校验的对象应当是最终要落库的那份数据</b>：
 *
 * <ul>
 *   <li>多选控件的逻辑值是 {@code ["a","b"]}，落库形态是 {@code "a,b"}；
 *       文件控件的逻辑值是对象数组，落库形态是一整串 JSON。两者长度差得远，
 *       量逻辑形态等于量错了对象</li>
 *   <li>富媒体净化把 base64 转存成 URL，长度差两个数量级 —— 排在它之前会拦下一个
 *       本该先被转存的值，报错内容对用户毫无意义。而 serialize 天然在净化之后，
 *       所以这条要求自动满足</li>
 * </ul>
 *
 * <h3>为什么必须先有元数据缓存</h3>
 *
 * 列长度只能从数据库元数据拿。而 {@code DatabaseTableService.getTable} 每次调用都
 * 新建连接 + 用 Generator 解析整库结构（实测 50–80ms），直接放进提交路径上的后果
 * 不是「慢一点」，而是每次保存多出几百毫秒、并发提交时把连接池打满 ——
 * 那比不做这条校验糟得多。所以本类只走 {@link TableMetadataCache}。
 *
 * <h3>字符 vs 字节：两个族的上限单位不一样</h3>
 *
 * <ul>
 *   <li>{@code varchar(255)} / {@code char(n)}：MySQL 算的是<b>字符</b>数，
 *       一个汉字算 1。所以按码点数比</li>
 *   <li>{@code TEXT} 家族：上限是<b>字节</b>数（TEXT 65535、MEDIUMTEXT 16MB），
 *       一个汉字在 utf8mb4 下占 3 字节。所以按 UTF-8 字节数比 ——
 *       按字符数比的话，两万多字的中文富文本会被判成「没超」，然后在写库时报 1406</li>
 * </ul>
 *
 * 只管这两族。数字、日期这些即便超了也不是「太长」，由类型兼容性校验负责。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class ValueLengthGuard {

    /**
     * 报错里回显的值片段长度
     *
     * 不回显整个值：拦下来的值动辄几 KB，整段塞进错误消息会让接口返回体
     * 比数据本身还大，日志也跟着爆。给个开头让用户认出是哪一段内容就够了。
     */
    private static final int PREVIEW_LENGTH = 20;

    @Resource
    private TableMetadataCache metadataCache;
    @Resource
    private WidgetContractRegistry contractRegistry;

    /**
     * 校验一份落库数据里有没有超长的值
     *
     * @param dataSourceId   数据源编号
     * @param formFieldsJson 表单字段规则，用来把列名换成用户看得懂的字段标题
     * @param binding        表绑定
     * @param dbData         <b>落库形态</b>的数据（{@code FormValueSerializer.serialize} 的产物）
     */
    public ValidationResult validate(Long dataSourceId, String formFieldsJson,
                                     TableBinding binding, Map<String, Object> dbData) {
        ValidationResult result = new ValidationResult();
        if (binding == null || dbData == null || dbData.isEmpty()) {
            return result;
        }
        Map<String, String> titles = parseFieldTitles(formFieldsJson);

        // 主表。⚠️ 映射到副表的字段也在这份 fieldMapping 里（靠 mapping.table 区分），
        // 按各自的 table 取列，与写入路径一致
        checkTable(dataSourceId, binding.getMainTable(), binding.getFieldMapping(),
                dbData, titles, result);

        // 副表：1:1，值同样在顶层
        if (binding.getSideTables() != null) {
            for (SideTableBinding side : binding.getSideTables()) {
                if (side != null) {
                    checkTable(dataSourceId, side.getTable(), side.getFieldMapping(),
                            dbData, titles, result);
                }
            }
        }

        // 子表：1:N，值是行的清单，逐行看
        if (binding.getSubTables() != null) {
            for (SubTableBinding sub : binding.getSubTables()) {
                if (sub == null || StrUtil.isBlank(sub.getField())) {
                    continue;
                }
                checkSubTable(dataSourceId, sub, dbData.get(sub.getField()), titles, result);
            }
        }
        return result;
    }

    // ==================== 逐表 ====================

    private void checkTable(Long dataSourceId, String tableName,
                            Map<String, FieldMapping> fieldMapping, Map<String, Object> row,
                            Map<String, String> titles, ValidationResult result) {
        if (StrUtil.isBlank(tableName) || fieldMapping == null || fieldMapping.isEmpty()) {
            return;
        }
        Map<String, TableField> columns = null;
        for (Map.Entry<String, FieldMapping> entry : fieldMapping.entrySet()) {
            String field = entry.getKey();
            FieldMapping mapping = entry.getValue();
            if (mapping == null || StrUtil.isBlank(mapping.getColumn())) {
                continue;
            }
            // 映射声明了别的表时，这一条不属于当前表 —— 副表字段混在主表 fieldMapping 里
            String owner = StrUtil.blankToDefault(mapping.getTable(), tableName);
            if (!owner.equals(tableName)) {
                continue;
            }
            // 没提交的字段不参与：更新是局部的，没提交的键压根不会进 SQL
            if (!row.containsKey(field)) {
                continue;
            }
            String value = asText(row.get(field));
            if (value == null || value.isEmpty()) {
                continue;
            }
            // 到这里才去取列信息：绝大多数提交里没有任何超长值，
            // 而多数表单的字段值都很短，能省一次 Map 构建就省一次
            if (columns == null) {
                columns = metadataCache.columns(dataSourceId, tableName);
                if (columns.isEmpty()) {
                    // 表不存在由别的校验负责报错，这里不重复，也不能因此拦住提交
                    return;
                }
            }
            Violation violation = check(columns.get(mapping.getColumn().toLowerCase()),
                    mapping.getColumn(), value);
            if (violation != null) {
                result.addError(buildMessage(labelOf(titles, field), violation, ""));
            }
        }
    }

    /**
     * 逐行检查子表明细
     *
     * ⚠️ 同一列多行超长要<b>合并成一条</b>，不是一行一条。30 行明细的备注列都太长时，
     * 报 30 条错误是刷屏，用户反而找不到重点 —— 这是本模块的通则，
     * 行号文案统一走 {@link FormErrorText#rows}，各校验器各写一套的话，
     * 用户会以为是两套不同的东西。
     */
    @SuppressWarnings("unchecked")
    private void checkSubTable(Long dataSourceId, SubTableBinding sub, Object rows,
                               Map<String, String> titles, ValidationResult result) {
        if (!(rows instanceof List<?> list) || list.isEmpty()
                || StrUtil.isBlank(sub.getTable()) || sub.getFieldMapping() == null) {
            return;
        }
        Map<String, TableField> columns = null;
        // 字段 -> 出错的行号；同时记下第一次命中的详情，用来拼消息
        Map<String, List<Integer>> badRows = new LinkedHashMap<>();
        Map<String, Violation> samples = new LinkedHashMap<>();

        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> row = (Map<String, Object>) map;
            for (Map.Entry<String, FieldMapping> entry : sub.getFieldMapping().entrySet()) {
                FieldMapping mapping = entry.getValue();
                if (mapping == null || StrUtil.isBlank(mapping.getColumn())
                        || !row.containsKey(entry.getKey())) {
                    continue;
                }
                String value = asText(row.get(entry.getKey()));
                if (value == null || value.isEmpty()) {
                    continue;
                }
                if (columns == null) {
                    columns = metadataCache.columns(dataSourceId, sub.getTable());
                    if (columns.isEmpty()) {
                        return;
                    }
                }
                Violation violation = check(columns.get(mapping.getColumn().toLowerCase()),
                        mapping.getColumn(), value);
                if (violation != null) {
                    // 行号从 1 开始，与界面一致
                    badRows.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(i + 1);
                    // 只留第一条样本：多行的值各不相同，回显哪一条都行，
                    // 但超出量取最先命中的那行，用户据此知道大概要删多少
                    samples.putIfAbsent(entry.getKey(), violation);
                }
            }
        }
        String widgetLabel = labelOf(titles, sub.getField());
        badRows.forEach((field, numbers) -> {
            String position = String.format("%s 第 %s 行的", widgetLabel, FormErrorText.rows(numbers));
            result.addError(buildMessage(labelOf(titles, field), samples.get(field), position));
        });
    }

    // ==================== 单个值 ====================

    /**
     * 量一个值
     *
     * @return 超长时返回详情，没超或不该量时返回 null
     */
    private Violation check(TableField column, String columnName, String value) {
        if (column == null || column.getMetaInfo() == null
                || column.getMetaInfo().getJdbcType() == null) {
            return null;
        }
        int limit = column.getMetaInfo().getLength();
        if (limit <= 0) {
            // 元数据没给出长度（有些驱动对 TEXT 返回 0）。宁可漏判也不要凭猜测拦下
            return null;
        }
        JdbcTypeFamily family = contractRegistry.resolveJdbcTypeFamily(
                column.getMetaInfo().getJdbcType().name());
        int actual;
        String unit;
        if (family == JdbcTypeFamily.STRING) {
            // varchar(n) / char(n) 的 n 是字符数。用码点数而不是 String.length()——
            // 后者数的是 UTF-16 代码单元，一个 emoji 会被算成 2，
            // 而 MySQL 只算 1，于是刚好卡在边界上的值会被误拦
            actual = value.codePointCount(0, value.length());
            unit = "字符";
        } else if (family == JdbcTypeFamily.TEXT) {
            // TEXT 家族的上限是字节数
            actual = value.getBytes(StandardCharsets.UTF_8).length;
            unit = "字节";
        } else {
            return null;
        }
        if (actual <= limit) {
            return null;
        }
        Violation violation = new Violation();
        violation.setColumn(columnName);
        violation.setUnit(unit);
        violation.setLimit(limit);
        violation.setActual(actual);
        violation.setPreview(preview(value));
        return violation;
    }

    /**
     * 拼一条能定位的报错
     *
     * 四样都要有，缺一样用户就得来回试：<b>字段标题</b>（他在界面上看到的名字，
     * 不是列名）、<b>超了多少</b>（不说的话他不知道要删几个字）、
     * <b>列名与上限</b>（给实施人员改表用）、<b>值的开头</b>（一个字段可能有多处内容，
     * 富文本尤其看不出是哪一段）。
     */
    private String buildMessage(String label, Violation v, String position) {
        return String.format("%s「%s」内容过长：%d %s，超出上限 %d %s（对应列 %s）。开头是「%s…」",
                position, label, v.getActual(), v.getUnit(), v.getLimit(), v.getUnit(),
                v.getColumn(), v.getPreview());
    }

    private String preview(String value) {
        String flat = value.replaceAll("\\s+", " ").trim();
        return flat.length() <= PREVIEW_LENGTH ? flat : flat.substring(0, PREVIEW_LENGTH);
    }

    /**
     * 值转成落库时的字符串形态
     *
     * dbData 里已经是落库形态，所以数组/对象在这里不该再出现 ——
     * 真出现了说明它的 storage 是 {@code none} 或契约没覆盖，那种值不会被写进列，
     * 量它没有意义。所以只认字符串，其余一律跳过。
     */
    private String asText(Object value) {
        return value instanceof CharSequence text ? text.toString() : null;
    }

    private String labelOf(Map<String, String> titles, String field) {
        return StrUtil.blankToDefault(titles.get(field), field);
    }

    // ==================== 字段标题 ====================

    /**
     * 收集字段标题
     *
     * 要递归两处：布局容器的 {@code children}、子表控件的 {@code props.rule}。
     * 少递归子表的话，明细列会报成裸字段名 —— 而报错里用标题不用 ID 是本模块的通则。
     */
    private Map<String, String> parseFieldTitles(String formFieldsJson) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String rule : FormFieldsCodec.read(formFieldsJson)) {
            Map<String, Object> node = JsonUtils.parseObjectQuietly(rule,
                    new TypeReference<Map<String, Object>>() {});
            if (node != null) {
                collectTitles(node, result);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void collectTitles(Map<String, Object> node, Map<String, String> result) {
        if (node.get("field") instanceof String field && StrUtil.isNotBlank(field)
                && node.get("title") instanceof String title && StrUtil.isNotBlank(title)) {
            result.putIfAbsent(field, title);
        }
        if (node.get("props") instanceof Map<?, ?> props
                && ((Map<String, Object>) props).get("rule") instanceof List<?> inner) {
            for (Object item : inner) {
                if (item instanceof Map<?, ?> map) {
                    collectTitles((Map<String, Object>) map, result);
                }
            }
        }
        if (node.get("children") instanceof List<?> children) {
            for (Object child : children) {
                if (child instanceof Map<?, ?> map) {
                    collectTitles((Map<String, Object>) map, result);
                }
            }
        }
    }

    /**
     * 一次超长的详情
     */
    @Data
    private static class Violation {

        private String column;
        /**
         * 「字符」或「字节」—— 两个族的上限单位不一样，报错里必须说清是哪一个，
         * 否则用户按字符数删了一半的中文，发现还是超
         */
        private String unit;
        private int limit;
        private int actual;
        private String preview;

    }

    /**
     * 校验结果
     *
     * 与 {@code FormValidationGuard.ValidationResult} 同一个形状：一次性汇总，
     * 而不是报第一条就返回 —— 用户改完一处再提交又被拦下一处，来回好几轮。
     */
    @Data
    public static class ValidationResult {

        private final List<String> errors = new ArrayList<>();

        public void addError(String message) {
            errors.add(message);
        }

        public boolean isPassed() {
            return errors.isEmpty();
        }

        public String toMessage() {
            return String.join("；", errors);
        }

    }

}
