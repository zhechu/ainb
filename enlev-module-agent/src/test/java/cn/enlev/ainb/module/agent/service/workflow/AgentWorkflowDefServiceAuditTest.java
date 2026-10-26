package cn.enlev.ainb.module.agent.service.workflow;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowDefAuditRespVO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowDefMapper;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowExecutionMapper;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslParser;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslToStateGraphCompiler;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ChangeManifest;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.WorkflowChangeManifestBuilder;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.AgentValidateResult;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.WorkflowDslValidator;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AgentWorkflowDefServiceImpl#auditAll(boolean)} 的编排单测（重构 S0：开闸前第 0 步）
 *
 * <p>纯 mock，不连库、不建真图。断言聚焦「盘点这份名单<b>可不可信</b>」——它要拿去做「要不要开闸」的决定，
 * 所以本类的重点不是「有没有跑出结果」，而是<b>结果会不会骗人</b>：
 * <ol>
 *   <li><b>扫得全</b>：三种状态都扫（DISABLED 行仍可被 rollback 当蓝本），不按 status 过滤</li>
 *   <li><b>只读</b>：对 Mapper 的唯一一次调用就是那条 select —— 这是「不改任何数据」那句承诺的唯一证据</li>
 *   <li><b>不把「没查」说成「没问题」</b>：干跑层的三态（NOT_REQUESTED / PROBED / UNAVAILABLE）必须分清，
 *       尤其「请求了但清单建不出来」绝不能显示成干净</li>
 *   <li><b>不把「工具坏了」说成「这行违规」</b>：某行体检自己抛异常 → 记 auditFailure、单独计数，
 *       且不拖累其余行（方案 §6.1：探测失败不是 error）</li>
 *   <li><b>计数自洽</b>：scanned = failed + warned + clean + auditFailed，四档互斥且穷尽</li>
 *   <li><b>逐行不串味</b>：A 行的 error 不会跑到 B 行头上；元信息（code/version/status…）如实带出，
 *       因为报告要靠它写「逐条 code@version」</li>
 *   <li><b>降档能自解释</b>（台账 #81）：每行如实带出「本 code 名下全部版本被<b>拉起</b>过几次」
 *       （🔴 E7：那是<b>尝试</b>次数，含 FAILED / INTERRUPTED，不是成功次数），
 *       且这个<b>事实</b>不受该行体检成败影响 —— 否则报告里会出现「有个 warning 但不知道为什么被降档」</li>
 *   <li><b>能两份对照</b>（C7）：{@code byStatus} 的键序只由状态名决定，与行的增删、改名、回滚全无关 ——
 *       这份报告是拿去做开闸决定、并且要<b>两份逐行对照</b>的，键序会漂就没法对照</li>
 * </ol>
 *
 * <p>规则本身的对错不在这儿测：判据与发布闸门是同一个 {@code validateDsl}，规则由
 * {@code WorkflowDslValidatorTest}（155 条）与 {@code AgentWorkflowDefServiceValidateTest} 守着。
 * 本类只钉「把那个判据应用到全库时，编排有没有走样」。
 *
 * @author 走进新时代
 */
class AgentWorkflowDefServiceAuditTest extends BaseMockitoUnitTest {

    @InjectMocks
    private AgentWorkflowDefServiceImpl defService;

    @Mock
    private AgentWorkflowDefMapper defMapper;
    @Mock
    private DslParser dslParser;
    @Mock
    private WorkflowDslValidator dslValidator;
    @Mock
    private DslToStateGraphCompiler dslCompiler;
    @Mock
    private WorkflowChangeManifestBuilder manifestBuilder;
    /** 执行史取数口（台账 #81）：盘点在循环外一次算完全量，逐行只读 Map */
    @Mock
    private AgentWorkflowExecutionMapper executionMapper;

    // ==================== 造样本 ====================

