package cn.enlev.ainb.module.agent.controller.admin.workflow;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowDefRespVO;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowDefSaveReqVO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentNodeExecutionMapper;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowDefMapper;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowExecutionMapper;
import cn.enlev.ainb.module.agent.enums.WorkflowDefStatusEnum;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.trigger.WorkflowTriggerExtractor;
import cn.enlev.ainb.module.agent.service.workflow.AgentWorkflowDefServiceImpl;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DEF_NOT_DELETABLE;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DEF_NOT_DRAFT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 「可就地编辑」与「可删除」这两条规则的<b>单一口径</b>测试（S5-g / 方案 §G14，配合 S5-c）
 *
 * <h3>G14 修的是什么</h3>
 * 同一条判据（「只有草稿能就地改」）原先写了两遍：后端 {@code updateDef} 抛
 * {@code WORKFLOW_DEF_NOT_DRAFT}，前端 {@code editor/index.vue} 自己再判一次 status 决定要不要挂只读横幅。
 * 两份判据必然漂移，漂移的表现是<b>按钮亮着、点下去报错</b>（或反过来：能改却显示只读）——
 * 这正是本平台头号缺陷形态「配了不生效且无提示」在权限面上的翻版。
 * S5-c 的做法是把结论<b>从服务端下发</b>：{@code AgentWorkflowDefRespVO} 上加派生 getter
 * （无字段、无 setter），前端只渲染不判断。迭代八给删除也照这个形态补了一对
 * （{@code getDeletable()} / {@code getDeleteDeniedReason()}）。
 *
 * <h3>🔴 为什么是「派生 getter」而不是「字段」，以及本类怎么钉住它</h3>
 * 字段要在每个出口手工填，漏一处就是「列表页按钮态对了、详情页错了」这种查半天的鬼故事；
 * getter 无从漏填 —— 但这个「无从」只有在<b>每个出口都真的返回这个 VO</b> 时才成立。
 * 所以本类有一条反射扫描：{@code AgentWorkflowDefController} 的所有 Web 出口，
 * 一个都不许把 {@code AgentWorkflowDefDO} 直接递给前端（DO 没有那几个 getter，
 * 一旦哪个出口图省事返回 DO，前端拿到的 {@code editable} 就是 {@code undefined}，
 * 而 {@code undefined} 在 Vue 的 {@code v-if} 里是假 ⟹ <b>静默变成全表只读</b>，一个错都不报）。
 *
 * <h3>🔴 最有价值的一条是跨类一致性，不是 getter 本身</h3>
 * {@code getEditable()} / {@code getDeletable()} 都只是转发 {@code WorkflowDefStatusEnum} 的一个静态方法，
 * 单测它们就是测一个转发。真正要钉的是「前端看到的那一格」与「后端接受不接受这个动作」<b>永远同结论</b> ——
 * 故本类对每个状态同时问两边，且<b>编辑与删除各一条</b>：
 * <ul>
 *   <li>{@code getEditable()} ⟺ {@code updateDef} 抛不抛 {@code WORKFLOW_DEF_NOT_DRAFT}</li>
 *   <li>{@code getDeletable()} ⟺ {@code deleteDef} 抛不抛 {@code WORKFLOW_DEF_NOT_DELETABLE}</li>
 * </ul>
 * 🔴 这两条<b>不能合成一条</b>：迭代八起两个判据的结论已经不同（DISABLED 不可编辑但可删），
 * 合成一条就等于断言「可编辑 ⟺ 可删」，那是句假话，且会把将来任何一次「放宽可删范围」误判成回归。
 * 将来谁把某个 getter 改成自己判 status，对应那一条会当场红。
 *
 * @author 走进新时代
 */
class AgentWorkflowDefEditableTest extends BaseMockitoUnitTest {

    @InjectMocks
    private AgentWorkflowDefServiceImpl defService;

    @Mock
    private AgentWorkflowDefMapper defMapper;

