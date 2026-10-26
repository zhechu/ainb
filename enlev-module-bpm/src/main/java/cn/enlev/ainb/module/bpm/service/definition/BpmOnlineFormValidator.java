package cn.enlev.ainb.module.bpm.service.definition;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.bpm.controller.admin.definition.vo.model.simple.BpmSimpleModelNodeVO;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.bpm.enums.ErrorCodeConstants.*;

/**
 * 在线表单校验器
 *
 * 对应方案中的「闸口 ②（模型保存）」与「闸口 ③（流程部署）」：前端的置灰不可选是闸口 ①，
 * 但它挡不住直接构造请求，也挡不住「保存后、部署前表结构被 DBA 改动」的时间窗口，
 * 因此服务端在保存与部署两处都要再校验一次。
 *
 * 校验口径完全委托 {@link FormDesignApi#validateFlowReady}，避免两个模块各写一套判断而产生分歧。
 *
 * @author 走进新时代
 */
@Component
public class BpmOnlineFormValidator {

    @Resource
    private FormDesignApi formDesignApi;

    /**
     * 校验主表单可用于流程
     *
     * 主表单承载业务数据主体，要求其主表同时具备 process_instance_id 与 process_status 两列。
     *
     * @param formDesignCode 在线表单编码
     */
    public void validateMainForm(String formDesignCode) {
        if (StrUtil.isEmpty(formDesignCode)) {
            throw exception(ONLINE_FORM_NOT_CONFIG);
        }
        String reason = formDesignApi.validateFlowReady(formDesignCode, true);
        if (reason != null) {
            throw exception(ONLINE_FORM_NOT_READY, formDesignCode, reason);
        }
    }

    /**
     * 校验流程中所有节点配置的独立表单可用于流程
     *
     * 节点表单只是补充填写，不承载流程状态，因此只要求 process_instance_id 列。
     *
     * @param rootNode SIMPLE 设计器的根节点。为空时直接跳过（BPMN 模式下没有节点表单）
     */
    public void validateNodeForms(BpmSimpleModelNodeVO rootNode) {
        if (rootNode == null) {
            return;
        }
        List<BpmSimpleModelNodeVO> nodes = new ArrayList<>();
        collectNodes(rootNode, nodes);
        for (BpmSimpleModelNodeVO node : nodes) {
            String formCode = node.getFormCode();
            if (StrUtil.isEmpty(formCode)) {
                continue;
            }
            String reason = formDesignApi.validateFlowReady(formCode, false);
            if (reason != null) {
                throw exception(ONLINE_FORM_NODE_FORM_NOT_READY, node.getName(), formCode, reason);
            }
        }
    }

    /**
     * 递归收集所有节点
     *
     * SIMPLE 模型是树：childNode 有且仅有一个，conditionNodes 用于条件、并行、包容分支。
     */
    private void collectNodes(BpmSimpleModelNodeVO node, List<BpmSimpleModelNodeVO> result) {
        if (node == null) {
            return;
        }
        result.add(node);
        collectNodes(node.getChildNode(), result);
        if (node.getConditionNodes() != null) {
            for (BpmSimpleModelNodeVO conditionNode : node.getConditionNodes()) {
                collectNodes(conditionNode, result);
            }
        }
    }

}
