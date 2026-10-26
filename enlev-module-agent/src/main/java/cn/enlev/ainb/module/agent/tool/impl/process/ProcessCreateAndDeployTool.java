package cn.enlev.ainb.module.agent.tool.impl.process;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.AgentValidateResult;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.BpmProcessValidator;
import cn.enlev.ainb.module.bpm.api.definition.BpmCategoryApi;
import cn.enlev.ainb.module.bpm.api.definition.BpmModelApi;
import cn.enlev.ainb.module.bpm.api.definition.dto.BpmModelCreateReqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_TOOL_PARAM_INVALID;

/**
 * 工具：创建并部署流程（方案 §5.7）
 *
 * <h3>为什么是「一次性」的</h3>
 * 这个平台上<b>没有</b>「先建草稿、回头再改」这条路 ——
 * 从未部署过的流程模型，其管理员判定读的是流程资源上的授权，
 * 而那行资源要部署成功之后才登记。所以中间态是个死胡同。
 *
 * <h3>建与改是同一个工具</h3>
 * key 不存在就新建，已存在就更新那一条，两条路都以部署收尾。
 * 不拆成两个工具，是因为模型很难提前判断「这条流程存不存在」——
 * 让它先查再选工具，多一轮调用不说，查完到执行之间状态还可能变。
 * 由工具自己按 key 决定，模型只管把想要的最终样子描述清楚。
 *
 * <h3>风险是 HIGH 而不是 IRREVERSIBLE</h3>
 * 部署本身可回退（重新部署一个新版本即可），不像建表那样动了就回不去。
 * 但影响面大 —— 部署完流程就能被发起了，所以仍要进 Plan 等确认。
 *
 * @author 走进新时代
 */
@Component
@AgentTool(
        name = ProcessCreateAndDeployTool.NAME,
        displayName = "创建并部署流程模型",
        groupName = "流程审批",
        description = "保存并立即部署一条审批流程。保存与部署是一步完成的，"
                + "因为这个平台上「建了但没部署」的流程模型会变成谁也改不了的半成品。"
                + "key 不存在就新建、已存在就覆盖更新那一条，部署后旧版本自动挂起。"
                + "要改已有流程，先用 process_get_simple 取回完整节点树，"
                + "在树上改完整体传回来 —— 传的是最终样子，没传的节点就是被删掉的节点。"
                + "⚠️ 绑定在线表单（formDesignCode）时，那张表单必须是【已发布的既有表单】，"
                + "且它的主表同时有 process_instance_id（STRING，长度不小于 64）"
                + "与 process_status（整数型：TINYINT/INT/LONG）两列"
                + "（process_instance_id 把表单数据关联到流程实例；process_status 只有主表单要，"
                + "当节点表单绑时不要它），否则部署会被拒绝。"
                + "🔴 这两列的类型写错不报错：建表、发布全都会成功，"
                + "只有到这一步才被拒，而表已经建出来了、DDL 回滚不掉 —— "
                + "建表时就照上面的类型写（判据由 datamodel_create 的生成后校验把着）。"
                + "🔴 不要绑同一个 Plan 里用 form_save_draft 现建的表单：那只是【草稿】，"
                + "而发布体检是对平台此刻状态做的只读快照 —— 编码写字面量当场就被拒，"
                + "报出来的还是「表单不存在，可能已被删除」这句把原因说反的话"
                + "（没人删过它，建它的节点要到运行期才跑）；写占位符能发布过去，"
                + "但运行期部署那一步照样被拒，而那时表已经建出来了、DDL 回滚不掉。"
                + "把 form_publish_bundle 排在它前面也救不了：那些节点同样要到运行期才跑。"
                + "要人工确认就用 hitl 节点，别为了拿一个闸口现造 BPM 流程",
        permission = "bpm:model:create",
        // 内部串了 create 与 deploy 两步，缺 deploy 权限的话会卡在第二步。
        // 前置判定：差一个就一步都不做
        extraPermissions = {"bpm:model:deploy"},
        risk = RiskLevel.HIGH)
@Slf4j
public class ProcessCreateAndDeployTool implements AgentToolFunction {

    /**
     * 工具键
     *
     * <p>🔴 上面注解的 {@code name} 与变更清单抽取器的 {@code toolId()} 都必须引用它。
     * 三处各写一遍字面量时，改名不会报错 —— 只会让清单静默退回兜底解读。
     */
    public static final String NAME = "process_create_and_deploy";

