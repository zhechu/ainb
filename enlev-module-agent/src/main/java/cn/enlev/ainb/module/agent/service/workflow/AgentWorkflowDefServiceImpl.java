package cn.enlev.ainb.module.agent.service.workflow;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowDefAuditRespVO;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowDefCopyReqVO;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowDefCopyRespVO;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowDefPageReqVO;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowDefSaveReqVO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentNodeExecutionDO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowExecutionDO;
import cn.enlev.ainb.module.agent.dal.mysql.def.AgentDefMapper;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentNodeExecutionMapper;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowDefMapper;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowExecutionMapper;
import cn.enlev.ainb.module.agent.enums.WorkflowDefStatusEnum;
import cn.enlev.ainb.module.agent.enums.WorkflowExecutionStatusEnum;
import cn.enlev.ainb.module.agent.enums.WorkflowSourceEnum;
import cn.enlev.ainb.module.agent.framework.agent.core.copy.WorkflowCopyRewriter;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslParser;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslToStateGraphCompiler;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.trigger.WorkflowTriggerExtractor;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ChangeManifest;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.WorkflowChangeManifestBuilder;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.AgentValidateResult;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.WorkflowDslValidator;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DEF_CODE_DUPLICATE;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DEF_DELETE_CHECKPOINT_FAILED;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DEF_DELETE_HAS_UNFINISHED;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DEF_DELETE_PINNED;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DEF_NOT_DELETABLE;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DEF_NOT_DRAFT;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DEF_NOT_EXISTS;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DEF_NOT_PUBLISHED;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DEF_VALIDATE_FAILED;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_MANIFEST_SOURCE_INVALID;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_SUB_WORKFLOW_NOT_FOUND;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_TRIGGER_CHAT_AGENT_NOT_FOUND;

/**
 * Workflow 定义 Service 实现
 *
 * @author 走进新时代
 */
@Service
@Slf4j
public class AgentWorkflowDefServiceImpl implements AgentWorkflowDefService {

    @Resource
    private AgentWorkflowDefMapper defMapper;

    /**
     * Agent 定义 Mapper（增量12 / slice-3c 批次 B2）：发布 chat 流程时反查「trigger.chat.config.agentId
     * 指向的 Agent code 是否存在」，不存在则拒发布（fail-close，防「配了不生效且无提示」，计划 RK2）。
     * 🔴 用 Mapper 而非 AgentDefService：避免与 B3（AgentDefController 反查工作流）形成 service 间循环依赖。
     */
    @Resource
    private AgentDefMapper agentDefMapper;

    /**
     * 触发器提取器（增量6）：createDef 落库前把 DSL 的 trigger 投影成可 SQL 查询列，并记录发布者 owner
     */
    @Resource
    private WorkflowTriggerExtractor triggerExtractor;

    /**
     * DSL 校验三件套（增量8 / P4 第二刀）：仅供 {@link #validateDsl} 只读打分，不参与 create/update 落库。
     * 与 {@code WorkflowPlannerServiceImpl} 注入的是同一批 bean，保证「管理页校验」与「生成器校验」口径一致。
     */
    @Resource
    private DslParser dslParser;
    @Resource
    private WorkflowDslValidator dslValidator;
    @Resource
    private DslToStateGraphCompiler dslCompiler;

    /**
     * 变更清单生成器（S2-f）：清单此前只有单测在跑，这里是它的第一个生产调用方。
     *
     * <p>⚠️ builder 反向依赖 {@code AgentToolDefService}，往这条链上加依赖前先确认不会绕回本 service
     * （Spring 的循环依赖要到启动时才炸）。
     */
    @Resource
    private WorkflowChangeManifestBuilder manifestBuilder;

    /**
     * 执行实例 Mapper（台账 #81）：数「这条流程 code 名下真跑过几次」，据此给<b>已落地的撞车</b>降一档。
     *
     * <p>🔴 用 Mapper 而非 {@code AgentWorkflowExecutionService}：本 service 已被执行侧反向依赖
     * （执行要按 code 反查已发布定义），走 service 就是 Spring 的循环依赖（同 {@link #agentDefMapper} 那条注释）。
     * 只读、只取一个 COUNT，也不需要执行侧的任何业务语义。
     */
    @Resource
    private AgentWorkflowExecutionMapper executionMapper;

    /**
     * 复制改写器（S5-b）：复制流程时逐格改写「会跟源流程抢同一份外部资源」的配置，并回显改了哪些
     *
     * <p>🔴 判据（哪一格算冲突、怎么改、改成什么后缀）只在改写器里有一份，本类不重抄任何字面量；
     * 本类只负责「取源定义 → 改写 → 落 DRAFT → 把清单原样带回」。
     */
    @Resource
    private WorkflowCopyRewriter copyRewriter;

    /**
     * 节点 trace Mapper（迭代七 D3）：删除时按 executionId 级联软删 {@code agent_node_execution}
     *
     * <p>迭代八起常规删除档（{@link #deleteDef} / {@link #deleteDefList}）也走这条级联 ——
     * 已停用的行曾发布过、名下可能有执行史与 trace，只软删 def 会留下指向已删 def 的孤儿行。
     *
     * <p>🔴 用 Mapper 而非 service，理由与 {@link #executionMapper} 那条注释逐字相同（避 Spring 循环依赖）。
     */
    @Resource
    private AgentNodeExecutionMapper nodeExecutionMapper;

    /**
     * checkpoint 存储（迭代七 D3）：删除时释放执行实例在 SAA 两张 {@code GRAPH_*} 表里的快照
     *
     * <p>迭代八起常规删除档也走这一步，理由同 {@link #nodeExecutionMapper}。
     *
     * <p>🔴 按 bean 名注入，与 {@code AgentWorkflowExecutionServiceImpl} 拿的是<b>同一个</b> MysqlSaver。
     * 沙箱那条路每次 {@code new MemorySaver()}、既不建执行行也不落库（见 {@code AgentWorkflowThreadName}
     * 的类注释），故库里凡有执行行，其快照必在这个 saver 下 —— 按名注入不会错拿沙箱的内存 saver。
     *
     * <p>{@code release} 是 SAA 唯一暴露的清理口（{@code BaseCheckpointSaver#release}）：
     * 把 {@code graph_thread.is_released} 置 TRUE，使快照从此对 SAA 不可见。
     * 🔴 它<b>不删</b> {@code graph_thread} 行本身、也<b>不删</b> {@code graph_checkpoint} 的行，
     * 这是库的真实形态，不是本方法的遗漏（取证见 {@link #releaseCheckpoints} 的 javadoc）。
     */
    @Resource(name = "workflowCheckpointSaver")
    private BaseCheckpointSaver checkpointSaver;

    /**
     * {@code sub_workflow} 的节点类型名（迭代七 D3 的 pin 扫描用）
     *
     * <p>🔴 运行期权威在 {@code DslToStateGraphCompiler#addSubWorkflowNode}（它才是真去解析子流程的那一处），
     * 这里只是<b>读</b>同一份 DSL 做引用扫描。
     *
     * <p>⚠️ 两个 config 键（{@code workflowId} / {@code version}）<b>刻意不抽常量</b>，就在读取点上写字面量，
     * 与 {@code DslToStateGraphCompiler#addSubWorkflowNode} / {@code WorkflowDslValidator#validateSubWorkflowRefs}
     * 同款。这不是省事：{@code NodeIoContractRuntimeReadScanTest} 按<b>源码字面量</b>扫 {@code service/workflow}，
     * 「{@code node.configStr("workflowId")}」是它认得的直读形态（能归属到 {@code sub_workflow} 的契约键），
     * 而抽成 {@code private static final String} 会退化成一个「裸字面量」噪声位 —— 那条扫描的第 ③ 派生规则
     * 立刻要求逐处认领「它属哪个命名空间、与契约同名键为什么不是一回事」。
     * 可这里<b>就是</b>在读那份契约键，答不出「不是一回事」，硬认领等于给扫描喂一句假话。
     * 实测过：抽常量的第一版正是这么把 {@code readFormDomainIsProven} 打红的（2026-10-08）。
     */
    private static final String NODE_TYPE_SUB_WORKFLOW = "sub_workflow";

    @Override
    public AgentWorkflowDefDO getPublishedByCode(String code) {
        AgentWorkflowDefDO def = defMapper.selectPublishedByCode(code);
        if (def == null) {
            throw exception(WORKFLOW_DEF_NOT_EXISTS);
        }
        return def;
    }

    @Override
    public AgentWorkflowDefDO getDef(Long id) {
        AgentWorkflowDefDO def = defMapper.selectById(id);
        if (def == null) {
            throw exception(WORKFLOW_DEF_NOT_EXISTS);
        }
        return def;
    }

    @Override
    public AgentWorkflowDefDO getPublishedChatTriggerByAgentCode(String agentCode) {
        // 反查「驱动该 Agent 的已发布 chatflow」（增量12 / slice-3c）。查不到返回 null（不抛）——
        // 调用方（聊天路径）判空后逐字回落普通 LLM 聊天；草稿/停用/填错 agentId 都表现为「无绑定」，符合 DD9①。
        return defMapper.selectPublishedChatTriggerByAgentCode(agentCode);
    }

    @Override
    public AgentWorkflowDefDO getSubWorkflowDef(String code, Integer version) {
        AgentWorkflowDefDO def = version == null
                ? defMapper.selectPublishedByCode(code)
                : defMapper.selectByCodeAndVersion(code, version);
        // 查不到，或 pin 的版本还是草稿（DRAFT 不可执行）→ 统一「子流程不可用」，跨租户同文案防探测
        if (def == null || WorkflowDefStatusEnum.DRAFT.getStatus().equals(def.getStatus())) {
            throw exception(WORKFLOW_SUB_WORKFLOW_NOT_FOUND,
                    version == null ? code : code + "@" + version);
        }
        return def;
    }

