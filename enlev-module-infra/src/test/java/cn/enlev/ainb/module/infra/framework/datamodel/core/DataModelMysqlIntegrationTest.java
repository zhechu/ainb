package cn.enlev.ainb.module.infra.framework.datamodel.core;

import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.module.infra.framework.datamodel.core.model.TableColumnModel;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数据模型的真实 MySQL 集成测试
 *
 * 直连 application-local.yaml 里配置的 ruoyi-vue-pro 库，验证 DDL 真的能建表改表。
 * 单测能保证「SQL 拼对了」，只有真实执行能保证「MySQL 认这个语法」—— 两者都需要。
 *
 * 用固定的临时表名，每次测试前后都清理，不影响库里其他数据。
 * 连不上数据库时整个类跳过，避免阻塞 CI。
 *
 * @author 走进新时代
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class) // 本类的测试有先后依赖（先建表才能改表），必须按 @Order 执行
class DataModelMysqlIntegrationTest {

    private static final String URL = "jdbc:mysql://127.0.0.1:3306/ruoyi-vue-pro"
            + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&nullCatalogMeansCurrent=true";
    private static final String USERNAME = "root";
    private static final String PASSWORD = "123456";

    /**
     * 测试用临时表，刻意用 zz_ 前缀，避免与框架表前缀冲突
     */
    private static final String TEST_TABLE = "zz_data_model_it";

    private JdbcTemplate jdbcTemplate;
    private TableMetaReader reader;
    private DdlExecutor executor;

