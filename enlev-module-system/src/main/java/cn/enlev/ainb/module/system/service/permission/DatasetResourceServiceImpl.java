package cn.enlev.ainb.module.system.service.permission;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormResourceDescriptorDTO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.SlotMappingDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.GrantMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.SlotMappingMapper;
import cn.enlev.ainb.module.system.enums.permission.PermissionSlotEnum;
import cn.enlev.ainb.module.system.enums.permission.ResourceTypeEnum;
import cn.enlev.ainb.module.system.framework.permission.core.dataset.DatasetResourceNaming;
import cn.enlev.ainb.module.system.framework.permission.core.dataset.NativeDatasetScanner;
import cn.enlev.ainb.module.system.framework.permission.core.dataset.SlotInferencer;
import cn.enlev.ainb.module.system.framework.permission.core.rule.RuleDataPermissionRule;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.*;

/**
 * 在线表单的数据集资源 Service 实现
 *
 * <h3>按需创建（v9 §5.2）：哪些列/字段会落行</h3>
 *
 * 默认<b>不落行</b>。列/字段的存在性由表单定义推导，求值时查不到资源行就用
 * 类型默认效果 ALLOW。落行的条件有三个，命中任意一个即可：
 *
 * <ol>
 *   <li><b>标了敏感</b>（{@code props.sensitive === true}）→ 落行，默认效果 <b>DENY</b>。
 *       这是 v9 §5.2 的原意：默认看不见，要看得单独授权</li>
 *   <li><b>配了脱敏规则</b>（{@code props.maskRule}）→ 落行，默认效果保持 <b>ALLOW</b>。
 *       理由见下</li>
 *   <li><b>已经有人给它配过授权</b> → 保留，永远不回收</li>
 * </ol>
 *
 * <h4>为什么脱敏字段落行、却不翻成 DENY</h4>
 *
 * 脱敏字段是现成的、可靠的「这一列敏感」信号 —— 手机号、身份证、银行卡本来就配在那里。
 * 拿它当敏感标记，管理员不必把同一件事再标一遍。
 *
 * 但<b>不能</b>顺手把默认效果翻成 DENY：那些字段现在是「脱敏后可见」，
 * 翻成 DENY 会让它们在 S4b 落地那天对所有人直接消失，而没有任何人配置过什么。
 * 落行但保持 ALLOW 的效果是：现状零变化，同时它出现在授权页上、可以被单独收紧。
 * 真要默认藏起来，管理员显式标「敏感」即可。
 *
 * <h3>为什么回收要看有没有授权</h3>
 *
 * 重新发布时，不再敏感的列/字段会被回收，免得资源表随着迭代越积越多。
 * 但只要有人配过授权就<b>不回收</b> —— 删掉资源等于把管理员的配置一并抹掉，
 * 而这件事没有任何提示。宁可留一条没人用的资源行。
 *
 * <h3>多租户</h3>
 *
 * {@code infra_form_design}（表单）与 {@code sys_resource}（资源）都是全局表，
 * 所以一张表单登记一次、所有租户共用同一份资源与槽位 —— 两者语义一致，不会错位。
 * 租户差异体现在 {@code sys_grant}：同一个字段资源，各租户各自授权。
 *
 * @author 走进新时代
 */
@Service
@Validated
@Slf4j
public class DatasetResourceServiceImpl implements DatasetResourceService {

    /**
     * 纯函数，无状态，不必进容器
     */
    private final SlotInferencer slotInferencer = new SlotInferencer();

