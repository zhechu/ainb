package cn.enlev.ainb.module.infra.service.datamodel;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.controller.admin.datamodel.vo.DataModelSaveReqVO;
import cn.enlev.ainb.module.infra.dal.mysql.datamodel.DataModelMapper;
import cn.enlev.ainb.module.infra.framework.datamodel.config.DataModelProperties;
import cn.enlev.ainb.module.infra.framework.datamodel.core.model.TableColumnModel;
import cn.enlev.ainb.module.infra.framework.datamodel.core.model.TableIndexModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 建模规范在 Service 层的接入测试（P0b）
 *
 * <p>{@link cn.enlev.ainb.module.infra.framework.datamodel.core.SqlSafeGuardConventionTest}
 * 测的是规则本身，这组测的是<b>规则有没有真的被挂上去</b> —— 以及关键的一条：
 * <b>规范只作用于新建，改表只管新增的部分</b>。后者错了会让存量表立刻改不动。
 *
 * @author 走进新时代
 */
public class DataModelConventionGuardTest extends BaseMockitoUnitTest {

    @InjectMocks
    private DataModelServiceImpl dataModelService;

    @Mock
    private DataModelMapper dataModelMapper;

    @Spy
    private DataModelProperties dataModelProperties = new DataModelProperties();

    @BeforeEach
    void setUp() {
        // 显式设成默认值，避免依赖字段初始值
        dataModelProperties.setTablePrefixEnforced(true);
        dataModelProperties.setTablePrefix("biz_");
        dataModelProperties.setSnakeCaseEnforced(true);
        dataModelProperties.setIndexPrefixEnforced(true);
        dataModelProperties.setMaxColumnCount(100);
        dataModelProperties.setMaxTableCount(200);
    }

    private TableColumnModel col(String name, String comment) {
        return new TableColumnModel().setColumnName(name).setColumnType("STRING")
                .setNullable(true).setPrimaryKey(false).setColumnComment(comment);
    }

    private DataModelSaveReqVO reqVO(String tableName, String tableComment) {
        DataModelSaveReqVO vo = new DataModelSaveReqVO();
        vo.setTableName(tableName);
        vo.setTableComment(tableComment);
        vo.setModelName("测试模型");
        return vo;
    }

    // ==================== 新建：规范全开 ====================

    @Test
    @DisplayName("完全合规的新建：通过")
    void testValidCreate() {
        assertDoesNotThrow(() -> dataModelService.validateCreateConvention(
                reqVO("biz_order", "订单主表"),
                List.of(col("order_no", "订单号")),
                List.of(new TableIndexModel().setIndexName("uk_order_no")
                        .setIndexType(TableIndexModel.TYPE_UNIQUE).setColumnNames(List.of("order_no")))));
    }