    @Mock
    private WorkflowTriggerExtractor triggerExtractor;

    /**
     * 级联三件套（迭代八起 {@code deleteDef} 也会走级联，不再是「一句 deleteById」）
     *
     * <p>🔴 本类<b>一个桩都不给</b>它们，靠的正是 Mockito 对集合返回值的空集合默认值：
     * {@code selectListForAudit()} 与 {@code selectListByDefId()} 都返回空 List ⟹
     * 没人 pin、没有在途执行、没有 checkpoint 要释放 ⟹ 级联一路空跑到 {@code deleteById}。
     * 这样删除那条一致性探针才能只盯「状态闸门」这一维，而不必为它复制一整套删除夹具 ——
     * 完整级联（顺序、批内互删、第 3 行失败前 2 行未动）在 {@code AgentWorkflowDefServiceDeleteTest}。
     *
     * <p>⚠️ 少任何一个 {@code @Mock}，对应字段就是 null，级联会 NPE，
     * 而 NPE 会被下面的 {@code catchServiceException} 漏过去（它只接 {@code ServiceException}）——
     * 表现为「可删的状态反而没抛异常」，断言假绿。故这三个字段是删除那条探针的<b>前提</b>，不是可选装饰。
     */
    @Mock
    private AgentWorkflowExecutionMapper executionMapper;
    @Mock
    private AgentNodeExecutionMapper nodeExecutionMapper;
    @Mock
    private BaseCheckpointSaver checkpointSaver;

    private static final Long ID = 101L;

    /**
     * 要被逐个问一遍的状态：三档真值 + 三档「不该出现但必须 fail-closed」的输入
     *
     * <p>🔴 后三档是重点。{@code null}（新 new 出来还没落库的 DO）、{@code "ARCHIVED"}（状态机将来加档）、
     * {@code "draft"}（大小写写错）都不是合法状态，而它们的<b>安全</b>结论只有一个：不可编辑、也不可删除。
     * 判据反过来的话，一份脏数据就能让「已发布的版本快照」在前端显示成可改 ——
     * 而后端会拒，于是又回到 G14 那个「按钮亮着、点下去报错」。
     */
    private static final List<String> STATUSES = Arrays.asList(
            WorkflowDefStatusEnum.DRAFT.getStatus(),
            WorkflowDefStatusEnum.PUBLISHED.getStatus(),
            WorkflowDefStatusEnum.DISABLED.getStatus(),
            null,
            "ARCHIVED",
            "draft");

    private static AgentWorkflowDefRespVO vo(String status) {
        AgentWorkflowDefRespVO vo = new AgentWorkflowDefRespVO();
        vo.setId(ID);
        vo.setStatus(status);
        return vo;
    }

    private static AgentWorkflowDefDO row(String status) {
        AgentWorkflowDefDO row = new AgentWorkflowDefDO();
        row.setId(ID);
        row.setCode("wf_x");
        row.setStatus(status);
        row.setDsl("{\"id\":\"wf_x\",\"nodes\":[]}");
        return row;
    }

    // ==================== 判据本身 ====================

    @Test
    void editable_onlyDraft_isEditable_everythingElseFailsClosed() {
        for (String status : STATUSES) {
            boolean expected = WorkflowDefStatusEnum.DRAFT.getStatus().equals(status);
            assertEquals(expected, vo(status).getEditable(),
                    "status=%s 的可编辑结论应当是 %s".formatted(status, expected));
        }
    }

    @Test
    void editDeniedReason_isNullExactlyWhenEditable_neverAThirdState() {
        for (String status : STATUSES) {
            AgentWorkflowDefRespVO vo = vo(status);
            if (vo.getEditable()) {
                assertNull(vo.getEditDeniedReason(),
                        "可编辑时不该带拒绝理由，否则前端横幅会渲染一句空话");
            } else {
                assertNotNull(vo.getEditDeniedReason(), "status=%s 不可编辑，必须给出理由".formatted(status));
                assertFalse(vo.getEditDeniedReason().isBlank(), "status=%s 的理由不能是空白".formatted(status));
            }
        }
    }

