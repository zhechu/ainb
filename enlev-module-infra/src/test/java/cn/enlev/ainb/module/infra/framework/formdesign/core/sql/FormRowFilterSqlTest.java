package cn.enlev.ainb.module.infra.framework.formdesign.core.sql;

import cn.enlev.ainb.framework.common.biz.system.permission.PermissionCommonApi;
import cn.enlev.ainb.framework.common.biz.system.permission.dto.DataRowFilterRespDTO;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.FieldMapping;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.TableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.permission.FormRowFilter;
import cn.enlev.ainb.module.infra.service.db.DatabaseTableService;
import com.baomidou.mybatisplus.generator.config.po.TableField;
import com.baomidou.mybatisplus.generator.config.po.TableInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 行级权限在生成的 SQL 里到底有没有生效
 *
 * <h3>这批用例挡的是什么</h3>
 *
 * 在线表单的运行时读写用 JdbcTemplate 拼原生 SQL，<b>不经过 MyBatis 执行器</b>，
 * 所以 {@code RuleDataPermissionRule}（MyBatis-Plus 拦截器）对它一行 SQL 都改写不了。
 * 接入之前的表现是：行级规则配了、表也注册了、启动日志也打了「已注册 N 张表」，
 * 但查询照常返回全部数据 —— 没有报错、没有日志、界面完全正常。
 *
 * 这类缺陷单靠接口冒烟测不出来（接口返回 200、数据也有），只能<b>断言 SQL 本身</b>。
 *
 * @author 走进新时代
 */
class FormRowFilterSqlTest {

    private static final Long DS_MAIN = 0L;
    private static final String TABLE = "biz_order";

    private FormDesignSqlBuilder builder;
    private PermissionCommonApi permissionApi;
    private DatabaseTableService databaseTableService;

    @BeforeEach
    void setUp() {
        databaseTableService = mock(DatabaseTableService.class);
        permissionApi = mock(PermissionCommonApi.class);
        builder = SqlBuilderTestFactory.create(databaseTableService);
        // 换上一个能拿到 PermissionCommonApi 的行级权限组件
        ReflectionTestUtils.setField(builder, "rowFilter", rowFilterWith(permissionApi));
        mockTable(TABLE, "id", "title", "creator", "dept_id");
    }

    // ============================= 读路径 =============================

    @Test
    void testBuildPage_行过滤条件被拼进WHERE() {
        whenFilter(DataRowFilterRespDTO.of("creator = ?", List.of(100L)));

        FormDesignSqlBuilder.SqlWithParams sql =
                builder.buildPage(DS_MAIN, binding(), List.of(), null, null, 1, 10);

        assertTrue(sql.getSql().contains("creator = ?"),
                "行过滤条件没进 SQL，等于权限完全没生效：" + sql.getSql());
        assertTrue(sql.getParams().contains(100L), "参数没带上：" + sql.getParams());
    }

    @Test
    void testBuildCount_必须与Page用同一份条件() {
        whenFilter(DataRowFilterRespDTO.of("creator = ?", List.of(100L)));

        FormDesignSqlBuilder.SqlWithParams count = builder.buildCount(DS_MAIN, binding(), List.of());

        // COUNT 不过滤而列表过滤的话，总数会大于实际可见条数，
        // 表现是「分页器显示 100 条，翻到第 5 页却是空的」
        assertTrue(count.getSql().contains("creator = ?"),
                "COUNT 漏了行过滤：" + count.getSql());
    }

    @Test
    void testBuildGet_详情也要过滤() {
        whenFilter(DataRowFilterRespDTO.of("creator = ?", List.of(100L)));

        FormDesignSqlBuilder.SqlWithParams sql = builder.buildGet(DS_MAIN, binding(), 1L);

        // 列表过滤了而详情没过滤的话，换个 id 直接读他人数据 —— 典型的水平越权
        assertTrue(sql.getSql().contains("creator = ?"), "详情漏了行过滤：" + sql.getSql());
    }

    // ============================= 写路径 =============================

    @Test
    void testBuildUpdate_不能改自己看不见的行() {
        whenFilter(DataRowFilterRespDTO.of("creator = ?", List.of(100L)));

        FormDesignSqlBuilder.SqlWithParams sql =
                builder.buildUpdate(DS_MAIN, binding(), Map.of("title", "x"), 1L, 1L, null);

        assertTrue(sql.getSql().contains("creator = ?"), "UPDATE 漏了行过滤：" + sql.getSql());
    }

