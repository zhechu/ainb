package cn.enlev.ainb.module.infra.dal.mysql.datainterface;

import cn.enlev.ainb.framework.common.pojo.PageParam;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.infra.dal.dataobject.datainterface.DataInterfaceVersionDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface DataInterfaceVersionMapper extends BaseMapperX<DataInterfaceVersionDO> {

    default PageResult<DataInterfaceVersionDO> selectPageByInterfaceId(Long interfaceId,
                                                                      PageParam pageParam) {
        return selectPage(pageParam, new LambdaQueryWrapperX<DataInterfaceVersionDO>()
                .eq(DataInterfaceVersionDO::getInterfaceId, interfaceId)
                .orderByDesc(DataInterfaceVersionDO::getVersion));
    }

    default DataInterfaceVersionDO selectByInterfaceIdAndVersion(Long interfaceId, Integer version) {
        return selectOne(DataInterfaceVersionDO::getInterfaceId, interfaceId,
                DataInterfaceVersionDO::getVersion, version);
    }

    /**
     * 按版本号倒序查某接口的全部版本，供版本数量清理使用
     */
    default List<DataInterfaceVersionDO> selectListByInterfaceId(Long interfaceId) {
        return selectList(new LambdaQueryWrapperX<DataInterfaceVersionDO>()
                .eq(DataInterfaceVersionDO::getInterfaceId, interfaceId)
                .orderByDesc(DataInterfaceVersionDO::getVersion));
    }

    default int deleteByInterfaceId(Long interfaceId) {
        return delete(new LambdaQueryWrapperX<DataInterfaceVersionDO>()
                .eq(DataInterfaceVersionDO::getInterfaceId, interfaceId));
    }

}
