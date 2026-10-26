package cn.enlev.ainb.module.agent.service.tooldef;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.tenant.core.context.TenantContextHolder;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.controller.admin.tooldef.vo.AgentRegisteredToolRespVO;
import cn.enlev.ainb.module.agent.controller.admin.tooldef.vo.AgentRegisteredToolUpdateReqVO;
import cn.enlev.ainb.module.agent.dal.dataobject.tooldef.AgentToolOverrideAuditDO;
import cn.enlev.ainb.module.agent.dal.dataobject.tooldef.AgentToolOverrideDO;
import cn.enlev.ainb.module.agent.dal.mysql.tooldef.AgentToolOverrideAuditMapper;
import cn.enlev.ainb.module.agent.dal.mysql.tooldef.AgentToolOverrideMapper;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolRegistry;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;

import java.util.List;

import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_TOOL_OVERRIDE_RELAX_NEEDS_CONFIRM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 内置工具在线维护的单元测试：B1 / S11 的「安全放松」处置（2026-10-05）+ D4-23 的「同名多行」收敛
 *
 * <p>方案 §8.1 要的验证是<b>双向三条</b>：放松要留痕、不放松不留痕、放松但被拒不留痕。
 * 三条缺一不可，而且第三条最关键 ——
 *
 * <h3>🔴 为什么必须有「被拒不留痕」这一条</h3>
 * 只测前两条的话，一个把审计插入写在 upsert <b>之前</b>、或者干脆写在放松判定<b>之前</b>的实现同样全绿，
 * 而它会产出一行「记录了某次放松」的审计，那次放松其实<b>被拒了、根本没生效</b>。
 * 事后复核的人照着这行去查线上配置，会发现配置是好的，于是怀疑审计造假 ——
 * 一条对不上现实的审计比没有审计更坏，因为它把「审计不可信」这个结论也一起写进去了。
 * 所以这条断言真正钉的是<b>顺序</b>：判定 → upsert → 审计，且判定失败时一个字都不落。
 *
 * <h3>🔴 为什么「不放松」要拆成五个子形状</h3>
 * 「没放松」不是一种情况。风险<b>调高</b>、权限<b>加多</b>、权限<b>只换顺序</b>、风险<b>原样重填</b>、
 * 风险<b>留空</b>（= 不覆盖）都要不落痕。其中「只换顺序」是判据写错时最容易漏的一个：
 * 按「字符串变短了」判会把 {@code a,b}→{@code b,a} 误判成放松，
 * 于是管理员改个展示名也被要求确认一次「你正在放松安全约束」——
 * 弹几次假的之后，真的那次他就直接点确定不看字了，二次确认当场失去意义。
 *
 * @author 走进新时代
 */
public class AgentToolOverrideServiceImplTest extends BaseMockitoUnitTest {

    /** 一个不可逆工具：降它的风险、删它的权限，正是 B1 要留痕的那个场景 */
    private static final String IRREVERSIBLE_TOOL = "demo_irreversible";
    private static final String PERM_RUN = "demo:irreversible:run";
    private static final String PERM_AUDIT = "demo:irreversible:audit";
    /** 一个只读工具：用它验「调高风险不算放松」与「READ_ONLY 已无更低档」 */
    private static final String READONLY_TOOL = "demo_readonly";
    private static final String PERM_QUERY = "demo:readonly:query";

    private static final Long TENANT = 1L;

    @AgentTool(name = IRREVERSIBLE_TOOL, displayName = "演示不可逆工具", groupName = "演示",
            permission = PERM_RUN, extraPermissions = {PERM_AUDIT}, risk = RiskLevel.IRREVERSIBLE)
    static class IrreversibleTool {
    }

    @AgentTool(name = READONLY_TOOL, displayName = "演示只读工具", groupName = "演示",
            permission = PERM_QUERY, risk = RiskLevel.READ_ONLY)
    static class ReadOnlyTool {
    }

    @InjectMocks
    private AgentToolOverrideServiceImpl service;

    @Mock
    private AgentToolOverrideMapper overrideMapper;
    @Mock
    private AgentToolOverrideAuditMapper overrideAuditMapper;

