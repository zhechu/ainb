package cn.enlev.ainb.module.infra.framework.datamodel.core;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.module.infra.framework.datamodel.core.model.TableColumnModel;
import cn.enlev.ainb.module.infra.framework.datamodel.core.model.TableIndexModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.DATA_MODEL_COLUMN_NOT_KEYABLE;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.DATA_MODEL_COLUMN_TYPE_INVALID;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MysqlDdlBuilder} 的单元测试
 *
 * DDL 拼错会直接损坏表结构且不可回滚，所以逐个语法要素断言。
 *
 * @author 走进新时代
 */
class MysqlDdlBuilderTest {

    private TableColumnModel idColumn() {
        return new TableColumnModel().setColumnName("id").setColumnType("LONG")
                .setColumnComment("编号").setPrimaryKey(true).setAutoIncrement(true).setNullable(false);
    }

    private TableColumnModel nameColumn() {
        return new TableColumnModel().setColumnName("name").setColumnType("STRING")
                .setLength(64).setColumnComment("名称").setNullable(false).setDefaultValue("");
    }

    /** JSON 列：2026-10-04 起可建，但不可做键（MySQL 报 3152，不是 TEXT 那个 1170） */
    private TableColumnModel jsonColumn() {
        return new TableColumnModel().setColumnName("extra_attrs").setColumnType("JSON")
                .setColumnComment("扩展属性").setNullable(true);
    }

    // ==================== 建表 ====================

    @Test
    @DisplayName("建表：完整语法要素")
    void testBuildCreateTable() {
        String sql = MysqlDdlBuilder.buildCreateTable("biz_order", "订单",
                List.of(idColumn(), nameColumn()));

        assertTrue(sql.startsWith("CREATE TABLE `biz_order` ("), "表名要反引号包裹");
        assertTrue(sql.contains("`id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号'"), "实际：" + sql);
        assertTrue(sql.contains("`name` varchar(64) NOT NULL COMMENT '名称'"), "实际：" + sql);
        assertTrue(sql.contains("PRIMARY KEY (`id`)"), "要有主键定义");
        assertTrue(sql.contains("ENGINE=InnoDB"), "要指定引擎");
        assertTrue(sql.contains("utf8mb4"), "要指定字符集");
        assertTrue(sql.contains("COMMENT='订单'"), "要有表注释");
    }

    @Test
    @DisplayName("⚠️ 默认值是空串时不生成 DEFAULT —— 空串表示「没设默认值」")
    void testBuildCreateTable_emptyDefaultMeansNone() {
        // 界面上那个输入框的占位符就是「无」，新增字段时初值也是空串。
        // 把空串当成「默认值是空字符串」的话，用户每加一个没填默认值的字段，
        // 都会平白多出一个 DEFAULT ''
        String sql = MysqlDdlBuilder.buildCreateTable("biz_order", "订单",
                List.of(idColumn(), nameColumn()));

        assertFalse(sql.contains("DEFAULT ''"), "空串不该生成 DEFAULT 子句，实际：" + sql);
    }

    @Test
    @DisplayName("真的填了默认值时才生成 DEFAULT")
    void testBuildCreateTable_withDefault() {
        TableColumnModel status = new TableColumnModel().setColumnName("status").setColumnType("STRING")
                .setLength(20).setColumnComment("状态").setNullable(false).setDefaultValue("DRAFT");

        String sql = MysqlDdlBuilder.buildCreateTable("biz_order", "订单",
                List.of(idColumn(), status));

        assertTrue(sql.contains("`status` varchar(20) NOT NULL DEFAULT 'DRAFT' COMMENT '状态'"),
                "实际：" + sql);
    }

    @Test
    @DisplayName("建表：无字段则拒绝")
    void testBuildCreateTable_emptyColumns() {
        assertThrows(ServiceException.class,
                () -> MysqlDdlBuilder.buildCreateTable("biz_order", "订单", List.of()));
        assertThrows(ServiceException.class,
                () -> MysqlDdlBuilder.buildCreateTable("biz_order", "订单", null));
    }

