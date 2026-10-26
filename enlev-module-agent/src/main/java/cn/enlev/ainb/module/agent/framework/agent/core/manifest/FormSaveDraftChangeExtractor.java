package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.tool.impl.form.FormSaveDraftTool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code form_save_draft} → {@link ChangeKind#FORM_CREATE}
 *
 * <h3>🔴 刻意不复刻「列表列 / 查询项由 fields 自动推导」那套逻辑</h3>
 * {@code FormSaveDraftTool} 在作者没显式给 {@code listColumns} / {@code searchItems} 时，
 * 会从 {@code fields} 自己推一份出来。那份推导规则是工具的私有实现，
 * 在这里再写一遍就得到第二份口径 —— 漂了不报错，表现是
 * 「清单说列表会显示 5 列、发布出来只有 3 列」。
 *
 * <p>所以本抽取器只报作者<b>写了什么</b>；「平台会推导出什么」交给 S2-d 的干跑
 * （{@code FormDesignApi.inspectFormDesign} 与发布走同一份校验）。
 *
 * <h3>机器码在这里就地翻译成中文</h3>
 * {@code widget:"select"} → 「下拉选择」、{@code operator:"LIKE"} → 「像」。
 * 对照表取自 {@link ManifestLabels}（infra 的控件契约与操作符枚举，与 S1 名称目录同一个出口），
 * 不在本模块抄第三份。翻不出来的原样显示机器码，不抛异常。
 *
 * <h3>行怎么拼不在这里</h3>
 * {@code detail.fields[]} 里每一行的键名、顺序、以及「什么算进查询区」都在
 * {@link ManifestFieldRows}，与 {@code FormUpdateChangeExtractor} 共用 ——
 * 前端把两种行并排渲染在同一张表里，还要断言「字段表里可查询的那些」与「查询条件表」
 * 两个集合<b>恰好相等</b>（迭代七第 08 轮 D4-20 的判据之一）。
 *
 * @author 走进新时代
 */
@Component
public class FormSaveDraftChangeExtractor implements ToolChangeExtractor {

    @Override
    public String toolId() {
        return FormSaveDraftTool.NAME;
    }

    @Override
    public ChangeItem extract(NodeDTO node, ManifestContext ctx) {
        Map<String, Object> args = ManifestArgs.of(node);

        List<Map<String, Object>> fields = new ArrayList<>();
        List<String> searchable = new ArrayList<>();
        for (Map<String, Object> field : ManifestArgs.mapList(args, FormSaveDraftTool.PARAM_FIELDS)) {
            // 行怎么拼、什么算「进查询区」、显示什么名字，三件事都在 ManifestFieldRows 里，
            // 与 FormUpdateChangeExtractor 共用一份 —— 前端要把两种行并排渲染，还要断言两张表集合相等
            Map<String, Object> shown = ManifestFieldRows.of(field, ctx);
            fields.add(shown);
            if (ManifestFieldRows.isSearchable(shown)) {
                String label = ManifestFieldRows.labelOf(shown);
                if (StrUtil.isNotBlank(label)) {
                    searchable.add(label);
                }
            }
        }

        ChangeItem item = new ChangeItem();
        item.setKind(ChangeKind.FORM_CREATE);
        item.getTarget().put("code", ManifestArgs.str(args, FormSaveDraftTool.PARAM_CODE));
        item.getTarget().put("name", ManifestArgs.str(args, FormSaveDraftTool.PARAM_NAME));
        item.getTarget().put("mainTable", ManifestArgs.str(args, FormSaveDraftTool.PARAM_MAIN_TABLE));
        item.getDetail().put("fields", fields);
        item.getDetail().put("fieldCount", fields.size());
        item.getDetail().put("searchableFields", searchable);
        item.setSummary(summary(item.getTarget(), fields.size(), searchable));
        return item;
    }

    /**
     * 一句话：表单名（编码），绑定表 X，共 N 个字段，其中 M 个进查询区
     *
     * <p>表单名带「」，故前面不留空格（与 {@code ProcessDeployChangeExtractor} 同口径）；
     * 表名是 ASCII，故「绑定表 」后面留一个。同一份清单上两种留白规则混着用，
     * 读起来像是两个系统拼的。
     *
     * <p>🔴 这条规则的<b>强制点</b>是 {@code SummarySpacingTest}，不是本注释 ——
     * 它原先只写在注释里，结果另外三个抽取器照样带着洞提交上来。
     */
    private String summary(Map<String, Object> target, int fieldCount, List<String> searchable) {
        String name = (String) target.get("name");
        String code = (String) target.get("code");
        String mainTable = (String) target.get("mainTable");
        StringBuilder sb = new StringBuilder("新建表单");
        sb.append(StrUtil.isBlank(name) ? "（名称未填）" : "「" + name + "」");
        if (StrUtil.isNotBlank(code)) {
            sb.append("（").append(code).append("）");
        }
        if (StrUtil.isNotBlank(mainTable)) {
            sb.append("，绑定表 ").append(mainTable);
        }
        sb.append("，共 ").append(fieldCount).append(" 个字段");
        if (!searchable.isEmpty()) {
            sb.append("，其中 ").append(searchable.size()).append(" 个进查询区（")
                    .append(String.join("、", searchable)).append("）");
        }
        return sb.toString();
    }

}
