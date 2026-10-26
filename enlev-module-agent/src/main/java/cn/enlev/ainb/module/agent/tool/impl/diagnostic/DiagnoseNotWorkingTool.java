package cn.enlev.ainb.module.agent.tool.impl.diagnostic;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.bpm.api.definition.BpmModelApi;
import cn.enlev.ainb.module.bpm.api.definition.dto.BpmModelRespDTO;
import cn.enlev.ainb.module.bpm.api.definition.dto.BpmProcessConstraintsRespDTO;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import cn.enlev.ainb.module.system.api.permission.SystemDiagnosticApi;
import cn.enlev.ainb.module.system.api.permission.dto.ResourceDiagnosticRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_TOOL_PARAM_INVALID;

/**
 * 工具：诊断「配了但不生效」（方案 §5.8）
 *
 * <h3>为什么专门为这一类问题做个工具</h3>
 * 这个子系统最常见的缺陷<b>不是报错</b>，而是「配了、保存成功了、发布成功了、
 * 但就是没生效，且没有任何提示」。它难查的原因是：所有能看的地方看起来都是对的 ——
 * 界面上配置在、数据库里记录在、接口返回 200。
 * 线索只存在于<b>几个特定位置的取值对不对得上</b>，而那些位置分散在三个模块里。
 *
 * <p>这个工具做的就是按已知清单把那几处逐条查出来比对。
 *
 * <h3>🔴 它的边界</h3>
 * <b>只做静态比对，不能替代在浏览器里真跑一遍。</b>
 * 每次输出都必须带上这句话 —— 因为「工具说没问题」很容易被当成「问题解决了」，
 * 而这个子系统里「静态检查全过、实际仍然不生效」是常态。
 *
 * @author 走进新时代
 */
@Component
@AgentTool(
        name = "diagnose_not_working",
        displayName = "诊断配了不生效问题",
        groupName = "诊断排错",
        description = "诊断「配了但不生效」类问题。按已知的静默失效清单逐条比对，"
                + "输出可能原因与验证方法。适用于：节点字段权限不生效、行级规则不生效、"
                + "授权配了等于没配、子表字段权限不生效、流程节点没人能审批。"
                + "⚠️ 只做静态比对，不能替代在浏览器里真跑一遍",
        permission = "agent:assistant:use",
        risk = RiskLevel.READ_ONLY)
@Slf4j
public class DiagnoseNotWorkingTool implements AgentToolFunction {

    public static final String PARAM_TARGET_TYPE = "targetType";
    public static final String PARAM_TARGET = "target";

    private static final String TYPE_PROCESS = "process";
    private static final String TYPE_PERMISSION = "permission";
    private static final String TYPE_TABLE = "table";
    private static final String TYPE_SUB_FIELD = "subField";

    /**
     * 这句话每次都要出现在输出里，不是客套
     */
    private static final String DISCLAIMER =
            "⚠️ 以上只是静态比对的结果，不能替代在浏览器里真跑一遍。"
                    + "这个子系统里「静态检查全过、实际仍然不生效」是常态 —— "
                    + "请务必用一个真实账号走一遍完整流程来确认。";

    @Resource
    private SystemDiagnosticApi systemDiagnosticApi;
    @Resource
    private BpmModelApi bpmModelApi;
    @Resource
    private FormDesignApi formDesignApi;

    @Override
    public Object execute(Map<String, Object> params) {
        String targetType = requireString(params, PARAM_TARGET_TYPE, "诊断对象类型");
        String target = StrUtil.trimToEmpty(asString(params.get(PARAM_TARGET)));

        List<Finding> findings = new ArrayList<>();
        switch (targetType.toLowerCase(Locale.ROOT)) {
            case "process" -> diagnoseProcess(target, findings);
            case "permission" -> diagnosePermission(target, findings);
            case "table" -> diagnoseTable(target, findings);
            case "subfield" -> diagnoseSubFieldSeparator(findings);
            default -> throw exception(AGENT_TOOL_PARAM_INVALID, "diagnose_not_working",
                    "targetType 只能是 " + String.join(" / ",
                            TYPE_PROCESS, TYPE_PERMISSION, TYPE_TABLE, TYPE_SUB_FIELD)
                            + "，收到的是：" + targetType);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("targetType", targetType);
        resp.put("target", target);
        resp.put("problemCount", findings.stream().filter(Finding::problem).count());
        resp.put("findings", findings.stream().map(Finding::toMap).toList());
        resp.put("disclaimer", DISCLAIMER);
        return resp;
    }

    // ==================== 流程 ====================

    /**
     * 诊断流程：节点字段权限不生效、没人能审批
     */
    private void diagnoseProcess(String key, List<Finding> findings) {
        if (StrUtil.isBlank(key)) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "diagnose_not_working",
                    "诊断流程时必须提供 target（流程标识 key 或流程名称）");
        }
        BpmModelRespDTO model = findModel(key);
        if (model == null) {
            findings.add(Finding.problem("流程不存在",
                    "按「" + key + "」既找不到流程标识也找不到流程名称",
                    "先用 process_list 确认流程的准确标识"));
            return;
        }

