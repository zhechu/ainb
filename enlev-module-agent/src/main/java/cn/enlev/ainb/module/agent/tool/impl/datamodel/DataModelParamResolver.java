package cn.enlev.ainb.module.agent.tool.impl.datamodel;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.module.infra.api.datamodel.DataModelApi;
import cn.enlev.ainb.module.infra.api.datamodel.dto.DataModelSaveReqDTO;
import cn.enlev.ainb.module.infra.api.datamodel.dto.DataModelStructureRespDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_TOOL_PARAM_INVALID;

/**
 * 数据模型类工具的入参解析：把模型给的参数组装成 {@link DataModelSaveReqDTO}
 *
 * <h3>🔴 为什么要单独抽一个类</h3>
 * {@code datamodel_preview_ddl} 存在的全部意义，是让 AI 能<b>在执行之前</b>拿到
 * 「这次改动会生成什么 DDL、能不能过」的判据。这个判据要成立，
 * 有一个前提：<b>预览提交的参数，必须和真正执行时提交的一模一样</b>。
 *
 * <p>如果预览工具自己解析一遍入参，它就会和 {@code datamodel_create} /
 * {@code datamodel_update} <b>各自漂移</b>：哪天改了「id 强制置空」或者
 * 「索引原样带回」这类规则，只改一边，表现就是「预览说能过，执行却失败」——
 * 或者更糟，「预览说会加一列，执行时顺手删了两列」。
 *
 * <p>所以组装逻辑只有一份，在这里；三个工具都从这里取。
 *
 * @author 走进新时代
 */
@Component
public class DataModelParamResolver {

    /** 参数名：建表定义，结构同 {@link DataModelSaveReqDTO} */
    public static final String PARAM_MODEL = "model";
    /** 参数名：要修改的表名 */
    public static final String PARAM_TABLE_NAME = "tableName";
    public static final String PARAM_ADD_COLUMNS = "addColumns";
    public static final String PARAM_MODIFY_COLUMNS = "modifyColumns";
    public static final String PARAM_DROP_COLUMNS = "dropColumns";

    /**
     * 建表参数说明，写进 prompt 用
     *
     * 与 {@link #resolveCreate} 放在一起，是因为两者必须同时改 ——
     * 说明里写了而解析不认的字段，模型会一直传、一直被忽略，且没有任何提示。
     */
    public static final String CREATE_PARAMS_SCHEMA = """
            model（必填，对象）：建表定义，结构为
              tableName    必填，字符串，须以 biz_ 开头的全小写 snake_case
              tableComment 必填，字符串，用中文说明这张表存什么，不能与表名相同
              modelName    可选，字符串，模型显示名；不传时平台取 tableComment 兜底
              columns      必填，数组，每项：
                columnName    必填，全小写 snake_case
                columnComment 必填，中文说明
                columnType    必填，取值见 datamodel_column_type
                length/scale  STRING 与 DECIMAL 必须显式给出
                nullable      可选，布尔，默认 true（可空）；只有用户明确说该字段必填才传 false
                primaryKey    必须为 false（主键 id 由平台自动补）
              indexes      可选，数组，每项：
                indexName   唯一索引须 uk_ 开头，普通索引须 idx_ 开头
                indexType   只能是精确大写的 UNIQUE 或 NORMAL
                columnNames 字段名数组
            不要传 id、dataSourceConfigId、systemColumnProfile，也不要传任何系统列
            （id/creator/create_time/updater/update_time/deleted/tenant_id）
            🔴 这张表之后要挂审批流（用户提到审批/流程/报送）时，建表就带上这两列 ——
               名字与类型都是平台定死的，写错不报错，只会让这张表单永远挂不上流程：
                 process_instance_id  STRING，length 不小于 64（流程实例编号）
                 process_status       TINYINT（流程状态码，整数型；INT/LONG 也收，STRING 不收）""";

