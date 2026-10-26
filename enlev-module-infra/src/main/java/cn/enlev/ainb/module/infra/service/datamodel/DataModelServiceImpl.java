package cn.enlev.ainb.module.infra.service.datamodel;

import cn.hutool.core.collection.CollUtil;
import com.google.common.annotations.VisibleForTesting;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.module.infra.controller.admin.datamodel.vo.DataModelColumnSaveReqVO;
import cn.enlev.ainb.module.infra.controller.admin.datamodel.vo.DataModelIndexSaveReqVO;
import cn.enlev.ainb.module.infra.controller.admin.datamodel.vo.DataModelLogPageReqVO;
import cn.enlev.ainb.module.infra.controller.admin.datamodel.vo.DataModelPageReqVO;
import cn.enlev.ainb.module.infra.controller.admin.datamodel.vo.DataModelSaveReqVO;
import cn.enlev.ainb.module.infra.controller.admin.datamodel.vo.DataModelStructureRespVO;
import cn.enlev.ainb.module.infra.dal.dataobject.datamodel.DataModelDO;
import cn.enlev.ainb.module.infra.dal.dataobject.datamodel.DataModelLogDO;
import cn.enlev.ainb.module.infra.dal.mysql.datamodel.DataModelLogMapper;
import cn.enlev.ainb.module.infra.dal.mysql.datamodel.DataModelMapper;
import cn.enlev.ainb.module.infra.enums.artifact.ArtifactSourceEnum;
import cn.enlev.ainb.module.infra.enums.datamodel.DataModelColumnTypeEnum;
import cn.enlev.ainb.module.infra.enums.datamodel.DataModelOperateTypeEnum;
import cn.enlev.ainb.module.infra.enums.datamodel.SystemColumnProfileEnum;
import cn.enlev.ainb.module.infra.framework.datamodel.config.DataModelProperties;
import cn.enlev.ainb.module.infra.framework.datamodel.core.*;
import cn.enlev.ainb.module.infra.framework.datamodel.core.model.TableColumnModel;
import cn.enlev.ainb.module.infra.framework.datamodel.core.model.TableIndexModel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.*;

/**
 * 数据模型 Service 实现类
 *
 * ⚠️ 两个必须清楚的前提：
 * 1. **DDL 无法回滚**。所以本类的 @Transactional 只保护「模型登记」这类元数据操作，
 *    保护不了 DDL 本身。执行顺序刻意设计为「先 DDL、后写元数据」——
 *    DDL 失败则元数据不写；元数据写失败时表已建好，此时留有 DDL 日志可查。
 * 2. **所有标识符必须先过 SqlSafeGuard**。本类是唯一的入口，校验在此收口。
 *
 * @author 走进新时代
 */
@Service
@Validated
@Slf4j
public class DataModelServiceImpl implements DataModelService {

    /**
     * 登记行的初始乐观锁版本
     *
     * <p>与 DDL 里 {@code version int NOT NULL DEFAULT 0} 的默认值是一对。
     */
    private static final int INITIAL_VERSION = 0;

    @Resource
    private DataModelMapper dataModelMapper;
    @Resource
    private DataModelLogMapper dataModelLogMapper;
    @Resource
    private JdbcTemplate jdbcTemplate;
    @Resource
    private DataModelProperties dataModelProperties;

    private TableMetaReader metaReader() {
        return new TableMetaReader(jdbcTemplate);
    }

    private DdlExecutor ddlExecutor() {
        return new DdlExecutor(jdbcTemplate);
    }

    // ==================== 建表 ====================

    @Override
    public Long createDataModel(DataModelSaveReqVO createReqVO) {
        return createDataModel(createReqVO, ArtifactSourceEnum.MANUAL);
    }

    @Override
    public Long createDataModel(DataModelSaveReqVO createReqVO, ArtifactSourceEnum source) {
        String tableName = createReqVO.getTableName();
        // 1.1 安全校验：标识符合法 + 非框架表
        SqlSafeGuard.checkTableName(tableName);
        SqlSafeGuard.checkNotFrameworkTable(tableName);
        List<TableColumnModel> columns = convertColumns(createReqVO.getColumns());
        validateColumnList(columns);
        // 1.1.1 建模规范校验：命名、前缀、注释、配额、索引名 —— 只作用于新建，导入存量表不受此约束
        validateCreateConvention(createReqVO, columns, convertIndexes(createReqVO.getIndexes()));
        // 补齐系统字段：tenant_id / deleted / 审计字段是动态 CRUD 的安全前提，见 SystemColumnInjector
        columns = SystemColumnInjector.inject(columns,
                SystemColumnProfileEnum.profileOf(createReqVO.getSystemColumnProfile()));
        // 1.1.2 配额按物理表的真实列数算，与改表路径口径一致
        validateColumnCount(columns.size());
        List<TableIndexModel> indexes = convertIndexes(createReqVO.getIndexes());
        // 1.2 业务校验：表不能已存在
        //
        // 🔴 「物理表在、但没登记」要与「已登记」分开报。
        //    前者的正确出路是【导入已有表】，不是换个名字重建 ——
        //    换名的结果是库里多出一张结构相同的表，而原表仍然躺在那里没人管。
        //    这种孤儿表最常见的来源，就是删模型时选了「仅删登记、保留真实表」。
        //    共用一个「请更换表名」的提示会把人（和 AI）直接引到错误的那条路上。
        boolean registered = dataModelMapper.selectByTableNameAndDataSourceConfigId(
                tableName, createReqVO.getDataSourceConfigId()) != null;
        TableMetaReader reader = metaReader();
        if (reader.existsTable(tableName)) {
            throw registered ? exception(DATA_MODEL_TABLE_EXISTS, tableName)
                    : exception(DATA_MODEL_TABLE_EXISTS_UNREGISTERED, tableName);
        }
        if (registered) {
            // 登记在、物理表不在：这是元数据与真实库不一致，仍按「已存在」拒绝
            throw exception(DATA_MODEL_TABLE_EXISTS, tableName);
        }
        // 1.3 配额：放在 DDL 之前。超限只拒绝新建，不影响已有模型的使用
        validateTableCount();

        // 2. 执行 DDL —— 放在写元数据之前，失败则不留脏数据
        String sql = MysqlDdlBuilder.buildCreateTable(tableName,
                createReqVO.getTableComment(), columns, indexes);
        DdlExecutor.Result result = ddlExecutor().execute(sql);
        saveLog(createReqVO.getDataSourceConfigId(), tableName,
                DataModelOperateTypeEnum.CREATE, result);
        if (!result.isSuccess()) {
            throw exception(DATA_MODEL_DDL_EXECUTE_FAIL, result.getErrorMsg());
        }

        // 3. 登记模型
        // 🔴 模型名称在设计页保存时是必填（SaveReqVO 上 @NotEmpty），但 API / 智能体路径
        //    不跑 Bean Validation，空名称能落库 —— 结果是建出一张「设计页永远存不下去」的
        //    半成品模型（补流程字段、改任何列都会被拦）。缺省时取表描述兜住，两者都是给人看的名字。
        if (StrUtil.isBlank(createReqVO.getModelName())) {
            createReqVO.setModelName(createReqVO.getTableComment());
        }
        DataModelDO dataModel = BeanUtils.toBean(createReqVO, DataModelDO.class);
        // 列的业务元数据（字典/关联）不落物理表，单独存 —— 注意要用「注入前」的业务列，
        // 系统列不会有这些语义
        dataModel.setColumnMeta(ColumnMetaStore.serialize(convertColumns(createReqVO.getColumns())));
        stampGovernanceOnCreate(dataModel, source, reader.getColumns(tableName));
        dataModelMapper.insert(dataModel);
        return dataModel.getId();
    }

