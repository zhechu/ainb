package cn.enlev.ainb.module.system.framework.permission.core.scan;

import cn.hutool.core.collection.CollUtil;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper;
import cn.enlev.ainb.module.system.enums.permission.ResourceTypeEnum;
import cn.enlev.ainb.module.system.service.permission.ResourceService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 权限点漂移检测器
 *
 * ## 什么是「漂移」
 *
 * 开发者在代码里新增了 @PreAuthorize("@ss.hasPermission('system:user:export')")，
 * 但忘记在 system_menu（或后来的 sys_resource）里添加对应的资源行。
 * 结果：权限检查永远返回 false（系统找不到这个权限），
 * 表现为「功能存在但没有任何人能使用」，排查时没有明显错误日志。
 *
 * 漂移检测器在应用启动时把代码里的权限串和 sys_resource 里的权限串对比，
 * 对差异打告警。不阻止启动——告警就够了，防止「偶发漂移导致整机无法启动」。
 *
 * ## 约定式推导
 *
 * 同时，检测器把代码里的全部权限串交给 {@link PermissionTreeDeriver}，
 * 推导出按命名约定应有的目录节点（如 system:user），
 * 并把缺失的节点自动创建进 sys_resource。
 *
 * 这样 sys_resource 里的树结构就不需要手动维护——代码里用了哪些权限串，
 * 对应的目录和叶子节点就都存在。
 *
 * ## 🔴 塌缩开启后，比对与推导都用「承载串」而不是代码里的原始串
 *
 * 判据收在 {@link CollapsedResourceLocator}，与判定侧
 * {@code GrantDecisionService#lookupOf} 同源。为什么这一步是必须的而不是优化：
 * 约定式推导会<b>写库</b>，照着原始串推导等于把塌缩退役掉的 457 个成员行原样建回来，
 * 而成员行一出现，判定侧的 R11 镜像就改按原始串查 —— 授权却早已搬到身份行上，
 * 结果是<b>每次重启后权限静默消失</b>，且不报错、不回滚。
 *
 * <p>顺带说明 {@link #repairParents} 为什么在塌缩后仍然可以照跑：
 * 它按同一套命名约定归位，而计划器给 33 个新建身份行挑父节点时用的也是这套约定
 * （{@code ResourceCollapsePlanner#newIdentityTemplate}）。
 * 落库后实测「约定父 ≠ 实际父」的行数是 <b>0</b>，所以它不会动到迁移的结果。
 *
 * <p>🔴 但那个 0 是<b>幂等性的测量，不是「死代码」的证明</b>，别拿它当删除依据 ——
 * 《权限资源统一方案》阶段 3 第 12 条写着「删掉 repairParents，约定树成为唯一形态」，
 * 那个前提<b>没有成立</b>：新建时的 {@code ResourceServiceImpl#attachToConventionParent}
 * 只试<b>直接</b>约定父（{@code a:b:c} 只找 {@code a:b}），找不到就退回根下；
 * 而 {@link #repairParents} 还会<b>逐层收窄</b>（再试 {@code a}），并且能在
 * 「子先于父被建出来」之后补挂。两者是同一件事的两半，不是重复实现。
 * 删掉它的后果是：某个菜单先于它的约定父被建出来，那一行就<b>永远</b>挂在根下，
 * 而挂在根下等于祖先链断了 —— 给 {@code a:b} 授权覆盖不到它，且全程不报错。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class PermissionDriftDetector {

    @Resource
    private ResourceMapper resourceMapper;
    @Resource
    private ResourceService resourceService;
    @Resource
    private CollapsedResourceLocator collapsedLocator;

    /**
     * 执行漂移检测 + 约定式推导
     *
     * @param codePermissions 从代码注解里扫描出来的全部权限串
     * @return 检测报告
     */
    public DriftReport detect(Set<String> codePermissions) {
        // 1. 从 sys_resource 里拿现有的所有权限串
        List<ResourceDO> existing = resourceMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ResourceDO>()
                        .isNotNull(ResourceDO::getPermission)
                        .ne(ResourceDO::getPermission, "")
        );
        Set<String> dbPermissions = existing.stream()
                .map(ResourceDO::getPermission)
                .collect(Collectors.toSet());

        // 🔴 塌缩开启后，代码里的权限串与库里的权限串<b>不再同名</b>：
        // 代码写的是 system:user:query，而库里承载它的那一行 permission 是 system:user。
        // 直接拿两个集合相减会得到两件错事：
        //   ① 一条「几乎所有权限点都漂移了」的假 WARN，而它们其实一个都不漂移；
        //   ② syncTree 照着代码里的原始串推导，把塌缩退役掉的 457 个成员行<b>原样建回来</b> ——
        //      而成员行一出现，判定侧的 R11 镜像就改按原始串查，授权却早已搬到身份行上，
        //      结果是<b>静默判成无权</b>。这一条是本次改造里最贵的那个坑
        // 所以先换算成「承载串」再比。换算口径与判定侧 lookupOf 同源，
        // 都走 PermissionStringResolver#identityOf（见 CollapsedResourceLocator）
        Map<String, String> carrierOf = collapsedLocator.identityOfAll(codePermissions);
        Set<String> codeCarriers = new LinkedHashSet<>(carrierOf.values());

        // 2. 漂移：代码里有、库里没有承载行的权限串
        Set<String> missing = new LinkedHashSet<>(codeCarriers);
        missing.removeAll(dbPermissions);

        // 3. 陈旧：库里有、代码里没有的 ACTION 类型权限串（目录层不检查——它们可以有代码不知道的节点）
        List<ResourceDO> staleRows = existing.stream()
                .filter(r -> ResourceTypeEnum.ACTION.getCode().equals(r.getType()))
                .filter(r -> !codeCarriers.contains(r.getPermission()))
                .toList();
        Set<String> stale = staleRows.stream()
                .map(ResourceDO::getPermission)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        // 🔴 陈旧里有一批是<b>结构性扫不到</b>的，必须与「可能是已删功能」的那批分开报，
        //    否则这个数字永久非零，看日志的人很快就学会忽略它 —— 而它一旦可以被忽略，
        //    真正值得查的那几条也就一起被忽略了。判据与理由见 datasetDrivenStale
        Set<String> datasetDriven = datasetDrivenStale(staleRows, existing);

        // 4. 约定式推导：把承载串按命名约定推导出完整树，创建缺失的节点
        int created = syncTree(codeCarriers, dbPermissions);

        DriftReport report = new DriftReport(missing, stale, datasetDriven, created);
        if (!missing.isEmpty()) {
            log.warn("[PermissionDriftDetector][漂移：代码里的权限点在 sys_resource 里找不到承载行，"
                    + "这些权限的鉴权永远返回 false，请检查！缺失 {} 个：{}]",
                    missing.size(), missing);
        }
        if (!datasetDriven.isEmpty()) {
            // 这一批不是缺陷，是扫描器的能力边界：它们由表单设计器 / 数据集同步在<b>运行时</b>生成，
            // 代码里从来没有对应的 @PreAuthorize，所以每次启动都会被算成「陈旧」。
            // 单列出来，下面那条 INFO 的数字才重新变成「值得看一眼」的量
            log.info("[PermissionDriftDetector][陈旧·数据集驱动 {} 个：父行是 DATASET/COLUMN/FIELD，"
                    + "由在线表单与原生数据集在运行时登记，注解扫描器结构上扫不到，属预期。按模块分布：{}]",
                    datasetDriven.size(), countByModule(datasetDriven));
        }
        Set<String> unexplained = report.unexplainedStale();
        if (!unexplained.isEmpty()) {
            // ⚠️ 只打数量与模块分布，不打全量权限串。
            //
            // 本项目根 pom 里大部分 module 是注释掉的（当前只启用 system/infra/bpm），
            // 而 system_menu 里有全部模块的菜单 —— 那些模块的源码在磁盘上但不参与编译，
            // 注解扫描器自然扫不到它们，于是全部被判成「陈旧」。实测 1533 个，
            // 其中 mes 316 / cms 188 / hrm 146 / erp 112 …都属于这一类，是预期而非缺陷。
            //
            // 早期实现把这 1533 个权限串整个拼进一行日志，除了淹没真正有用的信息
            // （上面那条 WARN 级的漂移告警）之外没有任何作用
            log.info("[PermissionDriftDetector][陈旧·待查 {} 个：sys_resource 里有但代码里找不到，"
                    + "且不是运行时生成的数据集资源。未启用模块（根 pom 里注释掉的）的权限点必然落在这里，"
                    + "属预期；剩下的才可能是已删功能。按模块分布：{}]",
                    unexplained.size(), countByModule(unexplained));
        }
        log.info("[PermissionDriftDetector][约定式推导完成：新建 {} 个节点]", created);
        return report;
    }

    /**
     * 把承载串按命名约定推导成完整树，把缺失的节点写进 sys_resource
     *
     * @param codeCarriers 代码里的权限串换算成「该由哪一行承载」之后的集合，
     *                     见 {@link CollapsedResourceLocator#identityOfAll}。
     *                     开关关闭时它就是代码里的原始串，本方法的行为与塌缩前逐字节一致
     */
    private int syncTree(Set<String> codeCarriers, Set<String> dbPermissions) {
        PermissionTreeDeriver deriver = new PermissionTreeDeriver();
        List<PermissionTreeDeriver.DerivedNode> nodes = deriver.derive(codeCarriers);

        // 先建目录节点（MENU），再建叶子节点（ACTION），这样父节点的 id 已经存在
        Map<String, Long> permissionToId = new HashMap<>();
        // 先从 db 里把已有的填进来
        resourceMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ResourceDO>()
                        .isNotNull(ResourceDO::getPermission)).forEach(r -> permissionToId.put(r.getPermission(), r.getId()));

        int created = 0;
        // 按层序：先 MENU 后 ACTION，MENU 按字典序（短的先建，保证父先于子）
        List<PermissionTreeDeriver.DerivedNode> ordered = new ArrayList<>(nodes);
        ordered.sort(Comparator.comparing((PermissionTreeDeriver.DerivedNode n) ->
                n.getType() == PermissionTreeDeriver.DerivedNodeType.MENU ? 0 : 1)
                .thenComparing(n -> n.getPermission().length()));

        for (PermissionTreeDeriver.DerivedNode node : ordered) {
            if (dbPermissions.contains(node.getPermission()) || permissionToId.containsKey(node.getPermission())) {
                continue;
            }
            ResourceDO resource = new ResourceDO();
            resource.setPermission(node.getPermission());
            resource.setName(node.getName());
            resource.setType(node.getType() == PermissionTreeDeriver.DerivedNodeType.ACTION
                    ? ResourceTypeEnum.ACTION.getCode()
                    : ResourceTypeEnum.MENU.getCode());
            resource.setDefaultEffect(ResourceTypeEnum.ACTION.getCode().equals(resource.getType())
                    ? ResourceTypeEnum.ACTION.defaultEffect()
                    : ResourceTypeEnum.MENU.defaultEffect());
            resource.setStatus(0);
            resource.setSort(0);
            // 找父节点 id
            if (node.getParentPermission() != null) {
                Long parentId = permissionToId.get(node.getParentPermission());
                resource.setParentId(parentId != null ? parentId : ResourceDO.ID_ROOT);
            } else {
                resource.setParentId(ResourceDO.ID_ROOT);
            }
            try {
                resourceService.createResource(resource);
                permissionToId.put(node.getPermission(), resource.getId());
                created++;
            } catch (Exception e) {
                log.warn("[PermissionDriftDetector][创建资源节点失败，权限串={}，原因={}]",
                        node.getPermission(), e.getMessage());
            }
        }

        // ⭐ 关键一步：把【已存在但挂错父节点】的资源归位
        //
        // <h3>🔴 不做这一步会怎样：资源树是一片扁平的</h3>
        //
        // 上面那个循环只【新建缺失的节点】，对已存在的一律 continue，所以它自己修不了任何旧行。
        // 而早期版本的 migrateFromMenu / syncFromMenu 建出来的资源<b>全部</b>是
        // {@code parentId = ID_ROOT}（那时它们的注释写着「先挂根下，约定式推导会重建树结构」）
        // —— 可推导根本没有重建，它跳过了所有已存在的节点。
        //
        // 当时的实测后果：840 个资源里 <b>712 个（83%）挂在根下</b>，
        // 比如 {@code system:role:query} 的路径是 {@code /root/system:role:query/}，
        // 而它应该是 {@code /root/system/system:role/system:role:query/}。
        //
        // 直接的业务影响有两条：
        // <ol>
        //   <li><b>授权继承失效</b> —— 给 {@code system:role} 授权，
        //       本该覆盖它下面全部 {@code system:role:*}，但那些权限点挂在根下、
        //       不在它的资源链里，于是一个都继承不到，必须逐个单独授权</li>
        //   <li>授权页的资源树被拍平成一长串，无法按模块折叠</li>
        // </ol>
        //
        // <h3>⚠️ 上面那个 712/840 是<b>历史</b>数字，别当现值读</h3>
        //
        // 两条创建路径后来都补上了 {@code ResourceServiceImpl#attachToConventionParent}，
        // 新建时就按约定挂对；本方法也把存量修完了。塌缩落库后复测：
        // 存活 682 行里挂在根下的是 <b>124 行，且全部是单段的模块根</b>
        // （{@code system} / {@code infra} / …），本来就该在根下；
        // 「直接约定父存在却不是实际父」的行 = <b>0</b>，
        // 「要靠逐层收窄才找得到祖先」的行 = <b>0</b>。
        //
        // 🔴 所以它现在<b>每次都修 0 行</b>，但仍然不能删 —— 那是幂等，不是死代码。
        // 理由写在类注释里：创建侧只试直接约定父、找不到就退回根下，
        // 而「子先于父被建出来」这件事在菜单管理里随时可能发生（管理员按什么顺序建菜单，
        // 与权限串的层级没有任何关系）。真发生时，能把它补挂回去的只有本方法。
        //
        // 修正走 {@code updateResource} 而不是直接改 parentId ——
        // 它会重算物化路径并<b>级联更新所有后代</b>；只改 parentId 的话
        // path 还是旧的，而祖先链是沿 path 上溯算的，等于没修。
        //
        // ⚠️ 遍历的是【库里全部资源】，不是 ordered（推导出的节点）。
        //    推导器只扫得到代码注解里的权限串（实测 221 个），而库里有几百个资源 ——
        //    未启用模块、表单发布、流程部署产生的资源都不在扫描范围内。
        //    只遍历 ordered 的话，那几百个资源永远修不到（第一版就是这么写的，只修了 101 个）。
        int repaired = repairParents();
        if (repaired > 0) {
            log.info("[PermissionDriftDetector][修正 {} 个挂错父节点的资源]", repaired);
        }
        return created;
    }

    /**
     * 按权限串命名约定，把挂错父节点的资源全部归位
     *
     * {@code a:b:c} 的父应当是 {@code a:b}。逐层收窄找最近的存在的祖先：
     * {@code a:b} 不存在就试 {@code a}，都不存在才留在根下。
     *
     * @return 修正的条数
     */
    private int repairParents() {
        List<ResourceDO> all = resourceMapper.selectList();
        // 权限串 → id。同串多行时取 id 最小的那行当父（与 hasPermission 的 get(0) 口径一致）
        Map<String, Long> byPermission = new HashMap<>();
        for (ResourceDO r : all) {
            if (r.getPermission() == null || r.getPermission().isEmpty()) {
                continue;
            }
            byPermission.merge(r.getPermission(), r.getId(), Math::min);
        }

        int repaired = 0;
        for (ResourceDO r : all) {
            String permission = r.getPermission();
            if (permission == null || permission.indexOf(':') < 0) {
                continue; // 无权限串或只有一段，本来就该挂根下
            }
            // 逐层收窄找最近的存在的祖先
            Long parentId = null;
            String p = permission;
            while (true) {
                int idx = p.lastIndexOf(':');
                if (idx <= 0) {
                    break;
                }
                p = p.substring(0, idx);
                Long candidate = byPermission.get(p);
                // ⚠️ 不能把自己当父（同串多行时 get 到的可能就是自己）
                if (candidate != null && !candidate.equals(r.getId())) {
                    parentId = candidate;
                    break;
                }
            }
            if (parentId == null || parentId.equals(r.getParentId())) {
                continue;
            }
            // ⚠️ 防成环：候选父的祖先链里不能包含自己
            if (isAncestorOf(r.getId(), parentId, all)) {
                log.warn("[repairParents][资源({}) 挂到 {} 会成环，跳过]", r.getId(), parentId);
                continue;
            }
            r.setParentId(parentId);
            try {
                resourceService.updateResource(r); // 重算 path 并级联后代
                repaired++;
            } catch (Exception e) {
                log.warn("[repairParents][修正失败，权限串={}，原因={}]", permission, e.getMessage());
            }
        }
        return repaired;
    }

    /**
     * candidate 的祖先链里是否包含 selfId（用于防成环）
     */
    private boolean isAncestorOf(Long selfId, Long candidate, List<ResourceDO> all) {
        Map<Long, Long> parentOf = new HashMap<>();
        for (ResourceDO r : all) {
            parentOf.put(r.getId(), r.getParentId());
        }
        Long cur = candidate;
        int guard = 0;
        while (cur != null && !ResourceDO.ID_ROOT.equals(cur) && guard++ < 32) {
            if (cur.equals(selfId)) {
                return true;
            }
            cur = parentOf.get(cur);
        }
        return false;
    }

    /**
     * 从「陈旧」里挑出<b>注解扫描器结构上永远扫不到</b>的那批：父行是数据集类型的 ACTION 行
     *
     * <h3>为什么要单列出来</h3>
     *
     * 在线表单发布与原生数据集登记会在运行时按描述符建出列/字段/动作资源
     * （{@code DatasetResourceServiceImpl#syncChildren} 与 {@code #collectDesired}），
     * 它们的权限串是<b>拼出来的</b>（如 {@code infra:form-design-run:device_manage:create}），
     * 代码里从来没有对应的 {@code @PreAuthorize} —— {@code FormDesignRuntimeController}
     * 是拿 {@code String.format} 现场拼串再交给 {@code checkPermission} 的。
     * 所以这批行<b>每次启动都会被算成陈旧</b>，且永远不可能不陈旧。
     *
     * <p>后果不是「多一条日志」，而是<b>这条日志再也读不出信息</b>：
     * 塌缩落库后它永久停在 137（其中 infra 128 就是这批），
     * 于是「陈旧」这个数字失去了「可能是已删功能」的含义，
     * 而看日志的人一旦学会跳过它，真正值得查的那几条也一起被跳过了。
     *
     * <h3>判据为什么是「父行的类型」，而不是写死前缀</h3>
     *
     * 写死 {@code infra:form-design-run:} 会把一个跨模块的字符串常量埋进 system 模块，
     * 而将来任何新的运行时资源族都得回来改这里 —— 漏改的表现正是「又变回永久噪声」。
     * 父行类型是数据本身的性质：数据集驱动的资源一定挂在 DATASET/COLUMN/FIELD 下面，
     * 而代码侧的权限点挂在约定树（MENU/ACTION）下面。实测这个判据把 238 行存活 ACTION
     * 干净地切成 139（父行 MENU，代码侧）与 99（父行 DATASET，运行时侧），没有交叉。
     *
     * <p>⚠️ 已知的一例「判据说是运行时、其实是代码侧」：{@code system:user} 一族被 R11
     * 整族拦下没有塌缩，它那 8 行动词成员的父行正是 DATASET。但这不影响本方法的用途 ——
     * 那 8 个串代码里扫得到，压根不会进 {@code stale}，也就轮不到在这里被分类。
     *
     * @param staleRows 已经算成陈旧的那些 ACTION 行
     * @param existing  库里全部有权限串的存活行，用来查父行的类型
     */
    private static Set<String> datasetDrivenStale(List<ResourceDO> staleRows, List<ResourceDO> existing) {
        Map<Long, String> typeOfId = new HashMap<>(existing.size());
        for (ResourceDO r : existing) {
            typeOfId.put(r.getId(), r.getType());
        }
        Set<String> datasetTypes = Set.of(ResourceTypeEnum.DATASET.getCode(),
                ResourceTypeEnum.COLUMN.getCode(), ResourceTypeEnum.FIELD.getCode());
        Set<String> out = new LinkedHashSet<>();
        for (ResourceDO r : staleRows) {
            // 父行查不到（parentId 指向 0、空权限串、已软删）时<b>不</b>归入本类：留在「待查」那一堆里。
            // 归错方向的代价不对称 —— 把一条真该查的塞进「属预期」等于把它永久藏起来
            //
            // 🔴 这个 null 判必须显式写，别把它折回一行 {@code datasetTypes.contains(typeOfId.get(..))}：
            //    {@code Set.of} 造出来的是 ImmutableCollections.SetN，它对 {@code contains(null)}
            //    抛 NPE 而不是返回 false（HashMap#get 那种宽容在这里不存在），
            //    而「父行查不到」不是异常分支，是常态 —— 根级行的 parentId 就是 0，库里没有 id=0 的行。
            //    真炸过一次：detect 在启动期扫描里被调，NPE 会直接掀掉整个权限扫描
            String parentType = r.getParentId() == null ? null : typeOfId.get(r.getParentId());
            if (parentType != null && datasetTypes.contains(parentType)) {
                out.add(r.getPermission());
            }
        }
        return out;
    }

    /**
     * 按权限串的第一段（模块名）统计数量，按数量倒序
     *
     * 排查时真正有用的是「哪个模块整片缺失」，而不是逐个权限串 ——
     * 整片缺失通常意味着那个模块没启用，个别缺失才值得查
     */
    private Map<String, Long> countByModule(Set<String> permissions) {
        return permissions.stream()
                .map(p -> {
                    int index = p.indexOf(':');
                    return index > 0 ? p.substring(0, index) : p;
                })
                .collect(Collectors.groupingBy(module -> module, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }

    /**
     * 检测报告
     */
    public record DriftReport(
            /** 代码里的权限点在 sys_resource 里找不到<b>承载行</b>（漂移，需要补登记）。
             *  塌缩开启时这里是承载串（如 system:user）而不是代码里的原始串（system:user:query），
             *  因为库里那一行的 permission 就是承载串 */
            Set<String> missing,
            /** sys_resource 里有但代码里没有（陈旧）。<b>含</b> {@link #datasetDrivenStale} 那批 */
            Set<String> stale,
            /** {@link #stale} 的子集：运行时生成的数据集资源，注解扫描器结构上扫不到，属预期。
             *  判据与理由见 {@code #datasetDrivenStale} */
            Set<String> datasetDrivenStale,
            /** 本次约定式推导新建了多少个节点 */
            int created) {

        public boolean hasDrift() {
            return !missing.isEmpty();
        }

        /**
         * 陈旧里<b>真正值得看一眼</b>的那部分：刨掉运行时生成的数据集资源
         *
         * <p>调用方要报数字时报这个，不要报 {@link #stale} —— 后者永久非零
         * （塌缩落库后实测 137，其中 128 个是数据集驱动），报它等于报一个常量
         */
        public Set<String> unexplainedStale() {
            Set<String> out = new LinkedHashSet<>(stale);
            out.removeAll(datasetDrivenStale);
            return out;
        }
    }

}