    @Test
    @DisplayName("建表：无主键则拒绝")
    void testBuildCreateTable_noPrimaryKey() {
        assertThrows(ServiceException.class,
                () -> MysqlDdlBuilder.buildCreateTable("biz_order", "订单", List.of(nameColumn())));
    }

    @Test
    @DisplayName("建表：多主键则拒绝")
    void testBuildCreateTable_multiPrimaryKey() {
        TableColumnModel another = nameColumn().setPrimaryKey(true);
        assertThrows(ServiceException.class,
                () -> MysqlDdlBuilder.buildCreateTable("biz_order", "订单",
                        List.of(idColumn(), another)));
    }

    @Test
    @DisplayName("建表：字段名重复则拒绝（大小写不敏感）")
    void testBuildCreateTable_duplicateColumn() {
        TableColumnModel dup = nameColumn().setColumnName("NAME");
        assertThrows(ServiceException.class,
                () -> MysqlDdlBuilder.buildCreateTable("biz_order", "订单",
                        List.of(idColumn(), nameColumn(), dup)));
    }

    @Test
    @DisplayName("建表：不支持的类型则拒绝")
    void testBuildCreateTable_invalidType() {
        TableColumnModel bad = new TableColumnModel().setColumnName("x")
                .setColumnType("BLOB").setPrimaryKey(false);
        assertThrows(ServiceException.class,
                () -> MysqlDdlBuilder.buildCreateTable("t", "", List.of(idColumn(), bad)));
    }

    // ==================== 各类型定义 ====================

    @Test
    @DisplayName("STRING：未给长度用默认 255")
    void testColumnDefinition_stringDefaultLength() {
        String def = MysqlDdlBuilder.buildColumnDefinition(
                new TableColumnModel().setColumnName("code").setColumnType("STRING").setNullable(true));
        assertTrue(def.contains("varchar(255)"), "实际：" + def);
    }

    @Test
    @DisplayName("DECIMAL：长度与精度都体现")
    void testColumnDefinition_decimal() {
        String def = MysqlDdlBuilder.buildColumnDefinition(
                new TableColumnModel().setColumnName("price").setColumnType("DECIMAL")
                        .setLength(12).setScale(4).setNullable(true));
        assertTrue(def.contains("decimal(12,4)"), "实际：" + def);
    }

    @Test
    @DisplayName("DECIMAL：未给长度精度用默认 10,2")
    void testColumnDefinition_decimalDefault() {
        String def = MysqlDdlBuilder.buildColumnDefinition(
                new TableColumnModel().setColumnName("price").setColumnType("DECIMAL").setNullable(true));
        assertTrue(def.contains("decimal(10,2)"), "实际：" + def);
    }

    @Test
    @DisplayName("TEXT / DATETIME 等不带长度的类型不应出现括号")
    void testColumnDefinition_noLengthTypes() {
        for (String type : List.of("TEXT", "LONG_TEXT", "JSON", "INT", "LONG", "DATE", "DATETIME")) {
            String def = MysqlDdlBuilder.buildColumnDefinition(
                    new TableColumnModel().setColumnName("f").setColumnType(type).setNullable(true));
            assertFalse(def.matches(".*\\w\\(\\d+.*"), type + " 不该有长度，实际：" + def);
        }
    }

    @Test
    @DisplayName("JSON：落成 json，不带长度也不带精度")
    void testColumnDefinition_json() {
        String def = MysqlDdlBuilder.buildColumnDefinition(jsonColumn());
        assertTrue(def.contains("`extra_attrs` json"), "实际：" + def);
    }

