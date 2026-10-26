package cn.enlev.ainb.module.agent.service.workflow;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentNameCatalogRespVO;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentNameCatalogRespVO.OperatorItem;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentNameCatalogRespVO.ToolItem;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentNameCatalogRespVO.WorkflowItem;
import cn.enlev.ainb.module.agent.dal.dataobject.tooldef.AgentToolDefDO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolRegistry;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.agent.service.tooldef.AgentToolDefService;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 名称目录 Service 实现
 *
 * <h3>🔴 工具合流：registry-first，与执行路径逐字同口径</h3>
 * {@code AgentToolExecutor#execute} 与 {@code AgentSecurityGateway} 解析工具名时都是
 * 「先查 {@link AgentToolRegistry}，查不到才回落 {@code agent_tool_def}」。本类的合流顺序必须与之一致 ——
 * 否则设计器下拉里显示的是自定义工具的展示名，真正被调用的却是同名的内置工具，
 * 这类分叉从界面上完全看不出来。登记时已拒重名（{@code AGENT_TOOL_DEF_KEY_CONFLICT_CODE_TOOL}），
 * 所以走到冲突分支只有一种可能：内置工具是<b>后加的</b>、把已存在的自定义工具名占了。
 * 这种情况静默丢掉自定义那行会让管理员困惑「我登记的工具去哪了」，故打一条 warn 留痕。
 *
 * @author 走进新时代
 */
@Service
@Validated
@Slf4j
public class AgentNameCatalogServiceImpl implements AgentNameCatalogService {

    @Resource
    private AgentToolRegistry toolRegistry;
    @Resource
    private AgentToolDefService toolDefService;
    @Resource
    private AgentWorkflowDefService defService;
    @Resource
    private FormDesignApi formDesignApi;

    @Override
    public AgentNameCatalogRespVO getNameCatalog() {
        AgentNameCatalogRespVO catalog = new AgentNameCatalogRespVO();
        catalog.setTools(buildTools());
        catalog.setWidgetLabels(buildWidgetLabels());
        catalog.setOperators(buildOperators());
        catalog.setWorkflows(buildWorkflows());
        return catalog;
    }

    /**
     * 内置工具（生效值）∪ 自定义工具（<b>全部</b>行，含停用），按「分组名（拼音）→ 工具键」排序
     *
     * <p>排序是必要的而非装饰：内置工具的收集顺序来自 Spring 的 bean 扫描顺序，
     * 换个 classpath 就可能变，下拉里的工具会随之乱跳。排完序，同一份数据的输出就是确定的。
     *
     * <p>🔴 分组名用 {@link Collator}（拼音序）而不是 {@code String.compareTo}（UTF-16 码点序）：
     * 码点序下中文分组的先后对人来说等于随机。组内第二级用<b>工具键</b>而非展示名 ——
     * 键是 ASCII、口径唯一，不必再赌一次 Collator 对中英混排的行为，且运维按键找工具本来就更快。
     * Collator 非线程安全，故每次调用现取一个（本端点只在页面加载时调一次）。
     *
     * <p>🔴 自定义工具那一侧刻意取<b>全量</b>而不是 {@code getEnabledToolDefList()}（D4-27）：
     * 目录的职责是「机器键 → 人能读的名字」，名字与启用状态无关。此前用启用列表，
     * 停用的自定义工具就从目录里整个消失，存量 DSL 里那个键翻不出名字、
     * 前端 {@code el-select} 回落到 {@code currentLabel = value}（画布上退化成裸机器键），
     * 而 {@code allow-create} 还允许把它重新敲回去且一声不吭。内置工具本来就全量下发、
     * 靠 {@code enabled} 让前端置灰，两类工具对同一个「停用」动作处置不一致 —— 现在统一成「都下发、都置灰」。
     * 取舍与「清单/校验/planner 仍只用启用行」的理由都写在
     * {@code AgentToolDefService#getToolDefListIncludingDisabled} 的注释里。
     */
    private List<ToolItem> buildTools() {
        Map<String, ToolItem> byName = new LinkedHashMap<>();
        for (AgentToolRegistry.ToolDefinition def : toolRegistry.list()) {
            byName.put(def.getName(), toBuiltinItem(def));
        }
        for (AgentToolDefDO row : toolDefService.getToolDefListIncludingDisabled()) {
            String key = row.getToolKey();
            if (StrUtil.isBlank(key)) {
                continue;
            }
            if (byName.containsKey(key)) {
                log.warn("[buildTools][自定义工具({}) 与内置工具重名，按 registry-first 采用内置那一行]", key);
                continue;
            }
            byName.put(key, toCustomItem(row));
        }
        List<ToolItem> tools = new ArrayList<>(byName.values());
        Collator collator = Collator.getInstance(Locale.CHINA);
        tools.sort(Comparator.comparing(ToolItem::getGroupName, collator)
                .thenComparing(ToolItem::getName));
        return tools;
    }

    /**
     * 内置工具 → 目录项
     *
     * <p>展示类字段读<b>生效值</b> getter（{@code getDisplayName/getGroupName/isEnabled}），
     * 它们由注册表按当前租户的覆盖行现算 —— 故管理员在线维护改完，下一次打开设计器就是新名字，
     * 这也是本类不需要缓存失效钩子的原因。
     *
     * <p>🔴 风险<b>必须下发两份</b>，理由写在 {@link AgentNameCatalogRespVO.ToolItem} 的类注释里，
     * 一句话：运行期各道闸读 {@code getDeclaredRisk()}，而降险改的是 {@code getRisk()}。
     * 本方法此前只下发生效值，直接后果是设计器把降过险的工具画成「无需确认」、
     * {@code agent_def} 的工具下拉把它放进「只读可用」那一档，用户照着编排、点发布才被 V3 撞回来 ——
     * 这是「界面主动指反方向」，比不显示更坏。
     * {@code declaredRequireConfirm} 也<b>不能</b>图省事直接调 {@code def.isRequireConfirm()}：
     * 那个 getter 内部走的是 {@code getRisk()}（生效值），必须由 {@code getDeclaredRisk()} 自己推。
     */
    private ToolItem toBuiltinItem(AgentToolRegistry.ToolDefinition def) {
        ToolItem item = new ToolItem();
        item.setName(def.getName());
        item.setDisplayName(def.getDisplayName());
        item.setDescription(def.getDescription());
        item.setGroupName(def.getGroupName());
        RiskLevel effective = def.getRisk();
        RiskLevel declared = def.getDeclaredRisk();
        item.setRisk(levelOf(effective));
        item.setRiskDescription(descriptionOf(effective));
        item.setRequireConfirm(effective == null ? null : effective.isRequireConfirm());
        item.setDeclaredRisk(levelOf(declared));
        item.setDeclaredRiskDescription(descriptionOf(declared));
        item.setDeclaredRequireConfirm(declared == null ? null : declared.isRequireConfirm());
        item.setRiskOverridden(declared != effective);
        item.setEnabled(def.isEnabled());
        item.setOverridden(def.isOverridden());
        item.setSource(AgentNameCatalogRespVO.SOURCE_BUILTIN);
        return item;
    }

    /**
     * 自定义工具 → 目录项
     *
     * <p>{@code requireConfirm} 由风险等级推导（{@link RiskLevel#isRequireConfirm()}），与内置工具同源 ——
     * 前端据此在写操作工具后挂风险色点，口径不能两边各算一套。
     * {@code overridden} 恒为 false：自定义工具<b>本身就是</b>数据，没有「代码声明值」可被覆盖，
     * 这个标记只对内置工具有意义。
     *
     * <p>🔴 同理，{@code declared*} 三件套在这里<b>恒等于</b>生效值、{@code riskOverridden} 恒为 false：
     * {@code agent_tool_def} 那一行就是它的声明，没有第二个来源可以分叉。之所以仍要填满而不是留 null，
     * 是因为前端「预测闸门行为」的地方一律读 {@code declared*} —— 留 null 就等于自定义工具在设计器里
     * 一个色点都不画、也永远进不了「只读可用」那一档，那是把内置工具的病换成自定义工具的病。
     */
    private ToolItem toCustomItem(AgentToolDefDO row) {
        ToolItem item = new ToolItem();
        item.setName(row.getToolKey());
        // 展示名回落工具键：登记时 displayName 非必填，空着也不能让下拉里出现一行空白
        item.setDisplayName(StrUtil.blankToDefault(row.getDisplayName(), row.getToolKey()));
        item.setDescription(row.getDescription());
        item.setGroupName(StrUtil.nullToEmpty(row.getGroupName()));
        RiskLevel risk = RiskLevel.parseOrHigh(row.getRiskLevel());
        item.setRisk(risk.getLevel());
        item.setRiskDescription(risk.getDescription());
        item.setRequireConfirm(risk.isRequireConfirm());
        item.setDeclaredRisk(risk.getLevel());
        item.setDeclaredRiskDescription(risk.getDescription());
        item.setDeclaredRequireConfirm(risk.isRequireConfirm());
        item.setRiskOverridden(false);
        // 🔴 读**真实值**而不是写死 true。D4-26 加这句时停用行还到不了这里（数据源是
        // {@code getEnabledToolDefList()}，WHERE enabled = true），钉的是**契约**；
        // D4-27 把数据源换成全量列表之后它变成了**唯一**正确的写法 —— 写死常量会让前端刚加的
        // 置灰逻辑（{@code nameCatalog.disabledToolBlock}）读到 true、把停用的工具画成正常候选，
        // 界面主动指反方向，比不显示更坏。
        // 判据与 {@code AgentToolDefServiceImpl#resolveForGateway} 一字不差（null 视为停用，fail-close）：
        // 目录必须和网关给出同一个答案，否则前端置灰了、网关却放行（或反之），两边各说一套
        // 正是本模块的头号缺陷。
        item.setEnabled(Boolean.TRUE.equals(row.getEnabled()));
        item.setOverridden(false);
        item.setSource(row.getSource());
        return item;
    }

    private String levelOf(RiskLevel risk) {
        return risk == null ? null : risk.getLevel();
    }

    private String descriptionOf(RiskLevel risk) {
        return risk == null ? null : risk.getDescription();
    }

    /** 控件 type → 中文名。不过滤 supported/deprecated：历史表单仍可能引用旧控件，翻得出名字总比显示 type 好 */
    private Map<String, String> buildWidgetLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        for (FormDesignApi.WidgetSimpleRespDTO widget : formDesignApi.getWidgetContracts()) {
            if (widget == null || StrUtil.isBlank(widget.getType())) {
                continue;
            }
            labels.putIfAbsent(widget.getType(), StrUtil.blankToDefault(widget.getLabel(), widget.getType()));
        }
        return labels;
    }

    private List<OperatorItem> buildOperators() {
        List<OperatorItem> operators = new ArrayList<>();
        for (FormDesignApi.SearchOperatorRespDTO op : formDesignApi.getSearchOperatorList()) {
            OperatorItem item = new OperatorItem();
            item.setOperator(op.getOperator());
            item.setLabel(op.getLabel());
            operators.add(item);
        }
        return operators;
    }

    /**
     * 已发布流程 code → 中文名
     *
     * <p>Mapper 已按 {@code primary_flag DESC, version DESC} 排序，故同一 code 的<b>首行</b>
     * 就是当前生效那一版；{@code putIfAbsent} 即「取首行、忽略其余版本」。
     * 名字空着时回落 code —— 下拉里出现空白项比出现英文串更难排查。
     */
    private List<WorkflowItem> buildWorkflows() {
        Map<String, WorkflowItem> byCode = new LinkedHashMap<>();
        for (AgentWorkflowDefDO def : defService.listPublishedSimple()) {
            if (StrUtil.isBlank(def.getCode())) {
                continue;
            }
            WorkflowItem item = new WorkflowItem();
            item.setCode(def.getCode());
            item.setName(StrUtil.blankToDefault(def.getName(), def.getCode()));
            byCode.putIfAbsent(def.getCode(), item);
        }
        return new ArrayList<>(byCode.values());
    }

}
