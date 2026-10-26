package cn.enlev.ainb.module.infra.service.portal;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.biz.system.permission.PermissionCommonApi;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.infra.controller.admin.portal.vo.PortalPageReqVO;
import cn.enlev.ainb.module.infra.controller.admin.portal.vo.PortalPublishReqVO;
import cn.enlev.ainb.module.infra.controller.admin.portal.vo.PortalSaveReqVO;
import cn.enlev.ainb.module.infra.dal.dataobject.portal.PortalDO;
import cn.enlev.ainb.module.infra.dal.dataobject.portal.PortalVersionDO;
import cn.enlev.ainb.module.infra.dal.mysql.portal.PortalMapper;
import cn.enlev.ainb.module.infra.dal.mysql.portal.PortalVersionMapper;
import cn.enlev.ainb.module.infra.enums.portal.PortalStatusEnum;
import cn.enlev.ainb.module.infra.enums.portal.PortalTypeEnum;
import cn.enlev.ainb.module.infra.enums.portal.PortalVisibleScopeEnum;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.*;

@Service
@Validated
@Slf4j
public class PortalServiceImpl implements PortalService {

    private static final int MAX_KEEP_VERSIONS = 100;
    private static final String COPY_SUFFIX = "_copy";
    /** 与 system 模块 RoleCodeEnum.SUPER_ADMIN 保持一致；infra 不依赖 system，故用字面量 */
    private static final String SUPER_ADMIN_ROLE_CODE = "super_admin";