    /**
     * 真注册表，不是 mock。
     *
     * <p>🔴 放松判定读的是 {@code getDeclaredRisk()} / {@code getDeclaredPermissions()}，
     * 这两个值来自 {@code @AgentTool} 注解经 {@code ToolDefinition} 构造器的装配
     * （权限是 {@code permission} + {@code extraPermissions} 去重合并）。mock 掉注册表就等于
     * 自己编一份「声明值」去验自己编的判据 —— 注解怎么合并成列表这段真逻辑一点没测到。
     * 与 {@code AgentToolRegistryOverrideTest} 同一套夹具思路。
     */
    @Spy
    private AgentToolRegistry toolRegistry = new AgentToolRegistry();

    @BeforeEach
    public void setUp() {
        toolRegistry.register(IrreversibleTool.class);
        toolRegistry.register(ReadOnlyTool.class);
        TenantContextHolder.setTenantId(TENANT);
        // 🔴 手工跑一次 @PostConstruct：纯 Mockito 不起 Spring 容器，注解方法不会被调用。
        //    不注册懒加载器的话，注册表读到缓存未命中就直接落一份【空】快照（见 AgentToolRegistry
        //    #loadTenantOverrides 的 loader==null 分支），于是 listMerged 里的生效值一律退回声明值 ——
        //    「同名多行时生效的是哪一行」这条断言会红在一个与 D4-23 无关的地方，看起来像覆盖没生效。
        service.registerOverrideLoader();
        // update() 结尾会重建当前租户的覆盖快照；本测试不关心缓存内容，给个空表即可
        lenient().when(overrideMapper.selectListAll()).thenReturn(List.of());
    }

    @AfterEach
    public void tearDown() {
        TenantContextHolder.clear();
    }

    // ==================== 第一条：放松要留痕 ====================

    @Test
    @DisplayName("降风险 + 删空权限，且已二次确认 ⟹ 落一行审计，逐列记下从什么变成了什么")
    void update_relaxedAndConfirmed_writesAuditRow() {
        AgentRegisteredToolUpdateReqVO req = req(IRREVERSIBLE_TOOL);
        req.setRiskLevel(RiskLevel.READ_ONLY.name());
        req.setPermissions("");
        req.setConfirmRelax(true);
        req.setRemark("本租户不开放建表，降档以便自助排查");

        service.update(req);

        ArgumentCaptor<AgentToolOverrideAuditDO> captor =
                ArgumentCaptor.forClass(AgentToolOverrideAuditDO.class);
        verify(overrideAuditMapper).insert(captor.capture());
        AgentToolOverrideAuditDO audit = captor.getValue();

        assertEquals(IRREVERSIBLE_TOOL, audit.getToolName());
        assertEquals(RiskLevel.IRREVERSIBLE.getLevel(), audit.getRiskFrom());
        assertEquals(RiskLevel.READ_ONLY.getLevel(), audit.getRiskTo());
        // 🔴 这两列是审计行最容易被省掉的：只有等级名的话，读的人得自己去查 RiskLevel 才知道
        //    「IRREVERSIBLE→READ_ONLY」意味着确认要求从有到无。后果要写在脸上，不能让人查表。
        assertEquals(Boolean.TRUE, audit.getRequireConfirmFrom());
        assertEquals(Boolean.FALSE, audit.getRequireConfirmTo());
        assertEquals(PERM_RUN + "," + PERM_AUDIT, audit.getPermissionsFrom(),
                "声明权限应是 permission + extraPermissions 合并后的两个，顺序跟注解一致");
        assertEquals("", audit.getPermissionsTo());
        assertEquals("本租户不开放建表，降档以便自助排查", audit.getRemark(),
                "备注要【抄】进审计行：覆盖行的 remark 会被下次维护整份重写，审计行必须自带当时的理由");

        // 🔴 单测里没有登录上下文 ⟹ 拿不到操作人。这恰好验住了那条设计取舍：
        //    取不到名字【照样落行】，而不是因为拿不到操作人就把这次放松漏记掉。
        assertNull(audit.getOperatorId());
        assertEquals("", audit.getOperatorName());

        // 覆盖行本身也要真的落库（放松被确认 ⟹ 生效）
        verify(overrideMapper).insert(any(AgentToolOverrideDO.class));
    }

