package cn.enlev.ainb.module.agent.service.workflow;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowDefMapper;
import cn.enlev.ainb.module.agent.enums.WorkflowDefStatusEnum;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.trigger.WorkflowTriggerExtractor;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.List;

import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DEF_NOT_PUBLISHED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentWorkflowDefServiceImpl} 版本管理单测（增量14b：listVersionsByCode / rollbackToVersion / setPrimaryVersion）
 *
 * <p>纯 mock（{@link AgentWorkflowDefMapper} + {@link WorkflowTriggerExtractor}），0 token、不碰真 DB。
 * 断言聚焦版本管理的三条不可变纪律：
 * <ol>
 *   <li>listVersionsByCode：原样返回 mapper 的全部版本，但 <b>DSL 正文一律置 null</b>（列表只需元信息，对比另调 getDef）</li>
 *   <li>rollbackToVersion：<b>绝不改历史快照</b>——基于源版本 DSL 落一个 version=max+1 的新 DRAFT（copy code/name/desc、
 *       重跑 triggerExtractor.apply），返回新 id</li>
 *   <li>setPrimaryVersion：PUBLISHED → 先 clearPrimaryFlagByCode（同 code 至多一个主版本）再置目标 primary_flag=1
 *       （部分 DO 只带 id+primaryFlag，不碰 dsl/status/version）；已是主版本幂等无写；非 PUBLISHED 抛
 *       {@code WORKFLOW_DEF_NOT_PUBLISHED}</li>
 * </ol>
 *
 * @author 走进新时代
 */
class AgentWorkflowDefServiceVersionTest extends BaseMockitoUnitTest {

    @InjectMocks
    private AgentWorkflowDefServiceImpl defService;

    @Mock
    private AgentWorkflowDefMapper defMapper;

    @Mock
    private WorkflowTriggerExtractor triggerExtractor;

    private static AgentWorkflowDefDO def(Long id, String code, Integer version, String status) {
        AgentWorkflowDefDO d = new AgentWorkflowDefDO();
        d.setId(id);
        d.setCode(code);
        d.setVersion(version);
        d.setStatus(status);
        d.setDsl("{\"id\":\"" + code + "\",\"v\":" + version + "}");
        return d;
    }

    // ==================== listVersionsByCode ====================

