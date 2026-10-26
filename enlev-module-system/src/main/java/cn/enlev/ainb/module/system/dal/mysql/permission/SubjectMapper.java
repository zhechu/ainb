package cn.enlev.ainb.module.system.dal.mysql.permission;

import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.system.dal.dataobject.permission.SubjectDO;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectTypeEnum;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SubjectMapper extends BaseMapperX<SubjectDO> {

    default SubjectDO selectByKey(SubjectKey key) {
        LambdaQueryWrapperX<SubjectDO> query = new LambdaQueryWrapperX<SubjectDO>()
                .eq(SubjectDO::getType, key.type().name());
        if (key.type().isRefBased()) {
            query.eq(SubjectDO::getRefId, key.refId());
        } else {
            query.eq(SubjectDO::getCode, key.code());
        }
        // 唯一性由 Service 层保证（软删除 + 唯一索引在 MySQL 下语义很差，本项目统一不建）。
        // 万一并发插出两行，取其一即可 —— 两行指向同一个 (type, ref_id)，闭包结果一样
        return selectOne(query.last("limit 1"));
    }

    default List<SubjectDO> selectListByType(SubjectTypeEnum type) {
        return selectList(SubjectDO::getType, type.name());
    }

}