    @Override
    public Long createDef(String code, String name, String dsl, String status) {
        // 🔴 本入口只服务「系统自动生成」的两条路径（规划器 plan-workflow / 助手对话 BUILD 意图），
        //    管理员在页面上点「新增」走的是下面的 createDef(SaveReqVO)，故这里来源固定 AUTO。
        return insertNewVersion(code, name, dsl, status, null, WorkflowSourceEnum.AUTO.getSource());
    }

    @Override
    public Long createDef(AgentWorkflowDefSaveReqVO createReqVO) {
        // 🔴 查重（S5-b / 方案 §2-D6，修 G12）：管理页「新增」撞上已有 code 时，绝不能静默变成别人的新版本
        validateCodeNotUsed(createReqVO.getCode());
        // 管理页新建：一律落 DRAFT（草稿），发布是独立动作（publishDefById）
        return insertNewVersion(createReqVO.getCode(), createReqVO.getName(), createReqVO.getDsl(),
                WorkflowDefStatusEnum.DRAFT.getStatus(), createReqVO.getDescription(),
                WorkflowSourceEnum.MANUAL.getSource());
    }

    @Override
    public AgentWorkflowDefCopyRespVO copyDef(AgentWorkflowDefCopyReqVO reqVO) {
        // 源可为任意状态（PUBLISHED / DISABLED / DRAFT 都允许被抄）：复制是「拿它当模板」，不是「执行它」。
        // 源不存在直接抛 WORKFLOW_DEF_NOT_EXISTS（getDef 的语义），跨租户由 DO 的租户拦截挡住。
        AgentWorkflowDefDO source = getDef(reqVO.getId());
        String newCode = reqVO.getNewCode();
        validateCodeNotUsed(newCode);

        // rewriteConflicts 为 null 按 true 处理：默认要指向「不会撞车」那一侧（VO 上有说明）。
        // ⚠️ 关掉改写也照样返回逐格清单，只是每格都是「未改写 + 原因」——用户至少知道要手工改什么。
        WorkflowCopyRewriter.Result result = copyRewriter.rewrite(source.getDsl(), newCode, reqVO.getNewName(),
                reqVO.getNewDescription(), !Boolean.FALSE.equals(reqVO.getRewriteConflicts()));

        // 描述留空则沿用源流程的：改写器对空描述是「原样保留 DSL 里那一格」，这里的列同口径，
        // 两处不一致会出现「列表里描述是旧的、点进去 DSL 里描述是空的」。
        Long newId = insertNewVersion(newCode, reqVO.getNewName(), result.dsl(),
                WorkflowDefStatusEnum.DRAFT.getStatus(),
                StrUtil.blankToDefault(reqVO.getNewDescription(), source.getDescription()),
                WorkflowSourceEnum.MANUAL.getSource());

        // 🔴 回读而不是就地拼：version 由 insertNewVersion 按「同 code 现有最大 + 1」算，
        //    在这里写死 1 就是那份编号逻辑的第二份副本（新编码下确实是 1，但那是巧合不是契约）。
        AgentWorkflowDefCopyRespVO respVO = BeanUtils.toBean(getDef(newId), AgentWorkflowDefCopyRespVO.class);
        respVO.setRewritten(result.rewritten());
        return respVO;
    }

    /**
     * 断言这个 code 名下还没有任何一行（同租户），有就抛 {@code WORKFLOW_DEF_CODE_DUPLICATE}
     *
     * <h3>🔴 只准挂在「新开一个 code」的两条路径上：{@link #createDef(AgentWorkflowDefSaveReqVO)} 与
     * {@link #copyDef}。<b>绝不能下沉进 {@link #insertNewVersion}</b></h3>
     * 那方法有 3 个调用方，其中 {@link #rollbackToVersion} 是<b>故意</b>传同一个 code 的
     * （回滚 = 给这条流程加一个新版本），下沉进去回滚当场打死；
     * 而引擎入口 {@code createDef(code,name,dsl,status)} 服务的是「AI 生成一条流程」，
     * 生成端本来就要能反复重试同一个 code（第二次是加版本，不是重名）。
     *
     * <h3>⚠️ 与 {@code AgentDefServiceImpl.validateCodeNotReserved} 不是一回事，不要照抄</h3>
     * 那个方法查的是「撞上了框架保留前缀」（另一张表 {@code agent_def} 的命名空间规矩），
     * 这里查的是「撞上了同表已有的行」。workflow 侧的保留字校验在 DSL 校验器里，不归本方法管。
     *
     * <h3>为什么用 selectCount 而不是 selectList</h3>
     * {@code dsl} 是 mediumtext，为了判个存在性把该 code 名下每一版的整份 DSL 都拉回来是白烧内存。
     */
    private void validateCodeNotUsed(String code) {
        if (defMapper.selectCount(AgentWorkflowDefDO::getCode, code) > 0) {
            throw exception(WORKFLOW_DEF_CODE_DUPLICATE, code);
        }
    }

    /**
     * 落一个新版本（code 相同则 version = 现有最大 + 1），createDef 的两个入口 + 复制 + 版本回滚共用
     *
     * <p>🔴 <b>不要在这里加 code 查重</b>：回滚是故意传同一个 code 的，加了当场打死。
     * 查重只挂在「新开一个 code」的两条路径上，见 {@link #validateCodeNotUsed}。
     *
     * @param status      为空按 DRAFT 兜底
     * @param description 描述，可空（引擎入口 createDef(code,name,dsl,status) 不带）
     * @param source      来源，见 {@link WorkflowSourceEnum}；由调用方按「走的哪个入口」写死，不接受前端传入
     */
    private Long insertNewVersion(String code, String name, String dsl, String status,
                                  String description, String source) {
        // 版本号 = 同 code 现有最大版本 + 1（单调递增，执行实例绑定它，见 DO 注释）
        List<AgentWorkflowDefDO> existing = defMapper.selectList(AgentWorkflowDefDO::getCode, code);
        int nextVersion = existing.stream()
                .map(AgentWorkflowDefDO::getVersion)
                .filter(java.util.Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;

        AgentWorkflowDefDO def = new AgentWorkflowDefDO();
        def.setCode(code);
        def.setName(name);
        def.setVersion(nextVersion);
        def.setStatus(status == null ? WorkflowDefStatusEnum.DRAFT.getStatus() : status);
        def.setDsl(dsl);
        def.setDescription(description);
        def.setSource(source);
        // 增量6：从 DSL 提取 trigger 投影列（trigger_type/key/next_fire_at）+ 发布者 owner_user_id。
        // 放在 setDsl 之后、insert 之前——提取器要读 dsl，且这些列必须在落库前补齐。
        // 触发器配置非法（form_event 缺 formCode、cron 不合法、eventType 写错、webhook 不配鉴权等）在此抛
        // WORKFLOW_TRIGGER_CONFIG_INVALID 拒绝落库：触发是无头启动的，运行期没人在旁边看报错，只能在写进去之前就 fail-loud。
        // 🔴 时机是「落库前」而不是「发布期」——publishDef 只翻状态、不调提取器；发布期那一道是 WorkflowDslValidator 的 V1~V19
        triggerExtractor.apply(def);
        defMapper.insert(def);
        log.info("[insertNewVersion][新增 Workflow 定义 code({}) version({}) status({}) source({}) id({})]",
                code, nextVersion, def.getStatus(), source, def.getId());
        return def.getId();
    }

    @Override
    public Long publishDef(String code) {
        // 取该 code 的最新版本（不限状态）：发布动作要看「最新那一版现在是 DRAFT 还是已 PUBLISHED」
        AgentWorkflowDefDO latest = defMapper.selectLatestByCode(code);
        if (latest == null) {
            throw exception(WORKFLOW_DEF_NOT_EXISTS);
        }
        // 已发布：幂等返回（重复发布不报错、不新增版本）——生成端点可能因重试/二次确认再调一次
        if (WorkflowDefStatusEnum.PUBLISHED.getStatus().equals(latest.getStatus())) {
            return latest.getId();
        }
        // 只有 DRAFT 能发布；DISABLED（曾发布、现停用）不属本方法职责，与「不存在」同文案防探测
        if (!WorkflowDefStatusEnum.DRAFT.getStatus().equals(latest.getStatus())) {
            throw exception(WORKFLOW_DEF_NOT_EXISTS);
        }
        // 🔴 发布期 fail-close：chat 流程绑定的 Agent code 必须存在（增量12 / slice-3c 批次 B2）
        validateChatBinding(latest);
        // 🔴 发布闸门（S4b）：V1~V19 体检不通过就不许翻状态
        assertPublishable(latest);
        // 只翻状态：用「只带 id + status 的部分 DO」updateById，其余列（含触发投影列、dsl）保持不动
        AgentWorkflowDefDO update = new AgentWorkflowDefDO();
        update.setId(latest.getId());
        update.setStatus(WorkflowDefStatusEnum.PUBLISHED.getStatus());
        defMapper.updateById(update);
        log.info("[publishDef][发布 Workflow 定义 code({}) version({}) id({}) DRAFT→PUBLISHED]",
                code, latest.getVersion(), latest.getId());
        return latest.getId();
    }

    // ==================== 管理页 CRUD + 发布/停用（增量8 / P4） ====================

    @Override
    public PageResult<AgentWorkflowDefDO> getDefPage(AgentWorkflowDefPageReqVO pageReqVO) {
        return defMapper.selectPage(pageReqVO);
    }

    @Override
    public void updateDef(AgentWorkflowDefSaveReqVO updateReqVO) {
        AgentWorkflowDefDO existing = getDef(updateReqVO.getId());
        // 🔴 只放行草稿：已发布/已停用是执行实例绑定的版本快照，就地改 DSL 会让在途实例 resume 对不上节点。
        //    判据走 WorkflowDefStatusEnum#isDraft —— 与下发给前端的 AgentWorkflowDefRespVO#getEditable()
        //    同一个方法，两者不可能分岔（方案 §G14）。
        if (!WorkflowDefStatusEnum.isDraft(existing.getStatus())) {
            throw exception(WORKFLOW_DEF_NOT_DRAFT, existing.getStatus());
        }
        // 部分 DO：只带 id + 允许改的列（name/description/dsl + 触发投影列），其余列（code/version/status）不动。
        // MP updateById 默认 NOT_NULL 策略，null 字段不进 SET，故未提供的列保持原值。
        AgentWorkflowDefDO update = new AgentWorkflowDefDO();
        update.setId(existing.getId());
        update.setName(updateReqVO.getName());
        update.setDescription(updateReqVO.getDescription());
        update.setDsl(updateReqVO.getDsl());
        // DSL 可能改了触发器，重跑投影（apply 内部 dslParser.parse 顺带做「可解析」守卫，非法即抛）。
        // 🔴 apply 会把 ownerUserId 重置为当前编辑者——草稿的 owner 是临时的（发布时才成为运行身份），可接受。
        triggerExtractor.apply(update);
        defMapper.updateById(update);
        log.info("[updateDef][修改 Workflow 草稿定义 id({}) code({})]", existing.getId(), existing.getCode());
    }

    @Override
    public void deleteDef(Long id) {
        AgentWorkflowDefDO existing = getDef(id);
        CascadeResult result = doDeleteDefs(List.of(existing), Set.of(id), true).get(0);
        log.info("[deleteDef][删除 Workflow 定义 id({}) code({}) version({}) 原状态({})；"
                        + "级联：软删执行 {} 条、软删节点 trace {} 行、释放 checkpoint 的 thread {} 条]",
                id, existing.getCode(), existing.getVersion(), existing.getStatus(),
                result.deletedExecutions(), result.deletedNodes(), result.releasedThreads());
    }

    @Override
    public void deleteDefList(List<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return;
        }
        // 🔴 先去重再比数量：前端复选框不会给重复 id，但接口是公开的，
        //    拿原始 ids.size() 去比会让「同一个 id 勾两次」被误判成「有 id 查不到」。
        Set<Long> distinctIds = new LinkedHashSet<>(ids);
        List<AgentWorkflowDefDO> defs = defMapper.selectByIds(new ArrayList<>(distinctIds));
        // 数量对不上说明有 id 查不到（被别人先删了 / 跨租户）。此时报「不存在」而不是静默少删几条：
        // 静默成功会让用户以为勾中的都删了，而列表刷新后仍有残留，看起来像「删不掉」。
        if (defs.size() != distinctIds.size()) {
            throw exception(WORKFLOW_DEF_NOT_EXISTS);
        }
        // 🔴 按调用方给的 id 顺序走，不按 IN 的返回顺序：闸门是「撞上一条就整批拒绝」，
        //    顺序不确定就意味着同一批同样的数据、这次报 A 不合规、下次报 B，无从复现。
        Map<Long, AgentWorkflowDefDO> byId = new HashMap<>(defs.size());
        for (AgentWorkflowDefDO def : defs) {
            byId.put(def.getId(), def);
        }
        List<AgentWorkflowDefDO> targets = new ArrayList<>(defs.size());
        for (Long id : distinctIds) {
            targets.add(byId.get(id));
        }

        List<CascadeResult> results = doDeleteDefs(targets, distinctIds, true);
        int releasedThreads = 0;
        int deletedExecutions = 0;
        int deletedNodes = 0;
        for (CascadeResult result : results) {
            releasedThreads += result.releasedThreads();
            deletedExecutions += result.deletedExecutions();
            deletedNodes += result.deletedNodes();
        }
        log.info("[deleteDefList][批量删除 Workflow 定义 ids({})；级联：软删执行 {} 条、软删节点 trace {} 行、"
                        + "释放 checkpoint 的 thread {} 条]",
                distinctIds, deletedExecutions, deletedNodes, releasedThreads);
    }

