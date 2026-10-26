package cn.enlev.ainb.module.system.dal.mysql.permission;

import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.navnode.NavNodeListReqVO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.NavNodeDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/**
 * 分端导航节点 Mapper
 *
 * <p>仿 {@link MenuMapper}：全部走 MyBatis-Plus 逻辑删除，@TenantIgnore 由 DO 承担
 *
 * @author 走进新时代
 */
@Mapper
public interface NavNodeMapper extends BaseMapperX<NavNodeDO> {

    /**
     * 取某一端的全部节点，按 (sort, id) 升序 —— 建树与展示都依赖这个稳定顺序
     */
    default List<NavNodeDO> selectListByTerminal(String terminal) {
        return selectList(new LambdaQueryWrapperX<NavNodeDO>()
                .eq(NavNodeDO::getTerminal, terminal)
                .orderByAsc(NavNodeDO::getSort)
                .orderByAsc(NavNodeDO::getId));
    }

    default NavNodeDO selectByParentIdAndName(String terminal, Long parentId, String name) {
        return selectOne(new LambdaQueryWrapperX<NavNodeDO>()
                .eq(NavNodeDO::getTerminal, terminal)
                .eq(NavNodeDO::getParentId, parentId)
                .eq(NavNodeDO::getName, name));
    }

    default Long selectCountByParentId(Long parentId) {
        return selectCount(NavNodeDO::getParentId, parentId);
    }

    /**
     * 查这批节点下的全部直接子节点（批量删除校验「会不会留孤儿」用）
     *
     * <p>调用方须保证 {@code parentIds} 非空：MP 的 {@code in()} 收到空集合会拼出恒假条件，
     * 表现为「一个子节点都没有」，从而把该校验静默跳过
     */
    default List<NavNodeDO> selectListByParentIds(Collection<Long> parentIds) {
        return selectList(new LambdaQueryWrapperX<NavNodeDO>().in(NavNodeDO::getParentId, parentIds));
    }

    default List<NavNodeDO> selectList(NavNodeListReqVO reqVO) {
        return selectList(new LambdaQueryWrapperX<NavNodeDO>()
                .eqIfPresent(NavNodeDO::getTerminal, reqVO.getTerminal())
                .likeIfPresent(NavNodeDO::getName, reqVO.getName())
                .eqIfPresent(NavNodeDO::getNodeType, reqVO.getNodeType())
                .eqIfPresent(NavNodeDO::getStatus, reqVO.getStatus())
                .orderByAsc(NavNodeDO::getSort)
                .orderByAsc(NavNodeDO::getId));
    }

}
