package cn.enlev.ainb.module.infra.dal.mysql.portal;

import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.infra.dal.dataobject.portal.PortalVersionDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface PortalVersionMapper extends BaseMapperX<PortalVersionDO> {

    default List<PortalVersionDO> selectListByPortalId(Long portalId) {
        return selectList(new LambdaQueryWrapperX<PortalVersionDO>()
                .eq(PortalVersionDO::getPortalId, portalId)
                .orderByDesc(PortalVersionDO::getVersion));
    }

    default PortalVersionDO selectByPortalIdAndVersion(Long portalId, Integer version) {
        return selectOne(new LambdaQueryWrapperX<PortalVersionDO>()
                .eq(PortalVersionDO::getPortalId, portalId)
                .eq(PortalVersionDO::getVersion, version));
    }

    default Integer selectMaxVersionByPortalId(Long portalId) {
        PortalVersionDO latest = selectOne(new LambdaQueryWrapperX<PortalVersionDO>()
                .eq(PortalVersionDO::getPortalId, portalId)
                .orderByDesc(PortalVersionDO::getVersion)
                .last("LIMIT 1"));
        return latest == null ? 0 : latest.getVersion();
    }

    default void deleteOldestVersions(Long portalId, int count) {
        List<PortalVersionDO> versions = selectList(new LambdaQueryWrapperX<PortalVersionDO>()
                .eq(PortalVersionDO::getPortalId, portalId)
                .orderByAsc(PortalVersionDO::getVersion)
                .last("LIMIT " + count));
        versions.forEach(version -> deleteById(version.getId()));
    }
}
