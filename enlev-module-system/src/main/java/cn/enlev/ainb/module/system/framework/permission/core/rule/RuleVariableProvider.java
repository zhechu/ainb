package cn.enlev.ainb.module.system.framework.permission.core.rule;

import java.util.Map;

/**
 * 行过滤规则变量 SPI
 *
 * 实现此接口可以向条件树求值上下文注入变量，变量在规则模板里以 :variableName 形式引用。
 * 框架在条件树求值时，收集所有实现了此接口的 Bean，构建变量映射后再求值。
 *
 * ## 内置变量由 BuiltInVariableProvider 实现（在同一包里）
 *
 * ## 扩展方式（变量 SPI）
 *
 * 用户可以实现此接口来注入业务自定义变量，例如：
 * <pre>
 * {@code
 * @Component
 * public class BizVariableProvider implements RuleVariableProvider {
 *     public Map<String, Object> provide(Long userId, Map<String, Object> requestContext) {
 *         return Map.of("myBizVar", computeSomething(userId));
 *     }
 * }
 * }
 * </pre>
 *
 * @author 走进新时代
 */
public interface RuleVariableProvider {

    /**
     * 提供变量映射
     *
     * @param userId         当前用户 id，可能为 null（匿名请求）
     * @param requestContext 请求上下文，包含 processInstanceId / taskId 等请求级变量
     * @return 变量名 → 值，框架会把多个 Provider 的结果合并，后来者覆盖前者
     */
    Map<String, Object> provide(Long userId, Map<String, Object> requestContext);

}
