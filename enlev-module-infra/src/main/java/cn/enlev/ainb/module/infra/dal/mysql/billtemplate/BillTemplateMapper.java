package cn.enlev.ainb.module.infra.dal.mysql.billtemplate;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.infra.controller.admin.billtemplate.vo.BillTemplatePageReqVO;
import cn.enlev.ainb.module.infra.dal.dataobject.billtemplate.BillTemplateDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

@Mapper
public interface BillTemplateMapper extends BaseMapperX<BillTemplateDO> {

    default BillTemplateDO selectByCode(String code) {
        return selectOne(BillTemplateDO::getCode, code);
    }

    default List<BillTemplateDO> selectListByCodes(Collection<String> codes) {
        return selectList(BillTemplateDO::getCode, codes);
    }

    default PageResult<BillTemplateDO> selectPage(BillTemplatePageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<BillTemplateDO>()
                .likeIfPresent(BillTemplateDO::getName, reqVO.getName())
                .likeIfPresent(BillTemplateDO::getCode, reqVO.getCode())
                .eqIfPresent(BillTemplateDO::getCategory, reqVO.getCategory())
                .eqIfPresent(BillTemplateDO::getGenType, reqVO.getGenType())
                .eqIfPresent(BillTemplateDO::getStatus, reqVO.getStatus())
                .orderByAsc(BillTemplateDO::getSort)
                .orderByDesc(BillTemplateDO::getId));
    }

    /**
     * 回写当前流水值与最近号码
     *
     * 单独一条 UPDATE 而不是 updateById(整个 DO)：这是每次出号后都会执行的旁路写入，
     * 只该碰这三列。整对象更新会把并发请求里各自读到的旧配置写回去，
     * 把管理员刚改的配置覆盖掉。
     */
    default void updateStatistics(Long id, Long currentNumber, String lastNo, String lastPeriod) {
        BillTemplateDO update = new BillTemplateDO();
        update.setId(id);
        update.setCurrentNumber(currentNumber);
        update.setLastNo(lastNo);
        update.setLastPeriod(lastPeriod);
        updateById(update);
    }

}
