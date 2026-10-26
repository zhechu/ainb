package cn.enlev.ainb.module.infra.framework.formdesign.core.sql;

import cn.enlev.ainb.framework.common.biz.system.permission.PermissionCommonApi;
import cn.enlev.ainb.module.infra.framework.formdesign.core.meta.TableMetadataCache;
import cn.enlev.ainb.module.infra.framework.formdesign.core.permission.FormRowFilter;
import org.springframework.beans.factory.ObjectProvider;
import cn.enlev.ainb.module.infra.service.db.DatabaseTableService;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * SQL 构建器的测试装配
 *
 * {@link FormDesignSqlBuilder} 拆分后由几个协作者组成（共享支撑 + 副表 + 子表构建器），
 * 单测里要用反射逐个注入。四个测试类的装配代码是一样的，抽到这里来 ——
 * 否则以后再加一个协作者，就要在四个 setUp 里各改一遍，漏一个的表现是
 * 「某个测试类突然全是 NPE」。
 *
 * 用反射而不是构造函数注入：生产代码用的是 {@code @Resource} 字段注入，
 * 为了测试改成构造注入会让生产代码为测试让步，反过来了。
 *
 * @author 走进新时代
 */
final class SqlBuilderTestFactory {

    private SqlBuilderTestFactory() {
    }

    /**
     * 装配一个完整可用的 SQL 构建器
     *
     * @param databaseTableService mock 的元数据服务，决定白名单校验能看到哪些表与列
     * @return 已注入全部协作者的构建器
     */
    static FormDesignSqlBuilder create(DatabaseTableService databaseTableService) {
        // ⚠️ guard 现在读的是 TableMetadataCache 而不是 DatabaseTableService。
        // 这里给它一个【真实的】缓存去包住 mock，而不是再 mock 一层缓存：
        // 前者让用例仍然按「表里有哪些列」来表达（stub 不用改），
        // 后者会让每个用例都要多 stub 一个 columns()，且缓存本身就不被这批用例覆盖了
        SqlIdentifierGuard guard = new SqlIdentifierGuard();
        ReflectionTestUtils.setField(guard, "metadataCache", metadataCache(databaseTableService));

        SqlBuilderSupport support = new SqlBuilderSupport();
        ReflectionTestUtils.setField(support, "guard", guard);

        SideTableSqlBuilder sideTableSqlBuilder = new SideTableSqlBuilder();
        ReflectionTestUtils.setField(sideTableSqlBuilder, "guard", guard);
        ReflectionTestUtils.setField(sideTableSqlBuilder, "support", support);

        SubTableSqlBuilder subTableSqlBuilder = new SubTableSqlBuilder();
        ReflectionTestUtils.setField(subTableSqlBuilder, "guard", guard);
        ReflectionTestUtils.setField(subTableSqlBuilder, "support", support);

        FormDesignSqlBuilder builder = new FormDesignSqlBuilder();
        ReflectionTestUtils.setField(builder, "rowFilter", passThroughRowFilter());
        ReflectionTestUtils.setField(builder, "guard", guard);
        ReflectionTestUtils.setField(builder, "support", support);
        ReflectionTestUtils.setField(builder, "sideTableSqlBuilder", sideTableSqlBuilder);
        ReflectionTestUtils.setField(builder, "subTableSqlBuilder", subTableSqlBuilder);
        return builder;
    }

    /**
     * 造一个「不过滤」的行级权限组件
     *
     * 用真实的 {@link FormRowFilter} 而不是 mock：它拿不到 {@code PermissionCommonApi}
     * 时本来就会降级为不过滤（真实部署里 system 模块提供该 Bean，单测里没有）。
     * 用真实对象意味着这批 SQL 断言覆盖的是「没有配行级规则时 SQL 长什么样」——
     * 那正是它们本来要断言的东西。
     *
     * 行级权限<b>生效</b>时的 SQL 形态由 {@code FormRowFilterSqlTest} 单独覆盖。
     */
    static FormRowFilter passThroughRowFilter() {
        return new FormRowFilter(new ObjectProvider<>() {
            @Override
            public PermissionCommonApi getObject() {
                throw new UnsupportedOperationException();
            }

            @Override
            public PermissionCommonApi getObject(Object... args) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PermissionCommonApi getIfAvailable() {
                return null; // 不可用 → FormRowFilter 降级为不过滤
            }

            @Override
            public PermissionCommonApi getIfUnique() {
                return null;
            }
        });
    }

    /**
     * 造一个包住 mock 的真实元数据缓存
     *
     * ⚠️ 每次都造新的，不共用静态实例：缓存是有状态的，跨用例共用会让
     * 「A 用例 stub 的表结构在 B 用例里还生效」，而 B 的失败原因完全指不到这里。
     */
    static TableMetadataCache metadataCache(DatabaseTableService databaseTableService) {
        TableMetadataCache cache = new TableMetadataCache();
        ReflectionTestUtils.setField(cache, "databaseTableService", databaseTableService);
        return cache;
    }

}
