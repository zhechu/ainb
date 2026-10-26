package cn.enlev.ainb.module.agent.tool.impl.datamodel;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.AgentValidateResult;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.DataModelCreateValidator;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.DataModelUpdateValidator;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DataModelPreviewDdlTool} 的单元测试
 *
 * <p>这个工具的承诺是「预览通过 = 执行能过」。用例要钉住的是它<b>怎么失败</b> ——
 * 一个把「不能过」报成工具故障的预览工具，比没有预览工具更糟：
 * 模型收到工具故障会原样重试，收到「不能过，因为 X」才会去改参数。
 *
 * @author 走进新时代
 */
public class DataModelPreviewDdlToolTest extends BaseMockitoUnitTest {

    @InjectMocks
    private DataModelPreviewDdlTool tool;

    @Mock
    private DataModelApi dataModelApi;
    @Mock
    private DataModelParamResolver paramResolver;
    @Mock
    private DataModelCreateValidator createValidator;
    @Mock
    private DataModelUpdateValidator updateValidator;

    // ==================== 模式判定 ====================

    @Test
    @DisplayName("既没传 model 也没传 tableName：要说清楚两种用法")
    public void testMissingBoth() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> tool.execute(Map.of()));
        assertTrue(ex.getMessage().contains("model"), ex.getMessage());
        assertTrue(ex.getMessage().contains("tableName"), ex.getMessage());
    }

    @Test
    @DisplayName("🔴 model 与 tableName 同时传：拒绝，不能替模型挑一个")
    public void testBothGiven() {
        // 猜错的话，模型拿到的是「另一件事」的预览结果，而它不会察觉
        ServiceException ex = assertThrows(ServiceException.class,
                () -> tool.execute(Map.of("model", Map.of("tableName", "biz_a"),
                        "tableName", "biz_b")));
        assertTrue(ex.getMessage().contains("只能传一个"), ex.getMessage());
    }

    // ==================== 建表预览 ====================

    @Test
    @DisplayName("建表预览通过：返回 DDL，并指明真正执行该调谁")
    public void testPreviewCreatePassed() {
        DataModelSaveReqDTO reqDTO = createReqDTO();
        when(paramResolver.resolveCreate(any(), any())).thenReturn(reqDTO);
        when(createValidator.validate(reqDTO)).thenReturn(new AgentValidateResult());
        when(dataModelApi.previewDdl(reqDTO)).thenReturn(List.of("CREATE TABLE biz_demo ..."));

        Map<?, ?> resp = (Map<?, ?>) tool.execute(Map.of("model", Map.of()));

        assertEquals("CREATE", resp.get("mode"));
        assertEquals(true, resp.get("passed"));
        assertEquals(List.of("CREATE TABLE biz_demo ..."), resp.get("ddl"));
        assertTrue(resp.get("message").toString().contains("datamodel_create"),
                resp.get("message").toString());
    }

    @Test
    @DisplayName("🔴 agent 侧校验就没过：直接返回原因，不该再去调后端")
    public void testPreviewCreateRejectedByValidator() {
        DataModelSaveReqDTO reqDTO = createReqDTO();
        AgentValidateResult failed = new AgentValidateResult();
        failed.addError("表名必须以 biz_ 开头");
        when(paramResolver.resolveCreate(any(), any())).thenReturn(reqDTO);
        when(createValidator.validate(reqDTO)).thenReturn(failed);

        Map<?, ?> resp = (Map<?, ?>) tool.execute(Map.of("model", Map.of()));

        assertEquals(false, resp.get("passed"));
        assertTrue(resp.get("reason").toString().contains("biz_"), resp.get("reason").toString());
        verify(dataModelApi, never()).previewDdl(any());
    }

    @Test
    @DisplayName("🔴 后端校验不通过：转成「不通过 + 原因」，不能抛成工具故障")
    public void testPreviewCreateRejectedByBackend() {
        // 模型问的是「这份参数能不能过」——「不能过，因为 X」是对这个问题的成功回答。
        // 抛出去的话它收到的是一次工具调用失败，更可能原样重试而不是改参数
        DataModelSaveReqDTO reqDTO = createReqDTO();
        when(paramResolver.resolveCreate(any(), any())).thenReturn(reqDTO);
        when(createValidator.validate(reqDTO)).thenReturn(new AgentValidateResult());
        when(dataModelApi.previewDdl(reqDTO))
                .thenThrow(new ServiceException(1001, "表名不能以 system_ 开头"));

        Map<?, ?> resp = (Map<?, ?>) tool.execute(Map.of("model", Map.of()));

        assertEquals(false, resp.get("passed"));
        assertTrue(resp.get("reason").toString().contains("system_"),
                resp.get("reason").toString());
    }

    @Test
    @DisplayName("🔴 真故障照常抛：数据库连不上不该被伪装成「参数不合法」")
    public void testPreviewCreateRealFailureRethrown() {
        DataModelSaveReqDTO reqDTO = createReqDTO();
        when(paramResolver.resolveCreate(any(), any())).thenReturn(reqDTO);
        when(createValidator.validate(reqDTO)).thenReturn(new AgentValidateResult());
        when(dataModelApi.previewDdl(reqDTO))
                .thenThrow(new IllegalStateException("connection refused"));

        assertThrows(IllegalStateException.class, () -> tool.execute(Map.of("model", Map.of())));
    }

    @Test
    @DisplayName("建表预览：校验器的警告要带回去")
    public void testPreviewCreateWarnings() {
        DataModelSaveReqDTO reqDTO = createReqDTO();
        AgentValidateResult passed = new AgentValidateResult();
        passed.addWarning("STRING 没给长度，会按默认值 255 建");
        when(paramResolver.resolveCreate(any(), any())).thenReturn(reqDTO);
        when(createValidator.validate(reqDTO)).thenReturn(passed);
        when(dataModelApi.previewDdl(reqDTO)).thenReturn(List.of("CREATE TABLE biz_demo ..."));

        Map<?, ?> resp = (Map<?, ?>) tool.execute(Map.of("model", Map.of()));

        assertEquals(true, resp.get("passed"));
        assertEquals(List.of("STRING 没给长度，会按默认值 255 建"), resp.get("warnings"));
    }

    // ==================== 改表预览 ====================

    @Test
    @DisplayName("改表预览通过：回显变更意图，让模型能核对工具理解得对不对")
    public void testPreviewAlterPassed() {
        DataModelParamResolver.UpdateResolution resolution = updateResolution();
        when(paramResolver.resolveUpdate(any(), any())).thenReturn(resolution);
        when(updateValidator.validate(resolution.reqDTO(), resolution.current()))
                .thenReturn(new AgentValidateResult());
        when(dataModelApi.previewDdl(resolution.reqDTO()))
                .thenReturn(List.of("ALTER TABLE biz_order ADD COLUMN amount ..."));

        Map<?, ?> resp = (Map<?, ?>) tool.execute(Map.of("tableName", "biz_order"));

        assertEquals("ALTER", resp.get("mode"));
        assertEquals(true, resp.get("passed"));
        assertEquals(List.of("amount"), resp.get("addedColumns"));
        assertEquals(List.of("remark"), resp.get("droppedColumns"));
        assertTrue(resp.get("message").toString().contains("datamodel_update"),
                resp.get("message").toString());
    }

    @Test
    @DisplayName("🔴 改表预览被拒时，也要回显变更意图 —— 否则说不清是哪一处被拒")
    public void testPreviewAlterRejectedStillEchoesIntent() {
        DataModelParamResolver.UpdateResolution resolution = updateResolution();
        AgentValidateResult failed = new AgentValidateResult();
        failed.addError("不能删除系统列 tenant_id");
        when(paramResolver.resolveUpdate(any(), any())).thenReturn(resolution);
        when(updateValidator.validate(resolution.reqDTO(), resolution.current()))
                .thenReturn(failed);

        Map<?, ?> resp = (Map<?, ?>) tool.execute(Map.of("tableName", "biz_order"));

        assertEquals(false, resp.get("passed"));
        assertEquals(List.of("remark"), resp.get("droppedColumns"));
        assertTrue(resp.get("reason").toString().contains("tenant_id"),
                resp.get("reason").toString());
        verify(dataModelApi, never()).previewDdl(any());
    }

    // ==================== 造数据 ====================

    private DataModelSaveReqDTO createReqDTO() {
        DataModelSaveReqDTO reqDTO = new DataModelSaveReqDTO();
        reqDTO.setTableName("biz_demo");
        return reqDTO;
    }

    private DataModelParamResolver.UpdateResolution updateResolution() {
        DataModelSaveReqDTO reqDTO = new DataModelSaveReqDTO();
        reqDTO.setId(1L);
        reqDTO.setTableName("biz_order");
        reqDTO.setColumns(new ArrayList<>());
        DataModelStructureRespDTO current = new DataModelStructureRespDTO();
        current.setTableName("biz_order");
        current.setColumns(new ArrayList<>());
        return new DataModelParamResolver.UpdateResolution(reqDTO, current,
                List.of("amount"), List.of(), List.of("remark"));
    }

}
