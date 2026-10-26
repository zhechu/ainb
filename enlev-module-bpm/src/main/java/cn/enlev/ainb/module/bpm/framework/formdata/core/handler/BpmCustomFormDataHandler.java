package cn.enlev.ainb.module.bpm.framework.formdata.core.handler;

import cn.enlev.ainb.module.bpm.enums.definition.BpmModelFormTypeEnum;
import cn.enlev.ainb.module.bpm.framework.formdata.core.BpmFormDataHandler;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 业务表单（CUSTOM）的数据处理器
 *
 * 业务表单的数据完全由业务方自己的 Service 掌管（参考 BpmOALeaveServiceImpl 的实现范式：
 * 业务方先写自己的表，再以业务主键为 businessKey 发起流程，并通过
 * BpmProcessInstanceStatusEventListener 监听流程状态变化回写自己的表）。
 *
 * 所以这里的写操作全部是空实现 —— 这是正确的语义边界，不是没实现完：
 * BPM 不该越过业务方去写它的表，那会绕开业务方的校验逻辑。
 *
 * @author 走进新时代
 */
@Component
public class BpmCustomFormDataHandler implements BpmFormDataHandler {

    @Override
    public Integer getFormType() {
        return BpmModelFormTypeEnum.CUSTOM.getType();
    }

    @Override
    public Object create(String formKey, Map<String, Object> data) {
        return null;
    }

    @Override
    public void update(String formKey, Object pk, Map<String, Object> data) {
        // 空实现：见类注释
    }

    @Override
    public Map<String, Object> get(String formKey, Object pk) {
        // 业务表单的展示走前端自定义路由（formCustomViewPath），由业务方页面自行取数
        return null;
    }

    @Override
    public void delete(String formKey, Object pk) {
        // 空实现：见类注释
    }

    @Override
    public void updateStatus(String formKey, Object pk, Integer status) {
        // 空实现：业务方通过 BpmProcessInstanceStatusEventListener 自行同步
    }

}
