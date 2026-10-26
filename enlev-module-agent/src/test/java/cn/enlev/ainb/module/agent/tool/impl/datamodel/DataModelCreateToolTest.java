package cn.enlev.ainb.module.agent.tool.impl.datamodel;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.AgentValidateResult;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.DataModelCreateValidator;
import cn.enlev.ainb.module.infra.api.datamodel.DataModelApi;
import cn.enlev.ainb.module.infra.api.datamodel.dto.DataModelSaveReqDTO;
import cn.enlev.ainb.module.infra.api.datamodel.dto.DataModelStructureRespDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DataModelCreateTool} 的单元测试 —— E6 幂等
 *
 * <h3>要钉住的承诺</h3>
 * 「重放同一份参数不会抛异常，也不会建出第二张表」。这句话里最容易写错的是
 * <b>比对判据</b>：建表有一批后端静默补的默认值（不写长度建成 varchar(255)、
 * 不写精度建成 decimal(10,2)），拿请求里的 null 去比库里的 255，
 * 结论必然是「不一致」—— 而那恰恰是最常见的一次<b>合法重放</b>。
 * 所以这里的用例一半在验「该跳过的有没有跳过」，另一半在验
 * 「<b>不该</b>跳过的有没有跳过」：把一张同名的<b>别的</b>表说成
 * 「你要的表已经有了」，是本项目最贵的那类谎话 ——
 * 模型会接着往上绑表单、建流程，一路成功，直到运行时才炸。
 *
 * <p>⚠️ 这些是<b>单测</b>，验的是比对逻辑；§7③ 要求的
 * 「真的调两次、库里只多一张表」要在跑着的后端上走
 * {@code POST /agent/diagnostic/tool-execute?tool=datamodel_create} 复算。
 *
 * @author 走进新时代
 */
public class DataModelCreateToolTest extends BaseMockitoUnitTest {

    @InjectMocks
    private DataModelCreateTool tool;

    @Mock
    private DataModelApi dataModelApi;
    @Mock
    private DataModelParamResolver paramResolver;
    @Mock
    private DataModelCreateValidator validator;

    private static final Long EXISTING_ID = 77L;
    private static final String TABLE = "biz_order";

    // ==================== 该跳过的 ====================

    @Test
    @DisplayName("🔴 结构相符的重放：跳过、不执行 DDL，并把原来那个模型编号带回去")
    public void testIdenticalReplaySkipped() {
        stubRequest(request(column("order_no", "STRING", 64, null)));
        stubRegistered(structure(liveColumn("order_no", "STRING", 64, null)));

        Map<?, ?> resp = execute();

        assertEquals(true, resp.get("skipped"));
        assertEquals(EXISTING_ID, resp.get("id"), "要带上原来那个编号，调用方才能接着往下绑表单");
        assertTrue(resp.get("message").toString().contains("已存在"), resp.get("message").toString());
        assertTrue(resp.get("message").toString().contains("不要为了"),
                "要顺手拦住「改个表名重建」这个最常见的错误自救：" + resp.get("message"));
        verify(dataModelApi, never()).createDataModel(any());
    }

    @Test
    @DisplayName("🔴 请求里没写的项不参与比对 —— 否则最常见的一次合法重放会被判成「不一致」")
    public void testBackendFilledDefaultsAreNotCompared() {
        // 模型两次都没写长度/精度/可空性；库里那张是后端按默认值补出来的 255 / (10,2) / 可空
        stubRequest(request(column("order_no", "STRING", null, null),
                column("amount", "DECIMAL", null, null)));
        stubRegistered(structure(
                liveColumn("order_no", "STRING", 255, null, true, null),
                liveColumn("amount", "DECIMAL", 10, 2, true, null)));

        Map<?, ?> resp = execute();

        assertEquals(true, resp.get("skipped"),
                "拿 null 去比 255 会得到「不一致」，于是重试永远失败：" + resp);
        verify(dataModelApi, never()).createDataModel(any());
    }

    @Test
    @DisplayName("系统列不参与比对 —— 请求里本来就不该出现它们")
    public void testSystemColumnsIgnored() {
        stubRequest(request(column("order_no", "STRING", 64, null)));
        DataModelStructureRespDTO current = structure(liveColumn("order_no", "STRING", 64, null));
        for (String systemName : List.of("id", "creator", "create_time", "deleted", "tenant_id")) {
            DataModelStructureRespDTO.ColumnDTO system =
                    liveColumn(systemName, "BIGINT", null, null);
            system.setSystemColumn(true);
            current.getColumns().add(system);
        }
        stubRegistered(current);

        Map<?, ?> resp = execute();

        assertEquals(true, resp.get("skipped"),
                "把注入器补的系统列算进来，任何一次重放都会被判成「库里多了几列」：" + resp);
    }

