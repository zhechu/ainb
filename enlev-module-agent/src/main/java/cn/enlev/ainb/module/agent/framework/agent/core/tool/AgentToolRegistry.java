package cn.enlev.ainb.module.agent.framework.agent.core.tool;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.tenant.core.context.TenantContextHolder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 工具注册表
 *
 * 按 {@link AgentTool} 注解收集全部工具，供权限网关与 LLM prompt 使用。
 *
 * <h3>🔴 声明值 vs 生效值（2026-09-28 产品决策：内置工具放出来在线维护）</h3>
 * 旧设计把「权限串/风险等级属于实现的一部分、不可运行时改写」当底线。产品决策推翻该底线：
 * <b>内置工具全字段自由维护</b>。故每个工具拆两层：
 * <ul>
 *   <li><b>声明值</b>（{@code declared*}，final）：代码即事实，来自 {@link AgentTool} 注解，永不改变；</li>
 *   <li><b>生效值</b>：默认等于声明值，可被 {@code agent_tool_override} 覆盖表改写。getter 一律返回生效值，
 *       故在线维护改完立即生效、无需重启；声明值经 {@code getDeclared*} 暴露，供管理页回显默认与放松告警。</li>
 * </ul>
 *
 * <h3>🔴 生效值按【当前租户】解析，绝不写死在单例上</h3>
 * 本类是<b>单例</b>，而 {@code agent_tool_override} 是<b>租户级</b>数据（{@code tenant.enable=true}）。
 * 若把某租户的覆盖直接改进单例字段，会污染所有租户（租户 A 停用某工具，租户 B 也被停用）——这正是本模块
 * 处处强调的跨租户隔离红线所禁止的。故生效值<b>不落在 ToolDefinition 上</b>，而是每次 getter 调用时
 * 按 {@link TenantContextHolder#getTenantId()} 从 {@link #overridesByTenant} 缓存现取现算：
 * <ul>
 *   <li>缓存<b>按租户懒加载</b>：某租户首次被读到且缓存未命中时，经 {@link #setOverrideLoader 注册的加载器}
 *       （由 {@code AgentToolOverrideService} 提供）在<b>当前请求的租户上下文</b>里查一次库填充——
 *       用的是普通租户隔离查询，<b>绝不</b> {@code TenantUtils.executeIgnore}（那会关掉整个租户隔离，本模块架构测试明令禁止）；</li>
 *   <li>{@code AgentToolOverrideService} 在每次 update/reset 时重建<b>当前租户</b>的快照刷进缓存，故维护改完立即生效；</li>
 *   <li>每个租户的内层 map 是<b>不可变快照</b>，刷新时整体替换，读端无锁一致；</li>
 *   <li>无租户上下文（启动、异步、单测）时 {@code getTenantId()} 为 null → 不查库、不套用任何覆盖 → 返回声明值。</li>
 * </ul>
 * 这样调用方（网关 / 提示词 / 需确认判定）<b>一行都不用改</b>：它们仍调 {@code registry.get(name).isEnabled()}，
 * 只是读到的值天然带上了当前租户的维护结果。
 *
 * <h3>🔴 执行体与身份仍属于代码</h3>
 * 覆盖只改「运维旋钮」，不产生新工具：内置工具不可经数据新建/删除，{@link #get(String)} 查不到就是没有。
 * 放松（降风险/删权限）不在此拦截，由 Service 层 log.warn 留痕。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class AgentToolRegistry {

    /**
     * 工具名 → 定义（只含声明值，线程安全、跨租户共享）
     */
    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();

    /**
     * 租户ID → (工具名 → 覆盖值) 的生效值缓存
     *
     * <p>🔴 内层 map 是不可变快照，刷新时整体 {@code put} 替换，读端不加锁也一致。
     * key 缺失（该租户尚未加载 / 没有任何覆盖）→ 触发懒加载或走声明值。
     */
    private final Map<Long, Map<String, ToolOverride>> overridesByTenant = new ConcurrentHashMap<>();

    /**
     * 某租户覆盖的懒加载器：入参租户ID，返回该租户的 (工具名 → 覆盖值) 全量快照。
     *
     * <p>🔴 由 {@code AgentToolOverrideService} 在 {@code @PostConstruct} 注册（{@link #setOverrideLoader}），
     * 实现里用<b>普通租户隔离查询</b>读当前租户的覆盖行——绝不用 {@code TenantUtils.executeIgnore}
     * （本模块架构测试 {@code AgentArchitectureTest#testNoTenantExecuteIgnore} 明令禁止）。
     * 为 null（如单测直接 {@code new AgentToolRegistry()}）时不懒加载，缓存未命中即走声明值。
     */
    private volatile Function<Long, Map<String, ToolOverride>> overrideLoader;

    /**
     * 注册覆盖懒加载器（由 Service 在启动时调用一次）
     */
    public void setOverrideLoader(Function<Long, Map<String, ToolOverride>> loader) {
        this.overrideLoader = loader;
    }

    @Value("${enlev.mode:public}")
    private String enlevMode;

    /**
     * 启动时自动收集所有带 {@link AgentTool} 注解的 bean
     *
     * 用 Spring 扫描而不是手工登记：新增工具只要加注解 + {@code @Component}，
     * 漏登记这种低级失误直接不存在。
     *
     * <p>注意取的是 {@code AopUtils.getTargetClass} —— 工具可能被 AOP 代理
     * （事务、日志），代理类上读不到注解。
     */
    @Autowired(required = false)
    public void collect(List<AgentToolFunction> functions) {
        if (!"professional".equals(enlevMode)) {
            log.info("[collect][enlev.mode={} 非 professional，跳过 AI 工具收集]", enlevMode);
            return;
        }
        for (AgentToolFunction function : CollUtil.emptyIfNull(functions)) {
            register(AopUtils.getTargetClass(function), function);
        }
    }

    /**
     * 一个内置工具在【某租户】下的在线维护覆盖值（来自 {@code agent_tool_override} 行）
     *
     * <p>🔴 空值语义逐字段不同，别混：
     * <ul>
     *   <li>{@code displayName}：null/空白=不覆盖（展示名回落工具名）；非空=覆盖；</li>
     *   <li>{@code description}/{@code paramsSchema}：null=不覆盖；非 null（含空串）=覆盖；</li>
     *   <li>{@code groupName}：null=不覆盖；非 null（含空串）=覆盖；</li>
     *   <li>{@code risk}：null=不覆盖；非 null=覆盖（<b>允许低于声明值</b>，产品决策）；</li>
     *   <li>{@code permissions}：null=不覆盖；非 null（<b>含空列表=调用只需登录</b>）=覆盖；</li>
     *   <li>{@code enabled}：null=不覆盖；非 null=覆盖（可停用，也可重新启用）。</li>
     * </ul>
     */
    public record ToolOverride(String displayName, String description, String groupName,
                               RiskLevel risk, List<String> permissions, Boolean enabled,
                               String paramsSchema) {
    }

    /**
     * 一个内置工具的定义：只承载<b>声明值</b>（不可变），生效值 getter 按当前租户现算。
     */
    public class ToolDefinition {

        // ---------- 声明值：代码即事实，不可变，跨租户共享 ----------
        @Getter
        private final String name;
        @Getter
        private final String declaredDisplayName;
        @Getter
        private final String declaredDescription;
        @Getter
        private final String declaredGroupName;
        @Getter
        private final List<String> declaredPermissions;
        @Getter
        private final RiskLevel declaredRisk;
        @Getter
        private final boolean declaredEnabled;
        @Getter
        private final String declaredParamsSchema;
        /**
         * 返回值里数组值的键（全部返回分支的并集），见 {@link AgentToolFunction#resultArrayKeys()}。
         *
         * <p>🔴 <b>不参与覆盖</b>：它是代码里的事实（{@code execute} 到底往结果对象塞了哪些数组），
         * 不是一档可由管理员调的策略。让它可被 {@code agent_tool_override} 改写，
         * 就等于允许一行数据把校验器 V26 判「这里不点名会不会运行期抛」的依据换掉 ——
         * 与「风险级在校验路径上只读声明值」是同一条纪律。
         */
        @Getter
        private final List<String> declaredResultArrayKeys;
        /**
         * 返回形状<b>能不能</b>静态声明，见 {@link AgentToolFunction#resultShapeKnown()}。
         *
         * <p>🔴 与 {@link #declaredResultArrayKeys} 是一对，缺一不可：只有键名而没有这一格，
         * 「不可知」与「确实没有数组」就会被渲染成同一句话，而那句话是<b>确定的指引</b>
         * （「压根不回数组，别对它用 pickRows」）—— 对形状不可知的工具说这句就是假话。
         * 同样<b>不参与覆盖</b>，理由同上。
         */
        @Getter
        private final boolean declaredResultShapeKnown;
        /**
         * 结果对象<b>顶层</b>的键（全部返回分支的并集），见 {@link AgentToolFunction#resultKeys()}。
         *
         * <p>用途与 {@link #declaredResultArrayKeys} 不同：那个给 script 节点的 pickRows 用，
         * 这个给<b>下游节点的 {@code {{$.变量.子键}}} 占位符</b>用 —— 校验器 V35 拿它判
         * 「这个子键压根不存在」，规划提示词拿它把正确的取值路径印给模型看。
         *
         * <p>🔴 <b>空列表 = 未登记，不是「不回对象」</b>（与 {@code declaredResultArrayKeys} 刻意不同，
         * 理由见 {@link AgentToolFunction#resultKeys()}）。所以消费方必须把未登记者单独归一栏、
         * 且 V35 对未登记者一律沉默 —— 误报会把能跑的流程拦在体检外，比漏报坏得多。
         *
         * <p>同样<b>不参与覆盖</b>：它是 {@code execute} 里的代码事实，不是策略旋钮。
         */
        @Getter
        private final List<String> declaredResultKeys;
        /**
         * 结果回灌时是否可被截断。白名单类工具为 false，理由见 {@link AgentTool#truncateResult()}。
         * 🔴 不参与覆盖：截断与否是结果体积问题，不是安全旋钮。
         */
        @Getter
        private final boolean truncateResult;
        /** 实现类，用于排查「这个工具到底是谁」 */
        @Getter
        private final Class<?> type;
        /** 实际执行体，手工注册的定义可能为 null（测试用） */
        @Getter
        private final AgentToolFunction function;

        ToolDefinition(AgentTool annotation, Class<?> type, AgentToolFunction function) {
            this.name = annotation.name();
            this.declaredDisplayName = annotation.displayName();
            this.declaredDescription = annotation.description();
            this.declaredGroupName = annotation.groupName();
            List<String> all = new ArrayList<>();
            all.add(annotation.permission());
            for (String extra : annotation.extraPermissions()) {
                if (!all.contains(extra)) {
                    all.add(extra);
                }
            }
            this.declaredPermissions = List.copyOf(all);
            this.declaredRisk = annotation.risk();
            this.declaredEnabled = annotation.enabled();
            this.declaredParamsSchema = function == null ? "" : function.paramsSchema();
            this.declaredResultArrayKeys = function == null ? List.of() : function.resultArrayKeys();
            // function 为 null 的手工注册定义（测试用）按「形状不可知」处理：
            // 拿不到实现就没有任何依据断言它不回数组，而断错的后果见 declaredResultShapeKnown 的注释
            this.declaredResultShapeKnown = function != null && function.resultShapeKnown();
            this.declaredResultKeys = function == null ? List.of() : function.resultKeys();
            this.truncateResult = annotation.truncateResult();
            this.type = type;
            this.function = function;
        }

        /** 当前租户对本工具的覆盖值；无租户上下文或该租户未维护本工具时返回 null */
        private ToolOverride override() {
            return currentTenantOverride(name);
        }

        // ---------- 生效值 getter（网关/确认风险/提示词读这些；按当前租户解析） ----------

        /**
         * 生效展示名：维护值 → 代码声明的中文展示名 → 工具名。
         *
         * <p>🔴 三级回落里中间那级是 2026-09-29 才加的。此前未维护时直接回落 {@code name}，
         * 于是管理页整屏都是 {@code datamodel_preview_ddl} 这种下划线英文——对模型精确，对人等于密码。
         */
        public String getDisplayName() {
            ToolOverride o = override();
            if (o != null && StrUtil.isNotBlank(o.displayName())) {
                return o.displayName();
            }
            return StrUtil.isNotBlank(declaredDisplayName) ? declaredDisplayName : name;
        }

        public String getDescription() {
            ToolOverride o = override();
            return o != null && o.description() != null ? o.description() : declaredDescription;
        }

        /** 生效分组（管理页归类用）：维护值 → 代码声明分组 → 空串（不分组） */
        public String getGroupName() {
            ToolOverride o = override();
            if (o != null && o.groupName() != null) {
                return o.groupName();
            }
            return StrUtil.nullToEmpty(declaredGroupName);
        }

        /**
         * 生效的<b>全部</b>权限串（可被覆盖改写），网关按「全都得有」判定。
         * 未维护=声明权限；维护成空列表=调用只需登录。
         */
        public List<String> getPermissions() {
            ToolOverride o = override();
            return o != null && o.permissions() != null ? o.permissions() : declaredPermissions;
        }

        public RiskLevel getRisk() {
            ToolOverride o = override();
            return o != null && o.risk() != null ? o.risk() : declaredRisk;
        }

        public boolean isEnabled() {
            ToolOverride o = override();
            return o != null && o.enabled() != null ? o.enabled() : declaredEnabled;
        }

        public String getParamsSchema() {
            ToolOverride o = override();
            return o != null && o.paramsSchema() != null ? o.paramsSchema() : declaredParamsSchema;
        }

        /** 是否必须进 Plan 等用户确认（跟随当前租户的生效风险等级） */
        public boolean isRequireConfirm() {
            return getRisk().isRequireConfirm();
        }

        /**
         * 当前租户是否正在线维护本工具（存在覆盖行即为 true）
         *
         * <p>🔴 是「存不存在覆盖行」，<b>不是</b>「生效值与声明值是否不同」。点一次「保存并生效」
         * 就落一行，哪怕填的值与代码声明一模一样 —— 这是刻意的：维护动作本身值得在列表上标出来，
         * 且逐字段比对的代价是「管理员改了又改回去，标签闪一下」这种更难解释的行为。
         * 要真正退回代码声明，用「恢复默认」删掉这行。
         */
        public boolean isOverridden() {
            return override() != null;
        }
    }

    /**
     * 注册一个工具（仅声明，无执行体）
     */
    public void register(Class<?> type) {
        register(type, null);
    }

    /**
     * 注册一个工具
     *
     * 重名直接抛错而不是后者覆盖前者：两个工具同名时，
     * 「哪个生效」若取决于扫描顺序，就会变成很难查的诡异行为 —— 尤其是它们的权限串可能不同。
     */
    public void register(Class<?> type, AgentToolFunction function) {
        AgentTool annotation = type.getAnnotation(AgentTool.class);
        if (annotation == null) {
            throw new IllegalArgumentException("工具类 " + type.getName() + " 缺少 @AgentTool 注解");
        }
        ToolDefinition existing = tools.get(annotation.name());
        if (existing != null) {
            throw new IllegalStateException(String.format(
                    "工具名重复：%s 同时被 %s 与 %s 声明。工具名是 LLM 选工具的唯一依据，不能重复",
                    annotation.name(), existing.getType().getName(), type.getName()));
        }
        ToolDefinition definition = new ToolDefinition(annotation, type, function);
        tools.put(annotation.name(), definition);
        log.info("[register][注册 AI 工具({}) 权限({}) 风险({})]",
                annotation.name(), definition.getDeclaredPermissions(), annotation.risk());
    }

    /**
     * 按名字取工具定义，不存在返回 null
     */
    public ToolDefinition get(String name) {
        return name == null ? null : tools.get(name);
    }

    /**
     * 全部工具定义
     */
    public Collection<ToolDefinition> list() {
        return tools.values();
    }

    /**
     * 刷新某租户的全部覆盖快照（在线维护保存/恢复默认后调用），立即对该租户后续请求生效。
     *
     * <p>🔴 整体替换而非逐条改：调用方（{@code AgentToolOverrideService}）每次从库里重建该租户的完整
     * 覆盖 map 传进来，避免「删了一行但缓存没同步」这类部分态。传空 map = 该租户全部工具回到声明值
     * （仍缓存这个空快照，免得 getter 再触发一次懒加载查库）。
     */
    public void refreshTenantOverrides(Long tenantId, Map<String, ToolOverride> overrides) {
        if (tenantId == null) {
            return;
        }
        overridesByTenant.put(tenantId, sanitize(overrides, tenantId));
    }

    /**
     * 取当前租户对某工具的覆盖值；无租户上下文返回 null（→ 生效值回落声明值）。
     *
     * <p>缓存未命中且已注册懒加载器时，在当前请求的租户上下文里查一次库填充（租户隔离查询，非 executeIgnore）。
     */
    private ToolOverride currentTenantOverride(String name) {
        Long tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            return null;
        }
        Map<String, ToolOverride> tenantOverrides = overridesByTenant.get(tenantId);
        if (tenantOverrides == null) {
            tenantOverrides = loadTenantOverrides(tenantId);
        }
        return tenantOverrides == null ? null : tenantOverrides.get(name);
    }

    /** 懒加载某租户的覆盖快照并缓存；无加载器时缓存空快照（避免每次 getter 都尝试加载） */
    private Map<String, ToolOverride> loadTenantOverrides(Long tenantId) {
        Function<Long, Map<String, ToolOverride>> loader = this.overrideLoader;
        if (loader == null) {
            Map<String, ToolOverride> empty = Map.of();
            overridesByTenant.put(tenantId, empty);
            return empty;
        }
        // computeIfAbsent 保证并发下每租户只查一次库；加载器抛错则不缓存、下次重试
        return overridesByTenant.computeIfAbsent(tenantId, id -> sanitize(loader.apply(id), id));
    }

    /** 过滤掉指向未注册工具的覆盖行（代码删了工具但库里留了残行），返回不可变快照 */
    private Map<String, ToolOverride> sanitize(Map<String, ToolOverride> overrides, Long tenantId) {
        if (overrides == null || overrides.isEmpty()) {
            return Map.of();
        }
        Map<String, ToolOverride> snapshot = new LinkedHashMap<>();
        overrides.forEach((toolName, o) -> {
            if (o == null) {
                return;
            }
            if (!tools.containsKey(toolName)) {
                log.warn("[sanitize][租户({}) 覆盖行指向未注册工具({})，已忽略]", tenantId, toolName);
                return;
            }
            snapshot.put(toolName, o);
        });
        return Map.copyOf(snapshot);
    }

}
