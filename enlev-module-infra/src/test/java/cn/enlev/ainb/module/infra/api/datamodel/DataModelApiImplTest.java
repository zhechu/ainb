package cn.enlev.ainb.module.infra.api.datamodel;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.api.datamodel.dto.DataModelSaveReqDTO;
import cn.enlev.ainb.module.infra.api.datamodel.dto.DataModelStructureRespDTO;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApiImpl;
import cn.enlev.ainb.module.infra.controller.admin.datamodel.vo.DataModelSaveReqVO;
import cn.enlev.ainb.module.infra.controller.admin.datamodel.vo.DataModelStructureRespVO;
import cn.enlev.ainb.module.infra.dal.dataobject.datamodel.DataModelDO;
import cn.enlev.ainb.module.infra.enums.artifact.ArtifactSourceEnum;
import cn.enlev.ainb.module.infra.enums.datamodel.DataModelColumnTypeEnum;
import cn.enlev.ainb.module.infra.framework.datamodel.config.DataModelProperties;
import cn.enlev.ainb.module.infra.framework.datamodel.core.model.TableColumnModel;
import cn.enlev.ainb.module.infra.framework.datamodel.core.model.TableIndexModel;
import cn.enlev.ainb.module.infra.service.datamodel.DataModelService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据模型 API 的单元测试
 *
 * <p>前一半用例守的是「薄封装」这个约定本身：api 层<b>只做搬运，不加判断</b>。
 * 一旦有人在这里塞进业务逻辑，「界面走 Controller、AI 走 api」两条路径就开始分叉，
 * 而那类不一致最难反查 —— 两边都「能用」，只是结果不同。
 *
 * <p>🔴 后一半（「两个读者之间的对账」那组）守的是<b>相反</b>的东西：
 * {@code getPhysicalTableList} 与 {@code getDanglingRegistrationList} <b>必须</b>有判断，
 * 因为对账这件事没有 Service 可以委托（Service 的每个方法只站在一个读者上）。
 * 它们要钉住的是三条：keyword 是表名 OR 注释、两个方向共用同一份大小写归一、
 * 悬挂对账的物理表取<b>全库</b>。三条都是踩过坑量出来的，见各组用例里的注释。
 *
 * @author 走进新时代
 */
public class DataModelApiImplTest extends BaseMockitoUnitTest {

    @InjectMocks
    private DataModelApiImpl dataModelApi;

    @Mock
    private DataModelService dataModelService;

    @Mock
    private DataModelProperties dataModelProperties;

    private TableColumnModel col(String name) {
        return new TableColumnModel().setColumnName(name).setColumnType("STRING")
                .setNullable(true).setPrimaryKey(false).setColumnComment("列");
    }

    @Test
    @DisplayName("建表：原样委托给 Service，字段逐项搬到 VO")
    void testCreateDelegates() {
        DataModelSaveReqDTO dto = new DataModelSaveReqDTO();
        dto.setTableName("biz_order");
        dto.setTableComment("订单表");
        dto.setModelName("订单模型");
        dto.setSystemColumnProfile("GLOBAL");
        dto.setRiskConfirmed(true);
        DataModelSaveReqDTO.ColumnDTO column = new DataModelSaveReqDTO.ColumnDTO();
        column.setColumnName("order_no");
        column.setColumnComment("订单号");
        column.setColumnType("STRING");
        column.setDictType("order_status");
        dto.setColumns(List.of(column));
        when(dataModelService.createDataModel(any(), any())).thenReturn(9L);

        assertEquals(9L, dataModelApi.createDataModel(dto));

        ArgumentCaptor<DataModelSaveReqVO> captor = ArgumentCaptor.forClass(DataModelSaveReqVO.class);
        // 🔴 走 api 包 = AI 路径，来源必须是 AI。这个参数是 api 层<b>自己定死</b>的，
        //    不从 DTO 取 —— DTO 上压根没有 source 字段。允许调用方自报来源的话，
        //    AI 就能把自家产物洗白成「人工创建」，而 manual_edited_flag 那套
        //    「人工接管过的 AI 产物不许被 AI 覆盖」的治理逻辑会整个失效，且失效得无声无息
        verify(dataModelService).createDataModel(captor.capture(), eq(ArtifactSourceEnum.AI));
        DataModelSaveReqVO vo = captor.getValue();
        assertEquals("biz_order", vo.getTableName());
        assertEquals("订单表", vo.getTableComment());
        // 🔴 profile 必须传下去，漏了的话 GLOBAL 会静默退化成 TENANT，
        // 建出来的表凭空多一个 tenant_id
        assertEquals("GLOBAL", vo.getSystemColumnProfile());
        assertEquals(true, vo.getRiskConfirmed());
        assertEquals(1, vo.getColumns().size());
        assertEquals("order_no", vo.getColumns().get(0).getColumnName());
        // 业务语义也要搬过去，否则字典配置在 api 路径上会丢
        assertEquals("order_status", vo.getColumns().get(0).getDictType());
    }

