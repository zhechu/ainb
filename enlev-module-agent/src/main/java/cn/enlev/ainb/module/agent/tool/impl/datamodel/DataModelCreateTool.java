package cn.enlev.ainb.module.agent.tool.impl.datamodel;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.AgentValidateResult;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.DataModelCreateValidator;
import cn.enlev.ainb.module.infra.api.datamodel.DataModelApi;
import cn.enlev.ainb.module.infra.api.datamodel.dto.DataModelSaveReqDTO;
import cn.enlev.ainb.module.infra.api.datamodel.dto.DataModelStructureRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_TOOL_PARAM_INVALID;

/**
 * 工具：新建数据表
 *
 * <h3>🔴 这是目前风险最高的工具</h3>
 * 它<b>即刻执行 {@code CREATE TABLE}</b>，而 MySQL 的 DDL 没有事务 ——
 * {@code DdlExecutor} 的类注释写得很直白：「不假装能回滚」。
 * 所以它的风险等级是 {@link RiskLevel#IRREVERSIBLE}，
 * 必须进 Plan 等用户确认，界面上要显著标注「执行后无法回滚」。
 *
 * <h3>不要张口就建新表（方案 §5.2）</h3>
 * 建表单有两条入口，风险差一个数量级：
 * <ul>
 *   <li><b>表已存在</b>：查到 → 绑上去 → 发布。全程无 DDL，完全可逆</li>
 *   <li><b>表不存在</b>：先建表。含不可逆操作</li>
 * </ul>
 * Agent 循环的硬规则是<b>先走只读工具查库里有没有可复用的表</b>，
 * 查到候选就在 Plan 里呈现「用这张现成的」作为首选项。
 * 绝大多数场景根本碰不到这个工具 —— 这既降低风险，也降低技术债。
 * 这条约束写在 description 里，模型选工具时能看到。
 *
 * <p>拿不准这份参数能不能过，可以先用 {@code datamodel_preview_ddl} 预览 ——
 * 它与本工具共用同一套入参组装与校验，不产生任何副作用。
 *
 * <h3>🔴 幂等（E6）：重放同一份参数不会抛异常，也不会建出第二张表</h3>
 * Agent 的失败重试、Plan 的重复确认、用户点两次「同意执行」，都会让同一个
 * {@code datamodel_create} 被执行两遍。而这个工具的副作用<b>不可回滚</b>：
 * 第二次执行会撞上 {@code DATA_MODEL_TABLE_EXISTS}，模型拿到一句「表已存在」，
 * 于是它认为<b>自己这一步失败了</b> —— 而事实恰恰相反，它上一次已经成功了。
 *
 * <p>后果不是多报一个错那么简单：模型会去「修复」这个失败，
 * 常见做法是换个表名再建一次（库里多出一张重复表，而且没人知道哪张算数），
 * 或者告诉用户「建表失败」（用户以为没有表，接着又建）。
 *
 * <p>所以现在执行前先按表名查已登记的模型、取回真实结构、与本次请求逐列比对：
 * <ul>
 *   <li><b>结构相符</b> ⟹ 返回「已存在，跳过」，<b>不执行任何 DDL</b>，
 *       并且带上原来那个模型编号 —— 对调用方来说这就是一次成功的建表</li>
 *   <li><b>结构不符</b> ⟹ 报错，但报的是「哪几列不一致、该改用 datamodel_update」，
 *       而不是后端那句「表已存在」。<b>刻意不跳过</b>：一张同名的<b>别的</b>表，
 *       说成「你要的表已经有了」是最坏的那种谎话 —— 模型会接着往上绑表单、建流程</li>
 *   <li><b>没登记</b> ⟹ 交给后端判（物理表可能存在但未登记，那是
 *       {@code DATA_MODEL_TABLE_EXISTS_UNREGISTERED}，补救办法是 {@code datamodel_import}）</li>
 * </ul>
 *
 * @author 走进新时代
 */
