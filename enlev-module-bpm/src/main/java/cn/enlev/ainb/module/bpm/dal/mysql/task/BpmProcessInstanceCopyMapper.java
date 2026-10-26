package cn.enlev.ainb.module.bpm.dal.mysql.task;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.bpm.controller.admin.task.vo.instance.BpmProcessInstanceCopyPageReqVO;
import cn.enlev.ainb.module.bpm.dal.dataobject.task.BpmProcessInstanceCopyDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BpmProcessInstanceCopyMapper extends BaseMapperX<BpmProcessInstanceCopyDO> {

    default PageResult<BpmProcessInstanceCopyDO> selectPage(Long loginUserId, BpmProcessInstanceCopyPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<BpmProcessInstanceCopyDO>()
                .eqIfPresent(BpmProcessInstanceCopyDO::getUserId, loginUserId)
                .likeIfPresent(BpmProcessInstanceCopyDO::getProcessInstanceName, reqVO.getProcessInstanceName())
                .betweenIfPresent(BpmProcessInstanceCopyDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(BpmProcessInstanceCopyDO::getId));
    }

    default void deleteByProcessInstanceId(String processInstanceId) {
        delete(BpmProcessInstanceCopyDO::getProcessInstanceId, processInstanceId);
    }

    /**
     * 该用户是不是这条流程实例的抄送人
     *
     * 给实例级访问控制用：抄送人虽然不审批，但确实该看得到这条单子。
     * 用 count 而不是查出实体 —— 只关心存不存在，抄送一条实例可能有很多人
     */
    default boolean existsByProcessInstanceIdAndUserId(String processInstanceId, Long userId) {
        return selectCount(new LambdaQueryWrapperX<BpmProcessInstanceCopyDO>()
                .eq(BpmProcessInstanceCopyDO::getProcessInstanceId, processInstanceId)
                .eq(BpmProcessInstanceCopyDO::getUserId, userId)) > 0;
    }

}
