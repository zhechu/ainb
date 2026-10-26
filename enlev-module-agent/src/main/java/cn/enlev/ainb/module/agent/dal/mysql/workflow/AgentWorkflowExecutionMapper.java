package cn.enlev.ainb.module.agent.dal.mysql.workflow;

import cn.hutool.core.collection.CollUtil;
import cn.enlev.ainb.framework.common.pojo.PageParam;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentWorkflowExecutionPageReqVO;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowExecutionDO;
import cn.enlev.ainb.module.agent.enums.WorkflowExecutionStatusEnum;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Workflow 执行实例 Mapper
 *
 * @author 走进新时代
 */
@Mapper
public interface AgentWorkflowExecutionMapper extends BaseMapperX<AgentWorkflowExecutionDO> {

    /**
     * 按 thread_name 查（resume 时用，checkpoint 关联键）
     */
    default AgentWorkflowExecutionDO selectByThreadName(String threadName) {
        return selectOne(AgentWorkflowExecutionDO::getThreadName, threadName);
    }

    /**
     * 分页查某用户的执行历史，按开始时间倒序
     *
     * ⚠️ userId 用 eq 而非 eqIfPresent：传 null 应查不出东西，
     * 而不是「不过滤」把全租户的执行历史端出来（同 AgentConversationMapper 的越权教训）。
     */
    default PageResult<AgentWorkflowExecutionDO> selectPageByUserId(
            Long userId, PageParam pageParam) {
        return selectPage(pageParam, new LambdaQueryWrapperX<AgentWorkflowExecutionDO>()
                .eq(AgentWorkflowExecutionDO::getUserId, userId)
                .orderByDesc(AgentWorkflowExecutionDO::getBeginTime)
                .orderByDesc(AgentWorkflowExecutionDO::getId));
    }