    /**
     * 改表参数说明，写进 prompt 用
     */
    public static final String UPDATE_PARAMS_SCHEMA = """
            tableName（必填，字符串）：要修改的表名，如 biz_purchase_apply
            addColumns（可选，数组）：要新增的列，每项：
              columnName    必填，全小写 snake_case
              columnComment 必填，中文说明
              columnType    必填，取值见 datamodel_column_type
              length/scale  STRING 与 DECIMAL 必须显式给出
              nullable      可选，布尔，默认 true
              defaultValue  可选
            modifyColumns（可选，数组）：要修改的列，每项 columnName 必填，
              其余只写要改的那些字段，没写的保持原样

            🔴 不要传完整的列清单 —— 只描述要变什么就行，
               现有结构由工具自己取回并合并，你不会因为漏写某一列而把它删掉。
            🔴 删列（dropColumns）【已不支持】，不要传：
               删列不可回滚，必须由人在能看到表结构的地方决定，
               所以后端要求 confirmDropColumns=true，而那个标记不许由 AI 设置。
               传了 dropColumns 会直接被拒 —— 请让用户去
               【基础设施 → 数据管理 → 数据模型 → 该表 → 修改】里删，那边有二次确认弹窗。
               （空表也不例外：表是空的只说明数据没丢，不说明结构没丢）
            🔴 也不要传 version / schemaFingerprint：这两个是并发与结构漂移的判据，
               由工具自己从 datamodel_structure 取回后带上，自己填等于自己给自己发通行证。
            ⚠️ 不支持改表名、改主键、改索引 —— 这几项要重建表。
            🔴 给既有表补流程回写列（这张表要挂审批流）时，两列的名字与类型都是平台定死的，
               写错不报错，只会让这张表单永远挂不上流程：
                 process_instance_id  STRING，length 不小于 64（流程实例编号）
                 process_status       TINYINT（流程状态码，整数型；INT/LONG 也收，STRING 不收）
               这两列已经存在但类型不对时，用 modifyColumns 把类型改过来 ——
               界面上那个「快速添加」只按列名判存在，不会替你改类型。""";

    @Resource
    private DataModelApi dataModelApi;

    // ==================== 建表 ====================

    /**
     * 解析建表入参，并补齐那些<b>不该由模型来填</b>的固定值
     *
     * @param params   工具入参
     * @param toolName 工具名，只用于报错时说清楚是谁拒的
     * @return 建表参数
     */
    public DataModelSaveReqDTO resolveCreate(Map<String, Object> params, String toolName) {
        Object model = params == null ? null : params.get(PARAM_MODEL);
        if (model == null) {
            throw exception(AGENT_TOOL_PARAM_INVALID, toolName, "缺少 model（建表定义）");
        }
        DataModelSaveReqDTO reqDTO;
        try {
            reqDTO = JSONUtil.toBean(JSONUtil.parseObj(model), DataModelSaveReqDTO.class);
        } catch (Exception ex) {
            throw exception(AGENT_TOOL_PARAM_INVALID, toolName,
                    "model 不是合法的建表定义：" + ex.getMessage());
        }

        // 🔴 强制置空 id：建表工具只负责<b>建表</b>。
        //    带上 id 会走 update 路径，而 update 是<b>全量同步语义</b> ——
        //    没传的列一律被判为 DROP。模型若误传一个 id，
        //    表现就是「我让它建个表，它把另一张表的字段删了一半」
        reqDTO.setId(null);

        // §5.5 规则 12：恒传 0。多数据源在数据模型上实际不支持
        //（Service 用默认 JdbcTemplate、无 @DS），表永远建在主库。
        // 让模型自由填这个字段只会产生一个看起来能配、实则无效的参数
        reqDTO.setDataSourceConfigId(0L);

        // 业务表一律 TENANT：GLOBAL 与 LEGACY 都不注入 tenant_id，
        // 意味着该表数据对所有租户可见。这个决定不该由模型来做
        if (reqDTO.getSystemColumnProfile() == null) {
            reqDTO.setSystemColumnProfile("TENANT");
        }
        normalizeColumnTypes(reqDTO);
        return reqDTO;
    }

