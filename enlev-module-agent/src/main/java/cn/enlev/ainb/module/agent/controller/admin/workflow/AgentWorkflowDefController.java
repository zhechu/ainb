package cn.enlev.ainb.module.agent.controller.admin.workflow;

import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentNameCatalogRespVO;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentPlannerAdoptionStatRespVO;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowDefAuditRespVO;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowDefCopyReqVO;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowDefCopyRespVO;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowDefPageReqVO;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowDefRespVO;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowDefSaveReqVO;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowDefValidateReqVO;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowDefValidateRespVO;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowManifestReqVO;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowSandboxReqVO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.io.NodeIoContract;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ChangeManifest;
import cn.enlev.ainb.module.agent.framework.agent.core.security.OutputModerator;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.AgentValidateResult;
import cn.enlev.ainb.module.agent.service.workflow.AgentNameCatalogService;
import cn.enlev.ainb.module.agent.service.workflow.AgentPlannerAdoptionService;
import cn.enlev.ainb.module.agent.service.workflow.AgentWorkflowDefService;
import cn.enlev.ainb.module.agent.service.workflow.AgentWorkflowExecutionService.WorkflowEvent;
import cn.enlev.ainb.module.agent.service.workflow.AgentWorkflowSandboxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

import static cn.enlev.ainb.framework.common.exception.enums.GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR;
import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_SANDBOX_MOCK_WRITES_REQUIRED;

/**
 * Workflow 定义管理（增量8 / P4 管理页）
 *
 * <h3>🔴 与「执行」分开的关注点</h3>
 * {@code AgentWorkflowController}(/agent/workflow) 管执行的启动/恢复/查询；本控制器管<b>定义</b>的
 * CRUD + 发布/停用。plan-as-workflow 生成器（{@code AgentWorkflowPlannerController}）也会落定义，
 * 但那是「聊天里生成草稿并确认发布」的窄入口；这里是运维/编排者浏览、管理全部定义的通用入口。
 *
 * <h3>权限：读写分权限点（增量14a 拆分，此前统一 {@code agent:workflow:query}）</h3>
 * 读（{@code get/page}）与只读预检（{@code validate} / {@code manifest}）保留 {@code agent:workflow:query}；
 * 写拆成 {@code create/update/delete/publish}——「停用」与「发布」同属状态机跃迁，共用
 * {@code agent:workflow:publish}。执行侧（{@code AgentWorkflowController} 的 run-stream/resume-stream/event）
 * 另有 {@code agent:workflow:run}（副作用面），与定义写权限分开。对应 type=3 菜单按钮行 + 角色授权见
 * {@code sql/mysql/xpy-2026-09-27-agent-fine-grained-perms.sql}。super_admin 绕过一切权限点，不受拆分影响。
 *
 * <p>🔴 {@code force-delete}（轮10 D3）是<b>第 8 个</b>权限点 {@code agent:workflow:force-delete}，
 * 且刻意<b>只授权给 super_admin</b>：它是唯一能删已发布定义并连带抹掉执行史留痕的档，
 * 见该方法上的注释。授权脚本 {@code xpy-2026-10-08-agent-workflow-force-delete-perm.sql}。
 *
 * <p>⚠️ {@code manifest} 归 query 是因为它<b>对本模块只读</b>（不写任何表、不改任何状态），
 * 但它<b>会联网</b>：清单里的干跑结论要调 infra/bpm 的只读接口去问「平台接不接受这么改」。
 * 故它不是 {@code validate} 那种纯静态检查，响应时间取决于那两个模块当时通不通
 * （不通时相关项落 {@code NOT_PROBED}，不报错）。
 *
 * @author 走进新时代
 */
@Tag(name = "管理后台 - Workflow 定义管理")
@RestController
@RequestMapping("/agent/workflow-def")
@Validated
@Slf4j
public class AgentWorkflowDefController {

    @Resource
    private AgentWorkflowDefService defService;
    @Resource
    private AgentWorkflowSandboxService sandboxService;
    @Resource
    private AgentNameCatalogService nameCatalogService;
    @Resource
    private AgentPlannerAdoptionService adoptionService;

    @PostMapping("/create")
    @Operation(summary = "创建 Workflow 定义（落 DRAFT 草稿）")
    @PreAuthorize("@ss.hasPermission('agent:workflow:create')")
    public CommonResult<Long> createDef(@Valid @RequestBody AgentWorkflowDefSaveReqVO createReqVO) {
        return success(defService.createDef(createReqVO));
    }