    /**
     * 🔴 MySQL 8.0.13 起 JSON 只收**表达式**默认值 {@code DEFAULT ('{}')}，字面量形式一律语法错误。
     * MysqlDdlBuilder 刻意不发表达式形式（两条理由写在 appendDefaultValue 的注释里，
     * 其中一条是「导入已有表 → 改表」会把 information_schema 里的表达式文本再套一层引号，
     * 发出 {@code DEFAULT (''{}'')} 这种废 SQL），所以这里必须一个 DEFAULT 都不发。
     *
     * <p>发错了不会在单测里红，也不会在保存时红 —— 只在 DDL 真执行那一刻炸，
     * 正是本平台「配置保存成功但不生效」那一类缺陷。
     */
    @Test
    @DisplayName("🔴 JSON 带默认值：一个 DEFAULT 子句都不发（发了就是废 SQL）")
    void testColumnDefinition_jsonDefaultValueSkipped() {
        String def = MysqlDdlBuilder.buildColumnDefinition(jsonColumn().setDefaultValue("{}"));
        assertFalse(def.contains("DEFAULT"), "实际：" + def);
        assertFalse(def.contains("{}"), "默认值应当被整段丢弃，实际：" + def);
    }

    @Test
    @DisplayName("主键强制 NOT NULL，即便声明了可空")
    void testColumnDefinition_primaryKeyForceNotNull() {
        String def = MysqlDdlBuilder.buildColumnDefinition(idColumn().setNullable(true));
        assertTrue(def.contains("NOT NULL"), "实际：" + def);
    }

    @Test
    @DisplayName("可空字段不写 NOT NULL")
    void testColumnDefinition_nullable() {
        String def = MysqlDdlBuilder.buildColumnDefinition(
                new TableColumnModel().setColumnName("memo").setColumnType("STRING")
                        .setLength(200).setNullable(true));
        assertFalse(def.contains("NOT NULL"), "实际：" + def);
    }

    @Test
    @DisplayName("注释里的单引号要转义，防止破坏 DDL")
    void testColumnDefinition_escapeComment() {
        String def = MysqlDdlBuilder.buildColumnDefinition(
                new TableColumnModel().setColumnName("f").setColumnType("INT")
                        .setNullable(true).setColumnComment("它's 测试"));
        assertTrue(def.contains("\\'"), "单引号要转义，实际：" + def);
    }

    // ==================== 改表 ====================

    @Test
    @DisplayName("改表：删除字段")
    void testBuildAlterTable_drop() {
        List<String> sqlList = MysqlDdlBuilder.buildAlterTable("biz_order",
                null, null, List.of("old_field"));
        assertEquals(1, sqlList.size());
        assertEquals("ALTER TABLE `biz_order` DROP COLUMN `old_field`", sqlList.get(0));
    }

    @Test
    @DisplayName("改表：新增字段")
    void testBuildAlterTable_add() {
        List<String> sqlList = MysqlDdlBuilder.buildAlterTable("biz_order",
                List.of(nameColumn()), null, null);
        assertEquals(1, sqlList.size());
        assertTrue(sqlList.get(0).startsWith("ALTER TABLE `biz_order` ADD COLUMN `name`"),
                "实际：" + sqlList.get(0));
    }

    @Test
    @DisplayName("改表：仅改类型用 MODIFY COLUMN")
    void testBuildAlterTable_modify() {
        List<String> sqlList = MysqlDdlBuilder.buildAlterTable("biz_order",
                null, List.of(nameColumn().setLength(128)), null);
        assertTrue(sqlList.get(0).contains("MODIFY COLUMN `name` varchar(128)"),
                "实际：" + sqlList.get(0));
    }

    @Test
    @DisplayName("改表：改名用 CHANGE COLUMN 且带原名")
    void testBuildAlterTable_rename() {
        TableColumnModel renamed = nameColumn().setColumnName("title").setOldColumnName("name");
        List<String> sqlList = MysqlDdlBuilder.buildAlterTable("biz_order",
                null, List.of(renamed), null);
        assertTrue(sqlList.get(0).contains("CHANGE COLUMN `name` `title` varchar(64)"),
                "实际：" + sqlList.get(0));
    }

    @Test
    @DisplayName("改表：原名与新名相同时按 MODIFY 处理")
    void testBuildAlterTable_sameNameUseModify() {
        TableColumnModel same = nameColumn().setOldColumnName("name");
        List<String> sqlList = MysqlDdlBuilder.buildAlterTable("biz_order",
                null, List.of(same), null);
        assertTrue(sqlList.get(0).contains("MODIFY COLUMN"), "实际：" + sqlList.get(0));
    }

