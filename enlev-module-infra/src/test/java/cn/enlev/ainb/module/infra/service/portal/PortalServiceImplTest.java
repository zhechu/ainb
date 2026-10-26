package cn.enlev.ainb.module.infra.service.portal;

import cn.enlev.ainb.module.infra.controller.admin.portal.vo.PortalPublishReqVO;
import cn.enlev.ainb.module.infra.controller.admin.portal.vo.PortalSaveReqVO;
import cn.enlev.ainb.module.infra.dal.dataobject.portal.PortalDO;
import cn.enlev.ainb.module.infra.dal.dataobject.portal.PortalVersionDO;
import cn.enlev.ainb.module.infra.dal.mysql.portal.PortalMapper;
import cn.enlev.ainb.module.infra.dal.mysql.portal.PortalVersionMapper;
import cn.enlev.ainb.module.infra.enums.portal.PortalStatusEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortalServiceImplTest {

    private static final String CONTENT = "{\"schemaVersion\":1,\"columns\":24,\"rowHeight\":8,\"gap\":12,\"items\":[]}";

    @Mock
    private PortalMapper portalMapper;
    @Mock
    private PortalVersionMapper portalVersionMapper;
    @Mock
    private PortalConfigValidator configValidator;
    @InjectMocks
    private PortalServiceImpl portalService;

    @Test
    void createPortal_savesDraftContentAndStatus() {
        PortalSaveReqVO reqVO = saveRequest(null, false);
        when(portalMapper.selectByCode(reqVO.getCode())).thenReturn(null);

        portalService.createPortal(reqVO);

        ArgumentCaptor<PortalDO> captor = ArgumentCaptor.forClass(PortalDO.class);
        verify(portalMapper).insert(captor.capture());
        PortalDO saved = captor.getValue();
        assertEquals(CONTENT, saved.getDraftContent());
        assertEquals(PortalStatusEnum.DRAFT.getStatus(), saved.getStatus());
        assertEquals(0, saved.getPublishedVersion());
        assertFalse(saved.getIsDefault());
        verify(configValidator).validate(CONTENT);
    }

    @Test
    void updatePortal_whenDraftMatchesPublishedSnapshot_marksPublished() {
        PortalDO old = portal(10L, PortalStatusEnum.PUBLISHED_MODIFIED.getStatus(), 3, CONTENT);
        PortalVersionDO published = version(10L, 3, CONTENT);
        PortalSaveReqVO reqVO = saveRequest(10L, false);
        when(portalMapper.selectById(10L)).thenReturn(old);
        when(portalMapper.selectByCode(reqVO.getCode())).thenReturn(old);
        when(portalVersionMapper.selectByPortalIdAndVersion(10L, 3)).thenReturn(published);

        portalService.updatePortal(reqVO);

        ArgumentCaptor<PortalDO> captor = ArgumentCaptor.forClass(PortalDO.class);
        verify(portalMapper).updateById(captor.capture());
        assertEquals(PortalStatusEnum.PUBLISHED.getStatus(), captor.getValue().getStatus());
    }

    @Test
    void publish_createsNextVersionAndUpdatesPortal() {
        PortalDO portal = portal(10L, PortalStatusEnum.DRAFT.getStatus(), 0, CONTENT);
        when(portalMapper.selectById(10L)).thenReturn(portal);
        when(portalVersionMapper.selectMaxVersionByPortalId(10L)).thenReturn(2);
        when(portalVersionMapper.selectListByPortalId(10L)).thenReturn(java.util.List.of());

        PortalPublishReqVO reqVO = new PortalPublishReqVO();
        reqVO.setId(10L);
        reqVO.setPublishRemark("release");
        Integer version = portalService.publish(reqVO);

        assertEquals(3, version);
        ArgumentCaptor<PortalVersionDO> versionCaptor = ArgumentCaptor.forClass(PortalVersionDO.class);
        verify(portalVersionMapper).insert(versionCaptor.capture());
        assertEquals(3, versionCaptor.getValue().getVersion());
        assertEquals(CONTENT, versionCaptor.getValue().getContent());
        ArgumentCaptor<PortalDO> portalCaptor = ArgumentCaptor.forClass(PortalDO.class);
        verify(portalMapper).updateById(portalCaptor.capture());
        assertEquals(PortalStatusEnum.PUBLISHED.getStatus(), portalCaptor.getValue().getStatus());
        assertEquals(3, portalCaptor.getValue().getPublishedVersion());
    }

    @Test
    void getPublishedVersion_whenModifiedStillReturnsPublishedSnapshot() {
        PortalDO portal = portal(10L, PortalStatusEnum.PUBLISHED_MODIFIED.getStatus(), 3, "draft");
        PortalVersionDO published = version(10L, 3, CONTENT);
        when(portalVersionMapper.selectByPortalIdAndVersion(10L, 3)).thenReturn(published);

        PortalVersionDO result = portalService.getPublishedVersion(portal);

        assertNotNull(result);
        assertEquals(CONTENT, result.getContent());
    }

    @Test
    void rollback_whenTargetIsCurrentPublishedVersion_marksPublished() {
        PortalDO portal = portal(10L, PortalStatusEnum.PUBLISHED_MODIFIED.getStatus(), 3, "draft");
        PortalVersionDO published = version(10L, 3, CONTENT);
        when(portalMapper.selectById(10L)).thenReturn(portal);
        when(portalVersionMapper.selectByPortalIdAndVersion(10L, 3)).thenReturn(published);

        portalService.rollback(10L, 3);

        ArgumentCaptor<PortalDO> captor = ArgumentCaptor.forClass(PortalDO.class);
        verify(portalMapper).updateById(captor.capture());
        assertEquals(PortalStatusEnum.PUBLISHED.getStatus(), captor.getValue().getStatus());
        assertEquals(CONTENT, captor.getValue().getDraftContent());
    }

    private static PortalSaveReqVO saveRequest(Long id, boolean isDefault) {
        PortalSaveReqVO reqVO = new PortalSaveReqVO();
        reqVO.setId(id);
        reqVO.setName("Portal");
        reqVO.setCode("portal_code");
        reqVO.setContent(CONTENT);
        reqVO.setIsDefault(isDefault);
        reqVO.setEnabled(true);
        return reqVO;
    }

    private static PortalDO portal(Long id, Integer status, Integer publishedVersion, String draftContent) {
        PortalDO portal = new PortalDO();
        portal.setId(id);
        portal.setName("Portal");
        portal.setCode("portal_code");
        portal.setStatus(status);
        portal.setPublishedVersion(publishedVersion);
        portal.setDraftContent(draftContent);
        portal.setEnabled(true);
        portal.setIsDefault(false);
        return portal;
    }

    private static PortalVersionDO version(Long portalId, Integer version, String content) {
        PortalVersionDO result = new PortalVersionDO();
        result.setPortalId(portalId);
        result.setVersion(version);
        result.setContent(content);
        result.setSchemaVersion(PortalConfigValidator.SCHEMA_VERSION);
        return result;
    }
}
