package cn.enlev.ainb.module.agent.controller.admin.def;

import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.module.agent.controller.admin.def.vo.AgentDefCopyReqVO;
import cn.enlev.ainb.module.agent.controller.admin.def.vo.AgentDefPageReqVO;
import cn.enlev.ainb.module.agent.controller.admin.def.vo.AgentDefRespVO;
import cn.enlev.ainb.module.agent.controller.admin.def.vo.AgentDefSaveReqVO;
import cn.enlev.ainb.module.agent.dal.dataobject.def.AgentDefDO;
import cn.enlev.ainb.module.agent.service.def.AgentDefService;
import cn.enlev.ainb.module.agent.service.workflow.AgentWorkflowDefService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;

/**
 * Agent 定义管理（P5 Agent Builder 第一刀）
 *
 * <h3>🔴 与「流程编排」分开的关注点</h3>
 * {@code AgentWorkflowDefController}(/agent/workflow-def) 管<b>流程</b>定义；本控制器管<b>智能体</b>定义
 * （人设 + 版本化 + 发布/停用）。两者生命周期同构，但是两类资源、两张表、两套错误码段。
 *
 * <h3>权限：读写分权限点（增量14a 拆分，此前统一 {@code agent:def:query}）</h3>
 * 读（{@code get/page}）保留 {@code agent:def:query}；写拆成 {@code create/update/delete/publish}
 * ——其中「停用」与「发布」同属状态机跃迁，共用 {@code agent:def:publish}（停用只是发布的反向，
 * 单独给一个权限点会造出「能发布却不能停用」的割裂，反而更难解释）。对应 type=3 菜单按钮行 +
 * 角色授权见 {@code sql/mysql/xpy-2026-09-27-agent-fine-grained-perms.sql}（照 {@code AgentToolDefController}
 * 用 {@code agent:config:update} 拆写权限的先例）。super_admin 角色绕过一切权限点，不受拆分影响。
 *
 * <h3>🔴 有界</h3>
 * 本控制器只有目录 CRUD + 发布/停用；persona 的运行期消费见聊天路径（增量10 起）。
 *
 * @author 走进新时代
 */
@Tag(name = "管理后台 - Agent 定义管理")
@RestController
@RequestMapping("/agent/def")
@Validated
public class AgentDefController {

    @Resource
    private AgentDefService defService;

    /**
     * 工作流定义 Service（增量12 / slice-3c 批次 B3）：反查「是否有已发布 chatflow 驱动该 Agent」，
     * 给列表/详情打「工作流驱动」只读标识（discoverability，计划 RK2 缓解）。
     */
    @Resource
    private AgentWorkflowDefService workflowDefService;