    @Test
    @DisplayName("改表：原样委托")
    void testUpdateDelegates() {
        DataModelSaveReqDTO dto = new DataModelSaveReqDTO();
        dto.setId(5L);
        dto.setTableName("biz_order");
        dataModelApi.updateDataModel(dto);

        ArgumentCaptor<DataModelSaveReqVO> captor = ArgumentCaptor.forClass(DataModelSaveReqVO.class);
        verify(dataModelService).updateDataModel(captor.capture(), eq(ArtifactSourceEnum.AI));
        assertEquals(5L, captor.getValue().getId());
    }

    /**
     * 🔴 两个基线必须活着穿过 {@code BeanUtils.toBean} 这一跳（B4-2 / B4-4）
     *
     * <p>这一跳是<b>按属性名</b>搬的，改名不会报错 —— DTO 上叫 {@code schemaFingerprint}、
     * VO 上哪天叫成 {@code fingerprint}，编译照样过、单测里其它用例照样绿，
     * 而 AI 那条改表路径从此<b>每一次</b>都被
     * {@code DATA_MODEL_RECORD_VERSION_REQUIRED} 挡死（缺 version），
     * 或者结构漂移复核静默退化成「无基线放行」（缺指纹）。
     * 前者是把 AI 改表整个废掉，后者是把那道闸悄悄拆掉 —— 两个方向都不能靠肉眼发现。
     *
     * <p>{@code confirmDropColumns} 一并钉在这里：它在 AI 路径上<b>恒为 null</b>
     * （{@code DataModelParamResolver} 抹掉的），null 也要能原样搬过去，
     * 而不是被搬成 {@code false} —— 两者的区别是「后端报『请确认后重试』」
     * 与「后端以为有人确认过『不要删』」。
     */
    @Test
    @DisplayName("🔴 改表：version / schemaFingerprint / confirmDropColumns 要穿过 DTO→VO 那一跳")
    void testUpdateCarriesGovernanceBaselines() {
        DataModelSaveReqDTO dto = new DataModelSaveReqDTO();
        dto.setId(5L);
        dto.setTableName("biz_order");
        dto.setVersion(7);
        dto.setSchemaFingerprint("a".repeat(64));

        dataModelApi.updateDataModel(dto);

        ArgumentCaptor<DataModelSaveReqVO> captor = ArgumentCaptor.forClass(DataModelSaveReqVO.class);
        verify(dataModelService).updateDataModel(captor.capture(), eq(ArtifactSourceEnum.AI));
        DataModelSaveReqVO vo = captor.getValue();
        assertEquals(7, vo.getVersion(), "version 没搬过去 ⟹ AI 改表每一次都被乐观锁绊线挡死");
        assertEquals("a".repeat(64), vo.getSchemaFingerprint(),
                "指纹没搬过去 ⟹ 结构漂移复核退化成「无基线放行」，那道闸等于拆了");
        assertNull(vo.getConfirmDropColumns(), "AI 路径上它恒为 null，不能被搬成 false");
    }

    @Test
    @DisplayName("🔴 读结构：两个基线要随结构一起给出去 —— DataModelApi 没有「按 id 读登记行」的出口")
    void testStructureCarriesGovernanceBaselines() {
        DataModelDO model = new DataModelDO();
        model.setTableName("biz_order");
        when(dataModelService.getDataModel(1L)).thenReturn(model);
        DataModelStructureRespVO structure = new DataModelStructureRespVO();
        structure.setColumns(List.of(col("id")));
        structure.setVersion(7);
        structure.setSchemaFingerprint("a".repeat(64));
        when(dataModelService.getDataModelStructure(1L)).thenReturn(structure);

        DataModelStructureRespDTO dto = dataModelApi.getStructure(1L);

        // 取不回去，Agent 那边就无从「原样回传」，改表路径整条死掉
        assertEquals(7, dto.getVersion());
        assertEquals("a".repeat(64), dto.getSchemaFingerprint());
    }