    @Override
    public void forceDeleteDef(Long id) {
        AgentWorkflowDefDO target = getDef(id);
        CascadeResult result = doDeleteDefs(List.of(target), Set.of(id), false).get(0);
        log.info("[forceDeleteDef][管理员强制删除 Workflow 定义 id({}) code({}) version({}) 原状态({})；"
                        + "级联：软删执行 {} 条、软删节点 trace {} 行、释放 checkpoint 的 thread {} 条"
                        + "（graph_thread 行按 SAA 语义保留、只翻 is_released）]",
                id, target.getCode(), target.getVersion(), target.getStatus(),
                result.deletedExecutions(), result.deletedNodes(), result.releasedThreads());
    }

    /**
     * 删除的唯一内核：常规档（{@link #deleteDef} / {@link #deleteDefList}）与管理员档
     * （{@link #forceDeleteDef}）都走这里，{@code checkStatus} 是两档之间<b>唯一</b>的差别
     *
     * <h3>🔴 为什么是「阶段一全部过闸门、阶段二才动数据」，而不是逐行「过闸门 + 删」</h3>
     * 不是整洁问题 —— {@code checkpointSaver.release} 发的是真实的
     * {@code UPDATE GRAPH_THREAD SET is_released = TRUE}，<b>{@code @Transactional} 回滚不了它</b>
     * （那是 SAA 的表，不在本平台的租户/业务边界内，且释放本身就是要让它对 SAA 不可见）。
     * 边删边校验的话，「第 3 行被拦」会留下前两行「执行行还在、快照已 released」的中间态，
     * 而<b>没有任何接口能解释它</b>：执行历史抽屉里那两条还在，但 resume 读不出快照了。
     * 先把所有行的三道闸门跑完，被拦时数据一行未动，错误提示就是全部事实。
     *
     * <h3>🔴 为什么这里<b>不</b>加 {@code @Transactional}</h3>
     * 主导步骤（释放 checkpoint）不可回滚，事务只覆盖便宜的那一半，反而制造「看起来原子、其实不是」的假象。
     * 真正兜住的是<b>可重入</b>：每行按 release → node_execution → execution → def 的顺序删，
     * 任何中途失败都让 def <b>存活</b>，重跑再过一遍闸门即可 —— 已 released 的 thread 走 warn 分支，
     * 软删过的行 {@code deleteBatch} 影响 0 行。
     * ⚠️ {@code #pinPrimaryIfAbsent} 的注释断言「本类无 {@code @Transactional}」，将来若要加，须先改那句。
     *
     * <h3>🔴 闸门顺序：先状态（零 IO）、再取全表快照、再逐行 pin 与在途</h3>
     * 状态闸门只读入参那一行的 {@code status}，不查库；而 pin 闸门要 {@code selectListForAudit()}
     * 把<b>全部活定义连 DSL 正文</b>捞回来。整批都是已发布时（前端复选框挡不住的接口直调），
     * 先跑状态闸门就能省掉那次全表捞取。反过来排不会漏判，只是白付一次最贵的查询。
     *
     * @param targets         待删的定义行（已按确定顺序排好）
     * @param beingDeletedIds 本次一并删除的 id 集合，供 pin 扫描排除「自己人」；单删时就是 {@code Set.of(id)}
     * @param checkStatus     是否执行状态闸门（{@code WorkflowDefStatusEnum#isDeletable}）；管理员档传 false
     * @return 逐行的级联实收数字，顺序与 {@code targets} 一致
     */
    private List<CascadeResult> doDeleteDefs(List<AgentWorkflowDefDO> targets, Set<Long> beingDeletedIds,
                                             boolean checkStatus) {
        if (checkStatus) {
            for (AgentWorkflowDefDO target : targets) {
                assertStatusDeletable(target);
            }
        }

        // 🔴 整批<b>只调一次</b>：pin 扫描要逐行读 DSL，「同 code 还有没有别的活 PUBLISHED 行」也要在这份里数 ——
        //    分两次查会读到两个时刻的库，批内前后两行可能各拿一份不一致的快照。
        List<AgentWorkflowDefDO> aliveDefs = defMapper.selectListForAudit();

        List<DeletePlan> plans = new ArrayList<>(targets.size());
        for (AgentWorkflowDefDO target : targets) {
            assertNotPinned(target, aliveDefs, beingDeletedIds);
            plans.add(new DeletePlan(target, assertNoUnfinishedExecutions(target)));
        }

        List<CascadeResult> results = new ArrayList<>(plans.size());
        for (DeletePlan plan : plans) {
            results.add(cascadeDelete(plan.def(), plan.executions()));
        }
        return results;
    }

    /** 状态闸门：草稿与已停用放行，已发布拒删。判据只在 {@code WorkflowDefStatusEnum#isDeletable} 一处 */
    private void assertStatusDeletable(AgentWorkflowDefDO def) {
        if (!WorkflowDefStatusEnum.isDeletable(def.getStatus())) {
            throw exception(WORKFLOW_DEF_NOT_DELETABLE, def.getStatus());
        }
    }

    /** 引用闸门：仍被活定义的 sub_workflow pin 住就拒绝，并把引用方逐条点名进错误文案 */
    private void assertNotPinned(AgentWorkflowDefDO target, List<AgentWorkflowDefDO> aliveDefs,
                                 Set<Long> beingDeletedIds) {
        List<String> referencers = findSubWorkflowReferencers(target, aliveDefs, beingDeletedIds);
        if (!referencers.isEmpty()) {
            throw exception(WORKFLOW_DEF_DELETE_PINNED, describe(target), referencers.size(),
                    String.join("；", referencers));
        }
    }

    /**
     * 在途闸门：名下还有未终态执行就拒绝；通过后把那批执行原样交回，供级联阶段复用（不再查第二次）
     *
     * <p>🔴 判据用 {@code isFinal} 的正面形态而不是 {@code !isCancellable}：COMPENSATING 既不在 isFinal 里、
     *    也不可取消，拿「可取消」当尺子会把它判成「已了结」而放过去，可它正在逆序调反做工具（半撤销比卡死更难查）。
     *    status 为 NULL/未知值时 {@code isFinal} 也返回 false，一并拦住 = fail-close。
     */
    private List<AgentWorkflowExecutionDO> assertNoUnfinishedExecutions(AgentWorkflowDefDO target) {
        List<AgentWorkflowExecutionDO> executions = executionMapper.selectListByDefId(target.getId());
        List<String> unfinished = new ArrayList<>();
        for (AgentWorkflowExecutionDO execution : executions) {
            if (!WorkflowExecutionStatusEnum.isFinal(execution.getStatus())) {
                unfinished.add("#" + execution.getId() + "=" + execution.getStatus());
            }
        }
        if (!unfinished.isEmpty()) {
            throw exception(WORKFLOW_DEF_DELETE_HAS_UNFINISHED, describe(target), unfinished.size(),
                    String.join("，", unfinished));
        }
        return executions;
    }

