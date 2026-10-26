package cn.enlev.ainb.module.agent.framework.agent.core.validate;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code data_interface_*} 两个 plan 期校验器的单元测试
 *
 * <h3>为什么是两个而不是一个</h3>
 * 与 {@code FormDataParamValidatorTest} 同款理由：{@link AgentToolParamValidator#validate(Map)}
 * 拿不到 toolName，单个校验器无法安全区分 query/invoke。这里钉住两者的 {@code supports} 边界
 * （互不认领、只读的 list 谁都不认）与结构性必填规则（code 非空、params 形态合法）。
 *
 * <h3>刻意不测的</h3>
 * 入参级深校验（接口定义了哪些参数、必填、类型）—— 那是 infra ParamValidator 执行期的职责，
 * plan 期重复校验只会与接口定义漂移（见校验器 javadoc）。
 *
 * @author 走进新时代
 */
public class DataInterfaceParamValidatorTest extends BaseMockitoUnitTest {

    private final DataInterfaceQueryParamValidator queryValidator = new DataInterfaceQueryParamValidator();
    private final DataInterfaceInvokeParamValidator invokeValidator = new DataInterfaceInvokeParamValidator();

    // ==================== supports 边界 ====================

    @Test
    @DisplayName("supports：各校验器只认自己那一个工具；list 是只读不进 Plan，谁都不认")
    public void testSupports() {
        assertTrue(queryValidator.supports("data_interface_query"));
        assertFalse(queryValidator.supports("data_interface_invoke"));
        assertFalse(queryValidator.supports("data_interface_list"));

        assertTrue(invokeValidator.supports("data_interface_invoke"));
        assertFalse(invokeValidator.supports("data_interface_query"));
        assertFalse(invokeValidator.supports("data_interface_list"));
    }

    // ==================== 通过路径 ====================

    @Test
    @DisplayName("code + 合法 params（对象或 JSON 串）→ 通过；无参接口不给 params 也通过")
    public void testValid() {
        for (AgentToolParamValidator v : new AgentToolParamValidator[]{queryValidator, invokeValidator}) {
            assertTrue(v.validate(Map.of("code", "q_read_sys")).isPassed());
            assertTrue(v.validate(Map.of("code", "q_read_sys", "params", Map.of("status", 1))).isPassed());
            assertTrue(v.validate(Map.of("code", "q_read_sys", "params", "{\"status\":1}")).isPassed());
        }
    }

    // ==================== 拦截路径 ====================

    @Test
    @DisplayName("缺 code → 不通过，反馈教模型先 list 查编码")
    public void testMissingCode() {
        for (AgentToolParamValidator v : new AgentToolParamValidator[]{queryValidator, invokeValidator}) {
            AgentValidateResult result = v.validate(new LinkedHashMap<>());

            assertFalse(result.isPassed());
            assertTrue(result.toFeedback().contains("code"), result.toFeedback());
            assertTrue(result.toFeedback().contains("data_interface_list"), result.toFeedback());
        }
    }

    @Test
    @DisplayName("params 非法形态（不是对象也解析不出对象）→ 不通过，plan 期就拦下")
    public void testInvalidParamsShape() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("code", "q_read_sys");
        params.put("params", "oops-not-json");

        for (AgentToolParamValidator v : new AgentToolParamValidator[]{queryValidator, invokeValidator}) {
            AgentValidateResult result = v.validate(params);

            assertFalse(result.isPassed());
            assertTrue(result.toFeedback().contains("params"), result.toFeedback());
        }
    }

}
