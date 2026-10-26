package cn.enlev.ainb.module.system.controller.admin.permission;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.slotmapping.SlotMappingRespVO;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.slotmapping.SlotMappingSaveReqVO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.SlotMappingDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.SlotMappingMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.SLOT_MAPPING_COLUMN_NOT_EXISTS;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.SLOT_MAPPING_DUPLICATE;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.SLOT_MAPPING_TABLE_NOT_IN_DB;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link SlotMappingAdminController} 的单元测试类
 *
 * 覆盖三件事，都是「配了但不生效」这条静默失效路径上的关口：
 *
 *   1. <b>写入口核实列存在</b>（{@code validateColumnExists}）——
 *      存一个不存在的列名，后果不是报错而是规则恒假，所以必须在保存时就拒；
 *   2. <b>读出口标出存量脏数据</b>（{@code markColumnExists}）——
 *      写入口只拦得住今后，库里早期自动推断写进去的行没人管，
 *      不标出来的话管理员看到的是一条「看着好好的」失效配置；
 *   3. <b>拒绝时抛的必须是 {@link ServiceException}</b>——见下面各条断言处的说明。
 *      第 1 条拦下来了、消息却到不了用户眼前，等于白拦：全局异常处理器只翻译
 *      {@code ServiceException}，别的异常一律兜底成「系统异常」并记一条堆栈日志
 *
 * ⚠️ 用纯 Mockito 而不是 BaseDbUnitTest：这两个方法查的都是 {@code information_schema}
 * 与 MySQL 的 {@code DATABASE()} 函数，H2 上跑不起来（H2 没有 DATABASE()）
 *
 * @author 走进新时代
 */
public class SlotMappingAdminControllerTest extends BaseMockitoUnitTest {

    @InjectMocks
    private SlotMappingAdminController controller;

    @Mock
    private SlotMappingMapper slotMappingMapper;
    @Mock
    private ResourceMapper resourceMapper;
    @Mock
    private JdbcTemplate jdbcTemplate;

    // ============================= 写入口：核实列存在 =============================

    /**
     * 表在、列也在 ⟹ 放行，且表名与列名一律转小写落库
     *
     * 🔴 小写这件事不是洁癖：求值时是拿 tableName 当 key 去行过滤传进来的限定符表里查的，
     * 而那张 map 的 key 是 {@code tableName.toLowerCase()}。大小写不统一的话，
     * 一条配置正确的映射会因为 key 对不上而求不出来 —— 又是一次静默失效
     */
    @Test
    public void testCreate_列存在_放行并转小写() {
        stubTableExists(1);
        stubColumnExists(1);

        CommonResult<Long> result = controller.createSlotMapping(
                saveReqVO(1L, "owner", "Test_Leave", "Creator"));

        assertEquals(0, result.getCode());
        verify(slotMappingMapper).insert(argThat((SlotMappingDO inserted) ->
                "test_leave".equals(inserted.getTableName())
                        && "creator".equals(inserted.getColumnName())));
    }

    /**
     * 表在、列不在 ⟹ 拒，且错误消息里要同时带上表名与列名
     *
     * 这条正是 {@code test_leave} 的 status 槽位在库里那条真实脏数据的形状
     */
    @Test
    public void testCreate_列不存在_被拒() {
        stubTableExists(1);
        stubColumnExists(0);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.createSlotMapping(saveReqVO(1L, "status", "test_leave", "status")));

        assertEquals(SLOT_MAPPING_COLUMN_NOT_EXISTS.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("test_leave"), "错误消息要带上表名：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("status"), "错误消息要带上列名：" + ex.getMessage());
        verify(slotMappingMapper, never()).insert(any(SlotMappingDO.class));
    }

    /**
     * 表压根不在库里 ⟹ 拒，且给的是「表不在」那句而不是「列不在」
     *
     * 刻意分两条消息：前者多半是选错了数据集（或表单还没发布、表还没建出来），
     * 后者是列名记错了。合成一句「表或列不存在」等于让人自己去猜
     */
    @Test
    public void testCreate_表不在库里_被拒且不再查列() {
        stubTableExists(0);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.createSlotMapping(
                        saveReqVO(1L, "owner", "biz_book_borrow", "creator")));

