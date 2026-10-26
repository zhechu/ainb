package cn.enlev.ainb.module.system.framework.permission.core.rule;

import cn.enlev.ainb.framework.test.core.ut.BaseDbUnitTest;
import cn.enlev.ainb.module.system.dal.dataobject.permission.SlotMappingDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.SlotMappingMapper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ConditionTreeEvaluator} 的单元测试类
 *
 * ⚠️ v10 S3b 要求：注入用例必须覆盖
 *
 * 安全边界三道防线：
 *   1. 操作符白名单（防止任意 SQL 操作符注入）
 *   2. 列名白名单（列名来自 sys_slot_mapping，不来自用户输入）
 *   3. 参数化（值通过占位符传参，不拼接 SQL）
 *
 * @author 走进新时代
 */
@Import(ConditionTreeEvaluator.class)
public class ConditionTreeEvaluatorTest extends BaseDbUnitTest {

    @Resource
    private ConditionTreeEvaluator evaluator;
    @Resource
    private SlotMappingMapper slotMappingMapper;

    // ============================= 基础功能 =============================

    @Test
    public void testEvaluate_单条件_变量引用() {
        insertSlotMapping(1L, "owner", "create_user_id");

        ConditionNode node = ConditionNode.condition("owner", "EQ", ":currentUserId");
        ConditionTreeEvaluator.SqlFragment result = evaluator.evaluate(node, 1L,
                Map.of("currentUserId", 42L));

        assertEquals("create_user_id = ?", result.sql());
        assertEquals(1, result.params().size());
        assertEquals(42L, result.params().get(0));
    }

    @Test
    public void testEvaluate_IN条件_集合变量() {
        insertSlotMapping(1L, "dept", "dept_id");

        ConditionNode node = ConditionNode.condition("dept", "IN", ":currentDeptAndChildren");
        ConditionTreeEvaluator.SqlFragment result = evaluator.evaluate(node, 1L,
                Map.of("currentDeptAndChildren", List.of(10L, 20L, 30L)));

        assertEquals("dept_id IN (?, ?, ?)", result.sql());
        assertEquals(3, result.params().size());
    }

    @Test
    public void testEvaluate_AND组合() {
        insertSlotMapping(1L, "owner", "create_user_id");
        insertSlotMapping(1L, "status", "biz_status");

        ConditionNode node = ConditionNode.group(ConditionNode.LogicOperator.AND, List.of(
                ConditionNode.condition("owner", "EQ", ":currentUserId"),
                ConditionNode.condition("status", "EQ", "1")
        ));
        ConditionTreeEvaluator.SqlFragment result = evaluator.evaluate(node, 1L,
                Map.of("currentUserId", 7L));

        assertTrue(result.sql().contains("AND"), "AND 组合应该包含 AND");
        assertEquals(2, result.params().size());
    }

    @Test
    public void testEvaluate_IS_NULL不需要参数() {
        insertSlotMapping(1L, "owner", "create_user_id");

        ConditionNode node = ConditionNode.condition("owner", "IS_NULL", null);
        ConditionTreeEvaluator.SqlFragment result = evaluator.evaluate(node, 1L, Map.of());

        assertEquals("create_user_id IS NULL", result.sql());
        assertTrue(result.params().isEmpty());
    }

    @Test
    public void testEvaluate_IN空集合返回1等于0() {
        insertSlotMapping(1L, "dept", "dept_id");

        ConditionNode node = ConditionNode.condition("dept", "IN", ":depts");
        ConditionTreeEvaluator.SqlFragment result = evaluator.evaluate(node, 1L,
                Map.of("depts", List.of()));

        assertEquals("1=0", result.sql(), "IN 空集合应该返回 1=0（永远为假）");
    }

    /**
     * 🔴 NOT_IN 空集合是 1=1，与上一条方向相反，这是对的，别「统一」掉
     *
     * <p>「用户一个角色都没有」时，{@code 角色 NOT_IN (空)} 本该不排除任何角色 ——
     * SQL 三值逻辑下 {@code x NOT IN ()} 就是真。把它改成 1=0 的话，
     * 一个没有任何角色的用户会在所有「排除某些角色」的规则下看不到任何数据，
     * 而那恰恰是最该被这类规则放过去的人。
     *
     * <p>之所以要专门钉住：{@code :currentRoleIds} 曾经恒为空集合
     * （{@code BuiltInVariableProvider} 里写死 {@code Set.of()}），
     * 于是这条分支是唯一会被走到的那条，改它的诱惑很大。
     */
    @Test
    public void testEvaluate_NOT_IN空集合返回1等于1() {
        insertSlotMapping(1L, "role", "role_id");

        ConditionNode node = ConditionNode.condition("role", "NOT_IN", ":roles");
        ConditionTreeEvaluator.SqlFragment result = evaluator.evaluate(node, 1L,
                Map.of("roles", List.of()));

        assertEquals("1=1", result.sql(), "NOT_IN 空集合应该返回 1=1（永远为真）");
        assertTrue(result.params().isEmpty());
    }

