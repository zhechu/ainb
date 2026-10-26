package cn.enlev.ainb.module.infra.service.datamodel;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.module.infra.controller.admin.datamodel.vo.DataModelDataPageReqVO;
import cn.enlev.ainb.module.infra.dal.dataobject.datamodel.DataModelDO;
import cn.enlev.ainb.module.infra.dal.mysql.datamodel.DataModelMapper;
import cn.enlev.ainb.module.infra.framework.datamodel.core.*;
import cn.enlev.ainb.module.infra.framework.datamodel.core.model.TableColumnModel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.*;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.*;

/**
 * 动态数据 Service 实现类
 *
 * 每个操作都走同一套安全前置流程（见 {@link #prepare}）：
 * 1. 模型必须已登记 → 表在受管白名单内
 * 2. 表必须真实存在
 * 3. 拿到实时字段清单，供字段名白名单校验
 *
 * 少了任何一步，{@link DynamicSqlExecutor} 就失去了判断依据。
 *
 * @author 走进新时代
 */
@Service
@Validated
@Slf4j
public class DataModelDataServiceImpl implements DataModelDataService {

    @Resource
    private DataModelMapper dataModelMapper;
    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * 一次操作所需的上下文
     */
    private record Context(DataModelDO model, String tableName,
                           Set<String> columnNames, List<TableColumnModel> columns) {
    }

    /**
     * 安全前置：校验模型与表，取回实时字段清单
     */
    private Context prepare(Long modelId) {
        DataModelDO model = dataModelMapper.selectById(modelId);
        if (model == null) {
            throw exception(DATA_MODEL_NOT_EXISTS);
        }
        String tableName = model.getTableName();
        // 第一关：标识符合法性
        SqlSafeGuard.checkTableName(tableName);
        // 第二关：受管范围 —— 表名来自已登记的模型，这里再确认一次，防止登记数据被篡改
        Set<String> managed = new HashSet<>();
        dataModelMapper.selectListByDataSourceConfigId(model.getDataSourceConfigId())
                .forEach(item -> managed.add(item.getTableName()));
        SqlSafeGuard.checkTableManaged(tableName, managed);

        TableMetaReader reader = new TableMetaReader(jdbcTemplate);
        if (!reader.existsTable(tableName)) {
            throw exception(DATA_MODEL_TABLE_NOT_EXISTS, tableName);
        }
        // 第三关的数据来源：实时字段清单
        List<TableColumnModel> columns = reader.getColumns(tableName);
        Set<String> columnNames = reader.getColumnNames(tableName);
        return new Context(model, tableName, columnNames, columns);
    }

    private DynamicSqlExecutor executor() {
        return new DynamicSqlExecutor(jdbcTemplate);
    }

    // ==================== 查询 ====================

    @Override
    public PageResult<Map<String, Object>> getDataPage(DataModelDataPageReqVO pageReqVO) {
        Context ctx = prepare(pageReqVO.getModelId());
        List<DynamicSqlExecutor.QueryCondition> conditions =
                convertConditions(pageReqVO.getConditions(), ctx);
        // 排序字段走白名单，这是 ORDER BY 无法参数化的必要防护
        String orderBy = null;
        if (StrUtil.isNotBlank(pageReqVO.getSortField())) {
            orderBy = SqlSafeGuard.checkOrderColumn(pageReqVO.getSortField(),
                    Boolean.TRUE.equals(pageReqVO.getSortAsc()), ctx.columnNames());
        }

        DynamicSqlExecutor executor = executor();
        long total = executor.selectCount(ctx.tableName(), ctx.columnNames(), conditions);
        if (total == 0) {
            return PageResult.empty();
        }
        int offset = (pageReqVO.getPageNo() - 1) * pageReqVO.getPageSize();
        List<Map<String, Object>> list = executor.selectPage(ctx.tableName(), ctx.columnNames(),
                conditions, orderBy, offset, pageReqVO.getPageSize());
        return new PageResult<>(list, total);
    }

    @Override
    public Map<String, Object> getData(Long modelId, Object id) {
        Context ctx = prepare(modelId);
        Map<String, Object> data = executor().selectById(ctx.tableName(), ctx.columnNames(), id);
        if (data == null) {
            throw exception(DATA_MODEL_DATA_NOT_EXISTS);
        }
        return data;
    }

    @Override
    public List<TableColumnModel> getDataColumns(Long modelId) {
        return prepare(modelId).columns();
    }

