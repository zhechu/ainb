package cn.enlev.ainb.module.system.dal.mysql.permission;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.grant.GrantPageReqVO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.GrantDO;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectTypeEnum;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Mapper
public interface GrantMapper extends BaseMapperX<GrantDO> {

    /**
     * 按资源 id 列表批量查授权，含通配符动作（action='*'）
     *
     * 决策引擎的入口查询。同时拉 context=NULL（全局）和 context=指定值的行，
     * 在内存里做优先级排列，让 SQL 尽量简单，索引友好
     */
    default List<GrantDO> selectListByResourceIds(Collection<Long> resourceIds) {
        return selectList(new LambdaQueryWrapperX<GrantDO>()
                .in(GrantDO::getResourceId, resourceIds)
                .and(w -> w.isNull(GrantDO::getExpireTime)
                        .or().gt(GrantDO::getExpireTime, LocalDateTime.now())));
    }

    /**
     * 按主体查该主体被授予的全部授权（授权管理页使用）
     */
    default List<GrantDO> selectListBySubject(SubjectKey key) {
        LambdaQueryWrapperX<GrantDO> wrapper = new LambdaQueryWrapperX<GrantDO>()
                .eq(GrantDO::getSubjectType, key.type().name());
        if (key.type().isRefBased()) {
            wrapper.eq(GrantDO::getSubjectRefId, key.refId());
        } else {
            wrapper.eq(GrantDO::getSubjectCode, key.code());
        }
        return selectList(wrapper);
    }