    /**
     * 一行的级联删除：释放 checkpoint → 软删节点 trace → 软删执行 → 软删定义
     *
     * <p>🔴 顺序不可换，理由见 {@code WORKFLOW_DEF_DELETE_CHECKPOINT_FAILED} 那一节
     * （{@code thread_name} 只存在执行行上，而它是 {@code GRAPH_CHECKPOINT} 的唯一寻址键）。
     * 每行独立走完这四步、且 def 排在最后，正是「可重入」的来源：中途失败时 def 还活着，重跑能接着收尾。
     */
    private CascadeResult cascadeDelete(AgentWorkflowDefDO def, List<AgentWorkflowExecutionDO> executions) {
        int releasedThreads = releaseCheckpoints(executions);
        List<Long> executionIds = new ArrayList<>(executions.size());
        for (AgentWorkflowExecutionDO execution : executions) {
            executionIds.add(execution.getId());
        }
        // deleteBatch 自带空集合守卫（空则返回 0，不打一条 IN () 的废 SQL）；两处都是 @TableLogic 软删。
        int deletedNodes = nodeExecutionMapper.deleteBatch(AgentNodeExecutionDO::getExecutionId, executionIds);
        int deletedExecutions = executionMapper.deleteBatch(AgentWorkflowExecutionDO::getId, executionIds);
        defMapper.deleteById(def.getId());
        return new CascadeResult(def, releasedThreads, deletedExecutions, deletedNodes);
    }

    /** 一行「三道闸门都过了、可以动数据」的计划 */
    private record DeletePlan(AgentWorkflowDefDO def, List<AgentWorkflowExecutionDO> executions) {
    }

    /**
     * 一行级联的实收数字，交给各入口写自己的日志
     *
     * <p>🔴 内核<b>不</b>打日志：日志的方法名标签（{@code [deleteDef]} / {@code [forceDeleteDef]}）必须是
     * 调用方那一个，在内核里写死就等于给三档共用一个假标签。
     */
    private record CascadeResult(AgentWorkflowDefDO def, int releasedThreads, int deletedExecutions,
                                 int deletedNodes) {
    }

    /**
     * 扫出「活定义里还有谁的 sub_workflow 会因为删掉 {@code target} 而查不到子流程」
     *
     * <p>🔴 判据与运行期解析<b>同源</b>（{@link #getSubWorkflowDef}）：pin 了具体 version 的走
     * {@code selectByCodeAndVersion}，没 pin 的走 {@code selectPublishedByCode} 回落。
     * 两条路的「查不到」条件不同，故分开判：
     * <ul>
     *   <li>pin 的正是本行的 version ⟹ 拦；pin 的是别的版本 ⟹ 指向的是另一行，与本次删除无关，不拦。</li>
     *   <li>没 pin version ⟹ 只有「本行是 PUBLISHED <b>且</b>同 code 再没有别的活 PUBLISHED 行」才拦。
     *       本行是 DRAFT/DISABLED 时 {@code selectPublishedByCode} 压根选不到它，删它不改变回落结果。</li>
     * </ul>
     *
     * <p>🔴 DSL 解析不出来 / version 那一格填了非数字 ⟹ <b>都拦</b>，并在返回的清单里点名原因。
     * 这两种情况下「它有没有 pin 住 target」是<b>说不清</b>的，而删除不可逆，取 fail-close 那一边。
     * 注意这与「解析不了就等于跑不了、所以不算引用」不是一回事：跑不了是它自己的毛病，
     * 修好之后它照样会去查那个 code —— 那时 target 已经没了。
     * 也正因为这两条 fail-close 存在，<b>草稿没有快速通道</b>：DRAFT 一样能被 version pin
     * （本方法不看 target 的状态），只是运行期解析不到而已。
     *
     * <h3>🔴 {@code beingDeletedIds} 要同时用在<b>两处</b>比较上，漏一处是真 bug</h3>
     * 批量删除时「同批一起删的行」不算引用方 —— 它自己也要没了，那条 pin 不会悬空：
     * <ol>
     *   <li>循环里的跳过（原先是「跳过自己」）：A 的 DSL pin 住 B、而 A、B 同批 ⟹ 两条都放行。</li>
     *   <li>{@code codeFallbackWouldDangle} 的存活者判据：同 code 的 v1+v2 一起勾删、
     *       外部有一条<b>未 pin 版本</b>的引用时，若还把 v2 当成「别的活 PUBLISHED 行」，
     *       v1 就会被放行，批删之后那条外部引用悬空了。</li>
     * </ol>
     * 单删与强删传 {@code Set.of(id)}，两处都退化成原来的「等于 target 自己」，行为与改造前逐字一致。
     *
     * @param beingDeletedIds 本次一并删除的 id 集合（含 target 自己）
     * @return 引用方清单（人类可读，直接进错误文案）；空列表 = 没人 pin，可以删
     */
    private List<String> findSubWorkflowReferencers(AgentWorkflowDefDO target, List<AgentWorkflowDefDO> aliveDefs,
                                                    Set<Long> beingDeletedIds) {
        boolean codeFallbackWouldDangle =
                WorkflowDefStatusEnum.PUBLISHED.getStatus().equals(target.getStatus())
                        && aliveDefs.stream().noneMatch(other -> !beingDeletedIds.contains(other.getId())
                        && target.getCode().equals(other.getCode())
                        && WorkflowDefStatusEnum.PUBLISHED.getStatus().equals(other.getStatus()));
        List<String> referencers = new ArrayList<>();
        for (AgentWorkflowDefDO def : aliveDefs) {
            // 同批一起删的不算引用方（含 target 自己）：自己引用自己由校验器拦（编译期「检测到循环引用」），
            // 与本次删除无关；同批的引用方删完就没了，pin 不会悬空。
            if (beingDeletedIds.contains(def.getId())) {
                continue;
            }
            WorkflowDefDTO parsed;
            try {
                parsed = dslParser.parse(def.getDsl());
            } catch (Exception ex) {
                referencers.add(describe(def) + "（DSL 解析失败，无法判定是否 pin 住它：" + ex.getMessage() + "）");
                continue;
            }
            List<NodeDTO> nodes = parsed == null ? null : parsed.getNodes();
            if (nodes == null) {
                continue;
            }
            for (NodeDTO node : nodes) {
                // 🔴 两个键都写字面量、不抽常量，理由见 NODE_TYPE_SUB_WORKFLOW 的注释。
                //    workflowId 装的是子流程的 code（= 子 DSL 的 id），不是数字主键。
                if (node == null || !NODE_TYPE_SUB_WORKFLOW.equals(node.getType())
                        || !target.getCode().equals(node.configStr("workflowId"))) {
                    continue;
                }
                Integer pinnedVersion;
                try {
                    // 缺省 null = 没 pin 版本 ⟹ 运行期走 selectPublishedByCode 回落
                    pinnedVersion = node.configInt("version", null);
                } catch (NumberFormatException ex) {
                    referencers.add(describe(def) + " 的节点(" + node.getId() + ")：version 填的不是数字，pin 不明");
                    continue;
                }
                if (pinnedVersion == null) {
                    if (codeFallbackWouldDangle) {
                        referencers.add(describe(def) + " 的节点(" + node.getId() + ")：未 pin 版本，删后回不到别的已发布版本");
                    }
                } else if (pinnedVersion.equals(target.getVersion())) {
                    referencers.add(describe(def) + " 的节点(" + node.getId() + ")：pin 的正是这一版");
                }
            }
        }
        return referencers;
    }

