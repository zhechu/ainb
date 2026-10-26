package cn.enlev.ainb.module.system.service.permission;

import cn.enlev.ainb.module.system.controller.admin.permission.vo.navnode.NavNodeListReqVO;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.navnode.NavNodeSaveVO;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.navnode.NavNodeSortReqVO;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.navnode.NavNodeTreeRespVO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.NavNodeDO;

import java.util.List;
import java.util.Set;

/**
 * 分端导航节点 Service 接口
 *
 * <p>维护 {@code system_nav_node}（每端一棵独立导航树），并对外提供
 * 「按当前用户授权过滤后的导航树」（移动端消费）与 admin CRUD（「移动端导航」编辑器消费）。
 * 设计见「移动端优化方案」§4.4.3
 *
 * @author 走进新时代
 */
public interface NavNodeService {

    /**
     * 创建导航节点
     *
     * @param createReqVO 节点信息
     * @return 创建出来的节点编号
     */
    Long createNavNode(NavNodeSaveVO createReqVO);

    /**
     * 更新导航节点
     *
     * @param updateReqVO 节点信息
     */
    void updateNavNode(NavNodeSaveVO updateReqVO);

    /**
     * 删除导航节点
     *
     * @param id 节点编号
     */
    void deleteNavNode(Long id);

    /**
     * 批量删除导航节点
     *
     * @param ids 节点编号数组
     */
    void deleteNavNodeList(List<Long> ids);

    /**
     * 批量更新排序 / 父节点（拖拽排序用），同一事务落库
     *
     * @param list 待更新的 (id, parentId, sort) 列表
     */
    void updateNavNodeSort(List<NavNodeSortReqVO> list);

    /**
     * 获得导航节点
     *
     * @param id 节点编号
     * @return 节点
     */
    NavNodeDO getNavNode(Long id);

    /**
     * 筛选导航节点列表（admin 编辑器用，不做授权过滤）
     *
     * @param reqVO 筛选条件请求 VO
     * @return 节点列表
     */
    List<NavNodeDO> getNavNodeList(NavNodeListReqVO reqVO);

    /**
     * 获得某一端、按授权过滤后的导航树（移动端工作台消费）
     *
     * <p>过滤规则：{@code status} 正常、{@code visible=1}、且（{@code permissions} 为空
     * <b>或</b>其任一命中 {@code allowedPermissions}）；分组 / 子分组节点<b>无可见子节点则级联隐藏</b>
     *
     * @param terminal           端标识，见 {@link cn.enlev.ainb.module.system.enums.permission.NavTerminalEnum}
     * @param allowedPermissions 当前登录用户的授权权限串集合（与 {@code get-permission-info} 同源）
     * @return 过滤 + 建树后的导航树
     */
    List<NavNodeTreeRespVO> getNavTree(String terminal, Set<String> allowedPermissions);

}
