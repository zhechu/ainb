package cn.enlev.ainb.module.agent.controller.admin.planner;

import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.module.agent.controller.admin.planner.vo.AgentWorkflowDraftRespVO;
import cn.enlev.ainb.module.agent.controller.admin.planner.vo.AgentWorkflowPlanReqVO;
import cn.enlev.ainb.module.agent.controller.admin.planner.vo.AgentWorkflowPlanRespVO;
import cn.enlev.ainb.module.agent.controller.admin.planner.vo.AgentWorkflowPublishRespVO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.enums.WorkflowDefStatusEnum;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.framework.agent.core.security.OutputModerator;
import cn.enlev.ainb.module.agent.service.plan.WorkflowDraftBO;
import cn.enlev.ainb.module.agent.service.plan.WorkflowPlannerService;
import cn.enlev.ainb.module.agent.service.workflow.AgentPlannerAdoptionService;
import cn.enlev.ainb.module.agent.service.workflow.AgentWorkflowDefService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;

/**
 * plan-as-workflow 生成端点（增量7a，P3 Planner 改造第一切片）
 *
 * <h3>为什么先走独立 REST 端点，而不是直接改聊天 BUILD 路径</h3>
 * 「让 LLM 可靠产出结构合法、可执行的 DSL」是整个 P3 方差最高的部分。本端点把这条链路
 * （生成 → 双层校验自修正 → 落 DRAFT → 发布 → 既有 {@code run()} 执行）<b>纯增量</b>地端到端验证，
 * 期间<b>不动</b>聊天 BUILD 路径、不删线性 planner、不动表。
 *
 * <p>增量7b：验证成立后，聊天 BUILD 意图已切到本生成器（见
 * {@code AgentAssistantServiceImpl#workflowDraftStream}），线性 planner 已删除。本控制器新增
 * {@code publish-workflow}（确认卡「确认并运行」的发布步）与 {@code workflow-draft}（确认卡取 DSL
 * 渲染节点预览）两个端点；{@code plan-workflow} 作为「一句话直接生成并可选发布」的便捷端点保留。
 *
 * <h3>🔴 身份快照必须在 Servlet 线程上捕获</h3>
 * 生成循环同步跑在请求线程里，{@code AgentExecutionContext.snapshot()} 此刻能拿到 ThreadLocal 里的身份；
 * 干跑校验 {@code dryRunValidate} 也要用它占位（建图不执行，仅捕获）。
 *
 * <h3>异常处理</h3>
 * 普通 JSON 端点（非 SSE）：{@code ServiceException}（如轮次耗尽的 {@code WORKFLOW_PLAN_DSL_GENERATION_FAILED}）
 * 交全局处理器转 {@code CommonResult.error}，无需在此 try-catch。
 *
 * @author 走进新时代
 */
@Tag(name = "管理后台 - plan-as-workflow 生成")
@Slf4j
@RestController
@RequestMapping("/agent/planner")
@Validated
public class AgentWorkflowPlannerController {

    @Resource
    private WorkflowPlannerService plannerService;
    @Resource
    private AgentWorkflowDefService defService;
    /**
     * 规划采纳率埋点（迭代七 B5-3）：生成即冻结基线，确认（发布）即比对是否手改。
     * best-effort，内部 try/catch，绝不影响生成 / 发布主流程。
     */
    @Resource
    private AgentPlannerAdoptionService adoptionService;