    /**
     * 建表 / 导入时盖上治理字段
     *
     * <p>🔴 <b>必须由服务端盖，不能让 {@code BeanUtils.toBean} 从 VO 拷。</b>
     * VO 上现在有了 {@code version} 与 {@code schemaFingerprint} 两个字段
     * （改表要回传它们），而 {@code toBean} 是按名字整份搬的 ——
     * 不显式覆盖，调用方传什么就落什么。那两个值是<b>本模块产出的事实</b>：
     * 让调用方自己指定版本，乐观锁就成了摆设；
     * 让调用方自己指定指纹，B4-2 那道闸就成了「自己跟自己比」，永远相等。
     *
     * @param dataModel    待插入的 DO
     * @param source       来源
     * @param liveColumns  DDL 之后<b>真实</b>的列
     */
    private void stampGovernanceOnCreate(DataModelDO dataModel, ArtifactSourceEnum source,
                                         List<TableColumnModel> liveColumns) {
        dataModel.setSource(source.getSource());
        // 刚建出来 / 刚导入的表还没人接管过
        dataModel.setManualEditedFlag(false);
        // 🔴 指纹取【DDL 之后的真实结构】，不取提交的目标结构。
        //    目标结构是「请求」，真实结构才是「事实」，两者的类型表示法未必逐字节相同
        //    （TableMetaReader 是把 MySQL 的类型反向映射回受控类型的）。
        //    拿请求当基线，下一次的复核就会是一次永久的假阳性 —— 表现为「这张表永远改不动」
        dataModel.setSchemaFingerprint(SchemaFingerprint.of(liveColumns));
        // 列是 NOT NULL DEFAULT 0，交给库默认值也能落 0。
        // 这里显式写：内存里这个对象立刻就是准的，而且「每条登记行都有版本」这条不变量
        // 在代码里看得见，不用去翻 DDL
        dataModel.setVersion(INITIAL_VERSION);
    }

    // ==================== 改表 ====================

    @Override
    public void updateDataModel(DataModelSaveReqVO updateReqVO) {
        updateDataModel(updateReqVO, ArtifactSourceEnum.MANUAL);
    }

