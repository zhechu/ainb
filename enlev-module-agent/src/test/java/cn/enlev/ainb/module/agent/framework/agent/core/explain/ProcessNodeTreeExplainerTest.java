package cn.enlev.ainb.module.agent.framework.agent.core.explain;

import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.module.bpm.api.definition.dto.BpmProcessConstraintsRespDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProcessNodeTreeExplainer} 的测试
 *
 * <h3>🔴 前 5 条是「搬动前后逐字一致」的黄金快照，不是新写的期望值</h3>
 * 那 5 段 expected 是<b>重构之前</b>把 {@code ExplainProcessTool} 原有的 private
 * {@code describe} 递归跑在同一批 fixture 上、实测导出的文本，一个字符都没改过。
 * 之所以要这么绕：这套逻辑的产物是<b>给人读的中文</b>，
 * 而「照着代码把期望值推一遍」推出来的东西永远和代码一致 —— 那测的是我会不会抄，
 * 不是搬代码有没有搬错。只有先测量、再搬动、再比对，等价性才算被证过。
 *
 * <p>fixture 是刻意堆的：5 棵树覆盖了原逻辑的每一个分支
 * （发起人/结束/审批/办理/抄送/分支/未知类型、条件表达式/空表达式/条件规则的且与或/
 * 默认分支/缺 conditionType/无 setting/空 conditions/无 groups、
 * approveType 错、比例审批、candidateStrategy 查不到、name 缺失落 id、
 * 分支里混进非条件节点、嵌套缩进、空树）。
 *
 * @author 走进新时代
 */
public class ProcessNodeTreeExplainerTest {

    // ==================== fixture：与重构前实测快照时用的是同一批 ====================

    /** F1 主干：发起人 → 审批（正常 approveType/approveMethod/strategy） → 结束 */
    private static final String F1 = """
            {"id":"StartUserNode","type":10,"name":"发起人",
             "childNode":{"id":"n1","type":11,"name":"部门审批","approveType":1,
               "approveMethod":1,"candidateStrategy":30,"candidateParam":"1,2",
               "childNode":{"id":"EndEvent","type":1,"name":"结束"}}}""";

    /** F2 approveType 错 + 比例审批 + 办理节点 + 抄送节点 + 无 name（落 id） */
    private static final String F2 = """
            {"id":"StartUserNode","type":10,
             "childNode":{"id":"n1","type":11,"approveType":2,
               "approveMethod":2,"approveRatio":60,"candidateStrategy":30,"candidateParam":"1",
               "childNode":{"id":"n2","type":13,"name":"办理人","approveType":1,
                 "candidateStrategy":99,
                 "childNode":{"id":"n3","type":12,"name":"抄送财务","candidateStrategy":30,
                   "childNode":{"id":"EndEvent","type":1}}}}}}""";

    /** F3 分支全家桶 */
    private static final String F3 = """
            {"id":"StartUserNode","type":10,
             "childNode":{"id":"gw","type":51,"name":"按天数",
               "conditionNodes":[
                 {"id":"c1","type":50,"name":"大于3天",
                  "conditionSetting":{"defaultFlow":false,"conditionType":1,
                    "conditionExpression":"${var:getOrDefault(day,null) > 3}"},
                  "childNode":{"id":"n2","type":11,"name":"总经理审批","approveType":1,
                    "approveMethod":1,"candidateStrategy":30,"candidateParam":"1"}},
                 {"id":"c2","type":50,"name":"空表达式",
                  "conditionSetting":{"defaultFlow":false,"conditionType":1}},
                 {"id":"c3","type":50,"name":"规则组",
                  "conditionSetting":{"defaultFlow":false,"conditionType":2,
                    "conditionGroups":{"and":true,"conditions":[
                      {"and":true,"rules":[{"leftSide":"day","opCode":">","rightSide":"1"}]},
                      {"and":false,"rules":[{"leftSide":"dept","opCode":"==","rightSide":"研发部"},
                                            {"leftSide":"level","opCode":"==","rightSide":"P7"}]}]}}},
                 {"id":"c4","type":50,"name":"缺类型","conditionSetting":{"defaultFlow":false}},
                 {"id":"c5","type":50,"name":"无setting"},
                 {"id":"c6","type":50,"name":"空规则",
                  "conditionSetting":{"defaultFlow":false,"conditionType":2,
                    "conditionGroups":{"and":false,"conditions":[]}}},
                 {"id":"c7","type":50,"name":"无groups",
                  "conditionSetting":{"defaultFlow":false,"conditionType":2}},
                 {"id":"c8","type":50,"name":"其它","conditionSetting":{"defaultFlow":true}},
                 {"id":"notACondition","type":11,"name":"混进来的非条件节点"}],
               "childNode":{"id":"EndEvent","type":1}}}""";

