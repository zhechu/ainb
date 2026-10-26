package cn.enlev.ainb.module.agent.service.workflow;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowDefAuditRespVO;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowDefCopyReqVO;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowDefCopyRespVO;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowDefPageReqVO;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowDefSaveReqVO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ChangeManifest;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.AgentValidateResult;

/**
 * Workflow 定义 Service
 *
 * <p>P1 只提供引擎运行所需的最小读取能力 + 一个用于落定义的写入口（供规划器/助手对话等程序化路径）。
 * 增量8 / P4 管理页补齐定义的 CRUD + 发布/停用（可视化编排本身仍是后续刀）。
 *
 * @author 走进新时代
 */
public interface AgentWorkflowDefService {

    /**
     * 按 code 取已发布的最新版本，没有则抛 {@code WORKFLOW_DEF_NOT_EXISTS}
     */
    AgentWorkflowDefDO getPublishedByCode(String code);

    /**
     * 按 id 取定义，没有则抛 {@code WORKFLOW_DEF_NOT_EXISTS}
     */
    AgentWorkflowDefDO getDef(Long id);

    /**
     * 反查「驱动某个 Agent 的已发布 chatflow」（增量12 / slice-3c）
     *
     * <p>接线 DSL 预留的 {@code trigger.chat}：发布一份 {@code trigger.chat.config.agentId=<Agent code>} 的工作流后，
     * {@code WorkflowTriggerExtractor} 把该 agentCode 投影进 {@code trigger_key}；聊天路径选中 Agent 后，
     * 用其 {@code code} 反查这条 chatflow——命中则本轮消息不走直连 LLM，而是跑工作流（见
     * {@code AgentAssistantServiceImpl.workflowRunStream}）。
     *
     * <p>🔴 只认 PUBLISHED：草稿/停用的绑定工作流不生效 → 返回 null，聊天路径逐字回落普通 LLM 聊天（DD9①）。
     * 同 Agent 被多条已发布 chatflow 绑定时按 {@code version desc, id desc} 取第一条（确定性，DD9②）。
     *
     * @param agentCode Agent 的业务编码（{@code AgentDefDO.code}，版本稳定，非 numeric id，见 DD2）
     * @return 命中的已发布 chatflow 定义；无绑定/未发布则返回 {@code null}（<b>不抛异常</b>，供调用方判空回落）
     */
    AgentWorkflowDefDO getPublishedChatTriggerByAgentCode(String agentCode);

    /**
     * 取可作为 sub_workflow 子流程内嵌执行的定义
     *
     * @param code    子流程业务编码（= 子 DSL 的 {@code id}，非 DB 主键）
     * @param version 指定版本；为空则取最新已发布版本（等价 {@link #getPublishedByCode}）
     * @return 定义 DO；查不到、或 pin 的版本是 DRAFT（草稿不可执行）→ 抛 {@code WORKFLOW_SUB_WORKFLOW_NOT_FOUND}
     */
    AgentWorkflowDefDO getSubWorkflowDef(String code, Integer version);

    /**
     * 落一份定义（新增一个版本）——<b>系统自动生成</b>入口
     *
     * <p>🔴 只给「不是管理员手点新增」的程序化路径用，当前两个调用方都由大模型驱动：
     * {@code AgentWorkflowPlannerController.planToWorkflow}（plan-as-workflow 生成器）与
     * {@code AgentAssistantServiceImpl.emitWorkflowDraft}（助手对话里的「搭流程」意图）。
     * 落库时 {@code source} 固定写 {@link cn.enlev.ainb.module.agent.enums.WorkflowSourceEnum#AUTO}，
     * 这样管理页能把「我没建过、LLM 悄悄生成的」行标出来并一键滤掉。
     * 管理员在页面上新建请走 {@link #createDef(AgentWorkflowDefSaveReqVO)}（source=MANUAL）。
     *
     * @param code   业务编码
     * @param name   名称
     * @param dsl    DSL JSON 原文
     * @param status 状态，见 {@link cn.enlev.ainb.module.agent.enums.WorkflowDefStatusEnum}
     * @return 新定义 id
     */
    Long createDef(String code, String name, String dsl, String status);

    /**
     * 管理页新建定义（增量8 / P4）：一律落 DRAFT，带 description，{@code source} 记为 MANUAL
     *
     * <p>与 {@link #createDef(String, String, String, String)} 共用「新增一个版本」逻辑，
     * 区别只是这个入口从 SaveReqVO 取全字段、状态固定 DRAFT（发布是独立动作 {@link #publishDefById}）。
     *
     * <p>🔴 {@code AgentWorkflowDefSaveReqVO} <b>刻意不含 source 字段</b>：来源由服务端按入口决定，
     * 若允许前端传入，自动生成的行就能被伪装成手动，该列的维护价值随之归零。
     *
     * @param createReqVO 新建信息（code/name/dsl/description）
     * @return 新定义 id
     */
    Long createDef(AgentWorkflowDefSaveReqVO createReqVO);

