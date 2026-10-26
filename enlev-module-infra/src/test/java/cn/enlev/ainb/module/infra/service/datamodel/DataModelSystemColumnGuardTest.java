package cn.enlev.ainb.module.infra.service.datamodel;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.framework.datamodel.core.TableDiffer;
import cn.enlev.ainb.module.infra.framework.datamodel.core.model.TableColumnModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 改表时系统列保护的单元测试
 *
 * 🔴 这组用例守的是本模块最危险的一种静默数据破坏。
 *
 * <p>改表是<b>全量同步语义</b>：真实表里有、而提交的 columns 里没有的列，一律被推断为待删除。
 * 而改表路径<b>不走</b> SystemColumnInjector（只有建表走），于是调用方按
 * 「我只想加一个字段」的直觉提交业务列，7 个系统列就全部落进 dropColumns。
 *
 * <p>此前只有 id 被主键校验挡住，其余六列靠有损校验 —— 但它在表为空时直接放行。
 * 也就是说<b>空表改结构会静默删掉 tenant_id 与 deleted</b>：
 * 前者一丢跨租户泄漏，后者一丢逻辑删除变物理删除。不报错、不可回滚。
 *
 * @author 走进新时代
 */
public class DataModelSystemColumnGuardTest extends BaseMockitoUnitTest {

    @InjectMocks
    private DataModelServiceImpl dataModelService;

    private TableColumnModel col(String name) {
        return new TableColumnModel().setColumnName(name).setColumnType("STRING")
                .setNullable(true).setPrimaryKey(false).setAutoIncrement(false)
                .setColumnComment("").setDefaultValue(null);
    }

    /** 模拟「只提交业务列」：真实表有系统列，目标结构里没有 */
    private TableDiffer.Diff diffOmitting(String... currentOnly) {
        List<TableColumnModel> target = List.of(col("order_no"));
        List<TableColumnModel> current = new java.util.ArrayList<>();
        current.add(col("order_no"));
        for (String name : currentOnly) {
            current.add(col(name));
        }
        return TableDiffer.diff(target, current);
    }

    // ==================== 必须拦住 ====================

    @Test
    @DisplayName("🔴 漏传 tenant_id：拒绝 —— 丢了它跨租户数据泄漏")
    void testTenantIdDropRejected() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> dataModelService.validateSystemColumnsNotDropped(diffOmitting("tenant_id")));
        assertTrue(ex.getMessage().contains("tenant_id"), ex.getMessage());
    }

    @Test
    @DisplayName("🔴 漏传 deleted：拒绝 —— 丢了它逻辑删除变物理删除")
    void testDeletedDropRejected() {
        assertThrows(ServiceException.class,
                () -> dataModelService.validateSystemColumnsNotDropped(diffOmitting("deleted")));
    }

    @Test
    @DisplayName("🔴 漏传审计字段：同样拒绝")
    void testAuditColumnsDropRejected() {
        assertThrows(ServiceException.class, () -> dataModelService.validateSystemColumnsNotDropped(
                diffOmitting("creator", "create_time", "updater", "update_time")));
    }

    @Test
    @DisplayName("🔴 一次漏传多个：错误信息要把它们全列出来，而不是只报第一个")
    void testAllSystemColumnsListed() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> dataModelService.validateSystemColumnsNotDropped(
                        diffOmitting("tenant_id", "deleted", "creator")));
        String msg = ex.getMessage();
        assertTrue(msg.contains("tenant_id") && msg.contains("deleted") && msg.contains("creator"),
                "应一次列全，实际：" + msg);
    }

    // ==================== 应当放过 ====================

    @Test
    @DisplayName("删业务列：放过 —— 那是用户的自由，由有损校验去要确认")
    void testBusinessColumnDropPasses() {
        List<TableColumnModel> target = List.of(col("order_no"));
        List<TableColumnModel> current = List.of(col("order_no"), col("obsolete_remark"));
        assertDoesNotThrow(() ->
                dataModelService.validateSystemColumnsNotDropped(TableDiffer.diff(target, current)));
    }

    @Test
    @DisplayName("按正确姿势整体回传（含系统列）：放过")
    void testFullColumnSetPasses() {
        List<TableColumnModel> full = List.of(col("order_no"), col("tenant_id"), col("deleted"),
                col("creator"), col("create_time"), col("updater"), col("update_time"));
        assertDoesNotThrow(() ->
                dataModelService.validateSystemColumnsNotDropped(TableDiffer.diff(full, full)));
    }

}
