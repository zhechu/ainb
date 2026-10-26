package cn.enlev.ainb.module.system.framework.permission.core.dataset;

import cn.enlev.ainb.framework.common.biz.system.permission.annotation.PermissionDataset;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import com.baomidou.mybatisplus.annotation.TableName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 原生数据集声明的扫描（S7a）
 *
 * <h3>这组用例守的是「声明能不能被看见」</h3>
 *
 * 扫描漏掉一个声明的表现是：那张表<b>没有</b>被注册进行级规则的表名集合，
 * 于是给它配的行级规则一次都不会被调用 —— 查询照常返回，只是没有过滤。
 * 没有报错、没有日志、界面完全正常。
 *
 * @author 走进新时代
 */
public class NativeDatasetScannerTest extends BaseMockitoUnitTest {

    /**
     * 完整声明：表名来自 {@code table}
     */
    @PermissionDataset(value = "test:full", name = "完整声明", table = "test_full_table",
            owner = "creator", dept = "dept_id", status = "status",
            fields = {"mobile", "id_card"}, labels = {"mobile=手机号", "bad entry"}, sensitive = true)
    public static class FullDeclared {
    }

    /**
     * 表名留空：应当回落到 {@code @TableName}
     */
    @PermissionDataset(value = "test:from-table-name", owner = "creator")
    @TableName("test_from_table_name")
    public static class TableNameFallback {
    }

    /**
     * 既没有 table 也没有 @TableName：应当被跳过（且 ERROR 留痕）
     */
    @PermissionDataset(value = "test:no-table")
    public static class NoTable {
    }

    /**
     * 主键列不叫 id 的表：显式写出来
     */
    @PermissionDataset(value = "test:custom-pk", table = "test_custom_pk", bizKey = "order_no")
    public static class CustomBizKey {
    }

    private final NativeDatasetScanner scanner = new NativeDatasetScanner();

    @Test
    @DisplayName("扫得到完整声明，槽位与字段原样带出")
    void testScanFullDeclaration() {
        NativeDatasetScanner.NativeDatasetDescriptor descriptor = find("test:full");

        assertNotNull(descriptor);
        assertEquals("完整声明", descriptor.name());
        assertEquals("test_full_table", descriptor.table());
        // ⚠️ bizKey 有默认值 id，所以它总在 —— 这是刻意的：
        // 少了它，派生可见性与记录共享在这张表上会【恒假】（一行都看不到），
        // 比不配还糟（见 PermissionSlotEnum.BIZ_KEY）
        assertEquals(Map.of("owner", "creator", "dept", "dept_id", "status", "status",
                "bizKey", "id"), descriptor.slots());
        assertEquals(List.of("mobile", "id_card"), descriptor.fields());
        assertTrue(descriptor.sensitive());
        // labels：合法条目进 Map，格式非法的（没有 =）被忽略 —— 忽略时必须留 ERROR 日志
        assertEquals(Map.of("mobile", "手机号"), descriptor.labels());
    }

    @Test
    @DisplayName("table 留空时回落到 @TableName —— 让声明贴着定义处，不必把表名抄两遍")
    void testTableNameFallback() {
        NativeDatasetScanner.NativeDatasetDescriptor descriptor = find("test:from-table-name");

        assertNotNull(descriptor);
        assertEquals("test_from_table_name", descriptor.table());
        // name 留空时取权限串，不留空字符串 —— 授权页上显示空白比显示权限串更难认
        assertEquals("test:from-table-name", descriptor.name());
    }

    @Test
    @DisplayName("🔴 没有表名的声明必须被跳过 —— 登记出一个没有表的数据集，行级规则永远匹配不上它")
    void testNoTableSkipped() {
        assertNull(find("test:no-table"));
    }

    @Test
    @DisplayName("bizKey 默认是 id，主键不叫 id 的表可以显式覆盖")
    void testBizKeyDefaultAndOverride() {
        assertEquals("id", find("test:from-table-name").slots().get("bizKey"));
        assertEquals("order_no", find("test:custom-pk").slots().get("bizKey"));
    }

    @Test
    @DisplayName("⚠️ 真实的接入样例也要扫得到 —— 否则 system_users 的行级权限是关着的")
    void testRealAdoptionScanned() {
        NativeDatasetScanner.NativeDatasetDescriptor descriptor = find("system:user");

        assertNotNull(descriptor, "AdminUserDO 上的 @PermissionDataset 没被扫到");
        assertEquals("system_users", descriptor.table());
        assertEquals("id", descriptor.slots().get("owner"));
        assertEquals("dept_id", descriptor.slots().get("dept"));
        assertEquals("id", descriptor.slots().get("bizKey"));
        // 资源名中文化靠这份 labels（生成「用户:字段:手机号」），丢了会退回英文字段名
        assertEquals("手机号", descriptor.labels().get("mobile"));
    }

    private NativeDatasetScanner.NativeDatasetDescriptor find(String permission) {
        return scanner.scan().stream()
                .filter(item -> permission.equals(item.permission()))
                .findFirst().orElse(null);
    }

}
