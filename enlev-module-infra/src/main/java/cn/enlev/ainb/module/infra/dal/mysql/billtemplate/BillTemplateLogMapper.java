package cn.enlev.ainb.module.infra.dal.mysql.billtemplate;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.infra.controller.admin.billtemplate.vo.BillTemplateLogPageReqVO;
import cn.enlev.ainb.module.infra.dal.dataobject.billtemplate.BillTemplateLogDO;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;

@Mapper
public interface BillTemplateLogMapper extends BaseMapperX<BillTemplateLogDO> {

    default PageResult<BillTemplateLogDO> selectPage(BillTemplateLogPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<BillTemplateLogDO>()
                .eqIfPresent(BillTemplateLogDO::getTemplateId, reqVO.getTemplateId())
                .likeIfPresent(BillTemplateLogDO::getBillNo, reqVO.getBillNo())
                .eqIfPresent(BillTemplateLogDO::getSource, reqVO.getSource())
                .eqIfPresent(BillTemplateLogDO::getSuccess, reqVO.getSuccess())
                .betweenIfPresent(BillTemplateLogDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(BillTemplateLogDO::getId));
    }

    /**
     * 清理指定时间之前的日志，供将来的定时任务调用
     *
     * 取号日志默认关闭、量可控，暂不做自动清理，先预留钩子
     */
    default int deleteByCreateTimeBefore(LocalDateTime time) {
        return delete(new LambdaQueryWrapperX<BillTemplateLogDO>()
                .lt(BillTemplateLogDO::getCreateTime, time));
    }

}
