package cn.enlev.ainb.module.infra.framework.datainterface.core.param;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.infra.framework.datamodel.core.ValueConverter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.*;

/**
 * 入参校验与类型转换
 *
 * 校验必填、按定义的类型转换值。类型不匹配直接报错，**不静默转换** ——
 * 静默转换会让「传了个字符串到数字字段」这类错误潜伏到 SQL 执行时才暴露，
 * 那时的报错信息对用户毫无帮助。
 *
 * 类型转换复用数据模型的 {@link ValueConverter}，两处保持一致的转换语义。
 *
 * @author 走进新时代
 */
public class ParamValidator {

    private ParamValidator() {
    }

    /**
     * 校验并转换参数
     *
     * @param definitions 入参定义
     * @param requestArgs 请求传来的值
     * @return 转换后的值，key 为参数名
     */
    public static Map<String, Object> validateAndConvert(List<ParamDefinition> definitions,
                                                         Map<String, Object> requestArgs) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (CollUtil.isEmpty(definitions)) {
            return result;
        }
        Map<String, Object> args = requestArgs == null ? Collections.emptyMap() : requestArgs;

        for (ParamDefinition definition : definitions) {
            String name = definition.getName();
            if (StrUtil.isBlank(name)) {
                continue;
            }
            Object raw = args.get(name);
            // 未传值时用默认值兜底
            if (isBlankValue(raw) && StrUtil.isNotBlank(definition.getDefaultValue())) {
                raw = definition.getDefaultValue();
            }
            // 必填校验：默认值兜底之后仍为空才算缺失
            if (isBlankValue(raw)) {
                if (definition.isRequired()) {
                    throw exception(DATA_INTERFACE_PARAM_REQUIRED,
                            StrUtil.blankToDefault(definition.getLabel(), name));
                }
                result.put(name, null);
                continue;
            }
            // 类型转换，失败会抛出明确的错误
            try {
                result.put(name, ValueConverter.convert(raw, definition.getType()));
            } catch (Exception ex) {
                throw exception(DATA_INTERFACE_PARAM_TYPE_INVALID,
                        StrUtil.blankToDefault(definition.getLabel(), name),
                        definition.getType());
            }
        }
        return result;
    }

    private static boolean isBlankValue(Object value) {
        return value == null || (value instanceof String str && StrUtil.isBlank(str));
    }

}
