package cn.enlev.ainb.module.system.framework.permission.core.rule;

import cn.enlev.ainb.module.system.dal.dataobject.permission.SlotMappingDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.SlotMappingMapper;
import cn.enlev.ainb.framework.test.core.ut.BaseDbUnitTest;
import jakarta.annotation.Resource;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.expression.Expression;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ConditionTreeEvaluator} 的真实 SQL 断言测试
 *
 * v10 S3c 要求：真实 SQL 断言（用 JSqlParser 验证生成的 SQL 语法正确）
 *
 * @author 走进新时代
 */
@Import(ConditionTreeEvaluator.class)
public class ConditionTreeSqlTest extends BaseDbUnitTest {

    @Resource
    private ConditionTreeEvaluator evaluator;
    @Resource
    private SlotMappingMapper slotMappingMapper;

    /**
     * 验证单条件 EQ 生成的 SQL 语法可被 JSqlParser 解析
     */
    @Test
    public void testSql_单条件_JSqlParser能解析() throws Exception {
        insertSlotMapping(10L, "owner", "create_user_id");

        ConditionNode node = ConditionNode.condition("owner", "EQ", ":currentUserId");
        ConditionTreeEvaluator.SqlFragment fragment = evaluator.evaluate(node, 10L,
                Map.of("currentUserId", 42L));

        assertFalse(fragment.isEmpty());
        // 把参数填进 SQL，用 JSqlParser 解析验证语法正确
        String fullSql = fragment.sql().replace("?", fragment.params().get(0).toString());
        Expression expr = CCJSqlParserUtil.parseCondExpression(fullSql);
        assertNotNull(expr, "JSqlParser 应能解析生成的 SQL");
        assertEquals("create_user_id = 42", expr.toString());
    }

    /**
     * 验证 AND 组合的 SQL 语法正确
     */
    @Test
    public void testSql_AND组合_JSqlParser能解析() throws Exception {
        insertSlotMapping(10L, "owner", "create_user_id");
        insertSlotMapping(10L, "status", "biz_status");

        ConditionNode node = ConditionNode.group(ConditionNode.LogicOperator.AND, List.of(
                ConditionNode.condition("owner", "EQ", ":currentUserId"),
                ConditionNode.condition("status", "IN", ":allowedStatuses")
        ));

        ConditionTreeEvaluator.SqlFragment fragment = evaluator.evaluate(node, 10L,
                Map.of("currentUserId", 1L, "allowedStatuses", List.of(1, 2, 3)));

        assertFalse(fragment.isEmpty());
        // 填入参数重建 SQL
        String sql = fillParams(fragment.sql(), fragment.params());
        Expression expr = CCJSqlParserUtil.parseCondExpression(sql);
        assertNotNull(expr, "JSqlParser 应能解析 AND 组合");
        assertTrue(expr.toString().contains("AND"), "结果应包含 AND");
        assertTrue(expr.toString().contains("IN"), "结果应包含 IN");
    }

    /**
     * 验证 OR 组合的 SQL 语法正确
     */
    @Test
    public void testSql_OR组合_JSqlParser能解析() throws Exception {
        insertSlotMapping(10L, "owner", "create_user_id");
        insertSlotMapping(10L, "dept", "dept_id");

        ConditionNode node = ConditionNode.group(ConditionNode.LogicOperator.OR, List.of(
                ConditionNode.condition("owner", "EQ", ":currentUserId"),
                ConditionNode.condition("dept", "EQ", ":currentDeptId")
        ));

        ConditionTreeEvaluator.SqlFragment fragment = evaluator.evaluate(node, 10L,
                Map.of("currentUserId", 5L, "currentDeptId", 10L));

        assertFalse(fragment.isEmpty());
        String sql = fillParams(fragment.sql(), fragment.params());
        Expression expr = CCJSqlParserUtil.parseCondExpression(sql);
        assertNotNull(expr);
        assertTrue(expr.toString().contains("OR"), "结果应包含 OR");
    }

    /**
     * 验证 IS NULL 的 SQL 语法正确
     */
    @Test
    public void testSql_IS_NULL_JSqlParser能解析() throws Exception {
        insertSlotMapping(10L, "owner", "create_user_id");

        ConditionNode node = ConditionNode.condition("owner", "IS_NULL", null);
        ConditionTreeEvaluator.SqlFragment fragment = evaluator.evaluate(node, 10L, Map.of());

        assertFalse(fragment.isEmpty());
        Expression expr = CCJSqlParserUtil.parseCondExpression(fragment.sql());
        assertNotNull(expr);
        assertEquals("create_user_id IS NULL", expr.toString());
    }

    /**
     * 验证嵌套三层条件树的 SQL 语法正确
     */
    @Test
    public void testSql_嵌套三层_JSqlParser能解析() throws Exception {
        insertSlotMapping(10L, "owner", "create_user_id");
        insertSlotMapping(10L, "dept", "dept_id");
        insertSlotMapping(10L, "status", "biz_status");

        // (owner = :currentUserId OR dept = :currentDeptId) AND status = :status
        ConditionNode inner = ConditionNode.group(ConditionNode.LogicOperator.OR, List.of(
                ConditionNode.condition("owner", "EQ", ":currentUserId"),
                ConditionNode.condition("dept", "EQ", ":currentDeptId")
        ));
        ConditionNode root = ConditionNode.group(ConditionNode.LogicOperator.AND, List.of(
                inner,
                ConditionNode.condition("status", "EQ", "1")
        ));

        ConditionTreeEvaluator.SqlFragment fragment = evaluator.evaluate(root, 10L,
                Map.of("currentUserId", 1L, "currentDeptId", 20L));

        assertFalse(fragment.isEmpty());
        String sql = fillParams(fragment.sql(), fragment.params());
        Expression expr = CCJSqlParserUtil.parseCondExpression(sql);
        assertNotNull(expr, "JSqlParser 应能解析三层嵌套");
        assertTrue(expr.toString().contains("AND"), "顶层应有 AND");
        assertTrue(expr.toString().contains("OR"), "内层应有 OR");
    }

    // ============================= 工具方法 =============================

    private void insertSlotMapping(Long resourceId, String slot, String columnName) {
        SlotMappingDO mapping = new SlotMappingDO();
        mapping.setResourceId(resourceId);
        mapping.setSlot(slot);
        mapping.setColumnName(columnName);
        slotMappingMapper.insert(mapping);
    }

    private String fillParams(String sql, List<Object> params) {
        String result = sql;
        for (Object param : params) {
            String literal = paramToLiteral(param);
            result = result.replaceFirst("\\?", literal);
        }
        return result;
    }

    private String paramToLiteral(Object param) {
        if (param instanceof Number) return param.toString();
        if (param instanceof java.util.Collection) {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (Object v : (java.util.Collection<?>) param) {
                if (!first) sb.append(", ");
                sb.append(paramToLiteral(v));
                first = false;
            }
            return sb.toString();
        }
        return "'" + param + "'";
    }

}
