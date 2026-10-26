package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.tool.impl.datamodel.DataModelImportTool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@code datamodel_import} → {@link ChangeKind#TABLE_REGISTER}
 *
 * <p>「登记」而不是「新建」：这些表<b>已经在库里</b>，工具只是把它们纳进数据模型的管理范围。
 * 与 {@link ChangeKind#TABLE_CREATE} 分成两种，是因为二者对用户的含义完全相反 ——
 * 一个是不可逆地动库，一个是可逆地加一条元数据（LOW 风险）。
 * 混成一种的话，「本次含不可逆操作」这句提示就会在只做了登记时也说出口，
 * 说多了的警告和说少了一样没人信。
 *
 * @author 走进新时代
 */
@Component
public class DataModelImportChangeExtractor implements ToolChangeExtractor {

    @Override
    public String toolId() {
        return DataModelImportTool.NAME;
    }

    @Override
    public ChangeItem extract(NodeDTO node, ManifestContext ctx) {
        Map<String, Object> args = ManifestArgs.of(node);
        List<String> tableNames = ManifestArgs.strList(args, DataModelImportTool.PARAM_TABLE_NAMES);

        ChangeItem item = new ChangeItem();
        item.setKind(ChangeKind.TABLE_REGISTER);
        item.getTarget().put("tableNames", tableNames);
        item.getDetail().put("count", tableNames.size());
        item.setSummary(tableNames.isEmpty()
                // 执行期要求 tableNames 必须是真数组（写成字符串会被拒），
                // 但清单不替执行器判类型，只如实说认不出来
                ? "登记已有表（未认出表名，请检查 tableNames 是否写成了数组）"
                : "登记 " + tableNames.size() + " 张已有表：" + String.join("、", tableNames));
        return item;
    }

}