    /**
     * 理由文案必须<b>与具体状态无关</b>，且必须指出出路
     *
     * <p>🔴 前半条是修一处真错话：S5-c 原稿写「<b>已发布的</b>流程是执行实例绑定的版本快照…」，
     * 而 DISABLED（曾发布、现停用）同样不可就地编辑 —— 那句话会让状态徽标是「已停用」的行
     * 配上一句「已发布的流程」，自相矛盾。故这里断言各档非草稿状态拿到的是<b>同一句</b>，
     * 且这句里不点名任何<b>具体</b>状态。
     *
     * <p>⚠️ 禁止名单里<b>没有</b>「草稿」二字：说成「非草稿版本」恰恰是唯一与状态无关的说法
     * （它点名的是补集，不是某一档），把它一起禁掉就是我这条探针自己的错话 —— 首跑就是这么红的。
     *
     * <p>🔴 后半条：一句只说「不能改」的话把人堵死在原地。前端横幅是只读态下唯一的解释，
     * 它必须说清「那该怎么办」（复制出新草稿 / 回滚出新版本），否则用户只能去猜或者提工单。
     */
    @Test
    void editDeniedReason_isStatusAgnostic_andPointsAtTheWayOut() {
        List<String> reasons = new ArrayList<>();
        for (String status : STATUSES) {
            AgentWorkflowDefRespVO vo = vo(status);
            if (!vo.getEditable()) {
                reasons.add(vo.getEditDeniedReason());
            }
        }
        assertEquals(1, reasons.stream().distinct().count(),
                "非草稿状态共用同一句理由（点名具体状态的话，另一档就会配上错话），实际=%s"
                        .formatted(reasons.stream().distinct().toList()));
        String reason = reasons.get(0);
        for (String forbidden : List.of("已发布", "已停用")) {
            assertFalse(reason.contains(forbidden),
                    "理由里不该点名具体状态「%s」，否则另一档会配上错话，实际=「%s」".formatted(forbidden, reason));
        }
        assertTrue(reason.contains("复制") || reason.contains("回滚"),
                "必须指出出路（复制 / 回滚出新草稿），实际=「%s」".formatted(reason));
    }

    // ==================== 判据本身（删除那一档） ====================

    @Test
    void deletable_draftAndDisabled_only_everythingElseFailsClosed() {
        for (String status : STATUSES) {
            boolean expected = WorkflowDefStatusEnum.isDeletable(status);
            assertEquals(expected, vo(status).getDeletable(),
                    "status=%s 的可删结论应当是 %s".formatted(status, expected));
        }
        // 🔴 正面对照：判据本体必须真收两档，否则上面这条循环会跟着判据一起错、还全绿
        assertTrue(WorkflowDefStatusEnum.isDeletable(WorkflowDefStatusEnum.DRAFT.getStatus()));
        assertTrue(WorkflowDefStatusEnum.isDeletable(WorkflowDefStatusEnum.DISABLED.getStatus()));
        assertFalse(WorkflowDefStatusEnum.isDeletable(WorkflowDefStatusEnum.PUBLISHED.getStatus()));
    }

    @Test
    void deleteDeniedReason_isNullExactlyWhenDeletable_neverAThirdState() {
        for (String status : STATUSES) {
            AgentWorkflowDefRespVO vo = vo(status);
            if (vo.getDeletable()) {
                assertNull(vo.getDeleteDeniedReason(),
                        "可删时不该带拒绝理由，否则前端 tooltip 会渲染一句空话");
            } else {
                assertNotNull(vo.getDeleteDeniedReason(), "status=%s 不可删，必须给出理由".formatted(status));
                assertFalse(vo.getDeleteDeniedReason().isBlank(), "status=%s 的理由不能是空白".formatted(status));
            }
        }
    }

