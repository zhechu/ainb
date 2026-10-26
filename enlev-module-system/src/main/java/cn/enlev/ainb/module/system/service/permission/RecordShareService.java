package cn.enlev.ainb.module.system.service.permission;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.datapermission.core.annotation.DataPermission;
import cn.enlev.ainb.module.system.dal.dataobject.permission.RecordShareDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.RecordShareMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper;
import cn.enlev.ainb.module.system.enums.permission.RecordSharePolicyEnum;
import cn.enlev.ainb.module.system.framework.permission.core.dataset.DatasetResourceNaming;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 记录共享 Service（S7b-2）
 *
 * <h3>它的定位：点名放行，逐条</h3>
 *
 * {@code sys_grant} 回答「谁能对这【类】对象做什么」，本 Service 回答
 * 「谁能看这【条】记录」。前者按属性、按规则圈一批行，后者逐条点名。
 *
 * <h3>⚠️ 只放开，不收紧</h3>
 *
 * 没有「取消某人对某条的可见性」这种语义 —— 取消共享只是把这条共享记录删掉，
 * 他若通过别的途径（是创建人、本部门、有角色授权）本来就看得到，删了共享照样看得到。
 * 要真的挡住他得用 {@code sys_grant} 的 DENY。
 *
 * 这一条必须在界面上说清楚，否则用户会把「取消共享」理解成「禁止查看」，
 * 而这两件事的差别只有在出事时才会被发现。
 *
 * @author 走进新时代
 */
@Service
@Slf4j
public class RecordShareService {

    @Resource
    private RecordShareMapper recordShareMapper;
    @Resource
    private ResourceMapper resourceMapper;
    @Resource
    private RecordOwnerResolver recordOwnerResolver;

    // ==================== 发起权（谁能分享）====================

    /**
     * 判断某人能不能分享某条记录
     *
     * <h3>口径：默认「仅归属人」，按数据集可配</h3>
     *
     * 共享是<b>把数据扩散出去</b>的动作，而且人人都能做。
     * 不定口径的话只有两种极端：谁看得到谁就能转出去（扩散没有边界），
     * 或者只有管理员能分享（功能等于没有）。所以默认取保守档，
     * 需要放开的数据集在 {@code sys_resource.share_policy} 上单独配。
     *
     * <h3>⚠️ 判不出归属人时拒绝，不是放行</h3>
     *
     * 没配 owner 槽位、表不在主库、记录不存在 —— 一律拒绝。
     * 放行的话，一个没配好槽位的数据集会悄悄变成「人人可分享」，
     * 而这件事没有任何提示。拒绝至少是「有人会来报」的失败方式
     *
     * @return 不能分享时返回拒绝原因；能分享时返回 null
     */
    @DataPermission(enable = false)
    public String checkShareable(Long userId, String datasetCode, String recordId) {
        if (userId == null) {
            return "未登录";
        }
        ResourceDO dataset = findDatasetResource(datasetCode);
        if (dataset == null) {
            // 数据集没登记 → 读不到 share_policy，也读不到归属人。
            // ⚠️ 这里【不能】默认放行：没登记的数据集恰恰是最不该随便扩散的
            return StrUtil.format("数据集({})没有登记成可授权对象，无法判断谁能分享。"
                    + "请先发布该表单，或在原生模块的 DO 上标 @PermissionDataset", datasetCode);
        }
        RecordSharePolicyEnum policy = RecordSharePolicyEnum.parse(dataset.getSharePolicy());
        if (policy == RecordSharePolicyEnum.DISABLED) {
            return StrUtil.format("数据集({})已禁止记录共享", datasetCode);
        }
        if (policy == RecordSharePolicyEnum.ANY_VIEWER) {
            // 这一档不校验归属，但【仍然要求他看得到这条】——
            // 否则这一档的名字就是假的：实际成了「任何人都能分享」。
            // 可见性走的是与业务列表查询同一份行过滤判定，不另写一套
            return recordOwnerResolver.canSeeRecord(userId, dataset, recordId)
                    ? null : "你看不到这条记录，不能分享它";
        }
        Long ownerUserId = recordOwnerResolver.resolveOwnerUserId(dataset, recordId);
        if (ownerUserId == null) {
            return StrUtil.format("判断不出记录({})的归属人，按「不可分享」处理。"
                    + "常见原因：数据集没配 owner 槽位映射、业务表不在主库、记录不存在", recordId);
        }
        if (!ownerUserId.equals(userId)) {
            return "只有记录的创建人可以分享它";
        }
        return null;
    }

