package cn.enlev.ainb.module.infra.framework.datamodel.core;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 建模规范校验的单元测试（P0b / R5 R6 R7 R8）
 *
 * <p>与 {@link SqlSafeGuardTest} 的分工：那组守的是<b>防注入</b>（别把库搞坏），
 * 这组守的是<b>建模规范</b>（别把库搞乱）。前者不可关，后者可配置关闭 ——
 * 所以每条规则都要有「关掉后放行」的用例，否则配置项形同虚设。
 *
 * @author 走进新时代
 */
public class SqlSafeGuardConventionTest {

    @Nested
    @DisplayName("R6 snake_case 命名")
    class SnakeCase {

        @Test
        @DisplayName("规范名字：通过")
        void testValid() {
            assertTrue(SqlSafeGuard.isSnakeCase("biz_order"));
            assertTrue(SqlSafeGuard.isSnakeCase("biz_order_item_2"));
            assertTrue(SqlSafeGuard.isSnakeCase("id"));
            assertTrue(SqlSafeGuard.isSnakeCase("tenant_id"));
        }

        @Test
        @DisplayName("驼峰：拒绝 —— Linux 下 lower_case_table_names=0 时表名大小写敏感")
        void testCamelCaseRejected() {
            assertFalse(SqlSafeGuard.isSnakeCase("myTableName"));
            assertFalse(SqlSafeGuard.isSnakeCase("BizOrder"));
            assertFalse(SqlSafeGuard.isSnakeCase("biz_Order"));
        }

        @Test
        @DisplayName("下划线开头或结尾：拒绝")
        void testUnderscoreEdgeRejected() {
            assertFalse(SqlSafeGuard.isSnakeCase("_biz_order"));
            assertFalse(SqlSafeGuard.isSnakeCase("biz_order_"));
        }

        @Test
        @DisplayName("数字开头、单字符、空：拒绝")
        void testOtherInvalid() {
            assertFalse(SqlSafeGuard.isSnakeCase("1biz"));
            assertFalse(SqlSafeGuard.isSnakeCase("a"));
            assertFalse(SqlSafeGuard.isSnakeCase(""));
            assertFalse(SqlSafeGuard.isSnakeCase(null));
        }

        @Test
        @DisplayName("表名不规范时抛出，且错误信息带上表名")
        void testCheckTableName() {
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> SqlSafeGuard.checkTableNameSnakeCase("myTable", true));
            assertTrue(ex.getMessage().contains("myTable"), ex.getMessage());
        }

