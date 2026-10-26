package cn.enlev.ainb.module.agent.service.workflow;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.module.agent.enums.WaitTypeEnum;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslToStateGraphCompiler;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code AgentWorkflowExecutionServiceImpl} 那两个 private 反查方法的单测（缺陷 E2）
 *
 * <p>挂起时 wait 节点<b>尚未执行</b>，故 {@code resume_at} / {@code event_key} 只能凭中断元数据的
 * {@code node()} 回到 DSL 里查 config —— 这两列是「谁来唤醒、什么时候唤醒」的<b>唯一</b>载体：
 * <ul>
 *   <li>{@code resume_at} 为 NULL ⟹ {@code WorkflowWaitResumeScheduler} 扫不到它，定时等待永不到点；</li>
 *   <li>{@code event_key} 为 NULL ⟹ {@code POST /agent/workflow/event} 匹配不到它，外部事件永远叫不醒。</li>
 * </ul>
 * 两种都是「流程停在 INTERRUPTED、既不报错也不前进」，而界面上与「正在等，还没到」长得一模一样。
 *
 * <h3>🔴 本类存在的直接理由：E2 修掉的那个 trim 分叉</h3>
 * 编译期 {@code DslToStateGraphCompiler.validateWaitConfig} 与校验器 V14 判 {@code waitType} 时<b>都 trim</b>，
 * 而这两个方法过去写的是 {@code "duration".equals(...)} / {@code "event".equals(...)} —— <b>逐字比、不 trim</b>。
 * 于是 {@code "waitType":" event "} 这份 DSL：发布闸门放行、V14 照样要求作者声明外部唤醒方（作者照做了）、
 * 挂起时 {@code event_key} 却<b>不落库</b> ⟹ 永久卡死且无任何提示。
 * 这正是本子系统最常见的缺陷形态：「配置保存成功但不生效且无提示」。
 * E2 把四个读取点统一到 {@link WaitTypeEnum#parse(String)}（会 trim）之后，
 * 这一格不再可能分叉；下面 {@link #paddedWaitTypeIsStillRecognised()} 就是钉住这个统一口径的回归。
 *
 * <h3>为什么用反射调 private 方法，而不是走 {@code publishEvent} / 整条挂起链</h3>
 * 走整条链要 mock 图编译、checkpoint、Mapper 与 CAS 认领，而真正要证的事只有一件：
 * 「同一个 {@code waitType} 字面量，编译期认得出来 ⟹ 运行期也必须认得出来」。
 * 用 {@code ReflectionTestUtils} 直接调这两个方法，用例与被证命题一一对应，
 * 红了就是口径分叉，不会与「图没跑起来」混在一起。{@code AgentWorkflowExecutionServiceImplEventTest}
 * 那条走 spy 的路子是为了证 {@code publishEvent} 的派发，与本类要证的不是一件事。
 *
 * @author 走进新时代
 */
class AgentWorkflowExecutionServiceImplWaitResolveTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 两个方法都不碰任何注入字段（duration/eventKey 的解析委托给编译器的静态方法），故裸 new 即可 */
    private final AgentWorkflowExecutionServiceImpl service = new AgentWorkflowExecutionServiceImpl();

    /** 一份只含一个 wait 节点的最小 DSL；{@code waitConfigJson} 原样拼进 config，好逐字控制 waitType 的写法 */
    private static WorkflowDefDTO def(String waitConfigJson) throws Exception {
        return MAPPER.readValue("{\"dslVersion\":\"1.0\",\"id\":\"wf_wait\",\"name\":\"等待\","
                + "\"nodes\":[{\"id\":\"w\",\"type\":\"wait\",\"config\":" + waitConfigJson + "}],"
                + "\"edges\":[{\"from\":\"START\",\"to\":\"w\"},{\"from\":\"w\",\"to\":\"END\"}]}",
                WorkflowDefDTO.class);
    }

    private String resolveEventKey(WorkflowDefDTO dto, String nodeId) {
        return ReflectionTestUtils.invokeMethod(service, "resolveWaitEventKey", dto, nodeId);
    }

    private LocalDateTime resolveResumeAt(WorkflowDefDTO dto, String nodeId) {
        return ReflectionTestUtils.invokeMethod(service, "resolveWaitResumeAt", dto, nodeId);
    }

    // ==================== ① 🔴 带空格的取值：编译期放行 ⟹ 运行期也必须认得 ====================

    @Test
    @DisplayName("🔴 waitType 带空格时 event_key / resume_at 都要照常算出来 —— 逐字比较时这一格是静默卡死")
    void paddedWaitTypeIsStillRecognised() throws Exception {
        // event 那一支：漏认的后果是 event_key 为 NULL，POST /event 永远匹配不到
        WorkflowDefDTO event = def("{\"waitType\":\" event \",\"eventKey\":\"order_paid\"}");
        assertEquals("order_paid", resolveEventKey(event, "w"),
                "带空格的 event 也是 event：编译期 trim 后放行了，运行期就不许认不出来");

        // duration 那一支：漏认的后果是 resume_at 为 NULL，调度器扫不到它
        WorkflowDefDTO duration = def("{\"waitType\":\" duration \",\"duration\":30}");
        LocalDateTime at = resolveResumeAt(duration, "w");
        assertNotNull(at, "带空格的 duration 也是 duration，resume_at 不许留 NULL");
        long delta = Duration.between(LocalDateTime.now(), at).getSeconds();
        assertTrue(delta > 0 && delta <= 30,
                "resume_at 应是「现在 + 30 秒」附近，实际相差 " + delta + " 秒");
    }

    // ==================== ② 🔴 大小写敏感必须<b>保持</b>（别把 trim 顺手放宽成 equalsIgnoreCase） ====================

    @Test
    @DisplayName("大小写不对的取值仍认不出来 —— 与编译器同一口径，交由编译器报「未知 waitType」")
    void wrongCasedWaitTypeIsStillRejected() throws Exception {
        // 这一条防的是「修 trim 时顺手放宽成不敏感」：那会让同一份 DSL 同时收到编译器的
        // 「未知 waitType(EVENT)」与 V14 的「没声明唤醒方」两条 —— 一个根因说两遍。
        // 且这种 DSL 压根发布不出去，运行期认不认得它并不重要，重要的是两侧口径一致。
        WorkflowDefDTO upper = def("{\"waitType\":\"EVENT\",\"eventKey\":\"order_paid\"}");
        assertNull(resolveEventKey(upper, "w"), "EVENT 不是合法取值，不该落 event_key");

        WorkflowDefDTO mixed = def("{\"waitType\":\"Duration\",\"duration\":30}");
        assertNull(resolveResumeAt(mixed, "w"), "Duration 不是合法取值，不该落 resume_at");
    }

    // ==================== ③ 分档互斥：event 不走 duration，duration 不走 event ====================

    @Test
    @DisplayName("两档各走各的：duration 不落 event_key，event 不落 resume_at")
    void eachWaitTypeOnlyFeedsItsOwnColumn() throws Exception {
        WorkflowDefDTO duration = def("{\"waitType\":\"duration\",\"duration\":30}");
        assertNull(resolveEventKey(duration, "w"), "定时等待没有外部唤醒方，event_key 该是 NULL");
        assertNotNull(resolveResumeAt(duration, "w"), "定时等待必须算出 resume_at，否则调度器扫不到");

        // 🔴 event 那一支若少了守卫，会拿一个没有 duration 的节点去调 resolveWaitDurationSeconds，
        //    于是「挂起」这个正常动作变成抛 WORKFLOW_DSL_INVALID —— 作者看到的是一条与他的配置无关的报错
        WorkflowDefDTO event = def("{\"waitType\":\"event\",\"eventKey\":\"order_paid\"}");
        assertNull(resolveResumeAt(event, "w"), "等外部事件的没有到点时刻，resume_at 该是 NULL（且不许抛）");
        assertEquals("order_paid", resolveEventKey(event, "w"));
    }

    // ==================== ④ 查不到就是 null，绝不猜 ====================

    @Test
    @DisplayName("节点 id 不匹配 / 不是 wait / 没有节点 / 传 null：一律返回 null")
    void unmatchedLookupsReturnNull() throws Exception {
        WorkflowDefDTO event = def("{\"waitType\":\"event\",\"eventKey\":\"order_paid\"}");
        assertNull(resolveEventKey(event, "别的节点"), "id 不匹配 → null");
        assertNull(resolveEventKey(event, null), "中断元数据没给出节点 → null");

        WorkflowDefDTO noNodes = MAPPER.readValue(
                "{\"dslVersion\":\"1.0\",\"id\":\"wf_empty\",\"name\":\"空\"}", WorkflowDefDTO.class);
        assertNull(resolveEventKey(noNodes, "w"), "没有 nodes → null，不许 NPE");
        assertNull(resolveResumeAt(noNodes, "w"), "没有 nodes → null，不许 NPE");

        // 非 wait 节点即便碰巧也叫 w、也配了 eventKey，同样不该被当成等待点
        WorkflowDefDTO notWait = MAPPER.readValue("{\"dslVersion\":\"1.0\",\"id\":\"wf_tool\",\"name\":\"工具\","
                        + "\"nodes\":[{\"id\":\"w\",\"type\":\"tool\",\"config\":"
                        + "{\"waitType\":\"event\",\"eventKey\":\"order_paid\"}}],"
                        + "\"edges\":[{\"from\":\"START\",\"to\":\"w\"},{\"from\":\"w\",\"to\":\"END\"}]}",
                WorkflowDefDTO.class);
        assertNull(resolveEventKey(notWait, "w"), "只有 type=wait 的节点才是等待点");
    }

    // ==================== ⑤ 🔴 编译期与运行期对同一种写法必须同进同退 ====================

    @Test
    @DisplayName("🔴 编译器放行的每一种写法，运行期这两个反查方法也必须认得出来（两侧各走自己的真实入口）")
    void runtimeAgreesWithTheCompilerOnEverySpelling() throws Exception {
        // 🔴 两侧都必须走<b>真实入口</b>：编译期走 validateWaitConfig，运行期走 Service 那两个 private 反查方法。
        //   本条第一版是拿 {@code WaitTypeEnum.parse} 当「运行期那一侧」—— 而 validateWaitConfig 自己也调 parse，
        //   于是两侧恒等、这条<b>永远绿</b>。变异探针实测过：把 parse 的 trim 拿掉、或把它改成 equalsIgnoreCase，
        //   本条都不红（红的是 ① 与 ②）。那是最危险的一种假绿：它看起来像在比两侧，其实只在比它自己。
        //   改成走 Service 的方法之后，「有人在 Service 里把 parse 换回裸 equals」才会真的红。
        for (String raw : List.of("event", " event ", "EVENT", "duration", " duration\t",
                "Duration", "hitl", "telepathy", "", "   ")) {
            WorkflowDefDTO dto = waitDef(raw);
            // 两侧看到的是<b>同一个</b> NodeDTO：差别只可能来自「谁在读它」，不可能来自「读了不同的东西」
            boolean compilerRejected = compilerRejects(dto.getNodes().get(0));
            // 运行期「认得出来」= 两个反查方法里至少一个给出了要落库的非 null 值
            // （event 档给 event_key，duration 档给 resume_at；hitl 两档都不给）
            boolean runtimeRecognised =
                    resolveEventKey(dto, "w") != null || resolveResumeAt(dto, "w") != null;
            assertEquals(compilerRejected, !runtimeRecognised,
                    "写法 " + quote(raw) + " 在编译期与运行期分叉了：编译期"
                            + (compilerRejected ? "拒" : "放行") + "，运行期"
                            + (runtimeRecognised ? "认得出来" : "认不出来"));
        }
    }

    /**
     * 一份 wait 节点 DSL：{@code waitType} 逐字用 {@code rawWaitType}，{@code duration} 与 {@code eventKey} 都填好
     *
     * <p>两个载荷都填是为了让「取值写法」成为<b>唯一</b>的变量：否则某一档红了，
     * 分不清是取值没认出来、还是那一档自己的载荷缺失。
     * 取值经 Jackson 序列化再反序列化 ⟹ 空格与制表符逐字保真，不会被测试自己 trim 掉。
     */
    private static WorkflowDefDTO waitDef(String rawWaitType) throws Exception {
        return def("{\"waitType\":" + MAPPER.writeValueAsString(rawWaitType)
                + ",\"duration\":30,\"eventKey\":\"order_paid\"}");
    }

    /** 编译期权威 {@code validateWaitConfig} 对这个节点是不是拒的 */
    private static boolean compilerRejects(NodeDTO node) {
        try {
            DslToStateGraphCompiler.validateWaitConfig(node);
            return false;
        } catch (ServiceException e) {
            return true;
        }
    }

    private static String quote(String raw) {
        return "\"" + raw + "\"";
    }

}
