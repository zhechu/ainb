package cn.enlev.ainb.module.agent.framework.agent.core.validate;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.api.datamodel.DataModelApi;
import cn.enlev.ainb.module.infra.api.datamodel.dto.DataModelSaveReqDTO;
import cn.enlev.ainb.module.infra.api.datamodel.dto.DataModelStructureRespDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

/**
 * {@link DataModelUpdateValidator} 的单元测试
 *
 * <p>改表是整套工具里最高危的一个，所以这里的用例全是「该拦住的有没有拦住」。
 * B4-3（删列在 AI 路径上一律拒）与 B4-4（乐观锁基线必须带上）之后，
 * 原来「删列只出警告」的口径已经反过来了 —— 相关用例一并改写。
 *
 * @author 走进新时代
 */
public class DataModelUpdateValidatorTest extends BaseMockitoUnitTest {

    @InjectMocks
    private DataModelUpdateValidator validator;

    @Mock
    private DataModelApi dataModelApi;

    @BeforeEach
    public void setUp() {
        DataModelApi.TableConstraintsRespDTO constraints = new DataModelApi.TableConstraintsRespDTO();
        // ⚠️ 名单里刻意带上 version：它并不是每张表都有，用来验「判据是现有表里有的，不是名单里列的」
        constraints.setSystemColumnNames(Arrays.asList(
                "id", "creator", "create_time", "updater", "update_time",
                "deleted", "tenant_id", "version"));
        // 流程回写列（B18）：改表这条只判「新增的」与「类型被改过的」，判据同样从这份约束读
        constraints.setFlowColumns(Arrays.asList(
                flowColumn("process_instance_id", List.of("STRING"), 64),
                flowColumn("process_status", List.of("TINYINT", "INT", "LONG"), null)));
        lenient().when(dataModelApi.getTableConstraints()).thenReturn(constraints);
    }

    // ==================== 系统列 ====================

    @Test
    @DisplayName("🔴 漏掉原有的系统列 = 要删它，必须拦")
    public void testMissingSystemColumn() {
        DataModelStructureRespDTO current = structure("biz_a",
                "id", "order_no", "tenant_id", "deleted");
        // 提交时漏了 tenant_id
        DataModelSaveReqDTO reqDTO = request(1L, "biz_a", "id", "order_no", "deleted");

        AgentValidateResult result = validator.validate(reqDTO, current);
        assertFalse(result.isPassed());
        assertTrue(result.toFeedback().contains("tenant_id"), result.toFeedback());
        assertTrue(result.toFeedback().contains("跨租户"), "要说清楚后果：" + result.toFeedback());
    }

    @Test
    @DisplayName("🔴 表里本来就没有 version，不该因此判定「缺列」")
    public void testSystemColumnNotInThisTable() {
        // 这张表没有 version（TENANT profile 不注入它）
        DataModelStructureRespDTO current = structure("biz_a",
                "id", "order_no", "creator", "create_time", "updater", "update_time",
                "deleted", "tenant_id");
        DataModelSaveReqDTO reqDTO = request(1L, "biz_a",
                "id", "order_no", "creator", "create_time", "updater", "update_time",
                "deleted", "tenant_id");

        AgentValidateResult result = validator.validate(reqDTO, current);
        // 拿名单当必填清单的话，这张表会永远改不了 —— 实测踩过
        assertTrue(result.isPassed(), "不该因为名单里有 version 就拦下来：" + result.toFeedback());
    }

    // ==================== 表名与主键 ====================

    @Test
    @DisplayName("改表名要拦 —— 后端会静默丢弃，改了等于没改")
    public void testTableNameChanged() {
        DataModelStructureRespDTO current = structure("biz_a", "id", "order_no");
        DataModelSaveReqDTO reqDTO = request(1L, "biz_b", "id", "order_no");

        AgentValidateResult result = validator.validate(reqDTO, current);
        assertFalse(result.isPassed());
        assertTrue(result.toFeedback().contains("不能改表名"), result.toFeedback());
    }

    @Test
    @DisplayName("改主键要拦")
    public void testPrimaryKeyChanged() {
        DataModelStructureRespDTO current = structure("biz_a", "id", "order_no");
        current.getColumns().get(0).setPrimaryKey(true);
        DataModelSaveReqDTO reqDTO = request(1L, "biz_a", "id", "order_no");
        // 把主键挪到 order_no 上
        reqDTO.getColumns().get(1).setPrimaryKey(true);

        AgentValidateResult result = validator.validate(reqDTO, current);
        assertFalse(result.isPassed());
        assertTrue(result.toFeedback().contains("不能改主键"), result.toFeedback());
    }

