package cn.enlev.ainb.module.agent.service.def;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.module.agent.controller.admin.def.vo.AgentDefPageReqVO;
import cn.enlev.ainb.module.agent.controller.admin.def.vo.AgentDefSaveReqVO;
import cn.enlev.ainb.module.agent.dal.dataobject.def.AgentDefDO;

import java.util.List;

/**
 * Agent 定义 Service —— P5 Agent Builder 第一刀
 *
 * <p>提供「智能体目录」的版本化 CRUD + 发布/停用，生命周期与 {@code AgentWorkflowDefService} 对齐
 * （DRAFT-only 就地编辑、发布/停用幂等）。
 *
 * <p>🔴 增量10（P5 第二刀）起，已发布定义可被对话流消费：{@link #getExecutableDef(Long)} 供聊天入口
 * 取「可用于对话的已发布 Agent」（persona 作系统提示词、modelId 固定模型）。工具集/知识库/绑定工作流
 * 因需先建消费方，顺延 slice-3。
 *
 * @author 走进新时代
 */
public interface AgentDefService {

    /**
     * 管理页分页（按 code/name 模糊、status 精确）
     */
    PageResult<AgentDefDO> getDefPage(AgentDefPageReqVO pageReqVO);

    /**
     * 按 id 取定义（含完整 persona）；查不到抛 {@code AGENT_DEF_NOT_EXISTS}（跨租户同文案防探测）
     */
    AgentDefDO getDef(Long id);

    /**
     * 取「可用于对话」的已发布定义（聊天入口按 Agent 路由用）。
     *
     * <p>🔴 fail-close：不存在、或状态非 PUBLISHED（草稿/已停用）一律抛 {@code AGENT_DEF_NOT_EXISTS}
     * ——不泄漏状态存在性，且与「前端选择器只列 PUBLISHED」自洽（陈旧 UI 选了已停用/草稿的边界也安全兜住）。
     */
    AgentDefDO getExecutableDef(Long id);

    /**
     * 取当前租户的「默认平台助手」内置定义（需求10）
     *
     * <p>聊天入口未选 Agent 时，系统提示词取自这一行的 persona —— 于是管理员能在「智能体管理」里
     * 在线调平台助手的措辞，不必改代码重新发版。
     *
     * <p>🔴 查不到<b>返回 null 而不抛异常</b>：调用方会回落到代码里的 {@code AgentPromptBuilder.BASE_PROMPT}。
     * 少一行种子数据（新环境没跑迁移、或有人手工删了）只该让助手用回出厂提示词，
     * 绝不该让整个对话 500 —— 那是把一个配置问题放大成可用性事故。
     */
    AgentDefDO getPlatformAssistant();

    /**
     * 新建定义（落 DRAFT 草稿，version = 同 code 现有最大 + 1），返回新定义 id
     */
    Long createDef(AgentDefSaveReqVO createReqVO);

    /**
     * 修改定义（仅 DRAFT 可改：改 name/persona/description）；非草稿抛 {@code AGENT_DEF_NOT_DRAFT}
     *
     * <p>🔴 系统内置行（{@code builtin=1}，即默认平台助手）是<b>唯一例外</b>：它虽为 PUBLISHED 也允许就地改，
     * 且只接受 name/persona/description 三列（其余列在「未选 Agent」那条路上不被读取，见 {@code AgentDefDO#builtin}）。
     */
    void updateDef(AgentDefSaveReqVO updateReqVO);

    /**
     * 基于任意状态的定义派生一个新 DRAFT 版本（同 code、version = 现有最大 + 1），返回新版本 id
     *
     * <p>需求11「编辑派生新版本」：已发布/已停用的定义是不可变快照，前端点「编辑」时先调本方法拿到新草稿 id
     * 再进编辑器，从而既满足「就地不改快照」的不变式，又不强迫管理员手抄一遍原配置去「新增」。
     * 内置行不参与版本化，抛 {@code AGENT_DEF_BUILTIN_READONLY}。
     */
    Long deriveDef(Long id);

    /**
     * 复制一个定义成「另一个智能体」的 DRAFT（新 code、version 从 1 起），返回新定义 id
     *
     * <p>需求11：与 {@link #deriveDef} 的区别在<b>意图</b>——派生是「同一个 Agent 的新版本」（沿用 code），
     * 复制是「照这个做一个新的 Agent」（换新 code、版本重新从 1 开始）。任意状态的定义都可作为复制源。
     *
     * @param id      源定义 id
     * @param newCode 新业务编码，必须未被占用（否则抛 {@code AGENT_DEF_CODE_EXISTS}）
     * @param newName 新展示名
     */
    Long copyDef(Long id, String newCode, String newName);

    /**
     * 删除定义（DRAFT 直接删；PUBLISHED 抛 {@code AGENT_DEF_PUBLISHED_NOT_DELETABLE}，须先停用；DISABLED 可删）
     *
     * <p>需求11：此前只放行 DRAFT，导致「发布了又想撤掉」的 Agent 只能永久停在停用列表里。
     * 放行 DISABLED 是安全的：停用已把它从聊天入口的选择器摘掉，不会有新对话再绑上来。
     */
    void deleteDef(Long id);

    /**
     * 批量删除定义，判据与 {@link #deleteDef} 逐字一致（内置行拒、PUBLISHED 拒）
     *
     * <p>🔴 语义是<b>全成或全不成</b>：先把 ids 全查回来校验一遍，有一条不该删就整批拒绝、一条都不动，
     * 而不是删到出错那条为止。批量操作里「删了一半」比「一条没删」难收拾得多——用户看到报错以为都没删，
     * 回列表才发现少了几行，还得逐个回想是哪些。有 id 查不到时抛 {@code AGENT_DEF_NOT_EXISTS}（同理，
     * 静默少删几条会让人以为「删不掉」）。
     *
     * @param ids 定义编号列表；空列表直接返回，不报错
     */
    void deleteDefList(List<Long> ids);

    /**
     * 发布定义（→ PUBLISHED，按 id 精确发布该版本）。已 PUBLISHED 幂等返回
     *
     * <p>DRAFT 与 DISABLED 都可发布：停用只是把它从聊天选择器摘掉，配置仍是完整可用的一份快照，
     * 「临时下线排障、修好再放回来」是常态。此前对 DISABLED 抛 {@code AGENT_DEF_NOT_DRAFT}，逼管理员
     * 为一个原封不动的 Agent 派生出内容完全相同的 v(n+1)，纯属多余动作。
     *
     * <p>🔴 重新发布同样要过发布期的工具只读校验（{@code AGENT_DEF_TOOL_INVALID}）：停用期间工具注册表
     * 可能已变（某个原本只读的工具被改成写操作），不重校验就等于把一个不再合规的 Agent 放回线上。
     *
     * @return 该定义 id
     */
    Long publishDefById(Long id);

    /**
     * 停用定义（PUBLISHED → DISABLED）。已 DISABLED 幂等；DRAFT 抛 {@code AGENT_DEF_NOT_PUBLISHED}
     */
    void disableDefById(Long id);

}
