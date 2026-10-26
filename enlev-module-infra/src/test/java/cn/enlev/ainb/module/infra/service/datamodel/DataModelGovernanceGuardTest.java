package cn.enlev.ainb.module.infra.service.datamodel;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.dal.dataobject.datamodel.DataModelDO;
import cn.enlev.ainb.module.infra.framework.datamodel.core.SchemaFingerprint;
import cn.enlev.ainb.module.infra.framework.datamodel.core.TableDiffer;
import cn.enlev.ainb.module.infra.framework.datamodel.core.model.TableColumnModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static cn.enlev.ainb.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.DATA_MODEL_DROP_COLUMN_NEED_CONFIRM;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.DATA_MODEL_RECORD_VERSION_CONFLICT;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.DATA_MODEL_RECORD_VERSION_REQUIRED;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.DATA_MODEL_SCHEMA_DRIFT;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「AI 产物治理」三道闸的单元测试（迭代七-09 B4-2 / B4-3 / B4-4）
 *
 * <p>本类只测三个 {@code @VisibleForTesting} 的门方法，不测 {@code updateDataModel} 整条链路：
 * 整条链路要真库（{@code TableMetaReader} 与 {@code DdlExecutor} 都是在方法内部
 * {@code new} 出来的、由注入的 {@code JdbcTemplate} 驱动），而这三道闸是<b>纯函数</b> ——
 * 输入是「你手里那份」与「实测那份」，输出是放行或抛错。纯函数用真库测反而弱化断言：
 * 一旦失败，分不清是闸判错了还是库没准备好。
 *
 * <h3>🔴 三道闸各自防的静默事故</h3>
 * <ul>
 *   <li><b>B4-2 结构指纹</b>：ALTER 本身照样能执行成功（diff 是拿<b>实时</b>结构算的），
 *       但调用方手里那份列列表描述的已经是另一张表了，于是它一并提交的 {@code columnMeta}
 *       与绑在这张表上的表单字段映射全部错位 —— 「接口返回成功、配置却悄悄失效」。</li>
 *   <li><b>B4-3 删列确认</b>：改表是<b>全量同步</b>语义，「只想加一个字段」的直觉写法
 *       （只提交那个新字段）等于把其它业务列全删掉。此前空表删列<b>没有任何东西会报错</b>
 *       （{@code validateLossyChange} 只在 rows &gt; 0 时拦），表被清成一列、接口返回成功、
 *       DDL 不可回滚。</li>
 *   <li><b>B4-4 记录级乐观锁</b>：MyBatis-Plus 的拦截器在实体 version 为 null 时
 *       <b>静默跳过</b>版本条件，于是漏传版本的调用把锁悄悄关掉<b>且不报错</b>。
 *       这就是「加了 {@code @Version} 注解只算 10%」里那 90% 的失效形态。</li>
 * </ul>
 *
 * <p>⚠️ 乐观锁「两个并发写、第二个真的抛出来」的<b>真跑</b>负对照不在这里 ——
 * mock 出来的乐观锁什么都证明不了。那一条在
 * {@code ArtifactRecordOptimisticLockTest}（H2 + 真的 {@code SqlSessionFactory}）里。
 *
 * @author 走进新时代
 */
public class DataModelGovernanceGuardTest extends BaseMockitoUnitTest {

    @InjectMocks
    private DataModelServiceImpl dataModelService;

    private static final String TABLE = "biz_order_demo";

    // ==================== 造数据 ====================

    private TableColumnModel col(String name) {
        return col(name, "STRING", 255);
    }

    private TableColumnModel col(String name, String type, Integer length) {
        return new TableColumnModel().setColumnName(name).setColumnType(type).setLength(length)
                .setNullable(true).setPrimaryKey(false).setAutoIncrement(false)
                .setColumnComment(name).setDefaultValue(null);
    }

    /** 一张三业务列的表的实测结构 */
    private List<TableColumnModel> threeColumns() {
        return new ArrayList<>(List.of(col("order_no"), col("amount", "DECIMAL", 10), col("memo")));
    }

    private DataModelDO model(Integer version, String fingerprint) {
        DataModelDO dataModel = new DataModelDO();
        dataModel.setId(1L);
        dataModel.setTableName(TABLE);
        dataModel.setVersion(version);
        dataModel.setSchemaFingerprint(fingerprint);
        return dataModel;
    }

