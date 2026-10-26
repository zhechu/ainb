package cn.enlev.ainb.module.bpm.service.definition;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.framework.tenant.core.context.TenantContextHolder;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.bpm.controller.admin.definition.vo.model.BpmModelMetaInfoVO;
import cn.enlev.ainb.module.bpm.controller.admin.definition.vo.model.BpmModelSaveReqVO;
import cn.enlev.ainb.module.bpm.controller.admin.definition.vo.model.simple.BpmSimpleModelNodeVO;
import cn.enlev.ainb.module.bpm.enums.definition.BpmModelTypeEnum;
import cn.enlev.ainb.module.bpm.enums.definition.BpmSimpleModelNodeTypeEnum;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Model;
import org.flowable.engine.repository.ModelQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static cn.enlev.ainb.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.enlev.ainb.module.bpm.enums.ErrorCodeConstants.MODEL_NOT_EXISTS;
import static cn.enlev.ainb.module.bpm.enums.ErrorCodeConstants.MODEL_UPDATE_FAIL_NOT_MANAGER;
import static cn.enlev.ainb.module.bpm.enums.ErrorCodeConstants.MODEL_UPDATE_FAIL_NOT_MANAGER_FOR_SCOPE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link BpmModelServiceImpl} 的单元测试
 *
 * @author 芋道源码
 */
public class BpmModelServiceImplTest extends BaseMockitoUnitTest {

    private static final Long TENANT_ID = 1L;
    private static final String MODEL_ID = "model-id";

    @InjectMocks
    private BpmModelServiceImpl modelService;

    @Mock
    private RepositoryService repositoryService;
    @Mock
    private ModelQuery modelQuery;
    @Mock
    private cn.enlev.ainb.module.bpm.framework.permission.BpmProcessPermissionChecker processPermissionChecker;

    @BeforeEach
    public void setUp() {
        TenantContextHolder.setTenantId(TENANT_ID);
    }

    @AfterEach
    public void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    public void testExportModel_bpmn() {
        // 准备参数
        Model model = mockModel(BpmModelTypeEnum.BPMN.getType());
        String bpmnXml = "<definitions />";
        // mock 方法（repositoryService）
        mockGetModel(model);
        when(repositoryService.getModelEditorSource(eq(MODEL_ID))).thenReturn(StrUtil.utf8Bytes(bpmnXml));

        // 调用
        BpmModelSaveReqVO result = modelService.exportModel(MODEL_ID);

        // 断言
        assertEquals(model.getKey(), result.getKey());
        assertEquals(model.getName(), result.getName());
        assertEquals(model.getCategory(), result.getCategory());
        assertEquals(BpmModelTypeEnum.BPMN.getType(), result.getType());
        assertEquals(bpmnXml, result.getBpmnXml());
        assertNull(result.getSimpleModel());
    }

    @Test
    public void testExportModel_simple() {
        // 准备参数
        Model model = mockModel(BpmModelTypeEnum.SIMPLE.getType());
        BpmSimpleModelNodeVO simpleModel = new BpmSimpleModelNodeVO();
        simpleModel.setId("start");
        simpleModel.setType(BpmSimpleModelNodeTypeEnum.START_NODE.getType());
        // mock 方法（repositoryService）
        mockGetModel(model);
        when(repositoryService.getModelEditorSourceExtra(eq(MODEL_ID)))
                .thenReturn(JsonUtils.toJsonByte(simpleModel));

        // 调用
        BpmModelSaveReqVO result = modelService.exportModel(MODEL_ID);

        // 断言
        assertEquals(BpmModelTypeEnum.SIMPLE.getType(), result.getType());
        assertNotNull(result.getSimpleModel());
        assertEquals(simpleModel.getId(), result.getSimpleModel().getId());
        assertEquals(simpleModel.getType(), result.getSimpleModel().getType());
        assertNull(result.getBpmnXml());
    }

    @Test
    public void testExportModel_notExists() {
        // mock 方法（repositoryService）
        when(repositoryService.createModelQuery()).thenReturn(modelQuery);
        when(modelQuery.modelId(eq(MODEL_ID))).thenReturn(modelQuery);
        when(modelQuery.modelTenantId(eq(TENANT_ID.toString()))).thenReturn(modelQuery);
        when(modelQuery.singleResult()).thenReturn(null);

        // 调用，并断言异常
        assertServiceException(() -> modelService.exportModel(MODEL_ID), MODEL_NOT_EXISTS);
    }

