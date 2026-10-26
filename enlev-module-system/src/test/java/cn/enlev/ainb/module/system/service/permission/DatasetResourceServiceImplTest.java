package cn.enlev.ainb.module.system.service.permission;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.test.core.ut.BaseDbUnitTest;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormResourceDescriptorDTO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.GrantDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.SlotMappingDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.GrantMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.SlotMappingMapper;
import cn.enlev.ainb.module.system.enums.permission.ResourceTypeEnum;
import cn.enlev.ainb.module.system.framework.permission.core.dataset.DatasetResourceNaming;
import cn.enlev.ainb.module.system.framework.permission.core.rule.RuleDataPermissionRule;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import cn.enlev.ainb.module.system.framework.permission.config.PermissionConfiguration;
import cn.enlev.ainb.module.system.framework.permission.core.scan.CollapsedResourceLocator;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

/**
 * {@link DatasetResourceServiceImpl} 的单元测试类
 *
 * 重点覆盖 v9 §5.2「按需创建」的三条边界：
 * <ol>
 *   <li>普通字段<b>不</b>落行 —— 这是资源表不爆炸的全部理由</li>
 *   <li>敏感 → 落行且 DENY；仅脱敏 → 落行但保持 ALLOW（不改变现状）</li>
 *   <li>配过授权的资源永不回收 —— 回收等于静默删掉管理员的配置</li>
 * </ol>
 *
 * 以及两条「重新发布不能把人工修正改回去」的约定：槽位只补不改、动作资源归位幂等。
 *
 * @author 走进新时代
 */
@Import({DatasetResourceServiceImpl.class, ResourceServiceImpl.class,
        CollapsedResourceLocator.class, PermissionConfiguration.class})
public class DatasetResourceServiceImplTest extends BaseDbUnitTest {

    private static final String FORM_CODE = "demo_order";
    private static final String MAIN_TABLE = "biz_demo_order";

    @Resource
    private DatasetResourceServiceImpl datasetResourceService;
    @Resource
    private ResourceMapper resourceMapper;
    @Resource
    private GrantMapper grantMapper;
    @Resource
    private SlotMappingMapper slotMappingMapper;

    /**
     * 行级规则是内存态组件，这里只关心「有没有被通知到」，不需要它真的工作
     */
    @MockitoBean
    private RuleDataPermissionRule ruleDataPermissionRule;

    /** 表单发布走 ResourceServiceImpl 的写方法，那里要驱逐资源链缓存；本类只关心资源与授权行本身 */
    @MockitoBean
    private GrantDecisionService grantDecisionService;

    // ============================= 数据集资源本体 =============================

    @Test
    public void testRegister_首次登记建出数据集资源并注册表名() {
        FormResourceDescriptorDTO descriptor = buildDescriptor();

        DatasetResourceService.RegisterResult result = datasetResourceService.register(descriptor);

        ResourceDO dataset = resourceMapper.selectById(result.datasetResourceId());
        assertNotNull(dataset);
        assertEquals(DatasetResourceNaming.dataset(FORM_CODE), dataset.getPermission());
        assertEquals(ResourceTypeEnum.DATASET.getCode(), dataset.getType());
        assertEquals(MAIN_TABLE, dataset.getDatasetTable());
        assertEquals(CommonStatusEnum.ENABLE.getStatus(), dataset.getStatus());
        // DATASET 不是访问控制点 ——「能不能进这张表单」由它下面六个 ACTION 承担。
        // 设成 DENY 的话零配置环境下所有在线表单会被全面拒访，
        // 而授权页要到 S8a 才有，那期间没人能把它配回来
        assertEquals("ALLOW", dataset.getDefaultEffect());
        // 挂在「在线表单」这个共同父节点下，而不是散在根下
        ResourceDO parent = resourceMapper.selectById(dataset.getParentId());
        assertNotNull(parent);
        assertEquals("infra:form-design-run", parent.getPermission());

        // 没有这一步，规则配了框架也不会来调
        verify(ruleDataPermissionRule).registerTable(MAIN_TABLE);
    }

