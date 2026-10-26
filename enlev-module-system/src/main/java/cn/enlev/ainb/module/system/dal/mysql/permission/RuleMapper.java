package cn.enlev.ainb.module.system.dal.mysql.permission;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.rule.RulePageReqVO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.RuleDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface RuleMapper extends BaseMapperX<RuleDO> {

    default PageResult<RuleDO> selectPage(RulePageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<RuleDO>()
                .likeIfPresent(RuleDO::getName, reqVO.getName())
                .likeIfPresent(RuleDO::getTemplate, reqVO.getTemplate())
                .eqIfPresent(RuleDO::getStatus, reqVO.getStatus())
                .eqIfPresent(RuleDO::getBuiltIn, reqVO.getBuiltIn())
                .betweenIfPresent(RuleDO::getCreateTime, reqVO.getCreateTime())
                .orderByAsc(RuleDO::getId));
    }

    default List<RuleDO> selectListByTemplate(String template) {
        return selectList(RuleDO::getTemplate, template);
    }

}
