package cn.enlev.ainb.module.infra.framework.formdesign.core.sql;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.module.infra.enums.formdesign.OperatorEnum;
import cn.enlev.ainb.module.infra.service.db.DatabaseTableService;
import com.baomidou.mybatisplus.generator.config.po.TableField;
import com.baomidou.mybatisplus.generator.config.po.TableInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SqlIdentifierGuard} 的单元测试
 *
 * 守卫是所有动态 SQL 的唯一入口，它的每一条校验都是一道安全边界。
 * 这些用例直接对应「防 SQL 注入」的设计承诺，任何一条失效都意味着注入风险。
 *
 * 用手工 Mockito 注入而非启动 Spring 容器：本类只依赖一个 Service，
 * 起容器纯属浪费，且会让测试变慢到没人愿意跑。
 *
 * @author 走进新时代
 */
class SqlIdentifierGuardTest {

    private static final Long DS_MAIN = 0L;

    private SqlIdentifierGuard guard;
    private DatabaseTableService databaseTableService;

    @BeforeEach
    void setUp() {
        guard = new SqlIdentifierGuard();
        databaseTableService = mock(DatabaseTableService.class);
        // guard 现在读的是 TableMetadataCache。这里注入一个【真实的】缓存包住 mock，
        // 用例的 stub 方式因此完全不用改；每个用例一个新缓存，避免跨用例串状态
        ReflectionTestUtils.setField(guard, "metadataCache",
                SqlBuilderTestFactory.metadataCache(databaseTableService));
    }

    /**
     * 让 mock 返回一张含指定列的表
     */
    private void mockTable(String tableName, String... columns) {
        TableInfo tableInfo = mock(TableInfo.class);
        List<TableField> fields = java.util.Arrays.stream(columns)
                .map(col -> {
                    TableField field = mock(TableField.class);
                    when(field.getName()).thenReturn(col);
                    return field;
                })
                .toList();
        when(tableInfo.getFields()).thenReturn(fields);
        when(databaseTableService.getTable(eq(DS_MAIN), eq(tableName))).thenReturn(tableInfo);
    }

    // ==================== 表名校验 ====================

    @Test
    @DisplayName("合法表名且在白名单内且真实存在：通过")
    void testValidTable() {
        mockTable("biz_customer", "id", "name");
        String result = guard.validateTable(DS_MAIN, "biz_customer", Set.of("biz_customer"), false);
        assertEquals("biz_customer", result);
    }

    @Test
    @DisplayName("⚠️ 表名含注入片段：被正则拦截")
    void testTableNameInjectionBlocked() {
        Set<String> whitelist = Set.of("biz_customer");
        // 典型注入尝试
        assertThrows(ServiceException.class,
                () -> guard.validateTable(DS_MAIN, "biz_customer; DROP TABLE users", whitelist, false));
        assertThrows(ServiceException.class,
                () -> guard.validateTable(DS_MAIN, "biz_customer'--", whitelist, false));
        assertThrows(ServiceException.class,
                () -> guard.validateTable(DS_MAIN, "biz customer", whitelist, false));
    }

    @Test
    @DisplayName("⚠️ 表名不在白名单：拒绝（即使数据库里真实存在）")
    void testTableNotInWhitelist() {
        mockTable("system_users", "id");
        // system_users 真实存在，但没在配置里声明过
        assertThrows(ServiceException.class,
                () -> guard.validateTable(DS_MAIN, "system_users", Set.of("biz_customer"), false));
    }

    @Test
    @DisplayName("⚠️ 白名单为 null：一律拒绝")
    void testNullWhitelistRejects() {
        assertThrows(ServiceException.class,
                () -> guard.validateTable(DS_MAIN, "biz_customer", null, false));
    }

    @Test
    @DisplayName("⚠️ 框架表写操作：硬拦截")
    void testFrameworkTableWriteDenied() {
        mockTable("system_menu", "id");
        // 即使被加进了白名单，写操作也必须拒绝
        assertThrows(ServiceException.class,
                () -> guard.validateTable(DS_MAIN, "system_menu", Set.of("system_menu"), true),
                "system_ 前缀表不允许写");

        mockTable("infra_config", "id");
        assertThrows(ServiceException.class,
                () -> guard.validateTable(DS_MAIN, "infra_config", Set.of("infra_config"), true));

        mockTable("bpm_form", "id");
        assertThrows(ServiceException.class,
                () -> guard.validateTable(DS_MAIN, "bpm_form", Set.of("bpm_form"), true));
    }

    @Test
    @DisplayName("框架表读操作：允许（做报表可能需要读系统表）")
    void testFrameworkTableReadAllowed() {
        mockTable("system_users", "id", "nickname");
        assertDoesNotThrow(
                () -> guard.validateTable(DS_MAIN, "system_users", Set.of("system_users"), false));
    }

