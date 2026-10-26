package cn.enlev.ainb.module.system.service.user;

import cn.enlev.ainb.module.system.dal.dataobject.user.UserPreferenceDO;
import cn.enlev.ainb.module.system.dal.mysql.user.UserPreferenceMapper;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserPreferenceServiceImpl implements UserPreferenceService {

    @Resource
    private UserPreferenceMapper userPreferenceMapper;

    @Override
    public UserPreferenceDO getUserPreference(Long userId) {
        return userPreferenceMapper.selectByUserId(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveUserPreference(Long userId, String themeConfig) {
        UserPreferenceDO preference = userPreferenceMapper.selectByUserId(userId);
        if (preference == null) {
            preference = new UserPreferenceDO();
            preference.setUserId(userId);
            preference.setThemeConfig(themeConfig);
            try {
                userPreferenceMapper.insert(preference);
                return;
            } catch (DuplicateKeyException ignored) {
                // 并发首次保存时，另一请求可能刚创建了同一用户的偏好记录，转为更新即可。
                preference = userPreferenceMapper.selectByUserId(userId);
                if (preference == null) {
                    throw ignored;
                }
            }
        }
        UserPreferenceDO update = new UserPreferenceDO();
        update.setId(preference.getId());
        update.setThemeConfig(themeConfig);
        userPreferenceMapper.updateById(update);
    }

}
