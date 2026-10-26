package cn.enlev.ainb.module.infra.dal.mysql.formdesign;

import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignVersionDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 表单设计版本 Mapper
 *
 * @author 走进新时代
 */
@Mapper
public interface FormDesignVersionMapper extends BaseMapperX<FormDesignVersionDO> {

    default List<FormDesignVersionDO> selectListByDesignId(Long designId) {
        return selectList(new LambdaQueryWrapperX<FormDesignVersionDO>()
                .eq(FormDesignVersionDO::getDesignId, designId)
                .orderByDesc(FormDesignVersionDO::getVersion));
    }

    default FormDesignVersionDO selectByDesignIdAndVersion(Long designId, Integer version) {
        return selectOne(new LambdaQueryWrapperX<FormDesignVersionDO>()
                .eq(FormDesignVersionDO::getDesignId, designId)
                .eq(FormDesignVersionDO::getVersion, version));
    }

    default Integer selectMaxVersionByDesignId(Long designId) {
        FormDesignVersionDO latest = selectOne(new LambdaQueryWrapperX<FormDesignVersionDO>()
                .eq(FormDesignVersionDO::getDesignId, designId)
                .orderByDesc(FormDesignVersionDO::getVersion)
                .last("LIMIT 1"));
        return latest != null ? latest.getVersion() : 0;
    }

    /**
     * 物理删除指定 designId 下最老的 N 个版本
     *
     * 用于版本清理：保留最新 {@link FormDesignVersionDO#MAX_KEEP_VERSIONS} 个，超出则删最老的。
     * 采用物理删除而非逻辑删除，避免堆积与唯一索引冲突。
     */
    default void deleteOldestVersions(Long designId, int countToDelete) {
        // 先查出要删的版本号
        List<FormDesignVersionDO> toDelete = selectList(new LambdaQueryWrapperX<FormDesignVersionDO>()
                .eq(FormDesignVersionDO::getDesignId, designId)
                .orderByAsc(FormDesignVersionDO::getVersion)
                .last("LIMIT " + countToDelete));
        if (toDelete.isEmpty()) {
            return;
        }
        // 物理删除
        toDelete.forEach(v -> deleteById(v.getId()));
    }

}