    @Test
    @DisplayName("⚠️ 白名单通过但数据库里不存在：拒绝（DBA 删表或改名的场景）")
    void testTableNotExistsInDb() {
        when(databaseTableService.getTable(eq(DS_MAIN), eq("biz_gone"))).thenReturn(null);
        assertThrows(ServiceException.class,
                () -> guard.validateTable(DS_MAIN, "biz_gone", Set.of("biz_gone"), false));
    }

    // ==================== 列名校验 ====================

    @Test
    @DisplayName("合法列名且在白名单内且真实存在：通过")
    void testValidColumn() {
        mockTable("biz_customer", "id", "name", "level");
        String result = guard.validateColumn(DS_MAIN, "biz_customer", "name",
                Set.of("id", "name", "level"));
        assertEquals("name", result);
    }

    @Test
    @DisplayName("⚠️ 列名含注入片段：被正则拦截")
    void testColumnNameInjectionBlocked() {
        Set<String> whitelist = Set.of("name");
        assertThrows(ServiceException.class,
                () -> guard.validateColumn(DS_MAIN, "biz_customer", "name = 1 OR 1=1", whitelist));
        assertThrows(ServiceException.class,
                () -> guard.validateColumn(DS_MAIN, "biz_customer", "1=1", whitelist));
    }

    @Test
    @DisplayName("⚠️ 列名不在白名单：拒绝（防止读取未声明的敏感列）")
    void testColumnNotInWhitelist() {
        mockTable("biz_customer", "id", "name", "secret_key");
        // secret_key 数据库里有，但没在配置里声明
        assertThrows(ServiceException.class,
                () -> guard.validateColumn(DS_MAIN, "biz_customer", "secret_key",
                        Set.of("id", "name")));
    }

    @Test
    @DisplayName("⚠️ 白名单通过但列在数据库里不存在：拒绝（DBA 改列名的场景）")
    void testColumnNotExistsInDb() {
        mockTable("biz_customer", "id", "name");
        assertThrows(ServiceException.class,
                () -> guard.validateColumn(DS_MAIN, "biz_customer", "old_column",
                        Set.of("id", "name", "old_column")));
    }

    // ==================== 排序方向 ====================

    @Test
    @DisplayName("排序方向 ASC / DESC：通过并归一化为大写")
    void testValidSortDirection() {
        assertEquals("ASC", guard.validateSortDirection("asc"));
        assertEquals("DESC", guard.validateSortDirection("desc"));
        assertEquals("ASC", guard.validateSortDirection("ASC"));
        assertEquals("DESC", guard.validateSortDirection(" DESC "));
    }

    @Test
    @DisplayName("排序方向为空：默认 ASC")
    void testBlankSortDirectionDefaultsAsc() {
        assertEquals("ASC", guard.validateSortDirection(null));
        assertEquals("ASC", guard.validateSortDirection(""));
    }

    @Test
    @DisplayName("⚠️ 排序方向含注入：拒绝")
    void testSortDirectionInjectionBlocked() {
        assertThrows(ServiceException.class,
                () -> guard.validateSortDirection("ASC; DROP TABLE users"));
        assertThrows(ServiceException.class,
                () -> guard.validateSortDirection("ASC, (SELECT 1)"));
    }

    // ==================== 操作符 ====================

    @Test
    @DisplayName("合法操作符：正确映射为枚举")
    void testValidOperator() {
        assertEquals(OperatorEnum.EQ, guard.validateOperator("EQ"));
        assertEquals(OperatorEnum.LIKE, guard.validateOperator("like"));
        assertEquals(OperatorEnum.BETWEEN, guard.validateOperator("BETWEEN"));
        assertEquals(OperatorEnum.IS_NULL, guard.validateOperator("IS_NULL"));
    }

    @Test
    @DisplayName("操作符为空：默认 EQ")
    void testBlankOperatorDefaultsEq() {
        assertEquals(OperatorEnum.EQ, guard.validateOperator(null));
        assertEquals(OperatorEnum.EQ, guard.validateOperator(""));
    }

    @Test
    @DisplayName("⚠️ 未知操作符：拒绝（不能让用户塞任意 SQL 片段）")
    void testUnknownOperatorBlocked() {
        assertThrows(ServiceException.class, () -> guard.validateOperator("OR 1=1"));
        assertThrows(ServiceException.class, () -> guard.validateOperator("UNION SELECT"));
        assertThrows(ServiceException.class, () -> guard.validateOperator("NOT_AN_OPERATOR"));
    }

    // ==================== 其他 ====================

    @Test
    @DisplayName("标识符包裹：用反引号")
    void testQuote() {
        assertEquals("`name`", guard.quote("name"));
        // 与关键字同名的列必须能正常包裹
        assertEquals("`order`", guard.quote("order"));
    }

    @Test
    @DisplayName("超长标识符：被正则拦截（上限 64 字符）")
    void testOverlongIdentifierBlocked() {
        String longName = "a".repeat(65);
        assertThrows(ServiceException.class,
                () -> guard.validateTable(DS_MAIN, longName, Set.of(longName), false));
    }

}
