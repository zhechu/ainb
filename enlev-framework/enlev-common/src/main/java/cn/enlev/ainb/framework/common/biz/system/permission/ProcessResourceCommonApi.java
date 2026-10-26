package cn.enlev.ainb.framework.common.biz.system.permission;

import java.util.Collection;

/**
 * 流程相关资源的登记通道
 *
 * <h3>为什么要有这条通道</h3>
 *
 * 流程发布时要把「这个流程」「它的每个审批节点」登记成可授权对象，
 * 而资源表在 {@code enlev-module-system}，发布动作在 {@code enlev-module-bpm} ——
 * bpm 依赖 system 的 api 层，所以接口放 common、由 system 实现。
 * 与 {@code PermissionCommonApi} 同一条路数。
 *
 * <h3>⚠️ 不登记的后果</h3>
 *
 * 节点资源不存在时，字段模式求值里的 {@code contextId} 解析为 null，
 * 于是<b>退回全局判定</b> —— 在流程设计器里给某个节点配的字段权限
 * 「配了等于没配」，且不报错、界面正常。
 * 这正是 S6d 修的那个缺陷，而它的根在「没人登记节点资源」。
 *
 * @author 走进新时代
 */
public interface ProcessResourceCommonApi {

    /**
     * 登记一个流程及其审批节点
     *
     * <p>幂等：同一流程反复发布，结果收敛到同一份资源，不会越积越多。</p>
     *
     * <h3>⚠️ 节点资源只增不删</h3>
     *
     * 流程改版删掉某个节点时，<b>不回收</b>它的资源行。原因：
     * 历史流程实例仍停在那个节点上，回收了的话它们的节点级字段权限会立刻失效，
     * 而那是静默的（退回全局判定，字段可能因此多显示）。
     * 清理留给归档阶段统一处理。
     *
     * @param processDefinitionKey 流程定义 key（<b>不是</b> definitionId ——
     *                             后者每次部署都变，拿它当资源标识的话，
     *                             流程一改一部署，之前配的授权就全指向了没人用的旧版本）
     * @param processName          流程名称
     * @param categoryCode         流程分类编码，用于挂父资源实现分类继承；可为空
     * @param nodes                审批节点：节点 key → 节点名称
     * @return 本次新建的资源数
     */
    int registerProcess(String processDefinitionKey, String processName, String categoryCode,
                        Collection<NodeDescriptor> nodes);

    /**
     * 把流程定义上的三个 ID 列表<b>投影成授权</b>（S7b / TODO #30）
     *
     * <h3>为什么是「投影」而不是「迁移」</h3>
     *
     * S5d 做的是一次性迁移脚本，判定处写成「授权 OR 旧列表」。
     * 那条 OR 摘不掉，因为前端仍然在写这三个字段 ——
     * 摘了之后管理员在流程设计器里配的发起范围<b>就再也不生效了</b>，
     * 而界面上一切正常。那是本项目最典型的缺陷形态。
     *
     * 所以改成：<b>每次部署都把这三个列表同步成授权</b>。
     * 三个字段退化为「编辑界面」，{@code sys_grant} 成为唯一判据。
     * 前端一行不用改，判定处的 OR 可以安全摘掉。
     *
     * <h3>「不限定」也要落成一条授权</h3>
     *
     * 两个发起范围都为空时，旧语义是「所有人都可以发起」。
     * 流程资源的 {@code default_effect} 是 DENY，
     * 所以这件事必须表达成一条给 {@code ALL:all-users} 的 START 授权 ——
     * 靠「没有授权」是表达不出「人人可发起」的，那只会变成「谁都发不了」。
     *
     * <h3>⚠️ 只覆盖自己写的那些行</h3>
     *
     * 同步时只删除本通道自己创建的授权（按 creator 标记），
     * 管理员在授权界面上手工配的一律不动 —— 否则每次重新部署流程，
     * 管理员配了半天的授权就被静默清空了。
     *
     * @param processDefinitionKey 流程定义 key
     * @param startUserIds         可发起的用户编号；空表示不按用户限定
     * @param startDeptIds         可发起的部门编号；空表示不按部门限定
     * @param managerUserIds       流程管理员的用户编号
     * @return 本次写入的授权数
     */
    int syncProcessAuthorization(String processDefinitionKey,
                                 Collection<Long> startUserIds,
                                 Collection<Long> startDeptIds,
                                 Collection<Long> managerUserIds);

    /**
     * 审批节点描述
     *
     * @param nodeKey  BPMN XML 里的节点 id
     * @param nodeName 节点名称
     */
    record NodeDescriptor(String nodeKey, String nodeName) {
    }

}