    /**
     * 分享一条记录
     *
     * 幂等：同一 (数据集, 记录, 主体) 重复分享只更新到期时间与说明，不再插一行。
     * 不幂等的话，「分享 → 取消 → 再分享」会留下一堆同义行，
     * 而取消时删哪一行就成了问题
     *
     * @param datasetCode 数据集编码：在线表单是 form code，原生模块是数据集权限串
     * @param recordId    业务主键
     * @param subject     被分享的主体
     * @param expireTime  到期时间，null = 永不过期
     * @param remark      共享说明。⚠️ 强烈建议填：三个月后没人记得当初为什么分享
     * @return 共享记录编号
     */
    @Transactional(rollbackFor = Exception.class)
    public Long share(String datasetCode, String recordId, SubjectKey subject,
                      LocalDateTime expireTime, String remark) {
        // ⚠️ 刻意留 IllegalArgumentException，不换业务错误码：唯一的生产调用方
        // RecordShareController#share 已经在更外面拦过了 —— ShareReqVO 上两条 @NotBlank
        // 给出的是逐字段的 400（比这句笼统的更好读），parseSubject 也保证 subject 不为 null。
        // 所以这一条只可能被程序化误用触发，那种时候大声炸掉比一句温和的业务提示更容易被发现。
        // 若将来新增的调用方直接吃用户输入，要么给它加 @Valid，要么把这里换成 ServiceException
        if (StrUtil.isBlank(datasetCode) || StrUtil.isBlank(recordId) || subject == null) {
            throw new IllegalArgumentException("数据集、记录主键、被分享主体都不能为空");
        }
        RecordShareDO existing = findOne(datasetCode, recordId, subject);
        if (existing != null) {
            existing.setExpireTime(expireTime);
            existing.setRemark(remark);
            recordShareMapper.updateById(existing);
            return existing.getId();
        }
        RecordShareDO share = new RecordShareDO();
        share.setDatasetCode(datasetCode);
        share.setRecordId(recordId);
        share.setSubjectType(subject.type().name());
        share.setSubjectRefId(subject.type().isRefBased() ? subject.refId() : null);
        share.setSubjectCode(subject.type().isRefBased() ? null : subject.code());
        share.setExpireTime(expireTime);
        share.setRemark(remark);
        recordShareMapper.insert(share);
        return share.getId();
    }

    /**
     * 取消分享
     *
     * ⚠️ 这不是「禁止他查看」—— 见类注释
     *
     * @return 是否真的删掉了一条
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean revoke(String datasetCode, String recordId, SubjectKey subject) {
        RecordShareDO existing = findOne(datasetCode, recordId, subject);
        if (existing == null) {
            return false;
        }
        recordShareMapper.deleteById(existing.getId());
        return true;
    }

    /**
     * 查某条记录被分享给了谁（含已过期，供管理界面续期）
     */
    @DataPermission(enable = false) // 查「这条被分享给谁」本身不该再被行级权限过滤一遍
    public List<RecordShareDO> listByRecord(String datasetCode, String recordId) {
        if (StrUtil.isBlank(datasetCode) || StrUtil.isBlank(recordId)) {
            return List.of();
        }
        return recordShareMapper.selectListByRecord(datasetCode, recordId);
    }

    /**
     * 批量分享给多个主体
     *
     * @return 成功的条数
     */
    @Transactional(rollbackFor = Exception.class)
    public int shareToAll(String datasetCode, String recordId, Collection<SubjectKey> subjects,
                          LocalDateTime expireTime, String remark) {
        if (subjects == null || subjects.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (SubjectKey subject : subjects) {
            if (subject != null) {
                share(datasetCode, recordId, subject, expireTime, remark);
                count++;
            }
        }
        return count;
    }

    /**
     * 按数据集编码找资源行
     *
     * ⚠️ 两种形态都要认，与 {@code DataRowFilterResolver#resolveDatasetCode} 互为反函数：
     * 在线表单是 {@code infra:form-design-run:<code>}，原生数据集的编码本身就是权限串。
     * 只认一种的话，另一种的分享会因为「找不到数据集」而一律被拒
     */
    private ResourceDO findDatasetResource(String datasetCode) {
        for (String permission : List.of(
                DatasetResourceNaming.dataset(datasetCode), datasetCode)) {
            List<ResourceDO> list = resourceMapper.selectListByPermission(permission);
            for (ResourceDO resource : list) {
                if (StrUtil.isNotBlank(resource.getDatasetTable())) {
                    // 要求绑了表 = 它确实是数据集节点，而不是同名的菜单/动作节点
                    return resource;
                }
            }
        }
        return null;
    }

    private RecordShareDO findOne(String datasetCode, String recordId, SubjectKey subject) {
        for (RecordShareDO share : recordShareMapper.selectListByRecord(datasetCode, recordId)) {
            if (share.subjectMatches(subject)) {
                return share;
            }
        }
        return null;
    }

    /**
     * 便捷入口：分享给某个用户
     */
    public Long shareToUser(String datasetCode, String recordId, Long userId,
                            LocalDateTime expireTime, String remark) {
        return share(datasetCode, recordId, SubjectKey.of(SubjectTypeEnum.USER, userId),
                expireTime, remark);
    }

}