    @Resource
    private ResourceMapper resourceMapper;
    @Resource
    private ResourceService resourceService;
    @Resource
    private GrantMapper grantMapper;
    @Resource
    private SlotMappingMapper slotMappingMapper;
    @Resource
    private RuleDataPermissionRule ruleDataPermissionRule;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RegisterResult register(FormResourceDescriptorDTO descriptor) {
        if (descriptor == null || StrUtil.isBlank(descriptor.getFormCode())
                || StrUtil.isBlank(descriptor.getMainTable())) {
            log.warn("[register][描述不完整，跳过登记：{}]", descriptor);
            return new RegisterResult(null, 0, 0, 0, Map.of());
        }

        // 1. 数据集资源本体（不存在则建；已被 S2c 推导成 MENU 节点的，升级成 DATASET）
        ResourceDO dataset = upsertDataset(descriptor);

        // 2. 把该表单的六个动作资源收拢到数据集节点下，让祖先链完整
        int reparented = reparentActions(dataset, DatasetResourceNaming.dataset(descriptor.getFormCode()) + ":");

        // 3. 按需创建列/字段资源，并回收不再需要的
        ChildSyncResult childResult = syncChildren(dataset, descriptor);

        // 4. 语义槽位：只补不改
        Map<String, String> slots = syncSlots(dataset, descriptor);

        // 5. 让行级规则认识这张表。没有这一步，规则配了也不会被框架调用
        ruleDataPermissionRule.registerTable(descriptor.getMainTable());

        log.info("[register][表单({})登记完成：资源 {}，动作归位 {}，列/字段 新建 {} 回收 {} 保留 {}，槽位 {}]",
                descriptor.getFormCode(), dataset.getId(), reparented,
                childResult.created, childResult.removed, childResult.kept, slots);
        return new RegisterResult(dataset.getId(), childResult.created,
                childResult.removed, childResult.kept, slots);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UnregisterResult unregister(String formCode) {
        if (StrUtil.isBlank(formCode)) {
            return UnregisterResult.nothing();
        }
        ResourceDO dataset = resourceMapper.selectByPermissionIncludeDisabled(
                DatasetResourceNaming.dataset(formCode));
        if (dataset == null) {
            // 从未发布过（register 压根没建锚点），或者已经注销过。都是正常路径，不告警：
            // 「删一张草稿表单」必然走到这里，把它记成 warn 会让日志里全是噪音
            return UnregisterResult.nothing();
        }

        List<Long> ids = collectSubtreeIds(dataset);

        // 先清授权行与槽位映射，再删资源行。
        //
        // ⚠️ 槽位必须走物理删除：uk_resource_slot(tenant_id, resource_id, slot) <b>不含 deleted</b>，
        //    软删的话这个组合会被墓碑永久占住（理由见 SlotMappingMapper#deleteByIdPhysically）。
        //    而资源行本身只是软删，重新占用同一个表单编码时 register 会建出<b>新</b> id，
        //    所以物理删掉旧槽位不会影响那张新表单
        int removedGrants = grantMapper.deleteByResourceIds(ids);
        int removedSlots = 0;
        for (Long id : ids) {
            for (SlotMappingDO mapping : slotMappingMapper.selectListByResourceId(id)) {
                removedSlots += slotMappingMapper.deleteByIdPhysically(mapping.getId());
            }
        }
        // deleteResourceList 的约束是「子节点必须一起勾选」，collectSubtreeIds 已经把整棵树收进来了。
        // 用它而不是逐个 deleteResource：单条版本见子节点就抛 RESOURCE_HAS_CHILDREN，
        // 而它还会一次性把三个权限缓存（PERMISSION_MENU_ID_LIST / GRANT_USER_PERMISSIONS /
        // GRANT_USER_MENU_IDS）失效掉 —— 逐个删要失效 N 次，且中间态会被别的请求读到
        resourceService.deleteResourceList(ids);

        // ⚠️ 刻意不把 dataset_table 从 ruleDataPermissionRule 的表名集合里摘掉：
        //    一张物理表可能被多张表单绑定，摘掉会把另一张活表单的行级过滤一起关掉。
        //    留着是安全的 —— 拦截器为这张表调规则时按表名反查数据集
        //    （selectListByDatasetTable 自带 deleted=0），查不到锚点就是不加过滤，
        //    而这张表此时已经没有在线表单入口了。下次启动 reloadRegisteredTables
        //    按存活的 DATASET 行重建集合，这条残留自动消失
        log.info("[unregister][表单({})的数据集资源已注销：资源 {} 行，授权 {} 行，槽位 {} 行]",
                formCode, ids.size(), removedGrants, removedSlots);
        return new UnregisterResult(dataset.getId(), ids.size(), removedGrants, removedSlots);
    }

    /**
     * 收集数据集锚点及其全部后代的资源 id
     *
     * <p>用 parentId 逐层 BFS，<b>不用</b>物化路径前缀查（{@code selectDescendantsByPathPrefix}）：
     * 后者依赖 path 列被正确维护，而本方法跑在<b>删除</b>路径上。一旦某条历史行的 path 是空的，
     * 前缀查会静默漏掉它的子树，接着 {@code deleteResourceList} 的孤儿校验抛
     * {@code RESOURCE_HAS_CHILDREN} —— 用户看到的是「表单删不掉」，报错却指向资源树，
     * 根因谁也看不出来。树最多三层（数据集 → 列/字段/动作），逐层查的代价可以忽略。
     *
     * <p>{@code seen} 兜住脏数据里的环：parentId 指回自己后代时，没有它会死循环。
     */
    private List<Long> collectSubtreeIds(ResourceDO dataset) {
        List<Long> ids = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(dataset.getId());
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            if (!seen.add(current)) {
                continue;
            }
            ids.add(current);
            for (ResourceDO child : resourceMapper.selectListByParentId(current)) {
                queue.add(child.getId());
            }
        }
        return ids;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RegisterResult registerNative(NativeDatasetScanner.NativeDatasetDescriptor descriptor) {
        if (descriptor == null || StrUtil.isBlank(descriptor.permission())
                || StrUtil.isBlank(descriptor.table())) {
            log.warn("[registerNative][声明不完整，跳过登记：{}]", descriptor);
            return new RegisterResult(null, 0, 0, 0, Map.of());
        }
        // 1. 数据集本体。与在线表单共用 upsert 语义：S2c 的约定式推导多半已经按
        //    命名约定把 system:user 这类节点建成了 MENU 目录，这里升级成 DATASET，
        //    而不是另建一行 —— 另建会让六个动作与列/字段挂在两个节点下，祖先链断成两截
        ResourceDO dataset = upsertNativeDataset(descriptor);

        // 2. 把该模块的动作资源收拢到数据集节点下，让祖先链完整。
        //    不做的话，system:user:query 这些动作留在根下，
        //    「给整张用户表授权」落不到一个节点上 —— 决策算法走的是祖先链
        int reparented = reparentActions(dataset, descriptor.permission() + ":");

        // 3. 字段资源：按需创建，只建声明里写了的那些
        ChildSyncResult childResult = syncNativeFields(dataset, descriptor);

        // 4. 槽位映射：只补不改。人工在规则编辑器里改过的列名不能被启动扫描覆盖回去，
        //    否则表现是「规则按错误的列过滤」，而且每次重启都复发
        Map<String, String> slots = syncGivenSlots(dataset, descriptor.slots());

        // 5. ⚠️ 这一步才是本方法的意义：让行级规则认识这张表。
        //    少了它，前四步建出来的资源与槽位全都在，授权页上也配得出规则，
        //    但 MyBatis 拦截器压根不会为这张表调用规则 —— 配了等于没配
        ruleDataPermissionRule.registerTable(descriptor.table());

        log.info("[registerNative][原生数据集({} → 表 {})登记完成：资源 {}，动作归位 {}，"
                        + "字段 新建 {} 保留 {}，槽位 {}]",
                descriptor.permission(), descriptor.table(), dataset.getId(), reparented,
                childResult.created, childResult.kept, slots);
        return new RegisterResult(dataset.getId(), childResult.created,
                childResult.removed, childResult.kept, slots);
    }

    private ResourceDO upsertNativeDataset(NativeDatasetScanner.NativeDatasetDescriptor descriptor) {
        ResourceDO existing = resourceMapper.selectByPermissionIncludeDisabled(descriptor.permission());
        if (existing != null) {
            applyDefaultEffectOnTypeUpgrade(existing);
            existing.setType(ResourceTypeEnum.DATASET.getCode());
            existing.setName(descriptor.name());
            existing.setDatasetTable(descriptor.table());
            existing.setStatus(CommonStatusEnum.ENABLE.getStatus());
            resourceService.updateResource(existing);
            return existing;
        }
        ResourceDO dataset = new ResourceDO();
        dataset.setPermission(descriptor.permission());
        dataset.setName(descriptor.name());
        dataset.setType(ResourceTypeEnum.DATASET.getCode());
        dataset.setParentId(resolveNativeParentId(descriptor.permission()));
        dataset.setSort(0);
        dataset.setDefaultEffect(ResourceTypeEnum.DATASET.defaultEffect());
        dataset.setStatus(CommonStatusEnum.ENABLE.getStatus());
        dataset.setDatasetTable(descriptor.table());
        resourceService.createResource(dataset);
        return dataset;
    }

    /**
     * 登记已存在的行时，只在<b>类型升级</b>那一次填 {@code default_effect}
     *
     * <h3>🔴 原来无条件覆盖，实测把塌缩迁移的成果在第一次重启时就抹掉了</h3>
     *
     * 两处登记（原生 {@code upsertNativeDataset} / 在线表单 {@code upsertDataset}）
     * 原本都写着 {@code existing.setDefaultEffect(ResourceTypeEnum.DATASET.defaultEffect())}，
     * 也就是每次启动都把这一列刷回 ALLOW。而 {@code default_effect} 有两个<b>合法</b>的
     * DENY 来源，都不是登记流程能替它们做主的：
     *
     * <ul>
     *   <li><b>塌缩计划器</b>。{@code ResourceCollapsePlanner#applyStrictestDefaultEffect}
     *       在一个 DATASET 锚点并入若干 DENY 的 ACTION 成员时，必须把锚点收紧成 DENY ——
     *       成员行退役后，{@code system:dept:create} 这类串被翻译成
     *       {@code (system:dept, CREATE)}，链上没有授权时就回落到这一行的标量。
     *       实测本次迁移收紧了 7 行，其中 {@code system:dept}（id=2056）
     *       在开关翻开后的<b>第一次启动</b>就被刷回 ALLOW：只授了 VIEW 的角色
     *       （实测 ROLE 3、ROLE 9000000576）于是对「新增/修改/删除部门」拿到放行，
     *       而塌缩前它们落到自己那行的 DENY 是拒绝的 —— 一次静默越权，全程 200 无异常</li>
     *   <li><b>管理员</b>。{@code DataRowFilterResolver#defaultEffectOf} 的契约写着
     *       「只有管理员显式配了 DENY 的数据集才会走到 denyAll」；无条件覆盖等于
     *       管理员配了、当场生效、下次重启自己变回去</li>
     * </ul>
     *
     * <h3>🔴 判据为什么是「当前还不是 DATASET」，而不是「一律不覆盖」</h3>
     *
     * 一律不覆盖会打断 MENU → DATASET 的升级：约定式推导先按命名约定建出的是 MENU 目录节点，
     * {@code default_effect=DENY}（{@code ResourceTypeEnum#defaultEffect()} 里 MENU 就是 DENY）。
     * 升级成 DATASET 时若沿用旧值，新登记的在线表单会立刻走到
     * {@code DataRowFilterResolver#defaultEffectOf} 的 denyAll —— 没配过行级规则的人
     * 一张行也看不见。所以「换类型」这一次照旧按新类型的出厂值填，
     * 已经是 DATASET 的行则一律不动它现有的值。
     *
     * <p>⚠️ 必须在 {@code setType(DATASET)} <b>之前</b>调用：判据读的就是升级前的类型。
     *
     * <p>本次迁移收紧的 7 行里有 6 行是 {@code infra:form-design-run:*} 在线表单数据集，
     * 走的是 {@code upsertDataset}。观察到的那次启动它们还没被重新登记（仍是 DENY），
     * 属于同一个缺陷的未引爆状态 —— 所以两处一起改。
     */
    private void applyDefaultEffectOnTypeUpgrade(ResourceDO existing) {
        if (ResourceTypeEnum.DATASET.getCode().equals(existing.getType())) {
            return;
        }
        existing.setDefaultEffect(ResourceTypeEnum.DATASET.defaultEffect());
    }

    /**
     * 原生数据集挂在哪：按命名约定取上一段
     *
     * {@code system:user} → 找 {@code system}，找不到就挂根下。
     * 挂根下不会出错，只是授权页上散成一堆平级节点 —— 与在线表单那边同一个口径。
     *
     * ⚠️ 不自己造父节点。S2c 的约定式推导本来就会按命名约定逐段建目录，
     * 这里再造一遍会和它抢着建同一个串
     */
    private Long resolveNativeParentId(String permission) {
        int lastColon = permission.lastIndexOf(':');
        if (lastColon <= 0) {
            return ResourceDO.ID_ROOT;
        }
        ResourceDO parent = resourceMapper.selectByPermissionIncludeDisabled(
                permission.substring(0, lastColon));
        return parent != null ? parent.getId() : ResourceDO.ID_ROOT;
    }

    private ChildSyncResult syncNativeFields(ResourceDO dataset,
                                             NativeDatasetScanner.NativeDatasetDescriptor descriptor) {
        // COLUMN 与 FIELD 分别登记：前者管「列表里这一列显不显示」，
        // 后者管「详情里这个字段能不能看」。分开才能表达
        // 「列表不显示手机号、点进详情能看」这类配置
        ChildSyncResult fieldResult = syncNativeChildren(
                dataset, descriptor.fields(), ResourceTypeEnum.FIELD,
                descriptor.sensitive(), descriptor.labels());
        ChildSyncResult columnResult = syncNativeChildren(
                dataset, descriptor.columns(), ResourceTypeEnum.COLUMN,
                descriptor.sensitive(), descriptor.labels());
        return new ChildSyncResult(
                fieldResult.created() + columnResult.created(),
                0,
                fieldResult.kept() + columnResult.kept());
    }

    /**
     * 按类型登记原生数据集的子资源（COLUMN / FIELD）
     *
     * ⚠️ 两种类型必须各自查各自的已存在集合。用一个集合的话，
     * {@code xxx:col:mobile} 会被 {@code xxx:fld:mobile} 的存在性"挡掉"而建不出来
     */
    private ChildSyncResult syncNativeChildren(ResourceDO dataset, List<String> names,
                                               ResourceTypeEnum type, boolean sensitive,
                                               Map<String, String> labels) {
        if (names == null || names.isEmpty()) {
            return new ChildSyncResult(0, 0, 0);
        }
        List<ResourceDO> existing = resourceMapper.selectListByParentIdAndTypes(dataset.getId(),
                List.of(type.getCode()));
        Map<String, ResourceDO> existingByPermission = new LinkedHashMap<>();
        for (ResourceDO child : existing) {
            existingByPermission.put(child.getPermission(), child);
        }
        int created = 0;
        int kept = 0;
        for (String name : names) {
            String permission = DatasetResourceNaming.childOf(dataset.getPermission(), type, name);
            String displayName = nativeChildDisplayName(dataset, type, name, labels);
            ResourceDO old = existingByPermission.get(permission);
            if (old != null) {
                kept++;
                // 名称变了要跟上：原生数据集的名称源头是注解（labels），
                // 与在线表单路径（syncChildren）同一个口径。不更新的话，
                // 注解里改了中文名而授权页还显示旧名，管理员会以为配错了对象
                if (!StrUtil.equals(old.getName(), displayName)) {
                    old.setName(displayName);
                    resourceService.updateResource(old);
                }
                continue;
            }
            ResourceDO resource = new ResourceDO();
            resource.setPermission(permission);
            resource.setName(displayName);
            resource.setType(type.getCode());
            resource.setParentId(dataset.getId());
            resource.setSort(0);
            resource.setDefaultEffect(sensitive ? "DENY" : type.defaultEffect());
            resource.setStatus(CommonStatusEnum.ENABLE.getStatus());
            resourceService.createResource(resource);
            created++;
        }
        // ⚠️ 不回收：注解里删掉一个字段就把资源连同它上面的授权一起抹掉，
        //    是一次没有任何提示的配置丢失
        return new ChildSyncResult(created, 0, kept);
    }

    /**
     * 原生数据集的字段/列资源展示名：{@code 数据集名:字段:中文名} / {@code 数据集名:列:中文名}
     *
     * 中文名没配 label 时回落字段名本身
     */
    private String nativeChildDisplayName(ResourceDO dataset, ResourceTypeEnum type,
                                          String fieldName, Map<String, String> labels) {
        String label = labels != null ? labels.getOrDefault(fieldName, fieldName) : fieldName;
        String typeLabel = type == ResourceTypeEnum.FIELD ? "字段" : "列";
        return dataset.getName() + ":" + typeLabel + ":" + label;
    }

    /**
     * 写入显式给定的槽位映射（只补不改，理由同 {@link #syncSlots}）
     */
    private Map<String, String> syncGivenSlots(ResourceDO dataset, Map<String, String> given) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : given.entrySet()) {
            SlotMappingDO old = slotMappingMapper.selectByResourceIdAndSlot(
                    dataset.getId(), entry.getKey());
            if (old != null) {
                result.put(entry.getKey(), old.getColumnName());
                continue;
            }
            SlotMappingDO mapping = new SlotMappingDO();
            mapping.setResourceId(dataset.getId());
            mapping.setSlot(entry.getKey());
            mapping.setTableName(mainTableName(dataset));
            mapping.setColumnName(entry.getValue());
            PermissionSlotEnum slot = PermissionSlotEnum.parse(entry.getKey());
            mapping.setDescription(slot != null ? slot.getDescription() : null);
            slotMappingMapper.insert(mapping);
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    @Override
    public int reloadRegisteredTables() {        // sys_resource 是全局表（@TenantIgnore），启动期没有租户上下文也能查。
        // tableNames 本身也是「哪些物理表需要行级过滤」的全局集合，不分租户
        List<ResourceDO> datasets = resourceMapper.selectListByTypeDataset();
        int count = 0;
        for (ResourceDO dataset : datasets) {
            if (StrUtil.isNotBlank(dataset.getDatasetTable())) {
                ruleDataPermissionRule.registerTable(dataset.getDatasetTable());
                count++;
            }
        }
        return count;
    }

    // ==================== 数据集资源 ====================

    private ResourceDO upsertDataset(FormResourceDescriptorDTO descriptor) {
        String permission = DatasetResourceNaming.dataset(descriptor.getFormCode());
        String name = StrUtil.blankToDefault(descriptor.getFormName(), descriptor.getFormCode());
        String table = descriptor.getMainTable().toLowerCase();

        ResourceDO existing = resourceMapper.selectByPermissionIncludeDisabled(permission);
        if (existing != null) {
            // 常见路径：S2c 的约定式推导已经按命名约定把它建成了 MENU 目录节点。
            // 升级成 DATASET 而不是另建一行 —— 另建会让同一张表单有两个资源节点，
            // 六个动作挂在一个下面、列/字段挂在另一个下面，祖先链断成两截
            applyDefaultEffectOnTypeUpgrade(existing);
            existing.setType(ResourceTypeEnum.DATASET.getCode());
            existing.setName(name);
            existing.setDatasetTable(table);
            existing.setStatus(CommonStatusEnum.ENABLE.getStatus());
            resourceService.updateResource(existing);
            return existing;
        }

        ResourceDO dataset = new ResourceDO();
        dataset.setPermission(permission);
        dataset.setName(name);
        dataset.setType(ResourceTypeEnum.DATASET.getCode());
        dataset.setParentId(resolveDatasetParentId());
        dataset.setSort(0);
        dataset.setDefaultEffect(ResourceTypeEnum.DATASET.defaultEffect());
        dataset.setStatus(CommonStatusEnum.ENABLE.getStatus());
        dataset.setDatasetTable(table);
        resourceService.createResource(dataset);
        return dataset;
    }

    /**
     * 数据集节点挂在哪
     *
     * 优先挂在 {@code infra:form-design-run} 下（全部在线表单的共同父节点）；
     * 它不存在就现建一个目录节点。都不行才挂根下 —— 挂根下不会出错，
     * 只是授权页上会散成一堆平级节点
     */
    private Long resolveDatasetParentId() {
        String parentPermission = StrUtil.removeSuffix(
                DatasetResourceNaming.RUN_PERMISSION_PREFIX, ":");
        ResourceDO parent = resourceMapper.selectByPermissionIncludeDisabled(parentPermission);
        if (parent != null) {
            return parent.getId();
        }
        ResourceDO created = new ResourceDO();
        created.setPermission(parentPermission);
        created.setName("在线表单");
        created.setType(ResourceTypeEnum.MENU.getCode());
        created.setParentId(ResourceDO.ID_ROOT);
        created.setSort(0);
        created.setDefaultEffect(ResourceTypeEnum.MENU.defaultEffect());
        created.setStatus(CommonStatusEnum.ENABLE.getStatus());
        resourceService.createResource(created);
        return created.getId();
    }

    /**
     * 把该表单的动作资源收拢到数据集节点下
     *
     * 六个动作权限（query/create/update/delete/export/import）是发布时建菜单带出来的，
     * 由 {@code ResourceMigrateRunner} 迁进 sys_resource 时一律挂在根下。
     * 挂在根下时，「给整张表单授权」落不到一个节点上 ——
     * 决策算法第 3 步走的是祖先链，动作不在数据集的子树里就继承不到它的授权。
     *
     * @return 本次挪动的资源数
     */
    /**
     * 把权限串以 {@code prefix} 开头的动作资源收拢到数据集节点下
     *
     * @param prefix 动作权限串的公共前缀，形如 {@code infra:form-design-run:xxx:} 或 {@code system:user:}
     */
    private int reparentActions(ResourceDO dataset, String prefix) {
        List<ResourceDO> candidates = resourceMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ResourceDO>()
                        .likeRight(ResourceDO::getPermission, prefix)
                        .eq(ResourceDO::getType, ResourceTypeEnum.ACTION.getCode()));
        int moved = 0;
        for (ResourceDO action : candidates) {
            if (Objects.equals(action.getParentId(), dataset.getId())) {
                continue;
            }
            action.setParentId(dataset.getId());
            // updateResource 会重算 path 并级联更新后代，不能直接 updateById
            resourceService.updateResource(action);
            moved++;
        }
        return moved;
    }

