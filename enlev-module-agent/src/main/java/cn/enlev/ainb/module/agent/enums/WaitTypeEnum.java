package cn.enlev.ainb.module.agent.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.StringJoiner;

/**
 * {@code wait} 节点的「等待方式」—— DSL {@code config.waitType} 的<b>取值</b>全集（缺陷 E2 的唯一权威）
 *
 * <h3>🔴 为什么要有这个枚举</h3>
 * 在本类出现之前，这三个取值是<b>裸字面量</b>散在 5 个文件里的：编译器的 {@code switch}（三档 case）、
 * Service 的两处（算 {@code resume_at} / 取 {@code event_key}）、变更清单的一处（决定要不要进 {@code waits[]}）、
 * 校验器的一处（V14 只认 event 那一档，且它自己抄了个 {@code private static final WAIT_TYPE_EVENT}）。
 * 字面量一旦散开，后来者只会照抄最近看到的那一处，没人知道全貌 —— 与 {@code AgentTaskKeys} 收口任务键
 * 是同一条理由。改完之后「waitType 有哪些取值」这个问题在全仓只有<b>一个</b>答案。
 *
 * <h3>🔴 {@link #parse} 的三条口径，每一条都是刻意的，别照抄别的枚举</h3>
 * <ol>
 *   <li><b>trim</b>：编译器一直是 {@code switch (waitType.trim())}，权威侧就 trim。
 *       🟡 本类落地时顺带统一掉了一处<b>口径分裂</b>：Service 的两处与变更清单那一处原先是<b>逐字比、不 trim</b>，
 *       于是 {@code waitType:" event "} 会「编译放行、V14 也认（它 trim）、清单不列、运行期不写 {@code event_key}」
 *       ⟹ 那次执行永久卡在 INTERRUPTED 且<b>没有任何报错</b>。这正是平台头号缺陷「配了不生效且无提示」的形状。
 *       存量已只读实测：全库 80 个 def 里 <b>wait 节点数 = 0</b>（{@code waitType} 字样命中也是 0，
 *       同一套 LIKE 写法扫 {@code tool} 命中 77 ⟹ 证明那个 0 不是扫错了），故这次统一不影响任何在跑的流程。</li>
 *   <li><b>大小写敏感</b>：{@code EVENT} 一律判未知。🔴 这一条与 {@link WorkflowRecoveryEnum#parse}
 *       的 {@code equalsIgnoreCase} <b>相反</b>，不是笔误 —— 那一侧放宽是安全的（认不出来回退 FORWARD
 *       = 不擅自触发反做），这一侧放宽会把 {@code EVENT} 悄悄当合法、或把 {@code telepathy} 悄悄当 duration，
 *       编译器那句「未知，仅支持 duration/event」就再也发不出来了。钉住它的既有测试：
 *       {@code WorkflowDslValidatorTest#validate_headlessWaitWrongCasedWaitType_leftToTheCompiler}
 *       （断言校验器<b>不</b>代劳、把大小写留给编译器独家报）与前端
 *       {@code triggerCases.spec.ts} 的「waitType 大小写敏感：EVENT 归编译器判未知，本谓词不代劳」。</li>
 *   <li><b>认不出来返回 {@code null}，绝不兜底</b>：兜底等于替作者把写错的值改对，
 *       而「改对成哪一档」这个问题没有安全答案（兜底成 duration 会让等事件的流程静默变成等 30 秒）。</li>
 * </ol>
 *
 * <h3>🟡 {@code "duration"} 一字两义 —— 这是本类最容易被误改的地方</h3>
 * {@code duration} 既是 {@code waitType} 的<b>取值</b>（{@link #DURATION}），又是 wait 节点 config 的<b>键名</b>
 * （{@code config.duration} = 等多少秒，由 {@code NodeIoContract} 登记、由
 * {@code DslToStateGraphCompiler#resolveWaitDurationSeconds} 直读）。两者处在<b>不同命名空间</b>：
 * 前者是 {@code config.get("waitType")} 的<b>值</b>，后者是 {@code config.get("duration")} 的<b>键</b>。
 * ⟹ 改任何一侧的名字都要回来看另一侧：本类改 {@code value} 会让存量 DSL 全部判未知；
 * 契约改键名会让 {@code resolveWaitDurationSeconds} 取不到秒数。两侧都由
 * {@code WaitTypeEnumTest} 钉住（它把 {@link #DURATION}{@code .getValue()} 与契约里那个键<b>逐字比对</b>，
 * 任一侧单独改动都会红）。
 *
 * <h3>⚠️ 前端那一份是<b>副本</b>，不是引用</h3>
 * {@code nodeConfigSchema.ts} 的 {@code wait.waitType.options} 里也写着 {@code 'duration'} / {@code 'event'}
 * （刻意<b>不列</b> {@code hitl} —— 那一档编译期就拒，摆进下拉只会教作者填一个必被拒的值）。
 * 跨语言没法共享枚举，故那一份只能靠测试对齐：后端侧由 {@code WorkflowDslPlannerPromptTest} 钉住
 * 「提示词教的取值 = 本枚举 {@code supported=true} 的那些」，与 E1 钉「节点类型目录 = 契约类型」同一条纪律。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum WaitTypeEnum {

    /**
     * 定时等待：挂起时由 Service 算出 {@code resume_at}，{@code WorkflowWaitResumeScheduler} 到点自动唤醒。
     *
     * <p>config 另需 {@code duration}（数字秒或 ISO-8601 如 {@code PT3S}，{@code >0} 且 {@code <=} 7 天）。
     * ⚠️ 那个键名与本档的取值逐字相同，理由见类注释「一字两义」那一节。
     */
    DURATION("duration", "定时等待", true),

    /**
     * 等外部事件：挂起时把 {@code eventKey} 写进执行记录的 {@code event_key} 列，
     * 等外部系统调 {@code POST /agent/workflow/event} 按 key 匹配唤醒。
     *
     * <p>🔴 平台内<b>零调用方</b>（前端也没有）⟹ 无头流程用这一档必须在 config 里声明
     * {@code externalWakeup}，否则 V14 判 error（没人会来发这个事件 = 永久卡死）。
     */
    EVENT("event", "等外部事件", true),

    /**
     * 人工介入 —— <b>已知但不支持</b>（{@link #isSupported()} 为 {@code false}）。
     *
     * <p>🔴 它必须在枚举里，正因为不支持：它与现成的 {@code hitl.confirm} 节点完全等价
     * （挂起、{@code resume_at}/{@code event_key} 皆 NULL、等人工 resume-stream），做它纯属重复表面。
     * 编译器要给这一档<b>专属</b>的报错文案（叫作者直接用 {@code hitl.confirm}），
     * 而不是笼统的「未知 waitType」—— 收进枚举才能让那条文案与「哪些值算已知」共用一份数据。
     */
    HITL("hitl", "人工介入（请改用 hitl.confirm 节点）", false),
    ;

    /** DSL 里逐字写的那个取值（大小写敏感，见类注释） */
    private final String value;

    /** 中文说法，给报错文案与设计器下拉用 */
    private final String description;

    /** 编译器会不会放行；{@code false} = 认识它、但明确拒（今天只有 {@link #HITL}） */
    private final boolean supported;

    /**
     * 严格解析：trim 后<b>大小写敏感</b>逐字比；空白或认不出来一律返回 {@code null}（<b>绝不兜底</b>）
     *
     * <p>三条口径的理由见类注释。🔴 别把它改成 {@code equalsIgnoreCase} 或加个默认返回值 ——
     * 那两条都会让编译器那句「未知，仅支持 duration/event」变成死代码。
     */
    public static WaitTypeEnum parse(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        if (v.isEmpty()) {
            return null;
        }
        for (WaitTypeEnum item : values()) {
            if (item.value.equals(v)) {
                return item;
            }
        }
        return null;
    }

    /**
     * 「作者能填哪些值」的文案，形如 {@code duration/event} —— 供报错与提示词现取，别再手抄字面量
     *
     * <p>只列 {@link #isSupported()} 的那些：{@link #HITL} 有它自己那条专属文案，混进来会让
     * 「仅支持 duration/event/hitl」与「hitl 请改用 hitl.confirm」两句话当场打架。
     */
    public static String supportedValuesText() {
        StringJoiner joiner = new StringJoiner("/");
        for (WaitTypeEnum item : values()) {
            if (item.supported) {
                joiner.add(item.value);
            }
        }
        return joiner.toString();
    }

}
