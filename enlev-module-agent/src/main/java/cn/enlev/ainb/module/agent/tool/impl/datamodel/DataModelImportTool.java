package cn.enlev.ainb.module.agent.tool.impl.datamodel;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.mybatis.core.util.SystemTablePolicy;
import cn.enlev.ainb.framework.mybatis.core.util.TableOperation;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.infra.api.datamodel.DataModelApi;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_TOOL_PARAM_INVALID;

/**
 * 工具：把已存在的物理表登记进数据模型（方案 §5.2 的路径优先级）
 *
 * <h3>它为什么重要</h3>
 * 「建表单」有两条入口，风险差一个数量级：
 * <ul>
 *   <li><b>表已存在</b>：查到 → 绑上去 → 发布。全程无 DDL，完全可逆</li>
 *   <li><b>表不存在</b>：先建表。含<b>不可逆</b>操作</li>
 * </ul>
 * 但「表已存在」这条路有个前提：那张表得能被工具看见、读得到结构。
 * 没登记进数据模型的表，{@code datamodel_structure} 读不到、
 * {@code datamodel_update} 也改不了 —— 于是模型只能退回去建新表，
 * 平白多出一次不可逆操作，还多出一张重复的表。
 *
 * <p>这个工具就是把那条低风险路径接通。
 *
 * <h3>风险等级是 LOW</h3>
 * 它<b>不执行任何 DDL</b>，只是把表的元数据读进 {@code infra_data_model}。
 * 表本身一个字节都不会变，登记错了删掉模型即可（删模型默认不删物理表）。
 * 但它仍要进 Plan —— 铁律是「用户确认前不执行任何写操作」。
 *
 * @author 走进新时代
 */
@Component
@AgentTool(
        name = DataModelImportTool.NAME,
        displayName = "登记已有物理表",
        groupName = "数据建模",
        description = "把数据库里已存在、但还没登记进数据模型的物理表登记进来。"
                + "不执行任何 DDL，表本身不会变。"
                + "什么时候用：想复用一张已有的表建表单或改表，但 datamodel_list 里查不到它 —— "
                + "先用本工具登记，之后才能读结构、改字段。"
                + "⚠️ 能复用已有表就不要建新表，建表是不可逆的",
        permission = "infra:data-model:create",
        risk = RiskLevel.LOW)
@Slf4j
public class DataModelImportTool implements AgentToolFunction {

    /**
     * 工具键
     *
     * <p>🔴 上面注解的 {@code name} 与变更清单抽取器的 {@code toolId()} 都必须引用它。
     * 三处各写一遍字面量时，改名不会报错 —— 只会让清单静默退回兜底解读，
     * 表现是「登记表」在确认框里显示成「调用工具」。
     */
    public static final String NAME = "datamodel_import";

    public static final String PARAM_TABLE_NAMES = "tableNames";

    @Resource
    private DataModelApi dataModelApi;

    @Override
    public Object execute(Map<String, Object> params) {
        List<String> tableNames = parseTableNames(params);

        // 已登记的直接跳过，不要报错 —— 模型重试或并发时很容易撞上，
        // 而「已经登记了」本来就是想要的结果
        Set<String> registered = dataModelApi.getDataModelList().stream()
                .map(DataModelApi.DataModelSimpleRespDTO::getTableName)
                .filter(StrUtil::isNotBlank)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        List<String> toImport = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (String name : tableNames) {
            // 🔴 框架表一律拒绝。登记本身不改表，但登记之后 datamodel_update 就能改它了 ——
            //    把 sys_grant 登记进来，等于给改权限表开了一条路
            if (!SystemTablePolicy.isAllowed(name, TableOperation.REGISTER)) {
                throw exception(AGENT_TOOL_PARAM_INVALID, "datamodel_import",
                        "「" + name + "」是平台框架表，不允许登记进数据模型。"
                                + "登记本身不改表，但登记之后就能通过改表工具动它了");
            }
            if (registered.contains(name.toLowerCase(Locale.ROOT))) {
                skipped.add(name);
            } else {
                toImport.add(name);
            }
        }

        if (!toImport.isEmpty()) {
            dataModelApi.importTables(toImport);
            log.info("[execute][AI 登记物理表完成：{}]", toImport);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("imported", toImport);
        resp.put("alreadyRegistered", skipped);
        resp.put("message", toImport.isEmpty()
                ? "这些表都已经登记过了，可以直接用 datamodel_structure 读结构"
                : "已登记 " + toImport.size() + " 张表（未执行任何 DDL）。"
                        + "现在可以用 datamodel_structure 读它们的结构了");
        return resp;
    }

    private List<String> parseTableNames(Map<String, Object> params) {
        Object raw = params.get(PARAM_TABLE_NAMES);
        if (raw == null) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "datamodel_import",
                    "缺少 tableNames（要登记的表名数组）");
        }
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "datamodel_import",
                    "tableNames 必须是非空的表名数组");
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item != null && StrUtil.isNotBlank(item.toString())) {
                result.add(item.toString().trim());
            }
        }
        if (result.isEmpty()) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "datamodel_import",
                    "tableNames 里没有有效的表名");
        }
        return result;
    }

    /** 见 {@link AgentToolFunction#resultArrayKeys}：本工具全部返回分支里数组值的键（并集） */
    @Override
    public List<String> resultArrayKeys() {
        return List.of("imported", "alreadyRegistered");
    }

    @Override
    public String paramsSchema() {
        return """
                tableNames（必填，字符串数组）：要登记的物理表名，
                  从 datamodel_physical_tables 的结果里取 registered=false 的那些。
                已经登记过的表会被跳过，不会报错。平台框架表会被拒绝。""";
    }

}
