package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.AgentValidateResult;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.BpmProcessValidator;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.DataModelCreateValidator;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.DataModelUpdateValidator;
import cn.enlev.ainb.module.agent.tool.impl.datamodel.DataModelParamResolver;
import cn.enlev.ainb.module.agent.tool.impl.form.FormPublishBundleTool;
import cn.enlev.ainb.module.agent.tool.impl.form.FormPublishParamResolver;
import cn.enlev.ainb.module.agent.tool.impl.form.FormSaveDraftTool;
import cn.enlev.ainb.module.agent.tool.impl.process.ProcessCreateAndDeployTool;
import cn.enlev.ainb.module.bpm.api.definition.BpmCategoryApi;
import cn.enlev.ainb.module.bpm.api.definition.BpmModelApi;
import cn.enlev.ainb.module.bpm.api.definition.dto.BpmModelRespDTO;
import cn.enlev.ainb.module.infra.api.datamodel.DataModelApi;
import cn.enlev.ainb.module.infra.api.datamodel.dto.DataModelSaveReqDTO;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDesignMenuPreviewRespDTO;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDesignPublishRespDTO;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDesignSaveReqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 变更清单的只读干跑：把「平台将会做什么」里<b>只有查了才知道</b>的那一半补上（方案 §3-L2）
 *
 * <h3>与抽取器的分工</h3>
 * 抽取器报「DSL 里写了什么」，本类报「平台将会做什么」（判据见 {@link ChangeItem} 类注释）。
 * 于是本类是全清单<b>唯一</b>允许查库的地方，且只准调只读接口：
 * {@code DataModelApi#previewDdl} / {@code #getPhysicalTableList}、
 * {@code FormDesignApi#inspectFormDesign} / {@code #previewPublishMenu} / {@code #validateFlowReady}、
 * {@code BpmModelApi#getProcessConstraints} / {@code #getModelList}、
 * {@code BpmCategoryApi#getEnabledCategoryCodes}。一个写接口都不调 —— 干跑要能反复跑、
 * 能在设计器里随手打开，一旦留下副作用就再也不是「预演」了。
 *
 * <h3>🔴 干跑的步骤必须与真执行同一条链，否则「预演通过」是个假信号</h3>
 * 建表这一支照 {@code DataModelCreateTool#execute} 逐步骤对齐：
 * {@code resolveCreate} → {@code DataModelCreateValidator.validate} → 后端预演。
 * 少一步就会「清单说没问题、执行时失败」—— 那比不做干跑更坏，因为它给了用户信心。
 * <p>⚠️ 已知残留：这三步<b>都不查表名是否已被占用</b>（{@code previewDdl} 的新建分支
 * {@code DataModelServiceImpl#previewDdl} 与 {@code DataModelCreateValidator#validateTableName}
 * 都没有这一条，只有 {@code DataModelServiceImpl#createDataModel} 在执行时才拦），
 * 所以本类<b>另外</b>调 {@code getPhysicalTableList} 补一次存在性探测，结果落在
 * {@link ChangeItem#getConflict()} 而不是 {@link DryRunResult} —— 它不是「参数写错了」，
 * 是「撞上了已有的东西」，出路也不同（换名 / 导入）。这条链于是有了第三份副本，
 * 漂移面是「步骤清单」本身，用 {@code ChangeManifestDryRunnerTest} 的调用顺序断言钉住。
 *
 * <h3>覆盖到哪一步了</h3>
 * 十种 {@link ChangeKind} 里，五种探得了、两种<b>原理上</b>探不了、三种还没有接口：
 * <ul>
 *   <li>{@code TABLE_CREATE} / {@code TABLE_ALTER} —— 与真执行同一条链，外加一次表名撞车探测。</li>
 *   <li>{@code FORM_CREATE} —— 只探得到「主表存不存在」「编码撞没撞」两个查询；
 *       字段与列对不对得上要先把简化描述展开成 form-create 结构，那是工具的私有实现，
 *       重写一份必然漂移，故<b>探通了也不报绿</b>（理由见 {@code probeFormCreate}）。</li>
 *   <li>{@code FORM_PUBLISH} —— 体检 + 菜单预演，{@code publish} 的两条拒绝路径都覆盖了。</li>
 *   <li>{@code PROCESS_DEPLOY} —— 节点树合法性（调工具调的那个 {@code BpmProcessValidator} bean）、
 *       key 是新建还是覆盖、分类是否启用中、主表单能不能用于流程，四项都探；
 *       但<b>绑普通表单（{@code formId}）时不报绿</b>，节点上配了独立表单时也不报绿
 *       （理由见 {@code probeProcessDeploy}）。</li>
 *   <li>{@code GENERIC_TOOL} / {@code DATA_INTERFACE} —— 改什么取决于运行期参数与被调用方自己，
 *       没有只读接口能替它回答。这一条不是「还没做」，是做不了。</li>
 *   <li>{@code TABLE_REGISTER} / {@code FORM_UPDATE} / {@code DATA_WRITE}
 *       —— 落到 {@code default} 分支，明说「这类变更还没有干跑接口」。</li>
 * </ul>
 *
 * <h3>🔴 本类不抛异常</h3> * 契约与 {@link WorkflowChangeManifestBuilder#build} 同一条：清单是给人看的诊断物，
 * 它自己绝不能成为新的失败点。每一项的探测各自接住异常，落成
 * {@link DryRunResult.Outcome#NOT_PROBED}。
 *
 * <p>⚠️ 这里<b>刻意偏离</b>方案 §3-L2 核实结果 ② 那句「只 catch {@code ServiceException}，
 * infra 级故障要让它冒出来」。那句话的语境是 {@code DataModelPreviewDdlTool}：它的调用方是模型，
 * 异常冒出去会变成一条工具调用失败、模型能看见也能报告。而本类的调用方是清单构建，
 * 清单的契约是「照出、不抛」（方案决策表第 4 行）。两者不冲突的地方在于<b>意图</b>：
 * ② 真正要防的是「infra 挂了被渲染成这个变更没问题」，而三态模型正是为此而生 ——
 * 非 {@code ServiceException} 一律落 {@code NOT_PROBED}（黄色、「本次没校验」），
 * 绝不落 {@code PASSED}。意图保住了，且清单不会因为 infra 抖动整个消失。
 *
 * <h3>耗时</h3>
 * 一趟干跑最多打几次只读查询（改表那一支要读回现有结构，稍重）。方案 §6.1 认定
 * 「发布是低频动作，可接受」，并给了预算：单次 ≤3s、整体 ≤15s。
 * <p>🔴 本类只实现了<b>整体</b>那一条（{@link #BUDGET_SECONDS}，逐项检查剩余时间，
 * 超了就给剩下的项落 {@code NOT_PROBED}）。<b>单次那一条实现不了</b>：这些是<b>同进程</b>的
 * api 调用，要打断它得另起线程池 + {@code Future.get(timeout)}，而 JDBC 调用被打断后
 * 底层连接往往照旧跑完，等于白花一个线程。单次上限实际由数据源自己的 socket timeout 兜。
 * 这一条留给 S4b：发布闸门是 fail-close 的，那里「探不出来」要不要放行是个必须显式回答的问题，
 * 与这里「照出黄色」的处置不同。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class ChangeManifestDryRunner {

    /**
     * 整趟干跑的墙钟预算（秒）。方案 §6.1：整体 ≤15s
     */
    private static final int BUDGET_SECONDS = 15;

    private static final long BUDGET_NANOS = TimeUnit.SECONDS.toNanos(BUDGET_SECONDS);

    /**
     * 「没探成」的文案里，异常消息最多带多少字
     *
     * <p>要带（否则运维拿到满屏黄色无从判断是网络、权限还是 SQL 超时），
     * 但 {@code DataAccessException} 的消息里可能整条 SQL 都在里面，故截断。
     * 上限与 {@code ManifestArgs.MAX_VALUE_CHARS} 同一个数量级即可，两者无耦合。
     */
    private static final int REASON_DETAIL_MAX_CHARS = 200;

    @Resource
    private DataModelApi dataModelApi;
    @Resource
    private DataModelParamResolver dataModelParamResolver;
    @Resource
    private DataModelCreateValidator createValidator;
    @Resource
    private DataModelUpdateValidator updateValidator;
    @Resource
    private FormDesignApi formDesignApi;
    /**
     * 发布那一支要用它把「上级菜单名字」翻成编号
     *
     * <p>🔴 必须是<b>同一个 bean</b>，不能在这里另写一遍反查：干跑用的编号与真发布用的编号
     * 一旦不是同一份口径，清单就会说「挂到人事管理下」而执行时因为找不到目录整条流程失败。
     */
    @Resource
    private FormPublishParamResolver formPublishParamResolver;
    @Resource
    private BpmModelApi bpmModelApi;
    @Resource
    private BpmCategoryApi bpmCategoryApi;
    /**
     * 流程节点树的校验器
     *
     * <p>🔴 同样是<b>同一个 bean</b>：它读的是 {@code BpmModelApi#getProcessConstraints()}
     * 拿回来的真实取值（审批方式有哪几种、候选人策略有哪几种、子表分隔符是什么），
     * 一个枚举值都不抄。干跑器另写一份树校验，就等于把 bpm 的取值再抄一遍 ——
     * bpm 加一种审批方式，清单就开始把合法的树判成不合法。
     */
    @Resource
    private BpmProcessValidator processValidator;

    /**
     * 给已构建好的清单补上每一项的干跑结论，就地改 {@code manifest}
     *
     * <p>由 {@link WorkflowChangeManifestBuilder#build} 在遍历完节点后统一调用一次。
     * 放在这里而不是逐项插在遍历里，是因为<b>预算是整趟的</b>：逐项检查剩余时间
     * 才做得到「前面的项探得慢，后面的项就直接说没探」；同理，
     * 「本流程自己会建出哪些表」也要先看完全部项才知道。
     *
     * <p>⚠️ 必须<b>先于</b> builder 的 {@code finish} 调用：本方法往各项的
     * {@code detail.permissions} 里写按钮权限串，而 {@code finish} 的
     * {@code countPermissions} 正是从这里求和得出 {@code stats.permissions}
     * （headline 那句「含 N 个按钮权限」的数据源）。顺序反了 N 恒为 0。
     *
     * <p>🔴 本方法<b>只写逐项结论，不写 {@code stats}</b>。原先这里还顺手累加
     * {@code dryRunRejected} / {@code dryRunNotProbed} 两个计数，而 S2-d3d 的
     * {@link ManifestScoper} 裁剪发生在干跑<b>之后</b> —— 探的是全图、显示的是子图，
     * 那两个数就会把已经裁掉的项也算进去（「标题说 1 项、红标说 3 项干跑被拒」，
     * 而界面上找不到那 2 项）。汇总统一交给 {@code finish}，它从留下来的 {@code changes} 现算。
     *
     * @param manifest 已填好 {@code changes} 的清单；往各项的 {@code dryRun} / {@code conflict}
     *                 / {@code detail} 里写探测结果
     * @param ctx      本次构建的上下文。用它的 {@code nodeById} 按 id 找回节点，
     *                 用它的 {@code graph} 判断「建表节点走不走得到建表单节点」
     */
    public void enrich(ChangeManifest manifest, ManifestContext ctx) {
        if (manifest == null || manifest.getChanges().isEmpty()) {
            return;
        }
        CreatedTables tablesCreatedHere = tablesCreatedHere(manifest);
        long deadline = System.nanoTime() + BUDGET_NANOS;
        for (ChangeItem item : manifest.getChanges()) {
            DryRunResult result = System.nanoTime() > deadline
                    ? DryRunResult.notProbed("干跑总预算（" + BUDGET_SECONDS + " 秒）已用尽，本项未探测")
                    : probeSafely(item, ctx == null ? null : ctx.node(item.getNodeId()),
                            tablesCreatedHere, ctx);
            item.setDryRun(result);
        }
    }

    /**
     * 本流程自己会建出哪些表：表名（小写）→ 建它的那一项
     *
     * <p>🔴 这张表是「建表单」那一支不冤枉人的前提。典型 DSL 是
     * {@code 建表 → 存表单草稿 → 发布}，做清单的这一刻<b>表还不存在</b> ——
     * 直接拿主表名去查库，得到的结论是「主表不存在，会被拒」，
     * 而这份 DSL 恰恰是最标准的那一种。清单在最正常的流程上喊狼来了，
     * 用户学会的就是「这个红标不用看」，比不做干跑更坏。
     *
     * <p>只收 {@code TABLE_CREATE}：{@code TABLE_REGISTER}（导入已有表）登记的是
     * <b>库里已经有</b>的表，那种表查库本来就查得到，不需要在这里放行。
     *
     * <p>存的是整个 {@link ChangeItem} 而不只是节点 id，因为说给用户听时要的是
     * {@code nodeName}（「节点 n3」等于没说），而判断先后要的是 {@code nodeId}。
     *
     * <p>读的是抽取器填好的 {@link ChangeItem#getTarget()}（{@code target.tableName}），
     * 不再从 args 里剥一遍 —— {@code target} 就是「这项变更改的是哪个东西」的对外契约。
     */
    private CreatedTables tablesCreatedHere(ChangeManifest manifest) {
        Map<String, ChangeItem> tables = new LinkedHashMap<>();
        List<ChangeItem> namePending = new ArrayList<>();
        for (ChangeItem item : manifest.getChanges()) {
            if (item.getKind() != ChangeKind.TABLE_CREATE) {
                continue;
            }
            Object tableName = item.getTarget().get("tableName");
            if (tableName == null) {
                namePending.add(item);
                continue;
            }
            String name = tableName.toString();
            // 占位符表名不进这张表：它指的是运行期才知道的某张表，
            // 拿字面量去和别处的主表名比，比中了反而是假的
            if (StrUtil.isBlank(name) || ManifestArgs.isPending(name)) {
                namePending.add(item);
                continue;
            }
            tables.putIfAbsent(name.toLowerCase(Locale.ROOT), item);
        }
        return new CreatedTables(tables, namePending);
    }

    /**
     * 「本流程自己会建出哪些表」的两半
     *
     * @param byName      表名（小写）→ 建它的那一项；{@link #mainTableRejection} 据此放行「上游会建」的主表
     * @param namePending 表名<b>定不下来</b>的那些建表项（整份建表定义是占位符 ⟹ 抽取器读不到表名）。
     *                    它们进不了 {@code byName}，但绝不等于「本流程不建表」——
     *                    把它们一并交出去，{@link #mainTableRejection} 才分得开
     *                    「上游确实不建这张表」（该拒）与「上游会建、只是名字还没定」（该降成警告）。
     *                    🔴 少这半边的后果是缺陷 D4-17 的第二条红：示例 55 里
     *                    {@code create_table} 的 model 整个是占位符，于是下游 {@code save_draft}
     *                    被冤判「主表 biz_meeting_booking 在数据库里不存在」——
     *                    而这张表恰恰是本流程上一步要建的。
     */
    private record CreatedTables(Map<String, ChangeItem> byName, List<ChangeItem> namePending) {
    }

    // ==================== 分派 ====================

    /**
     * 探一项，任何异常都收在这里
     *
     * <p>连本类自己的分派代码出错（比如某个 {@code probe*} 里踩了 NPE）也要接住：
     * 一份「因为干跑器自己有 bug 而整个消失」的清单，比一份带着黄色的清单坏得多。
     */
    private DryRunResult probeSafely(ChangeItem item, NodeDTO node,
                                     CreatedTables tablesCreatedHere, ManifestContext ctx) {
        if (node == null) {
            return DryRunResult.notProbed("找不到节点「" + item.getNodeId() + "」，无从干跑");
        }
        if (item.getKind() == null) {
            return DryRunResult.notProbed("这项变更的类型没定下来，无从干跑");
        }
        // 🔴 args 的形状先判（缺陷 D4-14）：这一条与变更类型无关，五个 probe 全都会踩 ——
        //    它们开头都是同一句 ManifestArgs.of(node)。放在 switch 之前就不必写五遍，
        //    而「五份兜底里总有一份漏」正是 ManifestArgs 这个类当初存在的理由。
        //    结论是 REJECTED 而不是 NOT_PROBED：执行期收到空参数表是**确定**会失败的，
        //    说「本次没探」会让审批人放过一项注定跑不通的高风险变更。
        String argsShape = ManifestArgs.argsShapeProblem(node);
        if (argsShape != null) {
            return DryRunResult.rejected(argsShape);
        }
        try {
            return switch (item.getKind()) {
                case TABLE_CREATE -> probeTableCreate(item, node);
                case TABLE_ALTER -> probeTableAlter(item, node);
                case FORM_CREATE -> probeFormCreate(item, node, tablesCreatedHere, ctx);
                case FORM_PUBLISH -> probeFormPublish(item, node);
                case PROCESS_DEPLOY -> probeProcessDeploy(item, node);
                case GENERIC_TOOL, DATA_INTERFACE -> DryRunResult.notProbed(
                        "平台无从预演这项变更：它改什么取决于运行期的参数与被调用方自己，"
                                + "没有只读接口能替它回答");
                default -> DryRunResult.notProbed("「" + item.getKind().getLabel()
                        + "」这类变更还没有干跑接口，本次未校验它能不能跑通");
            };
        } catch (Exception ex) {
            log.warn("[probeSafely][干跑自身出错 节点({}) 类型({})]", item.getNodeId(), item.getKind(), ex);
            return DryRunResult.notProbed(probeBroken("干跑", ex));
        }
    }

    // ==================== 建表 ====================

    /**
     * 建表：先探「表名撞没撞上已有的表」，再走与 {@code datamodel_create} 同一条预演链
     *
     * <p>顺带就地填 {@link ChangeItem#setConflict}：撞车是这一支才探得出来的东西，
     * 放在别处就得把 {@code getPhysicalTableList} 的结果再传一遍。
     */
    private DryRunResult probeTableCreate(ChangeItem item, NodeDTO node) {
        Map<String, Object> args = ManifestArgs.of(node);
        // 🔴 整份建表定义写成占位符时必须<b>先</b>拦（缺陷 D4-17）：下面那句 ManifestArgs.map
        //    解析 "{{$.prepared_args.model}}" 会失败并按契约静默返回空 Map，
        //    tableName 于是是 null 而<b>不是</b>占位符 —— 「表名是占位符」那道守卫形同不存在，
        //    原始占位符被送进 resolveCreate，hutool 的库内部文案一路透传成一条体检 error。
        //    判据收在 ManifestArgs#pendingObject，与部署那一支的 simpleModel 共用一份。
        String pendingModel = ManifestArgs.pendingObject(args, DataModelParamResolver.PARAM_MODEL);
        if (pendingModel != null) {
            return DryRunResult.notProbed("整份建表定义要到运行期才由脚本节点算出（" + pendingModel
                    + "），无从干跑；执行期它会被渲染成 JSON 串，建表工具两种形态都收");
        }
        // 🔴 建表定义整个嵌在 model 下面一层，与 DataModelCreateChangeExtractor#extract 同一个剥法
        String tableName = ManifestArgs.str(
                ManifestArgs.map(args, DataModelParamResolver.PARAM_MODEL),
                DataModelParamResolver.PARAM_TABLE_NAME);
        // 占位符拿去干跑，infra 会按「表名不合法」拒 —— 而 DSL 其实没问题，只是值还没定。
        // 报 REJECTED 就是清单在冤枉一份合法的 DSL，用户会去改一个不该改的地方
        if (ManifestArgs.isPending(tableName)) {
            return DryRunResult.notProbed("表名要到运行期才确定（" + tableName + "），无从干跑");
        }
        List<String> extraWarnings = new ArrayList<>();
        if (StrUtil.isNotBlank(tableName)) {
            item.setConflict(tableConflict(tableName, extraWarnings));
        }
        // 🔴 撞车就到此为止：这一项已经是红的，出路是「换名 / 导入已有表」，
        //    再把它本来要跑的那串 DDL 预演出来并不能帮用户早点动手。
        //    代价是同一个节点上的第二个问题（比如列类型不合规）要等撞车解决后才露头 ——
        //    两句话挤在一个 reason 里读不出先后，分开两轮反而清楚。
        if (item.getConflict() != null) {
            return DryRunResult.rejected(item.getConflict().getMessage());
        }
        return previewCreate(args, item.getTool(), extraWarnings);
    }

    private DryRunResult previewCreate(Map<String, Object> args, String tool,
                                       List<String> extraWarnings) {
        try {
            DataModelSaveReqDTO reqDTO = dataModelParamResolver.resolveCreate(args, tool);
            return previewDdl(reqDTO, createValidator.validate(reqDTO), extraWarnings);
        } catch (ServiceException ex) {
            return DryRunResult.rejected(ex.getMessage()).withWarnings(extraWarnings);
        } catch (Exception ex) {
            log.warn("[previewCreate][建表干跑没探成 工具({})]", tool, ex);
            return DryRunResult.notProbed(probeBroken("建表干跑", ex));
        }
    }

    // ==================== 改表 ====================

    private DryRunResult probeTableAlter(ChangeItem item, NodeDTO node) {
        Map<String, Object> args = ManifestArgs.of(node);
        String tableName = ManifestArgs.str(args, DataModelParamResolver.PARAM_TABLE_NAME);
        if (ManifestArgs.isPending(tableName)) {
            return DryRunResult.notProbed("表名要到运行期才确定（" + tableName + "），无从干跑");
        }
        // 改表不探撞车：它要的恰恰是「表已经在了」，不在的话 resolveUpdate 自己会说
        return previewAlter(args, item.getTool());
    }

    private DryRunResult previewAlter(Map<String, Object> args, String tool) {
        try {
            DataModelParamResolver.UpdateResolution resolution =
                    dataModelParamResolver.resolveUpdate(args, tool);
            return previewDdl(resolution.reqDTO(),
                    updateValidator.validate(resolution.reqDTO(), resolution.current()),
                    List.of());
        } catch (ServiceException ex) {
            return DryRunResult.rejected(ex.getMessage());
        } catch (Exception ex) {
            log.warn("[previewAlter][改表干跑没探成 工具({})]", tool, ex);
            return DryRunResult.notProbed(probeBroken("改表干跑", ex));
        }
    }

    // ==================== 建表单 ====================

    /**
     * 建表单草稿：照 {@code FormSaveDraftTool#execute} 的顺序探，探得到几步算几步
     *
     * <h3>🔴 探通了也<b>不落 PASSED</b></h3>
     * 工具那条链是：必填参数 → 主表存在性（{@code :92}）→ 展开字段（{@code :99}）→
     * 结构校验（{@code :101}）→ 编码撞车（{@code :111}）→ 落库。本方法只探得到其中两个<b>查询</b>：
     * 主表存在性与编码撞车。中间那两步探不了 —— {@code build} 要把简化字段描述<b>展开成
     * form-create 的 rule 与 fieldMapping</b>，而 {@code FormSchemaValidator} 校验的正是展开后的产物
     * （字段与列对不对得上、列类型能不能绑这个控件）。展开逻辑是工具的私有实现，
     * 在这里重写一份就是 {@code FormSaveDraftParamValidator} 类注释点名的那个坑：
     * 两份必然漂移，漂移的表现是「清单说没问题、执行时报错」。
     *
     * <p>所以结构那一半<b>探不了</b>，全查过了也只能落 {@code NOT_PROBED}，
     * 并把「查过什么、没查什么」写进 reason。{@code PASSED} 的契约是
     * 「探到了，且这么改跑得通」（见 {@link DryRunResult.Outcome}），
     * 少查了一半还报绿，就是把方案 §3-L2 那个假信号再造一遍。
     *
     * <h3>为什么不在这里跑 {@code FormSaveDraftParamValidator}</h3>
     * 它确实能拦下不少（控件类型幻觉、缺 title/jdbcType、字段映射到 tenant_id），
     * 但它<b>不在工具的执行链上</b> —— 那是规划期把错误回灌给模型自修正的一层（方案 §5.5）。
     * 本类的铁律是「与真执行同一条链」，把另一层的校验混进来，清单就会报一件
     * 执行时根本不会发生的拒绝。手改 DSL 绕过规划期的情况归 S4a 的校验层管。
     */
    private DryRunResult probeFormCreate(ChangeItem item, NodeDTO node,
                                         CreatedTables tablesCreatedHere,
                                         ManifestContext ctx) {
        Map<String, Object> args = ManifestArgs.of(node);
        List<String> warnings = new ArrayList<>();

        DryRunResult rejected = mainTableRejection(item,
                ManifestArgs.str(args, FormSaveDraftTool.PARAM_MAIN_TABLE),
                tablesCreatedHere, ctx, warnings);
        if (rejected != null) {
            return rejected.withWarnings(warnings);
        }

        String code = ManifestArgs.str(args, FormSaveDraftTool.PARAM_CODE);
        if (ManifestArgs.isPending(code)) {
            warnings.add("表单编码要到运行期才确定（" + code + "），没能确认它是否已被占用");
        } else if (StrUtil.isBlank(code)) {
            // 必填缺失归 S4a 的校验层；这里不复刻工具那句「缺少 code（表单编码）」，
            // 那会是第二份文案，而清单多一句少一句并不改变审批人的决定
            warnings.add("表单编码没填，没能确认它是否已被占用");
        } else {
            DryRunResult taken = codeTakenRejection(item, code, warnings);
            if (taken != null) {
                return taken.withWarnings(warnings);
            }
        }

        // 🔴 括号里那句不是客套：本项的 warnings 里可能正躺着「主表名没填 / 是占位符 /
        //    上游建表项表名定不下来 / 探测自己失败」这类<b>没能确认</b>的条目，
        //    而「都查过了、没撞上」单读起来会被当成「主表确认存在」。
        //    清单上这两句话挨着显示，互相打架时用户只会挑对自己方便的那句读。
        return DryRunResult.notProbed("主表与表单编码都查过了、没撞上（若本项另有警告，说明有没能确认的，以警告为准）；"
                        + "但字段与列对不对得上、"
                        + "控件类型能不能绑这些列，要等运行期把字段展开成表单结构之后才校验得了，本次没探")
                .withWarnings(warnings);
    }

    /**
     * 主表存在性：先问「本流程自己会不会把它建出来」，问不出再查库
     *
     * <p>判据与 fail-soft 口径都照 {@code FormSaveDraftTool#validateMainTableExists}：
     * 查询本身失败时<b>不据此拒</b>（「校验的依赖」故障不该变成「这个变更不合法」），
     * 只留一条 warning。文案则共用工具那份 {@code mainTableMissingMessage}。
     *
     * <p>⚠️ 「库里查不到」不恒等于「该拒」。还有一形态是<b>上游建表项的表名定不下来</b>
     * （整份建表定义写成了占位符 ⟹ 抽取器读不到表名 ⟹ 它进不了 {@code byName}），
     * 见 {@link #reachableNamePendingCreate}；那种情况只能降成 warning（缺陷 D4-17）。
     *
     * @return 该拒时返回结论；不该拒（含「没能确认」）返回 null，
     *         要说的话已经写进 {@code warnings} 了
     */
    private DryRunResult mainTableRejection(ChangeItem item, String mainTable,
                                            CreatedTables tablesCreatedHere,
                                            ManifestContext ctx, List<String> warnings) {
        if (StrUtil.isBlank(mainTable)) {
            warnings.add("主表名没填，没能确认它存不存在");
            return null;
        }
        if (ManifestArgs.isPending(mainTable)) {
            warnings.add("主表名要到运行期才确定（" + mainTable + "），没能确认它存不存在");
            return null;
        }
        ChangeItem creator = tablesCreatedHere.byName().get(mainTable.toLowerCase(Locale.ROOT));
        if (creator != null) {
            // 本流程里有建表节点会建出它。但「会建」还得「建在前面」才算数
            if (ctx != null && ctx.graph() != null
                    && ctx.graph().reaches(creator.getNodeId(), item.getNodeId())) {
                return null;
            }
            return DryRunResult.rejected("表单要绑的主表 " + mainTable + " 由本流程的「"
                    + StrUtil.blankToDefault(creator.getNodeName(), creator.getNodeId())
                    + "」建，但那个节点走不到这个节点（排在它后面，或在另一条分支上）。"
                    + "照这个顺序执行，建表单时表还没建出来，会被工具拦下");
        }
        DataModelApi.PhysicalTableRespDTO table;
        try {
            table = findExactTable(mainTable);
        } catch (Exception ex) {
            log.warn("[mainTableRejection][主表存在性探测没探成 表名({})]", mainTable, ex);
            warnings.add("未能确认主表 " + mainTable + " 是否存在（" + brief(ex)
                    + "）；若它不存在，建表单会在执行时被拦下");
            return null;
        }
        if (table == null) {
            // 🔴 库里确实没有，但上游有个「会建表、表名却定不下来」的节点走得到这里 ⟹ 只能说「无从确认」，
            //    不能说「主表不存在」。缺陷 D4-17 的第二条红就是这个：整份建表定义写成占位符时
            //    抽取器读不到表名，byName 里于是没有它 —— 而它建的偏偏就是这张表。
            //    照原来那句直接拒，用户读到的是「请先用 datamodel_create 把它建出来」，
            //    可他自己那份流程上一步就在建。这正是本类 tablesCreatedHere 注释里写过的代价：
            //    在最标准的流程上喊狼来了，用户学会的就是「这个红标不用看」。
            ChangeItem pending = reachableNamePendingCreate(tablesCreatedHere, ctx, item);
            if (pending != null) {
                warnings.add("库里现在没有主表 " + mainTable + "，但本流程的「"
                        + StrUtil.blankToDefault(pending.getNodeName(), pending.getNodeId())
                        + "」会建一张表、且它走得到这个节点，只是那张表的名字要到运行期才由脚本算出，"
                        + "无从确认建的是不是 " + mainTable + "；若它建的是别的名字，建表单会在执行时被拦下");
                return null;
            }
            return DryRunResult.rejected(FormSaveDraftTool.mainTableMissingMessage(mainTable));
        }
        return null;
    }

    /**
     * 上游有没有一个「会建表、但表名定不下来」的节点，且它<b>走得到</b>本节点
     *
     * <p>🔴 必须要求「走得到」：整张图里随便有个建表节点表名待定，就给所有缺主表的建表项放行，
     *    等于把主表存在性这条判据整体关掉 —— 另一条分支上建的表救不了这条分支上的表单。
     *    而上面 {@code creator != null} 那一支正是为「建在后面 / 在另一条分支上」硬拒的，
     *    两处对「先后与可达」的口径必须一致，否则同一张图在两处会得到相反的结论。
     */
    private ChangeItem reachableNamePendingCreate(CreatedTables tablesCreatedHere,
                                                  ManifestContext ctx, ChangeItem item) {
        if (ctx == null || ctx.graph() == null) {
            return null;
        }
        for (ChangeItem candidate : tablesCreatedHere.namePending()) {
            if (ctx.graph().reaches(candidate.getNodeId(), item.getNodeId())) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 表单编码撞车。撞上时同时填 {@link ChangeItem#setConflict} ——
     * 出路是「换个编码」或「直接用那张现成的表单」，与「参数写错了」不是一回事
     */
    private DryRunResult codeTakenRejection(ChangeItem item, String code, List<String> warnings) {
        FormDesignSaveReqDTO existing;
        try {
            existing = formDesignApi.getFormDesignByCode(code);
        } catch (Exception ex) {
            log.warn("[codeTakenRejection][表单编码占用探测没探成 编码({})]", code, ex);
            warnings.add("未能确认表单编码 " + code + " 是否已被占用（" + brief(ex)
                    + "）；若已被占用，建表单会在执行时才失败");
            return null;
        }
        if (existing == null) {
            return null;
        }
        ChangeItem.Conflict conflict = new ChangeItem.Conflict();
        conflict.setType(ChangeItem.Conflict.TYPE_FORM_CODE_TAKEN);
        conflict.setMessage(FormSaveDraftTool.codeTakenMessage(code, existing.getId()));
        item.setConflict(conflict);
        return DryRunResult.rejected(conflict.getMessage());
    }

    // ==================== 发布表单 ====================

    /**
     * 发布表单：照 {@code FormDesignServiceImpl#publish} 的顺序探它的两条拒绝路径
     *
     * <h3>为什么这两步就够</h3>
     * {@code publish} 只有两处会拒：
     * <ol>
     *   <li>{@code :383} 的 {@code publishValidator.validate(formDesign)}。它<b>就是</b>
     *       {@code inspect} 的结果拼一句话（{@code FormDesignPublishValidator#validate}：
     *       {@code inspect(...)} 非空则 {@code throw exception(..., String.join("；", problems))}）。
     *       所以这里调 {@code inspectFormDesign} 拿回来的问题清单，正是发布时会抛的那句话，
     *       <b>逐字不差</b> —— 连分隔符都是同一个「；」。</li>
     *   <li>{@code :387-389} 的「首发却没给上级菜单」。这一条在本链上<b>恒不成立</b>：
     *       {@code FormPublishParamResolver#resolveParentMenuId} 永不返回 null
     *       （不传或传 0 一律 {@code 0L}），而判据是 {@code parentMenuId == null}。
     *       故 {@code previewPublishMenu} 的 {@code parentMenuMissing} 在这里不必处置 ——
     *       真要传个 null 进去，反倒会报一件本工具根本不会犯的错。
     *       那条不变量由 {@code FormPublishParamResolverTest#parentMenu_absentMeansTopLevelNeverNull} 钉住。</li>
     * </ol>
     * 剩下的步骤（存版本快照、改状态、广播事件）都不会拒，也就不必预演。
     *
     * <h3>顺带把「会建出什么菜单」填进 detail</h3>
     * 这正是 {@code FormDesignApi#previewPublishMenu} 这个只读出口存在的理由：
     * 建不建菜单取决于「这张表单当前有没有绑过菜单」，光看 DSL 算不出来，
     * 抽取器硬算的结果是<b>再发布时也报「将新建 1 个菜单」</b>。
     *
     * <p>⚠️ {@code inspectFormDesign} 会顺带清一次表结构元数据缓存
     * （{@code FormDesignServiceImpl#inspect}）。对数据是只读的，但不是零成本：
     * 一趟干跑里每个发布项清一次。方案 §6.1 认定发布是低频动作，可接受。
     */
    private DryRunResult probeFormPublish(ChangeItem item, NodeDTO node) {
        Map<String, Object> args = ManifestArgs.of(node);
        String rawId = ManifestArgs.str(args, FormPublishBundleTool.PARAM_ID);
        // 🔴 占位符必须在交给解析器<b>之前</b>拦下。典型 DSL 里发布的是上一步刚建的表单，
        //    id 写成 {{$.save_form.tool_result.id}}，而 resolveFormId 对它抛
        //    「id 必须是数字，实际收到：{{…}}」—— 那会把一份合法的 DSL 判成 REJECTED
        if (ManifestArgs.isPending(rawId)) {
            return DryRunResult.notProbed("表单编号要到运行期才确定（" + rawId + "），无从干跑");
        }

        List<String> warnings = new ArrayList<>();
        Long formId;
        try {
            formId = formPublishParamResolver.resolveFormId(args);
        } catch (ServiceException ex) {
            return DryRunResult.rejected(ex.getMessage());
        }

        ParentMenu parent = resolveParentMenu(args, warnings);
        if (parent.rejection() != null) {
            return DryRunResult.rejected(parent.rejection()).withWarnings(warnings);
        }

        DryRunResult notPassed = inspectForm(formId, warnings);
        if (notPassed != null) {
            return notPassed;
        }
        if (parent.id() == null) {
            // 体检已过 ⟹ 发布本身能成，只是说不清菜单会建成什么样。
            // 不落 PASSED-with-menu：那份菜单预演根本没做
            return DryRunResult.passed("体检通过，可以发布").withWarnings(warnings);
        }
        return previewMenu(item, formId, parent.id(), warnings);
    }

    /**
     * 上级菜单的解析结果：{@code id} 为 null 表示「是占位符、查不了」，
     * {@code rejection} 非 null 表示解析器已经给出了拒绝理由
     *
     * <p>不用「返回 null 代表占位符、抛异常代表拒绝」那种写法：
     * 这里 null 已经在表示另一件事了，一个 null 担两个含义正是本清单要消灭的含糊。
     */
    private record ParentMenu(Long id, String rejection) {
    }

    /**
     * 解析上级菜单，但<b>先把占位符拦在解析器外面</b>
     *
     * <p>🔴 {@code FormPublishParamResolver} 是给<b>执行期</b>用的，那时占位符已经被
     * {@code ToolNodeExecutor} 替换成了真值。干跑读的是<b>裸 DSL</b>，
     * 把 {@code parentMenuName="{{$.menu_name}}"} 原样喂进去，它会拿这个字面量去反查启用目录、
     * 必然查不到，于是抛出「找不到名为「{{$.menu_name}}」的启用目录」——
     * 一份合法的 DSL 被判成 REJECTED，用户会去改一个根本没写错的地方。
     */
    private ParentMenu resolveParentMenu(Map<String, Object> args, List<String> warnings) {
        String rawName = ManifestArgs.str(args, FormPublishBundleTool.PARAM_PARENT_MENU_NAME);
        String rawId = ManifestArgs.str(args, FormPublishBundleTool.PARAM_PARENT_MENU_ID);
        if (ManifestArgs.isPending(rawName) || ManifestArgs.isPending(rawId)) {
            warnings.add("上级菜单要到运行期才确定（" + StrUtil.blankToDefault(rawName, rawId)
                    + "），没能预演菜单会建成什么样");
            return new ParentMenu(null, null);
        }
        try {
            return new ParentMenu(formPublishParamResolver.resolveParentMenuId(args), null);
        } catch (ServiceException ex) {
            // 最常见的一种：模型把用户说的菜单名写错一个字。
            // 它会让这条五连写在第一步就失败，而失败前一步（发布）已经真发布了
            return new ParentMenu(null, ex.getMessage());
        }
    }

    /**
     * 表单体检 = 发布的第一步校验
     *
     * @return 体检没过（REJECTED）或体检没探成（NOT_PROBED）时返回结论；
     *         体检通过返回 null，由调用方接着往下走
     */
    private DryRunResult inspectForm(Long formId, List<String> warnings) {
        List<String> problems;
        try {
            problems = formDesignApi.inspectFormDesign(formId);
        } catch (ServiceException ex) {
            // 表单不存在就是这一条（FORM_DESIGN_NOT_EXISTS）。发布时会抛同一句
            return DryRunResult.rejected(ex.getMessage()).withWarnings(warnings);
        } catch (Exception ex) {
            log.warn("[inspectForm][表单体检没探成 表单({})]", formId, ex);
            return DryRunResult.notProbed(probeBroken("表单体检", ex)).withWarnings(warnings);
        }
        if (CollUtil.isEmpty(problems)) {
            return null;
        }
        // 🔴 用「；」连成一行，与 FormDesignPublishValidator#validate 抛的那句逐字相同
        return DryRunResult.rejected(String.join("；", problems))
                .withWarnings(warnings)
                .withEvidence("inspectIssues", problems);
    }

    /**
     * 菜单预演，并把结果填进 {@code detail.menu} / {@code detail.permissions}
     *
     * <p>⚠️ 这一步探不成<b>不影响结论</b>：体检已过，发布就会成功，
     * 菜单预演只是「顺便告诉你会建成什么样」。故失败落一条 warning、仍然 PASSED ——
     * 与前面几处「探不成就落黄色」不同，因为那里的探测是<b>判据</b>，这里的是<b>说明</b>。
     */
    private DryRunResult previewMenu(ChangeItem item, Long formId, Long parentMenuId,
                                     List<String> warnings) {
        FormDesignMenuPreviewRespDTO preview;
        try {
            preview = formDesignApi.previewPublishMenu(formId, parentMenuId);
        } catch (Exception ex) {
            log.warn("[previewMenu][菜单预演没探成 表单({})]", formId, ex);
            warnings.add("菜单预演没探成（" + brief(ex) + "）；发布本身照样会成功，"
                    + "只是说不清这次会不会新建菜单");
            return DryRunResult.passed("体检通过，可以发布").withWarnings(warnings);
        }
        if (preview == null) {
            warnings.add("菜单预演没有返回结果；发布本身照样会成功，只是说不清这次会不会新建菜单");
            return DryRunResult.passed("体检通过，可以发布").withWarnings(warnings);
        }
        // parentMenuMissing 恒为 false，理由见 probeFormPublish 的方法注释，故这里不处置它
        if (!Boolean.TRUE.equals(preview.getNeedCreateMenu()) || preview.getMenuConfig() == null) {
            item.getDetail().put("menu", "已绑过菜单"
                    + (preview.getExistingMenuId() == null ? "" : " #" + preview.getExistingMenuId())
                    + "，本次只出新版本，不新建菜单");
            // 🔴 刻意不写 detail.permissions：builder 的 countPermissions 会把它求和进
            //    stats.permissions，headline 于是说「含 N 个按钮权限」。
            //    再发布一个按钮都不建，写上去就是清单在报一件不会发生的事 ——
            //    那正是 previewPublishMenu 这个出口要消灭的那句话
            return DryRunResult.passed("体检通过。表单已绑过菜单，本次只出新版本，不新建菜单")
                    .withWarnings(warnings)
                    .withEvidence("existingMenuId", preview.getExistingMenuId());
        }

        FormDesignPublishRespDTO.MenuConfig config = preview.getMenuConfig();
        List<String> permissions = new ArrayList<>();
        for (FormDesignPublishRespDTO.PermissionConfig perm
                : CollUtil.emptyIfNull(config.getPermissions())) {
            if (perm != null) {
                permissions.add(perm.getPermission());
            }
        }
        item.getDetail().put("menu", menuProjection(config, permissions.size()));
        item.getDetail().put("permissions", permissions);
        // 🔴 记录数一律算出来，不写死「8」：按钮个数来自 infra 的 actions 数组
        //    （FormDesignServiceImpl#buildMenuConfig，当前 7 个），它加一个动作这里就得跟着变，
        //    写死的话清单会少报一条菜单记录
        return DryRunResult.passed("体检通过。这次发布会新建 1 个菜单，含 " + permissions.size()
                + " 个按钮权限，共 " + (permissions.size() + 1) + " 条菜单记录")
                .withWarnings(warnings)
                .withEvidence("permissionCount", permissions.size());
    }

    /**
     * 菜单配置的<b>投影</b>，只留审批人要看的那几项
     *
     * <p>🔴 不直接把 {@code MenuConfig} 整个塞进 detail：{@code ChangeItem#detail} 与
     * {@code DryRunResult#evidence} 的序列化契约是「只放字符串、数字、布尔、List 与 Map」
     * （清单要被冻结进 BPM 流程变量，读回来是 Map 而不是原类型）。
     * 放 POJO 眼下能渲染，读回来就变了形状 —— 那是只有二次读取才会露出来的坑。
     *
     * <p>剩下的 component / componentName / icon / sort 对「要不要同意这次发布」没有帮助，
     * 全列出来只会把最要紧的那三项挤到折叠区里。
     */
    private Map<String, Object> menuProjection(FormDesignPublishRespDTO.MenuConfig config,
                                               int permissionCount) {
        Map<String, Object> menu = new LinkedHashMap<>();
        menu.put("name", config.getName());
        menu.put("path", config.getPath());
        menu.put("permissionCount", permissionCount);
        return menu;
    }

    // ==================== 部署审批流 ====================

    /**
     * 部署审批流：照 {@code ProcessCreateAndDeployTool#execute} 的顺序探
     *
     * <h3>这一支为什么非做不可</h3>
     * 它是十种变更里唯一「部署成功也可能不是你要的」那一类：审批节点的 {@code approveType}
     * 写错，候选人、审批方式、字段权限、按钮、超时、监听器<b>全部被静默丢弃，而部署照样成功</b>
     * （{@code BpmProcessValidator} 类注释点名的第三类失败，也是本子系统最典型的缺陷形态）。
     * 抽取器刻意一个字都不往清单里搬 {@code simpleModel}（几十 KB 的节点树进不了要被冻结的清单），
     * 于是「这棵树合不合法」全清单只有这里能回答。
     *
     * <h3>🔴 回答的办法是调工具调的那个 bean，不是自己再判一遍</h3>
     * {@link BpmProcessValidator} 的取值一律来自 {@code BpmModelApi#getProcessConstraints()}，
     * 一个枚举值都不抄。在这里另写一份「节点树体检」就是第二份口径，
     * 分叉的表现是「清单说合法、部署时被拒」或反过来 —— 与 {@code formPublishParamResolver}
     * 同一条规矩。
     *
     * <h3>探到了什么、没探到什么</h3>
     * 探到四项：节点树合法性（含 approveType 那条静默失效规则）、key 是新建还是覆盖、
     * 分类是否启用中、主表单能不能用于流程。
     * <p>没探两项：① 绑普通表单（{@code formId}）时表单还在不在 —— agent 这侧没有只读出口；
     * ② 节点上各自绑的独立表单（{@code validateNodeForms}）—— 见方法末尾那段降级。
     * 两项都落 {@code NOT_PROBED}，绝不落 {@code PASSED}。
     *
     * <p>⚠️ 与真执行的顺序有一处刻意偏离：真执行是「树 → 分类 → 表单 → 部署时才查 key」，
     * 本方法把查 key 提到了表单之前。查 key 的结论<b>不是拒绝</b>而是「这是覆盖」这个事实，
     * 而它是审批人点同意前必须知道的事（覆盖会整体替换掉一条正在用的流程）；
     * 排在后面的话，一旦被表单问题短路就再也看不见了。
     */
    private DryRunResult probeProcessDeploy(ChangeItem item, NodeDTO node) {
        Map<String, Object> args = ManifestArgs.of(node);

        // 🔴 两个占位符守卫都必须排在真校验之前：key 是 {{$.x}} 时 validateKey 会按
        //    「不是合法的流程标识」拒，simpleModel 是 {{$.x}} 时 parseSimpleModel 会按
        //    「不是合法的 JSON 对象」拒 —— 两次都是在冤枉一份合法的 DSL
        String key = ManifestArgs.str(args, ProcessCreateAndDeployTool.PARAM_KEY);
        if (ManifestArgs.isPending(key)) {
            return DryRunResult.notProbed("流程标识要到运行期才确定（" + key + "），无从干跑");
        }
        String pendingTree = ManifestArgs.pendingObject(args, ProcessCreateAndDeployTool.PARAM_SIMPLE_MODEL);
        if (pendingTree != null) {
            return DryRunResult.notProbed("流程节点树要到运行期才确定（" + pendingTree + "），无从干跑");
        }

        List<String> warnings = new ArrayList<>();
        String simpleModel;
        try {
            // 归一化也共用工具那份：失败路径上它说得出「不是合法的 JSON 对象」，
            // 换成 ManifestArgs.map 的「解析不出来就当空对象」，报给用户的就成了
            // 「根节点的 type 必须是 10，当前是 null」—— 指着一个不存在的问题让人去改
            simpleModel = ProcessCreateAndDeployTool.parseSimpleModel(args);
        } catch (ServiceException ex) {
            return DryRunResult.rejected(ex.getMessage());
        }

        DryRunResult treeRejected = validateProcessTree(key, simpleModel, warnings);
        if (treeRejected != null) {
            return treeRejected;
        }
        describeDeployMode(item, key, warnings);
        checkCategory(args, warnings);

        String formDesignCode = ManifestArgs.str(args, ProcessCreateAndDeployTool.PARAM_FORM_DESIGN_CODE);
        if (formDesignCode == null) {
            // 走 formId（BPM 自带动态表单）那一支：BpmModelServiceImpl#deployModel 只校验 formId 非空
            // 与该表单存在，而「普通表单还在不在」agent 这侧没有只读出口 ⟹ 探不了，不报绿。
            // 这一支同时不看下面那段 formCode 扫描：BpmModelServiceImpl#deployModel 的节点表单校验
            // 只在 formType == ONLINE 时才跑，普通表单流程根本不校验节点上的独立表单。
            // 两个参数都没给的情况也不在这里复刻工具那句「必须绑定一张表单」：
            // 抽取器的 summary 已经说了，再说一遍就是第二份文案
            return DryRunResult.notProbed("这条流程没绑在线表单；绑的普通表单还在不在、配置齐不齐，"
                            + "agent 这侧没有只读接口能查，本次没探")
                    .withWarnings(warnings);
        }
        DryRunResult formRejected = checkMainForm(formDesignCode, warnings);
        if (formRejected != null) {
            return formRejected;
        }

        // 🔴 节点上还配了独立表单的话，部署时 bpm 会逐个校验（BpmModelServiceImpl#deployModel →
        //    BpmOnlineFormValidator#validateNodeForms，同样是 validateFlowReady），
        //    这一步这里探不了，故降级成黄色。
        //    判据是对序列化后的树做一次 "formCode" 字面量扫描。粗是刻意的：
        //    真去遍历就是第三份节点树遍历（bpm 的 collectNodes、agent 的
        //    ProcessNodeTreeExplainer 各已有一份，后者还不对外暴露节点上的 formCode）。
        //    粗扫的偏差方向是安全的 —— 把「其实没配独立表单」误判成黄色，
        //    绝不会把「配了但不可用」漏成绿色。
        if (simpleModel.contains("\"formCode\"")) {
            return DryRunResult.notProbed("流程标识、节点树、分类、主表单都查过了、没发现问题；"
                            + "但节点上还配了独立表单，部署时会逐个校验它们能不能用于流程，本次没探")
                    .withWarnings(warnings);
        }
        return DryRunResult.passed("预演通过：节点树合法、主表单可用于流程；这些改动尚未执行")
                .withWarnings(warnings);
    }

    /**
     * 节点树合法性：与工具<b>同两步、同顺序</b>
     *
     * <p>{@code ProcessCreateAndDeployTool#execute} 是 {@code validate(key, simpleModel)} 紧跟
     * {@code validateFirstApproveNode(parseObj(simpleModel), result)}，两步往同一个 result 里写。
     * 少调第二步就会漏掉「首个审批节点用了『审批人自选』」那类部署期才报的错。
     *
     * <p>拒绝文案用「；」连成一行而不用 {@code toFeedback()}：那份编号列表是回灌给 LLM
     * 自修正的格式（{@code AgentValidateResult} 类注释），清单是给人看的 —— 与建表那一支同一条口径。
     *
     * @return 该拒/没探成时返回结论；通过返回 null，警告已经写进 {@code warnings} 了
     */
    private DryRunResult validateProcessTree(String key, String simpleModel, List<String> warnings) {
        AgentValidateResult result;
        try {
            result = processValidator.validate(key, simpleModel);
            processValidator.validateFirstApproveNode(JSONUtil.parseObj(simpleModel), result);
        } catch (Exception ex) {
            log.warn("[validateProcessTree][节点树校验没跑成 key({})]", key, ex);
            return DryRunResult.notProbed(probeBroken("节点树校验", ex));
        }
        // 警告在通过与不通过两条路上都要带出去：它们说的正是「后端不拦、但建出来的不是你要的」
        warnings.addAll(result.getWarnings());
        if (result.isPassed()) {
            return null;
        }
        List<String> errors = result.getErrors();
        return DryRunResult.rejected(String.join("；", errors))
                .withWarnings(warnings)
                .withEvidence("treeIssues", errors);
    }

    /**
     * 这次部署是「新建」还是「覆盖已有流程」，结论落在 {@code detail.deployMode}
     *
     * <p>🔴 这不是 {@code conflict}：撞 key 就覆盖是工具<b>设计好的行为</b>
     * （{@code BpmModelApiImpl#saveAndDeployModel} 的注释原话「不能无脑 createModel ——
     * 流程标识全局唯一，撞上已有的会直接被拒」），不是「撞上了已有的东西、出路是换名」。
     * 但它必须说出来，因为覆盖的后果是不可逆的：整棵节点树被替换、旧版部署被挂起。
     */
    private void describeDeployMode(ChangeItem item, String key, List<String> warnings) {
        BpmModelRespDTO existing;
        try {
            existing = findModelByKey(key);
        } catch (Exception ex) {
            log.warn("[describeDeployMode][流程模型查询没探成 key({})]", key, ex);
            // 又是一个「静默的 null」：查过了没撞上、与没查成，界面上长得一样而含义相反
            warnings.add("未能确认标识 " + key + " 是否已有流程（" + brief(ex)
                    + "）；若已有，这次部署会覆盖它而不是新建");
            return;
        }
        if (existing == null) {
            item.getDetail().put("deployMode", "新建一条流程（当前没有同标识的流程模型）");
            return;
        }
        String existingName = StrUtil.blankToDefault(existing.getName(), existing.getId());
        item.getDetail().put("deployMode", "覆盖已有流程「" + existingName + "」并发新版本");
        warnings.add("标识 " + key + " 已经有流程「" + existingName + "」—— 这次是覆盖它，不是新建："
                + "传过去的节点树会整体替换掉原有流程（后端拿这份 simpleModel 重新生成流程图，不与旧的合并），"
                + "部署后旧版本自动挂起、只有最新版能被发起。"
                + "覆盖还要求当前用户是这条流程的管理员，不是的话部署第一步就会被拒");
    }

    /**
     * 按 key <b>精确</b>找已有的流程模型
     *
     * <p>🔴 只比 key，<b>不比 name</b>。{@code ExplainProcessTool#execute} 那个
     * 「key 或 name 命中」的写法搬到这里是错的：后端
     * {@code BpmModelServiceImpl#getModelByKey} 用的是 {@code modelKey(key)} 精确匹配，
     * 多比一个 name 就会把「新建」说成「覆盖」—— 而那句话会让审批人以为
     * 有一条正在用的流程要被改掉，进而否掉一份本来没问题的 DSL。
     *
     * <p>⚠️ {@code BpmModelApi} 没有 {@code getModelByKey} 这个出口，只有
     * {@code getModelList(name)}（按名字模糊匹配）。这里传 {@code null} 取全量再自己筛，
     * 与 {@code ExplainProcessTool} 同一个做法。代价是每有一个部署节点就取一次全量模型表 ——
     * 一份 DSL 里的部署节点通常只有一个，且整趟干跑有 {@link #BUDGET_SECONDS} 兜着，
     * 为它加一层跨项缓存不值当。
     */
    private BpmModelRespDTO findModelByKey(String key) {
        if (StrUtil.isBlank(key)) {
            return null;
        }
        for (BpmModelRespDTO model : CollUtil.emptyIfNull(bpmModelApi.getModelList(null))) {
            if (key.equals(model.getKey())) {
                return model;
            }
        }
        return null;
    }

    /**
     * 分类是否启用中：照 {@code ProcessCreateAndDeployTool#resolveCategory} 的三条分支探
     *
     * <p>这一项不是「参数写错了」，是「部署成功但流程在发起列表里隐身」——
     * 平台<b>一声不响</b>地回落到启用中排序最前的分类。清单能做的就是把这次回落说出来，
     * 用的还是工具自己那句话（{@code categoryFallbackWarning}）。
     *
     * <p>🔴 一条分类都没配时<b>不给警告</b>：那时工具原样落库
     * （{@code StrUtil.blankToDefault(wanted, "1")}，发起列表本就无法分组展示），
     * 不存在「被悄悄改掉」这件事，警告会是假的。
     */
    private void checkCategory(Map<String, Object> args, List<String> warnings) {
        String wanted = ManifestArgs.str(args, ProcessCreateAndDeployTool.PARAM_CATEGORY);
        if (ManifestArgs.isPending(wanted)) {
            warnings.add("流程分类要到运行期才确定（" + wanted + "），没能确认它是否启用中");
            return;
        }
        List<String> enabled;
        try {
            enabled = bpmCategoryApi.getEnabledCategoryCodes();
        } catch (Exception ex) {
            log.warn("[checkCategory][流程分类探测没探成]", ex);
            warnings.add("未能确认流程分类是否启用中（" + brief(ex)
                    + "）；若传的分类没启用，部署照样成功，但流程会被挂到另一个分类下");
            return;
        }
        if (CollUtil.isEmpty(enabled) || (StrUtil.isNotBlank(wanted) && enabled.contains(wanted))) {
            return;
        }
        warnings.add(ProcessCreateAndDeployTool.categoryFallbackWarning(wanted, enabled.get(0)));
    }

    /**
     * 主表单能不能用于流程：调 bpm 闸口③调的<b>同一个方法</b>
     *
     * <p>真执行那条链是 {@code BpmModelServiceImpl#deployModel} →
     * {@code BpmModelServiceImpl#validateFormConfig} → {@code BpmOnlineFormValidator#validateMainForm} →
     * {@code FormDesignApi#validateFlowReady(code, true)}。最后那一步是 infra 的只读接口，
     * 于是这里不必重写「发布了吗、绑的是主库吗、完成表绑定了吗、子表控件都配了明细表吗、
     * 主表在吗、{@code process_instance_id} 与 {@code process_status} 两列齐不齐」这七条判断
     * （{@code FormDesignApiImpl#resolveUnavailableReason}），直接拿回后端要说的原话。
     *
     * <p>🔴 这一条同时兑现了抽取器留下的 {@code detail.requiresColumn}：
     * {@code ProcessDeployChangeExtractor} 只<b>声明</b>「在线表单主表必须有 process_instance_id 列」，
     * 声明与核实是两件事，核实归这里。少了这一步，一份主表缺列的 DSL 会被报成 PASSED，
     * 而它部署时必然被拒 —— 正是方案 §3-L2 要防的那个假信号。
     *
     * <p>⚠️ 拒绝文案的前缀（「在线表单 X 不可用于流程：」）是本类自己写的，冒号后面一字不改。
     * 前缀不得不自己写：bpm 那句 {@code ONLINE_FORM_NOT_READY} 长在
     * {@code bpm/enums/ErrorCodeConstants}，跨模块只准走 {@code api/} 层
     * （{@code AgentArchitectureTest#testOnlyDependOnApiLayer}），拿不到。
     * 后半句照抄 infra 的原话，是为了不出现第二份「究竟哪一列不合格」的口径。
     *
     * <p>🔴 编码是占位符、或这次探测自己失败了，一律落 {@code NOT_PROBED} 而不是「留条警告继续报绿」：
     * 主表单不可用是<b>部署期硬拒</b>，没探到它就宣布 PASSED，正是方案 §3-L2 要防的那个假信号。
     * 与建表单那一支（主表探测失败只留警告）不同，那边探不到的是「表在不在」，
     * 而这里探不到的是「这七项配置齐不齐」—— 后者是真拒绝路径上的全部判据。
     *
     * @return 该拒 / 没探成时返回结论；确认过、可用时返回 null
     */
    private DryRunResult checkMainForm(String formDesignCode, List<String> warnings) {
        if (ManifestArgs.isPending(formDesignCode)) {
            return DryRunResult.notProbed("在线表单编码要到运行期才确定（" + formDesignCode
                            + "），没能确认它能不能用于流程")
                    .withWarnings(warnings);
        }
        String reason;
        try {
            // 🔴 第二个参数必须是 true：主表单要同时有 process_instance_id 与 process_status 两列，
            //    传 false 就只查前一列，于是「主表缺 process_status」会被报成 PASSED
            reason = formDesignApi.validateFlowReady(formDesignCode, true);
        } catch (Exception ex) {
            log.warn("[checkMainForm][主表单可用性探测没探成 编码({})]", formDesignCode, ex);
            return DryRunResult.notProbed(probeBroken("主表单可用性探测", ex)).withWarnings(warnings);
        }
        if (reason == null) {
            return null;
        }
        return DryRunResult.rejected("在线表单 " + formDesignCode + " 不可用于流程：" + reason)
                .withWarnings(warnings);
    }

    // ==================== 公共 ====================


    /**
     * 校验通过后向后端要 DDL；三态的判定收在这一处，建表与改表两条链共用
     *
     * <p>⚠️ 本方法<b>会抛</b>，由两个调用方各自的 catch 决定落哪一态 ——
     * 因为「谁抛的」决定了措辞（建表 / 改表），而措辞要能让人看出是哪一步没探成。
     */
    private DryRunResult previewDdl(DataModelSaveReqDTO reqDTO, AgentValidateResult validation,
                                    List<String> extraWarnings) {
        if (!validation.isPassed()) {
            // 用「；」连成一行而不是照搬 toFeedback()：那份文本是写给模型的
            // （「以下问题必须修正：\n1. …」），清单上要多行列表由渲染层按 warnings 那套排
            return DryRunResult.rejected(String.join("；", validation.getErrors()))
                    .withWarnings(validation.getWarnings())
                    .withWarnings(extraWarnings);
        }
        List<String> ddl = dataModelApi.previewDdl(reqDTO);
        return DryRunResult.passed("预览通过，将执行 " + CollUtil.emptyIfNull(ddl).size()
                + " 条 DDL；这些语句尚未执行")
                .withWarnings(validation.getWarnings())
                .withWarnings(extraWarnings)
                .withEvidence("ddl", CollUtil.emptyIfNull(ddl));
    }

    /**
     * 表名撞车探测
     *
     * <p>🔴 自己接住异常，并把「没探出来」写进 {@code warnings}。
     * 静默返回 null 的话，{@code conflict=null} 会被读成「确认过、没撞车」，
     * 而实情是「没查成」—— 这两件事在界面上长得一模一样，正是本清单要消灭的那类含糊。
     */
    private ChangeItem.Conflict tableConflict(String tableName, List<String> warnings) {
        DataModelApi.PhysicalTableRespDTO table;
        try {
            table = findExactTable(tableName);
        } catch (Exception ex) {
            log.warn("[tableConflict][表存在性探测没探成 表名({})]", tableName, ex);
            warnings.add("未能确认表 " + tableName + " 是否已被占用（" + brief(ex)
                    + "）；若它已存在，建表会在执行时才失败");
            return null;
        }
        if (table == null) {
            return null;
        }
        ChangeItem.Conflict conflict = new ChangeItem.Conflict();
        if (Boolean.TRUE.equals(table.getRegistered())) {
            conflict.setType(ChangeItem.Conflict.TYPE_TABLE_ALREADY_EXISTS);
            conflict.setMessage("表 " + tableName + " 已经建好、并且已登记进数据模型，再建一次会被拒。"
                    + "请换一个表名，或者直接复用这张现成的表");
        } else {
            conflict.setType(ChangeItem.Conflict.TYPE_TABLE_EXISTS_UNREGISTERED);
            conflict.setMessage("库里已经有一张 " + tableName + "，但它没登记进数据模型"
                    + "（多半来自删模型时选了「仅删登记、保留真实表」）。"
                    + "正确的出路是用「导入已有表」把它收进来，而不是换个名字重建 —— "
                    + "换名的结果是库里多一张结构相同的表，而这张原表仍然躺着没人管");
        }
        return conflict;
    }

    /**
     * 按<b>全名精确</b>找一张物理表，找不到返回 null
     *
     * <p>🔴 「先模糊查、再按全名精确比一次」这个两步判据是全模块唯一一份，
     * 建表撞车探测与建表单的主表存在性探测都走这里：
     * {@code DataModelApi#getPhysicalTableList} 的入参是<b>关键字</b>而不是表名，
     * 而且它对<b>表名与表注释两个字段做 OR 匹配</b>（那是 LLM 用中文词搜表所必需的），
     * 所以返回的是一批模糊命中。少了第二步的话，探 {@code biz_emp} 会被 {@code biz_employee}
     * 顶成一个假结论 —— 建表那边是假撞车，建表单那边是假放行，两个方向都会让清单说错话。
     *
     * <p>⚠️ 本方法<b>会抛</b>：查询本身的故障由调用方决定怎么处置
     * （建表那一支留一条 warning 后放行，建表单那一支照工具的 fail-soft 口径办）。
     */
    private DataModelApi.PhysicalTableRespDTO findExactTable(String tableName) {
        for (DataModelApi.PhysicalTableRespDTO table
                : CollUtil.emptyIfNull(dataModelApi.getPhysicalTableList(tableName))) {
            if (table != null && tableName.equalsIgnoreCase(table.getTableName())) {
                return table;
            }
        }
        return null;
    }

    /**
     * 「没探成」的文案：带上异常类名与截断后的消息
     */
    private String probeBroken(String what, Exception ex) {
        return "未能完成" + what + "（" + brief(ex) + "），本次未校验它能不能跑通";
    }

    private String brief(Exception ex) {
        return ex.getClass().getSimpleName() + "：" + StrUtil.maxLength(
                StrUtil.nullToEmpty(ex.getMessage()), REASON_DETAIL_MAX_CHARS);
    }

}
