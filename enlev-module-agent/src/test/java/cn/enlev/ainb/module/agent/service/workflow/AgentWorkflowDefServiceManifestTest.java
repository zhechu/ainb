package cn.enlev.ainb.module.agent.service.workflow;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowDefMapper;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslParser;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ChangeManifest;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.WorkflowChangeManifestBuilder;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.WorkflowDslValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DEF_NOT_EXISTS;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DSL_INVALID;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_MANIFEST_SOURCE_INVALID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentWorkflowDefServiceImpl#buildManifest} / {@code buildManifestOrNull} 单测（S2-f）
 *
 * <p>清单本身的正确性由 {@code WorkflowChangeManifestBuilderTest} 逐条钉住，本类只钉<b>接线</b>：
 * 两个入口怎么解析、版本号从哪来、以及「严格版抛 / 附带版返回 null」这条分界画在哪。
 * 三件事都属于「编译得过、界面上看不出来、错了会让人觉得安全」的那类。
 *
 * <h3>🔴 钉得最紧的两条</h3>
 * <ol>
 *   <li>{@link #buildManifest_bothGiven_refusesInsteadOfPickingOne} —— dsl 与 id 同时给时<b>不许自己挑一个</b>。
 *       挑了也能跑通、也返回一份格式完全正确的清单，只是那份清单描述的是<b>另一份 DSL</b>。
 *       用户会据它点「确认并运行」，这是本刀唯一能直接导致误操作的路径。</li>
 *   <li>{@link #buildManifestOrNull_builderCrashes_returnsNullSoTheMainResponseSurvives} ——
 *       清单生成崩了不能把主响应一起带走。注意它断言的是 {@code null} 而<b>不是</b>一份空清单：
 *       空清单的 headline 是「本次不产生任何写操作」，那是一句假话；null 是看得见地缺席。</li>
 * </ol>
 *
 * @author 走进新时代
 */
class AgentWorkflowDefServiceManifestTest extends BaseMockitoUnitTest {

    @InjectMocks
    private AgentWorkflowDefServiceImpl defService;

    @Mock
    private AgentWorkflowDefMapper defMapper;
    @Mock
    private DslParser dslParser;
    @Mock
    private WorkflowChangeManifestBuilder manifestBuilder;

    // 本类不触发校验/落库路径，但 @InjectMocks 需要能把它们注进去
    @Mock
    private WorkflowDslValidator dslValidator;

    private static final String DSL = "{\"id\":\"wf_x\",\"nodes\":[],\"edges\":[]}";

    @Test
    @DisplayName("只给 dsl：按草稿算，版本号传 null（未落库），fromNodeId 原样透传")
    void buildManifest_withDsl_treatsItAsAnUnsavedDraft() {
        WorkflowDefDTO def = new WorkflowDefDTO();
        when(dslParser.parse(DSL)).thenReturn(def);
        ChangeManifest built = new ChangeManifest();
        when(manifestBuilder.build(def, null, "approve_order")).thenReturn(built);

        ChangeManifest result = defService.buildManifest(DSL, null, "approve_order");

        assertSame(built, result, "service 只做取数与转发，不许复制或改写 builder 的产物");
        // 版本号为 null 是本条的重点：草稿还没落库，清单里 workflow.version 就该是空的，
        // 渲染方据此显示「草稿」。若这里错传了 0 或 1，卡片会谎报一个并不存在的版本号。
        verify(manifestBuilder).build(def, null, "approve_order");
        verify(defMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("只给 id：按 id 精确取那一版，清单的版本号用它自己的 version")
    void buildManifest_withId_carriesThatVersionsNumber() {
        AgentWorkflowDefDO stored = new AgentWorkflowDefDO();
        stored.setId(77L);
        stored.setCode("wf_plan_customer");
        stored.setVersion(3);
        stored.setDsl(DSL);
        when(defMapper.selectById(77L)).thenReturn(stored);
        WorkflowDefDTO def = new WorkflowDefDTO();
        when(dslParser.parse(DSL)).thenReturn(def);
        when(manifestBuilder.build(def, 3, null)).thenReturn(new ChangeManifest());

        defService.buildManifest(null, 77L, null);

        // 版本号取自库里那一行、而非硬编码或 null：管理页/审批卡看的是用户点的那一版，
        // 清单上标错版本等于让人对着 v3 的影响面批准 v1
        verify(manifestBuilder).build(eq(def), eq(3), isNull());
    }

    @Test
    @DisplayName("🔴 dsl 与 id 同时给：拒绝，而不是自己挑一个 —— 挑错了用户会据另一份 DSL 的清单点同意")
    void buildManifest_bothGiven_refusesInsteadOfPickingOne() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> defService.buildManifest(DSL, 77L, null));

        assertEquals(WORKFLOW_MANIFEST_SOURCE_INVALID.getCode(), ex.getCode());
        assertEquals("变更清单的入参不合法：dsl 与 id 只能给一个（两份都给了，无法判断要预览哪一份）",
                ex.getMessage(), "文案必须说清是「都给」而不是「都没给」，否则用户按提示改会改错方向");
        // 🔴 一次都不许调 builder：「挑一个然后照常出清单」在返回值上看不出区别，
        //    唯一能钉住它的就是这条。少了它，本测试在「优先用 id」的实现下同样是绿的。
        verify(manifestBuilder, never()).build(any(), any(), any());
        verify(dslParser, never()).parse(anyString());
        verify(defMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("两个都没给：也拒绝 —— 否则会得到一份 headline 写着「本次不产生任何写操作」的空清单")
    void buildManifest_neitherGiven_refusesInsteadOfReturningAnEmptyManifest() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> defService.buildManifest("  ", null, null));

        assertEquals(WORKFLOW_MANIFEST_SOURCE_INVALID.getCode(), ex.getCode());
        assertEquals("变更清单的入参不合法：dsl 与 id 必须给一个（两份都没给）", ex.getMessage());
        verify(manifestBuilder, never()).build(any(), any(), any());
    }

    @Test
    @DisplayName("按 id 取不到（不存在或跨租户）：抛 WORKFLOW_DEF_NOT_EXISTS，不回落成空清单")
    void buildManifest_idNotFound_throws() {
        when(defMapper.selectById(404L)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> defService.buildManifest(null, 404L, null));

        assertEquals(WORKFLOW_DEF_NOT_EXISTS.getCode(), ex.getCode());
        verify(manifestBuilder, never()).build(any(), any(), any());
    }

    @Test
    @DisplayName("严格版：DSL parse 不出来就冒泡 —— 这个端点的产品就是清单，没有别的地方可以放这条错")
    void buildManifest_parseThrows_propagates() {
        when(dslParser.parse("{bad json")).thenThrow(exception(WORKFLOW_DSL_INVALID, "JSON 解析失败：第 3 行缺逗号"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> defService.buildManifest("{bad json", null, null));

        assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("第 3 行缺逗号"), "parse 的原始原因要带出去，用户才知道改哪");
        verify(manifestBuilder, never()).build(any(), any(), any());
    }

    @Test
    @DisplayName("附带版：DSL parse 不出来返回 null —— 体检结果照常返回，原因已在 errors 里写着")
    void buildManifestOrNull_parseThrows_returnsNull() {
        // 必须给一段非空白但非法的 JSON：给空串会先撞上「二选一」那条，测的就不是 parse 分支了
        when(dslParser.parse("{bad json")).thenThrow(exception(WORKFLOW_DSL_INVALID, "JSON 解析失败：第 3 行缺逗号"));

        assertNull(defService.buildManifestOrNull("{bad json", null, null));
        verify(manifestBuilder, never()).build(any(), any(), any());
    }

    @Test
    @DisplayName("🔴 附带版：清单生成器自己崩了也返回 null —— 一个附加面板的 bug 不该把主响应一起带走")
    void buildManifestOrNull_builderCrashes_returnsNullSoTheMainResponseSurvives() {
        WorkflowDefDTO def = new WorkflowDefDTO();
        when(dslParser.parse(DSL)).thenReturn(def);
        when(manifestBuilder.build(def, null, null))
                .thenThrow(new IllegalStateException("溯源撞上没料到的节点形状"));

        // 这条钉的是「不抛」。它是 /validate 与草稿卡共用 buildManifestOrNull 的全部理由：
        // 那两个响应的主产品是体检结果与节点预览，清单只是附带品。
        // 🔴 断言 null 而不是「一份空清单」：空清单的 headline 是「本次不产生任何写操作」，
        //    兜住异常再返回它会等于把一次崩溃翻译成一句让人放心点同意的话。
        assertNull(defService.buildManifestOrNull(DSL, null, null));
    }

    @Test
    @DisplayName("附带版：入参不合法同样返回 null（两个附带调用方各自只会给一个来源，故这条分支它们走不到）")
    void buildManifestOrNull_badSource_returnsNull() {
        assertNull(defService.buildManifestOrNull(null, null, null));
        verify(manifestBuilder, never()).build(any(), any(), any());
    }

}
