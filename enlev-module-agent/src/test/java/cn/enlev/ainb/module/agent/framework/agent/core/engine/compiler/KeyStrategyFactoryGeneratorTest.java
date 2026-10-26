package cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.VariableDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.HitlBpmApprovalNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.HitlConfirmNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.KnowledgeRetrievalNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.LlmNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.ScriptNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.ToolNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.io.NodeIoContract;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.WorkflowDslValidator;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KeyStrategyFactoryGenerator} 单测
 *
 * 核心约束：DSL 变量的 merge 策略必须 1:1 映射到 SAA KeyStrategy（DSL §3.1）。
 * 映射错了不会报错，只会让「追加型变量被覆盖」这类静默错误在运行时发生 —— 最难查。
 *
 * <p>🔴 第二条约束（缺陷 C8）：<b>契约里全部非空缺省输出名必须无条件注册</b>，
 * 且注册集必须与 {@code WorkflowDslValidator} 的 V19 豁免集<b>同源</b>。
 * 理由与「两个方向的漂移都是缺陷」的论证写在最后那条测试的 javadoc 里。
 *
 * @author 走进新时代
 */
class KeyStrategyFactoryGeneratorTest {

    private static VariableDTO var(String key, String merge) {
        VariableDTO v = new VariableDTO();
        v.setKey(key);
        v.setMerge(merge);
        return v;
    }

    @Test
    void generate_mapsMergeToKeyStrategy() {
        WorkflowDefDTO def = new WorkflowDefDTO();
        def.setVariables(List.of(
                var("scalar", "replace"),
                var("history", "append"),
                var("nested", "merge")));

        KeyStrategyFactory factory = KeyStrategyFactoryGenerator.generate(def);
        Map<String, KeyStrategy> strategies = factory.apply();

        assertSame(KeyStrategy.REPLACE, strategies.get("scalar"));
        assertSame(KeyStrategy.APPEND, strategies.get("history"));
        assertSame(KeyStrategy.MERGE, strategies.get("nested"));
    }

    @Test
    void generate_unknownOrNullMerge_fallsBackToReplace() {
        WorkflowDefDTO def = new WorkflowDefDTO();
        def.setVariables(List.of(var("weird", "nonsense"), var("nul", null)));

        Map<String, KeyStrategy> strategies = KeyStrategyFactoryGenerator.generate(def).apply();

        assertSame(KeyStrategy.REPLACE, strategies.get("weird"));
        assertSame(KeyStrategy.REPLACE, strategies.get("nul"));
    }

    @Test
    void generate_noVariables_doesNotThrow() {
        WorkflowDefDTO def = new WorkflowDefDTO();
        // variables 为 null：适配层不能 NPE，应产出可用的工厂。
        // ⚠️ 此时仍然注册得到那 7 个契约缺省输出名（它们不依赖 variables[]）—— 那正是 C8 的意义：
        //    一份没声明任何变量的 DSL 也能安全跨过挂起点。defaultStrategy(REPLACE) 只兜「同一次运行内」，
        //    兜不住 resume（OverAllState.input() 只保留注册过的键）。
        KeyStrategyFactory factory = KeyStrategyFactoryGenerator.generate(def);
        assertNotNull(factory);
        assertNotNull(factory.apply());
    }

    /**
     * 🔴 三个「恢复时才注入」的回调键必须无条件注册，DSL 没声明也要在。
     *
     * <p>defaultStrategy(REPLACE) <b>兜不住这些键</b>：resume 时 SAA 走
     * {@code OverAllState.input(checkpointState)} 重载状态，而 {@code input()} 只保留注册过
     * KeyStrategy 的键（{@code WaitNodeRuntimeTest} 已实测）。不注册 → 回调决定在恢复瞬间被丢掉 →
     * 节点读到 null 把「同意/通过」判成「拒绝/驳回」，流程静默走拒绝分支还把执行状态报成 SUCCEEDED。
     *
     * <p>三个键都是「回调方拿不到节点 config，只能写死默认名」造成的：
     * {@code hitl_approved}（聊天内恢复）、{@code bpm_approved}/{@code bpm_status}（BPM 审批完成事件）。
     *
     * <p>⚠️ C8 之后这条<b>被下面那条包含</b>了（三个键也是契约的缺省输出名），刻意仍留着：
     * 它钉的是<b>三条显式 {@code addStrategy}</b> 那一道地板。真有人把契约里 hitl.confirm 的
     * {@code defaultName} 改成 null，遍历契约那条会静默少注册一个键、照样绿，而这一条会红。
     */
    @Test
    void generate_registersResumeCallbackKeys_evenWhenVariablesDoNotDeclareThem() {
        WorkflowDefDTO def = new WorkflowDefDTO();
        // 刻意只声明业务变量：真实的编排器/AI 造的 DSL 几乎不会声明这三个回调键
        def.setVariables(List.of(var("order_no", "replace"), var("messages", "append")));

        Map<String, KeyStrategy> strategies = KeyStrategyFactoryGenerator.generate(def).apply();

        assertSame(KeyStrategy.REPLACE, strategies.get(HitlConfirmNodeExecutor.DEFAULT_APPROVE_VAR),
                "hitl_approved 未注册 → 聊天内恢复的「同意」会在 OverAllState.input() 处被丢掉");
        assertSame(KeyStrategy.REPLACE, strategies.get(HitlBpmApprovalNodeExecutor.DEFAULT_APPROVE_VAR),
                "bpm_approved 未注册 → BPM 审批「通过」会在恢复瞬间丢失，被判成驳回");
        assertSame(KeyStrategy.REPLACE, strategies.get(HitlBpmApprovalNodeExecutor.DEFAULT_STATUS_VAR),
                "bpm_status 未注册 → 状态码丢失，取消(4) 无法与驳回(3) 区分");
        // 业务变量仍逐个显式注册，append/merge 生效（不因新增回调键而回退）
        assertSame(KeyStrategy.REPLACE, strategies.get("order_no"));
        assertSame(KeyStrategy.APPEND, strategies.get("messages"));
    }

