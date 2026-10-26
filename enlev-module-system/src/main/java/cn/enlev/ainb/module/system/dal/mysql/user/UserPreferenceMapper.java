package cn.enlev.ainb.module.system.dal.mysql.user;

import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.module.system.dal.dataobject.user.UserPreferenceDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserPreferenceMapper extends BaseMapperX<UserPreferenceDO> {

    default UserPreferenceDO selectByUserId(Long userId) {
        return selectOne(UserPreferenceDO::getUserId, userId);
    }

}