    /**
     * 按「主体元组 + 资源 id 集合」查既有授权行，供 upsert 判键用
     *
     * <h3>返回的是【超集】</h3>
     *
     * 不筛 action / contextResourceId，精确匹配由调用方在内存里按
     * {@code (subjectType, subjectRefId, subjectCode, resourceId, contextResourceId, action)}
     * 六元组分组完成（见 {@code GrantAdminServiceImpl} 里的 GrantKey record）。
     * 六元组里有三个可空列，用 SQL 表达 null 相等太啰嗦；内存里用 record 的 equals
     * （逐字段 Objects.equals，null 安全）一行就够。
     *
     * 这样做的代价是多传回同一批资源上的其它动作行 —— 授权页一批提交通常是同一个角色的
     * 十几个资源，多出来的行数在几十量级，换掉的是「200 项 = 200 次 DB 往返」
     * 和「六元组 OR 拼接到优化器放弃 idx_subject」两个更糟的选项。
     *
     * <h3>🔴 可空列必须走 isNull 分支</h3>
     *
     * {@code eq(field, null)} 生成的是 {@code field = null}，SQL 三值逻辑下恒为 UNKNOWN，
     * 整个 WHERE 恒假。表现是「库里明明有重复行，upsert 却判成新增」，每提交一次多插一行 ——
     * <b>恰好批量制造 upsert 想消灭的那种重复</b>。三个列都踩得到：
     * subjectRefId（SYSTEM/ALL 主体为 null）、subjectCode（ROLE/USER 主体为 null）、
     * contextResourceId（全局授权为 null）。
     *
     * <h3>⚠️ 刻意不过滤 expireTime</h3>
     *
     * 已过期的同键行也必须命中 —— 否则「给过期授权续期」会在过期行旁边插出第二行同键授权，
     * 而续期恰是编辑授权的常规操作（本方法不过滤，与 {@link #selectListByResourceIds}
     * 那条决策路径的过滤是两回事）
     *
     * <h3>⚠️ subjectCode 的空串与 NULL 必须一起捞</h3>
     *
     * 内存里的判键把 {@code ""} 归一化成 null（见 {@code GrantAdminServiceImpl} 的
     * {@code GrantKey#normalizeCode}），但归一化只能作用于「查回来之后」——
     * 若这里只写 {@code isNull(subject_code)}，库里那行 {@code ''} 永远进不了超集，
     * 归一化就成了没有观察效果的装饰，提交两次 {@code ""} 照样插出两行。
     * 所以 blank 分支要 {@code IS NULL OR = ''} 一起捞。
     *
     * 判 blank 用的函数（{@link StrUtil#emptyToNull}）必须与 GrantKey 那侧<b>逐字相同</b>：
     * 两侧口径一旦漂移（比如这里换成 {@code isNotBlank}），空白串就会一边进 blank 分支、
     * 一边留在 key 里，超集查回来却在内存里匹配不上 —— 症状与上面那个恒假陷阱一模一样
     */
    default List<GrantDO> selectListBySubjectAndResourceIds(String subjectType, Long subjectRefId,
                                                            String subjectCode,
                                                            Collection<Long> resourceIds) {
        if (resourceIds == null || resourceIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapperX<GrantDO> wrapper = new LambdaQueryWrapperX<GrantDO>()
                .eq(GrantDO::getSubjectType, subjectType)
                .in(GrantDO::getResourceId, resourceIds);
        if (subjectRefId != null) {
            wrapper.eq(GrantDO::getSubjectRefId, subjectRefId);
        } else {
            wrapper.isNull(GrantDO::getSubjectRefId);
        }
        String code = StrUtil.emptyToNull(subjectCode);
        if (code != null) {
            wrapper.eq(GrantDO::getSubjectCode, code);
        } else {
            wrapper.and(w -> w.isNull(GrantDO::getSubjectCode)
                    .or().eq(GrantDO::getSubjectCode, StrUtil.EMPTY));
        }
        return selectList(wrapper);
    }

    /**
     * 按<b>一批主体</b>查未过期的授权
     *
     * <h3>为什么按主体查，而不是按资源 id 批量查</h3>
     *
     * 「列出某人被允许的全部权限」这个问题，按资源查要传进去全部资源 id
     * （本项目 1900+ 个），MySQL 面对上千元素的 IN 列表会放弃 {@code idx_resource}
     * 转成全表扫描 —— 实测一次 36 秒（在 purge 被长事务堵住、表里堆着死行时尤其明显）。
     *
     * 而按主体查是<b>高选择性</b>的：一个用户的主体闭包通常就几个，
     * 命中 {@code idx_subject(tenant_id, subject_type, subject_ref_id)}，
     * 返回的行数与「这个人被授过多少权」成正比，与系统里有多少资源无关。
     *
     * ⚠️ SYSTEM / ALL 这类没有 refId 的主体用 code 寻址，所以两种条件要分开拼。
     */
    default List<GrantDO> selectListActiveBySubjects(Collection<SubjectKey> subjects) {
        LambdaQueryWrapperX<GrantDO> wrapper = buildSubjectWrapper(subjects);
        if (wrapper == null) {
            return List.of();
        }
        // ⚠️ 就地追加，不要写成 selectList(wrapper.and(...))：
        // MP 的 and() 声明返回的是父类 Children（LambdaQueryWrapper），赋不回 LambdaQueryWrapperX
        wrapper.and(w -> w.isNull(GrantDO::getExpireTime)
                .or().gt(GrantDO::getExpireTime, LocalDateTime.now()));
        return selectList(wrapper);
    }

    /**
     * 按<b>一批主体</b>查授权，<b>不过滤过期</b>
     *
     * <h3>为什么不能复用 {@link #selectListActiveBySubjects}</h3>
     *
     * 生效权限树（{@code GrantDecisionService#listEffectiveTree}）要在汇总里报
     * 「另有 Y 条授权已过期，未计入」—— 否则树形节点数和列表页行数对不上，会被当成 bug。
     * 那条 SQL 已经把过期行滤掉了，数不出来。
     *
     * 顺带把过期口径统一到判定侧：本方法捞回全部行，由 {@code GrantDO#isActive()} 决定谁参与判定。
     * 这比在 SQL 里再写一份 {@code expire_time > now()} 好 —— 内存侧是
     * {@code !(now.isAfter(expireTime))}，两边差一个等号（{@code expire_time == now}
     * 在 SQL 侧算过期、在内存侧算有效）。实务无感，但两份口径就是两份漂移的机会。
     *
     * 行数不是问题：按主体查是高选择性的，单角色最多几百行（实测超管 733 行）。
     */
    default List<GrantDO> selectListBySubjects(Collection<SubjectKey> subjects) {
        LambdaQueryWrapperX<GrantDO> wrapper = buildSubjectWrapper(subjects);
        return wrapper == null ? List.of() : selectList(wrapper);
    }

    /**
     * 拼「主体元组 OR」条件，供上面两个方法共用
     *
     * ⚠️ 返回 null 表示主体集合为空 —— 调用方必须直接返回空列表。
     * 🔴 千万别在这里返回一个空 wrapper：空 wrapper 等于没有 WHERE，会全表扫出所有主体的授权行，
     * 而权限判定的调用方拿到这批行只会得出「什么都有权限」
     */
    private static LambdaQueryWrapperX<GrantDO> buildSubjectWrapper(Collection<SubjectKey> subjects) {
        if (subjects == null || subjects.isEmpty()) {
            return null;
        }
        List<SubjectKey> refBased = new ArrayList<>();
        List<SubjectKey> codeBased = new ArrayList<>();
        for (SubjectKey key : subjects) {
            (key.type().isRefBased() ? refBased : codeBased).add(key);
        }
        LambdaQueryWrapperX<GrantDO> wrapper = new LambdaQueryWrapperX<>();
        wrapper.and(outer -> {
            // 每个主体是一组 (type, refId) 或 (type, code) 的组合条件，彼此 OR
            boolean[] first = {true};
            for (SubjectKey key : refBased) {
                if (first[0]) {
                    first[0] = false;
                    outer.and(w -> w.eq(GrantDO::getSubjectType, key.type().name())
                            .eq(GrantDO::getSubjectRefId, key.refId()));
                } else {
                    outer.or(w -> w.eq(GrantDO::getSubjectType, key.type().name())
                            .eq(GrantDO::getSubjectRefId, key.refId()));
                }
            }
            for (SubjectKey key : codeBased) {
                if (first[0]) {
                    first[0] = false;
                    outer.and(w -> w.eq(GrantDO::getSubjectType, key.type().name())
                            .eq(GrantDO::getSubjectCode, key.code()));
                } else {
                    outer.or(w -> w.eq(GrantDO::getSubjectType, key.type().name())
                            .eq(GrantDO::getSubjectCode, key.code()));
                }
            }
        });
        return wrapper;
    }

    /**
     * 按主体分页查授权（授权配置页列表用）
     *
     * 不过滤 expireTime：已过期的行也要展示出来，界面标「已过期」，续期/删除是常规操作
     *
     * @param resourceIds 资源名称/权限标识模糊匹配解析出的资源 id 集合；null 表示不按资源过滤
     */
    default PageResult<GrantDO> selectPage(GrantPageReqVO reqVO, Collection<Long> resourceIds) {
        return selectPage(reqVO, new LambdaQueryWrapperX<GrantDO>()
                .eqIfPresent(GrantDO::getSubjectType, reqVO.getSubjectType())
                .eqIfPresent(GrantDO::getSubjectRefId, reqVO.getSubjectRefId())
                .eqIfPresent(GrantDO::getSubjectCode, reqVO.getSubjectCode())
                .eqIfPresent(GrantDO::getResourceId, reqVO.getResourceId())
                .inIfPresent(GrantDO::getResourceId, resourceIds)
                .eqIfPresent(GrantDO::getAction, reqVO.getAction())
                .eqIfPresent(GrantDO::getEffect, reqVO.getEffect())
                .betweenIfPresent(GrantDO::getExpireTime, reqVO.getExpireTime())
                .orderByDesc(GrantDO::getId));
    }

    /**
     * 按资源删除（资源被删时级联清掉）
     */
    default void deleteByResourceId(Long resourceId) {
        delete(new LambdaQueryWrapperX<GrantDO>()
                .eq(GrantDO::getResourceId, resourceId));
    }

    /**
     * 按资源批量删除（资源被整棵子树注销时级联清掉）
     *
     * <p>⚠️ 连 {@code context_resource_id} 一起清：授权行的「上下文」指向的同样是一个资源，
     * 只清 {@code resource_id} 会留下「上下文指向一个已删资源」的行。
     * 那种行在授权页上查不出来（页面按 resource_id 关联），却仍会被
     * 按上下文精配的判定路径读到 —— 属于典型的看不见的脏数据。
     *
     * @return 删除的行数
     */
    default int deleteByResourceIds(Collection<Long> resourceIds) {
        if (resourceIds == null || resourceIds.isEmpty()) {
            return 0;
        }
        // 显式用 and(...) 把 OR 括起来：@TableLogic 会把 deleted=0 拼在 wrapper 的 SQL 片段之前，
        // 顶层裸写 or() 的话最终形状取决于 MP 有没有替我加括号 —— 一旦没加，
        // 就成了「deleted=0 AND resource_id IN (...) OR context_resource_id IN (...)」，
        // 后半个分支连软删行一起命中。不赌它，自己括
        return delete(new LambdaQueryWrapperX<GrantDO>()
                .and(wrapper -> wrapper.in(GrantDO::getResourceId, resourceIds)
                        .or()
                        .in(GrantDO::getContextResourceId, resourceIds)));
    }

    /**
     * 这个资源上有没有人配过授权
     *
     * ⚠️ 不过滤 expireTime：已过期的授权行也算「有人配过」。
     * 用它判断一个列/字段资源能不能回收 —— 回收等于把管理员配过的东西删掉，
     * 而过期只是暂时不生效，续期是常规操作，删了就找不回来了
     */
    default Long selectCountByResourceId(Long resourceId) {
        return selectCount(GrantDO::getResourceId, resourceId);
    }

    /**
     * 批量统计这批资源上的授权行数，口径与 {@link #selectCountByResourceId} 完全一致
     * （含通配符动作、含已过期），只是一次查多个 id。
     *
     * 供批量删除的「全有或全无」校验用：只要这批里任意一行有授权，整批都拒绝删除。
     */
    default Long selectCountByResourceIds(Collection<Long> resourceIds) {
        if (resourceIds == null || resourceIds.isEmpty()) {
            return 0L;
        }
        return selectCount(new LambdaQueryWrapperX<GrantDO>()
                .in(GrantDO::getResourceId, resourceIds));
    }

    /**
     * 查某角色的「套餐型」授权行：ALLOW + 无上下文，动作可选过滤
     *
     * 给租户套餐重算用。<b>刻意不包含 DENY 行和带 context_resource_id 的行</b> ——
     * 那些是管理员显式收紧 / 按上下文精配的结果，套餐重算把它们删掉会造成权限放大。
     *
     * 不过滤 expireTime：带有效期的套餐授权同样属于要对齐的对象，
     * 漏掉它会在过期后留下一条永远删不掉的孤儿行
     *
     * <h3>{@code action} 传 null 表示不限动作</h3>
     *
     * 塌缩（{@code enlev.permission.collapse-enabled}）打开后必须传 null：
     * 那时同一个资源上会同时存在 VIEW / CREATE / UPDATE … 多行套餐授权，
     * 只查 VIEW 的话，重算的第 3 步<b>看不见</b>其余动作的行 ——
     * 于是「套餐里取消勾选某个按钮」在 VIEW 上收得回、在具体动作上永远收不回，
     * 而且不报错。列本身是 {@code NOT NULL}，所以 null 只会去掉这个过滤条件，
     * 不会拼出 {@code action IS NULL} 这种恒假条件。
     *
     * @param action 动作码；null 表示不限
     */
    default List<GrantDO> selectListPackageStyleByRole(Long roleId, String action) {
        return selectList(new LambdaQueryWrapperX<GrantDO>()
                .eq(GrantDO::getSubjectType, SubjectTypeEnum.ROLE.name())
                .eq(GrantDO::getSubjectRefId, roleId)
                .eqIfPresent(GrantDO::getAction, action)
                .eq(GrantDO::getEffect, "ALLOW")
                .isNull(GrantDO::getContextResourceId));
    }

}
