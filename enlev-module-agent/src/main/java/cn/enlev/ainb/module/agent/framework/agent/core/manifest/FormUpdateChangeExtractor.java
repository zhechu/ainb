package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.ToolNodeExecutor;
import cn.enlev.ainb.module.agent.tool.impl.form.FormPublishBundleTool;
import cn.enlev.ainb.module.agent.tool.impl.form.FormUpdateTool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code form_update} → {@link ChangeKind#FORM_UPDATE}
 *
 * <h3>这个抽取器要跨节点看一件事：改完有没有人发布</h3>
 * {@code form_update} 改完，表单状态变成「已发布有改动」，<b>用户看到的仍是旧的</b>，
 * 必须再跑一次 {@code form_publish_bundle} 才生效（见 {@code FormUpdateTool} 类注释）。
 * 这是本模块最典型的一类缺陷：「配置保存成功但不生效且无提示」。
 * 清单是唯一一个能在<b>点同意之前</b>把这件事说出来的地方 —— 执行完再提示，用户已经走了。
 *
 * <p>🔴 只在「下游一个发布节点都没有」时才提示，因此不会误报：
 * 下游有发布节点时，它发的究竟是不是这张表单要查库才知道（S2-d 的干跑负责），
 * 那时不提示总比提示错了好。可达性走 {@link ManifestContext#graph()}，
 * 与编译器同口径（S2-b 收敛出的那一份）。
 *
 * <h3>{@code patch} 的键只列名字，不列值</h3>
 * 表单定义里的值是整份 formConf / 校验规则 / 打印模板，塞进清单会把这份要冻结进
 * BPM 流程变量的 JSON 撑到几十 KB，而对「看懂要改什么」毫无帮助 ——
 * 用户要看的是「动了哪几项」，不是那几项的新内容。
 *
 * <p>⚠️ 这条约束有一个刻意的例外：{@code patch} 里的<b>字段结构</b>要展开成
 * {@code detail.fields[]}（见下一节）。字段名、控件、是否进查询区恰恰是
 * 「看懂要改什么」最需要的那几项，而一份 formFields 撑死几十行 ——
 * 与整份 formConf 不是一个量级。
 *
 * <h3>🔴 改表也要能新增查询条件（迭代七第 08 轮 · 裁定 R-02）</h3>
 * 此前本抽取器<b>完全不看</b> patch 里的字段，只列键名，于是「查询条件」这条通道
 * 对 FORM_UPDATE 永远是空的 —— 而前端「表单字段」那张表<b>认</b> FORM_UPDATE。
 * 两侧口径分岔的表现是：同一份清单上，改表这一项在「表单」区块里有一行，
 * 在「列表查询条件」区块里什么都没有，审批人以为这次改动不涉及查询。
 * 裁定 R-02 原话：「让改表也能新增查询条件。」
 *
 * <p>patch 里能表达「这个字段要进查询区」的写法有三种，<b>都要认</b>：
 * <ul>
 *   <li>{@code patch.formFields[]} —— 工具认的那个键，但模型常把<b>字段描述</b>
 *       （{@code {field,widget,title,searchable}}）写进去，而 DTO 上那一格是
 *       {@code List<String>}（form-create 的 rule 串）。清单照出作者写了什么，
 *       不替他判执行期会不会跑通（那是校验器与干跑的事）。</li>
 *   <li>{@code patch.fields[]} —— <b>不是</b>可改字段，{@code applyPatch} 会直接报错。
 *       实测模型真写过（2026-10-05 真跑出的 {@code agent_workflow_def} #467，
 *       {@code patch.fields[0].searchable=true}）。照出的理由同上：审批人要在点同意前
 *       看见「这一项写歪了」，而不是等执行期撞一句报错。</li>
 *   <li>{@code patch.listConf} 里的 {@code searchItems[]} —— 改表加查询条件
 *       <b>唯一「保存后真的生效」</b>的路径（{@code FormSaveDraftTool#buildListConf}
 *       产出的就是这一格，运行时列表页读的也是它）。searchItem 本身就是
 *       「这个字段可查」的声明，故一律按 {@code searchable=true} 记。</li>
 * </ul>
 * 三处提到同一个字段时并成一行、空位互补，见 {@link #mergeInto}。
 *
 * @author 走进新时代
 */
@Component
public class FormUpdateChangeExtractor implements ToolChangeExtractor {

    /**
     * patch 里可能装字段数组的两个键
     *
     * <p>{@code formFields} 是工具认的，{@code fields} 不是（会报「不是可修改的表单字段」）——
     * 但两个都要认，理由见类注释。顺序即合并时的优先级：先到的占住那一行。
     */
    private static final List<String> FIELD_ARRAY_KEYS = List.of("formFields", "fields");

    /** {@code patch} 里装列表配置的键；DTO 上那一格是 JSON 串，{@link ManifestArgs#map} 两种形态都收 */
    private static final String KEY_LIST_CONF = "listConf";
    /** listConf 里装查询项的键；与 {@code FormSaveDraftTool#buildListConf} 同源 */
    private static final String KEY_SEARCH_ITEMS = "searchItems";

    /** {@link #pendingNote} 里占位符原文的截断长度；占位符本该很短，长了说明作者把整段脚本塞进了 args */
    private static final int MAX_PENDING_CHARS = 80;

    @Override
    public String toolId() {
        return FormUpdateTool.NAME;
    }

    @Override
    public ChangeItem extract(NodeDTO node, ManifestContext ctx) {
        Map<String, Object> args = ManifestArgs.of(node);
        Map<String, Object> patch = ManifestArgs.map(args, FormUpdateTool.PARAM_PATCH);
        String patchPending = ManifestArgs.pendingObject(args, FormUpdateTool.PARAM_PATCH);
        List<String> patchKeys = new ArrayList<>(patch.keySet());
        List<String> clearFields = ManifestArgs.strList(args, FormUpdateTool.PARAM_CLEAR_FIELDS);
        boolean needsRepublish = !hasDownstreamPublish(node.getId(), ctx);

        List<Map<String, Object>> fields = patchFields(patch, ctx);
        List<String> searchable = new ArrayList<>();
        for (Map<String, Object> row : fields) {
            if (ManifestFieldRows.isSearchable(row)) {
                String label = ManifestFieldRows.labelOf(row);
                if (StrUtil.isNotBlank(label)) {
                    searchable.add(label);
                }
            }
        }

        ChangeItem item = new ChangeItem();
        item.setKind(ChangeKind.FORM_UPDATE);
        item.getTarget().put("formId", ManifestArgs.str(args, FormUpdateTool.PARAM_ID));
        item.getDetail().put("patchKeys", patchKeys);
        item.getDetail().put("clearFields", clearFields);
        item.getDetail().put("needsRepublish", needsRepublish);
        item.getDetail().put("fields", fields);
        item.getDetail().put("fieldCount", fields.size());
        item.getDetail().put("searchableFields", searchable);
        if (patchPending != null) {
            item.getDetail().put("patchPendingNote", pendingNote(patchPending));
        }
        item.setSummary(summary(item.getTarget(), patchKeys, patchPending, searchable,
                clearFields, needsRepublish));
        return item;
    }

    /**
     * 从 patch 里认出字段行：两个字段数组 + listConf 里的查询项，同名字段并成一行
     *
     * <p>没有字段名的行排在最后、且<b>不去重</b>：认不出是谁就不该猜，
     * 宁可多列一行让审批人看见「这里有个说不清的字段」。
     */
    private List<Map<String, Object>> patchFields(Map<String, Object> patch, ManifestContext ctx) {
        Map<String, Map<String, Object>> byField = new LinkedHashMap<>();
        List<Map<String, Object>> unnamed = new ArrayList<>();
        for (String key : FIELD_ARRAY_KEYS) {
            for (Map<String, Object> field : ManifestArgs.mapList(patch, key)) {
                collect(byField, unnamed, ManifestFieldRows.of(field, ctx));
            }
        }
        for (Map<String, Object> item
                : ManifestArgs.mapList(ManifestArgs.map(patch, KEY_LIST_CONF), KEY_SEARCH_ITEMS)) {
            collect(byField, unnamed, ManifestFieldRows.of(searchItemAsField(item), ctx));
        }
        List<Map<String, Object>> rows = new ArrayList<>(byField.values());
        rows.addAll(unnamed);
        return rows;
    }

    /**
     * 一个 listConf 查询项 → 字段描述的形状，好走 {@link ManifestFieldRows#of} 那唯一一份行构造
     *
     * <p>键名对不上是两种配置各自的历史：查询项用 {@code label}，字段描述用 {@code title}。
     * 在这里对齐，比让行构造认两套键名便宜。
     *
     * <p>⚠️ 查询项的 {@code widget} 是<b>查询控件</b>机器码（{@code TEXT / SELECT / DATE_RANGE /
     * NUMBER_RANGE / SWITCH}，见 {@code FormSaveDraftTool#buildSearchItem}），
     * 与表单控件的机器码（{@code input / select / datePicker}）不是一个目录，
     * 所以 {@code widgetLabel} 大概率翻不出来、原样显示机器码。这是诚实的：
     * 硬拿表单控件表去翻，翻出来的会是一个看着像中文、其实指错了控件的名字。
     */
    private Map<String, Object> searchItemAsField(Map<String, Object> item) {
        Map<String, Object> asField = new LinkedHashMap<>();
        asField.put("field", ManifestArgs.str(item, "field"));
        asField.put("title", ManifestArgs.str(item, "label"));
        asField.put("widget", ManifestArgs.str(item, "widget"));
        asField.put("dictType", ManifestArgs.str(item, "dictType"));
        asField.put("operator", ManifestArgs.str(item, "operator"));
        asField.put("searchable", Boolean.TRUE);
        return asField;
    }

    private void collect(Map<String, Map<String, Object>> byField, List<Map<String, Object>> unnamed,
                         Map<String, Object> row) {
        String name = (String) row.get("field");
        if (StrUtil.isBlank(name)) {
            unnamed.add(row);
            return;
        }
        Map<String, Object> existing = byField.putIfAbsent(name, row);
        if (existing != null) {
            mergeInto(existing, row);
        }
    }

    /**
     * 同一个字段被两处提到时并成一行：空位互补，{@code searchable} 取「或」
     *
     * <p>不合并的后果不是少一行，是<b>多一行</b>：{@code formFields} 里写了
     * {@code searchable:false}、{@code listConf.searchItems} 里又列了同一个字段，
     * 清单上就会出现两行同名字段、一行「否」一行「是」——
     * 而前端「查询条件」那张表是从这些行里挑 {@code searchable} 为真的，
     * 于是同一份清单会自相矛盾。
     *
     * <p>{@code searchable} 取「或」而不是「后到的覆盖」：查询项是「这个字段可查」的<b>声明</b>，
     * 声明过了就不该被另一处一个 {@code false} 抹掉。
     */
    private void mergeInto(Map<String, Object> into, Map<String, Object> from) {
        for (Map.Entry<String, Object> entry : from.entrySet()) {
            Object current = into.get(entry.getKey());
            boolean blank = current == null || (current instanceof String s && StrUtil.isBlank(s));
            if (blank && entry.getValue() != null) {
                into.put(entry.getKey(), entry.getValue());
            }
        }
        if (ManifestFieldRows.isSearchable(from)) {
            into.put("searchable", Boolean.TRUE);
        }
    }

    /**
     * 本节点之后有没有 {@code form_publish_bundle} 节点
     */
    private boolean hasDownstreamPublish(String nodeId, ManifestContext ctx) {
        Set<String> reachable = ctx.graph().reachableFrom(nodeId);
        for (String id : reachable) {
            NodeDTO downstream = ctx.node(id);
            if (downstream != null
                    && ToolNodeExecutor.TYPE.equals(downstream.getType())
                    && FormPublishBundleTool.NAME.equals(downstream.configStr("toolId"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * patch 整个写成了占位符（由前置 script 节点算出来）时，如实说「清单看不到里面」
     *
     * <p>🔴 不说这句话的代价：{@link ManifestArgs#map} 认不出占位符会返回空 Map，
     * 于是 {@code patchKeys} 是空的，summary 落到「没有给出要改的项」——
     * 一句<b>假话</b>（作者给了，只是给在别处），而且它把「这一项我看不见」
     * 说成了「这一项没有内容」。实测这种写法真出现过（2026-10-08 基线轮真跑出的
     * {@code agent_workflow_def} #566：{@code "patch": "{{$.patch_payload.patch}}"}，
     * 内容在前置 {@code prepare_patch} 脚本节点里），
     * 与 {@link ManifestArgs#argsShapeProblem} 处置的是同一族形状。
     *
     * <p>同一句话既进 summary 也进 {@code detail.patchPendingNote}（前端在
     * 「表单」区块里原样显示），故只在这里写一遍。
     */
    private String pendingNote(String placeholder) {
        return "要改的项由前置节点算出（" + StrUtil.maxLength(placeholder, MAX_PENDING_CHARS)
                + "），清单看不到具体改了什么";
    }

    private String summary(Map<String, Object> target, List<String> patchKeys, String patchPending,
                           List<String> searchable, List<String> clearFields, boolean needsRepublish) {
        String formId = (String) target.get("formId");
        StringBuilder sb = new StringBuilder("修改表单");
        // 空格跟着编号走，不放在打底串里。规则见 SummarySpacingTest
        sb.append(StrUtil.isBlank(formId) ? "（编号未填）" : " #" + formId);
        if (!patchKeys.isEmpty()) {
            sb.append("：改动 ").append(String.join("、", patchKeys));
        } else if (patchPending != null) {
            sb.append("：").append(pendingNote(patchPending));
        } else {
            sb.append("：没有给出要改的项");
        }
        if (!searchable.isEmpty()) {
            // 措辞刻意不说「新增」：清单不查库，无从知道这几个字段原先在不在查询区，
            // 说「新增」就是把没核实的事说成核实过（与 FORM_CREATE 的「其中 M 个进查询区」同口径）
            sb.append("；带 ").append(searchable.size()).append(" 个字段进查询区（")
                    .append(String.join("、", searchable)).append("）");
        }
        if (!clearFields.isEmpty()) {
            // 清空是「有意删掉」，与「忘了传」在数据上无法区分，所以工具要求显式写出来；
            // 清单同样要把它说出来，否则用户以为自己只是改了几个字段
            sb.append("；并清空 ").append(String.join("、", clearFields));
        }
        if (needsRepublish) {
            sb.append("。⚠️ 这条流程里它后面没有发布节点，改完不会生效，用户看到的仍是旧表单");
        }
        return sb.toString();
    }

}