    @Test
    public void testRegister_已被约定式推导建成MENU的节点升级为DATASET() {
        // S2c 的约定式推导会按命名约定先把这个节点建成 MENU 目录
        ResourceDO derived = new ResourceDO();
        derived.setPermission(DatasetResourceNaming.dataset(FORM_CODE));
        derived.setName(FORM_CODE);
        derived.setType(ResourceTypeEnum.MENU.getCode());
        derived.setParentId(ResourceDO.ID_ROOT);
        derived.setSort(0);
        derived.setDefaultEffect("DENY");
        derived.setStatus(CommonStatusEnum.ENABLE.getStatus());
        derived.setPath("/root/" + derived.getPermission() + "/");
        derived.setDepth((short) 1);
        resourceMapper.insert(derived);

        DatasetResourceService.RegisterResult result = datasetResourceService.register(buildDescriptor());

        // 升级同一行，而不是另建一行 —— 另建会让祖先链断成两截
        assertEquals(derived.getId(), result.datasetResourceId());
        List<ResourceDO> all = resourceMapper.selectList(ResourceDO::getPermission,
                DatasetResourceNaming.dataset(FORM_CODE));
        assertEquals(1, all.size());
        assertEquals(ResourceTypeEnum.DATASET.getCode(), all.get(0).getType());
        assertEquals(MAIN_TABLE, all.get(0).getDatasetTable());
        // 🔴 换类型这一次必须按新类型的出厂值填。沿用 MENU 的 DENY 的话，
        // 新登记的在线表单会立刻走到 DataRowFilterResolver#defaultEffectOf 的 denyAll，
        // 没配过行级规则的人一张行也看不见
        assertEquals("ALLOW", all.get(0).getDefaultEffect());
    }

    @Test
    public void testRegister_已是DATASET的行不被刷回ALLOW() {
        // 塌缩计划器（applyStrictestDefaultEffect）与管理员都能合法地把数据集收紧成 DENY。
        // 登记流程每次启动都跑，无条件覆盖的表现是「收紧了、生效了、重启就自己变回去」：
        // 实测 system:dept 在 collapse-enabled 翻开后的第一次启动就被刷回 ALLOW，
        // 而它的动词成员行已退役，只授了 VIEW 的角色于是对新增/修改/删除拿到放行
        ResourceDO tightened = new ResourceDO();
        tightened.setPermission(DatasetResourceNaming.dataset(FORM_CODE));
        tightened.setName(FORM_CODE);
        tightened.setType(ResourceTypeEnum.DATASET.getCode());
        tightened.setParentId(ResourceDO.ID_ROOT);
        tightened.setSort(0);
        tightened.setDefaultEffect("DENY");
        tightened.setStatus(CommonStatusEnum.ENABLE.getStatus());
        tightened.setPath("/root/" + tightened.getPermission() + "/");
        tightened.setDepth((short) 1);
        resourceMapper.insert(tightened);

        datasetResourceService.register(buildDescriptor());

        ResourceDO after = resourceMapper.selectById(tightened.getId());
        assertEquals("DENY", after.getDefaultEffect(), "重复登记不许改动已收紧的默认效果");
        assertEquals(ResourceTypeEnum.DATASET.getCode(), after.getType());
        assertEquals(MAIN_TABLE, after.getDatasetTable());
    }

    @Test
    public void testRegister_六个动作资源被收拢到数据集节点下() {
        // 迁移过来的动作资源一律挂在根下
        ResourceDO action = new ResourceDO();
        action.setPermission(DatasetResourceNaming.dataset(FORM_CODE) + ":query");
        action.setName("查询");
        action.setType(ResourceTypeEnum.ACTION.getCode());
        action.setParentId(ResourceDO.ID_ROOT);
        action.setSort(0);
        action.setDefaultEffect("DENY");
        action.setStatus(CommonStatusEnum.ENABLE.getStatus());
        action.setPath("/root/" + action.getPermission() + "/");
        action.setDepth((short) 1);
        resourceMapper.insert(action);

        DatasetResourceService.RegisterResult result = datasetResourceService.register(buildDescriptor());

        ResourceDO moved = resourceMapper.selectById(action.getId());
        assertEquals(result.datasetResourceId(), moved.getParentId());
        // 路径要跟着重算，否则祖先链查询找不到它
        ResourceDO dataset = resourceMapper.selectById(result.datasetResourceId());
        assertTrue(moved.getPath().startsWith(dataset.getPath()),
                "动作路径(" + moved.getPath() + ")应以数据集路径(" + dataset.getPath() + ")为前缀");
    }

    // ============================= 按需创建 =============================

    @Test
    public void testRegister_普通字段不落行() {
        FormResourceDescriptorDTO descriptor = buildDescriptor();
        descriptor.getFields().add(item("title", "标题", false, false));
        descriptor.getFields().add(item("amount", "金额", false, false));

        DatasetResourceService.RegisterResult result = datasetResourceService.register(descriptor);

        assertEquals(0, result.createdCount());
        assertTrue(children(result.datasetResourceId()).isEmpty(),
                "普通字段不该落行 —— 这是资源表不爆炸的全部理由");
    }

