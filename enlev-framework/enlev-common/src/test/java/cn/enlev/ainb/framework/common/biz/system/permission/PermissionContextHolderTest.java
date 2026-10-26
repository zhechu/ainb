package cn.enlev.ainb.framework.common.biz.system.permission;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 权限上下文持有者
 *
 * <h3>这批用例挡的是什么</h3>
 *
 * 字段模式的三层合并里，「上下文」= 审批节点。这一层 S2b 就实现了、三条校验也有单测，
 * 但 {@code contextId} 一直恒传 null —— 也就是说它在线上<b>从未被触发过</b>：
 * 在流程设计器里给某个节点配的字段权限，<b>配了等于没配</b>，
 * 不报错、界面正常。
 *
 * S6d 把它接上之后，上下文的<b>泄漏与丢失</b>就都成了新的风险：
 *
 * <ul>
 *   <li><b>泄漏</b> —— 上下文没清干净，串到同线程的下一个请求上，
 *       于是某人在完全无关的页面上拿到了某个审批节点的字段权限</li>
 *   <li><b>丢失</b> —— 嵌套调用结束时把外层的也清掉，
 *       外层剩下的语句突然退回全局判定，同样是静默的</li>
 * </ul>
 *
 * @author 走进新时代
 */
class PermissionContextHolderTest {

    private static final String NODE_A = "bpm:node:leave:Activity_A";
    private static final String NODE_B = "bpm:node:leave:Activity_B";

    @AfterEach
    void tearDown() {
        // ThreadLocal 是同线程复用的，留着会串到下一个用例
        PermissionContextHolder.execute(null, () -> {
        });
    }

    @Test
    @DisplayName("基本：进入上下文可读到，退出后恢复为空")
    void testBasic() {
        assertNull(PermissionContextHolder.get());

        String inside = PermissionContextHolder.execute(NODE_A, PermissionContextHolder::get);

        assertEquals(NODE_A, inside);
        assertNull(PermissionContextHolder.get(), "退出后必须清干净，否则会串到同线程的下一个请求");
    }

    @Test
    @DisplayName("⚠️ 嵌套：内层结束后外层上下文要恢复，而不是被清空")
    void testNestedRestoresOuter() {
        String outerAfterInner = PermissionContextHolder.execute(NODE_A, () -> {
            PermissionContextHolder.execute(NODE_B, () -> null);
            // 内层结束了，这里应当仍是 A
            return PermissionContextHolder.get();
        });

        assertEquals(NODE_A, outerAfterInner,
                "内层把外层的也清掉的话，外层剩下的语句会突然退回全局判定 —— 而那是静默的");
        assertNull(PermissionContextHolder.get());
    }

    @Test
    @DisplayName("⚠️ 抛异常时也要还原 —— 否则异常之后整条线程都带着脏上下文")
    void testRestoredOnException() {
        assertThrows(IllegalStateException.class, () ->
                PermissionContextHolder.execute(NODE_A, () -> {
                    throw new IllegalStateException("boom");
                }));

        assertNull(PermissionContextHolder.get(),
                "异常路径不还原的话，这个线程后续处理的所有请求都会带着某个审批节点的上下文");
    }

    @Test
    @DisplayName("上下文为 null 时不设置 —— 拿不到节点信息就退回全局判定")
    void testNullContext() {
        String inside = PermissionContextHolder.execute(null, PermissionContextHolder::get);

        assertNull(inside);
    }

    @Test
    @DisplayName("Runnable 重载与 Supplier 行为一致")
    void testRunnableOverload() {
        StringBuilder seen = new StringBuilder();
        PermissionContextHolder.execute(NODE_A, () -> seen.append(PermissionContextHolder.get()));

        assertEquals(NODE_A, seen.toString());
        assertNull(PermissionContextHolder.get());
    }

}
