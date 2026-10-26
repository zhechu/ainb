package cn.enlev.ainb.module.system.service.permission;

import cn.enlev.ainb.module.system.controller.admin.permission.vo.grant.GrantBatchSaveRespVO;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.grant.GrantSaveReqVO;

import java.util.List;

/**
 * 授权行（sys_grant）的管理侧写操作 Service
 *
 * <h3>为什么抽这一层（此前 GrantAdminController 直接操作 Mapper）</h3>
 *
 * 引入 upsert 之后，写路径变成「查既有行 → 判能否收敛 → 更新/插入/软删」的多步事务，
 * {@code @DSTransactional} 按仓库惯例落在 Service 层（先例
 * {@code PermissionServiceImpl#syncRoleMenuGrants}）；且本仓没有任何 {@code *AdminControllerTest}
 * 基建，抽出来才能套 {@code PermissionServiceTest} 的 {@code BaseDbUnitTest} 模板，
 * 对真实 H2 把 upsert 的每个分支测一遍 —— 在 Controller 上测等于另发明一套。
 *
 * <h3>读路径不进这里</h3>
 *
 * 鉴权走 {@link GrantDecisionService}；授权管理页的查询仍在 Controller 里直查 Mapper。
 * 本接口<b>只有写操作</b>，别把查询也塞进来，否则会与那两条路径形成第三份口径。
 *
 * @author 走进新时代
 */
public interface GrantAdminService {

    /**
     * 新增授权行（<b>upsert 语义</b>）
     *
     * 同键（主体 + 资源 + 动作 + 上下文）已有行时<b>覆盖</b>该行，而不是再插一行 ——
     * {@code sys_grant} 上没有唯一索引，旧行为是「点几次新增就插几行」，
     * 实测库里已经积了 15 组这样的重复行。重复行对鉴权是幂等噪声
     * （{@code GrantDecisionEngine#isAllowed} 全用 anyMatch），但会让列表页与人工排查越来越浑，
     * 且同键下 effect 一旦不一致就变成安全问题（任意一行 DENY 一票否决所有 ALLOW）
     *
     * @return 新插入行的 id，或被覆盖的那个存活行（同键多行时取 min(id)）的 id
     */
    Long upsertGrant(GrantSaveReqVO reqVO);

    /**
     * 批量新增授权行（逐项 upsert）
     *
     * <b>全有或全无</b>：任意一项校验不过（资源不存在 / 批内键重复 / 同键异内容无法收敛），
     * 整批一行都不落库。部分成功会让前端拿到「加了几条、哪几条没加不知道」的中间态，
     * 用户无从判断该重选哪些
     *
     * @param items 与单条 {@link #upsertGrant} 完全同构的授权项列表
     * @return 新增 / 覆盖 / 收敛三个计数
     */
    GrantBatchSaveRespVO createGrantList(List<GrantSaveReqVO> items);

    /**
     * 修改授权行
     *
     * 校验口径与 upsert 对齐：id 必填、行必须存在、资源必须存在
     */
    void updateGrant(GrantSaveReqVO reqVO);

    /**
     * 批量删除授权行
     *
     * <b>全有或全无</b>：任意一个 id 不存在则整批不删。
     * 空列表幂等成功（与 {@code ResourceAdminController#deleteResourceList} 同口径）——
     * 删除的 ids 来自表格勾选状态，翻页/重载竞态可能把选择清空，静默成功是对的
     */
    void deleteGrantList(List<Long> ids);

}