    // ==================== 列 / 字段资源 ====================

    private ChildSyncResult syncChildren(ResourceDO dataset, FormResourceDescriptorDTO descriptor) {
        Map<String, ResourceDO> desired = new LinkedHashMap<>();
        collectDesired(desired, dataset, descriptor.getFormCode(),
                descriptor.getFields(), ResourceTypeEnum.FIELD);
        collectDesired(desired, dataset, descriptor.getFormCode(),
                descriptor.getColumns(), ResourceTypeEnum.COLUMN);

        List<ResourceDO> existing = resourceMapper.selectListByParentIdAndTypes(dataset.getId(),
                List.of(ResourceTypeEnum.COLUMN.getCode(), ResourceTypeEnum.FIELD.getCode()));
        Map<String, ResourceDO> existingByPermission = new LinkedHashMap<>();
        for (ResourceDO child : existing) {
            existingByPermission.put(child.getPermission(), child);
        }

        int created = 0;
        int kept = 0;
        for (Map.Entry<String, ResourceDO> entry : desired.entrySet()) {
            ResourceDO old = existingByPermission.get(entry.getKey());
            if (old == null) {
                resourceService.createResource(entry.getValue());
                created++;
                continue;
            }
            kept++;
            // 名称变了要跟上：字段改名之后授权页上还显示旧名，
            // 管理员会以为自己配错了对象
            ResourceDO want = entry.getValue();
            boolean nameChanged = !StrUtil.equals(old.getName(), want.getName());
            // 🔴 default_effect 只跟「变严」的方向，见 tightenOnly
            boolean tightened = tightenOnly(old.getDefaultEffect(), want.getDefaultEffect());
            if (nameChanged || tightened) {
                old.setName(want.getName());
                if (tightened) {
                    old.setDefaultEffect(want.getDefaultEffect());
                }
                resourceService.updateResource(old);
            }
        }

        int removed = 0;
        for (ResourceDO child : existing) {
            if (desired.containsKey(child.getPermission())) {
                continue;
            }
            if (grantMapper.selectCountByResourceId(child.getId()) > 0) {
                // 有人配过授权，不回收。见类注释
                kept++;
                continue;
            }
            resourceService.deleteResource(child.getId());
            removed++;
        }
        return new ChildSyncResult(created, removed, kept);
    }

