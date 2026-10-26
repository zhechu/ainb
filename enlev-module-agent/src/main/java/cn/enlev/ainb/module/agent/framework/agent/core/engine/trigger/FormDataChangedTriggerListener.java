package cn.enlev.ainb.module.agent.framework.agent.core.engine.trigger;

import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowDefMapper;
import cn.enlev.ainb.module.infra.api.formdesign.event.FormDataChangedEvent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * form_event 触发器监听器（增量6）：表单数据变更 → 自动拉起订阅了该事件的 Workflow
 *
 * <h3>数据流</h3>
 * infra 的 {@code FormDesignRuntimeServiceImpl} 在 create/update/delete 成功时（事务内）广播
 * {@link FormDataChangedEvent} → 本监听器在<b>事务提交后</b>被回调 → 按 {@code {formCode}:{eventType}}
 * 反查订阅的已发布定义 → 逐条经 {@link WorkflowTriggerInvoker} 无头启动。这是「客户提交表单 → AI 自动接管」
 * 的反射弧，本平台独有差异化能力（设计方案 P6，本增量提前落地）。
 *
 * <h3>🔴 事务语义：AFTER_COMMIT，且触发失败绝不波及用户已提交的表单数据</h3>
 * 用 {@code @TransactionalEventListener(AFTER_COMMIT)} 而非普通 {@code @EventListener}：
 * <ul>
 *   <li>只在数据变更事务<b>提交成功后</b>才触发下游 AI 流程；回滚的尝试（如 create 撞单号重试）天然不触发。</li>
 *   <li>本方法<b>全程 try/catch，任何异常只 log.warn 绝不外抛</b>——此时用户数据已落库，
 *       AI 流程拉没拉起是次要的，绝不能因为触发失败让用户看到「表单提交失败」。这是 fail-safe 的第一优先级。</li>
 * </ul>
 * 与 infra {@code FormDesignPublishedEvent}（普通 {@code @EventListener}、同事务、失败即回滚发布）刻意相反，
 * 理由见 {@link FormDataChangedEvent} 类注释。{@code fallbackExecution=true}：万一有无事务的发布路径，
 * 也立即执行而非静默丢弃（防御；当前三个发布点都在事务内）。
 *
 * <h3>🔴 租户隔离</h3>
 * AFTER_COMMIT 同步跑在<b>请求线程</b>上，ambient 租户 = 写表单的请求租户。
 * {@code selectPublishedByFormEventTrigger} 是 {@code TenantBaseDO} 查询，TenantLineInnerInterceptor 自动拼
 * {@code tenant_id=?}——故租户 A 的表单变更绝匹配不到租户 B 的定义。这也满足 {@link WorkflowTriggerInvoker}
 * 要求的「ambient 租户 == def.tenant」（def 正是用 ambient 租户查出来的）。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class FormDataChangedTriggerListener {

    @Resource
    private AgentWorkflowDefMapper defMapper;
    @Resource
    private WorkflowTriggerInvoker triggerInvoker;

    /**
     * 表单数据变更事件回调。空匹配（常态：该表单没有 form_event 订阅）静默返回，开销仅一次索引查询
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onFormChanged(FormDataChangedEvent event) {
        // 🔴 整体 try/catch：触发链上任何异常都只记日志、绝不外抛，保证「用户表单写入永远成功」这条底线。
        //    此刻事务已提交，用户数据稳如泰山；AI 流程拉起失败是可接受的次要损失
        try {
            String formCode = event.getFormCode();
            String eventType = event.getEventType();
            if (formCode == null || eventType == null) {
                log.warn("[onFormChanged][表单变更事件缺 formCode/eventType，跳过：{}]", event);
                return;
            }
            // 与 WorkflowTriggerExtractor 落库的 triggerKey 逐字一致：{formCode}:{eventType 小写}
            String triggerKey = formCode + ":" + eventType.toLowerCase();
            List<AgentWorkflowDefDO> defs = defMapper.selectPublishedByFormEventTrigger(triggerKey);
            if (defs == null || defs.isEmpty()) {
                return;
            }
            // 载荷整体写进 state[inputVar]（默认 "input"）。全 String，JSON 安全。
            // 下游节点用 dataId 做 form_data_get 精确取回刚变更的那一行
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("formCode", formCode);
            payload.put("eventType", eventType.toLowerCase());
            payload.put("dataId", event.getDataId());
            for (AgentWorkflowDefDO def : defs) {
                // 逐条无头启动；单条失败由 invoker 内部吞掉记日志（返回 false），不中断其余订阅方
                boolean dispatched = triggerInvoker.invokeHeadless(def, payload);
                log.info("[onFormChanged][表单({}) {} 事件命中定义({})，派发={}]",
                        formCode, eventType, def.getCode(), dispatched);
            }
        } catch (Exception ex) {
            log.warn("[onFormChanged][处理表单变更事件({}) 触发失败，已忽略以保护用户表单写入：{}]",
                    event, ex.getMessage(), ex);
        }
    }

}