    @Test
    public void testRegister_敏感字段落行且默认DENY() {
        FormResourceDescriptorDTO descriptor = buildDescriptor();
        descriptor.getFields().add(item("idCard", "身份证", true, false));
        descriptor.getFields().add(item("title", "标题", false, false));

        DatasetResourceService.RegisterResult result = datasetResourceService.register(descriptor);

        List<ResourceDO> children = children(result.datasetResourceId());
        assertEquals(1, children.size());
        ResourceDO field = children.get(0);
        assertEquals(ResourceTypeEnum.FIELD.getCode(), field.getType());
        assertEquals("身份证", field.getName());
        assertEquals("DENY", field.getDefaultEffect());
        assertEquals(DatasetResourceNaming.child(FORM_CODE, ResourceTypeEnum.FIELD, "idCard"),
                field.getPermission());
    }

    @Test
    public void testRegister_仅脱敏的字段落行但保持ALLOW() {
        // 翻成 DENY 会让这些字段在运行时裁剪落地那天对所有人直接消失，
        // 而没有任何人配置过什么。落行但 ALLOW：现状零变化，同时可以被单独收紧
        FormResourceDescriptorDTO descriptor = buildDescriptor();
        descriptor.getFields().add(item("mobile", "手机号", false, true));

        DatasetResourceService.RegisterResult result = datasetResourceService.register(descriptor);

        List<ResourceDO> children = children(result.datasetResourceId());
        assertEquals(1, children.size());
        assertEquals("ALLOW", children.get(0).getDefaultEffect());
    }

    @Test
    public void testRegister_列与字段分开落行() {
        FormResourceDescriptorDTO descriptor = buildDescriptor();
        descriptor.getFields().add(item("mobile", "手机号", true, false));
        descriptor.getColumns().add(item("mobile", "手机号", true, false));

        DatasetResourceService.RegisterResult result = datasetResourceService.register(descriptor);

        List<ResourceDO> children = children(result.datasetResourceId());
        assertEquals(2, children.size());
        // 列表看不到 ≠ 详情看不到，两者必须是两个可授权对象
        assertTrue(children.stream().anyMatch(r -> ResourceTypeEnum.FIELD.getCode().equals(r.getType())));
        assertTrue(children.stream().anyMatch(r -> ResourceTypeEnum.COLUMN.getCode().equals(r.getType())));
    }

    @Test
    public void testRegister_子表明细字段也能落行() {
        FormResourceDescriptorDTO descriptor = buildDescriptor();
        descriptor.getFields().add(item("items.mobile", "联系电话", true, false));

        DatasetResourceService.RegisterResult result = datasetResourceService.register(descriptor);

        List<ResourceDO> children = children(result.datasetResourceId());
        assertEquals(1, children.size());
        assertTrue(children.get(0).getPermission().endsWith(":fld:items.mobile"),
                "实际：" + children.get(0).getPermission());
    }

    // ============================= 重新发布 =============================

    @Test
    public void testRegister_重复登记幂等() {
        FormResourceDescriptorDTO descriptor = buildDescriptor();
        descriptor.getFields().add(item("idCard", "身份证", true, false));

        DatasetResourceService.RegisterResult first = datasetResourceService.register(descriptor);
        DatasetResourceService.RegisterResult second = datasetResourceService.register(buildSame(descriptor));

        assertEquals(first.datasetResourceId(), second.datasetResourceId());
        assertEquals(1, first.createdCount());
        assertEquals(0, second.createdCount(), "第二次不该再建一遍");
        assertEquals(1, children(first.datasetResourceId()).size());
    }

    @Test
    public void testRegister_不再敏感的字段被回收() {
        FormResourceDescriptorDTO first = buildDescriptor();
        first.getFields().add(item("idCard", "身份证", true, false));
        DatasetResourceService.RegisterResult firstResult = datasetResourceService.register(first);
        assertEquals(1, children(firstResult.datasetResourceId()).size());

        // 设计者取消了敏感标记后重新发布
        FormResourceDescriptorDTO second = buildDescriptor();
        second.getFields().add(item("idCard", "身份证", false, false));
        DatasetResourceService.RegisterResult secondResult = datasetResourceService.register(second);

        assertEquals(1, secondResult.removedCount());
        assertTrue(children(secondResult.datasetResourceId()).isEmpty());
    }