    @Test
    @DisplayName("缺少模型编号要拦 —— 不带 id 会被当成新建")
    public void testMissingId() {
        DataModelStructureRespDTO current = structure("biz_a", "id", "order_no");
        DataModelSaveReqDTO reqDTO = request(null, "biz_a", "id", "order_no");

        AgentValidateResult result = validator.validate(reqDTO, current);
        assertFalse(result.isPassed());
        assertTrue(result.toFeedback().contains("模型编号"), result.toFeedback());
    }

    // ==================== riskConfirmed ====================

    @Test
    @DisplayName("🔴 AI 不许设置 riskConfirmed —— 那是人看过数据之后的判断")
    public void testRiskConfirmedForbidden() {
        DataModelStructureRespDTO current = structure("biz_a", "id", "order_no");
        DataModelSaveReqDTO reqDTO = request(1L, "biz_a", "id", "order_no");
        reqDTO.setRiskConfirmed(true);

        AgentValidateResult result = validator.validate(reqDTO, current);
        assertFalse(result.isPassed());
        assertTrue(result.toFeedback().contains("riskConfirmed"), result.toFeedback());
        assertTrue(result.toFeedback().contains("数据模型界面"), "要指出正确的去处：" + result.toFeedback());
    }

    // ==================== 删业务列 ====================

    @Test
    @DisplayName("🔴 删业务列一律拦 —— 空表也拦，不再只是给个警告")
    public void testDropBusinessColumnRejected() {
        DataModelStructureRespDTO current = structure("biz_a", "id", "order_no", "remark");
        DataModelSaveReqDTO reqDTO = request(1L, "biz_a", "id", "order_no");

        AgentValidateResult result = validator.validate(reqDTO, current);
        assertFalse(result.isPassed(), "B4-3 之后删列在 AI 路径上不存在：" + result.toFeedback());
        String feedback = result.toFeedback();
        assertTrue(feedback.contains("remark"), "要点名是哪一列：" + feedback);
        assertTrue(feedback.contains("数据模型"), "要说清楚该去哪儿做：" + feedback);
    }

    @Test
    @DisplayName("🔴 只提交一个新字段 = 把其它业务列全删了 —— 最典型的误用，必须拦")
    public void testAddOnlyNewColumnIsRejectedAsMassDrop() {
        // 「加一个字段」的直觉写法。改表是全量同步语义，这样写等于删掉 order_no / amount
        DataModelStructureRespDTO current = structure("biz_a", "id", "order_no", "amount");
        DataModelSaveReqDTO reqDTO = request(1L, "biz_a", "memo");

        AgentValidateResult result = validator.validate(reqDTO, current);
        assertFalse(result.isPassed());
        String feedback = result.toFeedback();
        assertTrue(feedback.contains("order_no") && feedback.contains("amount"),
                "两列都要点名：" + feedback);
        assertTrue(feedback.contains("漏了这几列"), "要提示这可能是漏传而不是真要删：" + feedback);
    }

    @Test
    @DisplayName("🔴 就算带上 confirmDropColumns 也拦 —— 那个标记不许由 AI 设置")
    public void testDropRejectedEvenWithConfirmTrue() {
        DataModelStructureRespDTO current = structure("biz_a", "id", "order_no", "remark");
        DataModelSaveReqDTO reqDTO = request(1L, "biz_a", "id", "order_no");
        reqDTO.setConfirmDropColumns(true);

        AgentValidateResult result = validator.validate(reqDTO, current);
        assertFalse(result.isPassed());
        String feedback = result.toFeedback();
        // 两条都要在：删列那条说「不许 AI 删」，标记那条说「不许 AI 设这个位」
        assertTrue(feedback.contains("remark"), feedback);
        assertTrue(feedback.contains("confirmDropColumns"), feedback);
    }

    @Test
    @DisplayName("只加字段时不该有任何拦阻")
    public void testAddColumnOnlyPasses() {
        DataModelStructureRespDTO current = structure("biz_a", "id", "order_no");
        DataModelSaveReqDTO reqDTO = request(1L, "biz_a", "id", "order_no", "status");

        AgentValidateResult result = validator.validate(reqDTO, current);
        assertTrue(result.isPassed(), result.toFeedback());
        assertTrue(result.getWarnings().isEmpty(), "只加字段不该有警告：" + result.getWarnings());
    }