    /** 造一行定义。dsl 用 code 派生，保证每行 DSL 互不相同（便于按 DSL 分别 stub 体检结论） */
    private static AgentWorkflowDefDO row(long id, String code, int version, String status,
                                          String source, String triggerType) {
        AgentWorkflowDefDO def = new AgentWorkflowDefDO();
        def.setId(id);
        def.setCode(code);
        def.setName(code + " 的名称");
        def.setVersion(version);
        def.setStatus(status);
        def.setSource(source);
        def.setTriggerType(triggerType);
        def.setPrimaryFlag(false);
        def.setDsl("{\"id\":\"" + code + "\",\"nodes\":[],\"edges\":[]}");
        return def;
    }

    /**
     * 给同一个 code 的多个版本各造一份**互不相同**的 DSL。
     *
     * <p>{@link #row} 用 code 派生 DSL，这在「一行一个 code」的用例里正好（DSL 天然互不相同，
     * 便于按 DSL 分别 stub）。但盘点扫的是<b>全部版本</b>，同 code 的多行会拿到逐字相同的 DSL，
     * 而 {@code dslParser.parse} 是按 equals 匹配的 —— 后写的 stub 静默覆盖先写的，
     * 于是只有最后一版真被 stub 到，其余各版的 {@code validate} stub 全部沦为「用不上的 stub」，
     * STRICT_STUBS 当场判红。这里按 id 把 DSL 拆开，让「一行一份 DSL」这个前提在多版本场景下也成立。
     */
    private static void distinctDsl(AgentWorkflowDefDO... defs) {
        for (AgentWorkflowDefDO def : defs) {
            def.setDsl("{\"id\":\"" + def.getCode() + "#" + def.getId() + "\",\"nodes\":[],\"edges\":[]}");
        }
    }

    /**
     * 让某行的静态体检产出给定 error / warning。
     *
     * <p>🔴 只在<b>无 error</b> 时才 stub 深层干跑：{@code validateDsl} 的纪律是「基础层不通过就不跑深层」，
     * 那时 stub 了也用不上，而 MockitoExtension 是 STRICT_STUBS —— 多余的 stub 会直接判测试失败。
     * 文案一律用 {@code addError("%s", x)} 传参而不是拼进格式串：值里含 {@code %} 时拼进去会炸（本仓既有纪律）。
     *
     * <p>🔴 <b>每行的 DTO 必须有一个可区分的身份</b>（这里用 {@code id} = code），且 stub 一律用 {@code same()} 而不是
     * {@code any()}/{@code equals()}：{@code WorkflowDefDTO} 是 Lombok {@code @Data}（按字段 equals），一堆「空 DTO」
     * 彼此<b>相等</b>，而 Mockito 按 equals 匹配实参 —— 于是后一行的 stub 会静默覆盖前一行的，
     * 全部行都拿到最后一行的体检结论。测试照跑照绿，但一条都没测到（本类第一版就是这么翻的车：
     * 10 条里 7 条红，且红得莫名其妙 —— 「明明 stub 了 error 却拿到空列表」）。
     *
     * @return 该行 stub 出来的 DTO，供需要按行区分清单/干跑的用例继续 stub
     */
    private WorkflowDefDTO stubStatic(AgentWorkflowDefDO def, List<String> errors, List<String> warnings) {
        WorkflowDefDTO dto = new WorkflowDefDTO();
        dto.setId(def.getCode());
        when(dslParser.parse(def.getDsl())).thenReturn(dto);
        AgentValidateResult basic = new AgentValidateResult();
        errors.forEach(e -> basic.addError("%s", e));
        warnings.forEach(w -> basic.addWarning("%s", w));
        when(dslValidator.validate(same(dto))).thenReturn(basic);
        if (errors.isEmpty()) {
            when(dslCompiler.dryRunValidate(same(dto), any()))
                    .thenReturn(new AgentValidateResult());
        }
        return dto;
    }

