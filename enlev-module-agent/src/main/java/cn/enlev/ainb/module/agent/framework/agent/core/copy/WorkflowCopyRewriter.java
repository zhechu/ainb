package cn.enlev.ainb.module.agent.framework.agent.core.copy;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslParser;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.HitlBpmApprovalNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.ToolNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.support.StateTemplateResolver;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.trigger.WorkflowTriggerExtractor;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.AgentValidateResult;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.DataModelCreateValidator;
import cn.enlev.ainb.module.agent.tool.impl.datamodel.DataModelCreateTool;
import cn.enlev.ainb.module.agent.tool.impl.datamodel.DataModelParamResolver;
import cn.enlev.ainb.module.agent.tool.impl.form.FormSaveDraftTool;
import cn.enlev.ainb.module.agent.tool.impl.process.ProcessCreateAndDeployTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 复制流程时的冲突配置改写器（方案 §3-L4 / 4.1，S5-b）
 *
 * <h3>为什么复制不能只是「换 code 存一份」</h3>
 * 一条工作流的 DSL 里嵌着一堆<b>平台级全局标识</b>：要建的表名、要存的表单编码、要部署的 BPM 流程 key、
 * webhook 的入站 path。原样复制一份，两条流程就会去抢同一个对象 —— 建表撞名、表单互相覆盖、
 * 两条流程接同一个匿名入口。而这些都是<b>不可逆</b>的写操作。
 *
 * <p>🔴 但反过来，<b>静默改写同样不可接受</b>：本仓 §6 那「8 条静默改写」的教训就是
 * 「保存成功、但建出来的不是你要的、且不报错」。所以本类每改一格都必须产出一条
 * {@link Rewrite}，由 {@code /copy} 原样回显给前端弹「已自动改写以下配置」——
 * <b>改写可以，静默不行</b>。
 *
 * <h3>🔴 回显不是锦上添花，是这一步唯一的用户可见信号</h3>
 * {@code insertNewVersion} 全方法<b>不跑</b> {@code validateDsl}（S4b 的闸门在<b>发布期</b>，复制在<b>建草稿期</b>）
 * ⟹ 复制可以产出一份<b>必然发不出去</b>的草稿而不报任何错。用户此刻能看到的只有 {@code rewritten[]}。
 * 因此本类的失败取向是「<b>改不动就说出来</b>」而不是「改不动就抛」：
 * 表名加完后缀不合法时保留原值 + 回显原因，而不是让整个复制失败
 * （源可能是一条早于命名规范落库的存量行，把它复制出来看/改恰恰是合理动作）。
 *
 * <h3>🔴 为什么走 Jackson 原始树，而不是 {@code WorkflowDefDTO} 往返一趟</h3>
 * {@code WorkflowDefDTO} / {@code NodeDTO} 都挂着 {@code @JsonIgnoreProperties(ignoreUnknown = true)}，
 * 且那是<b>刻意的</b>（类注释：DSL 会随版本演进新增字段，旧引擎读到新 DSL 应当忽略不认识的字段继续跑）。
 * 代价是：<b>反序列化再序列化会静默丢掉所有未建模的字段</b>。
 * 对执行无所谓（引擎本来就只读它认识的），对复制是致命的 —— 「复制」这个动作的承诺是「与源一致，除了我告诉你改了的那些」，
 * 悄悄少几个字段正是本平台头号缺陷的形状。故这里只认原始树（{@link DslParser#parseTree}），就地改，原样吐回。
 *
 * <h3>⚠️ 键名的字面量副本（已知债，见台账）</h3>
 * 下面这组 {@code K_*} 里，只有 {@code trigger.config} 的四个键引到了常量
 * （{@link WorkflowTriggerExtractor}，S5-b 顺手提的）；工具侧的
 * {@code DataModelParamResolver.PARAM_*} / {@code FormSaveDraftTool.PARAM_*} /
 * {@code ProcessCreateAndDeployTool.PARAM_KEY} 与各 {@code *.NAME} / {@code *.TYPE} 也都是引常量。
 * 但 {@code args} / {@code toolId} / {@code sub_workflow} / {@code workflowId} / {@code bpmProcessDefKey}
 * 在本仓<b>本来就没有公开常量</b>（分别已有 1 / 10 / 6 / 3 / 3 份字面量副本，散在
 * {@code ManifestArgs}、{@code ToolNodeExecutor}、{@code DslToStateGraphCompiler}、
 * {@code WorkflowDslValidator}、{@code UntrustedInputTracer}、{@code AgentBpmApprovalService} 等处）。
 * 把它们收敛成单一权威是独立的一刀（台账 #100/#105 那一族），塞进 S5-b 会让「复制能用」这条验收
 * 混进一个四文件重构，故此处按方案给的第二个选项处理：<b>明确记为已知副本</b>。
 *
 * <h3>🔴 已知覆盖缺口</h3>
 * {@code form_publish_bundle.args.id}（发布表单菜单时指向的那张已存在表单）<b>没有</b>改写规则 ——
 * 复制品会去重新发布源流程的那张表单。方案的 11 条规则表里没这一行，S5-b 按「只做方案写了的」处理，
 * 已登记待裁定。
 *
 * @author 走进新时代
 */
@Component
public class WorkflowCopyRewriter {

    /**
     * 冲突标识的统一后缀。
     *
     * <p>⚠️ 刻意<b>不</b>跟着 newCode 走：用户把副本命名为 {@code wf_x_v2} 时，
     * 表名仍改成 {@code biz_emp_copy}。两者不同步无害（表名只要唯一且合法），
     * 而从 newCode 派生后缀会让「表名会改成什么」变成一件要算才知道的事。
     */
    public static final String SUFFIX = "_copy";

    // ==================== DSL 结构键（JSON 层） ====================
    // 与 WorkflowDefDTO / NodeDTO 的字段名一一对应。它们没有公开常量，
    // 而「DTO 字段名漂移」由 WorkflowCopyRewriterTest 的整份 DSL 往返用例钉住。

    private static final String K_ID = "id";
    private static final String K_TYPE = "type";
    private static final String K_NAME = "name";
    private static final String K_DESCRIPTION = "description";
    private static final String K_NODES = "nodes";
    private static final String K_TRIGGER = "trigger";
    private static final String K_CONFIG = "config";

    // ==================== 无公开常量的 config 键（见类注释「已知债」） ====================

    /** {@code tool} 节点存工具入参的那一层；同 {@code ManifestArgs.CONFIG_ARGS}（包级可见，够不着） */
    private static final String K_ARGS = "args";
    /** {@code tool} 节点的工具键名；同 {@code ToolNodeExecutor} 里读的那个（那边也是字面量，见类注释「已知债」） */
    private static final String K_TOOL_ID = "toolId";
    /** 节点类型：内嵌子流程。本仓已有 6 份同值字面量 */
    private static final String K_TYPE_SUB_WORKFLOW = "sub_workflow";
    /** {@code sub_workflow} 指向的子流程 code */
    private static final String K_SUB_WORKFLOW_ID = "workflowId";
    /** {@code hitl.bpm_approval} 指向的已部署 BPM 流程标识 */
    private static final String K_BPM_PROCESS_DEF_KEY = "bpmProcessDefKey";

    /** 密钥在 {@link Rewrite#from}/{@link Rewrite#to} 里的替身。🔴 真值绝不进回显，理由见 {@link Rewrite} */
    private static final String SECRET_MASK = "（已沿用源流程的密钥，值不显示）";

    /**
     * DSL 文本 → <b>原始树</b>的唯一入口（{@code DslParser#parseTree}）
     *
     * <p>🔴 不在本类里自己 {@code objectMapper.readTree}：解析失败的错误码与文案必须与
     * {@code DslParser#parse}（发布/落库走的那一支）同源，两处各写一份就是第二份口径。
     */
    @Resource
    private DslParser dslParser;

    /**
     * 表名合法性的<b>唯一</b>判据（方案 §2-D7）。
     *
     * <p>🔴 不许在本类里重抄「{@code biz_} 前缀 + 标识符正则 + 64 字上限 + 框架保留前缀」：
     * infra 自己已经有 {@code SqlSafeGuard} 与 {@code SqlIdentifierGuard} 两份同正则了（台账 #108），
     * 再加一份就是第三份，而三份口径不一致的表现是「复制时说合法、建表时被拒」。
     */
    @Resource
    private DataModelCreateValidator dataModelCreateValidator;

    /**
     * 改写一份 DSL，产出「新 DSL + 改写清单」
     *
     * <p>🔴 <b>顶层 id/name/description 无条件改写，不看 {@code rewriteConflicts}</b>：
     * DSL 的 {@code id} 就是流程 code，{@code WorkflowDslValidator} 把它当强制不变量校验，
     * 不改等于复制出一份「编码对不上自己」的草稿；而 DO 的 name/description 取的是入参，
     * DSL 里留着源流程的旧值就是同一份东西两个说法。
     * 这三格<b>不进</b> {@code rewritten[]}：它们是用户在对话框里亲手填的，回显是噪声 ——
     * 清单只装「用户没填、服务端替他决定了的」东西。
     *
     * @param dslJson          源 DSL 原文
     * @param newCode          新业务编码（调用方已查重）
     * @param newName          新名称
     * @param newDescription   新描述；空白表示沿用源描述
     * @param rewriteConflicts {@code false} = 只换身份、冲突配置一律原样带入，但<b>照样逐格回显</b>
     *                         （用户主动关掉了自动改写，那他更需要知道有哪些格子等着他手工处理）
     * @return 新 DSL 原文 + 改写清单（非 null；清单可为空列表，表示这份 DSL 里没有任何冲突配置）
     * @throws cn.enlev.ainb.framework.common.exception.ServiceException DSL 不是合法 JSON 对象 →
     *         {@code WORKFLOW_DSL_INVALID}。🔴 这里<b>必须</b>抛而不是「认不出就当没有」：
     *         改写器的产物是要落库的，parse 不出来还吐一份原文等于把源 DSL 原样复制一遍，
     *         冲突一个没改而清单是空的 —— 那是一份「看起来干净」的假清单
     */
    public Result rewrite(String dslJson, String newCode, String newName,
                          String newDescription, boolean rewriteConflicts) {
        ObjectNode root = dslParser.parseTree(dslJson);
        List<Rewrite> rewritten = new ArrayList<>();

        root.put(K_ID, newCode);
        root.put(K_NAME, newName);
        // 描述可空：空就<b>原样留着</b>源 DSL 里那一格，不写回一个 trim 过的副本（改了却没人要求改）
        if (StrUtil.isNotBlank(newDescription)) {
            root.put(K_DESCRIPTION, newDescription);
        }

        rewriteTrigger(root, rewriteConflicts, rewritten);
        rewriteNodes(root, rewriteConflicts, rewritten);
        return new Result(root.toString(), rewritten);
    }

    // ==================== 触发器（trigger.config.*） ====================

    /**
     * 🔴 <b>按「有没有这一格」判，不按触发器类型判</b>
     *
     * <p>类型在 DSL 里写的是全名（{@code trigger.webhook}），落库时才被
     * {@code WorkflowTriggerExtractor.normalizeType} 剥成短名 —— 在这里复刻那份归一化就是第二份副本。
     * 而 {@code path}/{@code secret} 只可能出现在 webhook、{@code cron} 只可能出现在 schedule、
     * {@code formCode} 只可能出现在 form_event，按格判与按类型判结果一致，且少一处会漂移的口径。
     * 代价是：某份非法 DSL 在 form_event 里塞了个 {@code path}，也会被改写 —— 那种 DSL 本来就该被校验器拒。
     */
    private void rewriteTrigger(ObjectNode root, boolean rewriteConflicts, List<Rewrite> out) {
        ObjectNode config = child(child(root, K_TRIGGER), K_CONFIG);
        if (config == null) {
            return;
        }
        String base = K_TRIGGER + "." + K_CONFIG + ".";

        // formCode：不改 + 提示。订阅同一张表单的事件通常正是复制的意图
        String formCode = text(config, WorkflowTriggerExtractor.CONFIG_FORM_CODE);
        if (formCode != null) {
            out.add(unchanged(base + WorkflowTriggerExtractor.CONFIG_FORM_CODE, formCode,
                    "表单事件订阅未改写：复制品仍监听同一张表单的同一类事件（这通常正是复制的意图）"));
        }

        // path：必须改。不改的话两条流程抢同一个匿名入口，入站请求只会命中其中一条
        String path = text(config, WorkflowTriggerExtractor.CONFIG_PATH);
        if (path != null) {
            String key = base + WorkflowTriggerExtractor.CONFIG_PATH;
            if (!rewriteConflicts) {
                out.add(unchanged(key, path,
                        "webhook 入站路径未改写（你关掉了自动改写）：复制品与源流程抢同一个匿名入口，发布前必须手工改"));
            } else if (StateTemplateResolver.containsPlaceholder(path)) {
                out.add(unchanged(key, path, "webhook 入站路径未改写：它是运行期才定的占位符，复制时看不出真值"));
            } else {
                String newPath = shrinkToLegalPath(path);
                config.put(WorkflowTriggerExtractor.CONFIG_PATH, newPath);
                out.add(new Rewrite(key, path, newPath,
                        "webhook 入站路径已改写：不改的话两条流程会抢同一个匿名入口。"
                                + "原路径加上后缀会超出白名单长度时，从末尾截短到合法为止"));
            }
        }

        // 🔴 secret：复制 + 显式回显，但真值绝不进清单。
        //    为什么不能「干脆不复制」：WorkflowTriggerExtractor.applyWebhook 对 secret 是无条件必填，
        //    删掉它会让每一份 webhook 复制品在 insertNewVersion 就抛 WORKFLOW_TRIGGER_CONFIG_INVALID
        //    ⟹ 「复制」这个功能对 webhook 流程直接不可用，而前端对话框没有填 secret 的格子。
        //    为什么不能静默带走：一份凭据凭空多出一条副本，正是本类要消灭的那个形状。
        if (text(config, WorkflowTriggerExtractor.CONFIG_SECRET) != null) {
            out.add(unchanged(base + WorkflowTriggerExtractor.CONFIG_SECRET, SECRET_MASK,
                    "验签密钥沿用了源流程。一份凭据多出一条副本，请在发布前确认是否要换成新密钥"));
        }

        // cron：保留 + 显式回显。
        //    为什么不能「默认停掉触发器」：删掉整个 trigger 块本身就是一次静默的语义改写
        //    （复制品从「定时自动跑」变成「只能手动跑」），与 secret 那条同一个道理。
        //    为什么留着也不危险：定时轮询只扫 status=PUBLISHED 的行
        //    （AgentWorkflowDefMapper 的 schedule 查询带该过滤），而复制品一律是 DRAFT
        //    ⟹ 复制这一刻不会真的多出一条定时任务；风险推迟到发布那一刻，
        //      而作者那时正看着 rewritten[] 这个对话框。
        String cron = text(config, WorkflowTriggerExtractor.CONFIG_CRON);
        if (cron != null) {
            out.add(unchanged(base + WorkflowTriggerExtractor.CONFIG_CRON, cron,
                    "定时表达式沿用源流程。复制品是草稿、不会触发，但发布后两条流程会在同一时刻各自跑一轮"
                            + "（各烧一次 token、各写一份数据），请确认是否要停掉其中一条"));
        }
    }

    /**
     * 从末尾截短 base，直到 {@code base + "_copy"} 落进 webhook path 白名单
     *
     * <p>🔴 这里<b>不出现「64」这个数</b>：上限只编码在
     * {@link WorkflowTriggerExtractor#WEBHOOK_PATH_PATTERN} 里，抄一份就会漂移
     * （漂了的表现是「复制当场抛 WORKFLOW_TRIGGER_CONFIG_INVALID，而用户没有格子能改 path」）。
     * 有界循环：{@code len} 递减到 0 时候选就是 {@code "_copy"}，而下划线在白名单里，必命中。
     */
    private static String shrinkToLegalPath(String path) {
        for (int len = path.length(); len >= 0; len--) {
            String candidate = path.substring(0, len) + SUFFIX;
            if (WorkflowTriggerExtractor.WEBHOOK_PATH_PATTERN.matcher(candidate).matches()) {
                return candidate;
            }
        }
        return path;
    }

    // ==================== 节点 ====================

    /**
     * 🔴 <b>两趟，顺序不可换</b>：{@code form_save_draft.mainTable} 的规则是「<b>跟随</b>本流程新建表的改写」，
     * 所以必须先把所有 {@code datamodel_create} 的 旧表名→新表名 收齐，再去看 mainTable。
     * 一趟写完的后果是「建表节点排在表单节点之后」的流程里 mainTable 跟不上，
     * 表单写进一张不存在的表 —— 而这在执行期才炸。
     */
    private void rewriteNodes(ObjectNode root, boolean rewriteConflicts, List<Rewrite> out) {
        JsonNode nodes = root.get(K_NODES);
        if (nodes == null || !nodes.isArray()) {
            return;
        }
        Map<String, String> tableRenames = new LinkedHashMap<>();
        Set<String> createdTables = new LinkedHashSet<>();
        for (int i = 0; i < nodes.size(); i++) {
            rewriteDataModelCreate(nodes.get(i), i, rewriteConflicts, out, tableRenames, createdTables);
        }
        for (int i = 0; i < nodes.size(); i++) {
            JsonNode node = nodes.get(i);
            ObjectNode config = child(node, K_CONFIG);
            if (config == null) {
                continue;
            }
            if (ToolNodeExecutor.TYPE.equals(text(node, K_TYPE))) {
                rewriteToolNode(node, config, i, rewriteConflicts, out, tableRenames, createdTables);
            } else if (HitlBpmApprovalNodeExecutor.TYPE.equals(text(node, K_TYPE))) {
                noticeSubResource(out, nodePath(node, i) + "." + K_BPM_PROCESS_DEF_KEY,
                        text(config, K_BPM_PROCESS_DEF_KEY),
                        "审批流未复制：复制品仍指向同一条已部署的 BPM 流程（复用同一条审批流是常见意图）");
            } else if (K_TYPE_SUB_WORKFLOW.equals(text(node, K_TYPE))) {
                noticeSubResource(out, nodePath(node, i) + "." + K_SUB_WORKFLOW_ID,
                        text(config, K_SUB_WORKFLOW_ID),
                        "子流程未复制：复制品仍内嵌同一条已发布流程（要复制它请对那条流程再复制一次）");
            }
        }
    }

    private void rewriteToolNode(JsonNode node, ObjectNode config, int index, boolean rewriteConflicts,
                                 List<Rewrite> out, Map<String, String> tableRenames, Set<String> createdTables) {
        ObjectNode args = child(config, K_ARGS);
        if (args == null) {
            return;
        }
        String base = nodePath(node, index) + "." + K_ARGS + ".";
        String toolId = text(config, K_TOOL_ID);
        if (FormSaveDraftTool.NAME.equals(toolId)) {
            rewriteSuffixed(args, base, FormSaveDraftTool.PARAM_CODE, rewriteConflicts, out,
                    "表单编码加了后缀，避免复制品覆盖源流程的那张表单");
            rewriteMainTable(args, base, rewriteConflicts, out, tableRenames, createdTables);
        } else if (ProcessCreateAndDeployTool.NAME.equals(toolId)) {
            rewriteSuffixed(args, base, ProcessCreateAndDeployTool.PARAM_KEY, rewriteConflicts, out,
                    "BPM 流程标识加了后缀，避免部署时与源流程的 key 撞车");
        }
    }

    /**
     * {@code datamodel_create.args.model.tableName}
     *
     * <p>⚠️ <b>两层嵌套，且 {@code model} 有两种形态</b>：执行期 {@code DataModelParamResolver.resolveCreate}
     * 用 {@code JSONUtil.parseObj(model)} 取值，Map 与「模型把对象序列化成的 JSON 串」都认。
     * 只认 Map 的话，同一份 DSL 在执行期能跑通、在复制里却被跳过 ⟹ 表名原样带走、两条流程抢同一张表，
     * 而清单上一句提示都没有（方案的 11 条规则表把深度写成了单层，S5-a 已更正）。
     */
    private void rewriteDataModelCreate(JsonNode node, int index, boolean rewriteConflicts,
                                        List<Rewrite> out, Map<String, String> tableRenames,
                                        Set<String> createdTables) {
        ObjectNode config = child(node, K_CONFIG);
        if (config == null || !ToolNodeExecutor.TYPE.equals(text(node, K_TYPE))
                || !DataModelCreateTool.NAME.equals(text(config, K_TOOL_ID))) {
            return;
        }
        ObjectNode args = child(config, K_ARGS);
        if (args == null) {
            return;
        }
        String base = nodePath(node, index) + "." + K_ARGS + "." + DataModelParamResolver.PARAM_MODEL + ".";
        JsonNode model = args.get(DataModelParamResolver.PARAM_MODEL);

        if (model instanceof ObjectNode modelObj) {
            rewriteTableName(modelObj, base, rewriteConflicts, out, tableRenames, createdTables);
            return;
        }
        if (model == null || !model.isTextual()) {
            return;
        }
        // model 是 JSON 串：解出来改，改完再塞回同一个键（仍是串，与源形态一致）
        ObjectNode inner;
        try {
            inner = dslParser.parseTree(model.asText());
        } catch (ServiceException ignored) {
            // 既不是合法 JSON 也不是对象 ⟹ 执行期 JSONUtil.parseObj 同样取不出表名，
            // 这里没什么可改的，也不该由「复制」来替「执行」报错
            return;
        }
        rewriteTableName(inner, base, rewriteConflicts, out, tableRenames, createdTables);
        args.put(DataModelParamResolver.PARAM_MODEL, inner.toString());
    }

    private void rewriteTableName(ObjectNode model, String base, boolean rewriteConflicts,
                                  List<Rewrite> out, Map<String, String> tableRenames,
                                  Set<String> createdTables) {
        String key = base + DataModelParamResolver.PARAM_TABLE_NAME;
        String tableName = text(model, DataModelParamResolver.PARAM_TABLE_NAME);
        if (tableName == null) {
            return;
        }
        // 🔴 「本流程建了这张表」与「这张表改名成功了」是两件事，必须分开记：
        //    mainTable 的回显要据此给出<b>不同的行动指引</b>（见 rewriteMainTable）
        createdTables.add(tableName);
        if (!rewriteConflicts) {
            out.add(unchanged(key, tableName,
                    "建表名未改写（你关掉了自动改写）：复制品会去建源流程那张同名表，而建表不可逆，发布前必须手工改"));
            return;
        }
        if (StateTemplateResolver.containsPlaceholder(tableName)) {
            out.add(unchanged(key, tableName, "建表名未改写：它是运行期才定的占位符，复制时看不出真值"));
            return;
        }
        String candidate = tableName + SUFFIX;
        AgentValidateResult verdict = dataModelCreateValidator.validateTableName(candidate);
        if (!verdict.isPassed()) {
            // 改不动就说出来，不让整个复制失败：源可能是早于命名规范落库的存量行，
            // 把它复制出来看/改恰恰是合理动作。而 insertNewVersion 不跑校验 ⟹ 这条回显是唯一的信号
            out.add(unchanged(key, tableName,
                    "建表名未改写：加后缀后不符合建表规范（%s）。复制品发布前必须手工改这个表名，否则会与源流程抢同一张表",
                    String.join("；", verdict.getErrors())));
            return;
        }
        model.put(DataModelParamResolver.PARAM_TABLE_NAME, candidate);
        tableRenames.put(tableName, candidate);
        out.add(new Rewrite(key, tableName, candidate,
                "建表名加了后缀，避免与源流程建同一张表（建表不可逆，撞上就只能改表）"));
    }

    /**
     * {@code form_save_draft.args.mainTable}：<b>跟随</b>本流程新建表的改写
     *
     * <p>为什么不能也盲目加后缀：mainTable 说的是「这张表单的数据存到哪张表」，
     * 它必须与建表节点建出来的那张<b>是同一张</b>。盲目加后缀在「表不是本流程建的」时会指向一张不存在的表，
     * 在「表是本流程建的、但改名被驳回」时会与建表名对不上 —— 两种都到执行期才炸。
     */
    private void rewriteMainTable(ObjectNode args, String base, boolean rewriteConflicts,
                                  List<Rewrite> out, Map<String, String> tableRenames,
                                  Set<String> createdTables) {
        String key = base + FormSaveDraftTool.PARAM_MAIN_TABLE;
        String mainTable = text(args, FormSaveDraftTool.PARAM_MAIN_TABLE);
        if (mainTable == null) {
            return;
        }
        String renamed = tableRenames.get(mainTable);
        if (renamed != null) {
            args.put(FormSaveDraftTool.PARAM_MAIN_TABLE, renamed);
            out.add(new Rewrite(key, mainTable, renamed,
                    "主表跟随本流程新建的表名一起改写（两者必须一致，否则表单写不进新表）"));
            return;
        }
        // 🔴 各档「没跟随上」的原因必须分开说，否则会给出错误的行动指引：
        //    关掉开关 = 你自己选的；表不是本流程建的 = 本来就不该跟随；
        //    本流程建了这张表却没改成 = <b>两处必须一起手工改</b>（这一档原先被并进「对不上」/「不是本流程建的」，
        //    而那两句话都在告诉用户「不用管」，恰恰是最需要管的一档）；
        //    本流程建了别的表 = 源流程自己两处就不一致
        String reason;
        if (!rewriteConflicts) {
            reason = "主表未改写（你关掉了自动改写）：复制品仍写入源流程那张表，发布前必须手工改";
        } else if (createdTables.contains(mainTable)) {
            reason = "主表未改写：本流程确实建这张表，但它的建表名没能改写（原因见上一条）——"
                    + "两处必须一起改，否则复制品会把数据写进源流程那张表";
        } else if (tableRenames.isEmpty()) {
            reason = "主表未改写：这张表不是本流程建的，复制品仍写入同一张表";
        } else {
            reason = "主表未改写：它与本流程新建的表名对不上（本流程改写的建表名见上），复制品仍写入同一张表";
        }
        out.add(unchanged(key, mainTable, reason));
    }

    /**
     * 加后缀类改写的公共形状：占位符跳过、关开关时只回显
     */
    private void rewriteSuffixed(ObjectNode args, String base, String param,
                                 boolean rewriteConflicts, List<Rewrite> out, String reason) {
        String key = base + param;
        String value = text(args, param);
        if (value == null) {
            return;
        }
        if (!rewriteConflicts) {
            out.add(unchanged(key, value,
                    "未改写（你关掉了自动改写）：复制品与源流程写同一个对象，发布前必须手工改"));
            return;
        }
        if (StateTemplateResolver.containsPlaceholder(value)) {
            out.add(unchanged(key, value, "未改写：它是运行期才定的占位符，复制时看不出真值"));
            return;
        }
        String candidate = value + SUFFIX;
        args.put(param, candidate);
        out.add(new Rewrite(key, value, candidate, reason));
    }

    /**
     * 「指向别处、刻意不复制」的那类提示：{@code from == to}，前端据此渲染成「未改写」而不是「已改写」
     */
    private static void noticeSubResource(List<Rewrite> out, String path, String value, String reason) {
        if (value != null) {
            out.add(unchanged(path, value, reason));
        }
    }

    private static Rewrite unchanged(String path, String value, String reasonFormat, Object... args) {
        return new Rewrite(path, value, value,
                args.length == 0 ? reasonFormat : String.format(reasonFormat, args));
    }

    /**
     * 节点在清单里的定位串：优先用节点 id（人能对上画布），没有才退回下标
     */
    private static String nodePath(JsonNode node, int index) {
        String id = text(node, K_ID);
        return K_NODES + "[" + (id == null ? String.valueOf(index) : id) + "]." + K_CONFIG;
    }

    private static ObjectNode child(JsonNode parent, String key) {
        JsonNode v = parent == null ? null : parent.get(key);
        return v instanceof ObjectNode o ? o : null;
    }

    /**
     * 取文本值；缺失、null 字面量、空白都返回 null
     *
     * <p>数字会被 {@code asText} 转成串（与 {@code ManifestArgs.str} 同口径）：
     * DSL 里把标识符写成数字是常见笔误，认成「没有这一格」会让复制静默跳过它，
     * 而转成串至少能在清单上被看见。
     */
    private static String text(JsonNode parent, String key) {
        JsonNode v = parent == null ? null : parent.get(key);
        if (v == null || v.isNull() || !v.isValueNode()) {
            return null;
        }
        String s = v.asText();
        return StrUtil.isBlank(s) ? null : s.trim();
    }

    /**
     * 改写结果
     *
     * @param dsl       新 DSL 原文（原始树就地改完再 toString，未建模字段一个不丢）
     * @param rewritten 改写清单，按发现顺序；可为空列表
     */
    public record Result(String dsl, List<Rewrite> rewritten) {
    }

    /**
     * 一格改写 / 一条「没改但你要知道」的提示
     *
     * <p>🔴 {@code from.equals(to)} 表示<b>没有改写</b>，前端据此分成两组渲染
     * （「已自动改写以下配置」/「以下配置未复制，仍需你确认」）。
     * 刻意不加一个 {@code changed} 布尔：两个字段说同一件事，早晚对不上。
     *
     * <p>🔴 {@code from}/{@code to} <b>永远不是密钥</b>：webhook 的 secret 那一行填的是
     * {@link #SECRET_MASK}。本对象会经 {@code /copy} 回到浏览器、可能被前端打进日志、
     * 也可能被冻结进 BPM 流程变量（同 {@code ChangeManifest} 的序列化契约），
     * 任何一条路都会把一份凭据从「只有 DSL 里有」变成「到处都是」。
     *
     * @param path   这一格在 DSL 里的位置，如 {@code nodes[save].config.args.code}
     * @param from   源值
     * @param to     新值；与 {@code from} 相同即未改写
     * @param reason 为什么这么处理，写给<b>要据此决定发不发布</b>的人看，故必须说清后果
     */
    public record Rewrite(String path, String from, String to, String reason) {
    }

}
