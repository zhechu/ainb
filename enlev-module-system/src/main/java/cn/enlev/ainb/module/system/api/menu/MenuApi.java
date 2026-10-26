package cn.enlev.ainb.module.system.api.menu;

import cn.enlev.ainb.module.system.api.menu.dto.MenuCreateReqDTO;
import cn.enlev.ainb.module.system.api.menu.dto.MenuSimpleRespDTO;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 菜单 API 接口
 *
 * 供 agent 模块使用（方案 §5.6 form_publish_bundle）：
 * 表单发布后要建主菜单与按钮权限菜单，再把这批菜单编号回写给表单、分配给角色。
 *
 * <h3>为什么 infra 不能自己建菜单</h3>
 * infra 不能反向依赖 system（循环依赖），且走真正的 {@code MenuService.createMenu}
 * 才能触发 {@code @CacheEvict}（{@code FormDesignServiceImpl.java:383-388}）。
 * 绕过 Service 直接写库会让菜单缓存保留旧数据，
 * 表现是「菜单在库里但界面上看不到」。
 *
 * @author 走进新时代
 */
public interface MenuApi {

    /**
     * 创建菜单
     *
     * @param reqDTO 菜单定义
     * @return 菜单编号
     */
    Long createMenu(MenuCreateReqDTO reqDTO);

    /**
     * 删除菜单（及其子菜单）
     *
     * @param id 菜单编号
     */
    void deleteMenu(Long id);

    /**
     * 赋予角色菜单（全量替换，grant-enabled 下走 sys_grant）
     *
     * 方案 §5.6 第 5 步：把新建的菜单合并进角色现有的菜单集合再回写。
     *
     * ⚠️ 用 {@code assignRoleMenuGrants} 而不是 {@code assignRoleMenu}：
     * 本项目 grant-enabled=true，鉴权读的是 {@code sys_grant} 而不是
     * {@code system_role_menu}，只写后者表现为「分配了但没生效」。
     *
     * @param roleId  角色编号
     * @param menuIds 该角色的全量菜单编号集合（含原有的，不是增量）
     */
    void assignRoleMenus(Long roleId, Set<Long> menuIds);

    /**
     * 获取角色拥有的菜单编号集合
     *
     * @param roleId 角色编号
     * @return 菜单编号集合
     */
    Set<Long> getRoleMenuIds(Long roleId);

    /**
     * 获取多个角色的菜单编号并集
     *
     * @param roleIds 角色编号集合
     * @return 菜单编号集合（并集）
     */
    Set<Long> getRoleMenuIds(Collection<Long> roleIds);

    /**
     * 获取启用中的目录（type=1）清单
     *
     * 供「按名字挂菜单」使用：用户说的是「发布到在线示例菜单」这样的<b>名字</b>，
     * 而建菜单要的是 parentId 数字。调用方拿这份清单自己做名字→id 的匹配，
     * 匹配不上时也能把可选目录名报回去，而不是静默挂到顶层。
     *
     * @return 目录清单（id/name/parentId/type）
     */
    List<MenuSimpleRespDTO> getEnabledDirectoryList();

}
