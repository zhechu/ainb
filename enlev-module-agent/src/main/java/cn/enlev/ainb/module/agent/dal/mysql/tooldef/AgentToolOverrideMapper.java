package cn.enlev.ainb.module.agent.dal.mysql.tooldef;

import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.agent.dal.dataobject.tooldef.AgentToolOverrideDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 内置工具覆盖值 Mapper（2026-09-28 增量）
 *
 * <p>🔴 租户隔离：本表是 {@code TenantBaseDO}，TenantLineInnerInterceptor 自动拼 {@code tenant_id=?}，
 * 故各租户的覆盖互不串味（租户 A 把某工具停了不影响租户 B）。
 *
 * <h3>🔴 两个查询都带「最新在前」的排序，这不是排版偏好（D4-23，2026-10-05）</h3>
 * 本表只有非唯一的 {@code idx_tenant_tool(tenant_id, tool_name)}，而唯一索引在这张表上<b>建不起来</b>：
 * {@code deleted} 是 {@code @TableLogic} 的软删标记，「恢复默认」再「重新维护」会留下一行墓碑继续占着同一个
 * {@code (tenant_id, tool_name)}，加 UNIQUE 会让第二次维护直接撞键 —— 实测本仓 {@code datamodel_create}
 * 一个工具名下已经积了 28 行墓碑，{@code UNIQUE(tenant_id, tool_name, deleted)} 也在第 3 行就撞。
 *
 * <p>于是「同一租户同一工具名下有多行 live」是<b>数据库允许、并发保存真能走到</b>的状态
 * （两个管理员同时点保存，或前端重复提交，两边都读到「还没有覆盖行」而双双 insert）。
 * 既然拦不住，就只能规定<b>多行时哪一行算数</b>，而这个规定只能落在 ORDER BY 上：
 * 没有它，「哪一行生效」就等于 MySQL 当次的扫描顺序，跨版本、跨执行计划都会变。
 * 服务层的三处读快照与自愈都按「最新那条」收敛，口径必须与这里一致，否则
 * <b>自愈这个动作本身就会悄悄改掉工具的生效配置</b>（没人做过维护，工具行为却变了）。
 *
 * <p>MySQL 的 {@code DESC} 把 NULL 排在最后，故 {@code update_time} 为空的行自然被当成最旧。
 *
 * @author 走进新时代
 */
@Mapper
public interface AgentToolOverrideMapper extends BaseMapperX<AgentToolOverrideDO> {

    /**
     * 按内置工具名查当前租户的<b>全部</b> live 覆盖行，最新的排在最前（软删行被 MP 逻辑删自动过滤）。
     *
     * <p>🔴 返回列表而不是单行，是 D4-23 的修复本体，不是顺手改的签名。
     * 旧的 {@code selectOne} 版本在多行时<b>永久</b>抛 {@code TooManyResultsException}，
     * 而它的两个调用方是 {@code update} 与 {@code reset} —— 也就是说「保存」坏了之后，
     * 界面上那个「恢复默认」的逃生门<b>也一起坏了</b>，管理员在 UI 里没有任何办法把这个工具救回来，
     * 只能改库。列表 + 服务层自愈才是能让功能自己爬回来的形状。
     *
     * <p>{@code update_time DESC} 取「最近维护过的那行」，{@code id DESC} 是决胜局：
     * 并发 insert 出来的两行 {@code update_time} 可能同到毫秒，此时保留后写入的那行。
     */
    default List<AgentToolOverrideDO> selectListByToolName(String toolName) {
        return selectList(new LambdaQueryWrapperX<AgentToolOverrideDO>()
                .eq(AgentToolOverrideDO::getToolName, toolName)
                .orderByDesc(AgentToolOverrideDO::getUpdateTime)
                .orderByDesc(AgentToolOverrideDO::getId));
    }

    /**
     * 当前租户的全部覆盖行，每个工具名下<b>最新的排在最前</b>（启动装配 + 管理页合并视图用；
     * 规模=内置工具数，无需分页）。排序理由见类注释。
     */
    default List<AgentToolOverrideDO> selectListAll() {
        return selectList(new LambdaQueryWrapperX<AgentToolOverrideDO>()
                .orderByDesc(AgentToolOverrideDO::getUpdateTime)
                .orderByDesc(AgentToolOverrideDO::getId));
    }

}