    /**
     * 复制一份定义为<b>新编码下的新草稿</b>（S5-b / 方案 §3-L4 4.1，修 G11+G12）
     *
     * <h3>与 {@link #rollbackToVersion} 的区别</h3>
     * 回滚是「同一条流程加一个新版本」（code 不变），复制是「另起一条流程」（code 必须新）。
     * 两者都产出 MANUAL 草稿、都复用 {@code insertNewVersion}，但只有复制会跑
     * {@code WorkflowCopyRewriter} —— 因为回滚要的恰恰是「与那一版逐字一致」，
     * 而复制若不改写建表名/表单编码/BPM key/webhook path，两条流程就会去抢同一个<b>不可逆</b>的对象。
     *
     * <h3>🔴 源可为任意状态</h3>
     * DRAFT / PUBLISHED / DISABLED 都能作为蓝本。这是本方法存在的核心理由之一：
     * 已发布的定义是执行实例绑定的不可变快照，<b>就地改不了</b>（见 {@link #updateDef} 的草稿守卫），
     * 用户面对一条不可编辑的流程时真正想做的就是「复制一份来改」。
     *
     * <h3>🔴 复制品一律 DRAFT + MANUAL</h3>
     * DRAFT：发布是独立动作（{@link #publishDefById}），且发布期才跑 S4b 的 V1~V19 闸门 ——
     * 复制这一步<b>不</b>校验 DSL，故它可以产出一份将来发不出去的草稿。
     * MANUAL：这一版是管理员点「复制」建出来的，即使蓝本当初由 LLM 自动生成（按「谁建的」记，同回滚）。
     *
     * <p>⚠️ 正因为复制不跑校验，{@code rewritten[]} 是这一步<b>唯一</b>的用户可见信号，
     * 前端必须弹出来，不能只 toast「复制成功」。
     *
     * @param reqVO 源编号 + 新编码/名称/描述 + 是否自动改写冲突配置（缺省按 true）
     * @return 新草稿的元信息 + 改写清单
     * @throws cn.enlev.ainb.framework.common.exception.ServiceException 源不存在 →
     *         {@code WORKFLOW_DEF_NOT_EXISTS}；新编码已被占用 → {@code WORKFLOW_DEF_CODE_DUPLICATE}；
     *         源 DSL 不是合法 JSON → {@code WORKFLOW_DSL_INVALID}；改写后触发器非法 →
     *         {@code WORKFLOW_TRIGGER_CONFIG_INVALID}（由 {@code insertNewVersion} 里的提取器抛）
     */
    AgentWorkflowDefCopyRespVO copyDef(AgentWorkflowDefCopyReqVO reqVO);

    /**
     * 发布某 code 的最新草稿版本：DRAFT → PUBLISHED（增量7a，plan-as-workflow 生成器落库后用）
     *
     * <p>语义：
     * <ul>
     *   <li>最新版是 DRAFT → 置为 PUBLISHED，返回其 id；</li>
     *   <li>最新版已是 PUBLISHED → <b>幂等</b>返回其 id（重复发布不报错、不新增版本）；</li>
     *   <li>该 code 无任何版本，或最新版是 DISABLED（曾发布、现停用，不属本方法职责）→ 抛 {@code WORKFLOW_DEF_NOT_EXISTS}。</li>
     * </ul>
     *
     * <p>🔴 只翻状态、不重跑 {@code triggerExtractor}：触发投影列在 {@link #createDef} 落库时已填好。
     *
     * @param code 业务编码
     * @return 被发布（或本就已发布）的定义 id
     */
    Long publishDef(String code);

    // ==================== 管理页 CRUD + 发布/停用（增量8 / P4） ====================

    /**
     * 分页获得定义（管理页列表）
     *
     * <p>租户隔离由 {@code TenantBaseDO} 自动保证。同 code 多版本时新版在前（见 mapper 排序）。
     */
    PageResult<AgentWorkflowDefDO> getDefPage(AgentWorkflowDefPageReqVO pageReqVO);

    /**
     * 修改定义（<b>仅 DRAFT</b>；非草稿抛 {@code WORKFLOW_DEF_NOT_DRAFT}）
     *
     * <p>🔴 只放行草稿：已发布/已停用的定义是执行实例绑定的版本快照，就地改其 DSL 会让在途实例 resume 时对不上节点。
     * 要改已发布流程请 {@link #createDef} 出新版本。修改会重跑触发器投影（{@code WorkflowTriggerExtractor}），
     * 故 DSL 触发器非法在此即抛 {@code WORKFLOW_TRIGGER_CONFIG_INVALID}。
     *
     * @param updateReqVO 修改信息（含 id）
     */
    void updateDef(AgentWorkflowDefSaveReqVO updateReqVO);

