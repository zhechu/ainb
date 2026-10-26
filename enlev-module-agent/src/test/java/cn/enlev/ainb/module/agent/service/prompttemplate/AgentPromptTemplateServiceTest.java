package cn.enlev.ainb.module.agent.service.prompttemplate;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.controller.admin.prompttemplate.vo.AgentPromptTemplatePageReqVO;
import cn.enlev.ainb.module.agent.controller.admin.prompttemplate.vo.AgentPromptTemplateSaveReqVO;
import cn.enlev.ainb.module.agent.dal.dataobject.prompttemplate.AgentPromptTemplateDO;
import cn.enlev.ainb.module.agent.dal.mysql.prompttemplate.AgentPromptTemplateCaseMapper;
import cn.enlev.ainb.module.agent.dal.mysql.prompttemplate.AgentPromptTemplateMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;

import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_PROMPT_TEMPLATE_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 提示词模板 Service 的单元测试（需求7）
 *
 * <p>mock mapper、不连库。这个 Service 除了 CRUD 只管一件跨表的事（删模板时连带删它的用例），
 * 故测试的重心不在「有没有存进去」，而在下面三件事上：
 *
 * <ul>
 *   <li><b>默认值兜底</b>：{@code status} 落 NULL 的话，聊天面板按 {@code status=0} 过滤永远查不到它 ——
 *       「新建成功了却在助手里找不到」，且没有任何报错。这是本平台头号缺陷的形状，必须钉住。</li>
 *   <li><b>不存在的拒绝</b>：本表是 {@code TenantBaseDO}，{@code selectById} 自带租户过滤，
 *       所以「他租户的行」与「真不存在」走同一条 {@code AGENT_PROMPT_TEMPLATE_NOT_EXISTS}，
 *       不泄漏「该 id 在别的租户存在」。跨租户改/删必须被这条挡住，而不是静默影响 0 行。</li>
 *   <li><b>删模板要连带删它的用例</b>（S7 起模板下挂了用例表）：留着就是一批指向已删模板的行 ——
 *       管理页的用例子表查不到它们、聊天面板也不再显示，分页接口却还数得出来，
 *       表现为「明明有 45 条，页面上只找得到 30 条」。且必须与删模板同一个事务。</li>
 * </ul>
 *
 * @author 走进新时代
 */
public class AgentPromptTemplateServiceTest extends BaseMockitoUnitTest {

    @InjectMocks
    private AgentPromptTemplateServiceImpl service;

    @Mock
    private AgentPromptTemplateMapper promptTemplateMapper;

    /**
     * 用例表的 Mapper。删模板时要连带删它的用例，故 Service 直接持有 Mapper 而不是用例 Service
     * —— 用例 Service 反过来要读模板（校验模板存在、过滤下架模板的用例），
     * 注入 Service 会形成循环依赖。字段名与 Impl 里保持一致，@InjectMocks 按类型注入。
     */
    @Mock
    private AgentPromptTemplateCaseMapper promptTemplateCaseMapper;

    /** insert 时回填自增 id，模拟 MyBatis-Plus 的行为（Service 靠它返回新 id） */
    private void stubInsertAssignsId(long id) {
        doAnswer(invocation -> {
            AgentPromptTemplateDO arg = invocation.getArgument(0);
            arg.setId(id);
            return 1;
        }).when(promptTemplateMapper).insert(any(AgentPromptTemplateDO.class));
    }

    private AgentPromptTemplateSaveReqVO saveReqVO(String name, String content) {
        AgentPromptTemplateSaveReqVO reqVO = new AgentPromptTemplateSaveReqVO();
        reqVO.setName(name);
        reqVO.setContent(content);
        return reqVO;
    }

    private AgentPromptTemplateDO existing(Long id, String category) {
        AgentPromptTemplateDO template = new AgentPromptTemplateDO();
        template.setId(id);
        template.setName("已有模板");
        template.setContent("正文");
        template.setCategory(category);
        template.setStatus(CommonStatusEnum.ENABLE.getStatus());
        template.setSort(0);
        return template;
    }