    @Test
    @DisplayName("改表：先删后加，避免同名字段冲突")
    void testBuildAlterTable_dropBeforeAdd() {
        List<String> sqlList = MysqlDdlBuilder.buildAlterTable("biz_order",
                List.of(nameColumn()), null, List.of("name"));
        assertEquals(2, sqlList.size());
        assertTrue(sqlList.get(0).contains("DROP COLUMN"), "第一条应是 DROP");
        assertTrue(sqlList.get(1).contains("ADD COLUMN"), "第二条应是 ADD");
    }

    @Test
    @DisplayName("改表：无任何变更返回空列表")
    void testBuildAlterTable_empty() {
        assertTrue(MysqlDdlBuilder.buildAlterTable("biz_order", null, null, null).isEmpty());
    }

    // ==================== 删表与表注释 ====================

    @Test
    @DisplayName("删表：不加 IF EXISTS，让表不存在时报错")
    void testBuildDropTable() {
        String sql = MysqlDdlBuilder.buildDropTable("biz_order");
        assertEquals("DROP TABLE `biz_order`", sql);
        assertFalse(sql.contains("IF EXISTS"));
    }

    @Test
    @DisplayName("修改表注释")
    void testBuildAlterTableComment() {
        assertEquals("ALTER TABLE `biz_order` COMMENT='新描述'",
                MysqlDdlBuilder.buildAlterTableComment("biz_order", "新描述"));
    }

    // ==================== 索引 ====================

    private TableIndexModel normalIndex(String name, String... cols) {
        return new TableIndexModel().setIndexName(name)
                .setIndexType(TableIndexModel.TYPE_NORMAL).setColumnNames(List.of(cols));
    }

    private TableIndexModel uniqueIndex(String name, String... cols) {
        return new TableIndexModel().setIndexName(name)
                .setIndexType(TableIndexModel.TYPE_UNIQUE).setColumnNames(List.of(cols));
    }

    @Test
    @DisplayName("建表：普通索引与唯一索引都拼在主键之后")
    void testBuildCreateTable_withIndexes() {
        String sql = MysqlDdlBuilder.buildCreateTable("biz_order", "订单",
                List.of(idColumn(), nameColumn()),
                List.of(normalIndex("idx_name", "name"), uniqueIndex("uk_id_name", "id", "name")));

        assertTrue(sql.contains("KEY `idx_name` (`name`)"), "实际：" + sql);
        assertTrue(sql.contains("UNIQUE KEY `uk_id_name` (`id`, `name`)"), "实际：" + sql);
        assertTrue(sql.indexOf("PRIMARY KEY") < sql.indexOf("KEY `idx_name`"),
                "索引应排在主键之后，实际：" + sql);
    }

    @Test
    @DisplayName("建表：索引引用不存在的列则拒绝")
    void testBuildCreateTable_indexColumnNotExists() {
        assertThrows(ServiceException.class, () -> MysqlDdlBuilder.buildCreateTable("biz_order", "订单",
                List.of(idColumn(), nameColumn()), List.of(normalIndex("idx_x", "not_exist"))));
    }

    @Test
    @DisplayName("建表：索引名重复则拒绝（大小写不敏感）")
    void testBuildCreateTable_duplicateIndexName() {
        assertThrows(ServiceException.class, () -> MysqlDdlBuilder.buildCreateTable("biz_order", "订单",
                List.of(idColumn(), nameColumn()),
                List.of(normalIndex("idx_name", "name"), normalIndex("IDX_NAME", "id"))));
    }

    @Test
    @DisplayName("建表：索引名为空或非法则拒绝")
    void testBuildCreateTable_invalidIndexName() {
        assertThrows(ServiceException.class, () -> MysqlDdlBuilder.buildCreateTable("biz_order", "订单",
                List.of(idColumn(), nameColumn()), List.of(normalIndex("", "name"))));
        assertThrows(ServiceException.class, () -> MysqlDdlBuilder.buildCreateTable("biz_order", "订单",
                List.of(idColumn(), nameColumn()), List.of(normalIndex("idx name", "name"))));
    }

