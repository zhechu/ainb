package cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.VariableDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.HitlBpmApprovalNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.HitlConfirmNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.io.NodeIoContract;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.KeyStrategyFactoryBuilder;

/**
 * DSL variables → SAA {@link KeyStrategyFactory}（DSL §3.1/§3.3 的映射器）
 *
 * <p>验证④结论：OverAllState 就是 {@code Map<String,Object>} + 每键合并策略，与「全局 typed JSON context」
 * 同构，映射成本 LOW —— 本类 + {@code WorkflowContext} 就是那「两个适配类」之一。
 *
 * <h3>merge → KeyStrategy 的 1:1 翻译</h3>
 * replace→REPLACE、append→APPEND、merge→MERGE。
 *
 * <h3>🔴 defaultStrategy 设为 REPLACE 是有意兜底（但只兜「同一次运行内」）</h3>
 * 引擎会往 state 里写一些 DSL 没声明的内部键（如条件节点的路由键 {@code __route_<id>}、
 * 框架保留的 {@code _TOKEN_USAGE_}）。这些键不在 variables 里，靠默认 REPLACE 兜住，
 * 免得「未注册的键写不进 state」这类静默失败。业务变量仍逐个显式注册，保证 append/merge 生效。
 *
 * <p>但兜底<b>不跨挂起</b>：resume 时 SAA 走 {@code OverAllState.input(checkpointState)} 重载状态，
 * 而 {@code input()} 只保留<b>注册过 KeyStrategy</b> 的键（defaultStrategy 不给未声明键补注册，
 * {@code WaitNodeRuntimeTest} 已实测）。所以凡是「执行器会往 state 写、而作者不必在
 * {@code variables[]} 里声明」的键，都必须在这里显式注册。
 *
 * <h3>🔴 这一档的口径是 {@link NodeIoContract#defaultOutputNames()}（缺陷 C8）</h3>
 * 判据是<b>「作者不填这一格时执行器落哪个键名」</b>，而不是「这个键是不是回调写进来的」。
 * 原先这里只硬编码了三个 hitl 回调键（{@code hitl_approved}/{@code bpm_approved}/{@code bpm_status}），
 * 理由是「回调方拿不到节点 config，只能写死默认名」—— 那个理由<b>对，但不够</b>：
 * {@code tool_result}/{@code llm_output} 这类普通产出键在<b>下游有挂起点</b>时是一模一样的死法
 * （工具跑完 → wait → 恢复 → 键被 {@code input()} 滤掉 → 下游读到空值），
 * 而它们的作者同样<b>不需要</b>声明什么，缺省名是执行器自己兜的。
 *
 * <p>🔴 于是 C8 之前存在一个<b>校验器知情却不报</b>的洞：{@code WorkflowDslValidator} 的 V19
 * 对「这一格作者没填」刻意放过（它的前提②），而那一档恰恰就是会丢的那一档。
 * C8 之后洞被堵上，V19 的放过从「知情不报」变成「确实不会丢」；
 * 相应地 V19 的豁免集 {@code RESUME_REGISTERED_VARS} 必须<b>同源</b>扩到全部缺省名 ——
 * 否则作者把缺省名<b>显式抄一遍</b>时（如把 {@code approveVar} 填成 {@code bpm_approved}）会收到一条
 * 「这个值会在恢复时被丢弃」的<b>假警报</b>，而它给的改法（删掉这一格）还会把流程改坏。
 *
 * <p>⚠️ 上一段刻意<b>不给键名加引号</b>：{@code NodeIoContractTest#mainSourcesHaveNoDefaultNameLiterals}
 * 扫的是<b>文本行</b>、分不清注释与代码，javadoc 里把这些缺省名用双引号括起来就会被判成字面量副本
 * （它自己的 javadoc 第 3 条写了这个取舍，失败信息里也给了「把引号去掉即可」的处置）。
 * 2026-10-06 我第一版正是在这里写了一段带引号的示例 config，当场被它扫出来 —— 那条扫描是对的。
 * 🔴 于是本段也<b>不能</b>把当时的写法原样引出来「举例」，否则同样中招；只能这样描述它。
 *
 * <p>⚠️ 三个 hitl 键在下面仍<b>单独</b>注册了一遍，与这一趟重复。是刻意的，理由写在
 * {@link #generate} 里那三条 {@code addStrategy} 旁边（一句话：契约是元数据，谁把
 * {@code defaultName} 改成 null 都看不出后果，而这一趟会静默停止注册那个键）。
 *
 * @author 走进新时代
 */