    /**
     * 理由文案必须<b>只陈述要求</b>，不点名当前状态；且必须指出出路
     *
     * <p>🔴 与 {@link #editDeniedReason_isStatusAgnostic_andPointsAtTheWayOut} 同一条纪律，
     * 但<b>禁止名单不一样</b>：这里只禁「已发布」。
     * 「草稿」「已停用」是<b>允许集</b>的名字，必须出现（否则用户不知道要什么状态才删得掉）；
     * 而「已发布」是<b>当前状态</b>的名字，不能出现 —— 能走到「不可删」的不止 PUBLISHED 一档，
     * {@code isDeletable} 对 {@code null} 与未知状态值同样返回 false（fail-close），
     * 文案写死「已发布的版本…」就会让那些行配上一句解释不了自己处境的话。
     *
     * <p>🔴 后半条：一句只说「不能删」的话把人堵死在原地。前端 tooltip 是不可删态下唯一的解释，
     * 它必须说清「那该怎么办」（先停用），否则用户只能去猜或者提工单。
     */
    @Test
    void deleteDeniedReason_statesTheRequirement_notTheCurrentStatus_andPointsAtTheWayOut() {
        List<String> reasons = new ArrayList<>();
        for (String status : STATUSES) {
            AgentWorkflowDefRespVO vo = vo(status);
            if (!vo.getDeletable()) {
                reasons.add(vo.getDeleteDeniedReason());
            }
        }
        assertEquals(1, reasons.stream().distinct().count(),
                "不可删的各档共用同一句理由（点名当前状态的话，另一档就会配上错话），实际=%s"
                        .formatted(reasons.stream().distinct().toList()));
        String reason = reasons.get(0);
        assertFalse(reason.contains("已发布"),
                "理由里不该点名当前状态「已发布」，否则 null / 未知状态那些行会配上错话，实际=「%s」".formatted(reason));
        assertTrue(reason.contains("草稿") && reason.contains("已停用"),
                "必须说清允许集（草稿 + 已停用），否则用户不知道要什么状态才删得掉，实际=「%s」".formatted(reason));
        assertTrue(reason.contains("停用"),
                "必须指出出路（先停用再删），实际=「%s」".formatted(reason));
    }

    // ==================== 🔴 跨类一致性（G14 的正题） ====================

    @Test
    void editableGetter_andUpdateEnforcementPoint_neverDisagree() {
        for (String status : STATUSES) {
            AgentWorkflowDefDO row = row(status);
            when(defMapper.selectById(ID)).thenReturn(row);

            boolean voSays = vo(status).getEditable();
            AgentWorkflowDefSaveReqVO saveReqVO = new AgentWorkflowDefSaveReqVO();
            saveReqVO.setId(ID);
            saveReqVO.setName("改名");
            saveReqVO.setDsl(row.getDsl());

            ServiceException updateEx = catchServiceException(() -> defService.updateDef(saveReqVO));

            if (voSays) {
                assertNull(updateEx, "status=%s：VO 说可编辑，后端就必须接受保存".formatted(status));
            } else {
                assertNotNull(updateEx, "status=%s：VO 说不可编辑，后端就必须拒保存".formatted(status));
                assertEquals(WORKFLOW_DEF_NOT_DRAFT.getCode(), updateEx.getCode());
            }
        }
    }