    @Test
    @DisplayName("🔴 读结构时给系统列打上 systemColumn 标记 —— 调用方据此知道哪些列必须原样回传")
    void testStructureMarksSystemColumns() {
        DataModelDO model = new DataModelDO();
        model.setTableName("biz_order");
        model.setTableComment("订单表");
        when(dataModelService.getDataModel(1L)).thenReturn(model);

        DataModelStructureRespVO structure = new DataModelStructureRespVO();
        structure.setColumns(List.of(col("id"), col("order_no"), col("tenant_id"),
                col("deleted"), col("version"), col("create_time")));
        structure.setIndexes(List.of(new TableIndexModel().setIndexName("uk_order_no")
                .setIndexType("UNIQUE").setColumnNames(List.of("order_no"))));
        when(dataModelService.getDataModelStructure(1L)).thenReturn(structure);

        DataModelStructureRespDTO dto = dataModelApi.getStructure(1L);
        assertEquals("biz_order", dto.getTableName());
        assertEquals("订单表", dto.getTableComment());

        Map<String, Boolean> flags = dto.getColumns().stream().collect(
                Collectors.toMap(DataModelStructureRespDTO.ColumnDTO::getColumnName,
                        DataModelStructureRespDTO.ColumnDTO::getSystemColumn));
        for (String system : List.of("id", "tenant_id", "deleted", "version", "create_time")) {
            assertTrue(flags.get(system), system + " 应当被标记为系统列");
        }
        assertFalse(flags.get("order_no"), "业务列不该被标记为系统列");

        assertEquals(1, dto.getIndexes().size());
        assertEquals("uk_order_no", dto.getIndexes().get(0).getIndexName());
    }

    @Test
    @DisplayName("预览 DDL：原样委托并返回 Service 的结果")
    void testPreviewDelegates() {
        when(dataModelService.previewDdl(any())).thenReturn(List.of("CREATE TABLE ..."));
        DataModelSaveReqDTO dto = new DataModelSaveReqDTO();
        dto.setTableName("biz_order");
        assertEquals(List.of("CREATE TABLE ..."), dataModelApi.previewDdl(dto));
    }

    @Test
    @DisplayName("列类型列表来自枚举，含 P0b 新增的 TINYINT")
    void testColumnTypes() {
        List<DataModelApi.ColumnTypeRespDTO> types = dataModelApi.getColumnTypeList();
        Map<String, DataModelApi.ColumnTypeRespDTO> byType = types.stream()
                .collect(Collectors.toMap(DataModelApi.ColumnTypeRespDTO::getType, Function.identity()));
        assertTrue(byType.containsKey("TINYINT"), "TINYINT 应当在受支持类型里");
        assertTrue(byType.containsKey("DECIMAL"));
        assertEquals("tinyint", byType.get("TINYINT").getMysqlType());
        assertTrue(byType.get("DECIMAL").getNeedScale(), "DECIMAL 需要精度");
        assertFalse(byType.get("TINYINT").getNeedLength(), "TINYINT 不需要长度");
    }

    /**
     * 🔴 JSON 是 2026-10-04 加进枚举的（用户裁定「改建表枚举，加上 JSON」，缺陷 D4-15 的根治）。
     *
     * <p>本条守的是**这个出口**，而不是替身：{@code DataModelCreateValidator} 判「模型写的类型收不收」、
     * {@code WorkflowDslPlannerPrompt} 印给模型的白名单、前端那个类型下拉，三处读的都是它。
     * agent 侧那两份测试里的清单是手抄的替身，抄漏了不会红 —— 真清单的形状只有这里守。
     *
     * <p>{@code description} 那句「不能做主键或索引列」是**唯一**能把这件事告诉模型的地方
     * （那条路径上没有任何工具可调），所以它被断言在这里，改描述时会先撞到这条。
     */
    @Test
    @DisplayName("🔴 列类型列表含 JSON，且它的描述把「不能做键」说清楚了")
    void testColumnTypes_json() {
        Map<String, DataModelApi.ColumnTypeRespDTO> byType = dataModelApi.getColumnTypeList().stream()
                .collect(Collectors.toMap(DataModelApi.ColumnTypeRespDTO::getType, Function.identity()));

        assertTrue(byType.containsKey("JSON"), "JSON 应当在受支持类型里（D4-15 的根治）");
        assertEquals("json", byType.get("JSON").getMysqlType());
        assertEquals("String", byType.get("JSON").getJavaType(), "JDBC 只能绑字符串，Java 侧必须是 String");
        assertFalse(byType.get("JSON").getNeedLength(), "JSON 不接受长度");
        assertFalse(byType.get("JSON").getNeedScale(), "JSON 不接受精度");
        assertTrue(byType.get("JSON").getDescription().contains("不能做主键或索引列"),
                "描述里少了这句，模型就会去建一个必被 MySQL 3152 拒掉的 JSON 索引"
                        + "（3152 不是 1170：它连前缀长度都不认，只能靠生成列）。实际："
                        + byType.get("JSON").getDescription());
    }