    @Test
    @DisplayName("只删权限、风险原样 ⟹ 也算放松，且审计行的风险四列全为空（没放松的维度不编造内容）")
    void update_onlyPermissionsRelaxed_leavesRiskColumnsEmpty() {
        AgentRegisteredToolUpdateReqVO req = req(IRREVERSIBLE_TOOL);
        req.setRiskLevel(RiskLevel.IRREVERSIBLE.name());
        req.setPermissions(PERM_RUN); // 少了 PERM_AUDIT
        req.setConfirmRelax(true);

        service.update(req);

        ArgumentCaptor<AgentToolOverrideAuditDO> captor =
                ArgumentCaptor.forClass(AgentToolOverrideAuditDO.class);
        verify(overrideAuditMapper).insert(captor.capture());
        AgentToolOverrideAuditDO audit = captor.getValue();

        assertEquals("", audit.getRiskFrom());
        assertEquals("", audit.getRiskTo());
        assertNull(audit.getRequireConfirmFrom());
        assertNull(audit.getRequireConfirmTo());
        assertEquals(PERM_RUN + "," + PERM_AUDIT, audit.getPermissionsFrom());
        assertEquals(PERM_RUN, audit.getPermissionsTo());
    }

    // ==================== 第二条：放松但被拒 ⟹ 不留痕（🔴 钉的是顺序） ====================

    @Test
    @DisplayName("放松却没带确认 ⟹ 抛异常，且覆盖行与审计行【都不写】")
    void update_relaxedWithoutConfirm_throwsAndWritesNothing() {
        AgentRegisteredToolUpdateReqVO req = req(IRREVERSIBLE_TOOL);
        req.setRiskLevel(RiskLevel.READ_ONLY.name());
        req.setPermissions("");
        req.setConfirmRelax(null); // 前端没带这个字段（旧版前端 / curl / 批量脚本）

        ServiceException ex = assertThrows(ServiceException.class, () -> service.update(req));
        assertEquals(AGENT_TOOL_OVERRIDE_RELAX_NEEDS_CONFIRM.getCode(), ex.getCode());

        verify(overrideAuditMapper, never()).insert(any(AgentToolOverrideAuditDO.class));
        verify(overrideMapper, never()).insert(any(AgentToolOverrideDO.class));
        verify(overrideMapper, never()).updateById(any(AgentToolOverrideDO.class));
    }