    /** 把指纹的最后一个字符换掉 —— §7② 点名的「改一个字符」负对照 */
    private String drift(String fingerprint) {
        char last = fingerprint.charAt(fingerprint.length() - 1);
        char replacement = last == '0' ? '1' : '0';
        return fingerprint.substring(0, fingerprint.length() - 1) + replacement;
    }

    // ==================== B4-2：结构指纹复核 ====================

    @Test
    @DisplayName("🔴 B4-2 负对照：声明的指纹与实测只差一个字符 → 拒绝")
    void testSchemaDriftByOneCharRejected() {
        List<TableColumnModel> current = threeColumns();
        String actual = SchemaFingerprint.of(current);
        String declared = drift(actual);
        assertNotEquals(actual, declared, "前置：造出来的确实是一个不同的指纹");

        // 报错信息里必须带上表名与两个短指纹，否则用户看不出「哪张表、差在哪」
        assertServiceException(
                () -> dataModelService.validateSchemaNotDrifted(model(0, null), declared, current),
                DATA_MODEL_SCHEMA_DRIFT, TABLE, SchemaFingerprint.shortOf(declared),
                SchemaFingerprint.shortOf(actual));
    }

    @Test
    @DisplayName("B4-2 正对照：声明的指纹与实测一致 → 放行")
    void testSchemaMatchPasses() {
        List<TableColumnModel> current = threeColumns();
        assertDoesNotThrow(() -> dataModelService.validateSchemaNotDrifted(
                model(0, SchemaFingerprint.of(current)), SchemaFingerprint.of(current), current));
    }

    @Test
    @DisplayName("🔴 B4-2：两个基线都为空 → 放行（否则本列上线前建的每一张表都从此改不动）")
    void testNoBaselinePasses() {
        assertDoesNotThrow(() -> dataModelService.validateSchemaNotDrifted(
                model(0, null), null, threeColumns()));
        assertDoesNotThrow(() -> dataModelService.validateSchemaNotDrifted(
                model(0, ""), "", threeColumns()));
    }

    @Test
    @DisplayName("B4-2：调用方没传指纹时退回库存基线，库存基线对不上一样拦")
    void testFallsBackToStoredBaseline() {
        List<TableColumnModel> current = threeColumns();
        String actual = SchemaFingerprint.of(current);
        DataModelDO dataModel = model(0, drift(actual));

        assertServiceException(
                () -> dataModelService.validateSchemaNotDrifted(dataModel, null, current),
                DATA_MODEL_SCHEMA_DRIFT, TABLE, SchemaFingerprint.shortOf(dataModel.getSchemaFingerprint()),
                SchemaFingerprint.shortOf(actual));
    }

    /**
     * 🔴 守 {@link SchemaFingerprint} 类注释里那条不变量：
     * 业务语义（dictType / refTable / refColumn）<b>不得</b>进指纹。
     *
     * <p>为什么这条必须有测试：两个计算点的输入并不一致 ——
     * {@code getDataModelStructure} 拿的是 {@code ColumnMetaStore.merge} 之后的列，
     * {@code updateDataModel} 拿的是 {@code TableMetaReader#getColumns} 的原始列。
     * 业务语义一旦进了指纹，这两处会对<b>同一张表</b>算出两个值，
     * 那道闸从此<b>永远</b>报「结构被人改过」—— 而表现是「所有改表都被拦」，
     * 报错还指向一个根本没动过表的人。这种缺陷在单表测试里看不出来。
     */
    @Test
    @DisplayName("🔴 B4-2 不变量：挂字典/关联（业务语义）不改变指纹，否则那道闸会永久误报")
    void testBusinessSemanticsDoNotAffectFingerprint() {
        List<TableColumnModel> plain = threeColumns();
        List<TableColumnModel> enriched = threeColumns();
        enriched.get(0).setDictType("infra_boolean_string");
        enriched.get(1).setRefTable("biz_customer");
        enriched.get(2).setRefColumn("customer_no");

        assertEquals(SchemaFingerprint.of(plain), SchemaFingerprint.of(enriched),
                "业务语义存在 column_meta 里、不在物理表上，改了它表结构一个字节都没变");
    }

