package cn.enlev.ainb.module.agent.framework.agent.core.validate;

import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.bpm.api.definition.BpmModelApi;
import cn.enlev.ainb.module.bpm.api.definition.dto.BpmProcessConstraintsRespDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

/**
 * {@link BpmProcessValidator} 的单元测试
 *
 * <p>每条用例对应方案 §5.5「流程校验」里的一条硬规则。断言方式统一是
 * 「不通过 + 错误信息里提到关键字」—— 只断言 {@code isPassed()} 的话，
 * 校验器因为<b>另一个</b>原因拒绝也会让测试通过，那就测了个寂寞。
 *
 * @author 走进新时代
 */
public class BpmProcessValidatorTest extends BaseMockitoUnitTest {

    @InjectMocks
    private BpmProcessValidator validator;

    @Mock
    private BpmModelApi bpmModelApi;

    @BeforeEach
    public void setUp() {
        lenient().when(bpmModelApi.getProcessConstraints()).thenReturn(buildConstraints());
    }

    // ==================== 规则 1、2：树的形状 ====================

    @Test
    @DisplayName("完整的树应当通过")
    public void testValidate_success() {
        AgentValidateResult result = validator.validate("purchase_apply",
                BpmProcessValidator.FEW_SHOT_EXAMPLE);
        assertTrue(result.isPassed(), "示例骨架本身必须是合法的，否则 prompt 在教模型写错的：\n"
                + result.toFeedback());
    }

    @Test
    @DisplayName("规则 1：根节点不是发起人节点")
    public void testValidate_rootNotStartUser() {
        String json = """
                {"id":"n1","name":"审批","type":11,"approveType":1,"approveMethod":1,
                 "candidateStrategy":30,"candidateParam":"1",
                 "childNode":{"id":"EndEvent","name":"结束","type":1}}""";
        AgentValidateResult result = validator.validate("flow_a", json);
        assertFalse(result.isPassed());
        assertTrue(result.toFeedback().contains("根节点"), result.toFeedback());
    }

    @Test
    @DisplayName("规则 2：缺结束节点 —— 后端会直接 NPE，必须提前拦")
    public void testValidate_missingEndNode() {
        String json = """
                {"id":"StartUserNode","name":"发起人","type":10,
                 "childNode":{"id":"n1","name":"审批","type":11,"approveType":1,
                   "approveMethod":1,"candidateStrategy":30,"candidateParam":"1"}}""";
        AgentValidateResult result = validator.validate("flow_a", json);
        assertFalse(result.isPassed());
        assertTrue(result.toFeedback().contains("结束节点"), result.toFeedback());
    }

    @Test
    @DisplayName("节点缺 id —— 会被后端整棵静默丢弃")
    public void testValidate_missingNodeId() {
        String json = """
                {"id":"StartUserNode","name":"发起人","type":10,
                 "childNode":{"name":"审批","type":11,"approveType":1,"approveMethod":1,
                   "candidateStrategy":30,"candidateParam":"1",
                   "childNode":{"id":"EndEvent","name":"结束","type":1}}}""";
        AgentValidateResult result = validator.validate("flow_a", json);
        assertFalse(result.isPassed());
        assertTrue(result.toFeedback().contains("没有 id"), result.toFeedback());
    }

    // ==================== 规则 6：唯一会静默失效的一条 ====================

    @Test
    @DisplayName("🔴 规则 6：approveType 不是 1 —— 必须硬拦，不能只警告")
    public void testValidate_approveTypeNotUser() {
        String json = """
                {"id":"StartUserNode","name":"发起人","type":10,
                 "childNode":{"id":"n1","name":"审批","type":11,"approveType":2,
                   "approveMethod":1,"candidateStrategy":30,"candidateParam":"1",
                   "childNode":{"id":"EndEvent","name":"结束","type":1}}}""";
        AgentValidateResult result = validator.validate("flow_a", json);
        assertFalse(result.isPassed(), "approveType 错了必须是 error 而不是 warning —— "
                + "它是唯一一条部署成功却完全不生效的配置");
        assertTrue(result.toFeedback().contains("approveType"), result.toFeedback());
    }

