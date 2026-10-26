package cn.enlev.ainb.module.system.service.permission;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.grant.GrantBatchSaveRespVO;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.grant.GrantSaveReqVO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.GrantDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.GrantMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper;
import cn.enlev.ainb.module.system.dal.redis.RedisKeyConstants;
import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.framework.common.util.collection.CollectionUtils.convertList;
import static cn.enlev.ainb.framework.common.util.collection.CollectionUtils.convertSet;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.GRANT_BATCH_KEY_DUPLICATE;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.GRANT_DUPLICATE_CONFLICT;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.GRANT_NOT_EXISTS;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.GRANT_UPDATE_ID_REQUIRED;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.RESOURCE_NOT_EXISTS;

/**
 * {@link GrantAdminService} 实现类
 *
 * <h3>单条与批量共用一条 upsert 路径</h3>
 *
 * {@link #upsertGrant} 就是 size=1 的 {@link #createGrantList}，两者都委托 {@link #doUpsert}。
 * 「批量与单条校验口径一致」不是靠两处各写一遍再人工对齐，而是<b>结构上只有一份</b> ——
 * 这是抽 Service 的首要动机，也是前端做笛卡尔展开、后端只收
 * {@code List<GrantSaveReqVO>} 的原因。
 *
 * <h3>缓存驱逐的两种写法都对，别去「统一」它</h3>
 *
 * 这里用裸名 {@code GRANT_USER_PERMISSIONS}，而 {@code PermissionServiceImpl} /
 * {@code ResourceServiceImpl} 写的是 {@code GRANT_USER_PERMISSIONS + "#30s"}。
 * {@code TimeoutRedisCacheManager#createRedisCache} 会把 {@code #30s} 剥掉再建 RedisCache，
 * 所以两种写法指向<b>同一个</b> Redis key 前缀，{@code allEntries = true} 清的是同一批键。
 * TTL 后缀只对 {@code @Cacheable} 有意义，驱逐时写上纯属噪声 ——
 * 保持搬移前 Controller 上的原样，是为了让这次改动的 diff 里不混入无关变化
 *
 * @author 走进新时代
 */
@Service
@Slf4j
public class GrantAdminServiceImpl implements GrantAdminService {

    @Resource
    private GrantMapper grantMapper;
    @Resource
    private ResourceMapper resourceMapper;

    @Override
    @DSTransactional
    @Caching(evict = {
            @CacheEvict(value = RedisKeyConstants.GRANT_USER_PERMISSIONS, allEntries = true),
            @CacheEvict(value = RedisKeyConstants.GRANT_USER_MENU_IDS, allEntries = true)
    })
    public Long upsertGrant(GrantSaveReqVO reqVO) {
        return doUpsert(List.of(reqVO)).singleId();
    }

    @Override
    @DSTransactional
    @Caching(evict = {
            @CacheEvict(value = RedisKeyConstants.GRANT_USER_PERMISSIONS, allEntries = true),
            @CacheEvict(value = RedisKeyConstants.GRANT_USER_MENU_IDS, allEntries = true)
    })
    public GrantBatchSaveRespVO createGrantList(List<GrantSaveReqVO> items) {
        UpsertResult result = doUpsert(items);
        GrantBatchSaveRespVO respVO = new GrantBatchSaveRespVO();
        respVO.setInsertedCount(result.insertedCount());
        respVO.setUpdatedCount(result.updatedCount());
        respVO.setConvergedCount(result.convergedCount());
        return respVO;
    }

    @Override
    @DSTransactional
    @Caching(evict = {
            @CacheEvict(value = RedisKeyConstants.GRANT_USER_PERMISSIONS, allEntries = true),
            @CacheEvict(value = RedisKeyConstants.GRANT_USER_MENU_IDS, allEntries = true)
    })
    public void updateGrant(GrantSaveReqVO reqVO) {
        // 旧实现这里抛的是裸 IllegalArgumentException：没有错误码，全局异常处理器只能兜成
        // 通用文案，前端无法按码提示。改走 ServiceException 与其余校验口径一致
        if (reqVO.getId() == null) {
            throw exception(GRANT_UPDATE_ID_REQUIRED);
        }
        // 新增的两条存在性校验（旧实现是 updateById 直接打，改一个不存在的 id 会静默返回 true）：
        // 列表页勾选后数据可能已陈旧，静默成功会让管理员以为改到了
        if (grantMapper.selectById(reqVO.getId()) == null) {
            throw exception(GRANT_NOT_EXISTS);
        }
        validateResourcesExist(List.of(reqVO));
        grantMapper.updateById(BeanUtils.toBean(reqVO, GrantDO.class));
    }

