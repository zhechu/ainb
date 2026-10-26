package cn.enlev.ainb.module.system.dal.dataobject.permission;

import cn.enlev.ainb.framework.tenant.core.db.TenantBaseDO;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectTypeEnum;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 记录共享（v9 的 RECORD 能力，S7b-2）
 *
 * <h3>与 {@link GrantDO} 的分工</h3>
 *
 * <pre>
 * sys_grant         「谁能对这【类】对象做什么」 —— 按属性、按规则圈一批行
 * sys_record_share  「谁能看这【条】记录」       —— 点名放行，逐条
 * </pre>
 *
 * <h3>为什么不复用 sys_grant + 每条记录一个 sys_resource</h3>
 *
 * v9 §5.1 论证过资源数爆炸：共享一万条单据就是一万行资源加一万行授权，
 * 而且资源树会被一堆「某条具体记录」的叶子淹没，授权界面没法看。
 *
 * 记录共享的形状与 {@code bpm_instance_participant} 完全一样 ——
 * 「一串具体的业务主键对某人可见」，所以做法也一样：
 * 独立表 + 派生可见性（把主键集合注入 {@code bizKey} 槽位的 IN 条件）。
 * 两者最终落在同一条规则通道上，不会出现两套判定口径。
 *
 * <h3>⚠️ 只放开，不收紧</h3>
 *
 * 表里只有「分享给谁」，没有「不分享给谁」。收紧要用 {@code sys_grant} 的 DENY。
 * 共享是一个人对另一个人的日常动作（把单子转给同事看一眼），
 * 让它能收紧的话，一次随手的操作就能把管理员配好的权限盖掉。
 *
 * @author 走进新时代
 */
@TableName("sys_record_share")
@KeySequence("sys_record_share_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class RecordShareDO extends TenantBaseDO {

    @TableId
    private Long id;

    /**
     * 数据集编码
     *
     * 在线表单是 form code（{@code test_leave}），
     * 原生模块是数据集权限串（{@code system:user}）—— 与 {@code @PermissionDataset#value} 一致
     */
    private String datasetCode;

    /**
     * 业务主键
     *
     * ⚠️ 用 String 而不是 Long：主键不一定是数字（雪花 ID 字符串、单据编号）。
     * 拼进 SQL 时由条件树求值器按列类型处理
     */
    private String recordId;

    /**
     * 被分享的主体类型，见 {@link SubjectTypeEnum}
     */
    private String subjectType;

    /**
     * 主体指向的业务表主键；code 寻址的类型为 null
     */
    private Long subjectRefId;

    /**
     * code 寻址的主体标识
     */
    private String subjectCode;

    /**
     * 到期时间，NULL = 永不过期
     *
     * 口径与 {@code sys_grant.expire_time} 完全一致 —— 两处对「过期」的理解必须一样，
     * 否则会出现「共享到期了但授权还在 / 反过来」这种没人能解释的状态
     */
    private LocalDateTime expireTime;

    /**
     * 共享说明
     *
     * 这是给被分享人看的「为什么给你」，排查「他怎么看得到这条」时也靠它。
     * 共享是人对人的动作，没有说明的话，三个月后没人知道当初为什么分享
     */
    private String remark;

    /**
     * 是否命中某个主体
     *
     * 与 {@link GrantDO#subjectMatches} 逐字对齐 —— 两处口径不一样的话，
     * 同一个人「授权算他命中、共享不算他命中」，而这种不一致没人能凭直觉推出来
     */
    public boolean subjectMatches(SubjectKey key) {
        if (key == null) {
            return false;
        }
        if (!key.type().name().equals(subjectType)) {
            return false;
        }
        if (key.type().isRefBased()) {
            return key.refId() != null && key.refId().equals(subjectRefId);
        }
        return key.code() != null && key.code().equals(subjectCode);
    }

}
