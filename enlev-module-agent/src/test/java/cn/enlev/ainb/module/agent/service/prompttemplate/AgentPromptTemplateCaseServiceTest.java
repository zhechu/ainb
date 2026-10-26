package cn.enlev.ainb.module.agent.service.prompttemplate;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.controller.admin.prompttemplate.vo.AgentPromptTemplateCasePageReqVO;
import cn.enlev.ainb.module.agent.controller.admin.prompttemplate.vo.AgentPromptTemplateCaseSaveReqVO;
import cn.enlev.ainb.module.agent.dal.dataobject.prompttemplate.AgentPromptTemplateCaseDO;
import cn.enlev.ainb.module.agent.dal.dataobject.prompttemplate.AgentPromptTemplateDO;
import cn.enlev.ainb.module.agent.dal.mysql.prompttemplate.AgentPromptTemplateCaseMapper;
import cn.enlev.ainb.module.agent.dal.mysql.prompttemplate.AgentPromptTemplateMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.List;

import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_PROMPT_TEMPLATE_CASE_INTENT_INVALID;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_PROMPT_TEMPLATE_CASE_NOT_EXISTS;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_PROMPT_TEMPLATE_CASE_TEMPLATE_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 提示词模板<b>用例</b> Service 的单元测试（S7 / L5）
 *
 * <p>mock 两个 mapper、不连库。用例表在 CRUD 之外只多做两件事，测试的重心也就在这两件事上：
 *
 * <ul>
 *   <li><b>写入前校验模板存在</b>：{@code template_id} 是逻辑外键、库里没有 {@code FOREIGN KEY}。
 *       放过一个不存在的 id，这条用例就变成「哪儿都看不见但确实存在」—— 创建接口返回成功、
 *       分页接口数得出它，管理页的用例子表（按 templateId 查）里没有它，聊天面板也不显示。
 *       这正是本平台头号缺陷「保存成功但不生效且无提示」的形状，只能在写入口拦。</li>
 *   <li><b>可见性同时取决于用例自己和它模板的开关</b>：{@code getEnabledCaseList} 后半句是它
 *       存在的理由。模板被关闭意为「这条提问暂时下架」，此时它的示例若还挂在聊天面板上，
 *       用户照着发出去，走的却是一条管理员刚刚决定不再引导的路 —— 而两个接口都是绿的。</li>
 * </ul>
 *
 * <p>{@code expectIntent} 的归一化单独钉住：验收脚本按这一列挑用例真发一次，
 * 一个认不出的值会让它静默跳过这条用例，看起来「全部通过」，实际一条都没验。
 *
 * @author 走进新时代
 */
public class AgentPromptTemplateCaseServiceTest extends BaseMockitoUnitTest {

    @InjectMocks
    private AgentPromptTemplateCaseServiceImpl service;

    @Mock
    private AgentPromptTemplateCaseMapper caseMapper;

    @Mock
    private AgentPromptTemplateMapper promptTemplateMapper;

    /** insert 时回填自增 id，模拟 MyBatis-Plus 的行为（Service 靠它返回新 id） */
    private void stubInsertAssignsId(long id) {
        doAnswer(invocation -> {
            AgentPromptTemplateCaseDO arg = invocation.getArgument(0);
            arg.setId(id);
            return 1;
        }).when(caseMapper).insert(any(AgentPromptTemplateCaseDO.class));
    }

    private AgentPromptTemplateCaseSaveReqVO saveReqVO(Long templateId, String title, String content) {
        AgentPromptTemplateCaseSaveReqVO reqVO = new AgentPromptTemplateCaseSaveReqVO();
        reqVO.setTemplateId(templateId);
        reqVO.setTitle(title);
        reqVO.setContent(content);
        return reqVO;
    }

    private AgentPromptTemplateCaseDO existingCase(Long id, Long templateId, Integer status) {
        AgentPromptTemplateCaseDO entity = new AgentPromptTemplateCaseDO();
        entity.setId(id);
        entity.setTemplateId(templateId);
        entity.setTitle("已有用例");
        entity.setContent("正文");
        entity.setStatus(status);
        entity.setSort(0);
        return entity;
    }