    /** F4 空分支 + 未知节点类型 */
    private static final String F4 = """
            {"id":"StartUserNode","type":10,
             "childNode":{"id":"gw","type":51,"name":"空分支","conditionNodes":[],
               "childNode":{"id":"x","type":77,"name":"神秘节点",
                 "childNode":{"id":"EndEvent","type":1}}}}}""";

    /** F5 空树 */
    private static final String F5 = "{}";

    private static List<String> explain(String json) {
        return ProcessNodeTreeExplainer.explain(JSONUtil.parseObj(json), constraints());
    }

    private static List<String> golden(String block) {
        return block.lines().toList();
    }

    @Nested
    @DisplayName("🔴 黄金快照：与重构前 ExplainProcessTool 的输出逐字一致")
    class GoldenSnapshot {

        @Test
        @DisplayName("F1 主干")
        void f1() {
            assertEquals(golden("""
                    ① 由发起人提交申请
                    → 审批节点「部门审批」：由「用户」审批（参数 1,2），随机挑选一人审批
                    ⑧ 流程结束
                    """), explain(F1));
        }

        @Test
        @DisplayName("F2 approveType 错、比例审批、办理/抄送节点、name 缺失落 id")
        void f2() {
            assertEquals(golden("""
                    ① 由发起人提交申请
                    → 审批节点「n1」：由「用户」审批（参数 1），按比例通过（需 60% 通过），⚠️ 该节点的 approveType 是 2（应为 1），上面这些配置实际上【全部不生效】，流程跑到这里没有人能审批
                    → 办理节点「办理人」：⚠️ 没有配置审批人（candidateStrategy=99），部署时会被拒绝
                    → 抄送「抄送财务」：由「用户」审批
                    ⑧ 流程结束
                    """), explain(F2));
        }

        @Test
        @DisplayName("F3 分支全家桶（含混进 conditionNodes 的非条件节点被跳过）")
        void f3() {
            assertEquals(golden("""
                    ① 由发起人提交申请
                    → 分支「按天数」，按下面的条件分流：
                      · 分支「大于3天」：满足表达式 ${var:getOrDefault(day,null) > 3} 时走这一条
                        → 审批节点「总经理审批」：由「用户」审批（参数 1），随机挑选一人审批
                      · 分支「空表达式」：⚠️ 选了「条件表达式」却没填表达式，这条分支会变成恒成立
                      · 分支「规则组」：满足「day > 1 且 dept == 研发部 或 level == P7」时走这一条
                      · 分支「缺类型」：⚠️ 没有指定条件类型 —— 这条分支实际上是【无条件】的，也就是永远成立，它后面的分支再也走不到，而且不报任何错
                      · 分支「无setting」：⚠️ 没有配置条件（后端处理这种情况时会出错）
                      · 分支「空规则」：⚠️ 条件规则是空的
                      · 分支「无groups」：⚠️ 选了「条件规则」却没填规则
                      · 分支「其它」：以上条件都不满足时，走这一条（默认分支）
                    ⑧ 流程结束
                    """), explain(F3));
        }

        @Test
        @DisplayName("F4 空分支与未知节点类型")
        void f4() {
            assertEquals(golden("""
                    ① 由发起人提交申请
                    → 分支「空分支」，按下面的条件分流：
                      ⚠️ 这个分支没有配任何条件节点，流程走到这里会出错
                    → 节点「神秘节点」（类型 77）
                    ⑧ 流程结束
                    """), explain(F4));
        }

        @Test
        @DisplayName("F5 空树 —— 0 行，而不是 1 行空字符串")
        void f5() {
            // 这条钉的是「explain 返回 List 而不是拼好的 String」那个决定：
            // 拼好的话 nodeCount 只能靠 split("\n") 反推，空树会得到 1
            assertTrue(explain(F5).isEmpty());
        }

    }

    @Nested
    @DisplayName("🔴 对照表拿不到时的降级 —— 不许装作没问题，也不许编一个诊断")
    class DegradedDictionary {

        @Test
        @DisplayName("整个 constraints 为 null：只说「翻译不了」，不输出半棵树")
        void nullConstraints() {
            List<String> lines = ProcessNodeTreeExplainer.explain(JSONUtil.parseObj(F1), null);
            assertEquals(1, lines.size(), "此时连哪些是分支都判不了，硬画出来的走向是错的：" + lines);
            assertTrue(lines.get(0).contains("取不到平台的流程规则对照表"), lines.get(0));
            // 反过来说：不许出现「看起来像正常解读」的那几句
            assertTrue(lines.get(0).contains("不给出走向解读"), lines.get(0));
        }

