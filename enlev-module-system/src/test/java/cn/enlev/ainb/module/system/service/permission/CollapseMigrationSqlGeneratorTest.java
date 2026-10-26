package cn.enlev.ainb.module.system.service.permission;

import cn.enlev.ainb.module.system.dal.dataobject.permission.GrantDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.framework.permission.core.scan.PermissionStringResolver;
import cn.enlev.ainb.module.system.framework.permission.core.scan.ResourceCollapseApplier;
import cn.enlev.ainb.module.system.framework.permission.core.scan.ResourceCollapseApplier.CollapsedWorld;
import cn.enlev.ainb.module.system.framework.permission.core.scan.ResourceCollapsePlanner;
import cn.enlev.ainb.module.system.framework.permission.core.scan.ResourceCollapsePlanner.CollapsePlan;
import cn.enlev.ainb.module.system.framework.permission.core.scan.ResourceCollapsePlanner.GrantRewrite;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 阶段 2d 的<b>落库脚本生成器</b>：把 {@link CollapsePlan} 渲染成一份可执行、可审计、自带守卫的 SQL
 *
 * <h3>为什么是「生成 SQL」而不是「写一个 Service 去调 Mapper」</h3>
 *
 * 三个理由，第一个就是决定性的：
 * <ol>
 *   <li><b>租户拦截器</b>。{@code GrantDO extends TenantBaseDO}，走 MyBatis 批量改写
 *       {@code resource_id} 时拦截器会往 WHERE 里塞 {@code tenant_id = ?}，
 *       而本次要改的 1127 条授权横跨 4 个租户（1/121/122/162）。
 *       要么逐租户跑四遍（漏一个就是静默半迁移），要么关拦截器 ——
 *       而「为了迁移把租户隔离整体关掉」正是本仓库明令禁止的那类操作。
 *       裸 SQL 按主键改写，压根不进这条路</li>
 *   <li><b>不留生产代码</b>。一次性的迁移执行器留在 {@code src/main/java} 里，
 *       将来谁都能再调一次 —— 而它对已经塌缩过的世界再跑一遍会发生什么，没人验证过。
 *       生成器住在 {@code src/test/java}，产物是一份读完就能扔的脚本</li>
 *   <li><b>可审计</b>。1127 条改写摊开成人能读的文件，评审能真的看；
 *       而一段循环里的 {@code updateById} 谁也看不出它到底改了哪一行</li>
 * </ol>
 *
 * <h3>🔴 发号起点必须取<b>全表</b> MAX(id)，不是快照的 MAX(id)</h3>
 *
 * 实测 {@code sys_resource} 全表 {@code MAX(id)=9000100776}，而 {@code deleted=0} 的最大值只有
 * {@code 9000100768} —— 中间那 8 个 id 是已软删的死行。
 * {@link ResourceCollapseApplier#sequentialFrom} 是拿<b>入参那批行</b>算起点的，
 * 而快照只含存活行，所以照它发号会从 {@code 9000100769} 开始，
 * <b>正好撞上那 8 行死行的主键</b> ⟹ 插入直接 PK 冲突。
 *
 * <p>撞上是好事（会报错），真正可怕的是反过来：如果那些死行的 id 恰好不在新号段里，
 * 脚本会顺利跑完，而库里多出一批 id 与死行交错的身份行 —— 没有任何症状。
 * 所以这里用 {@link #FIRST_NEW_RESOURCE_ID} 显式定死起点，并由前置守卫
 * 把「全表 MAX(id) 仍等于 {@link #EXPECTED_MAX_RESOURCE_ID}」钉成执行条件
 *
 * <h3>守卫是这份脚本的主要价值，不是附带品</h3>
 *
 * 计划是<b>基于一份快照</b>算出来的。快照导出之后库只要动过一行，这份脚本就是错的，
 * 而它的错法全是静默的（改错一条授权 = 改错一次权限）。所以：
 * <ul>
 *   <li><b>前置守卫</b>比对两张存活表的<b>整表校验和</b>（逐列拼接后 MD5）。
 *       不是「行数对就行」—— 行数一样、内容不一样的情况恰恰是最难发现的</li>
 *   <li><b>后置守卫</b>在事务<b>内部</b>比对迁移后的行数、悬空引用数、97 个身份是否都还在。
 *       任一条不满足就 {@code SIGNAL}，客户端中止，事务不提交即回滚</li>
 * </ul>
 *
 * 🔴 执行时<b>绝不能加 {@code --force}</b>：加了之后守卫报错会被忽略、脚本继续往下跑，
 * 最后照样 COMMIT。守卫的全部意义就在于让客户端停下来
 *
 * <h3>产物与执行方式</h3>
 *
 * 三个文件都写到仓库外的 {@code _scratch/}（与快照同目录，都不进 git）：
 * <pre>
 * 2d_migration.sql           真正执行的脚本
 * 2d_checksum_probe.sql      只读：把库里的实际校验和查出来，供与期望值比对
 * 2d_expected_world.tsv      迁移后应有的全部存活行，供落库后逐行 diff
 * 2d_verify.sql              只读：按与 tsv 完全相同的列与顺序把库里的实际行导出来
 * </pre>
 *
 * 执行顺序（每一步都要过，不许跳）：
 * <pre>
 * ① mysqldump 备份 sys_resource / sys_grant / system_menu
 * ② 跑本类生成四个文件
 * ③ mysql &lt; 2d_checksum_probe.sql &gt; actual.tsv，与生成器打印的期望值逐行比对 —— 不一致就停
 * ④ mysql &lt; 2d_migration.sql（不加 --force）
 * ⑤ mysql -N -B &lt; 2d_verify.sql &gt; actual_world.tsv，与 2d_expected_world.tsv diff —— 必须为空
 * ⑥ 翻 enlev.permission.collapse-enabled，重启，冒烟
 * </pre>
 *
 * 🔴 第 ③⑤ 两步的 diff 都要先 {@code tr -d '\r'} 并按同一口径排序：
 * Windows 上 mysql 客户端输出 CRLF 而 Java 写的是 LF，
 * 不处理的话每一行都「不同」，而八项全对的门禁看起来像是全错 ——
 * 那种 diff 只会让人开始怀疑比对方法本身，而不是去看数据
 *
 * @author 走进新时代
 */
class CollapseMigrationSqlGeneratorTest {

    // ==================== 快照导出时刻的库状态（实测值，前置守卫按这几个数字钉死） ====================

    /** {@code SELECT COUNT(*) FROM sys_resource WHERE deleted=0} */
    private static final int EXPECTED_LIVE_RESOURCES = 1022;
    /** {@code SELECT COUNT(*) FROM sys_grant WHERE deleted=0} */
    private static final int EXPECTED_LIVE_GRANTS = 1802;
    /** 🔴 全表 MAX(id)，含软删行。见类 javadoc 那一段 */
    private static final long EXPECTED_MAX_RESOURCE_ID = 9000100776L;
    /** 33 个新建身份行的第一个主键 = {@link #EXPECTED_MAX_RESOURCE_ID} + 1 */
    private static final long FIRST_NEW_RESOURCE_ID = 9000100777L;

    /** 迁移后应有的存活行数，来自自校验报告实测 */
    private static final int EXPECTED_AFTER_RESOURCES = 598;
    private static final int EXPECTED_AFTER_GRANTS = 1787;

    /** 落库痕迹写进 updater，将来在库里能一眼认出哪些行是这次迁移改的 */
    private static final String MIGRATION_ACTOR = "collapse-2d";

    private static final DateTimeFormatter COMPACT_TS =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static CollapseSnapshot snapshot;
    private static CollapsePlan plan;
    private static CollapsedWorld world;
    private static Path scratch;

    private static final List<String> REPORT = new ArrayList<>();

    @BeforeAll
    static void load() {
        snapshot = CollapseSnapshot.loadOrNull();
        assumeTrue(snapshot != null, "没有 _scratch/s2c_snapshot.json，跳过（不是失败）");
        snapshot.assertSubjectGraphEmpty();
        scratch = scratchDir();

        plan = new ResourceCollapsePlanner(new PermissionStringResolver()).plan(
                snapshot.resources(), snapshot.grants(),
                snapshot.menuPermissions(), snapshot.navMenuIds());
        world = ResourceCollapseApplier.apply(
                snapshot.resources(), snapshot.grants(), plan, dbMaxAllocator());

        // 🔴 这两条是「生成器与自校验跑的是同一个计划」的唯一证据。
        // 数字来自 target/s2c-selfcheck-report.txt 的实测行，
        // 对不上说明快照或计划器在两次运行之间变了 —— 那份绿了的自校验就不作数了
        assertEquals(EXPECTED_LIVE_RESOURCES, snapshot.resources().size(),
                "快照里的资源行数与前置守卫钉死的数字不一致：守卫会永远中止，先搞清楚哪一边过期了");
        assertEquals(EXPECTED_LIVE_GRANTS, snapshot.grants().size(),
                "快照里的授权行数与前置守卫钉死的数字不一致");
        assertEquals(EXPECTED_AFTER_RESOURCES, world.resources().size(),
                "迁移后的资源行数与自校验报告里的 598 不一致 —— 计划变了，自校验必须重跑");
        assertEquals(EXPECTED_AFTER_GRANTS, world.grants().size(),
                "迁移后的授权行数与自校验报告里的 1787 不一致 —— 计划变了，自校验必须重跑");
    }

    /**
     * 从<b>全表</b> MAX(id) 之后发号，而不是照 {@link ResourceCollapseApplier#sequentialFrom}
     *
     * <p>{@code sequentialFrom} 只看传给它的那批行，而快照只含存活行 ——
     * 用它发号会撞上软删死行的主键。理由与后果见类 javadoc
     */
    private static ResourceCollapseApplier.IdentityIdAllocator dbMaxAllocator() {
        long[] next = {FIRST_NEW_RESOURCE_ID};
        return template -> next[0]++;
    }

    // ==================== 产物 1：迁移脚本 ====================

    @Test
    @DisplayName("生成 2d_migration.sql：前置守卫 + 一个事务里的全部 DML + 后置守卫")
    void generateMigrationSql() throws IOException {
        Map<String, String> identities = newIdentityIds();
        StringBuilder sql = new StringBuilder(64 * 1024);

        sql.append(header());
        sql.append("SET SESSION group_concat_max_len = 4294967295;\n\n");
        sql.append("DROP PROCEDURE IF EXISTS _collapse_2d_pre_guard;\n");
        sql.append("DROP PROCEDURE IF EXISTS _collapse_2d_post_guard;\n\n");

        // 存储过程是 DDL，会隐式提交，所以必须建在事务之外
        sql.append("DELIMITER $$\n");
        sql.append(preGuardProcedure());
        sql.append(postGuardProcedure(identities.keySet()));
        sql.append("DELIMITER ;\n\n");

        sql.append("-- 前置守卫：库必须与快照逐字节一致，否则整批放弃\n");
        sql.append("CALL _collapse_2d_pre_guard();\n\n");

        sql.append("START TRANSACTION;\n\n");
        sql.append("-- 脏数据基线。这两处本次迁移都不负责修，但也不许把它们改多或改少，\n");
        sql.append("-- 所以后置守卫比的是「与这里的值相同」而不是「为 0」。\n");
        sql.append("-- 实测基线：悬空授权 56 条（指向早已软删的资源），parent_id 悬空 0 行\n");
        sql.append("SET @dirty_grants_before := (").append(DIRTY_GRANT_SQL).append(");\n");
        sql.append("SET @dangling_parents_before := (").append(DANGLING_PARENT_SQL).append(");\n\n");

        appendRetireResources(sql);
        appendMenuIdUpdates(sql);
        appendDefaultEffectUpdates(sql);
        appendCreatedIdentities(sql, identities);
        appendGrantRewrites(sql, identities);
        appendRedundantGrants(sql);

        sql.append("-- 后置守卫：在事务内比对，任一条不满足就 SIGNAL ⟹ 客户端中止 ⟹ 不提交即回滚\n");
        sql.append("CALL _collapse_2d_post_guard(@dirty_grants_before, @dangling_parents_before);\n\n");
        sql.append("COMMIT;\n\n");
        sql.append("DROP PROCEDURE IF EXISTS _collapse_2d_pre_guard;\n");
        sql.append("DROP PROCEDURE IF EXISTS _collapse_2d_post_guard;\n");

        Path out = scratch.resolve("2d_migration.sql");
        Files.writeString(out, sql.toString(), StandardCharsets.UTF_8);
        int created = plan.getCreatedIdentities().size();
        REPORT.add(String.format("[产物] %s（%d 字节）", out.toAbsolutePath(), sql.length()));
        REPORT.add(String.format("[变更集] 资源：退役 %d 行、新建 %d 行、menu_id 改 %d 行、default_effect 改 %d 行",
                plan.getRetiredResourceIds().size(), created,
                plan.getMenuIdUpdates().size(), plan.getDefaultEffectUpdates().size()));
        REPORT.add(String.format("[变更集] 授权：改写 %d 条、判重软删 %d 条、原样 %d 条",
                plan.getGrantRewrites().size(), plan.getRedundantGrantIds().size(),
                plan.getUntouchedGrantIds().size()));
        REPORT.add(String.format("[发号] %d 个新建身份行主键 %d ~ %d（全表 MAX(id)=%d 之后）",
                created, FIRST_NEW_RESOURCE_ID, FIRST_NEW_RESOURCE_ID + created - 1,
                EXPECTED_MAX_RESOURCE_ID));
        REPORT.add(String.format("[身份表] 共 %d 项 = 已有锚点 %d 个 + 新建 %d 行",
                identities.size(), identities.size() - created, created));
    }

    private String header() {
        return """
                -- ============================================================================
                -- 阶段 2d：权限资源塌缩迁移
                --
                -- 由 CollapseMigrationSqlGeneratorTest 生成，🔴 请勿手工编辑：
                -- 手改过的脚本与自校验证明过的那一份就不再是同一个东西了
                --
                -- 计划来源：_scratch/s2c_snapshot.json
                -- 生成时间：%s
                --
                -- 执行：
                --   mysql -h127.0.0.1 -P3306 -uroot -p --default-character-set=utf8mb4 \\
                --         "ruoyi-vue-pro" < 2d_migration.sql
                --
                -- 🔴 绝不能加 --force：守卫报错会被忽略、脚本继续往下跑、最后照样 COMMIT。
                --    守卫的全部意义就是让客户端停下来
                -- 🔴 执行前必须已完成 mysqldump 备份，且 2d_checksum_probe.sql 的比对已通过
                -- ============================================================================

                """.formatted(LocalDateTime.now().withNano(0));
    }

    // ==================== 守卫 ====================

    /** {@code sys_resource} 一行的校验和表达式（存活行） */
    private static final String RESOURCE_ROW_EXPR = """
            CONCAT_WS(CHAR(1), id, permission, name, type, parent_id, path, depth, sort, \
            default_effect, status, IFNULL(menu_id, CHAR(126)), IFNULL(dataset_table, CHAR(126)), \
            IFNULL(share_policy, CHAR(126)))""";

    /**
     * {@code sys_grant} 一行的校验和表达式（存活行）
     *
     * <p>🔴 {@code DATE_FORMAT} 的格式串写<b>单</b>百分号。这个常量只是被拼进
     * {@link #GRANT_CHECKSUM_SQL}，然后作为<b>参数</b>传给 {@code formatted(...)} ——
     * 参数里的 {@code %} 不会被二次解释。写成 {@code %%Y} 的话脚本里就真的多出两个百分号，
     * 而 {@code DATE_FORMAT} 遇到非法格式串不报错，只是返回一段与 Java 侧对不上的文本，
     * 表现是「前置守卫永远说库偏离了快照」，查起来毫无头绪
     */
    private static final String GRANT_ROW_EXPR = """
            CONCAT_WS(CHAR(1), id, subject_type, IFNULL(subject_ref_id, CHAR(126)), \
            IFNULL(subject_code, CHAR(126)), resource_id, IFNULL(context_resource_id, CHAR(126)), \
            action, effect, IFNULL(rule_id, CHAR(126)), IFNULL(field_mode, CHAR(126)), \
            IFNULL(DATE_FORMAT(expire_time, '%Y%m%d%H%i%s'), CHAR(126)), tenant_id)""";

    /**
     * 整表校验和：先把<b>每一行</b>压成 32 位十六进制，再用 GROUP_CONCAT 的<b>默认</b>分隔符拼起来
     *
     * <h3>🔴 为什么不用 {@code SEPARATOR CHAR(2)}</h3>
     *
     * 因为那不是合法 SQL。MySQL 的 {@code GROUP_CONCAT} 文法里 SEPARATOR 后面只能跟
     * <b>字符串字面量</b>，跟函数调用会直接 1064：
     * {@code near 'CHAR(2))) FROM sys_resource'}（实测过，就在 2d 第一次跑探针时）。
     * 退一步写 {@code SEPARATOR '\n'} 之类的字面量也不妥 —— 那等于把「本表的
     * permission/name/path 里绝不出现换行」当成前提，而这个前提没有任何东西在保证
     * （{@code bpm:category} 下面就已经挂着一条 permission 是 URL 的脏行了）。
     *
     * <p>嵌套一层 MD5 之后分隔符不再承载任何信息：每个元素恒为 32 个 {@code [0-9a-f]}，
     * 逗号在这个字母表里不可能出现，所以拼接是<b>无歧义</b>的，不需要挑一个「大概不会撞上」的串。
     * 代价只是多算一千多次 MD5，对一次性的迁移无所谓
     *
     * <p>{@code ORDER BY id} 必须在里面：不加的话 GROUP_CONCAT 的顺序由执行计划决定，
     * 同一个库跑两次能得出两个校验和，那守卫就只会制造假警报
     *
     * <p>{@code SET SESSION group_concat_max_len} 也必须在外面先设：默认 1024 字节，
     * 而 1022 行 × 33 字节 ≈ 34KB，会被<b>静默截断</b> —— 截断之后两边都算得出一个值，
     * 只是永远不相等，看起来像「库偏离了快照」
     */
    private static final String RESOURCE_CHECKSUM_SQL =
            "MD5(GROUP_CONCAT(MD5(" + RESOURCE_ROW_EXPR + ") ORDER BY id))";
    private static final String GRANT_CHECKSUM_SQL =
            "MD5(GROUP_CONCAT(MD5(" + GRANT_ROW_EXPR + ") ORDER BY id))";

    /** 行与行之间的分隔符，对应 GROUP_CONCAT 的默认分隔符 */
    private static final String ROW_SEPARATOR = ",";

    /** 存活授权指向「不存在或已软删的资源」。今天实测 56 条，迁移不负责修 */
    private static final String DIRTY_GRANT_SQL =
            "SELECT COUNT(*) FROM sys_grant g LEFT JOIN sys_resource r"
                    + " ON r.id = g.resource_id AND r.deleted = 0"
                    + " WHERE g.deleted = 0 AND r.id IS NULL";

    /** 存活资源的 parent_id 悬空。实测今天是 0 行，迁移不负责维持它的语义，只负责不改它 */
    private static final String DANGLING_PARENT_SQL =
            "SELECT COUNT(*) FROM sys_resource c LEFT JOIN sys_resource p"
                    + " ON p.id = c.parent_id AND p.deleted = 0"
                    + " WHERE c.deleted = 0 AND c.parent_id <> 0 AND p.id IS NULL";

    private String preGuardProcedure() {
        return """
                CREATE PROCEDURE _collapse_2d_pre_guard()
                BEGIN
                  DECLARE n_res INT;
                  DECLARE n_gr INT;
                  DECLARE max_id BIGINT;
                  DECLARE non_zero_tenant INT;
                  DECLARE cs_res CHAR(32);
                  DECLARE cs_gr CHAR(32);
                  SELECT COUNT(*) INTO n_res FROM sys_resource WHERE deleted = 0;
                  SELECT COUNT(*) INTO n_gr FROM sys_grant WHERE deleted = 0;
                  SELECT MAX(id) INTO max_id FROM sys_resource;
                  SELECT COUNT(*) INTO non_zero_tenant FROM sys_resource WHERE deleted = 0 AND tenant_id <> 0;
                  SELECT %s INTO cs_res FROM sys_resource WHERE deleted = 0;
                  SELECT %s INTO cs_gr FROM sys_grant WHERE deleted = 0;
                  IF n_res <> %d THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '中止：存活资源行数与快照不符，库已偏离';
                  END IF;
                  IF n_gr <> %d THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '中止：存活授权行数与快照不符，库已偏离';
                  END IF;
                  IF max_id <> %d THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '中止：资源表 MAX(id) 变了，新建行主键会撞车';
                  END IF;
                  IF non_zero_tenant <> 0 THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '中止：出现了 tenant_id 非 0 的资源行';
                  END IF;
                  IF cs_res IS NULL OR cs_res <> '%s' THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '中止：资源表内容与快照不一致，计划已过期';
                  END IF;
                  IF cs_gr IS NULL OR cs_gr <> '%s' THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '中止：授权表内容与快照不一致，计划已过期';
                  END IF;
                END$$

                """.formatted(RESOURCE_CHECKSUM_SQL, GRANT_CHECKSUM_SQL,
                EXPECTED_LIVE_RESOURCES, EXPECTED_LIVE_GRANTS, EXPECTED_MAX_RESOURCE_ID,
                resourceChecksum(), grantChecksum());
    }

    private String postGuardProcedure(Set<String> identities) {
        return """
                CREATE PROCEDURE _collapse_2d_post_guard(IN dirty_before INT, IN dangling_before INT)
                BEGIN
                  DECLARE n_res INT;
                  DECLARE n_gr INT;
                  DECLARE dirty INT;
                  DECLARE dangling INT;
                  DECLARE ident INT;
                  DECLARE non_zero_tenant INT;
                  SELECT COUNT(*) INTO n_res FROM sys_resource WHERE deleted = 0;
                  SELECT COUNT(*) INTO n_gr FROM sys_grant WHERE deleted = 0;
                  SELECT COUNT(*) INTO non_zero_tenant FROM sys_resource WHERE deleted = 0 AND tenant_id <> 0;
                  SELECT (%s) INTO dirty;
                  SELECT (%s) INTO dangling;
                  SELECT COUNT(DISTINCT permission) INTO ident FROM sys_resource
                   WHERE deleted = 0 AND permission IN (%s);
                  IF n_res <> %d THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '中止：迁移后存活资源行数不是预期值';
                  END IF;
                  IF n_gr <> %d THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '中止：迁移后存活授权行数不是预期值';
                  END IF;
                  IF non_zero_tenant <> 0 THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '中止：新建的身份行 tenant_id 不是 0';
                  END IF;
                  IF dirty <> dirty_before THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '中止：悬空授权条数变了，改写指错了资源';
                  END IF;
                  IF dangling <> dangling_before THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '中止：parent_id 悬空行数变了';
                  END IF;
                  IF ident <> %d THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '中止：身份串没有全部落在存活行上';
                  END IF;
                END$$

                """.formatted(DIRTY_GRANT_SQL, DANGLING_PARENT_SQL,
                inList(identities), EXPECTED_AFTER_RESOURCES, EXPECTED_AFTER_GRANTS,
                identities.size());
    }

    // ==================== DML 各段 ====================

    private void appendRetireResources(StringBuilder sql) {
        Set<Long> ids = new LinkedHashSet<>(plan.getRetiredResourceIds());
        sql.append("-- 1) 退役被合并掉的成员行（软删）。共 ").append(ids.size()).append(" 行\n");
        appendSoftDelete(sql, "sys_resource", ids);
        sql.append('\n');
    }

    private void appendMenuIdUpdates(StringBuilder sql) {
        Map<Long, Long> updates = plan.getMenuIdUpdates();
        sql.append("-- 2) R1：menu_id 归属。只改真的会变的那些行，共 ")
                .append(updates.size()).append(" 行\n");
        sql.append("-- 🔴 绝不能拿 NULL 去覆盖一个本来有值的 menu_id，所以这里逐行写死目标值\n");
        for (Map.Entry<Long, Long> e : updates.entrySet()) {
            sql.append(String.format(
                    "UPDATE sys_resource SET menu_id = %d, updater = '%s', update_time = NOW()"
                            + " WHERE deleted = 0 AND id = %d;%n",
                    e.getValue(), MIGRATION_ACTOR, e.getKey()));
        }
        sql.append('\n');
    }

    private void appendDefaultEffectUpdates(StringBuilder sql) {
        Map<Long, String> updates = plan.getDefaultEffectUpdates();
        sql.append("-- 3) 合并后取最严的 default_effect，共 ").append(updates.size()).append(" 行\n");
        sql.append("-- 🔴 这是本次迁移唯一一处有意改变判定结果的地方：漏掉它，这批成员串\n");
        sql.append("--    就改走锚点的 ALLOW 回落（越权），全程 200、无异常。\n");
        sql.append("--    自校验量到的口径：改写 7 行、涉及 7 个身份串、改变 464 个决策点，\n");
        sql.append("--    方向全部是「迁移后收紧」，未登记差异 0 个\n");
        for (Map.Entry<Long, String> e : updates.entrySet()) {
            sql.append(String.format(
                    "UPDATE sys_resource SET default_effect = %s, updater = '%s', update_time = NOW()"
                            + " WHERE deleted = 0 AND id = %d;%n",
                    sqlStr(e.getValue()), MIGRATION_ACTOR, e.getKey()));
        }
        sql.append('\n');
    }

    private void appendCreatedIdentities(StringBuilder sql, Map<String, String> identities) {
        List<String> rows = new ArrayList<>();
        for (ResourceDO r : world.resources()) {
            if (r.getId() < FIRST_NEW_RESOURCE_ID) {
                continue;   // 已有行，不是新建的
            }
            rows.add(String.format(
                    "  (%d, %s, %s, %s, %d, %s, %d, %d, %s, %d, %s, %s, %s, '%s', NOW(), '%s', NOW(), 0, 0)",
                    r.getId(), sqlStr(r.getPermission()), sqlStr(r.getName()), sqlStr(r.getType()),
                    r.getParentId(), sqlStr(r.getPath()), r.getDepth(), nz(r.getSort()),
                    sqlStr(r.getDefaultEffect()), nz(r.getStatus()), nullableNum(r.getMenuId()),
                    nullableStr(r.getDatasetTable()), nullableStr(r.getSharePolicy()),
                    MIGRATION_ACTOR, MIGRATION_ACTOR));
        }
        // 🔴 比的是「新建行数 = 计划器给出的新建模板数」，不是身份总数。
        // 身份表 97 项里 64 项落在<b>已有</b>的锚点行上（不新建），只有 33 项要发新号 ——
        // 拿 97 去比 33 必然红，而那条红会被误读成「发号出问题了」
        assertEquals(plan.getCreatedIdentities().size(), rows.size(),
                "新建行的条数与计划器的 createdIdentities 对不上 —— 发号起点或 world 里混进了别的东西");
        assertEquals(identities.size(), plan.getIdentityOf().size() + rows.size(),
                "身份表应当 = 已有锚点 + 新建行，两边加不拢说明有身份既是锚点又是新建");
        sql.append("-- 4) 新建身份行，共 ").append(rows.size()).append(" 行\n");
        sql.append("-- 🔴 主键显式写出（").append(FIRST_NEW_RESOURCE_ID)
                .append(" 起），不是让 AUTO_INCREMENT 发号：\n");
        sql.append("--    后面第 5 段的授权改写要按 id 指向它们，靠 LAST_INSERT_ID() 一次只能拿到一个\n");
        sql.append("INSERT INTO sys_resource\n");
        sql.append("  (id, permission, name, type, parent_id, path, depth, sort, default_effect,\n");
        sql.append("   status, menu_id, dataset_table, share_policy, creator, create_time,\n");
        sql.append("   updater, update_time, deleted, tenant_id)\nVALUES\n");
        sql.append(String.join(",\n", rows)).append(";\n\n");
    }

    /**
     * R9 + R7：把授权搬到身份行上，并把动作从权限串里挪进 {@code action} 列
     *
     * <p>按 (目标资源, 目标动作) 分桶再批量改写，而不是逐条一句：
     * 1127 条摊成 1127 句会让脚本大到没法审，而分桶后每一句的语义是完全一样的
     */
    private void appendGrantRewrites(StringBuilder sql, Map<String, String> identities) {
        // (资源 id, 动作) → grant id 集合。直接遍历改写集建桶，不先摊成两张平行的 map ——
        // 平行 map 要靠「两边的 key 集合恒等」才对得上，而那正是不写出来就没人保证的性质
        Map<String, List<Long>> buckets = new TreeMap<>();
        for (GrantRewrite rw : plan.getGrantRewrites()) {
            String idExpr = identities.get(rw.getIdentity());
            assertNotNull(idExpr, "改写目标身份 " + rw.getIdentity() + " 没有对应的资源行 id");
            buckets.computeIfAbsent(idExpr + "\u0000" + rw.getAction(), k -> new ArrayList<>())
                    .add(rw.getGrantId());
        }
        sql.append("-- 5) R9 授权改写（含 R7 的通配收窄）：共 ")
                .append(plan.getGrantRewrites().size()).append(" 条，分 ")
                .append(buckets.size()).append(" 桶批量执行\n");
        sql.append("-- 🔴 resource_id 与 action 必须同时改：只搬 id 不改动作 ⟹ 判定侧解析出\n");
        sql.append("--    CREATE 而库里只有 VIEW ⟹ 新增/修改/删除/导出/导入按钮全部静默消失；\n");
        sql.append("--    只改动作不搬 id ⟹ 授权留在已退役的行上，等于被删掉\n");
        int total = 0;
        for (Map.Entry<String, List<Long>> e : buckets.entrySet()) {
            String[] parts = e.getKey().split("\u0000");
            List<Long> ids = e.getValue();
            total += ids.size();
            sql.append(String.format(
                    "UPDATE sys_grant SET resource_id = %s, action = %s, updater = '%s',"
                            + " update_time = NOW() WHERE deleted = 0 AND id IN (%s);%n",
                    parts[0], sqlStr(parts[1]), MIGRATION_ACTOR, joinIds(ids)));
        }
        assertEquals(plan.getGrantRewrites().size(), total,
                "分桶之后授权条数变了 —— 桶键撞车，有两条改写被并成了一句");
        sql.append('\n');
    }

    private void appendRedundantGrants(StringBuilder sql) {
        Set<Long> ids = new LinkedHashSet<>(plan.getRedundantGrantIds());
        sql.append("-- 6) R10：合并后完全重复的授权，软删。共 ").append(ids.size()).append(" 条\n");
        sql.append("-- 留着的话「同一个人对同一资源的同一动作」在授权页上会出现两行，删除时只删得掉一行\n");
        appendSoftDelete(sql, "sys_grant", ids);
        sql.append('\n');
    }

    private void appendSoftDelete(StringBuilder sql, String table, Collection<Long> ids) {
        if (ids.isEmpty()) {
            sql.append("-- （本次没有需要软删的行）\n");
            return;
        }
        // 分片：一句 IN 里塞几千个 id 会撞上 max_allowed_packet，也让 diff 读不动
        List<Long> all = new ArrayList<>(ids);
        for (int i = 0; i < all.size(); i += 200) {
            List<Long> chunk = all.subList(i, Math.min(all.size(), i + 200));
            sql.append(String.format(
                    "UPDATE %s SET deleted = 1, updater = '%s', update_time = NOW()"
                            + " WHERE deleted = 0 AND id IN (%s);%n",
                    table, MIGRATION_ACTOR, joinIds(chunk)));
        }
    }

    // ==================== 产物 2/3/4：校验和探针、期望世界、导出脚本 ====================

    @Test
    @DisplayName("生成 2d_checksum_probe.sql：把库里的实际校验和查出来，供与期望值比对")
    void generateChecksumProbe() throws IOException {
        String sql = """
                -- 只读。执行方式：
                --   mysql -h127.0.0.1 -P3306 -uroot -p --default-character-set=utf8mb4 -N -B \\
                --         "ruoyi-vue-pro" < 2d_checksum_probe.sql > 2d_checksum_actual.tsv
                -- 然后与 2d_checksum_expected.tsv diff —— 必须完全一致才允许执行 2d_migration.sql
                SET SESSION group_concat_max_len = 4294967295;
                SELECT 'live_resources', COUNT(*) FROM sys_resource WHERE deleted = 0;
                SELECT 'live_grants', COUNT(*) FROM sys_grant WHERE deleted = 0;
                SELECT 'max_resource_id', MAX(id) FROM sys_resource;
                SELECT 'non_zero_tenant_resources', COUNT(*) FROM sys_resource WHERE deleted = 0 AND tenant_id <> 0;
                SELECT 'dirty_grants', (%s);
                SELECT 'dangling_parents', (%s);
                SELECT 'resource_checksum', %s FROM sys_resource WHERE deleted = 0;
                SELECT 'grant_checksum', %s FROM sys_grant WHERE deleted = 0;
                """.formatted(DIRTY_GRANT_SQL, DANGLING_PARENT_SQL,
                RESOURCE_CHECKSUM_SQL, GRANT_CHECKSUM_SQL);
        Files.writeString(scratch.resolve("2d_checksum_probe.sql"), sql, StandardCharsets.UTF_8);

        String expected = String.join("\n", List.of(
                "live_resources\t" + EXPECTED_LIVE_RESOURCES,
                "live_grants\t" + EXPECTED_LIVE_GRANTS,
                "max_resource_id\t" + EXPECTED_MAX_RESOURCE_ID,
                "non_zero_tenant_resources\t0",
                "dirty_grants\t" + countDirtyGrants(),
                "dangling_parents\t" + countDanglingParents(),
                "resource_checksum\t" + resourceChecksum(),
                "grant_checksum\t" + grantChecksum())) + "\n";
        Files.writeString(scratch.resolve("2d_checksum_expected.tsv"), expected, StandardCharsets.UTF_8);

        REPORT.add("[校验和] 资源表 " + resourceChecksum());
        REPORT.add("[校验和] 授权表 " + grantChecksum());
        REPORT.add(String.format("[脏数据基线] 悬空授权 %d 条、parent_id 悬空 %d 行（后置守卫比的是「不变」）",
                countDirtyGrants(), countDanglingParents()));
    }

    /** 迁移后应有的全部存活行。落库后与 {@code 2d_verify.sql} 的输出逐行 diff */
    @Test
    @DisplayName("生成 2d_expected_world.tsv 与 2d_verify.sql：落库后逐行对账用")
    void generateExpectedWorld() throws IOException {
        StringBuilder tsv = new StringBuilder(256 * 1024);
        for (ResourceDO r : world.resources()) {
            tsv.append(String.join("\t", "R",
                    String.valueOf(r.getId()), nil(r.getPermission()), nil(r.getName()),
                    nil(r.getType()), String.valueOf(r.getParentId()), nil(r.getPath()),
                    String.valueOf(r.getDepth()), String.valueOf(nz(r.getSort())),
                    nil(r.getDefaultEffect()), String.valueOf(nz(r.getStatus())),
                    nil(r.getMenuId()), nil(r.getDatasetTable()), nil(r.getSharePolicy()))).append('\n');
        }
        for (GrantDO g : world.grants()) {
            tsv.append(String.join("\t", "G",
                    String.valueOf(g.getId()), nil(g.getSubjectType()), nil(g.getSubjectRefId()),
                    nil(g.getSubjectCode()), String.valueOf(g.getResourceId()),
                    nil(g.getContextResourceId()), nil(g.getAction()), nil(g.getEffect()),
                    nil(g.getRuleId()), nil(g.getFieldMode()), String.valueOf(g.getTenantId()))).append('\n');
        }
        Files.writeString(scratch.resolve("2d_expected_world.tsv"), tsv.toString(), StandardCharsets.UTF_8);

        // 🔴 列与顺序必须与上面逐字对应，NULL 一律渲染成字面量 NULL，
        // 否则 diff 出来的差异是格式差异而不是数据差异 —— 那种 diff 会被人当成噪音忽略掉
        //
        // 🔴 两句 SELECT 分开写，不用 UNION ALL 拼成一句：资源行 14 列、授权行 12 列，
        //    UNION ALL 要求列数相等，拼起来直接 ERROR 1222（实测过）。
        //    补齐成同列数当然也行，但那些填充列除了制造噪音没有任何用处，
        //    而 -N -B 下两句的输出本来就是前后相接的，与 tsv 的排布天然一致
        String sql = """
                -- 只读。执行方式：
                --   mysql -h127.0.0.1 -P3306 -uroot -p --default-character-set=utf8mb4 -N -B \\
                --         "ruoyi-vue-pro" < 2d_verify.sql > 2d_actual_world.tsv
                -- 然后与 2d_expected_world.tsv 比对（两边先按 类型+id 排序，去掉 CR）—— 必须为空
                --
                -- ⚠️ expire_time 不在比对范围内：本次迁移不写这一列，而它在 Java 与 MySQL
                --    两侧的文本格式天然不同，放进来只会制造假差异。它由前置校验和覆盖
                SELECT 'R', id, permission, name, type, parent_id, path, depth, sort,
                       default_effect, status, IFNULL(menu_id, 'NULL'),
                       IFNULL(dataset_table, 'NULL'), IFNULL(share_policy, 'NULL')
                  FROM sys_resource WHERE deleted = 0 ORDER BY id;
                SELECT 'G', id, subject_type, IFNULL(subject_ref_id, 'NULL'),
                       IFNULL(subject_code, 'NULL'), resource_id,
                       IFNULL(context_resource_id, 'NULL'), action, effect,
                       IFNULL(rule_id, 'NULL'), IFNULL(field_mode, 'NULL'), tenant_id
                  FROM sys_grant WHERE deleted = 0 ORDER BY id;
                """;
        Files.writeString(scratch.resolve("2d_verify.sql"), sql, StandardCharsets.UTF_8);

        REPORT.add(String.format("[期望世界] 资源 %d 行 + 授权 %d 行 → %s",
                world.resources().size(), world.grants().size(),
                scratch.resolve("2d_expected_world.tsv").toAbsolutePath()));
    }

    // ==================== 校验和的 Java 侧渲染 ====================

    /**
     * 🔴 必须与 {@link #RESOURCE_ROW_EXPR} 逐列、逐分隔符一致
     *
     * <p>{@code CONCAT_WS} 会<b>跳过</b> NULL 参数，所以 SQL 侧对所有可空列都套了 IFNULL；
     * Java 侧对应地渲染成同一个哨兵字符 {@code ~}。两侧只要有一处漏了，
     * 校验和就永远对不上 —— 而那会被误读成「库偏离了快照」，
     * 于是前置守卫永远中止，迁移做不下去。所以这一对表达式改任何一边都要同时改另一边
     *
     * <p>{@code tenant_id} 不在表达式里：{@code ResourceDO extends BaseDO}（不是 TenantBaseDO），
     * Java 侧压根读不到这一列。它由守卫里单独的 {@code non_zero_tenant_resources = 0} 覆盖
     */
    private static String resourceChecksum() {
        List<String> parts = new ArrayList<>(snapshot.resources().size());
        snapshot.resources().stream()
                .sorted(java.util.Comparator.comparingLong(ResourceDO::getId))
                .forEach(r -> parts.add(md5(String.join("\u0001",
                        String.valueOf(r.getId()), nil(r.getPermission()), nil(r.getName()),
                        nil(r.getType()), String.valueOf(r.getParentId()), nil(r.getPath()),
                        String.valueOf(r.getDepth()), String.valueOf(nz(r.getSort())),
                        nil(r.getDefaultEffect()), String.valueOf(nz(r.getStatus())),
                        tilde(r.getMenuId()), tilde(r.getDatasetTable()),
                        tilde(r.getSharePolicy())))));
        return md5(String.join(ROW_SEPARATOR, parts));
    }

    private static String grantChecksum() {
        List<String> parts = new ArrayList<>(snapshot.grants().size());
        snapshot.grants().stream()
                .sorted(java.util.Comparator.comparingLong(GrantDO::getId))
                .forEach(g -> parts.add(md5(String.join("\u0001",
                        String.valueOf(g.getId()), nil(g.getSubjectType()), tilde(g.getSubjectRefId()),
                        tilde(g.getSubjectCode()), String.valueOf(g.getResourceId()),
                        tilde(g.getContextResourceId()), nil(g.getAction()), nil(g.getEffect()),
                        tilde(g.getRuleId()), tilde(g.getFieldMode()),
                        g.getExpireTime() == null ? "~" : g.getExpireTime().format(COMPACT_TS),
                        String.valueOf(g.getTenantId())))));
        return md5(String.join(ROW_SEPARATOR, parts));
    }

    private static int countDirtyGrants() {
        Set<Long> liveResourceIds = new LinkedHashSet<>();
        for (ResourceDO r : snapshot.resources()) {
            liveResourceIds.add(r.getId());
        }
        int n = 0;
        for (GrantDO g : snapshot.grants()) {
            if (!liveResourceIds.contains(g.getResourceId())) {
                n++;
            }
        }
        return n;
    }

    private static int countDanglingParents() {
        Set<Long> liveResourceIds = new LinkedHashSet<>();
        for (ResourceDO r : snapshot.resources()) {
            liveResourceIds.add(r.getId());
        }
        int n = 0;
        for (ResourceDO r : snapshot.resources()) {
            Long pid = r.getParentId();
            if (pid != null && pid != 0L && !liveResourceIds.contains(pid)) {
                n++;
            }
        }
        return n;
    }

    /** 身份串 → SQL 里的资源 id 字面量（已有的写 id，新建的也写 id，因为发号已定死） */
    private static Map<String, String> newIdentityIds() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : world.identityToResourceId().entrySet()) {
            out.put(e.getKey(), String.valueOf(e.getValue()));
        }
        assertEquals(plan.identityCount(), out.size(),
                "身份表条数与计划器的 identityCount() 不一致");
        return out;
    }

    // ==================== 小工具 ====================

    private static String inList(Collection<String> values) {
        List<String> quoted = new ArrayList<>(values.size());
        for (String v : values) {
            quoted.add(sqlStr(v));
        }
        return String.join(",", quoted);
    }

    private static String joinIds(Collection<Long> ids) {
        List<String> out = new ArrayList<>(ids.size());
        for (Long id : ids) {
            out.add(String.valueOf(id));
        }
        return String.join(",", out);
    }

    /** SQL 字符串字面量。反斜杠在 MySQL 里默认是转义符，所以要先翻倍 */
    private static String sqlStr(String v) {
        if (v == null) {
            return "NULL";
        }
        return "'" + v.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    private static String nullableStr(String v) {
        return v == null ? "NULL" : sqlStr(v);
    }

    /**
     * 可空的数值列渲染成<b>不带引号</b>的字面量
     *
     * <p>{@code menu_id} 是 bigint，写成 {@code '9000050906'} MySQL 也会收（隐式转换），
     * 但在严格模式下会记一条 warning，而且读脚本的人会以为那是个字符串列
     */
    private static String nullableNum(Long v) {
        return v == null ? "NULL" : String.valueOf(v);
    }

    /** tsv 里的 null 渲染成字面量 NULL，与 SQL 侧 IFNULL(x,'NULL') 对齐 */
    private static String nil(Object v) {
        return v == null ? "NULL" : String.valueOf(v);
    }

    /** 校验和里的 null 哨兵，与 SQL 侧 CHAR(126) 对齐 */
    private static String tilde(Object v) {
        return v == null ? "~" : String.valueOf(v);
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 MD5", e);
        }
    }

    /** 与 {@link CollapseSnapshot#loadOrNull()} 同一套向上查找：surefire 的工作目录是模块根 */
    private static Path scratchDir() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve("_scratch");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("找不到 _scratch 目录（快照就在那儿，它一定存在）");
    }

    @AfterAll
    static void writeReport() {
        if (REPORT.isEmpty()) {
            return;
        }
        try {
            Path out = Paths.get("target", "2d-generator-report.txt");
            Files.createDirectories(out.getParent());
            Files.write(out, REPORT, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("2d 生成报告写不出去", e);
        }
    }

}
