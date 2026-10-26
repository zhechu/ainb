package cn.enlev.ainb.module.system.convert.auth;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.module.system.api.sms.dto.code.SmsCodeSendReqDTO;
import cn.enlev.ainb.module.system.api.sms.dto.code.SmsCodeUseReqDTO;
import cn.enlev.ainb.module.system.api.social.dto.SocialUserBindReqDTO;
import cn.enlev.ainb.module.system.controller.admin.auth.vo.AuthPermissionInfoRespVO;
import cn.enlev.ainb.module.system.controller.admin.auth.vo.AuthSmsLoginReqVO;
import cn.enlev.ainb.module.system.controller.admin.auth.vo.AuthSmsSendReqVO;
import cn.enlev.ainb.module.system.controller.admin.auth.vo.AuthSocialLoginReqVO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.MenuDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.RoleDO;
import cn.enlev.ainb.module.system.dal.dataobject.user.AdminUserDO;
import cn.enlev.ainb.module.system.enums.permission.MenuTypeEnum;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;
import org.slf4j.LoggerFactory;

import java.util.*;

import static cn.enlev.ainb.framework.common.util.collection.CollectionUtils.convertSet;
import static cn.enlev.ainb.framework.common.util.collection.CollectionUtils.filterList;
import static cn.enlev.ainb.module.system.dal.dataobject.permission.MenuDO.ID_ROOT;

@Mapper
public interface AuthConvert {

    AuthConvert INSTANCE = Mappers.getMapper(AuthConvert.class);

    default AuthPermissionInfoRespVO convert(AdminUserDO user, List<RoleDO> roleList, List<MenuDO> menuList) {
        return AuthPermissionInfoRespVO.builder()
                .user(BeanUtils.toBean(user, AuthPermissionInfoRespVO.UserVO.class))
                .roles(convertSet(roleList, RoleDO::getCode))
                // 权限标识信息
                .permissions(convertSet(menuList, MenuDO::getPermission))
                // 菜单树
                .menus(buildMenuTree(menuList))
                .build();
    }

    /**
     * 同上，但<b>权限标识由调用方给定</b>，不再从菜单列表推导
     *
     * 给新授权引擎（{@code grant-enabled: true}）用：权限清单要来自 {@code sys_grant} 的判定，
     * 而不是 {@code system_role_menu}。两者分叉时的表现是
     * 「后端放行、前端按钮不显示」，且没有任何报错 —— 见
     * {@code GrantDecisionService#listAllowedPermissions} 的注释。
     *
     * ⚠️ 菜单树来自 menuList，而 menuList 装的是什么由调用方决定：
     * {@code grant-enabled} 打开时是 {@code GrantDecisionService#listAllowedMenuIds} 的结果
     * （同一套判定），关闭时才是 {@code system_role_menu}。
     * 两者必须同源 —— 只切一半会出现「按钮有了、但进不去那个页面」
     */
    default AuthPermissionInfoRespVO convert(AdminUserDO user, List<RoleDO> roleList,
                                             List<MenuDO> menuList, Set<String> permissions) {
        return AuthPermissionInfoRespVO.builder()
                .user(BeanUtils.toBean(user, AuthPermissionInfoRespVO.UserVO.class))
                .roles(convertSet(roleList, RoleDO::getCode))
                .permissions(permissions)
                .menus(buildMenuTree(menuList))
                .build();
    }

    /**
     * 将菜单列表，构建成菜单树
     *
     * @param menuList 菜单列表
     * @return 菜单树
     */
    default List<AuthPermissionInfoRespVO.MenuVO> buildMenuTree(List<MenuDO> menuList) {
        if (CollUtil.isEmpty(menuList)) {
            return Collections.emptyList();
        }
        // 移除按钮
        menuList.removeIf(menu -> menu.getType().equals(MenuTypeEnum.BUTTON.getType()));
        // 排序，保证菜单的有序性
        menuList.sort(Comparator.comparing(MenuDO::getSort));

        // 构建菜单树
        // 使用 LinkedHashMap 的原因，是为了排序 。实际也可以用 Stream API ，就是太丑了。
        Map<Long, AuthPermissionInfoRespVO.MenuVO> treeNodeMap = new LinkedHashMap<>();
        menuList.forEach(menu -> treeNodeMap.put(menu.getId(),
                BeanUtils.toBean(menu, AuthPermissionInfoRespVO.MenuVO.class)));
        // 处理父子关系
        treeNodeMap.values().stream().filter(node -> ObjUtil.notEqual(node.getParentId(), ID_ROOT)).forEach(childNode -> {
            // 获得父节点
            AuthPermissionInfoRespVO.MenuVO parentNode = treeNodeMap.get(childNode.getParentId());
            if (parentNode == null) {
                LoggerFactory.getLogger(getClass()).error("[buildRouterTree][resource({}) 找不到父资源({})]",
                        childNode.getId(), childNode.getParentId());
                return;
            }
            // 将自己添加到父节点中
            if (parentNode.getChildren() == null) {
                parentNode.setChildren(new ArrayList<>());
            }
            parentNode.getChildren().add(childNode);
        });
        // 获得到所有的根节点
        return filterList(treeNodeMap.values(), node -> ID_ROOT.equals(node.getParentId()));
    }

    SocialUserBindReqDTO convert(Long userId, Integer userType, AuthSocialLoginReqVO reqVO);

    SmsCodeSendReqDTO convert(AuthSmsSendReqVO reqVO);

    SmsCodeUseReqDTO convert(AuthSmsLoginReqVO reqVO, Integer scene, String usedIp);

}