    /**
     * {@code want} 是否比 {@code current} 更严 —— 只有更严才允许覆盖已存在的值
     *
     * <h3>🔴 原来是双向跟随描述符，会把管理员显式配的 DENY 静默刷回 ALLOW</h3>
     *
     * {@code default_effect} 有两个合法的写入方：本方法（按表单描述符的 {@code sensitive} 标记）
     * 与<b>管理员</b>（{@code ResourceSaveReqVO#defaultEffect} 走资源管理接口，
     * COLUMN/FIELD 行的 {@code menu_id} 为空、不受「菜单派生行只许改 sort/status」的限制，
     * 所以管理员改得动）。双向跟随的结果是：管理员配了 DENY、当场生效，
     * 下次表单发布自己变回 ALLOW —— 与 {@link #applyDefaultEffectOnTypeUpgrade}
     * 修掉的是同一个缺陷，只是发生在列/字段这一层。
     *
     * <h3>为什么选「单向收紧」而不是「一律不覆盖」</h3>
     *
     * 一律不覆盖会让 {@code sensitive} 标记永远只能加不能撤：作者取消敏感标记后，
     * 那一列仍然按 DENY 走 {@code DataRowFilterResolver#defaultEffectOf} 的 denyAll。
     * 两个方向都会静默，所以只能选代价小的那个，而代价<b>完全不对等</b>：
     *
     * <ul>
     *   <li>跟着放开（DENY→ALLOW）⟹ 敏感列的行重新可见，是<b>越权</b>，
     *       全程 200 无异常，没有人会报「我怎么突然能看见了」</li>
     *   <li>不跟着放开（停在 DENY）⟹ 表现是「这张表单查不出数据」，
     *       一定会有人来报，且管理员在资源页上把它改回 ALLOW 就立刻恢复</li>
     * </ul>
     *
     * 与本仓库既有的取舍同口径：{@code ResourceCollapsePlanner#resolveTargetAction}
     * 的注释里写着「放权比收权更难发现」。
     *
     * <p>⚠️ 判据必须与 {@code GrantDecisionEngine#explain} 的回落读法逐字节一致 ——
     * 那边写的是 {@code "ALLOW".equals(self.getDefaultEffect())}，
     * 也就是 null、空串、脏值一律<b>等价于 DENY</b>，不是第三态。
     * 这里若把 null 当「未知」而放行覆盖，就会把一行脏数据解释成 ALLOW 并写回去
     */
    private static boolean tightenOnly(String current, String want) {
        return "ALLOW".equals(current) && !"ALLOW".equals(want);
    }

