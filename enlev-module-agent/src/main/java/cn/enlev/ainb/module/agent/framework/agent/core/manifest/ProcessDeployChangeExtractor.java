package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.tool.impl.process.ProcessCreateAndDeployTool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@code process_create_and_deploy} → {@link ChangeKind#PROCESS_DEPLOY}
 *
 * <h3>措辞一律用「部署」，不说「新建」也不说「更新」</h3>
 * 工具自己的契约写着：{@code key} 命中已有流程 = <b>覆盖更新那条流程并发新版本</b>，
 * 用新 key 才是新建（见 {@link ProcessCreateAndDeployTool#paramsSchema()} 对 {@code key} 的说明）。
 * 分辨这两者要查 {@code BpmModelApi#getModelList}，属于「得查库才知道」的事 ——
 * 抽取器只报 DSL 里写了什么，故这里只说「部署审批流 X（标识 Y）」，把 key 摆出来让人自己认。
 * 新建/覆盖的判定与「覆盖会让在途实例怎样」留给 S2-d 的干跑器（只读查询即可给出）。
 *
 * <h3>🔴 {@code simpleModel} 一个字都不往清单里搬</h3>
 * 两个理由：
 * <ol>
 *   <li>它是整棵审批节点树，几十上百行 JSON。清单要能被冻结进 BPM 流程变量，搬进来就是几十 KB</li>
 *   <li>它的<b>合法性</b>由 {@code BpmProcessValidator#validate} 在 plan 期硬拦 ——
 *       包括「{@code approveType} 必须为 1」那条唯一会静默失效的规则
 *       （{@code BpmProcessValidator#validateApproveNode} 里 {@code addError}）。
 *       清单再判一遍就是第二份口径，两份不一致时用户看到的是自相矛盾的两句话</li>
 * </ol>
 * 把节点树翻译成人话（「谁审批、什么条件走哪个分支」）是 {@code ExplainProcessTool} 已经会做的事，
 * 抽成 {@code ProcessNodeTreeExplainer} 供清单复用要拿真实的 {@code constraints}（需查 bpm），
 * 同样归 S2-d。所以本刀这一项<b>刻意很薄</b>：宁可少说，不可说错。
 *
 * <h3>本刀唯一越界说了平台语义的一处，以及为什么</h3>
 * {@code startUserIds} 为空时清单会说「人人可发起」。这确实是平台行为而非 DSL 原文，
 * 但它同时写在两处、且两处都可查：工具契约原话「不传表示不限」，
 * bpm 侧 {@code BpmProcessDefinitionInfoDO#startUserIds} 的注释原话「如果为空，则表示"全部可以发起"！」
 * （并由 {@code BpmProcessPermissionChecker} 投影成 {@code ALL:all-users} 的 START 授权）。
 * 之所以值得破例：一条刚部署的审批流「全平台谁都能发起」是审批人点同意前<b>必须</b>知道的事，
 * 而 DSL 里它表现为「这个参数没写」—— 不翻译就完全看不出来。
 *
 * <p>⚠️ 反过来，凡是要查库才知道的（分类是否启用、key 是否已存在、
 * 绑的表单主表有没有 {@code process_instance_id} 列、当前登录用户会被强制加进管理员名单）
 * 一律不在这里判，只在 {@code detail.requiresColumn} 里<b>声明要求</b>，
 * 由 S2-d 的干跑器去核实。声明要求与核实要求是两件事，混在一起就会出现
 * 「清单说有这一列、库里其实没有」。
 *
 * @author 走进新时代
 */
@Component
public class ProcessDeployChangeExtractor implements ToolChangeExtractor {

    /** 在线表单主表必须有的流程实例列；出处是 {@link ProcessCreateAndDeployTool#paramsSchema()} 对 {@code formDesignCode} 的说明 */
    private static final String REQUIRED_COLUMN = "process_instance_id";

    @Override
    public String toolId() {
        return ProcessCreateAndDeployTool.NAME;
    }

    @Override
    public ChangeItem extract(NodeDTO node, ManifestContext ctx) {
        Map<String, Object> args = ManifestArgs.of(node);
        String key = ManifestArgs.str(args, ProcessCreateAndDeployTool.PARAM_KEY);
        String name = ManifestArgs.str(args, ProcessCreateAndDeployTool.PARAM_NAME);
        String formDesignCode = ManifestArgs.str(args, ProcessCreateAndDeployTool.PARAM_FORM_DESIGN_CODE);
        String formId = ManifestArgs.str(args, ProcessCreateAndDeployTool.PARAM_FORM_ID);
        List<String> managerUserIds = ManifestArgs.strList(args, ProcessCreateAndDeployTool.PARAM_MANAGER_USER_IDS);
        List<String> startUserIds = ManifestArgs.strList(args, ProcessCreateAndDeployTool.PARAM_START_USER_IDS);

        ChangeItem item = new ChangeItem();
        item.setKind(ChangeKind.PROCESS_DEPLOY);
        item.getTarget().put("key", key);
        item.getTarget().put("name", name);

        Map<String, Object> detail = item.getDetail();
        putIfPresent(detail, "category", ManifestArgs.str(args, ProcessCreateAndDeployTool.PARAM_CATEGORY));
        putIfPresent(detail, "description", ManifestArgs.str(args, ProcessCreateAndDeployTool.PARAM_DESCRIPTION));
        putIfPresent(detail, "formDesignCode", formDesignCode);
        putIfPresent(detail, "formId", formId);
        detail.put("managerUserIds", managerUserIds);
        detail.put("startUserIds", startUserIds);
        if (startUserIds.isEmpty()) {
            detail.put("startUserNote", "未限定可发起人 —— 部署后全平台所有人都能发起这条流程");
        }
        if (formDesignCode != null) {
            detail.put("requiresColumn", REQUIRED_COLUMN);
        }

        item.setSummary(buildSummary(key, name, formDesignCode, formId, startUserIds));
        return item;
    }

    private String buildSummary(String key, String name, String formDesignCode, String formId,
                                List<String> startUserIds) {
        StringBuilder sb = new StringBuilder("部署审批流");
        sb.append(name == null ? "（名称未填）" : "「" + name + "」");
        sb.append(key == null ? "，标识未填" : "（标识 " + key + "）");

        if (formDesignCode == null && formId == null) {
            // 工具在 applyForm 里对「两个都没给」是直接抛异常的。清单提前说出来，
            // 省得用户点完同意才看见一句「必须绑定一张表单」
            sb.append("。⚠️ 没有绑定表单（formDesignCode / formId 都没给），执行时会被直接拒绝");
            return sb.toString();
        }
        sb.append("，绑定").append(formDesignCode != null
                ? "在线表单 " + formDesignCode
                : "普通表单 #" + formId);
        if (startUserIds.isEmpty()) {
            sb.append("。⚠️ 未限定可发起人，部署后人人都能发起");
        }
        return sb.toString();
    }

    private void putIfPresent(Map<String, Object> detail, String key, String value) {
        if (value != null) {
            detail.put(key, value);
        }
    }

}