    @Test
    @DisplayName("显式传 confirmRelax=false 同样被拒 —— 不能只认 null")
    void update_relaxedWithConfirmFalse_throws() {
        AgentRegisteredToolUpdateReqVO req = req(IRREVERSIBLE_TOOL);
        req.setRiskLevel(RiskLevel.LOW.name());
        req.setPermissions(PERM_RUN + "," + PERM_AUDIT);
        req.setConfirmRelax(false);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.update(req));
        assertEquals(AGENT_TOOL_OVERRIDE_RELAX_NEEDS_CONFIRM.getCode(), ex.getCode());
        verify(overrideAuditMapper, never()).insert(any(AgentToolOverrideAuditDO.class));
        verify(overrideMapper, never()).insert(any(AgentToolOverrideDO.class));
    }

    @Test
    @DisplayName("🔴 报错文案必须说清代价，不是「请确认」—— 两个维度各说各的真后果")
    void update_relaxRejected_messageStatesTheActualCost() {
        AgentRegisteredToolUpdateReqVO req = req(IRREVERSIBLE_TOOL);
        req.setRiskLevel(RiskLevel.READ_ONLY.name());
        req.setPermissions(PERM_RUN); // 少了 PERM_AUDIT
        req.setConfirmRelax(null);

        String msg = assertThrows(ServiceException.class, () -> service.update(req)).getMessage();

        // 风险维度：要有前后等级，且要点明「不会绕过判定」这个反直觉的事实（台账 D4-22）
        assertTrue(msg.contains("IRREVERSIBLE→READ_ONLY"), "文案里没有风险的前后值：" + msg);
        assertTrue(msg.contains("声明"),
                "文案没提「运行期闸读的是声明值」—— 不说这句，管理员会按界面旧口径以为自己放开了确认：" + msg);
        // 权限维度：要点名少的是哪个权限点，且说清这是【立即生效】的真放松
        assertTrue(msg.contains(PERM_AUDIT), "文案没点名丢掉的权限点，管理员无从判断影响面：" + msg);
        assertTrue(msg.contains("立即生效"), "文案没说权限这一条会立刻生效：" + msg);
        assertTrue(msg.contains(IRREVERSIBLE_TOOL), "文案没点名是哪个工具：" + msg);
    }

    // ==================== 第三条：不放松 ⟹ 不留痕（五个子形状） ====================

    @Test
    @DisplayName("只改展示名/说明，风险与权限原样 ⟹ 不留痕、也不要求确认")
    void update_cosmeticOnly_writesNoAuditRow() {
        AgentRegisteredToolUpdateReqVO req = req(IRREVERSIBLE_TOOL);
        req.setDisplayName("建表演示工具（改个名）");
        req.setDescription("换个说法");
        req.setRiskLevel(RiskLevel.IRREVERSIBLE.name());
        req.setPermissions(PERM_RUN + "," + PERM_AUDIT);
        req.setConfirmRelax(null); // 🔴 不带确认也必须过得去，否则每次改名字都弹一次「你在放松安全约束」

        service.update(req);

        verify(overrideAuditMapper, never()).insert(any(AgentToolOverrideAuditDO.class));
        verify(overrideMapper).insert(any(AgentToolOverrideDO.class));
    }

    @Test
    @DisplayName("把风险【调高】⟹ 不是放松，不留痕")
    void update_riskRaised_writesNoAuditRow() {
        AgentRegisteredToolUpdateReqVO req = req(READONLY_TOOL);
        req.setRiskLevel(RiskLevel.HIGH.name()); // READ_ONLY → HIGH，收紧
        req.setPermissions(PERM_QUERY);

        service.update(req);

        verify(overrideAuditMapper, never()).insert(any(AgentToolOverrideAuditDO.class));
    }

    @Test
    @DisplayName("🔴 权限【只换顺序】⟹ 不是放松（按「字符串变短」判会在这里误报）")
    void update_permissionsReordered_writesNoAuditRow() {
        AgentRegisteredToolUpdateReqVO req = req(IRREVERSIBLE_TOOL);
        req.setRiskLevel(RiskLevel.IRREVERSIBLE.name());
        req.setPermissions(PERM_AUDIT + ", " + PERM_RUN); // 反序 + 带空格

        service.update(req);

        verify(overrideAuditMapper, never()).insert(any(AgentToolOverrideAuditDO.class));
    }

    @Test
    @DisplayName("权限【加多】⟹ 不是放松，即便风险同时留空")
    void update_permissionsAdded_writesNoAuditRow() {
        AgentRegisteredToolUpdateReqVO req = req(IRREVERSIBLE_TOOL);
        req.setRiskLevel(""); // 留空 = 不覆盖风险，沿用声明
        req.setPermissions(PERM_RUN + "," + PERM_AUDIT + ",demo:extra:grant");

        service.update(req);

        verify(overrideAuditMapper, never()).insert(any(AgentToolOverrideAuditDO.class));
    }

    @Test
    @DisplayName("风险留空 = 不覆盖 ⟹ 不能被当成「降到了空档」而误判放松")
    void update_riskBlankMeansNoOverride_writesNoAuditRow() {
        AgentRegisteredToolUpdateReqVO req = req(READONLY_TOOL);
        req.setRiskLevel("");
        req.setPermissions(PERM_QUERY);

        service.update(req);

        verify(overrideAuditMapper, never()).insert(any(AgentToolOverrideAuditDO.class));
        // 落库的 riskLevel 必须是空串（= 不覆盖），而不是某个具体档位
        ArgumentCaptor<AgentToolOverrideDO> captor = ArgumentCaptor.forClass(AgentToolOverrideDO.class);
        verify(overrideMapper).insert(captor.capture());
        assertEquals("", captor.getValue().getRiskLevel());
    }

    // ==================== 判据本身的两个方向性探针 ====================

    @Test
    @DisplayName("🔴 比的是 severity 不是 ordinal：低于声明的每一档都算放松，等于或高于的一档都不算")
    void update_relaxationBoundaryFollowsSeverityNotOrdinal() {
        RiskLevel declared = RiskLevel.IRREVERSIBLE;
        for (RiskLevel candidate : RiskLevel.values()) {
            AgentRegisteredToolUpdateReqVO req = req(IRREVERSIBLE_TOOL);
            req.setRiskLevel(candidate.name());
            req.setPermissions(PERM_RUN + "," + PERM_AUDIT); // 权限维度不掺进来，只验风险
            req.setConfirmRelax(true);

            service.update(req);

            boolean expectAuditRow = candidate.getSeverity() < declared.getSeverity();
            assertEquals(expectAuditRow ? 1 : 0, countAuditInserts(),
                    "风险 " + declared + "→" + candidate + " 的放松判定不对（severity "
                            + declared.getSeverity() + "→" + candidate.getSeverity() + "）");
            // 🔴 每轮清一次调用记录，否则上一轮的 insert 会被这一轮数到，
            //    表现是「第一档之后就全是 1」—— 一个把所有档位都判成放松的实现反而全绿
            clearInvocations(overrideAuditMapper, overrideMapper);
        }
    }

    @Test
    @DisplayName("READ_ONLY 已是最低档 ⟹ 无论怎么填都不可能「降风险」")
    void update_readOnlyToolCanNeverRelaxRisk() {
        for (RiskLevel candidate : RiskLevel.values()) {
            AgentRegisteredToolUpdateReqVO req = req(READONLY_TOOL);
            req.setRiskLevel(candidate.name());
            req.setPermissions(PERM_QUERY);
            req.setConfirmRelax(null); // 🔴 不带确认：一旦误判成放松就会抛，正好当作断言用

            service.update(req);
            verify(overrideAuditMapper, never()).insert(any(AgentToolOverrideAuditDO.class));
            clearInvocations(overrideAuditMapper, overrideMapper);
        }
    }

    // ==================== D4-23：同名多行 live 的收敛与自愈 ====================

    /**
     * 🔴 这一小节钉的是「多行时哪一行算数」，而<b>不是</b>「多行不该发生」。
     *
     * <p>多行拦不住：{@code agent_tool_override} 上建不了唯一索引（软删墓碑会占着同一个
     * {@code (tenant_id, tool_name)}，实测 {@code datamodel_create} 名下已积 28 行墓碑），
     * 于是并发保存能各自读到「还没有覆盖行」而双双 insert。
     *
     * <p>修复前这里不是「多一行脏数据」那么轻：旧实现用 {@code selectOne} 读，多行时<b>永久</b>抛
     * {@code TooManyResultsException}，而 {@code update} 与 {@code reset} 都走它 ——
     * 「保存」坏了，界面上「恢复默认」那个逃生门也一起坏，管理员在 UI 里无路可走，只能改库。
     *
     * <p>🔴 最容易被漏掉、也最要紧的是<b>口径一致性</b>：读快照与自愈必须保留<b>同一条</b>。
     * 两边相反的话，「自愈」这个动作本身就会改掉工具的生效配置 —— 没人做过一次维护，
     * 工具行为却变了，而且没有异常、没有红测试。{@link #readSnapshotAndHealAgreeOnWhichRowWins}
     * 就是为了把这条不变量钉住。
     */

    @Test
    @DisplayName("🔴 同名两行 live ⟹ 保留最新那行、软删较旧那行，upsert 落在留下的那行上")
    void update_duplicateLiveRows_keepsNewestAndSoftDeletesTheRest() {
        // selectListByToolName 已按「最新在前」排序，故 id=20 排在 id=10 之前
        when(overrideMapper.selectListByToolName(IRREVERSIBLE_TOOL))
                .thenReturn(List.of(row(20L, "最新那行"), row(10L, "较旧那行")));

        service.update(noRelaxReq());

        // 留下的是最新那行：走 updateById。走 insert 的话等于又造一行，把病灶扩大成三行
        ArgumentCaptor<AgentToolOverrideDO> saved = ArgumentCaptor.forClass(AgentToolOverrideDO.class);
        verify(overrideMapper).updateById(saved.capture());
        verify(overrideMapper, never()).insert(any(AgentToolOverrideDO.class));
        assertEquals(Long.valueOf(20L), saved.getValue().getId(), "upsert 应落在【最新】那行上");

        // 较旧那行被删（deleteById 经 @TableLogic 就是软删）；留下的那行绝不能被删
        ArgumentCaptor<Long> deleted = ArgumentCaptor.forClass(Long.class);
        verify(overrideMapper, times(1)).deleteById(deleted.capture());
        assertEquals(List.of(10L), deleted.getAllValues(), "该软删的是较旧那行，且只删这一行");
    }

    @Test
    @DisplayName("只有一行 live ⟹ 一行都不删（自愈不许在正常路径上误伤）")
    void update_singleLiveRow_deletesNothing() {
        when(overrideMapper.selectListByToolName(IRREVERSIBLE_TOOL)).thenReturn(List.of(row(20L, "唯一那行")));

        service.update(noRelaxReq());

        assertEquals(0, countDeletes(), "只有一行 live 时不该删任何行");
        verify(overrideMapper).updateById(any(AgentToolOverrideDO.class));
    }

    @Test
    @DisplayName("🔴 reset 把同名的 live 行【全删】：只删一行的话工具仍被覆盖，而界面已提示恢复默认成功")
    void reset_duplicateLiveRows_deletesEveryRow() {
        when(overrideMapper.selectListByToolName(IRREVERSIBLE_TOOL))
                .thenReturn(List.of(row(20L, "最新那行"), row(10L, "较旧那行")));

        service.reset(IRREVERSIBLE_TOOL);

        ArgumentCaptor<Long> deleted = ArgumentCaptor.forClass(Long.class);
        verify(overrideMapper, times(2)).deleteById(deleted.capture());
        assertEquals(List.of(20L, 10L), deleted.getAllValues(),
                "reset 的语义是回到代码声明的默认值，留任何一行都等于没 reset");
    }

    @Test
    @DisplayName("没有覆盖行时 reset 仍重建一次缓存（幂等，别退化成「查不到就啥也不做」）")
    void reset_noOverrideRow_stillRefreshesTenantCache() {
        service.reset(IRREVERSIBLE_TOOL);

        assertEquals(0, countDeletes());
        verify(toolRegistry).refreshTenantOverrides(eq(TENANT), any());
    }

    @Test
    @DisplayName("🔴 读快照与自愈必须认同一条：同名两行时【生效】的是最新那行的值")
    void readSnapshotAndHealAgreeOnWhichRowWins() {
        // selectListAll 与 selectListByToolName 都是「最新在前」，故 id=20 排在 id=10 之前
        when(overrideMapper.selectListAll())
                .thenReturn(List.of(row(20L, "最新那行"), row(10L, "最旧那行")));

        service.reset(IRREVERSIBLE_TOOL); // reset 结尾必然重建一次当前租户缓存

        // 断言的是【可观察结果】（注册表里的生效展示名），不是中间那个 Map：
        // 网关、提示词、管理页读的都是这个生效值，它才是「哪一行算数」的最终口径。
        assertEquals("最新那行", toolRegistry.get(IRREVERSIBLE_TOOL).getDisplayName(),
                "同名两行时生效的必须是【最新】那行 —— 与 healDuplicateOverrides 保留的是同一条。"
                        + "若这里生效的是最旧那行，那么自愈会顺手改掉工具的生效配置");
    }

    @Test
    @DisplayName("管理页合并视图：同名两行时回显的覆盖行元数据也取自【最新】那行，不与生效值分家")
    void listMerged_duplicateRows_metadataComesFromTheNewestRow() {
        AgentToolOverrideDO newest = row(20L, "最新那行");
        when(overrideMapper.selectListAll()).thenReturn(List.of(newest, row(10L, "最旧那行")));

        AgentRegisteredToolRespVO vo = service.listMerged().stream()
                .filter(v -> IRREVERSIBLE_TOOL.equals(v.getToolName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("合并视图里应有 " + IRREVERSIBLE_TOOL));

        // 🔴 overrideId 取自 (a, b) -> a 即「先出现的赢」，与「最新在前」的排序配套才是最新那行。
        //    这一栏若指向最旧那行，界面上「覆盖行元数据」与「生效值」就来自两条不同的记录 ——
        //    管理员照着 updater/updateTime 去追责，追到的是一个并未生效的写法。
        assertEquals(Long.valueOf(20L), vo.getOverrideId(), "回显的覆盖行应是最新那行");
        assertEquals("最新那行", vo.getDisplayName(), "生效展示名应与元数据同源");
    }

    // ==================== 夹具 ====================

    /** 一份「除风险/权限外全部照默认填」的维护请求；风险与权限由各用例自己设 */
    private AgentRegisteredToolUpdateReqVO req(String toolName) {
        AgentRegisteredToolUpdateReqVO req = new AgentRegisteredToolUpdateReqVO();
        req.setToolName(toolName);
        req.setDisplayName("");
        req.setDescription("");
        req.setGroupName("演示");
        req.setRiskLevel("");
        req.setPermissions("");
        req.setEnabled(true);
        req.setParamsSchema("");
        req.setRemark("");
        return req;
    }

    /** 审计 mapper 到目前为止被 insert 过几次（比 {@code verify(times(n))} 好在能带自定义断言消息） */
    private int countAuditInserts() {
        return (int) mockingDetails(overrideAuditMapper).getInvocations().stream()
                .filter(inv -> "insert".equals(inv.getMethod().getName()))
                .count();
    }

    /**
     * 覆盖 mapper 到目前为止被 {@code deleteById} 过几次。
     *
     * <p>走 {@code mockingDetails} 而不是 {@code verify(never()).deleteById(...)}：MP 的
     * {@code BaseMapper} 有 {@code deleteById(Serializable)} 与 {@code deleteById(T)} 两个重载，
     * 往里塞 {@code anyLong()}/{@code any()} 会撞上重载解析，编译期就可能挑错那个。
     * 按方法名数调用次数没有这个问题。
     */
    private int countDeletes() {
        return (int) mockingDetails(overrideMapper).getInvocations().stream()
                .filter(inv -> "deleteById".equals(inv.getMethod().getName()))
                .count();
    }

    /**
     * 一行 live 覆盖。{@code displayName} 是给断言认人用的标记 ——
     * 三处读快照的地方都收敛成「最新那行生效」，而「生效的是哪一行」只能靠这个字段看出来。
     */
    private AgentToolOverrideDO row(long id, String displayName) {
        AgentToolOverrideDO r = new AgentToolOverrideDO();
        r.setId(id);
        r.setToolName(IRREVERSIBLE_TOOL);
        r.setDisplayName(displayName);
        r.setGroupName("演示");
        r.setRiskLevel("");
        r.setPermissions(PERM_RUN + "," + PERM_AUDIT);
        r.setEnabled(true);
        return r;
    }

    /**
     * 一份<b>不构成放松</b>的维护请求：风险留空（= 不覆盖）、权限照声明全填（= 没删）。
     *
     * <p>🔴 必须刻意避开 B1 的闸：D4-23 的用例只该测自愈，一旦请求里带了放松，
     * {@code update} 会在 upsert <b>之前</b>就抛「需要二次确认」，自愈那几行压根不会执行 ——
     * 用例仍然红，但红在一个与 D4-23 无关的地方，看的人会以为自愈没写对。
     */
    private AgentRegisteredToolUpdateReqVO noRelaxReq() {
        AgentRegisteredToolUpdateReqVO req = req(IRREVERSIBLE_TOOL);
        req.setRiskLevel("");
        req.setPermissions(PERM_RUN + "," + PERM_AUDIT);
        return req;
    }

}
