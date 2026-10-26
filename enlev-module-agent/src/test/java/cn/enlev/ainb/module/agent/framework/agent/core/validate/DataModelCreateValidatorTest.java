package cn.enlev.ainb.module.agent.framework.agent.core.validate;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.api.datamodel.DataModelApi;
import cn.enlev.ainb.module.infra.api.datamodel.dto.DataModelSaveReqDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;

/**
 * 建表校验器的单元测试
 *
 * <h3>为什么值得写这么多用例</h3>
 * 这个校验器拦的东西分两类，第二类是重点：
 * <ul>
 *   <li><b>后端也会拦的</b>（表名不合规、类型不存在）—— 这里拦只是为了给模型可读的修改建议</li>
 *   <li><b>后端<u>永远不会</u>拦的</b>（§5.5 的 8 条静默改写）—— 不报错、保存成功、
 *       但建出来的不是你要的。这些只能在这里说出来，而且建表不可逆，
 *       发现得晚就只能改表</li>
 * </ul>
 *
 * @author 走进新时代
 */
public class DataModelCreateValidatorTest extends BaseMockitoUnitTest {

    @InjectMocks
    private DataModelCreateValidator validator;

    @Mock
    private DataModelApi dataModelApi;

    @BeforeEach
    public void setUp() {
        DataModelApi.TableConstraintsRespDTO constraints = new DataModelApi.TableConstraintsRespDTO();
        constraints.setTablePrefix("biz_");
        constraints.setIdentifierPattern("^[a-z][a-z0-9_]{0,62}[a-z0-9]$");
        constraints.setStringMaxLength(16383);
        constraints.setStringDefaultLength(255);
        constraints.setSystemColumnNames(List.of("id", "creator", "create_time", "updater",
                "update_time", "version", "deleted", "tenant_id"));
        // 🔴 流程回写列那一份约束，抄的是 infra 的 {@code DataModelApiImpl#getTableConstraints()}：
        //    两列的名字、合格的受控类型、最小长度。抄漏了不会红（受影响的那条用例会以
        //    「本条沉默」的形式绿着），所以下面专门有一条钉住「名单读不到时整条沉默」，
        //    把沉默本身变成被断言的行为，而不是一个碰巧的绿。
        constraints.setFlowColumns(List.of(
                flowColumn("process_instance_id", List.of("STRING"), 64),
                flowColumn("process_status", List.of("TINYINT", "INT", "LONG"), null)));
        lenient().when(dataModelApi.getTableConstraints()).thenReturn(constraints);

        List<DataModelApi.ColumnTypeRespDTO> types = new ArrayList<>();
        // 🔴 手抄 DataModelColumnTypeEnum，加了枚举值要回来同步（JSON 是 2026-10-04 加的）。
        //    抄漏了不会红：validator 只拿它判「模型写的类型收不收」，替身里少一个类型，
        //    受影响的那条用例会以「类型不在受支持列表里」的形式红出来，而不是指着这里
        for (String type : List.of("STRING", "TEXT", "LONG_TEXT", "JSON", "TINYINT", "INT", "LONG",
                "DECIMAL", "BOOLEAN", "DATE", "DATETIME")) {
            DataModelApi.ColumnTypeRespDTO dto = new DataModelApi.ColumnTypeRespDTO();
            dto.setType(type);
            types.add(dto);
        }
        lenient().when(dataModelApi.getColumnTypeList()).thenReturn(types);
    }

    private DataModelSaveReqDTO.ColumnDTO column(String name, String type) {
        DataModelSaveReqDTO.ColumnDTO column = new DataModelSaveReqDTO.ColumnDTO();
        column.setColumnName(name);
        column.setColumnType(type);
        column.setColumnComment(name + "的说明");
        column.setPrimaryKey(false);
        if ("STRING".equals(type)) {
            column.setLength(64);
        }
        return column;
    }