    /**
     * 删除定义（<b>草稿与已停用</b>可删；已发布抛 {@code WORKFLOW_DEF_NOT_DELETABLE}）
     *
     * <h3>🔴 状态闸门比 {@link #updateDef} 宽一档，判据在 {@code WorkflowDefStatusEnum#isDeletable}</h3>
     * 编辑只放行 DRAFT，删除放行 DRAFT + DISABLED。差别不是随手放宽：DISABLED 行既不该被就地改
     * （改了就等于偷改一个历史版本的语义），也没有任何理由必须留着。
     * PUBLISHED 一律拒删 —— 它是<b>正在被选用</b>的那一版（触发入口按 code 回落到它），
     * 删掉等于把一个对外承诺过的入口悄悄拔掉。要删先停用，让「不再接受新执行」显式发生过一次。
     *
     * <h3>🔴 过了状态闸门<b>不等于</b>删得掉：后面还有两道要扫别的行的闸门</h3>
     * 与 {@link #forceDeleteDef} 逐字相同的两道（实现共用同一份私有方法，判据不许有第二份副本）：
     * <ul>
     *   <li>仍被活定义的 sub_workflow pin 住 → {@code WORKFLOW_DEF_DELETE_PINNED}</li>
     *   <li>名下还有未终态执行 → {@code WORKFLOW_DEF_DELETE_HAS_UNFINISHED}</li>
     * </ul>
     * ⚠️ 特别是「草稿必无子流程引用」这句话<b>不成立</b>：pin 写在<b>别人的 DSL 里</b>、
     * 可以按 (code,version) 精确指向一条草稿，只是运行期解析不到
     * （{@link #getSubWorkflowDef} 对 DRAFT 抛 {@code WORKFLOW_SUB_WORKFLOW_NOT_FOUND}）。
     * 而 fail-close 那几条（今天 DSL 解析不了、明天修好照样去查那个 code）正是草稿也可能撞上的。
     * 故这里<b>没有</b>「草稿一律安全」的快速通道。
     *
     * <h3>级联：DISABLED 行会连带清执行史</h3>
     * 与 {@link #forceDeleteDef} 同一条级联（释放 checkpoint → 软删 node_execution → 软删 execution → 软删 def）。
     * DISABLED 行曾发布过、名下完全可能有执行史与节点 trace，只软删 def 会留下一堆指向已删 def 的孤儿行 ——
     * 正是 {@link #forceDeleteDef} 批评裸 SQL 的那个后果。草稿名下没有执行行，级联自然是空跑。
     *
     * @param id 编号；不存在抛 {@code WORKFLOW_DEF_NOT_EXISTS}
     */
    void deleteDef(Long id);

    /**
     * 批量删除定义：判据与 {@link #deleteDef} 逐字一致，且<b>全成或全不成</b>
     *
     * <h3>🔴 「全成或全不成」在这里不是整洁要求，是数据一致性要求</h3>
     * 级联的主导步骤 {@code BaseCheckpointSaver#release} 发的是真实的
     * {@code UPDATE GRAPH_THREAD SET is_released = TRUE}，<b>{@code @Transactional} 回滚不了它</b>
     * （那是 SAA 的表）。边删边校验的话，「第 3 行被拦」会留下前两行「执行行还在、快照已 released」的中间态，
     * 而没有任何接口能解释它。故实现是<b>两阶段</b>：先把所有行的三道闸门全部跑完，才开始动任何一行的数据；
     * 被拦时业务数据一行未动，返回的那句错误就是全部事实。
     *
     * <h3>🔴 批内互相引用不算「被 pin」</h3>
     * A 的 DSL pin 住了 B、而 A、B 在同一次批删里 ⟹ 两条都放行（批删之后 A 也没了，pin 不会悬空）。
     * 反过来，同 code 的 v1+v2 一起勾删、而<b>批外</b>有一条未 pin 版本的引用 ⟹ 整批拒绝：
     * v2 不能算作「同 code 还有别的活 PUBLISHED 行」的存活者。
     *
     * <h3>行为细则</h3>
     * <ul>
     *   <li>{@code ids} 为空或 null → 直接返回，一次 mapper 都不调（前端「未勾选」不该打一次废请求）。</li>
     *   <li>有 id 查不到（去重后数量对不上）→ 抛 {@code WORKFLOW_DEF_NOT_EXISTS}，<b>不</b>静默少删：
     *       静默成功会让用户以为勾中的都删了，刷新后仍有残留，看起来像「删不掉」。</li>
     *   <li>任何一行不合规 → 整批拒绝，错误文案点名那一行（{@code #id code@version}）。</li>
     *   <li>逐行顺序按调用方给的 id 顺序，不按 {@code IN} 的返回顺序 —— 否则同样的数据这次报 A、下次报 B，无从复现。</li>
     * </ul>
     *
     * @param ids 编号列表；重复 id 自动去重
     */
    void deleteDefList(java.util.List<Long> ids);