    // ==================== 写操作 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Object createData(Long modelId, Map<String, Object> data) {
        Context ctx = prepare(modelId);
        // 按表的真实类型转换值，不信前端声明的类型
        Map<String, Object> converted = ValueConverter.convertRow(data, ctx.columns());
        return executor().insert(ctx.tableName(), ctx.columnNames(), converted);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateData(Long modelId, Object id, Map<String, Object> data) {
        Context ctx = prepare(modelId);
        Map<String, Object> converted = ValueConverter.convertRow(data, ctx.columns());
        int rows = executor().updateById(ctx.tableName(), ctx.columnNames(), id, converted);
        // 影响 0 行有两种可能：数据不存在，或不属于当前租户。对调用方而言都是「找不到」
        if (rows == 0) {
            throw exception(DATA_MODEL_DATA_NOT_EXISTS);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteData(Long modelId, Object id) {
        Context ctx = prepare(modelId);
        int rows = executor().deleteById(ctx.tableName(), ctx.columnNames(), id);
        if (rows == 0) {
            throw exception(DATA_MODEL_DATA_NOT_EXISTS);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDataList(Long modelId, List<Object> ids) {
        if (CollUtil.isEmpty(ids)) {
            return;
        }
        // 复用单条删除的校验逻辑；批量量级不大，不做特殊优化
        Context ctx = prepare(modelId);
        DynamicSqlExecutor executor = executor();
        for (Object id : ids) {
            executor.deleteById(ctx.tableName(), ctx.columnNames(), id);
        }
    }

    // ==================== 条件转换 ====================

    /**
     * 把前端传的条件转成执行器的内部表示
     *
     * 操作符必须落在受控枚举里 —— 前端传任何非法值都会被拒绝，而不是拼进 SQL
     */
    private List<DynamicSqlExecutor.QueryCondition> convertConditions(
            List<DataModelDataPageReqVO.Condition> conditions, Context ctx) {
        if (CollUtil.isEmpty(conditions)) {
            return Collections.emptyList();
        }
        List<DynamicSqlExecutor.QueryCondition> result = new ArrayList<>();
        for (DataModelDataPageReqVO.Condition condition : conditions) {
            // 值为空的条件直接跳过，等价于「不筛选」，省得前端清空输入框后还要删条件
            if (isBlankValue(condition.getValue())
                    && !isNullOperator(condition.getOperator())) {
                continue;
            }
            DynamicSqlExecutor.Operator operator = parseOperator(condition.getOperator());
            // 字段真实性校验（第三关）
            SqlSafeGuard.checkColumnExists(condition.getColumnName(), ctx.columnNames());
            // 按字段真实类型转换条件值，否则字符串比数字会走不到索引
            Object value = convertConditionValue(condition, ctx);
            result.add(new DynamicSqlExecutor.QueryCondition(
                    condition.getColumnName(), operator, value));
        }
        return result;
    }

    private DynamicSqlExecutor.Operator parseOperator(String operator) {
        if (StrUtil.isBlank(operator)) {
            return DynamicSqlExecutor.Operator.EQ;
        }
        try {
            return DynamicSqlExecutor.Operator.valueOf(operator.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw exception(DATA_MODEL_OPERATOR_INVALID, operator);
        }
    }

    private boolean isNullOperator(String operator) {
        return "IS_NULL".equalsIgnoreCase(operator) || "IS_NOT_NULL".equalsIgnoreCase(operator);
    }

    private boolean isBlankValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String str) {
            return StrUtil.isBlank(str);
        }
        if (value instanceof Collection<?> coll) {
            return coll.isEmpty();
        }
        return false;
    }

    /**
     * 转换条件值
     *
     * LIKE 恒按字符串处理（对数字字段做模糊匹配也是合理需求）；
     * BETWEEN / IN 是集合，逐个元素转换。
     */
    private Object convertConditionValue(DataModelDataPageReqVO.Condition condition, Context ctx) {
        Object value = condition.getValue();
        if ("LIKE".equalsIgnoreCase(condition.getOperator())) {
            return value == null ? null : value.toString();
        }
        String columnType = ctx.columns().stream()
                .filter(item -> item.getColumnName().equalsIgnoreCase(condition.getColumnName()))
                .map(TableColumnModel::getColumnType)
                .findFirst().orElse(null);
        if (columnType == null) {
            return value;
        }
        if (value instanceof Collection<?> coll) {
            List<Object> converted = new ArrayList<>();
            coll.forEach(item -> converted.add(ValueConverter.convert(item, columnType)));
            return converted;
        }
        return ValueConverter.convert(value, columnType);
    }

}
