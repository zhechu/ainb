package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import cn.enlev.ainb.module.agent.service.workflow.AgentBpmApprovalService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChangeManifest#degraded(String)} 单测（交接评审发现 ⑩ / 存量清单 #107 的正面）
 *
 * <p>降级清单存在的唯一理由：让审批人分得清「扫过了，确实不写」与「压根没扫成」。
 * 所以这里钉的全是<b>会把这个区分弄丢</b>的改法：
 * <ol>
 *   <li>{@code degraded} 必须是 true —— 前端靠它咽回「本次不产生任何写操作」那句安心话</li>
 *   <li>风险等级给 HIGH 而不是 IRREVERSIBLE —— 后者是一句此刻不知道真伪的断言</li>
 *   <li>失败原因必须原样落到 {@code unresolved[].reason} 里，且 type 是 {@code MANIFEST_FAILED}</li>
 *   <li>必须能过 {@code toManifestTree} 那次 JSON 往返并把 {@code degraded} 带过去 ——
 *       过不了的话前端拿到的就是一份「看起来正常的空清单」，比不给还坏</li>
 * </ol>
 *
 * @author 走进新时代
 */
@DisplayName("agent ChangeManifest 降级清单")
class ChangeManifestDegradedTest {

    @Test
    @DisplayName("degraded：标记为降级，且不伪装成「没有变更」")
    void degraded_isFlaggedAndDoesNotPretendToBeClean() {
        ChangeManifest m = ChangeManifest.degraded("DSL 读不出来");

        assertTrue(m.isDegraded(), "degraded 必须是 true —— 前端全靠它分辨「没扫成」和「确实不写」");
        assertEquals(ChangeManifest.VERSION, m.getManifestVersion(), "降级品也是清单，版本号不能少");
        assertTrue(m.getChanges().isEmpty(), "降级清单刻意不含任何变更项：它什么都不知道");
        assertNotNull(m.getHeadline());
        assertTrue(m.getHeadline().contains("生成失败"),
                "headline 是四个渲染点里三个唯一显示的一行，必须自己说清是失败：" + m.getHeadline());
        assertNotNull(m.getGeneratedAt(), "generatedAt 缺了的话前端会以为这份清单没有时间基准");
    }

    @Test
    @DisplayName("🔴 degraded：风险等级给 HIGH + 「风险未知」，绝不给 IRREVERSIBLE（那是我们不知道的断言）")
    void degraded_riskLevelIsHonestAboutNotKnowing() {
        ChangeManifest m = ChangeManifest.degraded("x");

        assertEquals("HIGH", m.getRiskLevel(),
                "HIGH 让渲染层出橙色条并强制展开明细；再高就是替审批人下了一个平台此刻下不了的结论");
        assertFalse("IRREVERSIBLE".equals(m.getRiskLevel()),
                "「不可逆」是一句关于后果的断言，清单都没生成出来时平台没资格说这句话");
        assertEquals("风险未知", m.getRiskDescription(), "短标签要直说不知道，不能回落成「无写操作」");
        assertTrue(m.getRiskNote().contains("不代表本次没有影响"),
                "长句必须挡住「没清单 = 没影响」这个误读：" + m.getRiskNote());
    }

    @Test
    @DisplayName("degraded：原因原样落进唯一一条 unresolved，type = MANIFEST_FAILED")
    void degraded_carriesReasonInUnresolved() {
        ChangeManifest m = ChangeManifest.degraded("子流程 42 展不开");

        assertEquals(1, m.getUnresolved().size(), "只应有一条：说的是「整份清单没成」，不是「某一项说不清」");
        ChangeManifest.UnresolvedItem u = m.getUnresolved().get(0);
        assertEquals(ChangeManifest.UnresolvedItem.TYPE_MANIFEST_FAILED, u.getType(),
                "type 给机器判：前端与将来的对账脚本都靠它把这一条与其它三种分开");
        assertTrue(u.getReason().contains("子流程 42 展不开"),
                "原因必须原样带出去，否则审批人只看到「失败了」却不知道找谁修什么：" + u.getReason());
        assertEquals("整条流程", u.getNodeName(),
                "nodeName 不能留空：渲染层会回落成「未知节点」，而这里说的不是某个节点");
    }

    @Test
    @DisplayName("🔴 degraded 必须能过 toManifestTree 的 JSON 往返，并把 degraded=true 带到前端")
    void degraded_survivesManifestTreeRoundTrip() {
        Map<String, Object> tree = AgentBpmApprovalService.toManifestTree(ChangeManifest.degraded("boom"));

        assertNotNull(tree, "过不了往返就等于白造：调用方拿到的还是 null，审批页依旧一片安静");
        assertEquals(Boolean.TRUE, tree.get("degraded"),
                "🔴 这一格是整条修复的落点：布尔过 JSON 往返后必须还是 true，"
                        + "否则前端 v-if=\"!manifest.degraded\" 判不出来，那句安心话又会印出来");
        assertTrue(tree.get("headline").toString().contains("生成失败"));
        assertEquals("HIGH", tree.get("riskLevel"));
        assertEquals("风险未知", tree.get("riskDescription"));
        List<?> unresolved = (List<?>) tree.get("unresolved");
        assertEquals(1, unresolved.size());
        Map<?, ?> u = (Map<?, ?>) unresolved.get(0);
        assertEquals("MANIFEST_FAILED", u.get("type"));
        assertTrue(u.get("reason").toString().contains("boom"));
        assertEquals(List.of(), tree.get("changes"), "空变更列表要真的空着，别在往返里变成 null");
    }

    @Test
    @DisplayName("正常清单的 degraded 恒为 false —— 否则前端会把好清单也当降级品渲染")
    void normalManifest_isNotDegraded() {
        assertFalse(new ChangeManifest().isDegraded());
    }

}
