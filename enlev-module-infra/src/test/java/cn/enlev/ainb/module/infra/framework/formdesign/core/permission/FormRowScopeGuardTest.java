package cn.enlev.ainb.module.infra.framework.formdesign.core.permission;

import cn.enlev.ainb.framework.common.biz.system.permission.dto.DataRowFilterRespDTO;
import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.TableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.sql.SqlIdentifierGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.FORM_DESIGN_ROW_OUT_OF_SCOPE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link FormRowScopeGuard} 的单元测试
 *
 * <h3>这批用例挡的是什么</h3>
 *
 * 写路径的 WHERE 校验的是改<b>之前</b>的值，挡不住「把 dept_id 改成别的部门」
 * 这种把记录推出自己可见域的写法。{@code FormRowScopeGuard} 写完之后再查一次，
 * 用的是与读路径完全相同的行过滤条件 —— 不一致就拒绝。
 *
 * <p>这里的核心不变量：</p>
 * <ol>
 *   <li>行仍在域内时不抛异常</li>
 *   <li>行逃出域外时抛 {@code FORM_DESIGN_ROW_OUT_OF_SCOPE}</li>
 *   <li>全拒（{@code denyAll}）直接抛，不发 SELECT</li>
 *   <li>无行级规则时不发 SELECT</li>
 *   <li>SELECT 的参数顺序必须是「先主键、后行过滤条件参数」</li>
 * </ol>
 *
 * @author 走进新时代
 */
class FormRowScopeGuardTest {

    private static final String TABLE = "biz_order";
    private static final String PK_COL = "id";
    private static final long PK_VALUE = 42L;

    private FormRowScopeGuard scopeGuard;
    private FormRowFilter rowFilter;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        rowFilter = mock(FormRowFilter.class);
        SqlIdentifierGuard identifierGuard = mock(SqlIdentifierGuard.class);
        jdbcTemplate = mock(JdbcTemplate.class);

        scopeGuard = new FormRowScopeGuard();
        ReflectionTestUtils.setField(scopeGuard, "rowFilter", rowFilter);
        ReflectionTestUtils.setField(scopeGuard, "guard", identifierGuard);
        ReflectionTestUtils.setField(scopeGuard, "jdbcTemplate", jdbcTemplate);

        // SqlIdentifierGuard.quote 的行为只加反引号，与生产代码一致
        when(identifierGuard.quote(anyString())).thenAnswer(inv -> "`" + inv.getArgument(0) + "`");
    }

    @Test
    @DisplayName("行仍在可见域内：放行，不抛异常")
    void testInScope_passes() {
        when(rowFilter.resolve(eq(TABLE), eq("")))
                .thenReturn(DataRowFilterRespDTO.of("creator = ?", List.of(100L)));
        // SELECT 返回非空 = 行在域内
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("1", 1)));

        assertDoesNotThrow(() -> scopeGuard.validateInScope(binding(), PK_VALUE));
    }

    @Test
    @DisplayName("行写入后落到域外：必须抛 FORM_DESIGN_ROW_OUT_OF_SCOPE")
    void testOutOfScope_throws() {
        when(rowFilter.resolve(eq(TABLE), eq("")))
                .thenReturn(DataRowFilterRespDTO.of("creator = ?", List.of(100L)));
        // SELECT 返回空 = 行不在域内
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> scopeGuard.validateInScope(binding(), PK_VALUE));
        assertEquals(FORM_DESIGN_ROW_OUT_OF_SCOPE.getCode(), ex.getCode(),
                "错误码必须是「落到可见域之外」，不能被误判成其他错误");
    }

    @Test
    @DisplayName("全拒（denyAll）：直接抛，不发 SELECT")
    void testDenyAll_throwsWithoutQuery() {
        when(rowFilter.resolve(anyString(), anyString())).thenReturn(DataRowFilterRespDTO.denyAll());

        assertThrows(ServiceException.class, () -> scopeGuard.validateInScope(binding(), PK_VALUE));

        // 全拒时若还去查库，是多此一举，且会因入参为空而抛另一个错
        verify(jdbcTemplate, never()).queryForList(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("无行级规则（pass）：不查库，直接返回")
    void testPass_noQuery() {
        when(rowFilter.resolve(anyString(), anyString())).thenReturn(DataRowFilterRespDTO.pass());

        assertDoesNotThrow(() -> scopeGuard.validateInScope(binding(), PK_VALUE));

        // 没有规则时发 SELECT 是浪费，更重要的是：「查不到 = 拒」会把所有 INSERT 都拦住
        verify(jdbcTemplate, never()).queryForList(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("binding 为 null：直接返回，不查库也不抛")
    void testNullBinding_noQuery() {
        assertDoesNotThrow(() -> scopeGuard.validateInScope(null, PK_VALUE));
        verify(jdbcTemplate, never()).queryForList(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("pkValue 为 null：直接返回，不查库也不抛")
    void testNullPk_noQuery() {
        assertDoesNotThrow(() -> scopeGuard.validateInScope(binding(), null));
        verify(jdbcTemplate, never()).queryForList(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("SELECT 参数顺序：先主键值，后行过滤条件参数")
    void testParamOrder_pkBeforeFilterParams() {
        when(rowFilter.resolve(anyString(), anyString()))
                .thenReturn(DataRowFilterRespDTO.of("creator = ?", List.of(100L)));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("1", 1)));

        scopeGuard.validateInScope(binding(), PK_VALUE);

        ArgumentCaptor<Object[]> paramsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForList(anyString(), paramsCaptor.capture());
        Object[] params = paramsCaptor.getValue();

        // WHERE `id` = ? AND (creator = ?) —— 问号顺序决定了 params 位置
        // 顺序搞反的后果：主键与用户 ID 互换，权限判定结果完全错误
        assertEquals(PK_VALUE, params[0], "第一个参数必须是主键值");
        assertEquals(100L, params[1], "第二个参数必须是行过滤条件的参数");
        assertEquals(2, params.length, "参数总数：主键 1 个 + 过滤条件 1 个");
    }

    @Test
    @DisplayName("多个行过滤参数时全部附在主键之后")
    void testMultipleFilterParams_allAppended() {
        // 比如「部门 IN (deptA, deptB, deptC)」会产生 3 个参数
        when(rowFilter.resolve(anyString(), anyString()))
                .thenReturn(DataRowFilterRespDTO.of("dept_id IN (?, ?, ?)", List.of(10L, 11L, 12L)));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("1", 1)));

        scopeGuard.validateInScope(binding(), PK_VALUE);

        ArgumentCaptor<Object[]> paramsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForList(anyString(), paramsCaptor.capture());
        Object[] params = paramsCaptor.getValue();

        assertEquals(4, params.length, "主键 1 + 过滤参数 3 = 4 个");
        assertEquals(PK_VALUE, params[0]);
        assertEquals(10L, params[1]);
        assertEquals(11L, params[2]);
        assertEquals(12L, params[3]);
    }

    // ==================== 构造 ====================

    private TableBinding binding() {
        TableBinding b = new TableBinding();
        b.setMainTable(TABLE);
        b.setMainPk(PK_COL);
        b.setPkStrategy("AUTO");
        return b;
    }

}