        assertEquals(SLOT_MAPPING_TABLE_NOT_IN_DB.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("不在当前库里"), "应给「表不在」那句：" + ex.getMessage());
        // 表都不在，就没必要再去查列了
        verify(jdbcTemplate, never()).queryForObject(contains("information_schema.columns"),
                eq(Integer.class), any(Object[].class));
        verify(slotMappingMapper, never()).insert(any(SlotMappingDO.class));
    }

    /** 修改走的是同一道核实，不能只在 create 上拦 */
    @Test
    public void testUpdate_列不存在_同样被拒() {
        stubTableExists(1);
        stubColumnExists(0);

        SlotMappingSaveReqVO reqVO = saveReqVO(1L, "status", "test_leave", "status");
        reqVO.setId(6L);

        ServiceException ex = assertThrows(ServiceException.class, () -> controller.updateSlotMapping(reqVO));
        assertEquals(SLOT_MAPPING_COLUMN_NOT_EXISTS.getCode(), ex.getCode());
        verify(slotMappingMapper, never()).update(any(SlotMappingDO.class), any());
    }

    /**
     * 同一个资源下重复配同一个槽位，要在撞唯一索引之前先拦成一句人话
     *
     * 🔴 断言的是 {@code ServiceException} 而不是 {@code IllegalArgumentException}：
     * 全局异常处理器没有后者的 handler，它会落到兜底分支变成「系统异常」+ 一条
     * {@code infra_api_error_log} 堆栈记录 —— 一次配置失误在运维眼里就成了线上故障。
     * 这条断言钉住的正是「消息必须能到用户眼前」这个契约
     */
    @Test
    public void testCreate_槽位重复_被拒且不查库结构() {
        when(slotMappingMapper.selectByResourceIdAndSlot(1L, "owner")).thenReturn(new SlotMappingDO());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.createSlotMapping(saveReqVO(1L, "owner", "test_leave", "creator")));

        assertEquals(SLOT_MAPPING_DUPLICATE.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("已存在"), ex.getMessage());
        verifyNoInteractions(jdbcTemplate);
    }

    // ============================= 读出口：标出存量脏数据 =============================

    @Test
    public void testListByResource_列存在标true_不存在标false() {
        when(slotMappingMapper.selectListByResourceId(2253L)).thenReturn(List.of(
                mappingDO(5L, 2253L, "owner", "test_leave", "creator"),
                mappingDO(6L, 2253L, "status", "test_leave", "status")));
        // information_schema 只回真实的列：test_leave 有 creator，没有 status
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                Map.of("tableName", "test_leave", "columnName", "id"),
                Map.of("tableName", "test_leave", "columnName", "creator"),
                Map.of("tableName", "test_leave", "columnName", "process_status")));

        List<SlotMappingRespVO> rows = controller.listByResource(2253L).getData();

        assertEquals(2, rows.size());
        assertEquals(Boolean.TRUE, rows.get(0).getColumnExists(), "creator 是真实列");
        assertEquals(Boolean.FALSE, rows.get(1).getColumnExists(),
                "status 不在 test_leave 上，必须标出来，否则它看着和一条好配置一样");
    }

    /**
     * 表还没建出来（表单未发布）⟹ 该表一列都查不到 ⟹ 标 false
     *
     * 实测本库有 6 个数据集资源是这种形状（biz_book_borrow 等），共 14 行
     */
    @Test
    public void testListByResource_表不在库里_标false() {
        when(slotMappingMapper.selectListByResourceId(1L)).thenReturn(List.of(
                mappingDO(1L, 1L, "owner", "biz_book_borrow", "creator")));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        List<SlotMappingRespVO> rows = controller.listByResource(1L).getData();

        assertEquals(Boolean.FALSE, rows.get(0).getColumnExists());
    }

    /**
     * 表名为空的行<b>不标</b>（留 null）
     *
     * 空是「主表」的存量语义（见 {@code SlotMappingDO#tableName}），
     * 而主表叫什么这个端点无从得知 —— 判成 false 等于对着一条其实没坏的配置喊坏
     */
    @Test
    public void testListByResource_表名为空_不标记也不查库() {
        when(slotMappingMapper.selectListByResourceId(1L)).thenReturn(List.of(
                mappingDO(1L, 1L, "owner", null, "creator")));

        List<SlotMappingRespVO> rows = controller.listByResource(1L).getData();

        assertNull(rows.get(0).getColumnExists(), "null 表示「没核实过」，与 false 是两件事");
        verifyNoInteractions(jdbcTemplate);
    }

    /** 大小写不同的存量行也要能对上：比对两边一律按小写 */
    @Test
    public void testListByResource_大小写不一致仍按小写比对() {
        when(slotMappingMapper.selectListByResourceId(1L)).thenReturn(List.of(
                mappingDO(1L, 1L, "owner", "Test_Leave", "CREATOR")));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                Map.of("tableName", "test_leave", "columnName", "creator")));

        List<SlotMappingRespVO> rows = controller.listByResource(1L).getData();

        assertEquals(Boolean.TRUE, rows.get(0).getColumnExists());
    }

    // ============================= 工具方法 =============================

    // ⚠️ 变长参数位上要用 any(Object[].class) 匹配【整个】参数数组：
    //    裸 any() 只匹配一个元素，而查列那次传的是 (tableName, columnName) 两个
    private void stubTableExists(int count) {
        when(jdbcTemplate.queryForObject(contains("information_schema.tables"),
                eq(Integer.class), any(Object[].class))).thenReturn(count);
    }

    private void stubColumnExists(int count) {
        when(jdbcTemplate.queryForObject(contains("information_schema.columns"),
                eq(Integer.class), any(Object[].class))).thenReturn(count);
    }

    private SlotMappingSaveReqVO saveReqVO(Long resourceId, String slot,
                                           String tableName, String columnName) {
        SlotMappingSaveReqVO reqVO = new SlotMappingSaveReqVO();
        reqVO.setResourceId(resourceId);
        reqVO.setSlot(slot);
        reqVO.setTableName(tableName);
        reqVO.setColumnName(columnName);
        return reqVO;
    }

    private SlotMappingDO mappingDO(Long id, Long resourceId, String slot,
                                    String tableName, String columnName) {
        SlotMappingDO mapping = new SlotMappingDO();
        mapping.setId(id);
        mapping.setResourceId(resourceId);
        mapping.setSlot(slot);
        mapping.setTableName(tableName);
        mapping.setColumnName(columnName);
        return mapping;
    }

}