    @Override
    @DSTransactional
    @Caching(evict = {
            @CacheEvict(value = RedisKeyConstants.GRANT_USER_PERMISSIONS, allEntries = true),
            @CacheEvict(value = RedisKeyConstants.GRANT_USER_MENU_IDS, allEntries = true)
    })
    public void deleteGrantList(List<Long> ids) {
        // 空列表幂等成功，与 ResourceAdminController#deleteResourceList 同口径。
        // ⚠️ 与 createGrantList 的 @NotEmpty 刻意不对称，理由见 GrantBatchSaveReqVO#items
        if (CollUtil.isEmpty(ids)) {
            return;
        }
        // 前端表格勾选可能把同一 id 传两次；不去重会让 size 比对把「重复」误判成「不存在」
        List<Long> distinctIds = ids.stream().distinct().toList();
        // 全有或全无：批量删除的价值恰恰在这条校验上 —— 勾选后数据可能已被另一个管理员
        // 或另一个标签页改过，静默部分成功会让操作者以为整批都删了
        if (grantMapper.selectByIds(distinctIds).size() != distinctIds.size()) {
            throw exception(GRANT_NOT_EXISTS);
        }
        grantMapper.deleteByIds(distinctIds);
        log.info("[deleteGrantList][批量删除授权行 {} 条]", distinctIds.size());
    }

    // ========== upsert 核心 ==========

    /**
     * 单条与批量共用的 upsert 实现
     *
     * <p>五步：资源存在性 → 批内判重 → 一次查一批既有行 → 逐项分派（插入 / 覆盖+收敛）→ 落库。
     * 前三步任意一步不过就抛异常，配合方法上的 {@code @DSTransactional} 保证整批一行不落。
     */
    private UpsertResult doUpsert(List<GrantSaveReqVO> items) {
        if (CollUtil.isEmpty(items)) {
            return new UpsertResult(0, 0, 0, null);
        }
        // 1. 资源存在性（全有或全无）
        validateResourcesExist(items);

        // 2. 批内判重：同一批里出现两个相同键，说明前端展开逻辑出了问题
        //（笛卡尔积本身不会产生重复键，除非勾了重复资源）。放过去的话后一项会覆盖前一项，
        // 用户看到的计数与实际落库行数对不上
        Map<GrantKey, GrantSaveReqVO> byKey = new LinkedHashMap<>();
        for (GrantSaveReqVO item : items) {
            GrantKey key = GrantKey.of(item);
            if (byKey.putIfAbsent(key, item) != null) {
                throw exception(GRANT_BATCH_KEY_DUPLICATE, item.getResourceId(), item.getAction());
            }
        }

        // 3. 一次查一批既有行（按主体元组分组，UI 场景下恒为 1 组 1 次查询）
        Map<GrantKey, List<GrantDO>> existingByKey = loadExistingByKey(items);

        // 4. 逐项分派
        boolean single = byKey.size() == 1;
        List<GrantDO> toInsert = new ArrayList<>();
        List<Long> convergeDeleteIds = new ArrayList<>();
        Long survivorId = null;
        for (Map.Entry<GrantKey, GrantSaveReqVO> entry : byKey.entrySet()) {
            GrantDO grant = BeanUtils.toBean(entry.getValue(), GrantDO.class);
            List<GrantDO> matched = existingByKey.get(entry.getKey());
            if (CollUtil.isEmpty(matched)) {
                // 🔴 必须显式 setId(null)：GrantSaveReqVO 与 /update 共用，id 的语义是
                // 「修改时必填」，新增路径<b>不能信任客户端传来的 id</b> ——
                // 带着 id 走 insert 会直插该主键，撞上既有行（含软删行）就是主键冲突，
                // 而撞上的是别人的行时更糟：静默改掉了一条不相干的授权
                grant.setId(null);
                toInsert.add(grant);
                continue;
            }
            // 命中既有行 → 覆盖。命中多行时先过一致性守卫，再收敛到 min(id)
            validateConvergeable(matched);
            GrantDO survivor = matched.stream()
                    .min(Comparator.comparing(GrantDO::getId)).orElseThrow();
            grant.setId(survivor.getId());
            // updateById 就够清空字段：ruleId / fieldMode / expireTime 在 GrantDO 上
            // 已标 @TableField(updateStrategy = FieldStrategy.ALWAYS)，null 会真的写进去
            grantMapper.updateById(grant);
            survivorId = survivor.getId();
            for (GrantDO extra : matched) {
                if (!extra.getId().equals(survivor.getId())) {
                    convergeDeleteIds.add(extra.getId());
                }
            }
        }

        // 5. 收敛掉的历史重复行 + 插入新行
        if (CollUtil.isNotEmpty(convergeDeleteIds)) {
            grantMapper.deleteByIds(convergeDeleteIds);
        }
        Long singleId = single ? survivorId : null;
        if (CollUtil.isNotEmpty(toInsert)) {
            if (toInsert.size() == 1) {
                // ⚠️ 单行走 insert 而不是 insertBatch：/create 要把 id 返给前端，
                // insertBatch 底层是 Db.saveBatch（批量执行器），主键回填不如单条 insert 确定。
                // 多行时走 insertBatch 且<b>不返回 id</b> —— 批量响应只有计数，这个不对称是刻意的
                GrantDO only = toInsert.get(0);
                grantMapper.insert(only);
                if (single) {
                    singleId = only.getId();
                }
            } else {
                grantMapper.insertBatch(toInsert);
            }
        }
        log.info("[doUpsert][提交 {} 项：新增 {}、覆盖 {}、收敛 {}]",
                byKey.size(), toInsert.size(), byKey.size() - toInsert.size(), convergeDeleteIds.size());
        return new UpsertResult(toInsert.size(), byKey.size() - toInsert.size(),
                convergeDeleteIds.size(), singleId);
    }

