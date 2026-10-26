package cn.enlev.ainb.module.agent.service.workflow;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ChangeManifest;
import cn.enlev.ainb.module.agent.framework.agent.core.security.OutputModerator;
import cn.enlev.ainb.module.bpm.api.task.BpmProcessInstanceApi;
import cn.enlev.ainb.module.bpm.api.task.dto.BpmProcessInstanceCreateReqDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DSL_INVALID;

/**
 * {@code hitl.bpm_approval} 节点的「发起审批」侧 Service（P6 收口 + S6 manifest 注入）
 *
 * <h3>🔴 为什么发起动作在<b>挂起时</b>做，而不是在执行器里做</h3>
 * 编译器对 {@code hitl.*} 节点登记 {@code interruptBefore(id)}：SAA 在执行到该节点<b>之前</b>就挂起、
 * checkpoint 落库、stream 结束，<b>执行器根本不会在挂起前被调用</b>——它只在 resume 之后才跑（与
 * {@code hitl.confirm}/{@code wait} 同机制）。所以「创建 BPM 流程实例」这个副作用必须发生在挂起时刻，
 * 由 {@code AgentWorkflowExecutionServiceImpl.runGraph} 在中断收尾处调本 Service（与算 resume_at/event_key
 * 同一处）。若把它放进执行器，则 resume 后才创建实例——审批永远晚于「等待审批」，且每次 resume 都会重复创建。
 *
 * <h3>🔴 跨模块只走 {@code bpm.api}（架构红线）</h3>
 * {@code AgentArchitectureTest} 钉死 agent 不得 import 其它模块的非 api 包。这里注入
 * {@link BpmProcessInstanceApi}，绝不碰 {@code BpmProcessInstanceService}。BPM 实例由 Flowable 在<b>当前租户</b>
 * 上下文里创建（{@code FlowableUtils.getTenantId()}），调用方（runGraph）已用 {@code context.execute} 贴好身份，
 * 故审批与发起天然同租户。
 *
 * <h3>businessKey = 唤醒凭据</h3>
 * 发起时把 businessKey 设成「执行级唯一」的串（{@code agent-wf:executionId:nodeId}），并把它同时落成执行实例的
 * {@code event_key}。审批完成时 {@link AgentBpmApprovalListener} 收到 {@code BpmProcessInstanceStatusEvent}，
 * 用事件回带的 businessKey 走既有 {@code publishEvent(eventKey,...)} 广播唤醒——零新查询、零 executeIgnore，
 * 全程复用 wait(event) 已验证的 resume 链路与 CAS 去重。
 *
 * <h3>S6：变更清单冻结进流程变量（台账 #88/#89 + G26）</h3>
 * <ul>
 *   <li><b>键名 {@link #AGENT_CHANGE_MANIFEST_VAR}</b>：以 S3 已落地的前端读取端（{@code bpm/processInstance/detail/index.vue} 的 {@code agentManifest} computed）为权威。</li>
 *   <li><b>载体是对象不是 JSON 字符串</b>：{@code act_ru_variable.TEXT_} 是 varchar(4000)，而实测 8 条最大存量流程
 *       manifest 6 条超限（最大 8477 字 = 212%）⟹ 字符串载体必然炸。Flowable 对非基本类型走 {@code serializable}
 *       → {@code act_ge_bytearray} blob，无此上限。故用 {@link #toManifestTree} 把 {@link ChangeManifest} 转成
 *       JDK 类型树（LinkedHashMap/List/String/Number），<b>绝不直接塞 POJO</b>（Java 序列化会把类名与
 *       serialVersionUID 冻进历史实例，改字段后旧实例反序列化失败）。</li>
 *   <li><b>绝不原地 put</b>（#89）：formVars 可能是 DSL 里那个活 map，注入前先拷一份。本类经
 *       {@link OutputModerator#moderateFormVars} 拿到的就是新 map。</li>
 *   <li><b>formVars 过 {@link OutputModerator}</b>（G26）：审批人是唯一有权拦不可逆动作的人，
 *       他眼前的值不许是未经审核的 LLM 产物。</li>
 *   <li><b>构建失败注入降级清单，不是不注入</b>：见 {@link ChangeManifest#degraded(String)}。
 *       「没算出来」和「不改什么」在审批页上必须是两句话。</li>
 * </ul>
 *
 * @author 走进新时代
 */
@Service
@Slf4j
public class AgentBpmApprovalService {

