package cn.enlev.ainb.module.system.framework.permission.core.scan;

import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.module.system.dal.dataobject.permission.GrantDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.framework.permission.core.scan.ResourceCollapsePlanner.CollapsePlan;
import cn.enlev.ainb.module.system.framework.permission.core.scan.ResourceCollapsePlanner.GrantRewrite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把 {@link CollapsePlan} 应用到一份资源/授权快照上，得到「迁移后的世界」
 *
 * <h3>🔴 为什么它必须是纯函数，而不是直接写库的那段代码</h3>
 *
 * 阶段 2 的成败判据（自校验）是「迁移前后，对每个主体、每个原始权限串跑同一个
 * {@code GrantDecisionEngine}，差集必须为空」，而它<b>必须在落库之前</b>就能跑 ——
 * 落库是唯一不可回滚的一步。所以校验需要一份「迁移后的世界」在内存里。
 *
 * 如果内存里那份世界是校验代码自己拼的、落库那份是另一段代码写的，
 * 那校验通过证明的只是<b>校验代码自己</b>没错，与真正要执行的 DML 无关。
 * 把「哪些行活下来、新行的 id 是谁、每条授权改写成什么」这件事收进这一个函数，
 * 两侧就只剩一个差别：<b>怎么把它写进库</b>。那个差别是机械的、可用行数与校验和对账的
 */
public class ResourceCollapseApplier {

    /**
     * 为新建的身份行分配主键
     *
     * <p>计划器<b>刻意不塞假 id</b>（见 {@link CollapsePlan#getCreatedIdentities()} 的注释）：
     * 假 id 一旦与真实序列撞上，写进去的就是别人的行，而且不报错。所以 id 由执行方给：
     * <ul>
     *   <li>内存校验：从「现存最大 id + 1」开始递增，确定性、可复现</li>
     *   <li>落库：insert 之后回填数据库生成的自增 id</li>
     * </ul>
     */
    @FunctionalInterface
    public interface IdentityIdAllocator {

        /**
         * @param template 计划器给出的新建行模板（尚无 id）
         * @return 分配到的主键，不得为 null
         */
        Long nextId(ResourceDO template);
    }

    /**
     * 迁移后的世界
     *
     * <p>🔴 里面每一行都是<b>副本</b>，入参那两份列表一个字节都不会被改 ——
     * 自校验要先用原始世界算一遍「迁移前」，再用这份算「迁移后」，
     * 就地改的话两遍就是同一份数据，差集恒为空，校验形同没有
     *
     * @param resources            存活的资源行（已退役的不在里面；含新建的身份行）
     * @param grants               存活的授权行（已判为重复的不在里面；已改写的写着新值）
     * @param identityToResourceId 身份串 → 承载它的资源行 id（已有锚点 + 新建的都在这张表里）
     */
    public record CollapsedWorld(List<ResourceDO> resources,
                                 List<GrantDO> grants,
                                 Map<String, Long> identityToResourceId) {
    }