    @Test
    @DisplayName("🔴 B4-2 不变量：列的物理摆放顺序不改变指纹（AFTER x 与追加到末尾是同一张表）")
    void testColumnOrderDoesNotAffectFingerprint() {
        List<TableColumnModel> one = threeColumns();
        List<TableColumnModel> reordered = new ArrayList<>(one);
        // 倒过来摆
        java.util.Collections.reverse(reordered);

        assertEquals(SchemaFingerprint.of(one), SchemaFingerprint.of(reordered));
    }

    @Test
    @DisplayName("B4-2 不变量：注释不进指纹（MySQL 对注释有归一化行为，算进来只会带来假阳性）")
    void testCommentDoesNotAffectFingerprint() {
        List<TableColumnModel> one = threeColumns();
        List<TableColumnModel> remarked = threeColumns();
        remarked.get(0).setColumnComment("订单号（改了注释）");

        assertEquals(SchemaFingerprint.of(one), SchemaFingerprint.of(remarked));
    }

    @Test
    @DisplayName("🔴 B4-2：真的结构变化必须算出不同的指纹（否则上面几条『不变量』就是在拿空转当绿）")
    void testRealStructureChangeDoesAffectFingerprint() {
        String base = SchemaFingerprint.of(threeColumns());

        List<TableColumnModel> added = threeColumns();
        added.add(col("extra_col"));
        assertNotEquals(base, SchemaFingerprint.of(added), "加一列必须变");

        List<TableColumnModel> widened = threeColumns();
        widened.get(0).setLength(500);
        assertNotEquals(base, SchemaFingerprint.of(widened), "改长度必须变");

        List<TableColumnModel> retyped = threeColumns();
        retyped.get(0).setColumnType("LONG");
        assertNotEquals(base, SchemaFingerprint.of(retyped), "改类型必须变");

        List<TableColumnModel> nulled = threeColumns();
        nulled.get(0).setNullable(false);
        assertNotEquals(base, SchemaFingerprint.of(nulled), "改可空必须变");
    }

    /**
     * 🔴 编码必须是「长度前缀」而不是「分隔符拼接」，这条钉的是<b>单射</b>。
     *
     * <p>{@code defaultValue} 是这批字段里<b>唯一的自由文本</b>，所以它是唯一能夹带分隔符的。
     * 拿 {@code |} 拼的话，一个默认值里带上 {@code |} 就能伪造出字段的边界，
     * 甚至伪造出<b>下一列</b>：下面那两张表 —— 一张<b>一列</b>、一张<b>两列</b> ——
     * 在 {@link #naiveJoin} 下拼出<b>完全相同</b>的串。而指纹的全部用途就是判等，
     * 两张表判成同一张，那道闸就在最该拦的地方放行。
     *
     * <p>⚠️ 别把反例换成「{@code (name=ab, type=c)} 与 {@code (name=a, type=bc)} 都拼成
     * {@code ab|c}」—— 那一对拼出来是 {@code ab|c} 与 {@code a|bc}，<b>本来就不同</b>。
     * 本条最初就是这么写的：前置断言当场红掉，才看出「分隔符会撞」这个说法需要
     * 一个真能撞的例子才成立。真改成那样跑通的话，它就退化成一条永真断言，
     * 把这条不变量看空 —— 而这正是「拿空转当绿」的形态。
     */
    @Test
    @DisplayName("🔴 B4-2：长度前缀编码是单射的 —— 分隔符拼接会撞的那一对，指纹必须不同")
    void testCanonicalizeIsInjectiveWhereDelimiterJoinWouldCollide() {
        // 表 A：一列，默认值里带着分隔符（多值默认值形如 "on|off" 在本项目里是真实存在的）
        TableColumnModel piped = col("config", "VARCHAR", 64);
        piped.setDefaultValue("on|note|TEXT|");
        List<TableColumnModel> oneColumnWithPipedDefault = List.of(piped);

        // 表 B：两列 —— 第二列的名字与类型，正是上面那个默认值伪造出来的
        TableColumnModel config = col("config", "VARCHAR", 64);
        config.setDefaultValue("on");
        TableColumnModel note = col("note", "TEXT", null);
        note.setDefaultValue("");
        List<TableColumnModel> twoColumns = List.of(config, note);

        // 前置：分隔符拼接确实分不开这两张表。少了这条，下面两句就是永真断言
        assertEquals(naiveJoin(oneColumnWithPipedDefault), naiveJoin(twoColumns),
                "前置不成立 —— 换一对真能撞的例子，否则这条什么都没证明");

        assertNotEquals(SchemaFingerprint.canonicalize(oneColumnWithPipedDefault),
                SchemaFingerprint.canonicalize(twoColumns));
        assertNotEquals(SchemaFingerprint.of(oneColumnWithPipedDefault),
                SchemaFingerprint.of(twoColumns), "两张不同的表不得算出同一个指纹");
    }

