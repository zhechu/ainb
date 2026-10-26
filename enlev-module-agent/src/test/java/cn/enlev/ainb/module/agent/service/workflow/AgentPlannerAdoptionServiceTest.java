package cn.enlev.ainb.module.agent.service.workflow;

import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentPlannerAdoptionStatRespVO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentPlannerAdoptionDO;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentPlannerAdoptionMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentPlannerAdoptionService} 单测（迭代七 B5-3，纯 mock，0 token）
 *
 * <p>钉住采纳率埋点的命门：
 * ① {@code recordGenerated} 落一行、冻基线、adopted=false；空 code / 写库抛错都不冒泡（best-effort）；
 * ② {@code recordAdopted} 无待确认行 → 静默 no-op（管理页手写流程的发布不污染采纳率）；
 * ③ 「语义相等但格式不同」→ handModified=false（不误判成手改，采纳率不失真）；
 * ④ 真改了 → handModified=true 且 editCount>0；
 * ⑤ {@code getAdoptionStat} 计数与比率算对；分母为 0 → 比率 null（语义「暂无」而非「0%」）。
 *
 * @author 走进新时代
 */
class AgentPlannerAdoptionServiceTest {

    private AgentPlannerAdoptionServiceImpl adoptionService;
    private AgentPlannerAdoptionMapper adoptionMapper;

    @BeforeEach
    void setUp() {
        adoptionMapper = mock(AgentPlannerAdoptionMapper.class);
        adoptionService = new AgentPlannerAdoptionServiceImpl();
        ReflectionTestUtils.setField(adoptionService, "adoptionMapper", adoptionMapper);
    }

    /** 待确认基线行（recordAdopted 用它比对） */
    private static AgentPlannerAdoptionDO pendingRow(Long id, String code, String baselineDsl) {
        AgentPlannerAdoptionDO row = new AgentPlannerAdoptionDO();
        row.setId(id);
        row.setWorkflowCode(code);
        row.setBaselineDsl(baselineDsl);
        row.setAdopted(false);
        return row;
    }

    // ==================== recordGenerated ====================

    @Test
    @DisplayName("生成：INSERT 一行，冻基线 DSL，adopted=false，handModified/editCount 留 null")
    void recordGenerated_insertsBaseline() {
        String dsl = "{\"nodes\":[{\"id\":\"start\"}],\"edges\":[]}";

        adoptionService.recordGenerated("wf_plan_1", 42L, dsl);

        ArgumentCaptor<AgentPlannerAdoptionDO> captor = ArgumentCaptor.forClass(AgentPlannerAdoptionDO.class);
        verify(adoptionMapper).insert(captor.capture());
        AgentPlannerAdoptionDO row = captor.getValue();
        assertEquals("wf_plan_1", row.getWorkflowCode());
        assertEquals(42L, row.getWorkflowDefId());
        assertEquals(dsl, row.getBaselineDsl());
        assertFalse(row.getAdopted(), "生成时 adopted 必为 false");
        assertNull(row.getHandModified(), "尚未确认，handModified 三态里应是 null");
        assertNull(row.getEditCount(), "尚未确认，editCount 应是 null");
        assertNotNull(row.getGenerateTime(), "生成时刻应落");
        assertNull(row.getAdoptTime(), "尚未确认，adoptTime 应是 null");
    }

    @Test
    @DisplayName("生成：空 code → no-op，不写库")
    void recordGenerated_blankCode_noop() {
        adoptionService.recordGenerated("  ", 1L, "{}");
        verify(adoptionMapper, never()).insert(any(AgentPlannerAdoptionDO.class));
    }

    @Test
    @DisplayName("生成：写库抛错 → 被吞掉不冒泡（埋点绝不拖垮生成主流程）")
    void recordGenerated_insertThrows_swallowed() {
        when(adoptionMapper.insert(any(AgentPlannerAdoptionDO.class))).thenThrow(new RuntimeException("表没建"));
        assertDoesNotThrow(() -> adoptionService.recordGenerated("wf_plan_1", 1L, "{}"));
    }

    // ==================== recordAdopted ====================

    @Test
    @DisplayName("确认：无待确认行 → 静默 no-op，不 UPDATE（管理页手写流程发布不污染采纳率）")
    void recordAdopted_noPendingRow_noop() {
        when(adoptionMapper.selectPendingByCode("wf_manual")).thenReturn(null);

        adoptionService.recordAdopted("wf_manual", "{\"nodes\":[]}");

        verify(adoptionMapper, never()).updateById(any(AgentPlannerAdoptionDO.class));
    }

    @Test
    @DisplayName("确认：语义相等但键顺序/缩进不同 → handModified=false，editCount=0（不误判手改）")
    void recordAdopted_semanticallyEqual_notModified() {
        String baseline = "{\"nodes\":[{\"id\":\"a\",\"type\":\"start\"}],\"edges\":[]}";
        // 同一份语义：顶层键换序、节点内键换序、加了缩进与换行
        String reformatted = "{\n  \"edges\": [],\n  \"nodes\": [ {\n    \"type\": \"start\",\n    \"id\": \"a\"\n  } ]\n}";
        when(adoptionMapper.selectPendingByCode("wf_plan_1")).thenReturn(pendingRow(7L, "wf_plan_1", baseline));

        adoptionService.recordAdopted("wf_plan_1", reformatted);

        ArgumentCaptor<AgentPlannerAdoptionDO> captor = ArgumentCaptor.forClass(AgentPlannerAdoptionDO.class);
        verify(adoptionMapper).updateById(captor.capture());
        AgentPlannerAdoptionDO update = captor.getValue();
        assertEquals(7L, update.getId());
        assertTrue(update.getAdopted(), "确认后 adopted 翻 true");
        assertFalse(update.getHandModified(), "语义相等 → 不算手改");
        assertEquals(0, update.getEditCount(), "没改 → 处数 0");
        assertNotNull(update.getAdoptTime());
    }