    /**
     * 🔴 流程回写列的判据只有这一份（B18，方案 §10.13）
     *
     * <p>{@code FormDesignApiImpl#resolveUnavailableReason} 第 6/7 条判「这张表够不够格挂流程」，
     * 而它给出的是**事后**判决：建表、存草稿、发布一路绿灯，到挂流程那一刻才把表单标成「不可用」，
     * 而那时 DDL 已经落地、回滚不掉。{@code getTableConstraints()} 存在的意义就是把同一条规则
     * <b>提前</b>交给 AI 侧的生成后校验，让它拦在建表参数上。
     *
     * <p>所以本条断言的是「两边不可能漂移」，而不是「值等于某个字面量」：
     * 列名与最小长度取自 {@code FormDesignApiImpl} 的常量，合格类型取自
     * {@code DataModelColumnTypeEnum} 的 {@code javaType}。写死 {@code "process_status"} 或
     * {@code "TINYINT"} 的话，改了那边不会红在这里，表现恰好是这条要防的那个 bug。
     *
     * <p>{@code allowedTypes} 必须是**受控类型名**而不是 MySQL 词：B17 记的就是两份类型词表
     * 各自漂移的代价（{@code varchar} 与 {@code STRING} 说的是同一列，两边都「对」，写库时错）。
     */
    @Test
    @DisplayName("🔴 flowColumns：两列的名字/长度取自就绪校验，合格类型取自列类型枚举")
    void testTableConstraints_flowColumns() {
        List<DataModelApi.FlowColumnConstraint> flowColumns =
                dataModelApi.getTableConstraints().getFlowColumns();

        assertEquals(2, flowColumns.size(), "平台保留的流程回写列就两列：" + flowColumns);
        Map<String, DataModelApi.FlowColumnConstraint> byName = flowColumns.stream()
                .collect(Collectors.toMap(DataModelApi.FlowColumnConstraint::getColumnName, Function.identity()));
        assertTrue(byName.containsKey(FormDesignApiImpl.COLUMN_PROCESS_INSTANCE_ID), byName.keySet().toString());
        assertTrue(byName.containsKey(FormDesignApiImpl.COLUMN_PROCESS_STATUS), byName.keySet().toString());

        DataModelApi.FlowColumnConstraint instanceId = byName.get(FormDesignApiImpl.COLUMN_PROCESS_INSTANCE_ID);
        assertEquals(List.of(DataModelColumnTypeEnum.STRING.getType()), instanceId.getAllowedTypes());
        assertEquals(FormDesignApiImpl.PROCESS_INSTANCE_ID_MIN_LENGTH, instanceId.getMinLength(),
                "长度下限抄的就绪校验那一份，短了平台写进去的值会被截断");

        DataModelApi.FlowColumnConstraint status = byName.get(FormDesignApiImpl.COLUMN_PROCESS_STATUS);
        assertTrue(status.getAllowedTypes().contains(DataModelColumnTypeEnum.TINYINT.getType()),
                "状态码是整数，TINYINT 必须在合格名单里（第 6 轮实录就是把它建成了 STRING）："
                        + status.getAllowedTypes());
        assertTrue(status.getAllowedTypes().contains(DataModelColumnTypeEnum.INT.getType()),
                status.getAllowedTypes().toString());
        assertTrue(status.getAllowedTypes().contains(DataModelColumnTypeEnum.LONG.getType()),
                status.getAllowedTypes().toString());
        assertFalse(status.getAllowedTypes().contains(DataModelColumnTypeEnum.STRING.getType()),
                "STRING 一进来这条约束就等于没有：" + status.getAllowedTypes());
        assertNull(status.getMinLength(), "整数型不看长度");

        // 🔴 名单里的每个名字都得是平台真收的受控类型 —— 否则 AI 照着改也改不出来
        List<String> supported = dataModelApi.getColumnTypeList().stream()
                .map(DataModelApi.ColumnTypeRespDTO::getType).toList();
        for (DataModelApi.FlowColumnConstraint constraint : flowColumns) {
            assertTrue(supported.containsAll(constraint.getAllowedTypes()),
                    constraint.getColumnName() + " 的合格类型不是受控类型名：" + constraint.getAllowedTypes());
        }
    }

