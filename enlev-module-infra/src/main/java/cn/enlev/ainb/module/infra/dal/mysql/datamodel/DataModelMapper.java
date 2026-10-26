package cn.enlev.ainb.module.infra.dal.mysql.datamodel;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.infra.controller.admin.datamodel.vo.DataModelPageReqVO;
import cn.enlev.ainb.module.infra.dal.dataobject.datamodel.DataModelDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface DataModelMapper extends BaseMapperX<DataModelDO> {

    default DataModelDO selectByTableNameAndDataSourceConfigId(String tableName, Long dataSourceConfigId) {
        return selectOne(DataModelDO::getTableName, tableName,
                DataModelDO::getDataSourceConfigId, dataSourceConfigId);
    }

    default List<DataModelDO> selectListByDataSourceConfigId(Long dataSourceConfigId) {
        return selectList(DataModelDO::getDataSourceConfigId, dataSourceConfigId);
    }

    default PageResult<DataModelDO> selectPage(DataModelPageReqVO pageReqVO) {
        return selectPage(pageReqVO, new LambdaQueryWrapperX<DataModelDO>()
                .eqIfPresent(DataModelDO::getDataSourceConfigId, pageReqVO.getDataSourceConfigId())
                .likeIfPresent(DataModelDO::getTableName, pageReqVO.getTableName())
                .likeIfPresent(DataModelDO::getTableComment, pageReqVO.getTableComment())
                .likeIfPresent(DataModelDO::getModelName, pageReqVO.getModelName())
                .betweenIfPresent(DataModelDO::getCreateTime, pageReqVO.getCreateTime())
                .orderByDesc(DataModelDO::getId));
    }

}
