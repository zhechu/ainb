package cn.enlev.ainb.module.infra.service.datainterface;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.pojo.PageParam;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.framework.common.util.servlet.ServletUtils;
import cn.enlev.ainb.framework.security.core.service.SecurityFrameworkService;
import cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils;
import cn.enlev.ainb.module.infra.controller.admin.datainterface.vo.DataInterfaceLogPageReqVO;
import cn.enlev.ainb.module.infra.controller.admin.datainterface.vo.DataInterfacePageReqVO;
import cn.enlev.ainb.module.infra.controller.admin.datainterface.vo.DataInterfaceSaveReqVO;
import cn.enlev.ainb.module.infra.controller.admin.datainterface.vo.DataInterfaceSqlDetectRespVO;
import cn.enlev.ainb.module.infra.dal.dataobject.datainterface.DataInterfaceDO;
import cn.enlev.ainb.module.infra.dal.dataobject.datainterface.DataInterfaceLogDO;
import cn.enlev.ainb.module.infra.dal.dataobject.datainterface.DataInterfaceVersionDO;
import cn.enlev.ainb.module.infra.dal.mysql.datainterface.DataInterfaceLogMapper;
import cn.enlev.ainb.module.infra.dal.mysql.datainterface.DataInterfaceMapper;
import cn.enlev.ainb.module.infra.dal.mysql.datainterface.DataInterfaceVersionMapper;
import cn.enlev.ainb.module.infra.enums.datainterface.DataInterfaceTypeEnum;
import cn.enlev.ainb.module.infra.enums.datainterface.SqlStatementTypeEnum;
import cn.enlev.ainb.module.infra.framework.datainterface.core.executor.HttpExecutor;
import cn.enlev.ainb.module.infra.framework.datainterface.core.executor.InterfaceExecuteContext;
import cn.enlev.ainb.module.infra.framework.datainterface.core.executor.InterfaceExecutor;
import cn.enlev.ainb.module.infra.framework.datainterface.core.param.ParamDefinition;
import cn.enlev.ainb.module.infra.framework.datainterface.core.param.SqlParamBinder;
import cn.enlev.ainb.module.infra.framework.datainterface.core.script.ScriptPostProcessor;
import cn.enlev.ainb.module.infra.framework.datainterface.core.sql.SqlPolicyValidator;
import cn.enlev.ainb.module.infra.framework.datainterface.core.sql.SqlStatementAnalyzer;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;

import java.util.*;
import java.util.stream.Collectors;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.*;

/**
 * 数据接口 Service 实现类
 *
 * 职责编排：配置 CRUD、版本快照、执行调度、调用日志。
 * 真正的执行逻辑在各个 {@link InterfaceExecutor} 里，本类只负责编排。
 *
 * ⚠️ 多语句的事务边界：{@link #invoke} 与 {@link #preview} 标了
 * {@code @Transactional}，保证「一个接口里的多条语句失败全回滚」。
 * 由于已禁止 DDL（见 {@link SqlPolicyValidator}），事务保证是可靠的。
 *
 * @author 走进新时代
 */
@Service
@Validated
@Slf4j
public class DataInterfaceServiceImpl implements DataInterfaceService {

    /**
     * 调用方式：在线预览
     */
    private static final int INVOKE_TYPE_PREVIEW = 1;
    /**
     * 调用方式：接口调用
     */
    private static final int INVOKE_TYPE_INVOKE = 2;
    /**
     * 预览返回条数上限
     */
    private static final int PREVIEW_LIMIT = 100;
    /**
     * 日志字段长度上限，超长截断，避免单条日志过大
     */
    private static final int LOG_TEXT_MAX_LENGTH = 4000;
    /**
     * 失败原因摘要长度上限，供日志列表「报错原因」列展示
     */
    private static final int LOG_ERROR_MSG_MAX_LENGTH = 500;
    /**
     * 单个接口最多保留的版本数，超出后清理最旧的
     */
    private static final int VERSION_MAX_KEEP = 100;
    /**
     * 配置写操作 SQL 所需的权限串
     *
     * 写成常量而不是散在各处的字面量：它同时是菜单按钮（system_menu）与
     * 权限资源（sys_resource）的锚点，三处必须逐字一致，改一处漏两处就是静默放行
     */
    private static final String PERMISSION_WRITE_SQL = "infra:data-interface:write-sql";

    @Resource
    private DataInterfaceMapper dataInterfaceMapper;
    @Resource
    private DataInterfaceVersionMapper versionMapper;
    @Resource
    private DataInterfaceLogMapper logMapper;
    /**
     * 所有执行器，由 Spring 注入后按类型建索引
     */
    @Resource
    private List<InterfaceExecutor> executors;
    /**
     * JS 后处理器。三种类型的接口都会在执行后走一遍
     */
    @Resource
    private ScriptPostProcessor scriptPostProcessor;
    /**
     * 事务管理器：调用日志要在独立事务里提交，见 {@link #saveLog}
     */
    @Resource
    private PlatformTransactionManager transactionManager;
    /**
     * 编程式权限校验，只用于 {@link #PERMISSION_WRITE_SQL} 这一条
     *
     * 为什么不用 {@code @PreAuthorize}：那条注解只能钉在方法上，而这里要判的是
     * <b>请求体内容</b>（SQL 里有没有写语句）—— 解析完才知道要不要这个权限，
     * 进方法之前判不了。同模块的 {@code FormDesignRuntimeController} 是同一个理由
     */
    @Resource
    private SecurityFrameworkService securityFrameworkService;