    /**
     * 改表
     *
     * <h3>🔴 校验的顺序不是随便排的：能拦的都要拦在 DDL 之前</h3>
     * MySQL 的 DDL <b>不可回滚</b>，而 {@code DdlExecutor} 是多条顺序执行、失败即停 ——
     * 也就是说一旦走到执行那一步，「拦下来」这个选项就没了，剩下的只有
     * 「改对了」与「改了一半」。所以下面 5 道闸全部排在
     * {@code ddlExecutor().execute(sqlList)} 之前，按「越便宜越靠前」排：
     * <ol>
     *   <li>{@link #validateRecordVersion} —— 纯内存比对，最便宜，且撞上就是白干一场</li>
     *   <li>{@link #validateSchemaNotDrifted} —— 复用已经读到的实时结构，不多查一次</li>
     *   <li>{@link #validateSystemColumnsNotDropped} —— 绝对拒绝，不看确认位</li>
     *   <li>{@link #validateDropColumnsConfirmed} —— 删列的显式确认</li>
     *   <li>{@link #validateLossyChange} —— 有数据时的显式确认</li>
     * </ol>
     * 3 排在 4、5 之前是必须的：删系统列是<b>无论如何都不许</b>的，
     * 而 4、5 都能被确认位放行，先跑确认位就等于让 {@code riskConfirmed=true} 把系统列也放过去。
     *
     * <p>⚠️ 第 1 道闸只能挡住「读到写之间跨请求」的并发（窗口 = 用户编辑的这几分钟，
     * 也是乐观锁真正要防的那一种）。同一请求内「读完登记行 → 执行 DDL → 写回登记行」
     * 这几毫秒里若真有别人提交成功，写回会命中 0 行、此时表已经改了 ——
     * 彻底关掉这个窗口需要在一条不可回滚的 DDL 上全程持有行锁，
     * 与本类「DDL 不进事务」的既有设计相冲突，故不做，如实记在这里。
     */
    @Override
    public void updateDataModel(DataModelSaveReqVO updateReqVO, ArtifactSourceEnum operator) {
        DataModelDO dataModel = validateDataModelExists(updateReqVO.getId());
        String tableName = dataModel.getTableName();
        SqlSafeGuard.checkNotFrameworkTable(tableName);
        // 0. 乐观锁：版本必须带上、且必须与库里一致 —— 见方法注释，这一步之后就没有回头路了
        validateRecordVersion(updateReqVO.getVersion(), dataModel);
        List<TableColumnModel> targetColumns = convertColumns(updateReqVO.getColumns());
        validateColumnList(targetColumns);

        TableMetaReader reader = metaReader();
        if (!reader.existsTable(tableName)) {
            throw exception(DATA_MODEL_TABLE_NOT_EXISTS, tableName);
        }
        // 1. 比对差异
        List<TableColumnModel> currentColumns = reader.getColumns(tableName);
        // 1.1 结构指纹复核：你手里那份结构还是不是现在这张表
        validateSchemaNotDrifted(dataModel, updateReqVO.getSchemaFingerprint(), currentColumns);
        TableDiffer.Diff diff = TableDiffer.diff(targetColumns, currentColumns);
        // 2. 系统列一律不许删 —— 必须在两个确认位之前，理由见该方法注释
        validateSystemColumnsNotDropped(diff);
        // 2.1 删列一律要显式确认，与表里有没有数据无关
        validateDropColumnsConfirmed(diff, updateReqVO.getConfirmDropColumns());
        // 3. 有损操作要显式确认
        validateLossyChange(diff, tableName, reader, updateReqVO.getRiskConfirmed());
        // 4. 主键不允许改动 —— 改主键涉及索引重建，风险高于收益，要求重建表
        validatePrimaryKeyNotChanged(diff, currentColumns);

        // 3.1 索引差异：先按「字段全部就位后」的目标结构校验索引列，再与真实索引比对
        List<TableIndexModel> targetIndexes = convertIndexes(updateReqVO.getIndexes());
        MysqlDdlBuilder.validateIndexes(targetIndexes, SystemColumnInjector.inject(targetColumns));
        List<TableIndexModel> currentIndexes = reader.getIndexes(tableName);
        TableDiffer.IndexDiff indexDiff = TableDiffer.diffIndexes(targetIndexes, currentIndexes);

        // 3.2 建模规范：只校验本次新增的列与索引，存量不合规的部分放行
        validateAlterConvention(diff.getAddColumns(), indexDiff.getAddIndexes());
        // 3.3 配额按改完之后的真实列数算
        validateColumnCount(targetColumns.size());

        // 4. 生成并执行 DDL
        List<String> sqlList = buildAlterSqlList(tableName, dataModel, updateReqVO, diff, indexDiff);
        if (CollUtil.isNotEmpty(sqlList)) {
            DdlExecutor.Result result = ddlExecutor().execute(sqlList);
            saveLog(dataModel.getDataSourceConfigId(), tableName,
                    DataModelOperateTypeEnum.ALTER, result);
            if (!result.isSuccess()) {
                // 部分执行的情况要让用户知道，错误信息里带上已执行条数
                String message = result.isPartial()
                        ? String.format("%s（注意：前 %d 条已生效且无法回滚）",
                            result.getErrorMsg(), result.getExecutedSqlList().size())
                        : result.getErrorMsg();
                throw exception(DATA_MODEL_DDL_EXECUTE_FAIL, message);
            }
        }

        // 5. 更新模型登记信息
        DataModelDO updateObj = BeanUtils.toBean(updateReqVO, DataModelDO.class);
        updateObj.setTableName(null); // 表名不允许通过本接口修改
        // 列元数据按改表后的实际列重建：改名要跟随、删列要清理。
        // 不重建的话，改名会让配置静默失联，删列会留下「幽灵配置」附到将来的同名新列上
        updateObj.setColumnMeta(ColumnMetaStore.rebuild(targetColumns, dataModel.getColumnMeta()));
        // 治理字段：来源不变、指纹重新记、接管标记只在「人工改 AI 产物」时打上
        // 没有 DDL 时结构没动过，直接复用第 1 步已经读到的那份，省一次 information_schema 查询
        List<TableColumnModel> afterColumns = CollUtil.isNotEmpty(sqlList)
                ? reader.getColumns(tableName) : currentColumns;
        stampGovernanceOnUpdate(updateObj, dataModel, operator, afterColumns);
        // 🔴 必须查影响行数：拦截器在版本对不上时【不抛异常】，只是让 UPDATE 命中 0 行，
        //    不查的话并发覆盖是静默的
        if (dataModelMapper.updateById(updateObj) == 0) {
            throw exception(DATA_MODEL_RECORD_VERSION_CONFLICT);
        }
    }

    /**
     * 改表时盖上治理字段
     *
     * <p>与 {@link #stampGovernanceOnCreate} 同样，这几个值<b>一律由服务端定</b>，
     * 把 {@code BeanUtils.toBean} 从 VO 拷过来的那份覆盖掉。
     *
     * @param updateObj   待更新的 DO
     * @param dataModel   改表前从库里读到的那一份（提供旧来源与已核过的版本）
     * @param operator    本次操作的入口来源
     * @param afterColumns DDL 之后<b>真实</b>的列
     */
    private void stampGovernanceOnUpdate(DataModelDO updateObj, DataModelDO dataModel,
                                         ArtifactSourceEnum operator,
                                         List<TableColumnModel> afterColumns) {
        // 来源是出生证明：人工改 AI 建的表，它仍然是 AI 建的，只是多了一个「被人接管过」的标记。
        // 置 null 而不是回填旧值 —— MP 的字段策略是 NOT_NULL，null 就不进 SET 子句，
        // 库里那个值原样保留，比「读出来再写回去」少一次覆盖别人改动的机会
        updateObj.setSource(null);
        updateObj.setSchemaFingerprint(SchemaFingerprint.of(afterColumns));
        // 用【核过的那一份】而不是 VO 里的那一份：validateRecordVersion 已经断言两者相等，
        // 但断言写在别处，这里再依赖它就等于把正确性押在「将来没人放宽那道校验」上
        updateObj.setVersion(dataModel.getVersion());
        // 🔴 接管标记是【单调】的：一旦为真就不再回退。
        //    回退的后果是「人改过的 AI 产物」重新变回「AI 可以随便覆盖的东西」，
        //    而下一次 AI 覆盖掉的就是人工的成果 —— 且没人会收到任何提示
        if (operator == ArtifactSourceEnum.MANUAL
                && ArtifactSourceEnum.AI.getSource().equals(dataModel.getSource())) {
            updateObj.setManualEditedFlag(true);
        } else {
            updateObj.setManualEditedFlag(null);
        }
    }

    /**
     * 生成改表的 DDL 列表，包含索引变更、字段变更与表注释变更
     *
     * 顺序很关键：删索引 → 删/加/改字段 → 加索引 → 改表注释。
     * 索引引用的字段可能在本次改表里被删或新建，所以删索引必须抢在删字段之前，
     * 加索引必须落在字段就位之后，否则会报「索引列不存在」。
     */
    private List<String> buildAlterSqlList(String tableName, DataModelDO dataModel,
                                           DataModelSaveReqVO updateReqVO, TableDiffer.Diff diff,
                                           TableDiffer.IndexDiff indexDiff) {
        List<String> sqlList = new java.util.ArrayList<>();
        // 1. 先删索引
        sqlList.addAll(MysqlDdlBuilder.buildIndexAlter(tableName,
                List.of(), indexDiff.getDropIndexNames()));
        // 2. 字段变更（内部已按 删→加→改 排序）
        sqlList.addAll(MysqlDdlBuilder.buildAlterTable(tableName,
                diff.getAddColumns(), diff.getModifyColumns(), diff.getDropColumns()));
        // 3. 再加索引
        sqlList.addAll(MysqlDdlBuilder.buildIndexAlter(tableName,
                indexDiff.getAddIndexes(), List.of()));
        // 4. 表描述变了也要改
        if (!cn.hutool.core.util.StrUtil.equals(dataModel.getTableComment(),
                updateReqVO.getTableComment())) {
            sqlList.add(MysqlDdlBuilder.buildAlterTableComment(tableName,
                    updateReqVO.getTableComment()));
        }
        return sqlList;
    }

