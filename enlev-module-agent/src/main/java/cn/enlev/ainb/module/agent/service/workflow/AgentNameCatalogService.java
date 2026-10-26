package cn.enlev.ainb.module.agent.service.workflow;

import cn.enlev.ainb.module.agent.controller.admin.workflow.vo.AgentNameCatalogRespVO;

/**
 * 名称目录 Service（重构 S1 / L1 名称目录层）
 *
 * <p>把散在各处的「机器键 → 中文名」映射收敛成一个只读出口，供流程编排设计器、只读画布、
 * 聊天草稿卡、执行历史抽屉统一取用。字段取舍与权限理由见 {@link AgentNameCatalogRespVO} 类注释。
 *
 * <h3>🔴 只读、无缓存</h3>
 * 刻意<b>不</b>加缓存（方案初稿写的是「租户级 60s」，落地时去掉了）。理由很直接：本模块反复出现的
 * 头号缺陷是「配置保存成功但不生效且无提示」，而一个 60 秒的名称目录缓存恰好会造出它的一个新实例 ——
 * 管理员把工具展示名改完，设计器最长一分钟仍显示旧名，且没有任何提示。
 * 代价只是页面打开时多跑几条查询（工具合流 1 条 + 已发布流程 1 条 + 控件契约走内存注册表 +
 * 操作符是枚举常量），这个端点只在页面加载时调一次，不在任何循环或按键路径上。
 *
 * @author 走进新时代
 */
public interface AgentNameCatalogService {

    /**
     * 获得名称目录（当前租户视角）
     *
     * <p>🔴 在调用方的租户上下文里跑：自定义工具与已发布流程都是租户级数据，
     * {@code TenantLineInnerInterceptor} 自动拼 {@code tenant_id=?}；内置工具的生效值
     * （展示名/分组/风险/启用）由 {@code AgentToolRegistry} 按当前租户的覆盖行现算。
     * 无租户上下文时自定义工具与流程为空、内置工具回落声明值 —— 不抛异常。
     *
     * @return 名称目录，各字段一律非 null（无数据时是空集合/空 map）
     */
    AgentNameCatalogRespVO getNameCatalog();

}