    /** businessKey / event_key 前缀：让监听器一眼认出「这是 agent 工作流发起的审批」，与在线表单等其它 businessKey 区分 */
    public static final String BUSINESS_KEY_PREFIX = "agent-wf";

    /**
     * 变更清单在 BPM 流程变量里的键名（S6）。
     *
     * <p>🔴 以 S3 已落地的前端读取端为权威：{@code bpm/processInstance/detail/index.vue} 的 {@code agentManifest} computed 读的就是这个键。
     * 不要改成 {@code _ref}/{@code _summary} 之类的变体——全仓零消费者（见方案 §4 S6 行 #88）。
     */
    public static final String AGENT_CHANGE_MANIFEST_VAR = "agent_change_manifest";

    @Resource
    private BpmProcessInstanceApi bpmProcessInstanceApi;

    /**
     * 构造某执行某节点的 businessKey（= 唤醒用的 event_key）
     *
     * <p>执行级唯一：同一执行内不会有两个同名节点，故 {@code executionId + nodeId} 足以唯一定位一次挂起。
     * 监听器凭它反推 executionId/nodeId 无需查库，也保证不同执行/节点的事件互不误唤醒。
     */
    public static String buildBusinessKey(Long executionId, String nodeId) {
        return BUSINESS_KEY_PREFIX + ":" + executionId + ":" + nodeId;
    }

    /** businessKey 是否由本 Service 发起（监听器据此过滤非 agent 工作流的流程事件） */
    public static boolean isAgentWorkflowBusinessKey(String businessKey) {
        return StrUtil.isNotEmpty(businessKey) && businessKey.startsWith(BUSINESS_KEY_PREFIX + ":");
    }

    /**
     * 发起一个 BPM 重审批流程实例（在 hitl.bpm_approval 节点挂起时调用）
     *
     * @param userId            发起人（执行实例所有者），作为 BPM startUser
     * @param processDefKey     BPM 流程定义 key（节点 config.bpmProcessDefKey）
     * @param businessKey       业务唯一标识（= event_key），审批完成事件原样回带
     * @param formVars          传给流程的变量（节点 config.formVars，可为空）；<b>不会被原地修改</b>
     * @param manifestTree      冻结后的变更清单对象树（{@link #toManifestTree} 的产物）；null = 不注入。
     *                          🔴 null 只应由 {@code injectManifest=false} 产生（作者主动关掉，审批页不出现页签是对的）；
     *                          清单<b>构建失败</b>时调用方注入的是 {@link ChangeManifest#degraded(String)}，
     *                          不是 null——否则审批人分不清「这流程不改什么」和「平台没算出来」，等于让他盲批。
     * @return Flowable 流程实例 id（仅日志/排查用，唤醒不依赖它，依赖 businessKey）
     */
    public String createApproval(Long userId, String processDefKey, String businessKey,
                                 Map<String, Object> formVars, Object manifestTree) {
        if (StrUtil.isEmpty(processDefKey)) {
            throw exception(WORKFLOW_DSL_INVALID, "hitl.bpm_approval 节点缺少 bpmProcessDefKey");
        }
        // 🔴 先审核、后注入：审核拿到的就是新 map（#89 的拷贝在这里结构性成立）。
        // 🔴 下面这段是【2026-10-07 · B2-2 改掉的假话】。原文写的是：
        //      「注入的 manifest 不受审核影响（它不是 LLM 产物，是平台自己算出来的清单）。」
        //    实测推翻：平台算的是清单的**结构**，但清单里的**文字是 AI 写的** ——
        //    WorkflowChangeManifestBuilder 把 node.getName()（AI 起草 DSL 时写的节点名）直接塞进
        //    ChangeItem 的 nodeName，又把 AI 写的工具入参拼成一句「给人看的人话」；
        //    UntrustedInputTracer#buildSummary 同理拼 sinkNodeName，而它自己的注释就写着「直接印在确认卡上」。
        //    ⟹ manifest 恰恰**就是** OutputModerator 立论里那份「被操纵的变更说明」，
        //       而审批人正是 G26 所指的那个决策者。故它照审，审核点落在 toManifestTree
        //       （5 条投递通道的共同边界，理由见那个方法的注释）。
        OutputModerator.Moderation moderated = OutputModerator.moderateFormVars(formVars);
        if (!moderated.flaggedKeys().isEmpty()) {
            log.warn("[createApproval][formVars 经输出审核被标记/截断 businessKey({}) keys({})]",
                    businessKey, moderated.flaggedKeys());
        }
        Map<String, Object> variables = new LinkedHashMap<>(moderated.variables());
        if (manifestTree != null) {
            variables.put(AGENT_CHANGE_MANIFEST_VAR, manifestTree);
        }
        BpmProcessInstanceCreateReqDTO reqDTO = new BpmProcessInstanceCreateReqDTO();
        reqDTO.setProcessDefinitionKey(processDefKey);
        reqDTO.setBusinessKey(businessKey);
        reqDTO.setVariables(variables);
        String processInstanceId = bpmProcessInstanceApi.createProcessInstance(userId, reqDTO);
        log.info("[createApproval][发起 BPM 审批 processDefKey({}) businessKey({}) 实例({}) formVars({}) 清单注入({})]",
                processDefKey, businessKey, processInstanceId, variables.size(), manifestTree != null);
        return processInstanceId;
    }