        // 检查 1：部署过没有。没部署的流程发起不了，也不出现在发起列表里
        if (!Boolean.TRUE.equals(model.getDeployed())) {
            findings.add(Finding.problem("流程从未部署",
                    "流程「" + model.getName() + "」建好了但没有部署过",
                    "未部署的流程无法发起，也不出现在用户的发起列表里。"
                            + "用 process_create_and_deploy 重新部署一次"));
        }

        // 检查 2：流程资源有没有登记。没登记的话节点级权限「配了等于没配」
        ResourceDiagnosticRespDTO resource =
                systemDiagnosticApi.diagnoseResource("bpm:process:" + model.getKey());
        if (!Boolean.TRUE.equals(resource.getRegistered())) {
            findings.add(Finding.problem("流程资源未登记",
                    "sys_resource 里没有 bpm:process:" + model.getKey(),
                    "节点级字段权限的 contextId 会解析为 null，退回全局判定 —— "
                            + "表现就是「在流程设计器里给某个节点配的字段权限完全不起作用」。"
                            + "资源是部署时登记的，重新部署一次即可补上"));
        } else {
            appendGrantFindings(resource, findings);
        }

        // 检查 3：🔴 approveType —— 唯一会静默失效的一条
        String simpleJson = bpmModelApi.getModelSimpleJson(model.getId());
        if (StrUtil.isBlank(simpleJson)) {
            findings.add(Finding.info("无节点树",
                    "这个流程没有仿钉钉设计器的节点树",
                    "它可能是用 BPMN 设计器画的，本工具的节点检查不适用"));
            return;
        }
        checkApproveType(simpleJson, findings);
    }

    /**
     * 递归检查每个审批节点的 approveType
     */
    private void checkApproveType(String simpleJson, List<Finding> findings) {
        BpmProcessConstraintsRespDTO constraints = bpmModelApi.getProcessConstraints();
        Integer required = constraints.getRequiredApproveType();
        List<String> badNodes = new ArrayList<>();
        walkNodes(JSONUtil.parseObj(simpleJson), node -> {
            Integer type = node.getInt("type");
            // 11=审批人、13=办理人
            if (!Objects.equals(type, 11) && !Objects.equals(type, 13)) {
                return;
            }
            if (!Objects.equals(node.getInt("approveType"), required)) {
                badNodes.add(StrUtil.blankToDefault(node.getStr("name"), node.getStr("id"))
                        + "（approveType=" + node.getInt("approveType") + "）");
            }
        });
        if (badNodes.isEmpty()) {
            findings.add(Finding.ok("审批节点的 approveType 正确",
                    "所有审批/办理节点的 approveType 都是 " + required, null));
            return;
        }
        findings.add(Finding.problem("🔴 审批节点的 approveType 不对",
                "这些节点的 approveType 不等于 " + required + "：" + String.join("、", badNodes),
                "这是整套流程配置里唯一会【静默失效】的一条：approveType 不等于 " + required
                        + " 时，后端会把该节点的候选人、审批方式、字段权限、按钮、超时设置全部丢弃，"
                        + "而且部署照样成功、不报任何错。表现就是流程跑到这个节点没有任何人能审批、"
                        + "或者配的字段权限完全不起作用。"
                        + "修法：把这些节点的 approveType 改成 " + required + " 后重新部署"));
    }

    // ==================== 权限点 ====================

    private void diagnosePermission(String permission, List<Finding> findings) {
        if (StrUtil.isBlank(permission)) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "diagnose_not_working",
                    "诊断权限点时必须提供 target（权限串，如 infra:form-design:query）");
        }
        ResourceDiagnosticRespDTO resource = systemDiagnosticApi.diagnoseResource(permission);
        if (!Boolean.TRUE.equals(resource.getRegistered())) {
            findings.add(Finding.problem("权限点未登记为资源",
                    "sys_resource 里查不到「" + permission + "」",
                    "判定引擎查不到资源时直接返回「无权限」，所以在这个权限点上配的授权"
                            + "【一条都不会生效】，而授权记录本身看着是好好的。"
                            + "资源来自菜单写入、表单发布、流程部署、@PermissionDataset 扫描四个来源，"
                            + "确认对应的来源动作真的执行过"));
            return;
        }
        appendGrantFindings(resource, findings);
    }

    /**
     * 授权相关的共用检查
     */
    private void appendGrantFindings(ResourceDiagnosticRespDTO resource, List<Finding> findings) {
        // ⚠️ 记下起点：末尾的「看起来正常」只能基于【本方法新增的】结论来判断。
        // 扫全量的话，流程诊断里一个无关的问题（比如「没部署过」）
        // 就会把授权这块的正常结论吃掉，让人以为授权也有问题
        int begin = findings.size();

        // 同一权限串多行资源 —— 授权配在哪一行上会影响判定
        if (resource.getResourceCount() != null && resource.getResourceCount() > 1) {
            findings.add(Finding.warn("这个权限串对应多行资源",
                    "共 " + resource.getResourceCount() + " 行",
                    "同一权限串挂在多个菜单上是上游常见做法。要留意授权配在了哪一行上 —— "
                            + "历史上出现过「前端按钮显示了、点下去 403」正是因为两侧读的不是同一行"));
        }
        if (resource.getGrantCount() == null || resource.getGrantCount() == 0) {
            findings.add(Finding.problem("这个资源上没有任何授权",
                    "sys_grant 里查不到针对它的授权行",
                    "资源登记了但没授权，等于所有人都没权限。到「权限管理」里给对应角色授权"));
            return;
        }
        // 🔴 租户写错
        if (resource.getTenantMismatchGrantCount() != null
                && resource.getTenantMismatchGrantCount() > 0) {
            findings.add(Finding.problem("🔴 有授权的租户写错了",
                    resource.getTenantMismatchGrantCount() + " 条授权的 tenant_id 与角色的租户对不上",
                    "sys_grant.tenant_id 的正确来源是 system_role.tenant_id，而不是默认的 0。"
                            + "对不上的那些在判定时永远匹配不到，表现为「授权配了等于没配」。"
                            + "这类数据多半是直接写库造出来的 —— 从界面上操作不会出现"));
        }
        // 🔴 非法 fieldMode
        if (resource.getIllegalFieldModes() != null && !resource.getIllegalFieldModes().isEmpty()) {
            findings.add(Finding.problem("🔴 有非法的字段模式取值",
                    "出现了：" + String.join("、", resource.getIllegalFieldModes()),
                    "合法值只有 " + String.join(" / ", systemDiagnosticApi.getLegalFieldModes())
                            + "。传了别的（最常见是 VISIBLE）会让【整行配置被静默丢弃】，不报错。"
                            + "把这些值改成合法取值"));
        }
        if (findings.subList(begin, findings.size()).stream().noneMatch(Finding::problem)) {
            findings.add(Finding.ok("授权配置看起来正常",
                    "共 " + resource.getGrantCount() + " 条授权，租户与字段模式都没问题", null));
        }
    }

    // ==================== 数据表 ====================

    private void diagnoseTable(String tableName, List<Finding> findings) {
        if (StrUtil.isBlank(tableName)) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "diagnose_not_working",
                    "诊断行级规则时必须提供 target（物理表名）");
        }
        Set<String> datasetTables = systemDiagnosticApi.getNativeDatasetTables();
        if (datasetTables.contains(tableName.toLowerCase(Locale.ROOT))) {
            findings.add(Finding.ok("这张表已登记为数据集",
                    "行级权限规则会对它生效", null));
            return;
        }
        findings.add(Finding.problem("🔴 这张表没有登记为数据集",
                "「" + tableName + "」不在已登记的数据集表里（当前共 "
                        + datasetTables.size() + " 张：" + String.join("、", datasetTables) + "）",
                "行级权限规则【只对已登记的表生效】。给一张没登记的表配行级规则，"
                        + "规则一次都不会被调用，而且不报任何错 —— "
                        + "界面上规则还在，数据却是全量返回的。"
                        + "登记方式是在对应的 DO 上标 @PermissionDataset，"
                        + "标完要重启（登记发生在启动时的 Runner 里）"));
    }

    // ==================== 子表分隔符 ====================

    /**
     * 比对「契约 JSON 里的值」与「bpm 字节码里内联的值」
     *
     * 这一条是整个清单里最难自己发现的：现象是「子表字段配了权限却不生效」，
     * 而值分散在 infra 的契约 JSON 与 bpm 编译出来的 class 里。
     *
     * ⚠️ 域：只比这两处（①契约 JSON ↔ ⑤bpm），<b>不比</b> infra 的 Java 常量 ——
     * 那一侧由 SubFieldSeparatorSingleSourceTest 在构建期钉住，运行期再看没有新信息。
     *
     * 台账 #101(b) 之后 Java 侧只有一份常量、bpm 引用它，所以一次完整构建之后这里
     * 报不出不一致。它仍然保留：编译期常量会被内联（JLS 15.28），只重装 infra 的 jar
     * 而没重装 bpm 就会真漂移，而这种漂移单测结构上看不见 —— 运行期比对是唯一的出口。
     */
    private void diagnoseSubFieldSeparator(List<Finding> findings) {
        String contractValue = formDesignApi.getSubFieldSeparator();
        String bpmValue = bpmModelApi.getProcessConstraints().getSubFieldSeparator();
        if (Objects.equals(contractValue, bpmValue)) {
            findings.add(Finding.ok("子表字段分隔符两侧一致",
                    "控件契约与 bpm 都是「" + contractValue + "」", null));
            return;
        }
        findings.add(Finding.problem("🔴 子表字段分隔符两侧不一致",
                "控件契约是「" + contractValue + "」，bpm 是「" + bpmValue + "」",
                "Java 侧只有一份常量（FormResourceDescriptorDTO.SUB_FIELD_SEPARATOR，bpm 引用它），"
                        + "并且与契约 JSON 由单测钉住 —— 所以不一致几乎只有一个原因："
                        + "只重装了 infra 的 jar，bpm 的 class 里还是内联进去的旧值。"
                        + "全量 mvn install 再重启后端即可。"
                        + "对不上的表现是「子表字段配了权限却不生效」，"
                        + "或反过来把有权限的字段裁掉 —— 从现象几乎不可能反推到分隔符。"
                        + "若全量重装后仍不一致，才是真把值改了：改 structural.subFieldSeparator "
                        + "（规范副本在后端 resources/form-design/ 下，改完要 cp 到前端）"
                        + "与 FormResourceDescriptorDTO.SUB_FIELD_SEPARATOR，"
                        + "另有两条钉字面量的测试会红 —— 那是设计如此，不是误报"));
    }

    // ==================== 辅助 ====================

    /**
     * 一条诊断结论
     *
     * @param problem 是否是问题（而非「正常」或提示）
     */
    private record Finding(String title, String detail, String advice, String level, boolean problem) {

        static Finding problem(String title, String detail, String advice) {
            return new Finding(title, detail, advice, "PROBLEM", true);
        }

        static Finding warn(String title, String detail, String advice) {
            return new Finding(title, detail, advice, "WARN", false);
        }

        static Finding ok(String title, String detail, String advice) {
            return new Finding(title, detail, advice, "OK", false);
        }

        static Finding info(String title, String detail, String advice) {
            return new Finding(title, detail, advice, "INFO", false);
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("level", level);
            map.put("title", title);
            map.put("detail", detail);
            if (StrUtil.isNotBlank(advice)) {
                map.put("advice", advice);
            }
            return map;
        }
    }

    private BpmModelRespDTO findModel(String key) {
        List<BpmModelRespDTO> models = bpmModelApi.getModelList(null);
        return models.stream()
                .filter(item -> Objects.equals(item.getKey(), key)
                        || Objects.equals(item.getName(), key))
                .findFirst().orElse(null);
    }

    /**
     * 深度遍历节点树，含 conditionNodes 分支
     */
    private void walkNodes(JSONObject node, java.util.function.Consumer<JSONObject> visitor) {
        if (node == null || node.isEmpty()) {
            return;
        }
        visitor.accept(node);
        JSONArray conditionNodes = node.getJSONArray("conditionNodes");
        if (conditionNodes != null) {
            for (Object item : conditionNodes) {
                if (item instanceof JSONObject branch) {
                    walkNodes(branch, visitor);
                }
            }
        }
        walkNodes(node.getJSONObject("childNode"), visitor);
    }

    private String requireString(Map<String, Object> params, String key, String label) {
        Object value = params.get(key);
        if (value == null || StrUtil.isBlank(value.toString())) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "diagnose_not_working",
                    "缺少 " + key + "（" + label + "）");
        }
        return value.toString().trim();
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    /** 见 {@link AgentToolFunction#resultArrayKeys}：本工具全部返回分支里数组值的键（并集） */
    @Override
    public List<String> resultArrayKeys() {
        return List.of("findings");
    }

    @Override
    public String paramsSchema() {
        return """
                targetType（必填，字符串）：要诊断什么，取值：
                  process     诊断一条流程：是否部署、资源是否登记、审批节点 approveType 是否正确
                  permission  诊断一个权限点：资源是否登记、授权租户是否写错、字段模式是否合法
                  table       诊断行级规则：这张表有没有登记为数据集
                  subField    比对子表字段分隔符的两份副本是否一致（不需要 target）
                target（除 subField 外必填，字符串）：
                  targetType=process    时传流程标识 key 或流程名称
                  targetType=permission 时传权限串，如 bpm:process:oa_leave
                  targetType=table      时传物理表名，如 biz_purchase_apply""";
    }

}
