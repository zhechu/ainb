package cn.enlev.ainb.module.agent.service.def;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.controller.admin.def.vo.AgentDefPageReqVO;
import cn.enlev.ainb.module.agent.controller.admin.def.vo.AgentDefSaveReqVO;
import cn.enlev.ainb.module.agent.dal.dataobject.def.AgentDefDO;
import cn.enlev.ainb.module.agent.dal.dataobject.tooldef.AgentToolDefDO;
import cn.enlev.ainb.module.agent.dal.mysql.def.AgentDefMapper;
import cn.enlev.ainb.module.agent.enums.AgentDefStatusEnum;
import cn.enlev.ainb.module.agent.enums.ToolSourceEnum;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolCatalog;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolRegistry;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.agent.service.tooldef.AgentToolDefService;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_DEF_BUILTIN_READONLY;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_DEF_CODE_EXISTS;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_DEF_NOT_DRAFT;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_DEF_NOT_EXISTS;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_DEF_NOT_PUBLISHED;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_DEF_PUBLISHED_NOT_DELETABLE;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_DEF_TOOL_INVALID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentDefServiceImpl} 单测（P5 Agent Builder 第一刀）
 *
 * <p>纯 mock（{@link AgentDefMapper}），0 token、不碰真 DB。断言聚焦这些事，与
 * {@code AgentWorkflowDefServiceTest} 同纪律（两套定义管理生命周期同构，测试也互为印证）：
 * <ol>
 *   <li>createDef：version = 同 code 现有最大 + 1（空则 1）、status 恒 DRAFT、builtin 恒 false、部分列落库</li>
 *   <li>updateDef：DRAFT 成功（部分 DO 只带 name/persona/description，不带 code/version/status）；
 *       非 DRAFT 抛 {@code AGENT_DEF_NOT_DRAFT}、不 updateById</li>
 *   <li>deleteDef：DRAFT / DISABLED 成功；PUBLISHED 抛 {@code AGENT_DEF_PUBLISHED_NOT_DELETABLE}（需求11）</li>
 *   <li>publishDefById / disableDefById：翻状态用「只带 id+status 的部分 DO」、幂等不 update、状态守卫抛错</li>
 *   <li>需求10：内置行（默认平台助手）取用与四道守卫（可就地改三列、不可删/停用/派生、保留 code 不可占用）</li>
 *   <li>需求11：deriveDef 派生同 code 新版本、copyDef 换新 code 整份搬配置</li>
 * </ol>
 *
 * @author 走进新时代
 */
class AgentDefServiceTest extends BaseMockitoUnitTest {

    @InjectMocks
    private AgentDefServiceImpl defService;

    @Mock
    private AgentDefMapper defMapper;

    /** 增量12 / slice-3b：发布期校验 toolKeys 只读性时按名查工具定义 */
    @Mock
    private AgentToolRegistry toolRegistry;

    /**
     * 自定义工具目录（B2）。绝大多数用例不桩它 —— 未桩的 mock 返回 null，
     * {@code AgentToolCatalog#customRows()} 据此当成「本租户没有自定义工具」，
     * 于是既有那些 {@code when(toolRegistry.get(...))} 的桩<b>一行都不用改</b>。
     */
    @Mock
    private AgentToolDefService toolDefService;

    /**
     * 🔴 注入的是<b>真实</b>目录（只把它的两个依赖换成 mock），不是 mock 掉的目录本身。
     * 理由与 {@code AgentChatToolLoopTest#setUp} 同一条，且这里更硬：
     * {@code ToolView} 是 record，Mockito 压根 mock 不了它，
     * 真要 mock 目录就得给每处桩手搓一个十四字段的 record ——
     * 那样测到的只是「我自己搓的那个 record 长什么样」，
     * 而本类②③④三档（写工具 / 降险 / 未登记）要钉的是
     * 「闸从<b>真实</b>解析结果里读的是声明值还是生效值」。
     */
    @BeforeEach
    void setUpToolCatalog() {
        ReflectionTestUtils.setField(defService, "toolCatalog",
                new AgentToolCatalog(toolRegistry, toolDefService));
    }

    private static AgentDefDO def(Long id, String code, Integer version, String status) {
        AgentDefDO d = new AgentDefDO();
        d.setId(id);
        d.setCode(code);
        d.setVersion(version);
        d.setStatus(status);
        d.setPersona("你是" + code + "的客服");
        return d;
    }

    /**
     * 系统内置行（需求10）：{@code platform_assistant}，恒 PUBLISHED + builtin=true。
     * 刻意做成一个独立工厂而不是给 {@link #def} 加参数——它是不参与版本化的单例，
     * 让「造一行内置数据」在测试里显式可见，比一个布尔形参更能说明意图。
     */
    private static AgentDefDO builtinDef(Long id) {
        AgentDefDO d = def(id, AgentDefDO.CODE_PLATFORM_ASSISTANT, 1,
                AgentDefStatusEnum.PUBLISHED.getStatus());
        d.setName("默认平台助手");
        d.setPersona("你是这个低代码平台的助手");
        d.setBuiltin(true);
        return d;
    }

    /** 让 mock 的 insert 回填一个自增 id，好断言 createDef 的返回值 */
    private void stubInsertAssignsId(long id) {
        when(defMapper.insert(any(AgentDefDO.class))).thenAnswer(inv -> {
            ((AgentDefDO) inv.getArgument(0)).setId(id);
            return 1;
        });
    }

    // ==================== createDef ====================

    @Test
    void createDef_firstVersion_isDraftVersionOne() {
        when(defMapper.selectList(any(SFunction.class), eq("order_assistant"))).thenReturn(List.of());
        stubInsertAssignsId(555L);
        AgentDefSaveReqVO reqVO = new AgentDefSaveReqVO();
        reqVO.setCode("order_assistant");
        reqVO.setName("订单客服");
        reqVO.setPersona("你是一名耐心的订单客服");
        reqVO.setDescription("处理订单咨询");

        Long id = defService.createDef(reqVO);

        assertEquals(555L, id);
        ArgumentCaptor<AgentDefDO> captor = ArgumentCaptor.forClass(AgentDefDO.class);
        verify(defMapper).insert(captor.capture());
        AgentDefDO inserted = captor.getValue();
        assertEquals("order_assistant", inserted.getCode());
        assertEquals("订单客服", inserted.getName());
        assertEquals("你是一名耐心的订单客服", inserted.getPersona());
        assertEquals(1, inserted.getVersion(), "首个版本 = 1");
        assertEquals(AgentDefStatusEnum.DRAFT.getStatus(), inserted.getStatus(), "新建恒落草稿");
        assertFalse(inserted.getBuiltin(), "🔴 需求10：新建恒非内置——内置标记只能由种子迁移给出，"
                + "否则用户能自己造一行删不掉、停不了的僵尸记录");
    }

    @Test
    void createDef_existingCode_bumpsToMaxVersionPlusOne() {
        when(defMapper.selectList(any(SFunction.class), eq("order_assistant"))).thenReturn(List.of(
                def(1L, "order_assistant", 1, AgentDefStatusEnum.DISABLED.getStatus()),
                def(2L, "order_assistant", 2, AgentDefStatusEnum.PUBLISHED.getStatus())));
        stubInsertAssignsId(556L);
        AgentDefSaveReqVO reqVO = new AgentDefSaveReqVO();
        reqVO.setCode("order_assistant");
        reqVO.setName("订单客服 v3");
        reqVO.setPersona("新人设");

        defService.createDef(reqVO);

        ArgumentCaptor<AgentDefDO> captor = ArgumentCaptor.forClass(AgentDefDO.class);
        verify(defMapper).insert(captor.capture());
        assertEquals(3, captor.getValue().getVersion(), "同 code 现有最大版本 2 → 新版本 3");
        assertEquals(AgentDefStatusEnum.DRAFT.getStatus(), captor.getValue().getStatus());
    }

    @Test
    void createDef_persistsModelId() {
        when(defMapper.selectList(any(SFunction.class), eq("order_assistant"))).thenReturn(List.of());
        stubInsertAssignsId(557L);
        AgentDefSaveReqVO reqVO = new AgentDefSaveReqVO();
        reqVO.setCode("order_assistant");
        reqVO.setName("订单客服");
        reqVO.setPersona("你是一名耐心的订单客服");
        reqVO.setModelId(88L);

        defService.createDef(reqVO);

        ArgumentCaptor<AgentDefDO> captor = ArgumentCaptor.forClass(AgentDefDO.class);
        verify(defMapper).insert(captor.capture());
        assertEquals(88L, captor.getValue().getModelId(), "增量10：新建要落库绑定模型（空则为动态默认）");
    }