    /**
     * 模型爱写的 SQL 类型别名 → 平台受支持类型
     *
     * <p>模型给日期字段写 {@code TIMESTAMP}、给文本写 {@code VARCHAR} 是常态，
     * 而受支持列表是 STRING/DECIMAL/…/DATETIME 这套。不归一的下场是建表在运行期
     * 被校验拒掉、整条流程失败；归一必须发生在<b>校验与建 DDL 之前</b>，
     * 只在校验期归一救不了已经写坏的 columnType。
     */
    private static final Map<String, String> COLUMN_TYPE_ALIASES = Map.ofEntries(
            Map.entry("TIMESTAMP", "DATETIME"),
            Map.entry("VARCHAR", "STRING"),
            Map.entry("CHAR", "STRING"),
            Map.entry("NVARCHAR", "STRING"),
            Map.entry("LONGTEXT", "LONG_TEXT"),
            Map.entry("MEDIUMTEXT", "LONG_TEXT"),
            Map.entry("INTEGER", "INT"),
            Map.entry("BIGINT", "LONG"),
            Map.entry("SMALLINT", "INT"),
            Map.entry("BOOL", "BOOLEAN"),
            Map.entry("NUMERIC", "DECIMAL"));

    private void normalizeColumnTypes(DataModelSaveReqDTO reqDTO) {
        if (reqDTO == null || reqDTO.getColumns() == null) {
            return;
        }
        for (DataModelSaveReqDTO.ColumnDTO column : reqDTO.getColumns()) {
            column.setColumnType(canonicalColumnType(column.getColumnType()));
            // 🔴 默认「可空」：模型建表时几乎不会显式传 nullable，而底层
            //    TableColumnModel.isNullable() 是 Boolean.TRUE.equals(nullable) —— null 会被当成 NOT NULL。
            //    不补这个默认值，表现就是「用户没说必填，AI 建出来的表却每个字段都 NOT NULL」，
            //    与表单页面（required 默认 false）对不上。只有模型显式传了 false 才建必填列。
            if (column.getNullable() == null) {
                column.setNullable(true);
            }
        }
    }

    private static String canonicalColumnType(String type) {
        if (type == null) {
            return null;
        }
        String upper = type.trim().toUpperCase(Locale.ROOT);
        return COLUMN_TYPE_ALIASES.getOrDefault(upper, upper);
    }

    // ==================== 改表 ====================

    /**
     * 改表入参的解析结果
     *
     * @param reqDTO   组装好的全量改表参数，可直接交给 {@code updateDataModel} 或 {@code previewDdl}
     * @param current  改之前的真实结构，校验器要拿它做判据
     * @param added    本次新增的列名
     * @param modified 本次修改的列名
     * @param dropped  本次删除的列名
     */
    public record UpdateResolution(DataModelSaveReqDTO reqDTO,
                                   DataModelStructureRespDTO current,
                                   List<String> added,
                                   List<String> modified,
                                   List<String> dropped) {
    }