@Component
@AgentTool(
        name = DataModelCreateTool.NAME,
        displayName = "创建数据表",
        groupName = "数据建模",
        description = "新建一张业务数据表并立即执行 CREATE TABLE。⚠️ 不可逆，执行后无法回滚。"
                + "调用前必须先用 datamodel_list / datamodel_structure 确认库里没有可复用的表 —— "
                + "能用现成的表就不要建新表。表名须以 biz_ 开头，业务字段一律不设主键"
                + "（主键 id 与租户、审计等系统列由平台自动补齐，不要自己传）。"
                + "本工具是幂等的：同名表已存在且结构一致时返回「已存在，跳过」而不报错，"
                + "所以重试是安全的，不要为了绕开「表已存在」而改表名重建",
        permission = "infra:data-model:create",
        risk = RiskLevel.IRREVERSIBLE)
@Slf4j
public class DataModelCreateTool implements AgentToolFunction {

    /**
     * 工具键
     *
     * <p>🔴 上面注解的 {@code name} 与变更清单抽取器的 {@code toolId()} 都必须引用它。
     * 三处各写一遍字面量时，改名不会报错 —— 只会让清单静默退回兜底解读，
     * 表现是「新建数据表」在确认框里显示成「调用工具」。
     */
    public static final String NAME = "datamodel_create";

    @Resource
    private DataModelApi dataModelApi;
    @Resource
    private DataModelCreateValidator validator;
    @Resource
    private DataModelParamResolver paramResolver;

    @Override
    public Object execute(Map<String, Object> params) {
        DataModelSaveReqDTO reqDTO = paramResolver.resolveCreate(params, NAME);

        // 🔴 执行前<b>再校验一次</b>，哪怕生成 Plan 时已经校验过。
        //    理由与权限网关每步重校验相同：Plan 从生成到确认之间可能隔着几分钟，
        //    期间库里完全可能已经建了个同名表、或者管理员改了表前缀配置。
        //    而这一步的代价是不可逆的 —— 宁可多跑一次校验
        AgentValidateResult result = validator.validate(reqDTO);
        if (!result.isPassed()) {
            throw exception(AGENT_TOOL_PARAM_INVALID, NAME, result.toFeedback());
        }

        // E6 幂等：这张表是不是上一次已经建好了
        // ⚠️ 放在校验<b>之后</b>：校验是只读的、且它那份「一次说完」的错误清单对模型最有用，
        //    没有理由为了跳过而少说一遍。真重放的参数与上一次逐字相同，
        //    上一次过了这一次也必然过
        ExistingStructure existing = inspectExisting(reqDTO);
        if (existing.identical()) {
            log.info("[execute][AI 建表幂等跳过 表名({}) 模型编号({}) —— 结构与本次请求一致，未执行 DDL]",
                    reqDTO.getTableName(), existing.modelId());
            return skippedResponse(reqDTO, existing);
        }
        if (existing.different()) {
            throw exception(AGENT_TOOL_PARAM_INVALID, NAME,
                    "表 " + reqDTO.getTableName() + " 已经存在，但结构与本次请求不一致：\n· "
                            + String.join("\n· ", existing.differences())
                            + "\n这不是同一次建表的重放，所以没有跳过。"
                            + "要改这张表请用 datamodel_update；要建一张新表请换一个表名");
        }

        Long id = dataModelApi.createDataModel(reqDTO);
        log.info("[execute][AI 建表完成 表名({}) 模型编号({})]", reqDTO.getTableName(), id);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", id);
        resp.put("tableName", reqDTO.getTableName());
        resp.put("columnCount", reqDTO.getColumns() == null ? 0 : reqDTO.getColumns().size());
        resp.put("message", "表 " + reqDTO.getTableName() + " 已创建");
        return resp;
    }