    @BeforeAll
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(URL, USERNAME, PASSWORD);
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        jdbcTemplate = new JdbcTemplate(dataSource);
        // 连不上就跳过整个类
        Assumptions.assumeTrue(canConnect(), "MySQL 连不上，跳过集成测试");
        reader = new TableMetaReader(jdbcTemplate);
        executor = new DdlExecutor(jdbcTemplate);
        dropTestTable();
    }

    @AfterAll
    void tearDown() {
        if (jdbcTemplate != null) {
            dropTestTable();
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

    private void dropTestTable() {
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS `" + TEST_TABLE + "`");
        } catch (Exception ignore) {
            // 清理失败不影响测试结论
        }
    }

    @Test
    @Order(1)
    @DisplayName("建表 → 读结构：MySQL 接受生成的 DDL，且读回来与预期一致")
    void testCreateAndRead() {
        List<TableColumnModel> columns = List.of(
                new TableColumnModel().setColumnName("id").setColumnType("LONG")
                        .setColumnComment("编号").setPrimaryKey(true).setAutoIncrement(true).setNullable(false),
                new TableColumnModel().setColumnName("order_no").setColumnType("STRING")
                        .setLength(32).setColumnComment("订单号").setNullable(false).setDefaultValue(""),
                new TableColumnModel().setColumnName("amount").setColumnType("DECIMAL")
                        .setLength(12).setScale(2).setColumnComment("金额").setNullable(true),
                new TableColumnModel().setColumnName("memo").setColumnType("TEXT")
                        .setColumnComment("备注").setNullable(true),
                new TableColumnModel().setColumnName("paid").setColumnType("BOOLEAN")
                        .setColumnComment("是否支付").setNullable(false).setDefaultValue("0"),
                new TableColumnModel().setColumnName("created_at").setColumnType("DATETIME")
                        .setColumnComment("创建时间").setNullable(true)
        );

        String sql = MysqlDdlBuilder.buildCreateTable(TEST_TABLE, "数据模型集成测试表", columns);
        DdlExecutor.Result result = executor.execute(sql);
        assertTrue(result.isSuccess(), "建表失败：" + result.getErrorMsg() + "\nSQL：" + sql);

        // 表真的存在
        assertTrue(reader.existsTable(TEST_TABLE));
        assertEquals("数据模型集成测试表", reader.getTableComment(TEST_TABLE));

        // 字段读回来要能对上
        List<TableColumnModel> actual = reader.getColumns(TEST_TABLE);
        assertEquals(6, actual.size(), "字段数不对");

        TableColumnModel id = actual.get(0);
        assertEquals("id", id.getColumnName());
        assertEquals("LONG", id.getColumnType(), "bigint 应反向映射为 LONG");
        assertTrue(id.isPrimaryKey(), "应识别为主键");
        assertTrue(id.isAutoIncrement(), "应识别为自增");
        assertFalse(id.isNullable());
        assertEquals("编号", id.getColumnComment());

        TableColumnModel orderNo = actual.get(1);
        assertEquals("STRING", orderNo.getColumnType());
        assertEquals(32, orderNo.getLength(), "varchar 长度应读回 32");

        TableColumnModel amount = actual.get(2);
        assertEquals("DECIMAL", amount.getColumnType());
        assertEquals(12, amount.getLength(), "decimal 总位数");
        assertEquals(2, amount.getScale(), "decimal 小数位");

        assertEquals("TEXT", actual.get(3).getColumnType());
        assertEquals("BOOLEAN", actual.get(4).getColumnType(), "bit 应映射为 BOOLEAN");
        assertEquals("DATETIME", actual.get(5).getColumnType());
    }

    @Test
    @Order(2)
    @DisplayName("字段名集合读取：供安全校验使用")
    void testGetColumnNames() {
        Set<String> names = reader.getColumnNames(TEST_TABLE);
        assertTrue(names.containsAll(List.of("id", "order_no", "amount", "memo", "paid", "created_at")));
    }

    @Test
    @Order(3)
    @DisplayName("改表：新增 + 改类型 + 改名 + 删除，一次跑通")
    void testAlterTable() {
        List<TableColumnModel> current = reader.getColumns(TEST_TABLE);

        List<TableColumnModel> target = List.of(
                // 不变
                new TableColumnModel().setColumnName("id").setColumnType("LONG")
                        .setColumnComment("编号").setPrimaryKey(true).setAutoIncrement(true).setNullable(false),
                // 改名 order_no → order_code，同时加长
                new TableColumnModel().setColumnName("order_code").setOldColumnName("order_no")
                        .setColumnType("STRING").setLength(64).setColumnComment("订单编码")
                        .setNullable(false).setDefaultValue(""),
                // 改精度
                new TableColumnModel().setColumnName("amount").setColumnType("DECIMAL")
                        .setLength(16).setScale(4).setColumnComment("金额").setNullable(true),
                // memo 保留
                new TableColumnModel().setColumnName("memo").setColumnType("TEXT")
                        .setColumnComment("备注").setNullable(true),
                // paid 保留
                new TableColumnModel().setColumnName("paid").setColumnType("BOOLEAN")
                        .setColumnComment("是否支付").setNullable(false).setDefaultValue("0"),
                // created_at 删除（不在 target 里）
                // 新增
                new TableColumnModel().setColumnName("status").setColumnType("INT")
                        .setColumnComment("状态").setNullable(false).setDefaultValue("0")
        );

        TableDiffer.Diff diff = TableDiffer.diff(target, current);
        assertEquals(1, diff.getAddColumns().size(), "应新增 status");
        assertEquals(List.of("created_at"), diff.getDropColumns(), "应删除 created_at");
        assertTrue(diff.hasLossyChange(), "含删除应标记有损");

        List<String> sqlList = MysqlDdlBuilder.buildAlterTable(TEST_TABLE,
                diff.getAddColumns(), diff.getModifyColumns(), diff.getDropColumns());
        DdlExecutor.Result result = executor.execute(sqlList);
        assertTrue(result.isSuccess(),
                "改表失败：" + result.getErrorMsg() + "\n失败语句：" + result.getFailedSql());

        // 验证结果
        Set<String> names = reader.getColumnNames(TEST_TABLE);
        assertTrue(names.contains("order_code"), "改名后应有 order_code");
        assertFalse(names.contains("order_no"), "改名后不该有 order_no");
        assertTrue(names.contains("status"), "应新增 status");
        assertFalse(names.contains("created_at"), "应删除 created_at");

        List<TableColumnModel> after = reader.getColumns(TEST_TABLE);
        TableColumnModel orderCode = after.stream()
                .filter(c -> "order_code".equals(c.getColumnName())).findFirst().orElseThrow();
        assertEquals(64, orderCode.getLength(), "长度应改为 64");
        assertEquals("订单编码", orderCode.getColumnComment(), "注释应更新");

        TableColumnModel amount = after.stream()
                .filter(c -> "amount".equals(c.getColumnName())).findFirst().orElseThrow();
        assertEquals(16, amount.getLength());
        assertEquals(4, amount.getScale());
    }

    @Test
    @Order(4)
    @DisplayName("行数统计：插入数据后能准确统计")
    void testCountRows() {
        assertEquals(0L, reader.countRows(TEST_TABLE), "初始应为 0 行");
        jdbcTemplate.update("INSERT INTO `" + TEST_TABLE
                + "` (order_code, amount, paid, status) VALUES (?, ?, ?, ?)", "A001", 12.5, true, 1);
        jdbcTemplate.update("INSERT INTO `" + TEST_TABLE
                + "` (order_code, amount, paid, status) VALUES (?, ?, ?, ?)", "A002", 30.0, false, 2);
        assertEquals(2L, reader.countRows(TEST_TABLE));
    }

    @Test
    @Order(5)
    @DisplayName("表列表查询：能查到测试表")
    void testGetTableList() {
        List<?> list = reader.getTableList(TEST_TABLE, null);
        assertEquals(1, list.size(), "应精确查到 1 张表");
    }

    @Test
    @Order(6)
    @DisplayName("DDL 失败时：失败即停，已执行的语句如实返回")
    void testExecute_failFast() {
        List<String> sqlList = List.of(
                "ALTER TABLE `" + TEST_TABLE + "` ADD COLUMN `ok_col` int NULL",
                "ALTER TABLE `" + TEST_TABLE + "` ADD COLUMN `ok_col` int NULL",  // 重复添加，必失败
                "ALTER TABLE `" + TEST_TABLE + "` ADD COLUMN `never_col` int NULL"
        );
        DdlExecutor.Result result = executor.execute(sqlList);

        assertFalse(result.isSuccess(), "第 2 条应失败");
        assertTrue(result.isPartial(), "应处于部分执行状态");
        assertEquals(1, result.getExecutedSqlList().size(), "只有第 1 条成功");
        assertNotNull(result.getFailedSql());
        // 第 3 条不该被执行 —— 这是 failFast 的关键
        assertFalse(reader.getColumnNames(TEST_TABLE).contains("never_col"),
                "失败后不应继续执行后续语句");
        assertTrue(reader.getColumnNames(TEST_TABLE).contains("ok_col"),
                "已执行的语句确实生效了，无法回滚");
    }

    @Test
    @Order(7)
    @DisplayName("删表")
    void testDropTable() {
        DdlExecutor.Result result = executor.execute(MysqlDdlBuilder.buildDropTable(TEST_TABLE));
        assertTrue(result.isSuccess(), "删表失败：" + result.getErrorMsg());
        assertFalse(reader.existsTable(TEST_TABLE));
    }

    /**
     * JSON 列的端到端：建表 → 反向映射 → 存取 → 建键被拒，全在真实 MySQL 上走一遍
     *
     * <p>JSON 是 2026-10-04 加进 {@code DataModelColumnTypeEnum} 的（用户裁定「改建表枚举，加上 JSON」，
     * 缺陷 D4-15 的根治）。单测能证「SQL 拼对了」，但下面这四件只有真库能判：
     * <ol>
     *   <li>{@code `x` json NULL} 这个 DDL，MySQL 到底收不收；</li>
     *   <li>读回来时 {@code DATA_TYPE} 是不是 {@code json}，能不能映射成受控的 JSON 枚举 ——
     *       映射不上就会走 {@code reverseType} 的 default 那条「保留原始类型名 + 前端只读」，
     *       导入的表就废了一半，而单测里那一行 case 是<b>永远走不到</b>的（没有 JDBC 元数据）；</li>
     *   <li>带默认值的 JSON 列会不会炸 —— {@code appendDefaultValue} 刻意跳过 JSON，
     *       这条证的是「跳过了所以建表仍然成功，且库里 COLUMN_DEFAULT 是空的」；</li>
     *   <li>🔴 JSON / TEXT 到底能不能建键、报的是哪个错 —— 「不能做主键或索引列」这句话被写进了
     *       枚举 description、错误码、{@code MysqlDdlBuilder}、{@code DataModelCreateValidator}、
     *       工具指引和文档共六处，全靠这里的错误码撑着：错码哪天变了，那六处就成了拦人的假规矩。
     *       两者必须分开钉，因为补救方向相反 —— TEXT 是 1170「缺前缀长度」（给了就能建），
     *       JSON 是 3152「只能靠生成列 + JSON path」（给前缀长度也没用）。
     *       顺带钉住「不拦的话用户看到什么」：{@link DdlExecutor} 只取 {@code ex.getMessage()}，
     *       而 Spring 那句 message 是 {@code bad SQL grammar [SQL]}，MySQL 原文留在 cause 里没被带出来。</li>
     * </ol>
     *
     * <p>用自己的表名，不碰 {@link #TEST_TABLE}：@Order(1)~(7) 是一条有先后依赖的链
     * （先建才能改、先插数才能计数、最后删表），往链里插一列会连带改动改表与计数两条用例。
     */
    @Test
    @Order(8)
    @DisplayName("JSON 列：建表 / 反向映射 / 存取 / 建键被拒（JSON 3152、TEXT 1170），真实 MySQL 全链路")
    void testJsonColumnEndToEnd() {
        String jsonTable = "zz_data_model_json_it";
        try {
            // extra_attrs 故意带上默认值：MySQL 8 对 json 的字面量默认值是要写成表达式形式
            // DEFAULT ('{}') 的，appendDefaultValue 选择整类跳过（理由见其注释），
            // 这里验的就是「跳过之后建表照样成功」
            List<TableColumnModel> columns = List.of(
                    new TableColumnModel().setColumnName("id").setColumnType("LONG")
                            .setColumnComment("编号").setPrimaryKey(true).setAutoIncrement(true).setNullable(false),
                    new TableColumnModel().setColumnName("extra_attrs").setColumnType("JSON")
                            .setColumnComment("扩展属性").setNullable(true).setDefaultValue("{}"),
                    // memo 只为了下面那组「TEXT 报 1170、且前缀长度能绕开」的对照断言
                    new TableColumnModel().setColumnName("memo").setColumnType("TEXT")
                            .setColumnComment("备注").setNullable(true)
            );
            String sql = MysqlDdlBuilder.buildCreateTable(jsonTable, "JSON 列集成测试表", columns);
            DdlExecutor.Result create = executor.execute(sql);
            assertTrue(create.isSuccess(), "建表失败：" + create.getErrorMsg() + "\nSQL：" + sql);

            // 反向映射：DATA_TYPE=json 必须落成受控枚举，而不是 default 那条「原名 + 只读」
            TableColumnModel extra = reader.getColumns(jsonTable).stream()
                    .filter(c -> "extra_attrs".equals(c.getColumnName())).findFirst().orElseThrow();
            assertEquals("JSON", extra.getColumnType(),
                    "json 没被反向映射成 JSON 枚举 —— 导入的表这一列会变成前端只读、类型名是裸的 json");
            assertNull(extra.getDefaultValue(),
                    "默认值本该被跳过、库里 COLUMN_DEFAULT 应为空；读出东西说明 appendDefaultValue 又发了 DEFAULT");

            // 存取：ValueConverter 序列化出来的文本要能被 json 列收下并原样取回
            Object bound = ValueConverter.convert(Map.of("level", 3), "JSON");
            jdbcTemplate.update("INSERT INTO `" + jsonTable + "` (id, extra_attrs) VALUES (?, ?)", 1L, bound);
            String readBack = jdbcTemplate.queryForObject(
                    "SELECT extra_attrs FROM `" + jsonTable + "` WHERE id = 1", String.class);
            // 不比字面量：MySQL 会把 json 规范化（{"level":3} 存成 {"level": 3}），比解析结果才稳
            assertEquals(3, ((Number) JsonUtils.parseObject(readBack, Map.class).get("level")).intValue(),
                    "存进去的 JSON 取回来对不上，实际：" + readBack);

            // 前端 columnMeta.formatCellValue 对 JSON 列走的是 String(value)，这要求
            // queryForList（数据管理页取行数据走的就是它）拿到的**就是字符串**。
            // 单测里验不了这条 —— 没有真的 ResultSet；Connector/J 哪天改返回类型，
            // 列表页就会显示成 [object Object]，而后端一切正常
            Object rawJson = jdbcTemplate
                    .queryForList("SELECT extra_attrs FROM `" + jsonTable + "` WHERE id = 1")
                    .get(0).get("extra_attrs");
            assertTrue(rawJson instanceof String,
                    "queryForList 对 json 列返回的不是 String 而是 "
                            + (rawJson == null ? "null" : rawJson.getClass().getName())
                            + " —— 前端 columnMeta.formatCellValue 的 String(value) 分支要跟着改");

            // ==================== 🔴 建键被拒：JSON 报 3152、TEXT 报 1170，补救方向不同 ====================
            // 这一段是枚举 description、DATA_MODEL_COLUMN_NOT_KEYABLE、MysqlDdlBuilder#isNonKeyable、
            // DataModelCreateValidator#validateIndexes、DataModelColumnTypeTool 的指引、
            // docs/数据建模规范.md 那六处「不能做主键或索引列」的**唯一事实依据**。
            // 错误码必须分开钉：1170 是「缺前缀长度」（给了就能建），3152 是「只能靠生成列 + JSON path」
            // （前缀长度也没用）—— 两者的提示语该说什么完全不一样，混为一谈就是编病因。

            // JSON：带不带前缀长度都是 3152
            assertKeyRejected(jsonTable, "extra_attrs", null, 3152);
            assertKeyRejected(jsonTable, "extra_attrs", 255, 3152);
            // TEXT：不带前缀长度是 1170，带了就**真能建上** —— 平台不可做键的原因是
            // buildIndexDefinition 从不生成前缀长度，不是 MySQL 不给建
            assertKeyRejected(jsonTable, "memo", null, 1170);
            assertDoesNotThrow(() -> jdbcTemplate.execute("ALTER TABLE `" + jsonTable
                            + "` ADD KEY `idx_memo_prefix` (`memo`(255))"),
                    "TEXT 带前缀长度本该能建上索引 —— 建不上就说明「TEXT 不可做键只是因为平台"
                            + "从不生成前缀长度」这句是错的，isNonKeyable 的注释要重写");

            // 反面钉一条：真让它跑到 MySQL，用户拿到的 errorMsg 里 MySQL 原文一个字都不剩。
            // 这才是「必须提前拦」的真正理由 —— 不是为了躲一句英文错误，而是躲一句
            // 连「JSON」「key」都没提到的 bad SQL grammar。
            // ⚠️ 哪天有人把 DdlExecutor 改成带出 rootCause（那是个正经修复），这条会红：
            //    届时请把上面六处的措辞与 docs/数据建模规范.md §6 一起改，别只删断言。
            DdlExecutor.Result viaExecutor = executor.execute(List.of(
                    "ALTER TABLE `" + jsonTable + "` ADD KEY `idx_extra_attrs2` (`extra_attrs`)"));
            assertFalse(viaExecutor.isSuccess());
            assertFalse(viaExecutor.getErrorMsg().contains("generated columns"),
                    "DdlExecutor 的 errorMsg 里已经带上 MySQL 原文了 —— 那么「拦不住就只剩 "
                            + "bad SQL grammar」这个前提不再成立，六处措辞要跟着改。实际："
                            + viaExecutor.getErrorMsg());
        } finally {
            // 本用例自带表，不依赖 @Order(7) 的删表；红在中途也必须清掉，
            // 否则下一次跑会在 buildCreateTable 上撞「表已存在」，把真红盖成假红
            try {
                jdbcTemplate.execute("DROP TABLE IF EXISTS `" + jsonTable + "`");
            } catch (Exception ignore) {
                // 清理失败不影响测试结论
            }
        }
    }

    /**
     * 断言「给这一列建索引」被 MySQL 拒，且错误码正是期望的那一个
     *
     * 🔴 钉错误码而不是只钉「拒了」：1170 与 3152 的补救方向完全相反 —— 1170 给个前缀长度就能建，
     * 3152 给了也没用、只能靠生成列。写进注释与提示语之前必须分清是哪一条，
     * 否则就是拿一个听起来合理的病因去盖住真的那个。
     *
     * @param prefixLength 前缀长度，null 表示不带
     */
    private void assertKeyRejected(String table, String column, Integer prefixLength, int expectedCode) {
        String spec = "`" + column + "`" + (prefixLength == null ? "" : "(" + prefixLength + ")");
        String indexName = "idx_probe_" + column + (prefixLength == null ? "" : "_p" + prefixLength);
        String sql = "ALTER TABLE `" + table + "` ADD KEY `" + indexName + "` (" + spec + ")";
        BadSqlGrammarException ex = assertThrows(BadSqlGrammarException.class,
                () -> jdbcTemplate.execute(sql), sql + " 本该被 MySQL 拒");
        assertEquals(expectedCode, ex.getSQLException().getErrorCode(),
                "MySQL 拒了「" + sql + "」，但错误码不是 " + expectedCode
                        + " —— 依据它的注释、错误码文案与提示语全部要重新核。MySQL 原文："
                        + ex.getSQLException().getMessage());
    }

}
