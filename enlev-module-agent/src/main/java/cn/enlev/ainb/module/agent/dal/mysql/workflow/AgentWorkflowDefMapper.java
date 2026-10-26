package cn.enlev.ainb.module.agent.dal.mysql.workflow;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowDefPageReqVO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.enums.WorkflowDefStatusEnum;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Workflow 定义 Mapper
 *
 * @author 走进新时代
 */
@Mapper
public interface AgentWorkflowDefMapper extends BaseMapperX<AgentWorkflowDefDO> {

    /**
     * 按 code 取已发布的<b>当前生效</b>版本
     *
     * 执行入口固定走这个查询：同一 code 可能有多个 PUBLISHED 版本（createDef 出新版本不停用旧版），
     * 排序 {@code primary_flag DESC, version DESC LIMIT 1} —— 🔴 主版本（增量14b）优先，无主版本时
     * 退化成「version 最大」。存量行 primary_flag 全为默认 0/false，故排序结果与增量14b 之前逐字一致
     * （完全向后兼容）；只有显式「设为主版本」后，生效版本才切到被指定的那一版。
     * 租户隔离由框架自动加 tenant_id 保证。
     */
    default AgentWorkflowDefDO selectPublishedByCode(String code) {
        return selectOne(new LambdaQueryWrapperX<AgentWorkflowDefDO>()
                .eq(AgentWorkflowDefDO::getCode, code)
                .eq(AgentWorkflowDefDO::getStatus, WorkflowDefStatusEnum.PUBLISHED.getStatus())
                .orderByDesc(AgentWorkflowDefDO::getPrimaryFlag)
                .orderByDesc(AgentWorkflowDefDO::getVersion)
                .last("LIMIT 1"));
    }

    /**
     * 按 (code, version) 精确取某个版本
     *
     * sub_workflow 节点 pin 到具体版本时用。<b>不带 status 过滤</b> —— 是否可执行
     * 由 Service 判定（DISABLED 版本仍可被已 pin 的调用跑到，DRAFT 不可）。
     * 租户隔离由框架自动加 tenant_id 保证。
     */
    default AgentWorkflowDefDO selectByCodeAndVersion(String code, Integer version) {
        return selectOne(new LambdaQueryWrapperX<AgentWorkflowDefDO>()
                .eq(AgentWorkflowDefDO::getCode, code)
                .eq(AgentWorkflowDefDO::getVersion, version)
                .last("LIMIT 1"));
    }

    /**
     * 按 code 取<b>最新版本</b>（不限状态），供 {@code publishDef} 判定该发布哪一版（增量7a）
     *
     * <p>与 {@link #selectPublishedByCode} 的区别：那个只认 PUBLISHED（执行入口用），
     * 这个不带 status 过滤、取 version 最大的一行——发布动作要看「最新那一版现在是 DRAFT 还是已 PUBLISHED」，
     * 故不能只查已发布的。租户隔离由框架自动加 tenant_id 保证。
     */
    default AgentWorkflowDefDO selectLatestByCode(String code) {
        return selectOne(new LambdaQueryWrapperX<AgentWorkflowDefDO>()
                .eq(AgentWorkflowDefDO::getCode, code)
                .orderByDesc(AgentWorkflowDefDO::getVersion)
                .last("LIMIT 1"));
    }

    /**
     * 按 code 取<b>全部版本</b>（不限状态），version 倒序（增量14b / 版本管理抽屉）
     *
     * <p>版本抽屉列出同一 code 的所有版本行（DRAFT/PUBLISHED/DISABLED 都在），供对比 / 回滚 / 设主版本。
     * 🔴 不带 DSL 正文的裁剪在 Controller/Service 做（本方法返回完整 DO，调用方决定是否置空 dsl）。
     * 租户隔离由框架自动加 tenant_id 保证。
     */
    default List<AgentWorkflowDefDO> selectListByCode(String code) {
        return selectList(new LambdaQueryWrapperX<AgentWorkflowDefDO>()
                .eq(AgentWorkflowDefDO::getCode, code)
                .orderByDesc(AgentWorkflowDefDO::getVersion)
                .orderByDesc(AgentWorkflowDefDO::getId));
    }