    @PostMapping("/plan-workflow")
    @Operation(summary = "把一句复杂需求拆解成 Workflow DSL 草稿，落库并（可选）发布")
    @PreAuthorize("@ss.hasPermission('agent:assistant:use')")
    public CommonResult<AgentWorkflowPlanRespVO> planWorkflow(@Valid @RequestBody AgentWorkflowPlanReqVO reqVO) {
        AgentExecutionContext context = AgentExecutionContext.snapshot();
        // 7a 端点把进度记日志即可；7b 接聊天时改成 emit PLANNING 事件
        WorkflowDraftBO draft = plannerService.planToWorkflow(reqVO.getQuestion(), context,
                text -> log.info("[planWorkflow][进度：{}]", text));

        // 落 DRAFT（createDef 内部 version=max+1、提取触发列），再按 publish 决定是否发布
        // 🔴 这里传<b>原文</b> name（不审核）：落库的值一旦带上「⚠️【疑似注入】」前缀就永久洗不掉，
        //    而读出来展示时还要再审一次 —— 审核只该发生在投递边界，理由见下面 respVO 那几行。
        Long draftDefId = defService.createDef(draft.getCode(), draft.getName(), draft.getDsl(),
                WorkflowDefStatusEnum.DRAFT.getStatus());
        // 🔴 B5-3：AI 起草即冻结采纳率基线（此刻 draft.getDsl() 是 AI 原文，用户手改后就没了）。best-effort。
        adoptionService.recordGenerated(draft.getCode(), draftDefId, draft.getDsl());
        String status = WorkflowDefStatusEnum.DRAFT.getStatus();
        // 🔴 TRUE.equals 而不是 !FALSE.equals（重构 S4b / D8）：VO 的字段默认已经是 false，
        //    但客户端显式传 "publish": null 时 setter 会把默认值覆盖成 null，
        //    而 !Boolean.FALSE.equals(null) == true ⟹ 一个「什么都没表态」的请求会去翻状态。
        //    判据必须落在「明确要求发布」这一侧：说不清的时候，不发。
        if (Boolean.TRUE.equals(reqVO.getPublish())) {
            Long defId = defService.publishDef(draft.getCode());
            // 🔴 发布即生效（B6 / S16）：这条路上「发布」与「生效」刻意**不分离**，理由见
            //    {@code AgentWorkflowDefServiceImpl#pinPrimaryIfAbsent} 的类内说明 —— 聊天里的
            //    「确认并运行」紧接着就按 code 起 run-stream，而 run-stream 只吃 code、不吃版本号。
            //    不钉主版本时生效版本回落到「version 最大」，看着也一样；但一旦该 code 之前被人
            //    钉过主版本（灰度回退），发布完跑的就是**上一版** —— 用户确认了 A、系统跑了 B。
            defService.setPrimaryVersion(defId);
            status = WorkflowDefStatusEnum.PUBLISHED.getStatus();
            // 🔴 B5-3：一句话「生成并直接发布」= 用户没机会手改，故按当前 DSL（即 AI 原文）记采纳，
            //    recordAdopted 比对刚冻的基线得 handModified=false、editCount=0，正是这条路径的语义。best-effort。
            adoptionService.recordAdopted(draft.getCode(), draft.getDsl());
        }

        AgentWorkflowPlanRespVO respVO = new AgentWorkflowPlanRespVO();
        respVO.setCode(draft.getCode());
        // 🔴 dsl <b>刻意不审</b>：前端要 JSON.parse 它，而审核会把超 2000 字符的串<b>截断</b>，
        //    一份几十节点的 DSL 远超这个长度，截断等于把 JSON 从中间劈开、节点预览整块崩掉。
        //    代价是 DSL 里的节点名（同样是 AI 写的）在预览里仍是原文 —— 作为残余面登记，见总账 N33。
        respVO.setDsl(draft.getDsl());
        // 🔴 【B2-2 · 口径 A 第 4 项 · REST 侧】name/summary 是模型产出并解析的 DSL 里的文字，
        //    这张草稿卡是「确认并运行」的决策面，故投递前审。
        // 🔴 审核点在这里而不在 WorkflowPlannerServiceImpl#buildDraft：那边的 draft.getName()
        //    同时是上面 createDef 的<b>落库入参</b>，在源头审会把前缀永久写进 workflow_def.name，
        //    之后 getWorkflowDraft 再读出来审一次就叠成两个前缀（moderateString 刻意不幂等）。
        // 🔴 code 不审：服务端生成的唯一键，也是 publish-workflow 的机器入参。
        respVO.setName(OutputModerator.moderateString(draft.getName()));
        respVO.setSummary(OutputModerator.moderateString(draft.getSummary()));
        respVO.setWarnings(draft.getWarnings());
        respVO.setRounds(draft.getRounds());
        respVO.setStatus(status);
        return success(respVO);
    }