    /**
     * <b>管理员强制删除</b>定义（迭代七 D3）：<b>任意状态</b>都能删，并连带清掉它的执行史
     *
     * <h3>🔴 这一档为什么必须存在，而不是让管理员去改库</h3>
     * {@link #deleteDef} 拒删 PUBLISHED，于是库里会攒出一批「既删不掉、又不能用」的行：
     * e2e 与探针造出来的 PUBLISHED 流程、还在被触发入口选用但业务上已经废弃的流程。
     * 没有这一档时，清它们的唯一出路是裸 SQL —— 而裸 SQL 绕过全部业务判据，
     * 删完就是一堆指向不存在 def 的孤儿行（执行史、{@code sub_workflow} 的 pin），
     * 且没有任何日志说明「谁在什么时候删了什么」。把能力做进 Service，
     * 判据、级联、日志、权限四件事才有人守。
     *
     * <h3>🔴 与常规档<b>共用一份内核</b>，唯一差别是状态闸门</h3>
     * 三个入口（{@link #deleteDef} / {@link #deleteDefList} / 本方法）都落到实现类的
     * {@code doDeleteDefs}，pin 闸门、在途闸门、级联顺序、可重入语义<b>各只有一份代码</b>。
     * 本方法传 {@code checkStatus = false}，即跳过 {@code WorkflowDefStatusEnum#isDeletable} 那一道。
     * 判据不许有第二份副本 —— 各写一份必然漂移，而漂移的表现正是「常规档拒了、管理员档却过了」
     * （或反过来），事后没人说得清哪一份才是本意。
     *
     * <h3>🔴 「强制」强制的是什么，不强制的是什么</h3>
     * <ul>
     *   <li><b>强制掉</b>：状态闸门（PUBLISHED / DISABLED 也能删）。</li>
     *   <li><b>不</b>强制掉：引用关系与在途执行。这两条仍然 fail-close，理由见下面两个小节。
     *       ⚠️ 执行史的级联清理也<b>不再</b>是本档独有 —— 迭代八起常规档删 DISABLED 行同样级联。</li>
     * </ul>
     *
     * <h3>闸门一：仍被活定义的 sub_workflow pin 住 ⟹ 拒绝</h3>
     * pin 写在<b>别人的 DSL 里</b>（{@code config.workflowId} = 本 def 的 code，可选 {@code config.version}）。
     * 删掉被 pin 的行，引用方下次编译就抛 {@code WORKFLOW_SUB_WORKFLOW_NOT_FOUND} ——
     * 一条<b>活定义</b>被你弄坏了，而它的作者毫不知情。判据与运行期解析<b>同源</b>
     * （见 {@link #getSubWorkflowDef}）：
     * <ul>
     *   <li>pin 了具体 version 且正是本行 ⟹ 拦；pin 的是别的版本 ⟹ 与本行无关，不拦。</li>
     *   <li>没 pin version（走 {@code selectPublishedByCode} 回落）⟹ 只有当本行是 PUBLISHED
     *       <b>且</b>同 code 再没有别的活 PUBLISHED 行时才拦；否则回落到另一版，不产生孤儿。</li>
     *   <li>某条活定义的 DSL 解析不出来 ⟹ <b>也拦</b>，并在清单里点名「无法判定」。
     *       解析不了就证明不了它没 pin，而这是不可逆操作，取 fail-close 那一边。</li>
     * </ul>
     * 刻意<b>不</b>改成「顺手把引用方的 DSL 也改掉」：那要写别人的 PUBLISHED 定义，
     * 撞历史快照不可变纪律；且「改指向哪一版」只有引用方的作者能回答。
     *
     * <h3>闸门二：名下还有未终态执行 ⟹ 拒绝</h3>
     * RUNNING / INTERRUPTED / COMPENSATING 的行<b>还会有人写</b>（settle、反做落 COMPENSATED），
     * 删了等于抽走它脚下的地；status 为 NULL/未知值也一并拦（说不清就不许删）。
     * 处置动作是现成的：{@code POST /agent/workflow/cancel}。
     *
     * <h3>级联顺序：先释放 checkpoint，再动业务行</h3>
     * {@code GRAPH_THREAD} / {@code GRAPH_CHECKPOINT} 是 SAA 的表，<b>没有 tenant_id、也没有本平台任何业务外键</b>；
     * 「哪个 def 的哪次执行对应哪个 thread」这层关系<b>只存在执行行的 {@code thread_name} 上</b>。
     * 先删执行行再释放，那些快照就成了<b>永远无法寻址</b>的孤儿。故顺序固定：
     * ①逐条 {@code BaseCheckpointSaver#release}（唯一的真接口）→ ②按 executionId 批量软删 {@code agent_node_execution}
     * → ③软删 {@code agent_workflow_execution} → ④软删本 def。
     *
     * <p>⚠️ 「释放」<b>既不删 {@code graph_checkpoint} 行、也不删 {@code graph_thread} 行</b> ——
     * 2026-10-08 现场 + 字节码双向实测：{@code MysqlSaver#releasedCheckpoints} 只发一条
     * {@code UPDATE GRAPH_THREAD SET is_released = TRUE WHERE thread_name = ? AND is_released = FALSE}。
     * 快照行留在库里，只是从此读不出来（SAA 的 SELECT 带 {@code t.is_released != TRUE}）。
     * 故删完之后 {@code graph_thread} 仍留行、{@code is_released} 翻成 1，而 {@code graph_checkpoint} <b>行数不减</b> ——
     * 那是库的真实形态，对账时要按这个口径解释，🔴 不要为了「凑成 0」去裸删。
     * 🔴 在 1.1.2.2 这个版本里，<b>没有任何一条代码路径能物理删掉快照行</b>：
     * {@code MysqlSaver} 虽然声明了常量 {@code DELETE_CHECKPOINTS}
     * （{@code DELETE FROM GRAPH_CHECKPOINT WHERE checkpoint_id = ?}），但它<b>从未被引用</b>
     * ——{@code javap -p -c} 全文里这句 SQL 在方法体中的命中数是 <b>0</b>，是个死常量；
     * 而两个 {@code rollback(Connection, …)} 重载做的是 JDBC 的 {@code Connection.rollback()}
     * （回滚<b>未提交的事务</b>），同样不删行（取证见 {@code _logs/r10/28-mysqlsaver-release-bytecode.log}）。
     * 唯一真能带走快照的是 {@code graph_thread} 行上的外键 {@code ON DELETE CASCADE} ——
     * 而那要先删 {@code graph_thread}，SAA 没给这个接口，本平台也不该去裸删别人的表。
     * 故本方法的职责止于「让它读不出来」，<b>不含</b>「让它消失」。
     *
     * <h3>🔴 本方法<b>不可逆</b>，且权限点是单独一档</h3>
     * 挂 {@code agent:workflow:force-delete}（<b>不是</b> {@code agent:workflow:delete}）：
     * 本档是唯一能删 <b>PUBLISHED</b>（此刻正被触发入口选用）定义的路子，
     * 合成一档就等于「给了删停用版的权限，顺带给了拔掉线上入口的权限」。
     * ⚠️ 分档的理由<b>不再是</b>「常规档必无执行史」—— 迭代八起常规档删 DISABLED 行同样级联清执行史；
     * 真正的差别是<b>能不能动正在被选用的那一版</b>。该权限只授管理员角色。
     *
     * @param id 定义编号；不存在抛 {@code WORKFLOW_DEF_NOT_EXISTS}
     * @throws cn.enlev.ainb.framework.common.exception.ServiceException
     *         被活定义 pin 住 → {@code WORKFLOW_DEF_DELETE_PINNED}（点名引用方）；
     *         名下有未终态执行 → {@code WORKFLOW_DEF_DELETE_HAS_UNFINISHED}（点名执行 id 与状态）；
     *         释放 checkpoint 真失败 → {@code WORKFLOW_DEF_DELETE_CHECKPOINT_FAILED}（<b>业务行一行未动</b>）。
     *         🔴 「thread 不存在 / 早已释放」<b>不算</b>失败：SAA 对这两种情况抛
     *         {@code IllegalStateException}，而它们的目标状态本就已达成，拦下来会造成死结
     *         （一单两条执行、第二条失败 ⟹ 中止 ⟹ 重跑时第一条已 released ⟹ 再抛 ⟹ 这行永远删不掉），
     *         故实现里记一行 warn 后继续。详见实现类 {@code releaseCheckpoints} 的注释。
     */
    void forceDeleteDef(Long id);

