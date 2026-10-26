package cn.enlev.ainb.module.agent.service.workflow;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowExecutionDO;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowExecutionMapper;
import cn.enlev.ainb.module.agent.enums.WorkflowDefStatusEnum;
import cn.enlev.ainb.module.agent.enums.WorkflowExecutionStatusEnum;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslParser;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DEF_DISABLED_NOT_RESUMABLE;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_EXECUTION_STATE_INVALID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code AgentWorkflowExecutionServiceImpl#resume} 的<b>定义状态闸门</b>（迭代七 B3-3）。
 *
 * <h3>为什么单测之外还必须钉「一条 WARN」</h3>
 * 本轮 B3-3 的交付物是「拒绝 + 告警」，而告警面<b>只有一行 {@code log.warn}</b>
 * （站内信通道刻意没借用，理由见 {@code requireDefResumable} 的 javadoc：那条通道的语义是
 * 「执行进了 FAILED 终态」，而这里执行仍是 INTERRUPTED，借它发信等于替没失败的执行编病因）。
 * 一行日志是<b>唯一</b>的可观测产物 —— 只断言「抛了异常」的话，一个把 warn 整段删掉的实现同样全绿，
 * 而「被静默拒掉」正是本子系统最典型的缺陷形态（操作无效且提示不指向真因）。
 * 故照 {@code AgentDefServiceTest} 的既有做法，用 {@code ListAppender} 把日志变成可断言的对象。
 *
 * <h3>四条断言各守什么</h3>
 * <ol>
 *   <li>{@link #resumeRejectedWhenDefDisabled}：DISABLED → 抛新错误码、<b>状态一格都不许动</b>
 *       （不 CAS、不 updateById）、恰好一条 WARN 且点名定义与出路。</li>
 *   <li>{@link #resumeNotBlockedByThisGateWhenDefPublished}：反向 —— PUBLISHED 的定义<b>不得</b>被本闸门拦下
 *       （用「CAS 返回 false ⟹ 抛的是状态码而不是停用码」证明它确实走到了闸门之后）。
 *       没有这条，一个「无条件拒绝 resume」的实现也能让第 1 条变绿。</li>
 *   <li>{@link #rejectionDoesNotBorrowTheFailureAlertChannel}：钉住「刻意不借用失败告警通道」这个决定 ——
 *       那条通道一发就是「工作流失败」的站内信，而这条执行并没有失败。</li>
 *   <li>{@link #disabledCheckSitsBeforeTheCasClaim}：源码级钉<b>顺序</b>。检查若被挪到 CAS 之后，
 *       前两条<b>照样全绿</b>（异常与日志都在），但库里会多出一行「状态说在跑、其实没人跑」的僵尸。</li>
 * </ol>
 *
 * @author 走进新时代
 */
class AgentWorkflowExecutionServiceImplResumeDefStatusTest {

    private static final Long EXECUTION_ID = 77L;
    private static final Long DEF_ID = 55L;
    private static final String DEF_CODE = "wf_disabled_resume";

    private AgentWorkflowExecutionServiceImpl service;
    private AgentWorkflowExecutionMapper executionMapper;
    private AgentWorkflowDefService defService;
    private AgentFailureAlertService failureAlertService;

    private final AgentExecutionContext context = AgentExecutionContext.ofForTest(9L, 1L);

    @BeforeEach
    void setUp() {
        executionMapper = mock(AgentWorkflowExecutionMapper.class);
        defService = mock(AgentWorkflowDefService.class);
        failureAlertService = mock(AgentFailureAlertService.class);
        DslParser dslParser = mock(DslParser.class);
        // 走到解析这一步说明定义闸门已放行；返回一个空 DTO 即可，本类不让图真跑
        when(dslParser.parse(any())).thenReturn(mock(WorkflowDefDTO.class));

        service = new AgentWorkflowExecutionServiceImpl();
        ReflectionTestUtils.setField(service, "executionMapper", executionMapper);
        ReflectionTestUtils.setField(service, "defService", defService);
        ReflectionTestUtils.setField(service, "dslParser", dslParser);
        ReflectionTestUtils.setField(service, "failureAlertService", failureAlertService);

        when(executionMapper.selectById(EXECUTION_ID)).thenReturn(suspendedExecution());
    }

    @Test
    @DisplayName("🔴 定义已停用 ⟹ 拒绝恢复、状态一格不动、恰好一条 WARN 点名定义与出路")
    void resumeRejectedWhenDefDisabled() {
        when(defService.getDef(DEF_ID)).thenReturn(def(WorkflowDefStatusEnum.DISABLED.getStatus()));

        ListAppender<ILoggingEvent> logs = captureLogs();
        try {
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> service.resume(EXECUTION_ID, Map.of("approved", true), context));
            assertEquals(WORKFLOW_DEF_DISABLED_NOT_RESUMABLE.getCode(), ex.getCode(),
                    "应报「定义已停用」这条专用码，而不是笼统的状态不允许 —— 后者会把人指去查执行状态，"
                            + "而真正要动的是定义（去重新发布一个新版本）");
            assertTrue(ex.getMessage().contains(DEF_CODE),
                    "报错必须点名是哪个定义被停用，管理员手上往往挂着多个流程：" + ex.getMessage());

            // 🔴 状态一格都不许动：这行确实还挂在 checkpoint 上，INTERRUPTED 是实话。
            //    动了就会留下「状态说在跑、其实没人跑」的僵尸行，或把没失败的执行写成 FAILED。
            verify(executionMapper, never()).casInterruptedToRunning(anyLong());
            // 🔴 updateById 必须带类型：BaseMapper 同时有 updateById(T) 与 updateById(Collection<T>)，
            //    裸 any() 会让 javac 报「不匹配」（重载解析不了）
            verify(executionMapper, never()).updateById(any(AgentWorkflowExecutionDO.class));
            verify(executionMapper, never()).updateByIdUnlessCancelled(any(AgentWorkflowExecutionDO.class));

            List<String> warns = warnMessages(logs);
            assertEquals(1, warns.size(), "应恰有一条 WARN（本轮的告警面就只有这一行），实际：" + warns);
            String warn = warns.get(0);
            assertTrue(warn.contains(DEF_CODE), "告警要点名定义 code：" + warn);
            assertTrue(warn.contains("停用"), "告警要说清拒绝的原因：" + warn);
            assertTrue(warn.contains("INTERRUPTED"),
                    "告警要说清这行的下落（保持 INTERRUPTED 不动）—— 否则运维会以为它被顺手关掉了：" + warn);
            assertTrue(warn.contains("取消"),
                    "告警要给出路（去执行历史里取消），只报问题不给出路的告警等于把活推给读的人：" + warn);
        } finally {
            releaseLogs(logs);
        }
    }

    @Test
    @DisplayName("🔴 定义仍已发布 ⟹ 不被本闸门拦（否则「无条件拒绝 resume」也能让上一条变绿）")
    void resumeNotBlockedByThisGateWhenDefPublished() {
        when(defService.getDef(DEF_ID)).thenReturn(def(WorkflowDefStatusEnum.PUBLISHED.getStatus()));
        // 让 CAS 认领失败：这样 resume 会在<b>闸门之后</b>的那一格抛状态码，正好证明它走过了闸门。
        // （casInterruptedToRunning 是 default 方法，Mockito 的 mock 不执行其方法体、恒返回 false，
        //   这里显式桩出来是为了让「靠哪一格抛的」这件事写在明面上。）
        when(executionMapper.casInterruptedToRunning(EXECUTION_ID)).thenReturn(false);

        ListAppender<ILoggingEvent> logs = captureLogs();
        try {
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> service.resume(EXECUTION_ID, Map.of(), context));
            assertEquals(WORKFLOW_EXECUTION_STATE_INVALID.getCode(), ex.getCode(),
                    "已发布的定义不该被停用闸门拦下；它应当一路走到 CAS 认领那一格才因认领失败而抛状态码");
            verify(executionMapper).casInterruptedToRunning(EXECUTION_ID);
            assertEquals(List.of(), warnMessages(logs),
                    "定义正常时一条 WARN 都不许有 —— 否则「无条件告警」的实现也能让上一条变绿");
        } finally {
            releaseLogs(logs);
        }
    }

    @Test
    @DisplayName("🔴 拒绝时不借用失败告警通道：这条执行并没有失败，发「工作流失败」站内信就是编病因")
    void rejectionDoesNotBorrowTheFailureAlertChannel() {
        when(defService.getDef(DEF_ID)).thenReturn(def(WorkflowDefStatusEnum.DISABLED.getStatus()));

        assertThrows(ServiceException.class, () -> service.resume(EXECUTION_ID, Map.of(), context));

        verify(failureAlertService, never()).alertExecutionFailure(any(), any(), any(), any());
    }

    @Test
    @DisplayName("🔴 顺序钉：定义状态检查必须落在 CAS 认领之前（挪到之后前两条照样全绿，但会留僵尸行）")
    void disabledCheckSitsBeforeTheCasClaim() throws Exception {
        java.nio.file.Path file = java.nio.file.Paths.get("src", "main", "java", "cn", "enlev", "ainb",
                "module", "agent", "service", "workflow", "AgentWorkflowExecutionServiceImpl.java");
        assertTrue(java.nio.file.Files.isRegularFile(file),
                "找不到 " + file.toAbsolutePath() + "（工作目录应是模块根，与 KnownTypesHintSingleSourceTest 同一口径）");
        String src = java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);

        String body = methodBody(src, "public Flux<WorkflowEvent> resume(Long executionId");
        int gate = body.indexOf("requireDefResumable(");
        int claim = body.indexOf("casInterruptedToRunning(");
        assertTrue(gate >= 0, "resume() 里没有 requireDefResumable 调用 —— 闸门被删了？方法体 → " + body);
        assertTrue(claim >= 0, "resume() 里没有 casInterruptedToRunning 调用 —— 认领逻辑被改了？请同步改本条断言");
        assertTrue(gate < claim,
                "定义状态检查跑到了 CAS 认领之后：认领会把 INTERRUPTED 改成 RUNNING，先认领再拒就会在库里留下"
                        + "一行「状态说在跑、其实没人跑」的僵尸。闸门必须在认领之前。方法体 → " + body);
    }

    // ==================== 夹具 ====================

    /** 一条挂在 hitl 上的执行（INTERRUPTED = {@code isResumable} 唯一放行的状态） */
    private AgentWorkflowExecutionDO suspendedExecution() {
        AgentWorkflowExecutionDO exec = new AgentWorkflowExecutionDO();
        exec.setId(EXECUTION_ID);
        exec.setWorkflowDefId(DEF_ID);
        exec.setWorkflowVersion(3);
        exec.setStatus(WorkflowExecutionStatusEnum.INTERRUPTED.getStatus());
        exec.setUserId(9L);
        exec.setThreadName("t1:" + EXECUTION_ID);
        return exec;
    }

    private AgentWorkflowDefDO def(String status) {
        AgentWorkflowDefDO def = new AgentWorkflowDefDO();
        def.setId(DEF_ID);
        def.setCode(DEF_CODE);
        def.setVersion(3);
        def.setStatus(status);
        def.setDsl("{\"code\":\"" + DEF_CODE + "\",\"nodes\":[]}");
        return def;
    }

    private ListAppender<ILoggingEvent> captureLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(AgentWorkflowExecutionServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    /** 🔴 必须摘掉：logger 是全局单例，漏摘会让后续用例继续往这个 appender 里灌日志，红点飘到别的测试上 */
    private void releaseLogs(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(AgentWorkflowExecutionServiceImpl.class)).detachAppender(appender);
        appender.stop();
    }

    private List<String> warnMessages(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /** 取指定签名的方法体（含签名与收尾大括号）；找不到或大括号不配对都直接失败，不静默返回空串 */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0,
                "找不到签名「" + signature + "」—— 方法被改名/改可见性/删了？改名会让顺序断言失去对象");
        int depth = 0;
        for (int i = source.indexOf('{', start); i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(start, i + 1);
            }
        }
        throw new AssertionError("「" + signature + "」的方法体大括号不配对，扫描结果不作数");
    }
}
