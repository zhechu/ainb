package cn.enlev.ainb.module.agent.tool.impl.form;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDesignPublishRespDTO;
import cn.enlev.ainb.module.system.api.menu.MenuApi;
import cn.enlev.ainb.module.system.api.menu.dto.MenuCreateReqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具：表单发布原子动作（方案 §5.6）
 *
 * <h3>🔴 这是一个「原子动作」</h3>
 * 「只 publish 不建菜单」和「建了菜单不分配角色」都是真实踩过的坑（见 demo_lib.py 注释）：
 * <ul>
 *   <li>只 publish → 表单 {@code status=1} 看着成功，菜单树下面空空如也</li>
 *   <li>建了菜单不分配角色 → 菜单在库里，不出现在 {@code get-permission-info} 里，
 *       前端不注册路由 → 「菜单在库里但界面上没有」</li>
 * </ul>
 * 所以这个工具把五步封装成一步，任一步失败即停，外层 Plan 把它标为失败步骤。
 *
 * <h3>执行序列（照方案 §5.6）</h3>
 * <ol>
 *   <li>POST publish → 取回菜单配置</li>
 *   <li>POST /system/menu/create → 主菜单（type=2）</li>
 *   <li>POST /system/menu/create × N → 按钮权限（type=3）</li>
 *   <li>POST /infra/form-design/bind-menu → 回绑</li>
 *   <li>GET 角色现有菜单 + 合并 + POST assign-role-menu → 回写</li>
 * </ol>
 *
 * <h3>后端不代建菜单的原因</h3>
 * infra 不能反向依赖 system（循环依赖），且必须走 {@code MenuService.createMenu}
 * 才能触发 {@code @CacheEvict}，直接写库菜单缓存不失效，
 * 表现是「菜单在库里但界面上看不到」。
 *
 * @author 走进新时代
 */
@Component
@AgentTool(
        name = FormPublishBundleTool.NAME,
        displayName = "一键发布表单",
        groupName = "表单设计",
        description = "发布表单并自动完成建菜单、建按钮权限、回绑、分配角色五步。"
                + "任何一步失败即停。分开调用无法保证原子性 —— 经验证「只发布不建菜单」会让表单变成孤儿。"
                + "需要提供：表单编号 id、上级菜单（用户说菜单名字就用 parentMenuName，如「在线示例」；"
                + "知道数字 id 才用 parentMenuId）、要分配到的角色编号列表 roleIds",
        permission = "infra:form-design:publish",
        // 这个工具第 2~3 步建菜单、第 5 步分配角色，少任何一个权限都会卡在半路，
        // 留下「发布了但没菜单」或「有菜单但没人看得到」的表单
        extraPermissions = {"system:menu:create", "system:permission:assign-role-menu"},
        risk = RiskLevel.HIGH)
@Slf4j
public class FormPublishBundleTool implements AgentToolFunction {

    /**
     * 工具键
     *
     * <p>🔴 上面注解的 {@code name} 与变更清单抽取器的 {@code toolId()} 都必须引用它。
     * 三处各写一遍字面量时，改名不会报错 —— 只会让清单静默退回兜底解读。
     */
    public static final String NAME = "form_publish_bundle";

    /**
     * 参数名。留在工具上而不是挪去 {@link FormPublishParamResolver}：
     * 它们是<b>本工具对外的参数契约</b>，抽取器（{@code FormPublishBundleChangeExtractor}）
     * 与解析器都按这份契约读 DSL，而契约属于工具。
     */
    public static final String PARAM_ID = "id";
    public static final String PARAM_PARENT_MENU_ID = "parentMenuId";
    public static final String PARAM_PARENT_MENU_NAME = "parentMenuName";
    public static final String PARAM_ROLE_IDS = "roleIds";

    @Resource
    private FormDesignApi formDesignApi;
    @Resource
    private MenuApi menuApi;
    /**
     * 入参解析。抽成独立 bean 是为了让变更清单的干跑器读同一份 ——
     * 「按名字反查上级目录」是查询，干跑器自己再写一遍就是第二份口径
     */
    @Resource
    private FormPublishParamResolver paramResolver;