    // ==================== 两个读者之间的对账 ====================
    //
    // 🔴 下面这组用例守的**不是**「薄封装」—— 恰恰相反，它们守的是「api 层允许有判断，
    // 但判断必须双向、必须共用同一份归一化、必须取全库论域」。
    // 类注释里「只做搬运」那句对这两个方法不成立（对账没有 Service 可委托），
    // 例外写在 DataModelApiImpl 的类注释里。
    //
    // 背景：「这张表存在吗」在平台上有两个读者 —— 逻辑登记（infra_data_model）与
    // 物理表（INFORMATION_SCHEMA）。台账 #93 就是它们给出相反答案而**两边都不报错**的活样本。

    @Test
    @DisplayName("🔴 keyword 必须同时按表名与表注释查（两次查询、按表名去重）")
    void testPhysicalTableKeywordMatchesNameOrComment() {
        stubRegistrations(registration(325L, "biz_device_manage", "设备管理"));
        // 表名侧命中 1 张；注释侧命中 2 张，其中 1 张与表名侧重复
        when(dataModelService.getDatabaseTableList(0L, "设备", null))
                .thenReturn(List.of(physicalRow("biz_device_manage", "设备管理信息表")));
        when(dataModelService.getDatabaseTableList(0L, null, "设备"))
                .thenReturn(List.of(physicalRow("biz_device_manage", "设备管理信息表"),
                        physicalRow("biz_equipment_repair", "设备报修单")));

        List<DataModelApi.PhysicalTableRespDTO> result = dataModelApi.getPhysicalTableList("设备");

        // 只按表名查的话这里就只有 1 条 —— 而调用方是 LLM，它拿中文词搜表，
        // 中文词只可能落在注释里（表名是拉丁的）。少给的那条会让它判定
        // 「库里没有可复用的表」，然后去建一张新表（不可逆）
        assertEquals(List.of("biz_device_manage", "biz_equipment_repair"),
                result.stream().map(DataModelApi.PhysicalTableRespDTO::getTableName).toList(),
                "两侧都要查，同一张表被两边命中时只返回一次");
        assertEquals(true, result.get(0).getRegistered());
        assertEquals(false, result.get(1).getRegistered());
    }

    @Test
    @DisplayName("keyword 为空/纯空白时只查一次全库，不做多余的第二次查询")
    void testPhysicalTableBlankKeywordQueriesWholeDatabaseOnce() {
        stubRegistrations();
        when(dataModelService.getDatabaseTableList(0L, null, null))
                .thenReturn(List.of(physicalRow("biz_order", "订单表")));

        for (String blank : new String[]{null, "", "   "}) {
            assertEquals(1, dataModelApi.getPhysicalTableList(blank).size(),
                    "keyword=[" + blank + "] 应当退化成全库查询");
        }
        verify(dataModelService, times(3)).getDatabaseTableList(0L, null, null);
        verify(dataModelService, never()).getDatabaseTableList(eq(0L), any(), notNull());
    }

    @Test
    @DisplayName("registered 标大小写不敏感，底层返回的大写列名也读得出来")
    void testPhysicalTableRegisteredFlagIsCaseInsensitive() {
        // 登记里写大写、information_schema 返回小写：MySQL 在 Windows 上表名不敏感、
        // 在 Linux 上敏感，比对必须自己归一，不能指望数据库
        stubRegistrations(registration(1L, "BIZ_ORDER", "订单"));
        when(dataModelService.getDatabaseTableList(0L, null, null))
                .thenReturn(List.of(physicalRow("biz_order", "订单表")));

        DataModelApi.PhysicalTableRespDTO dto = dataModelApi.getPhysicalTableList(null).get(0);
        assertEquals(true, dto.getRegistered());
        assertEquals("订单表", dto.getTableComment());
    }

