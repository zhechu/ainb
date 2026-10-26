package cn.enlev.ainb.framework.common.biz.bpm;

import java.util.Set;

/**
 * 流程参与关系的跨模块查询接口
 *
 * <h3>为什么要有这条通道</h3>
 *
 * 派生可见性（v9 §4.3）要回答的是：「这个人参与过哪些流程，
 * 因此该看到业务表的哪几行」。问题在于两头分属不同模块 ——
 * 参与关系在 {@code enlev-module-bpm}，而行级过滤的求值在
 * {@code enlev-module-system}，**而 system 不依赖 bpm**。
 *
 * 所以接口定义在 common，由 bpm 实现、system 通过 {@code ObjectProvider} 取用。
 * 与 {@code PermissionCommonApi}（system 实现、infra 取用）是同一条路数，
 * 方向相反而已。
 *
 * @author 走进新时代
 */
public interface BpmParticipantCommonApi {

    /**
     * 查某个用户在某张表单下参与过的全部业务主键
     *
     * <h3>⚠️ 返回空集合与返回 null 的含义不同</h3>
     *
     * <ul>
     *   <li><b>空集合</b> —— 查到了，但他一条都没参与过。
     *       调用方应当据此过滤成「一行都看不到」，<b>而不是不过滤</b></li>
     *   <li><b>null</b> —— 查不了（模块不可用）。调用方自行决定回落策略</li>
     * </ul>
     *
     * 这个区分是必须的：把「没参与过任何流程」误当成「无需过滤」，
     * 结果就是给了他全表可见 —— 配了规则反而比不配还宽
     *
     * @param userId   用户编号
     * @param formCode 表单编码；为空表示不限表单
     * @return 业务主键集合；模块不可用时返回 null
     */
    Set<String> getParticipatedBusinessKeys(Long userId, String formCode);

}
