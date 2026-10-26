package cn.enlev.ainb.module.agent.framework.agent.core.tool;

import java.util.List;

/**
 * 工具返回形状的判据 —— 提示词与校验器共用的<b>唯一</b>一份
 *
 * <h3>为什么需要这么个类</h3>
 * workflow 的 script 节点跑在沙箱里，拿到的工具结果是<b>对象</b>而不是数组，
 * 而全平台没有统一的列表包裹键。提示词因此教模型抄一个 {@code pickRows(o, key)}：
 * 点名的键 → 唯一的数组 → 多个数组里唯一一个叫「惯例名」的 → 都不成立才 throw。
 *
 * <p>那条「惯例名」清单同时被两处需要：提示词里那段 JS 要用它决定能不能不点名，
 * 校验器 V26 要用它决定「这里不点名会不会在运行期抛」。<b>两处各写一份必然漂移</b>，
 * 而漂移的后果极其难查：提示词说能不点名、校验器说必须点名（或反过来），
 * 于是同一段脚本在规划期被判合法、到运行期才抛，用户看到的是一句和自己代码对不上的错。
 *
 * <h3>🔴 JS 那一份是本类的镜像，有测试钉住</h3>
 * 提示词里的 {@code pickRows} 是<b>给模型抄的 JS 文本</b>，没法直接调 Java，
 * 所以 {@link #MAIN_LIST_KEYS} 在那边有一份字面副本。
 * {@code WorkflowDslPlannerPromptTest} 会把 JS 里的 {@code MAIN = [...]} 抠出来与本常量逐字比对，
 * 且把整段 {@code pickRows} 丢进真沙箱跑实测形状 —— 改这里必须同步改那边，否则测试会响。
 *
 * <h3>形状的<b>唯一出处</b>是工具自己的声明</h3>
 * 谁回哪些数组键由 {@link AgentToolFunction#resultArrayKeys()} 声明，
 * 经 {@code AgentToolRegistry.ToolDefinition#getDeclaredResultArrayKeys()} 暴露。
 * 本类只回答「给定这组键，不点名行不行」，<b>不</b>持有任何工具名单 ——
 * 名单一旦抄进这里就又成了第二份，而实测已经证明抄的名单会漏
 * （{@code datamodel_column_types} 与 {@code explain_form_rule} 就是这么被漏掉、
 * 进而在运行期抛「猜不出该取哪个数组」的）。
 *
 * @author 走进新时代
 */
public final class ToolResultShapes {

    /**
     * 「主列表」惯例名：一个结果对象里有多个数组时，若<b>恰好一个</b>键落在这份清单里，就认为它是主列表
     *
     * <p>只有三个，因为全平台实测下来只有这三种约定：
     * {@code items}（form_list / process_list / datamodel_physical_tables）、
     * {@code list}（data_interface_list / form_data_page）、
     * {@code models}（datamodel_list）。
     *
     * <p>🔴 <b>不要</b>为了让某个工具「也能不点名」而往这里加它的键名。
     * 加进去等于宣称「这个键名在全平台就是主列表的意思」，而
     * {@code datamodel_structure} 的 {@code systemColumns} / {@code businessColumns} 恰恰是反例：
     * 三个数组里第一个是<b>系统列</b>，任何「猜一个」的规则都会在某些流程里静默拿系统列去判业务列。
     * 该做的是让那个工具的调用方点名，不是放宽判据。
     */
    public static final List<String> MAIN_LIST_KEYS = List.of("items", "list", "models");

    /**
     * 「一眼看着像列表包裹名」的键 —— 校验器 V26 用它判「猜错了包裹键名」
     *
     * <p>比 {@link #MAIN_LIST_KEYS} 多两个（{@code rows} / {@code records}）：那两个不是本平台的约定，
     * 全平台没有任何工具用它们当结果键，但它们是模型<b>最常猜</b>的两个通用名，
     * 所以「写了它」同样是确定的取不到值。
     *
     * <h3>🔴 判据「这名字不在声明里 ⟹ 结果对象上没这个键」依赖一条<b>实测</b>事实</h3>
     * {@link AgentToolFunction#resultArrayKeys()} 声明的只是<b>数组值</b>的键，
     * 结果对象上还有大量没被声明的标量键（{@code total} / {@code message} /
     * {@code frameworkTablesFiltered} 这些）。所以「不在声明里」原则上推不出「不是键」。
     *
     * <p>这五个名字之所以能这么推，是因为 2026-10-09 把 {@code tool/impl} 下全部内置工具翻了一遍：
     * 它们<b>只</b>被当成数组值的键用过，一共 6 处
     * （{@code data_interface_list}→list、{@code datamodel_list}→models、
     * {@code datamodel_physical_tables}→items、{@code form_list}→items、
     * {@code form_data_page}→list、{@code process_list}→items），且<b>全部</b>已在各自的
     * {@code resultArrayKeys()} 里声明。V26 落地前用这条判据扫过 29 份活定义的 38 个 script 节点，
     * 命中 7 处、逐个人工核对<b>全是真缺陷</b>（写 {@code .items}/{@code .list} 去摸回 {@code models} 的结果）。
     *
     * <p>⚠️ 这条事实<b>没有守卫测试</b>：将来哪个工具回一个标量 {@code list}
     * （比如 {@code {"list": "该表单没有列表页"}}），V26 就会对它误报，而误报会让规划期的
     * 自修正回路去改一个没病的地方 —— 每轮都是一次真实计费。
     * 所以<b>别用这五个名字当标量键</b>，要表达「没有列表」就换个名字（{@code message} / {@code reason}）。
     * 这条纪律只能靠 review 兜，故写在这里而不是藏在 V26 的方法注释里。
     *
     * <p>{@code data} 刻意<b>不在</b>本清单：它是 {@code form_data_get} 等工具<b>真实</b>的标量键，
     * 把它算进「猜错」就是一类稳定的假警报。
     */
    public static final List<String> LIST_WRAPPER_KEYS = List.of("items", "list", "rows", "records", "models");

