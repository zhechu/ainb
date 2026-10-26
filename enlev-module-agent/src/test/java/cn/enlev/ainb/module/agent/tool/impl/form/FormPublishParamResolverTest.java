package cn.enlev.ainb.module.agent.tool.impl.form;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.system.api.menu.MenuApi;
import cn.enlev.ainb.module.system.api.menu.dto.MenuSimpleRespDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@link FormPublishParamResolver} 单测
 *
 * <h3>为什么这些文案要逐字钉住</h3>
 * 本类原先是 {@code FormPublishBundleTool} 的三个私有方法，S2-d3b 抽出来是为了让
 * 变更清单的干跑器读同一份「按名字反查上级目录」。抽出来后<b>调用方从一个变成两个</b>：
 * 工具那边它是一次工具调用失败的报错（给模型看，模型照它改参数），
 * 干跑器那边它是清单上的拒绝理由（给审批人看，他照它决定要不要点同意）。
 * 同一句话两个人读，改一个字都可能让其中一边读不通 —— 所以这里断言的是<b>整句</b>，
 * 不是 {@code contains}。
 *
 * <p>这些字符串是从工具里<b>原样搬过来</b>的，本测试同时充当那次搬迁的等价性证明：
 * 搬迁前后跑的是同一批断言。
 *
 * @author 走进新时代
 */
public class FormPublishParamResolverTest extends BaseMockitoUnitTest {

    private static final String PREFIX = "工具(form_publish_bundle)的参数不合法：";

    @Mock
    private MenuApi menuApi;

    @InjectMocks
    private FormPublishParamResolver resolver;

    private static MenuSimpleRespDTO dir(Long id, String name) {
        MenuSimpleRespDTO dto = new MenuSimpleRespDTO();
        dto.setId(id);
        dto.setName(name);
        dto.setType(1);
        return dto;
    }

    private String messageOf(Runnable call) {
        ServiceException ex = assertThrows(ServiceException.class, call::run);
        return ex.getMessage();
    }

    // ==================== 表单编号 ====================

    @Test
    @DisplayName("表单编号：数字与数字串都收")
    void formId_acceptsNumberAndNumericString() {
        assertEquals(1024L, resolver.resolveFormId(Map.of("id", 1024)));
        assertEquals(1024L, resolver.resolveFormId(Map.of("id", "1024")));
    }

    @Test
    @DisplayName("表单编号缺失：逐字报错")
    void formId_missing() {
        assertEquals(PREFIX + "缺少 id（表单编号）",
                messageOf(() -> resolver.resolveFormId(Map.of())));
    }

    @Test
    @DisplayName("表单编号不是数字：逐字报错，并把收到的原值带回去")
    void formId_notNumeric() {
        assertEquals(PREFIX + "id 必须是数字，实际收到：abc",
                messageOf(() -> resolver.resolveFormId(Map.of("id", "abc"))));
    }

    // ==================== 上级菜单 ====================

    @Test
    @DisplayName("按名字命中目录：返回它的编号")
    void parentMenu_byName() {
        when(menuApi.getEnabledDirectoryList()).thenReturn(List.of(dir(2600L, "在线示例")));
        assertEquals(2600L, resolver.resolveParentMenuId(Map.of("parentMenuName", "在线示例")));
    }

    @Test
    @DisplayName("按名字对不上：报出可选目录，绝不静默挂顶层")
    void parentMenu_unknownName() {
        when(menuApi.getEnabledDirectoryList())
                .thenReturn(List.of(dir(2600L, "在线示例"), dir(1L, "系统管理")));

        assertEquals(PREFIX + "找不到名为「人事管理」的启用目录，无法发布到该菜单下。"
                        + "可选目录有：在线示例、系统管理。请改用正确的 parentMenuName，或改传 parentMenuId",
                messageOf(() -> resolver.resolveParentMenuId(Map.of("parentMenuName", "人事管理"))));
    }

    @Test
    @DisplayName("一个启用目录都没有时，候选清单显示「（无）」而不是空着")
    void parentMenu_noDirectoriesAtAll() {
        when(menuApi.getEnabledDirectoryList()).thenReturn(List.of());

        String message = messageOf(
                () -> resolver.resolveParentMenuId(Map.of("parentMenuName", "在线示例")));
        assertTrue(message.contains("可选目录有：（无）"), message);
    }

    @Test
    @DisplayName("🔴 名字与编号都不给：返回 0L（顶层），<b>永不返回 null</b>")
    void parentMenu_absentMeansTopLevelNeverNull() {
        // 🔴 这条不变量是干跑器不冤枉人的前提：publish 的拒绝判据是
        //    firstPublish && parentMenuId == null，走本工具的路径永远凑不出 null。
        //    干跑器若自己传 null 进 previewPublishMenu，就会报一件本工具不会犯的错
        assertEquals(0L, resolver.resolveParentMenuId(Map.of()));
        assertEquals(0L, resolver.resolveParentMenuId(Map.of("parentMenuId", "0")));
        assertEquals(0L, resolver.resolveParentMenuId(Map.of("parentMenuId", "")));
        assertEquals(0L, resolver.resolveParentMenuId(Map.of("parentMenuId", "   ")));
    }

    @Test
    @DisplayName("显式编号：原样用")
    void parentMenu_byId() {
        assertEquals(2048L, resolver.resolveParentMenuId(Map.of("parentMenuId", "2048")));
        assertEquals(2048L, resolver.resolveParentMenuId(Map.of("parentMenuId", 2048)));
    }

    @Test
    @DisplayName("显式编号不是数字：逐字报错，并说明 0 的含义")
    void parentMenu_idNotNumeric() {
        assertEquals(PREFIX + "parentMenuId 必须是数字（不传或传 0 表示挂到顶层），实际收到：顶层",
                messageOf(() -> resolver.resolveParentMenuId(Map.of("parentMenuId", "顶层"))));
    }

    @Test
    @DisplayName("名字优先于编号：两个都给时按名字查，编号被忽略")
    void parentMenu_nameWinsOverId() {
        when(menuApi.getEnabledDirectoryList()).thenReturn(List.of(dir(2600L, "在线示例")));
        assertEquals(2600L, resolver.resolveParentMenuId(
                Map.of("parentMenuName", "在线示例", "parentMenuId", "9999")));
    }

    // ==================== 角色 ====================

    @Test
    @DisplayName("角色可省略：不给或给空数组都返回空列表")
    void roleIds_optional() {
        assertEquals(List.of(), resolver.resolveRoleIds(Map.of()));
        assertEquals(List.of(), resolver.resolveRoleIds(Map.of("roleIds", List.of())));
    }

    @Test
    @DisplayName("角色编号：数字与数字串混着给都收")
    void roleIds_mixedTypes() {
        assertEquals(List.of(101L, 102L), resolver.resolveRoleIds(Map.of("roleIds", List.of(101, "102"))));
    }

    @Test
    @DisplayName("角色编号不是数字数组：逐字报错")
    void roleIds_notNumeric() {
        assertEquals(PREFIX + "roleIds 必须是数字数组，实际收到：[销售员]",
                messageOf(() -> resolver.resolveRoleIds(Map.of("roleIds", List.of("销售员")))));
    }

}