    @Test
    @DisplayName("🔴 规则 6：漏写 approveType 同样要拦")
    public void testValidate_approveTypeMissing() {
        String json = """
                {"id":"StartUserNode","name":"发起人","type":10,
                 "childNode":{"id":"n1","name":"审批","type":11,
                   "approveMethod":1,"candidateStrategy":30,"candidateParam":"1",
                   "childNode":{"id":"EndEvent","name":"结束","type":1}}}""";
        AgentValidateResult result = validator.validate("flow_a", json);
        assertFalse(result.isPassed());
        assertTrue(result.toFeedback().contains("approveType"), result.toFeedback());
    }

    // ==================== 规则 4、5、9：审批节点配置 ====================

    @Test
    @DisplayName("规则 4：审批节点漏写 approveMethod")
    public void testValidate_missingApproveMethod() {
        String json = """
                {"id":"StartUserNode","name":"发起人","type":10,
                 "childNode":{"id":"n1","name":"审批","type":11,"approveType":1,
                   "candidateStrategy":30,"candidateParam":"1",
                   "childNode":{"id":"EndEvent","name":"结束","type":1}}}""";
        AgentValidateResult result = validator.validate("flow_a", json);
        assertFalse(result.isPassed());
        assertTrue(result.toFeedback().contains("approveMethod"), result.toFeedback());
    }

    @Test
    @DisplayName("规则 5：会签没给通过比例")
    public void testValidate_ratioRequired() {
        String json = """
                {"id":"StartUserNode","name":"发起人","type":10,
                 "childNode":{"id":"n1","name":"审批","type":11,"approveType":1,
                   "approveMethod":2,"candidateStrategy":30,"candidateParam":"1",
                   "childNode":{"id":"EndEvent","name":"结束","type":1}}}""";
        AgentValidateResult result = validator.validate("flow_a", json);
        assertFalse(result.isPassed());
        assertTrue(result.toFeedback().contains("approveRatio"), result.toFeedback());
    }

    @Test
    @DisplayName("规则 9：策略要求参数却没给")
    public void testValidate_candidateParamRequired() {
        String json = """
                {"id":"StartUserNode","name":"发起人","type":10,
                 "childNode":{"id":"n1","name":"审批","type":11,"approveType":1,
                   "approveMethod":1,"candidateStrategy":30,
                   "childNode":{"id":"EndEvent","name":"结束","type":1}}}""";
        AgentValidateResult result = validator.validate("flow_a", json);
        assertFalse(result.isPassed());
        assertTrue(result.toFeedback().contains("candidateParam"), result.toFeedback());
    }

    @Test
    @DisplayName("规则 9：竖线格式少给一段 —— 后端会 Assert 失败")
    public void testValidate_candidateParamPipeFormat() {
        String json = """
                {"id":"StartUserNode","name":"发起人","type":10,
                 "childNode":{"id":"n1","name":"审批","type":11,"approveType":1,
                   "approveMethod":1,"candidateStrategy":23,"candidateParam":"1,2",
                   "childNode":{"id":"EndEvent","name":"结束","type":1}}}""";
        AgentValidateResult result = validator.validate("flow_a", json);
        assertFalse(result.isPassed());
        assertTrue(result.toFeedback().contains("格式不对"), result.toFeedback());
    }

    // ==================== 规则 3：条件分支 ====================

    @Test
    @DisplayName("规则 3：条件分支没有默认分支")
    public void testValidate_branchWithoutDefaultFlow() {
        String json = """
                {"id":"StartUserNode","name":"发起人","type":10,
                 "childNode":{"id":"gw","name":"分支","type":51,
                   "conditionNodes":[
                     {"id":"c1","name":"条件1","type":50,
                      "conditionSetting":{"defaultFlow":false,"conditionType":1,
                        "conditionExpression":"${var:getOrDefault(a,null) > 1}"}}],
                   "childNode":{"id":"EndEvent","name":"结束","type":1}}}""";
        AgentValidateResult result = validator.validate("flow_a", json);
        assertFalse(result.isPassed());
        assertTrue(result.toFeedback().contains("默认分支"), result.toFeedback());
    }

