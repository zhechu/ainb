package cn.enlev.ainb.module.system.enums.permission;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 规则模板「视图元数据」注册表
 *
 * <h3>为什么单独建这个类，而不是往 {@link RuleTemplateEnum} 里塞字段</h3>
 *
 * 这里装的是<b>视图层关注点</b>：分组、给业务人员看的白话说明、以及每个参数对应的
 * 前端子编辑器种类（{@code kind}）。{@link RuleTemplateEnum} 被 resolver / evaluator
 * 等运行时链路引用，把这些 UI 字段塞进去会让领域枚举变得又长又难 review。
 *
 * <h3>防漂移</h3>
 *
 * 参数契约的<b>真相</b>是 {@code DataRowFilterResolver#expandBuiltInTemplate}——它决定
 * 每个模板实际会读 params 里的哪个 key。为了让「注册表声明的 key」与「resolver 读取的 key」
 * 不会各写各的，两边都引用本类的 {@code PARAM_*} 常量。
 * ⚠️ 改这里必须同步改 {@code DataRowFilterResolver#expandBuiltInTemplate}，反之亦然。
 * 另有 {@code RuleTemplateMetaRegistryTest} 做穷尽性校验：新增模板而忘了登记会直接测试失败。
 *
 * @author 走进新时代
 */
public final class RuleTemplateMetaRegistry {

    private RuleTemplateMetaRegistry() {
    }

    // ===== params 的 key 常量（与 DataRowFilterResolver 共用，杜绝字符串漂移）=====
    public static final String PARAM_ALLOWED_STATUSES = "allowedStatuses";
    public static final String PARAM_DEPT_IDS = "deptIds";
    public static final String PARAM_FIELD = "field";
    public static final String PARAM_VALUE = "value";
    public static final String PARAM_CONDITION_TREE = "conditionTree";

    // ===== 分组常量 =====
    /** 基础范围模板：按归属/部门/状态圈一批行 */
    public static final String GROUP_BASIC = "basic";
    /** 派生可见性：由流程参与 / 记录共享反推可见行，无参数 */
    public static final String GROUP_DERIVED = "derived";
    /** 高级：自定义条件树 */
    public static final String GROUP_ADVANCED = "advanced";

    // ===== 参数子编辑器种类（1:1 对应前端组件）=====
    /** 状态值列表（tag 输入） */
    public static final String KIND_STATUS_LIST = "STATUS_LIST";
    /** 部门多选（部门树） */
    public static final String KIND_DEPT_MULTI = "DEPT_MULTI";
    /** 语义槽位选择 */
    public static final String KIND_SLOT = "SLOT";
    /** 普通文本 */
    public static final String KIND_TEXT = "TEXT";
    /** 可视化条件树构建器 */
    public static final String KIND_CONDITION_TREE = "CONDITION_TREE";

    // ===== 值类型（前端序列化时据此决定 number/string）=====
    public static final String VALUE_TYPE_NUMBER = "number";
    public static final String VALUE_TYPE_STRING = "string";

    /**
     * 单个模板参数的录入规格
     *
     * @param key       params JSON 里的键，取 {@code PARAM_*} 常量
     * @param kind      子编辑器种类，取 {@code KIND_*} 常量
     * @param label     中文标签
     * @param required  是否必填
     * @param valueType 值类型（{@code VALUE_TYPE_NUMBER}/{@code VALUE_TYPE_STRING}），无则 null
     */
    public record ParamSpec(String key, String kind, String label, boolean required, String valueType) {
    }

    /**
     * 单个模板的视图元数据
     *
     * @param group               分组，取 {@code GROUP_*} 常量
     * @param businessDescription 给业务人员看的白话说明
     * @param requiredSlots       该模板依赖的语义槽位（取 {@link PermissionSlotEnum#getSlot()}），
     *                            为空表示「取决于参数」（如 FIELD_EQUALS 的槽位由 field 参数决定）
     * @param paramSpecs          参数规格列表，无参数模板为空列表
     */
    public record Meta(String group, String businessDescription,
                       List<String> requiredSlots, List<ParamSpec> paramSpecs) {
    }

    private static final Map<RuleTemplateEnum, Meta> REGISTRY = new EnumMap<>(RuleTemplateEnum.class);

    static {
        String owner = PermissionSlotEnum.OWNER.getSlot();
        String dept = PermissionSlotEnum.DEPT.getSlot();
        String status = PermissionSlotEnum.STATUS.getSlot();
        String bizKey = PermissionSlotEnum.BIZ_KEY.getSlot();

        // ===== 基础范围模板（多数无参数）=====
        REGISTRY.put(RuleTemplateEnum.OWNER_ONLY, new Meta(GROUP_BASIC,
                "只能看到自己创建（归属）的记录。适用于「销售只看自己的客户」这类场景。",
                List.of(owner), List.of()));
        REGISTRY.put(RuleTemplateEnum.DEPT_ONLY, new Meta(GROUP_BASIC,
                "只能看到本部门的记录。适用于部门内数据共享。",
                List.of(dept), List.of()));
        REGISTRY.put(RuleTemplateEnum.DEPT_AND_CHILDREN, new Meta(GROUP_BASIC,
                "能看到本部门及所有下级部门的记录。适用于大区/上级经理查看下属部门数据。",
                List.of(dept), List.of()));
        REGISTRY.put(RuleTemplateEnum.OWNER_OR_DEPT, new Meta(GROUP_BASIC,
                "能看到自己创建的，或本部门的记录（两者取并集）。",
                List.of(owner, dept), List.of()));
        REGISTRY.put(RuleTemplateEnum.STATUS_IN, new Meta(GROUP_BASIC,
                "只能看到指定状态的记录。请在下方填写允许查看的状态值（可多个）。",
                List.of(status),
                List.of(new ParamSpec(PARAM_ALLOWED_STATUSES, KIND_STATUS_LIST,
                        "允许的状态值", true, VALUE_TYPE_NUMBER))));
        REGISTRY.put(RuleTemplateEnum.DEPT_IN, new Meta(GROUP_BASIC,
                "能看到指定部门的记录；系统会自动兜底包含配置人自己所在的部门（否则其登录后可能查不到自身数据）。"
                        + "注意：仅所选部门本身，不含下级——含下级请改用「本部门及下级部门」模板。",
                List.of(dept),
                List.of(new ParamSpec(PARAM_DEPT_IDS, KIND_DEPT_MULTI,
                        "指定部门", true, VALUE_TYPE_NUMBER))));
        REGISTRY.put(RuleTemplateEnum.FIELD_EQUALS, new Meta(GROUP_BASIC,
                "只能看到某个字段等于指定值的记录。请选择字段（语义槽位）并填写期望值。",
                List.of(), // 槽位由 field 参数决定
                List.of(
                        new ParamSpec(PARAM_FIELD, KIND_SLOT, "字段（槽位）", true, VALUE_TYPE_STRING),
                        new ParamSpec(PARAM_VALUE, KIND_TEXT, "字段值", true, VALUE_TYPE_STRING))));

        // ===== 派生可见性（无参数）=====
        REGISTRY.put(RuleTemplateEnum.BPM_PARTICIPANT, new Meta(GROUP_DERIVED,
                "只能看到自己参与过流程的单据（由流程参与关系自动反推，无需参数）。",
                List.of(bizKey), List.of()));
        REGISTRY.put(RuleTemplateEnum.RECORD_SHARED, new Meta(GROUP_DERIVED,
                "只能看到别人单独共享给我的记录（由记录共享自动反推，无需参数）。",
                List.of(bizKey), List.of()));

        // ===== 高级：自定义条件树 =====
        REGISTRY.put(RuleTemplateEnum.CUSTOM_TREE, new Meta(GROUP_ADVANCED,
                "用可视化条件树表达任意复杂的行过滤条件（面向高级用户）。",
                List.of(),
                List.of(new ParamSpec(PARAM_CONDITION_TREE, KIND_CONDITION_TREE,
                        "条件树", true, null))));
    }

    /**
     * 取模板元数据；未登记返回 null（正常不会发生，有穷尽性单测兜底）
     */
    public static Meta get(RuleTemplateEnum template) {
        return REGISTRY.get(template);
    }

    /**
     * 全部已登记模板的元数据（按枚举声明顺序无从保证，调用方按需排序）
     */
    public static Collection<Meta> all() {
        return REGISTRY.values();
    }

    /**
     * 已登记模板数量，供单测断言「枚举与注册表一一对应」
     */
    public static int size() {
        return REGISTRY.size();
    }

}