    /**
     * 按 id 发布某个具体版本：DRAFT → PUBLISHED
     *
     * <p>与 {@link #publishDef(String)}（按 code 发布<b>最新版</b>）的区别：管理页列表按行操作，
     * 发布的是用户点的那一版（id 精确），而非「该 code 的最新版」——避免点 v1 却发布了 v3 的意外。
     * 已是 PUBLISHED 幂等返回；DISABLED 抛 {@code WORKFLOW_DEF_NOT_DRAFT}（停用版不可重发，请出新版本）。
     *
     * @param id 编号
     * @return 被发布（或本就已发布）的定义 id
     */
    Long publishDefById(Long id);

    /**
     * 按 id 停用定义：PUBLISHED → DISABLED（不再接受新执行，在途实例按快照跑完不受影响）
     *
     * <p>已是 DISABLED 幂等；DRAFT 抛 {@code WORKFLOW_DEF_NOT_PUBLISHED}（没发布过谈不上停用，直接删）。
     *
     * @param id 编号
     */
    void disableDefById(Long id);

    /**
     * 校验一份 DSL 原文（增量8 / P4 第二刀：新建-编辑表单的「校验」按钮）
     *
     * <p>只做<b>静态</b>那两层。要连「平台会不会拒绝这么改」一起看（V4/V5/V12），
     * 用三参重载 {@link #validateDsl(String, AgentExecutionContext, ChangeManifest)}。
     *
     * <p>⚠️ 「只有两层」说的是<b>要跑干跑的那些规则</b>。V16（外部能写的数据 → 写工具入参）
     * 是纯静态溯源，台账 C9 之后它在本重载上<b>照样生效</b>（{@code foldUntrustedWrites} 拿不到清单时
     * 自己追一遍），故两个重载的 warning 集合在这一条上没有差别。
     *
     * <h3>双层校验，与 plan-as-workflow 生成器同一套</h3>
     * 复用 {@code WorkflowDslValidator.validate}（收集式基础结构校验）+ 基础通过后再跑
     * {@code DslToStateGraphCompiler.dryRunValidate}（复用 buildStateGraph 的深层校验，无 DB/无 LLM/无副作用），
     * 合并两层结果。这正是 {@code WorkflowPlannerServiceImpl.validateTwoLayer} 的逻辑——那份是 private 且服务于
     * LLM 自修正回灌；这里把同一能力开放给管理页编排者，让「保存/发布前先看出问题」，而不是等执行时才炸。
     *
     * <h3>🔴 只读、绝不落库</h3>
     * 与 create/update 完全解耦：本方法不写任何表、不改任何状态，纯粹「给一份 DSL 打分」。
     * create/update 的行为<b>不因本方法而变</b>——草稿允许保存不完整的 DSL（WIP），是否校验由前端在保存前自行决定。
     *
     * <h3>JSON 本身不合法也不抛异常</h3>
     * {@code DslParser.parse} 对空/非法 JSON 抛 {@code WORKFLOW_DSL_INVALID}；本方法把它<b>捕获成一条 error</b>
     * 返回（{@code passed=false}），而非让异常冒泡到全局处理器——校验端点要统一返回结构化结果，
     * 好让前端无论「格式错」还是「结构错」都能内联展示。
     *
     * @param dsl DSL JSON 原文
     * @param ctx 身份快照（深层干跑 {@code buildStateGraph} 的占位身份；干跑不执行节点，故仅占位）
     * @return 合并两层的校验结果；{@link AgentValidateResult#isPassed()} 为真表示可作为可执行流程发布
     */
    AgentValidateResult validateDsl(String dsl, AgentExecutionContext ctx);

