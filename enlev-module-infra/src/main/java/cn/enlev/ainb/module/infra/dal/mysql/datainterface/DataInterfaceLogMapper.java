package cn.enlev.ainb.module.infra.dal.mysql.datainterface;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.infra.controller.admin.datainterface.vo.DataInterfaceLogPageReqVO;
import cn.enlev.ainb.module.infra.dal.dataobject.datainterface.DataInterfaceLogDO;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;

@Mapper
public interface DataInterfaceLogMapper extends BaseMapperX<DataInterfaceLogDO> {

    default PageResult<DataInterfaceLogDO> selectPage(DataInterfaceLogPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<DataInterfaceLogDO>()
                .eqIfPresent(DataInterfaceLogDO::getInterfaceId, reqVO.getInterfaceId())
                .likeIfPresent(DataInterfaceLogDO::getInterfaceCode, reqVO.getInterfaceCode())
                .eqIfPresent(DataInterfaceLogDO::getInvokeType, reqVO.getInvokeType())
                .eqIfPresent(DataInterfaceLogDO::getSuccess, reqVO.getSuccess())
                .betweenIfPresent(DataInterfaceLogDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(DataInterfaceLogDO::getId));
    }

    /**
     * 清理指定时间之前的日志，供定时任务调用
     *
     * 日志表会快速膨胀，必须定期清理
     */
    default int deleteByCreateTimeBefore(LocalDateTime time) {
        return delete(new LambdaQueryWrapperX<DataInterfaceLogDO>()
                .lt(DataInterfaceLogDO::getCreateTime, time));
    }

}
