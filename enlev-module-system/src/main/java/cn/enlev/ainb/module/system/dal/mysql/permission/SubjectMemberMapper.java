package cn.enlev.ainb.module.system.dal.mysql.permission;

import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.system.dal.dataobject.permission.SubjectMemberDO;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

@Mapper
public interface SubjectMemberMapper extends BaseMapperX<SubjectMemberDO> {

    default List<SubjectMemberDO> selectListBySubjectId(Long subjectId) {
        return selectList(SubjectMemberDO::getSubjectId, subjectId);
    }

    default List<SubjectMemberDO> selectListBySubjectIds(Collection<Long> subjectIds) {
        return selectList(SubjectMemberDO::getSubjectId, subjectIds);
    }

    default SubjectMemberDO selectBySubjectIdAndMember(Long subjectId, SubjectKey member) {
        return selectOne(new LambdaQueryWrapperX<SubjectMemberDO>()
                .eq(SubjectMemberDO::getSubjectId, subjectId)
                .eq(SubjectMemberDO::getMemberType, member.type().name())
                .eq(SubjectMemberDO::getMemberRefId, member.refId())
                .last("limit 1"));
    }

    default void deleteBySubjectId(Long subjectId) {
        delete(new LambdaQueryWrapperX<SubjectMemberDO>()
                .eq(SubjectMemberDO::getSubjectId, subjectId));
    }

    /**
     * 查出全部边
     *
     * 闭包缓存一次性把整张图读进内存来算，所以这里是全表扫。
     * 这张表只存「新增的」边（角色继承、用户组成员），量级在千行以内，
     * 全表扫比按需递归查便宜得多 —— 见 SubjectClosureServiceImpl 的类注释
     */
    default List<SubjectMemberDO> selectListAll() {
        return selectList(new LambdaQueryWrapperX<>());
    }

}