    private static AgentWorkflowDefAuditRespVO.Item itemOf(AgentWorkflowDefAuditRespVO resp, String code) {
        return resp.getItems().stream().filter(i -> code.equals(i.getCode())).findFirst()
                .orElseThrow(() -> new AssertionError("盘点结果里没有 " + code));
    }

    // ==================== ① 扫得全 ====================

    @Test
    void auditAll_scansAllThreeStatuses_neverFiltersByStatus() {
        AgentWorkflowDefDO draft = row(1L, "wf_draft", 1, "DRAFT", "MANUAL", "chat");
        AgentWorkflowDefDO published = row(2L, "wf_published", 3, "PUBLISHED", "AUTO", null);
        AgentWorkflowDefDO disabled = row(3L, "wf_disabled", 2, "DISABLED", "MANUAL", "chat");
        when(defMapper.selectListForAudit()).thenReturn(List.of(draft, published, disabled));
        stubStatic(draft, List.of(), List.of());
        stubStatic(published, List.of(), List.of());
        stubStatic(disabled, List.of(), List.of());

        AgentWorkflowDefAuditRespVO resp = defService.auditAll(false);

        // 🔴 DISABLED 也要盘：rollbackToVersion 接受任意状态作蓝本，停用行照样会被用户「改到」。
        //    少盘一种状态就会漏掉一类开闸后撞墙的人，而报告上看不出漏了。
        assertEquals(3, resp.getScanned(), "三种状态的行都要扫到");
        assertEquals(1, resp.getByStatus().get("DRAFT"));
        assertEquals(1, resp.getByStatus().get("PUBLISHED"));
        assertEquals(1, resp.getByStatus().get("DISABLED"));
        assertEquals("DRAFT", itemOf(resp, "wf_draft").getStatus());
        assertEquals("PUBLISHED", itemOf(resp, "wf_published").getStatus());
        assertEquals("DISABLED", itemOf(resp, "wf_disabled").getStatus());
    }

    /**
     * 🔴 C7：{@code byStatus} 的键序必须只由状态名决定，<b>与行序无关</b>。
     *
     * <p>缺陷现场：原先用的是 {@code LinkedHashMap}，注释还写着「按先见到的状态稳定输出，好让两份报告逐行对照」。
     * 但「先见到」取的是 {@code selectListForAudit} 的行序（{@code code ASC, version DESC}）——
     * 于是<b>新增或删除任意一行</b>，都会把它之后所有状态的键序整体推移。
     * 对照两份报告时看到的是「顺序变了」，而不是「哪一档的行数变了」，而这份报告是拿去做开闸决定的。
     *
     * <p>下面的输入行序刻意按生产的 {@code code ASC} 排（wf_a=PUBLISHED → wf_b=DISABLED → wf_c=DRAFT），
     * 也就是<b>不是</b>字典序 ⟹ 键序若取自行序，这条就会红。
     */
    @Test
    void auditAll_byStatusKeyOrder_followsStatusNameNotRowOrder() {
        AgentWorkflowDefDO published = row(1L, "wf_a", 1, "PUBLISHED", "AUTO", null);
        AgentWorkflowDefDO disabled = row(2L, "wf_b", 1, "DISABLED", "MANUAL", "chat");
        AgentWorkflowDefDO draft = row(3L, "wf_c", 1, "DRAFT", "MANUAL", "chat");
        when(defMapper.selectListForAudit()).thenReturn(List.of(published, disabled, draft));
        stubStatic(published, List.of(), List.of());
        stubStatic(disabled, List.of(), List.of());
        stubStatic(draft, List.of(), List.of());

        AgentWorkflowDefAuditRespVO resp = defService.auditAll(false);

        assertEquals(List.of("DISABLED", "DRAFT", "PUBLISHED"), List.copyOf(resp.getByStatus().keySet()),
                "键序必须是状态名字典序，不能跟着行序漂：" + resp.getByStatus());
    }