    @Test
    public void testRegister_配过授权的资源不被回收() {
        FormResourceDescriptorDTO first = buildDescriptor();
        first.getFields().add(item("idCard", "身份证", true, false));
        DatasetResourceService.RegisterResult firstResult = datasetResourceService.register(first);
        Long fieldId = children(firstResult.datasetResourceId()).get(0).getId();

        // 管理员给某个角色单独开了这个字段
        GrantDO grant = new GrantDO();
        grant.setSubjectType("ROLE");
        grant.setSubjectRefId(1L);
        grant.setResourceId(fieldId);
        grant.setAction("VIEW");
        grant.setEffect("ALLOW");
        grantMapper.insert(grant);

        // 之后取消了敏感标记重新发布
        FormResourceDescriptorDTO second = buildDescriptor();
        second.getFields().add(item("idCard", "身份证", false, false));
        DatasetResourceService.RegisterResult secondResult = datasetResourceService.register(second);

        assertEquals(0, secondResult.removedCount());
        assertNotNull(resourceMapper.selectById(fieldId),
                "回收它等于静默删掉管理员配过的授权");
    }

    @Test
    public void testRegister_字段改名后资源名跟着变() {
        FormResourceDescriptorDTO first = buildDescriptor();
        first.getFields().add(item("idCard", "身份证", true, false));
        DatasetResourceService.RegisterResult firstResult = datasetResourceService.register(first);

        FormResourceDescriptorDTO second = buildDescriptor();
        second.getFields().add(item("idCard", "证件号码", true, false));
        datasetResourceService.register(second);

        // 授权页上还显示旧名的话，管理员会以为自己配错了对象
        assertEquals("证件号码", children(firstResult.datasetResourceId()).get(0).getName());
    }

    @Test
    public void testRegister_取消敏感标记不把字段刷回ALLOW() {
        FormResourceDescriptorDTO first = buildDescriptor();
        first.getFields().add(item("mobile", "手机号", true, false));
        DatasetResourceService.RegisterResult firstResult = datasetResourceService.register(first);
        assertEquals("DENY", children(firstResult.datasetResourceId()).get(0).getDefaultEffect());

        // 「敏感」改成「仅脱敏」后重新发布：两种都 grantworthy，
        // 所以这一行走的是覆盖分支，而不是 testRegister_不再敏感的字段被回收 那条回收分支
        FormResourceDescriptorDTO second = buildDescriptor();
        second.getFields().add(item("mobile", "手机号", false, true));
        datasetResourceService.register(second);

        // 🔴 跟着描述符放开 = 一个曾被判定为敏感的字段重新对所有人可见，全程 200 无异常，
        // 没有人会报「我怎么突然能看见了」。停在 DENY 的代价是「这个字段仍要显式授权」，
        // 一定会有人来报，且管理员在资源页把它改回 ALLOW 就立刻恢复 —— 两个方向都静默时选代价小的
        assertEquals("DENY", children(firstResult.datasetResourceId()).get(0).getDefaultEffect(),
                "default_effect 只跟变严的方向，见 DatasetResourceServiceImpl#tightenOnly");
    }

    @Test
    public void testRegister_新标敏感仍能收紧成DENY() {
        FormResourceDescriptorDTO first = buildDescriptor();
        first.getFields().add(item("mobile", "手机号", false, true));
        DatasetResourceService.RegisterResult firstResult = datasetResourceService.register(first);
        assertEquals("ALLOW", children(firstResult.datasetResourceId()).get(0).getDefaultEffect());

        FormResourceDescriptorDTO second = buildDescriptor();
        second.getFields().add(item("mobile", "手机号", true, false));
        datasetResourceService.register(second);

        // 单向收紧不是「一律不覆盖」：那样 sensitive 标记就永远只能加不能撤。
        // 本例钉住另一半 —— 变严的方向必须照常跟上
        assertEquals("DENY", children(firstResult.datasetResourceId()).get(0).getDefaultEffect());
    }

    // ============================= 语义槽位 =============================

    @Test
    public void testRegister_槽位自动推断并落库() {
        DatasetResourceService.RegisterResult result = datasetResourceService.register(buildDescriptor());

        assertEquals("create_user_id", result.slots().get("owner"));
        assertEquals("status", result.slots().get("status"));
        SlotMappingDO owner = slotMappingMapper.selectByResourceIdAndSlot(
                result.datasetResourceId(), "owner");
        assertNotNull(owner);
        assertEquals("create_user_id", owner.getColumnName());
        assertNotNull(owner.getDescription(), "说明要写，规则编辑器要展示它");
    }

