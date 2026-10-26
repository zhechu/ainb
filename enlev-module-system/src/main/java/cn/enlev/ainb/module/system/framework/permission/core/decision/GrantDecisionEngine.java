package cn.enlev.ainb.module.system.framework.permission.core.decision;

import cn.enlev.ainb.module.system.dal.dataobject.permission.GrantDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.enums.permission.FieldModeEnum;
import cn.enlev.ainb.module.system.enums.permission.GrantActionEnum;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 授权决策引擎（动作判定 + 字段模式三层合并）
 *
 * 纯逻辑，不依赖 Spring 与 DB，便于单测直接构造各种边界情况。
 * 对应 v9 §7.1 的第 4-5 步（动作判定）和第 7 步（字段模式）：
 *
 * <pre>
 *     4. grants ← subjects × chain × (context 或 NULL) × action(含 *) × 有效期
 *     5. 动作   ← 全局 DENY > 上下文 DENY > 上下文 ALLOW > 全局 ALLOW > default_effect
 *     7. 字段模式 ← ceiling = min(全局 subject 命中的 field_mode)
 *                  value   = 上下文 subject max ?? 上下文基线 ?? 全局 subject max ?? default
 *                  最终    = min(ceiling, value)
 * </pre>
 *
 * @author 走进新时代
 */
public class GrantDecisionEngine {

    /**
     * 一次判定的完整结论：判决 + 判决理由 + 是谁投出的这一票
     *
     * <h3>为什么要有这个 record，而不是让调用方自己再算一遍理由</h3>
     *
     * 🔴 权限地图（{@code GrantDecisionService#listEffectiveTree}）要展示「这项权限从哪来」，
     * 而鉴权只要一个 boolean。如果两边各算各的，就又多了一份会漂移的口径 ——
     * 本类所在的这套体系已经漂过两次了：
     * {@code GrantDecisionService#buildResourceChain}（沿 path 上溯、不过滤 status）与
     * {@code #buildChainFromMap}（沿 parentId 上溯、只含启用资源）在「停用祖先」场景分叉；
     * {@code GrantAdminController#listByResource} 与 {@code #toRespVOs} 两处 expired 口径也不一致。
     *
     * 所以这里把「判决」和「理由」焊死在同一段代码里：{@link #isAllowed} 退化成
     * {@code explain(...).allowed()}，两者**在构造上不可能不一致**。
     *
     * @param allowed           判决
     * @param reason            走到哪个分支得出的判决
     * @param decidingGrant     投出决定性一票的授权行；回落 default_effect 或压根没判到行为 null
     * @param sourceResource    这一票所属的资源节点。🔴 它**未必是被判定的那个资源** ——
     *                          祖先链上的行同样参与判定，这正是「继承放行 / 上级否决」的来源；
     *                          回落 default_effect 时是链上最具体的那个（{@code chain.get(0)}）
     * @param fromDefaultEffect 判决是否来自资源的 default_effect 列（即链上没有任何命中行）
     */
    public record Decision(boolean allowed,
                           DecisionReason reason,
                           GrantDO decidingGrant,
                           ResourceDO sourceResource,
                           boolean fromDefaultEffect) {

        private static Decision of(boolean allowed, DecisionReason reason) {
            return new Decision(allowed, reason, null, null, false);
        }

        private static Decision ofGrant(boolean allowed, DecisionReason reason, GrantDO grant,
                                        ResourceDO sourceResource) {
            return new Decision(allowed, reason, grant, sourceResource, false);
        }

        private static Decision ofDefault(boolean allowed, ResourceDO sourceResource) {
            return new Decision(allowed, DecisionReason.DEFAULT_EFFECT, null, sourceResource, true);
        }
    }