    @Test
    void auditAll_emptyTable_returnsZeroCountsNotNull() {
        when(defMapper.selectListForAudit()).thenReturn(List.of());

        AgentWorkflowDefAuditRespVO resp = defService.auditAll(false);

        // 「库里一条都没有」必须与「盘点没跑起来」在响应里可区分：前者是 0 + 空列表，后者会是 null/异常
        assertEquals(0, resp.getScanned());
        assertEquals(0, resp.getFailed());
        assertEquals(0, resp.getAuditFailed());
        assertNotNull(resp.getItems(), "空库也要给空列表，不给 null");
        assertTrue(resp.getItems().isEmpty());
        assertTrue(resp.getByStatus().isEmpty());
    }

    // ==================== ② 只读 ====================

    @Test
    void auditAll_isReadOnly_theOnlyMapperCallIsThatOneSelect() {
        AgentWorkflowDefDO def = row(1L, "wf_a", 1, "DRAFT", "MANUAL", "chat");
        when(defMapper.selectListForAudit()).thenReturn(List.of(def));
        stubStatic(def, List.of("工具 foo 不存在"), List.of());

        defService.auditAll(false);

        verify(defMapper).selectListForAudit();
        // 🔴 用 verifyNoMoreInteractions 而不是逐个 never()：那样只覆盖我想到的写方法，
        //    Mapper 日后新增一个写方法、盘点里误调了它，逐个 never() 的写法照样全绿。
        //    「对 Mapper 的全部交互就是那一条 select」才是「不改任何数据」的完整表述。
        verifyNoMoreInteractions(defMapper);
    }

    // ==================== ③ 干跑层三态 ====================

    @Test
    void auditAll_withoutDryRun_marksNotRequestedAndNeverBuildsManifest() {
        AgentWorkflowDefDO a = row(1L, "wf_a", 1, "DRAFT", "MANUAL", "chat");
        AgentWorkflowDefDO b = row(2L, "wf_b", 1, "PUBLISHED", "AUTO", null);
        when(defMapper.selectListForAudit()).thenReturn(List.of(a, b));
        stubStatic(a, List.of(), List.of());
        stubStatic(b, List.of(), List.of());

        AgentWorkflowDefAuditRespVO resp = defService.auditAll(false);

        assertFalse(resp.getDryRunIncluded(), "没请求干跑，响应必须如实说");
        assertEquals("NOT_REQUESTED", itemOf(resp, "wf_a").getDryRunState());
        assertEquals("NOT_REQUESTED", itemOf(resp, "wf_b").getDryRunState());
        // 一次清单都不建：干跑每行最多 15 秒，默认档必须真的不打 infra/bpm
        verifyNoInteractions(manifestBuilder);
    }

    @Test
    void auditAll_withDryRun_distinguishesProbedFromUnavailable() {
        AgentWorkflowDefDO ok = row(1L, "wf_probe_ok", 1, "DRAFT", "MANUAL", "chat");
        AgentWorkflowDefDO down = row(2L, "wf_probe_down", 1, "DRAFT", "MANUAL", "chat");
        when(defMapper.selectListForAudit()).thenReturn(List.of(ok, down));
        // 传 dsl（不传 id）⟹ resolveManifestSource 走 dsl 分支，版本号记 null
        // 🔴 两行的 DTO 由 stubStatic 带上各自 code 作身份，否则它们相等 ⟹ 下面 thenThrow 会覆盖 thenReturn
        WorkflowDefDTO okDto = stubStatic(ok, List.of(), List.of());
        WorkflowDefDTO downDto = stubStatic(down, List.of(), List.of());
        when(manifestBuilder.build(same(okDto), isNull(), isNull())).thenReturn(new ChangeManifest());
        when(manifestBuilder.build(same(downDto), isNull(), isNull()))
                .thenThrow(new IllegalStateException("infra 不通"));

        AgentWorkflowDefAuditRespVO resp = defService.auditAll(true);

        assertTrue(resp.getDryRunIncluded());
        assertEquals("PROBED", itemOf(resp, "wf_probe_ok").getDryRunState(), "清单建成了 → 干跑层真的查过了");
        // 🔴 这一条是本类最要紧的断言：清单建不出来时若也标 PROBED（或干脆不标），
        //    报告上就是「这行 0 error」——把「infra 当时不通」说成「这行干净」，
        //    而这份报告是拿去决定开不开闸的。
        assertEquals("UNAVAILABLE", itemOf(resp, "wf_probe_down").getDryRunState(),
                "清单建不出来 → 这一行的 V4/V5/V12 压根没查，必须与「查过且干净」区分开"
                        + "（V16 不看清单：台账 C9 之后它是纯静态溯源，UNAVAILABLE 也照常判）");
        assertNull(itemOf(resp, "wf_probe_down").getAuditFailure(),
                "清单不可用不是体检失败：静态两层照跑，结论仍然有效");
    }

