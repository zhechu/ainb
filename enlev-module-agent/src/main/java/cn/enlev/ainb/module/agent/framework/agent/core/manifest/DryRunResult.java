package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一项变更的只读干跑结论：「平台将会做什么」里，<b>只有查了才知道</b>的那一半
 *
 * <h3>🔴 为什么是三态而不是样例里那个 {@code passed} 布尔</h3>
 * 方案 §3-L2 的样例写 {@code "dryRun": {"passed": true, ...}}。落地改成三态，因为两态
 * 表达不了这件事最要紧的那个区分（§6.1：<b>「探测得出『不合法』才是 error，探测失败不是」</b>）：
 * <ul>
 *   <li>{@link Outcome#REJECTED} —— 问到了，答案是「这么改跑不通」。这是<b>结论</b>，
 *       S4b 的发布闸门据此拒发布，渲染成红色。</li>
 *   <li>{@link Outcome#NOT_PROBED} —— 没问成（infra 抛了非业务异常 / 表名是运行期占位符 /
 *       干跑预算耗尽 / 这类变更还没有干跑接口）。这<b>不是结论</b>，是「本次没校验」，渲染成黄色。</li>
 * </ul>
 * 一个布尔会把两者挤成同一个 {@code passed=false}，于是「infra 挂了」显示成「这个变更不合法」，
 * 用户去改一份本来没问题的 DSL。反过来，若为了不冤枉它而一律 {@code passed=true}，
 * 就得到 §3-L2 核实结果 ② 点名的那个后果：「infra 挂了」被渲染成「这个变更没问题」。
 * <b>两个方向都是清单在撒谎，所以必须三态。</b>
 *
 * <h3>枚举值为什么叫 {@code REJECTED} 而不是 {@code FAILED}</h3>
 * 「failed」在中文里同时读作「变更不合法」与「干跑没跑成」—— 正是上面要拆开的那两件事。
 * 用一个本身就有歧义的词命名其中一态，等于把歧义写进 API。
 *
 * <h3>{@link #evidence} 为什么是 Map</h3>
 * 与 {@link ChangeItem#getDetail()} 同一个理由：不同 kind 的凭据形状不同
 * （建表是 {@code ddl} 语句列表、表单体检是 {@code inspectIssues}、发布是 {@code menu} 配置），
 * 一种形状一个强类型字段的话，前端要为每种 kind 写一套渲染分支，漏一套就是空白。
 * 键的<b>顺序</b>即展示顺序，故必须 {@link LinkedHashMap}。
 *
 * <h3>序列化契约</h3>
 * 本对象随 {@link ChangeManifest} 一起被冻结进 BPM 流程变量，故只放字符串、数字、布尔、
 * List 与 Map（见 {@code ChangeManifest} 类注释的同一条约束）。
 *
 * @author 走进新时代
 */
@Data
public class DryRunResult {

    /**
     * 干跑结论的三态
     */
    public enum Outcome {

        /** 探到了，且这么改跑得通 */
        PASSED,

        /** 探到了，且这么改<b>跑不通</b>。这是结论，不是故障 */
        REJECTED,

        /**
         * 没能探。看 {@link DryRunResult#getReason()} 才知道为什么，
         * 而「为什么」一律是「本次没校验」而非「这项没问题」
         */
        NOT_PROBED

    }

    private Outcome outcome;

    /**
     * 人话结论，三种状态都要有 —— 渲染层不该为「干跑没通过」自己编一句话
     *
     * <p>{@link Outcome#REJECTED} 时是探测拿回来的原文（如 infra 的
     * {@code ServiceException} 消息、校验器列出的问题），不二次改写：
     * 改写就多一份会漂的措辞，而原文里往往正带着「该怎么改」。
     */
    private String reason;

    /**
     * 不阻断、但用户该看见的提醒
     *
     * <p>典型是 {@code DataModelCreateValidator} 那 8 条「后端静默改写」——
     * 建表能成功，只是建出来的不是你要的（不写长度就是 varchar(255)）。
     * 建表不可逆，这类提醒晚看见一步就只能改表。
     */
    private List<String> warnings = new ArrayList<>();

    /**
     * 探测拿回来的原始凭据：{@code ddl} / {@code inspectIssues} / {@code menu} ……
     * 空 Map 表示这次探测没有可展示的凭据。
     */
    private Map<String, Object> evidence = new LinkedHashMap<>();

    // ==================== 三个状态各一个工厂 ====================

    public static DryRunResult passed(String reason) {
        return of(Outcome.PASSED, reason);
    }

    public static DryRunResult rejected(String reason) {
        return of(Outcome.REJECTED, reason);
    }

    public static DryRunResult notProbed(String reason) {
        return of(Outcome.NOT_PROBED, reason);
    }

    private static DryRunResult of(Outcome outcome, String reason) {
        DryRunResult result = new DryRunResult();
        result.setOutcome(outcome);
        result.setReason(reason);
        return result;
    }

    /**
     * 带上一条凭据，返回自身以便链式写
     */
    public DryRunResult withEvidence(String key, Object value) {
        evidence.put(key, value);
        return this;
    }

    /**
     * 带上警告，返回自身以便链式写
     */
    public DryRunResult withWarnings(List<String> items) {
        if (items != null) {
            warnings.addAll(items);
        }
        return this;
    }

}