        @Test
        @DisplayName("🔴 关掉开关后放行 —— 否则存量库根本接不进来")
        void testDisabled() {
            assertDoesNotThrow(() -> SqlSafeGuard.checkTableNameSnakeCase("myTable", false));
            assertDoesNotThrow(() -> SqlSafeGuard.checkColumnNameSnakeCase("myColumn", false));
        }
    }

    @Nested
    @DisplayName("R5 业务表前缀")
    class TablePrefix {

        @Test
        @DisplayName("带前缀：通过；大小写不敏感")
        void testValid() {
            assertDoesNotThrow(() -> SqlSafeGuard.checkBusinessTablePrefix("biz_order", true, "biz_"));
            assertDoesNotThrow(() -> SqlSafeGuard.checkBusinessTablePrefix("BIZ_ORDER", true, "biz_"));
        }

        @Test
        @DisplayName("🔴 不带前缀：拒绝 —— abc / t1 / yonghubiao 这类名字此前全都合法")
        void testMissingPrefixRejected() {
            for (String name : new String[]{"abc", "t1", "yonghubiao", "order_main"}) {
                ServiceException ex = assertThrows(ServiceException.class,
                        () -> SqlSafeGuard.checkBusinessTablePrefix(name, true, "biz_"),
                        "应当拒绝：" + name);
                assertTrue(ex.getMessage().contains("biz_"), ex.getMessage());
            }
        }

        @Test
        @DisplayName("关掉开关、或前缀配成空：放行")
        void testDisabled() {
            assertDoesNotThrow(() -> SqlSafeGuard.checkBusinessTablePrefix("abc", false, "biz_"));
            assertDoesNotThrow(() -> SqlSafeGuard.checkBusinessTablePrefix("abc", true, ""));
            assertDoesNotThrow(() -> SqlSafeGuard.checkBusinessTablePrefix("abc", true, null));
        }

        @Test
        @DisplayName("前缀白名单与框架表黑名单是两道独立关卡")
        void testIndependentFromBlacklist() {
            // system_users 撞黑名单，但它也不带 biz_ 前缀 —— 两道各自都能拦住它
            assertThrows(ServiceException.class,
                    () -> SqlSafeGuard.checkBusinessTablePrefix("system_users", true, "biz_"));
            assertThrows(ServiceException.class,
                    () -> SqlSafeGuard.checkNotFrameworkTable("system_users"));
            // 反过来：biz_ 开头的表过了白名单，黑名单也放它过
            assertDoesNotThrow(() -> SqlSafeGuard.checkBusinessTablePrefix("biz_order", true, "biz_"));
            assertDoesNotThrow(() -> SqlSafeGuard.checkNotFrameworkTable("biz_order"));
        }
    }

    @Nested
    @DisplayName("R7 索引名前缀")
    class IndexPrefix {

        @Test
        @DisplayName("uk_ 配唯一索引、idx_ 配普通索引：通过")
        void testValid() {
            assertDoesNotThrow(() -> SqlSafeGuard.checkIndexNamePrefix("uk_order_no", true, true));
            assertDoesNotThrow(() -> SqlSafeGuard.checkIndexNamePrefix("idx_create_time", false, true));
        }

        @Test
        @DisplayName("🔴 前缀与索引类型不匹配：拒绝")
        void testMismatchRejected() {
            // 唯一索引用了 idx_
            assertThrows(ServiceException.class,
                    () -> SqlSafeGuard.checkIndexNamePrefix("idx_order_no", true, true));
            // 普通索引用了 uk_
            assertThrows(ServiceException.class,
                    () -> SqlSafeGuard.checkIndexNamePrefix("uk_create_time", false, true));
            // 完全没前缀
            assertThrows(ServiceException.class,
                    () -> SqlSafeGuard.checkIndexNamePrefix("order_no_index", false, true));
        }

        @Test
        @DisplayName("关掉开关：放行")
        void testDisabled() {
            assertDoesNotThrow(() -> SqlSafeGuard.checkIndexNamePrefix("whatever", true, false));
        }
    }

    @Nested
    @DisplayName("R8 注释质量下限")
    class CommentQuality {

        @Test
        @DisplayName("正常注释：通过")
        void testValid() {
            assertTrue(SqlSafeGuard.isMeaningfulComment("订单号", "order_no"));
            assertTrue(SqlSafeGuard.isMeaningfulComment("Order No", "order_no"));
            assertTrue(SqlSafeGuard.isMeaningfulComment("金额（元）", "amount"));
        }

        @Test
        @DisplayName("🔴 空、纯空格：拒绝 —— 表注释此前走 @NotEmpty，一个空格就能过")
        void testBlankRejected() {
            assertFalse(SqlSafeGuard.isMeaningfulComment(null, "order_no"));
            assertFalse(SqlSafeGuard.isMeaningfulComment("", "order_no"));
            assertFalse(SqlSafeGuard.isMeaningfulComment("   ", "order_no"));
        }

        @Test
        @DisplayName("🔴 纯数字、纯标点、单字符：拒绝 —— 列注释此前只拦 isBlank，\"1\" 能过")
        void testMeaninglessRejected() {
            assertFalse(SqlSafeGuard.isMeaningfulComment("1", "order_no"));
            assertFalse(SqlSafeGuard.isMeaningfulComment("123", "order_no"));
            assertFalse(SqlSafeGuard.isMeaningfulComment("--", "order_no"));
            assertFalse(SqlSafeGuard.isMeaningfulComment("、。！", "order_no"));
            assertFalse(SqlSafeGuard.isMeaningfulComment("x", "order_no"));
        }

        @Test
        @DisplayName("🔴 注释与字段名相同：拒绝 —— 等于没写")
        void testSameAsNameRejected() {
            assertFalse(SqlSafeGuard.isMeaningfulComment("order_no", "order_no"));
            assertFalse(SqlSafeGuard.isMeaningfulComment("ORDER_NO", "order_no"));
            assertFalse(SqlSafeGuard.isMeaningfulComment("  order_no  ", "order_no"));
        }

        @Test
        @DisplayName("不传字段名时跳过同名判断")
        void testNullName() {
            assertTrue(SqlSafeGuard.isMeaningfulComment("order_no", null));
        }
    }
}
