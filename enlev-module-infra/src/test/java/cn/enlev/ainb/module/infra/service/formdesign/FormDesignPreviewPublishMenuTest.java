package cn.enlev.ainb.module.infra.service.formdesign;

import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDesignMenuPreviewRespDTO;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDesignPublishRespDTO;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignDO;
import cn.enlev.ainb.module.infra.dal.mysql.formdesign.FormDesignMapper;
import cn.enlev.ainb.module.infra.dal.mysql.formdesign.FormDesignMenuMapper;
import cn.enlev.ainb.module.infra.dal.mysql.formdesign.FormDesignVersionMapper;
import cn.enlev.ainb.module.infra.framework.formdesign.core.resource.FormResourceDescriptorBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static cn.enlev.ainb.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.FORM_DESIGN_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code previewPublishMenu} 的单元测试
 *
 * <h3>🔴 这个方法存在的唯一理由就是「一行都不写」，所以那一条被单独钉住</h3>
 * 它是给变更清单的干跑器用的只读出口。清单要在用户点同意<b>之前</b>说出
 * 「这次发布会不会建菜单、建成什么样」，而判据（表单当前有没有绑过菜单）光看 DSL 算不出来。
 *
 * <p>危险的替代品是 {@code publishFormDesign} —— 它<b>真发布</b>：存版本快照、改 status、
 * 在事务内同步广播权限资源登记事件。它的注释写「发布不会建菜单」，说的是<b>菜单</b>不建，
 * 不是说它没有副作用。所以 {@link #writesNothing()} 用
 * {@code verifyNoMoreInteractions} + {@code verifyNoInteractions} 把七个协作者全查一遍：
 * 只许 {@code selectById} 一次，别的一个都不许碰。
 *
 * <h3>另一条容易搞反的：{@code parentMenuId=0} 是合法的</h3>
 * 它表示「挂到顶层菜单」（{@code buildMenuPath} 把 null 与 0 同等对待，
 * {@code FormDesignApi#publishFormDesign} 的注释也这么写）。
 * 而 {@code publish} 只在 {@code parentMenuId == null} 时抛
 * {@code FORM_DESIGN_MENU_PARENT_REQUIRED}。
 * 把 0 也算成「没给」的话，清单会对一个合法输入喊「执行时会被拒」——
 * 误报和漏报一样会让用户不再相信清单，见 {@link #zeroParentMeansTopLevelNotMissing()}。
 *
 * @author 走进新时代
 */
@ExtendWith(MockitoExtension.class)
public class FormDesignPreviewPublishMenuTest {

    @InjectMocks
    private FormDesignServiceImpl service;

    @Mock
    private FormDesignMapper formDesignMapper;
    @Mock
    private FormDesignVersionMapper formDesignVersionMapper;
    @Mock
    private FormDesignMenuMapper formDesignMenuMapper;
    @Mock
    private FormDesignVersionService versionService;
    @Mock
    private FormDesignPublishValidator publishValidator;
    @Mock
    private FormResourceDescriptorBuilder resourceDescriptorBuilder;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private FormDesignDO formDesign(Long menuId) {
        FormDesignDO formDesign = new FormDesignDO();
        formDesign.setId(1L);
        formDesign.setCode("employee");
        formDesign.setName("员工信息");
        formDesign.setSort(5);
        formDesign.setMenuId(menuId);
        return formDesign;
    }

    @Test
    @DisplayName("首发：预演出 1 个组件菜单 + 8 个按钮权限，路径与权限串逐字可核")
    void firstPublishPreviewsOneMenuAndSevenPermissions() {
        when(formDesignMapper.selectById(1L)).thenReturn(formDesign(null));

        FormDesignMenuPreviewRespDTO resp = service.previewPublishMenu(1L, 2L);

        assertTrue(resp.getNeedCreateMenu());
        assertFalse(resp.getParentMenuMissing());
        assertNull(resp.getExistingMenuId());
        assertEquals("employee", resp.getCode());
        assertEquals("员工信息", resp.getName());

        FormDesignPublishRespDTO.MenuConfig menu = resp.getMenuConfig();
        assertEquals("员工信息", menu.getName());
        // 嵌套时是相对路径：顶层带前导 / 、嵌套不带，写反了菜单在库里但点进去白屏
        assertEquals("form-design-run/employee", menu.getPath());
        assertEquals("infra/formDesign/render/index", menu.getComponent());
        assertEquals("FormDesignRunEmployee", menu.getComponentName());
        assertEquals(2L, menu.getParentId());
        assertEquals(5, menu.getSort(), "reqVO 没给 menuSort，就该落回表单自己的 sort");
        assertEquals("ep:document", menu.getIcon());

        // 🔴 8 个，一个都不能少。export/import 少了的表现是「按钮点了没反应」，
        //    reference 少了的表现是「想收紧表单间关联时根本没有可授权的对象」，
        //    view-plain 少了的表现是「想收紧『谁能看脱敏字段明文』时没有可授权的对象」——
        //    而它比其他几个更要紧：plain-field 端点的全部意义就是绕过脱敏，
        //    没有这一行的话「能改数据」就永远等于「能看明文」，拆不开
        assertEquals(List.of(
                "infra:form-design-run:employee:query",
                "infra:form-design-run:employee:create",
                "infra:form-design-run:employee:update",
                "infra:form-design-run:employee:delete",
                "infra:form-design-run:employee:export",
                "infra:form-design-run:employee:import",
                "infra:form-design-run:employee:reference",
                "infra:form-design-run:employee:view-plain"),
                menu.getPermissions().stream()
                        .map(FormDesignPublishRespDTO.PermissionConfig::getPermission).toList());
        assertEquals(List.of("员工信息查询", "员工信息新增", "员工信息修改", "员工信息删除",
                        "员工信息导出", "员工信息导入", "员工信息被引用", "员工信息查看明文"),
                menu.getPermissions().stream()
                        .map(FormDesignPublishRespDTO.PermissionConfig::getName).toList());
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8),
                menu.getPermissions().stream()
                        .map(FormDesignPublishRespDTO.PermissionConfig::getSort).toList());
    }

    @Test
    @DisplayName("🔴 parentMenuId=0 是「挂顶层」，不是「没给」—— 不许对它喊会被拒")
    void zeroParentMeansTopLevelNotMissing() {
        when(formDesignMapper.selectById(1L)).thenReturn(formDesign(null));

        FormDesignMenuPreviewRespDTO resp = service.previewPublishMenu(1L, 0L);

        assertFalse(resp.getParentMenuMissing(), "0L 是合法写法，publish 不会拒它");
        // 顶层必须是绝对路径，否则 vue-router 抛 Route paths should start with a "/"
        assertEquals("/form-design-run/employee", resp.getMenuConfig().getPath());
        assertEquals(0L, resp.getMenuConfig().getParentId());
    }

    @Test
    @DisplayName("🔴 首发但 parentMenuId 没给：如实标出来，而不是抛异常")
    void nullParentOnFirstPublishIsReportedNotThrown() {
        when(formDesignMapper.selectById(1L)).thenReturn(formDesign(null));

        // publish 在这种组合下抛 FORM_DESIGN_MENU_PARENT_REQUIRED。预演刻意不抛 ——
        // 清单要做的恰恰是在用户点同意之前把这句说出来，而不是让他执行之后看见报错
        FormDesignMenuPreviewRespDTO resp = service.previewPublishMenu(1L, null);

        assertTrue(resp.getParentMenuMissing());
        assertTrue(resp.getNeedCreateMenu());
        // 配置照给：「补上 parentMenuId 之后会建成什么样」也是有用的信息
        assertEquals("/form-design-run/employee", resp.getMenuConfig().getPath());
    }

    @Test
    @DisplayName("🔴 再发布不建菜单 —— 硬算的话清单会报一件不会发生的事")
    void republishDoesNotCreateMenu() {
        when(formDesignMapper.selectById(1L)).thenReturn(formDesign(999L));

        FormDesignMenuPreviewRespDTO resp = service.previewPublishMenu(1L, 2L);

        assertFalse(resp.getNeedCreateMenu());
        assertEquals(999L, resp.getExistingMenuId());
        assertNull(resp.getMenuConfig(), "压根不会建，给一份配置出来只会让人以为要建");
        assertNull(resp.getParentMenuMissing(), "不建菜单就无所谓上级菜单给没给");
    }

    @Test
    @DisplayName("表单不存在时抛 ServiceException —— 干跑器按「诊断信息」接，不许吞成「没问题」")
    void missingFormDesignThrowsServiceException() {
        when(formDesignMapper.selectById(404L)).thenReturn(null);

        assertServiceException(() -> service.previewPublishMenu(404L, 2L), FORM_DESIGN_NOT_EXISTS);
    }

    @Test
    @DisplayName("🔴 一行都不写：七个协作者里只许 selectById 一次")
    void writesNothing() {
        when(formDesignMapper.selectById(1L)).thenReturn(formDesign(null));

        service.previewPublishMenu(1L, 2L);

        verify(formDesignMapper).selectById(1L);
        verifyNoMoreInteractions(formDesignMapper);
        verify(formDesignMapper, never()).insert(any(FormDesignDO.class));
        verify(formDesignMapper, never()).updateById(any(FormDesignDO.class));
        // 版本快照、菜单关联、发布校验、权限资源描述、发布事件 —— 一个都不许碰。
        // 碰了就意味着「为了看一眼菜单长什么样，把一条流程真的发布掉了」
        verifyNoInteractions(formDesignVersionMapper, formDesignMenuMapper, versionService,
                publishValidator, resourceDescriptorBuilder, eventPublisher);
    }

}
