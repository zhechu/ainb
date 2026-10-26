package cn.enlev.ainb.module.agent.framework.agent.core.explain;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.enlev.ainb.module.bpm.api.definition.dto.BpmProcessConstraintsRespDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 把仿钉钉设计器的流程节点树翻译成一句一句中文
 *
 * <h3>为什么它从 {@code ExplainProcessTool} 里搬出来</h3>
 * 变更清单（S2）要说「部署审批流 X 之后，谁审批、什么条件走哪条分支」，
 * 而这套翻译逻辑原本是 {@code ExplainProcessTool} 的 ~150 行 private 方法。
 * 在 agent 侧再抄一份 = 又一份会漂的副本（本仓库最恨的失败形态，见方案 D7）：
 * 上游加一种候选人策略，工具解释得出来、清单解释成「未知」，
 * 两处对同一条流程给出两种说法，而用户看到的恰恰是这两句并排出现。
 *
 * <h3>为什么是静态工具类而不是 {@code @Component}</h3>
 * 与 {@code GraphReachability} 同一个理由：<b>它不做任何 I/O</b>。
 * 平台对照表 {@code constraints} 由调用方查好传进来，于是「这里不会偷偷查库」
 * 是<b>结构上成立</b>的，而不是一句需要人去信的承诺。
 * 这一点对清单尤其要紧 —— 抽取器不许查库，干跑器才许，
 * 静态签名让这条边界在编译期就看得见。
 *
 * <h3>🔴 对照表拿不到时怎么说</h3>
 * 三种降级，一种都不许「装作没问题」，也不许「编一个诊断」：
 * <ul>
 *   <li>{@code constraints} 整个为 null（干跑器 fail-soft 时会是这个）——
 *       直接一句话说明「翻译不了」并返回，<b>不</b>输出半棵树。
 *       理由是此时连「哪些节点是分支」都判不了，硬画出来的走向是错的，
 *       而错误的走向比没有走向更危险（审批人会照它判断风险）</li>
 *   <li>某个子清单为 null（{@link BpmProcessConstraintsRespDTO} 的 List 字段<b>都没有初始值</b>）——
 *       说「平台没给出 X 清单，编码 N 翻译不出来」，
 *       而<b>不是</b>沿用 {@code describeCandidate} 那句「没有配置审批人」：
 *       后者是一句关于<b>用户配置</b>的断言，而事实是<b>我们查不到</b>，
 *       两件事混为一谈会让人去改一个本来没配错的地方</li>
 *   <li>{@code nodeTypes} 为 null —— 退回结构判断（有 {@code conditionNodes} 就当分支）。
 *       这个方向是挑过的：误判成分支只是多展开一层、看得见；
 *       反过来判不成分支，整棵 {@code conditionNodes} 子树会在解读里<b>静默消失</b></li>
 * </ul>
 *
 * <p>⚠️ 对照表<b>拿得到</b>时的所有措辞，与搬出来之前<b>逐字一致</b> ——
 * 由 {@code ProcessNodeTreeExplainerTest} 的黄金快照钉住（快照是重构前实测导出的，
 * 不是照着代码推的）。
 *
 * @author 走进新时代
 */
public final class ProcessNodeTreeExplainer {

    /** 审批节点 */
    private static final int TYPE_END = 1;
    private static final int TYPE_START_USER = 10;
    private static final int TYPE_APPROVE = 11;
    private static final int TYPE_COPY = 12;
    private static final int TYPE_TRANSACTOR = 13;
    private static final int TYPE_CONDITION = 50;

    private ProcessNodeTreeExplainer() {
    }

    /**
     * 翻译整棵树
     *
     * <p>返回<b>行列表</b>而不是拼好的字符串：调用方两个都要
     * （{@code ExplainProcessTool} 要 {@code explanation} 与 {@code nodeCount}，
     * 清单只要拼起来的那一句）。在这里就拼掉的话，
     * 行数得靠 {@code split("\n")} 反推 —— 而空树会得到 1 而不是 0。
     *
     * @param root        节点树根（{@code simpleModel} 解析出来的对象）；null 或空 → 空列表
     * @param constraints 平台对照表；null → 只有一行「翻译不了」的说明
     */
    public static List<String> explain(JSONObject root, BpmProcessConstraintsRespDTO constraints) {
        if (constraints == null) {
            return List.of("⚠️ 取不到平台的流程规则对照表（bpm 未响应或返回为空），"
                    + "既判不了哪些节点是分支、也翻译不了审批人编码，故不给出走向解读");
        }
        List<String> lines = new ArrayList<>();
        describe(root, constraints, lines, 0);
        return lines;
    }

