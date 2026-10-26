package cn.enlev.ainb.module.system.service.user;

import cn.enlev.ainb.module.system.dal.dataobject.user.UserPreferenceDO;

/** 当前用户主题偏好 Service。 */
public interface UserPreferenceService {

    /**
     * 获得用户主题配置。
     *
     * @param userId 用户编号
     * @return 偏好记录，不存在时返回 null
     */
    UserPreferenceDO getUserPreference(Long userId);

    /**
     * 保存用户主题配置。
     *
     * @param userId 用户编号
     * @param themeConfig 主题配置 JSON
     */
    void saveUserPreference(Long userId, String themeConfig);

}
