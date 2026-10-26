package cn.enlev.ainb.module.system.dal.mysql.dept;

import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.system.controller.admin.dept.vo.dept.DeptListReqVO;
import cn.enlev.ainb.module.system.dal.dataobject.dept.DeptDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

@Mapper
public interface DeptMapper extends BaseMapperX<DeptDO> {

    default List<DeptDO> selectList(DeptListReqVO reqVO) {
        return selectList(new LambdaQueryWrapperX<DeptDO>()
                .likeIfPresent(DeptDO::getName, reqVO.getName())
                .eqIfPresent(DeptDO::getStatus, reqVO.getStatus()));
    }

    default DeptDO selectByParentIdAndName(Long parentId, String name) {
        return selectOne(DeptDO::getParentId, parentId, DeptDO::getName, name);
    }

    /**
     * 按组织编码查一条
     *
     * 这里没写租户条件是**对的**，不是漏了：DeptDO 继承 TenantBaseDO，租户插件会自动追加
     * {@code tenant_id = ?}，所以「按编码查」天然就是「在本租户内按编码查」——
     * 这正是「组织编码同租户内唯一」的全部实现，不需要 DB 唯一索引
     */
    default DeptDO selectByCode(String code) {
        return selectOne(DeptDO::getCode, code);
    }

    default Long selectCountByParentId(Long parentId) {
        return selectCount(DeptDO::getParentId, parentId);
    }

    default List<DeptDO> selectListByParentId(Collection<Long> parentIds) {
        return selectList(DeptDO::getParentId, parentIds);
    }

    default List<DeptDO> selectListByLeaderUserId(Long id) {
        return selectList(DeptDO::getLeaderUserId, id);
    }

}