    // ==================== 新增 ====================

    @Test
    @DisplayName("新增：状态与排序留空时兜底为「开启 / 0」，绝不落 NULL")
    public void testCreateAppliesDefaults() {
        stubInsertAssignsId(100L);
        AgentPromptTemplateSaveReqVO reqVO = saveReqVO("追问来龙去脉", "请查一下 {{订单号}} 为什么没发货");
        // 刻意不 setStatus / setSort

        Long id = service.createPromptTemplate(reqVO);

        assertEquals(100L, id, "返回的是回填后的自增 id");
        ArgumentCaptor<AgentPromptTemplateDO> captor = ArgumentCaptor.forClass(AgentPromptTemplateDO.class);
        verify(promptTemplateMapper).insert(captor.capture());
        AgentPromptTemplateDO inserted = captor.getValue();
        // 🔴 这两条是本类最要紧的断言：status 落 NULL 时聊天面板按 status=0 过滤，
        //    模板「存进去了但永远不出现在助手里」，且不报错
        assertEquals(CommonStatusEnum.ENABLE.getStatus(), inserted.getStatus(), "状态缺省必须是开启");
        assertEquals(0, inserted.getSort(), "排序缺省必须是 0");
        assertEquals("追问来龙去脉", inserted.getName());
        assertEquals("请查一下 {{订单号}} 为什么没发货", inserted.getContent(), "正文原样存，不做 trim（首尾空行可能是有意的排版）");
    }

    @Test
    @DisplayName("新增：文本列去首尾空白，空白分类与说明归一成 null 而非空串")
    public void testCreateTrimsAndNullifiesBlanks() {
        stubInsertAssignsId(101L);
        AgentPromptTemplateSaveReqVO reqVO = saveReqVO("  带空白的名称  ", "正文");
        reqVO.setCategory("   ");
        reqVO.setDescription("  ");

        service.createPromptTemplate(reqVO);

        ArgumentCaptor<AgentPromptTemplateDO> captor = ArgumentCaptor.forClass(AgentPromptTemplateDO.class);
        verify(promptTemplateMapper).insert(captor.capture());
        AgentPromptTemplateDO inserted = captor.getValue();
        assertEquals("带空白的名称", inserted.getName());
        // 🔴 归一成 null 而不是留空串：前端「未分类」的判定与 mapper 的分类候选查询都按 null 走，
        //    混进空串会让分类下拉里多出一个空白项
        assertNull(inserted.getCategory(), "空白分类必须归一成 null");
        assertNull(inserted.getDescription(), "空白说明必须归一成 null");
    }

    @Test
    @DisplayName("新增：显式传了状态与排序就照传的值存（关闭态模板是正常用法，不能被兜底改成开启）")
    public void testCreateKeepsGivenStatusAndSort() {
        stubInsertAssignsId(102L);
        AgentPromptTemplateSaveReqVO reqVO = saveReqVO("待定的模板", "正文");
        reqVO.setStatus(CommonStatusEnum.DISABLE.getStatus());
        reqVO.setSort(70);
        reqVO.setCategory("数据查询");

        service.createPromptTemplate(reqVO);

        ArgumentCaptor<AgentPromptTemplateDO> captor = ArgumentCaptor.forClass(AgentPromptTemplateDO.class);
        verify(promptTemplateMapper).insert(captor.capture());
        assertEquals(CommonStatusEnum.DISABLE.getStatus(), captor.getValue().getStatus());
        assertEquals(70, captor.getValue().getSort());
        assertEquals("数据查询", captor.getValue().getCategory());
    }

    // ==================== 修改 ====================

    @Test
    @DisplayName("修改：模板不存在（含他租户的行）时报不存在，且不落库")
    public void testUpdateNotExistsThrows() {
        when(promptTemplateMapper.selectById(999L)).thenReturn(null);
        AgentPromptTemplateSaveReqVO reqVO = saveReqVO("新名字", "新正文");
        reqVO.setId(999L);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.updatePromptTemplate(reqVO));