    /**
     * 递归把节点树翻译成中文
     *
     * @param depth 缩进层级，用来体现分支的嵌套关系
     */
    private static void describe(JSONObject node, BpmProcessConstraintsRespDTO constraints,
                                 List<String> lines, int depth) {
        if (node == null || node.isEmpty()) {
            return;
        }
        String indent = "  ".repeat(depth);
        Integer type = node.getInt("type");
        String name = StrUtil.blankToDefault(node.getStr("name"), node.getStr("id"));

        if (Objects.equals(type, TYPE_START_USER)) {
            lines.add(indent + "① 由发起人提交申请");
        } else if (Objects.equals(type, TYPE_END)) {
            lines.add(indent + "⑧ 流程结束");
        } else if (Objects.equals(type, TYPE_APPROVE) || Objects.equals(type, TYPE_TRANSACTOR)) {
            lines.add(indent + "→ " + (Objects.equals(type, TYPE_APPROVE) ? "审批节点" : "办理节点")
                    + "「" + name + "」：" + describeApprover(node, constraints));
        } else if (Objects.equals(type, TYPE_COPY)) {
            lines.add(indent + "→ 抄送「" + name + "」：" + describeCandidate(node, constraints));
        } else if (isBranch(node, constraints)) {
            lines.add(indent + "→ 分支「" + name + "」，按下面的条件分流：");
            describeBranches(node, constraints, lines, depth + 1);
        } else {
            lines.add(indent + "→ 节点「" + name + "」（类型 " + type + "）");
        }

        describe(node.getJSONObject("childNode"), constraints, lines, depth);
    }

    private static void describeBranches(JSONObject node, BpmProcessConstraintsRespDTO constraints,
                                         List<String> lines, int depth) {
        JSONArray conditionNodes = node.getJSONArray("conditionNodes");
        if (conditionNodes == null || conditionNodes.isEmpty()) {
            lines.add("  ".repeat(depth) + "⚠️ 这个分支没有配任何条件节点，流程走到这里会出错");
            return;
        }
        String indent = "  ".repeat(depth);
        for (Object item : conditionNodes) {
            if (!(item instanceof JSONObject branch)
                    || !Objects.equals(branch.getInt("type"), TYPE_CONDITION)) {
                continue;
            }
            String branchName = StrUtil.blankToDefault(
                    branch.getStr("name"), branch.getStr("id"));
            JSONObject setting = branch.getJSONObject("conditionSetting");
            lines.add(indent + "· 分支「" + branchName + "」：" + describeCondition(setting));
            describe(branch.getJSONObject("childNode"), constraints, lines, depth + 1);
        }
    }

    /**
     * 把条件翻译成人话
     */
    private static String describeCondition(JSONObject setting) {
        if (setting == null) {
            return "⚠️ 没有配置条件（后端处理这种情况时会出错）";
        }
        if (Boolean.TRUE.equals(setting.getBool("defaultFlow"))) {
            return "以上条件都不满足时，走这一条（默认分支）";
        }
        Integer conditionType = setting.getInt("conditionType");
        if (Objects.equals(conditionType, 1)) {
            String expression = setting.getStr("conditionExpression");
            return StrUtil.isBlank(expression)
                    ? "⚠️ 选了「条件表达式」却没填表达式，这条分支会变成恒成立"
                    : "满足表达式 " + expression + " 时走这一条";
        }
        if (Objects.equals(conditionType, 2)) {
            return describeConditionGroups(setting.getJSONObject("conditionGroups"));
        }
        // 这是个真实的坑：conditionType 为空时后端生成的是「无条件」连线
        return "⚠️ 没有指定条件类型 —— 这条分支实际上是【无条件】的，"
                + "也就是永远成立，它后面的分支再也走不到，而且不报任何错";
    }

