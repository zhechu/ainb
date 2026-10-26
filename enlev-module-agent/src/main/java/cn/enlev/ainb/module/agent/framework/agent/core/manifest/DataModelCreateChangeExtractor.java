package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.tool.impl.datamodel.DataModelCreateTool;
import cn.enlev.ainb.module.agent.tool.impl.datamodel.DataModelParamResolver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code datamodel_create} → {@link ChangeKind#TABLE_CREATE}
 *
 * <h3>只报「DSL 里写了什么」，不报「平台将会建出什么」</h3>
 * 后者要调 {@code DataModelApi.previewDdl}，属 {@code ChangeManifestDryRunner}（S2-d）：
 * 系统列（id/creator/create_time/updater/update_time/deleted/tenant_id）、
 * 主键与逻辑删除列的补齐、列类型的规范化，全都在那一步才定下来。
 * 在这里把那些逻辑再抄一遍，就得到了第二份口径 —— 而它漂了不报错，
 * 表现是「清单上的建表语句和真跑出来的不一样」，正是本功能要消灭的缺陷类。
 *
 * <p>参数形态以 {@link DataModelParamResolver#CREATE_PARAMS_SCHEMA} 为准（那是写给模型的权威说明，
 * 与 {@code resolveCreate} 放在一起、要求同时改）。这里读的键名与它逐字一致。
 *
 * @author 走进新时代
 */
@Component
public class DataModelCreateChangeExtractor implements ToolChangeExtractor {

    @Override
    public String toolId() {
        return DataModelCreateTool.NAME;
    }

    @Override
    public ChangeItem extract(NodeDTO node, ManifestContext ctx) {
        Map<String, Object> args = ManifestArgs.of(node);
        // 🔴 建表定义整个嵌在 model 下面一层（DataModelParamResolver.PARAM_MODEL），
        // 少剥这一层就会什么都读不到、然后安静地报「新建 1 张数据表（未知表名）」
        Map<String, Object> model = ManifestArgs.map(args, DataModelParamResolver.PARAM_MODEL);

        ChangeItem item = new ChangeItem();
        item.setKind(ChangeKind.TABLE_CREATE);
        item.getTarget().put("tableName", ManifestArgs.str(model, "tableName"));
        item.getTarget().put("tableComment", ManifestArgs.str(model, "tableComment"));

        List<Map<String, Object>> columns = new ArrayList<>();
        for (Map<String, Object> column : ManifestArgs.mapList(model, "columns")) {
            Map<String, Object> shown = new LinkedHashMap<>();
            shown.put("columnName", ManifestArgs.str(column, "columnName"));
            shown.put("columnComment", ManifestArgs.str(column, "columnComment"));
            shown.put("columnType", ManifestArgs.str(column, "columnType"));
            shown.put("length", ManifestArgs.integer(column, "length"));
            // scale 与 length 是一对：只报 length 的话，DECIMAL(10,2) 在清单上就是「精度 10」，
            // 审批人看不出小数位是 2 —— 而金额列的小数位恰恰是最需要人确认的那个数字
            shown.put("scale", ManifestArgs.integer(column, "scale"));
            shown.put("nullable", ManifestArgs.raw(column, "nullable"));
            columns.add(shown);
        }
        List<Map<String, Object>> indexes = new ArrayList<>();
        for (Map<String, Object> index : ManifestArgs.mapList(model, "indexes")) {
            Map<String, Object> shown = new LinkedHashMap<>();
            shown.put("indexName", ManifestArgs.str(index, "indexName"));
            shown.put("indexType", ManifestArgs.str(index, "indexType"));
            shown.put("columnNames", ManifestArgs.strList(index, "columnNames"));
            indexes.add(shown);
        }
        item.getDetail().put("modelName", ManifestArgs.str(model, "modelName"));
        item.getDetail().put("columns", columns);
        item.getDetail().put("indexes", indexes);
        item.setSummary(summary(args, model, item.getTarget(), columns.size(), indexes.size()));
        return item;
    }

    /**
     * 一句话：表名（注释），共 N 列、M 个索引
     *
     * <p>表名缺失时不编一个，直说「表名未填」—— 建表参数不全是校验器的事，
     * 但清单不能把它显示成一张有名字的表。
     *
     * <p>🔴 {@code model} 整个缺失时另起一句话。此时说「表名未填」是<b>误诊</b>：
     * 真正缺的是整份建表定义（{@code DataModelParamResolver#resolveCreate} 会直接抛
     * 「缺少 model（建表定义）」）。审批人照「表名未填」去补个表名，补完还是跑不通。
     *
     * <p>🔴 而「{@code model} 整个是占位符」又是<b>第三种</b>形态，绝不能共用上面那句话
     * （缺陷 D4-17）：那种写法合法、执行期能跑通（{@code StateTemplateResolver#resolve}
     * 把脚本产出的对象渲染成 JSON 串，{@code resolveCreate} 两种形态都收），
     * 说它「执行时会被拒」就是清单在凭空造一个缺陷。此时该说的是「表名与列要到运行期才算得出，
     * 清单上列不出来」—— 即「无从复核」，不是「写错了」。
     */
    private String summary(Map<String, Object> args, Map<String, Object> model, Map<String, Object> target,
                           int columnCount, int indexCount) {
        String pendingModel = ManifestArgs.pendingObject(args, DataModelParamResolver.PARAM_MODEL);
        if (pendingModel != null) {
            return "新建数据表。整份建表定义由脚本在运行期算出（" + pendingModel
                    + "），表名与列无从在清单上列出，本次没法复核它建的是什么";
        }
        if (model.isEmpty()) {
            return "新建数据表。⚠️ 没有给出建表定义（args.model 缺失或不是对象），执行时会被拒";
        }
        String tableName = (String) target.get("tableName");
        String comment = (String) target.get("tableComment");
        StringBuilder sb = new StringBuilder("新建数据表");
        // 空格放在「有表名」那一边。规则见 SummarySpacingTest：ASCII 两侧留白、全角括号前不留，
        // 反过来的话缺表名这条分支会渲染成「新建数据表 （表名未填）」，读起来像个没填进去的变量
        sb.append(StrUtil.isBlank(tableName) ? "（表名未填）" : " " + tableName);
        if (StrUtil.isNotBlank(comment)) {
            sb.append("（").append(comment).append("）");
        }
        sb.append("，共 ").append(columnCount).append(" 列");
        if (indexCount > 0) {
            sb.append("、").append(indexCount).append(" 个索引");
        }
        return sb.toString();
    }

}
