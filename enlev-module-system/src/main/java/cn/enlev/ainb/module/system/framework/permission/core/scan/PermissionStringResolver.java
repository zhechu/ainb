package cn.enlev.ainb.module.system.framework.permission.core.scan;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.system.enums.permission.GrantActionEnum;
import lombok.Getter;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 把权限串解析成「资源身份 + 动作」
 *
 * ## 为什么需要这一层
 *
 * 现状是<b>动词被编码了两次</b>：一次在资源身份串里（{@code system:user:create} 是一个
 * <i>资源</i>），一次在 {@code sys_grant.action} 里（而这一列几乎完全空转 ——
 * 阶段 0 实测 1802 条活授权里 1771 条是 {@code VIEW}，占 98.3%，
 * {@code CREATE/UPDATE/DELETE/EXPORT/IMPORT} 的授权行数是 <b>0</b>）。
 *
 * 后果是 {@code system:user:query} 与 {@code system:user:create} 成了<b>兄弟资源</b>，
 * 而「用户管理这个页面」本身无处安放 —— 这正是 119 行空 permission 的 MENU 资源
 * 作为第二套平行体系存在的原因。
 *
 * 本类负责把末段动词从资源身份里挪回它本来就该在的动作维度：
 * <pre>
 *   system:user:query  →  resourceKey = system:user,  action = VIEW
 *   system:user:create →  resourceKey = system:user,  action = CREATE
 * </pre>
 *
 * ## 与 {@link PermissionTreeDeriver} 的关系
 *
 * 两者读的是<b>同一套命名约定</b>，所以刻意放在同一个包里：
 * deriver 按约定把串铺成树（末段一律当叶子），本类按约定把串拆成（身份, 动作）。
 * 约定一旦变更，两处必须同时改 —— 相邻放置就是为了让漏改显眼一点。
 *
 * ## 🔴 接线由 {@code enlev.permission.collapse-enabled} 控制，默认关
 *
 * 判定侧（{@code GrantDecisionService#hasPermission}）与清单侧
 * （{@code GrantDecisionService#listAllowedPermissions}）都在开关打开后才走本类。
 *
 * 开关<b>只</b>能在数据迁移落库、且自校验通过之后才翻 ——
 * 数据没迁就打开，判定会去查一批还不存在的身份行，结果是<b>静默判成无权</b>：
 * 全程 200、无异常，只是按钮全没了、接口全 403。
 * 详见 {@code PermissionProperties#collapseEnabled} 的注释。
 *
 * @author 走进新时代
 */
public class PermissionStringResolver {

    /**
     * 动词 → 动作的映射表
     *
     * 🔴 <b>{@code reference} 必须在表里。</b>它是 {@link GrantActionEnum} 的正式成员
     * （v9 §2 被引用查询），不是业务专有动词。阶段 0 的第一版映射表漏了它，
     * 把 39 行误判进档 C —— 而档 C 的处置是「保留为子资源」，
     * 等于白白放弃 39 行本可以直接归入动作维度的资源，档 C 因此从 35 行虚高到 153 行。
     *
     * 用 {@link LinkedHashMap} 是为了让 {@link #verbs()} 的遍历顺序稳定：
     * 守卫测试要把它与显式清单逐项比对，顺序随 hash 漂移会让失败信息没法读。
     */
    private static final Map<String, GrantActionEnum> VERB_TO_ACTION;

    /**
     * 六个标准 CRUD 动词，即档 A。阶段 0 实测覆盖遗留族 499 行（63.9%）
     */
    private static final Set<String> STANDARD_VERBS =
            Set.of("query", "create", "update", "delete", "export", "import");

    static {
        Map<String, GrantActionEnum> map = new LinkedHashMap<>();
        // 档 A：六个标准动词
        map.put("query", GrantActionEnum.VIEW);
        map.put("create", GrantActionEnum.CREATE);
        map.put("update", GrantActionEnum.UPDATE);
        map.put("delete", GrantActionEnum.DELETE);
        map.put("export", GrantActionEnum.EXPORT);
        map.put("import", GrantActionEnum.IMPORT);
        // 档 B：其余能落到 GrantActionEnum 现有成员的动词。
        // 一个都不新增枚举值 —— 这是本次改造「不是重新设计一套模型」的关键证据
        map.put("list", GrantActionEnum.VIEW);
        map.put("get", GrantActionEnum.VIEW);
        map.put("download", GrantActionEnum.EXPORT);
        map.put("print", GrantActionEnum.PRINT);
        map.put("reference", GrantActionEnum.REFERENCE);
        map.put("save", GrantActionEnum.UPDATE);
        map.put("update-status", GrantActionEnum.UPDATE);
        map.put("update-order", GrantActionEnum.UPDATE);
        VERB_TO_ACTION = Collections.unmodifiableMap(map);
    }

    /**
     * 解析一个权限串
     *
     * 全函数：任何输入都返回一个 {@link Resolution}，不抛异常、不返回 null。
     * 但空串的 {@code resourceKey} 与 {@code action} 都是 <b>null</b> ——
     * 这是刻意的：忽略档位直接取用的调用方会立刻 NPE，而不是拿着空身份去查库、
     * 查不到、然后<b>静默判成无权</b>。权限体系里「响亮的失败」永远优于「安静的错判」。
     *
     * @param permission 权限串，如 {@code system:user:query}
     * @return 解析结果，含档位
     */
    public Resolution resolve(String permission) {
        if (StrUtil.isBlank(permission)) {
            return new Resolution(permission, null, null, Tier.BLANK);
        }
        String trimmed = permission.trim();
        int lastColon = trimmed.lastIndexOf(':');
        if (lastColon < 0) {
            // 无冒号的单段串。⚠️ 不是脏数据 —— 阶段 0 实测这 5 行是
            // bpm / infra / system / ai / agent，都是 type=MENU、menu_id 为空的树顶层模块根
            return new Resolution(trimmed, trimmed, GrantActionEnum.VIEW, Tier.MODULE_ROOT);
        }
        String prefix = trimmed.substring(0, lastColon);
        String verb = trimmed.substring(lastColon + 1);
        GrantActionEnum action = VERB_TO_ACTION.get(verb);
        if (action != null) {
            return new Resolution(trimmed, prefix, action,
                    STANDARD_VERBS.contains(verb) ? Tier.STANDARD_VERB : Tier.MAPPED_VERB);
        }
        if (prefix.indexOf(':') < 0) {
            // 两段式且末段不是动词 ⟹ 末段是名词（system:tenant、infra:codegen）。
            // 它本来就是资源身份，不是「资源:动作」，整串即身份
            return new Resolution(trimmed, trimmed, GrantActionEnum.VIEW, Tier.RESOURCE_IDENTITY);
        }
        // 三段以上、末段是业务专有动词（publish/deploy/visit/assign-role-menu/...）。
        // 保留为子资源，动作沿用现状的 VIEW —— 保持生效权限不变
        return new Resolution(trimmed, trimmed, GrantActionEnum.VIEW, Tier.BUSINESS_VERB);
    }

    /**
     * 动词映射表的只读视图，供守卫测试逐项比对
     */
    public Map<String, GrantActionEnum> verbs() {
        return VERB_TO_ACTION;
    }

    /**
     * 这一档的权限串会不会发生塌缩（末段动词被挪进动作维度）
     *
     * <p>🔴 计划器与判定/清单两侧必须共用这一个判据。各写一份的后果不是「算得慢一点」，
     * 而是<b>迁移按 A 口径重写数据、判定按 B 口径查数据</b> ——
     * 两个口径只在某几档上分叉时，错的那部分既不会报错也不会全错，
     * 表现为「大部分按钮正常、某一类按钮全没了」，是最难反查的那类缺陷。
     */
    public static boolean isCollapsing(Tier tier) {
        return tier == Tier.STANDARD_VERB || tier == Tier.MAPPED_VERB;
    }

    /**
     * 塌缩后，这个权限串应当由 {@code sys_resource} 里 <b>permission 等于哪个串</b>的行承载
     *
     * <h3>🔴 这是「一个权限串对应哪一行资源」的唯一口径，写路径与读路径都必须走它</h3>
     *
     * 读路径（{@code GrantDecisionService#lookupOf}）用它决定去查哪一行；
     * 写路径（{@code ResourceServiceImpl#syncFromMenu}、{@code PermissionDriftDetector}）
     * 用它决定「这个串在库里有没有家、要不要为它建行」。
     * 两边各写一份的后果是<b>写侧建出一批读侧永远不会去查的行</b>（或反过来：
     * 读侧去查一批写侧刚刚删掉的行），两个方向都静默、都不报错。
     *
     * <h3>为什么 R11 的判据是入参而不是本方法自己查库</h3>
     *
     * 本类是<b>纯函数</b>，不碰 Spring、不碰 Mapper —— 计划器要在没有容器的单测里用它，
     * 而且要拿同一份口径去算「迁移前」和「迁移后」两个世界。
     * 「成员行还在不在库里」这个事实由调用方查（读路径复用 {@code chainsOf} 的缓存，
     * 写路径直接查一次），本方法只负责把它和档位合成一个答案。
     *
     * @param permission      原始权限串，如 {@code system:user:create}
     * @param memberRowExists 原始串自己是否还有<b>存活</b>资源行。true ⟹ 这一族被 R11
     *                        整族拦下（判据与后果见 {@code ResourceCollapsePlanner#blockedIdentities}），
     *                        成员行就是它的家，不能翻译成身份串
     * @return 承载它的资源行应当具有的 permission；不塌缩时原样返回入参
     */
    public String identityOf(String permission, boolean memberRowExists) {
        Resolution res = resolve(permission);
        if (!res.isCollapsed() || memberRowExists) {
            return permission;
        }
        return res.getResourceKey();
    }

    /**
     * 反向展开表的键：身份串 + 动作码
     *
     * <p>用不可见分隔符 {@code \u0000}，避免身份串里的冒号与动作码混淆
     */
    public static String key(String identity, GrantActionEnum action) {
        return identity + '\u0000' + action.getCode();
    }

    /**
     * 反向展开表的键（字符串动作码版本，供消费方直接用）
     */
    public static String key(String identity, String actionCode) {
        return identity + '\u0000' + actionCode;
    }

    /**
     * 建「(身份, 动作) → 塌缩前存在过的权限串」表
     *
     * <h3>为什么需要它</h3>
     *
     * {@code GrantDecisionService#listAllowedPermissions} 吐的是<b>权限串</b>，
     * 前端 {@code v-hasPermi} 与后端 {@code @PreAuthorize("@ss.hasPermission(...)")} 比的就是它。
     * 塌缩后资源行只剩 {@code system:user}，直接吐身份串会让前端所有按钮消失、
     * 所有接口 403。所以判定通过之后要按本表展开回原始串。
     *
     * <h3>🔴 源必须是「实际存在过的串」，绝不能按动词映射表反推</h3>
     *
     * 反推会造出库里从来没有的串：VIEW 反推出 {@code :query}/{@code :list}/{@code :get} 三个，
     * 而实测只有前两个存在过。多出来的串不会让判定出错，
     * 但会让「权限清单 vs 注解面」的对账永久对不平 —— 而对账正是发现漂移的唯一手段；
     * 更坏的是它<b>盖住了真正的缺口</b>：展开表看着是满的，前端按钮却因为对不上串而消失。
     *
     * <p>调用方负责决定喂什么进来，两侧喂的东西不一样、但都只喂真实存在的串：
     * <ul>
     *   <li><b>计划器</b>（{@code ResourceCollapsePlanner}）喂「资源行自己的 permission
     *       ∪ {@code system_menu.permission}」。它跑在迁移<b>之前</b>，塌缩行还在</li>
     *   <li><b>运行时</b>（{@code GrantDecisionService#listAllowedPermissions}）只能喂
     *       {@code system_menu.permission} —— 塌缩行那时已经被软删了。
     *       本次迁移<b>不动</b>导航表，所以它天然保留着全部原始串。
     *       实测这样建出 513 个键，覆盖了迁移后全部 510 个 (身份, 动作) 目标键，<b>差集为空</b></li>
     * </ul>
     *
     * <h3>为什么用 {@link TreeMap} + {@link TreeSet}</h3>
     *
     * 入参是个 {@code Collection}，迭代顺序不由本方法决定。直接遍历它建表，
     * 会让同一份数据跑两次得到顺序不同的展开表 —— 变更集就没法逐字节比对了，
     * 而「评审过的那份 = 执行的那份」是这次不可回滚迁移的全部前提。
     *
     * @param existingPermissions 真实存在过的权限串（资源行的、或菜单表的）
     * @return 不可修改的展开表，键见 {@link #key}
     */
    public Map<String, Set<String>> buildExpansionTable(Collection<String> existingPermissions) {
        Set<String> sources = new TreeSet<>();
        for (String permission : existingPermissions) {
            if (StrUtil.isNotBlank(permission)) {
                sources.add(permission);
            }
        }

        Map<String, Set<String>> table = new TreeMap<>();
        for (String permission : sources) {
            Resolution res = resolve(permission);
            if (!isCollapsing(res.getTier())) {
                continue;
            }
            table.computeIfAbsent(key(res.getResourceKey(), res.getAction()),
                    k -> new LinkedHashSet<>()).add(permission);
        }

        // 值集合在构建期必须是可写的，到最后一步才封起来。
        // ⚠️ 用 unmodifiableSet 而不是 Set.copyOf：后者不保序，
        //    而展开出来的串会直接进权限清单，顺序不稳会让前后两次的清单 diff 出假差异
        Map<String, Set<String>> sealed = new LinkedHashMap<>(table.size());
        for (Map.Entry<String, Set<String>> e : table.entrySet()) {
            sealed.put(e.getKey(), Collections.unmodifiableSet(e.getValue()));
        }
        return Collections.unmodifiableMap(sealed);
    }

    /**
     * 解析结果
     */
    @Getter
    public static class Resolution {

        /**
         * 传入的权限串，已 trim
         */
        private final String permission;
        /**
         * 塌缩后的资源身份。档 C/D/root 下等于整串；档 A/B 下是去掉末段动词的前缀；
         * {@link Tier#BLANK} 下为 <b>null</b>
         */
        private final String resourceKey;
        /**
         * 塌缩后的动作。{@link Tier#BLANK} 下为 <b>null</b>
         */
        private final GrantActionEnum action;
        /**
         * 档位
         */
        private final Tier tier;

        public Resolution(String permission, String resourceKey, GrantActionEnum action, Tier tier) {
            this.permission = permission;
            this.resourceKey = resourceKey;
            this.action = action;
            this.tier = tier;
        }

        /**
         * 资源身份是否真的发生了塌缩（末段动词被挪进了动作维度）
         *
         * 阶段 2 的迁移脚本靠它判断「这条授权要不要重写 resource_id」：
         * 只有塌缩了的行才需要，档 C/D/root 原样保留。
         *
         * 🔴 而 DENY 行的 {@code action='*'} 必须<b>只在塌缩发生时</b>收窄成
         * {@link #getAction()}。不收窄的后果见阶段 0 勘察报告第四节：
         * 多条分别挂在兄弟资源上的 DENY 合并到同一个资源后，
         * {@code *} 的作用域会从「一个动词」放大成「该资源的一切动作」，
         * 把原本放行的 VIEW / UPDATE 一起吞掉 —— 静默收权，不报错
         */
        public boolean isCollapsed() {
            return resourceKey != null && !resourceKey.equals(permission);
        }

        @Override
        public String toString() {
            return permission + " -> " + resourceKey + " / " + action + " (" + tier + ")";
        }

    }

    /**
     * 档位。分档口径来自阶段 0 对 781 行遗留族资源（type=ACTION/MENU）的实测
     */
    public enum Tier {

        /**
         * 档 A：六个标准 CRUD 动词。499 行（63.9%）
         */
        STANDARD_VERB,
        /**
         * 档 B：其余可映射动词（reference/list/download/print/save/update-status/update-order）。
         * 44 行（5.6%），其中 reference 独占 39 行
         */
        MAPPED_VERB,
        /**
         * 档 C：业务专有动词，保留为子资源。35 行（4.5%），27 个动词。
         * ⚠️ 这里的 {@code page}/{@code detail}/{@code manager-query}/{@code get-key-list}
         * 语义上其实是 query 的变体，<b>能</b>并进 VIEW，但本轮刻意不并 ——
         * 并错一个就是一次静默放权，得逐个确认调用方语义。
         *
         * <p>🔴 本档的判据是「三段以上且末段不在映射表里」，<b>它不区分末段是动词还是名词</b>。
         * 所以三段式的名词末段（例如 DATASET 族的 {@code infra:form-design-run:assessment_object_form}）
         * 会落进档 C 而不是 {@link #RESOURCE_IDENTITY}。这在本次作用域内不构成歧义 ——
         * 阶段 0 实测遗留族里档 C 的 27 个末段<b>全部是动词</b>；
         * 而已达标族（DATASET/COLUMN/FIELD/PROCESS/CATEGORY）本来就不走这个解析层，
         * 它们的资源身份不含动词，是靠 {@code action} + {@code field_mode} 表达的。
         * 若将来要把解析层用到已达标族上，这条判据必须先改
         */
        BUSINESS_VERB,
        /**
         * 档 D：两段式资源身份（末段是名词）。79 行（10.1%）。
         * 阶段 0 有个干净的结论：<b>84 行「有 permission 的 MENU 资源」与「档 D + MODULE_ROOT」
         * 是同一个集合</b>，也就是说这一档恰好就是树的中间节点，无一例外
         */
        RESOURCE_IDENTITY,
        /**
         * 树顶层模块根（无冒号单段）。5 行：bpm / infra / system / ai / agent
         */
        MODULE_ROOT,
        /**
         * 空 permission。119 行，全是 MENU 类型。
         * 🔴 塌缩后这一档应当消失，但阶段 0 实测有 <b>160 条活授权</b>挂在上面
         * （3 个主体），必须先重新指向、并为「压根没有按钮权限串」的菜单新造资源级串，
         * 才能删。直接删等于抹掉三个角色的菜单可见性
         */
        BLANK

    }

}