    /**
     * 判决理由。顺序即优先级顺序（见类注释里的 v9 §7.1 第 5 步）
     */
    public enum DecisionReason {
        /** 主体集合为空。🔴 此时**不**回落 default_effect，直接拒绝：没有身份就没有权限 */
        NO_SUBJECT,
        /** 资源链为空，无从判起 */
        EMPTY_CHAIN,
        /** 链上没有任何命中行，回落到最具体资源的 default_effect 列 */
        DEFAULT_EFFECT,
        /** 全局 DENY（优先级最高） */
        GLOBAL_DENY,
        /** 上下文 DENY */
        CONTEXT_DENY,
        /** 上下文 ALLOW */
        CONTEXT_ALLOW,
        /** 全局 ALLOW（优先级最低的一票） */
        GLOBAL_ALLOW,
        /**
         * 命中了行，但没有任何一行的 effect 是 ALLOW 或 DENY（null / 脏值）
         *
         * 🔴 这条是**静默拒绝**：既不是回落 default_effect，也不是被谁否决，
         * 就是一路走到了最后。脏数据不该放大成放行，但也值得在诊断里看得见
         */
        NO_MATCHING_EFFECT
    }

    /**
     * 判断是否有动作权限
     *
     * 🔴 本方法是 {@link #explain} 的薄封装，**不要**在这里另写一套判定 ——
     * 理由见 {@link Decision} 的类注释
     *
     * @param subjects       用户的主体闭包
     * @param resourceChain  资源祖先链（从资源本身到根，越靠前的越具体）
     * @param grants         与这批资源关联的全部授权行
     * @param action         请求的动作
     * @param contextId      上下文资源编号，NULL = 全局
     * @return true = 有权限
     */
    public boolean isAllowed(Set<SubjectKey> subjects,
                             List<ResourceDO> resourceChain,
                             List<GrantDO> grants,
                             String action,
                             Long contextId) {
        return explain(subjects, resourceChain, grants, action, contextId).allowed();
    }