    @Test
    void createDef_persistsKnowledgeId() {
        when(defMapper.selectList(any(SFunction.class), eq("order_assistant"))).thenReturn(List.of());
        stubInsertAssignsId(558L);
        AgentDefSaveReqVO reqVO = new AgentDefSaveReqVO();
        reqVO.setCode("order_assistant");
        reqVO.setName("订单客服");
        reqVO.setPersona("你是一名耐心的订单客服");
        reqVO.setKnowledgeId(7L);

        defService.createDef(reqVO);

        ArgumentCaptor<AgentDefDO> captor = ArgumentCaptor.forClass(AgentDefDO.class);
        verify(defMapper).insert(captor.capture());
        assertEquals(7L, captor.getValue().getKnowledgeId(),
                "增量11：新建要落库绑定知识库（空则该 Agent 不检索）");
    }

    // ==================== updateDef ====================


    @Test
    void updateDef_draft_updatesEditableColumnsOnly() {
        when(defMapper.selectById(100L))
                .thenReturn(def(100L, "order_assistant", 1, AgentDefStatusEnum.DRAFT.getStatus()));
        AgentDefSaveReqVO reqVO = new AgentDefSaveReqVO();
        reqVO.setId(100L);
        reqVO.setName("新名字");
        reqVO.setPersona("新人设");
        reqVO.setDescription("新描述");

        defService.updateDef(reqVO);

        ArgumentCaptor<AgentDefDO> captor = ArgumentCaptor.forClass(AgentDefDO.class);
        verify(defMapper).updateById(captor.capture());
        AgentDefDO updated = captor.getValue();
        assertEquals(100L, updated.getId(), "只带 id 定位那一行");
        assertEquals("新名字", updated.getName());
        assertEquals("新人设", updated.getPersona());
        assertEquals("新描述", updated.getDescription());
        // 🔴 部分更新：code/version/status 不该被带进 update（null 不进 SET，保持原值）
        assertNull(updated.getCode(), "code 不参与更新");
        assertNull(updated.getVersion(), "version 不参与更新");
        assertNull(updated.getStatus(), "status 不因编辑而变");
    }

    @Test
    void updateDef_draft_persistsModelId() {
        when(defMapper.selectById(100L))
                .thenReturn(def(100L, "order_assistant", 1, AgentDefStatusEnum.DRAFT.getStatus()));
        AgentDefSaveReqVO reqVO = new AgentDefSaveReqVO();
        reqVO.setId(100L);
        reqVO.setName("新名字");
        reqVO.setPersona("新人设");
        reqVO.setModelId(99L);

        defService.updateDef(reqVO);

        ArgumentCaptor<AgentDefDO> captor = ArgumentCaptor.forClass(AgentDefDO.class);
        verify(defMapper).updateById(captor.capture());
        assertEquals(99L, captor.getValue().getModelId(), "增量10：改绑模型要落库（NOT_NULL 策略下 null 表示不改）");
    }

    @Test
    void updateDef_draft_persistsKnowledgeId() {
        when(defMapper.selectById(100L))
                .thenReturn(def(100L, "order_assistant", 1, AgentDefStatusEnum.DRAFT.getStatus()));
        AgentDefSaveReqVO reqVO = new AgentDefSaveReqVO();
        reqVO.setId(100L);
        reqVO.setName("新名字");
        reqVO.setPersona("新人设");
        reqVO.setKnowledgeId(7L);

        defService.updateDef(reqVO);

        ArgumentCaptor<AgentDefDO> captor = ArgumentCaptor.forClass(AgentDefDO.class);
        verify(defMapper).updateById(captor.capture());
        assertEquals(7L, captor.getValue().getKnowledgeId(),
                "增量11：改绑知识库要落库（沿用 NOT_NULL 策略，null 表示不改、防误清）");
    }