    /**
     * 校验这批授权项指向的资源都存在，全有或全无
     *
     * <p>⚠️ 只校验 {@code resourceId}，<b>不校验 {@code contextResourceId}</b>：
     * 后者当前没有任何 UI 入口会填（单条表单与批量表单都不提供），
     * 给它加校验等于在本次改动里顺带改变 {@code /create} 对既有调用方的行为，超出范围
     */
    private void validateResourcesExist(Collection<GrantSaveReqVO> items) {
        Set<Long> resourceIds = convertSet(items, GrantSaveReqVO::getResourceId);
        if (resourceIds.isEmpty()) {
            return;
        }
        if (resourceMapper.selectByIds(resourceIds).size() != resourceIds.size()) {
            throw exception(RESOURCE_NOT_EXISTS);
        }
    }

    /**
     * 按「主体元组」分组，每组一次超集查询，再在内存里按精确六元组归类
     *
     * <p>不逐项查（200 项 = 200 次 DB 往返），也不拼 OR 大 SQL（六元组含三个可空列，
     * 复杂到优化器会放弃 {@code idx_subject}）。UI 批量提交的主体恒为同一个角色，
     * 所以这里实际就是 1 组、1 次查询；分组是为了让「一批里混了多个主体」也正确 ——
     * 例如将来开放「给多个角色一次授权」
     */
    private Map<GrantKey, List<GrantDO>> loadExistingByKey(Collection<GrantSaveReqVO> items) {
        Map<SubjectTuple, Set<Long>> resourceIdsBySubject = new LinkedHashMap<>();
        for (GrantSaveReqVO item : items) {
            resourceIdsBySubject.computeIfAbsent(SubjectTuple.of(item), k -> new LinkedHashSet<>())
                    .add(item.getResourceId());
        }
        Map<GrantKey, List<GrantDO>> result = new HashMap<>();
        for (Map.Entry<SubjectTuple, Set<Long>> entry : resourceIdsBySubject.entrySet()) {
            SubjectTuple subject = entry.getKey();
            List<GrantDO> rows = grantMapper.selectListBySubjectAndResourceIds(
                    subject.subjectType(), subject.subjectRefId(), subject.subjectCode(),
                    entry.getValue());
            for (GrantDO row : rows) {
                result.computeIfAbsent(GrantKey.of(row), k -> new ArrayList<>()).add(row);
            }
        }
        return result;
    }