    /**
     * 判断是否有动作权限，并给出判决理由与决定性的一票
     *
     * @param subjects       用户（或任意主体集合）的闭包
     * @param resourceChain  资源祖先链（从资源本身到根，越靠前的越具体）
     * @param grants         与这批资源关联的全部授权行
     * @param action         请求的动作
     * @param contextId      上下文资源编号，NULL = 全局
     * @return 完整判决，绝不为 null
     */
    public Decision explain(Set<SubjectKey> subjects,
                            List<ResourceDO> resourceChain,
                            List<GrantDO> grants,
                            String action,
                            Long contextId) {
        // 🔴 这两个早退都**不**回落 default_effect：没有身份 / 没有资源，就没有权限可言
        if (subjects == null || subjects.isEmpty()) {
            return Decision.of(false, DecisionReason.NO_SUBJECT);
        }
        if (resourceChain == null || resourceChain.isEmpty()) {
            return Decision.of(false, DecisionReason.EMPTY_CHAIN);
        }
        // 取资源链上的所有 id
        Set<Long> chainIds = new java.util.LinkedHashSet<>();
        for (ResourceDO resource : resourceChain) {
            chainIds.add(resource.getId());
        }

        // 过滤出命中「主体 × 资源链 × 动作 × 有效期 × 上下文」的行
        //
        // 🔴 最后那个「上下文」过滤是 S6d 补的，它修的是一个 S2a 就埋下、
        // 但要等 context 真正启用之后才会暴露的缺陷：
        //
        // 原来 matched 把【上下文限定的授权】也算进来，于是
        //   · matched 不为空 → 跳过了 default_effect 的回落
        //   · 而后面 contextDeny/contextAllow 都被 contextId != null 挡住
        //   · 最终落到 isGlobal && ALLOW → false → 判 DENY
        //
        // 后果是反直觉的：给某个审批节点配一条 ALLOW，
        // 反而让这个字段在【其它所有地方】变成不可见。
        // 实测表现是「发起流程时必填字段报『请填写』，而用户明明填了」——
        // 因为该字段在发起路径上被裁掉了。
        //
        // 正确语义：与当前上下文无关的授权，对本次判定【什么都没说】，
        // 应当完全不参与，从而让 default_effect 正常回落
        List<GrantDO> matched = grants.stream()
                .filter(g -> g.isActive())
                .filter(g -> chainIds.contains(g.getResourceId()))
                .filter(g -> subjectMatches(g, subjects))
                .filter(g -> g.actionMatches(action) || g.actionMatches(GrantActionEnum.WILDCARD.getCode()))
                .filter(g -> g.isGlobal() || (contextId != null && contextId.equals(g.getContextResourceId())))
                .toList();

        if (matched.isEmpty()) {
            // 没有任何命中的授权，回落到最具体资源的 default_effect
            //
            // 🔴 读的是 sys_resource.default_effect **列**，不是 ResourceTypeEnum#defaultEffect()。
            // 那个枚举方法只在 ResourceAdminController#createResource 里当填充默认值用；
            // 实测库里 PROCESS 类型有 18 行 default_effect=ALLOW、9 行 DENY，
            // 而枚举说 PROCESS 该是 DENY —— 按 type 推就会算错那 18 个节点
            ResourceDO self = resourceChain.get(0);
            return Decision.ofDefault("ALLOW".equals(self.getDefaultEffect()), self);
        }

        // 按优先级判定：全局 DENY > 上下文 DENY > 上下文 ALLOW > 全局 ALLOW
        // S2a 阶段 context 全为 NULL，所以实际执行路径是：全局 DENY > 全局 ALLOW
        //
        // 🔴 下面四处一律用 findFirst 而不是 anyMatch：判定结果完全一样，
        // 但顺手拿到了「是谁投的这一票」，权限地图靠它区分「本资源配置」与「继承自上级」
        GrantDO globalDeny = firstMatch(matched, g -> g.isGlobal() && "DENY".equals(g.getEffect()));
        if (globalDeny != null) {
            return Decision.ofGrant(false, DecisionReason.GLOBAL_DENY, globalDeny,
                    resourceOf(resourceChain, globalDeny));
        }
        if (contextId != null) {
            GrantDO contextDeny = firstMatch(matched,
                    g -> contextId.equals(g.getContextResourceId()) && "DENY".equals(g.getEffect()));
            if (contextDeny != null) {
                return Decision.ofGrant(false, DecisionReason.CONTEXT_DENY, contextDeny,
                        resourceOf(resourceChain, contextDeny));
            }
            GrantDO contextAllow = firstMatch(matched,
                    g -> contextId.equals(g.getContextResourceId()) && "ALLOW".equals(g.getEffect()));
            if (contextAllow != null) {
                return Decision.ofGrant(true, DecisionReason.CONTEXT_ALLOW, contextAllow,
                        resourceOf(resourceChain, contextAllow));
            }
        }
        GrantDO globalAllow = firstMatch(matched, g -> g.isGlobal() && "ALLOW".equals(g.getEffect()));
        if (globalAllow != null) {
            return Decision.ofGrant(true, DecisionReason.GLOBAL_ALLOW, globalAllow,
                    resourceOf(resourceChain, globalAllow));
        }
        // 命中了行，但没有一行的 effect 是 ALLOW/DENY → 静默拒绝，见 DecisionReason#NO_MATCHING_EFFECT
        return Decision.of(false, DecisionReason.NO_MATCHING_EFFECT);
    }

    /**
     * 找出第一条满足条件的授权行，没有则返回 null
     */
    private GrantDO firstMatch(List<GrantDO> grants, java.util.function.Predicate<GrantDO> predicate) {
        for (GrantDO grant : grants) {
            if (predicate.test(grant)) {
                return grant;
            }
        }
        return null;
    }

    /**
     * 授权行指向的资源在链上的那一个节点
     *
     * 链是按「越靠前越具体」排的，而命中判定已经保证 resourceId ∈ chainIds，
     * 所以正常情况一定找得到；找不到时退回 null，让调用方自己决定怎么显示
     * （宁可少显示一个来源标签，也不要为了填满字段去猜一个节点）
     */
    private ResourceDO resourceOf(List<ResourceDO> resourceChain, GrantDO grant) {
        for (ResourceDO resource : resourceChain) {
            if (resource.getId() != null && resource.getId().equals(grant.getResourceId())) {
                return resource;
            }
        }
        return null;
    }

