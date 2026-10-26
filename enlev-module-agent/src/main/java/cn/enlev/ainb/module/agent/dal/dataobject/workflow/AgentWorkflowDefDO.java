package cn.enlev.ainb.module.agent.dal.dataobject.workflow;

import cn.enlev.ainb.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * Workflow 定义 DO（版本化）
 *
 * <h3>为什么整份 DSL 落一列，而不是拆成节点表/边表</h3>
 * DSL 是一个<b>整体契约</b>：校验、编译、版本绑定都以「整份 JSON」为单位。
 * 拆成关系表会逼着每次执行都重新 JOIN 拼装，还要维护「拼出来的和存进去的是否一致」——
 * 而 SAA 的 StateGraph 编译本来就吃一份完整定义。存原文，编译期解析，最简单也最不易错。
 *
 * <p>代价是无法用 SQL 查「哪些流程用了某个工具」。这个诉求 P4 可视化编排时
 * 可以在应用层建索引，不必现在就把存储结构复杂化。
 *
 * @author 走进新时代
 */
@TableName("agent_workflow_def")
@KeySequence("agent_workflow_def_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentWorkflowDefDO extends TenantBaseDO {

    private Long id;

    /**
     * 业务编码，同一 code 多版本共存
     */
    private String code;

    private String name;

    /**
     * 版本号，单调递增。执行实例绑定它，保证「按发布时的定义跑完」
     */
    private Integer version;

    /**
     * 状态，枚举 {@link cn.enlev.ainb.module.agent.enums.WorkflowDefStatusEnum}
     */
    private String status;

    /**
     * 是否主版本（增量14b / 版本管理）：同 code 多已发布版本中，被<b>显式指定</b>为当前生效的那一版。
     *
     * <p>🔴 执行入口 {@code AgentWorkflowDefMapper.selectPublishedByCode} 与
     * {@code selectPublishedChatTriggerByAgentCode} 的排序为 {@code primary_flag DESC, version DESC LIMIT 1}：
     * 主版本优先，无主版本时退化成「version 最大」——故存量行（默认 false）行为与增量14b 之前逐字一致，
     * 完全向后兼容。只有运维在版本抽屉里「设为主版本」后，生效版本才切到被指定的那一版（灰度回退用）。
     *
     * <p>🔴 「同 code 至多一个主版本」由 Service 层保证（{@code setPrimaryVersion} 先清同 code 全部
     * primary_flag、再置目标行），不用 DB 唯一约束（唯一约束无法表达「每 code 至多一个 true」）。
     * 只有 PUBLISHED 版本可被设为主版本（草稿/停用版设主版本无意义，Service 层 fail-close）。
     *
     * <p>字段名照本仓 Boolean 标志先例（{@code enabled}/{@code pinned}/{@code selected}）用无 is 前缀的名词，
     * 避开 {@code Boolean isXxx} 的 Lombok getter 命名歧义；列名 {@code primary_flag}（primary 是 SQL 保留字）。
     */
    private Boolean primaryFlag;

    private String description;

    /**
     * 来源，枚举 {@link cn.enlev.ainb.module.agent.enums.WorkflowSourceEnum}：
     * {@code MANUAL}=管理员在「流程编排」页手动新增；{@code AUTO}=规划器 / 智能助手对话由 LLM 生成后落库
     *
     * <p>🔴 由服务端按「走的哪个 createDef 入口」写死，<b>不接受前端传入</b>（SaveReqVO 无此字段），
     * 否则自动生成的行可以被伪装成手动，这一列就白加了。存量行由回填脚本按 {@code code LIKE 'wf_plan_%'}
     * 认定为 AUTO（两条自动路径共用该前缀），其余按建表默认值 MANUAL。
     *
     * <p>用途：列表标出来源 + 支持「只看手动添加的」筛选，把 LLM 悄悄生成的草稿噪音一次滤掉。
     */
    private String source;

    /**
     * Workflow DSL v1 JSON 原文
     */
    private String dsl;

    // ========== 触发器投影列（增量6） ==========
    // 🔴 这四列是「把 DSL 里的 trigger 提取成可 SQL 查询的投影」，由 {@code WorkflowTriggerExtractor}
    //    在 createDef 时填充。DSL 是整份原文（无法按里面的 trigger 检索），而触发器家族需要两类查询：
    //    ① form_event：来了一条表单变更事件，按 trigger_key 反查「哪些已发布流程在等这个事件」；
    //    ② schedule：轮询器按 trigger_next_fire_at<=now 捞「到点该触发」的流程。
    //    没有这四列，这两类查询都得把全表 DSL 捞出来在应用层解析——正是本 DO 注释里说的
    //    「无法用 SQL 查、P4 可在应用层建索引」的那层索引，触发器提前把最小必需部分落了地。

    /**
     * 触发器类型（短名，已剥掉 DSL 里的 {@code trigger.} 前缀）：{@code form_event} / {@code schedule} /
     * {@code chat} / {@code webhook}；NULL=该定义无 trigger（历史 def、或纯手动 run-stream）
     *
     * <p>🔴 存短名而非 DSL 原文的 {@code trigger.form_event}：与 {@code AgentWorkflowDefMapper} 的
     * 查询条件逐字一致（{@code eq(triggerType,"form_event")}）。归一化在 {@code WorkflowTriggerExtractor} 做。
     */
    private String triggerType;

    /**
     * 触发器匹配键（按 triggerType 语义不同）：
     * <ul>
     *   <li>form_event：{@code {formCode}:{eventType}}（eventType 小写，如 {@code test_demo_customer:create}）</li>
     *   <li>schedule：cron 表达式原文（仅作展示/去重标识，真正到点判定看 {@link #triggerNextFireAt}）</li>
     * </ul>
     * varchar(191)：utf8mb4 下单列可进索引的最大长度（191*4<767），formCode+cron 都够用。
     */
    private String triggerKey;

    /**
     * schedule 触发器的「下次应触发时间」（由 cron 算出）；form_event/chat/webhook 恒为 NULL
     *
     * <p>轮询器扫 {@code triggerNextFireAt<=now} 的行触发，触发前先 CAS 把它推进到 cron 的下一个
     * {@code next(now)}（{@code casAdvanceSchedule}）——先认领后触发，保证多实例/重复扫描下 at-most-once。
     */
    private LocalDateTime triggerNextFireAt;

    /**
     * 定义发布者用户编号 = 触发执行时「以谁的身份跑」（{@code AgentExecutionContext.ofSystem(ownerUserId,tenantId)}）
     *
     * <p>🔴 权限由 enlev 权限 API 按此 userId 从库实时派生（非内存提权），语义即「谁发布的自动化，以谁的权限跑」。
     * 为 NULL 时 {@code WorkflowTriggerInvoker} <b>fail-close 跳过 + 告警</b>，绝不降级为匿名/系统身份放行。
     * e2e 用 SQL 直插 def 时须显式设 owner_user_id=1（admin）。
     */
    private Long ownerUserId;

}