        assertEquals(AGENT_PROMPT_TEMPLATE_NOT_EXISTS.getCode(), ex.getCode());
        verify(promptTemplateMapper, never()).updateById(any(AgentPromptTemplateDO.class));
    }

    @Test
    @DisplayName("修改：id 为空同样报不存在，而不是被当成新增")
    public void testUpdateNullIdThrows() {
        AgentPromptTemplateSaveReqVO reqVO = saveReqVO("名字", "正文");
        // 刻意不 setId

        ServiceException ex = assertThrows(ServiceException.class, () -> service.updatePromptTemplate(reqVO));

        assertEquals(AGENT_PROMPT_TEMPLATE_NOT_EXISTS.getCode(), ex.getCode());
        verify(promptTemplateMapper, never()).insert(any(AgentPromptTemplateDO.class));
    }

    @Test
    @DisplayName("修改：存在则按提交值整份更新，同样兜底状态与排序")
    public void testUpdateAppliesDefaults() {
        when(promptTemplateMapper.selectById(1L)).thenReturn(existing(1L, "平台使用"));
        AgentPromptTemplateSaveReqVO reqVO = saveReqVO("改过的名字", "改过的正文");
        reqVO.setId(1L);
        // 刻意不传 status / sort：前端漏传时不该把已开启的模板改成 NULL（=从聊天面板消失）

        service.updatePromptTemplate(reqVO);

        ArgumentCaptor<AgentPromptTemplateDO> captor = ArgumentCaptor.forClass(AgentPromptTemplateDO.class);
        verify(promptTemplateMapper).updateById(captor.capture());
        AgentPromptTemplateDO updated = captor.getValue();
        assertEquals(1L, updated.getId());
        assertEquals("改过的名字", updated.getName());
        assertEquals(CommonStatusEnum.ENABLE.getStatus(), updated.getStatus(), "漏传状态时兜底为开启，不能落 NULL 让模板从助手里消失");
        assertEquals(0, updated.getSort());
    }

    // ==================== 删除 ====================

    @Test
    @DisplayName("删除：不存在时报错且不删（防止 deleteById(null) 这类静默无效操作）")
    public void testDeleteNotExistsThrows() {
        when(promptTemplateMapper.selectById(999L)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.deletePromptTemplate(999L));

        assertEquals(AGENT_PROMPT_TEMPLATE_NOT_EXISTS.getCode(), ex.getCode());
        verify(promptTemplateMapper, never()).deleteById(any(Long.class));
        // 模板都没删，就更不该去动它名下的用例
        verify(promptTemplateCaseMapper, never()).deleteByTemplateId(any(Long.class));
    }

    @Test
    @DisplayName("删除：id 为空同样报不存在")
    public void testDeleteNullIdThrows() {
        ServiceException ex = assertThrows(ServiceException.class, () -> service.deletePromptTemplate(null));

        assertEquals(AGENT_PROMPT_TEMPLATE_NOT_EXISTS.getCode(), ex.getCode());
        verify(promptTemplateMapper, never()).deleteById(any(Long.class));
        verify(promptTemplateCaseMapper, never()).deleteByTemplateId(any(Long.class));
    }

    @Test
    @DisplayName("删除：存在则删，并连带删掉它名下的用例")
    public void testDeleteDeletes() {
        when(promptTemplateMapper.selectById(1L)).thenReturn(existing(1L, "平台使用"));
        when(promptTemplateCaseMapper.deleteByTemplateId(1L)).thenReturn(3);

        service.deletePromptTemplate(1L);

        verify(promptTemplateMapper).deleteById(1L);
        // 🔴 这条连带删是本类第三件要紧的事：漏了它就是一批指向已删模板的用例 ——
        //    管理页的用例子表（按 templateId 查）里没有它们、聊天面板也不显示，
        //    分页接口却还数得出来，表现为「明明有 45 条，页面上只找得到 30 条」
        verify(promptTemplateCaseMapper).deleteByTemplateId(1L);
    }

    @Test
    @DisplayName("删除：连带删用例必须与删模板在同一个事务里")
    public void testDeleteIsTransactional() throws Exception {
        // 为什么用反射断注解而不是断行为：mock 环境下两次 delete 都会「成功」，
        // 事务边界根本不参与执行。而真出事的路径是「模板删掉了、用例删失败」——
        // 没有 @Transactional 就是模板已提交、用例留下，正好造出上面那条缺陷，
        // 且所有单测全绿。声明式事务只能这样钉。
        Method method = AgentPromptTemplateServiceImpl.class.getMethod("deletePromptTemplate", Long.class);
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertNotNull(transactional, "deletePromptTemplate 必须带 @Transactional，否则连带删用例不是原子的");
        assertArrayEquals(new Class[]{Exception.class}, transactional.rollbackFor(),
                "必须对受检异常也回滚：默认只对 RuntimeException 回滚，"
                        + "而这里的失败更可能来自数据源/超时一类被包装过的异常");
    }

    // ==================== 查询 ====================

    @Test
    @DisplayName("详情：不存在时报错（而不是返回 null 让前端渲染成空表单）")
    public void testGetNotExistsThrows() {
        when(promptTemplateMapper.selectById(999L)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.getPromptTemplate(999L));

        assertEquals(AGENT_PROMPT_TEMPLATE_NOT_EXISTS.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("详情：存在则原样返回")
    public void testGetReturns() {
        AgentPromptTemplateDO template = existing(1L, "平台使用");
        when(promptTemplateMapper.selectById(1L)).thenReturn(template);

        assertSame(template, service.getPromptTemplate(1L));
    }

    @Test
    @DisplayName("分页：直接委托 mapper（筛选与排序全在 LambdaQueryWrapperX 里，Service 不加料）")
    public void testGetPageDelegates() {
        AgentPromptTemplatePageReqVO reqVO = new AgentPromptTemplatePageReqVO();
        reqVO.setPageNo(2);
        reqVO.setPageSize(20);
        reqVO.setCategory("数据查询");
        PageResult<AgentPromptTemplateDO> expected = new PageResult<>(List.of(existing(1L, "数据查询")), 21L);
        when(promptTemplateMapper.selectPage(reqVO)).thenReturn(expected);

        assertSame(expected, service.getPromptTemplatePage(reqVO));
    }

    @Test
    @DisplayName("聊天面板列表：直接委托 mapper（只取开启态、按分类+排序升序，由 mapper 的 wrapper 保证）")
    public void testGetEnabledListDelegates() {
        List<AgentPromptTemplateDO> expected = List.of(existing(1L, "平台使用"), existing(2L, "数据查询"));
        when(promptTemplateMapper.selectEnabledList()).thenReturn(expected);

        assertSame(expected, service.getEnabledPromptTemplateList());
    }

    @Test
    @DisplayName("分类候选：去重、去首尾空白、丢掉空白项，保持 mapper 给出的升序")
    public void testGetCategoryListDedupsAndDropsBlanks() {
        when(promptTemplateMapper.selectListForCategories()).thenReturn(List.of(
                existing(1L, "平台使用"),
                existing(2L, "平台使用"),
                existing(3L, "数据查询"),
                existing(4L, "  问题排查  "),
                existing(5L, "   "),
                existing(6L, null)));

        List<String> categories = service.getCategoryList();

        assertEquals(List.of("平台使用", "数据查询", "问题排查"), categories,
                "去重后按 mapper 的升序输出；空白与 null 分类不进候选（前端归入「未分类」）");
    }

    @Test
    @DisplayName("分类候选：库里一条模板都没有时返回空列表，而不是 null")
    public void testGetCategoryListWhenEmpty() {
        when(promptTemplateMapper.selectListForCategories()).thenReturn(List.of());

        assertEquals(List.of(), service.getCategoryList());
    }

}
