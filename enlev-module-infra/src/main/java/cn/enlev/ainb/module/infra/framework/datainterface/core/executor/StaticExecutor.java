package cn.enlev.ainb.module.infra.framework.datainterface.core.executor;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.module.infra.enums.datainterface.DataInterfaceTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.DATA_INTERFACE_STATIC_JSON_INVALID;

/**
 * 静态 JSON 类型的执行器
 *
 * 直接返回配置好的 JSON。适合做枚举字典、前端联调占位数据。
 * 不涉及数据库，也就没有租户隔离问题 —— 配置本身就是按租户存的。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class StaticExecutor implements InterfaceExecutor {

    @Override
    public DataInterfaceTypeEnum getType() {
        return DataInterfaceTypeEnum.STATIC;
    }

    @Override
    public Object execute(InterfaceExecuteContext context) {
        String config = context.getDataInterface().getConfig();
        if (StrUtil.isBlank(config)) {
            return null;
        }
        // 解析成对象再返回，而不是原样返回字符串 ——
        // 这样前端拿到的是结构化数据，与 SQL 类型的返回形态一致
        try {
            Object parsed = JsonUtils.parseObject(config, Object.class);
            if (parsed == null) {
                throw exception(DATA_INTERFACE_STATIC_JSON_INVALID);
            }
            return parsed;
        } catch (Exception ex) {
            log.warn("[execute][静态 JSON 解析失败：{}]", config, ex);
            throw exception(DATA_INTERFACE_STATIC_JSON_INVALID);
        }
    }

}
