package cn.enlev.ainb.module.infra.dal.mysql.portal;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.infra.controller.admin.portal.vo.PortalPageReqVO;
import cn.enlev.ainb.module.infra.dal.dataobject.portal.PortalDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PortalMapper extends BaseMapperX<PortalDO> {

    default PortalDO selectByCode(String code) {
        return selectOne(PortalDO::getCode, code);
    }

    default PageResult<PortalDO> selectPage(PortalPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<PortalDO>()
                .likeIfPresent(PortalDO::getName, reqVO.getName())
                .likeIfPresent(PortalDO::getCode, reqVO.getCode())
                .eqIfPresent(PortalDO::getStatus, reqVO.getStatus())
                .eqIfPresent(PortalDO::getEnabled, reqVO.getEnabled())
                .orderByAsc(PortalDO::getSort)
                .orderByDesc(PortalDO::getId));
    }

    default PortalDO selectDefault() {
        return selectOne(new LambdaQueryWrapperX<PortalDO>()
                .eq(PortalDO::getIsDefault, true)
                .eq(PortalDO::getEnabled, true)
                .in(PortalDO::getStatus, 1, 2)
                .last("LIMIT 1"));
    }

    default java.util.List<PortalDO> selectPublishedEnabledList() {
        return selectList(new LambdaQueryWrapperX<PortalDO>()
                .eq(PortalDO::getEnabled, true)
                .in(PortalDO::getStatus, 1, 2)
                .orderByDesc(PortalDO::getIsDefault)
                .orderByAsc(PortalDO::getSort)
                .orderByAsc(PortalDO::getId));
    }
}
