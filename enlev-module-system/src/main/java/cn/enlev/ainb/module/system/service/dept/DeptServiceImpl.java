package cn.enlev.ainb.module.system.service.dept;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.framework.datapermission.core.annotation.DataPermission;
import cn.enlev.ainb.module.system.controller.admin.dept.vo.dept.DeptListReqVO;
import cn.enlev.ainb.module.system.controller.admin.dept.vo.dept.DeptSaveReqVO;
import cn.enlev.ainb.module.system.dal.dataobject.dept.DeptDO;
import cn.enlev.ainb.module.system.dal.mysql.dept.DeptMapper;
import cn.enlev.ainb.module.system.dal.redis.RedisKeyConstants;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import jakarta.annotation.Resource;
import java.util.*;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.framework.common.util.collection.CollectionUtils.convertSet;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.*;

/**
 * 组织 Service 实现类
 *
 * @author 芋道源码
 */
@Service
@Validated
@Slf4j
public class DeptServiceImpl implements DeptService {

    @Resource
    private DeptMapper deptMapper;

    @Override
    @CacheEvict(cacheNames = RedisKeyConstants.DEPT_CHILDREN_ID_LIST,
            allEntries = true) // allEntries 清空所有缓存，因为操作一个组织，涉及到多个缓存
    public Long createDept(DeptSaveReqVO createReqVO) {
        if (createReqVO.getParentId() == null) {
            createReqVO.setParentId(DeptDO.PARENT_ID_ROOT);
        }
        // 校验父组织的有效性
        validateParentDept(null, createReqVO.getParentId());
        // 校验组织名的唯一性
        validateDeptNameUnique(null, createReqVO.getParentId(), createReqVO.getName());
        // 校验组织编码的唯一性
        validateDeptCodeUnique(null, createReqVO.getCode());

        // 插入组织
        DeptDO dept = BeanUtils.toBean(createReqVO, DeptDO.class);
        deptMapper.insert(dept);
        return dept.getId();
    }

    @Override
    @CacheEvict(cacheNames = RedisKeyConstants.DEPT_CHILDREN_ID_LIST,
            allEntries = true) // allEntries 清空所有缓存，因为操作一个组织，涉及到多个缓存
    public void updateDept(DeptSaveReqVO updateReqVO) {
        if (updateReqVO.getParentId() == null) {
            updateReqVO.setParentId(DeptDO.PARENT_ID_ROOT);
        }
        // 校验自己存在
        validateDeptExists(updateReqVO.getId());
        // 校验父组织的有效性
        validateParentDept(updateReqVO.getId(), updateReqVO.getParentId());
        // 校验组织名的唯一性
        validateDeptNameUnique(updateReqVO.getId(), updateReqVO.getParentId(), updateReqVO.getName());
        // 校验组织编码的唯一性
        validateDeptCodeUnique(updateReqVO.getId(), updateReqVO.getCode());

        // 更新组织
        DeptDO updateObj = BeanUtils.toBean(updateReqVO, DeptDO.class);
        deptMapper.updateById(updateObj);
    }

    @Override
    public void updateDeptStatus(Long id, Integer status) {
        // 校验存在
        validateDeptExists(id);
        // 更新状态。刻意只提交 id + status：updateDept 要求 code 非空，而存量组织行的 code
        // 是 NULL（不回填，见 DeptDO#code），整行更新会被 Bean Validation 拦成 400，
        // 界面上表现为「状态开关点了报错」。专用接口结构性地绕开这个冲突。
        //
        // 也不加 @CacheEvict：DEPT_CHILDREN_ID_LIST 缓存的是子组织 id 集合
        // （getChildDeptIdListFromCache → selectListByParentId，不过滤 status），
        // 改状态不影响它。上面几个方法清缓存是因为它们可能动 parent_id
        deptMapper.updateById(new DeptDO().setId(id).setStatus(status));
    }

