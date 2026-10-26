package cn.enlev.ainb.module.system.service.permission;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.module.system.dal.dataobject.permission.SubjectDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.SubjectMemberDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.SubjectMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.SubjectMemberMapper;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectGraph;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把库里的主体成员关系读成一张 {@link SubjectGraph}
 *
 * 抽出来是因为有两个调用方，且它们要的图**不一样**：
 *
 * <pre>
 *     写校验（SubjectServiceImpl）   要含停用主体的全图
 *         停用只是「暂时不生效」，环还是环 —— 校验时放过去，
 *         等哪天有人把主体重新启用，环就在登录路径上活了
 *
 *     求闭包（SubjectClosureServiceImpl） 要剔掉停用主体
 *         停用一个基础角色，就该让继承它的角色立刻失去那批权限
 * </pre>
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class SubjectGraphLoader {

    @Resource
    private SubjectMapper subjectMapper;
    @Resource
    private SubjectMemberMapper subjectMemberMapper;

    /**
     * 读图
     *
     * 两张表各全表扫一次。这张图只存角色继承边与用户组成员边，量级千行以内；
     * 写路径低频，读路径有整图缓存兜着，所以全表扫是最划算的做法 ——
     * 换来的是「每次都在最新数据上算」这个确定性，不用维护任何增量结构
     *
     * @param includeDisabled 是否把停用主体的边也算进来
     */
    public SubjectGraph load(boolean includeDisabled) {
        List<SubjectMemberDO> members = subjectMemberMapper.selectListAll();
        if (members.isEmpty()) {
            return new SubjectGraph(List.of());
        }

        // 容器端是外键，要换成寻址串才能进图
        List<SubjectDO> subjects = subjectMapper.selectByIds(
                members.stream().map(SubjectMemberDO::getSubjectId).distinct().toList());
        Map<Long, SubjectKey> containerKeys = new HashMap<>(subjects.size());
        for (SubjectDO subject : subjects) {
            if (!includeDisabled && CommonStatusEnum.DISABLE.getStatus().equals(subject.getStatus())) {
                continue;
            }
            SubjectKey key = subject.toKey();
            if (key != null) {
                containerKeys.put(subject.getId(), key);
            }
        }

        List<SubjectGraph.Edge> edges = new ArrayList<>(members.size());
        for (SubjectMemberDO member : members) {
            SubjectKey containerKey = containerKeys.get(member.getSubjectId());
            SubjectKey memberKey = member.toMemberKey();
            if (containerKey == null || memberKey == null) {
                // 容器被停用（本就该剔掉）、容器行被删了、或者类型是直接改库改出来的脏值。
                // 一律跳过而不是抛异常：这条边本来就不可能生效，让它静静消失；
                // 抛的话，一行坏数据会让所有人都登不上来
                continue;
            }
            edges.add(new SubjectGraph.Edge(containerKey, memberKey));
        }
        if (edges.size() < members.size()) {
            log.debug("[load][成员关系 {} 条，入图 {} 条，其余因容器停用或解析失败被跳过]",
                    members.size(), edges.size());
        }
        return new SubjectGraph(edges);
    }

}
