package cn.enlev.ainb.module.agent.dal.mysql.tooldef;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.agent.controller.admin.tooldef.vo.AgentToolDefPageReqVO;
import cn.enlev.ainb.module.agent.dal.dataobject.tooldef.AgentToolDefDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 用户自定义工具定义 Mapper（增量5a）
 *
 * <p>🔴 租户隔离：本表是 {@code TenantBaseDO}，TenantLineInnerInterceptor 自动给每条查询拼
 * {@code tenant_id=?}。故 {@link #selectByToolKey} 只会命中<b>当前租户</b>的工具——
 * 网关/执行器回落解析在调用方租户上下文里跑，租户 A 的工具绝不会被租户 B 解析到。
 *
 * @author 走进新时代
 */
@Mapper
public interface AgentToolDefMapper extends BaseMapperX<AgentToolDefDO> {

    /**
     * 按 tool_key 查当前租户的工具（网关/执行器回落解析、登记唯一性校验用）
     *
     * <p>软删行被 MP 逻辑删自动过滤（deleted=0），故返回的是唯一在册行。
     */
    default AgentToolDefDO selectByToolKey(String toolKey) {
        return selectOne(AgentToolDefDO::getToolKey, toolKey);
    }

    /**
     * 按来源查当前租户的工具列表（设计器/清单用）
     */
    default List<AgentToolDefDO> selectListBySource(String source) {
        return selectList(new LambdaQueryWrapperX<AgentToolDefDO>()
                .eq(AgentToolDefDO::getSource, source)
                .orderByAsc(AgentToolDefDO::getId));
    }

    /**
     * 查当前租户<b>启用中</b>的全部自定义工具（清单/校验/planner 提示词用）
     *
     * <p>只取启用行：停用工具不该出现在设计器下拉里，否则用户选得到一个网关必拒的键
     * （{@code AGENT_TOOL_DISABLED}）——那是「配了但跑不通」的一种。
     * 执行路径的启用判定仍在 {@code AgentSecurityGateway}，这里只是不把死路摆到用户面前。
     *
     * <p>🔴 <b>不要</b>拿本方法给名称目录用，见 {@link #selectListIncludingDisabled()}。
     */
    default List<AgentToolDefDO> selectEnabledList() {
        return selectList(new LambdaQueryWrapperX<AgentToolDefDO>()
                .eq(AgentToolDefDO::getEnabled, true)
                .orderByAsc(AgentToolDefDO::getToolKey));
    }

    /**
     * 查当前租户的<b>全部</b>自定义工具（含停用行），仅名称目录合流用
     *
     * <p>🔴 为什么目录必须看到停用行（D4-27）：目录的职责是「机器键 → 人能读的名字」，
     * 而<b>名字与启用状态无关</b>。用 {@link #selectEnabledList()} 喂目录的后果是停用的自定义工具
     * 从目录里<b>整个消失</b>，于是：
     * <ul>
     *   <li>存量 DSL 里那个键翻不出名字，前端 {@code el-select} 的 {@code getOption} 回落到
     *       {@code currentLabel = value} —— 画布上从「销售日报」退化成一串裸机器键；</li>
     *   <li>{@code allow-create} 还允许把它重新敲回去，敲回去之后<b>一个警告都没有</b>。</li>
     * </ul>
     * 内置工具走的是另一条路（{@code AgentToolRegistry.list()} 本来就返回全部，靠 {@code enabled}
     * 字段让前端置灰），所以同一个「停用」动作在两类工具上处置不一致 —— 这正是要修的东西。
     *
     * <p>🔴 反过来，<b>不要</b>把清单/校验/planner 提示词也换成本方法：那三处问的是
     * 「这个键会不会真的被调到」，停用行调用必被网关拒，把它当已知工具会让清单报一项
     * 根本不会发生的变更、让 planner 编出一个必然失败的步骤。两处问题不同，故数据源不同。
     *
     * <p>排序与 {@link #selectEnabledList()} 同（工具键升序），保证同一份数据的目录输出确定。
     */
    default List<AgentToolDefDO> selectListIncludingDisabled() {
        return selectList(new LambdaQueryWrapperX<AgentToolDefDO>()
                .orderByAsc(AgentToolDefDO::getToolKey));
    }

    default PageResult<AgentToolDefDO> selectPage(AgentToolDefPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<AgentToolDefDO>()
                .likeIfPresent(AgentToolDefDO::getToolKey, reqVO.getToolKey())
                .eqIfPresent(AgentToolDefDO::getSource, reqVO.getSource())
                .eqIfPresent(AgentToolDefDO::getGroupName, reqVO.getGroupName())
                .eqIfPresent(AgentToolDefDO::getEnabled, reqVO.getEnabled())
                .orderByDesc(AgentToolDefDO::getId));
    }

}