    @Resource
    private PortalMapper portalMapper;
    @Resource
    private PortalVersionMapper portalVersionMapper;
    @Resource
    private PortalConfigValidator configValidator;
    /**
     * 用 ObjectProvider 而不是直接注入：本模块不依赖 system 模块，
     * {@link PermissionCommonApi} 的实现由 system 提供，单测容器里没有该 Bean
     */
    @Resource
    private ObjectProvider<PermissionCommonApi> permissionApiProvider;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createPortal(PortalSaveReqVO reqVO) {
        validateCodeUnique(null, reqVO.getCode());
        Integer type = reqVO.getType() == null ? PortalTypeEnum.ONLINE_DESIGN.getType() : reqVO.getType();
        if (PortalTypeEnum.isLink(type)) {
            validateLinkInfo(reqVO.getLinkType(), reqVO.getLinkUrl());
        } else {
            configValidator.validate(reqVO.getContent());
        }

        PortalDO portal = BeanUtils.toBean(reqVO, PortalDO.class);
        portal.setId(null);
        portal.setType(type);
        portal.setDraftContent(reqVO.getContent());
        portal.setStatus(PortalStatusEnum.DRAFT.getStatus());
        portal.setPublishedVersion(0);
        portal.setSchemaVersion(PortalConfigValidator.SCHEMA_VERSION);
        portal.setEnabled(reqVO.getEnabled() == null || reqVO.getEnabled());
        portal.setIsDefault(Boolean.TRUE.equals(reqVO.getIsDefault()));
        applyVisibleScope(portal, reqVO);
        if (Boolean.TRUE.equals(portal.getIsDefault())) {
            clearDefaultPortal();
        }
        portalMapper.insert(portal);
        return portal.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePortal(PortalSaveReqVO reqVO) {
        PortalDO old = validatePortalExists(reqVO.getId());
        if (reqVO.getType() != null && old.getType() != null
                && !Objects.equals(old.getType(), reqVO.getType())) {
            throw exception(PORTAL_TYPE_CANNOT_UPDATE);
        }
        boolean link = PortalTypeEnum.isLink(old.getType());
        if (link) {
            validateLinkInfo(reqVO.getLinkType(), reqVO.getLinkUrl());
        } else {
            configValidator.validate(reqVO.getContent());
        }
        boolean published = isPublished(old);
        if (!Objects.equals(old.getCode(), reqVO.getCode()) && published) {
            throw exception(PORTAL_CODE_CANNOT_UPDATE);
        }
        validateCodeUnique(reqVO.getId(), reqVO.getCode());

        PortalDO update = BeanUtils.toBean(reqVO, PortalDO.class);
        update.setType(null); // 类型创建后不可改
        update.setDraftContent(reqVO.getContent());
        update.setEnabled(reqVO.getEnabled() == null || reqVO.getEnabled());
        update.setIsDefault(Boolean.TRUE.equals(reqVO.getIsDefault()));
        if (reqVO.getVisibleScope() != null) {
            applyVisibleScope(update, reqVO);
        }
        // visibleScope 为 null 表示调用方（如门户设计器保存）未提交可见范围：
        // 保持 null 让 updateById 跳过，沿用原有配置
        if (link) {
            // 链接门户没有内容快照：链接/基础信息修改即时生效，发布状态保持不变
            update.setStatus(null);
            update.setPublishedVersion(null);
            update.setSchemaVersion(null);
        } else if (published) {
            PortalVersionDO publishedVersion = getPublishedVersionSnapshot(old);
            if (publishedVersion != null && Objects.equals(publishedVersion.getContent(), reqVO.getContent())) {
                update.setStatus(PortalStatusEnum.PUBLISHED.getStatus());
            } else {
                update.setStatus(PortalStatusEnum.PUBLISHED_MODIFIED.getStatus());
            }
            update.setPublishedVersion(null);
            update.setSchemaVersion(PortalConfigValidator.SCHEMA_VERSION);
        } else {
            update.setStatus(PortalStatusEnum.DRAFT.getStatus());
            update.setPublishedVersion(null);
            update.setSchemaVersion(PortalConfigValidator.SCHEMA_VERSION);
        }
        if (Boolean.TRUE.equals(update.getIsDefault())) {
            clearDefaultPortal();
        }
        portalMapper.updateById(update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePortal(Long id) {
        validatePortalExists(id);
        portalVersionMapper.delete(new LambdaQueryWrapperX<PortalVersionDO>()
                .eq(PortalVersionDO::getPortalId, id));
        portalMapper.deleteById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long copyPortal(Long id) {
        PortalDO source = validatePortalExists(id);
        PortalDO target = BeanUtils.toBean(source, PortalDO.class);
        target.setId(null);
        target.setName(source.getName() + "（副本）");
        target.setCode(generateCopyCode(source.getCode()));
        target.setStatus(PortalStatusEnum.DRAFT.getStatus());
        target.setPublishedVersion(0);
        target.setIsDefault(false);
        target.setEnabled(true);
        target.clean();
        portalMapper.insert(target);
        return target.getId();
    }

    @Override
    public PortalDO getPortal(Long id) {
        return portalMapper.selectById(id);
    }

    @Override
    public PortalDO validatePortalExists(Long id) {
        PortalDO portal = portalMapper.selectById(id);
        if (portal == null) {
            throw exception(PORTAL_NOT_EXISTS);
        }
        return portal;
    }

    @Override
    public PageResult<PortalDO> getPortalPage(PortalPageReqVO reqVO) {
        return portalMapper.selectPage(reqVO);
    }

    @Override
    public boolean isCodeAvailable(Long id, String code) {
        if (StrUtil.isBlank(code)) {
            return true;
        }
        PortalDO exists = portalMapper.selectByCode(code);
        return exists == null || (id != null && Objects.equals(exists.getId(), id));
    }

    private void validateCodeUnique(Long id, String code) {
        if (!isCodeAvailable(id, code)) {
            throw exception(PORTAL_CODE_DUPLICATE, code);
        }
    }

    @Override
    public PortalDO getPortalByCode(String code) {
        return StrUtil.isBlank(code) ? null : portalMapper.selectByCode(code);
    }

    @Override
    public PortalVersionDO getPortalVersion(Long portalId, Integer version) {
        PortalVersionDO result = portalVersionMapper.selectByPortalIdAndVersion(portalId, version);
        if (result == null) {
            throw exception(PORTAL_VERSION_NOT_EXISTS);
        }
        return result;
    }

    @Override
    public List<PortalVersionDO> getPortalVersionList(Long portalId) {
        validatePortalExists(portalId);
        return portalVersionMapper.selectListByPortalId(portalId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer publish(PortalPublishReqVO reqVO) {
        PortalDO portal = validatePortalExists(reqVO.getId());
        if (PortalTypeEnum.isLink(portal.getType())) {
            // 链接门户没有设计内容：不产生版本快照，直接标记为已发布
            validateLinkInfo(portal.getLinkType(), portal.getLinkUrl());
            PortalDO update = new PortalDO();
            update.setId(portal.getId());
            update.setStatus(PortalStatusEnum.PUBLISHED.getStatus());
            portalMapper.updateById(update);
            return 0;
        }
        configValidator.validate(portal.getDraftContent());

        Integer version = portalVersionMapper.selectMaxVersionByPortalId(portal.getId()) + 1;
        PortalVersionDO snapshot = new PortalVersionDO();
        snapshot.setPortalId(portal.getId());
        snapshot.setVersion(version);
        snapshot.setContent(portal.getDraftContent());
        snapshot.setSchemaVersion(PortalConfigValidator.SCHEMA_VERSION);
        snapshot.setPublishRemark(reqVO.getPublishRemark());
        portalVersionMapper.insert(snapshot);
        trimOldVersions(portal.getId());

        PortalDO update = new PortalDO();
        update.setId(portal.getId());
        update.setStatus(PortalStatusEnum.PUBLISHED.getStatus());
        update.setPublishedVersion(version);
        portalMapper.updateById(update);
        return version;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unpublish(Long id) {
        PortalDO portal = validatePortalExists(id);
        if (!isPublished(portal)) {
            throw exception(PORTAL_NOT_PUBLISHED);
        }
        PortalDO update = new PortalDO();
        update.setId(id);
        update.setStatus(PortalStatusEnum.DRAFT.getStatus());
        update.setPublishedVersion(0);
        portalMapper.updateById(update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rollback(Long portalId, Integer version) {
        PortalDO portal = validatePortalExists(portalId);
        PortalVersionDO target = getPortalVersion(portalId, version);
        configValidator.validate(target.getContent());

        PortalDO update = new PortalDO();
        update.setId(portalId);
        update.setDraftContent(target.getContent());
        update.setSchemaVersion(target.getSchemaVersion());
        if (portal.getPublishedVersion() != null && portal.getPublishedVersion() > 0) {
            PortalVersionDO published = getPublishedVersionSnapshot(portal);
            update.setStatus(published != null && Objects.equals(published.getContent(), target.getContent())
                    ? PortalStatusEnum.PUBLISHED.getStatus()
                    : PortalStatusEnum.PUBLISHED_MODIFIED.getStatus());
        } else {
            update.setStatus(PortalStatusEnum.DRAFT.getStatus());
        }
        portalMapper.updateById(update);
    }

    @Override
    public PortalVersionDO getPublishedVersion(PortalDO portal) {
        if (portal == null || portal.getPublishedVersion() == null || portal.getPublishedVersion() <= 0
                || (Objects.equals(portal.getStatus(), PortalStatusEnum.DRAFT.getStatus()))) {
            return null;
        }
        return portalVersionMapper.selectByPortalIdAndVersion(portal.getId(), portal.getPublishedVersion());
    }

    private PortalVersionDO getPublishedVersionSnapshot(PortalDO portal) {
        if (portal == null || portal.getPublishedVersion() == null || portal.getPublishedVersion() <= 0) {
            return null;
        }
        return portalVersionMapper.selectByPortalIdAndVersion(portal.getId(), portal.getPublishedVersion());
    }

    @Override
    public PortalDO getDefaultPortal() {
        return portalMapper.selectDefault();
    }

    private void trimOldVersions(Long portalId) {
        List<PortalVersionDO> versions = portalVersionMapper.selectListByPortalId(portalId);
        int excess = versions.size() - MAX_KEEP_VERSIONS;
        if (excess > 0) {
            portalVersionMapper.deleteOldestVersions(portalId, excess);
        }
    }

    private void clearDefaultPortal() {
        PortalDO update = new PortalDO();
        update.setIsDefault(false);
        portalMapper.update(update, new LambdaUpdateWrapper<PortalDO>()
                .eq(PortalDO::getIsDefault, true));
    }

    private String generateCopyCode(String sourceCode) {
        String candidate = sourceCode + COPY_SUFFIX;
        if (isCodeAvailable(null, candidate)) {
            return candidate;
        }
        for (int i = 2; i < 100; i++) {
            candidate = sourceCode + COPY_SUFFIX + i;
            if (isCodeAvailable(null, candidate)) {
                return candidate;
            }
        }
        throw exception(PORTAL_CODE_DUPLICATE, candidate);
    }

    private boolean isPublished(PortalDO portal) {
        return !PortalStatusEnum.DRAFT.getStatus().equals(portal.getStatus());
    }

    private void validateLinkInfo(Integer linkType, String linkUrl) {
        boolean linkTypeValid = linkType != null
                && (linkType == PortalTypeEnum.LINK_PAGE || linkType == PortalTypeEnum.LINK_EXTERNAL);
        if (!linkTypeValid || StrUtil.isBlank(linkUrl)) {
            throw exception(PORTAL_LINK_INCOMPLETE);
        }
    }

    private void applyVisibleScope(PortalDO portal, PortalSaveReqVO reqVO) {
        Integer scope = reqVO.getVisibleScope() == null
                ? PortalVisibleScopeEnum.ALL.getScope() : reqVO.getVisibleScope();
        portal.setVisibleScope(scope);
        // 所有人时写空串而不是 null：updateById 会跳过 null 字段，
        // 从「指定角色/用户」改回「所有人」时旧名单必须被清掉
        portal.setVisibleIds(PortalVisibleScopeEnum.ALL.getScope().equals(scope)
                ? StrUtil.EMPTY : StrUtil.nullToEmpty(reqVO.getVisibleIds()));
    }

    private List<String> splitVisibleIds(PortalDO portal) {
        if (StrUtil.isBlank(portal.getVisibleIds())) {
            return List.of();
        }
        return Arrays.stream(portal.getVisibleIds().split(","))
                .map(String::trim).filter(StrUtil::isNotBlank).toList();
    }

    @Override
    public List<PortalDO> getVisiblePublishedPortals(Long userId) {
        return portalMapper.selectPublishedEnabledList().stream()
                .filter(portal -> isPortalVisible(portal, userId))
                .toList();
    }

    @Override
    public boolean isPortalVisible(PortalDO portal, Long userId) {
        if (portal == null || userId == null) {
            return false;
        }
        Integer scope = portal.getVisibleScope() == null
                ? PortalVisibleScopeEnum.ALL.getScope() : portal.getVisibleScope();
        if (PortalVisibleScopeEnum.ALL.getScope().equals(scope)) {
            return true;
        }
        PermissionCommonApi permissionApi = permissionApiProvider != null
                ? permissionApiProvider.getIfAvailable() : null;
        if (permissionApi != null && permissionApi.hasAnyRoles(userId, SUPER_ADMIN_ROLE_CODE)) {
            return true;
        }
        if (PortalVisibleScopeEnum.USER.getScope().equals(scope)) {
            return splitVisibleIds(portal).contains(String.valueOf(userId));
        }
        if (PortalVisibleScopeEnum.ROLE.getScope().equals(scope)) {
            if (permissionApi == null) {
                log.warn("[isPortalVisible][PermissionCommonApi 不可用，门户({}) 的角色可见范围按不可见处理]",
                        portal.getCode());
                return false;
            }
            List<String> roleCodes = splitVisibleIds(portal);
            return !roleCodes.isEmpty() && permissionApi.hasAnyRoles(userId, roleCodes.toArray(new String[0]));
        }
        return false;
    }
}