    @PostMapping("/create")
    @Operation(summary = "创建 Agent 定义（落 DRAFT 草稿）")
    @PreAuthorize("@ss.hasPermission('agent:def:create')")
    public CommonResult<Long> createDef(@Valid @RequestBody AgentDefSaveReqVO createReqVO) {
        return success(defService.createDef(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "修改 Agent 定义（草稿就地改；系统内置行也可就地改，但只认名称/人设/描述）")
    @PreAuthorize("@ss.hasPermission('agent:def:update')")
    public CommonResult<Boolean> updateDef(@Valid @RequestBody AgentDefSaveReqVO updateReqVO) {
        defService.updateDef(updateReqVO);
        return success(true);
    }

    /**
     * 派生新版本（需求11「编辑派生新版本」）
     *
     * <p>前端「编辑」按钮对<b>非草稿</b>行先调本接口拿到新草稿 id，再带它进编辑器。
     * 这样管理员不必照着原配置手抄一遍「新增」，同时已发布版本仍是不可变快照。
     * 草稿行不走这里（直接编辑即可），系统内置行会被拒（它不参与版本化）。
     */
    @PostMapping("/derive")
    @Operation(summary = "基于已有定义派生一个新草稿版本（同 code、version+1），返回新版本编号")
    @Parameter(name = "id", description = "源定义编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('agent:def:create')")
    public CommonResult<Long> deriveDef(@RequestParam("id") Long id) {
        return success(defService.deriveDef(id));
    }

    @PostMapping("/copy")
    @Operation(summary = "复制成一个新智能体（新 code、version 从 1 起，配置整份搬过来），返回新定义编号")
    @PreAuthorize("@ss.hasPermission('agent:def:create')")
    public CommonResult<Long> copyDef(@Valid @RequestBody AgentDefCopyReqVO copyReqVO) {
        return success(defService.copyDef(copyReqVO.getId(), copyReqVO.getCode(), copyReqVO.getName()));
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除 Agent 定义（草稿与已停用可删；已发布须先停用）")
    @Parameter(name = "id", description = "编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('agent:def:delete')")
    public CommonResult<Boolean> deleteDef(@RequestParam("id") Long id) {
        defService.deleteDef(id);
        return success(true);
    }

    /**
     * 批量删除。判据与单删逐字一致（内置行拒、已发布拒），且<b>全成或全不成</b>——
     * 有一条不该删就整批拒绝，避免「删了一半才报错」让管理员分不清到底少了哪几行。
     * 权限沿用 {@code agent:def:delete}：批量只是单删的复数形式，不是另一种能力，
     * 单独给一个权限点会造出「能删一个却不能删多个」的割裂。
     */
    @DeleteMapping("/delete-list")
    @Operation(summary = "批量删除 Agent 定义（仅草稿与已停用可删；有一条不合规则整批拒绝）")
    @Parameter(name = "ids", description = "编号列表", required = true)
    @PreAuthorize("@ss.hasPermission('agent:def:delete')")
    public CommonResult<Boolean> deleteDefList(@RequestParam("ids") List<Long> ids) {
        defService.deleteDefList(ids);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得 Agent 定义（含完整 persona 原文，供编辑/查看）")
    @Parameter(name = "id", description = "编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('agent:def:query')")
    public CommonResult<AgentDefRespVO> getDef(@RequestParam("id") Long id) {
        AgentDefDO def = defService.getDef(id);
        AgentDefRespVO vo = BeanUtils.toBean(def, AgentDefRespVO.class);
        vo.setWorkflowDriven(isWorkflowDriven(def.getCode()));
        return success(vo);
    }

    @GetMapping("/page")
    @Operation(summary = "获得 Agent 定义分页")
    @PreAuthorize("@ss.hasPermission('agent:def:query')")
    public CommonResult<PageResult<AgentDefRespVO>> getDefPage(@Valid AgentDefPageReqVO pageReqVO) {
        PageResult<AgentDefDO> pageResult = defService.getDefPage(pageReqVO);
        PageResult<AgentDefRespVO> voPage = BeanUtils.toBean(pageResult, AgentDefRespVO.class);
        // 🔴 列表不带 persona 正文：一段人设可能几 KB，一页 20 行就是几十 KB 负载，而列表只需元信息。
        //    编辑/查看另调 GET /get?id 取完整 persona。置 null 而非不映射，避免 RespVO 字段漂移。
        // workflowDriven（B3）：逐行反查「是否有已发布 chatflow 驱动它」。管理页一页 ≤ 20 行，N 次轻量索引查询可接受
        //    （trigger_key 有索引；且只对 PUBLISHED 行查——草稿/停用 Agent 聊天入口选不到，谈不上「被驱动」）。
        voPage.getList().forEach(vo -> {
            vo.setPersona(null);
            vo.setWorkflowDriven("PUBLISHED".equals(vo.getStatus()) && isWorkflowDriven(vo.getCode()));
        });
        return success(voPage);
    }

    /**
     * 反查「是否存在一条已发布 chatflow 的 {@code trigger.chat.config.agentId} = 该 Agent code」（B3 只读标识）
     *
     * <p>复用聊天路径同款反查（{@code AgentWorkflowDefService.getPublishedChatTriggerByAgentCode}），
     * 保证「列表显示的驱动状态」与「聊天时实际是否走工作流」逐字一致——两者判据不同就会出现「标了却不生效」。
     */
    private boolean isWorkflowDriven(String code) {
        return workflowDefService.getPublishedChatTriggerByAgentCode(code) != null;
    }

    @PostMapping("/publish")
    @Operation(summary = "发布 Agent 定义（草稿 / 已停用 → PUBLISHED，按 id 精确发布该版本）")
    @Parameter(name = "id", description = "编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('agent:def:publish')")
    public CommonResult<Long> publishDef(@RequestParam("id") Long id) {
        return success(defService.publishDefById(id));
    }

    @PostMapping("/disable")
    @Operation(summary = "停用 Agent 定义（PUBLISHED → DISABLED，不再被选用）")
    @Parameter(name = "id", description = "编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('agent:def:publish')")
    public CommonResult<Boolean> disableDef(@RequestParam("id") Long id) {
        defService.disableDefById(id);
        return success(true);
    }

}
