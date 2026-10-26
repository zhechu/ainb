package cn.enlev.ainb.module.bpm.dal.mysql.definition;

import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.bpm.dal.dataobject.definition.BpmDelegationDO;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 流程委托 Mapper
 *
 * @author 走进新时代
 */
@Mapper
public interface BpmDelegationMapper extends BaseMapperX<BpmDelegationDO> {

    /**
     * 查「谁把事情委托给了我」，且当前时间在有效期内
     *
     * ⚠️ 有效期判定放在 SQL 里而不是查出来再过滤：委托是<b>每次查待办都要走一遍</b>的
     * 热路径，把失效的行捞回内存再扔掉是白花的 IO。
     *
     * ⚠️ {@code end_time IS NULL} 要显式放行 —— 表示长期有效。
     * 漏了这一条的表现是「长期委托配了但一条都不生效」，而界面上看着好好的
     */
    default List<BpmDelegationDO> selectActiveListByDelegateUserId(Long delegateUserId, LocalDateTime now) {
        return selectList(new LambdaQueryWrapperX<BpmDelegationDO>()
                .eq(BpmDelegationDO::getDelegateUserId, delegateUserId)
                .eq(BpmDelegationDO::getStatus, 0)
                .le(BpmDelegationDO::getStartTime, now)
                .and(w -> w.isNull(BpmDelegationDO::getEndTime)
                        .or().ge(BpmDelegationDO::getEndTime, now)));
    }

    /**
     * 查「我委托给了谁」，用于管理界面
     */
    default List<BpmDelegationDO> selectListByDelegatorUserId(Long delegatorUserId) {
        return selectList(BpmDelegationDO::getDelegatorUserId, delegatorUserId);
    }

}