    @Override
    public Object execute(Map<String, Object> params) {
        Long id = paramResolver.resolveFormId(params);
        Long parentMenuId = paramResolver.resolveParentMenuId(params);
        List<Long> roleIds = paramResolver.resolveRoleIds(params);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("formId", id);

        // 步骤 1：发布。🔴 必须把 parentMenuId 透传给 infra —— 首次发布时
        //    FormDesignServiceImpl.publish 强制要求 parentMenuId 非空，否则抛
        //    「请选择上级菜单」。此前 API 只收 id、parentMenuId 恒为 null，
        //    AI 建的新表单永远发布不了（建表+存草稿成功、卡在发布，留半截）
        FormDesignPublishRespDTO publishResp = formDesignApi.publishFormDesign(id, parentMenuId);
        result.put("version", publishResp.getVersion());
        log.info("[execute][表单({}) 发布成功，版本号({})]", id, publishResp.getVersion());

        if (Boolean.FALSE.equals(publishResp.getNeedCreateMenu())
                || publishResp.getMenuConfig() == null) {
            // 已经绑过菜单，只需重新分配角色
            result.put("menuCreated", false);
            result.put("message", "表单已发布（版本 " + publishResp.getVersion()
                    + "）。表单之前已绑定菜单，跳过建菜单步骤。");
            return result;
        }

        FormDesignPublishRespDTO.MenuConfig menuConfig = publishResp.getMenuConfig();

        // 步骤 2：建主菜单
        Long mainMenuId = menuApi.createMenu(buildMainMenuReq(menuConfig, parentMenuId));
        log.info("[execute][主菜单({})创建成功]", mainMenuId);

        // 步骤 3：建按钮权限菜单
        List<Long> permissionMenuIds = new ArrayList<>();
        if (menuConfig.getPermissions() != null) {
            for (FormDesignPublishRespDTO.PermissionConfig perm : menuConfig.getPermissions()) {
                Long permMenuId = menuApi.createMenu(buildPermMenuReq(perm, mainMenuId));
                permissionMenuIds.add(permMenuId);
                log.info("[execute][权限菜单({} {})创建成功]", permMenuId, perm.getPermission());
            }
        }

        // 步骤 4：回绑。⚠️ bind-menu 参数在 query string 不在 body（FormDesignController#bindMenu）
        formDesignApi.bindMenu(id, mainMenuId, permissionMenuIds);
        log.info("[execute][表单({}) 与主菜单({}) 绑定成功]", id, mainMenuId);

        // 步骤 5：给角色分配菜单（合并现有 + 新建的）
        List<String> assignedRoles = new ArrayList<>();
        Set<Long> allNewMenuIds = new java.util.HashSet<>(List.of(mainMenuId));
        allNewMenuIds.addAll(permissionMenuIds);

        for (Long roleId : roleIds) {
            Set<Long> existing = menuApi.getRoleMenuIds(roleId);
            existing.addAll(allNewMenuIds);
            menuApi.assignRoleMenus(roleId, existing);
            assignedRoles.add(String.valueOf(roleId));
            log.info("[execute][角色({}) 菜单分配成功，新增菜单数({})]", roleId, allNewMenuIds.size());
        }

        result.put("menuCreated", true);
        result.put("mainMenuId", mainMenuId);
        result.put("permissionMenuIds", permissionMenuIds);
        result.put("assignedRoleIds", assignedRoles);
        result.put("message", roleIds.isEmpty()
                ? "表单已发布（版本 " + publishResp.getVersion() + "），菜单已建立但未分配角色，"
                        + "当前仅超级管理员可见；如需普通角色使用请补 roleIds 重新发布"
                : "表单已发布（版本 " + publishResp.getVersion() + "），"
                        + "菜单已建立并分配给 " + roleIds.size() + " 个角色。"
                        + "用户退出重新登录后即可看到菜单。");
        return result;
    }

    private MenuCreateReqDTO buildMainMenuReq(FormDesignPublishRespDTO.MenuConfig config,
                                              Long parentMenuId) {
        MenuCreateReqDTO req = new MenuCreateReqDTO();
        req.setName(config.getName());
        req.setPath(StrUtil.blankToDefault(config.getPath(), ""));
        req.setComponent(config.getComponent());
        req.setComponentName(config.getComponentName());
        req.setParentId(parentMenuId);
        req.setSort(config.getSort() != null ? config.getSort() : 100);
        req.setIcon(StrUtil.blankToDefault(config.getIcon(), ""));
        req.setType(2);  // 菜单
        req.setStatus(CommonStatusEnum.ENABLE.getStatus());
        req.setVisible(true);
        req.setKeepAlive(false); // 表单页面不 keepAlive，避免 SSE 等长连接残留
        req.setAlwaysShow(false);
        return req;
    }

    private MenuCreateReqDTO buildPermMenuReq(FormDesignPublishRespDTO.PermissionConfig perm,
                                              Long parentMenuId) {
        MenuCreateReqDTO req = new MenuCreateReqDTO();
        req.setName(perm.getName());
        req.setPermission(perm.getPermission());
        req.setParentId(parentMenuId);
        req.setSort(perm.getSort() != null ? perm.getSort() : 100);
        req.setType(3);  // 按钮
        req.setStatus(CommonStatusEnum.ENABLE.getStatus());
        req.setVisible(false);
        req.setKeepAlive(false);
        req.setAlwaysShow(false);
        return req;
    }

    // 入参解析（id / 上级菜单 / 角色）在 FormPublishParamResolver，
    // 与变更清单的干跑器共用同一份，故此处不再各写一遍

    /** 见 {@link AgentToolFunction#resultArrayKeys}：本工具全部返回分支里数组值的键（并集） */
    @Override
    public List<String> resultArrayKeys() {
        return List.of("permissionMenuIds", "assignedRoleIds");
    }

    /**
     * 见 {@link AgentToolFunction#resultKeys}：两个返回分支顶层键的并集
     *
     * <p>🔴 「已绑过菜单」那条分支在 {@code menuCreated=false} + {@code message} 之后就 return 了，
     * 所以 {@code mainMenuId} / {@code permissionMenuIds} / {@code assignedRoleIds} 三个键
     * <b>只在新建菜单那条分支才存在</b>。规划期不知道会走哪条，故按并集登记；
     * 下游要引用它们就得先判 {@code menuCreated}。
     */
    @Override
    public List<String> resultKeys() {
        return List.of("formId", "version", "menuCreated", "message",
                "mainMenuId", "permissionMenuIds", "assignedRoleIds");
    }

    @Override
    public String paramsSchema() {
        return """
                id（必填，数字）：表单编号
                parentMenuName（可选，字符串）：菜单挂在哪个上级目录「名字」下，如「在线示例」。
                  用户按名字指定发布位置时用这个；找不到同名启用目录会报错并列出可选目录，不会静默挂顶层
                parentMenuId（可选，数字）：上级目录的数字编号；已知 id 时才用。
                  parentMenuName 与 parentMenuId 都不传表示挂到顶层菜单
                roleIds（可选，数字数组）：要把菜单分配给哪些角色；不给则菜单仅超级管理员可见""";
    }

}
