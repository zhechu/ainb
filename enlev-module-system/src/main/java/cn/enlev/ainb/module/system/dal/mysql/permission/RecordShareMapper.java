package cn.enlev.ainb.module.system.dal.mysql.permission;

import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.system.dal.dataobject.permission.RecordShareDO;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Mapper
public interface RecordShareMapper extends BaseMapperX<RecordShareDO> {

    /**
     * 查某个数据集上、分享给这批主体的记录
     *
     * <h3>⚠️ 过期口径必须与 sys_grant 一致</h3>
     *
     * {@code expire_time IS NULL OR expire_time > now}，与
     * {@code GrantMapper#selectListByResourceIds} 逐字相同。
     * 两处不一致会出现「授权过期了但共享还在」这种没人能解释的状态。
     *
     * <h3>为什么主体条件按类型分组拼，而不是一串 (type,ref) 的 OR</h3>
     *
     * 闭包里通常有十几个主体（用户 + 角色 + 岗位 + 部门 + 用户组 + ALL）。
     * 逐个拼成 {@code (type=? AND ref=?) OR (type=? AND ref=?) ...} 的话，
     * 条件数随主体数线性增长，且索引 {@code idx_subject} 用不上。
     * 按类型归并成 {@code type=? AND ref IN (...)} 之后最多 6 个分支，
     * 每个分支都走得到索引前缀。
     */
    default List<RecordShareDO> selectListBySubjects(String datasetCode, Collection<SubjectKey> subjects) {
        if (datasetCode == null || subjects == null || subjects.isEmpty()) {
            return List.of();
        }
        // 按主体类型归并：ref 寻址的收 refId，code 寻址的收 code
        Map<String, List<Long>> refIdsByType = new LinkedHashMap<>();
        Map<String, List<String>> codesByType = new LinkedHashMap<>();
        for (SubjectKey key : subjects) {
            if (key == null) {
                continue;
            }
            if (key.type().isRefBased()) {
                if (key.refId() != null) {
                    refIdsByType.computeIfAbsent(key.type().name(), k -> new ArrayList<>()).add(key.refId());
                }
            } else if (key.code() != null) {
                codesByType.computeIfAbsent(key.type().name(), k -> new ArrayList<>()).add(key.code());
            }
        }
        if (refIdsByType.isEmpty() && codesByType.isEmpty()) {
            return List.of();
        }
        return selectList(new LambdaQueryWrapperX<RecordShareDO>()
                .eq(RecordShareDO::getDatasetCode, datasetCode)
                .and(w -> w.isNull(RecordShareDO::getExpireTime)
                        .or().gt(RecordShareDO::getExpireTime, LocalDateTime.now()))
                .and(w -> {
                    boolean first = true;
                    for (Map.Entry<String, List<Long>> entry : refIdsByType.entrySet()) {
                        LambdaQueryWrapper<RecordShareDO> branch = first ? w : w.or();
                        first = false;
                        branch.nested(b -> b.eq(RecordShareDO::getSubjectType, entry.getKey())
                                .in(RecordShareDO::getSubjectRefId, entry.getValue()));
                    }
                    for (Map.Entry<String, List<String>> entry : codesByType.entrySet()) {
                        LambdaQueryWrapper<RecordShareDO> branch = first ? w : w.or();
                        first = false;
                        branch.nested(b -> b.eq(RecordShareDO::getSubjectType, entry.getKey())
                                .in(RecordShareDO::getSubjectCode, entry.getValue()));
                    }
                }));
    }

    /**
     * 查某条记录被分享给了谁（共享管理界面用）
     *
     * ⚠️ 不过滤过期：管理界面要能看到「已过期」的共享并续期，
     * 过滤掉的话用户会以为自己从来没分享过
     */
    default List<RecordShareDO> selectListByRecord(String datasetCode, String recordId) {
        return selectList(new LambdaQueryWrapperX<RecordShareDO>()
                .eq(RecordShareDO::getDatasetCode, datasetCode)
                .eq(RecordShareDO::getRecordId, recordId));
    }

}