    /**
     * 同上，外加<b>第三层</b>：把一份已建好的变更清单里「平台已拒绝」的干跑结论折成 error（V4/V5/V12）
     *
     * <h3>🔴 清单要由调用方传进来，本方法绝不自己去建</h3>
     * 建一份清单最多要跑 15 秒的只读干跑。{@code /validate} 端点本来就要把清单塞进 RespVO，
     * 自己再建一遍就是同一趟干跑跑两次；生成器那条自修正回路最多 6 轮，更是 6 倍。
     * 所以本方法只做纯折叠（{@code WorkflowDslValidator#foldDryRunRejections}），探测留给调用方。
     *
     * <h3>传 null = 只要静态校验</h3>
     * 与两参重载等价。生成器走的就是这一档：它的自修正回路改的是 DSL 结构，
     * 而干跑结论（表名撞车、DDL 不合规）模型改一轮也变不了 —— 建表名撞车要人去决定换哪个名字。
     * 让它每轮都花 15 秒去问同一个问题没有意义。
     *
     * <h3>⚠️ {@code manifest} 与 {@code dsl} 必须是<b>同一份</b> DSL 派生的</h3>
     * 传错了不会报错，只会得到一份「errors 说的是 A、manifest 显示的是 B」的响应 ——
     * 而这恰恰是本方法要消灭的那个矛盾。调用方（{@code AgentWorkflowDefController}）
     * 用同一次 {@link #buildManifestOrNull} 的产物同时喂这两处，结构上就不会错。
     *
     * @param dsl      DSL JSON 原文
     * @param ctx      身份快照
     * @param manifest 已建好的变更清单；null 表示不折叠干跑结论
     * @return 合并三层的校验结果
     */
    AgentValidateResult validateDsl(String dsl, AgentExecutionContext ctx, ChangeManifest manifest);

    /**
     * 同上，外加「这条流程名下已经真跑过几次」—— 据此给<b>已落地的撞车</b>降一档（台账 #81 = S0 裁定① 的 A 方案）
     *
     * <h3>🔴 为什么这个参数必须由调用方给，而不是本方法自己查</h3>
     * 三参重载的调用方是生成器的自修正回路，它手上<b>没有</b> defId（DSL 还没落库），
     * 而它要的恰恰是「别降」：模型改一轮 DSL 改变不了「表名已被占用」，降成 warning 只会让它以为修好了。
     * 让本方法自己按 dsl 去猜身份，猜错的方向永远是「多放行一条」。
     *
     * <h3>降档的判据在 {@code WorkflowDslValidator#foldDryRunRejections}</h3>
     * 只有 {@code ChangeItem.conflict != null}（V5：表名/表单编码已被占用）才降，
     * V4（DDL 不合法）与 V12（主表缺列）跑过多少次都还是 error —— 那两类与「东西已经在平台上」无关。
     *
     * <h3>{@code defId} 传 null 或查不到 ⟹ 一档都不降</h3>
     * 降档是<b>放行</b>方向，故拿不到执行史时取 fail-close 的那一边（等价于三参重载）。
     * 特别是：id 在库里不存在时<b>不</b>抛 {@code WORKFLOW_DEF_NOT_EXISTS}，
     * 只读打分端点不该变成「先证明这行存在」。
     *
     * @param dsl      DSL JSON 原文（可以是未落库的草稿；{@code defId} 只用来取执行史，两者不要求同源）
     * @param ctx      身份快照
     * @param manifest 已建好的变更清单；null 表示不折叠干跑结论（此时 {@code defId} 也无关紧要）
     * @param defId    定义编号，用于按 code 展开全部版本数执行史；null 表示「当作没跑过」
     * @return 合并三层的校验结果
     */
    AgentValidateResult validateDsl(String dsl, AgentExecutionContext ctx, ChangeManifest manifest, Long defId);

    // ==================== 存量盘点（重构 S0：开闸前第 0 步） ====================

