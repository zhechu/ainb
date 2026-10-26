package cn.enlev.ainb.module.agent.framework.agent.core.validate;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.module.bpm.api.definition.BpmModelApi;
import cn.enlev.ainb.module.bpm.api.definition.dto.BpmProcessConstraintsRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 流程节点树的生成后校验（方案 §5.5「流程校验」9 条硬规则）
 *
 * <h3>为什么这一份校验比建表那份更要紧</h3>
 * 建表的错误<b>后端基本都会拦</b>，最坏是白烧一轮模型调用。
 * 流程不是 —— 这里有三类后果完全不同的失败：
 * <ol>
 *   <li><b>NPE</b>：缺结束节点、条件分支缺 conditionSetting，
 *       会在 {@code buildBpmnModel} 里直接空指针。用户看到的是「系统异常」，
 *       模型拿到的反馈也是「系统异常」—— 它改不动，只会原样重试</li>
 *   <li><b>部署期报错</b>：候选人没配、首节点用了「审批人自选」。这类还算好，至少报错了</li>
 *   <li>🔴 <b>静默失效</b>：{@code approveType} 不等于 1 时，
 *       候选人、审批方式、字段权限、按钮、超时、监听器<b>全部被丢弃</b>，
 *       而部署<b>照样成功</b>。表现是流程建好了、跑起来了，
 *       到了那个节点没有任何人能审批，且没有任何报错指向原因</li>
 * </ol>
 * 第三类是这个子系统的典型缺陷形态（CLAUDE.md 所谓「配了、保存成功了、但没生效」），
 * 所以对它<b>只能硬拦，不能给警告</b>。
 *
 * <h3>规则从哪来</h3>
 * 节点类型、候选人策略、审批方式、approveType 的取值一律来自
 * {@link BpmModelApi#getProcessConstraints()} —— 这里一个枚举值都不抄。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class BpmProcessValidator {

    /**
     * XML NCName：字母或下划线开头，后面是字母数字下划线连字符点
     *
     * 流程标识会被直接写进 BPMN XML 的 id，不合法就部署失败。
     */
    private static final Pattern NC_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.\\-]*$");

    private static final String FIELD_TYPE = "type";
    private static final String FIELD_ID = "id";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_CHILD_NODE = "childNode";
    private static final String FIELD_CONDITION_NODES = "conditionNodes";
    private static final String FIELD_CONDITION_SETTING = "conditionSetting";

    /** 节点类型：发起人 */
    private static final int TYPE_START_USER = 10;
    /** 节点类型：结束 */
    private static final int TYPE_END = 1;
    /** 节点类型：审批人 */
    private static final int TYPE_APPROVE = 11;
    /** 节点类型：办理人 */
    private static final int TYPE_TRANSACTOR = 13;
    /** 节点类型：条件 */
    private static final int TYPE_CONDITION = 50;
    /** 节点类型：条件分支 */
    private static final int TYPE_CONDITION_BRANCH = 51;
    /** 节点类型：并行分支 */
    private static final int TYPE_PARALLEL_BRANCH = 52;

    @Resource
    private BpmModelApi bpmModelApi;

    /**
     * 校验流程标识与节点树
     *
     * @param key            流程标识
     * @param simpleModelJson 节点树 JSON
     * @return 结果，{@code errors} 非空即不通过
     */
    public AgentValidateResult validate(String key, String simpleModelJson) {
        AgentValidateResult result = new AgentValidateResult();
        validateKey(key, result);

        JSONObject root = parseRoot(simpleModelJson, result);
        if (root == null) {
            return result;
        }

        BpmProcessConstraintsRespDTO constraints = bpmModelApi.getProcessConstraints();
        Context context = new Context(constraints, result);

        // 规则 1：根节点必须是发起人节点
        Integer rootType = root.getInt(FIELD_TYPE);
        if (!Objects.equals(rootType, TYPE_START_USER)) {
            result.addError("根节点的 type 必须是 %d（发起人），当前是 %s。"
                    + "不要自己加 type:0 的开始节点，后端会自动补", TYPE_START_USER, rootType);
        }

        walk(root, context);

        // 规则 2：树必须以结束节点收尾
        if (!context.hasEndNode) {
            result.addError("整棵节点树里没有 type:%d 的结束节点。"
                    + "必须在最后一个节点的 childNode 上挂 {\"id\":\"EndEvent\",\"name\":\"结束\",\"type\":%d}，"
                    + "否则后端在构造流程图时会直接空指针", TYPE_END, TYPE_END);
        }
        // 至少要有一个能办事的节点，否则这个流程发起即结束，没有任何意义
        if (context.approveNodeCount == 0) {
            result.addWarning("整个流程没有任何审批/办理节点，发起后会立刻结束。"
                    + "如果用户要的是审批流，这里应当至少有一个 type:%d 的审批节点", TYPE_APPROVE);
        }
        return result;
    }

    // ==================== 遍历 ====================

    /**
     * 遍历时要带着走的状态
     */
    private static class Context {

        private final BpmProcessConstraintsRespDTO constraints;
        private final AgentValidateResult result;
        /** 已出现过的节点 id，用于查重 */
        private final Set<String> seenIds = new HashSet<>();
        private boolean hasEndNode;
        private int approveNodeCount;

        Context(BpmProcessConstraintsRespDTO constraints, AgentValidateResult result) {
            this.constraints = constraints;
            this.result = result;
        }
    }

    private void walk(JSONObject node, Context context) {
        if (node == null || node.isEmpty()) {
            return;
        }
        Integer type = node.getInt(FIELD_TYPE);
        String id = node.getStr(FIELD_ID);
        String name = StrUtil.blankToDefault(node.getStr(FIELD_NAME), id);

        // id 必填：后端的 isValidNode 判据就是「id 非 null」，
        // id 为空的节点会被【整棵静默丢弃】，不报错 —— 这是最坏的一种失败
        if (StrUtil.isBlank(id)) {
            context.result.addError("有一个 type:%s 的节点没有 id。"
                    + "每个节点都必须有全局唯一的 id，没有 id 的节点会被后端静默丢弃，"
                    + "表现为「配了但流程里没有这个节点」", type);
        } else if (!context.seenIds.add(id)) {
            context.result.addError("节点 id「%s」重复了。id 会被用作 BPMN 元素标识，"
                    + "重复会导致后写的覆盖先写的", id);
        }

        if (type == null) {
            context.result.addError("节点「%s」没有 type", name);
            return;
        }
        if (!isKnownType(type, context)) {
            context.result.addError("节点「%s」的 type:%d 不是合法的节点类型。可用类型：%s",
                    name, type, describeNodeTypes(context));
            return;
        }

        if (type == TYPE_END) {
            context.hasEndNode = true;
        }
        if (type == TYPE_APPROVE || type == TYPE_TRANSACTOR) {
            context.approveNodeCount++;
            validateApproveNode(node, name, context);
        }
        if (isBranchType(type, context)) {
            validateBranchNode(node, name, type, context);
        }

        walk(node.getJSONObject(FIELD_CHILD_NODE), context);
    }

    // ==================== 审批节点 ====================

    private void validateApproveNode(JSONObject node, String name, Context context) {
        BpmProcessConstraintsRespDTO constraints = context.constraints;

        // 🔴 规则 6：approveType 必须是 1。这是唯一会静默失效的一条，必须硬拦
        Integer required = constraints.getRequiredApproveType();
        Integer approveType = node.getInt("approveType");
        if (!Objects.equals(approveType, required)) {
            context.result.addError("节点「%s」的 approveType 是 %s，必须改成 %d。"
                            + "不等于 %d 时，后端会把这个节点的候选人、审批方式、字段权限、按钮、"
                            + "超时设置【全部丢弃】，而且部署照样成功、不报任何错 —— "
                            + "结果是流程跑到这里没有任何人能审批",
                    name, approveType, required, required);
        }

        // 规则 4：approveMethod 事实必填
        Integer approveMethod = node.getInt("approveMethod");
        BpmProcessConstraintsRespDTO.ApproveMethod method = constraints.getApproveMethods().stream()
                .filter(item -> Objects.equals(item.getMethod(), approveMethod))
                .findFirst().orElse(null);
        if (method == null) {
            context.result.addError("节点「%s」的 approveMethod 是 %s，必须是下列之一：%s",
                    name, approveMethod, describeApproveMethods(context));
        } else if (Boolean.TRUE.equals(method.getRatioRequired())
                && node.getInt("approveRatio") == null) {
            // 规则 5
            context.result.addError("节点「%s」用了 approveMethod:%d（%s），"
                            + "必须同时给出 approveRatio（通过比例，如 100）",
                    name, method.getMethod(), method.getName());
        }

        // 规则 9：候选人策略与 candidateParam 的格式
        validateCandidate(node, name, context);
    }

    private void validateCandidate(JSONObject node, String name, Context context) {
        Integer strategyValue = node.getInt("candidateStrategy");
        String param = node.getStr("candidateParam");
        BpmProcessConstraintsRespDTO.CandidateStrategy strategy =
                context.constraints.getCandidateStrategies().stream()
                        .filter(item -> Objects.equals(item.getStrategy(), strategyValue))
                        .findFirst().orElse(null);
        if (strategy == null) {
            context.result.addError("节点「%s」的 candidateStrategy 是 %s，不是合法的候选人策略。"
                            + "最常用的是 30（指定用户，candidateParam 写逗号分隔的用户编号）",
                    name, strategyValue);
            return;
        }
        if (Boolean.TRUE.equals(strategy.getParamRequired()) && StrUtil.isBlank(param)) {
            context.result.addError("节点「%s」用了 candidateStrategy:%d（%s），"
                            + "必须提供 candidateParam：%s",
                    name, strategy.getStrategy(), strategy.getName(),
                    StrUtil.blankToDefault(strategy.getParamFormat(), "见流程配置规则"));
        }
        // 竖线格式那两条会在策略实现里 Assert 失败，提前拦住
        if (StrUtil.isNotBlank(param) && StrUtil.isNotBlank(strategy.getParamFormat())
                && strategy.getParamFormat().contains("|")
                && param.split("\\|").length != 2) {
            context.result.addError("节点「%s」的 candidateParam「%s」格式不对，应为 %s",
                    name, param, strategy.getParamFormat());
        }
    }

    // ==================== 分支节点 ====================

    private void validateBranchNode(JSONObject node, String name, Integer type, Context context) {
        JSONArray conditionNodes = node.getJSONArray(FIELD_CONDITION_NODES);
        if (conditionNodes == null || conditionNodes.isEmpty()) {
            context.result.addError("分支节点「%s」没有 conditionNodes。"
                    + "分支节点必须带至少两个 type:%d 的条件节点，每个条件节点用自己的 childNode "
                    + "挂该分支要走的节点", name, TYPE_CONDITION);
            return;
        }

        int defaultFlowCount = 0;
        for (Object item : conditionNodes) {
            if (!(item instanceof JSONObject branch)) {
                continue;
            }
            Integer branchType = branch.getInt(FIELD_TYPE);
            if (!Objects.equals(branchType, TYPE_CONDITION)) {
                context.result.addError("分支节点「%s」的 conditionNodes 里有一个 type:%s 的节点，"
                        + "conditionNodes 里只能放 type:%d 的条件节点", name, branchType, TYPE_CONDITION);
                continue;
            }
            String branchName = StrUtil.blankToDefault(
                    branch.getStr(FIELD_NAME), branch.getStr(FIELD_ID));

            // 规则 3 的前半：conditionSetting 非空。
            // 并行分支不走条件求值，后端会把条件强制写成 ${true}，所以不要求
            JSONObject setting = branch.getJSONObject(FIELD_CONDITION_SETTING);
            if (setting == null) {
                if (!Objects.equals(type, TYPE_PARALLEL_BRANCH)) {
                    context.result.addError("条件节点「%s」缺少 conditionSetting。"
                            + "缺了会让后端在取默认分支时直接空指针，用户只会看到「系统异常」",
                            branchName);
                }
                continue;
            }
            if (Boolean.TRUE.equals(setting.getBool("defaultFlow"))) {
                defaultFlowCount++;
                continue;
            }
            validateConditionSetting(setting, branchName, context);
        }

        // 规则 3 的后半：恰有一个默认分支
        if (Objects.equals(type, TYPE_CONDITION_BRANCH)) {
            if (defaultFlowCount == 0) {
                context.result.addError("条件分支「%s」没有默认分支。"
                        + "必须有且只有一个条件节点的 conditionSetting.defaultFlow 为 true"
                        + "（用来兜住所有条件都不满足的情况），否则后端会直接报错", name);
            } else if (defaultFlowCount > 1) {
                context.result.addError("条件分支「%s」有 %d 个默认分支，只能有一个",
                        name, defaultFlowCount);
            }
        }

        // 每个条件节点自己的 childNode 也要遍历到
        for (Object item : conditionNodes) {
            if (item instanceof JSONObject branch) {
                walk(branch.getJSONObject(FIELD_CHILD_NODE), context);
            }
        }
    }

    private void validateConditionSetting(JSONObject setting, String branchName, Context context) {
        Integer conditionType = setting.getInt("conditionType");
        List<Integer> allowed = context.constraints.getConditionTypes().stream()
                .map(BpmProcessConstraintsRespDTO.ConditionType::getType).toList();
        if (conditionType == null || !allowed.contains(conditionType)) {
            // 这条不报错会很难查：conditionType 为 null 时后端返回 null 表达式，
            // 也就是「无条件」—— 该分支变成恒成立，流程永远走它
            context.result.addError("条件节点「%s」的 conditionType 是 %s，必须是 %s。"
                            + "不填的话这条分支会变成【无条件】，也就是永远成立，"
                            + "其它分支再也走不到，而且不报任何错",
                    branchName, conditionType, allowed);
            return;
        }
        if (conditionType == 1 && StrUtil.isBlank(setting.getStr("conditionExpression"))) {
            context.result.addError("条件节点「%s」用了 conditionType:1（条件表达式），"
                    + "必须提供 conditionExpression，如 "
                    + "${var:getOrDefault(amount, null) > 1000}", branchName);
        }
        if (conditionType == 2 && setting.getJSONObject("conditionGroups") == null) {
            context.result.addError("条件节点「%s」用了 conditionType:2（条件规则），"
                    + "必须提供 conditionGroups", branchName);
        }
        // 直接引用变量在变量缺失时会抛异常，整个流程卡在这里
        String expression = setting.getStr("conditionExpression");
        if (conditionType == 1 && StrUtil.isNotBlank(expression)
                && !expression.contains("var:")) {
            context.result.addWarning(String.format(
                    "条件节点「%s」的表达式「%s」直接引用了流程变量。"
                            + "建议改写成 ${var:getOrDefault(字段名, null) ...} 的形式 —— "
                            + "直接引用时，若该字段没填，流程会在这里抛异常卡住",
                    branchName, expression));
        }
    }

    // ==================== 流程标识 ====================

    private void validateKey(String key, AgentValidateResult result) {
        if (StrUtil.isBlank(key)) {
            result.addError("必须提供流程标识 key");
            return;
        }
        // 规则 8
        if (!NC_NAME.matcher(key).matches()) {
            result.addError("流程标识「%s」不合法。只能用字母开头，后面是字母、数字、下划线，"
                    + "不能有中文、空格、冒号。建议改成全小写下划线，如 purchase_apply", key);
        }
    }

    // ==================== 首个审批节点 ====================

    /**
     * 校验首个审批节点的候选人策略（规则 7）
     *
     * 单独暴露而不是塞进 {@link #walk} 里：「第一个」这个概念只有在整棵树遍历完、
     * 知道顺序之后才成立，而且分支里的节点算不算「第一个」取决于后端怎么数 ——
     * 后端数的是 BPMN 里的 UserTask 顺序（SIMPLE 设计器取第 1 个，第 0 个固定是发起人）。
     * 这里只按主链判断，保守但不会误伤。
     *
     * @param root 根节点
     * @param result 校验结果
     */
    public void validateFirstApproveNode(JSONObject root, AgentValidateResult result) {
        BpmProcessConstraintsRespDTO constraints = bpmModelApi.getProcessConstraints();
        List<Integer> forbidden = constraints.getFirstNodeForbiddenStrategies();
        JSONObject node = root == null ? null : root.getJSONObject(FIELD_CHILD_NODE);
        while (node != null && !node.isEmpty()) {
            Integer type = node.getInt(FIELD_TYPE);
            if (Objects.equals(type, TYPE_APPROVE) || Objects.equals(type, TYPE_TRANSACTOR)) {
                Integer strategy = node.getInt("candidateStrategy");
                if (forbidden.contains(strategy)) {
                    result.addError("第一个审批节点「%s」不能用 candidateStrategy:%d（审批人自选）。"
                                    + "「自选」要由上一个审批人来指定下一个审批人，"
                                    + "而第一个节点前面没有审批人，部署时会被拒绝",
                            StrUtil.blankToDefault(node.getStr(FIELD_NAME), node.getStr(FIELD_ID)),
                            strategy);
                }
                return;
            }
            node = node.getJSONObject(FIELD_CHILD_NODE);
        }
    }

    // ==================== 辅助 ====================

    private JSONObject parseRoot(String simpleModelJson, AgentValidateResult result) {
        if (StrUtil.isBlank(simpleModelJson)) {
            result.addError("必须提供流程节点树 simpleModel");
            return null;
        }
        try {
            return JSONUtil.parseObj(simpleModelJson);
        } catch (Exception ex) {
            result.addError("流程节点树不是合法的 JSON：%s", ex.getMessage());
            return null;
        }
    }

    private boolean isKnownType(Integer type, Context context) {
        return context.constraints.getNodeTypes().stream()
                .anyMatch(item -> Objects.equals(item.getType(), type));
    }

    private boolean isBranchType(Integer type, Context context) {
        return context.constraints.getNodeTypes().stream()
                .anyMatch(item -> Objects.equals(item.getType(), type)
                        && Boolean.TRUE.equals(item.getBranch()));
    }

    private String describeNodeTypes(Context context) {
        return context.constraints.getNodeTypes().stream()
                .map(item -> item.getType() + "=" + item.getName())
                .collect(Collectors.joining("、"));
    }

    private String describeApproveMethods(Context context) {
        return context.constraints.getApproveMethods().stream()
                .map(item -> item.getMethod() + "=" + item.getName())
                .collect(Collectors.joining("、"));
    }

    /**
     * 把校验用到的规则整理成给模型看的文本，供 prompt 使用
     */
    public String describeConstraintsForPrompt() {
        BpmProcessConstraintsRespDTO constraints = bpmModelApi.getProcessConstraints();
        StringBuilder sb = new StringBuilder();
        sb.append("常用节点类型：10=发起人（根节点）、11=审批人、12=抄送人、13=办理人、")
                .append("51=条件分支、50=条件、1=结束\n");
        sb.append("审批方式 approveMethod：");
        sb.append(constraints.getApproveMethods().stream()
                .map(item -> item.getMethod() + "=" + item.getName())
                .collect(Collectors.joining("、")));
        sb.append('\n');
        sb.append("常用候选人策略 candidateStrategy：\n");
        List<String> lines = new ArrayList<>();
        Set<Integer> common = Set.of(30, 10, 21, 37, 22, 40);
        for (BpmProcessConstraintsRespDTO.CandidateStrategy item : constraints.getCandidateStrategies()) {
            if (!common.contains(item.getStrategy())) {
                continue;
            }
            lines.add("  " + item.getStrategy() + "=" + item.getName()
                    + (StrUtil.isBlank(item.getParamFormat()) ? ""
                    : "，candidateParam：" + item.getParamFormat()));
        }
        sb.append(String.join("\n", lines)).append('\n');
        return sb.toString();
    }

    /**
     * 给 prompt 用的最小可用样例
     *
     * 放在代码里而不是让模型自由发挥：这棵树的每一处都是必须的，
     * 少任何一项都会触发上面某条规则，而其中两项（结束节点、conditionSetting）
     * 的失败形态是空指针。给个能照抄的骨架，比让它自己试要省好几轮。
     */
    public static final String FEW_SHOT_EXAMPLE = """
            {
              "id": "StartUserNode", "name": "发起人", "type": 10,
              "childNode": {
                "id": "Activity_dept", "name": "部门负责人审批", "type": 11,
                "approveType": 1, "approveMethod": 1,
                "candidateStrategy": 37, "candidateParam": "1",
                "childNode": {
                  "id": "GateWay_amount", "name": "按金额分流", "type": 51,
                  "conditionNodes": [
                    {
                      "id": "Flow_big", "name": "金额大于1000", "type": 50,
                      "conditionSetting": {
                        "defaultFlow": false, "conditionType": 1,
                        "conditionExpression": "${var:getOrDefault(amount, null) > 1000}"
                      },
                      "childNode": {
                        "id": "Activity_boss", "name": "总经理审批", "type": 11,
                        "approveType": 1, "approveMethod": 1,
                        "candidateStrategy": 30, "candidateParam": "1"
                      }
                    },
                    {
                      "id": "Flow_else", "name": "其它情况", "type": 50,
                      "conditionSetting": { "defaultFlow": true }
                    }
                  ],
                  "childNode": { "id": "EndEvent", "name": "结束", "type": 1 }
                }
              }
            }""";

}