    @Test
    public void testImportModel_bpmn() {
        // 准备参数
        BpmModelSaveReqVO reqVO = new BpmModelSaveReqVO();
        reqVO.setKey("test_process");
        reqVO.setName("测试流程");
        reqVO.setCategory("OA");
        reqVO.setType(BpmModelTypeEnum.BPMN.getType());
        reqVO.setBpmnXml("<definitions />");
        reqVO.setStartUserIds(Arrays.asList(10L, 20L));
        reqVO.setStartDeptIds(Collections.singletonList(30L));
        reqVO.setManagerUserIds(Collections.singletonList(40L));
        Model model = mock(Model.class);
        when(model.getId()).thenReturn(MODEL_ID);
        // mock 方法（repositoryService）
        when(repositoryService.createModelQuery()).thenReturn(modelQuery);
        when(modelQuery.modelTenantId(eq(TENANT_ID.toString()))).thenReturn(modelQuery);
        when(modelQuery.modelKey(eq(reqVO.getKey()))).thenReturn(modelQuery);
        when(modelQuery.singleResult()).thenReturn(null);
        when(repositoryService.newModel()).thenReturn(model);

        // 调用
        String result = modelService.importModel(reqVO);

        // 断言
        assertEquals(MODEL_ID, result);
        verify(model).setTenantId(TENANT_ID.toString());
        verify(repositoryService).saveModel(same(model));
        verify(repositoryService).addModelEditorSource(eq(MODEL_ID),
                aryEq(StrUtil.utf8Bytes(reqVO.getBpmnXml())));
        ArgumentCaptor<String> metaInfoCaptor = ArgumentCaptor.forClass(String.class);
        verify(model).setMetaInfo(metaInfoCaptor.capture());
        BpmModelMetaInfoVO metaInfo = JsonUtils.parseObject(metaInfoCaptor.getValue(), BpmModelMetaInfoVO.class);
        assertNotNull(metaInfo);
        assertEquals(Arrays.asList(10L, 20L), metaInfo.getStartUserIds());
        assertEquals(Collections.singletonList(30L), metaInfo.getStartDeptIds());
        assertEquals(Collections.singletonList(40L), metaInfo.getManagerUserIds());
    }

    // ==================== validateModelManager：两条判据 ====================
    //
    // 🔴 这三条测试锁的是一个真实踩过的死锁（2026-09-22 实测）：
    //    流程资源行 bpm:process:<key> 是 deployModel 成功【之后】才登记的，
    //    于是「从未部署过」的模型上 MANAGE 判定恒假 —— 要部署得先是管理员，
    //    要当管理员得先部署成功。超管也解不开（hasResourceAction 不对超管短路），
    //    结果是新建的流程模型既部署不了也删不掉。
    //
    //    修法是在那个窗口内回落到 managerUserIds。下面三条分别锁住：
    //    放行、不过宽、不越界（已部署的仍走资源判定）。

    @Test
    public void testValidateModelManager_notDeployed_inManagerUserIds() {
        // 准备参数：从未部署过（deploymentId 为空），userId 在管理员名单里
        Long userId = 100L;
        Model model = mockModelWithManagers(null, Collections.singletonList(userId));
        mockGetModel(model);

        // 调用：不抛异常即为放行
        modelService.deleteModel(userId, MODEL_ID);

        // 断言：真的执行了删除，而不是悄悄跳过
        verify(repositoryService).deleteModel(eq(MODEL_ID));
        // 从未部署过时不该去查资源判定 —— 那条链在这个窗口内恒假，查了只会白拒
        verify(processPermissionChecker, never()).isManager(any(), any());
    }

    @Test
    public void testValidateModelManager_notDeployed_notInManagerUserIds() {
        // 准备参数：从未部署过，userId 不在管理员名单里
        Long userId = 100L;
        Model model = mockModelWithManagers(null, Collections.singletonList(999L));
        mockGetModel(model);

        // 调用并断言：回落不能退化成「谁都能改」
        assertServiceException(() -> modelService.deleteModel(userId, MODEL_ID),
                MODEL_UPDATE_FAIL_NOT_MANAGER, "测试流程");
        verify(repositoryService, never()).deleteModel(any());
    }