    /**
     * 解析改表入参：只收变更意图，现有结构由这里自己取回合并
     *
     * <p>这样「模型漏传某一列导致它被删」在结构上不可能发生 ——
     * 不是靠模型自觉，是靠它根本没有机会犯这个错。
     *
     * @param params   工具入参
     * @param toolName 工具名，只用于报错
     * @return 解析结果
     */
    public UpdateResolution resolveUpdate(Map<String, Object> params, String toolName) {
        String tableName = requireString(params, PARAM_TABLE_NAME, "要修改的表名", toolName);

        // 1. 定位模型。按表名找，因为模型编号对使用者不可见
        DataModelApi.DataModelSimpleRespDTO model = dataModelApi.getDataModelList().stream()
                .filter(item -> tableName.equalsIgnoreCase(item.getTableName()))
                .findFirst().orElse(null);
        if (model == null) {
            throw exception(AGENT_TOOL_PARAM_INVALID, toolName,
                    "找不到表「" + tableName + "」对应的数据模型。"
                            + "先用 datamodel_list 确认表名；"
                            + "如果这张表还没登记进数据模型，本工具改不了它");
        }

        // 2. 取回现有完整结构 —— 这是整个设计的支点
        DataModelStructureRespDTO current = dataModelApi.getStructure(model.getId());
        if (current == null || current.getColumns() == null || current.getColumns().isEmpty()) {
            throw exception(AGENT_TOOL_PARAM_INVALID, toolName,
                    "读不到表「" + tableName + "」的现有结构，无法安全地改表");
        }

        // 3. 在现有结构上应用变更
        MergeResult merged = merge(current, params, toolName);

        // 4. 组装全量回写参数
        DataModelSaveReqDTO reqDTO = new DataModelSaveReqDTO();
        reqDTO.setId(model.getId());
        reqDTO.setTableName(current.getTableName());
        reqDTO.setTableComment(current.getTableComment());
        reqDTO.setModelName(model.getModelName());
        reqDTO.setColumns(merged.columns());
        reqDTO.setIndexes(convertIndexes(current));
        reqDTO.setDataSourceConfigId(0L);
        // 🔴 恒不传：它的语义是「人已经看过这张表的数据，确认这些列可以丢」，
        //    而用户在 Plan 卡片上点的是「同意执行这个计划」—— 两件事不能混
        reqDTO.setRiskConfirmed(null);
        // 🔴 同上，而且更硬：这个标记连「表里有数据」都不看（B4-3 挡的正是空表上的删列）。
        //    恒 null ⟹ 后端一律拒；要删列请到数据模型界面，那里有二次确认弹窗
        reqDTO.setConfirmDropColumns(null);
        // 🔴 两条基线都取【同一次 getStructure 的取回值】（B4-2 / B4-4）：
        //    · version     —— 登记行的乐观锁版本。改表是「读结构 → 合并 → 回写」，
        //      这段窗口里另一个窗口可能已经把这张表改了；服务端拿它做
        //      UPDATE … WHERE version=? 并检查影响行数，撞上了就报「请刷新后重做」
        //      而不是把对方的改动悄悄抹掉。
        //    · schemaFingerprint —— 物理表结构的指纹。它防的是另一件事：
        //      DBA 直接执行 ALTER、迁移脚本、别的工具改过这张表 ——
        //      那些改动<b>不经过</b>登记行，所以压根不会推高 version，
        //      光靠乐观锁看不见。指纹对不上时后端在<b>执行任何 DDL 之前</b>就中止。
        //    ⚠️ 两个都必须是「取回来的那份」，不能让模型自己填 ——
        //       自己填的基线等于自己给自己发通行证，闸门当场作废。
        reqDTO.setVersion(current.getVersion());
        reqDTO.setSchemaFingerprint(current.getSchemaFingerprint());

        return new UpdateResolution(reqDTO, current, merged.added(), merged.modified(),
                merged.dropped());
    }

    // ==================== 合并 ====================

    private record MergeResult(List<DataModelSaveReqDTO.ColumnDTO> columns,
                               List<String> added, List<String> modified, List<String> dropped) {
    }