    // ==================== 删除 ====================

    /**
     * 🔴 本方法里那两处 {@code deleteById} <b>刻意不带 version 条件</b>，不是漏掉的
     *
     * <p>{@code BaseDO.deleted} 上有 {@code @TableLogic}，所以 {@code deleteById} 发出去的是
     * {@code UPDATE infra_data_model SET deleted = 1 ...} —— 按<b>语句形状</b>它算 B4-4 说的
     * 「update 路径」。但拦截器在这里结构上够不着：{@code doOptimisticLocker} 要从参数 Map 里
     * 取出 {@code et}（实体）才读得到 version，而 {@code deleteById(id)} 的参数只有 id，
     * 于是它<b>静默什么都不做</b> —— 与 {@code FormDesignMapper#updateToDelete} 同因，
     * 那条因此手写了版本条件。
     *
     * <h3>为什么表单那边的删除上了锁、这边不上</h3>
     * 表单删除前判的是「这张表单<b>没有绑定菜单</b>」，而判据来自<b>读到的那一行</b>；
     * 若有人在这中间发布了它、绑上了菜单，不加锁就会删掉一张刚建好菜单的表单，
     * 留下一批指向空处的 {@code system_menu} 记录 —— 那是<b>跨表的悬挂引用</b>。
     *
     * <p>本方法没有这种判据：{@code tableName} 不允许经改表接口修改（那边写死
     * {@code updateObj.setTableName(null)}），所以「要删的是哪张表」不会在读写之间变；
     * 而 {@code dropTable} 分支上的两道判据（表还在不在、有多少行）都是<b>当场读物理表</b>得的，
     * 不是从那条登记行推出来的。剩下能撞的只有「删除与并发改表同时发生，那次改动的结果
     * 随登记行一起消失」—— 行本身正在被删，不存在「改动被静默丢弃却还活着」的形态。
     *
     * <p>给这两处补锁要改 {@code deleteDataModel} 的入参契约（Controller 与前端都得跟着传
     * version），换来的正是上面这个不存在的收益，所以不做。
     *
     * <p>⚠️ 也别为了「两边一致」反过来把表单那边的版本条件摘掉：两边的差别是<b>判据来源</b>
     * （读到的那一行 vs 当场读物理表），不是风格。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDataModel(Long id, Boolean dropTable, Boolean riskConfirmed) {
        DataModelDO dataModel = validateDataModelExists(id);
        String tableName = dataModel.getTableName();
        // 系统内置表（system_/infra_/bpm_/act_ 等前缀）一律禁止通过数据模型删除，
        // 即便只删登记也不行 —— 防止误操作波及框架表
        SqlSafeGuard.checkNotFrameworkTable(tableName);
        // 只删登记、保留真实表：这是安全的默认行为
        if (!Boolean.TRUE.equals(dropTable)) {
            dataModelMapper.deleteById(id);
            return;
        }

        // 要删真实表：有数据需确认
        TableMetaReader reader = metaReader();
        if (reader.existsTable(tableName)) {
            long rows = reader.countRows(tableName);
            if (rows > 0 && !Boolean.TRUE.equals(riskConfirmed)) {
                throw exception(DATA_MODEL_DROP_TABLE_HAS_DATA, tableName, rows);
            }
            DdlExecutor.Result result = ddlExecutor()
                    .execute(MysqlDdlBuilder.buildDropTable(tableName));
            saveLog(dataModel.getDataSourceConfigId(), tableName,
                    DataModelOperateTypeEnum.DROP, result);
            if (!result.isSuccess()) {
                throw exception(DATA_MODEL_DDL_EXECUTE_FAIL, result.getErrorMsg());
            }
        }
        dataModelMapper.deleteById(id);
    }

    // ==================== 导入已有表 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void importTables(Long dataSourceConfigId, List<String> tableNames) {
        TableMetaReader reader = metaReader();
        for (String tableName : tableNames) {
            SqlSafeGuard.checkTableName(tableName);
            // 登记即纳入受管范围，口径必须与建表一致 —— 否则可以把 system_users 导入成数据模型，
            // 之后 getTableDataPage 就能读它的数据
            SqlSafeGuard.checkNotFrameworkTable(tableName);
            if (!reader.existsTable(tableName)) {
                throw exception(DATA_MODEL_TABLE_NOT_EXISTS, tableName);
            }
            // 已登记则跳过，便于重复导入不报错
            if (dataModelMapper.selectByTableNameAndDataSourceConfigId(
                    tableName, dataSourceConfigId) != null) {
                continue;
            }
            String comment = reader.getTableComment(tableName);
            DataModelDO dataModel = new DataModelDO();
            dataModel.setDataSourceConfigId(dataSourceConfigId);
            dataModel.setTableName(tableName);
            dataModel.setTableComment(comment == null ? "" : comment);
            dataModel.setModelName(tableName);
            // 导入的表没有 columnMeta，但同样要有指纹基线 ——
            // 否则第一次改它时两个基线都为空、B4-2 那道闸直接放行，
            // 而这张表恰恰是最可能被绕过平台改过的（它本来就是从外面来的）
            stampGovernanceOnCreate(dataModel, ArtifactSourceEnum.IMPORT, reader.getColumns(tableName));
            dataModelMapper.insert(dataModel);
        }
    }

    // ==================== 查询 ====================

    @Override
    public DataModelDO getDataModel(Long id) {
        // 与本类其余三个「按 id 读」的方法同口径：查不到就抛 DATA_MODEL_NOT_EXISTS。
        // 此前这里是裸 selectById，于是同一个 id 打 /get 得到 success(null)、
        // 打 /structure 得到报错 —— 一个资源两种答复，调用方无法据 code 分支处理。
        return validateDataModelExists(id);
    }

    @Override
    public PageResult<DataModelDO> getDataModelPage(DataModelPageReqVO pageReqVO) {
        return dataModelMapper.selectPage(pageReqVO);
    }

    @Override
    public List<TableColumnModel> getDataModelColumns(Long id) {
        DataModelDO dataModel = validateDataModelExists(id);
        TableMetaReader reader = metaReader();
        if (!reader.existsTable(dataModel.getTableName())) {
            throw exception(DATA_MODEL_TABLE_NOT_EXISTS, dataModel.getTableName());
        }
        List<TableColumnModel> columns = reader.getColumns(dataModel.getTableName());
        // 物理结构来自 information_schema，业务语义（字典/关联）来自 column_meta，在这里汇合
        ColumnMetaStore.merge(columns, dataModel.getColumnMeta());
        return columns;
    }

    @Override
    public DataModelStructureRespVO getDataModelStructure(Long id) {
        DataModelDO dataModel = validateDataModelExists(id);
        TableMetaReader reader = metaReader();
        if (!reader.existsTable(dataModel.getTableName())) {
            throw exception(DATA_MODEL_TABLE_NOT_EXISTS, dataModel.getTableName());
        }
        DataModelStructureRespVO respVO = new DataModelStructureRespVO();
        List<TableColumnModel> columns = reader.getColumns(dataModel.getTableName());
        // 🔴 指纹必须在 merge 之前算。merge 会往列上贴 dictType / refTable / refColumn，
        //    而改表那一侧拿的是 TableMetaReader 的原始列、没有 merge 过 ——
        //    业务语义一旦进了指纹，两处就会对同一张表算出两个值，那道闸从此永远报「结构被人改过」。
        //    SchemaFingerprint 目前确实不算那几个字段，这里把顺序也钉住，免得将来有人放宽
        respVO.setSchemaFingerprint(SchemaFingerprint.of(columns));
        ColumnMetaStore.merge(columns, dataModel.getColumnMeta());
        respVO.setColumns(columns);
        respVO.setIndexes(reader.getIndexes(dataModel.getTableName()));
        // 乐观锁基线。DataModelApi 没有「按 id 读登记行」的出口，
        // 所以改表要回传的两个基线（指纹 + 版本）都只能挂在这份结构上一起给出去
        respVO.setVersion(dataModel.getVersion());
        return respVO;
    }

    @Override
    public List<TableColumnModel> getSystemColumns() {
        return getSystemColumns(null);
    }

    @Override
    public List<TableColumnModel> getSystemColumns(String profile) {
        // 空业务字段列表走一遍注入器，得到的就是该策略下完整的系统内置字段。
        // 走注入器而不是自己列一份，是为了让界面展示与实际建表<b>永远</b>同源
        return SystemColumnInjector.inject(List.of(), SystemColumnProfileEnum.profileOf(profile));
    }

    @Override
    public List<String> previewDdl(DataModelSaveReqVO saveReqVO) {
        List<TableColumnModel> columns = convertColumns(saveReqVO.getColumns());
        validateColumnList(columns);
        List<TableIndexModel> indexes = convertIndexes(saveReqVO.getIndexes());
        // 新建：预览 CREATE
        if (saveReqVO.getId() == null) {
            SqlSafeGuard.checkTableName(saveReqVO.getTableName());
            // 与 createDataModel 同一套校验 —— 否则「预览通过」不等于「保存能过」，
            // AI 或脚本拿 preview 当可行性判据会误判
            SqlSafeGuard.checkNotFrameworkTable(saveReqVO.getTableName());
            validateCreateConvention(saveReqVO, columns, indexes);
            // profile 必须与 createDataModel 取同一个来源，否则预览的 DDL 与实际执行的不一致
            List<TableColumnModel> injected = SystemColumnInjector.inject(columns,
                    SystemColumnProfileEnum.profileOf(saveReqVO.getSystemColumnProfile()));
            validateColumnCount(injected.size());
            // 必须与 createDataModel 走同一套注入逻辑，否则预览的 DDL 与实际执行的不一致
            return List.of(MysqlDdlBuilder.buildCreateTable(saveReqVO.getTableName(),
                    saveReqVO.getTableComment(), injected, indexes));
        }
        // 修改：预览 ALTER
        DataModelDO dataModel = validateDataModelExists(saveReqVO.getId());
        TableMetaReader reader = metaReader();
        if (!reader.existsTable(dataModel.getTableName())) {
            throw exception(DATA_MODEL_TABLE_NOT_EXISTS, dataModel.getTableName());
        }
        String tableName = dataModel.getTableName();
        MysqlDdlBuilder.validateIndexes(indexes, SystemColumnInjector.inject(columns));
        TableDiffer.Diff diff = TableDiffer.diff(columns, reader.getColumns(tableName));
        TableDiffer.IndexDiff indexDiff = TableDiffer.diffIndexes(indexes, reader.getIndexes(tableName));
        validateAlterConvention(diff.getAddColumns(), indexDiff.getAddIndexes());
        validateColumnCount(columns.size());
        return buildAlterSqlList(tableName, dataModel, saveReqVO, diff, indexDiff);
    }

    @Override
    public List<Map<String, Object>> getDatabaseTableList(Long dataSourceConfigId,
                                                          String tableName, String tableComment) {
        return metaReader().getTableList(tableName, tableComment);
    }

    @Override
    public PageResult<Map<String, Object>> getTableDataPage(Long id, Integer pageNo, Integer pageSize) {
        DataModelDO dataModel = validateDataModelExists(id);
        String tableName = dataModel.getTableName();
        // 表名来自已登记的模型，且再过一次校验
        SqlSafeGuard.checkTableName(tableName);
        TableMetaReader reader = metaReader();
        if (!reader.existsTable(tableName)) {
            throw exception(DATA_MODEL_TABLE_NOT_EXISTS, tableName);
        }
        long total = reader.countRows(tableName);
        if (total == 0) {
            return PageResult.empty();
        }
        // LIMIT 的值用占位符，不拼接
        int offset = (pageNo - 1) * pageSize;
        List<Map<String, Object>> list = jdbcTemplate.queryForList(
                "SELECT * FROM " + SqlSafeGuard.wrap(tableName) + " LIMIT ?, ?", offset, pageSize);
        return new PageResult<>(list, total);
    }

    @Override
    public PageResult<DataModelLogDO> getDataModelLogPage(DataModelLogPageReqVO pageReqVO) {
        return dataModelLogMapper.selectPage(pageReqVO);
    }

    // ==================== 校验与转换 ====================

    private DataModelDO validateDataModelExists(Long id) {
        DataModelDO dataModel = dataModelMapper.selectById(id);
        if (dataModel == null) {
            throw exception(DATA_MODEL_NOT_EXISTS);
        }
        return dataModel;
    }

    /**
     * 建模规范校验（新建路径专用）
     *
     * 🔴 <b>为什么只在新建时做</b>：库里存量表五花八门 —— {@code QRTZ_LOCKS} 是大写、
     * {@code test_demo_*} 20 张样板表没有 {@code biz_} 前缀、导入表的列注释可能本就为空。
     * 规范若作用于改表，这些表会<b>立刻改不动</b>，而它们正在被表单设计器使用。
     *
     * <p>所以口径是：<b>新建从严、存量放行、改表只管新增的部分</b>。
     * 这不是妥协，是规范落到有历史的库上唯一可行的方式。
     *
     * @see #validateAlterConvention 改表路径的对应方法
     */
    @VisibleForTesting
    void validateCreateConvention(DataModelSaveReqVO reqVO, List<TableColumnModel> businessColumns,
                                  List<TableIndexModel> indexes) {
        String tableName = reqVO.getTableName();
        // R6 表名 snake_case
        SqlSafeGuard.checkTableNameSnakeCase(tableName, dataModelProperties.getSnakeCaseEnforced());
        // R5 业务表前缀。与 checkNotFrameworkTable 的黑名单是两道独立关卡，都要过
        SqlSafeGuard.checkBusinessTablePrefix(tableName,
                dataModelProperties.getTablePrefixEnforced(), dataModelProperties.getTablePrefix());
        // R8 表注释质量
        if (!SqlSafeGuard.isMeaningfulComment(reqVO.getTableComment(), tableName)) {
            throw exception(DATA_MODEL_TABLE_COMMENT_INVALID, reqVO.getTableComment());
        }
        // R6/R8 业务列的命名与注释
        validateNewColumnsConvention(businessColumns);
        // R7 索引名前缀
        validateIndexNamePrefix(indexes);
    }