    private AgentPromptTemplateDO template(Long id, Integer status) {
        AgentPromptTemplateDO template = new AgentPromptTemplateDO();
        template.setId(id);
        template.setName("模板 " + id);
        template.setContent("骨架 {{占位符}}");
        template.setStatus(status);
        template.setSort(0);
        return template;
    }

    private AgentPromptTemplateCaseDO inserted() {
        ArgumentCaptor<AgentPromptTemplateCaseDO> captor = ArgumentCaptor.forClass(AgentPromptTemplateCaseDO.class);
        verify(caseMapper).insert(captor.capture());
        return captor.getValue();
    }

    // ==================== 新增：模板存在性校验 ====================

    @Test
    @DisplayName("新增：挂到不存在的模板上（含他租户的模板）时报错，且不落库")
    public void testCreateWithMissingTemplateThrows() {
        when(promptTemplateMapper.selectById(999L)).thenReturn(null);
        AgentPromptTemplateCaseSaveReqVO reqVO = saveReqVO(999L, "用例", "正文");

        ServiceException ex = assertThrows(ServiceException.class, () -> service.createCase(reqVO));

        assertEquals(AGENT_PROMPT_TEMPLATE_CASE_TEMPLATE_NOT_EXISTS.getCode(), ex.getCode());
        // 🔴 这条 never 是本用例的要害：孤儿用例一旦落库就没有任何页面入口能点到它，
        //    只能靠 SQL 清理。拦不住的代价远大于报错文案不够好看
        verify(caseMapper, never()).insert(any(AgentPromptTemplateCaseDO.class));
    }

    @Test
    @DisplayName("新增：templateId 为空同样报「模板不存在」，而不是落一条 template_id 为 NULL 的行")
    public void testCreateWithNullTemplateIdThrows() {
        AgentPromptTemplateCaseSaveReqVO reqVO = saveReqVO(null, "用例", "正文");

        ServiceException ex = assertThrows(ServiceException.class, () -> service.createCase(reqVO));

        assertEquals(AGENT_PROMPT_TEMPLATE_CASE_TEMPLATE_NOT_EXISTS.getCode(), ex.getCode());
        verify(caseMapper, never()).insert(any(AgentPromptTemplateCaseDO.class));
    }

    @Test
    @DisplayName("新增：模板存在则照存（该放的要放，不能把校验做成一刀切拒绝）")
    public void testCreateWithExistingTemplateSucceeds() {
        stubInsertAssignsId(500L);
        when(promptTemplateMapper.selectById(1L)).thenReturn(template(1L, CommonStatusEnum.ENABLE.getStatus()));
        AgentPromptTemplateCaseSaveReqVO reqVO = saveReqVO(1L, "设备报修单，两级审批", "请帮我看看 agent_device_repair 这张表");

        Long id = service.createCase(reqVO);

        assertEquals(500L, id, "返回的是回填后的自增 id");
        assertEquals(1L, inserted().getTemplateId());
    }

    @Test
    @DisplayName("新增：模板处于关闭态也允许挂用例（关闭只是暂时下架，管理页仍要能编辑它的示例）")
    public void testCreateUnderDisabledTemplateSucceeds() {
        stubInsertAssignsId(501L);
        when(promptTemplateMapper.selectById(2L)).thenReturn(template(2L, CommonStatusEnum.DISABLE.getStatus()));

        service.createCase(saveReqVO(2L, "用例", "正文"));

        assertEquals(2L, inserted().getTemplateId());
    }

    // ==================== 新增：默认值与归一化 ====================

    @Test
    @DisplayName("新增：状态与排序留空时兜底为「开启 / 0」，绝不落 NULL")
    public void testCreateAppliesDefaults() {
        stubInsertAssignsId(502L);
        when(promptTemplateMapper.selectById(1L)).thenReturn(template(1L, CommonStatusEnum.ENABLE.getStatus()));
        // 刻意不 setStatus / setSort
        service.createCase(saveReqVO(1L, "标题", "正文"));

        AgentPromptTemplateCaseDO saved = inserted();
        // 🔴 status 落 NULL 时聊天面板按 status=0 过滤永远查不到它：
        //    「新建成功了却在助手里找不到」，且没有任何报错
        assertEquals(CommonStatusEnum.ENABLE.getStatus(), saved.getStatus(), "状态缺省必须是开启");
        assertEquals(0, saved.getSort(), "排序缺省必须是 0");
    }

