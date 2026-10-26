package cn.enlev.ainb.module.agent.dal.mysql.def;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.agent.controller.admin.def.vo.AgentDefPageReqVO;
import cn.enlev.ainb.module.agent.dal.dataobject.def.AgentDefDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 定义 Mapper —— P5 Agent Builder 第一刀
 *
 * @author 走进新时代
 */
@Mapper
public interface AgentDefMapper extends BaseMapperX<AgentDefDO> {

    /**
     * 管理页分页：按 code/name 模糊、status 精确过滤
     *
     * <p>排序 {@code version DESC, id DESC}：同一 code 的多版本里新版在前，整体又按新建倒序，
     * 让业务人员一眼看到「每个 Agent 的最新版本」（与 {@code AgentWorkflowDefMapper.selectPage} 同纪律）。
     * 租户隔离由 {@code TenantBaseDO} + TenantLineInnerInterceptor 自动拼 {@code tenant_id=?} 保证，
     * A 租户绝查不到 B 租户的定义。
     */
    default PageResult<AgentDefDO> selectPage(AgentDefPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<AgentDefDO>()
                .likeIfPresent(AgentDefDO::getCode, reqVO.getCode())
                .likeIfPresent(AgentDefDO::getName, reqVO.getName())
                .eqIfPresent(AgentDefDO::getStatus, reqVO.getStatus())
                .orderByDesc(AgentDefDO::getVersion)
                .orderByDesc(AgentDefDO::getId));
    }

    /**
     * 「这个 Agent 业务编码存不存在」的<b>唯一判据</b>
     *
     * <p>两个消费方：发布闸门 {@code AgentWorkflowDefServiceImpl#validateChatBinding}
     * （读投影列 {@code trigger_key}）与 DSL 校验器 {@code WorkflowDslValidator} 的 V28
     * （读 {@code trigger.chat.config.agentId}）。它们看的是<b>两个不同的字段</b>、
     * 但问的是同一句话，故那句话只许有一个实现 —— 两份必然漂移，而漂了的表现是
     * 「盘点说这行 clean、点发布却被拒」，正是那份闸门 javadoc 声明「必须是结构保证，不是巧合」的同一性。
     *
     * <h3>🔴 只问存在，不问状态（DRAFT / DISABLED 都算存在）</h3>
     * Agent 可能随后才发布，强要求 PUBLISHED 会引入更硬的发布顺序耦合，
     * 把「先起流程、后建 Agent」这条正常编辑顺序堵死。理由写在这一份上，两个消费方都不再各写一遍。
     *
     * <h3>🔴 为什么落在 Mapper 而不是 {@code AgentDefService}</h3>
     * 两个消费方都够不到 service 层而不成环：{@code AgentWorkflowDefServiceImpl} 本就按
     * 「注 Mapper 不注 Service」的纪律办事（它已被执行侧与 Agent 侧反向依赖），
     * 而校验器经 {@code AgentDefServiceImpl → AgentToolRegistry → 全部工具 bean} 这条链
     * 也可能绕回注入它的宿主。Mapper 不依赖任何东西，放这儿结构上不可能成环。
     *
     * @param code Agent 业务编码（{@code agent_def.code}，版本稳定键，<b>不是</b>数字 id）
     */
    default boolean existsByCode(String code) {
        return selectCount(AgentDefDO::getCode, code) > 0;
    }

}