    @Test
    public void testValidateModelManager_deployed_usesResourceGrant() {
        // 准备参数：已部署过（deploymentId 非空），且 userId 就在 managerUserIds 里
        Long userId = 100L;
        Model model = mockModelWithManagers("deployment-id", Collections.singletonList(userId));
        mockGetModel(model);
        // 但资源判定说「不是管理员」
        when(processPermissionChecker.isManager(eq(userId), eq("test_process"))).thenReturn(false);

        // 调用并断言：已部署的模型必须以资源判定为准。
        // 若这里放行了，说明回落泄漏到了正常路径上 —— 等于把 S7b 的重构整个绕开，
        // 因为 managerUserIds 是模型自带的字段，改模型的人就能改它
        assertServiceException(() -> modelService.deleteModel(userId, MODEL_ID),
                MODEL_UPDATE_FAIL_NOT_MANAGER, "测试流程");
        verify(repositoryService, never()).deleteModel(any());
    }

    // ==================== 设计权（DESIGN）与管理权（MANAGE）分档 ====================
    //
    // 🔴 这批用例锁的是「拆两档」这件事本身，尤其是【不能合并成一档】：
    //    七个写操作原本共用 validateModelManager，最省事的接法是在里面写成
    //    isManager(...) || canDesign(...) —— 那样一来给某人配「设计」（本意只能改流程图），
    //    他就顺带能删模型、能清掉全部流程数据。下面几条钉的就是这个提权缺口。

    @Test
    public void testDesigner_canUpdateModel() {
        // 准备参数：已部署过，只有 DESIGN、没有 MANAGE
        Long userId = 100L;
        Model model = mockModelWithScope("deployment-id", null, null, Collections.singletonList(40L));
        mockGetModel(model);
        when(processPermissionChecker.isManager(eq(userId), eq("test_process"))).thenReturn(false);
        when(processPermissionChecker.canDesign(eq(userId), eq("test_process"))).thenReturn(true);

        // 调用：只改流程图与基本信息，三个授权字段原样带回
        modelService.updateModel(userId, buildSaveReqVO(null, null, Collections.singletonList(40L)));

        // 断言：真的保存了
        verify(repositoryService).saveModel(eq(model));
    }

    @Test
    public void testDesigner_cannotDeleteModel() {
        // 准备参数：同上，只有 DESIGN
        Long userId = 100L;
        Model model = mockModelWithScope("deployment-id", null, null, Collections.singletonList(40L));
        mockGetModel(model);
        when(processPermissionChecker.isManager(eq(userId), eq("test_process"))).thenReturn(false);

        // 调用并断言：删除是破坏性操作，设计权不放行
        assertServiceException(() -> modelService.deleteModel(userId, MODEL_ID),
                MODEL_UPDATE_FAIL_NOT_MANAGER, "测试流程");
        verify(repositoryService, never()).deleteModel(any());
        // 窄档连问都不问 DESIGN —— 这条比「结果为 false」更强：
        // 它钉死了 deleteModel 走的是 validateModelManager 而不是宽档
        verify(processPermissionChecker, never()).canDesign(any(), any());
    }

    @Test
    public void testDesigner_cannotCleanModel() {
        // 准备参数：同上，只有 DESIGN
        Long userId = 100L;
        Model model = mockModelWithScope("deployment-id", null, null, Collections.singletonList(40L));
        mockGetModel(model);
        when(processPermissionChecker.isManager(eq(userId), eq("test_process"))).thenReturn(false);

        // 调用并断言：清理会删掉全部流程实例与历史，比删除模型更不可逆
        assertServiceException(() -> modelService.cleanModel(userId, MODEL_ID),
                MODEL_UPDATE_FAIL_NOT_MANAGER, "测试流程");
        verify(processPermissionChecker, never()).canDesign(any(), any());
    }

