package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.tool.impl.datamodel.DataModelParamResolver;
import cn.enlev.ainb.module.agent.tool.impl.datamodel.DataModelUpdateTool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code datamodel_update} → {@link ChangeKind#TABLE_ALTER}
 *
 * <h3>为什么「改了哪几项」要照 DSL 原文列出来，而不是自己判哪些键有效</h3>
 * {@code DataModelParamResolver.applyChange} 只认
 * {@code columnComment/columnType/length/scale/nullable/defaultValue} 这六个键，
 * 其余<b>静默丢弃</b>。在这里再抄一份键名白名单，就得到了第二份口径 ——
 * 而它漂了不报错，表现是「清单说会改注释、实际没改」。
 *
 * <p>所以本抽取器只把 DSL 里<b>实际写了的键名</b>原样列出来。用户看到
 * 「emp_name：将改 columnComment、lenght」时，那个拼错的键自己就跳出来了 ——
 * 不需要清单去维护一份会过期的判断。真正的取舍由 S2-d 的干跑给出
 * （{@code previewDdl} 与真实执行共用同一份校验）。
 *
 * @author 走进新时代
 */
@Component
public class DataModelUpdateChangeExtractor implements ToolChangeExtractor {

    /** {@code columnName} 是定位用的，不算「要改的项」 */
    private static final String KEY_COLUMN_NAME = "columnName";

    @Override
    public String toolId() {
        return DataModelUpdateTool.NAME;
    }

    @Override
    public ChangeItem extract(NodeDTO node, ManifestContext ctx) {
        Map<String, Object> args = ManifestArgs.of(node);

        List<Map<String, Object>> added = new ArrayList<>();
        for (Map<String, Object> column : ManifestArgs.mapList(args, DataModelParamResolver.PARAM_ADD_COLUMNS)) {
            Map<String, Object> shown = new LinkedHashMap<>();
            shown.put(KEY_COLUMN_NAME, ManifestArgs.str(column, KEY_COLUMN_NAME));
            shown.put("columnComment", ManifestArgs.str(column, "columnComment"));
            shown.put("columnType", ManifestArgs.str(column, "columnType"));
            shown.put("length", ManifestArgs.integer(column, "length"));
            // 与 DataModelCreateChangeExtractor 同理：只报 length 的话 DECIMAL(10,2) 看不出小数位
            shown.put("scale", ManifestArgs.integer(column, "scale"));
            added.add(shown);
        }
        List<Map<String, Object>> modified = new ArrayList<>();
        List<String> modifiedNames = new ArrayList<>();
        for (Map<String, Object> column : ManifestArgs.mapList(args, DataModelParamResolver.PARAM_MODIFY_COLUMNS)) {
            String name = ManifestArgs.str(column, KEY_COLUMN_NAME);
            List<String> changedKeys = new ArrayList<>();
            for (String key : column.keySet()) {
                if (!KEY_COLUMN_NAME.equals(key)) {
                    changedKeys.add(key);
                }
            }
            Map<String, Object> shown = new LinkedHashMap<>();
            shown.put(KEY_COLUMN_NAME, name);
            shown.put("changedKeys", changedKeys);
            modified.add(shown);
            if (StrUtil.isNotBlank(name)) {
                modifiedNames.add(name);
            }
        }
        List<String> dropped = ManifestArgs.strList(args, DataModelParamResolver.PARAM_DROP_COLUMNS);

        ChangeItem item = new ChangeItem();
        item.setKind(ChangeKind.TABLE_ALTER);
        item.getTarget().put("tableName", ManifestArgs.str(args, DataModelParamResolver.PARAM_TABLE_NAME));
        item.getDetail().put("addColumns", added);
        item.getDetail().put("modifyColumns", modified);
        item.getDetail().put("dropColumns", dropped);
        item.setSummary(summary(item.getTarget(), added.size(), modified, modifiedNames, dropped));
        return item;
    }

    private String summary(Map<String, Object> target, int addCount, List<Map<String, Object>> modified,
                           List<String> modifiedNames, List<String> dropped) {
        String tableName = (String) target.get("tableName");
        StringBuilder sb = new StringBuilder("修改表");
        // 空格跟着表名走，不放在打底串里。规则见 SummarySpacingTest
        sb.append(StrUtil.isBlank(tableName) ? "（表名未填）" : " " + tableName + " ").append("的结构：");
        List<String> parts = new ArrayList<>();
        if (addCount > 0) {
            parts.add("新增 " + addCount + " 列");
        }
        if (!modified.isEmpty()) {
            // 改列比加列危险：它动的是已有数据的定义。把列名说出来，别只给个数
            parts.add("修改 " + modified.size() + " 列（" + String.join("、", modifiedNames) + "）");
        }
        if (!dropped.isEmpty()) {
            parts.add("删除 " + dropped.size() + " 列（" + String.join("、", dropped) + "）");
        }
        if (parts.isEmpty()) {
            // addColumns/modifyColumns/dropColumns 全空时工具会拒（DataModelParamResolver#resolveUpdate），
            // 但清单不替校验器判，只如实说「没看出要改什么」
            return sb.append("未看出要改什么（三组列变更都是空的）").toString();
        }
        return sb.append(String.join("、", parts)).toString();
    }

}