        @Test
        @DisplayName("candidateStrategies 为 null：说「查不到」，不说「没配审批人」")
        void nullCandidateStrategies() {
            BpmProcessConstraintsRespDTO dto = constraints();
            dto.setCandidateStrategies(null);
            String text = String.join("\n", ProcessNodeTreeExplainer.explain(JSONUtil.parseObj(F1), dto));
            assertTrue(text.contains("平台没给出候选人策略清单"), text);
            assertTrue(text.contains("candidateStrategy=30"), text);
            // 🔴 这句话是一句关于「用户配置」的断言，而事实是我们查不到 —— 混为一谈
            //    会让人去改一个本来没配错的地方
            assertTrue(!text.contains("没有配置审批人"), text);
        }

        @Test
        @DisplayName("approveMethods 为 null：说「查不到」，但不为没填 approveMethod 的节点凭空造一句")
        void nullApproveMethods() {
            BpmProcessConstraintsRespDTO dto = constraints();
            dto.setApproveMethods(null);
            String withMethod = String.join("\n",
                    ProcessNodeTreeExplainer.explain(JSONUtil.parseObj(F1), dto));
            assertTrue(withMethod.contains("平台没给出审批方式清单"), withMethod);
            assertTrue(withMethod.contains("approveMethod=1"), withMethod);

            // F2 的 n2 是办理节点、压根没填 approveMethod，不该冒出一句 approveMethod=null
            String withoutMethod = String.join("\n",
                    ProcessNodeTreeExplainer.explain(JSONUtil.parseObj(F2), dto));
            assertTrue(!withoutMethod.contains("approveMethod=null"), withoutMethod);
        }

        @Test
        @DisplayName("🔴 nodeTypes 为 null：退回结构判断，分支子树不许静默消失")
        void nullNodeTypes() {
            BpmProcessConstraintsRespDTO dto = constraints();
            dto.setNodeTypes(null);
            List<String> lines = ProcessNodeTreeExplainer.explain(JSONUtil.parseObj(F3), dto);
            String text = String.join("\n", lines);
            // 判不成分支的话，conditionNodes 整棵子树都不会被走到 ——
            // 表现是解读里少了 8 行、而且不报任何错。这正是要防的失败形态
            assertTrue(text.contains("大于3天"), "分支子树静默消失了：" + text);
            assertTrue(text.contains("总经理审批"), "分支里挂的审批节点也一起没了：" + text);
            assertEquals(explain(F3).size(), lines.size(),
                    "缺 nodeTypes 只该影响「分支」这个词怎么说，不该影响出行数");
        }

    }

    @Test
    @DisplayName("🔴 不许有实例字段 —— 「不偷偷查库」要靠结构成立，不是靠承诺")
    void hasNoInstanceFields() {
        List<String> offenders = java.util.Arrays.stream(ProcessNodeTreeExplainer.class.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .map(Field::getName)
                .toList();
        assertTrue(offenders.isEmpty(),
                """
                ProcessNodeTreeExplainer 出现了实例字段：%s

                它是静态工具类，唯一的输入是调用方传进来的节点树与对照表。
                一旦能持有字段，就能持有 @Resource 的 Api，于是「抽取器不查库、
                干跑器才查库」这条边界从签名上看得出来，变成要靠人读实现才知道 ——
                而清单里那一栏是审批人据以判断风险的依据，不该建立在承诺上。
                """.formatted(offenders));
    }

    /**
     * 与 {@code ExplainToolsTest$ProcessTest#constraints()} 同形，另加一个「按比例通过」
     *
     * <p>⚠️ 三个 List 都用 {@code List.of} 装、但<b>顺序在断言里从不被依赖</b>
     * （查表是按 strategy/method/type 匹配，不是按下标）。
     * 真正会踩到 {@code Map.of} 那种「每 JVM 一次随机序」坑的是 Map，这里没有。
     */
    private static BpmProcessConstraintsRespDTO constraints() {
        BpmProcessConstraintsRespDTO dto = new BpmProcessConstraintsRespDTO();
        dto.setRequiredApproveType(1);
        BpmProcessConstraintsRespDTO.NodeType branch = new BpmProcessConstraintsRespDTO.NodeType();
        branch.setType(51);
        branch.setName("条件分支");
        branch.setBranch(true);
        dto.setNodeTypes(List.of(branch));
        BpmProcessConstraintsRespDTO.CandidateStrategy user = new BpmProcessConstraintsRespDTO.CandidateStrategy();
        user.setStrategy(30);
        user.setName("用户");
        user.setParamRequired(true);
        dto.setCandidateStrategies(List.of(user));
        BpmProcessConstraintsRespDTO.ApproveMethod random = new BpmProcessConstraintsRespDTO.ApproveMethod();
        random.setMethod(1);
        random.setName("随机挑选一人审批");
        random.setRatioRequired(false);
        BpmProcessConstraintsRespDTO.ApproveMethod ratio = new BpmProcessConstraintsRespDTO.ApproveMethod();
        ratio.setMethod(2);
        ratio.setName("按比例通过");
        ratio.setRatioRequired(true);
        dto.setApproveMethods(List.of(random, ratio));
        return dto;
    }

}
