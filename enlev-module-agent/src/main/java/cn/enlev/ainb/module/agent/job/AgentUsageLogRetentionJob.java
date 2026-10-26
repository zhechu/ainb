package cn.enlev.ainb.module.agent.job;

import cn.enlev.ainb.framework.quartz.core.handler.JobHandler;
import cn.enlev.ainb.framework.tenant.core.aop.TenantIgnore;
import cn.enlev.ainb.module.agent.service.usage.AgentUsageLogService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * 清理已过保留期的 AI 输入原文（方案 §16 第 6 条）
 *
 * <h3>它清什么、不清什么</h3>
 * <ul>
 *   <li><b>清</b>：{@code agent_usage_log.input_text} —— 用户提问原文快照，属个人信息</li>
 *   <li><b>不清</b>：行本身与全部计量字段 —— 那是<b>计费证据</b>，删了账上就有空洞</li>
 * </ul>
 * 两者保留诉求相反，所以只能置空不能删行。这也是 {@code AgentUsageLogMapper}
 * 「刻意不提供任何删除方法」那条规矩的延续 —— 新增的是 UPDATE，不是 DELETE。
 *
 * <h3>🔴 默认什么都不做</h3>
 * 判据是每行自己的 {@code retention_days}，默认 0 表示不清理。
 * 也就是说这个任务挂上去之后，在有人显式配置保留期之前<b>一行都不会动</b>。
 * 这是刻意的：方案 §16 第 5 条把「保留期策略」明确推迟了，
 * 现在要的是「字段和清理通道都就位，哪天定了策略不用改代码」。
 *
 * <h3>为什么要 {@code @TenantIgnore}</h3>
 * 清理是跨租户的运维动作。不加的话只会清到「当前租户」——
 * 而定时任务线程上压根没有当前租户，结果是一行都清不掉且不报错。
 *
 * @author 走进新时代
 */
@Slf4j
@Component
@ConditionalOnExpression("'professional'.equals('${enlev.mode:public}')")
public class AgentUsageLogRetentionJob implements JobHandler {

    /**
     * 单次执行处理的行数上限
     *
     * 分批而不是一次清完：这张表会长得很大，一条 UPDATE 扫全表会长时间持锁，
     * 把线上对话一起拖住。宁可多跑几次。
     */
    private static final int CLEAN_LIMIT = 500;

    @Resource
    private AgentUsageLogService agentUsageLogService;

    @Override
    @TenantIgnore
    public String execute(String param) {
        int count = agentUsageLogService.clearExpiredInputText(CLEAN_LIMIT);
        log.info("[execute][清理已过保留期的 AI 输入原文 ({}) 条]", count);
        return String.format("清理已过保留期的 AI 输入原文 %s 条", count);
    }

}
