package cn.enlev.ainb.module.system.service.permission;

import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;

import java.util.Map;
import java.util.Set;

/**
 * 主体闭包 Service
 *
 * 回答一个问题：**这个用户属于哪些主体**。决策引擎（S2a）拿这个集合去匹配授权。
 *
 * @author 走进新时代
 */
public interface SubjectClosureService {

    /**
     * 求用户的主体闭包
     *
     * 结果含：用户自己、启用的角色与岗位（以及可选的部门），
     * 再加上它们沿成员关系向上的全部祖先容器
     *
     * @return 主体寻址集合；用户不存在时返回只含它自己的集合
     */
    Set<SubjectKey> getSubjectKeysByUserId(Long userId);

    /**
     * 求任意主体的祖先容器（不含自身）
     */
    Set<SubjectKey> getAncestorKeys(SubjectKey key);

    /**
     * 整张成员关系图的祖先闭包，带缓存
     *
     * ⚠️ 这是缓存的入口方法，**必须经过 Spring 代理调用**，
     * 类内部直接调它不走缓存
     *
     * @return 成员寻址串 → 全部祖先容器寻址串
     */
    Map<String, Set<String>> getAncestorMapFromCache();

}