    private void collectDesired(Map<String, ResourceDO> desired, ResourceDO dataset, String formCode,
                                List<FormResourceDescriptorDTO.Item> items, ResourceTypeEnum type) {
        if (items == null) {
            return;
        }
        for (FormResourceDescriptorDTO.Item item : items) {
            if (item == null || StrUtil.isBlank(item.getKey()) || !item.isGrantworthy()) {
                continue;
            }
            String permission = DatasetResourceNaming.child(formCode, type, item.getKey());
            ResourceDO resource = new ResourceDO();
            resource.setPermission(permission);
            resource.setName(StrUtil.blankToDefault(item.getLabel(), item.getKey()));
            resource.setType(type.getCode());
            resource.setParentId(dataset.getId());
            resource.setSort(0);
            // 标了敏感才翻成 DENY；仅配了脱敏的保持 ALLOW（见类注释）
            resource.setDefaultEffect(item.isSensitive() ? "DENY" : type.defaultEffect());
            resource.setStatus(CommonStatusEnum.ENABLE.getStatus());
            desired.putIfAbsent(permission, resource);
        }
    }

    // ==================== 语义槽位 ====================

    /**
     * 同步槽位映射：<b>只补不改</b>
     *
     * 已存在的映射一律不动。自动推断是「猜」，人工在规则编辑器里改过的才是准的；
     * 每次发布都用推断结果覆盖，会把人工修正静默地改回去 ——
     * 而表现是「规则按错误的列过滤」，没有任何提示。
     *
     * 同理也不删多余的槽位：它可能是人工加的（推断只认识 owner/dept/status 三个）。
     */
    private Map<String, String> syncSlots(ResourceDO dataset, FormResourceDescriptorDTO descriptor) {
        Map<String, String> inferred = slotInferencer.infer(descriptor);
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : inferred.entrySet()) {
            SlotMappingDO old = slotMappingMapper.selectByResourceIdAndSlot(
                    dataset.getId(), entry.getKey());
            if (old != null) {
                result.put(entry.getKey(), old.getColumnName());
                continue;
            }
            SlotMappingDO mapping = new SlotMappingDO();
            mapping.setResourceId(dataset.getId());
            mapping.setSlot(entry.getKey());
            mapping.setTableName(mainTableName(dataset));
            mapping.setColumnName(entry.getValue());
            PermissionSlotEnum slot = PermissionSlotEnum.parse(entry.getKey());
            mapping.setDescription(slot != null ? slot.getDescription() : null);
            slotMappingMapper.insert(mapping);
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    /**
     * 槽位映射要记的所属表：数据集的主表名（小写）
     *
     * 自动推断与 {@code @PermissionDataset} 显式声明给出的列名，都只可能是主表上的列 ——
     * 两者的入参里压根没有关联表的信息，所以这里不需要（也无法）判断别的表。
     *
     * 存成小写是因为求值时拿它当 key 去行过滤传进来的限定符表里查，
     * 那张表的 key 一律是 {@code tableName.toLowerCase()}；大小写不统一的话，
     * 一条配置正确的映射会因为 key 对不上而求不出来 —— 又是静默失效
     */
    private static String mainTableName(ResourceDO dataset) {
        String table = dataset.getDatasetTable();
        return StrUtil.isBlank(table) ? null : table.toLowerCase();
    }

    /**
     * 列/字段对账的结果
     */
    private record ChildSyncResult(int created, int removed, int kept) {
    }

}
