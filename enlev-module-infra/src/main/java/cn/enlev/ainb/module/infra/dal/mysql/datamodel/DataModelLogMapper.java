package cn.enlev.ainb.module.infra.dal.mysql.datamodel;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.infra.controller.admin.datamodel.vo.DataModelLogPageReqVO;
import cn.enlev.ainb.module.infra.dal.dataobject.datamodel.DataModelLogDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DataModelLogMapper extends BaseMapperX<DataModelLogDO> {

    default PageResult<DataModelLogDO> selectPage(DataModelLogPageReqVO pageReqVO) {
        return selectPage(pageReqVO, new LambdaQueryWrapperX<DataModelLogDO>()
                .likeIfPresent(DataModelLogDO::getTableName, pageReqVO.getTableName())
                .eqIfPresent(DataModelLogDO::getOperateType, pageReqVO.getOperateType())
                .eqIfPresent(DataModelLogDO::getSuccess, pageReqVO.getSuccess())
                .betweenIfPresent(DataModelLogDO::getCreateTime, pageReqVO.getCreateTime())
                .orderByDesc(DataModelLogDO::getId));
    }

}