    @Override
    @CacheEvict(cacheNames = RedisKeyConstants.DEPT_CHILDREN_ID_LIST,
            allEntries = true) // allEntries 清空所有缓存，因为操作一个组织，涉及到多个缓存
    public void deleteDept(Long id) {
        // 校验是否存在
        validateDeptExists(id);
        // 校验是否有子组织
        if (deptMapper.selectCountByParentId(id) > 0) {
            throw exception(DEPT_EXITS_CHILDREN);
        }
        // 删除组织
        deptMapper.deleteById(id);
    }

    @Override
    @CacheEvict(cacheNames = RedisKeyConstants.DEPT_CHILDREN_ID_LIST,
            allEntries = true) // allEntries 清空所有缓存，因为操作一个组织，涉及到多个缓存
    public void deleteDeptList(List<Long> ids) {
        // 校验是否有子组织
        for (Long id : ids) {
            if (deptMapper.selectCountByParentId(id) > 0) {
                throw exception(DEPT_EXITS_CHILDREN);
            }
        }

        // 批量删除组织
        deptMapper.deleteByIds(ids);
    }

    @VisibleForTesting
    void validateDeptExists(Long id) {
        if (id == null) {
            return;
        }
        DeptDO dept = deptMapper.selectById(id);
        if (dept == null) {
            throw exception(DEPT_NOT_FOUND);
        }
    }

    @VisibleForTesting
    void validateParentDept(Long id, Long parentId) {
        if (parentId == null || DeptDO.PARENT_ID_ROOT.equals(parentId)) {
            return;
        }
        // 1. 不能设置自己为父组织
        if (Objects.equals(id, parentId)) {
            throw exception(DEPT_PARENT_ERROR);
        }
        // 2. 父组织不存在
        DeptDO parentDept = deptMapper.selectById(parentId);
        if (parentDept == null) {
            throw exception(DEPT_PARENT_NOT_EXITS);
        }
        // 3. 递归校验父组织，如果父组织是自己的子组织，则报错，避免形成环路
        if (id == null) { // id 为空，说明新增，不需要考虑环路
            return;
        }
        for (int i = 0; i < Short.MAX_VALUE; i++) {
            // 3.1 校验环路
            parentId = parentDept.getParentId();
            if (Objects.equals(id, parentId)) {
                throw exception(DEPT_PARENT_IS_CHILD);
            }
            // 3.2 继续递归下一级父组织
            if (parentId == null || DeptDO.PARENT_ID_ROOT.equals(parentId)) {
                break;
            }
            parentDept = deptMapper.selectById(parentId);
            if (parentDept == null) {
                break;
            }
        }
    }

    @VisibleForTesting
    void validateDeptNameUnique(Long id, Long parentId, String name) {
        DeptDO dept = deptMapper.selectByParentIdAndName(parentId, name);
        if (dept == null) {
            return;
        }
        // 如果 id 为空，说明不用比较是否为相同 id 的组织
        if (id == null) {
            throw exception(DEPT_NAME_DUPLICATE);
        }
        if (ObjectUtil.notEqual(dept.getId(), id)) {
            throw exception(DEPT_NAME_DUPLICATE);
        }
    }

    /**
     * 校验组织编码的唯一性
     *
     * 与上面的 validateDeptNameUnique 有一处刻意不同：**不带 parentId**。
     * 名称是「同父下唯一」（不同分公司各自可以有「财务部」），
     * 而编码是组织的身份标识，租户内全局唯一 —— 否则对接外部系统时一个编码会解析出两个组织。
     * 租户维度由 DeptMapper#selectByCode 上的租户插件自动兜住，这里不用管
     */
    @VisibleForTesting
    void validateDeptCodeUnique(Long id, String code) {
        DeptDO dept = deptMapper.selectByCode(code);
        if (dept == null) {
            return;
        }
        // 如果 id 为空，说明是新增，撞上任何一行都算重复
        if (id == null) {
            throw exception(DEPT_CODE_DUPLICATE);
        }
        if (ObjectUtil.notEqual(dept.getId(), id)) {
            throw exception(DEPT_CODE_DUPLICATE);
        }
    }

    @Override
    public DeptDO getDept(Long id) {
        return deptMapper.selectById(id);
    }