    @Test
    @DisplayName("🔴 登记在、物理表不在 → 必须报出来，并带上 id（清理要靠它）")
    void testDanglingRegistrationIsReported() {
        stubRegistrations(registration(324L, "biz_device", "设备模型"),
                registration(325L, "biz_device_manage", "设备管理"));
        when(dataModelService.getDatabaseTableList(0L, null, null))
                .thenReturn(List.of(physicalRow("biz_device_manage", "设备管理信息表")));

        List<DataModelApi.DanglingRegistrationRespDTO> dangling =
                dataModelApi.getDanglingRegistrationList();

        assertEquals(1, dangling.size(),
                "biz_device_manage 的表在，不该报；biz_device 的表不在，必须报");
        assertEquals(324L, dangling.get(0).getId(),
                "id 是给出去让人删登记用的（DELETE /infra/data-model/delete?id=），不能省");
        assertEquals("biz_device", dangling.get(0).getTableName());
        assertEquals("设备模型", dangling.get(0).getModelName());
    }

    @Test
    @DisplayName("🔴 悬挂对账的物理表必须取全库：绝不能拿关键字过滤后的集合当全集")
    void testDanglingUsesWholeDatabase() {
        stubRegistrations(registration(1L, "biz_order", "订单"));
        when(dataModelService.getDatabaseTableList(0L, null, null))
                .thenReturn(List.of(physicalRow("biz_order", "订单表")));

        assertTrue(dataModelApi.getDanglingRegistrationList().isEmpty());

        // 实测（_evidence/probe107.txt【二】）：拿关键字并集当全集，
        // 会把 80 条真悬挂灌成 243 条，其中 163 条的物理表其实好好在着
        verify(dataModelService).getDatabaseTableList(0L, null, null);
        verify(dataModelService, never()).getDatabaseTableList(eq(0L), notNull(), any());
        verify(dataModelService, never()).getDatabaseTableList(eq(0L), any(), notNull());
    }

    @Test
    @DisplayName("tableName 为空的登记不算悬挂 —— 它不是「表不在」，是「压根没说哪张表」")
    void testDanglingSkipsBlankTableName() {
        stubRegistrations(registration(1L, null, "没有表名的"),
                registration(2L, "   ", "只有空白的"));
        when(dataModelService.getDatabaseTableList(0L, null, null)).thenReturn(List.of());

        assertTrue(dataModelApi.getDanglingRegistrationList().isEmpty());
    }

    @Test
    @DisplayName("悬挂判定大小写不敏感 —— 与 registered 标共用同一份归一化")
    void testDanglingIsCaseInsensitive() {
        stubRegistrations(registration(1L, "BIZ_ORDER", "订单"));
        when(dataModelService.getDatabaseTableList(0L, null, null))
                .thenReturn(List.of(physicalRow("biz_order", "订单表")));

        assertTrue(dataModelApi.getDanglingRegistrationList().isEmpty(),
                "登记写大写、物理表小写，是同一张表，不能报成悬挂。"
                        + "两个方向若各写一份归一化，就会一个说有一个说没有");
    }

    // ==================== 对账用例的夹具 ====================

    private void stubRegistrations(DataModelDO... models) {
        when(dataModelService.getDataModelPage(any()))
                .thenReturn(new PageResult<>(List.of(models), (long) models.length));
    }

    private static DataModelDO registration(Long id, String tableName, String modelName) {
        DataModelDO model = new DataModelDO();
        model.setId(id);
        model.setTableName(tableName);
        model.setModelName(modelName);
        model.setTableComment(modelName == null ? null : modelName + "的表");
        return model;
    }

    /**
     * 造一行 information_schema 的返回
     *
     * <p>故意用<b>大写</b>列名：那是驱动的真实形态，
     * 而 {@code DataModelApiImpl#meta} 必须两种都认
     */
    private static Map<String, Object> physicalRow(String tableName, String tableComment) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("TABLE_NAME", tableName);
        row.put("TABLE_COMMENT", tableComment);
        return row;
    }

}