    /**
     * 删除那一半：键在 {@code getDeletable()} 上，<b>不</b>键在 {@code getEditable()} 上
     *
     * <p>🔴 与上面那条<b>刻意分开</b>，理由见类注释：迭代八起 DISABLED 不可编辑但可删，
     * 两个判据的结论已经不同。合成一条就等于断言「可编辑 ⟺ 可删」，那是句假话。
     *
     * <p>⚠️ 对<b>可删</b>的状态，这里只断言「状态闸门放行了」（即没抛异常）——
     * 级联本身是空跑，因为三个协作者一个桩都没给（见 {@link #executionMapper} 那段注释）。
     * 引用闸门、在途闸门、级联顺序、批内互删都在 {@code AgentWorkflowDefServiceDeleteTest}，
     * 本类<b>不</b>复制那套夹具：复制一份就是第二份真相，而两份必然漂移。
     */
    @Test
    void deletableGetter_andDeleteStatusGate_neverDisagree() {
        for (String status : STATUSES) {
            when(defMapper.selectById(ID)).thenReturn(row(status));

            boolean voSays = vo(status).getDeletable();
            ServiceException deleteEx = catchServiceException(() -> defService.deleteDef(ID));

            if (voSays) {
                assertNull(deleteEx, "status=%s：VO 说可删，后端的状态闸门就必须放行".formatted(status));
            } else {
                assertNotNull(deleteEx, "status=%s：VO 说不可删，后端就必须拒删除".formatted(status));
                assertEquals(WORKFLOW_DEF_NOT_DELETABLE.getCode(), deleteEx.getCode(),
                        "status=%s：拒绝理由必须是「状态不可删」那一条，而不是编辑闸门那一条".formatted(status));
            }
        }
    }

    /**
     * 🔴 两个判据的结论<b>必须真的不同</b>，否则上面那两条探针就是在测同一件事
     *
     * <p>这条是防「探针空转」的正面对照（与 {@code everyWebExit_…} 里那条 {@code assertEquals(3, …)}
     * 同一个用意）：哪天有人把 {@code isDeletable} 写成 {@code return isDraft(status)}，
     * 上面两条会一起变绿（因为结论又重合了），只有这条会红。
     */
    @Test
    void deletable_andEditable_deliberatelyDisagreeOnDisabled() {
        String disabled = WorkflowDefStatusEnum.DISABLED.getStatus();
        assertFalse(vo(disabled).getEditable(), "停用版不可就地改（改了就等于偷改历史版本的语义）");
        assertTrue(vo(disabled).getDeletable(), "停用版可删（它已不再接受新执行，没有理由必须留着）");

        String draft = WorkflowDefStatusEnum.DRAFT.getStatus();
        assertTrue(vo(draft).getEditable());
        assertTrue(vo(draft).getDeletable());

        String published = WorkflowDefStatusEnum.PUBLISHED.getStatus();
        assertFalse(vo(published).getEditable());
        assertFalse(vo(published).getDeletable(), "已发布正被触发入口选用，常规档一律拒删");
    }

    private static ServiceException catchServiceException(Runnable action) {
        try {
            action.run();
            return null;
        } catch (ServiceException ex) {
            return ex;
        }
    }

    // ==================== 🔴 出口面：派生 getter 够得着每一个出口吗 ====================

    @Test
    void everyWebExit_handsTheFrontendTheRespVo_neverTheDo() {
        List<String> leaks = new ArrayList<>();
        List<String> exits = new ArrayList<>();
        for (Method m : AgentWorkflowDefController.class.getDeclaredMethods()) {
            if (!isWebExit(m)) {
                continue;
            }
            String returnType = m.getGenericReturnType().getTypeName();
            exits.add(m.getName() + " → " + returnType);
            // DO 上没有那两个派生 getter：一旦哪个出口直接返回 DO，前端拿到的 editable 是 undefined，
            // 而 undefined 在 v-if 里为假 ⟹ 整个列表静默变只读，一个错都不报
            if (returnType.contains(AgentWorkflowDefDO.class.getName())) {
                leaks.add(m.getName() + " → " + returnType);
            }
        }
        assertTrue(leaks.isEmpty(), "这些出口把 DO 直接递给了前端：%s".formatted(leaks));

        // 🔴 正面对照，防止上面那条扫描空转（比如出口全被改名、或注解换了包 ⟹ leaks 天然为空）
        List<String> rowExits = exits.stream()
                .filter(e -> e.contains(AgentWorkflowDefRespVO.class.getName()))
                .toList();
        assertEquals(3, rowExits.size(),
                "把 workflow 定义行递给前端的出口应当是 /get、/page、/version-list 三处，实际=%s".formatted(rowExits));
    }

