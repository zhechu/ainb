package cn.enlev.ainb.module.bpm.enums.task;

import cn.hutool.core.util.ArrayUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 流程实例的参与方式
 *
 * <h3>为什么要区分参与方式，而不是只记「这个人参与了」</h3>
 *
 * 可见范围与可写范围由参与方式决定，两者并不一致：
 *
 * <ul>
 *   <li>审批人在<b>自己的任务未办结时</b>可写，办结后只剩只读 —— 所以要记 {@code writable}</li>
 *   <li>抄送人任何时候都只读。把抄送人和审批人记成同一种，
 *       「抄送给财务看一眼」就会变成「财务能改这张单子」</li>
 *   <li>转办出去的<b>原</b>受理人仍要能看（否则「我转办的单子」自己反而看不到），
 *       但不该再能写 —— 单子已经不归他办了</li>
 * </ul>
 *
 * 只记「参与了」的话，上面三条差别全部丢失，后面只能靠再查一遍 Flowable 补回来，
 * 而那正是本表要替代的东西。
 *
 * <h3>编号留了间隔</h3>
 *
 * 按 10 递增，方便后续插入新的参与方式（S6 的委托、子流程调起等）而不必重排。
 * 值一旦落库就不能改 —— 改了等于把历史数据的含义悄悄换掉。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum BpmParticipationEnum {

    /**
     * 发起人
     *
     * 实例创建时写入，全程可见。<b>不可写</b> —— 发起人改单据走的是
     * 「撤回后重新提交」，不是直接改
     */
    STARTER(10, "发起人", false),

    /**
     * 审批人
     *
     * 任务分配给某人时写入。任务办结时置 {@code writable=false} 并补 {@code end_time}
     */
    APPROVER(20, "审批人", true),

    /**
     * 转办的原受理人
     *
     * 转办把任务交给别人办，原受理人保留只读可见性 —— 单子已经不归他办了。
     * 新受理人另写一条 {@link #APPROVER}
     */
    TRANSFER_FROM(30, "转办出去的原受理人", false),

    /**
     * 委派的原受理人
     *
     * ⚠️ 与转办的差别就在可写性上：委派办完会<b>退回</b>给原受理人，
     * 所以他始终保有可写。写成不可写的后果是「委派回来之后自己反而改不了了」
     */
    DELEGATE_FROM(40, "委派出去的原受理人", true),

    /**
     * 加签发起人
     *
     * 加签把一个任务拆成多个，发起加签的人仍要跟进结果并最终办理
     */
    SIGN_FROM(50, "加签发起人", true),

    /**
     * 抄送人
     *
     * ⚠️ 永远只读。抄送的语义就是「知会」，不是「参与办理」
     */
    COPY(60, "抄送人", false);

    /**
     * 参与方式
     */
    private final Integer participation;
    /**
     * 名字
     */
    private final String name;
    /**
     * 默认是否可写
     *
     * 放在枚举上而不是散在六个写入时机里：可写性是参与方式的<b>固有属性</b>，
     * 分散判断的话迟早有一处漏改，而漏改的表现是静默的越权或静默的「改不了」
     */
    private final boolean defaultWritable;

    public static BpmParticipationEnum of(Integer participation) {
        return ArrayUtil.firstMatch(value -> value.getParticipation().equals(participation), values());
    }

    /**
     * 该参与方式是不是「任务移交出去的原受理人」
     *
     * 转办 / 委派 / 加签三者结构完全相同（都是「把任务交出去，但原受理人保留可见性」），
     * 差别只在可写性上。写入侧用这一条做入参校验，免得把 STARTER / COPY 误传进移交入口
     */
    public boolean isHandover() {
        return this == TRANSFER_FROM || this == DELEGATE_FROM || this == SIGN_FROM;
    }

}
