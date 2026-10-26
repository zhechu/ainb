package cn.enlev.ainb.module.agent.service.workflow;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.dal.mysql.def.AgentDefMapper;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowDefMapper;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowExecutionMapper;
import cn.enlev.ainb.module.agent.enums.WorkflowDefStatusEnum;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslParser;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslToStateGraphCompiler;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.trigger.WorkflowTriggerExtractor;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.WorkflowChangeManifestBuilder;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.AgentValidateResult;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.WorkflowDslValidator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.List;

import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DEF_NOT_EXISTS;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_TRIGGER_CHAT_AGENT_NOT_FOUND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentWorkflowDefServiceImpl#publishDef(String)} 单测（增量7a：plan-as-workflow 生成端点落 DRAFT 后发布）
 *
 * <p>纯 mock（{@link AgentWorkflowDefMapper} + {@link WorkflowTriggerExtractor}），0 token、不碰真 DB。
 * publishDef 只翻状态、只带 id+status 做部分 updateById，故断言聚焦「翻没翻、翻成什么、幂等、fail-close」四件事：
 * <ol>
 *   <li>① DRAFT → PUBLISHED：updateById 收到「只带 id + status=PUBLISHED」的部分 DO（dsl/触发列不动）</li>
 *   <li>② 已 PUBLISHED：幂等返回原 id，updateById <b>一次都不调</b>（重复发布不报错、不新增版本）</li>
 *   <li>③ code 不存在（selectLatestByCode 返回 null）：抛 {@code WORKFLOW_DEF_NOT_EXISTS}</li>
 *   <li>④ 最新版是 DISABLED（曾发布、现停用）：不属本方法职责，与「不存在」同文案抛 {@code WORKFLOW_DEF_NOT_EXISTS}（防探测）</li>
 * </ol>
 *
 * <p>⚠️ S4b 之后 publishDef 多了一道发布闸门（{@code assertPublishable}）。<b>本类一律让它放行</b>
 * （{@link #stubGatePasses()}），因为本类测的是状态机，不是体检判据 —— 闸门自己的判定在
 * {@code AgentWorkflowDefServicePublishGateTest}。把两边混在一个类里，改判据时就得同时读懂状态机断言。
 * 注意 {@code stubGatePasses()} 是<b>按用例显式调用</b>而不是放 {@code @BeforeEach}：
 * {@code MockitoExtension} 默认 STRICT_STUBS，②③④ 与两条 agentMissing 用例压根走不到闸门，
 * 统一 stub 会直接判成 UnnecessaryStubbing。
 *
 * <p>🔴 B6 / S16 之后本类还兼管「发布 ≠ 生效」那一小节（见文件末尾）：管理页发布在无主版本时补钉、
 * 有主版本时不抢线上，而 {@code publishDef(code)} 一律不钉（由聊天那侧的 Controller 显式钉）。
 * 这几条断言的是**状态机之外的第二类副作用**（primary_flag），与上面 ①~④ 的「只翻状态」并列，
 * 故放在同一个类里而不是新开一个 —— 它们共用同一套闸门放行 stub 与同一批 mock。
 *
 * @author 走进新时代
 */
class AgentWorkflowDefServicePublishDefTest extends BaseMockitoUnitTest {

    @InjectMocks
    private AgentWorkflowDefServiceImpl defService;

    @Mock
    private AgentWorkflowDefMapper defMapper;

    /** publishDef 不碰触发器提取器，但 @InjectMocks 需要它被满足 */
    @Mock
    private WorkflowTriggerExtractor triggerExtractor;

    /** 增量12 / slice-3c 批次 B2：发布 chat 流程时反查 trigger.chat.agentId 指向的 Agent 是否存在 */
    @Mock
    private AgentDefMapper agentDefMapper;

    /** S4b 发布闸门的三件套 + 清单生成器；只在 {@link #stubGatePasses()} 里被驱动 */
    @Mock
    private DslParser dslParser;
    @Mock
    private WorkflowDslValidator dslValidator;
    @Mock
    private DslToStateGraphCompiler dslCompiler;
    @Mock
    private WorkflowChangeManifestBuilder manifestBuilder;

    /** 执行史取数口（台账 #81）：本类一律让闸门放行，故它默认返回空 = 「没跑过」= 不降档 */
    @Mock
    private AgentWorkflowExecutionMapper executionMapper;

    /**
     * 让闸门放行：基础层与深层都返回「无 error 的干净结果」。
     *
     * <p>{@code manifestBuilder.build} 不 stub —— mock 默认返回 null，正好等价于「清单不可用」，
     * 于是 {@code foldDryRunRejections(null, …)} 自己早退，不必再 stub 那两个 void 方法。
     */
    private void stubGatePasses() {
        when(dslParser.parse(anyString())).thenReturn(new WorkflowDefDTO());
        when(dslValidator.validate(any(WorkflowDefDTO.class))).thenReturn(new AgentValidateResult());
        when(dslCompiler.dryRunValidate(any(WorkflowDefDTO.class), any())).thenReturn(new AgentValidateResult());
    }

    private static AgentWorkflowDefDO def(Long id, String code, Integer version, String status) {
        AgentWorkflowDefDO d = new AgentWorkflowDefDO();
        d.setId(id);
        d.setCode(code);
        d.setVersion(version);
        d.setStatus(status);
        d.setDsl("{\"id\":\"" + code + "\"}");
        return d;
    }

    // ==================== ① DRAFT → PUBLISHED ====================

    @Test
    void publishDef_draftToPublished_updatesStatusOnly() {
        when(defMapper.selectLatestByCode("wf_plan_x"))
                .thenReturn(def(100L, "wf_plan_x", 1, WorkflowDefStatusEnum.DRAFT.getStatus()));
        stubGatePasses();

        Long id = defService.publishDef("wf_plan_x");

        assertEquals(100L, id);
        ArgumentCaptor<AgentWorkflowDefDO> captor = ArgumentCaptor.forClass(AgentWorkflowDefDO.class);
        verify(defMapper).updateById(captor.capture());
        AgentWorkflowDefDO updated = captor.getValue();
        assertEquals(100L, updated.getId(), "只带 id 定位那一行");
        assertEquals(WorkflowDefStatusEnum.PUBLISHED.getStatus(), updated.getStatus(), "status 翻成 PUBLISHED");
        // 🔴 部分更新：dsl / version / code / 触发投影列都不该被带进 update（否则可能覆盖）
        assertEquals(null, updated.getDsl(), "dsl 不参与更新，保持原样");
        assertEquals(null, updated.getVersion(), "version 不参与更新");
        assertEquals(null, updated.getCode(), "code 不参与更新");
    }

    // ==================== ② 已 PUBLISHED：幂等 ====================

    @Test
    void publishDef_alreadyPublished_idempotentNoUpdate() {
        when(defMapper.selectLatestByCode("wf_plan_x"))
                .thenReturn(def(100L, "wf_plan_x", 2, WorkflowDefStatusEnum.PUBLISHED.getStatus()));

        Long id = defService.publishDef("wf_plan_x");

        assertEquals(100L, id, "幂等返回原 id");
        verify(defMapper, never()).updateById(any(AgentWorkflowDefDO.class));
    }

    // ==================== ③ 不存在：抛错 ====================

    @Test
    void publishDef_notExists_throws() {
        when(defMapper.selectLatestByCode("ghost")).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.publishDef("ghost"));
        assertEquals(WORKFLOW_DEF_NOT_EXISTS.getCode(), ex.getCode());
        verify(defMapper, never()).updateById(any(AgentWorkflowDefDO.class));
    }

    // ==================== ④ DISABLED：与不存在同文案 ====================

    @Test
    void publishDef_disabled_throws() {
        when(defMapper.selectLatestByCode("wf_plan_x"))
                .thenReturn(def(100L, "wf_plan_x", 3, WorkflowDefStatusEnum.DISABLED.getStatus()));

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.publishDef("wf_plan_x"));
        assertEquals(WORKFLOW_DEF_NOT_EXISTS.getCode(), ex.getCode());
        verify(defMapper, never()).updateById(any(AgentWorkflowDefDO.class));
    }

    // ==================== B2：chat 触发器发布期 fail-close 校验 agentId 存在性 ====================

    private static AgentWorkflowDefDO chatDef(Long id, String code, String status, String triggerKey) {
        AgentWorkflowDefDO d = def(id, code, 1, status);
        d.setTriggerType("chat");
        d.setTriggerKey(triggerKey);
        return d;
    }

    @Test
    void publishDef_chatTrigger_agentExists_publishes() {
        when(defMapper.selectLatestByCode("wf_chat"))
                .thenReturn(chatDef(100L, "wf_chat", WorkflowDefStatusEnum.DRAFT.getStatus(), "agent_x"));
        when(agentDefMapper.existsByCode("agent_x")).thenReturn(true);
        stubGatePasses();

        Long id = defService.publishDef("wf_chat");

        assertEquals(100L, id);
        verify(defMapper).updateById(any(AgentWorkflowDefDO.class));
    }

    @Test
    void publishDef_chatTrigger_agentMissing_throws() {
        when(defMapper.selectLatestByCode("wf_chat"))
                .thenReturn(chatDef(100L, "wf_chat", WorkflowDefStatusEnum.DRAFT.getStatus(), "ghost_agent"));
        when(agentDefMapper.existsByCode("ghost_agent")).thenReturn(false);

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.publishDef("wf_chat"));
        assertEquals(WORKFLOW_TRIGGER_CHAT_AGENT_NOT_FOUND.getCode(), ex.getCode(),
                "🔴 绑一个不存在的 Agent code 会让聊天反查永不命中、流程静躺不触发（配了不生效且无提示）→ 发布期挡住");
        verify(defMapper, never()).updateById(any(AgentWorkflowDefDO.class));
    }

    @Test
    void publishDefById_chatTrigger_agentMissing_throws() {
        when(defMapper.selectById(100L))
                .thenReturn(chatDef(100L, "wf_chat", WorkflowDefStatusEnum.DRAFT.getStatus(), "ghost_agent"));
        when(agentDefMapper.existsByCode("ghost_agent")).thenReturn(false);

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.publishDefById(100L));
        assertEquals(WORKFLOW_TRIGGER_CHAT_AGENT_NOT_FOUND.getCode(), ex.getCode());
        verify(defMapper, never()).updateById(any(AgentWorkflowDefDO.class));
    }

    @Test
    void publishDef_nonChatTrigger_skipsAgentValidation() {
        AgentWorkflowDefDO formEvent = def(100L, "wf_form", 1, WorkflowDefStatusEnum.DRAFT.getStatus());
        formEvent.setTriggerType("form_event");
        formEvent.setTriggerKey("order_form");
        when(defMapper.selectLatestByCode("wf_form")).thenReturn(formEvent);
        stubGatePasses();

        defService.publishDef("wf_form");

        verify(agentDefMapper, never()).existsByCode(anyString());
        verify(defMapper).updateById(any(AgentWorkflowDefDO.class));
    }

    // ==================== B6 / S16：发布 ≠ 生效 ====================

    /**
     * 「生效版本」今天是个**隐式回落**：{@code selectPublishedByCode} 排序
     * {@code primary_flag DESC, version DESC}，没人钉主版本时退化成「version 最大」。
     * 于是发布新版 = 立刻切线上。B6 的 (a) 方案把首个发布版**显式钉住**，此后发布不再动线上。
     *
     * <p>🔴 这一小节同时钉住那条**刻意的不对称**：管理页（{@code publishDefById}）发布不抢线上，
     * 聊天「确认并运行」（{@code publishDef(code)}）发布即生效 —— 后者由
     * {@code AgentWorkflowPlannerController} 显式调 {@code setPrimaryVersion} 完成，
     * 因为 {@code run-stream} 只吃 code、不吃版本号，那里若不生效，跑的就是上一版。
     *
     * <p>⚠️ 本小节有一条**够不着**的判据，写在这里免得后人以为已覆盖：
     * {@code selectPrimaryByCode} 带 {@code status = PUBLISHED} 过滤（因为 {@code disableDefById}
     * 只翻状态、不清 primary_flag，「主版本被停用」是正常操作能走到的状态）。这条过滤是
     * Mapper 里的 SQL，而本类是纯 mock —— {@code selectPrimaryByCode} 被 stub 成 null 时，
     * 「从来没钉过」与「钉过但那版已停用」在 mock 眼里**是同一个输入**，服务层的反应也确实应当相同
     * （都补钉），所以下面第一条用例覆盖的是这个反应，**不是**那条 SQL 过滤本身。
     * agent 模块整体没有 {@code BaseDbUnitTest} 先例，为一个查询方法引入 H2 基建不划算，
     * 故那条过滤目前只由代码评审保证（前端 {@code index.vue} 的 blocker 判定也带同样的
     * {@code status === 'PUBLISHED'} 条件，两侧口径一致）。
     */
    @Test
    void publishDefById_noPrimaryYet_pinsTheJustPublishedVersion() {
        when(defMapper.selectById(200L))
                .thenReturn(def(200L, "wf_b6", 1, WorkflowDefStatusEnum.DRAFT.getStatus()));
        when(defMapper.selectPrimaryByCode("wf_b6")).thenReturn(null);
        stubGatePasses();

        Long id = defService.publishDefById(200L);

        assertEquals(200L, id);
        ArgumentCaptor<AgentWorkflowDefDO> captor = ArgumentCaptor.forClass(AgentWorkflowDefDO.class);
        verify(defMapper, times(2)).updateById(captor.capture());
        // 🔴 按**内容**认领这两笔，不按下标：写的顺序是内部实现细节，按下标断言的话
        //    哪天调成「先钉后翻状态」，用例照样绿、却已经在测另一件事了。
        //    认领不到时用 AssertionError 而不是让它 NPE：NPE 只会指向下面某一行的解引用，
        //    真因（「发布压根没写 status」）反倒看不出来。
        List<AgentWorkflowDefDO> writes = captor.getAllValues();
        AgentWorkflowDefDO statusWrite = writes.stream()
                .filter(u -> u.getStatus() != null).findFirst()
                .orElseThrow(() -> new AssertionError("两笔写里没有带 status 的那笔，发布没翻状态"));
        AgentWorkflowDefDO primaryWrite = writes.stream()
                .filter(u -> u.getPrimaryFlag() != null).findFirst()
                .orElseThrow(() -> new AssertionError("两笔写里没有带 primary_flag 的那笔，无主版本时没补钉"));
        // 下面两条「各不带对方那一列」同时证明了这是**两笔独立的写**：
        // 合并成一笔（status + primary_flag 一起 update）会让「发布」和「生效」在数据层重新黏回一起，
        // 也正是本刀要拆开的那个东西。
        assertEquals(WorkflowDefStatusEnum.PUBLISHED.getStatus(), statusWrite.getStatus());
        assertNull(statusWrite.getPrimaryFlag(), "翻状态那笔绝不顺手带 primary_flag");
        assertTrue(primaryWrite.getPrimaryFlag(), "无主版本 ⟹ 刚发布这版被钉为主版本");
        assertEquals(200L, primaryWrite.getId());
        assertNull(primaryWrite.getStatus(), "钉主版本那笔绝不顺手带 status");
        assertNull(primaryWrite.getDsl(), "只动 primary_flag，已发布版本是不可变快照");
        assertNull(primaryWrite.getVersion(), "只动 primary_flag");
        assertNull(primaryWrite.getCode(), "只动 primary_flag");
        verify(defMapper).clearPrimaryFlagByCode("wf_b6");
    }

    @Test
    void publishDefById_existingPrimary_publishDoesNotMoveLiveVersion() {
        // 方案 §8.6 要的那条断言：「发布 v2 不设 primary → 线上仍是 v1」。
        // 🔴 注意这条断言在 B6 之前的代码下**也**成立（发布本来就不写 primary_flag），
        //    所以它钉的是「别把 B6 做反了」—— 真正的行为变化在下一条用例
        //    （无主版本时首发会被钉住，从而此后发布不再切线上）。
        when(defMapper.selectById(201L))
                .thenReturn(def(201L, "wf_b6", 2, WorkflowDefStatusEnum.DRAFT.getStatus()));
        when(defMapper.selectPrimaryByCode("wf_b6"))
                .thenReturn(def(200L, "wf_b6", 1, WorkflowDefStatusEnum.PUBLISHED.getStatus()));
        stubGatePasses();

        defService.publishDefById(201L);

        ArgumentCaptor<AgentWorkflowDefDO> captor = ArgumentCaptor.forClass(AgentWorkflowDefDO.class);
        verify(defMapper).updateById(captor.capture());
        AgentWorkflowDefDO updated = captor.getValue();
        assertEquals(201L, updated.getId(), "v2 确实发布了（状态翻了）");
        assertEquals(WorkflowDefStatusEnum.PUBLISHED.getStatus(), updated.getStatus());
        assertNull(updated.getPrimaryFlag(), "已有主版本 ⟹ 发布 v2 绝不抢线上，生效版本仍是 v1");
        verify(defMapper, never()).clearPrimaryFlagByCode(anyString());
    }

    @Test
    void publishDef_byCode_neverPinsPrimary() {
        // 🔴 与 publishDefById **刻意不对称**。这条是聊天/生成端点走的路，
        //    「发布即生效」由 AgentWorkflowPlannerController 拿到 defId 后显式调 setPrimaryVersion 完成。
        //    服务层这个方法必须保持本类 javadoc 里那句「publishDef 只翻状态」的老契约，
        //    否则两边都钉，Controller 那一笔就变成重复写、还会把「谁决定生效」这件事拆到两处。
        when(defMapper.selectLatestByCode("wf_plan_x"))
                .thenReturn(def(100L, "wf_plan_x", 1, WorkflowDefStatusEnum.DRAFT.getStatus()));
        stubGatePasses();

        defService.publishDef("wf_plan_x");

        verify(defMapper, never()).selectPrimaryByCode(anyString());
        verify(defMapper, never()).clearPrimaryFlagByCode(anyString());
        ArgumentCaptor<AgentWorkflowDefDO> captor = ArgumentCaptor.forClass(AgentWorkflowDefDO.class);
        verify(defMapper).updateById(captor.capture());
        assertNull(captor.getValue().getPrimaryFlag(), "publishDef(code) 只翻状态，钉不钉主版本由调用方决定");
    }

    @Test
    void publishDefById_alreadyPublished_doesNotPinPrimaryEither() {
        when(defMapper.selectById(202L))
                .thenReturn(def(202L, "wf_b6", 3, WorkflowDefStatusEnum.PUBLISHED.getStatus()));

        defService.publishDefById(202L);

        // 幂等返回不该顺手改线上生效版本：重复点「发布」是个近似只读的动作，
        // 若它也补钉主版本，运维为灰度回退特意钉住的旧版会被一次误点踢开。
        verify(defMapper, never()).selectPrimaryByCode(anyString());
        verify(defMapper, never()).updateById(any(AgentWorkflowDefDO.class));
    }

}