    @Test
    @DisplayName("规则 3：条件节点缺 conditionSetting —— 后端会 NPE")
    public void testValidate_conditionNodeMissingSetting() {
        String json = """
                {"id":"StartUserNode","name":"发起人","type":10,
                 "childNode":{"id":"gw","name":"分支","type":51,
                   "conditionNodes":[
                     {"id":"c1","name":"条件1","type":50},
                     {"id":"c2","name":"其它","type":50,
                      "conditionSetting":{"defaultFlow":true}}],
                   "childNode":{"id":"EndEvent","name":"结束","type":1}}}""";
        AgentValidateResult result = validator.validate("flow_a", json);
        assertFalse(result.isPassed());
        assertTrue(result.toFeedback().contains("conditionSetting"), result.toFeedback());
    }

    @Test
    @DisplayName("conditionType 缺失 —— 该分支会变成恒成立，且不报错")
    public void testValidate_conditionTypeMissing() {
        String json = """
                {"id":"StartUserNode","name":"发起人","type":10,
                 "childNode":{"id":"gw","name":"分支","type":51,
                   "conditionNodes":[
                     {"id":"c1","name":"条件1","type":50,
                      "conditionSetting":{"defaultFlow":false}},
                     {"id":"c2","name":"其它","type":50,
                      "conditionSetting":{"defaultFlow":true}}],
                   "childNode":{"id":"EndEvent","name":"结束","type":1}}}""";
        AgentValidateResult result = validator.validate("flow_a", json);
        assertFalse(result.isPassed());
        assertTrue(result.toFeedback().contains("conditionType"), result.toFeedback());
    }

    @Test
    @DisplayName("表达式没用 getOrDefault —— 警告但不拦")
    public void testValidate_expressionWithoutNullGuard() {
        String json = """
                {"id":"StartUserNode","name":"发起人","type":10,
                 "childNode":{"id":"gw","name":"分支","type":51,
                   "conditionNodes":[
                     {"id":"c1","name":"条件1","type":50,
                      "conditionSetting":{"defaultFlow":false,"conditionType":1,
                        "conditionExpression":"${amount > 1000}"}},
                     {"id":"c2","name":"其它","type":50,
                      "conditionSetting":{"defaultFlow":true}}],
                   "childNode":{"id":"EndEvent","name":"结束","type":1}}}""";
        AgentValidateResult result = validator.validate("flow_a", json);
        assertTrue(result.isPassed(), "这条只是隐患不是错误，不该拦：" + result.toFeedback());
        assertFalse(result.getWarnings().isEmpty(), "但必须给出警告");
    }

    // ==================== 规则 7、8 ====================

    @Test
    @DisplayName("规则 8：流程标识不是合法 NCName")
    public void testValidate_invalidKey() {
        AgentValidateResult result = validator.validate("采购 申请",
                BpmProcessValidator.FEW_SHOT_EXAMPLE);
        assertFalse(result.isPassed());
        assertTrue(result.toFeedback().contains("流程标识"), result.toFeedback());
    }

    @Test
    @DisplayName("规则 7：首个审批节点用了「审批人自选」")
    public void testValidateFirstApproveNode_forbiddenStrategy() {
        String json = """
                {"id":"StartUserNode","name":"发起人","type":10,
                 "childNode":{"id":"n1","name":"审批","type":11,"approveType":1,
                   "approveMethod":1,"candidateStrategy":34,
                   "childNode":{"id":"EndEvent","name":"结束","type":1}}}""";
        AgentValidateResult result = new AgentValidateResult();
        validator.validateFirstApproveNode(JSONUtil.parseObj(json), result);
        assertFalse(result.isPassed());
        assertTrue(result.toFeedback().contains("审批人自选"), result.toFeedback());
    }

