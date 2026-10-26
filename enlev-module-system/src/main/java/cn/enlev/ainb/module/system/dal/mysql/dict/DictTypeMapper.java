package cn.enlev.ainb.module.system.dal.mysql.dict;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.type.DictTypePageReqVO;
import cn.enlev.ainb.module.system.dal.dataobject.dict.DictTypeDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

@Mapper
public interface DictTypeMapper extends BaseMapperX<DictTypeDO> {

    default PageResult<DictTypeDO> selectPage(DictTypePageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<DictTypeDO>()
                .likeIfPresent(DictTypeDO::getName, reqVO.getName())
                .likeIfPresent(DictTypeDO::getType, reqVO.getType())
                .eqIfPresent(DictTypeDO::getStatus, reqVO.getStatus())
                .betweenIfPresent(DictTypeDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(DictTypeDO::getId));
    }

    default DictTypeDO selectByType(String type) {
        return selectOne(DictTypeDO::getType, type);
    }

    default DictTypeDO selectByName(String name) {
        return selectOne(DictTypeDO::getName, name);
    }

    /**
     * 查询分类树的全部节点，按同级排序
     *
     * 左树一次性取全量。分类的数量级是几百，全取比按层懒加载简单得多，也不用处理展开态的拼接
     */
    default List<DictTypeDO> selectListOrderBySort() {
        return selectList(new LambdaQueryWrapperX<DictTypeDO>()
                .orderByAsc(Arrays.asList(DictTypeDO::getParentId, DictTypeDO::getSort, DictTypeDO::getId)));
    }

    /**
     * 按关键字查询命中的分类
     *
     * 只返回命中项，祖先由 Service 层补齐——否则树在界面上会断掉
     */
    default List<DictTypeDO> selectListByKeyword(String keyword) {
        return selectList(new LambdaQueryWrapperX<DictTypeDO>()
                .and(w -> w.like(DictTypeDO::getName, keyword).or().like(DictTypeDO::getType, keyword))
                .orderByAsc(Arrays.asList(DictTypeDO::getParentId, DictTypeDO::getSort, DictTypeDO::getId)));
    }

    default Long selectCountByParentId(Long parentId) {
        return selectCount(DictTypeDO::getParentId, parentId);
    }

    default List<DictTypeDO> selectListByParentId(Long parentId) {
        return selectList(new LambdaQueryWrapperX<DictTypeDO>()
                .eq(DictTypeDO::getParentId, parentId)
                .orderByAsc(Arrays.asList(DictTypeDO::getSort, DictTypeDO::getId)));
    }

    /**
     * 批量查询多个父分类下的直接子分类
     */
    default List<DictTypeDO> selectListByParentIds(Collection<Long> parentIds) {
        return selectList(new LambdaQueryWrapperX<DictTypeDO>()
                .in(DictTypeDO::getParentId, parentIds));
    }

    /**
     * 更新分类的父级
     */
    @Update("UPDATE system_dict_type SET parent_id = #{parentId} WHERE id = #{id} AND deleted = 0")
    void updateParentId(@Param("id") Long id, @Param("parentId") Long parentId);

    /**
     * 更新单个分类的排序值
     */
    @Update("UPDATE system_dict_type SET sort = #{sort} WHERE id = #{id} AND deleted = 0")
    void updateSort(@Param("id") Long id, @Param("sort") Integer sort);

    @Update("UPDATE system_dict_type SET deleted = 1, deleted_time = #{deletedTime} WHERE id = #{id}")
    void updateToDelete(@Param("id") Long id, @Param("deletedTime") LocalDateTime deletedTime);

}