    @Test
    public void testDesigner_notDeployed_stillDenied() {
        // 准备参数：从未部署过。资源行是那次部署才写出来的，
        // 所以这个窗口里既没有 MANAGE 也没有 DESIGN 可授，判据只有 managerUserIds 一条
        Long userId = 100L;
        Model model = mockModelWithScope(null, null, null, Collections.singletonList(40L));
        mockGetModel(model);

        // 调用并断言：回落名单里没有他，就算给他配过 DESIGN 也不该被捞进来
        assertServiceException(() -> modelService.updateModel(userId,
                        buildSaveReqVO(null, null, Collections.singletonList(40L))),
                MODEL_UPDATE_FAIL_NOT_MANAGER, "测试流程");
        verify(repositoryService, never()).saveModel(any());
        // 而且根本不该去查 DESIGN：deploymentId 为空时那次查询恒假，查了只会白拒
        verify(processPermissionChecker, never()).canDesign(any(), any());
    }

    // ==================== 三个授权投影字段：只有管理员能改 ====================
    //
    // 🔴 startUserIds / startDeptIds / managerUserIds 看着是模型元数据，实际是授权数据：
    //    deployModel 会经 registerProcessResources → syncProcessAuthorization
    //    把它们投影成 sys_grant 里的 START / MANAGE 行。
    //    于是「设计权能改这三个字段」= 「设计权能给自己发管理员」：
    //    updateModel(managerUserIds=[自己]) → deployModel → 拿到 MANAGE → 删模型/清数据全放行。
    //    而且不必自己部署 —— 埋进 metaInfo，等真管理员下次部署照样生效。

    @Test
    public void testUpdateModel_designer_cannotAddSelfAsManager() {
        // 准备参数：库里管理员是 40，只有 DESIGN 的人把自己（100）加进去
        Long userId = 100L;
        Model model = mockModelWithScope("deployment-id", null, null, Collections.singletonList(40L));
        mockGetModel(model);
        when(processPermissionChecker.isManager(eq(userId), eq("test_process"))).thenReturn(false);
        when(processPermissionChecker.canDesign(eq(userId), eq("test_process"))).thenReturn(true);

        // 调用并断言
        assertServiceException(() -> modelService.updateModel(userId,
                        buildSaveReqVO(null, null, Arrays.asList(40L, userId))),
                MODEL_UPDATE_FAIL_NOT_MANAGER_FOR_SCOPE, "测试流程");
        // 关键：一个字节都没写进去。若这里放行了，两档拆分就等于没拆
        verify(repositoryService, never()).saveModel(any());
    }

    @Test
    public void testUpdateModel_designer_cannotChangeStartScope() {
        // 准备参数：库里没限定发起范围，只有 DESIGN 的人把它改成「只有我能发起」
        Long userId = 100L;
        Model model = mockModelWithScope("deployment-id", null, null, Collections.singletonList(40L));
        mockGetModel(model);
        when(processPermissionChecker.isManager(eq(userId), eq("test_process"))).thenReturn(false);
        when(processPermissionChecker.canDesign(eq(userId), eq("test_process"))).thenReturn(true);

        // 调用并断言：START 投影同样只有管理员能改
        assertServiceException(() -> modelService.updateModel(userId,
                        buildSaveReqVO(Collections.singletonList(userId), null, Collections.singletonList(40L))),
                MODEL_UPDATE_FAIL_NOT_MANAGER_FOR_SCOPE, "测试流程");
        verify(repositoryService, never()).saveModel(any());
    }

    @Test
    public void testUpdateModel_designer_reorderIsNotEscalation() {
        // 准备参数：同一批人，只是顺序不同（前端重新渲染就会这样）
        Long userId = 100L;
        Model model = mockModelWithScope("deployment-id", null, null, Arrays.asList(40L, 50L));
        mockGetModel(model);
        when(processPermissionChecker.isManager(eq(userId), eq("test_process"))).thenReturn(false);
        when(processPermissionChecker.canDesign(eq(userId), eq("test_process"))).thenReturn(true);

        // 调用：按集合比而不是按列表比，所以这不算越权
        modelService.updateModel(userId, buildSaveReqVO(null, null, Arrays.asList(50L, 40L)));

        // 断言
        verify(repositoryService).saveModel(eq(model));
    }