    /**
     * 确认并发布草稿（增量7b，聊天确认卡「确认并运行」的第一步）
     *
     * <p>🔴 <b>无状态幂等</b>：最新版是 DRAFT 就翻成 PUBLISHED，已是 PUBLISHED 直接返回其 id
     * （见 {@link AgentWorkflowDefService#publishDef}）。刻意<b>不复用</b> hitl 的挂起/恢复 ——
     * 那套 suspend-resume 绑定「一个活的 workflow 执行 + DB checkpoint 线程」，
     * 与聊天里一张轻确认卡不匹配；发布完由前端另起一次 {@code run-stream} 执行，更简单、风险更低。
     *
     * <p>权限沿用 {@code agent:assistant:use}（与 {@code plan-workflow} 一致）：发布草稿是在
     * <b>自己的</b>草稿上操作。真正执行走 {@code run-stream}（增量14a 起需 {@code agent:workflow:run}，
     * 副作用面；持有「流程编排」菜单者已被迁移非破坏性授予，故聊天里「确认并运行」照常可用）。
     */
    @PostMapping("/publish-workflow")
    @Operation(summary = "确认并发布 Workflow 草稿（DRAFT → PUBLISHED，幂等）")
    @Parameter(name = "code", description = "业务编码", required = true)
    @PreAuthorize("@ss.hasPermission('agent:assistant:use')")
    public CommonResult<AgentWorkflowPublishRespVO> publishWorkflow(@RequestParam("code") String code) {
        Long defId = defService.publishDef(code);
        // 🔴 发布即生效（B6 / S16）：本端点是聊天确认卡「确认并运行」的第一步，前端紧接着就按
        //    **同一个 code** 起 run-stream（不带版本号）。不钉主版本的话，若该 code 之前被钉过
        //    主版本（灰度回退场景），这一跑会落到上一版 —— 用户确认了 A、系统跑了 B。
        //    与管理页发布（publishDefById 只在无主版本时补钉）刻意不对称，理由见上一条注释。
        defService.setPrimaryVersion(defId);
        // 🔴 B5-3：这是聊天确认卡「确认并运行」的发布步 —— 采纳率埋点的<b>确认侧</b>。用户可能在生成后、
        //    确认前用 updateDef 就地手改过草稿，故拿<b>发布这一刻的当前 DSL</b>与基线比对：publishDef 只翻状态
        //    不改 dsl，getDef(defId) 读到的正是（可能被手改过的）当前正文。非 AI 生成的流程（无待确认基线行）
        //    → recordAdopted 内部静默 no-op，故管理页手写流程的发布不会污染采纳率。best-effort。
        adoptionService.recordAdopted(code, defService.getDef(defId).getDsl());
        AgentWorkflowPublishRespVO respVO = new AgentWorkflowPublishRespVO();
        respVO.setCode(code);
        respVO.setDefId(defId);
        respVO.setStatus(WorkflowDefStatusEnum.PUBLISHED.getStatus());
        return success(respVO);
    }