    @Test
    public void testRegister_已存在的槽位只补不改() {
        DatasetResourceService.RegisterResult first = datasetResourceService.register(buildDescriptor());

        // 人工在规则编辑器里把 owner 改到了别的列
        SlotMappingDO owner = slotMappingMapper.selectByResourceIdAndSlot(
                first.datasetResourceId(), "owner");
        owner.setColumnName("sales_user_id");
        slotMappingMapper.updateById(owner);

        datasetResourceService.register(buildDescriptor());

        // 自动推断是「猜」，人工改过的才是准的。覆盖回去的表现是
        // 「规则按错误的列过滤」，且没有任何提示
        assertEquals("sales_user_id", slotMappingMapper
                .selectByResourceIdAndSlot(first.datasetResourceId(), "owner").getColumnName());
        assertEquals(1, slotMappingMapper.selectListByResourceId(first.datasetResourceId()).stream()
                .filter(s -> "owner".equals(s.getSlot())).count(), "不该插出第二行 owner");
    }

    // ============================= 启动时重建 =============================

    @Test
    public void testReloadRegisteredTables_把已登记的表灌回内存() {
        datasetResourceService.register(buildDescriptor());

        int count = datasetResourceService.reloadRegisteredTables();

        assertEquals(1, count);
        // 不做这一步的话，重启后所有行级规则静默失效
        verify(ruleDataPermissionRule, org.mockito.Mockito.atLeast(2)).registerTable(MAIN_TABLE);
    }

    // ============================= 兜底 =============================

    @Test
    public void testRegister_描述不完整时跳过而不抛异常() {
        assertNull(datasetResourceService.register(null).datasetResourceId());

        FormResourceDescriptorDTO noTable = new FormResourceDescriptorDTO();
        noTable.setFormCode(FORM_CODE);
        assertNull(datasetResourceService.register(noTable).datasetResourceId());
    }

    // ============================= 注销（表单被删除） =============================

    @Test
    public void testUnregister_注销整棵子树与授权和槽位() {
        FormResourceDescriptorDTO descriptor = buildDescriptor();
        descriptor.getFields().add(item("mobile", "手机号", true, false));
        DatasetResourceService.RegisterResult registered = datasetResourceService.register(descriptor);
        Long datasetId = registered.datasetResourceId();
        Long fieldId = children(datasetId).get(0).getId();

        GrantDO onDataset = new GrantDO();
        onDataset.setSubjectType("ROLE");
        onDataset.setSubjectRefId(1L);
        onDataset.setResourceId(datasetId);
        onDataset.setAction("VIEW");
        onDataset.setEffect("ALLOW");
        grantMapper.insert(onDataset);
        GrantDO onField = new GrantDO();
        onField.setSubjectType("ROLE");
        onField.setSubjectRefId(1L);
        onField.setResourceId(fieldId);
        onField.setAction("VIEW");
        onField.setEffect("DENY");
        grantMapper.insert(onField);

        DatasetResourceService.UnregisterResult result = datasetResourceService.unregister(FORM_CODE);

        assertEquals(datasetId, result.datasetResourceId());
        assertEquals(2, result.removedResourceCount(), "数据集锚点 + 那一个敏感字段资源");
        assertEquals(2, result.removedGrantCount(), "锚点与子资源上的授权都要级联清掉");
        // 不写死槽位个数：SlotInferencer 推断出几个，注销就得清掉几个
        assertEquals(registered.slots().size(), result.removedSlotCount(), "推断出来的槽位要全部清掉");
        assertNull(resourceMapper.selectById(datasetId));
        assertNull(resourceMapper.selectById(fieldId));
        assertEquals(0, grantMapper.selectCountByResourceId(datasetId));
        assertEquals(0, grantMapper.selectCountByResourceId(fieldId));
        assertTrue(slotMappingMapper.selectListByResourceId(datasetId).isEmpty());
        // ⚠️ 槽位走的是物理删除（uk_resource_slot 不含 deleted，软删会把这个组合永久占住），
        //    但本测试库的 create_tables.sql 刻意不建任何唯一索引，所以那条只在生产上炸的路径
        //    在这里复现不出来。物理删除本身是 SlotMappingMapper#deleteByIdPhysically 的契约
    }

