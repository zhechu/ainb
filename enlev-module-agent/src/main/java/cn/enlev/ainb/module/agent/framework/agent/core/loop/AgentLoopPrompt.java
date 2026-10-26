package cn.enlev.ainb.module.agent.framework.agent.core.loop;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolCatalog;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * agent_loop 节点的 system prompt 组装
 *
 * <h3>为什么协议只有两种 type（tool_calls / answer，没有 plan）</h3>
 * agent_loop 是<b>只读的自主调查循环</b>：它只「查清现状 → 给结论」，不产出写方案，
 * 所以协议只有两种 type（tool_calls / answer），
 * 且只列<b>本节点 {@code tools[]} 声明的那几个只读工具</b>，不是全量工具表。
 * （增量7b 之前另有线性 planner，其协议带第三种 {@code plan} type 用于产出写方案；
 * 线性 planner 已删除，写方案改由 plan-as-workflow 生成器产 DSL，见 {@code WorkflowDslPlannerPrompt}。）
 *
 * <h3>🔴 为什么只列子集、且强调「都是只读的」</h3>
 * 循环里无人工确认，写工具一律禁止自动执行（编译期已拒、运行期再拦）。
 * prompt 里只摆只读工具，是从源头让模型不去点写操作；再明说「需要写就交给后续流程」，
 * 免得它因为「看不到建表工具」而困惑或编造。
 *
 * @author 走进新时代
 */
@Component
public class AgentLoopPrompt {

    @Resource
    private AgentToolCatalog toolCatalog;

    /**
     * 组装 agent_loop 的 system prompt
     *
     * @param toolNames 本节点可用的只读工具名（编译期已校验存在且 {@code isRequireConfirm()==false}）
     * @param maxSteps  自主循环的最大步数，取自节点 config 或 {@code enlev.agent.loop.default-max-steps}
     */
    public String buildSystemPrompt(List<String> toolNames, int maxSteps) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                你是一个自主调查助手。用户给你一个目标，你用只读工具查清现状，然后给出结论。

                ## 你的工作方式

                你每次只能回复一个 JSON 对象，不要输出任何其它文字，也不要用代码围栏包裹。
                JSON 必须是下面两种形态之一：

                1. 需要先查点东西才能继续时：
                   {"type":"tool_calls","calls":[{"tool":"工具名","params":{...}}]}
                   一次可以并列多个查询。查询结果会回灌给你，然后你继续下一步。

                2. 已经查清、可以下结论时：
                   {"type":"answer","text":"给用户的最终结论"}

                ## 硬规则

                - **你一共只有 %d 步（每执行一批工具调用记一步），最后必须给出 answer，不能一直查下去。**
                  所以查询要攒着发：把这一阶段需要的工具<b>一次并列全列出来</b>，不要一步只查一个。
                  信息够了就立刻出 answer，别为了"再确认一下"多烧一步。
                - **你只能用下面列出的这几个工具，它们全是只读的。**
                  写操作（建表、改表、存表单、发布流程等）<b>不在你的能力范围内</b>，
                  也不该由你自动执行 —— 如果结论是「需要做某个变更」，
                  就用 answer 把结论和建议讲清楚，交给后续流程或用户去确认执行。
                - 只能输出上面两种 JSON 之一；不要输出 plan，也不要输出裸文本。
                - 拿不准、或信息确实不够时，用 answer 说明还缺什么，<b>不要编造</b>。
                  编一个看着合理实则没根据的结论，比坦白「查不到」危害大得多。

                ## 可用工具（全部只读）

                """.formatted(maxSteps));

        appendTools(sb, toolNames);
        return sb.toString();
    }

    /**
     * 只渲染 {@code toolNames} 点名的那几个工具
     *
     * <p>与规划器「列全量、按读写分栏」不同：这里逐个按名从目录取定义，
     * 取不到就跳过（编译期/发布期已校验过存在，跳过只是防御 —— 例如运行期工具被停用）。
     *
     * <p>🔴 取的是 {@link AgentToolCatalog}（内置 ∪ 自定义）而不是 {@code AgentToolRegistry}（B2）。
     * 本方法是<b>两个</b>循环共用的 system prompt 渲染 —— {@code agent_loop} 节点走
     * {@code AgentLoopGraphBuilder}，聊天走 {@code AgentChatToolLoop}。此前它只查 registry，
     * 于是自定义工具即便被绑上来也<b>进不了 prompt</b>：闸放行了、模型却压根不知道有这个工具，
     * 用户看到的就是「配了但 AI 从来不用它」，而且没有任何一处会提示。
     * 这比发布期直接拒掉更坏 —— 拒掉至少 fail-loud。
     *
     * <p>⚠️ 自定义工具的 {@code description}/{@code paramsSchema} 是<b>库里的一行数据</b>，
     * 与内置工具「代码里的常量」不同源，这里却<b>照样内联渲染、不套 {@code PromptDataFence}</b>。
     * 这是跟着既有约定走的，不是漏了：{@code WorkflowDslPlannerPrompt#appendTools} 早在把
     * {@code getEnabledToolDefList()} 的说明与参数混进同一份工具目录时就是这么渲染的
     * （{@code - **key**（displayName）：description}），两处形状必须一致，否则模型在同一轮里
     * 会看到「同一个工具在两份目录里一份被围栏、一份没有」。写这两列需要
     * {@code agent:tool-def:create}，与 {@code persona} 不同（那是任意用户可改自己的），
     * 故按「管理员配置」而非「外部输入」对待。
     */
    private void appendTools(StringBuilder sb, List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            sb.append("（本节点没有配置任何可用工具，请直接用 answer 回应。）\n");
            return;
        }
        for (String name : toolNames) {
            AgentToolCatalog.ToolView tool = toolCatalog.resolve(name);
            if (tool == null) {
                continue;
            }
            sb.append("### ").append(tool.name()).append('\n')
                    // 🔴 说明可能为空（自定义工具的 description 列没有 @NotBlank）：
                    //    直接 append(null) 会在 prompt 里留下字面量 "null"，
                    //    模型会把它当成工具说明的一部分读进去
                    .append(StrUtil.blankToDefault(tool.description(), "（无说明）")).append('\n');
            String schema = tool.paramsSchema();
            if (StrUtil.isNotBlank(schema)) {
                sb.append("参数：\n").append(schema).append('\n');
            }
            sb.append('\n');
        }
    }

}