    private static String describeConditionGroups(JSONObject groups) {
        if (groups == null) {
            return "⚠️ 选了「条件规则」却没填规则";
        }
        JSONArray conditions = groups.getJSONArray("conditions");
        if (conditions == null || conditions.isEmpty()) {
            return "⚠️ 条件规则是空的";
        }
        List<String> parts = new ArrayList<>();
        for (Object item : conditions) {
            if (!(item instanceof JSONObject condition)) {
                continue;
            }
            JSONArray rules = condition.getJSONArray("rules");
            if (rules == null) {
                continue;
            }
            List<String> ruleTexts = new ArrayList<>();
            for (Object ruleItem : rules) {
                if (ruleItem instanceof JSONObject rule) {
                    ruleTexts.add(rule.getStr("leftSide") + " " + rule.getStr("opCode")
                            + " " + rule.getStr("rightSide"));
                }
            }
            parts.add(String.join(Boolean.TRUE.equals(condition.getBool("and")) ? " 且 " : " 或 ",
                    ruleTexts));
        }
        return "满足「" + String.join(
                Boolean.TRUE.equals(groups.getBool("and")) ? " 且 " : " 或 ", parts)
                + "」时走这一条";
    }

    /**
     * 描述审批人与审批方式
     */
    private static String describeApprover(JSONObject node, BpmProcessConstraintsRespDTO constraints) {
        List<String> parts = new ArrayList<>();
        parts.add(describeCandidate(node, constraints));

        Integer approveMethod = node.getInt("approveMethod");
        List<BpmProcessConstraintsRespDTO.ApproveMethod> methods = constraints.getApproveMethods();
        if (methods == null) {
            if (approveMethod != null) {
                parts.add("⚠️ 平台没给出审批方式清单，approveMethod=" + approveMethod + " 翻译不出来");
            }
        } else {
            methods.stream()
                    .filter(item -> Objects.equals(item.getMethod(), approveMethod))
                    .findFirst()
                    .ifPresent(item -> {
                        String text = item.getName();
                        if (Boolean.TRUE.equals(item.getRatioRequired())
                                && node.getInt("approveRatio") != null) {
                            text += "（需 " + node.getInt("approveRatio") + "% 通过）";
                        }
                        parts.add(text);
                    });
        }

        // 🔴 approveType 不对的话，上面这些全都不生效。解释里必须说出来 ——
        //    否则用户读完解释以为配好了，实际跑起来没人能审批
        Integer required = constraints.getRequiredApproveType();
        if (required != null && !Objects.equals(node.getInt("approveType"), required)) {
            parts.add("⚠️ 该节点的 approveType 是 " + node.getInt("approveType")
                    + "（应为 " + required + "），上面这些配置实际上【全部不生效】，"
                    + "流程跑到这里没有人能审批");
        }
        return String.join("，", parts);
    }

    /**
     * 描述候选人
     */
    private static String describeCandidate(JSONObject node, BpmProcessConstraintsRespDTO constraints) {
        Integer strategy = node.getInt("candidateStrategy");
        String param = node.getStr("candidateParam");
        List<BpmProcessConstraintsRespDTO.CandidateStrategy> strategies = constraints.getCandidateStrategies();
        if (strategies == null) {
            return "⚠️ 平台没给出候选人策略清单，candidateStrategy=" + strategy + " 翻译不出来";
        }
        return strategies.stream()
                .filter(item -> Objects.equals(item.getStrategy(), strategy))
                .findFirst()
                .map(item -> StrUtil.isBlank(param)
                        ? "由「" + item.getName() + "」审批"
                        : "由「" + item.getName() + "」审批（参数 " + param + "）")
                .orElse("⚠️ 没有配置审批人（candidateStrategy=" + strategy + "），部署时会被拒绝");
    }

    private static boolean isBranch(JSONObject node, BpmProcessConstraintsRespDTO constraints) {
        List<BpmProcessConstraintsRespDTO.NodeType> nodeTypes = constraints.getNodeTypes();
        if (nodeTypes != null) {
            Integer type = node.getInt("type");
            return nodeTypes.stream()
                    .anyMatch(item -> Objects.equals(item.getType(), type)
                            && Boolean.TRUE.equals(item.getBranch()));
        }
        JSONArray conditionNodes = node.getJSONArray("conditionNodes");
        return conditionNodes != null && !conditionNodes.isEmpty();
    }

}