    private Map<Integer, InterfaceExecutor> executorMap;

    private InterfaceExecutor getExecutor(Integer type) {
        if (executorMap == null) {
            Map<Integer, InterfaceExecutor> map = new HashMap<>();
            executors.forEach(item -> map.put(item.getType().getType(), item));
            executorMap = map;
        }
        InterfaceExecutor executor = executorMap.get(type);
        if (executor == null) {
            throw exception(DATA_INTERFACE_TYPE_INVALID, type);
        }
        return executor;
    }

    // ==================== 配置 CRUD ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createDataInterface(DataInterfaceSaveReqVO createReqVO) {
        validateCodeUnique(null, createReqVO.getCode());
        // SQL 类型要做策略校验并解析出语句类型
        SqlAnalyzeInfo analyzeInfo = analyzeSqlIfNeeded(createReqVO);

        DataInterfaceDO entity = BeanUtils.toBean(createReqVO, DataInterfaceDO.class);
        entity.setParams(JsonUtils.toJsonString(createReqVO.getParams()));
        entity.setStatus(createReqVO.getStatus() == null ? 0 : createReqVO.getStatus());
        // 日志开关默认开启，仅当显式传 0 时关闭
        entity.setLogEnabled(createReqVO.getLogEnabled() == null ? 1 : createReqVO.getLogEnabled());
        entity.setVersion(1);
        entity.setSqlStatementType(analyzeInfo.statementType);
        entity.setHasWriteOperation(analyzeInfo.writeOperation);
        // 分页开关取联动修正后的值，非查询动作会被置 0
        entity.setHasPage(analyzeInfo.hasPage);
        dataInterfaceMapper.insert(entity);
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDataInterface(DataInterfaceSaveReqVO updateReqVO) {
        DataInterfaceDO oldEntity = validateExists(updateReqVO.getId());
        validateCodeUnique(updateReqVO.getId(), updateReqVO.getCode());
        SqlAnalyzeInfo analyzeInfo = analyzeSqlIfNeeded(updateReqVO);

        // 先把「修改前的配置」存为版本快照，再更新主表。
        // 顺序不能颠倒，否则快照存的就是新配置了。
        saveVersionSnapshot(oldEntity, updateReqVO.getChangeSummary());

        DataInterfaceDO entity = BeanUtils.toBean(updateReqVO, DataInterfaceDO.class);
        entity.setParams(JsonUtils.toJsonString(updateReqVO.getParams()));
        entity.setSqlStatementType(analyzeInfo.statementType);
        entity.setHasWriteOperation(analyzeInfo.writeOperation);
        entity.setHasPage(analyzeInfo.hasPage);
        entity.setVersion(oldEntity.getVersion() + 1);
        dataInterfaceMapper.updateById(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDataInterface(Long id) {
        validateExists(id);
        dataInterfaceMapper.deleteById(id);
        // 版本历史一并删除，避免残留孤儿数据
        versionMapper.deleteByInterfaceId(id);
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        validateExists(id);
        DataInterfaceDO entity = new DataInterfaceDO();
        entity.setId(id);
        entity.setStatus(status);
        dataInterfaceMapper.updateById(entity);
    }

    @Override
    public DataInterfaceDO getDataInterface(Long id) {
        return dataInterfaceMapper.selectById(id);
    }

    @Override
    public PageResult<DataInterfaceDO> getDataInterfacePage(DataInterfacePageReqVO pageReqVO) {
        return dataInterfaceMapper.selectPage(pageReqVO);
    }

    @Override
    public DataInterfaceSqlDetectRespVO detectSql(String sql) {
        DataInterfaceSqlDetectRespVO resp = new DataInterfaceSqlDetectRespVO();
        if (StrUtil.isBlank(sql)) {
            resp.setSuccess(false);
            resp.setErrorMsg("SQL 不能为空");
            return resp;
        }
        SqlStatementAnalyzer.AnalyzeResult analyze = SqlStatementAnalyzer.analyze(toParsableSql(sql));
        if (!analyze.isSuccess()) {
            // 解析失败不抛异常：用户正在编辑，SQL 写一半是常态，前端只提示「暂时无法探测」
            resp.setSuccess(false);
            resp.setErrorMsg(analyze.getErrorMsg());
            return resp;
        }
        resp.setSuccess(true);
        resp.setStatementCount(analyze.getStatements().size());
        resp.setStatementTypes(analyze.getStatements().stream()
                .map(SqlStatementAnalyzer.StatementInfo::getStatementType).toList());
        resp.setWriteStatementTypes(analyze.getStatements().stream()
                .filter(SqlStatementAnalyzer.StatementInfo::isWriteOperation)
                .map(SqlStatementAnalyzer.StatementInfo::getStatementType)
                .distinct().toList());
        resp.setHasWriteOperation(analyze.hasWriteOperation());
        resp.setSuggestedStatementType(analyze.getOverallType());
        return resp;
    }

    /**
     * 把 #{xx} 占位符换成 ?，否则 SQL 解析器不认
     */
    private String toParsableSql(String sql) {
        return sql.replaceAll("#\\{\\s*[A-Za-z_][A-Za-z0-9_]*\\s*}", "?");
    }

    // ==================== 执行 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> preview(Long id, Map<String, Object> requestArgs) {
        DataInterfaceDO entity = validateExists(id);
        // 🔴 必须先过写权限，再执行。理由见 validatePreviewWritePermission
        validatePreviewWritePermission(entity);
        InterfaceExecuteContext context = buildContext(entity, requestArgs, true);
        Object data = doExecute(entity, context, INVOKE_TYPE_PREVIEW);
        // 预览额外回传实际执行的 SQL，方便用户核对租户条件是否按预期注入
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", data);
        result.put("executeSql", context.getExecuteSql());
        return result;
    }

    /**
     * 「在线测试」一个含写操作的接口，需要配置写操作 SQL 的权限
     *
     * <h3>🔴 这里原本是个越权口子，而且改名之后更容易被踩到</h3>
     *
     * 端点上挂的是 {@code @PreAuthorize("@ss.hasPermission('infra:data-interface:query')")}
     * —— 一个<b>只读</b>权限串，任何能打开接口管理列表的人都拿到它。而本方法上的
     * {@code @Transactional(rollbackFor = Exception.class)} 只在抛异常时回滚，
     * 执行成功就照常提交。两者相加的后果是：只有 query 权限的人点一下「测试」，
     * 就能让一个 INSERT/UPDATE/DELETE 接口真的写库并落盘，
     * HTTP 类型的接口同理（POST/PUT 一样发得出去，而且根本无从回滚）。
     * 对照正式调用路径 {@link #invoke}：它要 {@code infra:data-interface:invoke}，
     * 比 query 高一档。<b>测试不该比正式调用更容易触发写。</b>
     *
     * <p>前端把按钮从「预览」改成「测试」之后，这个口子更危险了 ——
     * 「预览」听起来不动数据，「测试」听起来会。名字改对了，权限也得跟上。
     *
     * <h3>为什么是加权限，而不是让预览自动回滚</h3>
     *
     * 「测试即回滚」看着更贴心，实际靠不住：
     * <ul>
     *   <li>接口可以指向别的数据源（{@code dataSourceConfigId}），那个连接不在本方法的事务里，
     *       回滚根本管不到；</li>
     *   <li>HTTP 转发类的写操作发出去就收不回来；</li>
     *   <li>就算技术上能全回滚，界面回一句「影响行数 1」而库里什么都没发生，
     *       是在骗用户 —— 他会以为这个接口已经验证通过了。</li>
     * </ul>
     * 所以选择拦住，并把话说清楚。
     *
     * <p>判据用 {@link #isReadOnly}（覆盖 SQL / 静态 JSON / HTTP 三种类型），
     * 而不是 {@code entity.getHasWriteOperation()}：后者只在 SQL 解析时写入，
     * HTTP 类型的 POST 并不体现在这个字段上，用它判会漏掉一整类。
     */
    private void validatePreviewWritePermission(DataInterfaceDO entity) {
        if (isReadOnly(entity)) {
            return;
        }
        if (securityFrameworkService.hasPermission(PERMISSION_WRITE_SQL)) {
            return;
        }
        // 没登录态时 hasPermission 一律返回 false，所以这里同样是 fail-close 的
        throw exception(DATA_INTERFACE_PREVIEW_WRITE_NEED_PERMISSION, entity.getCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Object invoke(String code, Map<String, Object> requestArgs) {
        DataInterfaceDO entity = validateInvokable(code);
        return executeInvokable(entity, requestArgs);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Object invokeReadOnly(String code, Map<String, Object> requestArgs) {
        DataInterfaceDO entity = validateInvokable(code);
        validateReadOnly(entity);
        return executeInvokable(entity, requestArgs);
    }

    @Override
    public List<DataInterfaceDO> getReadOnlySimpleList() {
        return dataInterfaceMapper.selectEnabledList().stream()
                .filter(this::isReadOnly)
                .toList();
    }

    @Override
    public List<DataInterfaceDO> getEnabledSimpleList() {
        return dataInterfaceMapper.selectEnabledList();
    }

    private DataInterfaceDO validateInvokable(String code) {
        DataInterfaceDO entity = dataInterfaceMapper.selectByCode(code);
        if (entity == null) {
            throw exception(DATA_INTERFACE_NOT_EXISTS);
        }
        if (entity.getStatus() != null && entity.getStatus() != 0) {
            throw exception(DATA_INTERFACE_DISABLED, code);
        }
        return entity;
    }

    private Object executeInvokable(DataInterfaceDO entity, Map<String, Object> requestArgs) {
        InterfaceExecuteContext context = buildContext(entity, requestArgs, false);
        return doExecute(entity, context, INVOKE_TYPE_INVOKE);
    }

    private void validateReadOnly(DataInterfaceDO entity) {
        if (!isReadOnly(entity)) {
            throw exception(DATA_INTERFACE_READ_ONLY_REQUIRED, entity.getCode());
        }
    }

    /**
     * 判断接口是否为「读取型」
     *
     * 运行时消费方只需登录态即可调用，所以必须在这里卡死：能被它们调到的接口不能有副作用。
     */
    private boolean isReadOnly(DataInterfaceDO entity) {
        if (Objects.equals(entity.getType(), DataInterfaceTypeEnum.SQL.getType())) {
            return SqlStatementTypeEnum.SELECT.getType().equalsIgnoreCase(entity.getSqlStatementType());
        }
        if (Objects.equals(entity.getType(), DataInterfaceTypeEnum.STATIC.getType())) {
            return true;
        }
        if (Objects.equals(entity.getType(), DataInterfaceTypeEnum.HTTP.getType())) {
            Map<String, Object> config = JsonUtils.parseObject(entity.getConfig(), Map.class);
            String method = config == null || config.get("method") == null
                    ? "GET" : String.valueOf(config.get("method"));
            return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
        }
        return false;
    }

    /**
     * 执行并记录日志
     *
     * 成功失败都写日志 —— 失败的调用往往才是需要排查的。
     * 日志写入放在 finally 里，保证异常路径也不漏记。
     */
    private Object doExecute(DataInterfaceDO entity, InterfaceExecuteContext context,
                             int invokeType) {
        long start = System.currentTimeMillis();
        boolean success = false;
        String errorMsg = null;
        Object data = null;
        try {
            // SQL 类型 + 用户选的动作是「查询」+ 已开启分页，才注入分页参数。
            // 动作类型是用户选的，所以这里的判断与用户在界面上看到的行为一致
            if (Objects.equals(entity.getType(), DataInterfaceTypeEnum.SQL.getType())
                    && SqlStatementTypeEnum.SELECT.getType().equalsIgnoreCase(entity.getSqlStatementType())
                    && Objects.equals(entity.getHasPage(), 1)
            ) {
                paramDefinitionDefaultHandler(context);
                requestArgsDefaultHandler(context);
            }

            data = getExecutor(entity.getType()).execute(context);

            // 后处理脚本在包装前执行，让脚本直接处理执行器的原始输出
            // （查询返回数组/分页对象，写操作返回 {affectedRows}）
            // 注意：分页查询返回 {total, list}，脚本要处理需访问 data.list
            data = scriptPostProcessor.process(entity.getScript(), data);

            // SQL 和 STATIC(JSON) 类型统一封装成标准响应格式 {code, msg, data}
            if (entity.getType() == DataInterfaceTypeEnum.SQL.getType()
                    || entity.getType() == DataInterfaceTypeEnum.STATIC.getType()) {
                data = wrapStandardResponse(data);
            }
            success = true;
            return data;
        } catch (Exception ex) {
            errorMsg = ex.getMessage();
            throw ex;
        } finally {
            // 关闭日志开关的接口（高频/高性能场景）不写调用日志
            if (!Objects.equals(entity.getLogEnabled(), 0)) {
                saveLog(entity, context, invokeType, success, errorMsg, data,
                        (int) (System.currentTimeMillis() - start));
            }
        }
    }

    /**
     * 参数定义默认处理器
     * @param context
     */
    private void paramDefinitionDefaultHandler(InterfaceExecuteContext context) {
        List<ParamDefinition> paramDefinitions = context.getParamDefinitions();
        if (paramDefinitions == null) {
            paramDefinitions = new ArrayList<>();
            ParamDefinition pageNo = new ParamDefinition();
            pageNo.setName("pageNo");
            pageNo.setLabel("页码");
            pageNo.setType("INT");
            pageNo.setDefaultValue("1");
            ParamDefinition pageSize = new ParamDefinition();
            pageSize.setName("pageSize");
            pageSize.setLabel("每页条数");
            pageSize.setType("INT");
            pageSize.setDefaultValue("20");
            paramDefinitions.add(pageNo);
            paramDefinitions.add(pageSize);
        } else {
            paramDefinitions = new ArrayList<>(paramDefinitions);
            boolean pageNoFlag = paramDefinitions.stream().filter(Objects::nonNull).anyMatch(e -> "pageNo".equals(e.getName()));
            if (!pageNoFlag) {
                ParamDefinition pageNo = new ParamDefinition();
                pageNo.setName("pageNo");
                pageNo.setLabel("页码");
                pageNo.setType("INT");
                pageNo.setDefaultValue("1");
                paramDefinitions.add(pageNo);
            }
            boolean pageSizeFlag = paramDefinitions.stream().filter(Objects::nonNull).anyMatch(e -> "pageSize".equals(e.getName()));
            if (!pageSizeFlag) {
                ParamDefinition pageSize = new ParamDefinition();
                pageSize.setName("pageSize");
                pageSize.setLabel("每页条数");
                pageSize.setType("INT");
                pageSize.setDefaultValue("20");
                paramDefinitions.add(pageSize);
            }
        }

        int pageNo = 1;
        Optional<ParamDefinition> pageNoOptional = paramDefinitions.stream().filter(Objects::nonNull).filter(e -> "pageNo".equals(e.getName())).findFirst();
        if (pageNoOptional.isPresent()) {
            String pageNoStr = pageNoOptional.get().getDefaultValue();
            if (StrUtil.isNotBlank(pageNoStr)) {
                pageNo = Integer.parseInt(pageNoStr);
            }
        }
        int pageSize = 20;
        Optional<ParamDefinition> pageSizeOptional = paramDefinitions.stream().filter(Objects::nonNull).filter(e -> "pageSize".equals(e.getName())).findFirst();
        if (pageSizeOptional.isPresent()) {
            String pageSizeStr = pageSizeOptional.get().getDefaultValue();
            if (StrUtil.isNotBlank(pageSizeStr)) {
                pageSize = Integer.parseInt(pageSizeStr);
            }
        }

        ParamDefinition offsetSize = new ParamDefinition();
        offsetSize.setName("offsetSize");
        offsetSize.setLabel("偏移量");
        offsetSize.setType("INT");
        offsetSize.setDefaultValue(((pageNo - 1) * pageSize) + "");
        paramDefinitions.add(offsetSize);
        context.setParamDefinitions(paramDefinitions);
    }

    /**
     * 请求参数默认处理器
     * @param context
     */
    private void requestArgsDefaultHandler(InterfaceExecuteContext context) {
        Map<String, Object> requestArgs = context.getRequestArgs();
        if (requestArgs == null) {
            requestArgs = new HashMap<>();
            requestArgs.put("pageNo", 1);
            requestArgs.put("pageSize", 20);
        } else {
            requestArgs = new HashMap<>(requestArgs);
            boolean pageNoFlag = requestArgs.containsKey("pageNo");
            if (!pageNoFlag) {
                requestArgs.put("pageNo", 1);
            }
            boolean pageSizeFlag = requestArgs.containsKey("pageSize");
            if (!pageSizeFlag) {
                requestArgs.put("pageSize", 20);
            }
        }

        int pageNo = 1;
        Object pageNoObj = requestArgs.get("pageNo");
        if (pageNoObj != null && StrUtil.isNotBlank(pageNoObj.toString())) {
            pageNo = Integer.parseInt(pageNoObj.toString());
        }

        int pageSize = 1;
        Object pageSizeObj = requestArgs.get("pageSize");
        if (pageSizeObj != null && StrUtil.isNotBlank(pageSizeObj.toString())) {
            pageSize = Integer.parseInt(pageSizeObj.toString());
        }

        requestArgs.put("offsetSize", ((pageNo - 1) * pageSize));
        context.setRequestArgs(requestArgs);
    }

    /**
     * 封装成标准响应格式
     */
    private Map<String, Object> wrapStandardResponse(Object data) {
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("code", 0);
        response.put("msg", "");
        response.put("data", data);
        return response;
    }

    private InterfaceExecuteContext buildContext(DataInterfaceDO entity,
                                                 Map<String, Object> requestArgs,
                                                 boolean preview) {
        // 获取当前请求的关键头，用于 HTTP 转发本系统接口时透传
        String authorization = null;
        String cookie = null;
        String tenantId = null;
        try {
            var request = ServletUtils.getRequest();
            authorization = request.getHeader("Authorization");
            cookie = request.getHeader("Cookie");
            tenantId = request.getHeader("tenant-id");
        } catch (Exception e) {
            // 非 HTTP 请求上下文（如单元测试）忽略
        }

        return new InterfaceExecuteContext()
                .setDataInterface(entity)
                .setParamDefinitions(parseParams(entity.getParams()))
                .setRequestArgs(requestArgs == null ? Collections.emptyMap() : requestArgs)
                .setPreview(preview)
                .setPreviewLimit(preview ? PREVIEW_LIMIT : null)
                .setAuthorization(authorization)
                .setCookie(cookie)
                .setTenantId(tenantId);
    }

    /**
     * 写调用日志
     *
     * 用独立事务（REQUIRES_NEW）提交：preview/invoke 外层事务会因为执行失败而回滚，
     * 失败日志如果跟着外层事务走就会被一起回滚掉，恰恰丢了最需要的那条记录。
     * 日志本身失败不能影响业务结果 —— catch 住并只记 error 日志。
     */
    private void saveLog(DataInterfaceDO entity, InterfaceExecuteContext context,
                         int invokeType, boolean success, String errorMsg,
                         Object data, int duration) {
        try {
            DataInterfaceLogDO log = new DataInterfaceLogDO();
            log.setInterfaceId(entity.getId());
            log.setInterfaceCode(entity.getCode());
            log.setInvokeType(invokeType);
            log.setRequestParams(truncate(JsonUtils.toJsonString(context.getRequestArgs()), LOG_TEXT_MAX_LENGTH));
            log.setExecuteSql(truncate(context.getExecuteSql(), LOG_TEXT_MAX_LENGTH));
            log.setSuccess(success);
            // errorMsg 存列表用的 500 字符摘要，errorDetail 存排查用的完整原因
            log.setErrorMsg(truncate(errorMsg, LOG_ERROR_MSG_MAX_LENGTH));
            log.setErrorDetail(truncate(errorMsg, LOG_TEXT_MAX_LENGTH));
            log.setResultCount(resolveResultCount(data));
            log.setDuration(duration);
            log.setUserId(SecurityFrameworkUtils.getLoginUserId());
            log.setUserIp(ServletUtils.getClientIP());
            TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
            requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            requiresNew.executeWithoutResult(status -> logMapper.insert(log));
        } catch (Exception ex) {
            log.error("[saveLog][记录调用日志失败，接口({})]", entity.getCode(), ex);
        }
    }

    /**
     * 结果条数：查询返回列表则取大小，写操作返回影响行数
     */
    private Integer resolveResultCount(Object data) {
        if (data instanceof Collection<?> collection) {
            return collection.size();
        }
        if (data instanceof Number number) {
            return number.intValue();
        }
        return data == null ? 0 : 1;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() > maxLength
                ? text.substring(0, maxLength) + "...(已截断)" : text;
    }

    // ==================== 版本管理 ====================

    @Override
    public PageResult<DataInterfaceVersionDO> getVersionPage(Long interfaceId, PageParam pageParam) {
        validateExists(interfaceId);
        return versionMapper.selectPageByInterfaceId(interfaceId, pageParam);
    }

    @Override
    public DataInterfaceVersionDO getVersion(Long interfaceId, Integer version) {
        DataInterfaceVersionDO entity = versionMapper
                .selectByInterfaceIdAndVersion(interfaceId, version);
        if (entity == null) {
            throw exception(DATA_INTERFACE_VERSION_NOT_EXISTS);
        }
        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rollbackVersion(Long interfaceId, Integer version) {
        DataInterfaceDO current = validateExists(interfaceId);
        DataInterfaceVersionDO target = getVersion(interfaceId, version);
        DataInterfaceDO snapshot = JsonUtils.parseObject(target.getSnapshot(), DataInterfaceDO.class);
        if (snapshot == null) {
            throw exception(DATA_INTERFACE_VERSION_NOT_EXISTS);
        }
        // 回滚不走 analyzeSqlIfNeeded，所以那道写操作权限校验够不着这里，必须单独补一次。
        // 🔴 位置在 saveVersionSnapshot 之前：快照本身就是一次写库，
        //    放到后面会让「被拒绝的回滚」也留下一条版本记录，事后看历史会以为回滚发生过
        // ⚠️ 判据用 isReadOnly，不用 snapshot.getHasWriteOperation()：后者只在 SQL 解析时写入，
        //    HTTP 转发类的 POST/PUT 在它上面是 false，用它判会放过一整类写操作。
        //    与 validatePreviewWritePermission 同一条口径，两处不会各说各话。
        //    老快照连 type 都没有时 isReadOnly 返回 false ⟹ 按写操作处理，fail-close
        validateWriteSqlPermission(!isReadOnly(snapshot),
                SqlStatementTypeEnum.isSelectable(snapshot.getSqlStatementType())
                        ? SqlStatementTypeEnum.typeOf(snapshot.getSqlStatementType()).getName()
                        : null);
        // 回滚本身也留痕：先把当前配置存成一个版本
        saveVersionSnapshot(current, "回滚到版本 " + version + " 之前的配置");

        // 只回滚配置内容，不回滚编号与版本号
        DataInterfaceDO updateObj = new DataInterfaceDO();
        updateObj.setId(interfaceId);
        updateObj.setName(snapshot.getName());
        updateObj.setOwner(snapshot.getOwner());
        updateObj.setCategory(snapshot.getCategory());
        // 标签跟着一起回滚。老快照的 JSON 里没有 tags 字段 ⟹ 解析出 null ⟹
        // MyBatis-Plus 的默认策略会跳过 null 列，所以那次回滚不会把现有标签抹掉
        updateObj.setTags(snapshot.getTags());
        updateObj.setType(snapshot.getType());
        updateObj.setDataSourceConfigId(snapshot.getDataSourceConfigId());
        updateObj.setConfig(snapshot.getConfig());
        updateObj.setParams(snapshot.getParams());
        updateObj.setScript(snapshot.getScript());
        updateObj.setSqlStatementType(snapshot.getSqlStatementType());
        updateObj.setHasWriteOperation(snapshot.getHasWriteOperation());
        // 分页配置必须跟动作类型一起回滚，否则会出现「动作=新增 但 hasPage=1」的矛盾状态
        updateObj.setHasPage(snapshot.getHasPage());
        updateObj.setCountSql(snapshot.getCountSql());
        updateObj.setBaseUrl(snapshot.getBaseUrl());
        updateObj.setAllowInternalUrl(snapshot.getAllowInternalUrl());
        updateObj.setLogEnabled(snapshot.getLogEnabled());
        updateObj.setRemark(snapshot.getRemark());
        updateObj.setVersion(current.getVersion() + 1);
        dataInterfaceMapper.updateById(updateObj);
    }

    /**
     * 存版本快照
     *
     * 存整份配置而非字段级 diff：配置项多且结构会演进，存快照最简单可靠，
     * 比对交给前端做。
     */
    private void saveVersionSnapshot(DataInterfaceDO entity, String changeSummary) {
        DataInterfaceVersionDO version = new DataInterfaceVersionDO();
        version.setInterfaceId(entity.getId());
        version.setVersion(entity.getVersion());
        version.setSnapshot(JsonUtils.toJsonString(entity));
        version.setChangeSummary(StrUtil.blankToDefault(changeSummary, ""));
        versionMapper.insert(version);
        cleanUpVersions(entity.getId());
    }

    /**
     * 版本数量控制：单个接口最多保留 {@link #VERSION_MAX_KEEP} 个版本，超出删最旧的
     */
    private void cleanUpVersions(Long interfaceId) {
        List<DataInterfaceVersionDO> versions = versionMapper.selectListByInterfaceId(interfaceId);
        if (versions.size() <= VERSION_MAX_KEEP) {
            return;
        }
        List<Long> removeIds = versions.subList(VERSION_MAX_KEEP, versions.size()).stream()
                .map(DataInterfaceVersionDO::getId).toList();
        versionMapper.deleteByIds(removeIds);
    }

    // ==================== 调用日志 ====================

    @Override
    public PageResult<DataInterfaceLogDO> getLogPage(DataInterfaceLogPageReqVO pageReqVO) {
        return logMapper.selectPage(pageReqVO);
    }

    // ==================== 校验与解析 ====================

    private DataInterfaceDO validateExists(Long id) {
        DataInterfaceDO entity = dataInterfaceMapper.selectById(id);
        if (entity == null) {
            throw exception(DATA_INTERFACE_NOT_EXISTS);
        }
        return entity;
    }

    private void validateCodeUnique(Long id, String code) {
        DataInterfaceDO existing = dataInterfaceMapper.selectByCode(code);
        if (existing != null && !Objects.equals(existing.getId(), id)) {
            throw exception(DATA_INTERFACE_CODE_DUPLICATE, code);
        }
    }

    /**
     * SQL 类型的保存前校验
     *
     * 动作类型（sqlStatementType）取用户在界面上选的值，不再由解析器推断 ——
     * 一个接口可以配多条 SQL，程序无法可靠判断这组语句整体算什么动作。
     * 解析器的结果降级为兜底校验：用户选「查询」但 SQL 里有写语句时拒绝保存。
     *
     * 做五件事：动作类型合法性、策略校验（禁 DDL、框架表写保护）、动作类型与
     * 实际语句的一致性、分页与动作类型的联动、写操作二次确认。
     * 另外校验后处理脚本 —— 三种类型都可能配脚本，所以放在这里统一做。
     */
    private SqlAnalyzeInfo analyzeSqlIfNeeded(DataInterfaceSaveReqVO reqVO) {
        // 脚本校验与类型无关，先做
        scriptPostProcessor.validateScript(reqVO.getScript());

        SqlAnalyzeInfo info = new SqlAnalyzeInfo();
        if (!Objects.equals(reqVO.getType(), DataInterfaceTypeEnum.SQL.getType())) {
            // 非 SQL 类型不涉及动作类型与分页联动，直接清零，避免切换类型后残留脏值
            info.hasPage = reqVO.getHasPage();
            validateHttpConfigIfNeeded(reqVO);
            return info;
        }
        if (StrUtil.isBlank(reqVO.getConfig())) {
            throw exception(DATA_INTERFACE_SQL_EMPTY);
        }

        // ① 动作类型必填且只能是用户可选的四种（DDL / OTHER 不开放）
        String statementType = validateStatementType(reqVO.getSqlStatementType());
        SqlStatementTypeEnum statementTypeEnum = SqlStatementTypeEnum.typeOf(statementType);

        // ② 解析 SQL：把 #{xx} 换成 ? 才能被解析器识别
        SqlStatementAnalyzer.AnalyzeResult analyze =
                SqlStatementAnalyzer.analyze(toParsableSql(reqVO.getConfig()));
        // 策略校验：禁 DDL、框架表写保护
        SqlPolicyValidator.validate(analyze);

        // ③ 动作类型与实际语句的一致性校验
        validateStatementTypeMatch(statementTypeEnum, analyze);

        // ④ SQL 里用到的参数必须都已定义，否则运行时才报错
        validateParamsDeclared(reqVO);

        info.statementType = statementType;
        // 是否含写操作由用户选的动作类型推导，保证「界面显示」与「落库标注」一致
        info.writeOperation = statementTypeEnum.getWriteOperation();

        // ⑤ 分页只对查询有意义，非查询动作强制关闭
        info.hasPage = resolveHasPage(statementTypeEnum, reqVO.getHasPage());

        // ⑥ 含写操作需显式确认风险
        if (info.writeOperation && !Boolean.TRUE.equals(reqVO.getRiskConfirmed())) {
            throw exception(DATA_INTERFACE_SQL_WRITE_NEED_CONFIRM, statementTypeEnum.getName());
        }
        // ⑦ 含写操作还需要单独的权限。放在风险确认之后：确认是「你知道自己在干什么」，
        //    权限是「你被允许干」，先问前者能让没权限的人也看懂自己为什么被拦
        validateWriteSqlPermission(info.writeOperation, statementTypeEnum.getName());
        return info;
    }

    /**
     * HTTP 类型的配置在保存时就解析一遍
     *
     * <p>不校验的后果是「存得进去、跑不起来」。config 是一整段自由 JSON 文本，
     * 形状不对也要等到有人点「测试」才暴露，而那时它已经落库、还进了版本快照。
     * 本环境就有一行这样的遗留数据：headers 存成了对象 {@code {}} 而不是数组，
     * 每次测试都抛一句与地址无关的「HTTP 地址不合法」，报文里还带着 Jackson 的全限定类名。
     *
     * <p>与执行期共用 {@link HttpExecutor#parseConfig}，不各写一份判据 ——
     * 分开写早晚会出现「保存时说合法、执行时说不合法」。
     *
     * <p>STATIC 类型不在这里校验：它的 config 由 {@code StaticExecutor} 自己解析，
     * 且已有独立错误码 {@code DATA_INTERFACE_STATIC_JSON_INVALID}。
     */
    private void validateHttpConfigIfNeeded(DataInterfaceSaveReqVO reqVO) {
        if (!Objects.equals(reqVO.getType(), DataInterfaceTypeEnum.HTTP.getType())) {
            return;
        }
        HttpExecutor.parseConfig(reqVO.getConfig());
    }

    /**
     * 配置写操作 SQL 的权限校验
     *
     * <h3>🔴 这条校验曾经不存在，而界面上一直摆着那个开关</h3>
     *
     * 菜单里有「配置写操作SQL」按钮（权限串 {@code infra:data-interface:write-sql}），
     * 资源树里有对应的一行，管理员可以在授权页勾选或取消它 —— 但代码里没有任何地方读过这个串，
     * 连为它准备的错误码 {@code DATA_INTERFACE_SQL_WRITE_NEED_PERMISSION} 都是零引用。
     * 后果是<b>勾了也没用、取消了也没用</b>：任何拿到 create/update 权限的人都能存下
     * INSERT/UPDATE/DELETE 接口，而「能把写操作开放给运行时调用」恰恰是这张表最危险的能力。
     *
     * <p>⚠️ 三个入口都要过这道校验，漏一个就等于开了后门：
     * {@code createDataInterface}、{@code updateDataInterface}（都经由
     * {@link #analyzeSqlIfNeeded}）、以及 {@link #rollbackVersion}（它不走解析，
     * 直接把历史快照里的写操作配置搬回主表）
     *
     * <p>⚠️ 两个入口的判据<b>刻意不同</b>，别顺手统一掉：
     * <ul>
     *   <li>保存路径传的是 {@code SqlAnalyzeInfo#writeOperation}，由用户选的动作类型推导，
     *       只对 SQL 类型有值 —— {@link #analyzeSqlIfNeeded} 对静态 JSON / HTTP 直接早返回。
     *       也就是说<b>存一个 HTTP POST 接口不需要这条权限</b>：那条权限串叫「配置写操作SQL」，
     *       管的是写库；HTTP 转发的风险由 SSRF 白名单与 {@code allowInternalUrl} 那一套管，
     *       两套控制各管一段，不重复设卡</li>
     *   <li>回滚路径传的是 {@code !isReadOnly(snapshot)}，覆盖三种类型。它必须更严 ——
     *       回滚是把一份<b>已经配好的</b>危险配置搬回主表，绕开了保存时的全部解析与确认</li>
     * </ul>
     *
     * @param writeOperation  这份配置是否含写操作
     * @param statementType   动作类型的显示名，仅用于报错文案；回滚路径传 null
     */
    private void validateWriteSqlPermission(Boolean writeOperation, String statementType) {
        if (!Boolean.TRUE.equals(writeOperation)) {
            return;
        }
        if (securityFrameworkService.hasPermission(PERMISSION_WRITE_SQL)) {
            return;
        }
        // 没登录态时 hasPermission 一律返回 false（见 SecurityFrameworkServiceImpl），
        // 所以这里天然是 fail-close 的：宁可拦住，不可放行
        throw exception(DATA_INTERFACE_SQL_WRITE_NEED_PERMISSION,
                StrUtil.blankToDefault(statementType, "写操作"));
    }

    /**
     * 校验动作类型
     *
     * 只接受 SELECT / INSERT / UPDATE / DELETE。DDL 已被策略禁止，
     * OTHER 是解析器的兜底值，都不允许用户手选。
     *
     * @return 规范化后的类型（统一大写）
     */
    private String validateStatementType(String statementType) {
        if (StrUtil.isBlank(statementType)) {
            throw exception(DATA_INTERFACE_STATEMENT_TYPE_REQUIRED);
        }
        if (!SqlStatementTypeEnum.isSelectable(statementType)) {
            throw exception(DATA_INTERFACE_STATEMENT_TYPE_INVALID, statementType);
        }
        return SqlStatementTypeEnum.typeOf(statementType).getType();
    }

    /**
     * 校验「用户选的动作类型」与「SQL 里实际的语句」是否矛盾
     *
     * 只拦一个方向：用户选「查询」但 SQL 里含写语句 —— 这会绕过风险确认与列表页红标，
     * 是真正危险的情况，必须拒绝。
     *
     * 反方向（选了写操作但 SQL 全是 SELECT）不拦：可能是用户先选类型再改 SQL，
     * 也可能是存储过程调用之类解析器认不准的写法，交给前端提示即可，不阻断保存。
     */
    private void validateStatementTypeMatch(SqlStatementTypeEnum statementTypeEnum,
                                            SqlStatementAnalyzer.AnalyzeResult analyze) {
        if (statementTypeEnum.getWriteOperation() || !analyze.hasWriteOperation()) {
            return;
        }
        // 把实际检出的写语句类型列出来，方便用户直接改对
        String detected = analyze.getStatements().stream()
                .filter(SqlStatementAnalyzer.StatementInfo::isWriteOperation)
                .map(SqlStatementAnalyzer.StatementInfo::getStatementType)
                .distinct().collect(Collectors.joining("、"));
        throw exception(DATA_INTERFACE_STATEMENT_TYPE_MISMATCH, detected);
    }

    /**
     * 分页与动作类型的联动
     *
     * 非查询动作只返回影响行数，分页无从谈起。前端会灰掉开关，
     * 这里再兜一层：直接置 0 而不是报错 —— 用户把「查询+分页」改成「新增」时
     * 意图很明确，报错只会打断操作。
     */
    private Integer resolveHasPage(SqlStatementTypeEnum statementTypeEnum, Integer hasPage) {
        if (SqlStatementTypeEnum.SELECT != statementTypeEnum) {
            return 0;
        }
        return hasPage == null ? 0 : hasPage;
    }

    /**
     * 校验 SQL 里用到的参数都已定义
     *
     * 注意：分页系统变量（pageSize、pageNo、offsetSize）在开启分页时自动注入，
     * 此处跳过检查，允许用户在 SQL 中使用。
     */
    private void validateParamsDeclared(DataInterfaceSaveReqVO reqVO) {
        List<String> used = SqlParamBinder.extractParamNames(reqVO.getConfig());
        if (CollUtil.isEmpty(used)) {
            return;
        }
        Set<String> declared = new HashSet<>();
        if (CollUtil.isNotEmpty(reqVO.getParams())) {
            reqVO.getParams().forEach(item -> declared.add(item.getName()));
        }
        for (String name : used) {
            if (!declared.contains(name)) {
                throw exception(DATA_INTERFACE_PARAM_NOT_DEFINED, name);
            }
        }
    }

    private List<ParamDefinition> parseParams(String paramsJson) {
        if (StrUtil.isBlank(paramsJson)) {
            return Collections.emptyList();
        }
        List<ParamDefinition> list = JsonUtils.parseArray(paramsJson, ParamDefinition.class);
        return list == null ? Collections.emptyList() : list;
    }

    /**
     * SQL 校验结果的轻量载体
     */
    private static class SqlAnalyzeInfo {
        /**
         * 动作类型，来自用户选择
         */
        private String statementType = "";
        private Boolean writeOperation = false;
        /**
         * 联动修正后的分页开关
         */
        private Integer hasPage = 0;
    }

}