    /**
     * 把变更意图应用到现有结构上
     *
     * 顺序是刻意的：先删、再改、最后加。
     * 反过来的话，「删掉 a 再加一个同名的 a」会变成「加了再删」，结果是列没了。
     */
    private MergeResult merge(DataModelStructureRespDTO current, Map<String, Object> params,
                              String toolName) {
        Set<String> dropNames = parseDropNames(params, toolName);
        Map<String, JSONObject> modifyMap = parseColumnMap(params, PARAM_MODIFY_COLUMNS, toolName);
        List<JSONObject> addList = parseColumnList(params, PARAM_ADD_COLUMNS, toolName);

        List<String> added = new ArrayList<>();
        List<String> modified = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        List<DataModelSaveReqDTO.ColumnDTO> columns = new ArrayList<>();
        Set<String> existingNames = new LinkedHashSet<>();

        for (DataModelStructureRespDTO.ColumnDTO item : current.getColumns()) {
            String lower = item.getColumnName().toLowerCase(Locale.ROOT);
            existingNames.add(lower);
            if (dropNames.contains(lower)) {
                dropped.add(item.getColumnName());
                continue;
            }
            DataModelSaveReqDTO.ColumnDTO column = copy(item);
            JSONObject change = modifyMap.get(lower);
            if (change != null) {
                applyChange(column, change);
                modified.add(item.getColumnName());
            }
            columns.add(column);
        }

        // 要改的列若压根不存在，必须报错而不是静默忽略 ——
        // 否则模型会以为改成功了，而实际什么也没发生
        List<String> unknownModify = modifyMap.keySet().stream()
                .filter(name -> !existingNames.contains(name)).toList();
        if (!unknownModify.isEmpty()) {
            throw exception(AGENT_TOOL_PARAM_INVALID, toolName,
                    "要修改的列不存在：" + String.join("、", unknownModify)
                            + "。现有的列是：" + String.join("、", existingNames));
        }
        List<String> unknownDrop = dropNames.stream()
                .filter(name -> !existingNames.contains(name)).toList();
        if (!unknownDrop.isEmpty()) {
            throw exception(AGENT_TOOL_PARAM_INVALID, toolName,
                    "要删除的列不存在：" + String.join("、", unknownDrop));
        }

        for (JSONObject item : addList) {
            String name = item.getStr("columnName");
            if (StrUtil.isBlank(name)) {
                throw exception(AGENT_TOOL_PARAM_INVALID, toolName,
                        "addColumns 里有一项没写 columnName");
            }
            if (existingNames.contains(name.toLowerCase(Locale.ROOT))
                    && !dropNames.contains(name.toLowerCase(Locale.ROOT))) {
                throw exception(AGENT_TOOL_PARAM_INVALID, toolName,
                        "列「" + name + "」已经存在，要改它请放进 modifyColumns");
            }
            DataModelSaveReqDTO.ColumnDTO column = new DataModelSaveReqDTO.ColumnDTO();
            column.setColumnName(name);
            column.setPrimaryKey(false);
            column.setNullable(true);
            applyChange(column, item);
            columns.add(column);
            added.add(name);
        }
        return new MergeResult(columns, added, modified, dropped);
    }

    /**
     * 把一项变更贴到列上。只覆盖显式给出的字段，其余保持原样
     */
    private void applyChange(DataModelSaveReqDTO.ColumnDTO column, JSONObject change) {
        if (change.containsKey("columnComment")) {
            column.setColumnComment(change.getStr("columnComment"));
        }
        if (change.containsKey("columnType")) {
            column.setColumnType(canonicalColumnType(change.getStr("columnType")));
        }
        if (change.containsKey("length")) {
            column.setLength(change.getInt("length"));
        }
        if (change.containsKey("scale")) {
            column.setScale(change.getInt("scale"));
        }
        if (change.containsKey("nullable")) {
            column.setNullable(change.getBool("nullable"));
        }
        if (change.containsKey("defaultValue")) {
            column.setDefaultValue(change.getStr("defaultValue"));
        }
    }

