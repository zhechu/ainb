package cn.enlev.ainb.module.agent.framework.agent.core.tool;

import java.util.List;
import java.util.Map;

/**
 * 工具的执行契约
 *
 * <p>入参是 LLM 产出的结构化参数（一个 JSON 对象反序列化来的 Map），
 * 返回值会被序列化后回灌给 LLM。
 *
 * <p>🔴 <b>实现里不要做权限校验</b> —— 那是 {@code AgentToolExecutor} 的职责，
 * 在这里各写一遍必然有人漏写，而漏写的后果是无声的越权。
 * 工具只管做事，进得来就说明已经过了闸门。
 *
 * @author 走进新时代
 */
public interface AgentToolFunction {

    /**
     * 执行
     *
     * @param params LLM 提供的参数，可能为空 Map
     * @return 执行结果，会被序列化回灌给 LLM；只读工具返回查询结果，写工具返回操作摘要
     */
    Object execute(Map<String, Object> params);

    /**
     * 参数说明，写进 prompt 供 LLM 拼参数
     *
     * <h3>为什么放在这里而不是注解上</h3>
     * 它和 {@link #execute} 里解析参数的代码是<b>同一件事的两面</b>：
     * 改了解析逻辑就得改说明。放在同一个类里，改的时候至少看得见；
     * 放到注解上，两者隔着一段距离，漂移了也没人发现 ——
     * 而漂移的表现是模型按旧说明拼参数，工具报「缺少 xxx」，
     * 它照着错误再试一次，又是一轮计费。
     *
     * <p>格式不限，但要说清<b>每个参数叫什么、什么类型、必填还是可选</b>。
     * 返回空串表示无参数。
     *
     * @return 参数说明
     */
    default String paramsSchema() {
        return "";
    }

    /**
     * 返回值里<b>数组值</b>的键，按它们在结果对象里出现的顺序
     *
     * <h3>为什么要有这么个东西</h3>
     * 全平台<b>没有统一的列表包裹键</b>：同样是一个列表，{@code datamodel_list} 回 {@code models}、
     * {@code form_list} 回 {@code items}、{@code data_interface_list} 回 {@code list}。
     * 而 workflow 的 script 节点跑在沙箱里，拿到的就是这些对象 —— 取列表必须先知道键名。
     * 规划期没有任何接口能查到返回契约，模型只能<b>赌</b>；赌错时它不报错，
     * 只是安静地拿到一个空列表，本节照样记成功，真正的失败要等下一节才冒出来。
     *
     * <p>声明出来之后，两件事才有了唯一出处：规划提示词里那张「谁回哪些数组」的表由它渲染，
     * 校验器 V26 由它判「这里不点名会不会运行期抛」。此前两处各抄一份散文，
     * 而实测发现<b>两份都漏</b>：{@code datamodel_column_types}（types + 选型指导）与
     * {@code explain_form_rule}（logicRules + validateRules）都没被写进去，
     * 于是流程一读这两个工具就在运行期抛「猜不出该取哪个数组」。
     *
     * <h3>🔴 必须写<b>全部返回分支</b>的并集，不是只写正常那条</h3>
     * 形状是<b>按分支变</b>的，而模型在规划期不知道会走哪条：
     * {@code datamodel_preview_ddl} 的 CREATE 分支一个数组都不回，ALTER 分支却回
     * {@code addedColumns}/{@code modifiedColumns}/{@code droppedColumns}/{@code ddl} 四个；
     * {@code widget_contract_get} 正常时只回 {@code widgets}，问到不存在的类型时还会多出
     * {@code 已停用的类型} 与 {@code 不存在的类型}。只声明常见那条，漏掉的分支就会在运行期抛 ——
     * 而且专挑<b>出错的时候</b>抛，那时用户最需要看清错误，却看到一句「猜不出该取哪个数组」。
     *
     * <p>条件性的键（如仅在有告警时才出现的 {@code warnings}）<b>也要</b>列进来，同一条理由。
     *
     * <h3>返回空列表是什么意思</h3>
     * 「本工具的结果对象里没有任何数组值的键」—— 这是一句<b>断言</b>，不是「我没想过」。
     * 形状真的不可静态声明的工具（返回什么由外部配置决定）必须同时把
     * {@link #resultShapeKnown()} 覆写成 {@code false}，理由见那个方法。
     *
     * <p>键名允许中文（{@code 选型指导}、{@code 改表须知} 这些是真的中文键，不是笔误）。
     *
     * @return 数组值的键，按出现顺序；无数组键时返回空列表
     */
    default List<String> resultArrayKeys() {
        return List.of();
    }

