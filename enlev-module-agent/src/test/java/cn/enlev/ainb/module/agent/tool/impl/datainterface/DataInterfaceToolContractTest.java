package cn.enlev.ainb.module.agent.tool.impl.datainterface;

import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据接口工具的<b>声明契约</b>测试（安全关键）
 *
 * <h3>🔴 钉死什么、为什么用反射钉</h3>
 * {@code data_interface_invoke} 是全能力执行（可写库、可 POST 外网），它的安全门
 * <b>只有</b> {@code @AgentTool} 上声明的那两样：
 * ① permission = {@code infra:data-interface:invoke} —— 与 infra {@code /invoke} 端点的
 *    {@code @PreAuthorize} 同一权限串，由 {@code AgentSecurityGateway.checkTool} 逐字校验；
 * ② risk = HIGH —— requireConfirm=true，助手路径进 Plan 等用户确认。
 *
 * <p>工具 execute 里没有任何权限代码（这是对的，网关管得住静态权限），
 * 所以「有人顺手把 permission 改成 agent:assistant:use 或把 risk 降成 LOW」
 * 不会被任何行为测试发现 —— 只会在生产里变成越权写。本测试用反射读注解，
 * 把这两个声明钉死；改动必须过这里，等于强制过一次 code review 的「你确定要放开写门？」。
 *
 * <p>只读两件套同样钉死：permission=agent:assistant:use（与 infra /options 登录级同口径）、
 * risk=READ_ONLY（不进 Plan，可 tool_calls 直调）。
 *
 * @author 走进新时代
 */
public class DataInterfaceToolContractTest {

    private static AgentTool annotationOf(Class<?> toolClass) {
        AgentTool annotation = toolClass.getAnnotation(AgentTool.class);
        assertNotNull(annotation, toolClass.getSimpleName() + " 缺少 @AgentTool 声明");
        return annotation;
    }

    @Test
    @DisplayName("🔴 invoke：permission 逐字 infra:data-interface:invoke、risk=HIGH 且 requireConfirm=true（写门不许放宽）")
    public void testInvokeContract() {
        AgentTool annotation = annotationOf(DataInterfaceInvokeTool.class);

        assertEquals("data_interface_invoke", annotation.name());
        assertEquals("infra:data-interface:invoke", annotation.permission());
        assertEquals(0, annotation.extraPermissions().length);
        assertEquals(RiskLevel.HIGH, annotation.risk());
        assertTrue(annotation.risk().isRequireConfirm(), "写工具必须进 Plan 确认");
        assertTrue(annotation.enabled());
    }

    @Test
    @DisplayName("query：permission=agent:assistant:use、risk=READ_ONLY（与 infra /options 登录级同口径）")
    public void testQueryContract() {
        AgentTool annotation = annotationOf(DataInterfaceQueryTool.class);

        assertEquals("data_interface_query", annotation.name());
        assertEquals("agent:assistant:use", annotation.permission());
        assertEquals(RiskLevel.READ_ONLY, annotation.risk());
        assertFalse(annotation.risk().isRequireConfirm(), "只读工具应可直调");
    }

    @Test
    @DisplayName("list：permission=agent:assistant:use、risk=READ_ONLY")
    public void testListContract() {
        AgentTool annotation = annotationOf(DataInterfaceListTool.class);

        assertEquals("data_interface_list", annotation.name());
        assertEquals("agent:assistant:use", annotation.permission());
        assertEquals(RiskLevel.READ_ONLY, annotation.risk());
        assertFalse(annotation.risk().isRequireConfirm());
    }

}
