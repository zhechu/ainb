package cn.enlev.ainb.module.agent.tool.impl.explain;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.bpm.api.definition.BpmModelApi;
import cn.enlev.ainb.module.bpm.api.definition.dto.BpmModelRespDTO;
import cn.enlev.ainb.module.bpm.api.definition.dto.BpmProcessConstraintsRespDTO;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDesignSaveReqDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 解释工具的单元测试
 *
 * <p>断言全部落在<b>输出文本里有没有那句话</b>上。这两个工具的产物就是给人读的文字，
 * 「返回了一个非空字符串」证明不了任何事。
 *
 * @author 走进新时代
 */
public class ExplainToolsTest {

    @ExtendWith(MockitoExtension.class)
    @Nested
    @DisplayName("explain_process")
    class ProcessTest {

        @InjectMocks
        private ExplainProcessTool tool;
        @Mock
        private BpmModelApi bpmModelApi;

        private void mockModel(String simpleJson) {
            BpmModelRespDTO model = new BpmModelRespDTO();
            model.setId("mid");
            model.setKey("oa_leave");
            model.setName("请假");
            model.setDeployed(true);
            when(bpmModelApi.getModelList(any())).thenReturn(List.of(model));
            lenient().when(bpmModelApi.getModelSimpleJson(eq("mid"))).thenReturn(simpleJson);
            lenient().when(bpmModelApi.getProcessConstraints()).thenReturn(constraints());
        }

        @SuppressWarnings("unchecked")
        private String explain(Object result) {
            return String.valueOf(((Map<String, Object>) result).get("explanation"));
        }

        @Test
        @DisplayName("把候选人策略与审批方式翻译成中文")
        public void testTranslatesCodes() {
            mockModel("""
                    {"id":"StartUserNode","type":10,"name":"发起人",
                     "childNode":{"id":"n1","type":11,"name":"部门审批","approveType":1,
                       "approveMethod":1,"candidateStrategy":30,"candidateParam":"1,2",
                       "childNode":{"id":"EndEvent","type":1,"name":"结束"}}}""");

            String text = explain(tool.execute(Map.of("target", "oa_leave")));
            assertTrue(text.contains("发起人提交申请"), text);
            assertTrue(text.contains("部门审批"), text);
            // 数字编码必须被翻译，否则等于没解释
            assertTrue(text.contains("用户"), "candidateStrategy:30 要译成「用户」：" + text);
            assertTrue(text.contains("随机挑选一人审批"), "approveMethod:1 要译出来：" + text);
            assertFalse(text.contains("candidateStrategy:30"), "不该把原始编码抛给用户");
        }

        @Test
        @DisplayName("🔴 approveType 不对时要在解释里直说「这些配置全都不生效」")
        public void testWarnsOnBrokenApproveType() {
            mockModel("""
                    {"id":"StartUserNode","type":10,
                     "childNode":{"id":"n1","type":11,"name":"部门审批","approveType":2,
                       "approveMethod":1,"candidateStrategy":30,"candidateParam":"1",
                       "childNode":{"id":"EndEvent","type":1}}}""");

            String text = explain(tool.execute(Map.of("target", "oa_leave")));
            // 不说的话，用户读完解释以为配好了，实际跑起来没人能审批
            assertTrue(text.contains("全部不生效") || text.contains("【全部不生效】"), text);
        }

        @Test
        @DisplayName("条件分支要说清楚什么条件走哪边，默认分支要标明")
        public void testExplainsBranches() {
            mockModel("""
                    {"id":"StartUserNode","type":10,
                     "childNode":{"id":"gw","type":51,"name":"按天数",
                       "conditionNodes":[
                         {"id":"c1","type":50,"name":"大于3天",
                          "conditionSetting":{"defaultFlow":false,"conditionType":1,
                            "conditionExpression":"${var:getOrDefault(day,null) > 3}"},
                          "childNode":{"id":"n2","type":11,"name":"总经理审批","approveType":1,
                            "approveMethod":1,"candidateStrategy":30,"candidateParam":"1"}},
                         {"id":"c2","type":50,"name":"其它","conditionSetting":{"defaultFlow":true}}],
                       "childNode":{"id":"EndEvent","type":1}}}""");

            String text = explain(tool.execute(Map.of("target", "oa_leave")));
            assertTrue(text.contains("大于3天"), text);
            assertTrue(text.contains("day"), "条件表达式要露出来：" + text);
            assertTrue(text.contains("默认分支"), "兜底分支要标明：" + text);
            assertTrue(text.contains("总经理审批"), "分支里的节点也要解释：" + text);
        }

        @Test
        @DisplayName("🔴 conditionType 缺失 —— 要指出这条分支实际是恒成立的")
        public void testWarnsOnMissingConditionType() {
            mockModel("""
                    {"id":"StartUserNode","type":10,
                     "childNode":{"id":"gw","type":51,"name":"分支",
                       "conditionNodes":[
                         {"id":"c1","type":50,"name":"条件1","conditionSetting":{"defaultFlow":false}},
                         {"id":"c2","type":50,"name":"其它","conditionSetting":{"defaultFlow":true}}],
                       "childNode":{"id":"EndEvent","type":1}}}""");

            String text = explain(tool.execute(Map.of("target", "oa_leave")));
            assertTrue(text.contains("永远成立") || text.contains("无条件"), text);
        }

