package cn.enlev.ainb.module.agent.tool.impl.datamodel;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.api.datamodel.DataModelApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DataModelPhysicalTableTool} 的单元测试
 *
 * <p>🔴 这个工具是平台上「表存不存在」<b>两个读者之间唯一的对账位</b>，
 * 所以用例守的是<b>它双向都报</b>：
 * <ul>
 *   <li>正向：物理表在、登记没有 → {@code items[].registered=false}</li>
 *   <li>反向：登记在、物理表没有（<b>悬挂登记</b>）→ {@code danglingRegistrations}</li>
 * </ul>
 *
 * <p>反向那一档是补出来的，不是原来就有：{@code items} 的论域是物理表，
 * 不存在的表压根进不了那个循环，所以悬挂登记在结构上不可能出现在里面。
 * 后果实测过（台账 #93）：{@code biz_device} 的登记行在库里挂了两天，
 * 两个读者给出相反答案而<b>都不报错</b>，最后是手写一次性脚本才发现的。
 *
 * <p>还有一条同样重要：<b>滤掉的东西必须如实报数</b>。
 * 悬挂登记里有 80 条属未启用模块的预留前缀（{@code hrm_}/{@code fms_}），本来就没表、
 * 删了反而在模块启用时坏事，所以不列给模型 —— 但条数与前缀必须给出来，
 * 否则「danglingRegistrations 是空的」会被读成「没有悬挂登记」。
 *
 * @author 走进新时代
 */
public class DataModelPhysicalTableToolTest extends BaseMockitoUnitTest {

    @InjectMocks
    private DataModelPhysicalTableTool tool;

    @Mock
    private DataModelApi dataModelApi;

    // ==================== 反向对账：悬挂登记 ====================

    @Test
    @DisplayName("🔴 登记在、物理表不在 → 必须出现在 danglingRegistrations 里，即使它压根不在 items 的论域内")
    @SuppressWarnings("unchecked")
    void testDanglingRegistrationIsReported() {
        // 物理侧只看得到 biz_device_manage；biz_device 的表已经被带外删掉了
        when(dataModelApi.getPhysicalTableList(any()))
                .thenReturn(List.of(physical("biz_device_manage", "设备管理信息表", true)));
        when(dataModelApi.getDanglingRegistrationList())
                .thenReturn(List.of(dangling(324L, "biz_device", "设备模型")));

        Map<String, Object> resp = (Map<String, Object>) tool.execute(Map.of());

        List<Map<String, Object>> reported =
                (List<Map<String, Object>>) resp.get("danglingRegistrations");
        assertEquals(1, reported.size(), "悬挂登记必须被报出来 —— 这正是本工具作为对账位存在的全部意义");
        assertEquals(324L, reported.get(0).get("id"),
                "id 要给出去：清理悬挂登记走 DELETE /infra/data-model/delete?id=&dropTable=false");
        assertEquals("biz_device", reported.get(0).get("tableName"));
        assertEquals("设备模型", reported.get(0).get("modelName"));
        // items 里不该有它 —— 它的物理表不存在，正向那个循环遍历不到
        List<Map<String, Object>> items = (List<Map<String, Object>>) resp.get("items");
        assertEquals(List.of("biz_device_manage"),
                items.stream().map(i -> i.get("tableName")).toList());
        assertTrue(resp.get("danglingMessage").toString().contains("登记着但物理表不在"),
                "光给数据不够，得说清这是什么、两个读者为什么打架：" + resp.get("danglingMessage"));
    }

    @Test
    @DisplayName("🔴 没有悬挂时 danglingRegistrations 这个键也必须在 —— 键消失就分不清「查了没有」和「没查」")
    @SuppressWarnings("unchecked")
    void testDanglingKeyAlwaysPresent() {
        when(dataModelApi.getPhysicalTableList(any()))
                .thenReturn(List.of(physical("biz_order", "订单表", true)));
        when(dataModelApi.getDanglingRegistrationList()).thenReturn(List.of());

        Map<String, Object> resp = (Map<String, Object>) tool.execute(Map.of());

        assertTrue(resp.containsKey("danglingRegistrations"),
                "键必须恒在。对账位的输出里「没有」和「没查」必须是两种可区分的形态");
        assertTrue(((List<Map<String, Object>>) resp.get("danglingRegistrations")).isEmpty());
        assertFalse(resp.containsKey("danglingMessage"), "没有悬挂时不必附带解释文案");
        assertFalse(resp.containsKey("danglingReservedModules"), "没滤掉任何东西时不必报数");
    }

