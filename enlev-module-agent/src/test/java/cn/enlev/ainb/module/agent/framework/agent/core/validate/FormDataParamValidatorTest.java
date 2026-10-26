package cn.enlev.ainb.module.agent.framework.agent.core.validate;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code form_data_*} 三个 plan 期校验器的单元测试
 *
 * <h3>为什么是三个而不是一个</h3>
 * {@link AgentToolParamValidator#validate(Map)} 拿不到 toolName，单个校验器无法安全区分
 * create/update/delete 各自的必填项。这里逐一钉住三者的 {@code supports} 边界与必填规则，
 * 尤其要验证「delete <b>不</b>要求 data」「create <b>不</b>要求 pk」—— 合并成一个校验器时
 * 最容易犯的错就是把某个工具的必填项强加到另一个上。
 *
 * @author 走进新时代
 */
public class FormDataParamValidatorTest extends BaseMockitoUnitTest {

    private final FormDataCreateParamValidator createValidator = new FormDataCreateParamValidator();
    private final FormDataUpdateParamValidator updateValidator = new FormDataUpdateParamValidator();
    private final FormDataDeleteParamValidator deleteValidator = new FormDataDeleteParamValidator();

    // ==================== supports 边界 ====================

    @Test
    @DisplayName("supports：各校验器只认自己那一个写工具，只读工具一律不认")
    public void testSupports() {
        assertTrue(createValidator.supports("form_data_create"));
        assertFalse(createValidator.supports("form_data_update"));
        assertFalse(createValidator.supports("form_data_delete"));

        assertTrue(updateValidator.supports("form_data_update"));
        assertFalse(updateValidator.supports("form_data_create"));

        assertTrue(deleteValidator.supports("form_data_delete"));
        assertFalse(deleteValidator.supports("form_data_update"));

        // 🔴 只读工具不进 Plan，plan 期校验对它们无意义 → 三个校验器都不该认
        for (AgentToolParamValidator v : new AgentToolParamValidator[]{
                createValidator, updateValidator, deleteValidator}) {
            assertFalse(v.supports("form_data_get"));
            assertFalse(v.supports("form_data_page"));
        }
    }

    // ==================== create ====================

    @Test
    @DisplayName("create：code + data 齐全 → 通过")
    public void testCreateValid() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("code", "customer");
        params.put("data", Map.of("name", "张三"));

        assertTrue(createValidator.validate(params).isPassed());
    }

    @Test
    @DisplayName("create：缺 code 与 data → 一次列出两条，各自说清怎么改")
    public void testCreateMissingBoth() {
        AgentValidateResult result = createValidator.validate(new LinkedHashMap<>());

        assertFalse(result.isPassed());
        assertEquals(2, result.getErrors().size());
        String feedback = result.toFeedback();
        assertTrue(feedback.contains("code"), feedback);
        assertTrue(feedback.contains("data"), feedback);
    }

    @Test
    @DisplayName("create：data 为空对象 → 不通过（新增至少要一个字段）")
    public void testCreateEmptyData() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("code", "customer");
        params.put("data", new LinkedHashMap<>());

        assertFalse(createValidator.validate(params).isPassed());
    }

    // ==================== update ====================

    @Test
    @DisplayName("update：code + pk + data 齐全 → 通过")
    public void testUpdateValid() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("code", "customer");
        params.put("pk", "1");
        params.put("data", Map.of("name", "李四"));

        assertTrue(updateValidator.validate(params).isPassed());
    }

    @Test
    @DisplayName("update：pk 用别名 id 也算数")
    public void testUpdateIdAliasAsPk() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("code", "customer");
        params.put("id", "1");
        params.put("data", Map.of("name", "李四"));

        assertTrue(updateValidator.validate(params).isPassed());
    }

    @Test
    @DisplayName("update：缺 pk → 不通过，并提示去 form_data_page 查 id")
    public void testUpdateMissingPk() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("code", "customer");
        params.put("data", Map.of("name", "李四"));

        AgentValidateResult result = updateValidator.validate(params);

        assertFalse(result.isPassed());
        assertTrue(result.toFeedback().contains("pk"), result.toFeedback());
    }

    // ==================== delete ====================

    @Test
    @DisplayName("delete：code + pk → 通过（🔴 不要求 data）")
    public void testDeleteValidWithoutData() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("code", "customer");
        params.put("pk", "1");

        assertTrue(deleteValidator.validate(params).isPassed());
    }

    @Test
    @DisplayName("delete：缺 pk → 不通过")
    public void testDeleteMissingPk() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("code", "customer");

        AgentValidateResult result = deleteValidator.validate(params);

        assertFalse(result.isPassed());
        assertTrue(result.toFeedback().contains("pk"), result.toFeedback());
    }

}