    /**
     * 盘点当前租户<b>全部</b>未删定义，逐行跑一遍体检（S0，fail-close 的必要前置）
     *
     * <h3>🔴 为什么开闸前必须有这一步</h3>
     * S4b 要把 V1~V19 接进发布闸门（{@code assertPublishable}），从此体检不过的流程发不出去。
     * 而库里的存量行大多是在这些规则<b>存在之前</b>落下的 —— 开闸等于<b>追溯既往</b>：
     * 一条跑了两年的已发布流程，可能因为「无头触发器里放了 {@code hitl.confirm}」（V13）
     * 或「webhook 用 {@code authType=none}」（V15）而再也不能重新发布，而它的作者对此毫不知情。
     * 追溯本身可能是对的（那些写法确实危险），但<b>必须先知道会追溯到谁</b>，才能决定要不要给存量豁免、
     * 要不要先通知责任人。本方法的产物就是那份名单，交人裁定后才进 S4b。
     *
     * <h3>🔴 与闸门同一套判据，绝不自己写一套</h3>
     * 实现直接调 {@link #validateDsl}——也就是 S4b 的 {@code assertPublishable} 将要调的那个方法。
     * 若盘点另写一套检查，它就是判据的第二份副本（方案 §2-D7），会与闸门各说各话，
     * 而「盘点说没事、发布却被拒」的清单比没有清单更坏：它让人带着错误的安心去开闸。
     *
     * <h3>{@code withDryRun}：干跑层默认不跑，且「没跑」写在响应里</h3>
     * {@code false}（默认）只跑静态两层（V16 属静态溯源，<b>两档都判</b>）；{@code true} 额外为每行建一次
     * 变更清单，把「平台已拒绝的干跑结论」（V4/V5/V12）折进来。默认关的两个理由：
     * <ul>
     *   <li><b>会超时</b>：建一份清单最多要跑 15 秒只读探测，几十行乘起来远超 HTTP 容忍度。</li>
     *   <li><b>对存量有系统性假阳性</b>：一条早已发布的建表流程，今天再探一次会得到「表名已被占用」——
     *       占用的正是它自己当初建的那张表。这类结论对「该不该开闸」毫无意义，混进头条数字只会吓人。
     *       故它必须能被单独跑、单独看（响应里逐行标 {@code dryRunState}），而不是默认掺在一起。</li>
     * </ul>
     *
     * <p>⚠️ 因此 {@code dryRunState=NOT_REQUESTED} 只说明 V4/V5/V12 缺席，<b>不</b>说明污点流向没查：
     * 台账 C9 之后 V16 拿不到清单也自己追一遍，两档文案逐字相同（实测 2026-10-07，对运行中的后端两档各跑一次）。
     *
     * <h3>🔴 逐行隔离：一行坏 DSL 不许拖垮整份盘点</h3>
     * 某行体检自己抛异常时，该行记 {@code auditFailure} 并计入 {@code auditFailed}，
     * <b>不计入</b> {@code failed}、也<b>不算</b> clean —— 「盘点工具坏了」与「这行违规」是两件事
     * （方案 §6.1：探测失败不是 error）。其余行照常出结果。
     *
     * <h3>🔴 只读、单租户</h3>
     * 不写任何表、不改任何状态（故权限归 {@code agent:workflow:query}）。
     * 范围是<b>当前 ambient 租户</b>：{@code agent_workflow_def} 是 {@code TenantBaseDO}，
     * 隔离由框架自动拼，且 agent 模块绝不用 {@code executeIgnore} 关掉它（架构红线）——
     * 要盘别的租户就用那个租户的身份再跑一次。
     *
     * @param withDryRun 是否为每行建变更清单以启用干跑层（V4/V5/V12；V16 不受本开关约束、两档都判）；见上
     * @return 盘点结果（非 null；库里没有定义时 {@code scanned=0} 且 {@code items} 为空列表）
     */
    AgentWorkflowDefAuditRespVO auditAll(boolean withDryRun);

    // ==================== 变更清单（S2-f：L2 的对外出口） ====================

    /**
     * 生成变更清单（<b>严格版</b>）：清单本身就是这次调用的产品，故任何失败都冒泡
     *
     * <p>给 {@code POST /agent/workflow-def/manifest} 用 —— 那是设计器「影响面」面板与审批卡的唯一数据源，
     * 清单生成不出来就该报错，不该返回一份看起来正常的空清单。
     *
     * <h3>🔴 {@code dsl} 与 {@code id} 必须<b>恰好给一个</b></h3>
     * 违反就抛 {@code WORKFLOW_MANIFEST_SOURCE_INVALID}，<b>不自己挑一个</b>：
     * 「我传了手上改到一半的草稿，看到的却是库里那一版的影响面」会让人据一份不是自己那份的清单点同意。
     * 给 {@code id} 时按 id 精确取那一版（同 {@link #getDef}，查不到抛 {@code WORKFLOW_DEF_NOT_EXISTS}），
     * 清单里的版本号也就取它的 {@code version}；给 {@code dsl} 是未落库的草稿，版本号记 {@code null}。
     *
     * <h3>⚠️ 含干跑，会打几次 infra/bpm 的<b>只读</b>接口</h3>
     * 方案 §6.1 认定发布是低频动作、可接受。infra 不可用时不会抛：那些项落成
     * {@code DryRunResult.Outcome.NOT_PROBED}（黄色「本次没校验」）并在 {@code stats.dryRunNotProbed} 上留计数。
     * 全程<b>只读、绝不落库</b>，与 {@link #validateDsl} 同一条纪律。
     *
     * <h3>DSL 结构有问题也照出清单</h3>
     * {@code WorkflowChangeManifestBuilder#build} 不抛业务异常：能认多少认多少，认不出来的落进
     * {@code unresolved[]}。但 JSON 本身 parse 不出来就没得认了 —— 那种情况抛
     * {@code WORKFLOW_DSL_INVALID}（与 {@link #validateDsl}「捕获成一条 error」不同，因为清单的返回形状里
     * 没有 errors 那一栏可以放它）。
     *
     * @param dsl        DSL 原文；与 {@code id} 二选一
     * @param id         定义编号；与 {@code dsl} 二选一
     * @param fromNodeId 起算节点（通常是 {@code hitl.*}）；传了就只展示「从这一步往后」，
     *                   裁掉了多少记在 {@code manifest.scope} 里。空白表示不裁剪
     * @return 变更清单（非 null）
     */
    ChangeManifest buildManifest(String dsl, Long id, String fromNodeId);

