package cn.enlev.ainb.module.agent.dal.mysql.usage;

import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.agent.dal.dataobject.usage.AgentUsageLogDO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.Data;
import java.math.BigDecimal;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Getter;
import java.util.Arrays;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 计量日志 Mapper
 *
 * 🔴 <b>刻意不提供任何删除方法。</b>计费证据不允许被删 ——
 * 靠「压根没有删除入口」保证，比靠约定或代码评审可靠。
 *
 * <p>{@code BaseMapperX} 继承来的 {@code deleteById} 等方法在技术上仍然存在，
 * 但本模块不会去调；架构测试可以在将来补一条扫描来钉死这点。
 *
 * @author 走进新时代
 */
@Mapper
public interface AgentUsageLogMapper extends BaseMapperX<AgentUsageLogDO> {

    /**
     * 清掉已过保留期的用户输入原文（方案 §16 第 6 条）
     *
     * <h3>🔴 只清 {@code input_text}，绝不删行</h3>
     * 计量字段是<b>计费证据</b>，删掉就是账上出现空洞。而 {@code input_text} 是
     * 「出示收费证据用」的原文快照，属于个人信息范畴，需要有个到期清理的口子 ——
     * 两者的保留诉求正好相反，所以这里只把原文置空，行本身与全部计量字段一个都不动。
     *
     * <p>这也是本 Mapper「刻意不提供任何删除方法」这条规矩的延续：
     * 新增的是一个 UPDATE，不是 DELETE。
     *
     * <h3>判据来自每行自己的 {@code retention_days}</h3>
     * 不写死全局天数。{@code retention_days = 0} 表示不清理（当前默认值），
     * 所以这个任务在没人显式配置保留期之前<b>一行都不会动</b> ——
     * 「先把字段留好、策略以后再定」正是 §16 第 5 条要求的后路。
     *
     * @param limit 单次处理的行数上限，避免一次锁太多行
     * @return 实际清理的行数
     */
    @Update("UPDATE agent_usage_log SET input_text = NULL "
            + "WHERE retention_days > 0 AND input_text IS NOT NULL "
            + "AND create_time < DATE_SUB(NOW(), INTERVAL retention_days DAY) "
            + "LIMIT #{limit}")
    int clearExpiredInputText(@Param("limit") Integer limit);

    /**
     * 按时间范围查本租户的记录
     */
    default List<AgentUsageLogDO> selectListByTimeRange(LocalDateTime begin, LocalDateTime end) {
        return selectList(new LambdaQueryWrapperX<AgentUsageLogDO>()
                .geIfPresent(AgentUsageLogDO::getBeginTime, begin)
                .leIfPresent(AgentUsageLogDO::getBeginTime, end)
                .orderByDesc(AgentUsageLogDO::getId));
    }

    /**
     * 按会话查
     *
     * 注意会话可能已被删除，而本表的记录仍在 —— 这正是弱引用的意义。
     */
    default List<AgentUsageLogDO> selectListByConversationId(Long conversationId) {
        return selectList(AgentUsageLogDO::getConversationId, conversationId);
    }

    /**
     * 汇总某个周期内的用量
     *
     * <h3>为什么在 SQL 里聚合而不是查出来再加</h3>
     * 配额检查在<b>每一次对话入口</b>都要跑一遍。一个活跃租户一个月能攒出几万行，
     * 全查回来在内存里求和，意味着每次提问前都要把这几万行读一遍 ——
     * 用户感觉到的是「助手越用越慢」，而且慢在发问之前，最难受的位置。
     *
     * <h3>⚠️ 统计口径含失败与中断的调用</h3>
     * 不含的话会留下一个刷额度的口子：中途取消的请求照样消耗了厂商的 token。
     *
     * @param userId 用户编号，为 null 表示统计整个租户
     * @param begin  周期起点
     * @return 汇总结果，没有记录时各项为 0
     */
    default UsageSumBO selectUsageSum(Long userId, LocalDateTime begin) {
        QueryWrapper<AgentUsageLogDO> wrapper = new QueryWrapper<AgentUsageLogDO>()
                .select("IFNULL(SUM(total_tokens), 0) AS totalTokens",
                        "IFNULL(SUM(cost), 0) AS totalCost")
                .ge("begin_time", begin);
        if (userId != null) {
            wrapper.eq("user_id", userId);
        }
        Map<String, Object> row = selectMaps(wrapper).stream().findFirst().orElse(Map.of());
        UsageSumBO result = new UsageSumBO();
        Object tokens = row.get("totalTokens");
        Object cost = row.get("totalCost");
        result.setTotalTokens(tokens == null ? 0L : ((Number) tokens).longValue());
        result.setTotalCost(cost == null ? BigDecimal.ZERO : new BigDecimal(cost.toString()));
        return result;
    }

