package cn.enlev.ainb.module.infra.dal.mysql.datainterface;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.framework.mybatis.core.util.MyBatisUtils;
import cn.enlev.ainb.module.infra.controller.admin.datainterface.vo.DataInterfacePageReqVO;
import cn.enlev.ainb.module.infra.dal.dataobject.datainterface.DataInterfaceDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface DataInterfaceMapper extends BaseMapperX<DataInterfaceDO> {

    default DataInterfaceDO selectByCode(String code) {
        return selectOne(DataInterfaceDO::getCode, code);
    }

    /**
     * 查询所有启用中的接口
     *
     * 给「选接口」这类下拉场景用，不带分页。是否可用于运行时取数还要再过一层读取型判断，
     * 那部分依赖 config 内容，SQL 里判断不了，放在 Service 层做。
     */
    default List<DataInterfaceDO> selectEnabledList() {
        return selectList(new LambdaQueryWrapperX<DataInterfaceDO>()
                .eq(DataInterfaceDO::getStatus, CommonStatusEnum.ENABLE.getStatus())
                .orderByAsc(DataInterfaceDO::getCategory)
                .orderByDesc(DataInterfaceDO::getId));
    }

    default PageResult<DataInterfaceDO> selectPage(DataInterfacePageReqVO reqVO) {
        LambdaQueryWrapperX<DataInterfaceDO> wrapper = new LambdaQueryWrapperX<DataInterfaceDO>()
                .likeIfPresent(DataInterfaceDO::getName, reqVO.getName())
                .likeIfPresent(DataInterfaceDO::getCode, reqVO.getCode())
                .eqIfPresent(DataInterfaceDO::getCategory, reqVO.getCategory())
                .eqIfPresent(DataInterfaceDO::getType, reqVO.getType())
                .eqIfPresent(DataInterfaceDO::getStatus, reqVO.getStatus())
                .eqIfPresent(DataInterfaceDO::getHasWriteOperation, reqVO.getHasWriteOperation())
                .eqIfPresent(DataInterfaceDO::getSqlStatementType, reqVO.getSqlStatementType());
        // tags 存的是逗号串，eq 匹配不到「form,report」里的 form，只能用 FIND_IN_SET。
        // 列名交给 MyBatisUtils 做合法性校验，值走 {0} 占位符绑定，不拼进 SQL
        if (StrUtil.isNotBlank(reqVO.getTag())) {
            wrapper.apply(MyBatisUtils.findInSet("tags"), reqVO.getTag());
        }
        return selectPage(reqVO, wrapper.orderByDesc(DataInterfaceDO::getId));
    }

}
