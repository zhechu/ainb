package cn.enlev.ainb.module.infra.dal.mysql.formdesign;

import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignMenuDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 表单设计菜单关联 Mapper
 *
 * @author 走进新时代
 */
@Mapper
public interface FormDesignMenuMapper extends BaseMapperX<FormDesignMenuDO> {

    default List<FormDesignMenuDO> selectListByDesignId(Long designId) {
        return selectList(FormDesignMenuDO::getDesignId, designId);
    }

    default void deleteByDesignId(Long designId) {
        delete(new LambdaQueryWrapperX<FormDesignMenuDO>()
                .eq(FormDesignMenuDO::getDesignId, designId));
    }

}