    @Test
    void updateDef_notDraft_throws() {

        when(defMapper.selectById(100L))
                .thenReturn(def(100L, "order_assistant", 2, AgentDefStatusEnum.PUBLISHED.getStatus()));
        AgentDefSaveReqVO reqVO = new AgentDefSaveReqVO();
        reqVO.setId(100L);
        reqVO.setName("x");
        reqVO.setPersona("y");

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.updateDef(reqVO));
        assertEquals(AGENT_DEF_NOT_DRAFT.getCode(), ex.getCode(), "已发布版本不可就地改");
        verify(defMapper, never()).updateById(any(AgentDefDO.class));
    }

    // ==================== deleteDef（需求11：放宽到「草稿 + 已停用」）====================

    @Test
    void deleteDef_draft_deletes() {
        when(defMapper.selectById(100L))
                .thenReturn(def(100L, "order_assistant", 1, AgentDefStatusEnum.DRAFT.getStatus()));

        defService.deleteDef(100L);

        verify(defMapper).deleteById(100L);
    }

    /**
     * 🔴 需求11 放宽的一档：已停用可删。停用后它已从聊天选择器消失、不再被任何对话引用，
     * 此时删除不会留孤儿。旧实现在这里抛 AGENT_DEF_NOT_DRAFT，管理员只能眼看着一条废记录删不掉。
     */
    @Test
    void deleteDef_disabled_deletes() {
        when(defMapper.selectById(100L))
                .thenReturn(def(100L, "order_assistant", 1, AgentDefStatusEnum.DISABLED.getStatus()));

        defService.deleteDef(100L);

        verify(defMapper).deleteById(100L);
    }

    /**
     * 🔴 已发布不可直接删：它还挂在聊天选择器里，删了等于让选到它的对话去解析一个不存在的 Agent。
     * 专用错误码（而非复用 AGENT_DEF_NOT_DRAFT）是为了把「该怎么做」讲清楚——先停用再删。
     */
    @Test
    void deleteDef_published_throwsNotDeletable() {
        AgentDefDO published = def(100L, "order_assistant", 2, AgentDefStatusEnum.PUBLISHED.getStatus());
        published.setName("订单客服");
        when(defMapper.selectById(100L)).thenReturn(published);

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.deleteDef(100L));
        assertEquals(AGENT_DEF_PUBLISHED_NOT_DELETABLE.getCode(), ex.getCode(), "已发布须先停用");
        verify(defMapper, never()).deleteById(any());
    }

    @Test
    void deleteDef_builtin_throws() {
        when(defMapper.selectById(1L)).thenReturn(builtinDef(1L));

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.deleteDef(1L));
        assertEquals(AGENT_DEF_BUILTIN_READONLY.getCode(), ex.getCode(),
                "🔴 需求10：删掉内置行会让所有「未选 Agent」的对话失去可维护的系统提示词入口");
        verify(defMapper, never()).deleteById(any());
    }

    // ==================== publishDefById ====================

    @Test
    void publishDefById_draftToPublished_updatesStatusOnly() {
        when(defMapper.selectById(100L))
                .thenReturn(def(100L, "order_assistant", 1, AgentDefStatusEnum.DRAFT.getStatus()));

        Long id = defService.publishDefById(100L);

        assertEquals(100L, id);
        ArgumentCaptor<AgentDefDO> captor = ArgumentCaptor.forClass(AgentDefDO.class);
        verify(defMapper).updateById(captor.capture());
        AgentDefDO updated = captor.getValue();
        assertEquals(100L, updated.getId());
        assertEquals(AgentDefStatusEnum.PUBLISHED.getStatus(), updated.getStatus());
        assertNull(updated.getPersona(), "只翻状态，persona 不动");
        assertNull(updated.getVersion(), "只翻状态，version 不动");
        assertNull(updated.getCode(), "只翻状态，code 不动");
    }

    @Test
    void publishDefById_alreadyPublished_idempotentNoUpdate() {
        when(defMapper.selectById(100L))
                .thenReturn(def(100L, "order_assistant", 2, AgentDefStatusEnum.PUBLISHED.getStatus()));

        Long id = defService.publishDefById(100L);

        assertEquals(100L, id, "幂等返回原 id");
        verify(defMapper, never()).updateById(any(AgentDefDO.class));
    }

    /**
     * 🔴 已停用可重新发布（「临时下线排障、修好再放回来」）。
     *
     * <p>旧实现在这里抛 {@code AGENT_DEF_NOT_DRAFT}，逼管理员为一个原封不动的 Agent
     * 派生出内容完全相同的 v(n+1)——多出的那个版本没有任何信息量，纯粹是绕路。
     * 停用的语义只是「从聊天选择器摘掉」，配置仍是完整快照，放回线上是安全的。
     */
    @Test
    void publishDefById_disabledToPublished_updatesStatusOnly() {
        when(defMapper.selectById(100L))
                .thenReturn(def(100L, "order_assistant", 3, AgentDefStatusEnum.DISABLED.getStatus()));

        Long id = defService.publishDefById(100L);

        assertEquals(100L, id);
        ArgumentCaptor<AgentDefDO> captor = ArgumentCaptor.forClass(AgentDefDO.class);
        verify(defMapper).updateById(captor.capture());
        assertEquals(AgentDefStatusEnum.PUBLISHED.getStatus(), captor.getValue().getStatus());
        assertNull(captor.getValue().getVersion(), "只翻状态，version 不动——它还是那个 v3");
    }

    /**
     * 🔴 重新发布照样要过发布期的工具只读闸。
     *
     * <p>停用期间工具注册表可能已经变了（某个原本只读的工具被改成写操作）。若「已发布过的就免检」，
     * 重新上线就会把一个不再合规的 Agent 放回聊天工具循环里——而那条循环是无人工确认的。
     */
    @Test
    void publishDefById_disabledWithWriteTool_throws() {
        AgentDefDO disabled = def(100L, "order_assistant", 3, AgentDefStatusEnum.DISABLED.getStatus());
        disabled.setToolKeys("form_data_create");
        when(defMapper.selectById(100L)).thenReturn(disabled);
        AgentToolRegistry.ToolDefinition writeTool = mock(AgentToolRegistry.ToolDefinition.class);
        // 🔴 只桩 getDeclaredRisk()，不桩 isRequireConfirm()/getRisk()：
        //    这两个走的是<b>租户生效值</b>（agent_tool_override 一行就能压低），发布闸绝不读它们
        //    —— 详见 {@link #publishDefById_writeToolDowngradedByOverride_stillThrows()}。
        //    STRICT_STUBS 下多桩一个不被调用的方法会直接判失败，所以这里不能「顺手都桩上」。
        when(writeTool.getDeclaredRisk()).thenReturn(RiskLevel.MEDIUM);
        when(toolRegistry.get("form_data_create")).thenReturn(writeTool);

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.publishDefById(100L));
        assertEquals(AGENT_DEF_TOOL_INVALID.getCode(), ex.getCode());
        verify(defMapper, never()).updateById(any(AgentDefDO.class));
    }

    // ==================== deleteDefList ====================

    /**
     * 批量删：草稿与已停用混着删都能过，一次 deleteByIds 删掉
     */
    @Test
    void deleteDefList_draftAndDisabled_deletes() {
        when(defMapper.selectByIds(List.of(1L, 2L))).thenReturn(List.of(
                def(1L, "a_assistant", 1, AgentDefStatusEnum.DRAFT.getStatus()),
                def(2L, "b_assistant", 2, AgentDefStatusEnum.DISABLED.getStatus())));

        defService.deleteDefList(List.of(1L, 2L));

        verify(defMapper).deleteByIds(List.of(1L, 2L));
    }

    /**
     * 🔴 全成或全不成：只要有一条是已发布，整批一条都不删。
     *
     * <p>这条是批量删除最要紧的语义。边删边校验会「删了一半才报错」——管理员看到失败以为都没动，
     * 回列表才发现少了几行，还得逐个回想是哪些。
     */
    @Test
    void deleteDefList_onePublished_deletesNothing() {
        when(defMapper.selectByIds(List.of(1L, 2L))).thenReturn(List.of(
                def(1L, "a_assistant", 1, AgentDefStatusEnum.DRAFT.getStatus()),
                def(2L, "b_assistant", 2, AgentDefStatusEnum.PUBLISHED.getStatus())));

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.deleteDefList(List.of(1L, 2L)));
        assertEquals(AGENT_DEF_PUBLISHED_NOT_DELETABLE.getCode(), ex.getCode());
        verify(defMapper, never()).deleteByIds(any());
    }

    /**
     * 批量删同样挡住内置行（与单删共用一份判据，不给「走批量绕过单删校验」留口子）
     */
    @Test
    void deleteDefList_builtin_deletesNothing() {
        when(defMapper.selectByIds(List.of(1L, 2L))).thenReturn(List.of(
                def(2L, "a_assistant", 1, AgentDefStatusEnum.DRAFT.getStatus()),
                builtinDef(1L)));

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.deleteDefList(List.of(1L, 2L)));
        assertEquals(AGENT_DEF_BUILTIN_READONLY.getCode(), ex.getCode());
        verify(defMapper, never()).deleteByIds(any());
    }

    /**
     * 有 id 查不到（被别人先删了 / 跨租户）时报「不存在」，而不是静默少删几条。
     * 静默成功会让人以为勾中的都删了，刷新后仍有残留，看起来像「删不掉」。
     */
    @Test
    void deleteDefList_missingId_throwsNotExists() {
        when(defMapper.selectByIds(List.of(1L, 2L))).thenReturn(List.of(
                def(1L, "a_assistant", 1, AgentDefStatusEnum.DRAFT.getStatus())));

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.deleteDefList(List.of(1L, 2L)));
        assertEquals(AGENT_DEF_NOT_EXISTS.getCode(), ex.getCode());
        verify(defMapper, never()).deleteByIds(any());
    }

    /**
     * 空列表直接返回：一次查询、一次删除都不该发生（前端禁用按钮时不该发请求，
     * 但万一发了也不能拼出 {@code in ()} 这种非法 SQL）
     */
    @Test
    void deleteDefList_empty_noop() {
        defService.deleteDefList(List.of());

        verify(defMapper, never()).selectByIds(any());
        verify(defMapper, never()).deleteByIds(any());
    }

    // ==================== disableDefById ====================

    @Test
    void disableDefById_publishedToDisabled_updatesStatusOnly() {
        when(defMapper.selectById(100L))
                .thenReturn(def(100L, "order_assistant", 1, AgentDefStatusEnum.PUBLISHED.getStatus()));

        defService.disableDefById(100L);

        ArgumentCaptor<AgentDefDO> captor = ArgumentCaptor.forClass(AgentDefDO.class);
        verify(defMapper).updateById(captor.capture());
        AgentDefDO updated = captor.getValue();
        assertEquals(100L, updated.getId());
        assertEquals(AgentDefStatusEnum.DISABLED.getStatus(), updated.getStatus());
        assertNull(updated.getPersona(), "只翻状态，persona 不动");
    }

    @Test
    void disableDefById_alreadyDisabled_idempotentNoUpdate() {
        when(defMapper.selectById(100L))
                .thenReturn(def(100L, "order_assistant", 1, AgentDefStatusEnum.DISABLED.getStatus()));

        defService.disableDefById(100L);

        verify(defMapper, never()).updateById(any(AgentDefDO.class));
    }

    @Test
    void disableDefById_draft_throwsNotPublished() {
        when(defMapper.selectById(100L))
                .thenReturn(def(100L, "order_assistant", 1, AgentDefStatusEnum.DRAFT.getStatus()));

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.disableDefById(100L));
        assertEquals(AGENT_DEF_NOT_PUBLISHED.getCode(), ex.getCode(), "没发布过谈不上停用");
        verify(defMapper, never()).updateById(any(AgentDefDO.class));
    }

    // ==================== getDef / getDefPage ====================

    @Test
    void getDef_notExists_throws() {
        when(defMapper.selectById(999L)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.getDef(999L));
        assertEquals(AGENT_DEF_NOT_EXISTS.getCode(), ex.getCode());
    }

    @Test
    void getDefPage_delegatesToMapper() {
        AgentDefPageReqVO reqVO = new AgentDefPageReqVO();
        reqVO.setCode("order");
        PageResult<AgentDefDO> expected = new PageResult<>(
                List.of(def(100L, "order_assistant", 1, AgentDefStatusEnum.DRAFT.getStatus())), 1L);
        when(defMapper.selectPage(reqVO)).thenReturn(expected);

        PageResult<AgentDefDO> actual = defService.getDefPage(reqVO);

        assertSame(expected, actual, "分页原样返回 mapper 结果，service 不再加工");
        verify(defMapper).selectPage(reqVO);
    }

    // ==================== getExecutableDef（增量10 D5：fail-close）====================

    /**
     * 🔴 这是「聊天入口按 Agent 路由」的安全闸：只有 PUBLISHED 的定义才能被拿去驱动对话。
     * 不存在 / DRAFT / DISABLED 一律抛 AGENT_DEF_NOT_EXISTS（不泄漏状态、与「选择器只列 PUBLISHED」自洽）。
     * 若这里放行草稿或停用版，陈旧 UI 选中它就会让对话跑在一个本不该生效的人设上——且不报错。
     */
    @Test
    void getExecutableDef_published_returns() {
        AgentDefDO published = def(100L, "order_assistant", 2, AgentDefStatusEnum.PUBLISHED.getStatus());
        when(defMapper.selectById(100L)).thenReturn(published);

        AgentDefDO actual = defService.getExecutableDef(100L);

        assertSame(published, actual, "PUBLISHED 定义原样返回，供 chat() 注入 persona/model");
    }

    @Test
    void getExecutableDef_draft_throwsNotExists() {
        when(defMapper.selectById(100L))
                .thenReturn(def(100L, "order_assistant", 1, AgentDefStatusEnum.DRAFT.getStatus()));

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.getExecutableDef(100L));
        assertEquals(AGENT_DEF_NOT_EXISTS.getCode(), ex.getCode(), "草稿不可执行；对外统一「不存在」不泄漏状态");
    }

    @Test
    void getExecutableDef_disabled_throwsNotExists() {
        when(defMapper.selectById(100L))
                .thenReturn(def(100L, "order_assistant", 3, AgentDefStatusEnum.DISABLED.getStatus()));

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.getExecutableDef(100L));
        assertEquals(AGENT_DEF_NOT_EXISTS.getCode(), ex.getCode(), "停用版不可执行（陈旧 UI 选到它也安全兜住）");
    }

    @Test
    void getExecutableDef_notExists_throws() {
        when(defMapper.selectById(999L)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.getExecutableDef(999L));
        assertEquals(AGENT_DEF_NOT_EXISTS.getCode(), ex.getCode());
    }

    // ==================== toolKeys 归一化 + 发布期只读校验（增量12 / slice-3b，批次 B4a）====================

    @Test
    void createDef_normalizesToolKeys() {
        when(defMapper.selectList(any(SFunction.class), eq("order_assistant"))).thenReturn(List.of());
        stubInsertAssignsId(560L);
        AgentDefSaveReqVO reqVO = new AgentDefSaveReqVO();
        reqVO.setCode("order_assistant");
        reqVO.setName("订单客服");
        reqVO.setPersona("你是一名耐心的订单客服");
        reqVO.setToolKeys(" ro_tool , ro_tool ,, data_interface_list ");

        defService.createDef(reqVO);

        ArgumentCaptor<AgentDefDO> captor = ArgumentCaptor.forClass(AgentDefDO.class);
        verify(defMapper).insert(captor.capture());
        assertEquals("ro_tool,data_interface_list", captor.getValue().getToolKeys(),
                "toolKeys 落库前逐个 trim、丢空项、去重、保序");
    }

    @Test
    void createDef_blankToolKeys_persistsEmptyString() {
        when(defMapper.selectList(any(SFunction.class), eq("order_assistant"))).thenReturn(List.of());
        stubInsertAssignsId(561L);
        AgentDefSaveReqVO reqVO = new AgentDefSaveReqVO();
        reqVO.setCode("order_assistant");
        reqVO.setName("订单客服");
        reqVO.setPersona("你是一名耐心的订单客服");
        reqVO.setToolKeys("  ,  ");

        defService.createDef(reqVO);

        ArgumentCaptor<AgentDefDO> captor = ArgumentCaptor.forClass(AgentDefDO.class);
        verify(defMapper).insert(captor.capture());
        assertEquals("", captor.getValue().getToolKeys(), "全空白 → 空串（非 null），表示不启用工具循环");
    }

    @Test
    void updateDef_canClearToolKeysToEmpty() {
        when(defMapper.selectById(100L))
                .thenReturn(def(100L, "order_assistant", 1, AgentDefStatusEnum.DRAFT.getStatus()));
        AgentDefSaveReqVO reqVO = new AgentDefSaveReqVO();
        reqVO.setId(100L);
        reqVO.setName("新名字");
        reqVO.setPersona("新人设");
        reqVO.setToolKeys(""); // 取消勾选全部工具

        defService.updateDef(reqVO);

        ArgumentCaptor<AgentDefDO> captor = ArgumentCaptor.forClass(AgentDefDO.class);
        verify(defMapper).updateById(captor.capture());
        assertEquals("", captor.getValue().getToolKeys(),
                "🔴 空串（非 null）才进 SET 子句，让「取消全部工具」真正清空该列（NOT_NULL 策略下 null 清不掉）");
    }

    @Test
    void publishDefById_readOnlyTool_passes() {
        AgentDefDO draft = def(100L, "order_assistant", 1, AgentDefStatusEnum.DRAFT.getStatus());
        draft.setToolKeys("ro_tool");
        when(defMapper.selectById(100L)).thenReturn(draft);
        AgentToolRegistry.ToolDefinition ro = mock(AgentToolRegistry.ToolDefinition.class);
        when(ro.getDeclaredRisk()).thenReturn(RiskLevel.READ_ONLY);
        // 🔴 必须显式桩成「启用」：mock 的 boolean 默认值是 false，而 D4-25 之后 validateToolKeys 会读
        //    isEnabled()。不桩的话本用例会悄悄走进「已停用」分支、放出一条 WARN —— 断言照样全绿，
        //    但这条名叫 readOnlyTool_passes 的用例验的就不再是「一个合法只读工具干净发布」了。
        when(ro.isEnabled()).thenReturn(true);
        when(toolRegistry.get("ro_tool")).thenReturn(ro);

        defService.publishDefById(100L);

        verify(defMapper).updateById(any(AgentDefDO.class));
    }

    @Test
    void publishDefById_writeTool_throws() {
        AgentDefDO draft = def(100L, "order_assistant", 1, AgentDefStatusEnum.DRAFT.getStatus());
        draft.setToolKeys("w_tool");
        when(defMapper.selectById(100L)).thenReturn(draft);
        AgentToolRegistry.ToolDefinition w = mock(AgentToolRegistry.ToolDefinition.class);
        when(w.getDeclaredRisk()).thenReturn(RiskLevel.LOW);
        when(toolRegistry.get("w_tool")).thenReturn(w);

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.publishDefById(100L));
        assertEquals(AGENT_DEF_TOOL_INVALID.getCode(), ex.getCode(),
                "🔴 聊天工具循环无人工确认，写工具绝不能绑进来被自动执行 → 拒发布");
        verify(defMapper, never()).updateById(any(AgentDefDO.class));
    }

    /**
     * 🔴 P0-1 的核心：一行 {@code agent_tool_override} 把声明 IRREVERSIBLE 压成生效 READ_ONLY，
     * 发布闸照样必须拒。
     *
     * <p>{@code ToolOverride.risk} 的 javadoc 明写「允许低于声明值，产品决策」—— 也就是说这<b>不是</b>
     * 假想路径，是产品刻意留的开关，界面上随时能点。而一旦发布闸读生效值（{@code isRequireConfirm()}
     * = {@code getRisk().isRequireConfirm()}），点下去之后：
     * <ol>
     *   <li>这道发布闸看不见写工具了，一个绑着「建表」的 Agent 照样发布上线；</li>
     *   <li>聊天工具循环是<b>无人值守</b>的，没有 Plan 确认卡兜着，模型自己就能执行它；</li>
     *   <li>界面上工具照样标着写操作、审计日志里也照样是写操作 —— <b>只有准入判定被改写，故全程静默</b>。</li>
     * </ol>
     *
     * <p>这就是文档 §1.2 里那条红线的由来（D11）：校验路径必须读搬不动的 {@code getDeclaredRisk()}。
     *
     * <p><b>🔴 变异探针（照 §2-8 的规矩）</b>：把 {@code AgentDefServiceImpl#validateToolKeys} 里的
     * {@code declaredRisk()} 改成 {@code requireConfirm()}（生效值），本用例必须变红，
     * 且红在 {@code assertThrows} 那一行（「本该拒绝却发布成功了」）。
     * 生效值是 READ_ONLY、其描述是「只读」，所以下面那句「报错必须含『不可逆』」也会同时红 ——
     * 红的理由只剩「闸读了生效值」这一个，不会混进别的噪声。
     */
    @Test
    void publishDefById_writeToolDowngradedByOverride_stillThrows() {
        AgentDefDO draft = def(100L, "order_assistant", 1, AgentDefStatusEnum.DRAFT.getStatus());
        draft.setToolKeys("datamodel_create");
        when(defMapper.selectById(100L)).thenReturn(draft);
        // 形状照 AgentLoopGraphBuilderTest#stubWriteDowngradedByOverride：
        // 生效值已被覆盖行压成「只读、不用确认」，声明值仍是不可逆。
        AgentToolRegistry.ToolDefinition downgraded = mock(AgentToolRegistry.ToolDefinition.class);
        // 这两个生效值桩如今会被 AgentToolCatalog 的 ToolView 真读到（它两格都装），
        // lenient() 只是兜底，判据仍只认下面那行声明值
        lenient().when(downgraded.isRequireConfirm()).thenReturn(false);
        lenient().when(downgraded.getRisk()).thenReturn(RiskLevel.READ_ONLY);
        when(downgraded.getDeclaredRisk()).thenReturn(RiskLevel.IRREVERSIBLE);
        when(toolRegistry.get("datamodel_create")).thenReturn(downgraded);

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.publishDefById(100L),
                "🔴 覆盖行把 IRREVERSIBLE 压成 READ_ONLY，发布闸也绝不能放行 —— 聊天工具循环无人工确认");
        assertEquals(AGENT_DEF_TOOL_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("不可逆"),
                "报错要说出<b>声明</b>风险级（不可逆），不能说「只读」—— 后者会让管理员以为覆盖行生效了，"
                        + "反而去查为什么没拦住。实际：" + ex.getMessage());
        verify(defMapper, never()).updateById(any(AgentDefDO.class));
        // 🔴 为什么这里不再断言 verify(downgraded, never()).getRisk()（B2 之前是有的）：
        //    闸现在隔着 AgentToolCatalog 读工具，而 ToolView 是<b>视图对象</b>，同时供配置页的
        //    「生效值快照」端点用，故它必然把 declared 与 effective 两格都读出来。红线因此从
        //    「校验路径上禁止调用 getRisk()」变成「闸只读 ToolView#declaredRisk()」，mock 层面表达不出来了。
        //    取而代之的钉法：上面那句「报错必须含『不可逆』」本身就是机械钉子 ——
        //    只有声明值是 IRREVERSIBLE，生效值是 READ_ONLY；闸一旦改读生效值，
        //    assertThrows 与这句会同时变红。另见 AgentToolCatalogTest 钉住 catalog 不把两格混成一格。
    }

    /**
     * 🔴 双向闸的另半边（§2-2）：声明只读的工具，即便被覆盖行<b>调高</b>成「要确认」，也照样放行。
     *
     * <p>没有这条，把判据写成「一律拒绝」也能让上面那条变绿。
     * 覆盖行调高确认要求是租户主动加保险，而发布闸守的是「聊天工具循环里不能有<b>本质上</b>会改数据的工具」
     * —— 本质只读的工具被要求确认，不该拦发布。判据只认声明值，两个方向都得对。
     */
    @Test
    void publishDefById_readOnlyToolUpgradedByOverride_passes() {
        AgentDefDO draft = def(100L, "order_assistant", 1, AgentDefStatusEnum.DRAFT.getStatus());
        draft.setToolKeys("ro_tool");
        when(defMapper.selectById(100L)).thenReturn(draft);
        AgentToolRegistry.ToolDefinition upgraded = mock(AgentToolRegistry.ToolDefinition.class);
        lenient().when(upgraded.isRequireConfirm()).thenReturn(true);
        lenient().when(upgraded.getRisk()).thenReturn(RiskLevel.MEDIUM);
        when(upgraded.getDeclaredRisk()).thenReturn(RiskLevel.READ_ONLY);
        when(upgraded.isEnabled()).thenReturn(true); // 见 publishDefById_readOnlyTool_passes 里那条同款注释
        when(toolRegistry.get("ro_tool")).thenReturn(upgraded);

        defService.publishDefById(100L);

        verify(defMapper).updateById(any(AgentDefDO.class));
        // 生效值 MEDIUM 是要确认的：闸一旦改读生效值，上面那句 updateById 就不会发生（本用例变红）。
        // 与 writeToolDowngradedByOverride_stillThrows 合成双向闸，两个方向都钉住「只认声明值」。
        // 原先这里还有 verify(upgraded, never()).getRisk()，B2 之后 catalog 的 ToolView 两格都装、
        // 必然会读到，那条机械断言已经表达不了红线了，理由见那个用例里的同款注释。
    }

    @Test
    void publishDefById_unregisteredTool_throws() {
        AgentDefDO draft = def(100L, "order_assistant", 1, AgentDefStatusEnum.DRAFT.getStatus());
        draft.setToolKeys("ghost_tool");
        when(defMapper.selectById(100L)).thenReturn(draft);
        when(toolRegistry.get("ghost_tool")).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.publishDefById(100L));
        assertEquals(AGENT_DEF_TOOL_INVALID.getCode(), ex.getCode(), "填错工具键 → 发布期 fail-loud，不留到运行期查不到");
        verify(defMapper, never()).updateById(any(AgentDefDO.class));
    }

    @Test
    void publishDefById_blankToolKeys_skipsValidation() {
        AgentDefDO draft = def(100L, "order_assistant", 1, AgentDefStatusEnum.DRAFT.getStatus());
        draft.setToolKeys(""); // 不绑工具
        when(defMapper.selectById(100L)).thenReturn(draft);

        defService.publishDefById(100L);

        verify(toolRegistry, never()).get(any()); // 空 toolKeys 直接放行，不查注册表
        verify(defMapper).updateById(any(AgentDefDO.class));
    }

    // ==================== D4-25 后端半：绑了「已停用」的工具 ⟹ 警告，但照样发布 ====================

    /**
     * 🔴 这一节的存在理由：D4-25 后端半的裁定是「警告而不是硬拒」，而「警告」这个交付物
     * <b>除了那行 log.warn 之外没有任何可观测产物</b> —— 不抛异常、不改返回值、不写库。
     *
     * <p>所以只断言「发布成功了」是断不住它的：一个把 warn 整段删掉的实现同样全绿，
     * 裁定就悄悄退化成「后端什么都不做」，而前端确认框里那句警告成了唯一防线
     * （列表页发布、curl、批量脚本都绕得开它）。故这里用 ListAppender 把日志变成可断言的对象，
     * 三条一起钉：<b>不拦</b>（状态照翻）、<b>要说</b>（WARN 在、点名工具键、说清后果）、
     * <b>别乱说</b>（工具没停用时一个字都不许有）。
     *
     * <p>🔴 为什么「已停用」不该进硬闸，见 {@code AgentDefServiceImpl#validateToolKeys} 的 javadoc：
     * 停用是「工具管理」页的动作，与本 Agent 的配置无关；硬拒会让绑了它的 Agent 连改人设、
     * 换模型这类无关改动都发布不了，且本方法只在 publishDefById 里被调，
     * 于是「重新发布」这个恢复动作会一起被堵死 —— 与它上面「DISABLED 也放行」的取舍直接打架。
     */
    private ListAppender<ILoggingEvent> captureLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(AgentDefServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    /** 🔴 必须摘掉：logger 是全局单例，漏摘会让后续用例继续往这个 appender 里灌日志，红点飘到别的测试上 */
    private void releaseLogs(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(AgentDefServiceImpl.class)).detachAppender(appender);
        appender.stop();
    }

    /** 该 logger 上所有 WARN 级日志的已格式化正文（占位符已解析） */
    private List<String> warnMessages(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /** 一个「声明只读、生效启用状态可控」的工具定义桩 */
    private AgentToolRegistry.ToolDefinition stubReadOnlyTool(String key, boolean enabled) {
        AgentToolRegistry.ToolDefinition t = mock(AgentToolRegistry.ToolDefinition.class);
        when(t.getDeclaredRisk()).thenReturn(RiskLevel.READ_ONLY);
        // 🔴 enabled 读【生效值】，与只读判据读【声明值】刻意相反：判「算不算只读」按声明值
        //    （否则一行覆盖就能把写工具洗成只读），判「调用会不会被拒」按生效值（网关读的就是它）。
        when(t.isEnabled()).thenReturn(enabled);
        when(toolRegistry.get(key)).thenReturn(t);
        return t;
    }

    @Test
    @DisplayName("绑了已停用的只读工具 ⟹ 照样发布（状态真翻了），并落一条 WARN 点名工具与后果")
    void publishDefById_disabledReadOnlyTool_publishesAndWarns() {
        AgentDefDO draft = def(100L, "order_assistant", 1, AgentDefStatusEnum.DRAFT.getStatus());
        draft.setToolKeys("form_list,ro_off");
        when(defMapper.selectById(100L)).thenReturn(draft);
        stubReadOnlyTool("form_list", true);
        stubReadOnlyTool("ro_off", false);

        ListAppender<ILoggingEvent> logs = captureLogs();
        try {
            Long published = defService.publishDefById(100L);

            // ① 不拦：这一条是裁定的本体
            assertEquals(100L, published);
            ArgumentCaptor<AgentDefDO> captor = ArgumentCaptor.forClass(AgentDefDO.class);
            verify(defMapper).updateById(captor.capture());
            assertEquals(AgentDefStatusEnum.PUBLISHED.getStatus(), captor.getValue().getStatus(),
                    "🔴 停用不是拒绝发布的理由 —— 硬拒会让「改个人设也发布不了」，见本节 javadoc");

            // ② 要说：光放行就成了「静默失效」，正是本模块头号缺陷
            List<String> warns = warnMessages(logs);
            assertEquals(1, warns.size(), "应恰有一条 WARN，实际：" + warns);
            String warn = warns.get(0);
            assertTrue(warn.contains("ro_off"), "警告必须点名是哪个工具被停用，否则管理员无从下手：" + warn);
            assertFalse(warn.contains("form_list"),
                    "没停用的那个工具不该被点名 —— 一起列出来会让管理员去解绑一个本来是好的工具：" + warn);
            assertTrue(warn.contains("停用"), "警告要说清原因（工具被停用）：" + warn);
            assertTrue(warn.contains("安全网关"),
                    "警告要说清后果发生在哪：运行期被安全网关拒，而不是「发布失败」或「配置无效」：" + warn);
            assertTrue(warn.contains("工具管理"),
                    "警告要给出路（去工具管理页重新启用），只报问题不给出路的告警等于把活推给读的人：" + warn);
        } finally {
            releaseLogs(logs);
        }
    }

    @Test
    @DisplayName("🔴 工具都启用着 ⟹ 一条 WARN 都不许有（否则「无条件警告」的实现也能让上一条变绿）")
    void publishDefById_allToolsEnabled_warnsNothing() {
        AgentDefDO draft = def(100L, "order_assistant", 1, AgentDefStatusEnum.DRAFT.getStatus());
        draft.setToolKeys("form_list,data_interface_list");
        when(defMapper.selectById(100L)).thenReturn(draft);
        stubReadOnlyTool("form_list", true);
        stubReadOnlyTool("data_interface_list", true);

        ListAppender<ILoggingEvent> logs = captureLogs();
        try {
            defService.publishDefById(100L);

            verify(defMapper).updateById(any(AgentDefDO.class));
            // 🔴 这条是上一条的反空转对照。告警一旦变成「每次发布都喊一嗓子」，
            //    管理员很快就不看了 —— 与 B1 里「弹几次假的二次确认，真的那次他就直接点确定」同一种失效方式。
            assertEquals(List.of(), warnMessages(logs),
                    "配置正常时不该有任何 WARN，实际：" + warnMessages(logs));
        } finally {
            releaseLogs(logs);
        }
    }

    @Test
    @DisplayName("已停用 Agent 带着停用工具「重新发布」⟹ 仍放行（硬拒会在这里堵死恢复动作）")
    void publishDefById_disabledToolOnRelaunch_stillPublishes() {
        // 🔴 刻意用 DISABLED 而不是 DRAFT：这一条钉的是「硬闸与恢复动作打架」那个具体后果。
        //    真实时序是——Agent 在线上跑着 ⟹ 管理员停用了它绑的某个工具 ⟹ 后来发现要临时下线排障
        //    ⟹ 修好了点「重新发布」。若停用进了硬闸，这一步就永远过不去，而配置本身一个字都没改。
        AgentDefDO disabled = def(100L, "order_assistant", 3, AgentDefStatusEnum.DISABLED.getStatus());
        disabled.setToolKeys("ro_off");
        when(defMapper.selectById(100L)).thenReturn(disabled);
        stubReadOnlyTool("ro_off", false);

        ListAppender<ILoggingEvent> logs = captureLogs();
        try {
            defService.publishDefById(100L);

            ArgumentCaptor<AgentDefDO> captor = ArgumentCaptor.forClass(AgentDefDO.class);
            verify(defMapper).updateById(captor.capture());
            assertEquals(AgentDefStatusEnum.PUBLISHED.getStatus(), captor.getValue().getStatus(),
                    "重新发布必须过得去 —— 与 publishDefById 里「DISABLED 也放行」的取舍保持一致");
            assertEquals(1, warnMessages(logs).size(), "放行归放行，警告照旧要说一次");
        } finally {
            releaseLogs(logs);
        }
    }

    @Test
    @DisplayName("多个工具被停用 ⟹ 一条 WARN 里全部点名（逐个喊会把日志刷成噪声，合并了才看得出面）")
    void publishDefById_severalDisabledTools_allNamedInOneWarn() {
        AgentDefDO draft = def(100L, "order_assistant", 1, AgentDefStatusEnum.DRAFT.getStatus());
        draft.setToolKeys("ro_off_a,form_list,ro_off_b");
        when(defMapper.selectById(100L)).thenReturn(draft);
        stubReadOnlyTool("ro_off_a", false);
        stubReadOnlyTool("form_list", true);
        stubReadOnlyTool("ro_off_b", false);

        ListAppender<ILoggingEvent> logs = captureLogs();
        try {
            defService.publishDefById(100L);

            List<String> warns = warnMessages(logs);
            assertEquals(1, warns.size(), "应合并成一条，实际 " + warns.size() + " 条：" + warns);
            assertTrue(warns.get(0).contains("ro_off_a") && warns.get(0).contains("ro_off_b"),
                    "两个停用项都要点名，只报第一个会让人修完一个以为好了：" + warns.get(0));
        } finally {
            releaseLogs(logs);
        }
    }

    // ==================== B2：自定义工具（agent_tool_def）也能绑到 Agent ====================

    /**
     * 🔴 这一节钉的是发布闸对<b>第二类工具</b>的处置。
     *
     * <p>B2 之前 {@code validateToolKeys} 只查 {@code AgentToolRegistry}，于是管理员在「工具管理」页
     * 登记好一个数据接口工具、启用着、风险填的只读，绑到 Agent 上点发布 —— 报「工具(x)未登记」。
     * 而运行期那两道真闸（{@code AgentSecurityGateway#checkTool}、{@code AgentToolExecutor#execute}）
     * 早就是 registry-first + 自定义目录回落，也就是说<b>这个工具本来是能执行的</b>，
     * 只是发布闸不认它。这类「明明能用却说不存在」的报错会把人引向完全错误的修法
     * （去反复检查那个工具是不是没保存成功）。
     *
     * <p>但放宽准入必须连带守住三件事，否则就是把一个报错换成一个更贵的静默故障：
     * <ol>
     *   <li><b>写工具照样拒</b> —— 聊天工具循环无人工确认，「是自定义的」不构成放宽理由；</li>
     *   <li><b>不可执行的来源照样拒</b> —— FORM/SCRIPT 是预留位，放过去就是「发布成功、每次调用都失败」；</li>
     *   <li><b>停用的照样只警告不硬拒</b> —— 与内置工具同一个 D4-25 裁定，不能因为换了一类工具就变卦。</li>
     * </ol>
     */
    private void stubCustomTool(String key, String source, String riskLevel, boolean enabled) {
        AgentToolDefDO row = new AgentToolDefDO();
        row.setToolKey(key);
        row.setSource(source);
        row.setRiskLevel(riskLevel);
        row.setEnabled(enabled);
        row.setDisplayName("销售日报");
        // registry 查不到它（unstubbed → null），于是 catalog 必然回落到自定义目录 ——
        // 这正是本组用例要走的那条路
        when(toolDefService.getToolDefListIncludingDisabled()).thenReturn(List.of(row));
    }

    @Test
    @DisplayName("🔴 B2 本体：自定义只读数据接口工具 ⟹ 发布通过（此前报「未登记」）")
    void publishDefById_customReadOnlyTool_passes() {
        AgentDefDO draft = def(100L, "order_assistant", 1, AgentDefStatusEnum.DRAFT.getStatus());
        draft.setToolKeys("sales_daily_report");
        when(defMapper.selectById(100L)).thenReturn(draft);
        stubCustomTool("sales_daily_report", ToolSourceEnum.DATA_INTERFACE.getSource(), "READ_ONLY", true);

        assertEquals(100L, defService.publishDefById(100L));

        verify(defMapper).updateById(any(AgentDefDO.class));
    }

    @Test
    @DisplayName("自定义工具与内置只读工具混绑 ⟹ 一起过（两类工具在同一道闸里同口径）")
    void publishDefById_builtinAndCustomMixed_passes() {
        AgentDefDO draft = def(100L, "order_assistant", 1, AgentDefStatusEnum.DRAFT.getStatus());
        draft.setToolKeys("form_list,sales_daily_report");
        when(defMapper.selectById(100L)).thenReturn(draft);
        stubReadOnlyTool("form_list", true);
        stubCustomTool("sales_daily_report", ToolSourceEnum.DATA_INTERFACE.getSource(), "READ_ONLY", true);

        defService.publishDefById(100L);

        verify(defMapper).updateById(any(AgentDefDO.class));
    }

    @Test
    @DisplayName("🔴 自定义写工具 ⟹ 照样拒，且报错说出它自己那列 risk_level（它没有覆盖表）")
    void publishDefById_customWriteTool_throws() {
        AgentDefDO draft = def(100L, "order_assistant", 1, AgentDefStatusEnum.DRAFT.getStatus());
        draft.setToolKeys("sales_reset");
        when(defMapper.selectById(100L)).thenReturn(draft);
        stubCustomTool("sales_reset", ToolSourceEnum.DATA_INTERFACE.getSource(), "IRREVERSIBLE", true);

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.publishDefById(100L));
        assertEquals(AGENT_DEF_TOOL_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("不可逆"),
                "报错要说出风险级的中文描述，管理员才知道该去改哪一列。实际：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("只读"),
                "报错要说清准入判据是「只读」，否则看不出这是聊天工具循环的限制。实际：" + ex.getMessage());
        verify(defMapper, never()).updateById(any(AgentDefDO.class));
    }

    @Test
    @DisplayName("🔴 risk_level 漏填的自定义工具 ⟹ 按 HIGH 兜、拒发布（宁可多说风险）")
    void publishDefById_customToolWithBlankRisk_throws() {
        AgentDefDO draft = def(100L, "order_assistant", 1, AgentDefStatusEnum.DRAFT.getStatus());
        draft.setToolKeys("risk_missing");
        when(defMapper.selectById(100L)).thenReturn(draft);
        stubCustomTool("risk_missing", ToolSourceEnum.DATA_INTERFACE.getSource(), null, true);

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.publishDefById(100L));
        assertEquals(AGENT_DEF_TOOL_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("高风险"),
                "缺失一律当 HIGH，与 RiskLevel.parseOrHigh 同方向。实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("🔴 source 是预留位（FORM）的自定义工具 ⟹ 拒发布，别留到运行期每次调用都失败")
    void publishDefById_customNonExecutableSource_throws() {
        AgentDefDO draft = def(100L, "order_assistant", 1, AgentDefStatusEnum.DRAFT.getStatus());
        draft.setToolKeys("form_writer");
        when(defMapper.selectById(100L)).thenReturn(draft);
        stubCustomTool("form_writer", ToolSourceEnum.FORM.getSource(), "READ_ONLY", true);

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.publishDefById(100L));
        assertEquals(AGENT_DEF_TOOL_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("FORM") && ex.getMessage().contains("不可执行"),
                "要点名是哪个来源不可执行 —— 这一支只有绕过登记接口直接改库才走得到，"
                        + "报错是唯一的线索。实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("停用的自定义工具 ⟹ 与内置同一个 D4-25 裁定：照样发布，并 WARN 点名")
    void publishDefById_disabledCustomTool_publishesAndWarns() {
        AgentDefDO draft = def(100L, "order_assistant", 1, AgentDefStatusEnum.DRAFT.getStatus());
        draft.setToolKeys("sales_daily_report");
        when(defMapper.selectById(100L)).thenReturn(draft);
        stubCustomTool("sales_daily_report", ToolSourceEnum.DATA_INTERFACE.getSource(), "READ_ONLY", false);

        ListAppender<ILoggingEvent> logs = captureLogs();
        try {
            assertEquals(100L, defService.publishDefById(100L),
                    "🔴 换成自定义工具就该硬拒的话，等于给 D4-25 开了个「按工具来源分叉」的口子");
            List<String> warns = warnMessages(logs);
            assertEquals(1, warns.size(), "应恰有一条 WARN，实际：" + warns);
            assertTrue(warns.get(0).contains("sales_daily_report"), "要点名是哪个工具：" + warns.get(0));
            assertTrue(warns.get(0).contains("工具管理"), "要给出路：" + warns.get(0));
        } finally {
            releaseLogs(logs);
        }
    }

    @Test
    @DisplayName("两类都查不到的键 ⟹ 报错点名两个来源（此前只说「未登记」，把人引向错误的修法）")
    void publishDefById_unknownTool_messageNamesBothSources() {
        AgentDefDO draft = def(100L, "order_assistant", 1, AgentDefStatusEnum.DRAFT.getStatus());
        draft.setToolKeys("ghost_tool");
        when(defMapper.selectById(100L)).thenReturn(draft);
        stubCustomTool("sales_daily_report", ToolSourceEnum.DATA_INTERFACE.getSource(), "READ_ONLY", true);

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.publishDefById(100L));
        assertTrue(ex.getMessage().contains("内置工具注册表") && ex.getMessage().contains("工具管理"),
                "两处都查过了就都要说出来，管理员才知道该往哪儿看。实际：" + ex.getMessage());
    }

    // ==================== 需求10：系统内置行（默认平台助手）====================

    /**
     * 🔴 取内置行按 {@code builtin} 标记而非按 code。
     * 按 code 取会用 selectOne，一旦历史上多出一行同 code 的记录（比如有人绕过校验插过），
     * MyBatis 直接抛 TooManyResultsException —— 那会把<b>每一次</b>「未选 Agent」的对话打死。
     * 这里取 id 最小的那行，多出来也只用一行、绝不打死对话。
     */
    @Test
    void getPlatformAssistant_returnsBuiltinRow() {
        AgentDefDO builtin = builtinDef(1L);
        when(defMapper.selectList(any(SFunction.class), eq(true))).thenReturn(List.of(builtin));

        assertSame(builtin, defService.getPlatformAssistant());
    }

    @Test
    void getPlatformAssistant_multipleRows_picksSmallestId() {
        AgentDefDO first = builtinDef(1L);
        AgentDefDO second = builtinDef(9L);
        // 刻意乱序返回：断言的是「按 id 取最小」这个确定性规则，而不是「数据库碰巧排好了」
        when(defMapper.selectList(any(SFunction.class), eq(true))).thenReturn(List.of(second, first));

        assertSame(first, defService.getPlatformAssistant());
    }

    /**
     * 🔴 查不到必须返回 null 而不是抛异常。调用方 {@code AgentAssistantServiceImpl} 拿 null 去调
     * {@code AgentPromptBuilder}，后者回落到代码里的出厂提示词 —— 新环境没跑种子迁移只是少一行数据，
     * 绝不该让整个对话 500。
     */
    @Test
    void getPlatformAssistant_absent_returnsNullNotThrow() {
        when(defMapper.selectList(any(SFunction.class), eq(true))).thenReturn(List.of());

        assertNull(defService.getPlatformAssistant(), "查不到内置行 → null，由调用方回落到出厂提示词");
    }

    /**
     * 内置行虽为 PUBLISHED，却允许就地改 —— 这是需求10 的核心：让它可在管理页在线维护。
     * 🔴 但只认 name/persona/description 三列：modelId/knowledgeId/toolKeys 在「未选 Agent」这条路上
     * 根本不被读取，落库了就是「配了但不生效」，所以刻意不带进 update。
     */
    @Test
    void updateDef_builtin_updatesInPlaceWithThreeColumnsOnly() {
        when(defMapper.selectById(1L)).thenReturn(builtinDef(1L));
        AgentDefSaveReqVO reqVO = new AgentDefSaveReqVO();
        reqVO.setId(1L);
        reqVO.setName("平台助手");
        reqVO.setPersona("新人设");
        reqVO.setDescription("新描述");
        reqVO.setModelId(88L);      // 以下三项对内置行必须被忽略
        reqVO.setKnowledgeId(7L);
        reqVO.setToolKeys("ro_tool");

        defService.updateDef(reqVO);

        ArgumentCaptor<AgentDefDO> captor = ArgumentCaptor.forClass(AgentDefDO.class);
        verify(defMapper).updateById(captor.capture());
        AgentDefDO updated = captor.getValue();
        assertEquals(1L, updated.getId());
        assertEquals("平台助手", updated.getName());
        assertEquals("新人设", updated.getPersona());
        assertEquals("新描述", updated.getDescription());
        assertNull(updated.getModelId(), "内置行不绑模型：走平台动态默认（带 failover）");
        assertNull(updated.getKnowledgeId(), "内置行不绑知识库：固定检索平台内置手册");
        assertNull(updated.getToolKeys(), "内置行不启用工具循环");
        assertNull(updated.getStatus(), "就地改，不翻状态（它恒 PUBLISHED）");
        assertNull(updated.getVersion(), "内置行不参与版本化，version 不动");
    }

    @Test
    void disableDefById_builtin_throws() {
        when(defMapper.selectById(1L)).thenReturn(builtinDef(1L));

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.disableDefById(1L));
        assertEquals(AGENT_DEF_BUILTIN_READONLY.getCode(), ex.getCode(),
                "🔴 停用内置行 = 让所有「未选 Agent」的对话失去系统提示词来源，比删掉更隐蔽");
        verify(defMapper, never()).updateById(any(AgentDefDO.class));
    }

    @Test
    void deriveDef_builtin_throws() {
        when(defMapper.selectById(1L)).thenReturn(builtinDef(1L));

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.deriveDef(1L));
        assertEquals(AGENT_DEF_BUILTIN_READONLY.getCode(), ex.getCode(),
                "派生出的新版本 builtin=false，不会被 getPlatformAssistant 读到 —— 改了个寂寞，故直接拒");
        verify(defMapper, never()).insert(any(AgentDefDO.class));
    }

    /**
     * 🔴 保留 code 必须挡住：若允许用户新建一个 code=platform_assistant 的普通 Agent，
     * 库里就会有<b>两行</b>自称平台助手，而只有 builtin 那行真正驱动对话 —— 用户改了新那行，
     * 保存成功、毫无提示、对话纹丝不动。这是本平台头号缺陷的原样复刻，故在写入侧 fail-loud。
     */
    @Test
    void createDef_reservedCode_throws() {
        AgentDefSaveReqVO reqVO = new AgentDefSaveReqVO();
        reqVO.setCode(AgentDefDO.CODE_PLATFORM_ASSISTANT);
        reqVO.setName("我的平台助手");
        reqVO.setPersona("x");

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.createDef(reqVO));
        assertEquals(AGENT_DEF_CODE_EXISTS.getCode(), ex.getCode());
        verify(defMapper, never()).insert(any(AgentDefDO.class));
    }

    @Test
    void copyDef_reservedCode_throws() {
        when(defMapper.selectById(100L))
                .thenReturn(def(100L, "order_assistant", 1, AgentDefStatusEnum.PUBLISHED.getStatus()));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> defService.copyDef(100L, AgentDefDO.CODE_PLATFORM_ASSISTANT, "副本"));
        assertEquals(AGENT_DEF_CODE_EXISTS.getCode(), ex.getCode());
        verify(defMapper, never()).insert(any(AgentDefDO.class));
    }

    // ==================== 需求11：deriveDef（派生同 code 新版本）====================

    /**
     * 已发布行「编辑」的真实语义：不改原行，而是复制出一个同 code、version+1 的<b>草稿</b>。
     * 🔴 配置五项（persona/modelId/knowledgeId/toolKeys/description）必须整份搬过来 ——
     * 少搬一项，用户就会看到「我明明配了工具，派生出来的新版本却没有」。
     */
    @Test
    void deriveDef_published_createsDraftNextVersionWithFullConfig() {
        AgentDefDO source = def(100L, "order_assistant", 2, AgentDefStatusEnum.PUBLISHED.getStatus());
        source.setName("订单客服");
        source.setModelId(88L);
        source.setKnowledgeId(7L);
        source.setToolKeys("ro_tool,data_interface_list");
        source.setDescription("处理订单咨询");
        when(defMapper.selectById(100L)).thenReturn(source);
        when(defMapper.selectList(any(SFunction.class), eq("order_assistant"))).thenReturn(List.of(
                def(99L, "order_assistant", 1, AgentDefStatusEnum.DISABLED.getStatus()), source));
        stubInsertAssignsId(200L);

        Long newId = defService.deriveDef(100L);

        assertEquals(200L, newId, "返回新草稿的 id，前端拿它直接进编辑器");
        ArgumentCaptor<AgentDefDO> captor = ArgumentCaptor.forClass(AgentDefDO.class);
        verify(defMapper).insert(captor.capture());
        AgentDefDO draft = captor.getValue();
        assertEquals("order_assistant", draft.getCode(), "同 code —— 这是「同一个 Agent 的新版本」");
        assertEquals(3, draft.getVersion(), "现有最大版本 2 → 新版本 3");
        assertEquals(AgentDefStatusEnum.DRAFT.getStatus(), draft.getStatus(), "派生出来必须是草稿，不能直接生效");
        assertEquals("订单客服", draft.getName());
        assertEquals(source.getPersona(), draft.getPersona());
        assertEquals(88L, draft.getModelId());
        assertEquals(7L, draft.getKnowledgeId());
        assertEquals("ro_tool,data_interface_list", draft.getToolKeys());
        assertEquals("处理订单咨询", draft.getDescription());
        assertFalse(draft.getBuiltin(), "🔴 内置标记绝不能被继承");
        // 原行一个字节都不该动：派生是「加一行」，不是「改一行」
        verify(defMapper, never()).updateById(any(AgentDefDO.class));
    }

    /** DISABLED 行同样可派生：停用版不可重发（publishDefById 会拒），出个新版本是唯一出路 */
    @Test
    void deriveDef_disabled_createsDraft() {
        when(defMapper.selectById(100L))
                .thenReturn(def(100L, "order_assistant", 3, AgentDefStatusEnum.DISABLED.getStatus()));
        when(defMapper.selectList(any(SFunction.class), eq("order_assistant"))).thenReturn(List.of());
        stubInsertAssignsId(201L);

        defService.deriveDef(100L);

        ArgumentCaptor<AgentDefDO> captor = ArgumentCaptor.forClass(AgentDefDO.class);
        verify(defMapper).insert(captor.capture());
        assertEquals(AgentDefStatusEnum.DRAFT.getStatus(), captor.getValue().getStatus());
    }

    @Test
    void deriveDef_notExists_throws() {
        when(defMapper.selectById(999L)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class, () -> defService.deriveDef(999L));
        assertEquals(AGENT_DEF_NOT_EXISTS.getCode(), ex.getCode());
        verify(defMapper, never()).insert(any(AgentDefDO.class));
    }

    // ==================== 需求11：copyDef（复制成一个新 Agent）====================

    /**
     * 🔴 与 deriveDef 的分水岭就在 code：复制换新 code、version 从 1 起，是<b>另一个</b> Agent。
     * 搞混了会让副本变成源 Agent 的 v(n+1)，一发布就把源 Agent 顶掉 —— 用户以为自己在建新东西，
     * 实际改掉了线上正在用的那个。
     */
    @Test
    void copyDef_createsIndependentAgentWithVersionOne() {
        AgentDefDO source = def(100L, "order_assistant", 2, AgentDefStatusEnum.PUBLISHED.getStatus());
        source.setName("订单客服");
        source.setModelId(88L);
        source.setKnowledgeId(7L);
        source.setToolKeys("ro_tool");
        source.setDescription("处理订单咨询");
        when(defMapper.selectById(100L)).thenReturn(source);
        when(defMapper.selectList(any(SFunction.class), eq("order_assistant_v2"))).thenReturn(List.of());
        stubInsertAssignsId(300L);

        Long newId = defService.copyDef(100L, "order_assistant_v2", "订单客服（大促版）");

        assertEquals(300L, newId);
        ArgumentCaptor<AgentDefDO> captor = ArgumentCaptor.forClass(AgentDefDO.class);
        verify(defMapper).insert(captor.capture());
        AgentDefDO copy = captor.getValue();
        assertEquals("order_assistant_v2", copy.getCode(), "新 code —— 与源 Agent 从此各走各的版本线");
        assertEquals(1, copy.getVersion(), "新 code 下没有历史版本，故从 1 起（不是源的 version+1）");
        assertEquals(AgentDefStatusEnum.DRAFT.getStatus(), copy.getStatus(), "复制出来是草稿，需自行发布");
        assertEquals("订单客服（大促版）", copy.getName());
        assertEquals(88L, copy.getModelId(), "配置整份搬过来");
        assertEquals(7L, copy.getKnowledgeId());
        assertEquals("ro_tool", copy.getToolKeys());
        assertEquals("处理订单咨询", copy.getDescription());
        assertFalse(copy.getBuiltin());
    }

    /** 名称留空时自动补「（副本）」，让「不改名直接确定」这条路也能走通 */
    @Test
    void copyDef_blankName_appendsCopySuffix() {
        AgentDefDO source = def(100L, "order_assistant", 1, AgentDefStatusEnum.DRAFT.getStatus());
        source.setName("订单客服");
        when(defMapper.selectById(100L)).thenReturn(source);
        when(defMapper.selectList(any(SFunction.class), eq("order_assistant_copy"))).thenReturn(List.of());
        stubInsertAssignsId(301L);

        defService.copyDef(100L, "order_assistant_copy", "  ");

        ArgumentCaptor<AgentDefDO> captor = ArgumentCaptor.forClass(AgentDefDO.class);
        verify(defMapper).insert(captor.capture());
        assertEquals("订单客服（副本）", captor.getValue().getName());
    }

    /** code 撞车要 fail-loud：否则同 code 两行会共用一条版本线，谁是谁分不清 */
    @Test
    void copyDef_codeExists_throws() {
        when(defMapper.selectById(100L))
                .thenReturn(def(100L, "order_assistant", 1, AgentDefStatusEnum.DRAFT.getStatus()));
        when(defMapper.selectList(any(SFunction.class), eq("order_assistant")))
                .thenReturn(List.of(def(100L, "order_assistant", 1, AgentDefStatusEnum.DRAFT.getStatus())));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> defService.copyDef(100L, "order_assistant", "副本"));
        assertEquals(AGENT_DEF_CODE_EXISTS.getCode(), ex.getCode());
        verify(defMapper, never()).insert(any(AgentDefDO.class));
    }

    @Test
    void copyDef_notExists_throws() {
        when(defMapper.selectById(999L)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> defService.copyDef(999L, "whatever_code", "副本"));
        assertEquals(AGENT_DEF_NOT_EXISTS.getCode(), ex.getCode());
    }

}