    // ============================= ⚠️ 注入用例（v10 S3b 必测）=============================

    /**
     * 注入用例 1：操作符注入
     *
     * 攻击者把操作符设成 `= 1; DROP TABLE system_users --` 之类的字符串。
     * 防线：操作符只允许 ConditionOperatorEnum 里的值，未知操作符 → 返回空片段，不生成 SQL。
     */
    @Test
    public void testEvaluate_注入用例1_未知操作符被拒绝() {
        insertSlotMapping(1L, "owner", "create_user_id");

        ConditionNode node = ConditionNode.condition("owner",
                "= 1; DROP TABLE system_users --",  // 注入字符串
                ":currentUserId");
        ConditionTreeEvaluator.SqlFragment result = evaluator.evaluate(node, 1L,
                Map.of("currentUserId", 1L));

        assertTrue(result.isEmpty(), "未知操作符应该返回空片段，不生成任何 SQL");
        assertTrue(result.unevaluable(),
                "操作符不在白名单里说明规则本身配坏了，必须让整条规则失效而不是只丢掉这个条件");
    }

    /**
     * 注入用例 2：槽位名注入（试图绕过白名单直接引用列名）
     *
     * 攻击者把 slot 设成真实列名加注入字符串，如 `create_user_id = 1 OR 1=1 --`。
     * 防线：slot 只是语义名，真实列名从 sys_slot_mapping 里取。
     * sys_slot_mapping 里没有这个 slot → 返回空片段。
     */
    @Test
    public void testEvaluate_注入用例2_不存在的槽位被跳过() {
        // 注意：不在 sys_slot_mapping 里插入任何槽位映射

        ConditionNode node = ConditionNode.condition(
                "create_user_id = 1 OR 1=1 --",  // 试图注入的 slot 名
                "EQ", ":currentUserId");
        ConditionTreeEvaluator.SqlFragment result = evaluator.evaluate(node, 1L,
                Map.of("currentUserId", 1L));

        assertTrue(result.isEmpty(), "不存在的槽位应该返回空片段，不生成任何 SQL");
        assertTrue(result.unevaluable(), "槽位没配映射必须让整条规则失效，不能静默变成不过滤");
    }

    /**
     * 注入用例 3：列名污染（sys_slot_mapping 里存了恶意列名）
     *
     * 假设 DB 数据被污染，column_name 里存了 `create_user_id; DROP TABLE` 这样的字符串。
     * 防线：列名正则校验 `[a-zA-Z_][a-zA-Z0-9_]*`，包含特殊字符 → 拒绝使用，返回空片段。
     */
    @Test
    public void testEvaluate_注入用例3_非法列名被正则拦截() {
        // 手动插入一条含非法字符的列名映射
        SlotMappingDO badMapping = new SlotMappingDO();
        badMapping.setResourceId(1L);
        badMapping.setSlot("evil_slot");
        badMapping.setColumnName("create_user_id; DROP TABLE system_users --");
        slotMappingMapper.insert(badMapping);

        ConditionNode node = ConditionNode.condition("evil_slot", "EQ", ":currentUserId");
        ConditionTreeEvaluator.SqlFragment result = evaluator.evaluate(node, 1L,
                Map.of("currentUserId", 1L));

        assertTrue(result.isEmpty(), "包含特殊字符的列名应该被正则拦截，不生成任何 SQL");
        assertFalse(result.sql() != null && result.sql().contains("DROP"),
                "SQL 片段里不能出现注入的 DROP 关键字");
        assertTrue(result.unevaluable(), "被拦下来的列名同样要让整条规则失效");
    }

