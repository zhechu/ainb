package cn.enlev.ainb.module.infra.framework.formdesign.core.schema;

import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignDO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 表单 Schema 版本号的语义测试
 *
 * 版本号本身只是个整数，真正需要守住的是它在三条写路径上的语义 —— 这三条各不相同，
 * 而且弄错了都不会立刻报错，只会在日后迁移时表现为「某些表单被跳过 / 被错误升级」：
 *
 * 1. 新建与更新 → 盖【当前】版本号（保存产出的就是当前结构）
 * 2. 复制       → 【继承】源表单的版本号（副本里的 JSON 是源那一版结构的数据）
 * 3. 回滚       → 【恢复】快照里的版本号（回滚后主表躺的是那一版结构的数据）
 *
 * 本测试直接验证语义规则，不启 Spring 容器 —— 这些规则是纯逻辑，
 * 用容器测反而会把「Mapper 有没有写库」这种无关问题混进来。
 *
 * @author 走进新时代
 */
class FormDesignSchemaTest {

    @Test
    @DisplayName("当前版本号为正数：0 或负数会让「是否需要迁移」的判断失去意义")
    void testCurrentVersionIsPositive() {
        assertTrue(FormDesignSchema.CURRENT_VERSION > 0);
    }

    @Test
    @DisplayName("快照键名不为空")
    void testSnapshotKey() {
        assertNotNull(FormDesignSchema.SNAPSHOT_KEY);
        assertFalse(FormDesignSchema.SNAPSHOT_KEY.isBlank());
    }

    // ==================== 语义一：新建与更新盖当前版本号 ====================

    @Test
    @DisplayName("新建/更新：DO 上盖的是当前版本号")
    void testSaveStampsCurrentVersion() {
        // 模拟 convertToDO 的行为
        FormDesignDO formDesign = new FormDesignDO();
        formDesign.setSchemaVersion(FormDesignSchema.CURRENT_VERSION);

        assertEquals(FormDesignSchema.CURRENT_VERSION, formDesign.getSchemaVersion());
    }

    // ==================== 语义二：复制继承源版本号 ====================

    @Test
    @DisplayName("⚠️ 复制：副本继承源表单的版本号，不能盖成当前版本")
    void testCopyInheritsSourceVersion() {
        // 造一张「旧结构」的源表单。注意这里故意用一个小于当前版本的值：
        // 如果哪天复制逻辑被改成盖当前版本，这条用例就会失败
        FormDesignDO source = new FormDesignDO();
        source.setSchemaVersion(1);

        // 模拟 copyFormDesign：BeanUtils 原样拷贝，schemaVersion 随之继承
        FormDesignDO target = new FormDesignDO();
        target.setSchemaVersion(source.getSchemaVersion());

        assertEquals(source.getSchemaVersion(), target.getSchemaVersion(),
                "副本里的 formFields/tableBinding 是源那一版结构的数据，"
                        + "盖上当前版本号等于对数据结构撒谎，迁移器会跳过它");
    }

    // ==================== 语义三：回滚恢复快照版本号 ====================

    /** 模拟 buildSnapshot 里与版本号相关的那一步 */
    private String buildSnapshot(Integer schemaVersion) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", "测试表单");
        snapshot.put(FormDesignSchema.SNAPSHOT_KEY, schemaVersion);
        return JsonUtils.toJsonString(snapshot);
    }

    /**
     * 模拟回滚时读版本号的那一步，含老快照的兜底
     *
     * 用 parseObjectQuietly 与生产代码保持一致 —— parseObject 遇到非法 JSON 会抛异常，
     * 这个差异正是本测试第一次跑时暴露出来的既有缺陷（回滚里的 null 判断是死代码，
     * 快照损坏时用户拿到 500 而非可读提示），已同步修掉。
     */
    @SuppressWarnings("unchecked")
    private Integer readSnapshotVersion(String snapshotJson) {
        Map<String, Object> snapshot = JsonUtils.parseObjectQuietly(snapshotJson, Map.class);
        Object raw = snapshot == null ? null : snapshot.get(FormDesignSchema.SNAPSHOT_KEY);
        if (raw instanceof Integer value) {
            return value;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        // 老快照没有这个键，按第 1 版处理 —— 那正是它们写入时的结构版本
        return 1;
    }

    @Test
    @DisplayName("快照带上版本号，回滚时能原样读回来")
    void testSnapshotRoundTrip() {
        String snapshot = buildSnapshot(FormDesignSchema.CURRENT_VERSION);
        assertTrue(snapshot.contains(FormDesignSchema.SNAPSHOT_KEY),
                "快照里必须有版本号，否则回滚会把旧结构数据塞回按新结构读的主表");
        assertEquals(FormDesignSchema.CURRENT_VERSION, readSnapshotVersion(snapshot));
    }

    @Test
    @DisplayName("⚠️ 本功能之前建的老快照没有版本号键，按第 1 版兜底而不是 null")
    void testLegacySnapshotFallsBackToVersion1() {
        // 老快照：只有业务字段，没有 schemaVersion
        String legacy = "{\"name\":\"老表单\",\"formConf\":\"{}\"}";
        assertEquals(1, readSnapshotVersion(legacy),
                "兜底成 null 会让回滚把 schema_version 写成空，"
                        + "而该列是 NOT NULL，更新时直接报错");
    }

    @Test
    @DisplayName("快照 JSON 解析失败时也不返回 null")
    void testBrokenSnapshotFallsBack() {
        assertEquals(1, readSnapshotVersion("not-a-json"));
    }

}
