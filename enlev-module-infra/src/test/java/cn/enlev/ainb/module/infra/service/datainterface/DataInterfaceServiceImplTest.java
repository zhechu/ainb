package cn.enlev.ainb.module.infra.service.datainterface;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.module.infra.dal.dataobject.datainterface.DataInterfaceDO;
import cn.enlev.ainb.module.infra.dal.dataobject.datainterface.DataInterfaceLogDO;
import cn.enlev.ainb.module.infra.dal.dataobject.datainterface.DataInterfaceVersionDO;
import cn.enlev.ainb.module.infra.dal.mysql.datainterface.DataInterfaceLogMapper;
import cn.enlev.ainb.module.infra.dal.mysql.datainterface.DataInterfaceMapper;
import cn.enlev.ainb.module.infra.dal.mysql.datainterface.DataInterfaceVersionMapper;
import cn.enlev.ainb.module.infra.enums.datainterface.DataInterfaceTypeEnum;
import cn.enlev.ainb.module.infra.framework.datainterface.core.executor.InterfaceExecuteContext;
import cn.enlev.ainb.module.infra.framework.datainterface.core.executor.InterfaceExecutor;
import cn.enlev.ainb.module.infra.framework.datainterface.core.script.ScriptPostProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.DATA_INTERFACE_DISABLED;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.DATA_INTERFACE_READ_ONLY_REQUIRED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataInterfaceServiceImplTest {

    @Mock
    private DataInterfaceMapper dataInterfaceMapper;
    @Mock
    private DataInterfaceVersionMapper versionMapper;
    @Mock
    private DataInterfaceLogMapper logMapper;
    @Mock
    private ScriptPostProcessor scriptPostProcessor;
    @Mock
    private InterfaceExecutor sqlExecutor;
    @Mock
    private InterfaceExecutor staticExecutor;
    @Mock
    private InterfaceExecutor httpExecutor;
    /** saveLog 用独立事务提交，测试里给个 mock 事务管理器即可走通 TransactionTemplate */
    @Mock
    private PlatformTransactionManager transactionManager;
    @InjectMocks
    private DataInterfaceServiceImpl dataInterfaceService;

    @BeforeEach
    void setUp() {
        lenient().when(sqlExecutor.getType()).thenReturn(DataInterfaceTypeEnum.SQL);
        lenient().when(staticExecutor.getType()).thenReturn(DataInterfaceTypeEnum.STATIC);
        lenient().when(httpExecutor.getType()).thenReturn(DataInterfaceTypeEnum.HTTP);
        lenient().when(scriptPostProcessor.process(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        ReflectionTestUtils.setField(dataInterfaceService, "executors",
                List.of(sqlExecutor, staticExecutor, httpExecutor));
    }

    @Test
    void invokeReadOnly_sqlSelect_isAllowed() {
        DataInterfaceDO entity = dataInterface("sql_read", DataInterfaceTypeEnum.SQL.getType());
        entity.setConfig("SELECT id FROM test_table");
        entity.setSqlStatementType("SELECT");
        when(dataInterfaceMapper.selectByCode(entity.getCode())).thenReturn(entity);
        when(sqlExecutor.execute(any(InterfaceExecuteContext.class)))
                .thenReturn(List.of(Map.of("id", 1)));

        Object result = dataInterfaceService.invokeReadOnly(entity.getCode(), Map.of());

        assertEquals(0, ((Map<?, ?>) result).get("code"));
        verify(sqlExecutor).execute(any(InterfaceExecuteContext.class));
    }

    @Test
    void invokeReadOnly_staticJson_isAllowed() {
        DataInterfaceDO entity = dataInterface("static_read", DataInterfaceTypeEnum.STATIC.getType());
        entity.setConfig("{\"items\":[1,2]}");
        when(dataInterfaceMapper.selectByCode(entity.getCode())).thenReturn(entity);
        when(staticExecutor.execute(any(InterfaceExecuteContext.class)))
                .thenReturn(Map.of("items", List.of(1, 2)));

        Object result = dataInterfaceService.invokeReadOnly(entity.getCode(), Map.of());

        assertEquals(0, ((Map<?, ?>) result).get("code"));
        verify(staticExecutor).execute(any(InterfaceExecuteContext.class));
    }

    @Test
    void invokeReadOnly_httpGet_isAllowed() {
        DataInterfaceDO entity = dataInterface("http_read", DataInterfaceTypeEnum.HTTP.getType());
        entity.setConfig("{\"url\":\"https://example.com/api\",\"method\":\"GET\"}");
        when(dataInterfaceMapper.selectByCode(entity.getCode())).thenReturn(entity);
        when(httpExecutor.execute(any(InterfaceExecuteContext.class)))
                .thenReturn(Map.of("ok", true));

        Object result = dataInterfaceService.invokeReadOnly(entity.getCode(), Map.of());

        assertEquals(Map.of("ok", true), result);
        verify(httpExecutor).execute(any(InterfaceExecuteContext.class));
    }

    @Test
    void invokeReadOnly_sqlWrite_isRejected() {
        DataInterfaceDO entity = dataInterface("sql_write", DataInterfaceTypeEnum.SQL.getType());
        entity.setConfig("UPDATE test_table SET name = 'changed'");
        entity.setSqlStatementType("UPDATE");
        when(dataInterfaceMapper.selectByCode(entity.getCode())).thenReturn(entity);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> dataInterfaceService.invokeReadOnly(entity.getCode(), Map.of()));

        assertEquals(DATA_INTERFACE_READ_ONLY_REQUIRED.getCode(), exception.getCode());
        verify(sqlExecutor, never()).execute(any(InterfaceExecuteContext.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "DELETE"})
    void invokeReadOnly_httpWrite_isRejected(String method) {
        DataInterfaceDO entity = dataInterface("http_" + method.toLowerCase(), DataInterfaceTypeEnum.HTTP.getType());
        entity.setConfig("{\"url\":\"https://example.com/api\",\"method\":\"" + method + "\"}");
        when(dataInterfaceMapper.selectByCode(entity.getCode())).thenReturn(entity);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> dataInterfaceService.invokeReadOnly(entity.getCode(), Map.of()));

        assertEquals(DATA_INTERFACE_READ_ONLY_REQUIRED.getCode(), exception.getCode());
        verify(httpExecutor, never()).execute(any(InterfaceExecuteContext.class));
    }

    @Test
    void getReadOnlySimpleList_filtersOutWritableInterfaces() {
        // 启用中的接口里混了写操作的，选接口列表必须挡掉 ——
        // 让用户选到一个运行时必然被拒的接口，是最难排查的那类配置错误
        DataInterfaceDO sqlRead = dataInterface("sql_read", DataInterfaceTypeEnum.SQL.getType());
        sqlRead.setSqlStatementType("SELECT");
        DataInterfaceDO sqlWrite = dataInterface("sql_write", DataInterfaceTypeEnum.SQL.getType());
        sqlWrite.setSqlStatementType("UPDATE");
        DataInterfaceDO staticJson = dataInterface("static_read", DataInterfaceTypeEnum.STATIC.getType());
        DataInterfaceDO httpGet = dataInterface("http_read", DataInterfaceTypeEnum.HTTP.getType());
        httpGet.setConfig("{\"url\":\"https://example.com/api\",\"method\":\"GET\"}");
        DataInterfaceDO httpPost = dataInterface("http_write", DataInterfaceTypeEnum.HTTP.getType());
        httpPost.setConfig("{\"url\":\"https://example.com/api\",\"method\":\"POST\"}");
        when(dataInterfaceMapper.selectEnabledList())
                .thenReturn(List.of(sqlRead, sqlWrite, staticJson, httpGet, httpPost));

        List<DataInterfaceDO> result = dataInterfaceService.getReadOnlySimpleList();

        assertEquals(List.of("sql_read", "static_read", "http_read"),
                result.stream().map(DataInterfaceDO::getCode).toList());
    }

    @Test
    void invokeReadOnly_disabledInterface_isRejected() {
        DataInterfaceDO entity = dataInterface("sql_read", DataInterfaceTypeEnum.SQL.getType());
        entity.setSqlStatementType("SELECT");
        entity.setStatus(1); // 停用
        when(dataInterfaceMapper.selectByCode(entity.getCode())).thenReturn(entity);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> dataInterfaceService.invokeReadOnly(entity.getCode(), Map.of()));

        assertEquals(DATA_INTERFACE_DISABLED.getCode(), exception.getCode());
        verify(sqlExecutor, never()).execute(any(InterfaceExecuteContext.class));
    }

    @Test
    void doExecute_logDisabled_doesNotWriteLog() {
        // 关闭日志开关的高频接口，执行后不落调用日志
        DataInterfaceDO entity = dataInterface("sql_read", DataInterfaceTypeEnum.SQL.getType());
        entity.setSqlStatementType("SELECT");
        entity.setLogEnabled(0);
        when(dataInterfaceMapper.selectByCode(entity.getCode())).thenReturn(entity);
        when(sqlExecutor.execute(any(InterfaceExecuteContext.class)))
                .thenReturn(List.of(Map.of("id", 1)));

        dataInterfaceService.invokeReadOnly(entity.getCode(), Map.of());

        verify(logMapper, never()).insert(any(DataInterfaceLogDO.class));
    }

    @Test
    void saveLog_errorMsgTruncatedTo500_detailTo4000() {
        // 失败原因摘要截断到 500，完整内容截断到 4000，避免超长写库失败
        DataInterfaceDO entity = dataInterface("sql_read", DataInterfaceTypeEnum.SQL.getType());
        entity.setSqlStatementType("SELECT");
        entity.setLogEnabled(1);
        when(dataInterfaceMapper.selectByCode(entity.getCode())).thenReturn(entity);
        String longMsg = "E".repeat(5000);
        when(sqlExecutor.execute(any(InterfaceExecuteContext.class)))
                .thenThrow(new RuntimeException(longMsg));

        assertThrows(RuntimeException.class,
                () -> dataInterfaceService.invokeReadOnly(entity.getCode(), Map.of()));

        ArgumentCaptor<DataInterfaceLogDO> captor = ArgumentCaptor.forClass(DataInterfaceLogDO.class);
        verify(logMapper).insert(captor.capture());
        DataInterfaceLogDO saved = captor.getValue();
        assertEquals(Boolean.FALSE, saved.getSuccess());
        assertEquals(500 + TRUNCATE_SUFFIX.length(), saved.getErrorMsg().length());
        assertEquals(4000 + TRUNCATE_SUFFIX.length(), saved.getErrorDetail().length());
    }

    @Test
    void saveVersionSnapshot_over100Versions_removesOldest() {
        // 存第 101 个版本快照时，触发清理，删掉最旧的那个
        DataInterfaceDO entity = dataInterface("sql_read", DataInterfaceTypeEnum.SQL.getType());
        entity.setVersion(101);
        // 模拟插入后库里有 101 个版本（version 101..1，倒序返回）
        List<DataInterfaceVersionDO> existing = new java.util.ArrayList<>();
        for (int v = 101; v >= 1; v--) {
            DataInterfaceVersionDO version = new DataInterfaceVersionDO();
            version.setId((long) v);
            version.setInterfaceId(entity.getId());
            version.setVersion(v);
            existing.add(version);
        }
        when(versionMapper.selectListByInterfaceId(entity.getId())).thenReturn(existing);

        ReflectionTestUtils.invokeMethod(dataInterfaceService, "saveVersionSnapshot", entity, "测试");

        verify(versionMapper).insert(any(DataInterfaceVersionDO.class));
        // 最旧的是 version=1（id=1），只应删除这一条
        verify(versionMapper).deleteByIds(List.of(1L));
    }

    private static final String TRUNCATE_SUFFIX = "...(已截断)";

    private static DataInterfaceDO dataInterface(String code, Integer type) {
        DataInterfaceDO entity = new DataInterfaceDO();
        entity.setId(1L);
        entity.setCode(code);
        entity.setType(type);
        entity.setStatus(0);
        entity.setScript("");
        return entity;
    }

}
