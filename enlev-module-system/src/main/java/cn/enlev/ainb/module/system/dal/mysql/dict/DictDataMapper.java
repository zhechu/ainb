package cn.enlev.ainb.module.system.dal.mysql.dict;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.data.DictDataPageReqVO;
import cn.enlev.ainb.module.system.dal.dataobject.dict.DictDataDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

@Mapper
public interface DictDataMapper extends BaseMapperX<DictDataDO> {

    default DictDataDO selectByDictTypeAndValue(String dictType, String value) {
        return selectOne(DictDataDO::getDictType, dictType, DictDataDO::getValue, value);
    }

    default DictDataDO selectByDictTypeAndLabel(String dictType, String label) {
        return selectOne(DictDataDO::getDictType, dictType, DictDataDO::getLabel, label);
    }

    default List<DictDataDO> selectByDictTypeAndValues(String dictType, Collection<String> values) {
        return selectList(new LambdaQueryWrapper<DictDataDO>().eq(DictDataDO::getDictType, dictType)
                .in(DictDataDO::getValue, values));
    }

    default long selectCountByDictType(String dictType) {
        return selectCount(DictDataDO::getDictType, dictType);
    }

    default PageResult<DictDataDO> selectPage(DictDataPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<DictDataDO>()
                .likeIfPresent(DictDataDO::getLabel, reqVO.getLabel())
                .eqIfPresent(DictDataDO::getDictType, reqVO.getDictType())
                .eqIfPresent(DictDataDO::getStatus, reqVO.getStatus())
                .orderByDesc(Arrays.asList(DictDataDO::getDictType, DictDataDO::getSort)));
    }

    default List<DictDataDO> selectListByStatusAndDictType(Integer status, String dictType) {
        return selectList(new LambdaQueryWrapperX<DictDataDO>()
                .eqIfPresent(DictDataDO::getStatus, status)
                .eqIfPresent(DictDataDO::getDictType, dictType));
    }

    // ========== 树形 ==========

    /**
     * 查询某个字典下的全部数据，按「父节点 → 同级排序」排列
     *
     * 前端拿到后用 handleTree 组装成树。排序放在 SQL 里做，保证同级顺序稳定
     */
    default List<DictDataDO> selectListByDictTypeOrderBySort(String dictType) {
        return selectList(new LambdaQueryWrapperX<DictDataDO>()
                .eq(DictDataDO::getDictType, dictType)
                .orderByAsc(Arrays.asList(DictDataDO::getParentId, DictDataDO::getSort, DictDataDO::getId)));
    }

    /**
     * 按关键字查询某个字典下命中的数据
     *
     * 只返回命中项，祖先由 Service 层依据 idPath 补齐
     */
    default List<DictDataDO> selectListByKeyword(String dictType, String keyword) {
        return selectList(new LambdaQueryWrapperX<DictDataDO>()
                .eq(DictDataDO::getDictType, dictType)
                .and(w -> w.like(DictDataDO::getLabel, keyword).or().like(DictDataDO::getValue, keyword))
                .orderByAsc(Arrays.asList(DictDataDO::getParentId, DictDataDO::getSort, DictDataDO::getId)));
    }

    /**
     * 查询直接子级，供懒加载字典按需展开
     */
    default List<DictDataDO> selectListByParentId(String dictType, Long parentId) {
        return selectList(new LambdaQueryWrapperX<DictDataDO>()
                .eq(DictDataDO::getDictType, dictType)
                .eq(DictDataDO::getParentId, parentId)
                .orderByAsc(Arrays.asList(DictDataDO::getSort, DictDataDO::getId)));
    }

    default Long selectCountByParentId(Long parentId) {
        return selectCount(DictDataDO::getParentId, parentId);
    }

    /**
     * 批量查询多个父节点下的直接子级
     *
     * 批量删除时用它一次取全，避免在循环里逐个 id 查库
     */
    default List<DictDataDO> selectListByParentIds(Collection<Long> parentIds) {
        return selectList(new LambdaQueryWrapperX<DictDataDO>()
                .in(DictDataDO::getParentId, parentIds));
    }

    /**
     * 查询某个节点的全部子孙（不含自身）
     *
     * 靠 idPath 前缀匹配一次取全，不需要递归。传入的 idPath 首尾都带分隔符，
     * 因此 {@code ',45,%'} 不会误命中 {@code ',145,'} 这类前缀
     */
    default List<DictDataDO> selectListByIdPathPrefix(String idPath) {
        return selectList(new LambdaQueryWrapperX<DictDataDO>()
                .likeRight(DictDataDO::getIdPath, idPath)
                .ne(DictDataDO::getIdPath, idPath));
    }

    /**
     * 回填新建节点的物化路径
     *
     * 不能用 updateById 传一个只带 id + idPath 的对象：DictDataDO.cssClass 上标了
     * {@code updateStrategy = FieldStrategy.ALWAYS}，MyBatis-Plus 会把它一并写进 SET 子句，
     * 于是这次「只想改路径」的更新会顺手把 css_class 清成 NULL。
     */
    @Update("UPDATE system_dict_data SET id_path = #{idPath} WHERE id = #{id} AND deleted = 0")
    void updateIdPath(@Param("id") Long id, @Param("idPath") String idPath);

    /**
     * 更新节点自身的父级、路径与层级
     *
     * 同样刻意避开 updateById，理由见 {@link #updateIdPath}。
     * 不含 sort：同级排序要整体重排，由 {@link #updateSort} 单独负责
     */
    @Update("UPDATE system_dict_data SET parent_id = #{parentId}, " +
            "id_path = #{idPath}, tree_level = #{treeLevel} WHERE id = #{id} AND deleted = 0")
    void updateTreeFields(@Param("id") Long id, @Param("parentId") Long parentId,
                          @Param("idPath") String idPath, @Param("treeLevel") Integer treeLevel);

    /**
     * 更新单个节点的排序值
     */
    @Update("UPDATE system_dict_data SET sort = #{sort} WHERE id = #{id} AND deleted = 0")
    void updateSort(@Param("id") Long id, @Param("sort") Integer sort);

    /**
     * 重写整棵子树的路径与层级
     *
     * 一条 SQL 搞定，与子树大小无关。REPLACE 只会替换掉路径的前缀部分：
     * oldPath 形如 ',12,45,'，它只可能出现在子孙 idPath 的开头
     */
    @Update("UPDATE system_dict_data SET id_path = REPLACE(id_path, #{oldPath}, #{newPath}), " +
            "tree_level = tree_level + #{levelGap} " +
            "WHERE deleted = 0 AND id_path LIKE CONCAT(#{oldPath}, '%') AND id != #{selfId}")
    void updateSubTreePath(@Param("oldPath") String oldPath, @Param("newPath") String newPath,
                           @Param("levelGap") int levelGap, @Param("selfId") Long selfId);

}