    private DataModelSaveReqDTO.ColumnDTO copy(DataModelStructureRespDTO.ColumnDTO source) {
        DataModelSaveReqDTO.ColumnDTO target = new DataModelSaveReqDTO.ColumnDTO();
        target.setColumnName(source.getColumnName());
        target.setColumnComment(source.getColumnComment());
        target.setColumnType(source.getColumnType());
        target.setLength(source.getLength());
        target.setScale(source.getScale());
        target.setNullable(source.getNullable());
        target.setPrimaryKey(source.getPrimaryKey());
        target.setAutoIncrement(source.getAutoIncrement());
        target.setDefaultValue(source.getDefaultValue());
        // 🔴 必须原样带回：TableDiffer 会比对它，漏了会被判成「这一列变了」，
        //    于是一次「加字段」会顺带把 update_time 的 ON UPDATE CURRENT_TIMESTAMP 抹掉，
        //    从此这张表的 update_time 不再自动更新，而且不报任何错。实测踩过
        target.setOnUpdateCurrentTimestamp(source.getOnUpdateCurrentTimestamp());
        return target;
    }

    /**
     * 索引原样带回
     *
     * 与列同理：不带的话索引会被当成要删。改索引与改列是两类操作，
     * 混在一起会让 Plan 卡片上的预览说不清楚，所以这套工具都不支持改索引。
     */
    private List<DataModelSaveReqDTO.IndexDTO> convertIndexes(DataModelStructureRespDTO current) {
        List<DataModelSaveReqDTO.IndexDTO> result = new ArrayList<>();
        if (current.getIndexes() == null) {
            return result;
        }
        for (DataModelStructureRespDTO.IndexDTO source : current.getIndexes()) {
            DataModelSaveReqDTO.IndexDTO target = new DataModelSaveReqDTO.IndexDTO();
            target.setIndexName(source.getIndexName());
            target.setIndexType(source.getIndexType());
            target.setColumnNames(source.getColumnNames());
            result.add(target);
        }
        return result;
    }

    // ==================== 入参 ====================

    private Set<String> parseDropNames(Map<String, Object> params, String toolName) {
        Object raw = params.get(PARAM_DROP_COLUMNS);
        Set<String> result = new LinkedHashSet<>();
        if (raw == null) {
            return result;
        }
        if (!(raw instanceof List<?> list)) {
            throw exception(AGENT_TOOL_PARAM_INVALID, toolName, "dropColumns 必须是列名数组");
        }
        for (Object item : list) {
            if (item != null && StrUtil.isNotBlank(item.toString())) {
                result.add(item.toString().trim().toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }

    private Map<String, JSONObject> parseColumnMap(Map<String, Object> params, String key,
                                                   String toolName) {
        Map<String, JSONObject> result = new LinkedHashMap<>();
        for (JSONObject item : parseColumnList(params, key, toolName)) {
            String name = item.getStr("columnName");
            if (StrUtil.isBlank(name)) {
                throw exception(AGENT_TOOL_PARAM_INVALID, toolName,
                        key + " 里有一项没写 columnName");
            }
            result.put(name.trim().toLowerCase(Locale.ROOT), item);
        }
        return result;
    }

    private List<JSONObject> parseColumnList(Map<String, Object> params, String key,
                                             String toolName) {
        Object raw = params.get(key);
        List<JSONObject> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        JSONArray array;
        try {
            array = JSONUtil.parseArray(raw);
        } catch (Exception ex) {
            throw exception(AGENT_TOOL_PARAM_INVALID, toolName,
                    key + " 不是合法的数组：" + ex.getMessage());
        }
        for (Object item : array) {
            if (item instanceof JSONObject obj) {
                result.add(obj);
            }
        }
        return result;
    }

    private String requireString(Map<String, Object> params, String key, String label,
                                 String toolName) {
        Object value = params == null ? null : params.get(key);
        if (value == null || StrUtil.isBlank(value.toString())) {
            throw exception(AGENT_TOOL_PARAM_INVALID, toolName,
                    "缺少 " + key + "（" + label + "）");
        }
        return value.toString().trim();
    }

}