    /**
     * 生成变更清单（<b>附带版</b>）：清单只是别人响应里的一个附加字段，生成不出来就返回 {@code null}
     *
     * <p>给两个「清单是附带品」的响应用：{@code AgentWorkflowDefValidateRespVO}（体检结果顺带把影响面拿全，
     * 设计器少一次往返）与 {@code AgentWorkflowDraftRespVO}（AI 助手草稿卡）。
     *
     * <h3>🔴 为什么要兜住，而 {@link #buildManifest} 不兜</h3>
     * 判据是「兜住之后返回的东西像不像真的」：
     * <ul>
     *   <li>这里兜住 → 字段是 {@code null}，<b>看得见地缺席</b>。体检结果照常返回，用户不会因为一个附加面板
     *       出问题就丢掉他真正要的那份 errors/warnings。</li>
     *   <li>若是在 builder <b>内部</b>兜住某一步（比如溯源），返回的会是一份<b>残缺但看起来完整</b>的清单 ——
     *       那正是 {@code WorkflowChangeManifestBuilder#build} 里刻意不包 try/catch 的理由，两者不矛盾。</li>
     * </ul>
     *
     * <p>🔴 因此 {@code null} 的含义是「这份 DSL 没能生成清单」（原因已记进日志），
     * <b>绝不等于</b>「本次不产生任何写操作」。渲染方必须显示「清单不可用」，
     * 不能显示空清单的那句 headline —— 后者会让人放心地点同意。
     *
     * <p>入参互斥规则与 {@link #buildManifest} 完全一致，只是失败时返回 null 而不是抛。
     *
     * @return 清单；生成失败（含入参不合法、DSL parse 不出、builder 自身出错）返回 {@code null}
     */
    ChangeManifest buildManifestOrNull(String dsl, Long id, String fromNodeId);

    // ==================== 版本管理（增量14b） ====================

    /**
     * 列出某 code 的<b>全部版本</b>（version 倒序），供版本管理抽屉展示 / 对比。
     *
     * <p>🔴 返回的 DO <b>不含 DSL 正文</b>（置 null）：版本列表只需元信息（version/status/primaryFlag/触发器/时间），
     * 对比时前端另调 {@link #getDef(Long)} 按 id 取两份完整 DSL。一页版本通常个位数，但每份 DSL 可能几十 KB，
     * 列表带正文会是兆级负载（同分页列表裁 dsl 的理由）。租户隔离由 {@code TenantBaseDO} 保证。
     *
     * @param code 业务编码
     * @return 该 code 的所有版本行（可能为空列表，不抛异常）
     */
    java.util.List<AgentWorkflowDefDO> listVersionsByCode(String code);

    /**
     * 列出当前租户全部<b>已发布</b>定义的简版行（名称目录用：code → 中文名）
     *
     * <p>用途很具体：{@code sub_workflow} 节点的 {@code workflowId} 存的是<b>code</b>，
     * 于是聊天草稿卡与只读画布上显示的是 {@code wf_asset_check} 这种给机器看的串。
     * 名称目录拿这份 code→name 把它翻成人话（见 {@code AgentNameCatalogService}）。
     *
     * <p>🔴 返回行<b>不含 DSL 正文</b>（Mapper 层就没 select 该列）。同一 code 多版本时
     * 「当前生效那版」排最前，调用方按 code 去重取首行。
     *
     * @return 已发布定义简版行（可能为空列表，不抛异常）
     */
    java.util.List<AgentWorkflowDefDO> listPublishedSimple();

    /**
     * 回滚到某个历史版本：把该版本的 DSL/名称/描述<b>复制成一个新草稿版本</b>（增量14b）。
     *
     * <p>新草稿的 {@code source} 记为 MANUAL——这一版是管理员点「回滚」建出来的，即使蓝本当初由 LLM 自动生成。
     *
     * <h3>🔴 为什么是「复制成新草稿」而不是「就地把旧版本翻回 PUBLISHED」</h3>
     * 已发布/已停用的定义是<b>执行实例绑定的不可变快照</b>（见 {@link #updateDef} 的草稿守卫）：在途实例
     * resume 时要按发布时的节点图跑完，就地改状态/DSL 会让它们对不上。故回滚遵循本仓既定纪律
     * 「要改已发布流程请出新版本」——基于旧版 DSL 落一个 version=max+1 的 DRAFT，作者 review 后再发布，
     * 发布后它因 version 最大（或被设为主版本）而生效。既保留了完整版本留痕，又不动任何历史快照。
     *
     * <p>源版本可为任意状态（PUBLISHED/DISABLED/DRAFT 都能作为回滚蓝本）；查不到抛 {@code WORKFLOW_DEF_NOT_EXISTS}。
     * 新草稿会重跑触发器投影（{@code WorkflowTriggerExtractor}），故旧 DSL 的触发器若按现行规则非法会在此抛
     * {@code WORKFLOW_TRIGGER_CONFIG_INVALID}（正确：回滚出的草稿也应可发布）。
     *
     * @param id 源版本编号
     * @return 新草稿版本的编号
     */
    Long rollbackToVersion(Long id);

    /**
     * 把某个<b>已发布</b>版本设为主版本（增量14b）：同 code 下至多一个主版本，先清后置。
     *
     * <p>设主版本后，执行入口（{@code selectPublishedByCode} / chat 反查）优先选中它而非「version 最大」的那版，
     * 用于灰度回退（新版已发布但想让旧版继续生效）。目标必须是 PUBLISHED（草稿/停用版设主版本无意义，
     * 抛 {@code WORKFLOW_DEF_NOT_PUBLISHED}）；已是主版本则幂等返回。
     *
     * <p>🔴 只动 primary_flag 一列，不碰 DSL/status/version（历史快照不可变纪律）。租户隔离由框架保证。
     *
     * @param id 目标版本编号（须 PUBLISHED）
     */
    void setPrimaryVersion(Long id);

}