    /**
     * 逐条释放执行实例的 checkpoint，返回真把 {@code is_released} 从 FALSE 翻成 TRUE 的 thread 数
     *
     * <h3>⚠️ 「释放」不是「删掉」—— 2026-10-08 现场 + 字节码双向实测</h3>
     * {@code MysqlSaver#releasedCheckpoints} 只发<b>一条</b> SQL：
     * {@code UPDATE GRAPH_THREAD SET is_released = TRUE WHERE thread_name = ? AND is_released = FALSE}。
     * 它<b>不碰</b> {@code GRAPH_CHECKPOINT} —— 快照行<b>留在库里</b>，只是从此读不出来
     * （{@code MysqlSaver} 的 SELECT 带 {@code t.is_released != TRUE}）。
     * 🔴 而在 1.1.2.2 里<b>根本不存在</b>能物理删快照的代码路径：{@code MysqlSaver} 声明的
     * {@code DELETE_CHECKPOINTS}（{@code DELETE FROM GRAPH_CHECKPOINT WHERE checkpoint_id = ?}）
     * 是个<b>死常量</b>——{@code javap -p -c} 全文里它在方法体中的命中数为 <b>0</b>；
     * 两个 {@code rollback(Connection, …)} 重载做的是 JDBC 的 {@code Connection.rollback()}
     * （回滚<b>未提交的事务</b>），也不删行（取证见 {@code _logs/r10/28-mysqlsaver-release-bytecode.log}）。
     * 唯一真能带走快照的是 {@code GRAPH_THREAD} 行上的外键 {@code ON DELETE CASCADE}，
     * 而那要先删 {@code GRAPH_THREAD}——SAA 没给这个接口，本平台也不该裸删别人的表。
     * <p>现场对账（样本 def #584、其执行 #175 对应的那个 thread）：强删后 {@code is_released} 0→1，
     * 而该 thread 名下仍有 <b>2</b> 行 {@code graph_checkpoint}。
     * 🔴 故任何报告都不得把本方法的效果写成「checkpoint 清零」。
     *
     * <h3>🔴 两类失败必须分开对待，否则这一档会变成「一次失败就永远删不掉」</h3>
     * 上面那条 UPDATE 带 {@code AND is_released = FALSE}，影响 0 行时 SAA 直接
     * {@code throw new IllegalStateException("Thread '%s' not found or already released")}；
     * 而 SQLException 走的是另一条 {@code throw new Exception("Unable to release checkpoint", cause)}。
     * 两者都被 {@code MemorySaver#loadOrInitCheckpoints} 原样上抛（它的异常表只有 unlock 的 finally，不包装）。
     * <ul>
     *   <li>{@code IllegalStateException} = <b>目标状态已达成</b>（thread 压根不存在，或早已释放）。
     *       拦下来毫无意义，还会造成死结：一单里两条执行、第二条释放失败 ⟹ 整单中止 ⟹
     *       重跑时第一条已是 released ⟹ 再抛 ⟹ <b>这行定义永远删不掉</b>。故记一行 warn 后继续。</li>
     *   <li>其余异常 = 真失败（连不上库、SQL 报错）。此时<b>整单中止</b>
     *       （{@code WORKFLOW_DEF_DELETE_CHECKPOINT_FAILED}）且业务行<b>一行未动</b> ——
     *       调用方把释放排在级联软删之前，正是为了让「中止」等于「什么都没发生」，
     *       否则会先删掉执行行、把 {@code thread_name} 这个唯一寻址键一起抹掉，
     *       剩下的快照就成了永远查不出属于谁、也没有任何接口能再删的孤儿。</li>
     * </ul>
     * ⚠️ 这条区分<b>认的是 SAA 抛的异常类型</b>。哪天它换了类型，这里退化成「一律中止」——
     * 方向是安全的那一边（宁可不删，不可删一半）。
     *
     * <p>{@code thread_name} 为空时跳过并记一行 warn：没有寻址键，SAA 那边本来也不可能有它的快照
     * （快照按 thread 存），这不是失败，不该把整单拦下来。
     */
    private int releaseCheckpoints(List<AgentWorkflowExecutionDO> executions) {
        int released = 0;
        for (AgentWorkflowExecutionDO execution : executions) {
            String threadName = execution.getThreadName();
            if (StrUtil.isBlank(threadName)) {
                log.warn("[releaseCheckpoints][执行 #{} 的 thread_name 为空，无寻址键，跳过释放]", execution.getId());
                continue;
            }
            try {
                checkpointSaver.release(RunnableConfig.builder().threadId(threadName).build());
                released++;
            } catch (IllegalStateException ex) {
                log.warn("[releaseCheckpoints][执行 #{} 的 thread({}) 无需释放：{}]",
                        execution.getId(), threadName, ex.getMessage());
            } catch (Exception ex) {
                throw exception(WORKFLOW_DEF_DELETE_CHECKPOINT_FAILED, threadName, ex.getMessage());
            }
        }
        return released;
    }

    /** 删除相关文案里对一行定义的统一称呼：{@code #id code@version} */
    private static String describe(AgentWorkflowDefDO def) {
        return "#" + def.getId() + " " + def.getCode() + "@" + def.getVersion();
    }

    @Override
    public Long publishDefById(Long id) {
        AgentWorkflowDefDO def = getDef(id);
        // 已发布：幂等返回（重复点发布不报错、不新增版本）
        if (WorkflowDefStatusEnum.PUBLISHED.getStatus().equals(def.getStatus())) {
            return def.getId();
        }
        // 只有 DRAFT 能发布；DISABLED（曾发布、现停用）不可重发，请出新版本
        if (!WorkflowDefStatusEnum.DRAFT.getStatus().equals(def.getStatus())) {
            throw exception(WORKFLOW_DEF_NOT_DRAFT, def.getStatus());
        }
        // 🔴 发布期 fail-close：chat 流程绑定的 Agent code 必须存在（增量12 / slice-3c 批次 B2）
        validateChatBinding(def);
        // 🔴 发布闸门（S4b）：V1~V19 体检不通过就不许翻状态
        assertPublishable(def);
        // 只翻状态：部分 DO 只带 id + status，其余列（dsl/触发投影列/version）不动（同 publishDef(code)）
        AgentWorkflowDefDO update = new AgentWorkflowDefDO();
        update.setId(def.getId());
        update.setStatus(WorkflowDefStatusEnum.PUBLISHED.getStatus());
        defMapper.updateById(update);
        log.info("[publishDefById][发布 Workflow 定义 id({}) code({}) version({}) DRAFT→PUBLISHED]",
                id, def.getCode(), def.getVersion());
        // 🔴 B6 / S16「发布 ≠ 生效」：管理页发布**不抢线上**，但该 code 一个主版本都没钉过时要把这一版钉上，
        //    理由与边界见 {@link #pinPrimaryIfAbsent}。放在翻状态之后：钉主版本要求行已是 PUBLISHED。
        pinPrimaryIfAbsent(def);
        return def.getId();
    }

    /**
     * 管理页发布后补钉主版本（B6 / S16，方案 §8.6 的 (a) 方案）：该 code **一个主版本都没有**时，
     * 把刚发布这一版钉成主版本；已经钉过就什么都不做。
     *
     * <p>🔴 为什么要钉：<b>「生效版本」今天是个隐式回落</b> ——
     * {@code selectPublishedByCode} 排序 {@code primary_flag DESC, version DESC}，没人钉主版本时
     * 它退化成「version 最大的那一版」。于是「发布新版」= 「立刻切线上」，而这两件事本该分开。
     * 把首个发布版显式钉住，是从「隐式回落」过渡到「显式指定」的那一步：钉住之后，
     * 后续发布 v3、v4 都不会再动线上，要切得去版本抽屉里显式点「设为主版本」。
     *
     * <p>🔴 为什么**只在没有主版本时**钉：已有主版本时若跟着改钉，就等于把「发布」重新变回「切线上」，
     * 本刀白做；而且会把运维为灰度回退特意钉住的旧版一脚踢开。
     *
     * <p>🔴 「没有主版本」按 {@code selectPrimaryByCode} 的口径算，它带 {@code status = PUBLISHED}
     * 过滤：**主版本被停用**（{@code disableDefById} 只翻状态、不清 primary_flag）等同于没有主版本，
     * 于是这一发会把钉移到真正生效的那版。理由与该过滤的必要性见那个方法的注释 ——
     * 不过滤的话，这种 code 会永远靠「version 最大」的回落生效，本方法的承诺对它就落空了。
     *
     * <p>🔴 与 {@link #publishDef(String)} 刻意**不对称**：那条路是聊天里的「确认并运行」
     * （{@code AgentWorkflowPlannerController}），用户刚看过这份计划、点了确认、紧接着就按 code 起
     * {@code run-stream}；而 {@code run-stream} 只吃 code、不吃版本号，走的是同一个回落查询。
     * 那条路上若发布完不生效，跑的就是**上一版** —— 「确认并运行」运行了别的东西，正是本模块头号缺陷
     * 「配了不生效且无提示」。所以那两处由 Controller 显式调 {@link #setPrimaryVersion(Long)}（发布即生效），
     * 本方法只服务管理页。
     *
     * <p>⚠️ 不在事务里（本类无 {@code @Transactional}）：翻状态与钉主版本是两次独立写。
     * 钉失败时该行是 PUBLISHED 但无主版本，生效版本回落到「version 最大」= 刚发布的这一版，
     * 即本刀之前的老行为 —— 退化方向是安全的，不会把线上指到一个更旧的版本。
     * 并发下两个发布同时看到「没有主版本」有可能各钉一次；这里复用 {@link #markPrimary}
     * 的先清后置，故最终仍只有一行 primary（它自己的并发窗口与今天逐字相同，不由本刀扩大）。
     *
     * <p>🔴 传的是**发布前读到的那个 DO**，不是 id：调用方手上已经有这行了，再按 id 读一次
     * 只是多一趟 SELECT；更要紧的是 {@link #setPrimaryVersion(Long)} 会重读并**重新校验 status 必须
     * PUBLISHED**，而本方法的调用点就紧跟在「把 status 写成 PUBLISHED」那一句后面 —— 走它就得依赖
     * 「写完立刻能读到自己写的」，在读写分离/缓存下是个没必要的假设。校验属于「用户显式点设为主版本」
     * 那个入口，不属于这里。
     */
    private void pinPrimaryIfAbsent(AgentWorkflowDefDO published) {
        if (defMapper.selectPrimaryByCode(published.getCode()) != null) {
            log.info("[pinPrimaryIfAbsent][code({}) 已有主版本，发布 version({}) 不改变线上生效版本]",
                    published.getCode(), published.getVersion());
            return;
        }
        markPrimary(published);
        log.info("[pinPrimaryIfAbsent][code({}) 此前无主版本，把刚发布的 version({}) id({}) 钉为主版本]",
                published.getCode(), published.getVersion(), published.getId());
    }

    @Override
    public void disableDefById(Long id) {
        AgentWorkflowDefDO def = getDef(id);
        // 已停用：幂等
        if (WorkflowDefStatusEnum.DISABLED.getStatus().equals(def.getStatus())) {
            return;
        }
        // 只有 PUBLISHED 能停用；DRAFT 没发布过谈不上停用（不想要直接删）
        if (!WorkflowDefStatusEnum.PUBLISHED.getStatus().equals(def.getStatus())) {
            throw exception(WORKFLOW_DEF_NOT_PUBLISHED, def.getStatus());
        }
        // 只翻状态：停用后不再被 getPublishedByCode 选中（新执行选不到它），在途实例按快照跑完不受影响
        AgentWorkflowDefDO update = new AgentWorkflowDefDO();
        update.setId(def.getId());
        update.setStatus(WorkflowDefStatusEnum.DISABLED.getStatus());
        defMapper.updateById(update);
        log.info("[disableDefById][停用 Workflow 定义 id({}) code({}) version({}) PUBLISHED→DISABLED]",
                id, def.getCode(), def.getVersion());
    }