    @PostMapping("/copy")
    @Operation(summary = "复制 Workflow 定义（基于源 DSL 改写冲突项，落成新编码下的新草稿；返回逐格改写清单）")
    @PreAuthorize("@ss.hasPermission('agent:workflow:create')")
    public CommonResult<AgentWorkflowDefCopyRespVO> copyDef(
            @Valid @RequestBody AgentWorkflowDefCopyReqVO copyReqVO) {
        // 权限归 create：产物是一个新 DRAFT 定义（与「新建流程」同类写动作），复用 agent:workflow:create，
        // 不新设权限点（同下面 /rollback 那条注释的理由）。发布它仍走 publish。
        // 🔴 刻意不接受前端传 dsl：复制的 DSL 只能由后端从源定义读出来再改写。
        //    接受前端拼好的 DSL 就是 G11 —— 前端「复制」按钮自己组装一份 DSL 时，
        //    它并不知道 webhook path / 建表名 / BPM key 会跟源流程抢同一份外部资源。
        return success(defService.copyDef(copyReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "修改 Workflow 定义（仅草稿可改）")
    @PreAuthorize("@ss.hasPermission('agent:workflow:update')")
    public CommonResult<Boolean> updateDef(@Valid @RequestBody AgentWorkflowDefSaveReqVO updateReqVO) {
        defService.updateDef(updateReqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除 Workflow 定义（仅草稿与已停用可删；被引用或有在途执行时拒绝）")
    @Parameter(name = "id", description = "编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('agent:workflow:delete')")
    public CommonResult<Boolean> deleteDef(@RequestParam("id") Long id) {
        defService.deleteDef(id);
        return success(true);
    }

    /**
     * 批量删除（迭代八）
     *
     * <h3>🔴 判据与 {@code /delete} 逐字一致，且<b>全成或全不成</b></h3>
     * 有一条不该删就整批拒绝，避免「删了一半才报错」让管理员分不清到底少了哪几行。
     * 这不是整洁要求：级联的主导步骤 {@code BaseCheckpointSaver#release} 发的是真实
     * {@code UPDATE GRAPH_THREAD SET is_released = TRUE}，<b>事务回滚不了它</b>，
     * 边删边校验会留下「执行行还在、快照已 released」且没有任何接口能解释的中间态。
     * 故 Service 是两阶段：所有行的闸门全部跑完，才开始动任何一行数据。
     *
     * <h3>🔴 权限沿用 {@code agent:workflow:delete}，不新给一个</h3>
     * 批量只是单删的复数形式，不是另一种能力；单独给一个权限点会造出「能删一个却不能删多个」的割裂。
     * 与 {@code AgentDefController} 的 {@code /delete-list} 同口径，故本次<b>不需要新的授权 SQL</b>。
     *
     * <h3>⚠️ 数组参数在前端必须逗号拼接</h3>
     * 本项目的 axios {@code paramsSerializer} 用 {@code qs} 的 {@code allowDots}，
     * 默认会把数组序列化成 {@code ids[]=1&ids[]=2}，绑不上这里的 {@code @RequestParam List<Long>}。
     * 前端传 {@code ids.join(',')}（与 {@code system/post} 的 {@code deletePostList} 同款）。
     */
    @DeleteMapping("/delete-list")
    @Operation(summary = "批量删除 Workflow 定义（仅草稿与已停用可删；有一条不合规就整批拒绝）")
    @Parameter(name = "ids", description = "编号列表", required = true)
    @PreAuthorize("@ss.hasPermission('agent:workflow:delete')")
    public CommonResult<Boolean> deleteDefList(@RequestParam("ids") List<Long> ids) {
        defService.deleteDefList(ids);
        return success(true);
    }

    /**
     * 强制删除（迭代七 / 轮10 D3）
     *
     * <h3>🔴 为什么是 {@code POST} 而不是跟着 {@code /delete} 用 {@code DELETE}</h3>
     * 同一路径下两个动词不同、语义却都叫「删除」，运维照着 REST 直觉猜就会猜错哪一个：
     * 一个只删草稿与停用版、一个连<b>正在被选用</b>的已发布版也能删。换成不同的路径名 + POST，
     * 让「这不是常规删除」这件事在 URL 上就看得见。副作用型动作用 POST 也符合本控制器既有惯例
     * （{@code /publish}、{@code /disable}、{@code /rollback}、{@code /set-primary} 都是 POST）。
     *
     * <h3>🔴 权限点必须是<b>单独一个</b>，不复用 {@code agent:workflow:delete}</h3>
     * 本档是唯一能删 <b>PUBLISHED</b>（此刻正被触发入口按 code 回落到）定义的路子；
     * 常规档碰到 PUBLISHED 一律抛 {@code WORKFLOW_DEF_NOT_DELETABLE}。
     * 复用同一个权限点，就等于「能删停用版的人也能拔掉一条正在线上做入口的流程」。
     * 授权脚本只发给 super_admin（{@code sql/mysql/xpy-2026-10-08-agent-workflow-force-delete-perm.sql}）。
     *
     * <p>⚠️ 分档的理由<b>不再是</b>「常规档必无执行史」——迭代八起常规档删 DISABLED 行同样级联
     * 软删执行史与节点 trace、同样释放 checkpoint。两档的级联与另两道闸门（引用 / 在途执行）
     * <b>共用同一份 Service 内核</b>，差别只有状态闸门那一道。
     *
     * <p>⚠️ 文案里说的是「释放」checkpoint 而不是「清理」：{@code release} 只把 {@code graph_thread.is_released}
     * 翻成 1（快照行留在库里、从此读不出来），并不删 {@code graph_checkpoint} 的行 —— 2026-10-08 实测。
     * 详见 {@code AgentWorkflowDefService#forceDeleteDef} 的「级联顺序」那一节。
     */
    @PostMapping("/force-delete")
    @Operation(summary = "强制删除 Workflow 定义（管理员档：连带软删执行史与节点 trace、释放 checkpoint；已发布/已停用的也能删）")
    @Parameter(name = "id", description = "编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('agent:workflow:force-delete')")
    public CommonResult<Boolean> forceDeleteDef(@RequestParam("id") Long id) {
        defService.forceDeleteDef(id);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得 Workflow 定义（含完整 DSL 原文，供只读画布渲染）")
    @Parameter(name = "id", description = "编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('agent:workflow:query')")
    public CommonResult<AgentWorkflowDefRespVO> getDef(@RequestParam("id") Long id) {
        AgentWorkflowDefDO def = defService.getDef(id);
        return success(BeanUtils.toBean(def, AgentWorkflowDefRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得 Workflow 定义分页")
    @PreAuthorize("@ss.hasPermission('agent:workflow:query')")
    public CommonResult<PageResult<AgentWorkflowDefRespVO>> getDefPage(@Valid AgentWorkflowDefPageReqVO pageReqVO) {
        PageResult<AgentWorkflowDefDO> pageResult = defService.getDefPage(pageReqVO);
        PageResult<AgentWorkflowDefRespVO> voPage = BeanUtils.toBean(pageResult, AgentWorkflowDefRespVO.class);
        // 🔴 列表不带 DSL 正文：一份 DSL 可能几十 KB（含内联 prompt），一页 20 行就是兆级负载，
        //    而列表只需元信息。画布另调 GET /get?id 取完整 dsl。置 null 而非不映射，避免 RespVO 字段漂移。
        voPage.getList().forEach(vo -> vo.setDsl(null));
        return success(voPage);
    }

    @PostMapping("/publish")
    @Operation(summary = "发布 Workflow 定义（DRAFT → PUBLISHED，按 id 精确发布该版本）")
    @Parameter(name = "id", description = "编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('agent:workflow:publish')")
    public CommonResult<Long> publishDef(@RequestParam("id") Long id) {
        return success(defService.publishDefById(id));
    }

    @PostMapping("/disable")
    @Operation(summary = "停用 Workflow 定义（PUBLISHED → DISABLED，不再接受新执行）")
    @Parameter(name = "id", description = "编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('agent:workflow:publish')")
    public CommonResult<Boolean> disableDef(@RequestParam("id") Long id) {
        defService.disableDefById(id);
        return success(true);
    }

    @PostMapping("/validate")
    @Operation(summary = "校验 Workflow DSL（保存/发布前预检，返回结构化 errors/warnings，只读不落库）")
    @PreAuthorize("@ss.hasPermission('agent:workflow:query')")
    public CommonResult<AgentWorkflowDefValidateRespVO> validateDsl(
            @RequestBody AgentWorkflowDefValidateReqVO reqVO) {
        // ctx 用当前登录者/租户的身份快照：深层干跑 buildStateGraph 需要一个占位身份（不执行节点，故仅占位）。
        // 🔴 用 OrNull 版本：清单在这里是「附带品」，它生成不出来不该把用户真正要的体检结果一起带走。
        //    null 的语义（「清单不可用」而不是「没有变更」）写在 RespVO 字段注释里，前端按那条渲染。
        ChangeManifest manifest = defService.buildManifestOrNull(reqVO.getDsl(), null, null);
        // 🔴 清单只建一次，既喂校验（折叠里面「平台已拒绝」的干跑结论 = V4/V5/V12）又塞进响应。
        //    此前这两处各建一份：多一次 parse 是小事，多跑一整趟最多 15 秒的只读干跑才是。
        //    更重要的是「只有一份清单」在结构上保证了 errors 与 manifest 说的是同一份 DSL ——
        //    两份各建的话，中间有人改了库（比如把撞名的那张表删了），
        //    就会得到「errors 说表名被占、清单说预演通过」这种自己打自己脸的响应。
        // 🔴 上面建清单刻意只传 dsl、不传 id：/manifest 那一侧 dsl 与 id 是「预览哪一份」的互斥二选一，
        //    传了 id 就会去读库里那一版，而这里要打分的必须是用户手上这一版（两者不同时就会抛互斥错）。
        //    id 只在下面这一处用，且只用它取执行史（台账 #81）—— 语义差别写在 ReqVO 的字段注释里。
        AgentValidateResult result =
                defService.validateDsl(reqVO.getDsl(), AgentExecutionContext.snapshot(), manifest, reqVO.getId());
        AgentWorkflowDefValidateRespVO respVO = new AgentWorkflowDefValidateRespVO();
        respVO.setPassed(result.isPassed());
        respVO.setErrors(result.getErrors());
        respVO.setWarnings(result.getWarnings());
        // 🔴 【B2-2】这里交给前端的是<b>审核后的副本</b>，而上面 validateDsl 拿到的是<b>原始那份</b>。
        //    这两者不能合并成一个变量：清单文字是 AI 起草 DSL 时写的（节点名、工具入参摘要），
        //    审核会剥不可见字符、加前缀、超 2000 截断；而折叠器对 sinkKind 做的是字符串 equals、
        //    对 sinkNodeId 做的是 map 取值 —— 文字动过一个字符就静默匹配不上、continue 放行，
        //    发布闸门从「拦」变成「放」。完整理由见 OutputModerator#moderateManifest 的类内说明。
        respVO.setManifest(OutputModerator.moderateManifestForDisplay(manifest));
        return success(respVO);
    }

    @PostMapping("/manifest")
    @Operation(summary = "生成 Workflow 变更清单（发布/审批前的影响面预览；只读不落库，但含 infra/bpm 只读干跑）")
    @PreAuthorize("@ss.hasPermission('agent:workflow:query')")
    public CommonResult<ChangeManifest> getManifest(@RequestBody AgentWorkflowManifestReqVO reqVO) {
        // 🔴 走严格版：这个端点的产品就是清单本身，生成不出来必须报错。
        //    若在这里也兜成 null，前端拿到 200 + null，与「这流程不改任何东西」在界面上是同一句话 ——
        //    而那句会让人放心地点同意。这正是 S2 全刀反复钉的那条：静默少一块比报错坏。
        // 权限归 query 而不是 run：清单只读，与 validate/node-io-contract 同一档（run 是副作用面）。
        // 🔴 【B2-2】审核点在这里而不在 buildManifest 里：这个端点的产物<b>就是给人看的</b>，
        //    而 buildManifest 的产物还要喂发布闸门 —— 在源头审核等于把闸门喂成被改过字的清单。
        return success(OutputModerator.moderateManifestForDisplay(
                defService.buildManifest(reqVO.getDsl(), reqVO.getId(), reqVO.getFromNodeId())));
    }

    // ==================== 存量盘点（重构 S0：开闸前第 0 步） ====================

    /**
     * 盘点当前租户<b>全部</b>未删定义，逐行跑与发布闸门同一套的体检。
     *
     * <p>S4b 要把 V1~V19 接进发布闸门，而存量行大多在这些规则存在之前就落库了 —— 开闸等于追溯既往。
     * 本端点的产物是「会追溯到谁」那份名单，<b>交人裁定后才进 S4b</b>（方案 §4 的 S0 行）。
     * 判据与闸门同一个（都走 {@code validateDsl}），故清单不会与闸门各说各话；详见
     * {@link AgentWorkflowDefService#auditAll(boolean)}。
     *
     * <p>🔴 只读、单租户：不写任何表，范围是调用者的 ambient 租户（{@code TenantBaseDO} 自动隔离，
     * 绝不 {@code executeIgnore}）。权限因此归 {@code agent:workflow:query}，与 {@code validate}/{@code manifest} 同档。
     *
     * <p>⚠️ {@code withDryRun=true} <b>可能要几分钟</b>：每行都要建一次变更清单，而清单含最多 15 秒的
     * infra/bpm 只读探测，几十行乘起来远超一般请求。命令行调用请给足超时；页面调用请先想清楚要不要这一层。
     * 默认 {@code false} 时 V4/V5/V12 <b>一条都没查</b>，这件事写在响应的 {@code dryRunIncluded} 与逐行
     * {@code dryRunState} 里 —— 免得把「没探」读成「干净」。
     *
     * <p>⚠️ 但那份「没查」名单里<b>没有 V16</b>（污点流进写工具）：V16 是纯静态溯源，只读 DSL 本身，
     * 台账 C9 之后拿不到清单也自己追一遍，两档都报且文案逐字相同（实测 2026-10-07，对运行中的后端两档各跑一次）。
     * 把 V16 算进「默认没查」会让读者以为默认档报告里「没有污点警告」= 「这行没有污点流向」——
     * 那正是本端点要防的误读。
     */
    @GetMapping("/audit-all")
    @Operation(summary = "盘点当前租户全部 Workflow 定义（开闸前的存量体检；只读不落库）")
    @Parameter(name = "withDryRun", description = "是否额外为每行建变更清单以启用干跑层（V4/V5/V12；V16 污点溯源不受本开关约束、两档都判）；"
            + "默认 false。true 时每行最多 15 秒探测，几十行可能跑几分钟", example = "false")
    @PreAuthorize("@ss.hasPermission('agent:workflow:query')")
    public CommonResult<AgentWorkflowDefAuditRespVO> auditAll(
            @RequestParam(value = "withDryRun", defaultValue = "false") boolean withDryRun) {
        return success(defService.auditAll(withDryRun));
    }

    @GetMapping("/node-io-contract")
    @Operation(summary = "获得节点输入/输出端口契约（设计器画类型化端口、下游「点选上游产出」用；只读静态元数据）")
    @PreAuthorize("@ss.hasPermission('agent:workflow:query')")
    public CommonResult<List<NodeIoContract.Contract>> getNodeIoContract() {
        return success(NodeIoContract.all());
    }

    /**
     * 名称目录（重构 S1 / L1）
     *
     * <p>🔴 权限刻意用 {@code agent:workflow:query} 而不是工具清单那个 {@code agent:config:query}：
     * 设计器此前直接调 {@code GET /agent/config/tool-list} 拿工具，而那是「AI 配置管理」的权限点。
     * 只有编排权的管理员打开设计器，工具下拉是空的且<b>没有任何提示</b>（前端把 403 catch 成了空数组）——
     * 这是 G7。挂在 workflow-def 下、用 workflow 自己的读权限，权限模型才自洽。
     */
    @GetMapping("/name-catalog")
    @Operation(summary = "获得名称目录（工具/控件/操作符/子流程的「机器键 → 中文名」映射；设计器与只读画布展示用）")
    @PreAuthorize("@ss.hasPermission('agent:workflow:query')")
    public CommonResult<AgentNameCatalogRespVO> getNameCatalog() {
        return success(nameCatalogService.getNameCatalog());
    }

    /**
     * 规划采纳率统计（迭代七 B5-8：管理页「出一个数字」）
     *
     * <p>🔴 挂 workflow-def、用 {@code agent:workflow:query}（与 {@link #getNameCatalog} 同一取舍）：
     * 这个数字显示在<b>流程编排管理页</b>上，看这页的人持有的是 workflow 读权限，未必有 {@code agent:assistant:use}
     * （那是聊天侧权限，埋点<b>写</b>在 planner 控制器，但<b>读</b>该归管理页所在的命名空间）。
     * 若挂到 {@code /agent/planner} 下，只有编排权的管理员会看到一个 403 —— 正是 G7 那个坑的翻版。
     *
     * <p>🔴 只读、当前租户维度（{@code TenantBaseDO} + 拦截器自动拼 {@code tenant_id}）。原始计数与比率一并下发，
     * 供验收③拿只读 SQL 逐一对齐行数，理由见 {@link AgentPlannerAdoptionStatRespVO} 类注释。
     */
    @GetMapping("/planner-adoption-stat")
    @Operation(summary = "获得规划采纳率统计（AI 起草的流程被采纳/手改的计数与比率；管理页展示用）")
    @PreAuthorize("@ss.hasPermission('agent:workflow:query')")
    public CommonResult<AgentPlannerAdoptionStatRespVO> getPlannerAdoptionStat() {
        return success(adoptionService.getAdoptionStat());
    }

    // ==================== 沙箱测试（流程编排设计器） ====================

    @Operation(summary = "沙箱试跑草稿 DSL（流式）：真跑草稿、写操作 mock、不落库")
    @PostMapping(value = "/sandbox-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@ss.hasPermission('agent:workflow:run')")
    public Flux<CommonResult<WorkflowEvent>> sandboxStream(@Valid @RequestBody AgentWorkflowSandboxReqVO reqVO) {
        // 🔴 显式关闭 mock = 脚枪（沙箱里真跑写操作会产生真实副作用），v1 直接拒绝
        if (Boolean.FALSE.equals(reqVO.getMockWrites())) {
            return Flux.just(CommonResult.error(AGENT_SANDBOX_MOCK_WRITES_REQUIRED.getCode(),
                    AGENT_SANDBOX_MOCK_WRITES_REQUIRED.getMsg()));
        }
        // 快照必须在 Servlet 线程上捕获（此刻 ThreadLocal 里有身份），晚一步就可能是 null
        AgentExecutionContext context = AgentExecutionContext.snapshot();
        try {
            return sandboxService.runSandbox(reqVO.getDsl(), reqVO.getInput(), context)
                    .map(CommonResult::success);
        } catch (ServiceException ex) {
            log.info("[sandboxStream][请求被拒绝，错误码({}) 原因({})]", ex.getCode(), ex.getMessage());
            return Flux.just(CommonResult.error(ex.getCode(), ex.getMessage()));
        } catch (Exception ex) {
            log.error("[sandboxStream][沙箱启动异常]", ex);
            return Flux.just(CommonResult.error(INTERNAL_SERVER_ERROR.getCode(),
                    "沙箱启动失败：" + ex.getMessage()));
        }
    }

    // ==================== 版本管理（增量14b） ====================

    @GetMapping("/version-list")
    @Operation(summary = "获得某 code 的全部版本（版本管理抽屉：对比/回滚/设主版本；不含 DSL 正文）")
    @Parameter(name = "code", description = "业务编码", required = true, example = "wf_plan_customer")
    @PreAuthorize("@ss.hasPermission('agent:workflow:query')")
    public CommonResult<List<AgentWorkflowDefRespVO>> getVersionList(@RequestParam("code") String code) {
        List<AgentWorkflowDefDO> versions = defService.listVersionsByCode(code);
        // 列表不带 DSL（Service 已置 null）；对比时前端另调 GET /get?id 取两份完整 DSL
        return success(BeanUtils.toBean(versions, AgentWorkflowDefRespVO.class));
    }

    @PostMapping("/rollback")
    @Operation(summary = "回滚到某历史版本（基于其 DSL 复制成一个新草稿，绝不改历史快照）")
    @Parameter(name = "id", description = "源版本编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('agent:workflow:create')")
    public CommonResult<Long> rollback(@RequestParam("id") Long id) {
        // 权限归 create：回滚的产物是一个新 DRAFT 版本（与「新建流程」同类写动作），复用 agent:workflow:create，
        // 不新设权限点（避免为每个动作都加一行 type=3 菜单）。发布该草稿仍走 publish（agent:workflow:publish）。
        return success(defService.rollbackToVersion(id));
    }

    @PostMapping("/set-primary")
    @Operation(summary = "把某已发布版本设为主版本（执行入口优先选中它，用于灰度回退）")
    @Parameter(name = "id", description = "目标版本编号（须 PUBLISHED）", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('agent:workflow:publish')")
    public CommonResult<Boolean> setPrimary(@RequestParam("id") Long id) {
        // 权限归 publish：设主版本改变「哪个已发布版本当前生效」，与发布/停用同属状态机/生效控制动作，
        // 复用 agent:workflow:publish（不新设权限点）。
        defService.setPrimaryVersion(id);
        return success(true);
    }

}