public final class KeyStrategyFactoryGenerator {

    private KeyStrategyFactoryGenerator() {
    }

    public static KeyStrategyFactory generate(WorkflowDefDTO def) {
        KeyStrategyFactoryBuilder builder = new KeyStrategyFactoryBuilder()
                .defaultStrategy(KeyStrategy.REPLACE)
                // 🔴 无条件注册 hitl.confirm 的默认确认键：聊天内恢复（AgentAssistantServiceImpl）只会写死
                //   这一个键，而 DSL 自定义了 config.approveVar 的流程通常没在 variables[] 里声明它。
                //   不注册 → resume 的 OverAllState.input() 过滤掉 → 节点读到 null 判成拒绝，
                //   流程静默走 default 分支还把执行状态报成 SUCCEEDED（用户看到的就是「同意了却没干活」）。
                .addStrategy(HitlConfirmNodeExecutor.DEFAULT_APPROVE_VAR, KeyStrategy.REPLACE)
                // 🔴 同理无条件注册 hitl.bpm_approval 的两个回调键：AgentBpmApprovalListener 只写死
                //   bpm_approved / bpm_status（事件在审批线程上发布，拿不到节点 config），
                //   而 DSL 几乎不会在 variables[] 里声明它们。不注册 → resume 的 input() 过滤掉 →
                //   审批结果在恢复瞬间丢失，节点读到 null 把「通过」判成「驳回」还报 SUCCEEDED。
                .addStrategy(HitlBpmApprovalNodeExecutor.DEFAULT_APPROVE_VAR, KeyStrategy.REPLACE)
                .addStrategy(HitlBpmApprovalNodeExecutor.DEFAULT_STATUS_VAR, KeyStrategy.REPLACE);
        // 🔴 C8：把契约里**全部**非空的输出端口默认名都注册上，不再只挑那三个 hitl 键。
        //   为什么上面三条还要留着（明知与下面这一趟重复）：
        //   ① addStrategy 就是 Map.put，同键同值重复写零成本，而 variables[] 那趟在最后 ⟹ 作者的
        //      append/merge 仍然赢（KeyStrategyFactoryGeneratorTest 钉住了这一点）；
        //   ② 那三段的注释记的是**后果**（「用户点了同意却按拒绝走完、执行状态还报 SUCCEEDED」）
        //      与**成因**（回调方在审批线程上发事件、拿不到节点 config，只能写死默认名），
        //      删掉就把唯一一份病因记录删掉了；
        //   ③ 它们是一道**地板**：契约是「只描述、不执行」的元数据，谁把 hitl.confirm 的
        //      defaultName 改成 null（= 必须由作者命名）都看不出后果，而下面这一趟会**静默**
        //      停止注册那个键 —— 最坏的那个缺陷就这么回来了。三条常量引用把这条路堵死。
        //   ⚠️ 于是这里不是「两份清单靠人肉同步」：唯一口径是 NodeIoContract#defaultOutputNames()，
        //      上面三条只是它的一个**子集**，且子集关系由
        //      KeyStrategyFactoryGeneratorTest#generate_registersEveryContractDefaultName 断言着。
        for (String defaultName : NodeIoContract.defaultOutputNames()) {
            builder.addStrategy(defaultName, KeyStrategy.REPLACE);
        }
        if (def.getVariables() != null) {
            for (VariableDTO var : def.getVariables()) {
                if (var.getKey() == null || var.getKey().isEmpty()) {
                    continue;
                }
                builder.addStrategy(var.getKey(), toKeyStrategy(var.getMerge()));
            }
        }
        return builder.build();
    }

    private static KeyStrategy toKeyStrategy(String merge) {
        if (merge == null) {
            return KeyStrategy.REPLACE;
        }
        return switch (merge.trim().toLowerCase()) {
            case "append" -> KeyStrategy.APPEND;
            case "merge" -> KeyStrategy.MERGE;
            default -> KeyStrategy.REPLACE;
        };
    }

}