    /**
     * 发布期校验 chat 触发器绑定的 Agent（增量12 / slice-3c 批次 B2，fail-close）
     *
     * <p>仅对 {@code trigger_type='chat'} 且 {@code trigger_key}（= 绑定的 Agent code，由
     * {@code WorkflowTriggerExtractor.applyChat} 投影）非空的定义生效：反查 {@code agent_def} 是否有该 code 的行，
     * 没有就抛 {@code WORKFLOW_TRIGGER_CHAT_AGENT_NOT_FOUND} 拒发布。
     *
     * <h3>🔴 为什么发布时才拦（而不是落草稿就拦）、且只要求「存在」不要求「已发布」</h3>
     * 草稿允许半成品（作者可能先起流程、后建 Agent），落草稿就拦会阻断正常编辑顺序；发布是「要真正生效」的动作，
     * 此时绑一个不存在的 code 会让聊天路径反查永不命中、流程静躺不触发（平台头号缺陷「配了不生效且无提示」，计划 RK2）。
     * 「存在」的口径（任意状态都算）写在 {@link AgentDefMapper#existsByCode} 上，这里不重述一遍。
     * 非 chat 触发器（form_event/schedule/webhook/无触发器）直接放行。
     *
     * <p>⚠️ 「发布时才拦」说的是<b>硬拦</b>（抛异常、动作失败）。规划期与盘点期那半是 V28 —— 它是
     * error 级规则，会进模型的自修正反馈、也会让盘点报告把这行标 failed，但<b>同样不拦落草稿</b>
     * （{@code doValidateDsl} 压根不在 {@code createDef/updateDef} 上）。两半合起来是
     * 「早告知 + 晚硬拦」，不是「两道都硬拦」。
     *
     * <h3>🔴 与校验器 V28 是<b>刻意重叠</b>的两道，不要去重</h3>
     * 本方法读<b>投影列</b> {@code trigger_key} —— 聊天入口
     * （{@code selectPublishedChatTriggerByAgentCode}）反查的就是它，所以「这条流程真会不会被拉起」
     * 由这一列说了算。V28 读 <b>DSL 里的</b> {@code trigger.chat.config.agentId} —— 那是作者看得见、
     * 也是盘点报告唯一能看到的东西（{@code auditAll} 只跑 {@code doValidateDsl}，压根不调本方法）。
     * 两列可以不一致（提取器收紧前入库的存量行、直改数据库），少了任何一道都会出现
     * 「报告说 clean、闸门却拒」或反过来。V15 与 {@code applyWebhook} 是同一套处置。
     */
    private void validateChatBinding(AgentWorkflowDefDO def) {
        if (!"chat".equals(def.getTriggerType()) || StrUtil.isBlank(def.getTriggerKey())) {
            return;
        }
        if (!agentDefMapper.existsByCode(def.getTriggerKey())) {
            throw exception(WORKFLOW_TRIGGER_CHAT_AGENT_NOT_FOUND, def.getTriggerKey());
        }
    }

    /**
     * 发布闸门：DSL 必须通过 V1~V19 体检才允许翻成 PUBLISHED（重构 S4b，fail-close）
     *
     * <h3>🔴 与 {@code auditAll} 逐行体检<b>逐字同形</b></h3>
     * 同一份 dsl、同一个 {@code buildManifestOrNull(dsl, null, null)}、同一个 {@code result.isPassed()} 判据。
     * 这样「盘点报告里 failed 的那一行」=「发布被拒的那一行」是<b>结构保证</b>，不是巧合 ——
     * 报告是给人做开闸决定用的，只要两处判据有第二份副本（§2-D7），它就迟早与闸门分岔，
     * 于是「报告说 0 行不通过、闸门却拒了 6 行」，而那份报告已经被人当成放行依据了。
     *
     * <h3>三条硬约束</h3>
     * <ol>
     *   <li><b>不提供 force / skipValidation 入参，也没有 yaml 降级开关。</b>
     *       任何一个「二次确认强推」的按钮都会把 V1~V19 从闸门降格成建议（用户决定 1）。</li>
     *   <li><b>只挂在两条发布路径上</b>（{@link #publishDef(String)} / {@link #publishDefById(Long)}），
     *       <b>不挂 createDef / updateDef</b>：草稿允许半成品（作者可能先起流程、后建表/后建 Agent），
     *       落草稿就拦会阻断正常编辑顺序 —— 与 {@link #validateChatBinding} 同一理由。</li>
     *   <li><b>幂等短路在闸门之前</b>：已 PUBLISHED 的行直接返回，不再体检。
     *       否则「V 系列后续加严」会让一次重试把历史上合法发布过的行报成不通过，而它并没有被改动。</li>
     * </ol>
     *
     * <h3>⚠️ 刻意<b>不</b>包 try/catch，这与 {@code auditAll} 不同</h3>
     * 盘点那边把异常记成 {@code auditFailed} 并继续下一行，因为「体检工具在这一行坏了」与「这一行违规」
     * 是两件事（§6.1：探测得出『不合法』才是 error，探测失败不是），且一行坏 DSL 不该让其余几十行的报告全丢。
     * 闸门这边没有下一行要救，而异常冒泡 ⟹ 事务中止 ⟹ 状态没翻 ⟹ <b>已经是 fail-close 了</b>。
     * 再包一层只会把「体检自己坏了」伪装成「你的 DSL 不通过」，让人去 DSL 里找一个根本不存在的问题。
     *
     * <h3>⚠️ 干跑层默认带上（S0 裁定⑥）</h3>
     * 实测 64 行全量盘点 0.6 秒，单行更轻 ⟹ 同步扛得住，不需要异步/缓存。
     * {@code buildManifestOrNull} 建不出来时返回 null，此时 V4/V5/V12 静默缺席而 V1~V3、V6~V11、V16 照常判 ——
     * 即「infra/bpm 当时不通」不会把发布堵死（探针失败不是违规），但也绝不会因此放过静态层的问题。
     * V16 归在「照常判」这一侧是台账 C9 的结果：它是纯静态溯源、只读 DSL 本身，不看清单。
     *
     * <h3>🔴 执行史按 code 展开（台账 #81 = S0 裁定① 的 A 方案）</h3>
     * 传给 {@code doValidateDsl} 的次数是 {@link #countExecutionsByCode}，即「本 code 名下<b>全部版本</b>」的合计，
     * 不是这一行的 id 自己跑过几次。理由写在 {@code AgentWorkflowExecutionMapper#countByDefIds} 上：
     * 回滚会造出<b>同 code 的新 id</b>，那个新版本自己一次都没跑过，执行史全在旧 id 上 ——
     * 少展开这一步，「回滚出的草稿」就会被判成「从没跑过」而照样拦下，正是 #81 要修的那个现象。
     */
    private void assertPublishable(AgentWorkflowDefDO def) {
        ChangeManifest manifest = buildManifestOrNull(def.getDsl(), null, null);
        // 🔴 与 auditAll 走同一个 doValidateDsl、同一个执行史口径（那边是把全量 defs 一次算完的批量版，
        //    两条路最终都落到 sumExecutionsByCode 这一个实现上），
        //    「盘点报告里 failed 的那一行」=「发布被闸门拒的那一行」仍是结构保证，不是巧合。
        AgentValidateResult result = doValidateDsl(def.getDsl(), AgentExecutionContext.snapshot(), manifest,
                countExecutionsByCode(def.getCode()));
        if (!result.isPassed()) {
            // 🔴 逐条带出、不折叠成条数：这是「点了发布、什么都没发生」时唯一能到达用户的文字。
            //    用「；」而不是「\n」拼接：这条最终落到 ElNotification 的 title（台账 #91），换行在 title 里不渲染。
            log.warn("[assertPublishable][定义({}/v{} id={}) 发布被闸门拒绝，errors={})",
                    def.getCode(), def.getVersion(), def.getId(), result.getErrors());
            throw exception(WORKFLOW_DEF_VALIDATE_FAILED, String.join("；", result.getErrors()));
        }
    }

    // ==================== 执行史取数（台账 #81） ====================

    /**
     * code → 名下<b>全部版本</b>的执行<b>尝试</b>次数合计（唯一的取数实现，两条入口都落到这里）
     *
     * <p>🔴 「尝试」= 含 FAILED / INTERRUPTED，<b>不是成功次数</b>，理由与「为什么不改成只数成功」
     * 都写在取数口 {@code AgentWorkflowExecutionMapper#countByDefIds} 上（只数成功会重新犯台账 #81 那个病）。
     * 本方法与 {@code countExecutionsByDefId} / {@code countExecutionsByCode} 都只是它的两种摊平方式，
     * 口径逐字继承，不另立第二套。
     *
     * <p>一次分组查询数完全部 id，故 {@code auditAll} 盘 N 行只多打<b>一条</b> SQL；
     * 若一行一查就是 {@code 2N} 次往返（先按 code 展开版本、再数执行），几十行的盘点会明显变慢，
     * 而这条查询本身是只读的、没有理由不能一次问完。
     *
     * <p>🔴 code 空白的行<b>不进 Map</b>（等价于 0 次）：它无从归属，硬给它凑一个数就是编造。
     * 这与单条路径「blank code → 0」是同一个口径。
     */
    private Map<String, Long> sumExecutionsByCode(List<AgentWorkflowDefDO> defs) {
        List<Long> ids = defs.stream()
                .filter(def -> StrUtil.isNotBlank(def.getCode()))
                .map(AgentWorkflowDefDO::getId)
                .toList();
        Map<Long, Long> perId = executionMapper.countByDefIds(ids);
        Map<String, Long> perCode = new HashMap<>(defs.size());
        for (AgentWorkflowDefDO def : defs) {
            if (StrUtil.isBlank(def.getCode())) {
                continue;
            }
            // countByDefIds 只返回次数 > 0 的 id，故这里必须用 getOrDefault 兜住「这一版从没跑过」
            perCode.merge(def.getCode(), perId.getOrDefault(def.getId(), 0L), Long::sum);
        }
        return perCode;
    }