    private static boolean isWebExit(Method m) {
        for (Annotation a : m.getAnnotations()) {
            String name = a.annotationType().getName();
            if (name.startsWith("org.springframework.web.bind.annotation.") && name.endsWith("Mapping")) {
                return true;
            }
        }
        return false;
    }

    // ==================== 前端拿到的 JSON 里真的有这两格吗 ====================

    /**
     * 🔴 派生 getter 必须真的被序列化出去
     *
     * <p>{@code getEditable()}/{@code getEditDeniedReason()}/{@code getDeletable()}/{@code getDeleteDeniedReason()}
     * 都没有对应字段，靠的是 Jackson「有 getter 就有属性」的默认约定。这个约定会被两件事悄悄打破：加一个
     * {@code @JsonIgnoreProperties} 的白名单、或给类加 {@code @JsonAutoDetect(fieldVisibility = ANY)}
     * 之类的可见性配置 —— 两种情况下 getter 都还在、单测还全绿，
     * 而前端拿到的 JSON 里<b>没有这几格</b>，于是 {@code editable} / {@code deletable} 是 undefined
     * ⟹ 全表静默只读、复选框全禁用，一个错都不报。
     * 与「DO 漏到前端」是同一个缺陷形状，故一并钉住。
     */
    @Test
    void derivedGetters_actuallySerializeIntoJson() throws Exception {
        // 🔴 不注册 JavaTimeModule：本例里 createTime/updateTime 都是 null，走 NullSerializer，
        //    不需要 LocalDateTime 的序列化器；少一次 ServiceLoader 扫描，也少一个「模块没在测试 classpath 上」的偶发红
        ObjectMapper mapper = new ObjectMapper();

        String published = mapper.writeValueAsString(vo(WorkflowDefStatusEnum.PUBLISHED.getStatus()));
        assertTrue(published.contains("\"editable\":false"), "已发布行的 JSON 里必须有 editable=false，实际=%s"
                .formatted(published));
        assertTrue(published.contains("\"editDeniedReason\""), "已发布行的 JSON 里必须有 editDeniedReason，实际=%s"
                .formatted(published));
        assertTrue(published.contains("\"deletable\":false"), "已发布行的 JSON 里必须有 deletable=false，实际=%s"
                .formatted(published));
        assertTrue(published.contains("\"deleteDeniedReason\""),
                "已发布行的 JSON 里必须有 deleteDeniedReason，实际=%s".formatted(published));

        String draft = mapper.writeValueAsString(vo(WorkflowDefStatusEnum.DRAFT.getStatus()));
        assertTrue(draft.contains("\"editable\":true"), "草稿行的 JSON 里必须有 editable=true，实际=%s"
                .formatted(draft));
        assertTrue(draft.contains("\"deletable\":true"), "草稿行的 JSON 里必须有 deletable=true，实际=%s"
                .formatted(draft));

        // 🔴 停用版是两档结论<b>分岔</b>的那一行：可删、但不可编辑。
        //    漏了这一格，前端「不可编辑 ⟹ 不可删」的直觉就会悄悄被写进代码，而复选框会全禁用。
        String disabled = mapper.writeValueAsString(vo(WorkflowDefStatusEnum.DISABLED.getStatus()));
        assertTrue(disabled.contains("\"editable\":false"), "停用行的 JSON 里必须是 editable=false，实际=%s"
                .formatted(disabled));
        assertTrue(disabled.contains("\"deletable\":true"), "停用行的 JSON 里必须是 deletable=true，实际=%s"
                .formatted(disabled));
        assertTrue(disabled.contains("\"deleteDeniedReason\":null"),
                "可删时 deleteDeniedReason 必须是 null（前端 tooltip 不该渲染空话），实际=%s".formatted(disabled));
    }

}