    // ==================== ④ 逐行隔离 ====================

    @Test
    void auditAll_rowWhoseAuditThrows_isIsolatedAndDoesNotStopTheRest() {
        AgentWorkflowDefDO bad = row(1L, "wf_bad", 1, "PUBLISHED", "AUTO", "chat");
        AgentWorkflowDefDO good1 = row(2L, "wf_good1", 1, "DRAFT", "MANUAL", "chat");
        AgentWorkflowDefDO good2 = row(3L, "wf_good2", 1, "DRAFT", "MANUAL", "chat");
        when(defMapper.selectListForAudit()).thenReturn(List.of(bad, good1, good2));
        // 🔴 抛的是 RuntimeException 而不是 ServiceException：后者会被 validateDsl 捕获成一条 error
        //    （那是「DSL 格式不合法」的正常路径），只有前者才是「盘点工具自己坏了」
        when(dslParser.parse(bad.getDsl())).thenThrow(new IllegalStateException("编译器炸了"));
        stubStatic(good1, List.of("表 biz_x 不存在"), List.of());
        stubStatic(good2, List.of(), List.of("outputVar 未声明"));

        AgentWorkflowDefAuditRespVO resp = defService.auditAll(false);

        assertEquals(3, resp.getScanned(), "坏行也要算进扫描数，否则「少了一行」看不出来");
        assertEquals(1, resp.getAuditFailed());
        AgentWorkflowDefAuditRespVO.Item badItem = itemOf(resp, "wf_bad");
        assertNotNull(badItem.getAuditFailure(), "失败原因要留痕，不能只是个计数");
        assertTrue(badItem.getAuditFailure().contains("编译器炸了"));
        // 🔴 异常绝不混进 errors：「盘点坏了」不是「这行违规」（§6.1）。混进去会让人去修一条没毛病的流程。
        assertTrue(badItem.getErrors().isEmpty(), "体检失败的行不许凭空多出 error");
        assertFalse(badItem.getPassed(), "结果未知时 fail-close：不知道就当不能发");
        assertEquals(1, resp.getFailed(), "这个 1 来自 good1（真违规）；坏行进的是 auditFailed，不占 failed");
        assertEquals(0, resp.getClean(), "坏行也不许算 clean —— 它的 error 数是未知，不是 0");
        // 🔴 循环必须继续：坏行排在第一位，后面两行照样出结果（rethrow / break 都会让这两条红）
        assertEquals(List.of("表 biz_x 不存在"), itemOf(resp, "wf_good1").getErrors());
        assertEquals(List.of("outputVar 未声明"), itemOf(resp, "wf_good2").getWarnings());
    }

    // ==================== ⑤ 计数自洽 ====================

