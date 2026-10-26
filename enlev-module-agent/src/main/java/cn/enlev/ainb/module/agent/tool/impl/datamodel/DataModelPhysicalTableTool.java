package cn.enlev.ainb.module.agent.tool.impl.datamodel;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.mybatis.core.util.SystemTablePolicy;
import cn.enlev.ainb.framework.mybatis.core.util.TableOperation;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.infra.api.datamodel.DataModelApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具：查库里的物理表（含未登记的），并顺带报出<b>反方向</b>的不一致
 *
 * <h3>与 {@code datamodel_list} 的分工</h3>
 * {@code datamodel_list} 只看得到<b>已登记</b>的模型 —— 而「能不能复用一张已有的表」
 * 这个问题，答案往往在未登记的那一批里。只查已登记的，结论就会是「没有可复用的表」，
 * 于是白白建一张新表（不可逆），库里还多出一张重复的。
 *
 * <p>所以判断「要不要建新表」的正确顺序是：
 * {@code datamodel_list} → 没有 → <b>本工具</b> → 找到了就
 * {@code datamodel_import} 登记 → 读结构 → 复用。
 *
 * <h3>🔴 本工具还是这两个读者之间唯一的对账位，所以它必须<b>双向</b></h3>
 * 「表存不存在」这个问题在平台上有两个读者，它们会给出相反答案而且<b>都不报错</b>：
 * <ul>
 *   <li><b>逻辑登记</b>（{@code infra_data_model}）：{@code datamodel_list}、{@code datamodel_structure}</li>
 *   <li><b>物理表</b>（INFORMATION_SCHEMA）：本工具的 {@code items}、主表存在性硬校验</li>
 * </ul>
 * {@code items} 上的 {@code registered} 标只覆盖了「物理表在、登记没有」这一个方向。
 * 反方向「登记在、物理表没有」（<b>悬挂登记</b>）结构上不可能出现在 {@code items} 里 ——
 * 那个循环遍历的是物理表，不存在的表压根不进论域。于是本工具额外给出
 * {@code danglingRegistrations}（详见 {@link #appendDanglingRegistrations}）。
 *
 * <p>台账 #93 就是这个反向盲区的活样本：{@code biz_device} 的登记行挂了两天没人发现，
 * 最后靠手写一次性脚本才捞出来。
 *
 * @author 走进新时代
 */
@Component
@AgentTool(
        name = "datamodel_physical_tables",
        displayName = "查询数据库物理表",
        groupName = "数据建模",
        description = "查数据库里真实存在的物理表，包括还没登记进数据模型的那些。"
                + "判断「要不要建新表」时必须查它 —— datamodel_list 只看得到已登记的，"
                + "只查那个会误判成「没有可复用的表」，然后白白建一张重复的表（还是不可逆的）。"
                + "结果里 registered=false 的，用 datamodel_import 登记后即可复用。"
                + "另外它还会报 danglingRegistrations：登记着但物理表已不在的悬挂登记"
                + "（这类不一致两个读者都不报错，只有这里看得见）",
        permission = "infra:data-model:query",
        risk = RiskLevel.READ_ONLY)
public class DataModelPhysicalTableTool implements AgentToolFunction {

    public static final String PARAM_KEYWORD = "keyword";

    /** 一次最多返回多少张，避免把几百张表全灌给模型 */
    private static final int MAX_ITEMS = 60;

    @Resource
    private DataModelApi dataModelApi;

    @Override
    public Object execute(Map<String, Object> params) {
        Object keywordParam = params.get(PARAM_KEYWORD);
        String keyword = keywordParam == null ? null : StrUtil.trimToNull(keywordParam.toString());

        List<Map<String, Object>> items = new ArrayList<>();
        int frameworkFiltered = 0;
        for (DataModelApi.PhysicalTableRespDTO table : dataModelApi.getPhysicalTableList(keyword)) {
            // 框架表对业务场景没有意义，而且它们也登记不进来（REGISTER 闸门会拒）。
            // 列出来只会占满结果、把真正有用的表挤掉
            if (!SystemTablePolicy.isAllowed(table.getTableName(), TableOperation.REGISTER)) {
                frameworkFiltered++;
                continue;
            }
            if (items.size() >= MAX_ITEMS) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tableName", table.getTableName());
            item.put("tableComment", table.getTableComment());
            item.put("registered", table.getRegistered());
            items.add(item);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", items.size());
        resp.put("items", items);
        if (frameworkFiltered > 0) {
            // 如实报出被滤掉的数量 —— 悄悄少给结果会让人以为库里就这么点表
            resp.put("frameworkTablesFiltered", frameworkFiltered);
        }
        appendDanglingRegistrations(resp);
        resp.put("message", "registered=false 的表还没登记进数据模型，"
                + "读不到结构也改不了；用 datamodel_import 登记后即可复用。"
                + (items.size() >= MAX_ITEMS
                        ? "结果已截断到 " + MAX_ITEMS + " 条，请带 keyword 缩小范围" : ""));
        return resp;
    }

    /**
     * 补上<b>反方向</b>的对账结果：登记了、物理表却不在（悬挂登记）
     *
     * <p>🔴 为什么非补不可：上面那个循环遍历的是<b>物理表</b>，{@code registered} 标只是它的查找表。
     * 悬挂登记的物理表不存在，所以它<b>永远不会被那个循环遍历到</b> —— 不是漏了个分支，是方向反了。
     * 后果实测过（台账 #93）：{@code biz_device} 的登记行在库里挂了两天，
     * 逻辑侧的读者说「有这张表」、物理侧的读者说「没有」，<b>两边都不报错</b>，
     * 最后是手写一次性脚本才发现的。本方法就是为了「下次不用手写脚本」。
     *
     * <p>⚠️ <b>「danglingRegistrations 是空的」不等于「没有悬挂登记」</b>：
     * 预留模块前缀（{@code hrm_}/{@code fms_} 这类根 pom 里注释掉、不参与编译的模块）
     * 那一批被滤掉了，实测有 80 条，它们本来就没表、删了反而在模块启用时坏事。
     * 所以滤掉的必须<b>按前缀报数</b>，不能只给个总数 ——
     * 万一哪天 {@code crm_} 底下冒出一条（正常路径进不来，得靠带外 SQL），
     * 前缀名会让它露出来，纯数字不会。
     */
    private void appendDanglingRegistrations(Map<String, Object> resp) {
        List<Map<String, Object>> dangling = new ArrayList<>();
        Map<String, Integer> reservedByPrefix = new LinkedHashMap<>();
        for (DataModelApi.DanglingRegistrationRespDTO row : dataModelApi.getDanglingRegistrationList()) {
            if (!SystemTablePolicy.isAllowed(row.getTableName(), TableOperation.REGISTER)) {
                // 与 frameworkFiltered 同一个谓词，但这里是**反着用**：
                // 上面用它挡「不许登记进来的表」，这里用它认「这属未启用模块，本来就没表」
                String prefix = reservedPrefixOf(row.getTableName());
                reservedByPrefix.merge(prefix, 1, Integer::sum);
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("tableName", row.getTableName());
            item.put("modelName", row.getModelName());
            dangling.add(item);
        }
        resp.put("danglingRegistrations", dangling);
        if (!reservedByPrefix.isEmpty()) {
            resp.put("danglingReservedModules", reservedByPrefix);
        }
        if (!dangling.isEmpty()) {
            resp.put("danglingMessage", "danglingRegistrations 里这些表**登记着但物理表不在**："
                    + "读逻辑登记的（datamodel_list / datamodel_structure）会说「有」，"
                    + "读物理表的会说「没有」，两边都不报错。"
                    + "先确认表是不是被带外删了，再决定是重建表还是删登记"
                    + "（删登记走 DELETE /infra/data-model/delete?id=&dropTable=false，id 已给出）");
        }
    }

    /**
     * 取表名命中的那个预留前缀，用于按前缀报数
     *
     * <p>🔴 名单不在这里抄一份：走 {@link SystemTablePolicy#prefixes}，
     * 与 {@code isAllowed} 用的是同一个集合 —— 抄一份就是两套口径的起点。
     */
    private static String reservedPrefixOf(String tableName) {
        String lower = tableName == null ? "" : tableName.trim().toLowerCase();
        return SystemTablePolicy.prefixes(TableOperation.REGISTER).stream()
                .filter(lower::startsWith)
                .findFirst()
                .orElse("(其他)");
    }

    /** 见 {@link AgentToolFunction#resultArrayKeys}：本工具全部返回分支里数组值的键（并集） */
    @Override
    public List<String> resultArrayKeys() {
        return List.of("items", "danglingRegistrations");
    }

    @Override
    public String paramsSchema() {
        return "keyword（可选，字符串）：按表名或表注释模糊查询，两个条件是「或」。"
                + "搜中文词（如「设备」）时命中的是注释 —— 表名是拉丁的，中文词只在注释里。"
                + "库里表很多，建议带上关键词，不带时结果会被截断。"
                + "🔴 keyword 只作用于 items，**不作用于 danglingRegistrations**："
                + "后者恒为全库对账结果，所以「带 keyword 查到 danglingRegistrations 为空」"
                + "就是真的没有，不是被 keyword 筛掉了";
    }

}