    @Test
    @DisplayName("表名大小写不同也算同一张表 —— MySQL 的表名比对不区分大小写")
    public void testTableNameCaseInsensitive() {
        DataModelSaveReqDTO reqDTO = request(column("order_no", "STRING", 64, null));
        reqDTO.setTableName("BIZ_ORDER");
        when(paramResolver.resolveCreate(any(), any())).thenReturn(reqDTO);
        when(validator.validate(reqDTO)).thenReturn(new AgentValidateResult());
        stubRegistered(structure(liveColumn("order_no", "STRING", 64, null)));

        Map<?, ?> resp = execute();

        assertEquals(true, resp.get("skipped"), resp.toString());
    }

    @Test
    @DisplayName("默认值不同只降级成 warning，不影响跳过 —— 后端有一批「默认值被静默丢弃」的规则")
    public void testDefaultValueDifferenceOnlyWarns() {
        DataModelSaveReqDTO.ColumnDTO want = column("remark", "TEXT", null, null);
        want.setDefaultValue("无");
        stubRequest(request(want));
        // TEXT 的默认值会被 MySQL 静默丢掉，所以库里读回来是 null
        stubRegistered(structure(liveColumn("remark", "TEXT", null, null, true, null)));

        Map<?, ?> resp = execute();

        assertEquals(true, resp.get("skipped"), resp.toString());
        List<?> warnings = (List<?>) resp.get("warnings");
        assertNotNull(warnings, "默认值对不上必须说出来：" + resp);
        assertTrue(warnings.stream().map(String::valueOf).anyMatch(item -> item.contains("默认值")),
                "但不能不说 —— 配了不生效且无提示是本项目的头号缺陷：" + warnings);
    }

    @Test
    @DisplayName("索引建没建上也只降级成 warning，但要随返回值说清楚")
    public void testIndexDifferenceOnlyWarns() {
        DataModelSaveReqDTO reqDTO = request(column("order_no", "STRING", 64, null));
        DataModelSaveReqDTO.IndexDTO index = new DataModelSaveReqDTO.IndexDTO();
        index.setIndexName("uk_order_no");
        index.setIndexType("UNIQUE");
        index.setColumnNames(List.of("order_no"));
        reqDTO.setIndexes(new ArrayList<>(List.of(index)));
        when(paramResolver.resolveCreate(any(), any())).thenReturn(reqDTO);
        when(validator.validate(reqDTO)).thenReturn(new AgentValidateResult());
        // 库里那张表没有这个索引
        stubRegistered(structure(liveColumn("order_no", "STRING", 64, null)));

        Map<?, ?> resp = execute();

        assertEquals(true, resp.get("skipped"), resp.toString());
        List<?> warnings = (List<?>) resp.get("warnings");
        assertNotNull(warnings, "索引没建上必须说出来：" + resp);
        assertTrue(warnings.stream().map(String::valueOf).anyMatch(item -> item.contains("uk_order_no")),
                "唯一约束没建上的表现是一切正常，直到某天出现重复数据：" + warnings);
    }

    // ==================== 不该跳过的 ====================

    @Test
    @DisplayName("🔴 库里少一列 = 不是同一次建表的重放：报错，并指名该改用 datamodel_update")
    public void testMissingColumnRejected() {
        stubRequest(request(column("order_no", "STRING", 64, null),
                column("amount", "DECIMAL", 10, 2)));
        stubRegistered(structure(liveColumn("order_no", "STRING", 64, null)));

        ServiceException ex = assertThrows(ServiceException.class, this::execute);

        assertTrue(ex.getMessage().contains("已经存在，但结构与本次请求不一致"), ex.getMessage());
        assertTrue(ex.getMessage().contains("amount"), "要点名是哪一列：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("datamodel_update"), "要给出正确去处：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("没有跳过"), ex.getMessage());
        verify(dataModelApi, never()).createDataModel(any());
    }

