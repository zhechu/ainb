package cn.enlev.ainb.module.bpm.api.definition.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 流程模型的创建请求 DTO
 *
 * <h3>为什么 simpleModel 是 JSON 字符串而不是 VO</h3>
 * {@code BpmSimpleModelNodeVO} 住在 {@code controller/admin/definition/vo/} 下 ——
 * 让别的模块引用它，等于把 controller 层的结构变成跨模块契约的一部分，
 * 上游改一次字段名就是一次跨模块编译失败。
 *
 * <p>用 JSON 字符串传递，反序列化在本模块内部完成：
 * 调用方只需要知道「这是一棵仿钉钉设计器的节点树」，
 * 不需要、也不应该 import 它的类型。
 *
 * @author 走进新时代
 */
@Data
public class BpmModelCreateReqDTO {

    /**
     * 流程标识
     *
     * ⚠️ 必须是合法的 XML NCName 且全局唯一，否则 {@code createModel} 直接拒绝。
     * 通俗说：字母开头，只含字母数字下划线连字符点，不能有中文、空格、冒号。
     */
    @NotEmpty(message = "流程标识不能为空")
    private String key;

    /**
     * 流程名称
     */
    @NotEmpty(message = "流程名称不能为空")
    private String name;

    /**
     * 流程描述
     */
    private String description;

    /**
     * 流程分类，对应 {@code bpm_category} 的编码
     */
    private String category;

    /**
     * 表单类型：10 普通表单、20 自定义表单、30 在线表单
     */
    @NotNull(message = "表单类型不能为空")
    private Integer formType;

    /**
     * 普通表单（formType=10）时的表单编号
     */
    private Long formId;

    /**
     * 在线表单（formType=30）时的表单编码，对应 {@code infra_form_design.code}
     */
    private String formDesignCode;

    /**
     * 可管理该流程的用户编号
     *
     * 🔴 <b>创建者必须在这个名单里，否则建完就废</b>：模型从未部署过时，
     * 流程资源行还不存在，管理员判定只能看这份名单 ——
     * 不在名单里的话，创建者既部署不了也删不掉自己刚建的模型。
     */
    @NotEmpty(message = "可管理用户不能为空")
    private List<Long> managerUserIds;

    /**
     * 可发起该流程的用户编号，为空表示不限
     */
    private List<Long> startUserIds;

    /**
     * 仿钉钉设计器的节点树（JSON）
     *
     * 根节点固定是「发起人」节点（type=10），末端必须有「结束」节点（type=1）。
     */
    @NotEmpty(message = "流程节点树不能为空")
    private String simpleModel;

}
