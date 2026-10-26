package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.agent.dal.dataobject.tooldef.AgentToolDefDO;
import cn.enlev.ainb.module.agent.enums.WaitTypeEnum;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.GraphReachability;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.ToolNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ChangeManifest.ApprovalItem;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ChangeManifest.ManifestStats;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ChangeManifest.UnresolvedItem;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ChangeManifest.WaitItem;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ChangeManifest.WorkflowRef;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolRegistry;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.ToolKeySuggester;
import cn.enlev.ainb.module.agent.service.tooldef.AgentToolDefService;
import cn.enlev.ainb.module.agent.service.workflow.AgentBpmApprovalService;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DSL → 变更清单（方案 §3-L2 的主入口）
 *
 * <h3>职责边界：本类只做「与具体工具无关」的那一半</h3>
 * 遍历节点、认工具、取风险、算可达性、汇总计数、拼 headline —— 这些对所有工具都一样，写一遍。
 * 「这个工具的参数意味着什么」交给 {@link ToolChangeExtractor}，一个工具一个实现。
 * 于是新增一个写工具的最小成本是「加一个抽取器 bean」；忘了加也不会静默少一块，
 * 而是退回 {@link GenericToolChangeExtractor} 并在 {@code stats.genericTools} 上留下计数。
 *
 * <p>「平台将会做什么」是第三方 —— {@link ChangeManifestDryRunner}，遍历完后统一调一次。
 * 三方各填 {@link ChangeItem} 的一部分，谁填哪些见它的类注释；越界的代价是第二份口径。
 *
 * <p>第四方 {@link ManifestScoper} 不填 {@link ChangeItem}，它只决定<b>哪几项给人看</b>
 * （审批人只该看「同意之后会发生什么」）。它排在干跑之后、汇总之前，两个位置都不能挪，
 * 理由分别写在它的类注释与 {@link #finish} 的注释里。
 *
 * <p>{@link UntrustedInputTracer} 也不填 {@link ChangeItem}，它填的是另一张正交的表
 * {@code untrustedInputs[]}：changes 说「会改什么」，它说「改的时候喂进去的值有多少是<b>别人能写的</b>」。
 * 它排在裁剪之前（否则裁掉的条目会被重新加回来），理由写在 {@link #build} 里。
 *
 * <h3>🔴 工具解析顺序必须与执行路径逐字同口径</h3>
 * {@code AgentToolExecutor} 与 {@code AgentSecurityGateway} 都是「先查 {@link AgentToolRegistry}，
 * 查不到才回落 {@code agent_tool_def}」。本类同样 registry-first（S1 的名称目录也是这个顺序）。
 * 若顺序不同，清单上显示的是自定义工具的展示名与风险、真正执行的却是同名内置工具 ——
 * 这类分叉从界面上完全看不出来，而它恰好发生在「用户据此点同意」的那一屏。
 *
 * <h3>只读工具不产生变更项，但要计数</h3>
 * {@code stats.readToolCount} 存在的理由是「证明扫过了」：清单说「本次不产生任何写操作」时，
 * 用户需要能区分「真的没有写操作」与「有 5 个工具但清单没认出来」。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class WorkflowChangeManifestBuilder {

    /** 人工介入节点的 type 前缀；与 {@code DslToStateGraphCompiler} 登记 interruptBefore 的判定同源 */
    private static final String HITL_PREFIX = "hitl.";
    /** 子流程节点 type；与 {@code DslToStateGraphCompiler} 的子图分派同源 */
    private static final String TYPE_SUB_WORKFLOW = "sub_workflow";
    /** 子流程节点里存子流程 code 的 config 键（键名叫 workflowId、装的其实是 code） */
    private static final String CONFIG_SUB_WORKFLOW_ID = "workflowId";
    /** 等待节点 type；只把 wait(event) 收进 waits[]，wait(duration) 不产生任何清单项 */
    private static final String TYPE_WAIT = "wait";
    /** wait 节点里声明外部唤醒方的 config 键（S6 / 裁定⑬ / V14 同源） */
    private static final String CONFIG_EXTERNAL_WAKEUP = "externalWakeup";

    @Resource
    private AgentToolRegistry toolRegistry;
    @Resource
    private ToolChangeExtractorRegistry extractorRegistry;
    @Resource
    private GenericToolChangeExtractor genericExtractor;
    @Resource
    private AgentToolDefService toolDefService;
    /**
     * 只用于给抽取器提供机器码→中文名的查表（见 {@link ManifestLabels}）。
     * 走 infra 的 api 层，与 S1 名称目录同一个出口，故两处对「select 叫什么」的答案必然一致。
     */
    @Resource
    private FormDesignApi formDesignApi;
    /**
     * 只读干跑：给每一项变更补上「平台接不接受这么改」。
     * 全清单<b>唯一</b>会打 infra/bpm 只读接口的地方，故耗时预算与 fail-soft 都收在它那边。
     */
    @Resource
    private ChangeManifestDryRunner dryRunner;

    public ChangeManifest build(WorkflowDefDTO def) {
        return build(def, null, null);
    }

    public ChangeManifest build(WorkflowDefDTO def, Integer version) {
        return build(def, version, null);
    }

    /**
     * 生成变更清单
     *
     * <p>🔴 <b>本方法不抛业务异常</b>。清单是给人看的诊断物，它自己绝不能成为新的失败点 ——
     * DSL 有问题应当由 {@code WorkflowDslValidator} 报错，清单照出（能认多少认多少）。
     *
     * <p>⚠️ 含干跑，故会打几次 infra 的只读接口（方案 §6.1 认定发布是低频动作、可接受）。
     * infra 不可用时不会抛：那些项落成 {@code DryRunResult.Outcome.NOT_PROBED}，
     * 并在 {@code stats.dryRunNotProbed} 上留下计数。
     *
     * <p>🔴 顺序不能换：<b>遍历 → 算 downstreamKinds → 干跑 → 溯源 → 裁剪 → 汇总</b>。
     * 干跑要在裁剪之前（理由见 {@link ManifestScoper} 类注释：跨项的「本流程自己会建出哪些表」
     * 需要看到全图，否则最标准的 {@code 建表 → 审批 → 建表单} 会被冤枉成「主表不存在」）；
     * 溯源同样要在裁剪之前（裁剪器要按 {@code sinkNodeId} 剪它并记账）；
     * 汇总要在裁剪之后（{@link #finish} 从<b>留下来的</b> {@code changes} 派生 stats 与 headline，
     * 于是「裁了但没重算」这种不一致在结构上就写不出来）。
     *
     * @param def        DSL；为 null 时返回一份「无任何变更」的空清单
     * @param version    流程版本号；由未落库的草稿派生时传 null
     * @param fromNodeId 起算节点，通常是 {@code hitl.*} 节点；传了就只展示「从这一步往后」，
     *                   并在 {@code manifest.scope} 里记下裁掉了多少。空白表示不裁剪
     */
    public ChangeManifest build(WorkflowDefDTO def, Integer version, String fromNodeId) {
        ChangeManifest manifest = new ChangeManifest();
        manifest.setGeneratedAt(LocalDateTime.now());
        manifest.setWorkflow(buildWorkflowRef(def, version));
        if (def == null) {
            finish(manifest, 0);
            return manifest;
        }

        GraphReachability.Graph graph = GraphReachability.of(def);
        Map<String, NodeDTO> nodeById = indexNodes(def);
        ManifestContext ctx = new ManifestContext(def, graph, nodeById, new ManifestLabels(formDesignApi));
        // 懒加载：只有真遇到注册表里没有的工具键时才查一次库；纯内置工具的 DSL 一次都不查
        Map<String, AgentToolDefDO> customTools = null;

        // 只读工具不进 changes，但「扫过了几个」要能被裁剪，故记 id 而不是记个数
        List<String> readToolNodeIds = new ArrayList<>();
        for (NodeDTO node : safeNodes(def)) {
            String type = node.getType();
            if (ToolNodeExecutor.TYPE.equals(type)) {
                String toolId = node.configStr("toolId");
                if (StrUtil.isBlank(toolId)) {
                    manifest.getUnresolved().add(unresolved(node, UnresolvedItem.TYPE_TOOL_MISSING, null,
                            "本节点没有指定 toolId，无法判断它会做什么；请在节点配置里选择要调用的工具"));
                    continue;
                }
                customTools = ensureCustomToolsLoaded(toolId, customTools);
                ToolMeta meta = resolveTool(toolId, customTools);
                if (meta == null) {
                    manifest.getUnresolved().add(unresolved(node, UnresolvedItem.TYPE_TOOL_UNKNOWN, toolId,
                            unknownToolReason(toolId, customTools)));
                    continue;
                }
                if (meta.risk() == RiskLevel.READ_ONLY) {
                    // 只读工具不改平台，不进清单；但计入 readToolCount，见类注释
                    readToolNodeIds.add(node.getId());
                    continue;
                }
                ChangeItem item = extract(node, ctx, toolId);
                fillCommonFields(item, node, toolId, meta);
                manifest.getChanges().add(item);
            } else if (type != null && type.startsWith(HITL_PREFIX)) {
                manifest.getApprovals().add(buildApproval(node, type));
            } else if (TYPE_SUB_WORKFLOW.equals(type)) {
                // 子流程内部的工具调用不在本份 DSL 里，展开要递归取子 def（并防环），S2 不做。
                // 🔴 但必须显式说出来：子流程里建一张表，与顶层建一张表对用户是同一件事，
                // 静默跳过就等于清单在「将会做什么」上撒谎。
                String code = node.configStr(CONFIG_SUB_WORKFLOW_ID);
                manifest.getUnresolved().add(unresolved(node, UnresolvedItem.TYPE_SUB_WORKFLOW, null,
                        StrUtil.isBlank(code)
                                ? "本节点引用子流程但未填 workflowId，其内部变更无法展开；请补上子流程 code"
                                : "本节点引用子流程「" + code + "」，其内部的变更未展开；"
                                + "如需确认全部影响面，请单独打开该子流程查看它的变更清单"));
            } else if (TYPE_WAIT.equals(type)
                    && WaitTypeEnum.parse(node.configStr("waitType")) == WaitTypeEnum.EVENT) {
                // S6 / 裁定⑬：wait(event) 进 waits[]，wait(duration) 不产生清单项（定时等待没有外部唤醒方）。
                // 判取值走 WaitTypeEnum.parse（会 trim）而不是裸 equals，与编译期 validateWaitConfig 同口径：
                // 这里少认一次，清单就少一条 waits[]，而「清单说没有外部唤醒方」与「运行期真没人唤醒」在界面上长得一样。
                manifest.getWaits().add(buildWait(node));
            }
            // 其余类型（llm / condition.* / parallel / iteration / agent_loop / wait / knowledge_retrieval / script）
            // 不改平台数据，不产生变更项。iteration/parallel 的 body 是<b>顶层节点 id 列表</b>，
            // 那些节点本轮遍历已经覆盖，不需要下钻。
        }

        fillDownstreamKinds(manifest, graph);
        // 🔴 干跑是构建的一部分，不是调用方的可选项。
        //    做成「想要就再调一次 dryRunner.enrich」的话，忘调的调用方会拿到一份
        //    dryRun 全空的清单 —— 而全空与「全部通过」在界面上长得一样。
        dryRunner.enrich(manifest, ctx);
        // 🔴 溯源按整份 DSL 跑、再裁给人看，与干跑同一条纪律：只溯源子图会少报 ——
        //    上游的 tool / knowledge_retrieval 节点是「新鲜源」的播种点，不在视野里污点就断流。
        //    排在裁剪之前是硬性的：裁剪器要剪这个列表并记 hiddenUntrusted，
        //    顺序倒了就会把剪掉的条目又加回来，那个数成了假话。
        //    排在干跑之后不是硬性的（它只读 ctx，与干跑无数据依赖），
        //    但这样「干跑 → 溯源 → 裁剪 → 汇总」连成一片，与本方法的 javadoc 顺着读就对得上。
        // 🔴 刻意不包 try/catch：兜住就等于把一次崩溃翻译成「没有外部输入流进任何入参」，
        //    比整份清单生成失败更坏 —— 前者用户看得见，后者用户点了同意。
        //    与干跑的 fail-soft 不矛盾：那边兜的是「infra 不通」这类预期内的外部故障，
        //    这边能兜到的只可能是本模块的 bug，而 bug 不该被翻译成一句让人放心的话。
        // 用 addAll 而不是 setUntrustedInputs：字段本就初始化为 ArrayList，
        // 而 trace 的早退分支返回的是不可变的 List.of()，setter 会把那颗雷留给裁剪器的 removeIf
        manifest.getUntrustedInputs().addAll(UntrustedInputTracer.trace(ctx));
        manifest.setScope(ManifestScoper.scope(manifest, ctx, readToolNodeIds, fromNodeId));
        finish(manifest, readToolNodeIds.size());
        return manifest;
    }

    // ==================== 节点遍历的公共物 ====================

    private WorkflowRef buildWorkflowRef(WorkflowDefDTO def, Integer version) {
        WorkflowRef ref = new WorkflowRef();
        ref.setVersion(version);
        if (def == null) {
            return ref;
        }
        ref.setCode(def.getId());
        ref.setName(StrUtil.blankToDefault(def.getName(), def.getId()));
        ref.setTriggerType(def.getTrigger() == null ? null : def.getTrigger().getType());
        return ref;
    }

    private List<NodeDTO> safeNodes(WorkflowDefDTO def) {
        return def.getNodes() == null ? List.of() : def.getNodes();
    }

    private Map<String, NodeDTO> indexNodes(WorkflowDefDTO def) {
        Map<String, NodeDTO> nodeById = new HashMap<>();
        for (NodeDTO node : safeNodes(def)) {
            if (node != null && StrUtil.isNotBlank(node.getId())) {
                nodeById.putIfAbsent(node.getId(), node);
            }
        }
        return Map.copyOf(nodeById);
    }

    // ==================== 工具解析（registry-first） ====================

    /**
     * 工具的展示口径：键、中文名、风险等级
     *
     * <p>只带清单需要的三样。刻意不直接暴露 {@code ToolDefinition} 或 {@code AgentToolDefDO}：
     * 内置工具的展示名要按租户现算、风险级却只认声明值，自定义工具的风险要从字符串解析，
     * 三者形状不同，归一成一个小记录后，下游就不必知道这个工具是哪来的、也不会有人再顺手读错。
     */
    private record ToolMeta(String tool, String displayName, RiskLevel risk) {
    }

    /**
     * 认工具：内置注册表优先，回落到当前租户启用中的自定义工具；都认不出返回 null
     *
     * <p>🔴 内置工具的风险级只读<b>声明值</b> {@code getDeclaredRisk()}，不读走租户生效值的
     * {@code getRisk()}。清单是<b>执行前</b>对「将会做什么」的陈述，也是 BPM 审批的依据；
     * 而 {@code agent_tool_override} 一行数据就能把 IRREVERSIBLE 压成 READ_ONLY
     * （{@code ToolOverride} 的 javadoc 明写「risk 允许低于声明值，产品决策」）。
     * 若按生效值判定，{@link #build} 里 {@code meta.risk() == READ_ONLY} 那一支会把整个 tool 节点
     * <b>静默剔出 changes[]</b> —— 用户批准了一份对建表只字不提的清单，执行期照建不误。
     * 更糟的是它与本站自己的校验器口径相反：{@code WorkflowDslValidator} 的 V11 走
     * {@code ToolResolver#declaredRiskOf}（声明值），于是同一份 DSL 会出现
     * 「校验器说不可逆、必须有闸口」而「清单说这一节点只读、无事发生」的矛盾，且界面上看不出原因。
     *
     * @param customTools 自定义工具表，可为 null（表示尚未加载，调用方负责先过
     *                    {@link #ensureCustomToolsLoaded}）
     */
    private ToolMeta resolveTool(String toolId, Map<String, AgentToolDefDO> customTools) {
        AgentToolRegistry.ToolDefinition builtin = toolRegistry.get(toolId);
        if (builtin != null) {
            // declaredRisk 理论上不会为 null（注解必填、自定义那侧走 parseOrHigh）。真为 null 时按 HIGH 兜 ——
            // 与 RiskLevel.parseOrHigh 的回落方向一致：宁可多说风险，不能把写操作标成只读
            RiskLevel risk = builtin.getDeclaredRisk() == null ? RiskLevel.HIGH : builtin.getDeclaredRisk();
            return new ToolMeta(builtin.getName(), builtin.getDisplayName(), risk);
        }
        AgentToolDefDO custom = customTools == null ? null : customTools.get(toolId);
        if (custom != null) {
            return new ToolMeta(custom.getToolKey(),
                    StrUtil.blankToDefault(custom.getDisplayName(), custom.getToolKey()),
                    RiskLevel.parseOrHigh(custom.getRiskLevel()));
        }
        return null;
    }

    /**
     * 只在「注册表里查不到这个键」时才去查一次自定义工具表，之后复用同一份
     *
     * <p>懒加载不是为了省这一次查询本身，而是为了让<b>纯内置工具的 DSL 完全不碰库</b>：
     * 清单在设计器面板、草稿卡、HITL 确认卡三处都会生成，其中前两处是高频打开的页面。
     */
    private Map<String, AgentToolDefDO> ensureCustomToolsLoaded(String toolId,
                                                               Map<String, AgentToolDefDO> loaded) {
        if (loaded != null || toolRegistry.get(toolId) != null) {
            return loaded;
        }
        return loadCustomTools();
    }

    /**
     * 当前租户启用中的自定义工具，按 toolKey 索引
     *
     * <p>只列启用行是刻意的：停用行调用会被网关拒，把它当已知工具会让清单报一项根本不会发生的变更。
     *
     * <p>🔴 与 S1 名称目录<b>不再</b>同源（D4-27 起），而且这是对的、不是漂移：
     * 目录用 {@code getToolDefListIncludingDisabled()}（含停用行），本处仍用 {@code getEnabledToolDefList()}。
     * 两处问的是不同的问题 —— 目录问「这个键<b>叫什么名字</b>」（名字与启用状态无关，
     * 少了它存量 DSL 里那个键就退化成裸机器键），本处问「这个键<b>会不会真的被调到</b>」
     * （停用行必被网关拒，报成一项变更就是报一件不会发生的事）。
     * 于是「同一个停用工具，画布上显示中文名、清单里说认不出」这个看起来矛盾的组合恰恰是正确输出：
     * 名字翻得出来，但那一步跑不通。
     * 别为了「两处一致」把任意一侧改成另一侧的口径 —— 那会分别造成裸机器键和虚假变更项。
     */
    private Map<String, AgentToolDefDO> loadCustomTools() {
        Map<String, AgentToolDefDO> byKey = new LinkedHashMap<>();
        for (AgentToolDefDO row : toolDefService.getEnabledToolDefList()) {
            if (row != null && StrUtil.isNotBlank(row.getToolKey())) {
                byKey.putIfAbsent(row.getToolKey(), row);
            }
        }
        return byKey;
    }

    /**
     * 「工具认不出」的中文原因，带相近候选
     *
     * <p>候选的挑选口径收在 {@link ToolKeySuggester}：校验器的 V1 也要给同一句提示，
     * 两处若各写一遍阈值，就会「清单说相近的有 A、体检说相近的有 B」。
     */
    private String unknownToolReason(String toolId, Map<String, AgentToolDefDO> customTools) {
        List<String> candidates = similarToolIds(toolId, customTools);
        String hint = candidates.isEmpty() ? "" : "（相近的有 " + String.join("、", candidates) + "）";
        return "工具「" + toolId + "」既不在内置工具注册表里，也不是当前租户启用中的自定义工具" + hint
                + "；请改正工具键，或先到「工具管理」登记它";
    }

    private List<String> similarToolIds(String toolId, Map<String, AgentToolDefDO> customTools) {
        Set<String> known = new LinkedHashSet<>();
        for (AgentToolRegistry.ToolDefinition def : toolRegistry.list()) {
            known.add(def.getName());
        }
        if (customTools != null) {
            known.addAll(customTools.keySet());
        }
        return ToolKeySuggester.suggest(toolId, known);
    }

    // ==================== 变更项 ====================

    /**
     * 取抽取器产出；没有专用抽取器、或抽取器违约时退回兜底
     *
     * <p>两处违约都<b>不</b>让这项变更消失：返回 null → 退回兜底并 warn；
     * 忘了设 kind → 按 {@link ChangeKind#GENERIC_TOOL} 处理并 warn
     * （不设就会在按 kind 计数时以 null 键撞进 {@link EnumMap}，把「清单少一块」升级成「清单接口 500」）。
     */
    private ChangeItem extract(NodeDTO node, ManifestContext ctx, String toolId) {
        ToolChangeExtractor extractor = extractorRegistry.get(toolId);
        ChangeItem item = extractor == null ? null : extractor.extract(node, ctx);
        if (item == null) {
            if (extractor != null) {
                // 契约是「不许返回 null」（见 ToolChangeExtractor 类注释）。
                // 静默跳过就是「清单少一块、但运行期照做」，故退回兜底并留痕。
                log.warn("[extract][工具({}) 的变更抽取器 {} 返回 null，退回兜底解读]",
                        toolId, extractor.getClass().getSimpleName());
            }
            item = genericExtractor.extract(node, ctx);
        }
        if (item.getKind() == null) {
            log.warn("[extract][工具({}) 的变更抽取器 {} 没有设置 kind，按 GENERIC_TOOL 处理]",
                    toolId, extractor.getClass().getSimpleName());
            item.setKind(ChangeKind.GENERIC_TOOL);
        }
        return item;
    }

    /**
     * 覆盖式填写与具体工具无关的字段
     *
     * <p>🔴 是<b>覆盖</b>不是「为空才补」：这些字段的唯一权威是工具注册表的生效值
     * （含租户在线维护的覆盖行）。允许抽取器自带一份，就会出现「同一个工具在名称目录里标中风险、
     * 在变更清单里标低风险」这种界面上查不出原因的矛盾。详见 {@link ChangeItem} 类注释。
     */
    private void fillCommonFields(ChangeItem item, NodeDTO node, String toolId, ToolMeta meta) {
        item.setNodeId(node.getId());
        item.setNodeName(StrUtil.blankToDefault(node.getName(), node.getId()));
        item.setTool(toolId);
        item.setToolDisplayName(meta.displayName());
        item.setRiskLevel(meta.risk().getLevel());
        item.setRiskDescription(meta.risk().getDescription());
        // 可逆性由风险等级推导，不按 kind 各判一套，理由见 ChangeItem#reversible
        item.setReversible(meta.risk() != RiskLevel.IRREVERSIBLE);
        if (StrUtil.isBlank(item.getSummary())) {
            item.setSummary(defaultSummary(item, meta));
        }
    }

    /**
     * 抽取器没写 summary 时的回落：一句话说清「谁、对什么、做了什么」
     */
    private String defaultSummary(ChangeItem item, ToolMeta meta) {
        StringBuilder sb = new StringBuilder(item.getKind() == null ? "调用工具" : item.getKind().getLabel());
        sb.append("（").append(meta.displayName()).append("）");
        Object first = item.getTarget().values().stream().findFirst().orElse(null);
        if (first != null && StrUtil.isNotBlank(String.valueOf(first))) {
            sb.append("：").append(first);
        }
        if (item.getKind() == ChangeKind.GENERIC_TOOL) {
            sb.append("；本工具暂无专用解读，参数见详情");
        }
        return sb.toString();
    }

    // ==================== 人工介入点 ====================

    private ApprovalItem buildApproval(NodeDTO node, String type) {
        ApprovalItem approval = new ApprovalItem();
        approval.setNodeId(node.getId());
        approval.setNodeName(StrUtil.blankToDefault(node.getName(), node.getId()));
        approval.setType(type.substring(HITL_PREFIX.length()));
        if (type.equals(HITL_PREFIX + "bpm_approval")) {
            // 🔴 复用 AgentBpmApprovalService.resolveConfig 而不是自己再解一遍 config：
            // 那个方法存在的理由就是「让发起审批与校验共用同一处口径，杜绝两处各解析一遍产生分歧」，
            // 清单是第三个消费者，同样不该另起一份。
            AgentBpmApprovalService.BpmApprovalConfig config = AgentBpmApprovalService.resolveConfig(node);
            approval.setBpmProcessDefKey(config.processDefKey());
            if (config.formVars() != null) {
                // 只给变量名不给值：值可能是上一步写库返回的主键或用户填的敏感内容，
                // 而这份清单会被冻结进 BPM 流程变量长期留库
                approval.getFormVarsKeys().addAll(config.formVars().keySet());
            }
        }
        return approval;
    }

    /**
     * 一个 wait(event) 节点进 waits[]（S6 / 裁定⑬）。
     *
     * <p>字段直取 {@code externalWakeup} 声明原文：作者填什么带什么，不替他编。
     * {@code declared=false} 表示「有等待点但没声明唤醒方」——V14 对无头流程会要求补，
     * 渲染层据此显示一句提醒而不是一行空白。
     */
    private WaitItem buildWait(NodeDTO node) {
        WaitItem wait = new WaitItem();
        wait.setNodeId(node.getId());
        wait.setNodeName(StrUtil.blankToDefault(node.getName(), node.getId()));
        Map<String, Object> wakeup = node.configMap(CONFIG_EXTERNAL_WAKEUP);
        if (wakeup == null) {
            wait.setDeclared(false);
            return wait;
        }
        wait.setDeclared(true);
        wait.setOwner(strOf(wakeup.get("owner")));
        wait.setEndpoint(strOf(wakeup.get("endpoint")));
        wait.setNote(strOf(wakeup.get("note")));
        return wait;
    }

    /** config 值转字符串：空白归一成 null，数字/布尔走 String.valueOf（与 NodeDTO.configStr 同口径） */
    private String strOf(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * 给每个人工介入点算「他同意之后会发生什么」
     *
     * <p>用 {@link GraphReachability#reachableFrom(String)}（S2-b 收敛出的唯一一份可达性判定，
     * 与编译器同口径：条件边只看 cases/default，不看多余的 {@code to}）。
     * 这是该类在校验器之外的第一个消费者。
     *
     * <p>放在遍历之后单独一趟：downstreamKinds 要的是<b>全部</b>变更项，
     * 而 hitl 节点在 DSL 里通常排在其下游工具之前。
     */
    private void fillDownstreamKinds(ChangeManifest manifest, GraphReachability.Graph graph) {
        if (manifest.getApprovals().isEmpty() || manifest.getChanges().isEmpty()) {
            // 变更项为空时 willSee 必为 false，仍要显式写一遍 —— 「确认点后面什么都没有」
            // 是一种编排错误，渲染层靠 willSee 提示，不能靠前端自己算
            for (ApprovalItem approval : manifest.getApprovals()) {
                approval.setWillSee(false);
            }
            return;
        }
        for (ApprovalItem approval : manifest.getApprovals()) {
            Set<String> reachable = graph.reachableFrom(approval.getNodeId());
            Set<ChangeKind> kinds = new LinkedHashSet<>();
            for (ChangeItem change : manifest.getChanges()) {
                if (reachable.contains(change.getNodeId()) && change.getKind() != null) {
                    kinds.add(change.getKind());
                }
            }
            // 按 ChangeKind 声明序输出，保证同一份 DSL 每次生成的清单逐字节相同
            List<String> names = new ArrayList<>();
            for (ChangeKind kind : ChangeKind.values()) {
                if (kinds.contains(kind)) {
                    names.add(kind.name());
                }
            }
            approval.setDownstreamKinds(names);
            approval.setWillSee(!names.isEmpty());
        }
    }

    // ==================== 汇总 ====================

    /**
     * 从<b>最终留下来的</b> {@code changes} 一次性派生 stats、headline 与总风险
     *
     * <p>🔴 三者必须同源：headline 是审批人唯一必读的那行，stats 是前端徽标的数据源，
     * 各数一遍就迟早对不上（「标题说 1 张表、徽标显示 2」这种矛盾查不出原因）。
     *
     * <p>🔴 「最终留下来的」这五个字是 S2-d3d 加进来的。原本按 kind 的计数与最高风险
     * 是在遍历节点时累加的，而裁剪发生在遍历之后 —— 于是裁过的清单会出现
     * 「headline 说 3 张表、changes 里只有 1 项」。改成全部从这里现算之后，
     * 那种不一致在结构上就写不出来了：谁裁了 {@code changes}，谁就自动得到了对的汇总。
     * 同理，干跑的两个计数也在这里数（原来由 {@code ChangeManifestDryRunner#tally}
     * 边探边加，探的是全图、显示的是子图，同样会对不上）。
     *
     * @param readToolCount 子图内被跳过的只读工具节点数。唯一不能从 {@code changes} 派生的量 ——
     *                      只读工具压根不进 {@code changes}，故由遍历阶段记 id、裁剪阶段一起裁
     */
    private void finish(ChangeManifest manifest, int readToolCount) {
        List<ChangeItem> changes = manifest.getChanges();
        Map<ChangeKind, Integer> countByKind = new EnumMap<>(ChangeKind.class);
        RiskLevel topRisk = RiskLevel.READ_ONLY;
        int rejected = 0;
        int notProbed = 0;
        for (ChangeItem item : changes) {
            // kind 恒非 null：extract 已经把「抽取器忘了设 kind」兜成 GENERIC_TOOL 了，
            // 那里是这条不变式的唯一强制点（理由见 extract 的注释），这里不再判第二遍
            countByKind.merge(item.getKind(), 1, Integer::sum);
            // riskLevel 是 fillCommonFields 从工具注册表的生效值写进去的，故这里 parse 回来
            // 不是第二份口径；parseOrHigh 认不出时回落 HIGH，方向与「宁可多说风险」一致
            topRisk = RiskLevel.higher(topRisk, RiskLevel.parseOrHigh(item.getRiskLevel()));
            DryRunResult dryRun = item.getDryRun();
            if (dryRun == null) {
                continue;
            }
            if (dryRun.getOutcome() == DryRunResult.Outcome.REJECTED) {
                rejected++;
            } else if (dryRun.getOutcome() == DryRunResult.Outcome.NOT_PROBED) {
                notProbed++;
            }
            // PASSED 不计数：writeToolCount - rejected - notProbed 就是它，
            // 再存一份只会多一处能对不上的数
        }

        ManifestStats stats = manifest.getStats();
        for (Map.Entry<ChangeKind, Integer> entry : countByKind.entrySet()) {
            applyCount(stats, entry.getKey(), entry.getValue());
        }
        stats.setPermissions(countPermissions(changes));
        stats.setIrreversibleCount(countIrreversible(changes));
        stats.setWriteToolCount(changes.size());
        stats.setReadToolCount(readToolCount);
        stats.setDryRunRejected(rejected);
        stats.setDryRunNotProbed(notProbed);

        manifest.setHeadline(buildHeadline(countByKind, stats));
        manifest.setRiskLevel(topRisk.getLevel());
        manifest.setRiskDescription(topRisk.getDescription());
        manifest.setRiskNote(buildRiskNote(topRisk, manifest.getUnresolved().size()));
    }

    private void applyCount(ManifestStats stats, ChangeKind kind, int count) {
        switch (kind) {
            case TABLE_CREATE -> stats.setTables(count);
            case TABLE_ALTER -> stats.setAlteredTables(count);
            case TABLE_REGISTER -> stats.setRegisteredTables(count);
            case FORM_CREATE -> stats.setForms(count);
            case FORM_UPDATE -> stats.setUpdatedForms(count);
            case FORM_PUBLISH -> stats.setMenus(count);
            case PROCESS_DEPLOY -> stats.setProcesses(count);
            case DATA_WRITE -> stats.setDataWrites(count);
            case DATA_INTERFACE -> stats.setDataInterfaces(count);
            case GENERIC_TOOL -> stats.setGenericTools(count);
        }
    }

    /**
     * 按钮权限串总数 = 各 FORM_PUBLISH 项 {@code detail.permissions} 的长度之和
     *
     * <p>方案样例把它写进 headline（「1 个菜单（含 7 个按钮权限）」），因为「发布表单」
     * 这四个字完全没说出它顺手建了 7 个权限点、还只授给了超管 —— 那是用户最常踩的一脚。
     */
    private int countPermissions(List<ChangeItem> changes) {
        int total = 0;
        for (ChangeItem change : changes) {
            if (change.getKind() != ChangeKind.FORM_PUBLISH) {
                continue;
            }
            Object permissions = change.getDetail().get("permissions");
            if (permissions instanceof Collection<?> collection) {
                total += collection.size();
            }
        }
        return total;
    }

    private int countIrreversible(List<ChangeItem> changes) {
        int total = 0;
        for (ChangeItem change : changes) {
            if (RiskLevel.IRREVERSIBLE.getLevel().equals(change.getRiskLevel())) {
                total++;
            }
        }
        return total;
    }

    private String buildHeadline(Map<ChangeKind, Integer> countByKind, ManifestStats stats) {
        if (countByKind.isEmpty()) {
            return "本次不产生任何写操作";
        }
        List<String> parts = new ArrayList<>();
        // 按 ChangeKind 声明序拼，不按 changes 的出现序：同一份 DSL 无论节点怎么排，headline 都稳定
        for (ChangeKind kind : ChangeKind.values()) {
            Integer count = countByKind.get(kind);
            if (count == null || count == 0) {
                continue;
            }
            String part = kind.headline(count);
            if (kind == ChangeKind.FORM_PUBLISH && stats.getPermissions() > 0) {
                part += "（含 " + stats.getPermissions() + " 个按钮权限）";
            }
            parts.add(part);
        }
        return "将" + String.join("、", parts);
    }

    private String buildRiskNote(RiskLevel topRisk, int unresolvedCount) {
        String note = switch (topRisk) {
            case IRREVERSIBLE -> "含不可逆操作：执行后无法自动回滚（MySQL 的 DDL 没有事务）";
            case HIGH -> "含高风险操作：影响面大或涉及多步，请逐项确认";
            case MEDIUM -> "含中风险操作：未显式给出的配置项可能被静默清空";
            case LOW -> "均为可逆的写操作，改错了可以改回来";
            case READ_ONLY -> "本次不产生任何写操作";
        };
        if (unresolvedCount > 0) {
            note += "；另有 " + unresolvedCount + " 处说不清的部分，请务必查看";
        }
        return note;
    }

    private UnresolvedItem unresolved(NodeDTO node, String type, String tool, String reason) {
        UnresolvedItem item = new UnresolvedItem();
        item.setNodeId(node.getId());
        item.setNodeName(StrUtil.blankToDefault(node.getName(), node.getId()));
        item.setType(type);
        item.setTool(tool);
        item.setReason(reason);
        return item;
    }

}
