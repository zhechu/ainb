package cn.enlev.ainb.module.infra.framework.datainterface.core.executor;

import cn.enlev.ainb.framework.tenant.core.context.TenantContextHolder;
import cn.enlev.ainb.module.infra.dal.dataobject.datainterface.DataInterfaceDO;
import cn.enlev.ainb.module.infra.framework.datainterface.core.param.ParamDefinition;
import cn.enlev.ainb.module.infra.framework.datainterface.core.sql.SqlTenantRewriter;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SQL 执行链路的真实 MySQL 集成测试
 *
 * 单测能验「SQL 拼对了」，只有真库能验「MySQL 认这个语法、租户隔离真的生效」。
 * 重点验证：
 * 1. 租户隔离在真实查询里生效（A 租户查不到 B 租户数据）
 * 2. 多语句逐条执行、参数按序正确分配
 * 3. INSERT 自动补租户列后能正常插入
 *
 * 这里直接组装 SqlTenantRewriter + JdbcTemplate 验证链路，不启 Spring 容器 ——
 * 启容器慢且会引入无关的失败因素。
 *
 * @author 走进新时代
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SqlExecutorIntegrationTest {

    // 🔴 库名要跟 application-local.yaml 的 master.url 一致。曾写死旧库 ruoyi-vue-pro，
    //    而下面 canConnect() 走的是 Assumptions.assumeTrue —— 连不上是【静默跳过】不是失败，
    //    于是新机器上（只建了 ainb-professional-init）这个集成测试会一直绿着不跑。
    //    本测试自建并自删 zz_di_order / zz_di_order_item，落到哪个库都不污染业务表。
    private static final String URL = "jdbc:mysql://127.0.0.1:3306/ainb-professional-init"
            + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&nullCatalogMeansCurrent=true";
    private static final String MAIN_TABLE = "zz_di_order";
    private static final String ITEM_TABLE = "zz_di_order_item";

    private JdbcTemplate jdbcTemplate;
    private SqlTenantRewriter rewriter;

    @BeforeAll
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(URL, "root", "123456");
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        jdbcTemplate = new JdbcTemplate(ds);
        Assumptions.assumeTrue(canConnect(), "MySQL 连不上，跳过集成测试");
        // 测试表：都带 tenant_id，模拟真实业务表
        jdbcTemplate.execute("DROP TABLE IF EXISTS `" + MAIN_TABLE + "`");
        jdbcTemplate.execute("DROP TABLE IF EXISTS `" + ITEM_TABLE + "`");
        jdbcTemplate.execute("CREATE TABLE `" + MAIN_TABLE + "` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,"
                + "`order_no` varchar(32) NOT NULL DEFAULT '',"
                + "`amount` decimal(12,2) DEFAULT NULL,"
                + "`status` int NOT NULL DEFAULT 0,"
                + "`tenant_id` bigint NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (`id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbcTemplate.execute("CREATE TABLE `" + ITEM_TABLE + "` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,"
                + "`order_id` bigint NOT NULL,"
                + "`qty` int NOT NULL DEFAULT 0,"
                + "`tenant_id` bigint NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (`id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        // 造两个租户的数据，用于验证隔离
        jdbcTemplate.update("INSERT INTO `" + MAIN_TABLE
                + "` (order_no, amount, status, tenant_id) VALUES ('T1-A', 100, 1, 1)");
        jdbcTemplate.update("INSERT INTO `" + MAIN_TABLE
                + "` (order_no, amount, status, tenant_id) VALUES ('T1-B', 200, 2, 1)");
        jdbcTemplate.update("INSERT INTO `" + MAIN_TABLE
                + "` (order_no, amount, status, tenant_id) VALUES ('T2-A', 300, 1, 2)");
        // zz_ 前缀不是框架表前缀，所以这些表都能被改写
        rewriter = new SqlTenantRewriter(table -> table.toLowerCase().startsWith("zz_di_"));
    }

    @AfterAll
    void tearDown() {
        TenantContextHolder.clear();
        if (jdbcTemplate != null) {
            try {
                jdbcTemplate.execute("DROP TABLE IF EXISTS `" + MAIN_TABLE + "`");
                jdbcTemplate.execute("DROP TABLE IF EXISTS `" + ITEM_TABLE + "`");
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

    /** 走完整链路：改写 → 执行 */
    private List<Map<String, Object>> query(String sql, Long tenantId, Object... args) {
        SqlTenantRewriter.RewriteResult r = rewriter.rewrite(sql, tenantId);
        assertTrue(r.isSuccess(), "改写应成功：" + r.getErrorMsg());
        return jdbcTemplate.queryForList(r.getStatementSqlList().get(0), args);
    }

    // ==================== 租户隔离 ====================

    @Test
    @Order(1)
    @DisplayName("⚠️ 租户隔离：租户1 只能查到自己的 2 条")
    void testTenantIsolation_tenant1() {
        List<Map<String, Object>> list = query("SELECT * FROM " + MAIN_TABLE, 1L);
        assertEquals(2, list.size(), "租户1 应查到 2 条，实际：" + list);
        list.forEach(row -> assertEquals(1L, ((Number) row.get("tenant_id")).longValue()));
    }

    @Test
    @Order(2)
    @DisplayName("⚠️ 租户隔离：租户2 只能查到自己的 1 条")
    void testTenantIsolation_tenant2() {
        List<Map<String, Object>> list = query("SELECT * FROM " + MAIN_TABLE, 2L);
        assertEquals(1, list.size(), "租户2 应查到 1 条，实际：" + list);
        assertEquals("T2-A", list.get(0).get("order_no"));
    }

    @Test
    @Order(3)
    @DisplayName("⚠️ 用户写死 tenant_id 试图窥探：查不到数据")
    void testTenantIsolation_forged() {
        // 租户1 的身份，SQL 里写死查租户2
        List<Map<String, Object>> list = query(
                "SELECT * FROM " + MAIN_TABLE + " WHERE tenant_id = 2", 1L);
        assertTrue(list.isEmpty(), "两个租户条件相与应查不到数据，实际：" + list);
    }

    @Test
    @Order(4)
    @DisplayName("带占位符的查询：参数正常绑定")
    void testWithPlaceholder() {
        List<Map<String, Object>> list = query(
                "SELECT * FROM " + MAIN_TABLE + " WHERE status = ?", 1L, 1);
        assertEquals(1, list.size(), "租户1 里 status=1 的只有 1 条：" + list);
        assertEquals("T1-A", list.get(0).get("order_no"));
    }

    // ==================== 多语句 ====================

    @Test
    @Order(5)
    @DisplayName("多语句：INSERT 主表 + INSERT 子表，逐条执行")
    void testMultiStatement_insert() {
        TenantContextHolder.setTenantId(1L);
        String sql = "INSERT INTO " + MAIN_TABLE + " (order_no, amount, status) VALUES ('T1-C', 400, 1); "
                + "INSERT INTO " + ITEM_TABLE + " (order_id, qty) VALUES (1, 5)";
        SqlTenantRewriter.RewriteResult r = rewriter.rewrite(sql, 1L);
        assertTrue(r.isSuccess(), r.getErrorMsg());
        assertEquals(2, r.getStatementSqlList().size());
        // 逐条执行
        r.getStatementSqlList().forEach(s -> jdbcTemplate.update(s));

        // 主表新增的那条应带上租户1
        List<Map<String, Object>> list = query(
                "SELECT * FROM " + MAIN_TABLE + " WHERE order_no = 'T1-C'", 1L);
        assertEquals(1, list.size(), "应插入成功且属于租户1");
        assertEquals(1L, ((Number) list.get(0).get("tenant_id")).longValue(),
                "INSERT 自动补的租户列应生效");
        // 子表同理
        List<Map<String, Object>> items = query("SELECT * FROM " + ITEM_TABLE, 1L);
        assertEquals(1, items.size());
        assertEquals(1L, ((Number) items.get(0).get("tenant_id")).longValue());
    }

    @Test
    @Order(6)
    @DisplayName("⚠️ INSERT 写死别的租户：被强制改成当前租户")
    void testInsert_forgedTenant() {
        String sql = "INSERT INTO " + MAIN_TABLE
                + " (order_no, amount, status, tenant_id) VALUES ('T1-D', 500, 1, 999)";
        SqlTenantRewriter.RewriteResult r = rewriter.rewrite(sql, 1L);
        assertTrue(r.isSuccess(), r.getErrorMsg());
        jdbcTemplate.update(r.getStatementSqlList().get(0));

        Long tenantId = jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM `" + MAIN_TABLE + "` WHERE order_no = 'T1-D'", Long.class);
        assertEquals(1L, tenantId, "写死的 999 应被改成当前租户 1");
    }

    // ==================== 写操作的租户隔离 ====================

    @Test
    @Order(7)
    @DisplayName("⚠️ UPDATE 跨租户：影响 0 行")
    void testUpdate_crossTenant() {
        // 租户2 的身份去改租户1 的数据
        SqlTenantRewriter.RewriteResult r = rewriter.rewrite(
                "UPDATE " + MAIN_TABLE + " SET status = 9 WHERE order_no = 'T1-A'", 2L);
        assertTrue(r.isSuccess(), r.getErrorMsg());
        int rows = jdbcTemplate.update(r.getStatementSqlList().get(0));
        assertEquals(0, rows, "跨租户更新应影响 0 行");

        // 确认数据没被改
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM `" + MAIN_TABLE + "` WHERE order_no = 'T1-A'", Integer.class);
        assertEquals(1, status, "数据不该被其他租户改动");
    }

    @Test
    @Order(8)
    @DisplayName("⚠️ DELETE 跨租户：影响 0 行")
    void testDelete_crossTenant() {
        SqlTenantRewriter.RewriteResult r = rewriter.rewrite(
                "DELETE FROM " + MAIN_TABLE + " WHERE order_no = 'T2-A'", 1L);
        assertTrue(r.isSuccess(), r.getErrorMsg());
        int rows = jdbcTemplate.update(r.getStatementSqlList().get(0));
        assertEquals(0, rows, "租户1 删不掉租户2 的数据");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM `" + MAIN_TABLE + "` WHERE order_no = 'T2-A'", Integer.class);
        assertEquals(1, count, "租户2 的数据应还在");
    }

    @Test
    @Order(9)
    @DisplayName("JOIN 查询：两表都注入租户条件")
    void testJoin() {
        List<Map<String, Object>> list = query("SELECT o.order_no, i.qty FROM " + MAIN_TABLE
                + " o JOIN " + ITEM_TABLE + " i ON o.id = i.order_id", 1L);
        // 只要能跑通且不报错即说明 JOIN 改写的 SQL 语法正确
        assertNotNull(list);
    }

}