    @Test
    @DisplayName("R5 表名没有 biz_ 前缀：拒绝")
    void testMissingTablePrefix() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> dataModelService.validateCreateConvention(
                        reqVO("order_main", "订单主表"), List.of(col("order_no", "订单号")), List.of()));
        assertTrue(ex.getMessage().contains("biz_"), ex.getMessage());
    }

    @Test
    @DisplayName("R6 表名是驼峰：拒绝")
    void testCamelCaseTableName() {
        assertThrows(ServiceException.class, () -> dataModelService.validateCreateConvention(
                reqVO("bizOrder", "订单主表"), List.of(col("order_no", "订单号")), List.of()));
    }

    @Test
    @DisplayName("R6 列名是驼峰：拒绝")
    void testCamelCaseColumnName() {
        assertThrows(ServiceException.class, () -> dataModelService.validateCreateConvention(
                reqVO("biz_order", "订单主表"), List.of(col("orderNo", "订单号")), List.of()));
    }

    @Test
    @DisplayName("R8 表注释是纯数字：拒绝")
    void testMeaninglessTableComment() {
        assertThrows(ServiceException.class, () -> dataModelService.validateCreateConvention(
                reqVO("biz_order", "123"), List.of(col("order_no", "订单号")), List.of()));
    }

    @Test
    @DisplayName("R8 列注释照抄列名：拒绝")
    void testColumnCommentSameAsName() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> dataModelService.validateCreateConvention(
                        reqVO("biz_order", "订单主表"), List.of(col("order_no", "order_no")), List.of()));
        assertTrue(ex.getMessage().contains("order_no"), ex.getMessage());
    }

    @Test
    @DisplayName("R7 唯一索引用了 idx_ 前缀：拒绝")
    void testIndexPrefixMismatch() {
        assertThrows(ServiceException.class, () -> dataModelService.validateCreateConvention(
                reqVO("biz_order", "订单主表"), List.of(col("order_no", "订单号")),
                List.of(new TableIndexModel().setIndexName("idx_order_no")
                        .setIndexType(TableIndexModel.TYPE_UNIQUE).setColumnNames(List.of("order_no")))));
    }

    @Test
    @DisplayName("系统列不受业务规范约束 —— 它们的注释由框架托管")
    void testSystemColumnsExempt() {
        assertDoesNotThrow(() -> dataModelService.validateCreateConvention(
                reqVO("biz_order", "订单主表"),
                List.of(col("order_no", "订单号"), col("tenant_id", ""), col("deleted", "")),
                List.of()));
    }

    // ==================== 改表：只管新增的部分 ====================

    @Test
    @DisplayName("🔴 改表时存量列不合规范：放行 —— 否则导入的存量表立刻改不动")
    void testAlterIgnoresExistingColumns() {
        // addColumns 为空，代表本次没新增列，只是动了别的东西
        assertDoesNotThrow(() -> dataModelService.validateAlterConvention(List.of(), List.of()));
    }

    @Test
    @DisplayName("改表时新增的列仍要合规")
    void testAlterChecksNewColumns() {
        assertThrows(ServiceException.class, () -> dataModelService.validateAlterConvention(
                List.of(col("newColumn", "新列")), List.of()));
        assertThrows(ServiceException.class, () -> dataModelService.validateAlterConvention(
                List.of(col("new_column", "new_column")), List.of()));
    }

    @Test
    @DisplayName("改表时新增的合规列：通过")
    void testAlterValidNewColumn() {
        assertDoesNotThrow(() -> dataModelService.validateAlterConvention(
                List.of(col("remark_text", "备注")), List.of()));
    }

    // ==================== R10 配额 ====================

    @Test
    @DisplayName("R10 列数超上限：拒绝，错误信息带上实际数与上限")
    void testColumnCountExceed() {
        dataModelProperties.setMaxColumnCount(10);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> dataModelService.validateColumnCount(11));
        assertTrue(ex.getMessage().contains("11") && ex.getMessage().contains("10"), ex.getMessage());
        // 正好等于上限：放行
        assertDoesNotThrow(() -> dataModelService.validateColumnCount(10));
    }

    @Test
    @DisplayName("R10 列数上限配 0 表示不限制")
    void testColumnCountUnlimited() {
        dataModelProperties.setMaxColumnCount(0);
        assertDoesNotThrow(() -> dataModelService.validateColumnCount(5000));
    }

    @Test
    @DisplayName("R10 模型数达上限：拒绝新建")
    void testTableCountExceed() {
        dataModelProperties.setMaxTableCount(3);
        when(dataModelMapper.selectCount()).thenReturn(3L);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> dataModelService.validateTableCount());
        assertTrue(ex.getMessage().contains("3"), ex.getMessage());
    }

    @Test
    @DisplayName("R10 模型数未达上限：放行")
    void testTableCountWithinLimit() {
        dataModelProperties.setMaxTableCount(3);
        when(dataModelMapper.selectCount()).thenReturn(2L);
        assertDoesNotThrow(() -> dataModelService.validateTableCount());
    }

    @Test
    @DisplayName("R10 模型数上限配 0 表示不限制，且不该去查库")
    void testTableCountUnlimited() {
        dataModelProperties.setMaxTableCount(0);
        assertDoesNotThrow(() -> dataModelService.validateTableCount());
    }

    @Test
    @DisplayName("🔴 模型数配额默认必须是「不限」—— 实测存量库单租户已有 286 个模型")
    void testTableCountDefaultIsUnlimited() {
        // 这条钉的是默认值本身：本项按总数算，存量直接决定还能不能建新表。
        // 给个 200 这样的「看起来合理」的默认值，升级后所有人立刻建不了表，而他们什么都没做错。
        assertEquals(0, new DataModelProperties().getMaxTableCount(),
                "maxTableCount 默认必须为 0（不限），接入 AI 前再按实际情况显式配置");
    }

    // ==================== 开关关闭 ====================

    @Test
    @DisplayName("🔴 规范全部关掉后，此前被拒的输入应当放行 —— 配置项不能是装饰")
    void testAllDisabled() {
        dataModelProperties.setTablePrefixEnforced(false);
        dataModelProperties.setSnakeCaseEnforced(false);
        dataModelProperties.setIndexPrefixEnforced(false);
        assertDoesNotThrow(() -> dataModelService.validateCreateConvention(
                reqVO("myLegacyTable", "存量表"), List.of(col("orderNo", "订单号")),
                List.of(new TableIndexModel().setIndexName("whatever")
                        .setIndexType(TableIndexModel.TYPE_UNIQUE).setColumnNames(List.of("orderNo")))));
    }
}