    @Test
    @DisplayName("新增：显式传了关闭态就照存（关闭态用例是正常用法，不能被兜底改成开启）")
    public void testCreateKeepsGivenStatusAndSort() {
        stubInsertAssignsId(503L);
        when(promptTemplateMapper.selectById(1L)).thenReturn(template(1L, CommonStatusEnum.ENABLE.getStatus()));
        AgentPromptTemplateCaseSaveReqVO reqVO = saveReqVO(1L, "待定的用例", "正文");
        reqVO.setStatus(CommonStatusEnum.DISABLE.getStatus());
        reqVO.setSort(70);

        service.createCase(reqVO);

        assertEquals(CommonStatusEnum.DISABLE.getStatus(), inserted().getStatus());
        assertEquals(70, inserted().getSort());
    }

    @Test
    @DisplayName("新增：标题去首尾空白、空白期望要点归一成 null，但正文一字不动")
    public void testCreateTrimsTitleAndNullifiesBlankHint() {
        stubInsertAssignsId(504L);
        when(promptTemplateMapper.selectById(1L)).thenReturn(template(1L, CommonStatusEnum.ENABLE.getStatus()));
        AgentPromptTemplateCaseSaveReqVO reqVO = saveReqVO(1L, "  带空白的标题  ", "\n请查一下 agent_device_repair 表的建表语句\n");
        reqVO.setExpectHint("   ");

        service.createCase(reqVO);

        AgentPromptTemplateCaseDO saved = inserted();
        assertEquals("带空白的标题", saved.getTitle(), "标题是列表里显示的一行字，首尾空白没有意义");
        assertNull(saved.getExpectHint(), "空白期望要点必须归一成 null");
        // 🔴 正文刻意不 trim：它是要被原样插进聊天输入框、然后原样发出去的那段话，
        //    e2e 直接断言「输入框里的字符串 == content 这一列」。首尾空行可能是有意的排版
        assertEquals("\n请查一下 agent_device_repair 表的建表语句\n", saved.getContent());
    }

    // ==================== 新增：expectIntent 归一化 ====================

    @Test
    @DisplayName("期望分型：小写 build 归一成大写 BUILD（与分类器的 parse 同款容忍度）")
    public void testCreateNormalizesIntentCase() {
        stubInsertAssignsId(505L);
        when(promptTemplateMapper.selectById(1L)).thenReturn(template(1L, CommonStatusEnum.ENABLE.getStatus()));
        AgentPromptTemplateCaseSaveReqVO reqVO = saveReqVO(1L, "标题", "正文");
        reqVO.setExpectIntent("build");

        service.createCase(reqVO);

        assertEquals("BUILD", inserted().getExpectIntent());
    }

    @Test
    @DisplayName("期望分型：留空 / 空白 / null 一律存成 null —— 空是有意义的第三态「两种分型都算对」")
    public void testCreateBlankIntentBecomesNull() {
        stubInsertAssignsId(506L);
        when(promptTemplateMapper.selectById(1L)).thenReturn(template(1L, CommonStatusEnum.ENABLE.getStatus()));

        AgentPromptTemplateCaseSaveReqVO blank = saveReqVO(1L, "标题", "正文");
        blank.setExpectIntent("   ");
        service.createCase(blank);
        assertNull(inserted().getExpectIntent(), "空白必须归一成 null，不能存成空串");
    }