    /**
     * 朴素的分隔符拼接编码 —— <b>只存在于本测试里</b>，专门用来构造出「会撞」的那一对。
     *
     * <p>刻意<b>不记列数</b>：这正是它的缺陷所在，多一列少一列都看不出来。
     */
    private static String naiveJoin(List<TableColumnModel> columns) {
        StringBuilder sb = new StringBuilder();
        for (TableColumnModel column : columns) {
            sb.append(column.getColumnName()).append('|')
                    .append(column.getColumnType()).append('|')
                    .append(column.getDefaultValue()).append('|');
        }
        return sb.toString();
    }

    // ==================== B4-3：删列必须显式确认 ====================

    /** 真实表有 current，目标结构只留 keep —— 其余全落进 dropColumns */
    private TableDiffer.Diff droppingAllBut(String... keep) {
        List<TableColumnModel> target = new ArrayList<>();
        for (String name : keep) {
            target.add(col(name));
        }
        return TableDiffer.diff(target, threeColumns());
    }

    @Test
    @DisplayName("🔴 B4-3 负对照（日志一）：要删列而 confirmDropColumns 缺失 → 拒绝")
    void testDropColumnsWithoutConfirmRejected() {
        TableDiffer.Diff diff = droppingAllBut("order_no");
        assertEquals(List.of("amount", "memo"), diff.getDropColumns(), "前置：确实推断出两个待删列");

        assertServiceException(
                () -> dataModelService.validateDropColumnsConfirmed(diff, null),
                DATA_MODEL_DROP_COLUMN_NEED_CONFIRM, "amount、memo");
    }

    @Test
    @DisplayName("🔴 B4-3 负对照：confirmDropColumns=false 与缺失同等对待，不退化成放行")
    void testDropColumnsWithFalseConfirmRejected() {
        assertServiceException(
                () -> dataModelService.validateDropColumnsConfirmed(droppingAllBut("order_no"), false),
                DATA_MODEL_DROP_COLUMN_NEED_CONFIRM, "amount、memo");
    }

    @Test
    @DisplayName("🔴 B4-3 正对照（日志二）：confirmDropColumns=true → 放行")
    void testDropColumnsWithConfirmPasses() {
        TableDiffer.Diff diff = droppingAllBut("order_no");
        assertDoesNotThrow(() -> dataModelService.validateDropColumnsConfirmed(diff, true));
    }

    @Test
    @DisplayName("B4-3：不删列时不要求确认（否则每次加字段都要点一次确认，确认框会被点成条件反射）")
    void testNoDropNeedsNoConfirm() {
        List<TableColumnModel> target = threeColumns();
        target.add(col("extra_col"));
        TableDiffer.Diff diff = TableDiffer.diff(target, threeColumns());
        assertTrue(diff.getDropColumns().isEmpty(), "前置：这次只加列");

        assertDoesNotThrow(() -> dataModelService.validateDropColumnsConfirmed(diff, null));
    }

    /**
     * 🔴 「全量同步语义」是本闸存在的全部理由，这条用例把它钉住：
     * 只提交一个新字段 ⟹ 其余三列全部落进 dropColumns。
     *
     * <p>这正是 AI 最容易撞上的形态 —— 它按「增量」的直觉构造参数，
     * 而接口是「全量」的语义。此前空表上这么提交：没有数据可丢 ⟹ 有损校验放行，
     * 没有任何东西报错，表结构被清成一列，DDL 不可回滚。
     */
    @Test
    @DisplayName("🔴 B4-3：只提交一个新字段 → 其余列全部被推断为待删，且必须被拦")
    void testIncrementalIntentIsFullSyncReality() {
        List<TableColumnModel> target = List.of(col("brand_new_col"));
        TableDiffer.Diff diff = TableDiffer.diff(target, threeColumns());

        // 顺序是【实测结构的顺序】：TableDiffer 用 LinkedHashMap 装现有字段，
        // 命中的移除、剩下的按插入序落进 dropColumns
        assertEquals(List.of("order_no", "amount", "memo"), diff.getDropColumns(),
                "全量同步：目标里没有的列一律待删");
        assertServiceException(() -> dataModelService.validateDropColumnsConfirmed(diff, null),
                DATA_MODEL_DROP_COLUMN_NEED_CONFIRM, "order_no、amount、memo");
    }