    /**
     * 取草稿的 DSL 与状态（增量7b，聊天确认卡渲染只读节点预览的唯一数据源）
     *
     * <p>单一装配路径：无论 SSE 直播还是刷新页面重建卡片，节点预览所需的 DSL 都从这里取
     * （前端 {@code JSON.parse(dsl)} 拿 {@code nodes[]}），不在 WORKFLOW_DRAFT 事件里再塞一份。
     *
     * <p>S2-f 起同一个响应还带 {@code manifest}（变更清单）：节点预览说的是「流程长什么样」，
     * 清单说的是「点了确认并运行之后平台上会多出/改掉什么」。AI 生成的草稿尤其需要后者 ——
     * 建表这类不可逆动作，该在确认卡上就看见，而不是等运行完才发现多了一张表。
     * 同样走「单一装配路径」：清单只在这里组装，不在 WORKFLOW_DRAFT 事件里另塞一份。
     *
     * <p>租户隔离由 {@code TenantBaseDO} + 全局拦截器保证：{@code getDef} 只可能取到本租户的定义，
     * 取不到抛 {@code WORKFLOW_DEF_NOT_EXISTS}（交全局处理器）。
     */
    @GetMapping("/workflow-draft")
    @Operation(summary = "取 Workflow DSL 草稿（供确认卡渲染只读节点预览）")
    @Parameter(name = "defId", description = "Workflow 定义编号", required = true)
    @PreAuthorize("@ss.hasPermission('agent:assistant:use')")
    public CommonResult<AgentWorkflowDraftRespVO> getWorkflowDraft(@RequestParam("defId") Long defId) {
        AgentWorkflowDefDO def = defService.getDef(defId);
        AgentWorkflowDraftRespVO respVO = new AgentWorkflowDraftRespVO();
        respVO.setDefId(def.getId());
        respVO.setCode(def.getCode());
        // 🔴 【B2-2 · 口径 A 第 4 项的 REST 侧】名字要审：它是 AI 起草的，而 WorkflowPlannerServiceImpl
        //    那边的单点收口改的是<b>草稿 BO</b>（draft.setName/setSummary），盖不住这条 ——
        //    这里读的是<b>已落库的 def</b>，而落库发生在 buildDraft 序列化 DSL 之后，
        //    名字早已原样进了库。故「单点收口一次盖住三条通道」这句话对本端点<b>不成立</b>，这里补一刀。
        respVO.setName(OutputModerator.moderateString(def.getName()));
        // 🔴 dsl <b>刻意不审</b>：前端是 JSON.parse(dsl) 拿 nodes[] 做节点预览的，
        //    而 OutputModerator 对超 2000 字符的字符串会<b>截断</b> —— 一份几十节点的 DSL 远超这个长度，
        //    截断会把 JSON 从中间劈开，节点预览整块崩掉（不是「少了几个字」，是解析直接失败）。
        //    代价是：DSL 里的<b>节点名</b>（同样是 AI 写的）在预览里仍是原文。
        //    这一段作为残余面登记，见本轮报告与总账 N33。
        respVO.setDsl(def.getDsl());
        respVO.setStatus(def.getStatus());
        respVO.setVersion(def.getVersion());
        // 🔴 清单是这张卡的「附带品」，故走 OrNull 版本：它生成不出来时，节点预览与「确认并运行」按钮
        //    必须照常可用 —— 否则一个附加面板的 bug 会把用户卡死在一张打不开的确认卡上。
        //    null 的语义（「清单不可用」而不是「没有变更」）写在 RespVO 字段注释里。
        // 这里会按 id 再读一次定义（上面那次是为了取 code/name/dsl/status）：一次主键查询，
        // 换 service 层不必为「已经有 DO 在手」再开一个重载。
        // 🔴 【B2-2 · 口径 B 第 5 项 · 通道 ③】审核点落在控制器这一侧而不是 buildManifest 里：
        //    buildManifest 的产物还要喂发布闸门，而闸门对清单文字做的是字符串 equals / map 取值，
        //    审核改过字就会静默匹配不上、放行 —— fail-open。完整理由见
        //    OutputModerator#moderateManifest 的类内说明。
        respVO.setManifest(OutputModerator.moderateManifestForDisplay(
                defService.buildManifestOrNull(null, defId, null)));
        return success(respVO);
    }

}