    @Test
    void listVersionsByCode_stripsDsl_returnsAllVersions() {
        AgentWorkflowDefDO v2 = def(102L, "wf_x", 2, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        AgentWorkflowDefDO v1 = def(101L, "wf_x", 1, WorkflowDefStatusEnum.DISABLED.getStatus());
        when(defMapper.selectListByCode("wf_x")).thenReturn(List.of(v2, v1));

        List<AgentWorkflowDefDO> result = defService.listVersionsByCode("wf_x");

        assertEquals(2, result.size(), "全部版本都返回（不限状态）");
        assertEquals(2, result.get(0).getVersion(), "version 倒序，新版在前");
        // 🔴 DSL 正文一律裁掉：列表只需元信息，对比时前端另调 getDef(id) 取完整 DSL
        assertNull(result.get(0).getDsl(), "版本列表不带 DSL 正文");
        assertNull(result.get(1).getDsl(), "版本列表不带 DSL 正文");
        verify(defMapper).selectListByCode("wf_x");
    }

    // ==================== rollbackToVersion ====================

    @Test
    void rollbackToVersion_createsNewDraftFromSourceDsl_neverMutatesSource() {
        AgentWorkflowDefDO source = def(102L, "wf_x", 2, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        source.setName("客户跟进");
        source.setDescription("旧描述");
        String sourceDsl = source.getDsl();
        when(defMapper.selectById(102L)).thenReturn(source);
        // insertNewVersion 用 selectList(code) 算 nextVersion：现有 v1、v2 → 新版本 = 3
        when(defMapper.selectList(any(SFunction.class), eq("wf_x")))
                .thenReturn(List.of(def(101L, "wf_x", 1, WorkflowDefStatusEnum.DISABLED.getStatus()), source));
        // insert 在真库由 MP 回填 id；mock 里手动回填，供 rollbackToVersion 返回
        when(defMapper.insert(any(AgentWorkflowDefDO.class))).thenAnswer(inv -> {
            inv.getArgument(0, AgentWorkflowDefDO.class).setId(999L);
            return 1;
        });

        Long newId = defService.rollbackToVersion(102L);

        assertEquals(999L, newId, "返回新草稿 id");
        ArgumentCaptor<AgentWorkflowDefDO> captor = ArgumentCaptor.forClass(AgentWorkflowDefDO.class);
        verify(defMapper).insert(captor.capture());
        AgentWorkflowDefDO inserted = captor.getValue();
        assertEquals("wf_x", inserted.getCode(), "同 code 出新版本");
        assertEquals(3, inserted.getVersion(), "version = 现有最大 + 1");
        assertEquals(WorkflowDefStatusEnum.DRAFT.getStatus(), inserted.getStatus(), "🔴 回滚产物是新草稿，不直接发布");
        assertEquals(sourceDsl, inserted.getDsl(), "复制源版本 DSL 原文");
        assertEquals("客户跟进", inserted.getName());
        assertEquals("旧描述", inserted.getDescription());
        // 重跑触发器投影（旧 DSL 的 trigger 也要按现行规则重新摊平）
        verify(triggerExtractor).apply(any(AgentWorkflowDefDO.class));
        // 🔴 绝不动历史快照：不 updateById、不 deleteById 源版本
        verify(defMapper, never()).updateById(any(AgentWorkflowDefDO.class));
        verify(defMapper, never()).deleteById(any());
    }

    // ==================== setPrimaryVersion ====================

    @Test
    void setPrimaryVersion_published_clearsThenSetsPrimaryFlagOnly() {
        AgentWorkflowDefDO target = def(101L, "wf_x", 1, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        target.setPrimaryFlag(false);
        when(defMapper.selectById(101L)).thenReturn(target);

        defService.setPrimaryVersion(101L);

        // 先清同 code 全部主版本标志，保证「同 code 至多一个主版本」
        verify(defMapper).clearPrimaryFlagByCode("wf_x");
        ArgumentCaptor<AgentWorkflowDefDO> captor = ArgumentCaptor.forClass(AgentWorkflowDefDO.class);
        verify(defMapper).updateById(captor.capture());
        AgentWorkflowDefDO updated = captor.getValue();
        assertEquals(101L, updated.getId());
        assertEquals(Boolean.TRUE, updated.getPrimaryFlag(), "目标行置为主版本");
        // 🔴 只动 primary_flag：dsl/status/version/code 都不带进 update（历史快照不可变）
        assertNull(updated.getDsl(), "只翻 primary_flag，dsl 不动");
        assertNull(updated.getStatus(), "只翻 primary_flag，status 不动");
        assertNull(updated.getVersion(), "只翻 primary_flag，version 不动");
        assertNull(updated.getCode(), "只翻 primary_flag，code 不动");
    }

    @Test
    void setPrimaryVersion_alreadyPrimary_idempotentNoWrite() {
        AgentWorkflowDefDO target = def(101L, "wf_x", 1, WorkflowDefStatusEnum.PUBLISHED.getStatus());
        target.setPrimaryFlag(true);
        when(defMapper.selectById(101L)).thenReturn(target);

        defService.setPrimaryVersion(101L);

        verify(defMapper, never()).clearPrimaryFlagByCode(any());
        verify(defMapper, never()).updateById(any(AgentWorkflowDefDO.class));
    }

    @Test
    void setPrimaryVersion_notPublished_throws() {
        when(defMapper.selectById(101L))
                .thenReturn(def(101L, "wf_x", 1, WorkflowDefStatusEnum.DRAFT.getStatus()));

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.setPrimaryVersion(101L));
        assertEquals(WORKFLOW_DEF_NOT_PUBLISHED.getCode(), ex.getCode(), "草稿/停用版设主版本无意义");
        verify(defMapper, never()).clearPrimaryFlagByCode(any());
        verify(defMapper, never()).updateById(any(AgentWorkflowDefDO.class));
    }

}