    /**
     * 从 DSL 节点取发起审批所需的配置：流程定义 key + 表单变量 + 是否注入变更清单。
     *
     * <p>独立成一个静态解析方法，让「挂起时发起」（runGraph）与「校验/清单」共用同一处口径，
     * 杜绝两处各解析一遍 config 产生分歧（与 wait 的 resolveWait* 同思路）。
     */
    public static BpmApprovalConfig resolveConfig(NodeDTO node) {
        String processDefKey = node.configStr("bpmProcessDefKey");
        Map<String, Object> formVars = node.configMap("formVars");
        boolean injectManifest = node.configBool("injectManifest", true);
        return new BpmApprovalConfig(processDefKey, formVars, injectManifest);
    }

    /**
     * 把 {@link ChangeManifest} 转成 JDK 类型树（LinkedHashMap / ArrayList / String / Number），
     * 供 Flowable 以 {@code serializable}（Java 序列化 → blob）方式冻结进流程变量。
     *
     * <p>🔴 不能直接塞 POJO：本库 Flowable 用 Java 序列化，POJO 会把类名与 serialVersionUID 冻进历史实例
     * （台账 #88 实测 {@code act_ge_bytearray.BYTES_} 前 4 字节 = {@code ACED0005}）。
     * 走 JSON 往返得到的是 JDK 类型，改字段不破坏历史实例的反序列化。
     *
     * <h3>🔴 B2-2：这里同时是 manifest 通向审批页那条通道的<b>审核点</b></h3>
     * 这棵树会被冻进 BPM 流程变量、最终渲染在审批页上，是<b>给人做决定用的</b>，故必须审核。
     * <p>审核点为什么放在<b>这个转换口</b>而不是 manifest 的构建处（{@code buildManifest}）——
     * 构建处看着是更漂亮的单点收口，但它的产物<b>还要喂发布闸门</b>：
     * {@code WorkflowDslValidator#foldUntrustedWrites} 拿 {@code equals} 比 {@code sinkKind}、
     * 拿 {@code nodeById.get} 查 {@code sinkNodeId}，而审核会剥不可见字符、会加前缀、会在 2000 字符处截断
     * ⟹ 在源头审核会让那两处比较<b>静默失配</b>，那条校验接着 {@code continue} 跳过，
     * 结果是<b>本该拒的发布被放行</b>。那是 fail-open，比不审核更糟。
     * 规则因此是：<b>闸门永远读原始 manifest，只有交给人看的那一份才审核。</b>
     * <p>本方法满足这条规则的前提是它<b>只服务审批注入</b>——已核对：唯一调用方是
     * {@code AgentWorkflowExecutionServiceImpl#buildFrozenManifestTree}，其产物只流向 {@link #createApproval}。
     * 🔴 将来若有人拿它去喂校验，这条前提就破了，审核必须挪走。
     */
    public static Map<String, Object> toManifestTree(ChangeManifest manifest) {
        if (manifest == null) {
            return null;
        }
        Map<String, Object> tree = JsonUtils.parseObject(JsonUtils.toJsonString(manifest),
                new TypeReference<Map<String, Object>>() {
                });
        OutputModerator.Moderation moderated = OutputModerator.moderateFormVars(tree);
        if (!moderated.flaggedKeys().isEmpty()) {
            // 🔴 只记路径不记内容：清单文字本身是 AI 产物，把它整条打进日志等于再造一个投递面。
            log.warn("[toManifestTree][变更清单经输出审核被标记/截断，冻结进审批变量的是审核后的副本 paths({})]",
                    moderated.flaggedKeys());
        }
        return moderated.variables();
    }

    /** hitl.bpm_approval 的发起配置快照 */
    public record BpmApprovalConfig(String processDefKey, Map<String, Object> formVars,
                                    boolean injectManifest) {
    }

}

