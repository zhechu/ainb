package cn.enlev.ainb.module.infra.controller.admin.portal;

import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils;
import cn.enlev.ainb.module.infra.controller.admin.portal.vo.*;
import cn.enlev.ainb.module.infra.dal.dataobject.portal.PortalDO;
import cn.enlev.ainb.module.infra.dal.dataobject.portal.PortalVersionDO;
import cn.enlev.ainb.module.infra.enums.portal.PortalStatusEnum;
import cn.enlev.ainb.module.infra.enums.portal.PortalTypeEnum;
import cn.enlev.ainb.module.infra.service.datainterface.DataInterfaceService;
import cn.enlev.ainb.module.infra.service.portal.PortalConfigValidator;
import cn.enlev.ainb.module.infra.service.portal.PortalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 门户设计")
@RestController
@RequestMapping("/infra/portal")
@Validated
public class PortalController {

    @Resource
    private PortalService portalService;
    @Resource
    private DataInterfaceService dataInterfaceService;

    @Value("${enlev.mode:public}")
    private String enlevMode;

    @PostMapping("/create")
    @Operation(summary = "创建门户")
    @PreAuthorize("@ss.hasPermission('infra:portal:create')")
    public CommonResult<Long> create(@Valid @RequestBody PortalSaveReqVO reqVO) {
        return success(portalService.createPortal(reqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新门户")
    @PreAuthorize("@ss.hasPermission('infra:portal:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody PortalSaveReqVO reqVO) {
        portalService.updatePortal(reqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除门户")
    @PreAuthorize("@ss.hasPermission('infra:portal:delete')")
    public CommonResult<Boolean> delete(@RequestParam("id") Long id) {
        portalService.deletePortal(id);
        return success(true);
    }

    @PostMapping("/copy")
    @Operation(summary = "复制门户")
    @PreAuthorize("@ss.hasPermission('infra:portal:create')")
    public CommonResult<Long> copy(@RequestParam("id") Long id) {
        return success(portalService.copyPortal(id));
    }

    @GetMapping("/get")
    @Operation(summary = "获取门户")
    @PreAuthorize("@ss.hasPermission('infra:portal:query')")
    public CommonResult<PortalRespVO> get(@RequestParam("id") Long id) {
        return success(toResp(portalService.validatePortalExists(id)));
    }

    @GetMapping("/page")
    @Operation(summary = "获得门户分页")
    @PreAuthorize("@ss.hasPermission('infra:portal:query')")
    public CommonResult<PageResult<PortalRespVO>> page(@Valid PortalPageReqVO reqVO) {
        PageResult<PortalDO> page = portalService.getPortalPage(reqVO);
        return success(new PageResult<>(page.getList().stream().map(this::toResp).toList(), page.getTotal()));
    }

    @GetMapping("/check-code")
    @Operation(summary = "校验门户编码")
    @PreAuthorize("@ss.hasPermission('infra:portal:query')")
    public CommonResult<Boolean> checkCode(@RequestParam String code,
                                           @RequestParam(required = false) Long id) {
        return success(portalService.isCodeAvailable(id, code));
    }

    @PostMapping("/publish")
    @Operation(summary = "发布门户")
    @PreAuthorize("@ss.hasPermission('infra:portal:publish')")
    public CommonResult<Integer> publish(@Valid @RequestBody PortalPublishReqVO reqVO) {
        return success(portalService.publish(reqVO));
    }

    @PostMapping("/unpublish")
    @Operation(summary = "下线门户")
    @PreAuthorize("@ss.hasPermission('infra:portal:publish')")
    public CommonResult<Boolean> unpublish(@RequestParam Long id) {
        portalService.unpublish(id);
        return success(true);
    }

    @GetMapping("/version-list")
    @Operation(summary = "获取门户版本列表")
    @PreAuthorize("@ss.hasPermission('infra:portal:query')")
    public CommonResult<java.util.List<PortalVersionRespVO>> versionList(@RequestParam Long portalId) {
        return success(portalService.getPortalVersionList(portalId).stream()
                .map(version -> cn.enlev.ainb.framework.common.util.object.BeanUtils.toBean(version, PortalVersionRespVO.class))
                .toList());
    }

    @GetMapping("/get-version")
    @Operation(summary = "获取门户版本")
    @PreAuthorize("@ss.hasPermission('infra:portal:query')")
    public CommonResult<PortalVersionRespVO> getVersion(@RequestParam Long portalId,
                                                        @RequestParam Integer version) {
        return success(cn.enlev.ainb.framework.common.util.object.BeanUtils.toBean(
                portalService.getPortalVersion(portalId, version), PortalVersionRespVO.class));
    }

    @PostMapping("/rollback")
    @Operation(summary = "回滚门户草稿")
    @PreAuthorize("@ss.hasPermission('infra:portal:rollback')")
    public CommonResult<Boolean> rollback(@RequestParam Long portalId, @RequestParam Integer version) {
        portalService.rollback(portalId, version);
        return success(true);
    }

    @GetMapping("/preview")
    @Operation(summary = "预览门户草稿")
    @PreAuthorize("@ss.hasPermission('infra:portal:preview')")
    public CommonResult<PortalRuntimeRespVO> preview(@RequestParam Long id) {
        PortalDO portal = portalService.validatePortalExists(id);
        PortalRuntimeRespVO result = new PortalRuntimeRespVO();
        result.setId(portal.getId());
        result.setName(portal.getName());
        result.setCode(portal.getCode());
        result.setVersion(portal.getPublishedVersion());
        result.setContent(portal.getDraftContent());
        result.setSchemaVersion(portal.getSchemaVersion());
        return success(result);
    }

    @GetMapping("/runtime/home")
    @Operation(summary = "获取当前用户首页门户")
    public CommonResult<PortalRuntimeRespVO> runtimeHome() {
        // 需要开启专业版才能使用门户设计功能
        if (!"professional".equals(enlevMode)) {
            return success(null);
        }

        // 仅需登录态：能否看到由门户「可见范围」决定，而非 infra:portal:run 权限。
        // 否则「所有人可见」的默认门户对未授予该菜单权限的用户（如演示角色）会静默不显示。
        PortalDO portal = portalService.getDefaultPortal();
        if (!portalService.isPortalVisible(portal, SecurityFrameworkUtils.getLoginUserId())) {
            return success(null);
        }
        return success(toRuntime(portal));
    }

    @GetMapping("/runtime/list")
    @Operation(summary = "获取当前用户可见的已发布门户列表")
    public CommonResult<java.util.List<PortalRuntimeRespVO>> runtimeList() {
        return success(portalService.getVisiblePublishedPortals(SecurityFrameworkUtils.getLoginUserId())
                .stream().map(this::toRuntimeListItem).toList());
    }

    @PostMapping("/runtime/data")
    @Operation(summary = "调用门户数据接口")
    public CommonResult<Object> runtimeData(@RequestParam String code,
                                            @RequestBody(required = false) java.util.Map<String, Object> args) {
        // 与 /infra/data-interface/options 一致：门户取数只需登录态，Service 层强制只读，
        // 能取到什么由接口配置者定义，调用方改不了 SQL / 地址 / 脚本。
        return success(dataInterfaceService.invokeReadOnly(code, args));
    }

    @GetMapping("/runtime/{code}")
    @Operation(summary = "获取已发布门户")
    public CommonResult<PortalRuntimeRespVO> runtime(@PathVariable String code) {
        PortalDO portal = portalService.getPortalByCode(code);
        if (portal == null || !Boolean.TRUE.equals(portal.getEnabled())) {
            return success(null);
        }
        if (!portalService.isPortalVisible(portal, SecurityFrameworkUtils.getLoginUserId())) {
            return success(null);
        }
        return success(toRuntime(portal));
    }

    private PortalRuntimeRespVO toRuntime(PortalDO portal) {
        if (portal == null) {
            return null;
        }
        if (PortalTypeEnum.isLink(portal.getType())) {
            // 链接门户没有版本快照：已发布即可用
            if (PortalStatusEnum.DRAFT.getStatus().equals(portal.getStatus())) {
                return null;
            }
            PortalRuntimeRespVO result = new PortalRuntimeRespVO();
            result.setId(portal.getId());
            result.setName(portal.getName());
            result.setCode(portal.getCode());
            result.setVersion(0);
            result.setType(portal.getType());
            result.setLinkType(portal.getLinkType());
            result.setLinkUrl(portal.getLinkUrl());
            result.setIsDefault(portal.getIsDefault());
            return result;
        }
        PortalVersionDO version = portalService.getPublishedVersion(portal);
        if (version == null) {
            return null;
        }
        PortalRuntimeRespVO result = new PortalRuntimeRespVO();
        result.setId(portal.getId());
        result.setName(portal.getName());
        result.setCode(portal.getCode());
        result.setVersion(version.getVersion());
        result.setContent(version.getContent());
        result.setSchemaVersion(version.getSchemaVersion());
        result.setType(portal.getType());
        result.setIsDefault(portal.getIsDefault());
        return result;
    }

    private PortalRuntimeRespVO toRuntimeListItem(PortalDO portal) {
        PortalRuntimeRespVO result = new PortalRuntimeRespVO();
        result.setId(portal.getId());
        result.setName(portal.getName());
        result.setCode(portal.getCode());
        result.setVersion(portal.getPublishedVersion());
        result.setType(portal.getType());
        result.setLinkType(portal.getLinkType());
        result.setLinkUrl(portal.getLinkUrl());
        result.setIsDefault(portal.getIsDefault());
        return result;
    }

    private PortalRespVO toResp(PortalDO portal) {
        PortalRespVO result = new PortalRespVO();
        result.setId(portal.getId());
        result.setName(portal.getName());
        result.setCode(portal.getCode());
        result.setDescription(portal.getDescription());
        result.setType(portal.getType());
        result.setLinkType(portal.getLinkType());
        result.setLinkUrl(portal.getLinkUrl());
        result.setSort(portal.getSort());
        result.setStatus(portal.getStatus());
        result.setContent(portal.getDraftContent());
        result.setPublishedVersion(portal.getPublishedVersion());
        result.setIsDefault(portal.getIsDefault());
        result.setEnabled(portal.getEnabled());
        result.setVisibleScope(portal.getVisibleScope());
        result.setVisibleIds(portal.getVisibleIds());
        result.setSchemaVersion(portal.getSchemaVersion());
        result.setCreator(portal.getCreator());
        result.setCreateTime(portal.getCreateTime());
        result.setUpdater(portal.getUpdater());
        result.setUpdateTime(portal.getUpdateTime());
        return result;
    }
}