    @Test
    @DisplayName("建表：索引列为空则拒绝")
    void testBuildCreateTable_emptyIndexColumns() {
        assertThrows(ServiceException.class, () -> MysqlDdlBuilder.buildCreateTable("biz_order", "订单",
                List.of(idColumn(), nameColumn()), List.of(normalIndex("idx_x"))));
    }

    // ==================== 🔴 不可做键的类型（TEXT/LONG_TEXT 报 1170，JSON 报 3152） ====================
    //
    // 两者都在真库上验过（DataModelMysqlIntegrationTest#testJsonColumnEndToEnd），
    // 错误码不同、补救方向也相反，别混成一个「反正建不了」：
    //   · TEXT / LONG_TEXT → 1170「BLOB/TEXT column 'x' used in key specification without a key
    //     length」。带前缀长度（KEY idx (col(255))）**能**建上，只是 buildIndexDefinition 从不生成
    //     前缀长度、主键更无从给长度 —— 所以在本平台的 DDL 方言里不可做键。
    //   · JSON → 3152「JSON column 'x' supports indexing only via generated columns on a specified
    //     JSON path.」。前缀长度**没用**（实测 (j(255)) 照样 3152），只能靠生成列，而平台没有生成列。
    //
    // 🔴 为什么必须在**拼 DDL 之前**拦：MySQL 报错时 CREATE TABLE 可能已经执行了一半，
    //    而 DdlExecutor 只取 ex.getMessage()，用户拿到的是半张表加一句 Spring 的
    //    「bad SQL grammar [SQL]」—— 连类型名都不含。与本文件既有的「索引列不存在」那条同一个理由。
    //
    // 🔴 这个洞在 JSON 之前就一直开着（TEXT 主键/索引同样拦不住），JSON 进枚举时一起堵上。
    //    堵它不可能弄坏任何能用的配置：MySQL 本来就会拒，区别只是干净的中文错误还是半张表。