    @Test
    @DisplayName("🔴 库里多一列（同名的别的表）也要拒 —— 说成「你要的表已经有了」是最坏的谎话")
    public void testExtraColumnInLiveRejected() {
        stubRequest(request(column("order_no", "STRING", 64, null)));
        stubRegistered(structure(liveColumn("order_no", "STRING", 64, null),
                liveColumn("customer_id", "BIGINT", null, null)));

        ServiceException ex = assertThrows(ServiceException.class, this::execute);

        assertTrue(ex.getMessage().contains("customer_id"), ex.getMessage());
        assertTrue(ex.getMessage().contains("本次请求里没有"), ex.getMessage());
        verify(dataModelApi, never()).createDataModel(any());
    }

    @Test
    @DisplayName("列名都一样、类型不一样：也要拒")
    public void testTypeMismatchRejected() {
        stubRequest(request(column("amount", "STRING", 64, null)));
        stubRegistered(structure(liveColumn("amount", "DECIMAL", 10, 2)));

        ServiceException ex = assertThrows(ServiceException.class, this::execute);

        assertTrue(ex.getMessage().contains("类型不一致"), ex.getMessage());
    }

    @Test
    @DisplayName("请求写了长度、库里对不上：要拒 —— 写了就是要它作数")
    public void testLengthMismatchRejected() {
        stubRequest(request(column("order_no", "STRING", 32, null)));
        stubRegistered(structure(liveColumn("order_no", "STRING", 64, null)));

        ServiceException ex = assertThrows(ServiceException.class, this::execute);

        assertTrue(ex.getMessage().contains("长度不一致"), ex.getMessage());
    }

    @Test
    @DisplayName("🔴 读不到结构时按「没登记」处理，绝不按「相符」处理")
    public void testUnreadableStructureFallsThroughToBackend() {
        stubRequest(request(column("order_no", "STRING", 64, null)));
        when(dataModelApi.getDataModelList()).thenReturn(List.of(simple(EXISTING_ID, TABLE)));
        when(dataModelApi.getStructure(EXISTING_ID)).thenReturn(null);
        when(dataModelApi.createDataModel(any())).thenReturn(EXISTING_ID);

        Map<?, ?> resp = execute();

        // 拿一个未知当已知，表现是告诉模型「你要的表已经有了」，而它要的表压根不存在
        assertFalse(resp.containsKey("skipped"), "读不到不等于相符：" + resp);
        verify(dataModelApi, times(1)).createDataModel(any());
    }

    @Test
    @DisplayName("🔴 登记行在、但结构里一列都读不到：同样按「没登记」处理")
    public void testEmptyStructureFallsThroughToBackend() {
        stubRequest(request(column("order_no", "STRING", 64, null)));
        when(dataModelApi.getDataModelList()).thenReturn(List.of(simple(EXISTING_ID, TABLE)));
        when(dataModelApi.getStructure(EXISTING_ID)).thenReturn(structure());
        when(dataModelApi.createDataModel(any())).thenReturn(EXISTING_ID);

        Map<?, ?> resp = execute();

        assertFalse(resp.containsKey("skipped"), resp.toString());
        verify(dataModelApi, times(1)).createDataModel(any());
    }

    // ==================== 首次建表 ====================

    @Test
    @DisplayName("库里没登记：照常建表，返回值里没有 skipped 标记")
    public void testFirstCreateGoesThrough() {
        stubRequest(request(column("order_no", "STRING", 64, null)));
        when(dataModelApi.getDataModelList()).thenReturn(List.of());
        when(dataModelApi.createDataModel(any())).thenReturn(EXISTING_ID);

        Map<?, ?> resp = execute();

        assertEquals(EXISTING_ID, resp.get("id"));
        assertEquals(TABLE, resp.get("tableName"));
        assertEquals(1, resp.get("columnCount"));
        assertFalse(resp.containsKey("skipped"), "真建了一张表，不该带上跳过标记：" + resp);
        assertTrue(resp.get("message").toString().contains("已创建"), resp.get("message").toString());
    }

    @Test
    @DisplayName("🔴 连着调两次：只建一张表，第二次是跳过 —— §7③「重放两次」的单测版")
    public void testReplayTwiceCreatesOnlyOneTable() {
        stubRequest(request(column("order_no", "STRING", 64, null)));
        // 第一次查不到、建完之后就查得到了 —— 用真实的状态翻转，而不是把返回值写死两遍
        AtomicBoolean created = new AtomicBoolean(false);
        lenient().when(dataModelApi.getDataModelList()).thenAnswer(invocation ->
                created.get() ? List.of(simple(EXISTING_ID, TABLE)) : List.of());
        lenient().when(dataModelApi.getStructure(EXISTING_ID))
                .thenReturn(structure(liveColumn("order_no", "STRING", 64, null)));
        lenient().when(dataModelApi.createDataModel(any())).thenAnswer(invocation -> {
            created.set(true);
            return EXISTING_ID;
        });

        Map<?, ?> first = execute();
        Map<?, ?> second = execute();

        assertFalse(first.containsKey("skipped"), first.toString());
        assertEquals(true, second.get("skipped"), second.toString());
        assertEquals(EXISTING_ID, second.get("id"));
        verify(dataModelApi, times(1)).createDataModel(any());
    }

