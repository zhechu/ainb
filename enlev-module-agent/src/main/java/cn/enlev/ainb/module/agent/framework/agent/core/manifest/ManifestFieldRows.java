package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import cn.hutool.core.util.StrUtil;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「表单字段」在清单上的<b>那一行</b>怎么拼 —— 只此一份
 *
 * <h3>为什么不能让两个抽取器各写一遍</h3>
 * {@code FormSaveDraftChangeExtractor}（新建表单）与 {@code FormUpdateChangeExtractor}（改表单）
 * 产出的行会被前端<b>并排</b>渲染在同一张「表单字段」表里，
 * 而「列表查询条件」那张表的每一行<b>就是从这些行里挑出 {@code searchable} 为真的那些</b>。
 * 迭代七第 08 轮的验收判据里有一条<b>双向</b>断言：两张表的字段集合必须<b>恰好相等</b>。
 * 两份行构造只要有一处漂开（少一个键、{@code searchable} 一个透传 raw 一个转布尔、
 * 标题兜底一个用 {@code title} 一个用 {@code label}），那条断言就会红 ——
 * 而红的原因写在另一个文件里，排查的人得先看出「这是两份口径」才知道该去哪儿改。
 *
 * <p>🔴 键名与顺序都是<b>对外契约</b>：前端 {@code ManifestFormFieldVO} 逐字对着它，
 * 改名等于同时改两侧，改之前先 grep 一遍 {@code widgetLabel}。
 *
 * <p>⚠️ 包级可见：这是抽取器的实现细节，不是清单层对外的契约（对外的是那些键名）。
 *
 * @author 走进新时代
 */
final class ManifestFieldRows {

    private ManifestFieldRows() {
    }

    /**
     * 一个字段描述 → 清单上的一行
     *
     * <p>🔴 {@code required} 与 {@code searchable} 是<b>原样透传</b>（{@link ManifestArgs#raw}），
     * 不转布尔：模型常写成 {@code "true"} 字符串，转了就把「作者写了个字符串」这件事藏起来了，
     * 而前端 {@code truthy()} 两种都认，显示上没差别 —— 差别只在排查时看不看得见原文。
     *
     * <p>机器码就地翻译成中文（{@code widget:"select"} → 「下拉选择」），
     * 翻不出来原样显示机器码，绝不抛。对照表见 {@link ManifestLabels}。
     */
    static Map<String, Object> of(Map<String, Object> field, ManifestContext ctx) {
        String widget = ManifestArgs.str(field, "widget");
        String operator = ManifestArgs.str(field, "operator");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("field", ManifestArgs.str(field, "field"));
        row.put("title", ManifestArgs.str(field, "title"));
        row.put("widget", widget);
        row.put("widgetLabel", ctx.labels().widgetLabel(widget));
        row.put("jdbcType", ManifestArgs.str(field, "jdbcType"));
        row.put("required", ManifestArgs.raw(field, "required"));
        row.put("dictType", ManifestArgs.str(field, "dictType"));
        row.put("searchable", ManifestArgs.raw(field, "searchable"));
        row.put("operator", operator);
        row.put("operatorLabel", ctx.labels().operatorLabel(operator));
        return row;
    }

    /**
     * 这个字段算不算「进查询区」
     *
     * <p>与 {@link #of} 里那次透传刻意<b>不同口径</b>：透传要的是原文（好让排查的人看见
     * 作者到底写了什么），这里要的是结论。
     *
     * <p>🔴 认「真」的四种写法逐字对齐前端的 {@code truthy}
     * （{@code ChangeManifestCard.vue}：{@code v === true || v === 'true' || v === 1 || v === '1'}），
     * 也对齐 e2e 里数查询条件用的那个集合。<b>不在这里另立一套</b>的理由很实在：
     * 「列表查询条件」那张表是前端按 {@code truthy(f.searchable)} 挑出来的，
     * 而 summary 与 {@code detail.searchableFields} 是后端按本方法挑的 ——
     * 两套口径只要差一种写法，同一份清单上就会出现「表格里明明列了一个查询条件，
     * 摘要却说这次不涉及查询区」。这类自相矛盾比少显示一项更难查，因为两边都没报错。
     *
     * <p>⚠️ 别顺手放宽成「非 false 就算真」：模型漏写 {@code searchable} 是常态
     * （实测 2026-10-08 基线轮的 def #564 八个字段一个都没写），
     * 放宽的表现是每个字段都进查询区，而那份清单就再也没人能信了。
     */
    static boolean isSearchable(Map<String, Object> row) {
        Object value = row.get("searchable");
        return Boolean.TRUE.equals(value) || "true".equals(value)
                || Integer.valueOf(1).equals(value) || "1".equals(value);
    }

    /**
     * 这个字段在清单上叫什么：标题优先，没写标题退回字段名
     *
     * <p>退回字段名而不是留空：查询区里显示一个空白框，比显示 {@code dept} 更让人摸不着头脑
     * （单测 {@code FormDesignChangeExtractorTest#translatesMachineCodesAndListsSearchableFields} 钉住）。
     */
    static String labelOf(Map<String, Object> row) {
        return StrUtil.blankToDefault((String) row.get("title"), (String) row.get("field"));
    }

}