        private BpmProcessConstraintsRespDTO constraints() {
            BpmProcessConstraintsRespDTO dto = new BpmProcessConstraintsRespDTO();
            dto.setRequiredApproveType(1);
            BpmProcessConstraintsRespDTO.NodeType branch = new BpmProcessConstraintsRespDTO.NodeType();
            branch.setType(51);
            branch.setName("条件分支");
            branch.setBranch(true);
            dto.setNodeTypes(List.of(branch));
            BpmProcessConstraintsRespDTO.CandidateStrategy user =
                    new BpmProcessConstraintsRespDTO.CandidateStrategy();
            user.setStrategy(30);
            user.setName("用户");
            user.setParamRequired(true);
            dto.setCandidateStrategies(List.of(user));
            BpmProcessConstraintsRespDTO.ApproveMethod random =
                    new BpmProcessConstraintsRespDTO.ApproveMethod();
            random.setMethod(1);
            random.setName("随机挑选一人审批");
            random.setRatioRequired(false);
            dto.setApproveMethods(List.of(random));
            return dto;
        }
    }

    @ExtendWith(MockitoExtension.class)
    @Nested
    @DisplayName("explain_form_rule")
    class FormRuleTest {

        @InjectMocks
        private ExplainFormRuleTool tool;
        @Mock
        private FormDesignApi formDesignApi;

        private void mockForm(String logicRules, String validateRules) {
            FormDesignSaveReqDTO form = new FormDesignSaveReqDTO();
            form.setCode("expense");
            form.setName("报销单");
            form.setLogicRules(logicRules);
            form.setValidateRules(validateRules);
            when(formDesignApi.getFormDesignByCode(eq("expense"))).thenReturn(form);
        }

        @SuppressWarnings("unchecked")
        private List<String> logicOf(Object result) {
            return (List<String>) ((Map<String, Object>) result).get("logicRules");
        }

        @SuppressWarnings("unchecked")
        private List<String> validateOf(Object result) {
            return (List<String>) ((Map<String, Object>) result).get("validateRules");
        }

        @Test
        @DisplayName("联动规则译成「什么时候、满足什么、就怎样」")
        public void testExplainsLogicRule() {
            mockForm("""
                    [{"id":"lg1","name":"金额超标要填说明","enabled":true,"trigger":"CHANGE",
                      "when":{"logic":"AND","items":[
                        {"field":"amount","operator":"GT","valueType":"CONST","value":5000}]},
                      "then":[{"type":"SHOW","target":"over_reason"},
                              {"type":"REQUIRE","target":"over_reason"}],
                      "otherwise":[{"type":"HIDE","target":"over_reason"}]}]""", null);

            String text = logicOf(tool.execute(Map.of("code", "expense"))).get(0);
            assertTrue(text.contains("相关字段被修改时"), text);
            assertTrue(text.contains("amount 大于 5000"), "条件要译成人话：" + text);
            assertTrue(text.contains("显示"), text);
            assertTrue(text.contains("设为必填"), text);
            assertTrue(text.contains("否则"), "otherwise 分支也要说：" + text);
        }

        @Test
        @DisplayName("🔴 被禁用的规则要标出来 —— 它是「配了不生效」最平凡的原因")
        public void testMarksDisabledRule() {
            mockForm("""
                    [{"id":"lg1","name":"已关掉的规则","enabled":false,"trigger":"LOAD",
                      "when":{"items":[{"field":"a","operator":"EQ","value":1}]},
                      "then":[{"type":"HIDE","target":"b"}]}]""", null);

            String text = logicOf(tool.execute(Map.of("code", "expense"))).get(0);
            assertTrue(text.contains("已禁用"), text);
        }

        @Test
        @DisplayName("🔴 右值是字段时不能当成字面量解释")
        public void testFieldValueType() {
            mockForm(null, """
                    [{"id":"r1","name":"售价不得高于标价",
                      "must":{"logic":"AND","items":[
                        {"field":"sale_price","operator":"LE","valueType":"FIELD",
                         "value":"list_price"}]},
                      "message":"售价不得高于标价"}]""");

            String text = validateOf(tool.execute(Map.of("code", "expense"))).get(0);
            assertTrue(text.contains("字段「list_price」的值"),
                    "valueType=FIELD 要译成字段引用而不是字符串：" + text);
            assertTrue(text.contains("小于等于"), text);
        }

        @Test
        @DisplayName("校验规则没配提示语要提醒")
        public void testValidateWithoutMessage() {
            mockForm(null, """
                    [{"id":"r1","name":"无提示语",
                      "must":{"items":[{"field":"a","operator":"NOT_EMPTY"}]}}]""");

            String text = validateOf(tool.execute(Map.of("code", "expense"))).get(0);
            assertTrue(text.contains("没有配置提示语"), text);
        }

        @Test
        @DisplayName("没有任何规则时要明说，而不是返回空")
        public void testNoRules() {
            mockForm(null, null);
            Object result = tool.execute(Map.of("code", "expense"));
            assertTrue(String.valueOf(((Map<String, Object>) result).get("summary"))
                    .contains("没有配置任何"), "空结果要有明确说法");
        }
    }

}