    /**
     * defId → 它所属 code 的执行次数合计（{@code auditAll} 用：全量 defs 一次算完，避免逐行查库）
     */
    private Map<Long, Long> countExecutionsByDefId(List<AgentWorkflowDefDO> defs) {
        Map<String, Long> perCode = sumExecutionsByCode(defs);
        Map<Long, Long> perDef = new HashMap<>(defs.size());
        for (AgentWorkflowDefDO def : defs) {
            perDef.put(def.getId(), perCode.getOrDefault(def.getCode(), 0L));
        }
        return perDef;
    }

    /**
     * 单条：按 code 展开全部版本后合计（发布闸门用）
     */
    private long countExecutionsByCode(String code) {
        if (StrUtil.isBlank(code)) {
            return 0L;
        }
        return sumExecutionsByCode(defMapper.selectListByCode(code)).getOrDefault(code, 0L);
    }

    /**
     * 单条：按 defId 反查 code 后合计（{@code /validate} 用 —— 那个端点只有 id，没有 DO）
     *
     * <p>🔴 查不到就按 0 次算（0 ⟹ 一档都不降），<b>不</b>抛 {@code WORKFLOW_DEF_NOT_EXISTS}：
     * {@code /validate} 是只读打分端点，为一个不存在的 id 抛「不存在」会把「给一份 DSL 打分」
     * 变成「先证明这行在库里」。而降档是<b>放行</b>方向，取 0 恰好落在 fail-close 的那一边。
     */
    private long countExecutionsForDefId(Long defId) {
        if (defId == null) {
            return 0L;
        }
        AgentWorkflowDefDO def = defMapper.selectById(defId);
        return def == null ? 0L : countExecutionsByCode(def.getCode());
    }

    @Override
    public AgentValidateResult validateDsl(String dsl, AgentExecutionContext ctx) {
        return doValidateDsl(dsl, ctx, null, 0L);
    }

    @Override
    public AgentValidateResult validateDsl(String dsl, AgentExecutionContext ctx, ChangeManifest manifest) {
        // 执行史传 0 ⟹ 一档都不降。三参重载的调用方（生成器的自修正回路）手上没有 defId，
        // 而它要的也恰恰是「别降」：模型改一轮 DSL 改变不了「表名已被占用」，降成 warning 只会让它以为修好了。
        return doValidateDsl(dsl, ctx, manifest, 0L);
    }

    @Override
    public AgentValidateResult validateDsl(String dsl, AgentExecutionContext ctx, ChangeManifest manifest, Long defId) {
        return doValidateDsl(dsl, ctx, manifest, countExecutionsForDefId(defId));
    }

    /**
     * 三层体检的唯一实现：四个 {@code validateDsl} 重载与 {@code auditAll} 全都落到这里
     *
     * <p>🔴 收成一个私有方法，是为了让「什么算不通过」只有一份代码（方案 §2-D7）。
     * 发布闸门、盘点、{@code /validate} 三个消费者只在<b>怎么拿到 {@code executedCount}</b> 上有别
     * （单条按 code 展开 / 批量一次算完 / 按 id 反查），判据本身逐字相同。
     *
     * @param executedCount 本 code 名下全部版本的执行<b>尝试</b>次数合计（🔴 含 FAILED / INTERRUPTED，
     *                      <b>不是成功次数</b>，理由见 {@code AgentWorkflowExecutionMapper#countByDefIds}）；
     *                      0 表示「不降任何一档」（fail-close 的那一边）
     */
    private AgentValidateResult doValidateDsl(String dsl, AgentExecutionContext ctx, ChangeManifest manifest,
                                              long executedCount) {
        AgentValidateResult merged = new AgentValidateResult();
        // 第一层前置：JSON 能否解析。parse 对空/非法 JSON 抛 WORKFLOW_DSL_INVALID——这里捕获成一条 error，
        // 让校验端点始终返回结构化结果（而非让异常冒泡成 HTTP 错误），前端可内联展示「JSON 格式错在哪」。
        WorkflowDefDTO def;
        try {
            def = dslParser.parse(dsl);
        } catch (ServiceException ex) {
            merged.addError("%s", ex.getMessage());
            return merged;
        }
        // 第一层：收集式基础结构校验（节点 id/type、边端点、START 入口/END 出口、outputVar 声明…）
        AgentValidateResult basic = dslValidator.validate(def);
        basic.getErrors().forEach(merged::addError);
        basic.getWarnings().forEach(merged::addWarning);
        // 第二层：仅在基础通过时才干跑编译器深层校验（复用 buildStateGraph，无 DB/无 LLM/无副作用）。
        // 🔴 与 validateTwoLayer 同纪律：基础都不对时深层大概率在同一处 fail-fast，先让人把基础错改完再暴露深错。
        if (basic.isPassed()) {
            AgentValidateResult deep = dslCompiler.dryRunValidate(def, ctx);
            deep.getErrors().forEach(merged::addError);
            deep.getWarnings().forEach(merged::addWarning);
        }
        // 第三层：折叠调用方已经建好的清单里「平台已拒绝」的干跑结论（V4/V5/V12）。
        // 🔴 刻意不受 basic.isPassed() 约束：清单是调用方按同一份 DSL 建的，
        //    基础结构错与「表名已被占用」是两件互不遮蔽的事，一次全说出来才不用来回点两次「校验」。
        //    与第二层不同——那层是 fail-fast 的编译器干跑，基础不对时它大概率在同一处再炸一遍，说了等于没说。
        dslValidator.foldDryRunRejections(manifest, merged, executedCount);
        // V16：「外部能写的数据流进了写工具的入参」。判据是 untrustedInputs[]（溯源是纯静态的、不打接口），
        // 故同样不受 basic.isPassed() 约束。
        // 🔴 台账 C9 之后它不再<b>依赖</b>清单：manifest 为 null（两参重载、或清单构建 fail-soft 失败）时
        //    折叠器自己追一遍，产物与清单里那份逐条相同。所以本行传 manifest 只是「已经建好了就别重算」，
        //    不是「没有清单就没这条规则」—— 与上面那行 foldDryRunRejections 不同，那条是真的没清单就没判据。
        dslValidator.foldUntrustedWrites(def, manifest, merged);
        return merged;
    }

    // ==================== 存量盘点（重构 S0：开闸前第 0 步） ====================

    /**
     * 逐行干跑层状态的三态标签。刻意用字符串常量而不是 enum：它是写进 RespVO 给<b>报告读者</b>看的标签，
     * 不是领域概念，也没有行为；三态的理由写在 {@code AgentWorkflowDefAuditRespVO.Item#dryRunState} 上。
     */
    private static final String DRY_RUN_NOT_REQUESTED = "NOT_REQUESTED";
    private static final String DRY_RUN_PROBED = "PROBED";
    private static final String DRY_RUN_UNAVAILABLE = "UNAVAILABLE";

    @Override
    public AgentWorkflowDefAuditRespVO auditAll(boolean withDryRun) {
        List<AgentWorkflowDefDO> defs = defMapper.selectListForAudit();
        // 身份快照取一次、全部行共用：深层干跑 buildStateGraph 需要一个占位身份（不执行节点，故仅占位）。
        // 逐行重取反而会让同一份 DSL 的结论随线程状态漂 —— 盘点要的是可复现。
        AgentExecutionContext ctx = AgentExecutionContext.snapshot();
        // 执行史在循环外一次算完（台账 #81）：selectListForAudit() 已经取回了本租户每个 code 的全部版本，
        // 故这一条分组查询就是「按 code 展开」的全部信息，不必逐行再查一次库（那会是 2N 次往返）。
        // 🔴 与发布闸门同一个口径：那边是 countExecutionsByCode(def.getCode())，两边最终都落到 sumExecutionsByCode。
        Map<Long, Long> executedCounts = countExecutionsByDefId(defs);
        // 🔴 TreeMap 而不是 LinkedHashMap：要的是「两份报告能逐行对照」，而 LinkedHashMap 给的是
        //    「先见到的状态排在前面」—— 那个顺序取决于 selectListForAudit 的行序（code ASC, version DESC），
        //    于是新增/删除任意一行都会把它后面所有状态的键序整体推移，报告就对不上了。
        //    改成按状态名排序 ⟹ 键序只由「出现了哪些状态」决定，与行的增删、改名、版本回滚全无关。
        //    status 列是 NOT NULL DEFAULT 'DRAFT'（见建表脚本），故不需要 nullsLast。
        Map<String, Integer> byStatus = new TreeMap<>();
        List<AgentWorkflowDefAuditRespVO.Item> items = new ArrayList<>(defs.size());
        int failed = 0;
        int warned = 0;
        int clean = 0;
        int auditFailed = 0;
        for (AgentWorkflowDefDO def : defs) {
            AgentWorkflowDefAuditRespVO.Item item = new AgentWorkflowDefAuditRespVO.Item();
            item.setId(def.getId());
            item.setCode(def.getCode());
            item.setName(def.getName());
            item.setVersion(def.getVersion());
            item.setStatus(def.getStatus());
            item.setSource(def.getSource());
            // 🔴 原样带出，不把 null 改写成 "none"：null 是「存量行从未做过触发器投影」，
            //    "none" 是「DSL 明确没有 trigger 块」。对 V11/V13/V14 两者都算无头，但它们是两种来历，
            //    报告里混成一个就会看不出「有多少行是老到连投影都没跑过」。
            item.setTriggerType(def.getTriggerType());
            item.setPrimaryFlag(def.getPrimaryFlag());
            item.setDryRunState(DRY_RUN_NOT_REQUESTED);
            // 放在 try 外面：执行次数是一个与体检成败无关的<b>事实</b>，某行体检自己抛异常时它照样该在报告里 ——
            // 否则「auditFailed 的那几行」就永远看不出「它是不是因为已落地撞车才被降档的」，报告自己少一块拼图。
            long executedCount = executedCounts.getOrDefault(def.getId(), 0L);
            item.setExecutedCount(executedCount);
            items.add(item);
            byStatus.merge(def.getStatus(), 1, Integer::sum);
            try {
                ChangeManifest manifest = null;
                if (withDryRun) {
                    // 走 OrNull 版：清单在这里是「附加的一层」，某行建不出来不该把它的静态结论一起带走。
                    // 传 dsl 而不是 id：DO 已在手上，按 id 会白跑一次库；两者互斥规则由 resolveManifestSource 判。
                    manifest = buildManifestOrNull(def.getDsl(), null, null);
                    // 🔴 null 记成 UNAVAILABLE 而不是 PROBED：否则「infra/bpm 当时不通」会被读成「这行干净」
                    item.setDryRunState(manifest != null ? DRY_RUN_PROBED : DRY_RUN_UNAVAILABLE);
                }
                AgentValidateResult result = doValidateDsl(def.getDsl(), ctx, manifest, executedCount);
                item.setPassed(result.isPassed());
                item.setErrors(result.getErrors());
                item.setWarnings(result.getWarnings());
                // 🔴 用 result.isPassed() 而不是自己数 errors：「什么算不通过」必须与 S4b 闸门同一个判据，
                //    这里另写一个条件就是判据的第二份副本（§2-D7），迟早与闸门分岔。
                if (!result.isPassed()) {
                    failed++;
                } else if (!result.getWarnings().isEmpty()) {
                    warned++;
                } else {
                    clean++;
                }
            } catch (Exception ex) {
                // 🔴 逐行隔离，且异常绝不混进 errors：「盘点工具在这一行坏了」与「这一行违规」是两件事
                //    （§6.1：探测得出『不合法』才是 error，探测失败不是）。故只计入 auditFailed，
                //    不计入 failed、也不算 clean —— 这些行的 error 数是未知，不是 0。
                //    passed 置 false 是 fail-close 的占位（不知道就当不能发），不是一条判定。
                //    不 rethrow：一行坏 DSL 不该让其余几十行的盘点全部丢失。
                log.error("[auditAll][定义({}/v{}) 体检自身失败，记入 auditFailed，其余行继续]",
                        def.getCode(), def.getVersion(), ex);
                item.setAuditFailure(ex.toString());
                item.setPassed(false);
                item.setErrors(List.of());
                item.setWarnings(List.of());
                auditFailed++;
            }
        }
        AgentWorkflowDefAuditRespVO respVO = new AgentWorkflowDefAuditRespVO();
        respVO.setScanned(defs.size());
        respVO.setFailed(failed);
        respVO.setWarned(warned);
        respVO.setClean(clean);
        respVO.setAuditFailed(auditFailed);
        respVO.setDryRunIncluded(withDryRun);
        respVO.setByStatus(byStatus);
        respVO.setItems(items);
        return respVO;
    }

