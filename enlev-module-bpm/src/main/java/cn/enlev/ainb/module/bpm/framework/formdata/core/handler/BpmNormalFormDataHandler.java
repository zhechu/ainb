package cn.enlev.ainb.module.bpm.framework.formdata.core.handler;

import cn.enlev.ainb.module.bpm.enums.definition.BpmModelFormTypeEnum;
import cn.enlev.ainb.module.bpm.framework.formdata.core.BpmFormDataHandler;
import jakarta.annotation.Resource;
import org.flowable.engine.RuntimeService;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 流程表单（NORMAL）的数据处理器
 *
 * 数据即 Flowable 流程变量，因此 pk 就是流程实例编号，没有独立的业务主键。
 *
 * @author 走进新时代
 */
@Component
public class BpmNormalFormDataHandler implements BpmFormDataHandler {

    @Resource
    private RuntimeService runtimeService;

    @Override
    public Integer getFormType() {
        return BpmModelFormTypeEnum.NORMAL.getType();
    }

    @Override
    public Object create(String formKey, Map<String, Object> data) {
        // 流程表单的数据是随流程实例一起创建的（作为启动变量传入），
        // 不存在「先建数据再启流程」的步骤，因此这里没有可做的事。
        // 流程实例编号由调用方在启动后作为 pk 使用
        return null;
    }

    @Override
    public void update(String formKey, Object pk, Map<String, Object> data) {
        if (pk == null || data == null || data.isEmpty()) {
            return;
        }
        runtimeService.setVariables(String.valueOf(pk), data);
    }

    @Override
    public Map<String, Object> get(String formKey, Object pk) {
        if (pk == null) {
            return null;
        }
        return runtimeService.getVariables(String.valueOf(pk));
    }

    @Override
    public void delete(String formKey, Object pk) {
        // 流程变量随流程实例一起删除，无需单独处理
    }

    @Override
    public void updateStatus(String formKey, Object pk, Integer status) {
        // 流程状态由 Flowable 自身维护，流程变量中无需冗余
    }

}
