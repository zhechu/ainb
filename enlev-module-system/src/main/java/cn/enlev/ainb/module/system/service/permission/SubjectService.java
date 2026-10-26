package cn.enlev.ainb.module.system.service.permission;

import cn.enlev.ainb.module.system.dal.dataobject.permission.SubjectDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.SubjectMemberDO;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;

import java.util.List;

/**
 * 授权主体 Service
 *
 * 负责主体的**按需登记**与成员关系（角色继承、用户组）的维护。
 * 求闭包在 {@link SubjectClosureService}，两者职责分开：
 * 这里是写路径（低频、要强校验），那里是读路径（高频、要缓存）。
 *
 * @author 走进新时代
 */
public interface SubjectService {

    /**
     * 按需取得主体行的编号，没有就建一行
     *
     * ⚠️ 不要为了「让主体表完整」而批量调它 —— 主体表按需落行是刻意的设计，
     * 见 {@link SubjectDO} 的类注释
     *
     * @param key  主体寻址
     * @param name 展示名，仅排错用，可为空
     * @return 主体编号
     */
    Long getOrCreateSubjectId(SubjectKey key, String name);

    /**
     * 新建一个用户组主体
     *
     * 用户组是唯一没有业务表的主体类型，只能在这里建。
     * 过渡期内 bpm_user_group 仍然存在，把它合并进来是 S6a 的事
     *
     * @return 主体编号，同时也是它的 refId
     */
    Long createGroupSubject(String name);

    /**
     * 查主体行，没登记过返回 null
     */
    SubjectDO getSubject(SubjectKey key);

    /**
     * 更新主体状态。停用后该主体不再参与闭包
     */
    void updateSubjectStatus(SubjectKey key, Integer status);

    /**
     * 加一条成员关系：member 成为 container 的成员，于是 member 继承 container 的权限
     *
     * ⚠️ 方向别搞反。「高级销售继承销售」要写成
     * {@code addMember(ROLE:销售, ROLE:高级销售)}
     *
     * 会校验：不能自己是自己的成员、不能重复、不能成环、链长不能超上限
     *
     * @return 边的编号
     */
    Long addMember(SubjectKey container, SubjectKey member);

    /**
     * 删一条成员关系。不存在时静默返回
     */
    void removeMember(SubjectKey container, SubjectKey member);

    /**
     * 查某个容器主体的直接成员
     */
    List<SubjectMemberDO> getMemberList(SubjectKey container);

}