    // ==================== 顺序 ====================

    @Test
    @DisplayName("🔴 幂等检查在校验之后：校验没过就不该去查库，更不该建表")
    public void testValidatorRunsBeforeIdempotencyCheck() {
        DataModelSaveReqDTO reqDTO = request(column("order_no", "STRING", 64, null));
        when(paramResolver.resolveCreate(any(), any())).thenReturn(reqDTO);
        AgentValidateResult failed = new AgentValidateResult();
        failed.addError("表名必须以 biz_ 开头");
        when(validator.validate(reqDTO)).thenReturn(failed);

        ServiceException ex = assertThrows(ServiceException.class, this::execute);

        assertTrue(ex.getMessage().contains("biz_"), ex.getMessage());
        // 校验是只读的、且它那份「一次说完」的错误清单对模型最有用，
        // 没有理由为了跳过而少说一遍
        verify(dataModelApi, never()).getDataModelList();
        verify(dataModelApi, never()).createDataModel(any());
    }

    // ==================== 造数据 ====================

    private Map<?, ?> execute() {
        return (Map<?, ?>) tool.execute(Map.of());
    }

    private void stubRequest(DataModelSaveReqDTO reqDTO) {
        when(paramResolver.resolveCreate(any(), any())).thenReturn(reqDTO);
        lenient().when(validator.validate(reqDTO)).thenReturn(new AgentValidateResult());
    }

    /** 登记行 + 能读回的结构 */
    private void stubRegistered(DataModelStructureRespDTO current) {
        lenient().when(dataModelApi.getDataModelList()).thenReturn(List.of(simple(EXISTING_ID, TABLE)));
        lenient().when(dataModelApi.getStructure(EXISTING_ID)).thenReturn(current);
    }

    private DataModelApi.DataModelSimpleRespDTO simple(Long id, String tableName) {
        DataModelApi.DataModelSimpleRespDTO dto = new DataModelApi.DataModelSimpleRespDTO();
        dto.setId(id);
        dto.setTableName(tableName);
        return dto;
    }

    private DataModelSaveReqDTO request(DataModelSaveReqDTO.ColumnDTO... columns) {
        DataModelSaveReqDTO reqDTO = new DataModelSaveReqDTO();
        reqDTO.setTableName(TABLE);
        reqDTO.setColumns(new ArrayList<>(List.of(columns)));
        return reqDTO;
    }

    private DataModelSaveReqDTO.ColumnDTO column(String name, String type, Integer length,
                                                 Integer scale) {
        DataModelSaveReqDTO.ColumnDTO dto = new DataModelSaveReqDTO.ColumnDTO();
        dto.setColumnName(name);
        dto.setColumnType(type);
        dto.setLength(length);
        dto.setScale(scale);
        return dto;
    }

    private DataModelStructureRespDTO structure(DataModelStructureRespDTO.ColumnDTO... columns) {
        DataModelStructureRespDTO dto = new DataModelStructureRespDTO();
        dto.setTableName(TABLE);
        dto.setColumns(new ArrayList<>(List.of(columns)));
        return dto;
    }

    private DataModelStructureRespDTO.ColumnDTO liveColumn(String name, String type,
                                                           Integer length, Integer scale) {
        return liveColumn(name, type, length, scale, true, null);
    }

    private DataModelStructureRespDTO.ColumnDTO liveColumn(String name, String type,
                                                           Integer length, Integer scale,
                                                           Boolean nullable, String defaultValue) {
        DataModelStructureRespDTO.ColumnDTO dto = new DataModelStructureRespDTO.ColumnDTO();
        dto.setColumnName(name);
        dto.setColumnType(type);
        dto.setLength(length);
        dto.setScale(scale);
        dto.setNullable(nullable);
        dto.setDefaultValue(defaultValue);
        dto.setSystemColumn(false);
        return dto;
    }

}