    @Test
    public void testUnregister_锚点不存在时什么都不做() {
        DatasetResourceService.UnregisterResult result = datasetResourceService.unregister("never_published");

        // 删一张从未发布过的草稿表单必然走到这里，这是最常见的正常路径，不该抛也不该告警
        assertNull(result.datasetResourceId());
        assertEquals(0, result.removedResourceCount());
        assertEquals(0, result.removedGrantCount());
        assertEquals(0, result.removedSlotCount());
        assertNull(datasetResourceService.unregister(null).datasetResourceId());
        assertNull(datasetResourceService.unregister("  ").datasetResourceId());
    }

    @Test
    public void testUnregister_重新占用同一个编码时建出干净的一行() {
        // 🔴 这条钉住的是本缺陷里唯一会真正影响鉴权的那一半。
        //
        // deleteFormDesign 会把 code 从 uk_code（不含 deleted 的全局唯一索引）里让出来，
        // 于是同一个编码能被新表单重新占用。注销之前，register 走的
        // selectByPermissionIncludeDisabled 只滤 deleted=0，会把那条<b>没被删掉</b>的旧锚点
        // 原样捞出来复用 —— 连带它身上被收紧过的 default_effect，
        // 以及 syncSlots「只补不改」策略下永远留着的旧槽位映射。
        // 结果是一张全新的表单静默继承了死表单的权限配置，全程 200 无异常
        FormResourceDescriptorDTO first = buildDescriptor();
        DatasetResourceService.RegisterResult firstResult = datasetResourceService.register(first);
        Long oldId = firstResult.datasetResourceId();

        // 模拟两个合法的、register 无权替它们做主的写入方
        ResourceDO tightened = resourceMapper.selectById(oldId);
        tightened.setDefaultEffect("DENY");           // 塌缩计划器 / 管理员收紧过
        resourceMapper.updateById(tightened);
        SlotMappingDO owner = slotMappingMapper.selectByResourceIdAndSlot(oldId, "owner");
        owner.setColumnName("some_legacy_column");    // 管理员在规则编辑器里人工改过
        slotMappingMapper.updateById(owner);

        datasetResourceService.unregister(FORM_CODE);
        DatasetResourceService.RegisterResult second =
                datasetResourceService.register(buildDescriptor());

        assertNotEquals(oldId, second.datasetResourceId(), "必须建出新的一行，而不是复用墓碑");
        ResourceDO fresh = resourceMapper.selectById(second.datasetResourceId());
        assertEquals("ALLOW", fresh.getDefaultEffect(),
                "新表单不该继承死表单被收紧过的默认效果，否则没人配过行级规则就一行也看不见");
        assertEquals("create_user_id", second.slots().get("owner"),
                "槽位要重新推断，而不是留着人工改过的那一版");
        assertEquals("create_user_id",
                slotMappingMapper.selectByResourceIdAndSlot(second.datasetResourceId(), "owner")
                        .getColumnName());
    }

    // ==================== 构造 ====================

    private FormResourceDescriptorDTO buildDescriptor() {
        FormResourceDescriptorDTO dto = new FormResourceDescriptorDTO();
        dto.setFormId(1L);
        dto.setFormCode(FORM_CODE);
        dto.setFormName("示例订单");
        dto.setDataSourceConfigId(0L);
        dto.setMainTable(MAIN_TABLE);
        dto.setMainPk("id");
        dto.setTableColumns(new ArrayList<>(List.of("id", "title", "create_user_id", "status")));
        dto.setFields(new ArrayList<>());
        dto.setColumns(new ArrayList<>());
        return dto;
    }

    /**
     * 复制一份等价描述，模拟「同一张表单再发布一次」
     */
    private FormResourceDescriptorDTO buildSame(FormResourceDescriptorDTO origin) {
        FormResourceDescriptorDTO dto = buildDescriptor();
        dto.getFields().addAll(origin.getFields());
        dto.getColumns().addAll(origin.getColumns());
        return dto;
    }

    private FormResourceDescriptorDTO.Item item(String key, String label,
                                                boolean sensitive, boolean masked) {
        FormResourceDescriptorDTO.Item item = new FormResourceDescriptorDTO.Item();
        item.setKey(key);
        item.setLabel(label);
        item.setSensitive(sensitive);
        item.setMasked(masked);
        return item;
    }

    private List<ResourceDO> children(Long datasetId) {
        return resourceMapper.selectListByParentIdAndTypes(datasetId,
                List.of(ResourceTypeEnum.COLUMN.getCode(), ResourceTypeEnum.FIELD.getCode()));
    }

}
