package cn.enlev.ainb.module.bpm.api.definition.dto;

import lombok.Data;

/**
 * 流程模型的简要信息 DTO
 *
 * @author 走进新时代
 */
@Data
public class BpmModelRespDTO {

    /**
     * 流程模型编号
     */
    private String id;

    /**
     * 流程标识
     */
    private String key;

    /**
     * 流程名称
     */
    private String name;

    /**
     * 流程分类
     */
    private String category;

    /**
     * 模型类型：10 BPMN 设计器、20 仿钉钉设计器
     *
     * ⚠️ 只有 20 的模型才有 simpleModel 可读。
     */
    private Integer type;

    /**
     * 表单类型：10 普通表单、20 自定义表单、30 在线表单
     */
    private Integer formType;

    /**
     * 在线表单的表单编码
     */
    private String formDesignCode;

    /**
     * 是否已部署过
     *
     * 🔴 未部署的模型<b>无法发起流程</b>，也不出现在用户的发起列表里。
     * 「建好了但没部署」是这个子系统里典型的「配了没生效」形态之一。
     */
    private Boolean deployed;

}