    private static DataModelApi.FlowColumnConstraint flowColumn(String name, List<String> allowedTypes,
                                                                Integer minLength) {
        DataModelApi.FlowColumnConstraint constraint = new DataModelApi.FlowColumnConstraint();
        constraint.setColumnName(name);
        constraint.setAllowedTypes(allowedTypes);
        constraint.setMinLength(minLength);
        return constraint;
    }

    /** 一个完全合规的建表定义 */
    private DataModelSaveReqDTO validModel() {
        DataModelSaveReqDTO dto = new DataModelSaveReqDTO();
        dto.setTableName("biz_purchase_request");
        dto.setTableComment("采购申请");
        dto.setColumns(new ArrayList<>(List.of(
                column("order_no", "STRING"),
                column("apply_time", "DATETIME"))));
        return dto;
    }

    @Test
    @DisplayName("合规的定义要能通过 —— 校验器不能严到什么都建不出来")
    public void testValidModelPasses() {
        AgentValidateResult result = validator.validate(validModel());
        assertTrue(result.isPassed(), "不该报错：" + result.getErrors());
        assertTrue(result.getWarnings().isEmpty(), "不该有警告：" + result.getWarnings());
    }

    // ==================== 表名 ====================

    @Test
    @DisplayName("表名缺 biz_ 前缀：报错并直接给出该改成什么")
    public void testMissingPrefix() {
        DataModelSaveReqDTO dto = validModel();
        dto.setTableName("purchase_request");

        AgentValidateResult result = validator.validate(dto);

        assertFalse(result.isPassed());
        // 🔴 错误信息是写给模型看的，必须说清「改成什么」。
        // 只说「必须以 biz_ 开头」，模型可能改成 biz_purchase 或 bizPurchaseRequest
        assertTrue(result.getErrors().stream()
                        .anyMatch(e -> e.contains("biz_purchase_request")),
                "要给出修改后的表名，实际：" + result.getErrors());
    }

    @Test
    @DisplayName("驼峰表名被拒 —— Linux 下 MySQL 表名大小写敏感")
    public void testCamelCaseRejected() {
        DataModelSaveReqDTO dto = validModel();
        dto.setTableName("biz_PurchaseRequest");
        assertFalse(validator.validate(dto).isPassed());
    }

    /**
     * 🔴 建议值本身必须是合法的
     *
     * 直接拼成 {@code biz_PurchaseOrder} 看着像帮了忙，实则驼峰仍然不合规 ——
     * 模型照做一遍，撞第二次错误，又是一轮真实的计费。
     * 「一次说完、一轮改对」是这个校验器存在的全部意义。
     */
    @Test
    @DisplayName("🔴 给出的建议表名本身必须合法，不能让模型照做还错一次")
    public void testSuggestedTableNameIsItselfValid() {
        DataModelSaveReqDTO dto = validModel();
        dto.setTableName("PurchaseOrder");

        AgentValidateResult result = validator.validate(dto);

        String suggestion = result.getErrors().stream()
                .filter(e -> e.contains("应改为"))
                .findFirst().orElseThrow()
                .replaceAll(".*应改为\\s*", "").trim();
        assertEquals("biz_purchase_order", suggestion);

        // 把建议值拿回来再校验一遍，必须不再报表名相关的错
        dto.setTableName(suggestion);
        assertTrue(validator.validate(dto).isPassed(),
                "照着建议改完应该就通过了，实际：" + validator.validate(dto).getErrors());
    }