    /** 见 {@link AgentToolFunction#resultArrayKeys}：本工具全部返回分支里数组值的键（并集） */
    @Override
    public List<String> resultArrayKeys() {
        return List.of("warnings");
    }

    /**
     * 见 {@link AgentToolFunction#resultKeys}：两个返回分支（真建 / 幂等跳过）顶层键的并集
     *
     * <p>🔴 结果<b>扁平</b>，{@code id} 就在顶层，没有 {@code model} 这一层 ——
     * 而入参恰恰是嵌在 {@code args.model} 里的。这一进一出的形状差正是规划器最容易赌错的地方
     * （实测 execution 217 写了 {@code {{$.create_out.model.id}}}，运行期解析成空串后
     * 下游 {@code datamodel_structure} 抛「id 必须是数字，收到的是：」）。
     * 登记出来，提示词才印得出正确的取值路径。
     */
    @Override
    public List<String> resultKeys() {
        return List.of("id", "tableName", "columnCount", "message", "skipped", "warnings");
    }

    @Override
    public String paramsSchema() {
        return DataModelParamResolver.CREATE_PARAMS_SCHEMA;
    }

    // ==================== E6 幂等比对 ====================

    /**
     * 与本次请求比对的结果
     *
     * @param modelId     已登记的模型编号；<b>没登记 / 读不到结构</b>时为 null
     * @param differences 不一致的项；空 ⟺ 结构相符
     * @param warnings    不影响「跳过」判断、但必须说出来的差异（默认值、索引）
     */
    private record ExistingStructure(Long modelId, List<String> differences, List<String> warnings) {

        /** 表已登记且结构与本次请求一致 ⟹ 幂等跳过 */
        boolean identical() {
            return modelId != null && differences.isEmpty();
        }

        /** 表已登记但结构不一致 ⟹ 这<b>不是</b>重放，必须说清楚差在哪 */
        boolean different() {
            return modelId != null && !differences.isEmpty();
        }
    }

    /**
     * 查这张表在不在、在的话结构与本次请求是否一致
     *
     * <p>🔴 <b>读不到就当作「没登记」</b>，绝不当作「相符」。
     * 拿一个未知当已知，表现是告诉模型「你要的表已经有了」，
     * 而它要的表压根不存在 —— 模型会接着往上绑表单、建流程，
     * 一路都成功，直到运行时才炸。宁可让后端去报它那句「表已存在」。
     */
    private ExistingStructure inspectExisting(DataModelSaveReqDTO reqDTO) {
        String tableName = StrUtil.trimToEmpty(reqDTO.getTableName());
        if (tableName.isEmpty()) {
            return new ExistingStructure(null, List.of(), List.of());
        }
        DataModelApi.DataModelSimpleRespDTO model = dataModelApi.getDataModelList().stream()
                .filter(item -> tableName.equalsIgnoreCase(item.getTableName()))
                .findFirst().orElse(null);
        if (model == null) {
            return new ExistingStructure(null, List.of(), List.of());
        }
        DataModelStructureRespDTO current = dataModelApi.getStructure(model.getId());
        if (current == null || CollUtil.isEmpty(current.getColumns())) {
            return new ExistingStructure(null, List.of(), List.of());
        }

        List<String> differences = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        compareColumns(reqDTO, current, differences, warnings);
        compareIndexes(reqDTO, current, warnings);
        return new ExistingStructure(model.getId(), differences, warnings);
    }

