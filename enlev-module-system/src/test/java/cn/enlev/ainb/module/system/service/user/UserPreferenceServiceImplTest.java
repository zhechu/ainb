package cn.enlev.ainb.module.system.service.user;

import cn.enlev.ainb.framework.test.core.ut.BaseDbUnitTest;
import cn.enlev.ainb.module.system.dal.dataobject.user.UserPreferenceDO;
import cn.enlev.ainb.module.system.dal.mysql.user.UserPreferenceMapper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import static cn.enlev.ainb.framework.test.core.util.RandomUtils.randomLongId;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Import(UserPreferenceServiceImpl.class)
class UserPreferenceServiceImplTest extends BaseDbUnitTest {

    @Resource
    private UserPreferenceServiceImpl userPreferenceService;

    @Resource
    private UserPreferenceMapper userPreferenceMapper;

    @Test
    void testSaveUserPreference_createAndUpdate() {
        Long userId = randomLongId();
        String firstConfig = "{\"layout\":\"sidebar-nav\",\"isDark\":false}";
        String secondConfig = "{\"layout\":\"header-nav\",\"isDark\":true}";

        userPreferenceService.saveUserPreference(userId, firstConfig);
        UserPreferenceDO created = userPreferenceMapper.selectByUserId(userId);
        assertNotNull(created);
        assertEquals(firstConfig, created.getThemeConfig());

        userPreferenceService.saveUserPreference(userId, secondConfig);
        UserPreferenceDO updated = userPreferenceMapper.selectByUserId(userId);
        assertNotNull(updated);
        assertEquals(created.getId(), updated.getId());
        assertEquals(secondConfig, updated.getThemeConfig());
    }

    @Test
    void testGetUserPreference_notExists() {
        assertEquals(null, userPreferenceService.getUserPreference(randomLongId()));
    }

}