    @Test
    public void testUpdateModel_manager_canStillChangeScope() {
        // 准备参数：已部署过，且资源判定说他是管理员
        Long userId = 100L;
        Model model = mockModelWithScope("deployment-id", null, null, Collections.singletonList(40L));
        mockGetModel(model);
        when(processPermissionChecker.isManager(eq(userId), eq("test_process"))).thenReturn(true);

        // 调用：把自己加进管理员名单
        modelService.updateModel(userId, buildSaveReqVO(null, null, Arrays.asList(40L, userId)));

        // 断言：MANAGE 的能力一点没被这次拆分削弱，改完的名单真的写进了 metaInfo
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(model).setMetaInfo(captor.capture());
        BpmModelMetaInfoVO saved = JsonUtils.parseObject(captor.getValue(), BpmModelMetaInfoVO.class);
        assertEquals(Arrays.asList(40L, userId), saved.getManagerUserIds());
        verify(repositoryService).saveModel(eq(model));
    }

    private BpmModelSaveReqVO buildSaveReqVO(java.util.List<Long> startUserIds,
                                             java.util.List<Long> startDeptIds,
                                             java.util.List<Long> managerUserIds) {
        BpmModelSaveReqVO reqVO = new BpmModelSaveReqVO();
        reqVO.setId(MODEL_ID);
        reqVO.setKey("test_process");
        reqVO.setName("测试流程");
        reqVO.setStartUserIds(startUserIds);
        reqVO.setStartDeptIds(startDeptIds);
        reqVO.setManagerUserIds(managerUserIds);
        return reqVO;
    }

    /**
     * 造一个带 deploymentId 与 managerUserIds 的模型
     *
     * @param deploymentId 部署编号，null 表示从未部署过
     * @param managerUserIds 管理员名单
     */
    private Model mockModelWithManagers(String deploymentId, java.util.List<Long> managerUserIds) {
        return mockModelWithScope(deploymentId, null, null, managerUserIds);
    }

    /**
     * 造一个带 deploymentId 与三个「授权投影字段」的模型
     *
     * @param deploymentId   部署编号，null 表示从未部署过
     * @param startUserIds   可发起用户
     * @param startDeptIds   可发起部门
     * @param managerUserIds 管理员名单
     */
    private Model mockModelWithScope(String deploymentId, java.util.List<Long> startUserIds,
                                     java.util.List<Long> startDeptIds, java.util.List<Long> managerUserIds) {
        Model model = mock(Model.class);
        // 各条用例走的是不同分支（放行 / 回落拒绝 / 资源判定拒绝 / 越权拒绝），
        // 各自只会读到其中一部分属性 —— 用 lenient 免得没读到的那些被判成多余 stub
        lenient().when(model.getKey()).thenReturn("test_process");
        lenient().when(model.getName()).thenReturn("测试流程");
        lenient().when(model.getCreateTime()).thenReturn(new Date(1_000L));
        lenient().when(model.getDeploymentId()).thenReturn(deploymentId);
        BpmModelMetaInfoVO metaInfo = new BpmModelMetaInfoVO();
        metaInfo.setType(BpmModelTypeEnum.SIMPLE.getType());
        metaInfo.setStartUserIds(startUserIds);
        metaInfo.setStartDeptIds(startDeptIds);
        metaInfo.setManagerUserIds(managerUserIds);
        lenient().when(model.getMetaInfo()).thenReturn(JsonUtils.toJsonString(metaInfo));
        return model;
    }

    private Model mockModel(Integer type) {
        Model model = mock(Model.class);
        when(model.getKey()).thenReturn("test_process");
        when(model.getName()).thenReturn("测试流程");
        when(model.getCategory()).thenReturn("OA");
        when(model.getCreateTime()).thenReturn(new Date(1_000L));
        BpmModelMetaInfoVO metaInfo = new BpmModelMetaInfoVO();
        metaInfo.setType(type);
        when(model.getMetaInfo()).thenReturn(JsonUtils.toJsonString(metaInfo));
        return model;
    }

    private void mockGetModel(Model model) {
        when(repositoryService.createModelQuery()).thenReturn(modelQuery);
        when(modelQuery.modelId(eq(MODEL_ID))).thenReturn(modelQuery);
        when(modelQuery.modelTenantId(eq(TENANT_ID.toString()))).thenReturn(modelQuery);
        when(modelQuery.singleResult()).thenReturn(model);
    }

}