    /**
     * 管理端分页（增量8 / P4）：按定义/状态过滤，<b>租户内全量</b>（不按 userId 收窄）
     *
     * <p>🔴 与上面的 {@link #selectPageByUserId} 是<b>两个不同信任级的视图</b>，别混用：
     * <ul>
     *   <li>{@code selectPageByUserId}：用户端「只看自己的执行历史」，故 {@code eq(userId)} 写死——
     *       传 null 就查不出东西，绝不能退化成「不过滤」把全租户历史端给普通用户（越权教训）。</li>
     *   <li>本方法：管理页「看本租户所有流程的执行」，由 {@code agent:workflow:query}（管理菜单授予）把关，
     *       租户内全量是合法的管理范围（同租户管理台看全租户数据的惯例）。故这里<b>不</b>带 userId 条件。</li>
     * </ul>
     * 两者的租户隔离都由 {@code TenantBaseDO} + TenantLineInnerInterceptor 自动拼 {@code tenant_id=?} 保证，
     * 跨租户绝不可见——管理端全量指的是「本租户全量」，不是「全平台全量」。
     * workflowDefId/status 用 {@code eqIfPresent}：不传即不过滤（管理页允许只按状态或只按定义筛）。
     */
    default PageResult<AgentWorkflowExecutionDO> selectPage(AgentWorkflowExecutionPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<AgentWorkflowExecutionDO>()
                .eqIfPresent(AgentWorkflowExecutionDO::getWorkflowDefId, reqVO.getWorkflowDefId())
                .eqIfPresent(AgentWorkflowExecutionDO::getStatus, reqVO.getStatus())
                .orderByDesc(AgentWorkflowExecutionDO::getBeginTime)
                .orderByDesc(AgentWorkflowExecutionDO::getId));
    }

    /**
     * 扫「到点该自动唤醒」的定时挂起执行（增量4a，供 {@code WorkflowWaitResumeScheduler} 用）
     *
     * <p>条件：status=INTERRUPTED 且 resume_at 非空且 <= now。resume_at 非空即「wait(duration) 挂起」，
     * 与 hitl 的人工挂起（resume_at 为 NULL）区分开 —— 后者绝不被本方法捞出自动恢复。
     *
     * <p>🔴 租户隔离：本表是 {@code TenantBaseDO}，TenantLineInnerInterceptor 会自动给本查询拼
     * {@code tenant_id=?}。调用方（轮询器）必须先在 {@code TenantUtils.execute(tenantId, …)} 里逐租户设好上下文，
     * 绝不 {@code executeIgnore}（agent 模块架构红线）。limit 限制单批，防一次唤醒过多打爆线程池。
     */
    default List<AgentWorkflowExecutionDO> selectDueWaits(LocalDateTime now, int limit) {
        return selectList(new LambdaQueryWrapperX<AgentWorkflowExecutionDO>()
                .eq(AgentWorkflowExecutionDO::getStatus, WorkflowExecutionStatusEnum.INTERRUPTED.getStatus())
                .isNotNull(AgentWorkflowExecutionDO::getResumeAt)
                .le(AgentWorkflowExecutionDO::getResumeAt, now)
                .orderByAsc(AgentWorkflowExecutionDO::getResumeAt)
                .last("LIMIT " + limit));
    }

    /**
     * 按 eventKey 查「正在等这个外部事件」的挂起执行（增量4b，供 {@code publishEvent} 用）
     *
     * <p>条件：status=INTERRUPTED 且 event_key=eventKey。这是「广播」语义——同一租户下所有等待该键的
     * 执行都会被返回、逐条唤醒；要精确单点唤醒，DSL 作者用足够唯一的 eventKey（如含 bizId）。
     * 额外 {@code isNull(resume_at)} 是防御：事件挂起本就不该有 resume_at（两类挂起互斥）。
     *
     * <p>🔴 租户隔离：本表是 {@code TenantBaseDO}，TenantLineInnerInterceptor 自动拼 {@code tenant_id=?}。
     * 调用方（Controller/Service）在发布者的 Servlet 租户上下文里调它，故租户 A 的事件绝匹配不到租户 B 的执行。
     * limit 限制单批（MVP 上界 50），防一个热点事件一次唤醒过多打爆线程池。
     */
    default List<AgentWorkflowExecutionDO> selectInterruptedByEventKey(String eventKey, int limit) {
        return selectList(new LambdaQueryWrapperX<AgentWorkflowExecutionDO>()
                .eq(AgentWorkflowExecutionDO::getStatus, WorkflowExecutionStatusEnum.INTERRUPTED.getStatus())
                .eq(AgentWorkflowExecutionDO::getEventKey, eventKey)
                .isNull(AgentWorkflowExecutionDO::getResumeAt)
                .orderByAsc(AgentWorkflowExecutionDO::getId)
                .last("LIMIT " + limit));
    }

    /**
     * 原子认领：把一条 INTERRUPTED 执行 CAS 成 RUNNING
     * （并清掉 resume_at、event_key、suspend_node、dropped_keys），返回是否认领成功。
     *
     * <p>🔴 这是「唤醒去重」的唯一闸门：轮询器多实例、同一 eventKey 并发发布、或轮询器/事件/人工 resume 互相撞车时，
     * {@code WHERE id=? AND status=INTERRUPTED} 保证只有一个调用方能把它从 INTERRUPTED 翻成 RUNNING
     * （影响 1 行），其余影响 0 行即「已被别人恢复」→ 调用方放弃，杜绝同一执行被双跑。
     * resume() 也走本方法（替代原 setStatus+updateById），让人工恢复同样获得并发安全。
     * 顺带清 resume_at（duration 挂起）、event_key（event 挂起）、suspend_node（挂起在哪道闸口）
     * 与 dropped_keys（恢复会丢哪些作者键），让已恢复的行不再带任何等待标记。
     *
     * <p>🔴 这四列必须用 wrapper 的 {@code .set(field, null)} 清，不能靠 {@code updateById}：
     * MP 的 {@code updateById} 默认 NOT_NULL 策略，字段为 null 时压根不进 UPDATE 语句 ⟹ 清不掉。
     * 少清 {@code suspend_node} 的表现是：执行恢复后又停在<b>第二个</b>闸口，而列表/抽屉上仍显示
     * 第一个闸口的名字（那次挂起时写入的值还在），救援的人被指到一个早已跑完的节点上。
     * 少清 {@code dropped_keys} 的表现更隐蔽：resume 那次算出的差集只在<b>非空时</b>才 UPDATE
     * （见 {@code AgentWorkflowExecutionServiceImpl#runGraph} 的 resume 分支，空集刻意不覆盖），
     * 于是「这次恢复一个作者键都不丢」会被上一次的旧清单顶替 —— 确认框照样弹警告，
     * 而运维照警告去补的声明其实早就补过了。
     */
    default boolean casInterruptedToRunning(Long id) {
        LambdaUpdateWrapper<AgentWorkflowExecutionDO> wrapper = new LambdaUpdateWrapper<AgentWorkflowExecutionDO>()
                .eq(AgentWorkflowExecutionDO::getId, id)
                .eq(AgentWorkflowExecutionDO::getStatus, WorkflowExecutionStatusEnum.INTERRUPTED.getStatus())
                .set(AgentWorkflowExecutionDO::getStatus, WorkflowExecutionStatusEnum.RUNNING.getStatus())
                .set(AgentWorkflowExecutionDO::getResumeAt, null)
                .set(AgentWorkflowExecutionDO::getEventKey, null)
                .set(AgentWorkflowExecutionDO::getSuspendNode, null)
                .set(AgentWorkflowExecutionDO::getDroppedKeys, null);
        // entity 传 null：全部 SET 由 wrapper 的 .set(...) 提供；返回影响行数 >0 即认领成功
        return update(null, wrapper) > 0;
    }

    /**
     * 原子取消：把一条<b>尚未收尾</b>的执行 CAS 成 {@code CANCELLED}，返回影响行数（&gt;0 即本次取消认领成功）。
     *
     * <p>🔴 为什么必须是条件更新而不是 {@code setStatus + updateById}（迭代七 B3-2）：
     * 「取消」与「执行自己收尾」是<b>并发的两件事</b> —— 管理员在抽屉上点取消的那一刻，
     * 图很可能正在 boundedElastic 上跑最后一个节点。两边都无条件写 status 的话，
     * 后写的那一方赢，于是「点了取消、列表刷新回来还是 SUCCEEDED」——正是本子系统头号缺陷
     * 「操作成功了但不生效」的形状。{@code WHERE status IN (未收尾的三档)} 让两边只有一个能赢，
     * 输的一方拿到 0 行，据此如实回话（见 {@code AgentWorkflowExecutionServiceImpl#cancel}）。
     *
     * <p>🔴 可取消的那几档<b>不在这里列字面量</b>，取自 {@link WorkflowExecutionStatusEnum#cancellableStatuses()}
     * —— 与 {@code cancel} 的预检<b>同源</b>。理由与两个反例（FAILED / COMPENSATING 都「不是终态却不可取消」）
     * 写在 {@link WorkflowExecutionStatusEnum#isCancellable} 的注释里，此处不重复一份、免得两份漂移。
     *
     * <p>🔴 <b>不清</b> {@code resume_at / event_key / suspend_node}（与 {@link #casInterruptedToRunning} 相反）：
     * 那两个唤醒凭据的消费者（轮询器 {@code selectDueWaits}、事件匹配 {@code selectInterruptedByEventKey}）
     * 都带 {@code status=INTERRUPTED} 条件，行一旦是 CANCELLED 就都扫不到它，留着无害；
     * 而 {@code suspend_node} 留着恰好是有用的事后信息 ——「这条执行被取消时正挂在哪个闸口上」。
     * 清掉它反而把排障线索抹了。
     *
     * @param id         执行实例编号
     * @param errorMsg   取消原因（落 {@code error_msg} 列；本表没有独立的 reason 列，这一列就是原因载体）
     * @param endTime    收尾时刻
     * @param durationMs 耗时。🔴 必须由调用方一并给：{@code settle} 那条路（SSE 断流）本来就写它，
     *                   若本方法不写，同一个「取消」动作经两条路径落库会长成两种形状 ——
     *                   一条有耗时、一条空着，而抽屉的「耗时(ms)」列正是本轮要给人看的那一列。
     * @return 影响行数。0 = 撞车（那边先收尾了）或该行已删/不属本租户，调用方据此如实回话
     */
    default int casToCancelled(Long id, String errorMsg, LocalDateTime endTime, Integer durationMs) {
        LambdaUpdateWrapper<AgentWorkflowExecutionDO> wrapper = new LambdaUpdateWrapper<AgentWorkflowExecutionDO>()
                .eq(AgentWorkflowExecutionDO::getId, id)
                .in(AgentWorkflowExecutionDO::getStatus, WorkflowExecutionStatusEnum.cancellableStatuses())
                .set(AgentWorkflowExecutionDO::getStatus, WorkflowExecutionStatusEnum.CANCELLED.getStatus())
                .set(AgentWorkflowExecutionDO::getErrorMsg, errorMsg)
                .set(AgentWorkflowExecutionDO::getEndTime, endTime)
                .set(AgentWorkflowExecutionDO::getDurationMs, durationMs);
        return update(null, wrapper);
    }

    /**
     * 收尾写，但<b>绝不覆盖已取消的行</b>：{@code WHERE id=? AND status<>'CANCELLED'}。
     *
     * <p>🔴 这一条与 {@link #casToCancelled} 是<b>一对</b>，缺任何一半取消都是一句假话（迭代七 B3-1/B3-2）：
     * 取消把行判成 CANCELLED 之后，那条<b>还在跑的图</b>迟早会跑到自然结束并调 {@code settle} 收尾。
     * 收尾若走裸 {@code updateById}，就会把 CANCELLED 覆盖成 SUCCEEDED/FAILED ——
     * 用户点了取消、当下看到「已取消」，几十秒后刷新回来变成「成功」。
     * 加了这道条件，晚到的收尾写影响 0 行，CANCELLED 站得住。
     *
     * <p>🔴 用 SQL 条件而不是内存里的「已取消 id 集合」：后者只在同一 JVM 内成立，
     * 而多实例部署下取消请求完全可能落到<b>另一台</b>（图跑在 A 台、cancel 打到 B 台），
     * 内存标记在 A 台上不存在，照样被覆盖。SQL 条件是唯一跨实例也成立的写法。
     *
     * <p>SET 由 entity 的非空字段提供（MyBatis-Plus 的 {@code update(entity, wrapper)} 语义，主键不进 SET），
     * 故调用方仍按原来那样填 DO；租户隔离与 {@code deleted=0} 由拦截器与 {@code @TableLogic} 自动拼。
     *
     * @return 影响行数。0 = 该行已被判为 CANCELLED（或已删/不属本租户），本次收尾<b>没有</b>落库
     */
    default int updateByIdUnlessCancelled(AgentWorkflowExecutionDO update) {
        return update(update, new LambdaUpdateWrapper<AgentWorkflowExecutionDO>()
                .eq(AgentWorkflowExecutionDO::getId, update.getId())
                .ne(AgentWorkflowExecutionDO::getStatus,
                        WorkflowExecutionStatusEnum.CANCELLED.getStatus()));
    }

    /**
     * 批量数「这些定义各被<b>拉起</b>过几次」（台账 #81：闸门给「目标物件已在平台上」那类撞车分档的唯一取数口）
     *
     * <p>🔴 「已落地」在本方法里指的是<b>那张表 / 那个表单已经在平台上了</b>（由干跑探测出来），
     * <b>不是</b>「本流程成功跑通过」—— 后者这个数答不了，理由见下面第三节。
     *
     * <h3>为什么是批量而不是一行一查</h3>
     * 两个消费者：发布闸门一次只问一行，而 {@code auditAll} 要问全租户几十行。
     * 一行一查在盘点那边就是 {@code 2N} 次往返（先按 code 展开版本、再数执行），
     * 而这条查询本身是只读的、可以一次问完 —— 故取数口只留批量这一个，
     * 单行的调用方传一个单元素集合，两侧走同一条 SQL、不会有第二套口径。
     *
     * <h3>🔴 为什么入参是 defId 集合、而不是 code</h3>
     * 本表<b>没有 code 列</b>（只有 {@code workflow_def_id} + {@code workflow_version}），
     * 而裁定要的是「按 code 查执行史」—— 回滚会造出<b>同 code 的新 id</b>，
     * 那个新版本自己一次都没跑过，执行史全在旧版本的 id 上。
     * 所以「code → 名下全部版本 id」这一步必须由调用方用 {@code selectListByCode} 先展开，
     * 本方法只认 id。少展开这一步的表现是：回滚出的草稿明明有执行史，却仍被判「从没跑过」而拦下。
     *
     * <p>列名写死在这里、不接受外部输入（同 {@code AgentUsageLogMapper#selectUsageGroupBy} 的纪律）；
     * {@code in} 走参数绑定，defId 又全是库里查出来的 Long，无注入面。
     * 租户隔离与 {@code deleted=0} 分别由 TenantLineInnerInterceptor 与 {@code @TableLogic} 自动拼，
     * 故这里的计数口径与管理页执行历史列表逐字一致。
     *
     * <h3>🔴 数的是<b>尝试次数</b>，不是成功次数 —— 而且这是<b>刻意</b>的</h3>
     * {@code COUNT(*)} 不带 status 过滤，故 FAILED / INTERRUPTED 的每一次尝试都算在内。
     * 实测过某一行 5 次里 3 次是 FAILED。所以：<b>拿这个数当「成功跑通过」的证据是错的</b>，
     * 它只能证明「这个 code 名下真被拉起过 N 次」。
     *
     * <p>⚠️ 那为什么不加 {@code status=SUCCEEDED}？因为唯一的消费者是发布闸门的<b>降档</b>判据
     * （{@code WorkflowDslValidator#foldDryRunRejections}：{@code executedCount > 0} 且有撞车 ⟹ error 降成 warning），
     * 它要回答的问题是「平台上已有的那张表/那个表单，<b>极可能就是本流程早先的版本建的</b>吗」。
     * 一次 FAILED 的执行完全可能<b>已经建完了表、只是在后面的节点上炸的</b>；HITL 挂起（INTERRUPTED）同理。
     * 改成只数 SUCCEEDED 会让这批行归零 ⟹ 不再降档 ⟹ 重新犯台账 #81 那个病
     * （「回滚出的草稿明明有执行史，却仍被判从没跑过而拦下」），而被拦下的恰好就是最常失败/中断的那一批。
     * 换句话说：<b>按成功过滤不是更准，是另一种不准，而且方向上会误拦。</b>
     *
     * <p>🔴 所以修的是<b>说法</b>不是<b>数法</b>：凡是把这个数当证据展示的地方（降档警告文案、盘点报告的
     * {@code executedCount}），都必须写明它是「尝试次数（含失败与中断）」。
     *
     * @param defIds 定义编号集合；空集合直接返回空 Map（不打一条 {@code IN ()} 的废 SQL）
     * @return defId → <b>尝试</b>次数（含 FAILED / INTERRUPTED），<b>只含次数 &gt; 0 的</b>；没跑过的 id 不出现在 Map 里
     */
    default Map<Long, Long> countByDefIds(Collection<Long> defIds) {
        if (CollUtil.isEmpty(defIds)) {
            return Map.of();
        }
        List<Map<String, Object>> rows = selectMaps(new QueryWrapper<AgentWorkflowExecutionDO>()
                .select("workflow_def_id AS defId", "COUNT(*) AS cnt")
                .in("workflow_def_id", defIds)
                .groupBy("workflow_def_id"));
        Map<Long, Long> counts = new HashMap<>(rows.size());
        for (Map<String, Object> row : rows) {
            // 别名刻意不带下划线：Map 结果的键就是列标签，绕开 mapUnderscoreToCamelCase 的口径差异
            if (row.get("defId") instanceof Number defId && row.get("cnt") instanceof Number cnt) {
                counts.put(defId.longValue(), cnt.longValue());
            }
        }
        return counts;
    }

    /**
     * 取某定义名下的<b>全部</b>执行实例（迭代七 D3：管理员强制删除的级联面），按 id 升序
     *
     * <h3>🔴 与 {@link #countByDefIds} 的区别：这里要的是<b>行</b>，不是次数</h3>
     * 强制删除要拿每一行的 {@code thread_name} 去释放 checkpoint、拿 {@code status} 判是否终态、
     * 拿 {@code id} 去级联软删节点 trace —— 三件事都要逐行做，一个 COUNT 顶不了。
     * 反过来，发布闸门那条降档判据只要「跑过几次」，故那边继续用 COUNT，两者不互相替代。
     *
     * <h3>🔴 按<b>单个</b> defId 查，刻意不做成「按 code 展开全部版本」</h3>
     * 执行行存的是 {@code workflow_def_id}（启动时那一版的<b>行 id</b>，不是 code），
     * 删哪一行就只清哪一行的执行史。若按 code 展开，删 v1 会连带清掉 v2 的执行史 ——
     * 那是<b>另一个版本</b>的留痕，与本次删除无关，清掉就是把审计线索一起抹了。
     *
     * <p>租户隔离与 {@code deleted=0} 由 TenantLineInnerInterceptor 与 {@code @TableLogic} 自动拼，
     * 故这里读到的与管理页执行历史列表同口径。
     *
     * @param workflowDefId 定义<b>行</b>编号（非 code）
     * @return 执行实例列表；该定义从没跑过则为空列表（不抛异常）
     */
    default List<AgentWorkflowExecutionDO> selectListByDefId(Long workflowDefId) {
        return selectList(new LambdaQueryWrapperX<AgentWorkflowExecutionDO>()
                .eq(AgentWorkflowExecutionDO::getWorkflowDefId, workflowDefId)
                .orderByAsc(AgentWorkflowExecutionDO::getId));
    }

}