    public static final String PARAM_KEY = "key";
    public static final String PARAM_NAME = "name";
    public static final String PARAM_SIMPLE_MODEL = "simpleModel";
    public static final String PARAM_FORM_DESIGN_CODE = "formDesignCode";
    public static final String PARAM_FORM_ID = "formId";
    public static final String PARAM_CATEGORY = "category";
    public static final String PARAM_DESCRIPTION = "description";
    public static final String PARAM_MANAGER_USER_IDS = "managerUserIds";
    public static final String PARAM_START_USER_IDS = "startUserIds";

    /** 表单类型：普通表单 */
    private static final int FORM_TYPE_NORMAL = 10;
    /** 表单类型：在线表单 */
    private static final int FORM_TYPE_ONLINE = 30;

    // ==================== 对外文案 ====================

    /**
     * 「分类不存在或已停用，会回落到哪个」这句话
     *
     * <p>🔴 抽出来给变更清单的干跑器共用：同一件事有两个读者 ——
     * 执行时的 Plan 卡片（{@link #resolveCategory} 往 {@code AgentValidateResult} 里加）
     * 与审批人看的清单（{@code ChangeManifestDryRunner} 在部署前就查一遍分类是否启用）。
     * 两处各写一份必然漂移，而漂移的表现是「清单说会回落到 A、执行时回落到 B」。
     *
     * <p>措辞用「改用」而不是「已改用」：干跑发生在部署之前，
     * 完成时态在清单上读起来像一件已经发生过的事。
     *
     * @param wanted   模型传的分类编码，为空时显示「未填」
     * @param fallback 实际会落库的那个（启用中、排序最前的分类）
     */
    public static String categoryFallbackWarning(String wanted, String fallback) {
        return String.format("流程分类「%s」不存在或已停用，改用启用中的分类「%s」；"
                        + "否则部署成功也只会让流程在「发起流程」列表里隐身",
                StrUtil.blankToDefault(wanted, "未填"), fallback);
    }

    @Resource
    private BpmModelApi bpmModelApi;
    @Resource
    private BpmCategoryApi bpmCategoryApi;
    @Resource
    private BpmProcessValidator validator;