    @Test
    @DisplayName("期望分型：认不出的值直接拒绝，报错里回显用户填的原值")
    public void testCreateRejectsUnknownIntent() {
        when(promptTemplateMapper.selectById(1L)).thenReturn(template(1L, CommonStatusEnum.ENABLE.getStatus()));
        AgentPromptTemplateCaseSaveReqVO reqVO = saveReqVO(1L, "标题", "正文");
        reqVO.setExpectIntent(" agent ");

        ServiceException ex = assertThrows(ServiceException.class, () -> service.createCase(reqVO));

        assertEquals(AGENT_PROMPT_TEMPLATE_CASE_INTENT_INVALID.getCode(), ex.getCode());
        // 🔴 回显原值而不是归一化后的 "AGENT"，否则用户对着自己填的 " agent " 看不出错在哪
        assertTrue(ex.getMessage().contains(" agent "),
                "报错应回显用户填的原值，实际是：" + ex.getMessage());
        assertFalse(ex.getMessage().contains("AGENT"),
                "报错不该回显归一化后的值，实际是：" + ex.getMessage());
        verify(caseMapper, never()).insert(any(AgentPromptTemplateCaseDO.class));
    }

    @Test
    @DisplayName("期望分型：中文别名「变更」也算认不出（验收脚本靠这一列挑用例，模糊值会让它静默跳过）")
    public void testCreateRejectsChineseAlias() {
        when(promptTemplateMapper.selectById(1L)).thenReturn(template(1L, CommonStatusEnum.ENABLE.getStatus()));
        AgentPromptTemplateCaseSaveReqVO reqVO = saveReqVO(1L, "标题", "正文");
        // AgentIntentEnum.BUILD 的 description 正是「变更」，很容易被人当成合法值填进去
        reqVO.setExpectIntent("变更");

        ServiceException ex = assertThrows(ServiceException.class, () -> service.createCase(reqVO));

        assertEquals(AGENT_PROMPT_TEMPLATE_CASE_INTENT_INVALID.getCode(), ex.getCode());
    }

    // ==================== 修改 ====================

    @Test
    @DisplayName("修改：用例不存在（含他租户的行）时报不存在，且不落库")
    public void testUpdateNotExistsThrows() {
        when(caseMapper.selectById(999L)).thenReturn(null);
        AgentPromptTemplateCaseSaveReqVO reqVO = saveReqVO(1L, "新标题", "新正文");
        reqVO.setId(999L);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.updateCase(reqVO));

