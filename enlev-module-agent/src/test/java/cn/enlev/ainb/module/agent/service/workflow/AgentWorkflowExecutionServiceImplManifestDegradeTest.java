package cn.enlev.ainb.module.agent.service.workflow;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowExecutionDO;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ChangeManifest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * {@code AgentWorkflowExecutionServiceImpl#buildFrozenManifestTree} 的降级行为单测
 * （交接评审发现 ⑩：清单构建失败原先只留一行 {@code log.warn}，审批人在盲批）
 *
 * <p>钉三件事：
 * <ol>
 *   <li>构建器返回 null ⟹ 注入<b>降级清单</b>，不是 null</li>
 *   <li>构建器抛异常 ⟹ 同样注入降级清单，且异常原因带得出去</li>
 *   <li>🔴 底线不变：正常清单照常透传，不被降级品顶掉（否则每份清单都在喊狼来了，
 *       而「狼来了」喊多了审批人就学会整块跳过它——那与沉默同样致命）</li>
 * </ol>
 *
 * <p>用 {@link ReflectionTestUtils} 打私有方法而不是驱动整条 runGraph：挂起链路要拉起
 * checkpoint / 编译器 / 轮询器，而这里要验的只是「失败时给什么」这一个决策点。
 *
 * @author 走进新时代
 */
@DisplayName("agent 执行服务：变更清单构建失败要降级，不许沉默")
class AgentWorkflowExecutionServiceImplManifestDegradeTest extends BaseMockitoUnitTest {

    @InjectMocks
    private AgentWorkflowExecutionServiceImpl service;

    @Mock
    private AgentWorkflowDefService defService;

    private static AgentWorkflowExecutionDO exec(long id, long defId) {
        AgentWorkflowExecutionDO e = new AgentWorkflowExecutionDO();
        e.setId(id);
        e.setWorkflowDefId(defId);
        return e;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> invoke(AgentWorkflowExecutionServiceImpl svc, AgentWorkflowExecutionDO e) {
        return (Map<String, Object>) ReflectionTestUtils.invokeMethod(svc, "buildFrozenManifestTree", e);
    }

    @Test
    @DisplayName("构建器返回 null ⟹ 注入降级清单（degraded=true），不是 null")
    void nullManifest_degradesInsteadOfStayingSilent() {
        when(defService.buildManifestOrNull(isNull(), eq(77L), isNull())).thenReturn(null);

        Map<String, Object> tree = invoke(service, exec(9L, 77L));

        assertNotNull(tree, "🔴 返回 null 就是本条要修的缺陷：审批页页签消失，审批人读到「这流程不改什么」");
        assertEquals(Boolean.TRUE, tree.get("degraded"));
        assertTrue(tree.get("headline").toString().contains("生成失败"));
        List<?> unresolved = (List<?>) tree.get("unresolved");
        assertEquals(1, unresolved.size());
        assertEquals("MANIFEST_FAILED", ((Map<?, ?>) unresolved.get(0)).get("type"));
    }

    @Test
    @DisplayName("🔴 构建器抛异常 ⟹ 照样降级，且异常原因带到审批人眼前")
    void throwingBuilder_degradesAndCarriesReason() {
        when(defService.buildManifestOrNull(any(), any(), any()))
                .thenThrow(new IllegalStateException("流程定义 77 不存在"));

        Map<String, Object> tree = invoke(service, exec(9L, 77L));

        assertNotNull(tree, "异常路径同样不许沉默 —— 它比返回 null 更该吼，因为那说明平台坏了");
        assertEquals(Boolean.TRUE, tree.get("degraded"));
        String reason = ((Map<?, ?>) ((List<?>) tree.get("unresolved")).get(0)).get("reason").toString();
        assertTrue(reason.contains("流程定义 77 不存在"),
                "原因必须原样落到 unresolved[].reason，否则运维只能去翻服务端日志：" + reason);
    }

    @Test
    @DisplayName("底线：正常清单照常透传，不被降级品顶掉")
    void healthyManifest_passesThrough() {
        ChangeManifest ok = new ChangeManifest();
        ok.setHeadline("将新建 1 张数据表");
        when(defService.buildManifestOrNull(isNull(), eq(77L), isNull())).thenReturn(ok);

        Map<String, Object> tree = invoke(service, exec(9L, 77L));

        assertNotNull(tree);
        assertEquals(Boolean.FALSE, tree.get("degraded"), "好清单被标成降级，等于每份清单都在喊狼来了");
        assertEquals("将新建 1 张数据表", tree.get("headline"));
        assertTrue(((List<?>) tree.get("unresolved")).isEmpty());
    }

}