    /**
     * 建模规范校验（改表路径）
     *
     * 只校验<b>本次新增</b>的列与索引 —— 存量列即使不合规范也要能继续改表，理由见
     * {@link #validateCreateConvention}。
     */
    @VisibleForTesting
    void validateAlterConvention(List<TableColumnModel> addColumns, List<TableIndexModel> addIndexes) {
        validateNewColumnsConvention(addColumns);
        validateIndexNamePrefix(addIndexes);
    }

    /**
     * 校验新增字段的命名与注释规范
     *
     * 系统列跳过：它们由 {@link SystemColumnInjector} 生成，注释是「创建者」「是否删除」这类
     * 框架托管的固定文案，不该受业务规范约束。
     */
    private void validateNewColumnsConvention(List<TableColumnModel> columns) {
        for (TableColumnModel column : CollUtil.emptyIfNull(columns)) {
            String columnName = column.getColumnName();
            if (SystemColumnInjector.isSystemColumn(columnName)) {
                continue;
            }
            // R6 列名 snake_case
            SqlSafeGuard.checkColumnNameSnakeCase(columnName, dataModelProperties.getSnakeCaseEnforced());
            // R8 列注释质量。此前只拦 isBlank —— 空格拦得住，"1" 和「字段名原样复制」拦不住
            if (!SqlSafeGuard.isMeaningfulComment(column.getColumnComment(), columnName)) {
                throw exception(DATA_MODEL_COLUMN_COMMENT_INVALID, columnName, column.getColumnComment());
            }
        }
    }

