package cn.enlev.ainb.module.infra.api.formdesign.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

/**
 * 表单<b>数据</b>变更事件（一行数据被 create / update / delete 时广播）
 *
 * <h3>为什么是事件，而不是 infra 反向调 agent</h3>
 * 与 {@link FormDesignPublishedEvent} 同理：模块依赖单向，agent 依赖 infra，infra 不能反向调 agent。
 * infra 只广播「某表单的某行数据发生了某种变更」，谁关心谁监听。
 * 增量6 的 {@code FormDataChangedTriggerListener}（agent 侧）监听本事件，实现 form_event 触发器：
 * 「客户提交表单 → 自动拉起 AI 流程质检/分类/回写」。
 *
 * <h3>🔴 事务语义与 {@link FormDesignPublishedEvent} <b>相反</b>——这是本事件最关键的设计点</h3>
 * {@link FormDesignPublishedEvent} 的监听方用普通 {@code @EventListener}，跑在发布事务<b>内</b>，
 * 登记失败即回滚整个发布（刻意：宁可发布失败得明明白白）。
 * <p>但<b>数据变更绝不能照抄这个语义</b>：AI 流程不能跑在用户表单提交的事务里，更不能因为
 * 「拉起 AI 流程失败」而回滚用户已经写好的数据——那是本末倒置（用户的业务数据比自动化重要得多）。
 * 因此本事件的监听方必须用 {@code @TransactionalEventListener(phase=AFTER_COMMIT)}：
 * <ul>
 *   <li>事件在数据变更事务<b>内</b>发布（{@code publishEvent} 在 create/update/delete 成功返回前调），
 *       但监听器<b>只在事务提交成功后</b>才被回调；事务回滚的尝试天然不触发下游。</li>
 *   <li>监听器内部再包 try/catch，触发失败只记日志、绝不外抛——此时用户数据已提交，稳如泰山。</li>
 * </ul>
 * 这与 create 的「单据号撞唯一索引 → 事务外重试」也天然契合：只有最终提交成功的那次才发事件。
 *
 * @author 走进新时代
 */
@Getter
@ToString
@RequiredArgsConstructor
public class FormDataChangedEvent {

    /** 变更类型：新建一行数据 */
    public static final String TYPE_CREATE = "create";
    /** 变更类型：更新一行数据 */
    public static final String TYPE_UPDATE = "update";
    /** 变更类型：删除一行数据 */
    public static final String TYPE_DELETE = "delete";

    /**
     * 表单编码（{@code formCode}），form_event 触发器按它匹配订阅方
     */
    private final String formCode;

    /**
     * 变更类型，取值见 {@link #TYPE_CREATE} / {@link #TYPE_UPDATE} / {@link #TYPE_DELETE}
     *
     * <p>DSL 的 {@code trigger.form_event.eventType} 只列了 create/update；delete 是本事件的扩展，
     * 供「数据删除后触发清理/通知类流程」用（订阅方按需监听）。
     */
    private final String eventType;

    /**
     * 变更行的主键（字符串化，对齐 {@code FormDataChangeLogDO.dataId} 的口径）
     *
     * <p>下游流程用它做 {@code form_data_get} 精确取回刚变更的那一行。delete 时该行已删，
     * 取回会落空——delete 触发的流程应把 dataId 当「标识」而非「可再查的活数据」用。
     */
    private final String dataId;

    /**
     * 租户编号。监听器据此确保在正确租户上下文里匹配 def（ambient 租户已由请求线程设定，
     * 此字段供监听器校验/日志，跨模块传递不依赖 ThreadLocal）
     */
    private final Long tenantId;

}