    /**
     * 同键多行的收敛前置检查：只在「决策相关字段」逐行一致时才允许收敛
     *
     * <h3>🔴 安全语义</h3>
     *
     * {@code GrantDecisionEngine#isAllowed} 对 DENY 用 anyMatch 判定 ——
     * 任意一行 DENY 否决所有 ALLOW。若同键行里一行 DENY 一行 ALLOW，
     * 收敛（只留 min(id)）等于<b>替用户决定那条 DENY 该不该删</b>：
     * 删了是放权，不删则他刚提交的「覆盖」不生效。两种结果都不是 upsert 的语义，
     * 只能拒绝，让人来清。
     *
     * <p>{@code ruleId} / {@code fieldMode} 一并检查：它们决定行过滤与字段可见性的合并结果，
     * 不一致时收敛同样静默改变语义（比如删掉一行绑了规则的 ALLOW，留下不受限的那行）。
     *
     * <p>{@code expireTime} <b>刻意不查</b>：存活行马上会被提交值整体覆盖，
     * 过期时间的口径以本次提交为准；保留多余行反而会让「设置到期时间」的意图
     * 被一行更晚过期的旧拷贝架空。
     *
     * <h3>现状备注</h3>
     *
     * 本库实存的 15 组同键重复行已逐组核实为三份完全相同的拷贝
     * （effect / ruleId / fieldMode / expireTime 全同），收敛它们语义无损。
     * 这道守卫防的是<b>将来</b> —— 表上没有唯一索引（软删模型下加了会被已删行占住键，
     * 删掉一条授权再重新授权就撞唯一键），同键不同内容的行随时可能被并发写入造出来。
     */
    private void validateConvergeable(List<GrantDO> matched) {
        if (matched.size() <= 1) {
            return;
        }
        GrantDO first = matched.get(0);
        boolean homogeneous = matched.stream().allMatch(grant ->
                Objects.equals(grant.getEffect(), first.getEffect())
                        && Objects.equals(grant.getRuleId(), first.getRuleId())
                        && Objects.equals(grant.getFieldMode(), first.getFieldMode()));
        if (!homogeneous) {
            throw exception(GRANT_DUPLICATE_CONFLICT, convertList(matched, GrantDO::getId));
        }
    }

    // ========== 内部值对象 ==========

    /**
     * upsert 判键用的六元组
     *
     * <p>用 record 而不是拼字符串 key：record 的 equals 逐字段 {@code Objects.equals}，
     * 三个可空列（subjectRefId / subjectCode / contextResourceId）天然参与相等性比较，
     * 不需要发明 null 占位符 —— 拼字符串时 {@code "null"} 与真实值 {@code "null"}
     * 撞车这类坑就是这么来的
     */
    private record GrantKey(String subjectType, Long subjectRefId, String subjectCode,
                            Long resourceId, Long contextResourceId, String action) {

        static GrantKey of(GrantSaveReqVO vo) {
            return new GrantKey(vo.getSubjectType(), vo.getSubjectRefId(),
                    normalizeCode(vo.getSubjectCode()), vo.getResourceId(),
                    vo.getContextResourceId(), vo.getAction());
        }

        static GrantKey of(GrantDO grant) {
            return new GrantKey(grant.getSubjectType(), grant.getSubjectRefId(),
                    normalizeCode(grant.getSubjectCode()), grant.getResourceId(),
                    grant.getContextResourceId(), grant.getAction());
        }

        /**
         * 空串归一化为 null
         *
         * <p>⚠️ 键的<b>两侧必须用同一个归一化</b>：库里既有 {@code subject_code IS NULL}
         * 也可能有 {@code ''}（历史写入路径不一致），不归一的话 {@code ''} 与 null
         * 会被判成两个不同的键，upsert 照样插出重复行
         *
         * <p>⚠️ 光在这里归一还不够 —— 超集查询也必须能把 {@code ''} 那行捞回来，
         * 否则本方法永远看不到它，归一化就成了没有观察效果的装饰。
         * 见 {@code GrantMapper#selectListBySubjectAndResourceIds} 的 blank 分支，
         * 两侧用的都是 {@link StrUtil#emptyToNull}，改一处必须改另一处
         */
        private static String normalizeCode(String code) {
            return StrUtil.emptyToNull(code);
        }
    }

    /**
     * 主体元组，用于把一批授权项按主体分组查询
     *
     * <p>同样是 record，理由与 {@link GrantKey} 一致：null 安全的 equals，
     * 替代 {@code List<Object>} + {@code String.valueOf} 那种丑写法
     */
    private record SubjectTuple(String subjectType, Long subjectRefId, String subjectCode) {

        static SubjectTuple of(GrantSaveReqVO vo) {
            return new SubjectTuple(vo.getSubjectType(), vo.getSubjectRefId(),
                    StrUtil.emptyToNull(vo.getSubjectCode()));
        }
    }

    /**
     * upsert 的结果计数
     *
     * @param singleId 只在<b>单项</b>提交时有值：新插入行的 id，或被覆盖的存活行 id。
     *                 批量提交时为 null —— 批量响应只有三个计数，逐项返回 id
     *                 既没有消费方，也会让「单行走 insert、多行走 insertBatch」
     *                 这个实现细节泄漏到接口契约上
     */
    private record UpsertResult(int insertedCount, int updatedCount, int convergedCount,
                                Long singleId) {
    }

}
