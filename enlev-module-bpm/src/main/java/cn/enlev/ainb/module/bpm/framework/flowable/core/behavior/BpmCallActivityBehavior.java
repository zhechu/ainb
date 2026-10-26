package cn.enlev.ainb.module.bpm.framework.flowable.core.behavior;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.bpm.framework.permission.BpmSubProcessCallChecker;
import lombok.extern.slf4j.Slf4j;
import org.flowable.bpmn.model.CallActivity;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.impl.bpmn.behavior.CallActivityBehavior;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.flowable.engine.impl.util.ProcessDefinitionUtil;
import org.flowable.engine.repository.ProcessDefinition;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.bpm.enums.ErrorCodeConstants.PROCESS_INSTANCE_START_CHILD_NOT_ALLOWED;

/**
 * 带「子流程调起授权」的 CallActivity 行为（v9 §4.2，S6c / TODO #37）
 *
 * <h3>⚠️ 为什么校验点从执行监听器搬到了这里</h3>
 *
 * S6c 最初把校验挂在 {@code BpmCallActivityListener#notify} 首部。那个监听器是
 * {@code SimpleModelUtils} 在把<b>钉钉式设计器</b>的子流程节点转成 BPMN 时手动挂上去的
 * （{@code ExecutionListener.EVENTNAME_START}），于是：
 *
 * <pre>
 * 钉钉式设计器画的子流程   → 挂了监听器 → 校验生效
 * 原生 BPMN 的 callActivity → 没有监听器 → 【一行校验都不跑】
 * </pre>
 *
 * 也就是说，<b>想绕过这道防线只要换一个设计器</b>，而界面上完全看不出区别。
 * 一道「换个入口就没有了」的防线，比没有防线更危险 —— 它会让人以为已经防住了。
 *
 * {@link org.flowable.engine.impl.bpmn.parser.factory.ActivityBehaviorFactory} 是
 * Flowable 解析<b>任何</b> CallActivity 的必经之路，与 XML 是谁生成的无关，
 * 所以校验放在这里才是真的没有旁路。
 *
 * <h3>解析不出子流程 key 时放行</h3>
 *
 * 这不是「失败就放过」—— 恰恰相反，<b>凡是我们解析不出来的情形，紧随其后的
 * {@code super.execute} 一样解析不出来，调用本身就会失败</b>（用的是同一个
 * {@link ProcessDefinitionUtil} 缓存、同一个表达式求值器）。
 * 在这里抢先抛「无权调起」只会把「子流程不存在」这种真实原因盖掉，
 * 让排查的人朝权限方向白找一天。
 *
 * @author 走进新时代
 */
@Slf4j
public class BpmCallActivityBehavior extends CallActivityBehavior {

    /**
     * 子流程调起授权校验器
     *
     * 允许为 null：行为对象是 Flowable 解析流程时造的，
     * 万一装配没到位也不该让所有子流程都跑不起来
     */
    private final BpmSubProcessCallChecker subProcessCallChecker;

    public BpmCallActivityBehavior(CallActivity callActivity, BpmSubProcessCallChecker subProcessCallChecker) {
        super(callActivity);
        this.subProcessCallChecker = subProcessCallChecker;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String childProcessKey = resolveChildProcessKey(execution);
        if (subProcessCallChecker != null
                && !subProcessCallChecker.canCall(execution.getProcessDefinitionId(), childProcessKey)) {
            throw exception(PROCESS_INSTANCE_START_CHILD_NOT_ALLOWED, childProcessKey);
        }
        superExecute(execution);
    }

    /**
     * 真正调起子流程
     *
     * 拆出这一层只为可测：{@code super.execute} 要 Flowable 的命令上下文，
     * 单测里起不来，而「被拦住时不能调起子流程」恰恰是本类最需要钉住的行为
     */
    protected void superExecute(DelegateExecution execution) {
        super.execute(execution);
    }

    /**
     * 解析被调起的子流程定义 key
     *
     * ⚠️ {@code calledElement} 有两种含义（{@code calledElementType}）：
     * {@code key} 是流程标识、{@code id} 是<b>某一个版本</b>的定义编号。
     * 授权配在 key 上（S5d：definitionId 每次部署都变，拿它配的授权会在改版后集体失效），
     * 所以 id 形态必须换算成 key 再判 —— 不换算的话，
     * 用「按 id 调用」的方式写流程就能绕开所有子流程授权
     *
     * @return 子流程 key；解析不出时返回 null（由调用方放行，理由见类注释）
     */
    protected String resolveChildProcessKey(DelegateExecution execution) {
        try {
            String calledElementValue = resolveCalledElementValue(execution);
            if (StrUtil.isBlank(calledElementValue)) {
                return null;
            }
            if (!CALLED_ELEMENT_TYPE_ID.equals(callActivity.getCalledElementType())) {
                // 不写 type 时 Flowable 按 key 处理，这里保持一致
                return calledElementValue;
            }
            return lookupProcessKeyById(calledElementValue);
        } catch (Exception ex) {
            log.warn("[resolveChildProcessKey][解析子流程 key 失败，调起授权本次不参与判定，calledElement({})]",
                    callActivity.getCalledElement(), ex);
            return null;
        }
    }

    /**
     * 求值 {@code calledElement}（它可以是表达式，如 {@code ${childKey}}）
     *
     * 单独拆出来只为可测：静态的 {@link CommandContextUtil} 在单测里没有命令上下文
     */
    protected String resolveCalledElementValue(DelegateExecution execution) {
        ProcessEngineConfigurationImpl configuration = CommandContextUtil.getProcessEngineConfiguration();
        return getCalledElementValue(execution, configuration);
    }

    /**
     * 按定义编号反查流程 key，走的是与 {@code super.execute} 同一份部署缓存
     */
    protected String lookupProcessKeyById(String processDefinitionId) {
        ProcessDefinition definition = ProcessDefinitionUtil.getProcessDefinition(processDefinitionId);
        return definition == null ? null : definition.getKey();
    }

}