    @Test
    @DisplayName("确认：真改了（多一个节点）→ handModified=true，editCount>0")
    void recordAdopted_modified_countsEdits() {
        String baseline = "{\"nodes\":[{\"id\":\"a\",\"type\":\"start\"}],\"edges\":[]}";
        String modified = "{\"nodes\":[{\"id\":\"a\",\"type\":\"start\"},{\"id\":\"b\",\"type\":\"end\"}],\"edges\":[]}";
        when(adoptionMapper.selectPendingByCode("wf_plan_1")).thenReturn(pendingRow(9L, "wf_plan_1", baseline));

        adoptionService.recordAdopted("wf_plan_1", modified);

        ArgumentCaptor<AgentPlannerAdoptionDO> captor = ArgumentCaptor.forClass(AgentPlannerAdoptionDO.class);
        verify(adoptionMapper).updateById(captor.capture());
        AgentPlannerAdoptionDO update = captor.getValue();
        assertTrue(update.getAdopted());
        assertTrue(update.getHandModified(), "加了节点 → 算手改");
        assertTrue(update.getEditCount() > 0, "手改了 → 处数 > 0，实得 " + update.getEditCount());
    }

    @Test
    @DisplayName("确认：空 code → no-op，不查不写")
    void recordAdopted_blankCode_noop() {
        adoptionService.recordAdopted("", "{}");
        verify(adoptionMapper, never()).selectPendingByCode(any());
        verify(adoptionMapper, never()).updateById(any(AgentPlannerAdoptionDO.class));
    }

    @Test
    @DisplayName("确认：写库抛错 → 被吞掉不冒泡（埋点绝不拖垮发布主流程）")
    void recordAdopted_updateThrows_swallowed() {
        when(adoptionMapper.selectPendingByCode("wf_plan_1"))
                .thenReturn(pendingRow(1L, "wf_plan_1", "{\"a\":1}"));
        when(adoptionMapper.updateById(any(AgentPlannerAdoptionDO.class))).thenThrow(new RuntimeException("约束撞了"));
        assertDoesNotThrow(() -> adoptionService.recordAdopted("wf_plan_1", "{\"a\":2}"));
    }

    // ==================== getAdoptionStat ====================

    @Test
    @DisplayName("统计：三档计数 + 采纳率/手改率算对（比率 = 分子/分母×100，保留 1 位）")
    void getAdoptionStat_computesRates() {
        // generated=8, adopted=6, handModified=3 → 采纳率 75.0%，手改率 50.0%
        when(adoptionMapper.selectCount()).thenReturn(8L);
        when(adoptionMapper.selectCount(any(SFunction.class), anyBoolean())).thenReturn(6L);
        when(adoptionMapper.selectCount(any(Wrapper.class))).thenReturn(3L);

        AgentPlannerAdoptionStatRespVO vo = adoptionService.getAdoptionStat();

        assertEquals(8L, vo.getGenerated());
        assertEquals(6L, vo.getAdopted());
        assertEquals(3L, vo.getHandModified());
        assertEquals(0, new BigDecimal("75.0").compareTo(vo.getAdoptionRate()), "6/8 = 75.0%");
        assertEquals(0, new BigDecimal("50.0").compareTo(vo.getHandModifiedRate()), "3/6 = 50.0%");
    }

    @Test
    @DisplayName("统计：分母为 0 → 比率 null（语义「暂无」，不是刺眼的「0%」）")
    void getAdoptionStat_emptyDenominator_nullRate() {
        when(adoptionMapper.selectCount()).thenReturn(0L);
        when(adoptionMapper.selectCount(any(SFunction.class), anyBoolean())).thenReturn(0L);
        when(adoptionMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        AgentPlannerAdoptionStatRespVO vo = adoptionService.getAdoptionStat();

        assertEquals(0L, vo.getGenerated());
        assertNull(vo.getAdoptionRate(), "generated=0 → 采纳率 null");
        assertNull(vo.getHandModifiedRate(), "adopted=0 → 手改率 null");
    }

    @Test
    @DisplayName("统计：selectCount 返回 null（异常边界）→ 计数当 0，不 NPE")
    void getAdoptionStat_nullCounts_treatedAsZero() {
        when(adoptionMapper.selectCount()).thenReturn(null);
        when(adoptionMapper.selectCount(any(SFunction.class), anyBoolean())).thenReturn(null);
        when(adoptionMapper.selectCount(any(Wrapper.class))).thenReturn(null);

        AgentPlannerAdoptionStatRespVO vo = assertDoesNotThrow(() -> adoptionService.getAdoptionStat());

        assertEquals(0L, vo.getGenerated());
        assertEquals(0L, vo.getAdopted());
        assertEquals(0L, vo.getHandModified());
        assertNull(vo.getAdoptionRate());
    }

    @Test
    @DisplayName("统计：三档 selectCount 各查一次（租户维度由拦截器保证，不额外传 tenant）")
    void getAdoptionStat_queriesThreeCounts() {
        when(adoptionMapper.selectCount()).thenReturn(2L);
        when(adoptionMapper.selectCount(any(SFunction.class), anyBoolean())).thenReturn(1L);
        when(adoptionMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        adoptionService.getAdoptionStat();

        verify(adoptionMapper, times(1)).selectCount();
        verify(adoptionMapper, times(1)).selectCount(any(SFunction.class), anyBoolean());
        verify(adoptionMapper, times(1)).selectCount(any(Wrapper.class));
    }

}