    @Test
    @DisplayName("🔴 未启用模块的预留前缀那批要滤掉，但必须**按前缀**如实报数")
    @SuppressWarnings("unchecked")
    void testDanglingReservedModuleFilteredButCounted() {
        when(dataModelApi.getPhysicalTableList(any())).thenReturn(List.of());
        when(dataModelApi.getDanglingRegistrationList()).thenReturn(List.of(
                dangling(1L, "hrm_employee", "员工"),
                dangling(2L, "hrm_department", "部门"),
                dangling(3L, "fms_voucher", "凭证"),
                dangling(324L, "biz_device", "设备模型")));

        Map<String, Object> resp = (Map<String, Object>) tool.execute(Map.of());

        List<Map<String, Object>> reported =
                (List<Map<String, Object>>) resp.get("danglingRegistrations");
        assertEquals(List.of("biz_device"),
                reported.stream().map(i -> i.get("tableName")).toList(),
                "只有低代码自家表族的悬挂才是可动手的；hrm_/fms_ 属根 pom 注释掉的模块，本来就没表");
        Map<String, Integer> byPrefix = (Map<String, Integer>) resp.get("danglingReservedModules");
        assertEquals(Map.of("hrm_", 2, "fms_", 1), byPrefix,
                "🔴 必须按前缀给而不是只给个总数 3：万一哪天 crm_ 底下冒出一条"
                        + "（正常路径进不来，得靠带外 SQL），前缀名会让它露出来，纯数字不会");
    }

    // ==================== 正向：物理表清单 ====================

    @Test
    @DisplayName("框架表滤掉但如实报数 —— 悄悄少给结果会让人以为库里就这么点表")
    @SuppressWarnings("unchecked")
    void testFrameworkTablesFilteredButCounted() {
        when(dataModelApi.getPhysicalTableList(any())).thenReturn(List.of(
                physical("biz_order", "订单表", true),
                physical("sys_grant", "系统授权", false),
                physical("infra_data_model", "数据模型登记", false)));
        when(dataModelApi.getDanglingRegistrationList()).thenReturn(List.of());

        Map<String, Object> resp = (Map<String, Object>) tool.execute(Map.of());

        assertEquals(1, resp.get("total"));
        assertEquals(2, resp.get("frameworkTablesFiltered"),
                "sys_ 与 infra_ 都属 SystemTablePolicy 的系统核心前缀");
    }

    @Test
    @DisplayName("超过 60 条要截断，且必须在 message 里说清是截断过的")
    @SuppressWarnings("unchecked")
    void testItemsTruncatedToMaxWithDisclosure() {
        List<DataModelApi.PhysicalTableRespDTO> many = new ArrayList<>();
        for (int i = 0; i < 61; i++) {
            many.add(physical("biz_t%02d".formatted(i), "表" + i, false));
        }
        when(dataModelApi.getPhysicalTableList(any())).thenReturn(many);
        when(dataModelApi.getDanglingRegistrationList()).thenReturn(List.of());

        Map<String, Object> resp = (Map<String, Object>) tool.execute(Map.of());

        assertEquals(60, resp.get("total"));
        assertTrue(resp.get("message").toString().contains("截断"),
                "截断而不说，模型会以为库里就 60 张表：" + resp.get("message"));
    }

    @Test
    @DisplayName("keyword 原样透传给 api，由 api 决定它是表名还是注释的模糊匹配")
    void testKeywordPassedThrough() {
        when(dataModelApi.getPhysicalTableList(any())).thenReturn(List.of());
        when(dataModelApi.getDanglingRegistrationList()).thenReturn(List.of());

        tool.execute(Map.of("keyword", "  设备  "));

        // 只去首尾空白，不做别的手脚：口径归 api 一处管，工具再掺一层就是两套口径
        verify(dataModelApi).getPhysicalTableList("设备");
    }

    // ==================== 夹具 ====================

    private static DataModelApi.PhysicalTableRespDTO physical(String tableName, String comment,
                                                              boolean registered) {
        DataModelApi.PhysicalTableRespDTO dto = new DataModelApi.PhysicalTableRespDTO();
        dto.setTableName(tableName);
        dto.setTableComment(comment);
        dto.setRegistered(registered);
        return dto;
    }

    private static DataModelApi.DanglingRegistrationRespDTO dangling(Long id, String tableName,
                                                                     String modelName) {
        DataModelApi.DanglingRegistrationRespDTO dto = new DataModelApi.DanglingRegistrationRespDTO();
        dto.setId(id);
        dto.setTableName(tableName);
        dto.setModelName(modelName);
        return dto;
    }

}
