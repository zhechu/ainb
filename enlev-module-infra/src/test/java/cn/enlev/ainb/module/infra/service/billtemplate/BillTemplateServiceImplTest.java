package cn.enlev.ainb.module.infra.service.billtemplate;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.module.infra.dal.dataobject.billtemplate.BillTemplateDO;
import cn.enlev.ainb.module.infra.dal.dataobject.billtemplate.BillTemplateLogDO;
import cn.enlev.ainb.module.infra.dal.mysql.billtemplate.BillTemplateLogMapper;
import cn.enlev.ainb.module.infra.dal.mysql.billtemplate.BillTemplateMapper;
import cn.enlev.ainb.module.infra.dal.redis.billtemplate.BillTemplateRedisDAO;
import cn.enlev.ainb.module.infra.enums.billtemplate.BillGenTypeEnum;
import cn.enlev.ainb.module.infra.enums.billtemplate.ResetCycleEnum;
import cn.enlev.ainb.module.infra.framework.billtemplate.core.BillNumberSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BillTemplateServiceImpl} 取号日志相关单元测试
 *
 * 聚焦本次新增的日志埋点：开关短路、来源区分、失败留痕、内联/预占复用不落日志。
 * 拼号算法本身由 {@code BillNumberGeneratorTest} 覆盖，这里不重复。
 */
@ExtendWith(MockitoExtension.class)
class BillTemplateServiceImplTest {

    @Mock
    private BillTemplateMapper billTemplateMapper;
    @Mock
    private BillTemplateRedisDAO billTemplateRedisDAO;
    @Mock
    private BillTemplateLogMapper billTemplateLogMapper;
    /** saveGenerateLog 用独立事务提交，测试里给个 mock 事务管理器即可走通 TransactionTemplate */
    @Mock
    private PlatformTransactionManager transactionManager;
    @InjectMocks
    private BillTemplateServiceImpl billTemplateService;

    private BillTemplateDO template(boolean logEnabled) {
        BillTemplateDO entity = new BillTemplateDO();
        entity.setId(1L);
        entity.setName("采购订单号");
        entity.setCode("test_tpl");
        entity.setGenType(BillGenTypeEnum.TIME.getType());
        entity.setPrefix("CG");
        entity.setSuffix("");
        entity.setDateFormat("yyyyMMdd");
        entity.setDigit(4);
        entity.setStartNumber(1);
        entity.setStep(1);
        entity.setResetCycle(ResetCycleEnum.DAY.getCode());
        entity.setGroupByPrefix(false);
        entity.setStatus(CommonStatusEnum.ENABLE.getStatus());
        entity.setLogEnabled(logEnabled);
        return entity;
    }

    @Test
    void generate_logEnabled_writesLog() {
        when(billTemplateMapper.selectByCode("test_tpl")).thenReturn(template(true));
        when(billTemplateRedisDAO.nextSequence(eq("test_tpl"), any(), any(), any())).thenReturn(5L);

        String no = billTemplateService.generate("test_tpl");

        assertTrue(no.startsWith("CG"));
        ArgumentCaptor<BillTemplateLogDO> captor = ArgumentCaptor.forClass(BillTemplateLogDO.class);
        verify(billTemplateLogMapper).insert(captor.capture());
        BillTemplateLogDO logDO = captor.getValue();
        assertEquals(1L, logDO.getTemplateId());
        assertEquals("test_tpl", logDO.getTemplateCode());
        assertEquals(no, logDO.getBillNo());
        assertEquals(BillGenTypeEnum.TIME.getType(), logDO.getGenType());
        assertEquals(5L, logDO.getSeq());
        assertEquals(1, logDO.getSource()); // 业务出号
        assertTrue(logDO.getSuccess());
        assertEquals("", logDO.getErrorMsg());
    }

    @Test
    void generate_logDisabled_noLog() {
        when(billTemplateMapper.selectByCode("test_tpl")).thenReturn(template(false));
        when(billTemplateRedisDAO.nextSequence(eq("test_tpl"), any(), any(), any())).thenReturn(5L);

        String no = billTemplateService.generate("test_tpl");

        assertTrue(no.startsWith("CG"));
        verify(billTemplateLogMapper, never()).insert(any(BillTemplateLogDO.class));
    }

    @Test
    void generate_redisFail_logsFailureAndRethrows() {
        when(billTemplateMapper.selectByCode("test_tpl")).thenReturn(template(true));
        when(billTemplateRedisDAO.nextSequence(eq("test_tpl"), any(), any(), any()))
                .thenThrow(new RuntimeException("redis down"));

        assertThrows(RuntimeException.class, () -> billTemplateService.generate("test_tpl"));

        ArgumentCaptor<BillTemplateLogDO> captor = ArgumentCaptor.forClass(BillTemplateLogDO.class);
        verify(billTemplateLogMapper).insert(captor.capture());
        BillTemplateLogDO logDO = captor.getValue();
        assertFalse(logDO.getSuccess());
        assertEquals("", logDO.getBillNo());
        assertNull(logDO.getSeq());
        assertTrue(logDO.getErrorMsg().contains("redis down"));
    }

    @Test
    void occupyByCode_newOccupy_logsSource2() {
        when(billTemplateMapper.selectByCode("test_tpl")).thenReturn(template(true));
        when(billTemplateRedisDAO.getOccupied(any(), eq("test_tpl"))).thenReturn(null);
        when(billTemplateRedisDAO.nextSequence(eq("test_tpl"), any(), any(), any())).thenReturn(7L);

        String no = billTemplateService.occupyByCode("test_tpl");

        assertTrue(no.startsWith("CG"));
        verify(billTemplateRedisDAO).occupy(any(), eq("test_tpl"), eq(no));
        ArgumentCaptor<BillTemplateLogDO> captor = ArgumentCaptor.forClass(BillTemplateLogDO.class);
        verify(billTemplateLogMapper).insert(captor.capture());
        BillTemplateLogDO logDO = captor.getValue();
        assertEquals(2, logDO.getSource()); // 表单预占
        assertTrue(logDO.getSuccess());
        assertEquals(7L, logDO.getSeq());
    }

    @Test
    void occupyByCode_reuseExisting_noLog() {
        when(billTemplateMapper.selectByCode("test_tpl")).thenReturn(template(true));
        when(billTemplateRedisDAO.getOccupied(any(), eq("test_tpl"))).thenReturn("CG202609200001");

        String no = billTemplateService.occupyByCode("test_tpl");

        assertEquals("CG202609200001", no);
        // 复用没有消耗计数：不取序号、不落日志
        verify(billTemplateRedisDAO, never()).nextSequence(any(), any(), any(), any());
        verify(billTemplateLogMapper, never()).insert(any(BillTemplateLogDO.class));
    }

    @Test
    void generateBySpec_inline_noLog() {
        BillNumberSpec spec = BillNumberSpec.builder()
                .counterScope("inline:purchase:f1")
                .prefix("IN")
                .suffix("")
                .genType(BillGenTypeEnum.TIME)
                .dateFormat("yyyyMMdd")
                .digit(4)
                .startNumber(1)
                .step(1)
                .resetCycle(ResetCycleEnum.DAY)
                .groupByPrefix(false)
                .build();
        when(billTemplateRedisDAO.nextSequence(eq("inline:purchase:f1"), any(), any(), any())).thenReturn(3L);

        String no = billTemplateService.generateBySpec(spec);

        assertTrue(no.startsWith("IN"));
        // 内联模式没有模板行，也就没有开关可判：不应触碰日志表
        verify(billTemplateLogMapper, never()).insert(any(BillTemplateLogDO.class));
    }

}