    /**
     * 注入用例 5：把表名当成注入点
     *
     * table_name 是新加的列，而它<b>永远不该被拼进 SQL</b> ——
     * 它只是查「本次查询里这张表叫什么」的 key。
     * 这里存一个带注入串的表名：查不到 ⟹ 求不出来 ⟹ 整条规则失效，
     * 而不是把这段字符串拼进 SQL
     */
    @Test
    public void testEvaluate_注入用例5_表名不会被拼进SQL() {
        insertSlotMapping(1L, "owner", "biz_order; DROP TABLE system_users --", "create_user_id");

        ConditionNode node = ConditionNode.condition("owner", "EQ", ":currentUserId");
        ConditionTreeEvaluator.SqlFragment result = evaluator.evaluate(node, 1L,
                Map.of("currentUserId", 1L), "`biz_order`.",
                Map.of("biz_order", "`biz_order`."));

        assertTrue(result.isEmpty(), "表名对不上时不该生成任何 SQL");
        assertTrue(result.unevaluable());
        assertFalse(result.sql() != null && result.sql().contains("DROP"),
                "表名绝不能出现在 SQL 片段里，它只是查限定符的 key");
    }

    // ============================= 表限定符（tableQualifiers）=============================

    /**
     * 槽位绑在带别名的关联表上时，必须用别名去限定
     *
     * 这正是「一定要绑定是哪张表」要解决的问题：JOIN 一旦给关联表起了别名，
     * 用物理表名限定就是非法 SQL（MySQL 报 Unknown column）；
     * 而只用主表前缀会匹配到主表的同名列 —— 语法合法、语义错误，且不报错
     */
    @Test
    public void testEvaluate_槽位绑在带别名的关联表上_用别名限定() {
        insertSlotMapping(1L, "owner", "system_users", "id");

        ConditionNode node = ConditionNode.condition("owner", "EQ", ":currentUserId");
        ConditionTreeEvaluator.SqlFragment result = evaluator.evaluate(node, 1L,
                Map.of("currentUserId", 42L), "`biz_order`.",
                Map.of("biz_order", "`biz_order`.", "system_users", "`u`."));

        assertEquals("`u`.id = ?", result.sql(), "关联表带别名时只能用别名限定");
        assertFalse(result.unevaluable());
    }

    /**
     * 同一张关联表不带别名时，限定符就是物理表名本身
     */
    @Test
    public void testEvaluate_槽位绑在不带别名的关联表上_用表名限定() {
        insertSlotMapping(1L, "dept", "system_dept", "id");

        ConditionNode node = ConditionNode.condition("dept", "EQ", ":currentDeptId");
        ConditionTreeEvaluator.SqlFragment result = evaluator.evaluate(node, 1L,
                Map.of("currentDeptId", 7L), "",
                Map.of("biz_order", "", "system_dept", "`system_dept`."));

        assertEquals("`system_dept`.id = ?", result.sql());
        assertFalse(result.unevaluable());
    }

    /**
     * 🔴 槽位绑的表不在本次查询里 → 求不出来，而不是悄悄用主表前缀去凑
     *
     * 用主表前缀去凑的后果是一条<b>语法合法、语义错误</b>的过滤：
     * 主表上若正好有同名列，就会按错误的列过滤，全程不报错
     */
    @Test
    public void testEvaluate_槽位绑的表不在本次查询里_判为求不出来() {
        insertSlotMapping(1L, "owner", "system_users", "id");

        ConditionNode node = ConditionNode.condition("owner", "EQ", ":currentUserId");
        ConditionTreeEvaluator.SqlFragment result = evaluator.evaluate(node, 1L,
                Map.of("currentUserId", 42L), "`biz_order`.",
                Map.of("biz_order", "`biz_order`."));

        assertTrue(result.unevaluable(),
                "本次查询没有 JOIN system_users，这个槽位求不出来，必须让整条规则失效");
        assertFalse(result.sql().contains("system_users"),
                "表名不该被拼进 SQL：" + result.sql());
    }

    /**
     * AND 组里一个条件绑错表 → 整棵树求不出来
     *
     * 这是「配错一半比全配错放得更开」那个缺陷的正面对照：
     * 若只丢掉绑错的那个条件，产出就成了 {@code (`biz_order`.creator = ?)}，
     * 比管理员配的 AND 宽
     */
    @Test
    public void testEvaluate_AND组里一个条件绑错表_整棵树失效() {
        insertSlotMapping(1L, "owner", "biz_order", "creator");
        insertSlotMapping(1L, "dept", "system_dept", "id");

        ConditionNode node = ConditionNode.group(ConditionNode.LogicOperator.AND, List.of(
                ConditionNode.condition("owner", "EQ", ":currentUserId"),
                ConditionNode.condition("dept", "EQ", ":currentDeptId")
        ));
        ConditionTreeEvaluator.SqlFragment result = evaluator.evaluate(node, 1L,
                Map.of("currentUserId", 42L, "currentDeptId", 7L), "`biz_order`.",
                Map.of("biz_order", "`biz_order`."));

        assertTrue(result.unevaluable());
        assertFalse(result.sql().contains(" AND "),
                "不能把剩下的半截条件当成完整规则用：" + result.sql());
    }

