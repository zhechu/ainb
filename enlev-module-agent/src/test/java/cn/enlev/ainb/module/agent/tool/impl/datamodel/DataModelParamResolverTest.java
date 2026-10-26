package cn.enlev.ainb.module.agent.tool.impl.datamodel;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.api.datamodel.DataModelApi;
import cn.enlev.ainb.module.infra.api.datamodel.dto.DataModelSaveReqDTO;
import cn.enlev.ainb.module.infra.api.datamodel.dto.DataModelStructureRespDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@link DataModelParamResolver} 的单元测试
 *
 * <p>这个类是 {@code datamodel_create} / {@code datamodel_update} /
 * {@code datamodel_preview_ddl} <b>共用</b>的入参组装。它一旦出错，
 * 三个工具会一起出错，而且「预览通过 = 执行能过」这个承诺同时失效。
 *
 * <p>所以用例分两类：一类钉住那些<b>刻意的强制取值</b>（id 置空、riskConfirmed 恒 null），
 * 一类钉住<b>踩过的坑</b>（onUpdateCurrentTimestamp 原样带回）。
 *
 * @author 走进新时代
 */
public class DataModelParamResolverTest extends BaseMockitoUnitTest {

    @InjectMocks
    private DataModelParamResolver resolver;

    @Mock
    private DataModelApi dataModelApi;

    // ==================== 建表 ====================

    @Test
    @DisplayName("建表：模型误传的 id 必须被强制置空")
    public void testResolveCreateForceNullId() {
        // 🔴 带 id 会走 update 路径，而 update 是全量同步语义 ——
        //    表现就是「让它建个表，它把另一张表的字段删了一半」
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("id", 123);
        model.put("tableName", "biz_demo");
        model.put("tableComment", "示例表");

        DataModelSaveReqDTO reqDTO = resolver.resolveCreate(Map.of("model", model), "any_tool");

        assertNull(reqDTO.getId());
        assertEquals("biz_demo", reqDTO.getTableName());
    }

    @Test
    @DisplayName("建表：dataSourceConfigId 恒 0、profile 默认 TENANT")
    public void testResolveCreateFixedValues() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("tableName", "biz_demo");
        // 模型自由填的数据源编号必须被覆盖：多数据源在数据模型上实际不支持
        model.put("dataSourceConfigId", 99);

        DataModelSaveReqDTO reqDTO = resolver.resolveCreate(Map.of("model", model), "any_tool");