    @Test
    void auditAll_countsAreSelfConsistent_fourBuckets() {
        AgentWorkflowDefDO errRow = row(1L, "wf_err", 1, "PUBLISHED", "MANUAL", "chat");
        AgentWorkflowDefDO warnRow = row(2L, "wf_warn", 1, "PUBLISHED", "AUTO", null);
        AgentWorkflowDefDO cleanRow = row(3L, "wf_clean", 1, "DRAFT", "MANUAL", "chat");
        AgentWorkflowDefDO throwRow = row(4L, "wf_throw", 1, "DISABLED", "MANUAL", "chat");
        when(defMapper.selectListForAudit()).thenReturn(List.of(errRow, warnRow, cleanRow, throwRow));
        // 刻意只给一条 error：把 !isPassed() 改成「error 数 > 1」这类判据会在这里露馅
        stubStatic(errRow, List.of("模型 gpt-x 不存在"), List.of("顺带一条 warning"));
        stubStatic(warnRow, List.of(), List.of("outputVar 未声明"));
        stubStatic(cleanRow, List.of(), List.of());
        when(dslParser.parse(throwRow.getDsl())).thenThrow(new IllegalStateException("炸"));

        AgentWorkflowDefAuditRespVO resp = defService.auditAll(false);

        assertEquals(4, resp.getScanned());
        assertEquals(1, resp.getFailed(), "有 error 的行（哪怕同时有 warning）只进 failed");
        assertEquals(1, resp.getWarned(), "无 error 但有 warning");
        assertEquals(1, resp.getClean());
        assertEquals(1, resp.getAuditFailed());
        // 🔴 四档互斥且穷尽 —— 这条恒等式是「报告头条数字加起来对得上」的唯一保证。
        //    任何一行被漏计或双计，这里就红。
        assertEquals(resp.getScanned(),
                resp.getFailed() + resp.getWarned() + resp.getClean() + resp.getAuditFailed(),
                "scanned 必须等于四档之和");
    }

    // ==================== ⑥ 逐行不串味 ====================

    @Test
    void auditAll_errorsStayOnTheirOwnRow() {
        AgentWorkflowDefDO a = row(1L, "wf_a", 1, "DRAFT", "MANUAL", "chat");
        AgentWorkflowDefDO b = row(2L, "wf_b", 1, "DRAFT", "AUTO", "chat");
        when(defMapper.selectListForAudit()).thenReturn(List.of(a, b));
        stubStatic(a, List.of("A 独有的错误"), List.of("A 独有的提醒"));
        stubStatic(b, List.of(), List.of());

        AgentWorkflowDefAuditRespVO resp = defService.auditAll(false);

        // 共用一个 AgentValidateResult 累加（而不是逐行新建）会让这两条一起红
        assertEquals(List.of("A 独有的错误"), itemOf(resp, "wf_a").getErrors());
        assertEquals(List.of("A 独有的提醒"), itemOf(resp, "wf_a").getWarnings());
        assertTrue(itemOf(resp, "wf_b").getErrors().isEmpty(), "B 行不许沾上 A 行的 error");
        assertTrue(itemOf(resp, "wf_b").getWarnings().isEmpty());
        assertFalse(itemOf(resp, "wf_a").getPassed());
        assertTrue(itemOf(resp, "wf_b").getPassed());
    }

    @Test
    void auditAll_carriesRowMetadataIntoItems() {
        AgentWorkflowDefDO def = row(77L, "wf_plan_customer", 3, "PUBLISHED", "AUTO", "webhook");
        def.setPrimaryFlag(true);
        when(defMapper.selectListForAudit()).thenReturn(List.of(def));
        stubStatic(def, List.of(), List.of());

        AgentWorkflowDefAuditRespVO.Item item = itemOf(defService.auditAll(false), "wf_plan_customer");

        // 报告要写「逐条 code@version + 状态 + 来源 + 触发器」，这些字段缺一个就没法定位到责任人
        assertEquals(77L, item.getId());
        assertEquals("wf_plan_customer", item.getCode());
        assertEquals(3, item.getVersion());
        assertEquals("PUBLISHED", item.getStatus());
        assertEquals("AUTO", item.getSource());
        assertEquals("webhook", item.getTriggerType());
        assertTrue(item.getPrimaryFlag());
        // 🔴 这里刻意不断言「没有 dsl」：Item 上根本没这个字段，编译器已经保证了，比任何运行时断言都硬
        //    （几十 KB 的 DSL 正文 × 几十行 = 兆级负载，故响应形状里就不该有它）
    }