    @Override
    public Object execute(Map<String, Object> params) {
        String key = requireString(params, PARAM_KEY, "流程标识");
        String name = requireString(params, PARAM_NAME, "流程名称");
        String simpleModel = parseSimpleModel(params);

        // 🔴 执行前再校验一次，理由同 datamodel_create：
        //    Plan 从生成到确认之间可能隔着几分钟，期间表单可能被改、流程 key 可能被占用
        AgentValidateResult result = validator.validate(key, simpleModel);
        validator.validateFirstApproveNode(JSONUtil.parseObj(simpleModel), result);
        if (!result.isPassed()) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "process_create_and_deploy",
                    result.toFeedback());
        }

        BpmModelCreateReqDTO reqDTO = new BpmModelCreateReqDTO();
        reqDTO.setKey(key);
        reqDTO.setName(name);
        reqDTO.setDescription(asString(params.get(PARAM_DESCRIPTION)));
        reqDTO.setCategory(resolveCategory(params, result));
        applyForm(params, reqDTO);
        reqDTO.setManagerUserIds(resolveManagerUserIds(params));
        reqDTO.setStartUserIds(parseLongList(params.get(PARAM_START_USER_IDS)));
        reqDTO.setSimpleModel(simpleModel);

        Long userId = SecurityFrameworkUtils.getLoginUserId();
        String modelId = bpmModelApi.saveAndDeployModel(userId, reqDTO);
        log.info("[execute][AI 建流程完成 标识({}) 模型编号({})]", key, modelId);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", modelId);
        resp.put("key", key);
        resp.put("name", name);
        if (!result.getWarnings().isEmpty()) {
            resp.put("warnings", result.getWarnings());
        }
        resp.put("message", "流程「" + name + "」已创建并部署，现在可以发起了。"
                + "⚠️ 部署成功只说明流程图和配置合法，"
                + "节点能不能真的审批、条件会不会按预期分流，必须在浏览器里发起一笔走一遍才算数");
        return resp;
    }

    // ==================== 表单 ====================

    /**
     * 绑定表单
     *
     * 流程必须绑表单 —— 部署时会校验，不绑直接报「表单未配置」。
     * 在线表单（{@code formDesignCode}）与普通表单（{@code formId}）二选一，
     * 优先在线表单：AI 建出来的表单都是在线表单。
     */
    private void applyForm(Map<String, Object> params, BpmModelCreateReqDTO reqDTO) {
        String formDesignCode = asString(params.get(PARAM_FORM_DESIGN_CODE));
        Object formId = params.get(PARAM_FORM_ID);
        if (StrUtil.isNotBlank(formDesignCode)) {
            reqDTO.setFormType(FORM_TYPE_ONLINE);
            reqDTO.setFormDesignCode(formDesignCode);
            return;
        }
        if (formId != null) {
            reqDTO.setFormType(FORM_TYPE_NORMAL);
            try {
                reqDTO.setFormId(Long.parseLong(formId.toString()));
            } catch (NumberFormatException ex) {
                throw exception(AGENT_TOOL_PARAM_INVALID, "process_create_and_deploy",
                        "formId 必须是数字，实际收到：" + formId);
            }
            return;
        }
        throw exception(AGENT_TOOL_PARAM_INVALID, "process_create_and_deploy",
                "必须绑定一张表单：在线表单传 formDesignCode（表单编码），"
                        + "普通表单传 formId。流程不绑表单的话部署会被拒绝");
    }

    // ==================== 分类 ====================

    /**
     * 把流程分类对齐到「启用中、真实存在」的分类 code
     *
     * 🔴 <b>为什么不能照模型传的原样落库、更不能默认 "1"。</b>
     * 「发起流程」列表是按 {@code bpm_category} 里启用中的分类 code 分组展示的，
     * 挂了一个不存在的分类（模型瞎填、或早先默认的 "1"）部署照样成功，
     * 但这条流程在发起列表里<b>永远不出现</b> —— 用户建完找不到，正是「配了不生效」那类缺陷。
     * 所以这里：传了且启用中就照用；否则回落到排序最前的启用分类，并记一条警告让 Plan 卡片看得见。
     */
    private String resolveCategory(Map<String, Object> params, AgentValidateResult result) {
        List<String> enabledCodes = bpmCategoryApi.getEnabledCategoryCodes();
        String wanted = asString(params.get(PARAM_CATEGORY));
        if (enabledCodes.isEmpty()) {
            // 平台一个分类都没配：无处可对齐，原样落库（发起列表本就无法分组展示），不强行造分类
            return StrUtil.blankToDefault(wanted, "1");
        }
        if (StrUtil.isNotBlank(wanted) && enabledCodes.contains(wanted)) {
            return wanted;
        }
        String fallback = enabledCodes.get(0);
        result.addWarning(categoryFallbackWarning(wanted, fallback));
        return fallback;
    }

    // ==================== 管理员 ====================

    /**
     * 确定可管理该流程的人
     *
     * 🔴 <b>无论模型怎么传，当前用户一定要在名单里。</b>
     * 模型从未部署过时，管理员判定只能看这份名单 ——
     * 当前用户不在里面的话，他连自己刚建的这条流程都删不掉，
     * 而部署更是第一步就被拒。
     */
    private List<Long> resolveManagerUserIds(Map<String, Object> params) {
        List<Long> managerUserIds = new ArrayList<>(parseLongList(params.get(PARAM_MANAGER_USER_IDS)));
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        if (userId != null && !managerUserIds.contains(userId)) {
            managerUserIds.add(userId);
        }
        if (managerUserIds.isEmpty()) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "process_create_and_deploy",
                    "无法确定流程管理员：当前登录用户为空，且没有传 managerUserIds");
        }
        return managerUserIds;
    }

    // ==================== 入参 ====================

    /**
     * 把 {@code simpleModel} 归一成 JSON 串：模型给对象、给 {@code JSONObject}、
     * 给已经序列化好的字符串，三种都收
     *
     * <p>🔴 抽成 {@code public static} 给变更清单的干跑器共用（{@code ChangeManifestDryRunner}）。
     * 干跑器手上只有节点的 {@code args}，它要把同一份 {@code simpleModel} 喂给同一个
     * {@code BpmProcessValidator}；若归一化各写一份，两边就会出现
     * 「清单说节点树合法、执行时说 JSON 不合法」这类分歧 —— 而这一支恰恰<b>只有失败路径会分歧</b>：
     * 成功时两边都得到同一个 JSON 串，失败时工具会说出「不是合法的 JSON 对象」，
     * 干跑器若改用「解析不出来就当空对象」的写法，报给用户的就成了
     * 「根节点的 type 必须是 10（发起人），当前是 null」—— 指着一个根本不存在的问题让人去改。
     *
     * <p>⚠️ 本方法<b>会抛</b> {@code ServiceException}（缺参数 / JSON 不合法），
     * 调用方自己决定这句话怎么落地：工具原样抛给模型，干跑器接住落成 REJECTED。
     */
    public static String parseSimpleModel(Map<String, Object> params) {
        Object raw = params.get(PARAM_SIMPLE_MODEL);
        if (raw == null) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "process_create_and_deploy",
                    "缺少 simpleModel（流程节点树）");
        }
        // 模型可能给对象，也可能给已经序列化好的字符串，两种都收
        if (raw instanceof Map || raw instanceof JSONObject) {
            return JSONUtil.parseObj(raw).toString();
        }
        String text = raw.toString().trim();
        try {
            return JSONUtil.parseObj(text).toString();
        } catch (Exception ex) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "process_create_and_deploy",
                    "simpleModel 不是合法的 JSON 对象：" + ex.getMessage());
        }
    }

    private String requireString(Map<String, Object> params, String paramKey, String label) {
        Object value = params.get(paramKey);
        if (value == null || StrUtil.isBlank(value.toString())) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "process_create_and_deploy",
                    "缺少 " + paramKey + "（" + label + "）");
        }
        return value.toString().trim();
    }

    private String asString(Object value) {
        return value == null ? null : StrUtil.trimToNull(value.toString());
    }

    private List<Long> parseLongList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "process_create_and_deploy",
                    "期望一个数字数组，实际收到：" + value);
        }
        List<Long> result = new ArrayList<>(list.size());
        for (Object item : list) {
            try {
                result.add(Long.parseLong(item.toString()));
            } catch (NumberFormatException ex) {
                throw exception(AGENT_TOOL_PARAM_INVALID, "process_create_and_deploy",
                        "数组里出现了非数字：" + item);
            }
        }
        return result;
    }

    /** 见 {@link AgentToolFunction#resultArrayKeys}：本工具全部返回分支里数组值的键（并集） */
    @Override
    public List<String> resultArrayKeys() {
        return List.of("warnings");
    }

    @Override
    public String paramsSchema() {
        return """
                key（必填，字符串）：流程标识，全小写下划线，如 purchase_apply。
                  字母开头，不能有中文/空格/冒号。
                  用 process_list 查到的已有 key = 覆盖更新那条流程并发新版本；
                  用新 key = 新建一条流程
                name（必填，字符串）：流程名称，如「采购申请审批」
                formDesignCode（在线表单必填）：要绑定的表单编码，必须是【已发布的既有表单】。
                  ⚠️ 它的主表必须同时有 process_instance_id（STRING，长度不小于 64）
                  与 process_status（整数型：TINYINT/INT/LONG）两列
                  （主表单两列都要；只当节点表单绑时才只要前一列）；
                  这张表若是同一 Plan 里用 datamodel_create 建的，建表时就要带上这两列，
                  且类型照上面写 —— process_status 建成 STRING 建表不会报错，
                  只会让这一步永远过不去，而那时表已经建出来了、DDL 回滚不掉
                  🔴 但不要绑同一 Plan 里 form_save_draft 现建的那张表单 —— 无论编码写字面量
                  还是写它的输出占位符都不行：form_save_draft 只建【草稿】，而流程主表单要求【已发布】。
                  字面量会被发布体检当场拒（报的还是「表单不存在，可能已被删除」，那句把原因说反了：
                  没人删过它，建它的节点要到运行期才跑）；占位符能发布过去，
                  但运行期部署那一步照样被拒，而那时表已经建出来了、DDL 回滚不掉。
                  把 form_publish_bundle 排在它前面也救不了，那些节点同样要到运行期才跑。
                  需要人工确认就用 hitl 节点，别为了拿一个闸口现造 BPM 流程
                formId（普通表单必填）：二选一，绑定 BPM 自带的动态表单
                category（可选）：流程分类编码。传了且启用中就照用；
                  没传或传的分类没启用，会回落到启用中排序最前的分类（不是固定回落 "1"）
                description（可选）：流程说明
                managerUserIds（可选，数字数组）：可管理该流程的人，当前用户会被自动加入
                startUserIds（可选，数字数组）：可发起该流程的人，不传表示不限
                simpleModel（必填，对象）：流程节点树。硬性要求：
                  · 根节点固定 type:10（发起人），不要自己加 type:0
                  · 末端必须挂 type:1 的结束节点，否则后端构造流程图时直接空指针
                  · 每个节点都要有全局唯一的 id，没 id 的节点会被静默丢弃
                  · 审批节点（type:11）必须写 approveType:1 —— 这条最要紧，
                    写错会让候选人、审批方式、字段权限全部被静默丢弃，且部署照样成功
                  · 审批节点必须写 approveMethod（1=随机一人、3=或签、4=依次审批；
                    2=会签时还要给 approveRatio）
                  · 审批节点必须写 candidateStrategy + candidateParam
                    （30=指定用户，param 是逗号分隔的用户编号；37=发起人部门负责人，param 是层级如 "1"）
                  · 条件分支（type:51）的 conditionNodes 里每项都是 type:50，
                    且必须恰有一个 conditionSetting.defaultFlow 为 true 兜底；
                    其余每项要写 conditionType:1 + conditionExpression，
                    表达式用 ${var:getOrDefault(字段名, null) > 100} 这种写法
                可以照抄的骨架：
                """ + BpmProcessValidator.FEW_SHOT_EXAMPLE;
    }

}
