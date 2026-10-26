package cn.enlev.ainb.module.system.api.menu;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.module.system.api.menu.dto.MenuCreateReqDTO;
import cn.enlev.ainb.module.system.api.menu.dto.MenuSimpleRespDTO;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.menu.MenuSaveVO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.MenuDO;
import cn.enlev.ainb.module.system.enums.permission.MenuTypeEnum;
import cn.enlev.ainb.module.system.service.permission.MenuService;
import cn.enlev.ainb.module.system.service.permission.PermissionService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static cn.enlev.ainb.framework.common.util.collection.CollectionUtils.convertList;
import static cn.enlev.ainb.framework.common.util.collection.CollectionUtils.filterList;

/**
 * 菜单 API 实现类
 *
 * 每个方法都是对 {@link MenuService} / {@link PermissionService} 的直接委托，
 * 只做 DTO ↔ VO 搬运，不含业务逻辑。
 *
 * @author 走进新时代
 */
@Service
public class MenuApiImpl implements MenuApi {

    @Resource
    private MenuService menuService;
    @Resource
    private PermissionService permissionService;

    @Override
    public Long createMenu(MenuCreateReqDTO reqDTO) {
        return menuService.createMenu(BeanUtils.toBean(reqDTO, MenuSaveVO.class));
    }

    @Override
    public void deleteMenu(Long id) {
        menuService.deleteMenu(id);
    }

    @Override
    public void assignRoleMenus(Long roleId, Set<Long> menuIds) {
        // 🔴 必须走 assignRoleMenuGrants（全量对齐 sys_grant），不能走 assignRoleMenu。
        //    本项目 grant-enabled=true，鉴权读的是 sys_grant，只写 system_role_menu
        //    的表现是「分配了但没生效」—— 菜单出现在设置界面，但前端路由拿不到。
        permissionService.assignRoleMenuGrants(roleId, menuIds);
    }

    @Override
    public Set<Long> getRoleMenuIds(Long roleId) {
        return permissionService.getRoleMenuListByRoleId(roleId);
    }

    @Override
    public Set<Long> getRoleMenuIds(Collection<Long> roleIds) {
        return permissionService.getRoleMenuListByRoleId(roleIds);
    }

    @Override
    public List<MenuSimpleRespDTO> getEnabledDirectoryList() {
        // 只取「启用中的目录（type=1）」：菜单要挂在目录下，且关闭的目录挂上去也不显示。
        // getMenuList() 返回全部（含关闭），这里按 type + status 过滤，BeanUtils 搬运成精简 DTO。
        List<MenuDO> dirs = filterList(menuService.getMenuList(),
                menu -> MenuTypeEnum.DIR.getType().equals(menu.getType())
                        && CommonStatusEnum.ENABLE.getStatus().equals(menu.getStatus()));
        return convertList(dirs, menu -> BeanUtils.toBean(menu, MenuSimpleRespDTO.class));
    }

}