    /**
     * 应用变更集
     *
     * @param resources 迁移前<b>全部存活</b>的资源行（含停用的 —— 停用行不参与塌缩但要留着）
     * @param grants    迁移前<b>全部存活</b>的授权行
     * @param plan      {@link ResourceCollapsePlanner#plan} 的产出，必须已经 freeze
     * @param allocator 新建身份行的主键来源
     * @return 迁移后的世界；任何一处对不上都直接抛，绝不返回一份半对的世界
     */
    public static CollapsedWorld apply(List<ResourceDO> resources, List<GrantDO> grants,
                                       CollapsePlan plan, IdentityIdAllocator allocator) {
        Map<String, Long> identityToResourceId = new LinkedHashMap<>(plan.getIdentityOf());

        // ---------- 资源 ----------
        Set<Long> retired = plan.getRetiredResourceIds();
        List<ResourceDO> newResources = new ArrayList<>(
                resources.size() - retired.size() + plan.getCreatedIdentities().size());
        for (ResourceDO r : resources) {
            if (retired.contains(r.getId())) {
                continue;   // 落库时是软删；在内存世界里等价于「不再存在」
            }
            ResourceDO copy = BeanUtils.toBean(r, ResourceDO.class);
            // R1：menu_id 归属。表里只有「真的会变」的那些（见 CollapsePlan#menuIdUpdates）
            Long chosenMenuId = plan.getMenuIdUpdates().get(r.getId());
            if (chosenMenuId != null) {
                copy.setMenuId(chosenMenuId);
            }
            // 合并后该行的默认效果。表里同样只有真的会变的（锚点原本 ALLOW、成员里有更严的），
            // 理由与代价见 ResourceCollapsePlanner#applyStrictestDefaultEffect ——
            // 🔴 这张表实测是 7 行（1 个 system:dept + 6 个 infra:form-design-run:*），
            //    自校验量到它们改变了 464 个决策点，方向全是「迁移后收紧」。
            //    漏应用这一条，那 7 行就仍是 ALLOW，而成员串已经被翻译成
            //    (身份, 动词) 去查它 —— 于是这批串全部默认放行，全程 200 无异常。
            //    自校验会红（放开的点落不进 expectedIdentityGains 白名单，
            //    因为白名单只放行裸身份串），但生产上没有任何东西会报出来
            String strictestEffect = plan.getDefaultEffectUpdates().get(r.getId());
            if (strictestEffect != null) {
                copy.setDefaultEffect(strictestEffect);
            }
            newResources.add(copy);
        }
        for (Map.Entry<String, ResourceDO> entry : plan.getCreatedIdentities().entrySet()) {
            ResourceDO template = entry.getValue();
            Long id = allocator.nextId(template);
            if (id == null) {
                throw new IllegalStateException("身份行 " + entry.getKey() + " 没拿到主键");
            }
            if (identityToResourceId.put(entry.getKey(), id) != null) {
                throw new IllegalStateException("身份 " + entry.getKey() + " 同时是已有锚点和新建行");
            }
            ResourceDO copy = BeanUtils.toBean(template, ResourceDO.class);
            copy.setId(id);
            newResources.add(copy);
        }

        // ---------- 授权 ----------
        // 「迁移后仍有授权指向已退役的资源」这种形态不需要在这里再查一遍：
        // 计划器第 2 步是 resourceIdRemap 与 retiredResourceIds 同一个循环里成对写入的
        // （两者的键集恒等），第 3 步又对每条 resourceIdRemap 命中的授权强制二选一
        // （进 grantRewrites 或进 redundantGrantIds）。所以这条性质是结构性的，
        // 不是「今天实测为 0」—— 为结构性不变量写扫描代码只会多一段永远测不到的分支
        Map<Long, GrantRewrite> rewriteById = new HashMap<>(plan.getGrantRewrites().size());
        for (GrantRewrite rw : plan.getGrantRewrites()) {
            rewriteById.put(rw.getGrantId(), rw);
        }
        Set<Long> redundant = plan.getRedundantGrantIds();
        List<GrantDO> newGrants = new ArrayList<>(grants.size() - redundant.size());
        for (GrantDO g : grants) {
            if (redundant.contains(g.getId())) {
                continue;   // R10：合并后完全重复
            }
            GrantDO copy = BeanUtils.toBean(g, GrantDO.class);
            GrantRewrite rw = rewriteById.get(g.getId());
            if (rw != null) {
                Long targetResourceId = identityToResourceId.get(rw.getIdentity());
                if (targetResourceId == null) {
                    throw new IllegalStateException(
                            "授权 " + g.getId() + " 的目标身份 " + rw.getIdentity() + " 没有对应资源行");
                }
                copy.setResourceId(targetResourceId);
                copy.setAction(rw.getAction());
            } else if (!plan.getUntouchedGrantIds().contains(g.getId())) {
                // 计划器对每条授权恰好给出三种归属之一（改写 / 重复 / 原样）。
                // 落到这里说明计划器变了而这里没跟着变 —— 静默漏改一条授权就是静默改一次权限
                throw new IllegalStateException("授权 " + g.getId() + " 不在变更集的任何一类里");
            }
            newGrants.add(copy);
        }

        // ---------- 收尾自检 ----------
        assertNoDanglingParent(newResources);

        return new CollapsedWorld(
                Collections.unmodifiableList(newResources),
                Collections.unmodifiableList(newGrants),
                Collections.unmodifiableMap(identityToResourceId));
    }

    /**
     * 存活行的 {@code parent_id} 不得指向已退役的行
     *
     * <p>🔴 指到了会怎样：{@code GrantDecisionService#buildChainFromMap} 沿 parentId 上溯，
     * 找不到父节点就<b>提前停下</b> —— 于是「从分类继承放行 / 上级否决」这类语义静默改变，
     * 而判定照样返回一个看起来正常的 boolean。
     *
     * <p>实测当前库里这种情况是 <b>0 例</b>（1022 个存活行、540 个退役行，
     * 体检脚本 {@code _scratch/s2c_orphan_check.py}）。所以这里选择<b>直接抛</b>而不是自动重挂：
     * 重挂要连带重算整棵子树的 path 与 depth，而为一个当前不存在的形态写一套改写逻辑，
     * 只会让「迁移到底改了什么」这件事更难看清。真出现了，就该由人决定挂到哪
     */
    private static void assertNoDanglingParent(List<ResourceDO> resources) {
        Set<Long> alive = new HashSet<>(resources.size());
        for (ResourceDO r : resources) {
            alive.add(r.getId());
        }
        for (ResourceDO r : resources) {
            Long parentId = r.getParentId();
            if (parentId == null || ResourceDO.ID_ROOT.equals(parentId) || alive.contains(parentId)) {
                continue;
            }
            throw new IllegalStateException(String.format(
                    "资源 %d(%s) 的 parent_id=%d 迁移后已不存在（被合并软删）。"
                            + "需要先人工决定它该挂到哪个身份下，再跑迁移",
                    r.getId(), r.getPermission(), parentId));
        }
    }

    /**
     * 一个确定性的主键分配器：从「现存最大 id + 1」开始递增
     *
     * <p>给内存校验用。落库那侧<b>不要</b>用它 —— 那边必须让数据库生成 id，
     * 否则并发写入时会撞主键，而撞上的表现是「迁移把别人的资源行改成了身份行」
     */
    public static IdentityIdAllocator sequentialFrom(List<ResourceDO> existing) {
        long max = 0L;
        for (ResourceDO r : existing) {
            if (r.getId() != null && r.getId() > max) {
                max = r.getId();
            }
        }
        final long[] next = {max + 1};
        return template -> next[0]++;
    }

}
