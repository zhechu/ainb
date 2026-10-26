package cn.enlev.ainb.module.system.service.permission;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.module.system.dal.dataobject.permission.SubjectDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.SubjectMemberDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.SubjectMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.SubjectMemberMapper;
import cn.enlev.ainb.module.system.dal.redis.RedisKeyConstants;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectGraph;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Objects;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.*;

/**
 * 授权主体 Service 实现类
 *
 * @author 走进新时代
 */
@Service
@Validated
@Slf4j
public class SubjectServiceImpl implements SubjectService {

    @Resource
    private SubjectMapper subjectMapper;
    @Resource
    private SubjectMemberMapper subjectMemberMapper;
    @Resource
    private SubjectGraphLoader subjectGraphLoader;

    @Override
    @CacheEvict(cacheNames = RedisKeyConstants.SUBJECT_ANCESTORS, allEntries = true)
    public Long getOrCreateSubjectId(SubjectKey key, String name) {
        SubjectDO subject = subjectMapper.selectByKey(key);
        if (subject != null) {
            return subject.getId();
        }
        subject = new SubjectDO();
        subject.setType(key.type().name());
        subject.setRefId(key.refId());
        subject.setCode(key.code());
        subject.setName(name);
        subject.setStatus(CommonStatusEnum.ENABLE.getStatus());
        subjectMapper.insert(subject);
        return subject.getId();
    }

    @Override
    @CacheEvict(cacheNames = RedisKeyConstants.SUBJECT_ANCESTORS, allEntries = true)
    public Long createGroupSubject(String name) {
        // 用户组没有业务表，ref_id 指向主体行自己 —— 这样 GROUP 才能和别的类型一样用
        // (type, ref_id) 统一寻址，而不用在寻址逻辑里为它开特例
        SubjectDO subject = new SubjectDO();
        subject.setType(SubjectTypeEnum.GROUP.name());
        subject.setName(name);
        subject.setStatus(CommonStatusEnum.ENABLE.getStatus());
        subjectMapper.insert(subject);

        SubjectDO updateObj = new SubjectDO();
        updateObj.setId(subject.getId());
        updateObj.setRefId(subject.getId());
        subjectMapper.updateById(updateObj);
        return subject.getId();
    }

    @Override
    public SubjectDO getSubject(SubjectKey key) {
        return subjectMapper.selectByKey(key);
    }

    @Override
    @CacheEvict(cacheNames = RedisKeyConstants.SUBJECT_ANCESTORS, allEntries = true)
    public void updateSubjectStatus(SubjectKey key, Integer status) {
        SubjectDO subject = subjectMapper.selectByKey(key);
        if (subject == null) {
            throw exception(SUBJECT_NOT_EXISTS);
        }
        SubjectDO updateObj = new SubjectDO();
        updateObj.setId(subject.getId());
        updateObj.setStatus(status);
        subjectMapper.updateById(updateObj);
    }

    @Override
    @CacheEvict(cacheNames = RedisKeyConstants.SUBJECT_ANCESTORS, allEntries = true)
    public Long addMember(SubjectKey container, SubjectKey member) {
        // 1.1 SYSTEM 不能当成员：它是「系统在做事」的身份，不是谁的下属。
        //     允许的话，把 SYSTEM 塞进某个角色，就等于给那个角色开了一扇后门
        if (!member.type().isRefBased()) {
            throw exception(SUBJECT_MEMBER_TYPE_NOT_SUPPORTED, member.type().name());
        }
        // 1.2 自己不能是自己的成员
        if (Objects.equals(container, member)) {
            throw exception(SUBJECT_MEMBER_SELF);
        }
        // 1.3 重复。容器行按需落地 —— 一个成为容器的主体，正是「需要区别对待」的主体
        Long containerId = getOrCreateSubjectId(container, null);
        if (subjectMemberMapper.selectBySubjectIdAndMember(containerId, member) != null) {
            throw exception(SUBJECT_MEMBER_DUPLICATE);
        }

        // 2. 环与深度：在【库里的真实数据】上算，不读缓存。
        //    校验要是读到一份过期的图，就会放过一条真的成环的边，
        //    而这条边的代价是登录时主体解析无限递归 —— 影响面是全体用户
        SubjectGraph graph = subjectGraphLoader.load(true);
        // 2.1 成环：从 container 往上能走到 member，说明 member 已经是 container 的祖先；
        //     再让 member 当 container 的成员，环就闭合了
        if (graph.reachesUpward(container, member)) {
            throw exception(SUBJECT_MEMBER_CYCLE, container.asString() + " → " + member.asString());
        }
        // 2.2 深度。注意是在【加上这条边之后】的图上量最长链，
        //     只量 container/member 各自那一段的话，会漏掉「两条短链被这条边接成一条长链」
        int chainLength = graph.plus(new SubjectGraph.Edge(container, member)).maxChainLength();
        if (chainLength > SubjectGraph.MAX_DEPTH) {
            throw exception(SUBJECT_MEMBER_DEPTH_EXCEED, chainLength, SubjectGraph.MAX_DEPTH);
        }

        // 3. 落库
        SubjectMemberDO entity = new SubjectMemberDO();
        entity.setSubjectId(containerId);
        entity.setMemberType(member.type().name());
        entity.setMemberRefId(member.refId());
        subjectMemberMapper.insert(entity);
        return entity.getId();
    }

    @Override
    @CacheEvict(cacheNames = RedisKeyConstants.SUBJECT_ANCESTORS, allEntries = true)
    public void removeMember(SubjectKey container, SubjectKey member) {
        SubjectDO subject = subjectMapper.selectByKey(container);
        if (subject == null) {
            return;
        }
        SubjectMemberDO entity = subjectMemberMapper.selectBySubjectIdAndMember(subject.getId(), member);
        if (entity == null) {
            return;
        }
        subjectMemberMapper.deleteById(entity.getId());
    }

    @Override
    public List<SubjectMemberDO> getMemberList(SubjectKey container) {
        SubjectDO subject = subjectMapper.selectByKey(container);
        return subject == null ? List.of() : subjectMemberMapper.selectListBySubjectId(subject.getId());
    }

}
