package cn.enlev.ainb.module.bpm.framework.flowable.core.behavior;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.bpm.framework.permission.BpmSubProcessCallChecker;
import org.flowable.bpmn.model.CallActivity;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoSession;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.concurrent.atomic.AtomicReference;

import static cn.enlev.ainb.module.bpm.enums.ErrorCodeConstants.PROCESS_INSTANCE_START_CHILD_NOT_ALLOWED;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 子流程调起授权挂在<b>行为层</b>的回归（TODO #37）
 *
 * <h3>这组用例钉的是「防线有没有旁路」</h3>
 *
 * S6c 把校验挂在 {@code BpmCallActivityListener} 上，而那个监听器是
 * {@code SimpleModelUtils} 只给<b>钉钉式设计器</b>的子流程节点挂的。
 * 于是原生 BPMN 的 {@code callActivity} 一行校验都不跑 ——
 * <b>换个设计器就绕过去了</b>，界面上还看不出区别。
 *
 * 所以这里测的不是「校验逻辑对不对」（那是 {@code BpmSubProcessCallCheckerTest}），
 * 而是「不管 XML 是谁生成的，都会走到校验」。
 *
 * @author 走进新时代
 */
@MockitoSettings(strictness = Strictness.LENIENT)
public class BpmCallActivityBehaviorTest extends BaseMockitoUnitTest {

    private static final String PARENT_DEF_ID = "main:1:uuid";
    private static final String CHILD_KEY = "salary_review";
    private static final String CHILD_DEF_ID = "salary_review:3:uuid";

    @Test
    @DisplayName("⚠️ 显式 DENY 时在 execute 首部就被拦住 —— 与 XML 由哪个设计器生成无关")
    void testDeniedBlocksExecute() {
        BpmSubProcessCallChecker checker = mock(BpmSubProcessCallChecker.class);
        when(checker.canCall(PARENT_DEF_ID, CHILD_KEY)).thenReturn(false);
        TestBehavior behavior = new TestBehavior(callActivity(CHILD_KEY, null), checker, CHILD_KEY, null);

        ServiceException exception = assertThrows(ServiceException.class, () -> behavior.execute(execution()));

        assertEquals(PROCESS_INSTANCE_START_CHILD_NOT_ALLOWED.getCode(), exception.getCode());
        // 没有走到 super.execute —— 子流程根本没被调起
        assertFalse(behavior.superExecuted);
    }

    @Test
    @DisplayName("放行时照常调起子流程")
    void testAllowedCallsSuper() {
        BpmSubProcessCallChecker checker = mock(BpmSubProcessCallChecker.class);
        when(checker.canCall(any(), any())).thenReturn(true);
        TestBehavior behavior = new TestBehavior(callActivity(CHILD_KEY, null), checker, CHILD_KEY, null);

        behavior.execute(execution());

        assertTrue(behavior.superExecuted);
        verify(checker).canCall(PARENT_DEF_ID, CHILD_KEY);
    }

    @Test
    @DisplayName("🔴 calledElementType=id 时换算成 key 再判 —— 否则「按 id 调用」就能绕开所有子流程授权")
    void testCalledElementTypeIdResolvedToKey() {
        BpmSubProcessCallChecker checker = mock(BpmSubProcessCallChecker.class);
        AtomicReference<String> asked = new AtomicReference<>();
        when(checker.canCall(any(), any())).thenAnswer(invocation -> {
            asked.set(invocation.getArgument(1));
            return true;
        });
        // calledElement 写的是定义编号，id → key 由部署缓存反查
        TestBehavior behavior = new TestBehavior(callActivity(CHILD_DEF_ID, "id"), checker, CHILD_DEF_ID, CHILD_KEY);

        behavior.execute(execution());

        assertEquals(CHILD_KEY, asked.get());
    }

    @Test
    @DisplayName("解析不出子流程 key 时放行 —— super.execute 一样解析不出来，抢先报「无权调起」只会盖掉真实原因")
    void testUnresolvableKeyPasses() {
        BpmSubProcessCallChecker checker = mock(BpmSubProcessCallChecker.class);
        when(checker.canCall(any(), any())).thenReturn(true);
        TestBehavior behavior = new TestBehavior(callActivity("${childKey}", null), checker, null, null);
        behavior.resolveThrows = true;

        behavior.execute(execution());

        assertTrue(behavior.superExecuted);
        verify(checker).canCall(PARENT_DEF_ID, null);
    }

    @Test
    @DisplayName("校验器没装配上时不拦 —— 一次装配问题不该让所有子流程都跑不起来")
    void testMissingCheckerPasses() {
        TestBehavior behavior = new TestBehavior(callActivity(CHILD_KEY, null), null, CHILD_KEY, null);

        behavior.execute(execution());

        assertTrue(behavior.superExecuted);
    }

    private CallActivity callActivity(String calledElement, String calledElementType) {
        CallActivity callActivity = new CallActivity();
        callActivity.setId("Activity_child");
        callActivity.setCalledElement(calledElement);
        callActivity.setCalledElementType(calledElementType);
        return callActivity;
    }

    private DelegateExecution execution() {
        DelegateExecution execution = mock(DelegateExecution.class);
        when(execution.getProcessDefinitionId()).thenReturn(PARENT_DEF_ID);
        return execution;
    }

    /**
     * 把两处需要 Flowable 命令上下文的调用换成固定值
     *
     * 单测里没有 {@link MockitoSession} 之外的任何 Flowable 运行时，
     * {@code CommandContextUtil} / {@code ProcessDefinitionUtil} 都取不到东西
     */
    private static class TestBehavior extends BpmCallActivityBehavior {

        private final String calledElementValue;
        private final String keyById;

        private boolean superExecuted;
        private boolean resolveThrows;

        TestBehavior(CallActivity callActivity, BpmSubProcessCallChecker checker,
                     String calledElementValue, String keyById) {
            super(callActivity, checker);
            this.calledElementValue = calledElementValue;
            this.keyById = keyById;
        }

        @Override
        protected String resolveCalledElementValue(DelegateExecution execution) {
            if (resolveThrows) {
                throw new IllegalStateException("表达式求值失败");
            }
            return calledElementValue;
        }

        @Override
        protected String lookupProcessKeyById(String processDefinitionId) {
            return keyById;
        }

        @Override
        protected void superExecute(DelegateExecution execution) {
            superExecuted = true;
        }

    }

}