    // ==================== 变更清单（S2-f） ====================

    @Override
    public ChangeManifest buildManifest(String dsl, Long id, String fromNodeId) {
        ManifestSource source = resolveManifestSource(dsl, id);
        return manifestBuilder.build(source.def(), source.version(), fromNodeId);
    }

    @Override
    public ChangeManifest buildManifestOrNull(String dsl, Long id, String fromNodeId) {
        try {
            return buildManifest(dsl, id, fromNodeId);
        } catch (Exception ex) {
            // 🔴 兜住的是「清单这个附加字段生成不出来」，返回的是看得见地缺席的 null，
            //    不是一份残缺但看起来完整的清单 —— 后者才是 build 里刻意不包 try/catch 要防的东西。
            //    代价是调用方必须把 null 渲染成「清单不可用」而不是「本次不产生任何写操作」，
            //    这条契约写在接口注释里，前端那一侧由 ChangeManifestCard 负责。
            log.error("[buildManifestOrNull][变更清单生成失败 id({}) fromNodeId({})，主响应照常返回]",
                    id, fromNodeId, ex);
            return null;
        }
    }

    /**
     * 把「dsl / id 二选一」解析成一份可交给 builder 的 DSL
     *
     * <p>{@code hasDsl == hasId} 一条判两种非法：都空、都给。都空时若返回一份空清单，
     * headline 会是「本次不产生任何写操作」—— 一句凭空捏造的假话；都给时挑哪一份都是猜，
     * 而清单是给人做发布/审批决定用的，猜错的代价是「据一份不是自己那份的清单点了同意」。
     */
    private ManifestSource resolveManifestSource(String dsl, Long id) {
        boolean hasDsl = StrUtil.isNotBlank(dsl);
        boolean hasId = id != null;
        if (hasDsl == hasId) {
            throw exception(WORKFLOW_MANIFEST_SOURCE_INVALID, hasId
                    ? "dsl 与 id 只能给一个（两份都给了，无法判断要预览哪一份）"
                    : "dsl 与 id 必须给一个（两份都没给）");
        }
        if (hasId) {
            // 按 id 精确取那一版（不是「该 code 的最新版」）：管理页/审批卡看的都是用户点的那一行。
            // 查不到抛 WORKFLOW_DEF_NOT_EXISTS；租户隔离由 TenantBaseDO 保证，跨租户表现为「不存在」。
            AgentWorkflowDefDO def = getDef(id);
            return new ManifestSource(dslParser.parse(def.getDsl()), def.getVersion());
        }
        // 未落库的草稿：版本号记 null，清单里 workflow.version 就是空的（渲染方据此显示「草稿」）
        return new ManifestSource(dslParser.parse(dsl), null);
    }

    private record ManifestSource(WorkflowDefDTO def, Integer version) {
    }

    // ==================== 版本管理（增量14b） ====================

    @Override
    public List<AgentWorkflowDefDO> listVersionsByCode(String code) {
        List<AgentWorkflowDefDO> versions = defMapper.selectListByCode(code);
        // 🔴 裁掉 DSL 正文：版本列表只需元信息，对比时前端另调 getDef(id) 取两份完整 DSL。
        //    置 null 而非不查——每份 DSL 几十 KB，一页版本带正文会是兆级负载（同分页列表裁 dsl 的理由）。
        versions.forEach(v -> v.setDsl(null));
        return versions;
    }

    @Override
    public List<AgentWorkflowDefDO> listPublishedSimple() {
        // Mapper 层已用 select(...) 裁掉 dsl 列，这里无需再置 null
        return defMapper.selectPublishedSimpleList();
    }

    @Override
    public Long rollbackToVersion(Long id) {
        AgentWorkflowDefDO source = getDef(id);
        // 基于旧版 DSL 落一个 version=max+1 的新草稿（绝不就地改历史快照，见接口注释）。
        // 复用 insertNewVersion：它会重跑 triggerExtractor（旧 DSL 触发器若按现行规则非法在此抛，正确），
        // 并保留 code/name/description，只把状态固定为 DRAFT 待作者 review 后发布。
        // 🔴 来源记 MANUAL 而非沿用源版本：这一版是管理员点「回滚」建出来的，即使蓝本当初由 LLM 自动生成。
        //    按「谁建的」而非「蓝本谁建的」记，管理员筛「只看手动添加」时才能捞到自己动过手的流程。
        Long newId = insertNewVersion(source.getCode(), source.getName(), source.getDsl(),
                WorkflowDefStatusEnum.DRAFT.getStatus(), source.getDescription(),
                WorkflowSourceEnum.MANUAL.getSource());
        log.info("[rollbackToVersion][回滚 Workflow 定义 code({}) 源版本({}) 源id({}) → 新草稿 id({})]",
                source.getCode(), source.getVersion(), id, newId);
        return newId;
    }

    @Override
    public void setPrimaryVersion(Long id) {
        AgentWorkflowDefDO def = getDef(id);
        // 已是主版本：幂等返回（重复点不报错）
        if (Boolean.TRUE.equals(def.getPrimaryFlag())) {
            return;
        }
        // 只有 PUBLISHED 版本可设为主版本：草稿还没生效、停用版已退出，设主版本无意义（fail-close）
        if (!WorkflowDefStatusEnum.PUBLISHED.getStatus().equals(def.getStatus())) {
            throw exception(WORKFLOW_DEF_NOT_PUBLISHED, def.getStatus());
        }
        // 🔴 先清同 code 全部主版本标志、再置目标行=1，保证「同 code 至多一个主版本」。
        //    只动 primary_flag 一列（部分 DO），不碰 DSL/status/version（历史快照不可变纪律）。
        markPrimary(def);
        log.info("[setPrimaryVersion][设主版本 Workflow 定义 code({}) version({}) id({}) primary_flag=1]",
                def.getCode(), def.getVersion(), id);
    }

    /**
     * 「同 code 至多一个主版本」这条不变量的**唯一**实现：先清同 code 全部 primary_flag、再置目标行=1。
     *
     * <p>🔴 只动 primary_flag 一列（部分 DO），dsl/status/version/code 一概不带进 update ——
     * 已发布版本是执行实例绑定的快照，就地改会让在途实例 resume 对不上节点（历史快照不可变纪律）。
     *
     * <p>两个调用方：{@link #setPrimaryVersion(Long)}（用户在版本抽屉里显式点「设为主版本」，
     * 带 status 必须 PUBLISHED 的校验）与 {@link #pinPrimaryIfAbsent}（管理页发布后补钉，
     * 调用点紧跟在写 PUBLISHED 之后，不需要也不应该再校验一次）。
     *
     * <p>⚠️ 库里没有唯一约束能表达「每 code 至多一个 true」（见 {@code AgentWorkflowDefDO#primaryFlag}），
     * 该不变量只由这里的先清后置维持，非事务下并发调用仍可能各钉一次 —— 与本刀之前逐字相同，
     * 不由 B6 扩大，也不在本刀里顺手「修」（那是 D4-23 那一类问题，要改就得连索引一起改）。
     */
    private void markPrimary(AgentWorkflowDefDO def) {
        defMapper.clearPrimaryFlagByCode(def.getCode());
        AgentWorkflowDefDO update = new AgentWorkflowDefDO();
        update.setId(def.getId());
        update.setPrimaryFlag(true);
        defMapper.updateById(update);
    }

}