    @Test
    void auditAll_keepsTriggerTypeNullDistinctFromNone() {
        AgentWorkflowDefDO legacy = row(1L, "wf_legacy", 1, "PUBLISHED", "MANUAL", null);
        AgentWorkflowDefDO headless = row(2L, "wf_headless", 1, "PUBLISHED", "MANUAL", "none");
        when(defMapper.selectListForAudit()).thenReturn(List.of(legacy, headless));
        stubStatic(legacy, List.of(), List.of());
        stubStatic(headless, List.of(), List.of());

        AgentWorkflowDefAuditRespVO resp = defService.auditAll(false);

        // 🔴 null 是「老行从未做过触发器投影」，"none" 是「DSL 明确没有 trigger 块」。
        //    对 V11/V13/V14 两者都算无头，但把 null 改写成 "none" 就再也看不出存量里有多少行老到没投影过
        //    —— 而那正是「闸门会不会追溯到一批作者早就联系不上的流程」这个问题的输入。
        assertNull(itemOf(resp, "wf_legacy").getTriggerType(), "null 要原样留着，不许兜成 none");
        assertEquals("none", itemOf(resp, "wf_headless").getTriggerType());
    }

    // ==================== ⑦ 执行史如实带出（台账 #81） ====================

    private static AgentWorkflowDefAuditRespVO.Item itemById(AgentWorkflowDefAuditRespVO resp, long id) {
        return resp.getItems().stream().filter(i -> i.getId() == id).findFirst()
                .orElseThrow(() -> new AssertionError("盘点结果里没有 id=" + id));
    }

