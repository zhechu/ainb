package cn.enlev.ainb.module.bpm.framework.formdata.core;

import cn.enlev.ainb.module.bpm.enums.definition.BpmModelFormTypeEnum;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.bpm.enums.ErrorCodeConstants.MODEL_DEPLOY_FAIL_FORM_NOT_CONFIG;

/**
 * 流程表单数据处理器工厂
 *
 * 按表单类型分派到对应的 {@link BpmFormDataHandler}。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class BpmFormDataHandlerFactory {

    @Resource
    private List<BpmFormDataHandler> handlers;

    private final Map<Integer, BpmFormDataHandler> handlerMap = new HashMap<>();

    @PostConstruct
    public void init() {
        for (BpmFormDataHandler handler : handlers) {
            BpmFormDataHandler exists = handlerMap.put(handler.getFormType(), handler);
            if (exists != null) {
                // 同一类型注册了两个处理器，说明有实现类重复声明了 formType。
                // 这会让分派结果取决于 Bean 加载顺序，属于必须在启动期暴露的配置错误
                throw new IllegalStateException(String.format(
                        "表单类型(%d) 存在多个处理器：%s 与 %s",
                        handler.getFormType(), exists.getClass().getName(), handler.getClass().getName()));
            }
        }
        log.info("[init][初始化流程表单数据处理器({}) 个]", handlerMap.size());
    }

    /**
     * 获得处理器
     *
     * @param formType 表单类型
     * @return 处理器
     */
    public BpmFormDataHandler getHandler(Integer formType) {
        if (formType == null) {
            throw exception(MODEL_DEPLOY_FAIL_FORM_NOT_CONFIG);
        }
        BpmFormDataHandler handler = handlerMap.get(formType);
        if (handler == null) {
            throw new IllegalArgumentException(String.format("表单类型(%d) 没有对应的处理器", formType));
        }
        return handler;
    }

    /**
     * 获得处理器
     *
     * @param formType 表单类型枚举
     * @return 处理器
     */
    public BpmFormDataHandler getHandler(BpmModelFormTypeEnum formType) {
        return getHandler(formType.getType());
    }

}