    /**
     * 清除某 code 下所有版本的主版本标志（置 primary_flag=0），供 {@code setPrimaryVersion} 先清后置
     *
     * <p>🔴 保证「同 code 至多一个主版本」：设新主版本前先把同 code 全部清零，再把目标行置 1。
     * 只更新 primary_flag=1 的行（WHERE 带上），避免无谓写放大；返回影响行数（可能为 0=本就没有主版本）。
     * 租户隔离由框架自动加 tenant_id 保证（跨租户的同 code 绝不会被误清）。
     */
    default int clearPrimaryFlagByCode(String code) {
        LambdaUpdateWrapper<AgentWorkflowDefDO> wrapper = new LambdaUpdateWrapper<AgentWorkflowDefDO>()
                .eq(AgentWorkflowDefDO::getCode, code)
                .eq(AgentWorkflowDefDO::getPrimaryFlag, true)
                .set(AgentWorkflowDefDO::getPrimaryFlag, false);
        return update(null, wrapper);
    }

    /**
     * 按 code 取**当前仍然生效的、被显式钉住的**主版本；一个都没有则返回 null。
     *
     * <p>与 {@link #selectPublishedByCode} 的区别是关键的：那个回答「现在跑的是哪一版」，
     * 它在没有主版本时会**回落**成「version 最大」；本方法回答「有没有人明确指定过、且那次指定还站着」，
     * 回落一概不认。发布流程要靠这个区别来决定「这一发会不会顺手把线上切走」（B6 / S16）。
     *
     * <p>🔴 带 {@code status = PUBLISHED} 过滤，理由不是洁癖而是**这个状态真能走到**：
     * {@code disableDefById} 只翻 status、**不清 primary_flag**，于是「主版本被停用」是正常操作的产物。
     * 此时 {@link #selectPublishedByCode}（同样带 PUBLISHED 过滤）根本选不到它，生效版本已回落到
     * 「PUBLISHED 里 version 最大」。若本方法不过滤 status，发布就会认为「已经有人钉过了」而不补钉，
     * 于是这个 code 一直靠回落生效 —— B6 承诺的「此后发布不再动线上」对它永远不成立，
     * 且库里留着一行「已停用却还钉着主版本」的自相矛盾数据（版本抽屉会给它画「主版本」标签）。
     * 过滤之后，下一次发布会把钉移到真正生效的那一版（{@code markPrimary} 的先清后置顺手清掉停用行的钉），
     * 与系统自己的不变量「主版本必是 PUBLISHED」（{@code setPrimaryVersion} 拒绝非 PUBLISHED）对齐。
     *
     * <p>🔴 仍带 {@code version DESC} + {@code LIMIT 1}：库里**没有**唯一约束能表达
     * 「每 code 至多一个 true」（见 {@code AgentWorkflowDefDO#primaryFlag} 的注释），该不变量只由
     * 应用层的先清后置维持，并发下可能被破坏。真出现两行时这里取 version 大的那一行，
     * 与 {@link #selectPublishedByCode} 的裁决结果一致 —— 否则「查有没有主版本」和「查生效版本」
     * 会指向两行不同的数据，排查时无从对齐。
     */
    default AgentWorkflowDefDO selectPrimaryByCode(String code) {
        return selectOne(new LambdaQueryWrapperX<AgentWorkflowDefDO>()
                .eq(AgentWorkflowDefDO::getCode, code)
                .eq(AgentWorkflowDefDO::getStatus, WorkflowDefStatusEnum.PUBLISHED.getStatus())
                .eq(AgentWorkflowDefDO::getPrimaryFlag, true)
                .orderByDesc(AgentWorkflowDefDO::getVersion)
                .last("LIMIT 1"));
    }