    @Override
    public List<DeptDO> getDeptList(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return Collections.emptyList();
        }
        return deptMapper.selectByIds(ids);
    }

    @Override
    public List<DeptDO> getDeptList(DeptListReqVO reqVO) {
        List<DeptDO> list = deptMapper.selectList(reqVO);
        list.sort(Comparator.comparing(DeptDO::getSort));
        return list;
    }

    @Override
    public List<DeptDO> getChildDeptList(Collection<Long> ids) {
        List<DeptDO> children = new LinkedList<>();
        // 遍历每一层
        Collection<Long> parentIds = ids;
        for (int i = 0; i < Short.MAX_VALUE; i++) { // 使用 Short.MAX_VALUE 避免 bug 场景下，存在死循环
            // 查询当前层，所有的子组织
            List<DeptDO> depts = deptMapper.selectListByParentId(parentIds);
            // 1. 如果没有子组织，则结束遍历
            if (CollUtil.isEmpty(depts)) {
                break;
            }
            // 2. 如果有子组织，继续遍历
            children.addAll(depts);
            parentIds = convertSet(depts, DeptDO::getId);
        }
        return children;
    }

    @Override
    public List<DeptDO> getParentDeptList(Long id) {
        List<DeptDO> parents = new ArrayList<>();
        Set<Long> visitedDeptIds = new HashSet<>();
        visitedDeptIds.add(id);
        DeptDO dept = getDept(id);
        // TODO DONE @AI：使用 Short.MAX_VALUE 限制父链遍历次数，并通过已访问集合提前结束脏数据环路。
        for (int i = 0; i < Short.MAX_VALUE; i++) {
            if (dept == null || dept.getParentId() == null
                    || ObjectUtil.equal(dept.getParentId(), DeptDO.PARENT_ID_ROOT)
                    || visitedDeptIds.contains(dept.getParentId())) {
                break;
            }
            visitedDeptIds.add(dept.getParentId());
            dept = getDept(dept.getParentId());
            if (dept == null) {
                break;
            }
            parents.add(dept);
        }
        return parents;
    }

    @Override
    public List<DeptDO> getDeptListByLeaderUserId(Long id) {
        return deptMapper.selectListByLeaderUserId(id);
    }

    @Override
    @DataPermission(enable = false) // 禁用数据权限，避免建立不正确的缓存
    @Cacheable(cacheNames = RedisKeyConstants.DEPT_CHILDREN_ID_LIST, key = "#id")
    public Set<Long> getChildDeptIdListFromCache(Long id) {
        List<DeptDO> children = getChildDeptList(id);
        return convertSet(children, DeptDO::getId);
    }

    @Override
    @DataPermission(enable = false) // 删除用户是系统级动作，不能被当前操作人的组织数据权限挡住，否则悬空引用又留下了
    public void processUserDeleted(Long userId) {
        List<DeptDO> depts = deptMapper.selectListByLeaderUserId(userId);
        if (CollUtil.isEmpty(depts)) {
            return;
        }
        // 用 UpdateWrapper 显式 set null：MyBatis-Plus 的 updateById 默认忽略 null 字段，抹不掉负责人
        deptMapper.update(null, new LambdaUpdateWrapper<DeptDO>()
                .in(DeptDO::getId, convertSet(depts, DeptDO::getId))
                .set(DeptDO::getLeaderUserId, null));
        log.info("[processUserDeleted][用户({}) 已删除，清空其担任负责人的组织({})]",
                userId, convertSet(depts, DeptDO::getId));
    }

    @Override
    public void validateDeptList(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return;
        }
        // 获得组织信息
        Map<Long, DeptDO> deptMap = getDeptMap(ids);
        // 校验
        ids.forEach(id -> {
            DeptDO dept = deptMap.get(id);
            if (dept == null) {
                throw exception(DEPT_NOT_FOUND);
            }
            if (!CommonStatusEnum.ENABLE.getStatus().equals(dept.getStatus())) {
                throw exception(DEPT_NOT_ENABLE, dept.getName());
            }
        });
    }

}