    @Test
    @DisplayName("规则 7：非首个节点用「审批人自选」是允许的")
    public void testValidateFirstApproveNode_allowedOnLaterNode() {
        String json = """
                {"id":"StartUserNode","name":"发起人","type":10,
                 "childNode":{"id":"n1","name":"一审","type":11,"approveType":1,
                   "approveMethod":1,"candidateStrategy":30,"candidateParam":"1",
                   "childNode":{"id":"n2","name":"二审","type":11,"approveType":1,
                     "approveMethod":1,"candidateStrategy":34,
                     "childNode":{"id":"EndEvent","name":"结束","type":1}}}}""";
        AgentValidateResult result = new AgentValidateResult();
        validator.validateFirstApproveNode(JSONUtil.parseObj(json), result);
        assertTrue(result.isPassed(), result.toFeedback());
    }

    // ==================== 造规则 ====================

    /**
     * 造一份与 {@code BpmModelApiImpl#getProcessConstraints} 同构的规则
     *
     * ⚠️ 这里刻意只放测试用到的几项，不是全量复刻 ——
     * 复刻等于又抄了一份枚举，而校验器的设计前提就是「规则只有一份」。
     */
    private BpmProcessConstraintsRespDTO buildConstraints() {
        BpmProcessConstraintsRespDTO constraints = new BpmProcessConstraintsRespDTO();
        constraints.setRequiredApproveType(1);
        constraints.setNodeTypes(List.of(
                nodeType(0, "开始", false), nodeType(1, "结束", false),
                nodeType(10, "发起人", false), nodeType(11, "审批人", false),
                nodeType(12, "抄送人", false), nodeType(13, "办理人", false),
                nodeType(50, "条件", false), nodeType(51, "条件分支", true),
                nodeType(52, "并行分支", true)));
        constraints.setCandidateStrategies(List.of(
                strategy(30, "用户", true, "逗号分隔的用户编号，如 \"1,2,3\""),
                strategy(34, "审批人自选", false, null),
                strategy(37, "发起人部门负责人", true, "层级数字，必须大于 0，如 \"1\""),
                strategy(23, "连续多级部门的负责人", true, "\"部门编号们|层级\"，如 \"1,2|2\"，两段都必须给")));
        constraints.setApproveMethods(List.of(
                approveMethod(1, "随机挑选一人审批", false),
                approveMethod(2, "多人会签(按通过比例)", true),
                approveMethod(3, "多人或签(一人通过或拒绝)", false),
                approveMethod(4, "依次审批", false)));
        constraints.setFirstNodeForbiddenStrategies(List.of(34));
        constraints.setConditionTypes(List.of(
                conditionType(1, "条件表达式"), conditionType(2, "条件规则")));
        return constraints;
    }

    private BpmProcessConstraintsRespDTO.NodeType nodeType(int type, String name, boolean branch) {
        BpmProcessConstraintsRespDTO.NodeType item = new BpmProcessConstraintsRespDTO.NodeType();
        item.setType(type);
        item.setName(name);
        item.setBranch(branch);
        return item;
    }

    private BpmProcessConstraintsRespDTO.CandidateStrategy strategy(
            int value, String name, boolean paramRequired, String paramFormat) {
        BpmProcessConstraintsRespDTO.CandidateStrategy item =
                new BpmProcessConstraintsRespDTO.CandidateStrategy();
        item.setStrategy(value);
        item.setName(name);
        item.setParamRequired(paramRequired);
        item.setParamFormat(paramFormat);
        return item;
    }

    private BpmProcessConstraintsRespDTO.ApproveMethod approveMethod(
            int method, String name, boolean ratioRequired) {
        BpmProcessConstraintsRespDTO.ApproveMethod item =
                new BpmProcessConstraintsRespDTO.ApproveMethod();
        item.setMethod(method);
        item.setName(name);
        item.setRatioRequired(ratioRequired);
        return item;
    }

    private BpmProcessConstraintsRespDTO.ConditionType conditionType(int type, String name) {
        BpmProcessConstraintsRespDTO.ConditionType item =
                new BpmProcessConstraintsRespDTO.ConditionType();
        item.setType(type);
        item.setName(name);
        return item;
    }

}