    /**
     * 逐列比对：只比<b>业务列</b>
     *
     * <p>系统列（id / creator / create_time / … / tenant_id）由注入器按 profile 补，
     * 请求里<b>本来就不该出现</b>（出现了会被 {@code DataModelCreateValidator} 拒掉），
     * 所以拿它们参与比对只会凭空造出不一致。
     *
     * <h3>🔴 只比「请求里写了的」那几项，不比全部</h3>
     * 建表有一批<b>后端静默补的默认值</b>：STRING 不写长度建成 varchar(255)、
     * DECIMAL 不写精度建成 decimal(10,2)、nullable 不写按可空……
     * 拿请求里的 null 去比库里的 255，结论必然是「不一致」——
     * 而那恰恰是最常见的一次<b>合法重放</b>（两次参数逐字相同，都没写长度）。
     * 所以判据是：<b>请求写了这一项，就要求库里对得上；没写就不比</b>。
     *
     * <p>⚠️ 这意味着「模型没写长度、库里那张是 varchar(64)」也会被判为相符。
     * 这是有意的取舍：那张表要么就是上一次这么建出来的（长度是后端补的 255 或模型自己写的），
     * 要么是同名的别的表 —— 后者会因为它<b>缺列或多列</b>而被抓住，
     * 而列名集合是这里唯一必比、且不受默认值影响的一项。
     */
    private void compareColumns(DataModelSaveReqDTO reqDTO, DataModelStructureRespDTO current,
                                List<String> differences, List<String> warnings) {
        Map<String, DataModelStructureRespDTO.ColumnDTO> liveByName = new LinkedHashMap<>();
        for (DataModelStructureRespDTO.ColumnDTO column : current.getColumns()) {
            if (Boolean.TRUE.equals(column.getSystemColumn())) {
                continue;
            }
            liveByName.put(lower(column.getColumnName()), column);
        }

        Set<String> requested = new LinkedHashSet<>();
        for (DataModelSaveReqDTO.ColumnDTO want : CollUtil.emptyIfNull(reqDTO.getColumns())) {
            String key = lower(want.getColumnName());
            requested.add(key);
            DataModelStructureRespDTO.ColumnDTO live = liveByName.get(key);
            if (live == null) {
                differences.add("库里没有字段 " + want.getColumnName());
                continue;
            }
            if (!Objects.equals(upper(want.getColumnType()), upper(live.getColumnType()))) {
                differences.add("字段 %s 的类型不一致：本次要 %s，库里是 %s".formatted(
                        want.getColumnName(), want.getColumnType(), live.getColumnType()));
            }
            if (want.getLength() != null && !want.getLength().equals(live.getLength())) {
                differences.add("字段 %s 的长度不一致：本次要 %d，库里是 %s".formatted(
                        want.getColumnName(), want.getLength(), live.getLength()));
            }
            if (want.getScale() != null && !want.getScale().equals(live.getScale())) {
                differences.add("字段 %s 的精度不一致：本次要 %d，库里是 %s".formatted(
                        want.getColumnName(), want.getScale(), live.getScale()));
            }
            if (want.getNullable() != null
                    && !Objects.equals(want.getNullable(), Boolean.TRUE.equals(live.getNullable()))) {
                differences.add("字段 %s 的可空性不一致：本次要%s，库里是%s".formatted(
                        want.getColumnName(),
                        Boolean.TRUE.equals(want.getNullable()) ? "可空" : "必填",
                        Boolean.TRUE.equals(live.getNullable()) ? "可空" : "必填"));
            }
            // 默认值只降级成 warning：后端有一批「默认值被静默丢弃」的规则
            //（TEXT / LONG_TEXT / JSON 一律丢、空串当作没设），
            // 拿请求里的值去要求库里对得上，会把这些已知的改写误判成「结构不符」
            if (want.getDefaultValue() != null
                    && !Objects.equals(want.getDefaultValue(), live.getDefaultValue())) {
                warnings.add("字段 %s 的默认值与库里不同（本次 %s，库里 %s）。"
                        .formatted(want.getColumnName(), want.getDefaultValue(), live.getDefaultValue())
                        + "注意 TEXT / LONG_TEXT / JSON 的默认值会被 MySQL 静默丢弃");
            }
        }
        liveByName.entrySet().stream()
                .filter(entry -> !requested.contains(entry.getKey()))
                .forEach(entry -> differences.add(
                        "库里有字段 " + entry.getValue().getColumnName() + "，本次请求里没有"));
    }

