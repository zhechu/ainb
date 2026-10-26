package cn.enlev.ainb.module.infra.dal.mysql.formdesign;

import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDataChangeLogDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 表单数据变更日志 Mapper
 *
 * @author 走进新时代
 */
@Mapper
public interface FormDataChangeLogMapper extends BaseMapperX<FormDataChangeLogDO> {

    /**
     * 按表单编码 + 数据主键查时间线，最新的在前
     *
     * 带 limit：一条被反复修改的记录可以有上百条日志，详情页的时间线不需要全部。
     * 不带的话，一次点开详情就把整段历史拉进内存，而用户只看最近几条。
     */
    default List<FormDataChangeLogDO> selectTimeline(String formCode, String dataId, int limit) {
        return selectList(new LambdaQueryWrapperX<FormDataChangeLogDO>()
                .eq(FormDataChangeLogDO::getFormCode, formCode)
                .eq(FormDataChangeLogDO::getDataId, dataId)
                .orderByDesc(FormDataChangeLogDO::getId)
                .last("LIMIT " + limit));
    }

}