    @Test
    @DisplayName("🔴 索引列是 JSON：拦在建表之前，且是「不可做键」那条错而不是别的")
    void testValidateIndexes_jsonColumnRejected() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> MysqlDdlBuilder.buildCreateTable("biz_order", "订单",
                        List.of(idColumn(), jsonColumn()),
                        List.of(normalIndex("idx_extra_attrs", "extra_attrs"))));
        // 🔴 钉错误码而不是只钉「抛了 ServiceException」：索引列名写错时也会抛，
        //    只断言抛异常的话，这条守卫哪天被删掉、由别的校验兜住了，测试照样绿
        assertEquals(DATA_MODEL_COLUMN_NOT_KEYABLE.getCode(), ex.getCode(), "实际：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("extra_attrs"), "错误里没点到列名：" + ex.getMessage());
    }

    @Test
    @DisplayName("索引列是 TEXT / LONG_TEXT：同样拦下")
    void testValidateIndexes_textColumnRejected() {
        for (String type : List.of("TEXT", "LONG_TEXT")) {
            TableColumnModel column = new TableColumnModel().setColumnName("remark")
                    .setColumnType(type).setColumnComment("备注").setNullable(true);
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> MysqlDdlBuilder.buildCreateTable("biz_order", "订单",
                            List.of(idColumn(), column), List.of(normalIndex("idx_remark", "remark"))),
                    type + " 本该被拦");
            assertEquals(DATA_MODEL_COLUMN_NOT_KEYABLE.getCode(), ex.getCode(),
                    type + " 实际：" + ex.getMessage());
        }
    }

    @Test
    @DisplayName("改表那条路（validateIndexes 被单独调）同样拦：两处入口不能一处有一处没有")
    void testValidateIndexes_alterPathRejected() {
        // 🔴 改表走的是 DataModelServiceImpl:157 那样**单独调 validateIndexes**，
        //    而不是 buildCreateTable；buildIndexAlter 只拼 SQL、压根不校验（它的签名里没有字段列表）。
        //    所以守卫必须长在 validateIndexes 里，两个入口才都覆盖到。
        //    只在 buildCreateTable 里拦的话，「先建表、再补索引」就绕过去了
        ServiceException ex = assertThrows(ServiceException.class,
                () -> MysqlDdlBuilder.validateIndexes(
                        List.of(normalIndex("idx_extra_attrs", "extra_attrs")),
                        List.of(idColumn(), jsonColumn())));
        assertEquals(DATA_MODEL_COLUMN_NOT_KEYABLE.getCode(), ex.getCode(), "实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("🔴 主键是 JSON：拦下（前端那个主键单选框对 JSON 列是可点的）")
    void testValidateColumns_jsonPrimaryKeyRejected() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> MysqlDdlBuilder.buildCreateTable("biz_order", "订单",
                        List.of(jsonColumn().setPrimaryKey(true).setNullable(false),
                                nameColumn())));
        assertEquals(DATA_MODEL_COLUMN_NOT_KEYABLE.getCode(), ex.getCode(), "实际：" + ex.getMessage());
    }

    /**
     * 🔴 这条守的是**守卫自己别炸**：{@code typeOf} 对不受支持的类型返回 null
     * （「导入已有表」会把 blob 这类原样带进来），而 {@code Set.of(...)} 的
     * {@code contains(null)} 是抛 NPE 不是返回 false。用 {@code ==} 比较才安全。
     *
     * <p>而且 null 在这里应当是「不归我管」—— 它由 buildColumnDefinition 用
     * DATA_MODEL_COLUMN_TYPE_INVALID 去拒，不该在这一步先炸成一个看不懂的 NPE。
     */
    @Test
    @DisplayName("🔴 不受支持的类型做主键：报「类型不支持」，不是 NullPointerException")
    void testValidateColumns_unsupportedTypeAsPrimaryKey_isNotAnNpe() {
        TableColumnModel blob = new TableColumnModel().setColumnName("payload")
                .setColumnType("BLOB").setColumnComment("二进制").setPrimaryKey(true).setNullable(false);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> MysqlDdlBuilder.buildCreateTable("biz_order", "订单", List.of(blob, nameColumn())));

        assertEquals(DATA_MODEL_COLUMN_TYPE_INVALID.getCode(), ex.getCode(), "实际：" + ex.getMessage());
        assertFalse(ex.getMessage().contains("NullPointerException"), "实际：" + ex.getMessage());
    }

    /**
     * 反向探针：加了守卫之后，JSON **列**本身必须照样建得出来。
     * 守的是「别把守卫写宽了」—— 一见 JSON 就拒，那用户裁定要加的类型等于没加。
     */
    @Test
    @DisplayName("JSON 列不做键时照常建表，DDL 里就是 json")
    void testBuildCreateTable_jsonColumnWithoutKeyPasses() {
        String sql = MysqlDdlBuilder.buildCreateTable("biz_order", "订单",
                List.of(idColumn(), jsonColumn()));
        assertTrue(sql.contains("`extra_attrs` json NULL"), "实际：" + sql);
    }

    @Test
    @DisplayName("改索引：先 DROP 后 ADD")
    void testBuildIndexAlter() {
        List<String> sqlList = MysqlDdlBuilder.buildIndexAlter("biz_order",
                List.of(normalIndex("idx_name", "name")), List.of("idx_old"));
        assertEquals(2, sqlList.size());
        assertEquals("ALTER TABLE `biz_order` DROP INDEX `idx_old`", sqlList.get(0));
        assertEquals("ALTER TABLE `biz_order` ADD KEY `idx_name` (`name`)", sqlList.get(1));
    }

    @Test
    @DisplayName("改索引：无增删返回空列表")
    void testBuildIndexAlter_empty() {
        assertTrue(MysqlDdlBuilder.buildIndexAlter("biz_order", null, null).isEmpty());
    }

}