    /**
     * R7 校验索引名前缀
     */
    private void validateIndexNamePrefix(List<TableIndexModel> indexes) {
        for (TableIndexModel index : CollUtil.emptyIfNull(indexes)) {
            SqlSafeGuard.checkIndexNamePrefix(index.getIndexName(), index.isUnique(),
                    dataModelProperties.getIndexPrefixEnforced());
        }
    }

    /**
     * R10 校验单表字段数配额
     *
     * @param columnCount 注入系统列之后的真实列数
     */
    @VisibleForTesting
    void validateColumnCount(int columnCount) {
        Integer max = dataModelProperties.getMaxColumnCount();
        if (max != null && max > 0 && columnCount > max) {
            throw exception(DATA_MODEL_COLUMN_COUNT_EXCEED, columnCount, max);
        }
    }

    /**
     * R10 校验数据模型数量配额
     *
     * 这条是<b>接口调用方特有的风险</b>：人工建表手速有限，天然不会失控；
     * 带重试循环的调用方（脚本、AI）一次失控就能把库刷满。
     */
    @VisibleForTesting
    void validateTableCount() {
        Integer max = dataModelProperties.getMaxTableCount();
        if (max == null || max <= 0) {
            return;
        }
        Long count = dataModelMapper.selectCount();
        if (count != null && count >= max) {
            throw exception(DATA_MODEL_TABLE_COUNT_EXCEED, max);
        }
    }

    /**
     * 校验字段列表：字段名合法、类型受支持、长度合理
     *
     * 主键数量与重名由 MysqlDdlBuilder 校验，此处不重复。
     */
    private void validateColumnList(List<TableColumnModel> columns) {
        if (CollUtil.isEmpty(columns)) {
            throw exception(DATA_MODEL_COLUMN_EMPTY);
        }
        for (TableColumnModel column : columns) {
            SqlSafeGuard.checkColumnName(column.getColumnName());
            // 描述必填只针对业务字段：系统内置字段（id/审计/租户/删除）的描述由框架托管，
            // 导入的真实表里可能本就为空且用户只读无法填写，不应因此卡住预览与保存
            if (StrUtil.isBlank(column.getColumnComment())
                    && !SystemColumnInjector.isSystemColumn(column.getColumnName())) {
                throw exception(DATA_MODEL_COLUMN_COMMENT_EMPTY, column.getColumnName());
            }
            DataModelColumnTypeEnum typeEnum = DataModelColumnTypeEnum.typeOf(column.getColumnType());
            if (typeEnum == null) {
                throw exception(DATA_MODEL_COLUMN_TYPE_INVALID,
                        column.getColumnName(), column.getColumnType());
            }
            // 长度必须为正；DECIMAL 的小数位不能超过总位数
            if (column.getLength() != null && column.getLength() <= 0) {
                throw exception(DATA_MODEL_COLUMN_LENGTH_INVALID, column.getColumnName());
            }
            if (typeEnum == DataModelColumnTypeEnum.DECIMAL
                    && column.getLength() != null && column.getScale() != null
                    && column.getScale() >= column.getLength()) { // 与 MysqlDdlBuilder 一致：scale 必须严格小于 length
                throw exception(DATA_MODEL_COLUMN_LENGTH_INVALID, column.getColumnName());
            }
        }
    }