    /**
     * 索引比对：只降级成 warning，不参与「相符 / 不符」的判定
     *
     * <p>理由与默认值同型 —— 建索引这件事平台有一批静默改写
     *（{@code indexType} 写成小写 unique 就按普通索引建、名字不合规照建不误），
     * 拿请求原样去要求库里对得上不可靠。
     *
     * <p>但<b>不能不说</b>：静默跳过而索引其实没建上，就是本项目头号缺陷
     * 「配了但不生效且无提示」的又一处入口 —— 唯一约束没建上的表现是一切正常，
     * 直到某天出现重复数据。所以差异写进 {@code warnings}，会随返回值一起回到模型手上。
     *
     * <p>库里<b>多出来</b>的索引不报：那是个超集，删索引从来不是本工具的职责
     *（它只会建，不会删），报出来只会让模型以为要处理。
     */
    private void compareIndexes(DataModelSaveReqDTO reqDTO, DataModelStructureRespDTO current,
                                List<String> warnings) {
        if (CollUtil.isEmpty(reqDTO.getIndexes())) {
            return;
        }
        Map<String, DataModelStructureRespDTO.IndexDTO> liveByName = new LinkedHashMap<>();
        for (DataModelStructureRespDTO.IndexDTO index : CollUtil.emptyIfNull(current.getIndexes())) {
            liveByName.put(lower(index.getIndexName()), index);
        }
        for (DataModelSaveReqDTO.IndexDTO want : reqDTO.getIndexes()) {
            DataModelStructureRespDTO.IndexDTO live = liveByName.get(lower(want.getIndexName()));
            if (live == null) {
                warnings.add("库里没有索引 " + want.getIndexName() + "（本次请求要建）");
                continue;
            }
            if (!Objects.equals(upper(want.getIndexType()), upper(live.getIndexType()))) {
                warnings.add("索引 %s 的类型与本次请求不同（本次 %s，库里 %s）".formatted(
                        want.getIndexName(), want.getIndexType(), live.getIndexType()));
            }
            if (!Objects.equals(lowerList(want.getColumnNames()), lowerList(live.getColumnNames()))) {
                warnings.add("索引 %s 的字段与本次请求不同（本次 %s，库里 %s）".formatted(
                        want.getIndexName(), want.getColumnNames(), live.getColumnNames()));
            }
        }
    }

    private Map<String, Object> skippedResponse(DataModelSaveReqDTO reqDTO,
                                                ExistingStructure existing) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", existing.modelId());
        resp.put("tableName", reqDTO.getTableName());
        resp.put("columnCount", reqDTO.getColumns() == null ? 0 : reqDTO.getColumns().size());
        // 🔴 这个标记必须在：调用方（尤其是变更清单与流程重放）要能区分
        //    「这次真建了一张表」与「这次什么也没做」。少了它，
        //    重放会在统计里记成两次建表
        resp.put("skipped", true);
        if (!existing.warnings().isEmpty()) {
            resp.put("warnings", existing.warnings());
        }
        resp.put("message", "表 " + reqDTO.getTableName() + " 已存在，且结构与本次请求一致，"
                + "未执行任何 DDL（幂等跳过）。⚠️ 不要为了「重试成功」而改表名重建 —— "
                + "那会在库里留下两张重复的表；要改这张表请用 datamodel_update");
        return resp;
    }

    private static String lower(String value) {
        return StrUtil.trimToEmpty(value).toLowerCase(Locale.ROOT);
    }

    private static String upper(String value) {
        return StrUtil.trimToEmpty(value).toUpperCase(Locale.ROOT);
    }

    private static List<String> lowerList(List<String> values) {
        return CollUtil.emptyIfNull(values).stream().map(DataModelCreateTool::lower).toList();
    }

}