        assertEquals(0L, reqDTO.getDataSourceConfigId());
        // 不是 TENANT 的话表里没有 tenant_id，数据对所有租户可见
        assertEquals("TENANT", reqDTO.getSystemColumnProfile());
    }

    @Test
    @DisplayName("建表：显式给了 profile 就不覆盖")
    public void testResolveCreateKeepGivenProfile() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("tableName", "biz_demo");
        model.put("systemColumnProfile", "GLOBAL");

        DataModelSaveReqDTO reqDTO = resolver.resolveCreate(Map.of("model", model), "any_tool");

        assertEquals("GLOBAL", reqDTO.getSystemColumnProfile());
    }

    @Test
    @DisplayName("建表：缺 model 报的是业务异常，且带上调用方的工具名")
    public void testResolveCreateMissingModel() {
        // 工具名要报对：三个工具共用这段代码，报错里说的是哪个工具，
        // 决定了模型接下来去改哪次调用
        ServiceException ex = assertThrows(ServiceException.class,
                () -> resolver.resolveCreate(Map.of(), "datamodel_preview_ddl"));
        assertTrue(ex.getMessage().contains("datamodel_preview_ddl"), ex.getMessage());
        assertTrue(ex.getMessage().contains("model"), ex.getMessage());
    }

    @Test
    @DisplayName("🔴 建表：没传 nullable 的列默认可空，只有显式 false 才 NOT NULL")
    public void testResolveCreateDefaultsNullableTrue() {
        // 锁死「用户没说必填，AI 建出来的表却每个字段都 NOT NULL」——
        // 底层 isNullable() 是 Boolean.TRUE.equals(nullable)，null 会被当成 NOT NULL
        Map<String, Object> optional = new LinkedHashMap<>();
        optional.put("columnName", "remark");
        optional.put("columnComment", "备注");
        optional.put("columnType", "STRING");
        Map<String, Object> mandatory = new LinkedHashMap<>();
        mandatory.put("columnName", "order_no");
        mandatory.put("columnComment", "订单号");
        mandatory.put("columnType", "STRING");
        mandatory.put("nullable", false);

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("tableName", "biz_demo");
        model.put("tableComment", "示例表");
        model.put("columns", List.of(optional, mandatory));

        DataModelSaveReqDTO reqDTO = resolver.resolveCreate(Map.of("model", model), "datamodel_create");

        DataModelSaveReqDTO.ColumnDTO remarkCol = reqDTO.getColumns().stream()
                .filter(c -> "remark".equals(c.getColumnName())).findFirst().orElseThrow();
        DataModelSaveReqDTO.ColumnDTO orderNoCol = reqDTO.getColumns().stream()
                .filter(c -> "order_no".equals(c.getColumnName())).findFirst().orElseThrow();
        assertTrue(remarkCol.getNullable(), "没传 nullable 的列应默认可空");
        assertFalse(orderNoCol.getNullable(), "显式 nullable=false 的列应保持 NOT NULL");
    }

    // ==================== 改表 ====================

    @Test
    @DisplayName("改表：只描述变更，现有列原样保留")
    public void testResolveUpdateKeepsExistingColumns() {
        mockTable("biz_order", "id", "order_no", "tenant_id", "deleted");

        DataModelParamResolver.UpdateResolution resolution = resolver.resolveUpdate(
                params("biz_order", List.of(column("amount", "DECIMAL")), null, null),
                "datamodel_update");

        List<String> names = resolution.reqDTO().getColumns().stream()
                .map(DataModelSaveReqDTO.ColumnDTO::getColumnName).toList();
        // 一列都不能少 —— 少的那列会被 updateDataModel 判成 DROP
        assertEquals(List.of("id", "order_no", "tenant_id", "deleted", "amount"), names);
        assertEquals(List.of("amount"), resolution.added());
        assertTrue(resolution.dropped().isEmpty());
    }

    @Test
    @DisplayName("🔴 改表：onUpdateCurrentTimestamp 必须原样带回（踩过的坑）")
    public void testResolveUpdateKeepsOnUpdateCurrentTimestamp() {
        // 漏了这个字段，TableDiffer 会把 update_time 判成「变了」，
        // 于是一次「加字段」顺手把 ON UPDATE CURRENT_TIMESTAMP 抹掉 ——
        // 从此这张表的 update_time 不再自动更新，且不报任何错
        DataModelStructureRespDTO structure = structure("biz_order", "id", "update_time");
        structure.getColumns().get(1).setOnUpdateCurrentTimestamp(true);
        mockTable(structure);

        DataModelParamResolver.UpdateResolution resolution = resolver.resolveUpdate(
                params("biz_order", List.of(column("amount", "DECIMAL")), null, null),
                "datamodel_update");

        DataModelSaveReqDTO.ColumnDTO updateTime = resolution.reqDTO().getColumns().stream()
                .filter(c -> "update_time".equals(c.getColumnName())).findFirst().orElseThrow();
        assertEquals(true, updateTime.getOnUpdateCurrentTimestamp());
    }

    @Test
    @DisplayName("改表：索引原样带回，否则会被当成要删")
    public void testResolveUpdateKeepsIndexes() {
        DataModelStructureRespDTO structure = structure("biz_order", "id", "order_no");
        DataModelStructureRespDTO.IndexDTO index = new DataModelStructureRespDTO.IndexDTO();
        index.setIndexName("uk_order_no");
        index.setIndexType("UNIQUE");
        index.setColumnNames(List.of("order_no"));
        structure.setIndexes(List.of(index));
        mockTable(structure);

        DataModelParamResolver.UpdateResolution resolution = resolver.resolveUpdate(
                params("biz_order", List.of(column("amount", "DECIMAL")), null, null),
                "datamodel_update");

        assertEquals(1, resolution.reqDTO().getIndexes().size());
        assertEquals("uk_order_no", resolution.reqDTO().getIndexes().get(0).getIndexName());
    }

    @Test
    @DisplayName("🔴 改表：riskConfirmed 恒为 null —— 那是人的判断，不是 AI 的")
    public void testResolveUpdateNeverConfirmsRisk() {
        mockTable("biz_order", "id", "order_no");

        Map<String, Object> params = params("biz_order", null, null, null);
        // 哪怕模型自作主张传了，也不能带下去
        params.put("riskConfirmed", true);
        DataModelParamResolver.UpdateResolution resolution =
                resolver.resolveUpdate(params, "datamodel_update");

        assertNull(resolution.reqDTO().getRiskConfirmed());
    }

    @Test
    @DisplayName("🔴 改表：confirmDropColumns 恒为 null —— 空表删列也拦（B4-3）")
    public void testResolveUpdateNeverConfirmsDropColumns() {
        mockTable("biz_order", "id", "order_no");

        Map<String, Object> params = params("biz_order", null, null, List.of("order_no"));
        // 哪怕模型自作主张传了，也不能带下去：带下去就等于让 AI 自己批准了一次不可回滚的删列
        params.put("confirmDropColumns", true);
        DataModelParamResolver.UpdateResolution resolution =
                resolver.resolveUpdate(params, "datamodel_update");

        assertNull(resolution.reqDTO().getConfirmDropColumns());
        assertEquals(List.of("order_no"), resolution.dropped(),
                "标记被抹掉了，但意图要如实报出来 —— 由校验器去拒，不是在这里静默丢掉");
    }

    // ==================== 🔴 B4-2 / B4-4：两条基线由工具搬运 ====================

    @Test
    @DisplayName("🔴 改表：version 与 schemaFingerprint 必须取自本次 getStructure 的取回值")
    public void testResolveUpdateCarriesBothBaselines() {
        DataModelStructureRespDTO structure = structure("biz_order", "id", "order_no");
        structure.setVersion(5);
        structure.setSchemaFingerprint("f".repeat(64));
        mockTable(structure);

        DataModelParamResolver.UpdateResolution resolution = resolver.resolveUpdate(
                params("biz_order", List.of(column("amount", "DECIMAL")), null, null),
                "datamodel_update");

        // version 缺了 ⟹ 后端乐观锁形同没开；指纹缺了 ⟹ DDL 前的结构漂移复核退化成「无基线放行」
        assertEquals(5, resolution.reqDTO().getVersion());
        assertEquals("f".repeat(64), resolution.reqDTO().getSchemaFingerprint());
        assertEquals(5, resolution.current().getVersion(), "current 与 reqDTO 必须是同一次取回");
    }

    @Test
    @DisplayName("🔴 改表：模型自己填的 version / schemaFingerprint 一律作废 —— 自己填等于自己发通行证")
    public void testResolveUpdateIgnoresModelSuppliedBaselines() {
        DataModelStructureRespDTO structure = structure("biz_order", "id", "order_no");
        structure.setVersion(5);
        structure.setSchemaFingerprint("f".repeat(64));
        mockTable(structure);

        Map<String, Object> params = params("biz_order", null, null, null);
        params.put("version", 999);
        params.put("schemaFingerprint", "0".repeat(64));
        DataModelParamResolver.UpdateResolution resolution =
                resolver.resolveUpdate(params, "datamodel_update");

        assertEquals(5, resolution.reqDTO().getVersion());
        assertEquals("f".repeat(64), resolution.reqDTO().getSchemaFingerprint());
    }

    @Test
    @DisplayName("取回的结构里没有 version 时如实留空 —— 由校验器报「工具链有问题」，不在这里编一个")
    public void testResolveUpdateLeavesBaselineEmptyWhenStructureHasNone() {
        // 存量模型（加这一列之前建的）与「getStructure 那条路径漏了搬运」都会走到这里。
        // 编一个 0 填上去的话，两道闸都会以为拿到了真基线：乐观锁会拿 0 去撞库里的真实版本
        // （表现是「已被他人修改」，而其实没人改过），指纹复核则永远放行
        mockTable("biz_order", "id", "order_no");

        DataModelParamResolver.UpdateResolution resolution = resolver.resolveUpdate(
                params("biz_order", null, null, null), "datamodel_update");

        assertNull(resolution.reqDTO().getVersion());
        assertNull(resolution.reqDTO().getSchemaFingerprint());
    }

    @Test
    @DisplayName("改表：先删再加，同名列不会被「加了又删」")
    public void testResolveUpdateDropThenAdd() {
        mockTable("biz_order", "id", "remark");

        DataModelParamResolver.UpdateResolution resolution = resolver.resolveUpdate(
                params("biz_order", List.of(column("remark", "STRING")), null, List.of("remark")),
                "datamodel_update");

        List<String> names = resolution.reqDTO().getColumns().stream()
                .map(DataModelSaveReqDTO.ColumnDTO::getColumnName).toList();
        assertEquals(List.of("id", "remark"), names);
        assertEquals(List.of("remark"), resolution.dropped());
        assertEquals(List.of("remark"), resolution.added());
    }

    @Test
    @DisplayName("改表：改一个不存在的列必须报错，不能静默忽略")
    public void testResolveUpdateUnknownModify() {
        // 静默忽略的话，模型以为改成功了，实际什么也没发生
        mockTable("biz_order", "id", "order_no");

        ServiceException ex = assertThrows(ServiceException.class, () -> resolver.resolveUpdate(
                params("biz_order", null, List.of(column("not_exists", "STRING")), null),
                "datamodel_update"));
        assertTrue(ex.getMessage().contains("not_exists"), ex.getMessage());
        // 要把现有列一并报出来，模型才知道该改成什么
        assertTrue(ex.getMessage().contains("order_no"), ex.getMessage());
    }

    @Test
    @DisplayName("改表：删一个不存在的列必须报错")
    public void testResolveUpdateUnknownDrop() {
        mockTable("biz_order", "id", "order_no");

        ServiceException ex = assertThrows(ServiceException.class, () -> resolver.resolveUpdate(
                params("biz_order", null, null, List.of("not_exists")), "datamodel_update"));
        assertTrue(ex.getMessage().contains("not_exists"), ex.getMessage());
    }

    @Test
    @DisplayName("改表：加一个已存在的列要提示改用 modifyColumns")
    public void testResolveUpdateAddExisting() {
        mockTable("biz_order", "id", "order_no");

        ServiceException ex = assertThrows(ServiceException.class, () -> resolver.resolveUpdate(
                params("biz_order", List.of(column("order_no", "STRING")), null, null),
                "datamodel_update"));
        assertTrue(ex.getMessage().contains("modifyColumns"), ex.getMessage());
    }

    @Test
    @DisplayName("改表：表没登记进数据模型时，要说清楚这个工具改不了它")
    public void testResolveUpdateTableNotRegistered() {
        when(dataModelApi.getDataModelList()).thenReturn(List.of());

        ServiceException ex = assertThrows(ServiceException.class, () -> resolver.resolveUpdate(
                params("biz_unknown", null, null, null), "datamodel_preview_ddl"));
        assertTrue(ex.getMessage().contains("datamodel_list"), ex.getMessage());
        assertTrue(ex.getMessage().contains("datamodel_preview_ddl"), ex.getMessage());
    }

    @Test
    @DisplayName("改表：只改字段说明时，其余属性保持原样")
    public void testResolveUpdateModifyOnlyGivenFields() {
        DataModelStructureRespDTO structure = structure("biz_order", "id", "order_no");
        DataModelStructureRespDTO.ColumnDTO orderNo = structure.getColumns().get(1);
        orderNo.setColumnType("STRING");
        orderNo.setLength(64);
        orderNo.setNullable(false);
        mockTable(structure);

        Map<String, Object> change = new LinkedHashMap<>();
        change.put("columnName", "order_no");
        change.put("columnComment", "订单号");
        DataModelParamResolver.UpdateResolution resolution = resolver.resolveUpdate(
                params("biz_order", null, List.of(change), null), "datamodel_update");

        DataModelSaveReqDTO.ColumnDTO result = resolution.reqDTO().getColumns().stream()
                .filter(c -> "order_no".equals(c.getColumnName())).findFirst().orElseThrow();
        assertEquals("订单号", result.getColumnComment());
        // 没写的字段不能被顺手改掉
        assertEquals("STRING", result.getColumnType());
        assertEquals(64, result.getLength());
        assertFalse(result.getNullable());
        assertEquals(List.of("order_no"), resolution.modified());
    }

    // ==================== 造数据 ====================

    private void mockTable(String tableName, String... columnNames) {
        mockTable(structure(tableName, columnNames));
    }

    private void mockTable(DataModelStructureRespDTO structure) {
        DataModelApi.DataModelSimpleRespDTO model = new DataModelApi.DataModelSimpleRespDTO();
        model.setId(1L);
        model.setTableName(structure.getTableName());
        model.setModelName("测试模型");
        when(dataModelApi.getDataModelList()).thenReturn(List.of(model));
        when(dataModelApi.getStructure(1L)).thenReturn(structure);
    }

    private DataModelStructureRespDTO structure(String tableName, String... columnNames) {
        DataModelStructureRespDTO dto = new DataModelStructureRespDTO();
        dto.setTableName(tableName);
        dto.setTableComment(tableName + " 的注释");
        List<DataModelStructureRespDTO.ColumnDTO> columns = new ArrayList<>();
        for (String name : columnNames) {
            DataModelStructureRespDTO.ColumnDTO column = new DataModelStructureRespDTO.ColumnDTO();
            column.setColumnName(name);
            column.setPrimaryKey("id".equals(name));
            columns.add(column);
        }
        dto.setColumns(columns);
        return dto;
    }

    private Map<String, Object> column(String name, String type) {
        Map<String, Object> column = new LinkedHashMap<>();
        column.put("columnName", name);
        column.put("columnComment", name + " 的说明");
        column.put("columnType", type);
        return column;
    }

    private Map<String, Object> params(String tableName, List<?> add, List<?> modify,
                                       List<String> drop) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tableName", tableName);
        if (add != null) {
            params.put("addColumns", add);
        }
        if (modify != null) {
            params.put("modifyColumns", modify);
        }
        if (drop != null) {
            params.put("dropColumns", drop);
        }
        return params;
    }

}
