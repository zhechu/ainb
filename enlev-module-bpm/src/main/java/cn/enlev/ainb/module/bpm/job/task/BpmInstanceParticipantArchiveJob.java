package cn.enlev.ainb.module.bpm.job.task;

import cn.enlev.ainb.framework.quartz.core.handler.JobHandler;
import cn.enlev.ainb.module.bpm.service.task.BpmInstanceParticipantArchiveService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * 流程参与表归档 Job（S9 / v9 §8.3）
 *
 * <h3>怎么启用</h3>
 *
 * 两步，缺一不可：
 * <ol>
 *   <li>配置 {@code enlev.bpm.participant-archive.enabled=true}</li>
 *   <li>在「基础设施 → 定时任务」里新建一条，
 *       处理器填 {@code bpmInstanceParticipantArchiveJob}，建议 cron 放在业务低谷
 *       （如每天凌晨 3 点 {@code 0 0 3 * * ?}）</li>
 * </ol>
 *
 * 分两步是有意的：配置项管「要不要归档」，定时任务管「什么时候归」。
 * 只有定时任务的话，某人建了条任务就能让全公司看不到老单据；
 * 只有配置项的话，归档会在启动时或某个说不清的时刻发生。
 *
 * <h3>⚠️ 没有 @TenantJob</h3>
 *
 * 与 {@code CrmCustomerAutoPutPoolJob} 那类按租户循环的 Job 不同，
 * 归档是<b>跨租户的一次性集合操作</b>（{@code INSERT ... SELECT} + {@code DELETE}）。
 * 套上 {@code @TenantJob} 会让它按租户跑 N 遍，而每一遍的 SQL 都要全表扫一次 ——
 * 租户越多越慢，且结果完全一样。
 *
 * 代价是归档的粒度是全局的：不能给不同租户配不同保留期。
 * 真要那样，用行上的 {@code retain_until}（写入时按租户算好），
 * 而不是把 Job 拆成按租户跑。
 *
 * @author 走进新时代
 */
@Component
public class BpmInstanceParticipantArchiveJob implements JobHandler {

    @Resource
    private BpmInstanceParticipantArchiveService archiveService;

    @Override
    public String execute(String param) {
        int count = archiveService.archive();
        // 返回值会写进 infra_job_log.result —— 归档了多少行是事后唯一能查到的痕迹，
        // 返回 null 的话「昨晚到底归没归」就再也说不清了
        return String.format("归档流程参与记录 %d 行", count);
    }

}