    /**
     * 本工具的返回形状<b>能不能</b>静态声明
     *
     * <h3>为什么空列表不足以表达「不知道」</h3>
     * {@link #resultArrayKeys()} 返回空列表被渲染成「压根不回数组，别对它用 pickRows」——
     * 那是一句<b>确定的指引</b>。形状不可知的工具若也返回空列表，就会被印进同一栏，
     * 于是提示词对它说了一句假话，而模型照做时写出的取值代码要到运行期才炸。
     * 实测过这个坑：{@code data_interface_invoke} 的结果就是那个数据接口回的东西，
     * 它<b>可能</b>回数组，只是平台无从知道。
     *
     * <p>返回 {@code false} 的工具会被单独渲染成一栏「形状随接口而定」，
     * 并附一句「要解析它的结果就先用 script 把键名打出来看一眼」——
     * 把一个静默的错误变成一个当场可见的动作。
     *
     * <p>🔴 别为了省事把「懒得核」写成 {@code false}：那一栏教模型多绕一步，
     * 而能声明的工具绕过它就能少一轮试探。真不知道该声明什么，去读 {@code execute} 的返回值。
     *
     * @return 形状可静态声明则 true（绝大多数工具）
     */
    default boolean resultShapeKnown() {
        return true;
    }

    /**
     * 结果对象<b>顶层</b>的键，按出现顺序（全部返回分支的并集）
     *
     * <h3>它和 {@link #resultArrayKeys()} 管的是两件不同的事</h3>
     * 那个管「script 节点里 pickRows 该点名哪个数组」，本条管「<b>下游节点写
     * {@code {{$.某变量.某键}}} 时，那个键到底存不存在</b>」。前者错了会 throw 一句看得懂的中文，
     * 后者错了<b>什么都不响</b> —— 见下面。
     *
     * <h3>🔴 为什么非登记不可：猜错的路径不会报错，只会安静地变成空串</h3>
     * 实测（execution 217 / def 900217）：{@code datamodel_create} 回的是<b>扁平</b>对象
     * {@code {id, tableName, columnCount, message}}，规划器却写了
     * {@code "id":"{{$.create_out.model.id}}"} —— 它把<b>入参</b>的形状
     * （建表参数确实整块嵌在 {@code args.model} 里）镜像成了<b>出参</b>的形状。
     * 于是 {@code StatePathNavigator.navigate} 在 {@code model} 那一段拿到 null 并<b>按设计返回 null</b>
     * （它刻意不抛，见其类注释），{@code StateTemplateResolver.stringify(null)} 再把它变成 {@code ""}，
     * 最后 {@code Long.parseLong("")} 抛出
     * 「工具(datamodel_structure)的参数不合法：id 必须是数字，收到的是：」——
     * 冒号后面空的，看错误的人根本不知道那个值原本该是什么。
     * 而<b>发布前体检查不出来</b>：{@code WorkflowDslValidator} 的 {@code PLACEHOLDER_ROOT}
     * 正则只截取 {@code $.} 后的第一个标识符（{@code create_out}，确实可解析），
     * {@code .model.id} 整段被丢掉。同一条链路上另两个引用
     * {@code {{$.draft_out.id}}} 是对的，所以这不是「模型不会写」，是「模型只能赌」。
     *
     * <h3>为什么模型只能赌</h3>
     * 提示词里那张「返回值形状」表只渲染 {@link #resultArrayKeys()}，
     * 而 {@code datamodel_create} 只声明了 {@code warnings} 一个数组键 ——
     * 那个真正被下游要的标量 {@code id} <b>从来没被登记过，也从没印给模型看过</b>。
     * 唯一提到它的地方是样例3 里一句 {@code {{$.createOut}}}（整个对象塞进 prompt），
     * 全平台没有一个样例演示过「从建表结果里取 id」。
     *
     * <h3>登记之后有三处受益（缺一处就还会犯）</h3>
     * <ol>
     *   <li>提示词把顶层键印出来，模型照着写；</li>
     *   <li>校验器 V35 拿它判「这个子键不存在」，在<b>体检</b>阶段报 error 并列出真实键名，
     *       规划器于是能自修正 —— 不必指望模型每次都读对那张表；</li>
     *   <li>错误信息能点名「实有的键是 …」，而不是留一个空尾巴。</li>
     * </ol>
     *
     * <h3>🔴 空列表的意思是「未登记」，不是「不回对象」</h3>
     * 这一点与 {@link #resultArrayKeys()} <b>刻意不同</b>，别照着那边的直觉改：
     * 那边空列表被渲染成一句确定的指引（「压根不回数组，别对它用 pickRows」），
     * 因为 51 个工具都已经逐个核过了；本条<b>还没有</b>，绝大多数工具尚未登记，
     * 若把空列表也当成断言，提示词就会对它们说一句假话，V35 更会照着假话去拦真能跑的流程。
     * 所以渲染时把未登记者单独归一栏、明说「平台没登记它的顶层键，别猜子键」，
     * V35 对未登记者一律<b>沉默</b>（宁可漏报，不可误报 —— 误报会把能跑的流程拦在体检外）。
     * 形状真的不可静态声明的工具（{@link #resultShapeKnown()} 为 false）同样落这一栏。
     *
     * <p>要写<b>全部返回分支</b>的并集，理由同 {@link #resultArrayKeys()}：
     * 模型在规划期不知道会走哪条。条件性的键（仅在有告警时才出现的 {@code warnings}）也要列。
     * 键名允许中文（{@code datamodel_structure} 的 {@code 改表须知} 是真键名，不是笔误）。
     *
     * @return 顶层键，按出现顺序；<b>尚未登记</b>时返回空列表
     */
    default List<String> resultKeys() {
        return List.of();
    }

}