    @Test
    @DisplayName("删掉的只有系统列时不重复刷屏 —— 那一条由系统列校验说")
    public void testSystemColumnDropNotReportedTwice() {
        DataModelStructureRespDTO current = structure("biz_a", "id", "order_no", "tenant_id");
        DataModelSaveReqDTO reqDTO = request(1L, "biz_a", "id", "order_no");

        AgentValidateResult result = validator.validate(reqDTO, current);
        assertFalse(result.isPassed());
        long dropErrors = result.getErrors().stream()
                .filter(error -> error.contains("会删除业务列")).count();
        assertEquals(0, dropErrors, "系统列缺失已由 validateSystemColumns 报过：" + result.getErrors());
        assertTrue(result.toFeedback().contains("缺少系统列"), result.toFeedback());
    }

    // ==================== confirmDropColumns ====================

    @Test
    @DisplayName("🔴 AI 不许设置 confirmDropColumns —— 那是人确实要删这几列的意思")
    public void testConfirmDropColumnsForbidden() {
        DataModelStructureRespDTO current = structure("biz_a", "id", "order_no");
        DataModelSaveReqDTO reqDTO = request(1L, "biz_a", "id", "order_no");
        reqDTO.setConfirmDropColumns(true);

        AgentValidateResult result = validator.validate(reqDTO, current);
        assertFalse(result.isPassed());
        String feedback = result.toFeedback();
        assertTrue(feedback.contains("confirmDropColumns"), feedback);
        assertTrue(feedback.contains("同意执行这个计划"),
                "要说清楚它跟用户在卡片上点的不是同一件事：" + feedback);
    }

    // ==================== 乐观锁基线 ====================

    @Test
    @DisplayName("🔴 没带 version 要拦 —— 缺了它后端乐观锁形同没开")
    public void testMissingVersionRejected() {
        DataModelStructureRespDTO current = structure("biz_a", "id", "order_no");
        DataModelSaveReqDTO reqDTO = request(1L, "biz_a", "id", "order_no");
        reqDTO.setVersion(null);

        AgentValidateResult result = validator.validate(reqDTO, current);
        assertFalse(result.isPassed());
        String feedback = result.toFeedback();
        assertTrue(feedback.contains("version"), feedback);
        assertTrue(feedback.contains("工具链有问题"),
                "这一条模型自己修不了，要说清是工具的问题：" + feedback);
    }

    @Test
    @DisplayName("version 为 0 是合法基线，不能当成「没带」")
    public void testZeroVersionIsCarried() {
        DataModelStructureRespDTO current = structure("biz_a", "id", "order_no");
        DataModelSaveReqDTO reqDTO = request(1L, "biz_a", "id", "order_no", "status");
        reqDTO.setVersion(0);

        AgentValidateResult result = validator.validate(reqDTO, current);
        assertTrue(result.isPassed(),
                "新建后还没改过的记录 version 就是 0，判成缺失会让这类表永远改不了：" + result.toFeedback());
    }

    // ==================== 流程回写列（B18） ====================

    @Test
    @DisplayName("🔴 新加的 process_status 建成 STRING 要拦 —— 类型错不报错，只会让这张表永远挂不上审批流")
    public void testNewProcessStatusAsStringRejected() {
        DataModelStructureRespDTO current = structure("biz_a", "id", "order_no");
        DataModelSaveReqDTO reqDTO = request(1L, "biz_a", "id", "order_no", "process_status");
        setType(reqDTO, "process_status", "STRING");

        AgentValidateResult result = validator.validate(reqDTO, current);
        assertFalse(result.isPassed(), result.toFeedback());
        String feedback = result.toFeedback();
        assertTrue(feedback.contains("process_status"), "要点名是哪一列：" + feedback);
        assertTrue(feedback.contains("TINYINT"), "要给出合格类型：" + feedback);
        assertTrue(feedback.contains("审批流"), "要说清楚毁掉的是之后哪一步：" + feedback);
    }