    /**
     * 有损操作校验：删字段且表里有数据时，必须显式确认
     */
    /**
     * 校验系统列没有被删除
     *
     * 🔴 这一条防的是本模块最危险的一种静默数据破坏。
     *
     * <p>改表是<b>全量同步语义</b>：{@code TableDiffer} 把「真实表里有、而提交的 columns 里没有」
     * 的列一律推断为待删除。而改表路径<b>不走</b> {@link SystemColumnInjector}（建表路径才走），
     * 于是调用方只要按「我只想加一个字段」的直觉提交业务列，7 个系统列就全部落进 dropColumns。
     *
     * <p>原先只有 {@link #validatePrimaryKeyNotChanged} 挡住了 id，其余六列靠
     * {@link #validateLossyChange} —— 但它在 {@code rows == 0} 时直接放行。
     * 也就是说<b>空表改结构会静默删掉 tenant_id 与 deleted</b>：
     * 前者一丢，动态 CRUD 拼不出租户条件，跨租户数据泄漏；
     * 后者一丢，逻辑删除变成物理删除，不可恢复。全程不报错、不可回滚。
     *
     * <p>所以这里<b>无论表里有没有数据、无论 riskConfirmed 传什么，一律拒绝</b>，
     * 必须排在 validateLossyChange 之前 —— 否则 riskConfirmed=true 会先把它放过去。
     *
     * <p>⚠️ 没有采用「在 diff 前调 inject() 自动补齐」的方案：inject() 生成的是合成定义
     * （如 {@code creator varchar(64) default ''}），真实表的定义若与之有出入，
     * diff 会产出一批意料之外的 ALTER。宁可报错让调用方按正确姿势重来 ——
     * 先 GET /structure 取回全量列，在其上增删改，再整体回传。
     */
    @VisibleForTesting
    void validateSystemColumnsNotDropped(TableDiffer.Diff diff) {
        List<String> dropped = diff.getDropColumns().stream()
                .filter(SystemColumnInjector::isSystemColumn)
                .toList();
        if (!dropped.isEmpty()) {
            throw exception(DATA_MODEL_SYSTEM_COLUMN_DROP_DENY, String.join("、", dropped));
        }
    }

    /**
     * 有损变更校验：删列且表里有数据时，要显式确认
     *
     * <p>⚠️ <b>它挡不住空表。</b>{@code rows == 0} 时直接放行 —— 理由是「没有数据可丢」。
     * 这个理由对「丢数据」成立，对「丢结构」不成立：DDL 一样不可回滚，
     * 表被清成只剩一列之后，重建结构比重灌数据更麻烦。
     * 所以 B4-3 另立了 {@link #validateDropColumnsConfirmed}，<b>不看行数</b>。
     * 两道闸是互补的，不是重复的：本闸管「数据能不能丢」，那道管「你是不是真要删这些列」。
     */
    private void validateLossyChange(TableDiffer.Diff diff, String tableName,
                                     TableMetaReader reader, Boolean riskConfirmed) {
        if (!diff.hasLossyChange() || Boolean.TRUE.equals(riskConfirmed)) {
            return;
        }
        long rows = reader.countRows(tableName);
        if (rows > 0) {
            throw exception(DATA_MODEL_DROP_COLUMN_HAS_DATA,
                    String.join("、", diff.getDropColumns()), rows);
        }
    }

    /**
     * B4-3：删列一律要显式确认，<b>与表里有没有数据无关</b>
     *
     * <p>🔴 这一条堵的是本模块最容易踩、且踩了完全无声的那个坑。
     * 改表是<b>全量同步语义</b>：{@link TableDiffer} 把「真实表里有、提交的列表里没有」的列
     * 一律推断为待删除。于是「我只想加一个字段」的直觉写法 —— 只提交那一个字段 ——
     * 等于把其余所有业务列都删掉。
     *
     * <p>在此之前，空表上这么干是<b>静默成功</b>的：
     * {@link #validateLossyChange} 因为 {@code rows == 0} 放行，
     * {@link #validateSystemColumnsNotDropped} 只管那 7 个系统列，
     * 于是接口返回成功、业务列全没了、DDL 不可回滚。
     * AI 走工具时尤其容易撞上：它按「增量」的直觉构造参数，而接口是「全量」的语义。
     *
     * <p>⚠️ 与 {@code riskConfirmed} <b>不可互换</b>，也不合并成一个参数：
     * riskConfirmed 的语义是「我看过数据、确认这些数据可以丢」，只在有数据时才需要；
     * confirmDropColumns 的语义是「我确实要删这些列」，与数据无关。
     * 合成一个的话，用户在「删空表的列」时被问「确认丢弃数据吗」，
     * 而表里根本没有数据 —— 一个与事实不符的提问，只会训练出「闭着眼睛点确认」的习惯。
     */
    @VisibleForTesting
    void validateDropColumnsConfirmed(TableDiffer.Diff diff, Boolean confirmDropColumns) {
        if (CollUtil.isEmpty(diff.getDropColumns()) || Boolean.TRUE.equals(confirmDropColumns)) {
            return;
        }
        throw exception(DATA_MODEL_DROP_COLUMN_NEED_CONFIRM, String.join("、", diff.getDropColumns()));
    }

    /**
     * B4-4：记录级乐观锁的两道检查
     *
     * <p>🔴 <b>「没带版本」必须是硬拒绝，不能退化成「那就不加锁」。</b>
     * MyBatis-Plus 的拦截器在实体的 version 为 null 时<b>静默跳过</b>版本条件
     * （3.5.16 的 {@code doOptimisticLocker} 取到 null 就 return），
     * 于是一条漏传版本的调用会把乐观锁悄悄关掉，而且<b>不报错</b> ——
     * 这正是「加了注解只算 10%」那 90% 的失效形态。
     *
     * <p>拦截器提供了 {@code setException} 能把这种情况变成失败，但那是<b>单例级、跨全仓</b>的开关，
     * 装在一个连编译都参与不了的模块也在用的 framework 里，且共享的异常实例堆栈指向 bean 构造处。
     * 所以绊线设在这里：按路径显式判，用本模块自己的错误码报。
     *
     * <p>第二道（版本相等）挡的是常见的那一种并发：用户在 A 窗口打开改表页，
     * B 窗口（或 AI 工具）改完并保存，A 再提交。窗口是几分钟量级，
     * 而 A 手里那份全量列列表已经是过期的 —— 直接写回会把 B 的改动整份抹掉。
     */
    @VisibleForTesting
    void validateRecordVersion(Integer reqVersion, DataModelDO dataModel) {
        if (reqVersion == null) {
            throw exception(DATA_MODEL_RECORD_VERSION_REQUIRED);
        }
        if (!reqVersion.equals(dataModel.getVersion())) {
            throw exception(DATA_MODEL_RECORD_VERSION_CONFLICT);
        }
    }

