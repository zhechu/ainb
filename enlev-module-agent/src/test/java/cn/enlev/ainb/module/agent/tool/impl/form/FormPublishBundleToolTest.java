package cn.enlev.ainb.module.agent.tool.impl.form;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDesignPublishRespDTO;
import cn.enlev.ainb.module.system.api.menu.MenuApi;
import cn.enlev.ainb.module.system.api.menu.dto.MenuSimpleRespDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FormPublishBundleTool} 的上级菜单解析回归
 *
 * <p>锁死「指定发布到在线示例菜单，却发布到顶级菜单去了」：用户说的是菜单<b>名字</b>，
 * 工具要按名字反查目录 id 再发布；名字对不上时必须报错并列出可选目录，绝不静默挂顶层。
 */
public class FormPublishBundleToolTest extends BaseMockitoUnitTest {

    @Mock
    private FormDesignApi formDesignApi;
    @Mock
    private MenuApi menuApi;

    @InjectMocks
    private FormPublishBundleTool tool;

    /**
     * 解析器用<b>真</b>的、只把 {@code MenuApi} 换成 mock
     *
     * <p>本测试锁的正是「按名字反查目录」这条行为，把解析器整个 mock 掉就等于没测。
     */
    @BeforeEach
    void wireResolver() {
        FormPublishParamResolver resolver = new FormPublishParamResolver();
        ReflectionTestUtils.setField(resolver, "menuApi", menuApi);
        ReflectionTestUtils.setField(tool, "paramResolver", resolver);
    }

    private FormDesignPublishRespDTO noMenuNeededResp() {
        FormDesignPublishRespDTO resp = new FormDesignPublishRespDTO();
        resp.setVersion(1);
        resp.setNeedCreateMenu(false);
        return resp;
    }

    private MenuSimpleRespDTO dir(Long id, String name) {
        MenuSimpleRespDTO dto = new MenuSimpleRespDTO();
        dto.setId(id);
        dto.setName(name);
        dto.setType(1);
        return dto;
    }

    @Test
    @DisplayName("按名字「在线示例」解析出目录 id，并用它发布")
    public void testResolveParentMenuByName() {
        when(menuApi.getEnabledDirectoryList()).thenReturn(List.of(dir(2600L, "在线示例")));
        when(formDesignApi.publishFormDesign(eq(5L), anyLong())).thenReturn(noMenuNeededResp());

        tool.execute(Map.of("id", 5, "parentMenuName", "在线示例"));

        // 发布时透传的 parentMenuId 必须是按名字反查到的 2600，而不是 0（顶层）
        verify(formDesignApi).publishFormDesign(5L, 2600L);
    }

    @Test
    @DisplayName("名字对不上时报错并列出可选目录，不静默挂顶层")
    public void testUnknownParentMenuNameFails() {
        when(menuApi.getEnabledDirectoryList()).thenReturn(List.of(dir(2600L, "在线示例"), dir(1L, "系统管理")));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> tool.execute(Map.of("id", 5, "parentMenuName", "不存在的菜单")));

        assertTrue(ex.getMessage().contains("不存在的菜单"), ex.getMessage());
        assertTrue(ex.getMessage().contains("在线示例"), ex.getMessage());
        // 名字没解析成功前绝不能发布
        verify(formDesignApi, never()).publishFormDesign(anyLong(), anyLong());
    }

}