    /**
     * 汇总某个 <b>Agent（按 code）</b> 在某周期内的用量（P7 / 待办 #6b：Agent 维度配额）
     *
     * <h3>🔴 为什么是 JOIN 归因，而不是给计费表加一列 agent_def_id</h3>
     * 用户选定「不改计费语义」——计费表 {@code agent_usage_log} 是收费证据，能不动就不动。
     * 好在归因链<b>早已存在</b>：每条计量行都带 {@code conversation_id}，而 {@code agent_conversation}
     * 带 {@code agent_def_id}，再 JOIN {@code agent_def} 取到稳定的 {@code code}。于是「某个 Agent 烧了多少」
     * 完全可从既有数据算出，无需给计费表加列、也无需在聊天热路径上多写一个字段。
     *
     * <h3>🔴 按 code 而非 id 归因：跨版本合并</h3>
     * agent_def 版本化（同 code 多行）。JOIN 到 {@code ad.code = #{code}} 能把该 Agent <b>所有版本</b>
     * 的用量合并计入本周期 —— 否则作者一发新版，旧版本的用量就从统计里漏掉，配额形同虚设。
     *
     * <h3>⚠️ 刻意不过滤 {@code c.deleted} / {@code ad.deleted}</h3>
     * 会话可被用户软删，但<b>token 已经真的烧掉了</b>。若按 {@code c.deleted=0} 过滤，用户删掉会话就能
     * 「洗掉」该 Agent 的配额占用 —— 与「中途取消照样计量」是同一条反刷额度纪律。软删的会话/定义行物理仍在，
     * JOIN 照样命中、照读其 agent_def_id/code，故用量如实计入。只对计费行自身加 {@code ul.deleted=0}（防御，
     * 该表本无删除入口）。租户隔离由 {@code TenantLineInnerInterceptor} 自动给三张表各拼 {@code tenant_id=?}；
     * 逻辑删除对原生 {@code @Select} 不自动生效，故 {@code ul.deleted} 手动写死。
     *
     * @param code  Agent 定义的 code（非 numeric id）
     * @param begin 周期起点
     * @return 汇总结果，没有记录时各项为 0
     */
    @Select("SELECT IFNULL(SUM(ul.total_tokens), 0) AS totalTokens, "
            + "IFNULL(SUM(ul.cost), 0) AS totalCost "
            + "FROM agent_usage_log ul "
            + "JOIN agent_conversation c ON ul.conversation_id = c.id "
            + "JOIN agent_def ad ON c.agent_def_id = ad.id "
            + "WHERE ad.code = #{code} AND ul.begin_time >= #{begin} AND ul.deleted = 0")
    UsageSumBO selectUsageSumByAgent(@Param("code") String code, @Param("begin") LocalDateTime begin);

    /**
     * 用量汇总
     */
    @Data
    class UsageSumBO {

        private Long totalTokens;
        private BigDecimal totalCost;

    }

    /**
     * 按某个维度分组汇总
     *
     * <h3>为什么分组列是<b>枚举出来的</b>而不是直接拼字符串</h3>
     * 这个方法的入参最终会进 SQL 的 GROUP BY。让调用方传任意字符串，
     * 等于开了一个注入口子 —— 而且是在一个<b>管钱的</b>查询上。
     * 所以调用方只能从 {@link GroupDimension} 里选，拼进 SQL 的是枚举自带的列名，
     * 永远不是外部输入。
     *
     * @param dimension 分组维度
     * @param begin     起始时间
     * @param end       结束时间
     * @param userId    限定用户，为 null 表示不限
     * @return 分组汇总，按 token 降序
     */
    default List<Map<String, Object>> selectUsageGroupBy(GroupDimension dimension,
                                                         LocalDateTime begin, LocalDateTime end,
                                                         Long userId) {
        QueryWrapper<AgentUsageLogDO> wrapper = new QueryWrapper<AgentUsageLogDO>()
                .select(dimension.getSelectExpr() + " AS groupKey",
                        "COUNT(*) AS callCount",
                        "IFNULL(SUM(prompt_tokens), 0) AS promptTokens",
                        "IFNULL(SUM(completion_tokens), 0) AS completionTokens",
                        "IFNULL(SUM(total_tokens), 0) AS totalTokens",
                        "IFNULL(SUM(cost), 0) AS totalCost")
                .ge(begin != null, "begin_time", begin)
                .le(end != null, "begin_time", end)
                .groupBy(dimension.getGroupExpr())
                .orderByDesc("totalTokens");
        if (userId != null) {
            wrapper.eq("user_id", userId);
        }
        return selectMaps(wrapper);
    }

    /**
     * 可用的分组维度
     *
     * 列名是写死在枚举里的常量，不接受外部输入 —— 见 {@link #selectUsageGroupBy}。
     */
    @Getter
    @AllArgsConstructor
    enum GroupDimension {

        /** 按用户 */
        USER("user_id", "user_id"),
        /** 按场景：CHAT / SUGGESTION / TOOL_LOOP / VALIDATE_RETRY / EMBEDDING / INTENT */
        SCENE("scene", "scene"),
        /** 按模型 */
        MODEL("model_name", "model_name"),
        /** 按天 */
        DAY("DATE(begin_time)", "DATE(begin_time)"),
        ;

        /** SELECT 里的表达式 */
        private final String selectExpr;
        /** GROUP BY 里的表达式 */
        private final String groupExpr;

        public static GroupDimension parse(String value) {
            return Arrays.stream(values())
                    .filter(item -> item.name().equalsIgnoreCase(value))
                    .findFirst()
                    // 认不出来时按场景分组：它是最能解释「这些 token 花在哪了」的维度，
                    // 也是方案 §8.3 强调必须分得清的那一个
                    .orElse(SCENE);
        }
    }

}