    @Test
    void testBuildDelete_物理删除分支也要过滤() {
        whenFilter(DataRowFilterRespDTO.of("creator = ?", List.of(100L)));

        FormDesignSqlBuilder.SqlWithParams sql = builder.buildDelete(DS_MAIN, binding(), 1L, 1L);

        // 逻辑删除那条分支加了而这条漏了的话，凡是没配逻辑删除的表单就全是敞开的
        assertTrue(sql.getSql().startsWith("DELETE"), "本用例要覆盖物理删除分支");
        assertTrue(sql.getSql().contains("creator = ?"), "物理删除漏了行过滤：" + sql.getSql());
    }

    // ============================= 边界 =============================

    @Test
    void testPass_没有行级规则时SQL不变() {
        whenFilter(DataRowFilterRespDTO.pass());

        FormDesignSqlBuilder.SqlWithParams sql =
                builder.buildPage(DS_MAIN, binding(), List.of(), null, null, 1, 10);

        assertFalse(sql.getSql().contains("1 = 0"), "没配规则却全拒了：" + sql.getSql());
        assertFalse(sql.getSql().contains("creator ="), "凭空多了条件：" + sql.getSql());
    }

    @Test
    void testDenyAll_拼成恒假条件而不是不过滤() {
        whenFilter(DataRowFilterRespDTO.denyAll());

        FormDesignSqlBuilder.SqlWithParams sql =
                builder.buildPage(DS_MAIN, binding(), List.of(), null, null, 1, 10);

        // 全拒时若退化成「不过滤」，就是把异常变成了越权
        assertTrue(sql.getSql().contains("1 = 0"), "全拒没生效：" + sql.getSql());
    }

    @Test
    void testApi抛异常时全拒而不是放行() {
        when(permissionApi.getDataRowFilter(any(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("权限模块炸了"));

        FormDesignSqlBuilder.SqlWithParams sql =
                builder.buildPage(DS_MAIN, binding(), List.of(), null, null, 1, 10);

        assertTrue(sql.getSql().contains("1 = 0"), "异常时放行了，等于把故障变成越权：" + sql.getSql());
    }

    @Test
    void testExecuteIgnore_忽略上下文里不过滤() {
        whenFilter(DataRowFilterRespDTO.of("creator = ?", List.of(100L)));

        FormDesignSqlBuilder.SqlWithParams sql = FormRowFilter.executeIgnore(
                () -> builder.buildPage(DS_MAIN, binding(), List.of(), null, null, 1, 10));

        assertFalse(sql.getSql().contains("creator = ?"), "忽略开关没生效：" + sql.getSql());
    }

    @Test
    void testExecuteIgnore_嵌套时内层结束不影响外层() {
        whenFilter(DataRowFilterRespDTO.of("creator = ?", List.of(100L)));

        String inner = FormRowFilter.executeIgnore(() -> {
            FormRowFilter.executeIgnore(() -> "内层");
            // 内层结束后，外层仍应处于忽略状态 ——
            // 直接置 false 的实现会让这里突然开始过滤
            return builder.buildPage(DS_MAIN, binding(), List.of(), null, null, 1, 10).getSql();
        });

        assertFalse(inner.contains("creator = ?"), "内层结束把外层的忽略也清掉了：" + inner);
    }

    // ==================== 构造 ====================

    private void whenFilter(DataRowFilterRespDTO filter) {
        when(permissionApi.getDataRowFilter(any(), anyString(), any(), any())).thenReturn(filter);
    }

    private TableBinding binding() {
        TableBinding binding = new TableBinding();
        binding.setMainTable(TABLE);
        binding.setMainPk("id");
        binding.setPkStrategy("AUTO");
        Map<String, FieldMapping> fieldMapping = new LinkedHashMap<>();
        FieldMapping title = new FieldMapping();
        title.setTable(TABLE);
        title.setColumn("title");
        fieldMapping.put("title", title);
        binding.setFieldMapping(fieldMapping);
        return binding;
    }

    private void mockTable(String tableName, String... columns) {
        TableInfo tableInfo = mock(TableInfo.class);
        List<TableField> fields = Arrays.stream(columns).map(col -> {
            TableField field = mock(TableField.class);
            when(field.getName()).thenReturn(col);
            return field;
        }).toList();
        when(tableInfo.getFields()).thenReturn(fields);
        when(databaseTableService.getTable(eq(DS_MAIN), eq(tableName))).thenReturn(tableInfo);
    }

    private FormRowFilter rowFilterWith(PermissionCommonApi api) {
        return new FormRowFilter(new ObjectProvider<>() {
            @Override
            public PermissionCommonApi getObject() {
                return api;
            }

            @Override
            public PermissionCommonApi getObject(Object... args) {
                return api;
            }

            @Override
            public PermissionCommonApi getIfAvailable() {
                return api;
            }

            @Override
            public PermissionCommonApi getIfUnique() {
                return api;
            }
        });
    }

}
