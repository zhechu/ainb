package cn.enlev.ainb.module.infra.framework.billtemplate.core;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.SubTableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.TableBinding;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从表单的 form-create 规则中解析出单据字段
 *
 * <h3>为什么要递归</h3>
 * 设计器允许把控件放进栅格、标签页、折叠面板等布局容器，此时控件不在规则数组的顶层，
 * 而是嵌在容器的 {@code children} 里。只扫顶层的话，用户把单据组件拖进一个两列栅格，
 * 单号就会静默不生成 —— 这种「配了但不生效」的失败最难排查。
 *
 * <h3>子表归属由表绑定判定，不看容器类型</h3>
 * 判断一个字段属于主表还是某张子表，依据是表绑定里的 {@code subTables[].fieldMapping}，
 * 而不是它在规则树里被哪种容器包着。容器类型种类多、且会随设计器版本变化；
 * 表绑定则是本模块自己的权威声明。
 *
 * <h3>解析失败一律降级为空</h3>
 * 与 {@link FormFieldsCodec#read} 的策略一致：脏数据不应该让整个保存流程崩掉。
 * 配置层面的问题由发布校验负责拦截，那里能给出明确的报错。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class FormBillFieldResolver {

    /**
     * 单据组件在 form-create 规则里的 type
     *
     * 必须与前端 {@code BillNumberInput.vue} 的组件名一致。
     */
    public static final String COMPONENT_TYPE = "BillNumberInput";

    /**
     * 解析结果缓存的容量上限
     *
     * 一张表单一个条目，不清理会随表单数量增长。超过上限直接整个清空 ——
     * 单据字段解析很便宜（一次 JSON 反序列化），为它引入一套 LRU 不划算。
     */
    private static final int CACHE_MAX_SIZE = 512;

    /**
     * 解析结果缓存
     *
     * <h3>⚠️ 为什么不能用版本号做键（曾经就是那么做的，有洞）</h3>
     *
     * 原来的键是 {@code 表单编码:版本号}，理由写的是「版本号在发布时递增，
     * 天然保证配置变更后缓存立即失效」。但两个调用方拿到的版本号都是
     * {@code design.getPublishedVersion()}，也就是**已发布**的那个版本，
     * 而它们要解析的却是**当前草稿**的 formFields —— 两者在下面两种情况下对不上：
     *
     * <ol>
     *   <li><b>发布校验</b>。校验的是「即将成为版本 N+1」的内容，
     *       而此刻版本号还是 N。于是：第一次发布因为单据字段没绑模板被拒，
     *       解析结果（templateCode 为空）被缓存进 {@code code:N}；
     *       用户把模板配好再发一次，命中的还是那份旧结果，
     *       **报同一句「未绑定单据模板」，直到重启为止**。
     *       反过来更糟：已发布过的表单新加一个没绑模板的单据字段，
     *       校验拿到的是上个版本的清单，这条校验直接失效。</li>
     *   <li><b>运行时填号</b>。草稿改了但还没发布时，formFields 变了、版本号没变。</li>
     * </ol>
     *
     * 现在键只用表单编码，条目里连同 formFields 原文一起存，
     * 命中后先比对原文再复用。内容变了就重新解析，与版本号无关 ——
     * 「缓存什么时候失效」这件事由内容本身决定，不依赖调用方传对版本号。
     */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /** 缓存条目：解析结果 + 当时的 formFields 原文 */
    private record CacheEntry(String formFields, List<BillFieldDefinition> definitions) {
    }

    /**
     * 解析表单中的全部单据字段
     *
     * @param formCode   表单编码，用作缓存键与日志
     * @param version    表单已发布版本号。**不参与缓存键**，仅保留在签名里兼容调用方
     * @param formFields 表单字段规则（DO 中存的 JSON 字符串）
     * @param binding    表绑定配置，用于判定字段属于主表还是子表
     * @return 单据字段列表，没有则返回空列表
     */
    public List<BillFieldDefinition> resolve(String formCode, Integer version,
                                             String formFields, TableBinding binding) {
        CacheEntry cached = cache.get(formCode);
        // 用 Objects.equals 而不是 == ：formFields 每次都是从 DO 里取出的新字符串
        if (cached != null && Objects.equals(cached.formFields(), formFields)) {
            return cached.definitions();
        }
        List<BillFieldDefinition> result = doResolve(formCode, formFields, binding);
        if (cache.size() >= CACHE_MAX_SIZE) {
            cache.clear();
        }
        cache.put(formCode, new CacheEntry(formFields, result));
        return result;
    }

    /**
     * 清空缓存
     *
     * 正常流程不需要调用（内容变更即失效），仅供排障与测试使用。
     */
    public void clearCache() {
        cache.clear();
    }

    private List<BillFieldDefinition> doResolve(String formCode, String formFields, TableBinding binding) {
        List<String> rules = FormFieldsCodec.read(formFields);
        if (rules.isEmpty()) {
            return Collections.emptyList();
        }
        // field → 所属子表的 field，主表字段不在其中
        Map<String, String> subTableOwner = buildSubTableOwner(binding);

        List<BillFieldDefinition> result = new ArrayList<>();
        for (String rule : rules) {
            Map<String, Object> node = parseRule(formCode, rule);
            if (node != null) {
                collect(node, subTableOwner, result);
            }
        }
        return result.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(result);
    }

    private Map<String, Object> parseRule(String formCode, String rule) {
        Map<String, Object> node = JsonUtils.parseObjectQuietly(rule, new TypeReference<Map<String, Object>>() {});
        if (node == null) {
            log.warn("[parseRule][表单({}) 的某条字段规则不是合法 JSON，已跳过]", formCode);
        }
        return node;
    }

    /**
     * 递归收集单据字段
     */
    @SuppressWarnings("unchecked")
    private void collect(Map<String, Object> node, Map<String, String> subTableOwner,
                         List<BillFieldDefinition> result) {
        if (COMPONENT_TYPE.equals(node.get("type"))) {
            BillFieldDefinition definition = toDefinition(node, subTableOwner);
            if (definition != null) {
                result.add(definition);
            }
            // 单据组件本身不会再嵌套子控件，不必继续往下走
            return;
        }
        // 布局容器的子控件在 children 里；子表单类容器在部分设计器版本中放在 props.rule 下。
        // 两处都扫，漏一处就意味着某种布局下单号静默不生成
        collectFromChildren(node.get("children"), subTableOwner, result);
        Object props = node.get("props");
        if (props instanceof Map<?, ?> propsMap) {
            collectFromChildren(((Map<String, Object>) propsMap).get("rule"), subTableOwner, result);
        }
    }

    @SuppressWarnings("unchecked")
    private void collectFromChildren(Object children, Map<String, String> subTableOwner,
                                     List<BillFieldDefinition> result) {
        if (!(children instanceof List<?> list)) {
            return;
        }
        for (Object child : list) {
            // 容器里可能混着纯文本节点（比如一段说明文字），它们不是 Map，直接跳过
            if (child instanceof Map<?, ?> childMap) {
                collect((Map<String, Object>) childMap, subTableOwner, result);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private BillFieldDefinition toDefinition(Map<String, Object> node, Map<String, String> subTableOwner) {
        Object field = node.get("field");
        if (!(field instanceof String fieldName) || StrUtil.isBlank(fieldName)) {
            // 没有 field 的规则存不下值，配了也没意义
            log.warn("[toDefinition][单据组件缺少 field，已跳过]");
            return null;
        }
        BillFieldDefinition definition = new BillFieldDefinition();
        definition.setField(fieldName);
        definition.setTitle(node.get("title") instanceof String title ? title : null);
        definition.setSubTableField(subTableOwner.get(fieldName));

        Object props = node.get("props");
        if (props instanceof Map<?, ?> propsMap) {
            Map<String, Object> p = (Map<String, Object>) propsMap;
            definition.setRuleMode(toInteger(p.get("ruleMode")));
            definition.setTemplateCode(p.get("templateCode") instanceof String code ? code : null);
            if (p.get("ruleConfig") instanceof Map<?, ?> ruleConfig) {
                definition.setRuleConfig((Map<String, Object>) ruleConfig);
            }
        }
        return definition;
    }

    /**
     * 建立「字段 → 所属子表」的索引
     */
    private Map<String, String> buildSubTableOwner(TableBinding binding) {
        if (binding == null || binding.getSubTables() == null) {
            return Collections.emptyMap();
        }
        Map<String, String> owner = new HashMap<>();
        for (SubTableBinding subTable : binding.getSubTables()) {
            if (subTable == null || StrUtil.isBlank(subTable.getField()) || subTable.getFieldMapping() == null) {
                continue;
            }
            for (String field : subTable.getFieldMapping().keySet()) {
                owner.put(field, subTable.getField());
            }
        }
        return owner;
    }

    /**
     * JSON 里的数字可能被反序列化成 Integer / Long / Double，统一转成 Integer
     */
    private Integer toInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && StrUtil.isNotBlank(text)) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

}
