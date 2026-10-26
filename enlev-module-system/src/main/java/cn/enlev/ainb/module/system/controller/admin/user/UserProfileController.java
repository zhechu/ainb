package cn.enlev.ainb.module.system.controller.admin.user;

import cn.hutool.core.collection.CollUtil;
import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.datapermission.core.annotation.DataPermission;
import cn.enlev.ainb.module.system.controller.admin.user.vo.profile.UserProfileRespVO;
import cn.enlev.ainb.module.system.controller.admin.user.vo.profile.UserProfileUpdatePasswordReqVO;
import cn.enlev.ainb.module.system.controller.admin.user.vo.profile.UserProfileUpdateReqVO;
import cn.enlev.ainb.module.system.controller.admin.user.vo.profile.UserThemeConfigRespVO;
import cn.enlev.ainb.module.system.controller.admin.user.vo.profile.UserThemeConfigUpdateReqVO;
import cn.enlev.ainb.module.system.convert.user.UserConvert;
import cn.enlev.ainb.module.system.dal.dataobject.dept.DeptDO;
import cn.enlev.ainb.module.system.dal.dataobject.dept.PostDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.RoleDO;
import cn.enlev.ainb.module.system.dal.dataobject.user.AdminUserDO;
import cn.enlev.ainb.module.system.service.dept.DeptService;
import cn.enlev.ainb.module.system.service.dept.PostService;
import cn.enlev.ainb.module.system.service.permission.PermissionService;
import cn.enlev.ainb.module.system.service.permission.RoleService;
import cn.enlev.ainb.module.system.service.user.AdminUserService;
import cn.enlev.ainb.module.system.service.user.UserPreferenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;
import static cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

@Tag(name = "管理后台 - 用户个人中心")
@RestController
@RequestMapping("/system/user/profile")
@Validated
@Slf4j
public class UserProfileController {

    @Resource
    private AdminUserService userService;
    @Resource
    private DeptService deptService;
    @Resource
    private PostService postService;
    @Resource
    private PermissionService permissionService;
    @Resource
    private RoleService roleService;
    @Resource
    private UserPreferenceService userPreferenceService;

    @GetMapping("/get")
    @Operation(summary = "获得登录用户信息")
    @DataPermission(enable = false) // 关闭数据权限，避免只查看自己时，查询不到部门。
    public CommonResult<UserProfileRespVO> getUserProfile() {
        // 获得用户基本信息
        AdminUserDO user = userService.getUser(getLoginUserId());
        // 获得用户角色
        List<RoleDO> userRoles = roleService.getRoleListFromCache(permissionService.getUserRoleIdListByUserId(user.getId()));
        // 获得部门信息
        DeptDO dept = user.getDeptId() != null ? deptService.getDept(user.getDeptId()) : null;
        // 获得岗位信息
        List<PostDO> posts = CollUtil.isNotEmpty(user.getPostIds()) ? postService.getPostList(user.getPostIds()) : null;
        return success(UserConvert.INSTANCE.convert(user, userRoles, dept, posts));
    }

    @PutMapping("/update")
    @Operation(summary = "修改用户个人信息")
    public CommonResult<Boolean> updateUserProfile(@Valid @RequestBody UserProfileUpdateReqVO reqVO) {
        userService.updateUserProfile(getLoginUserId(), reqVO);
        return success(true);
    }

    @PutMapping("/update-password")
    @Operation(summary = "修改用户个人密码")
    public CommonResult<Boolean> updateUserProfilePassword(@Valid @RequestBody UserProfileUpdatePasswordReqVO reqVO) {
        userService.updateUserPassword(getLoginUserId(), reqVO);
        return success(true);
    }

    @GetMapping("/theme")
    @Operation(summary = "获得当前用户主题配置")
    public CommonResult<UserThemeConfigRespVO> getUserThemeConfig() {
        var preference = userPreferenceService.getUserPreference(getLoginUserId());
        return success(new UserThemeConfigRespVO(preference != null ? preference.getThemeConfig() : null));
    }

    @PutMapping("/theme")
    @Operation(summary = "保存当前用户主题配置")
    public CommonResult<Boolean> updateUserThemeConfig(@Valid @RequestBody UserThemeConfigUpdateReqVO reqVO) {
        userPreferenceService.saveUserPreference(getLoginUserId(), reqVO.getThemeConfig());
        return success(true);
    }

}