    /**
     * 判断授权行的主体是否在给定的闭包里
     */
    private boolean subjectMatches(GrantDO grant, Collection<SubjectKey> subjects) {
        for (SubjectKey subject : subjects) {
            if (grant.subjectMatches(subject)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 计算字段模式（v8 §3.2，v9 §7.1 第 7 步）
     *
     * <pre>
     *   ceiling = min(全局 subject 命中的 field_mode)  ← 角色红线，不能被上下文突破
     *   value   = 上下文 subject max ?? 上下文基线 ?? 全局 subject max ?? default
     *   最终    = min(ceiling, value)
     * </pre>
     *
     * @param subjects       用户的主体闭包
     * @param resourceChain  资源祖先链（从资源本身到根，越靠前越具体）
     * @param grants         与这批资源关联的全部授权行（含 field_mode 字段）
     * @param contextId      上下文资源编号，NULL = 无上下文
     * @param defaultMode    资源本身的默认字段模式（null 时视为 EDITABLE）
     * @return 最终字段模式；null 表示没有任何约束（等同于 defaultMode）
     */
    public FieldModeEnum resolveFieldMode(Set<SubjectKey> subjects,
                                          List<ResourceDO> resourceChain,
                                          List<GrantDO> grants,
                                          Long contextId,
                                          FieldModeEnum defaultMode) {
        if (subjects == null || subjects.isEmpty() || resourceChain == null || resourceChain.isEmpty()) {
            return defaultMode;
        }
        Set<Long> chainIds = new java.util.LinkedHashSet<>();
        for (ResourceDO resource : resourceChain) {
            chainIds.add(resource.getId());
        }

        // 只看有 field_mode 的有效行
        List<GrantDO> relevant = grants.stream()
                .filter(GrantDO::isActive)
                .filter(g -> chainIds.contains(g.getResourceId()))
                .filter(g -> FieldModeEnum.parse(g.getFieldMode()) != null)
                .toList();

        // ceiling：全局（context=NULL）且主体命中的行 → min（取最严格）
        FieldModeEnum ceiling = relevant.stream()
                .filter(GrantDO::isGlobal)
                .filter(g -> subjectMatches(g, subjects))
                .map(g -> FieldModeEnum.parse(g.getFieldMode()))
                .reduce(null, FieldModeEnum::min);
        // ceiling 缺省 EDITABLE（不设上限）
        if (ceiling == null) {
            ceiling = FieldModeEnum.EDITABLE;
        }

        // value：上下文 subject max → 上下文基线 → 全局 subject max → default
        FieldModeEnum value = null;
        if (contextId != null) {
            // 上下文 subject 命中：max（取最宽松）
            value = relevant.stream()
                    .filter(g -> contextId.equals(g.getContextResourceId()))
                    .filter(g -> subjectMatches(g, subjects))
                    .map(g -> FieldModeEnum.parse(g.getFieldMode()))
                    .reduce(null, FieldModeEnum::max);
            if (value == null) {
                // 上下文基线：不关联 subject 的上下文行
                value = relevant.stream()
                        .filter(g -> contextId.equals(g.getContextResourceId()))
                        .filter(g -> !subjectMatches(g, subjects))
                        .map(g -> FieldModeEnum.parse(g.getFieldMode()))
                        .reduce(null, FieldModeEnum::max);
            }
        }
        if (value == null) {
            // 全局 subject max
            value = relevant.stream()
                    .filter(GrantDO::isGlobal)
                    .filter(g -> subjectMatches(g, subjects))
                    .map(g -> FieldModeEnum.parse(g.getFieldMode()))
                    .reduce(null, FieldModeEnum::max);
        }
        if (value == null) {
            value = defaultMode != null ? defaultMode : FieldModeEnum.EDITABLE;
        }

        return FieldModeEnum.min(ceiling, value);
    }

}
