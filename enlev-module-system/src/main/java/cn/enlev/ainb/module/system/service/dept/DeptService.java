package cn.enlev.ainb.module.system.service.dept;

import cn.enlev.ainb.framework.common.util.collection.CollectionUtils;
import cn.enlev.ainb.module.system.controller.admin.dept.vo.dept.DeptListReqVO;
import cn.enlev.ainb.module.system.controller.admin.dept.vo.dept.DeptSaveReqVO;
import cn.enlev.ainb.module.system.dal.dataobject.dept.DeptDO;

import java.util.*;

/**
 * 组织 Service 接口
 *
 * @author 芋道源码
 */
public interface DeptService {

    /**
     * 创建组织
     *
     * @param createReqVO 组织信息
     * @return 组织编号
     */
    Long createDept(DeptSaveReqVO createReqVO);

    /**
     * 更新组织
     *
     * @param updateReqVO 组织信息
     */
    void updateDept(DeptSaveReqVO updateReqVO);

    /**
     * 更新组织状态。只改 status 一个字段
     *
     * 为什么不复用 {@link #updateDept}：那条路径上 code 是必填的，而存量组织行的 code 是 NULL
     * （刻意不回填，见 {@link DeptDO#code}），整行提交会被 Bean Validation 拦成 400 ——
     * 列表页的状态快捷开关正是走这条路，加了必填字段之后就直接点不动了
     *
     * @param id     组织编号
     * @param status 状态，见 CommonStatusEnum
     */
    void updateDeptStatus(Long id, Integer status);

    /**
     * 删除组织
     *
     * @param id 组织编号
     */
    void deleteDept(Long id);

    /**
     * 批量删除组织
     *
     * @param ids 组织编号数组
     */
    void deleteDeptList(List<Long> ids);

    /**
     * 获得组织信息
     *
     * @param id 组织编号
     * @return 组织信息
     */
    DeptDO getDept(Long id);

    /**
     * 获得组织信息数组
     *
     * @param ids 组织编号数组
     * @return 组织信息数组
     */
    List<DeptDO> getDeptList(Collection<Long> ids);

    /**
     * 筛选组织列表
     *
     * @param reqVO 筛选条件请求 VO
     * @return 组织列表
     */
    List<DeptDO> getDeptList(DeptListReqVO reqVO);

    /**
     * 获得指定编号的组织 Map
     *
     * @param ids 组织编号数组
     * @return 组织 Map
     */
    default Map<Long, DeptDO> getDeptMap(Collection<Long> ids) {
        List<DeptDO> list = getDeptList(ids);
        return CollectionUtils.convertMap(list, DeptDO::getId);
    }

    /**
     * 获得指定组织的所有子组织
     *
     * @param id 组织编号
     * @return 子组织列表
     */
    default List<DeptDO> getChildDeptList(Long id) {
        return getChildDeptList(Collections.singleton(id));
    }

    /**
     * 获得指定组织的所有子组织
     *
     * @param ids 组织编号数组
     * @return 子组织列表
     */
    List<DeptDO> getChildDeptList(Collection<Long> ids);

    /**
     * 获得指定组织的所有父组织
     *
     * 按直属父组织到根组织的顺序返回，不包含指定组织自身。
     *
     * @param id 组织编号
     * @return 父组织列表
     */
    List<DeptDO> getParentDeptList(Long id);

    /**
     * 获得指定领导者的组织列表
     *
     * @param id 领导者编号
     * @return 组织列表
     */
    List<DeptDO> getDeptListByLeaderUserId(Long id);

    /**
     * 获得所有子组织，从缓存中
     *
     * @param id 父组织编号
     * @return 子组织列表
     */
    Set<Long> getChildDeptIdListFromCache(Long id);

    /**
     * 处理用户被删除：清空该用户担任组织负责人的引用
     *
     * 为什么必须清：组织负责人是 BPM「部门的负责人」「发起人部门负责人」等审批策略的取人依据
     * （策略名引自 BpmTaskCandidateStrategyEnum —— BPM 侧仍用「部门」称谓，本次改名不动它，
     *   所以这里照抄它的原文，否则读者按引号里的字去搜会搜不到）。
     * 留着指向已删除用户的负责人，审批节点算出来的候选人会被过滤成空，
     * 任务以「没有负责人」的形态创建出来，待办列表里谁都没有 —— 既不报错也没提示，
     * 只能等有人发现流程卡死。所以删用户时就把这个悬空引用抹掉，让「该组织暂无负责人」变成看得见的状态。
     *
     * @param userId 被删除的用户编号
     */
    void processUserDeleted(Long userId);

    /**
     * 校验组织们是否有效。如下情况，视为无效：
     * 1. 组织编号不存在
     * 2. 组织被禁用
     *
     * @param ids 角色编号数组
     */
    void validateDeptList(Collection<Long> ids);

}