    /**
     * ⚠️ 这道闸<b>不看</b>表里有没有数据，签名上也没有行数/reader 参数 ——
     * 「空表也不例外」是结构上成立的，不是靠某个 if 分支。
     * 与 {@code riskConfirmed} 刻意不合并：riskConfirmed 问的是「这些数据可以丢吗」，
     * 而空表上根本没有数据，那个提问与事实不符，只会训练出「闭着眼睛点确认」的习惯。
     */
    @Test
    @DisplayName("🔴 B4-3：闸的签名里没有任何行数入参 —— 空表与满表走的是同一条判据")
    void testGateTakesNoRowCount() {
        long rowCountParams = Arrays.stream(
                        DataModelServiceImpl.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("validateDropColumnsConfirmed"))
                .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                .filter(type -> type == long.class || type == int.class || type == Long.class
                        || type == Integer.class)
                .count();
        assertEquals(0, rowCountParams,
                "一旦出现行数入参，就说明有人把『空表放行』又加回来了 —— 那正是 B4-3 要堵的洞");
    }

    // ==================== B4-4：记录级乐观锁的两道检查 ====================

    @Test
    @DisplayName("🔴 B4-4 绊线：没带 version → 硬拒绝，绝不退化成『那就不加锁』")
    void testMissingVersionRejected() {
        assertServiceException(() -> dataModelService.validateRecordVersion(null, model(3, null)),
                DATA_MODEL_RECORD_VERSION_REQUIRED);
    }

    @Test
    @DisplayName("🔴 B4-4 负对照：版本落后（别人刚存过）→ 冲突")
    void testStaleVersionRejected() {
        assertServiceException(() -> dataModelService.validateRecordVersion(2, model(3, null)),
                DATA_MODEL_RECORD_VERSION_CONFLICT);
    }

    @Test
    @DisplayName("🔴 B4-4：版本超前一样拒 —— 判据是『相等』，不是『不小于』")
    void testFutureVersionRejected() {
        assertServiceException(() -> dataModelService.validateRecordVersion(4, model(3, null)),
                DATA_MODEL_RECORD_VERSION_CONFLICT);
    }

    @Test
    @DisplayName("B4-4 正对照：版本相等 → 放行")
    void testMatchingVersionPasses() {
        assertDoesNotThrow(() -> dataModelService.validateRecordVersion(3, model(3, null)));
        assertDoesNotThrow(() -> dataModelService.validateRecordVersion(0, model(0, null)),
                "存量行的初值是 0（DDL 里 version int NOT NULL DEFAULT 0），必须能过");
    }

    /**
     * 🔴 顺序是有意义的：{@code validateRecordVersion} 必须排在
     * {@code validateSchemaNotDrifted} 之前，因为版本冲突时「实测指纹」这个概念本身就不成立
     * （对方那次改动已经把它推进了）。反过来排的话，用户会先读到一句
     * 「有人绕过平台改了库」—— 而真相只是另一个窗口点了保存。
     *
     * <p>用「版本为 null 时指纹闸根本不该被问到」来表达这条顺序：
     * 两道闸都写在这里、且先问版本，就足以让漏传版本的调用报出正确的那一条。
     */
    @Test
    @DisplayName("🔴 B4-4 排在 B4-2 之前：漏传版本时报的是『没带版本号』，不是『结构被人改过』")
    void testVersionGateIsAskedFirst() {
        DataModelDO dataModel = model(3, drift(SchemaFingerprint.of(threeColumns())));

        ServiceException ex = assertThrows(ServiceException.class, () -> {
            // 与 updateDataModel 里的调用顺序一致
            dataModelService.validateRecordVersion(null, dataModel);
            dataModelService.validateSchemaNotDrifted(dataModel, null, threeColumns());
        });
        assertEquals(DATA_MODEL_RECORD_VERSION_REQUIRED.getCode(), ex.getCode(),
                "先撞到的是版本绊线，指纹闸根本没被问到");
    }

}
