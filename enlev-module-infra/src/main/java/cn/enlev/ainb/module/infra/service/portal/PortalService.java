package cn.enlev.ainb.module.infra.service.portal;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.module.infra.controller.admin.portal.vo.PortalPageReqVO;
import cn.enlev.ainb.module.infra.controller.admin.portal.vo.PortalPublishReqVO;
import cn.enlev.ainb.module.infra.controller.admin.portal.vo.PortalSaveReqVO;
import cn.enlev.ainb.module.infra.dal.dataobject.portal.PortalDO;
import cn.enlev.ainb.module.infra.dal.dataobject.portal.PortalVersionDO;

import java.util.List;

public interface PortalService {

    Long createPortal(PortalSaveReqVO reqVO);

    void updatePortal(PortalSaveReqVO reqVO);

    void deletePortal(Long id);

    Long copyPortal(Long id);

    PortalDO getPortal(Long id);

    PortalDO validatePortalExists(Long id);

    PageResult<PortalDO> getPortalPage(PortalPageReqVO reqVO);

    boolean isCodeAvailable(Long id, String code);

    PortalDO getPortalByCode(String code);

    PortalVersionDO getPortalVersion(Long portalId, Integer version);

    List<PortalVersionDO> getPortalVersionList(Long portalId);

    Integer publish(PortalPublishReqVO reqVO);

    void unpublish(Long id);

    void rollback(Long portalId, Integer version);

    PortalVersionDO getPublishedVersion(PortalDO portal);

    PortalDO getDefaultPortal();

    /**
     * 获得当前用户可见的、已发布且启用的门户列表（默认门户排前）
     */
    List<PortalDO> getVisiblePublishedPortals(Long userId);

    /**
     * 判断门户对用户是否可见（按可见范围过滤，超管可见全部）
     */
    boolean isPortalVisible(PortalDO portal, Long userId);
}