    @Test
    @DisplayName("🔴 本来就是 varchar 的 process_status，原样带过去不判 —— 否则这张表连无关的改动都做不了")
    public void testUntouchedBadFlowColumnIsSilent() {
        // 改表是全量同步语义：类型没动，这一列也照样要出现在提交里
        DataModelStructureRespDTO current = structure("biz_a", "id", "order_no", "process_status");
        setType(current, "process_status", "STRING");
        DataModelSaveReqDTO reqDTO = request(1L, "biz_a", "id", "order_no", "process_status");
        setType(reqDTO, "process_status", "STRING");

        AgentValidateResult result = validator.validate(reqDTO, current);
        assertTrue(result.isPassed(),
                "这张表已经错了，但 AI 至少还得能改它别的字段；把类型改过来是「本次改动」，不是「顺带审判」："
                        + result.toFeedback());
    }

    @Test
    @DisplayName("把 process_status 的类型改成整数 = 在修它，必须放行")
    public void testFlowColumnTypeFixedPasses() {
        DataModelStructureRespDTO current = structure("biz_a", "id", "process_status");
        setType(current, "process_status", "STRING");
        DataModelSaveReqDTO reqDTO = request(1L, "biz_a", "id", "process_status");
        setType(reqDTO, "process_status", "INT");

        AgentValidateResult result = validator.validate(reqDTO, current);
        assertTrue(result.isPassed(), result.toFeedback());
    }

    @Test
    @DisplayName("新加的 process_instance_id 长度不够要拦 —— 值会被截断，流程实例就对不上了")
    public void testNewProcessInstanceIdTooShortRejected() {
        DataModelStructureRespDTO current = structure("biz_a", "id");
        DataModelSaveReqDTO reqDTO = request(1L, "biz_a", "id", "process_instance_id");
        setType(reqDTO, "process_instance_id", "STRING");
        reqDTO.getColumns().get(1).setLength(32);

        AgentValidateResult result = validator.validate(reqDTO, current);
        assertFalse(result.isPassed(), result.toFeedback());
        assertTrue(result.toFeedback().contains("64"), "要说出下限是多少：" + result.toFeedback());
    }

    // ==================== 造数据 ====================

    private DataModelStructureRespDTO structure(String tableName, String... columnNames) {
        DataModelStructureRespDTO dto = new DataModelStructureRespDTO();
        dto.setTableName(tableName);
        List<DataModelStructureRespDTO.ColumnDTO> columns = new ArrayList<>();
        for (String name : columnNames) {
            DataModelStructureRespDTO.ColumnDTO column = new DataModelStructureRespDTO.ColumnDTO();
            column.setColumnName(name);
            column.setPrimaryKey("id".equals(name));
            columns.add(column);
        }
        dto.setColumns(columns);
        return dto;
    }

    private DataModelSaveReqDTO request(Long id, String tableName, String... columnNames) {
        DataModelSaveReqDTO dto = new DataModelSaveReqDTO();
        dto.setId(id);
        dto.setTableName(tableName);
        // 默认带上合法的乐观锁基线：生产路径上这个值由 DataModelParamResolver 从取回的结构里填，
        // 这里统一给 0，免得每条用例都被 version 校验绊住 —— 缺 version 单独有用例覆盖
        dto.setVersion(0);
        List<DataModelSaveReqDTO.ColumnDTO> columns = new ArrayList<>();
        for (String name : columnNames) {
            DataModelSaveReqDTO.ColumnDTO column = new DataModelSaveReqDTO.ColumnDTO();
            column.setColumnName(name);
            column.setPrimaryKey("id".equals(name));
            columns.add(column);
        }
        dto.setColumns(columns);
        return dto;
    }

    /** 改表侧的「类型」只有流程回写列那一条规则要判，所以这里按需单点设值，不把造数据搞成两张类型表 */
    private static void setType(DataModelStructureRespDTO structure, String columnName, String type) {
        structure.getColumns().stream()
                .filter(column -> columnName.equals(column.getColumnName()))
                .forEach(column -> column.setColumnType(type));
    }

    private static void setType(DataModelSaveReqDTO reqDTO, String columnName, String type) {
        reqDTO.getColumns().stream()
                .filter(column -> columnName.equals(column.getColumnName()))
                .forEach(column -> column.setColumnType(type));
    }

    private static DataModelApi.FlowColumnConstraint flowColumn(String name, List<String> allowedTypes,
                                                                Integer minLength) {
        DataModelApi.FlowColumnConstraint constraint = new DataModelApi.FlowColumnConstraint();
        constraint.setColumnName(name);
        constraint.setAllowedTypes(allowedTypes);
        constraint.setMinLength(minLength);
        return constraint;
    }

}