    /**
     * B4-2：DDL 执行前的结构指纹复核
     *
     * <p>防的是「接口返回成功、配置却悄悄失效」：ALTER 本身照样能执行成功
     * （diff 是拿<b>实时</b>结构算的），但调用方手里那份列列表描述的已经是另一张表了，
     * 于是它一并提交的 {@code columnMeta}、以及绑在这张表上的表单字段映射，全部指向了错位的东西。
     *
     * <h3>基线的两个来源，与为什么这样选</h3>
     * 优先用<b>调用方声明它读到的那一份</b>（{@code declared}），为空时退回<b>库里记的基线</b>。
     * <ul>
     *   <li>声明值来自 {@code GET /structure}，那个接口返回的是<b>实时</b>算出来的指纹，
     *       不是库存基线 —— 返回库存值的话，回传上来就变成「自己跟自己比」，永远相等，
     *       这道闸就是个空操作</li>
     *   <li>退回库存基线，是为了兜住不传指纹的调用方（老前端、只改了表描述的调用）。
     *       库存基线是本模块<b>上一次亲手产出</b>的结构，它与实时结构不一致
     *       就说明有人绕过平台动过这张表 —— 而调用方对此一无所知，此时不该放行</li>
     * </ul>
     *
     * <p>🔴 <b>这条闸永远有出路，不会把模型锁死。</b>两个基线都为空时直接放行
     * （本列上线之前建的存量模型从来没记过指纹，判成「被人改过」的话它们从此全改不动），
     * DDL 成功后补记；对不上时，出路就是重新读一次结构 —— 读到的正是实时指纹，
     * 带着它再提交即可通过。
     */
    @VisibleForTesting
    void validateSchemaNotDrifted(DataModelDO dataModel, String declared,
                                  List<TableColumnModel> currentColumns) {
        String baseline = StrUtil.blankToDefault(declared, dataModel.getSchemaFingerprint());
        if (!SchemaFingerprint.isRecorded(baseline)) {
            return;
        }
        String actual = SchemaFingerprint.of(currentColumns);
        if (!actual.equals(baseline)) {
            throw exception(DATA_MODEL_SCHEMA_DRIFT, dataModel.getTableName(),
                    SchemaFingerprint.shortOf(baseline), SchemaFingerprint.shortOf(actual));
        }
    }

    /**
     * 主键变更校验
     *
     * 改主键要重建索引、可能涉及数据迁移，出错代价高。本方案直接不支持，
     * 引导用户重建表 —— 这比提供一个半可靠的改主键功能更负责。
     */
    private void validatePrimaryKeyNotChanged(TableDiffer.Diff diff,
                                              List<TableColumnModel> currentColumns) {
        Set<String> currentPkNames = currentColumns.stream()
                .filter(TableColumnModel::isPrimaryKey)
                .map(column -> column.getColumnName().toLowerCase())
                .collect(Collectors.toSet());
        // 删掉主键字段
        for (String dropColumn : diff.getDropColumns()) {
            if (currentPkNames.contains(dropColumn.toLowerCase())) {
                throw exception(DATA_MODEL_PRIMARY_KEY_CHANGE_DENY, dropColumn);
            }
        }
        // 修改主键字段本身，或把普通字段改成主键
        for (TableColumnModel column : diff.getModifyColumns()) {
            String name = column.getColumnName().toLowerCase();
            boolean wasPk = currentPkNames.contains(name)
                    || (column.getOldColumnName() != null
                        && currentPkNames.contains(column.getOldColumnName().toLowerCase()));
            if (wasPk || column.isPrimaryKey()) {
                throw exception(DATA_MODEL_PRIMARY_KEY_CHANGE_DENY, column.getColumnName());
            }
        }
        // 新增字段声明为主键
        for (TableColumnModel column : diff.getAddColumns()) {
            if (column.isPrimaryKey()) {
                throw exception(DATA_MODEL_PRIMARY_KEY_CHANGE_DENY, column.getColumnName());
            }
        }
    }

    private List<TableColumnModel> convertColumns(List<DataModelColumnSaveReqVO> columns) {
        if (CollUtil.isEmpty(columns)) {
            return List.of();
        }
        return columns.stream()
                .map(vo -> BeanUtils.toBean(vo, TableColumnModel.class))
                .collect(Collectors.toList());
    }

    private List<TableIndexModel> convertIndexes(List<DataModelIndexSaveReqVO> indexes) {
        if (CollUtil.isEmpty(indexes)) {
            return List.of();
        }
        return indexes.stream()
                .map(vo -> new TableIndexModel()
                        .setIndexName(vo.getIndexName())
                        .setIndexType(vo.getIndexType())
                        .setColumnNames(vo.getColumnNames()))
                .collect(Collectors.toList());
    }

    /**
     * 记录 DDL 执行日志
     *
     * 无论成功失败都记录 —— 失败的 DDL 同样是排查线索。
     * 刻意不加 @Transactional：日志要独立于业务事务落库，否则业务回滚会把日志一起回滚掉。
     */
    private void saveLog(Long dataSourceConfigId, String tableName,
                         DataModelOperateTypeEnum operateType, DdlExecutor.Result result) {
        try {
            DataModelLogDO log = new DataModelLogDO();
            log.setDataSourceConfigId(dataSourceConfigId);
            log.setTableName(tableName);
            log.setOperateType(operateType.getType());
            // 记录全部涉及的语句：已执行的 + 失败的那条
            List<String> allSql = new java.util.ArrayList<>(result.getExecutedSqlList());
            if (result.getFailedSql() != null) {
                allSql.add("-- 【失败】" + result.getFailedSql());
            }
            log.setExecuteSql(String.join(";\n", allSql));
            log.setSuccess(result.isSuccess());
            log.setErrorMsg(result.getErrorMsg() == null ? "" : result.getErrorMsg());
            log.setDuration((int) result.getDuration());
            dataModelLogMapper.insert(log);
        } catch (Exception ex) {
            // 记日志失败不能影响主流程
            log.error("[saveLog][记录 DDL 日志失败，表({})]", tableName, ex);
        }
    }

}