    private ToolResultShapes() {
    }

    /**
     * 给定一个工具声明的数组键，判断 script 里<b>不点名</b>（{@code pickRows(o)}）能不能确定地取到主列表
     *
     * <p>与提示词里那段 JS 的取舍顺序逐条对应：
     * <ol>
     *   <li>0 个数组 ⟹ false。此时 {@code pickRows} 会 throw「猜不出该取哪个数组，候选：」（候选是空的）——
     *       对一个压根不回数组的结果用 {@code pickRows} 本身就是写错了。</li>
     *   <li>1 个数组 ⟹ true。唯一的数组就是它，没有歧义。</li>
     *   <li>多个数组 ⟹ 只有<b>恰好一个</b>落在 {@link #MAIN_LIST_KEYS} 里才 true。
     *       两个都沾惯例名（比如同时有 {@code items} 和 {@code list}）仍是 false：
     *       惯例名不是「随便挑一个」的许可证，挑错了照样静默判错。</li>
     * </ol>
     *
     * <p>⚠️ 入参必须是<b>全部返回分支的并集</b>（见 {@link AgentToolFunction#resultArrayKeys()}）。
     * 只按常见那条分支判，漏掉的分支就会在运行期抛 —— 而 {@code datamodel_preview_ddl} 正是这样：
     * CREATE 分支一个数组都不回，ALTER 分支回四个。
     *
     * @param arrayKeys 工具声明的数组键，可为 null（视同空）
     * @return 不点名也能确定取到主列表则为 true
     */
    public static boolean resolvableUnkeyed(List<String> arrayKeys) {
        return autoResolvedKey(arrayKeys) != null;
    }

    /**
     * 不点名时 {@code pickRows} 会取到的那个键；取不到确定答案则返回 null
     *
     * <p>取舍顺序与提示词里那段 JS <b>逐条同形</b>：唯一的数组 → 多个里唯一一个惯例名。
     * {@link #resolvableUnkeyed} 就是本方法的非空判定，所以判据在 Java 侧只有一份实现。
     *
     * <p>🔴 提示词渲染「不点名也对」那一栏时要印出本方法的返回值，而不是笼统写一句「不点名即可」：
     * 模型看得见落到哪个键，才会在<b>那个键不是它想要的</b>时改用点名 ——
     * {@code datamodel_physical_tables} 的 {@code items} 与 {@code danglingRegistrations} 就是这种情形。
     */
    public static String autoResolvedKey(List<String> arrayKeys) {
        if (arrayKeys == null || arrayKeys.isEmpty()) {
            return null;
        }
        if (arrayKeys.size() == 1) {
            return arrayKeys.get(0);
        }
        List<String> hits = arrayKeys.stream().filter(MAIN_LIST_KEYS::contains).toList();
        return hits.size() == 1 ? hits.get(0) : null;
    }

    /**
     * 这组声明里有没有数组键 —— 用来区分「结果是个对象，取列表要点名」与「结果里没有列表可取」
     *
     * <p>V26 判「script 直接对 {@code state.X} 调 {@code .find/.some/.map}」时要用它：
     * 只有当产出 {@code X} 的工具<b>确实</b>回对象（有数组键）时，那种写法才是确定的 TypeError。
     * 声明为空的工具（如 {@code explain_process}）结果形状各不相同，不能一概判错。
     */
    public static boolean declaresArrays(List<String> arrayKeys) {
        return arrayKeys != null && !arrayKeys.isEmpty();
    }

    /**
     * 这组声明<b>必须点名</b>：确实回数组，但不点名取不到确定答案
     *
     * <p>提示词渲染「必须点名」那一栏、校验器 V26 判「这里少第二个参数 ⟹ 运行期必抛」，
     * 用的是同一个判据。两边各写一份的后果见类注释。
     */
    public static boolean mustName(List<String> arrayKeys) {
        return declaresArrays(arrayKeys) && !resolvableUnkeyed(arrayKeys);
    }

}