    @Test
    @DisplayName("🔴 想建唯一索引却写错大小写：建议要按「想建唯一」给 uk_，而不是顺着笔误给 idx_")
    public void testUniqueIntentPreservedInSuggestion() {
        DataModelSaveReqDTO dto = validModel();
        DataModelSaveReqDTO.IndexDTO index = new DataModelSaveReqDTO.IndexDTO();
        index.setIndexName("order_no_index");
        index.setIndexType("unique");
        index.setColumnNames(List.of("order_no"));
        dto.setIndexes(List.of(index));

        AgentValidateResult result = validator.validate(dto);

        // 顺着笔误建议 idx_ 等于把唯一约束彻底弄没了 ——
        // 而唯一约束没建上的表现是「一切正常」，直到某天出现重复数据
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("uk_order_no")),
                "应建议 uk_order_no，实际：" + result.getErrors());
    }

    @Test
    @DisplayName("🔴 撞框架前缀的表名被拒 —— 走 SystemTablePolicy，不自己攒名单")
    public void testFrameworkPrefixRejected() {
        for (String tableName : List.of("system_user", "infra_file", "bpm_task", "sys_grant")) {
            DataModelSaveReqDTO dto = validModel();
            dto.setTableName(tableName);
            assertFalse(validator.validate(dto).isPassed(), tableName + " 应该被拒绝");
        }
    }

    @Test
    @DisplayName("表描述不能等于表名，也不能是一两个字")
    public void testTableComment() {
        DataModelSaveReqDTO dto = validModel();
        dto.setTableComment("biz_purchase_request");
        assertFalse(validator.validate(dto).isPassed());

        dto.setTableComment("");
        assertFalse(validator.validate(dto).isPassed());
    }

    // ==================== 列 ====================

    @Test
    @DisplayName("🔴 业务字段设主键被拒 —— 会和注入器补的 id 撞成两个主键")
    public void testBusinessColumnCannotBePrimaryKey() {
        DataModelSaveReqDTO dto = validModel();
        dto.getColumns().get(0).setPrimaryKey(true);

        AgentValidateResult result = validator.validate(dto);

        assertFalse(result.isPassed());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("primaryKey=false")),
                "要告诉模型怎么改：" + result.getErrors());
    }

    /**
     * 🔴 这条的后果是<b>跨租户数据泄漏</b>，而表现是「一切正常」
     *
     * 注入器遇到同名列会让位、不覆盖也不报错，后续动态 CRUD 的租户拼接行为异常。
     */
    @Test
    @DisplayName("🔴 业务字段与系统列同名被拒 —— 注入器会静默让位，后果是跨租户泄漏")
    public void testSystemColumnNameRejected() {
        for (String name : List.of("tenant_id", "deleted", "creator", "id")) {
            DataModelSaveReqDTO dto = validModel();
            dto.getColumns().add(column(name, "STRING"));
            assertFalse(validator.validate(dto).isPassed(), name + " 应该被拒绝");
        }
    }

    @Test
    @DisplayName("字段重名被拒")
    public void testDuplicateColumn() {
        DataModelSaveReqDTO dto = validModel();
        dto.getColumns().add(column("order_no", "STRING"));
        assertFalse(validator.validate(dto).isPassed());
    }

    @Test
    @DisplayName("字段缺注释被拒 —— VO 上没有 @NotEmpty，Service 层才拦")
    public void testMissingComment() {
        DataModelSaveReqDTO dto = validModel();
        dto.getColumns().get(0).setColumnComment("  ");
        assertFalse(validator.validate(dto).isPassed());
    }

    @Test
    @DisplayName("类型不在受支持列表里：要把可选值列出来")
    public void testUnsupportedType() {
        DataModelSaveReqDTO dto = validModel();
        dto.getColumns().get(0).setColumnType("VARCHAR");

        AgentValidateResult result = validator.validate(dto);

        assertFalse(result.isPassed());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("STRING")),
                "要列出可选类型：" + result.getErrors());
    }

    @Test
    @DisplayName("DECIMAL 的 scale 必须严格小于 length")
    public void testDecimalScale() {
        DataModelSaveReqDTO dto = validModel();
        DataModelSaveReqDTO.ColumnDTO amount = column("amount", "DECIMAL");
        amount.setLength(2);
        amount.setScale(2);
        dto.getColumns().add(amount);

        assertFalse(validator.validate(dto).isPassed());
    }

    @Test
    @DisplayName("STRING 长度超上限被拒")
    public void testStringTooLong() {
        DataModelSaveReqDTO dto = validModel();
        dto.getColumns().get(0).setLength(99999);
        assertFalse(validator.validate(dto).isPassed());
    }

    // ==================== 静默改写（warning）====================

    @Test
    @DisplayName("STRING 不写长度：不报错，但要警告会变成 varchar(255)")
    public void testStringDefaultLengthWarning() {
        DataModelSaveReqDTO dto = validModel();
        dto.getColumns().get(0).setLength(null);

        AgentValidateResult result = validator.validate(dto);

        // 后端不会拦这个 —— 它会静默用 255
        assertTrue(result.isPassed(), "这不该阻断建表");
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("varchar(255)")),
                "实际：" + result.getWarnings());
    }

    @Test
    @DisplayName("DECIMAL 不写长度精度：警告会变成 decimal(10,2)")
    public void testDecimalDefaultWarning() {
        DataModelSaveReqDTO dto = validModel();
        dto.getColumns().add(column("amount", "DECIMAL"));

        AgentValidateResult result = validator.validate(dto);

        assertTrue(result.isPassed());
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("decimal(10,2)")),
                "实际：" + result.getWarnings());
    }

    @Test
    @DisplayName("TEXT 带默认值：警告会被静默丢弃")
    public void testTextDefaultValueWarning() {
        DataModelSaveReqDTO dto = validModel();
        DataModelSaveReqDTO.ColumnDTO remark = column("remark", "TEXT");
        remark.setDefaultValue("无");
        dto.getColumns().add(remark);

        AgentValidateResult result = validator.validate(dto);

        assertTrue(result.isPassed());
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("静默丢弃")),
                "实际：" + result.getWarnings());
    }

    @Test
    @DisplayName("默认值写空串：警告后端会当作「没设默认值」")
    public void testEmptyDefaultValueWarning() {
        DataModelSaveReqDTO dto = validModel();
        dto.getColumns().get(0).setDefaultValue("");

        AgentValidateResult result = validator.validate(dto);

        assertTrue(result.isPassed());
        assertFalse(result.getWarnings().isEmpty());
    }

    // ==================== 索引 ====================

    /**
     * 🔴 这条的后果是「唯一约束没建上，而界面显示得好好的」—— 直到某天出现重复数据
     */
    @Test
    @DisplayName("🔴 indexType 写成小写 unique 被拒 —— 后端只认精确大写，否则按普通索引建且不报错")
    public void testLowercaseUniqueRejected() {
        DataModelSaveReqDTO dto = validModel();
        DataModelSaveReqDTO.IndexDTO index = new DataModelSaveReqDTO.IndexDTO();
        index.setIndexName("uk_order_no");
        index.setIndexType("unique");
        index.setColumnNames(List.of("order_no"));
        dto.setIndexes(List.of(index));

        AgentValidateResult result = validator.validate(dto);

        assertFalse(result.isPassed());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("精确大写")),
                "实际：" + result.getErrors());
    }

    @Test
    @DisplayName("索引命名前缀不对：给出该改成什么")
    public void testIndexNamePrefix() {
        DataModelSaveReqDTO dto = validModel();
        DataModelSaveReqDTO.IndexDTO index = new DataModelSaveReqDTO.IndexDTO();
        index.setIndexName("order_no_index");
        index.setIndexType("NORMAL");
        index.setColumnNames(List.of("order_no"));
        dto.setIndexes(List.of(index));

        AgentValidateResult result = validator.validate(dto);

        assertFalse(result.isPassed());
        // 建议里要去掉冗余的 _index 后缀，给出 idx_order_no 而不是 idx_order_no_index ——
        // 后者虽然合规，但会在库里留下一个别扭的索引名，而这是 AI 建的表，
        // 没人会回头去改它
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("idx_order_no")),
                "实际：" + result.getErrors());
        assertTrue(result.getErrors().stream().noneMatch(e -> e.contains("idx_order_no_index")),
                "不该建议出带冗余后缀的名字：" + result.getErrors());
    }

    @Test
    @DisplayName("合规的唯一索引要能通过")
    public void testValidUniqueIndex() {
        DataModelSaveReqDTO dto = validModel();
        DataModelSaveReqDTO.IndexDTO index = new DataModelSaveReqDTO.IndexDTO();
        index.setIndexName("uk_order_no");
        index.setIndexType("UNIQUE");
        index.setColumnNames(List.of("order_no"));
        dto.setIndexes(List.of(index));

        assertTrue(validator.validate(dto).isPassed());
    }

    /**
     * 🔴 JSON 能建列、但建不了索引（MySQL 报 <b>3152</b>「supports indexing only via generated
     * columns on a specified JSON path」—— 注意不是 TEXT 那个 1170，两者补救方向相反：
     * 1170 给个前缀长度就能建，3152 给了也没用）。这条拦的是「体检放过、真跑才炸」：
     * 建表是不可逆的，等 DDL 执行到一半再抛，用户拿到的是半张表 ——
     * 而且 {@code DdlExecutor} 只取 {@code ex.getMessage()}，那句还是 Spring 的
     * 「bad SQL grammar [SQL]」，连「JSON」三个字都不含。
     *
     * <p>JSON 是 2026-10-04 才进建表枚举的（用户裁定「改建表枚举，加上 JSON」）。
     * 加它的同时必须加这条 —— 否则等于新开了一个洞：前端那个「索引列」下拉
     * 会把 JSON 列一并列出来，而此前没有任何一处校验拦它。
     *
     * <p>错误码逐类实测于 infra 的 {@code DataModelMysqlIntegrationTest#testJsonColumnEndToEnd}。
     */
    @Test
    @DisplayName("🔴 索引列是 JSON：拦在建表之前，报 3152 并给出改法")
    public void testJsonIndexColumnRejected() {
        DataModelSaveReqDTO dto = validModel();
        dto.getColumns().add(column("extra_attrs", "JSON"));
        DataModelSaveReqDTO.IndexDTO index = new DataModelSaveReqDTO.IndexDTO();
        index.setIndexName("idx_extra_attrs");
        index.setIndexType("NORMAL");
        index.setColumnNames(List.of("extra_attrs"));
        dto.setIndexes(List.of(index));

        AgentValidateResult result = validator.validate(dto);

        assertFalse(result.isPassed());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("3152") && e.contains("extra_attrs")),
                "实际：" + result.getErrors());
        // 🔴 两面都钉：JSON 必须是 3152，且**不能**顺手写成 1170 —— 那个三元分支一旦被写反，
        //    只断言「有某个码」是抓不到的，而错的码会把人引向「加个前缀长度」这个对 JSON 无效的改法
        assertTrue(result.getErrors().stream().noneMatch(e -> e.contains("1170")),
                "JSON 那条错里不该出现 1170（那是 TEXT/LONG_TEXT 的码）：" + result.getErrors());
        // 🔴 光说「不行」模型改不动，必须给出改法。这句是照着本文件 §5.5 规则 1 那条的
        //    理由写的：报错信息若来自 MysqlDdlBuilder，模型看不出该怎么改。
        //    ⚠️ 改法里刻意不提「用生成列」—— 平台没有生成列，提了只会引向建不出来的设计
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("另建一个")),
                "没给改法，模型只会把索引删了或换个名字重试：" + result.getErrors());
    }

    /**
     * TEXT / LONG_TEXT 一样建不了索引 —— 这个洞在 JSON 之前**就一直开着**，
     * 顺手一起堵上。堵它不可能弄坏任何能用的配置：MySQL 本来就会拒，
     * 区别只是「干净的中文错误」还是「执行到一半的 1170」。
     *
     * <p>与上一条一起钉住那个按类型分叉的错误码：TEXT 这两类是 1170，不是 JSON 的 3152。
     */
    @Test
    @DisplayName("索引列是 TEXT / LONG_TEXT：同样拦下，报 1170（这个洞在 JSON 之前就开着）")
    public void testTextIndexColumnRejected() {
        for (String type : List.of("TEXT", "LONG_TEXT")) {
            DataModelSaveReqDTO dto = validModel();
            dto.getColumns().add(column("remark", type));
            DataModelSaveReqDTO.IndexDTO index = new DataModelSaveReqDTO.IndexDTO();
            index.setIndexName("idx_remark");
            index.setIndexType("NORMAL");
            index.setColumnNames(List.of("remark"));
            dto.setIndexes(List.of(index));

            AgentValidateResult result = validator.validate(dto);

            assertFalse(result.isPassed(), type + " 索引列本该被拦");
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains(type) && e.contains("1170")),
                    type + " 的错误里没点到类型名或错误码不对：" + result.getErrors());
            assertTrue(result.getErrors().stream().noneMatch(e -> e.contains("3152")),
                    type + " 那条错里不该出现 3152（那是 JSON 的码）：" + result.getErrors());
        }
    }

    /**
     * 反向探针：加了这条规则之后，JSON **列**本身必须照样能建。
     * 守的是「别把守卫写宽了」—— 只要出现了 JSON 就一律拒，那用户裁定要加的类型等于没加。
     */
    @Test
    @DisplayName("JSON 列不建索引时要能通过 —— 守卫只针对「做键」，不是针对类型本身")
    public void testJsonColumnWithoutIndexPasses() {
        DataModelSaveReqDTO dto = validModel();
        dto.getColumns().add(column("extra_attrs", "JSON"));

        AgentValidateResult result = validator.validate(dto);

        assertTrue(result.isPassed(), "不该报错：" + result.getErrors());
        assertTrue(result.getWarnings().isEmpty(), "不该有警告：" + result.getWarnings());
    }

    /**
     * JSON 的默认值会被静默丢弃（MySQL 只收 {@code DEFAULT ('{}')} 这种表达式形式，
     * 而 MysqlDdlBuilder 刻意不发它 —— 理由写在那里的 appendDefaultValue 上）。
     * 丢弃本身没法避免，但**必须说出来**：这正是本方法存在的那一类缺陷。
     */
    @Test
    @DisplayName("JSON 带默认值：不拦，但要有一条 warning 说清它会被丢掉")
    public void testJsonDefaultValueWarned() {
        DataModelSaveReqDTO dto = validModel();
        DataModelSaveReqDTO.ColumnDTO jsonColumn = column("extra_attrs", "JSON");
        jsonColumn.setDefaultValue("{}");
        dto.getColumns().add(jsonColumn);

        AgentValidateResult result = validator.validate(dto);

        assertTrue(result.isPassed(), "默认值被丢弃不该让建表失败：" + result.getErrors());
        assertTrue(result.getWarnings().stream()
                        .anyMatch(w -> w.contains("extra_attrs") && w.contains("静默丢弃")),
                "实际：" + result.getWarnings());
    }

    /**
     * 🔴 索引列写的是**别的列的名字**（拼错、或指着一个没定义的列）时，
     * 这条守卫必须闭嘴 —— 「列不存在」由 infra 侧 DATA_MODEL_INDEX_COLUMN_INVALID 负责，
     * 两处都报会给模型两条互相矛盾的错，它无从判断该改哪一条。
     */
    @Test
    @DisplayName("索引列压根不存在：不套用「不可建键」那条错，留给 infra 去判")
    public void testMissingIndexColumnDoesNotClaimNonKeyable() {
        DataModelSaveReqDTO dto = validModel();
        dto.getColumns().add(column("extra_attrs", "JSON"));
        DataModelSaveReqDTO.IndexDTO index = new DataModelSaveReqDTO.IndexDTO();
        index.setIndexName("idx_not_there");
        index.setIndexType("NORMAL");
        index.setColumnNames(List.of("not_there"));
        dto.setIndexes(List.of(index));

        AgentValidateResult result = validator.validate(dto);

        // 🔴 挑的是「不能建索引」这句**措辞**，不是错误码：本例的索引列指向 not_there（查不到类型），
        //    而 dto 里那个 JSON 列真被误判时报的会是 3152 —— 若只断言 noneMatch("1170")，
        //    守卫即使误判也照样绿，对照半边就恒真了（同本仓「恒真的断言挡不住缺陷」那类疤）
        assertTrue(result.getErrors().stream().noneMatch(e -> e.contains("不能建索引")),
                "列不存在却被判成「不可建键」，两条错会让模型无从下手：" + result.getErrors());
    }

    // ==================== 流程回写列（B18） ====================

    /**
     * 🔴 复现的是实录第 6 轮那次真事故，不是假想形状
     *
     * 用户说「这张表后面要挂审批流并按样品类型分流」，模型建表时自己补了两列，
     * {@code process_instance_id} 给对了（STRING 64），{@code process_status} 给成了 STRING(32)。
     * 建表、存草稿、发布、列表录入查询<b>全都成功</b>；到要挂审批流那一刻，
     * BPM 的在线表单下拉把这张表单标成「不可用：process_status 列类型为 VARCHAR，需改为整数型」，
     * 而数据模型页那个唯一的补齐入口只按列名判存在，报一句「相关字段已存在，无需重复添加」。
     * 建表不可逆 —— 用户除了手写 ALTER 没有出路。
     */
    @Test
    @DisplayName("🔴 process_status 建成字符串：拦下，并把该写成什么说全（实录第 6 轮的真事故）")
    public void testProcessStatusAsStringRejected() {
        DataModelSaveReqDTO dto = validModel();
        dto.getColumns().add(column("process_instance_id", "STRING"));
        DataModelSaveReqDTO.ColumnDTO status = column("process_status", "STRING");
        status.setLength(32);
        dto.getColumns().add(status);

        AgentValidateResult result = validator.validate(dto);

        assertFalse(result.isPassed(), "STRING 的流程状态列必须被拦下");
        String error = result.getErrors().stream()
                .filter(e -> e.contains("process_status"))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "要指名是哪一列，实际：" + result.getErrors()));
        // 🔴 断言的是「模型照着改一轮就能对」这件事，而不是文案措辞：
        //    合格的三个受控类型都要出现在同一条里（一次说完），
        //    且要说清后果是「挂不上审批流」而不是「类型不合法」——
        //    后者会让模型以为换个长度就行
        assertTrue(error.contains("TINYINT") && error.contains("INT") && error.contains("LONG"),
                "要把合格的类型一次列全，实际：" + error);
        assertTrue(error.contains("审批流"), "要说清后果，实际：" + error);
        // 同一次校验里，另一列（process_instance_id STRING 64）不该被牵连
        assertTrue(result.getErrors().stream().noneMatch(e -> e.contains("process_instance_id")),
                "合格的那一列不该被点名，实际：" + result.getErrors());
    }

    @Test
    @DisplayName("process_status 用 TINYINT / INT / LONG 都要放行 —— 三个都是整数族")
    public void testProcessStatusIntegerTypesAccepted() {
        for (String type : List.of("TINYINT", "INT", "LONG")) {
            DataModelSaveReqDTO dto = validModel();
            dto.getColumns().add(column("process_status", type));
            assertTrue(validator.validate(dto).isPassed(),
                    type + " 是合格的流程状态列类型，实际：" + validator.validate(dto).getErrors());
        }
    }

    @Test
    @DisplayName("process_instance_id 长度不够：拦下并说出下限（后端会静默放行，短了就是截断）")
    public void testProcessInstanceIdTooShort() {
        DataModelSaveReqDTO dto = validModel();
        DataModelSaveReqDTO.ColumnDTO instanceId = column("process_instance_id", "STRING");
        instanceId.setLength(32);
        dto.getColumns().add(instanceId);

        AgentValidateResult result = validator.validate(dto);

        assertFalse(result.isPassed());
        assertTrue(result.getErrors().stream()
                        .anyMatch(e -> e.contains("process_instance_id") && e.contains("64")),
                "要给出下限 64，实际：" + result.getErrors());
    }

    /**
     * ⚠️ 与「STRING 不写长度」那一档刻意区分开：不写长度时后端静默补 255（≥64），
     * 是合法的，本条不该开口 —— 那条静默改写归 {@code validateSilentRewrites} 说。
     */
    @Test
    @DisplayName("process_instance_id 不写长度：放行（后端补 255，比下限大）")
    public void testProcessInstanceIdWithoutLengthPasses() {
        DataModelSaveReqDTO dto = validModel();
        DataModelSaveReqDTO.ColumnDTO instanceId = column("process_instance_id", "STRING");
        instanceId.setLength(null);
        dto.getColumns().add(instanceId);

        assertTrue(validator.validate(dto).isPassed(),
                "实际：" + validator.validate(dto).getErrors());
    }

    /**
     * 🔴 这一条钉的是「沉默」本身
     *
     * 约束名单读不到时整条不报 —— 判据缺了一半还去报 error，报出来的是「类型不合格」
     * 却给不出「该改成什么」，模型改不动，白烧一轮。但「沉默」如果不被断言，
     * 它就只是一个碰巧的绿：名单哪天没接上，上面那几条会一起变绿，没人会发现。
     */
    @Test
    @DisplayName("🔴 约束名单读不到时整条沉默（把 fail-soft 钉成被断言的行为）")
    public void testSilentWhenFlowConstraintsMissing() {
        DataModelApi.TableConstraintsRespDTO constraints = dataModelApi.getTableConstraints();
        constraints.setFlowColumns(null);
        DataModelSaveReqDTO dto = validModel();
        DataModelSaveReqDTO.ColumnDTO status = column("process_status", "STRING");
        status.setLength(32);
        dto.getColumns().add(status);

        assertTrue(validator.validate(dto).isPassed(),
                "名单读不到就不该报，实际：" + validator.validate(dto).getErrors());
    }

    /**
     * ⚠️ 业务表有自己的 {@code status} 是常态（{@code process_status} 与它解耦正是为了同表共存，
     * 见 {@code FormDesignApiImpl#COLUMN_PROCESS_STATUS} 的注释）。
     * 把 {@code status} 也一起判的话，每张带状态字段的表都建不出来。
     */
    @Test
    @DisplayName("普通业务列 status / process_status_text 不受这条约束")
    public void testOnlyReservedNamesJudged() {
        DataModelSaveReqDTO dto = validModel();
        dto.getColumns().add(column("status", "STRING"));
        dto.getColumns().add(column("process_status_text", "STRING"));

        assertTrue(validator.validate(dto).isPassed(),
                "实际：" + validator.validate(dto).getErrors());
    }

    // ==================== 反馈文本 ====================

    @Test
    @DisplayName("🔴 一次把所有错误都列出来，而不是报第一个就返回")
    public void testCollectsAllErrors() {
        DataModelSaveReqDTO dto = new DataModelSaveReqDTO();
        dto.setTableName("PurchaseRequest");   // 缺前缀 + 驼峰
        dto.setTableComment("x");              // 太短
        DataModelSaveReqDTO.ColumnDTO bad = new DataModelSaveReqDTO.ColumnDTO();
        bad.setColumnName("tenant_id");        // 系统列同名
        bad.setColumnType("VARCHAR");          // 类型不存在
        bad.setPrimaryKey(true);               // 不许设主键
        dto.setColumns(List.of(bad));

        AgentValidateResult result = validator.validate(dto);

        // 一次只报一个错，模型改完再撞下一个 —— 三轮上限很快烧完，每轮都是真金白银
        assertTrue(result.getErrors().size() >= 5,
                "应该一次列出全部问题，实际只有 " + result.getErrors().size()
                        + " 条：" + result.getErrors());
        assertTrue(result.toFeedback().contains("必须修正"));
    }

}
