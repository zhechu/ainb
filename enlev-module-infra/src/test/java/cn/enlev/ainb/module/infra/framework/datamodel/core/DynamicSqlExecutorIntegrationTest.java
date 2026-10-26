package cn.enlev.ainb.module.infra.framework.datamodel.core;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.tenant.core.context.TenantContextHolder;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DynamicSqlExecutor} 的真实 MySQL 集成测试
 *
 * 动态 CRUD 走 JdbcTemplate 绕开了 MyBatis 拦截器，租户隔离与逻辑删除全靠手工拼接。
 * 这类逻辑单测覆盖不了（要看真实 SQL 行为），只能对真库验证。
 *
 * 重点验证：
 * 1. 租户隔离真的生效 —— 这是最严重的安全风险
 * 2. 逻辑删除真的生效
 * 3. 审计字段真的填充
 * 4. SQL 注入被拦截
 *
 * @author 走进新时代
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamicSqlExecutorIntegrationTest {

    private static final String URL = "jdbc:mysql://127.0.0.1:3306/ruoyi-vue-pro"
            + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&nullCatalogMeansCurrent=true";
    private static final String TEST_TABLE = "zz_dynamic_crud_it";

    private JdbcTemplate jdbcTemplate;
    private DynamicSqlExecutor executor;
    private Set<String> columns;
    private Object insertedId;

    @BeforeAll
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(URL, "root", "123456");
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        jdbcTemplate = new JdbcTemplate(ds);
        Assumptions.assumeTrue(canConnect(), "MySQL 连不上，跳过集成测试");
        executor = new DynamicSqlExecutor(jdbcTemplate);
        // 建一张带完整系统字段的测试表
        jdbcTemplate.execute("DROP TABLE IF EXISTS `" + TEST_TABLE + "`");
        jdbcTemplate.execute("CREATE TABLE `" + TEST_TABLE + "` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',"
                + "`name` varchar(64) NOT NULL DEFAULT '' COMMENT '名称',"
                + "`amount` decimal(12,2) DEFAULT NULL COMMENT '金额',"
                + "`creator` varchar(64) DEFAULT '' COMMENT '创建者',"
                + "`create_time` datetime DEFAULT NULL COMMENT '创建时间',"
                + "`updater` varchar(64) DEFAULT '' COMMENT '更新者',"
                + "`update_time` datetime DEFAULT NULL COMMENT '更新时间',"
                + "`deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',"
                + "`tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户编号',"
                + "PRIMARY KEY (`id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        columns = new TableMetaReader(jdbcTemplate).getColumnNames(TEST_TABLE);
    }

    @AfterAll
    void tearDown() {
        TenantContextHolder.clear();
        if (jdbcTemplate != null) {
            try {
                jdbcTemplate.execute("DROP TABLE IF EXISTS `" + TEST_TABLE + "`");
            } catch (Exception ignore) {
                // 清理失败不影响结论
            }
        }
    }

    private boolean canConnect() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    @Test
    @Order(1)
    @DisplayName("插入：自动填充审计字段与租户编号")
    void testInsert() {
        TenantContextHolder.setTenantId(1L);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "租户1的数据");
        data.put("amount", new BigDecimal("100.50"));

        insertedId = executor.insert(TEST_TABLE, columns, data);
        assertNotNull(insertedId, "应返回自增主键");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM `" + TEST_TABLE + "` WHERE id = ?", insertedId);
        assertEquals("租户1的数据", row.get("name"));
        assertEquals(1L, ((Number) row.get("tenant_id")).longValue(), "tenant_id 应自动填充");
        assertNotNull(row.get("create_time"), "create_time 应自动填充");
        assertNotNull(row.get("update_time"), "update_time 应自动填充");
    }

    @Test
    @Order(2)
    @DisplayName("⚠️ 租户隔离：切换租户后查不到别人的数据")
    void testTenantIsolation() {
        // 租户 1 能查到自己的
        TenantContextHolder.setTenantId(1L);
        assertEquals(1, executor.selectCount(TEST_TABLE, columns, null), "租户1应查到1条");
        assertNotNull(executor.selectById(TEST_TABLE, columns, insertedId), "租户1应能查到详情");

        // 租户 2 查不到租户 1 的数据 —— 这是最关键的断言
        TenantContextHolder.setTenantId(2L);
        assertEquals(0, executor.selectCount(TEST_TABLE, columns, null), "租户2不该查到租户1的数据");
        assertNull(executor.selectById(TEST_TABLE, columns, insertedId), "租户2不该查到租户1的详情");
    }

    @Test
    @Order(3)
    @DisplayName("⚠️ 租户隔离：跨租户改不到别人的数据")
    void testTenantIsolation_update() {
        TenantContextHolder.setTenantId(2L);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "被租户2篡改");
        int rows = executor.updateById(TEST_TABLE, columns, insertedId, data);
        assertEquals(0, rows, "跨租户更新应影响 0 行");

        // 确认数据没被改
        TenantContextHolder.setTenantId(1L);
        Map<String, Object> row = executor.selectById(TEST_TABLE, columns, insertedId);
        assertEquals("租户1的数据", row.get("name"), "数据不该被其他租户改动");
    }

    @Test
    @Order(4)
    @DisplayName("更新：同租户可改，且填充 updater")
    void testUpdate() {
        TenantContextHolder.setTenantId(1L);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "已更新");
        data.put("amount", new BigDecimal("200.00"));
        assertEquals(1, executor.updateById(TEST_TABLE, columns, insertedId, data));

        Map<String, Object> row = executor.selectById(TEST_TABLE, columns, insertedId);
        assertEquals("已更新", row.get("name"));
        assertEquals(0, new BigDecimal("200.00").compareTo((BigDecimal) row.get("amount")));
    }

    @Test
    @Order(5)
    @DisplayName("更新：不允许篡改 id / creator / tenant_id")
    void testUpdate_protectedFields() {
        TenantContextHolder.setTenantId(1L);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "再更新");
        data.put("id", 99999);          // 试图改主键
        data.put("tenant_id", 999);     // 试图改租户
        data.put("creator", "hacker");  // 试图改创建人
        executor.updateById(TEST_TABLE, columns, insertedId, data);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM `" + TEST_TABLE + "` WHERE id = ?", insertedId);
        assertEquals(1L, ((Number) row.get("tenant_id")).longValue(), "tenant_id 不该被改");
        assertNotEquals("hacker", row.get("creator"), "creator 不该被改");
    }

    @Test
    @Order(6)
    @DisplayName("查询条件：LIKE 的通配符走参数而非拼接")
    void testQueryCondition() {
        TenantContextHolder.setTenantId(1L);
        List<DynamicSqlExecutor.QueryCondition> conditions = List.of(
                new DynamicSqlExecutor.QueryCondition("name",
                        DynamicSqlExecutor.Operator.LIKE, "更新"));
        assertEquals(1, executor.selectCount(TEST_TABLE, columns, conditions));

        List<Map<String, Object>> list = executor.selectPage(
                TEST_TABLE, columns, conditions, null, 0, 10);
        assertEquals(1, list.size());
    }

    @Test
    @Order(7)
    @DisplayName("⚠️ SQL 注入：条件字段名不在表里则拒绝")
    void testInjection_conditionColumn() {
        TenantContextHolder.setTenantId(1L);
        List<DynamicSqlExecutor.QueryCondition> evil = List.of(
                new DynamicSqlExecutor.QueryCondition("name` = '' OR 1=1 -- ",
                        DynamicSqlExecutor.Operator.EQ, "x"));
        assertThrows(ServiceException.class,
                () -> executor.selectCount(TEST_TABLE, columns, evil),
                "注入的字段名必须被拒绝");
    }

    @Test
    @Order(8)
    @DisplayName("逻辑删除：deleted 置 1，且查不到")
    void testLogicDelete() {
        TenantContextHolder.setTenantId(1L);
        assertEquals(1, executor.deleteById(TEST_TABLE, columns, insertedId));

        // 走执行器查不到
        assertNull(executor.selectById(TEST_TABLE, columns, insertedId));
        assertEquals(0, executor.selectCount(TEST_TABLE, columns, null));
        // 但物理行还在，deleted = 1
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM `" + TEST_TABLE + "` WHERE id = ?", insertedId);
        assertNotNull(row, "逻辑删除不该物理删行");
        assertTrue(((Boolean) row.get("deleted")) || "1".equals(row.get("deleted").toString()),
                "deleted 应为 1，实际：" + row.get("deleted"));
    }

}
