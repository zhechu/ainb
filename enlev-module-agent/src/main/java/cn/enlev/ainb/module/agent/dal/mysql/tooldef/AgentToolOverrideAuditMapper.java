package cn.enlev.ainb.module.agent.dal.mysql.tooldef;

import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.module.agent.dal.dataobject.tooldef.AgentToolOverrideAuditDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 内置工具「安全放松」审计 Mapper（2026-10-05 增量，B1 / S11）
 *
 * <p>🔴 刻意只有 {@code insert}（继承自 {@link BaseMapperX}），不提供 update / delete 的便捷方法：
 * 审计行的价值全在「事后不可被当事人改写」。要查就直接写 SQL —— 这张表的读者是复核安全取舍的人，
 * 不是业务页面，没有分页列表的需求；真要做审计查询页时再按那时的口径加方法，别先猜。
 *
 * <p>租户隔离：本表是 {@code TenantBaseDO}，故租户 A 的放松痕迹不会出现在租户 B 的查询里。
 * 这与被审计的对象（{@code agent_tool_override}，同样租户级）口径一致 ——
 * A 租户放松一个工具不影响 B 租户的生效值，也就不该出现在 B 的审计视野里。
 *
 * @author 走进新时代
 */
@Mapper
public interface AgentToolOverrideAuditMapper extends BaseMapperX<AgentToolOverrideAuditDO> {

}