    /**
     * tableName 为空 = 数据集主表（存量行的语义，见迁移脚本的回填）
     */
    @Test
    public void testEvaluate_所属表为空时按主表处理() {
        insertSlotMapping(1L, "owner", null, "create_user_id");

        ConditionNode node = ConditionNode.condition("owner", "EQ", ":currentUserId");
        ConditionTreeEvaluator.SqlFragment result = evaluator.evaluate(node, 1L,
                Map.of("currentUserId", 42L), "`biz_order`.",
                Map.of("biz_order", "`biz_order`."));

        assertEquals("`biz_order`.create_user_id = ?", result.sql());
        assertFalse(result.unevaluable());
    }

    /**
     * 传 null 表示「调用方不知道本次查询有哪些表」→ 一律用 columnPrefix
     *
     * 守的是向后兼容：加 tableQualifiers 这个参数之前，所有调用口都是这个行为，
     * 逐字节一致才不会让存量规则突然改变过滤范围
     */
    @Test
    public void testEvaluate_限定符表为null时退回列前缀() {
        insertSlotMapping(1L, "owner", "system_users", "id");

        ConditionNode node = ConditionNode.condition("owner", "EQ", ":currentUserId");
        ConditionTreeEvaluator.SqlFragment result = evaluator.evaluate(node, 1L,
                Map.of("currentUserId", 42L), "`biz_order`.");

        assertEquals("`biz_order`.id = ?", result.sql());
        assertFalse(result.unevaluable());
    }

    /**
     * 变量在上下文里取不到 → 只是跳过这个条件，<b>不</b>判整条规则失效
     *
     * 与上面几条的区别是有意的：那说的是<b>当前这个用户</b>的数据状况
     * （比如他没有部门），不是配置错误。OWNER_OR_DEPT 遇上没有部门的用户，
     * 退化成「只看自己的」正是想要的行为
     */
    @Test
    public void testEvaluate_变量取不到不判为求不出来() {
        insertSlotMapping(1L, "owner", "biz_order", "creator");
        insertSlotMapping(1L, "dept", "biz_order", "dept_id");

        ConditionNode node = ConditionNode.group(ConditionNode.LogicOperator.OR, List.of(
                ConditionNode.condition("owner", "EQ", ":currentUserId"),
                ConditionNode.condition("dept", "IN", ":currentDeptAndChildren")
        ));
        ConditionTreeEvaluator.SqlFragment result = evaluator.evaluate(node, 1L,
                Map.of("currentUserId", 42L), "", Map.of("biz_order", ""));

        assertFalse(result.unevaluable(),
                "没有部门的用户该退化成只看自己的，而不是被判成一行都看不到");
        assertEquals("creator = ?", result.sql());
    }

    /**
     * 注入用例 4：值注入（字面量值包含 SQL 片段）
     *
     * 攻击者把字面量值设成 `1 OR 1=1`。
     * 防线：字面量值通过参数化（?）传递，不拼接到 SQL 里，MyBatis 会对它做转义。
     * 生成的 SQL 是 `column = ?`，不是 `column = 1 OR 1=1`。
     */
    @Test
    public void testEvaluate_注入用例4_字面量值通过参数化传递() {
        insertSlotMapping(1L, "status", "biz_status");

        ConditionNode node = ConditionNode.condition("status", "EQ",
                "1 OR 1=1");  // 注入字符串作为字面量
        ConditionTreeEvaluator.SqlFragment result = evaluator.evaluate(node, 1L, Map.of());

        // 生成的 SQL 里是 ?，不是拼接的注入字符串
        assertEquals("biz_status = ?", result.sql());
        assertEquals(1, result.params().size());
        assertEquals("1 OR 1=1", result.params().get(0));  // 值在参数列表里，不在 SQL 里
        assertFalse(result.sql().contains("OR"), "注入字符串不应该出现在 SQL 片段里");
    }

    // ============================= 工具方法 =============================

    private void insertSlotMapping(Long resourceId, String slot, String columnName) {
        insertSlotMapping(resourceId, slot, null, columnName);
    }

    private void insertSlotMapping(Long resourceId, String slot, String tableName, String columnName) {
        SlotMappingDO mapping = new SlotMappingDO();
        mapping.setResourceId(resourceId);
        mapping.setSlot(slot);
        mapping.setTableName(tableName);
        mapping.setColumnName(columnName);
        slotMappingMapper.insert(mapping);
    }

}