        assertEquals(AGENT_PROMPT_TEMPLATE_CASE_NOT_EXISTS.getCode(), ex.getCode());
        verify(caseMapper, never()).updateById(any(AgentPromptTemplateCaseDO.class));
    }

    @Test
    @DisplayName("修改：用例本身就不存在时先报「用例不存在」，压根不去查模板")
    public void testUpdateValidatesCaseBeforeTemplate() {
        when(caseMapper.selectById(999L)).thenReturn(null);
        AgentPromptTemplateCaseSaveReqVO reqVO = saveReqVO(888L, "新标题", "新正文");
        reqVO.setId(999L);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.updateCase(reqVO));

        // 钉住校验顺序：用户的心智模型是「我在编辑这条用例」，
        // 先报「模板不存在」会把他引到完全无关的方向上去查。
        // 🔴 这里刻意不给 promptTemplateMapper 打桩，而是断言它没被调用 ——
        //    打了桩反而会撞 MockitoExtension 的 STRICT_STUBS（桩没被用到），
        //    报错信息会指向「多余的桩」而不是「顺序错了」
        assertEquals(AGENT_PROMPT_TEMPLATE_CASE_NOT_EXISTS.getCode(), ex.getCode());
        verify(promptTemplateMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("修改：id 为空同样报不存在，而不是被当成新增")
    public void testUpdateNullIdThrows() {
        AgentPromptTemplateCaseSaveReqVO reqVO = saveReqVO(1L, "标题", "正文");
        // 刻意不 setId

        ServiceException ex = assertThrows(ServiceException.class, () -> service.updateCase(reqVO));

        assertEquals(AGENT_PROMPT_TEMPLATE_CASE_NOT_EXISTS.getCode(), ex.getCode());
        verify(caseMapper, never()).insert(any(AgentPromptTemplateCaseDO.class));
    }

    @Test
    @DisplayName("修改：改挂到一个不存在的模板上也要拒（否则编辑时手滑改 templateId 同样能造出孤儿）")
    public void testUpdateRejectsMissingTemplate() {
        when(caseMapper.selectById(1L)).thenReturn(existingCase(1L, 1L, CommonStatusEnum.ENABLE.getStatus()));
        when(promptTemplateMapper.selectById(888L)).thenReturn(null);
        AgentPromptTemplateCaseSaveReqVO reqVO = saveReqVO(888L, "新标题", "新正文");
        reqVO.setId(1L);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.updateCase(reqVO));

        assertEquals(AGENT_PROMPT_TEMPLATE_CASE_TEMPLATE_NOT_EXISTS.getCode(), ex.getCode());
        verify(caseMapper, never()).updateById(any(AgentPromptTemplateCaseDO.class));
    }

    @Test
    @DisplayName("修改：存在则按提交值整份更新，同样兜底状态与排序")
    public void testUpdateAppliesDefaults() {
        when(caseMapper.selectById(1L)).thenReturn(existingCase(1L, 1L, CommonStatusEnum.ENABLE.getStatus()));
        when(promptTemplateMapper.selectById(1L)).thenReturn(template(1L, CommonStatusEnum.ENABLE.getStatus()));
        AgentPromptTemplateCaseSaveReqVO reqVO = saveReqVO(1L, "改过的标题", "改过的正文");
        reqVO.setId(1L);
        // 刻意不传 status / sort：前端漏传时不该把已开启的用例改成 NULL（=从聊天面板消失）

        service.updateCase(reqVO);

        ArgumentCaptor<AgentPromptTemplateCaseDO> captor = ArgumentCaptor.forClass(AgentPromptTemplateCaseDO.class);
        verify(caseMapper).updateById(captor.capture());
        AgentPromptTemplateCaseDO updated = captor.getValue();
        assertEquals(1L, updated.getId());
        assertEquals("改过的标题", updated.getTitle());
        assertEquals(CommonStatusEnum.ENABLE.getStatus(), updated.getStatus(), "漏传状态时兜底为开启，不能落 NULL 让用例从助手里消失");
        assertEquals(0, updated.getSort());
    }

    // ==================== 删除 ====================

    @Test
    @DisplayName("删除：不存在时报错且不删（防止 deleteById(null) 这类静默无效操作）")
    public void testDeleteNotExistsThrows() {
        when(caseMapper.selectById(999L)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.deleteCase(999L));

        assertEquals(AGENT_PROMPT_TEMPLATE_CASE_NOT_EXISTS.getCode(), ex.getCode());
        verify(caseMapper, never()).deleteById(any(Long.class));
    }

    @Test
    @DisplayName("删除：id 为空同样报不存在")
    public void testDeleteNullIdThrows() {
        ServiceException ex = assertThrows(ServiceException.class, () -> service.deleteCase(null));

        assertEquals(AGENT_PROMPT_TEMPLATE_CASE_NOT_EXISTS.getCode(), ex.getCode());
        verify(caseMapper, never()).deleteById(any(Long.class));
    }

    @Test
    @DisplayName("删除：存在则删")
    public void testDeleteDeletes() {
        when(caseMapper.selectById(1L)).thenReturn(existingCase(1L, 1L, CommonStatusEnum.ENABLE.getStatus()));

        service.deleteCase(1L);

        verify(caseMapper).deleteById(1L);
    }

    // ==================== 查询 ====================

    @Test
    @DisplayName("详情：不存在时报错（而不是返回 null 让前端渲染成空表单）")
    public void testGetNotExistsThrows() {
        when(caseMapper.selectById(999L)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.getCase(999L));

        assertEquals(AGENT_PROMPT_TEMPLATE_CASE_NOT_EXISTS.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("详情：存在则原样返回")
    public void testGetReturns() {
        AgentPromptTemplateCaseDO entity = existingCase(1L, 1L, CommonStatusEnum.ENABLE.getStatus());
        when(caseMapper.selectById(1L)).thenReturn(entity);

        assertSame(entity, service.getCase(1L));
    }

    @Test
    @DisplayName("分页：直接委托 mapper（筛选与排序全在 LambdaQueryWrapperX 里，Service 不加料）")
    public void testGetPageDelegates() {
        AgentPromptTemplateCasePageReqVO reqVO = new AgentPromptTemplateCasePageReqVO();
        reqVO.setPageNo(2);
        reqVO.setPageSize(20);
        reqVO.setTemplateId(1L);
        PageResult<AgentPromptTemplateCaseDO> expected =
                new PageResult<>(List.of(existingCase(1L, 1L, CommonStatusEnum.ENABLE.getStatus())), 21L);
        when(caseMapper.selectPage(reqVO)).thenReturn(expected);

        assertSame(expected, service.getCasePage(reqVO));
    }

    @Test
    @DisplayName("某模板下的用例子表：直接委托 mapper（含关闭态，管理页要能重新打开一条下架的用例）")
    public void testGetListByTemplateIdDelegates() {
        List<AgentPromptTemplateCaseDO> expected = List.of(
                existingCase(1L, 1L, CommonStatusEnum.ENABLE.getStatus()),
                existingCase(2L, 1L, CommonStatusEnum.DISABLE.getStatus()));
        when(caseMapper.selectListByTemplateId(1L)).thenReturn(expected);

        assertSame(expected, service.getCaseListByTemplateId(1L));
    }

    // ==================== 聊天面板列表：用例可见性 ====================

    @Test
    @DisplayName("聊天面板列表：模板被关掉时它的用例一起消失，模板开着的用例照原顺序返回（双向断言）")
    public void testGetEnabledCaseListDropsCasesOfDisabledTemplates() {
        // 模板 1 开着（模板 2 关着，故不在 selectEnabledList 的结果里）。
        // 用例 10/11 挂在模板 1 下，用例 20 挂在模板 2 下，三条自己都是开启态
        when(promptTemplateMapper.selectEnabledList()).thenReturn(List.of(
                template(1L, CommonStatusEnum.ENABLE.getStatus())));
        AgentPromptTemplateCaseDO keptA = existingCase(10L, 1L, CommonStatusEnum.ENABLE.getStatus());
        AgentPromptTemplateCaseDO dropped = existingCase(20L, 2L, CommonStatusEnum.ENABLE.getStatus());
        AgentPromptTemplateCaseDO keptB = existingCase(11L, 1L, CommonStatusEnum.ENABLE.getStatus());
        when(caseMapper.selectEnabledList()).thenReturn(List.of(keptA, dropped, keptB));

        List<AgentPromptTemplateCaseDO> visible = service.getEnabledCaseList();

        // 🔴 该拒的拒了：模板关闭 = 这条提问「暂时下架」，它的示例不能还挂在聊天面板上，
        //    否则用户照着发出去，走的是一条管理员刚决定不再引导的路 —— 而两边接口都是绿的
        assertEquals(2, visible.size(), "被下架模板的用例必须一起消失");
        assertFalse(visible.contains(dropped), "模板已关闭，它的用例不该出现在聊天面板");
        // 该放的放了：模板开启，它的用例原样、原序返回（assertSame 而不是 equals，
        // 免得 DO 的 equals 语义变化把这条断言悄悄放宽）
        assertSame(keptA, visible.get(0), "应保持 mapper 给出的顺序，Service 不重排");
        assertSame(keptB, visible.get(1));
    }

    @Test
    @DisplayName("聊天面板列表：一条模板都没开时返回空列表，而不是 null")
    public void testGetEnabledCaseListWhenNoTemplateEnabled() {
        when(promptTemplateMapper.selectEnabledList()).thenReturn(List.of());
        when(caseMapper.selectEnabledList()).thenReturn(List.of(
                existingCase(10L, 1L, CommonStatusEnum.ENABLE.getStatus())));

        List<AgentPromptTemplateCaseDO> visible = service.getEnabledCaseList();

        assertEquals(List.of(), visible, "没有开启的模板就没有可展示的用例，但不能返回 null 让前端炸");
    }

    @Test
    @DisplayName("聊天面板列表：库里一条用例都没有时返回空列表")
    public void testGetEnabledCaseListWhenEmpty() {
        when(promptTemplateMapper.selectEnabledList()).thenReturn(List.of(
                template(1L, CommonStatusEnum.ENABLE.getStatus())));
        when(caseMapper.selectEnabledList()).thenReturn(List.of());

        assertEquals(List.of(), service.getEnabledCaseList());
    }

}