    /**
     * 管理页分页（增量8 / P4）：按 code/name 模糊、status/triggerType/source 精确过滤
     *
     * <p>排序 {@code version DESC, id DESC}：同一 code 的多版本里新版在前，整体又按新建倒序，
     * 让编排者一眼看到「每个流程的最新版本」。租户隔离由 {@code TenantBaseDO} + TenantLineInnerInterceptor 自动拼
     * {@code tenant_id=?} 保证，A 租户绝查不到 B 租户的定义。
     *
     * <p>source 用普通等值即可（不必 {@code OR IS NULL}）：该列是 {@code NOT NULL DEFAULT 'MANUAL'}，
     * 加列时存量行已被填成 MANUAL，e2e 直插 SQL 不写该列也会落默认值，库里不存在 NULL。
     */
    default PageResult<AgentWorkflowDefDO> selectPage(AgentWorkflowDefPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<AgentWorkflowDefDO>()
                .likeIfPresent(AgentWorkflowDefDO::getCode, reqVO.getCode())
                .likeIfPresent(AgentWorkflowDefDO::getName, reqVO.getName())
                .eqIfPresent(AgentWorkflowDefDO::getStatus, reqVO.getStatus())
                .eqIfPresent(AgentWorkflowDefDO::getTriggerType, reqVO.getTriggerType())
                .eqIfPresent(AgentWorkflowDefDO::getSource, reqVO.getSource())
                .orderByDesc(AgentWorkflowDefDO::getVersion)
                .orderByDesc(AgentWorkflowDefDO::getId));
    }

    /**
     * 查当前租户全部<b>已发布</b>定义的「简版」行（名称目录用：code → name）
     *
     * <p>🔴 显式 {@code select} 只取 5 列，<b>不取 dsl</b>：一份 DSL 可能几十 KB（含内联 prompt），
     * 而名称目录只要「这个 code 叫什么」。{@link #selectPage} 是把 dsl 查出来再在 Controller 置 null，
     * 数据库那一趟的传输省不掉；这里在 SQL 层就不取。
     *
     * <p>排序 {@code primary_flag DESC, version DESC} 与 {@link #selectPublishedByCode} 同口径 ——
     * 同一 code 有多版时，「当前生效那一版」排在最前，调用方按 code 去重取首行即可。
     * 租户隔离由框架自动加 tenant_id 保证。
     */
    default List<AgentWorkflowDefDO> selectPublishedSimpleList() {
        return selectList(new LambdaQueryWrapperX<AgentWorkflowDefDO>()
                .select(AgentWorkflowDefDO::getId, AgentWorkflowDefDO::getCode,
                        AgentWorkflowDefDO::getName, AgentWorkflowDefDO::getVersion,
                        AgentWorkflowDefDO::getPrimaryFlag)
                .eq(AgentWorkflowDefDO::getStatus, WorkflowDefStatusEnum.PUBLISHED.getStatus())
                .orderByDesc(AgentWorkflowDefDO::getPrimaryFlag)
                .orderByDesc(AgentWorkflowDefDO::getVersion));
    }

    /**
     * 存量盘点（重构 S0）：取当前租户<b>全部未删行</b>，含 DSL 正文，顺序确定
     *
     * <p>与 {@link #selectPublishedSimpleList} 恰好相反的两处取舍，理由都在「盘点」这个用途上：
     * <ul>
     *   <li><b>必须取 dsl</b>：盘点要逐行跑 {@code validateDsl}，正文就是输入，省不掉。
     *       代价是一次把几十份 DSL 拉进内存 —— 这是低频运维动作（开闸前跑一次），可接受。</li>
     *   <li><b>不按 status 过滤</b>：DRAFT/PUBLISHED/DISABLED 都要。已发布行是「用户一改就撞闸」的那批，
     *       草稿是「本来就发不出去」的那批，而 DISABLED 行仍可能被 {@code rollbackToVersion} 当蓝本复制成新草稿
     *       （源版本可为任意状态）⟹ 三种状态都在闸门的射程内，少盘一种就会漏掉一类撞墙的人。</li>
     * </ul>
     *
     * <p>🔴 排序 {@code code ASC, version DESC}：同一 code 的各版本相邻、新版在前。盘点报告要能被<b>重跑并对比</b>
     * （每加一条规则就该重盘一次），无序结果会让两份报告没法逐行对照；相邻版本还让人一眼看出
     * 「v3 修掉了 v2 的哪个错」。不加 {@code id} 兜底是因为 (code, version) 在本表内已唯一。
     *
     * <p>🔴 租户隔离由 {@code TenantBaseDO} + TenantLineInnerInterceptor 自动拼 {@code tenant_id=?} 保证，
     * <b>绝不</b>为了「一次盘全库」而 {@code executeIgnore}（agent 架构红线）。跨租户盘点请用各租户身份分别跑。
     */
    default List<AgentWorkflowDefDO> selectListForAudit() {
        return selectList(new LambdaQueryWrapperX<AgentWorkflowDefDO>()
                .orderByAsc(AgentWorkflowDefDO::getCode)
                .orderByDesc(AgentWorkflowDefDO::getVersion));
    }

    // ========== 触发器查询（增量6） ==========

    /**
     * 按 form_event 触发键查「正在等这个表单变更事件」的已发布定义（供 {@code FormDataChangedTriggerListener} 用）
     *
     * <p>条件：status=PUBLISHED 且 trigger_type='form_event' 且 trigger_key={formCode}:{eventType}。
     * 这是「广播」语义——同一租户下所有订阅该键的已发布定义都会被返回、逐条无头拉起；
     * 同一表单同一事件被多个流程订阅是合法用法（各自独立执行）。
     *
     * <p>🔴 租户隔离：本表是 {@code TenantBaseDO}，TenantLineInnerInterceptor 自动拼 {@code tenant_id=?}。
     * 调用方（AFTER_COMMIT 监听器）跑在发布者的请求线程里，ambient 租户=请求租户，故租户 A 的表单变更
     * 绝匹配不到租户 B 的定义。triggerType 用短名 'form_event'，与 {@code WorkflowTriggerExtractor} 落库的短名逐字一致。
     */
    default List<AgentWorkflowDefDO> selectPublishedByFormEventTrigger(String triggerKey) {
        return selectList(new LambdaQueryWrapperX<AgentWorkflowDefDO>()
                .eq(AgentWorkflowDefDO::getStatus, WorkflowDefStatusEnum.PUBLISHED.getStatus())
                .eq(AgentWorkflowDefDO::getTriggerType, "form_event")
                .eq(AgentWorkflowDefDO::getTriggerKey, triggerKey));
    }

    /**
     * 按「绑定的 Agent code」查驱动它的已发布 chat 流程（增量12 / slice-3c，供聊天路径 {@code AgentAssistantServiceImpl} 用）
     *
     * <p>条件：status=PUBLISHED 且 trigger_type='chat' 且 trigger_key={@code agentCode}。{@code trigger_key} 由
     * {@code WorkflowTriggerExtractor.applyChat} 从 DSL 的 {@code trigger.chat.config.agentId} 投影而来（存 Agent 的 code，
     * 版本稳定键，见计划 DD2）。命中即「这个 Agent 由该 chatflow 驱动」——聊天路径把用户消息写进 state 变量 {@code input}
     * 跑该流程，而非走直连 LLM 问答。
     *
     * <p>与 form_event 的「广播」（一个事件唤醒多条）不同：一个 Agent 至多由一条 chatflow 驱动，故取<b>单条</b>。
     * 理论上多条已发布 chatflow 可绑同一 agentCode（边界），按 {@code primary_flag DESC, version DESC, id DESC}
     * 取第一条保证确定性（主版本优先，同增量14b 的 selectPublishedByCode；DD9②）。
     * 未发布（DRAFT/DISABLED）的流程不命中 → 聊天路径回落普通问答（DD9①，草稿不生效）。
     *
     * <p>🔴 租户隔离：本表是 {@code TenantBaseDO}，TenantLineInnerInterceptor 自动拼 {@code tenant_id=?}。调用方跑在
     * 聊天用户的请求线程里，ambient 租户=请求租户，故租户 A 的 Agent 绝匹配不到租户 B 的流程。triggerType 用短名 'chat'，
     * 与 {@code WorkflowTriggerExtractor} 落库的短名逐字一致。
     */
    default AgentWorkflowDefDO selectPublishedChatTriggerByAgentCode(String agentCode) {
        return selectOne(new LambdaQueryWrapperX<AgentWorkflowDefDO>()
                .eq(AgentWorkflowDefDO::getStatus, WorkflowDefStatusEnum.PUBLISHED.getStatus())
                .eq(AgentWorkflowDefDO::getTriggerType, "chat")
                .eq(AgentWorkflowDefDO::getTriggerKey, agentCode)
                .orderByDesc(AgentWorkflowDefDO::getPrimaryFlag)
                .orderByDesc(AgentWorkflowDefDO::getVersion)
                .orderByDesc(AgentWorkflowDefDO::getId)
                .last("LIMIT 1"));
    }

    /**
     * 按 webhook path 查「接这个入站 hook」的已发布流程（增量14c / P6，供 {@code AgentWebhookTriggerService} 用）
     *
     * <p>条件：status=PUBLISHED 且 trigger_type='webhook' 且 trigger_key={@code path}。{@code trigger_key} 由
     * {@code WorkflowTriggerExtractor.applyWebhook} 从 DSL 的 {@code trigger.webhook.config.path} 投影而来
     * （已校验为单段 URL 安全字符）。命中即「这个匿名入站 URL 由该流程接管」——验签通过后把请求体作为工作流输入无头拉起。
     *
     * <p>与 form_event 的「广播」不同：一个 path 至多由一条流程接管，故取<b>单条</b>。理论上多条已发布流程可配同一 path
     * （同 code 多版本、或不同 code 撞 path），按 {@code primary_flag DESC, version DESC, id DESC} 取第一条保证确定性
     * （主版本优先，同 {@link #selectPublishedByCode}）。未发布（DRAFT/DISABLED）不命中 → 入站回「不存在或未启用」。
     *
     * <p>🔴 租户隔离：本表是 {@code TenantBaseDO}，TenantLineInnerInterceptor 自动拼 {@code tenant_id=?}。
     * webhook 是匿名入站、无登录态，租户由调用方传的 {@code tenant-id} 请求头经 {@code TenantContextWebFilter} 落到
     * ambient（{@code TenantSecurityWebFilter} 校验其合法），故本查询在该 ambient 租户内过滤——A 租户的 hook
     * 绝匹配不到 B 租户的流程。<b>绝不用 {@code @TenantIgnore}/{@code executeIgnore} 关掉隔离</b>（agent 架构红线）。
     * triggerType 用短名 'webhook'，与 {@code WorkflowTriggerExtractor} 落库的短名逐字一致。
     */
    default AgentWorkflowDefDO selectPublishedByWebhookKey(String webhookKey) {
        return selectOne(new LambdaQueryWrapperX<AgentWorkflowDefDO>()
                .eq(AgentWorkflowDefDO::getStatus, WorkflowDefStatusEnum.PUBLISHED.getStatus())
                .eq(AgentWorkflowDefDO::getTriggerType, "webhook")
                .eq(AgentWorkflowDefDO::getTriggerKey, webhookKey)
                .orderByDesc(AgentWorkflowDefDO::getPrimaryFlag)
                .orderByDesc(AgentWorkflowDefDO::getVersion)
                .orderByDesc(AgentWorkflowDefDO::getId)
                .last("LIMIT 1"));
    }

    /**
     * 扫「到点该触发」的 schedule 定义（供 {@code WorkflowScheduleTriggerScheduler} 用）
     *
     * <p>条件：status=PUBLISHED 且 trigger_type='schedule' 且 trigger_next_fire_at<=now。
     * 与 form_event 的「事件驱动」相反，这是「时间驱动」：轮询器每 fixedDelay 扫一次到期行。
     *
     * <p>🔴 租户隔离：TenantLineInnerInterceptor 自动拼 {@code tenant_id=?}。调用方（轮询器）必须先在
     * {@code TenantUtils.execute(tenantId, …)} 里逐租户设好上下文，绝不 {@code executeIgnore}（agent 架构红线，
     * 同 {@code WorkflowWaitResumeScheduler}）。limit 限制单批，防一次触发过多打爆线程池。
     * 到期判定用 trigger_next_fire_at（cron 预先算好的绝对时间），不在 SQL 里解析 cron。
     */
    default List<AgentWorkflowDefDO> selectDueSchedules(LocalDateTime now, int limit) {
        return selectList(new LambdaQueryWrapperX<AgentWorkflowDefDO>()
                .eq(AgentWorkflowDefDO::getStatus, WorkflowDefStatusEnum.PUBLISHED.getStatus())
                .eq(AgentWorkflowDefDO::getTriggerType, "schedule")
                .le(AgentWorkflowDefDO::getTriggerNextFireAt, now)
                .orderByAsc(AgentWorkflowDefDO::getTriggerNextFireAt)
                .last("LIMIT " + limit));
    }

    /**
     * 原子推进 schedule 的下次触发时间（CAS，返回是否推进成功）
     *
     * <p>🔴 这是 schedule「at-most-once」的唯一闸门：轮询器多实例、或同一实例重复扫到同一行时，
     * {@code WHERE id=? AND trigger_next_fire_at=oldNextFire} 保证只有一个调用方能把它从旧值推进到
     * cron 的下一个 next(now)（影响 1 行），其余影响 0 行即「已被别的实例认领」→ 调用方跳过、不触发，
     * 杜绝同一到点被双跑。轮询器必须<b>先 CAS 推进、成功后再 invokeHeadless</b>（先认领后触发）。
     * 与 {@code AgentWorkflowExecutionMapper.casInterruptedToRunning} 是同一套 CAS 范式。
     */
    default boolean casAdvanceSchedule(Long id, LocalDateTime oldNextFire, LocalDateTime newNextFire) {
        LambdaUpdateWrapper<AgentWorkflowDefDO> wrapper = new LambdaUpdateWrapper<AgentWorkflowDefDO>()
                .eq(AgentWorkflowDefDO::getId, id)
                .eq(AgentWorkflowDefDO::getTriggerNextFireAt, oldNextFire)
                .set(AgentWorkflowDefDO::getTriggerNextFireAt, newNextFire);
        // entity 传 null：SET 由 wrapper 的 .set(...) 提供；返回影响行数 >0 即认领成功
        return update(null, wrapper) > 0;
    }

}