    /**
     * 🔴 C8 的主体：<b>契约里全部非空缺省输出名</b>都必须无条件注册，不只是那三个 hitl 键。
     *
     * <p>漏掉的键与 hitl 那三个<b>死法完全一样</b>，只是没那么戏剧化：
     * {@code tool → wait → 读 {{$.tool_result}}} 这种流程，工具结果写进 state 时没人拦，
     * wait 挂起、调度器捞回来 resume 的那一刻被 {@code OverAllState.input()} 滤掉，
     * 下游读到 null。<b>同一次运行内看不出任何问题</b>，只有跨过挂起点才发作 —— 最难复现的一类。
     *
     * <p>🔴 判据从 {@code NodeIoContract.defaultOutputNames()} 派生，<b>不在这里列键名</b>：
     * 列了就得跟契约人肉同步，而「两处清单漂了」正是 C8 要消灭的缺陷结构。
     * 代价是本条对「契约里写错一个 defaultName」是瞎的 —— 那一档的强制点是
     * {@code NodeIoContractRuntimeAlignmentTest}（目前只覆盖 tool / llm）。
     */
    @Test
    void generate_registersEveryContractDefaultName_evenWhenVariablesDoNotDeclareThem() {
        Set<String> defaults = NodeIoContract.defaultOutputNames();

        // ⚠️ 非空地板：少了它，契约哪天被清空，上面那个 for 就一条不跑、本测试<b>恒绿</b>。
        //    恒绿的负控比没有负控更坏 —— 它会让后来人以为这一档有人守着。
        assertTrue(defaults.size() >= 7,
                "契约的非空缺省输出名应至少 7 个（tool/llm/knowledge_retrieval/script/hitl.confirm/hitl.bpm_approval×2）"
                        + "，实测 " + defaults.size() + " = " + defaults
                        + "。变少说明有端口的 defaultName 被改成 null 了 —— 那会让执行器的兜底键名"
                        + "从「无条件注册」里静默消失，本测试却照样绿");
        // 🔴 更要紧的一条地板：必须<b>不止</b>那三个 hitl 键。只断言 size 的话，
        //    把 tool_result / llm_output 从契约里删掉、再补三个同义端口也能凑够数。
        assertTrue(defaults.contains(ToolNodeExecutor.DEFAULT_OUTPUT_VAR),
                "tool_result 不在派生集里 → C8 的推广等于没做，退回到只保三个 hitl 键");
        assertTrue(defaults.contains(LlmNodeExecutor.DEFAULT_OUTPUT_VAR),
                "llm_output 不在派生集里 → 同上");
        assertTrue(defaults.contains(ScriptNodeExecutor.DEFAULT_OUTPUT_VAR),
                "script 的缺省输出名不在派生集里 → 同上");
        assertTrue(defaults.contains(KnowledgeRetrievalNodeExecutor.DEFAULT_OUTPUT_VAR),
                "knowledge_retrieval 的缺省输出名不在派生集里 → 同上");

        WorkflowDefDTO def = new WorkflowDefDTO();
        def.setVariables(List.of(var("order_no", "replace")));
        Map<String, KeyStrategy> strategies = KeyStrategyFactoryGenerator.generate(def).apply();

        for (String name : defaults) {
            assertSame(KeyStrategy.REPLACE, strategies.get(name),
                    "缺省输出名 " + name + " 未注册 → 下游有挂起点时，它的值会在 resume 的 "
                            + "OverAllState.input() 处被静默丢弃，而 WorkflowDslValidator 的 V19 "
                            + "对「作者没填这一格」是刻意放过的 ⟹ 没有任何一处会报出来");
        }
        // 注册这一趟不许把业务变量的注册挤掉
        assertSame(KeyStrategy.REPLACE, strategies.get("order_no"));
    }