    /**
     * 报告必须自己说清「这一行为什么被降档」
     *
     * <p>降档把一条 error 变成 warning，而 warning 在报告里是<b>不阻断</b>的。若不同时给出
     * 「本 code 名下真跑过几次」这个事实，读报告的人只看到「这行有个 warning」，
     * 无从分辨它是「早就落地了、占用的正是它自己建的东西」还是「随便提一嘴」。
     * 这个字段因此是报告的<b>自解释</b>部分，不是调试信息。
     *
     * <p>🔴 按 code 合计、不是按行 id：回滚会造出一个<b>新 id</b>，执行史却留在旧 id 上。
     * 本例 v3（id=77）自己一次没跑过，v1（id=70）跑过 4 次、v2（id=74）跑过 2 次，
     * 三行都必须写 6 —— v3 那行写成 0 就等于宣布「这流程从没落地过」，
     * 而它恰恰是回滚出来的那一版，降档随之失效（#81 要治的正是这个）。
     *
     * <p>🔴 执行表<b>没有 code 列</b>，故 Mapper 只能按 defId 分组数，合计到 code 是 service 那一层的事。
     * 这条用例同时钉住了这个分工：stub 给的是 per-id 的数，断言的是 per-code 的和。
     */
    @Test
    void auditAll_carriesExecutedCountSummedAcrossTheCode() {
        AgentWorkflowDefDO v1 = row(70L, "wf_plan_device", 1, "DISABLED", "AUTO", "chat");
        AgentWorkflowDefDO v2 = row(74L, "wf_plan_device", 2, "DISABLED", "AUTO", "chat");
        AgentWorkflowDefDO v3 = row(77L, "wf_plan_device", 3, "PUBLISHED", "AUTO", "chat");
        AgentWorkflowDefDO other = row(88L, "wf_plan_other", 1, "PUBLISHED", "AUTO", "chat");
        AgentWorkflowDefDO neverRun = row(99L, "wf_plan_fresh", 1, "DRAFT", "MANUAL", "chat");
        // 🔴 三个版本 code 相同，而 row() 是**用 code 派生 dsl** 的 —— 不改就会造出三份逐字相同的 DSL，
        //    于是 dslParser.parse 的三次 stub 按 equals 互相覆盖（最后写的赢），
        //    前两行的 validate/dryRunValidate stub 就成了「用不上的 stub」，被 STRICT_STUBS 直接判红。
        //    本类 stubStatic 的 javadoc 里记着同一个坑的另一副面孔（空 DTO 彼此相等）。
        distinctDsl(v1, v2, v3);
        when(defMapper.selectListForAudit()).thenReturn(List.of(v1, v2, v3, other, neverRun));
        stubStatic(v1, List.of(), List.of());
        stubStatic(v2, List.of(), List.of());
        stubStatic(v3, List.of(), List.of());
        stubStatic(other, List.of(), List.of());
        stubStatic(neverRun, List.of(), List.of());
        // countByDefIds 只返回次数 > 0 的 id：neverRun(99) 刻意不在里面
        when(executionMapper.countByDefIds(anyCollection())).thenReturn(Map.of(70L, 4L, 74L, 2L, 88L, 9L));

        AgentWorkflowDefAuditRespVO resp = defService.auditAll(false);

        assertEquals(6L, itemById(resp, 70L).getExecutedCount());
        assertEquals(6L, itemById(resp, 74L).getExecutedCount());
        assertEquals(6L, itemById(resp, 77L).getExecutedCount(),
                "回滚/升版出来的这一版自己没跑过，但它的 code 名下跑过 6 次");
        assertEquals(9L, itemById(resp, 88L).getExecutedCount(), "别的 code 不许串味");
        // 🔴 0 是<b>实测到的</b> 0（库里查过、确实没有），不是「没查到」—— 字段是 long 而非 Long 就是这个意思
        assertEquals(0L, itemById(resp, 99L).getExecutedCount());
    }

    /**
     * 体检自己抛异常的行，执行次数照样要报出来
     *
     * <p>{@code setExecutedCount} 刻意写在逐行 try 的<b>外面</b>：执行次数是一个与体检成败无关的<b>事实</b>。
     * 挪进 try（或挪到 {@code validateDsl} 之后）的话，auditFailed 行的这个格子会永远是 0，
     * 而 0 在这份报告里的意思是「实测到它从没跑过」—— 于是一行「盘点工具炸了、但其实真跑过 5 次」的流程
     * 会被读成「没落地过」，恰好把最该降档的那批行排除在降档之外。
     */
    @Test
    void auditAll_rowWhoseAuditThrows_stillReportsExecutedCount() {
        AgentWorkflowDefDO throwRow = row(4L, "wf_throw", 1, "DISABLED", "MANUAL", "chat");
        when(defMapper.selectListForAudit()).thenReturn(List.of(throwRow));
        when(dslParser.parse(throwRow.getDsl())).thenThrow(new IllegalStateException("编译器炸了"));
        when(executionMapper.countByDefIds(anyCollection())).thenReturn(Map.of(4L, 5L));

        AgentWorkflowDefAuditRespVO.Item item = itemOf(defService.auditAll(false), "wf_throw");

        assertEquals(5L, item.getExecutedCount(), "体检炸了也不许把这个事实抹成 0");
        // 顺带钉住「事实照报」没有反过来污染结论：异常仍然只进 auditFailure，不混进 errors（§6.1）
        assertNotNull(item.getAuditFailure());
        assertTrue(item.getErrors().isEmpty());
        assertFalse(item.getPassed());
    }

}