    /**
     * 🔴 作者在 {@code variables[]} 里的声明必须<b>赢过</b>缺省名的无条件注册。
     *
     * <p>这一条钉的是 {@code generate} 里的<b>顺序</b>：遍历契约那一趟必须排在 {@code variables[]}
     * 那一趟<b>之前</b>。{@code KeyStrategyFactoryBuilder.addStrategy} 就是个 {@code Map.put}
     * （已用 {@code javap -c} 核实），同键后写覆盖先写 —— 顺序一反，作者写的
     * {@code merge:"append"} 会被悄悄改回 REPLACE，跨轮追加的历史每轮只剩最后一条。
     * 那种回退<b>不报错</b>，正是本类 javadoc 说的「最难查」。
     */
    @Test
    void generate_variablesDeclarationWins_overContractDefaultRegistration() {
        WorkflowDefDTO def = new WorkflowDefDTO();
        def.setVariables(List.of(
                // 三个都同时被「无条件注册」覆盖到：hitl_approved 还被显式注册了两遍
                // （三条 addStrategy + 遍历契约），故这一条同时证明 variables[] 赢过<b>两处</b>
                var(HitlConfirmNodeExecutor.DEFAULT_APPROVE_VAR, "append"),
                var(ToolNodeExecutor.DEFAULT_OUTPUT_VAR, "merge"),
                var(LlmNodeExecutor.DEFAULT_OUTPUT_VAR, "append")));

        Map<String, KeyStrategy> strategies = KeyStrategyFactoryGenerator.generate(def).apply();

        assertSame(KeyStrategy.APPEND, strategies.get(HitlConfirmNodeExecutor.DEFAULT_APPROVE_VAR),
                "作者声明的 append 被无条件注册覆盖回 REPLACE 了 → generate 里两趟的顺序反了");
        assertSame(KeyStrategy.MERGE, strategies.get(ToolNodeExecutor.DEFAULT_OUTPUT_VAR),
                "同上（merge 档）");
        assertSame(KeyStrategy.APPEND, strategies.get(LlmNodeExecutor.DEFAULT_OUTPUT_VAR),
                "同上");
    }

    /**
     * 🔴 注册集与 V19 的豁免集必须<b>同源</b>（§13 合流对①：C8 + B3-7 的口径约束）。
     *
     * <p>{@code WorkflowDslValidator.RESUME_REGISTERED_VARS} 现在直接等于
     * {@code NodeIoContract.defaultOutputNames()}，而 {@code generate} 也从同一个方法派生。
     * 这条断言把「同源」这件事本身钉住：<b>两个方向的漂移都是缺陷</b>，而只有一个方向会红给别人看 ——
     * <ul>
     *   <li>注册了却没豁免 ⟹ V19 报假警报，还附带一条「把这一格删掉」的错改法（会把流程改坏）；</li>
     *   <li>豁免了却没注册 ⟹ 值真的在恢复瞬间丢掉，而校验器<b>一路绿灯</b>。这个更坏，
     *       且没有任何其它测试会红 —— C8 之前就是这一形态。</li>
     * </ul>
     *
     * <p>⚠️ 用反射读私有静态字段是刻意的：改成 package-private 只为测试可见，
     * 会让「这是实现细节」这个信号消失，反而更容易被人当成公开 API 去依赖。
     */
    @Test
    void generate_registeredDefaultsStayInSyncWithValidatorExemption() {
        @SuppressWarnings("unchecked")
        Set<String> exempted = (Set<String>) ReflectionTestUtils.getField(
                WorkflowDslValidator.class, "RESUME_REGISTERED_VARS");

        assertNotNull(exempted, "读不到 WorkflowDslValidator.RESUME_REGISTERED_VARS：字段被改名了？");
        assertEquals(NodeIoContract.defaultOutputNames(), exempted,
                "V19 的豁免集与 KeyStrategyFactoryGenerator 的注册集不同源了。"
                        + "两侧必须都从 NodeIoContract#defaultOutputNames() 派生 —— 见本测试的 javadoc，"
                        + "两个方向的漂移都是缺陷，而「豁免了却没注册」那一侧不会有别的测试变红");

        // 反向也要成立：注册集里每一个契约缺省名都真在工厂产物里
        Map<String, KeyStrategy> strategies =
                KeyStrategyFactoryGenerator.generate(new WorkflowDefDTO()).apply();
        assertTrue(strategies.keySet().containsAll(exempted),
                "豁免集里有键没被注册 → 那一档是「校验器沉默 + 运行期真丢」，最坏的组合");
    }

}
