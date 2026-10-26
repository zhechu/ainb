package cn.enlev.ainb.module.infra.api.formdesign.dto;

import lombok.Data;

import java.util.List;

/**
 * 表单设计保存 DTO（设计态）
 *
 * <h3>🔴 这是一份「全量 VO」，不是「要改什么」</h3>
 * {@code updateFormDesign} <b>不是增量更新</b>。
 * {@code FormDesignServiceImpl.clearableColumnsWrapper} 对 8 个列做<b>显式清空</b>：
 * 这一次没传值的，一律 set null。
 *
 * <p>这个设计对设计器是对的 —— 设计器永远提交全量，用户「清空某项配置」正是靠它。
 * 但调用方若按「我只改一个字段」的直觉提交局部 DTO，该表单的
 * <b>联动规则、校验规则、自定义按钮、页签、打印模板、前端脚本会被静默清空</b>。
 *
 * <p>⚠️ 比 {@code datamodel_update} 更危险：建表那边还有 {@code riskConfirmed}
 * 与有损变更校验，这边什么都没有，直接 set null。
 *
 * <p>所以调用方（尤其是 AI 工具）必须：<b>先取回全量 → 局部应用变更 → 整体回写</b>。
 * 见 {@link #CLEARABLE_FIELDS}。
 *
 * @author 走进新时代
 */
@Data
public class FormDesignSaveReqDTO {

    /**
     * 「没传就会被清空」的 8 个字段
     *
     * 公开成常量是为了让调用方能<b>程序化地断言</b>「原本有值的不能变成 null」，
     * 而不是靠开发者记住这份名单 —— 记不住的后果是用户辛苦配的联动规则
     * 在一次「改个标题」的操作后全没了，而且不报任何错。
     */
    public static final List<String> CLEARABLE_FIELDS = List.of(
            "listConf", "logicRules", "validateRules", "customButtons",
            "detailTabs", "printTemplate", "formScript", "remark");

    /**
     * 编号。改表单时必传，新建时为 null
     */
    private Long id;

    private String name;

    /**
     * 表单编码
     *
     * ⚠️ <b>已发布的表单不能改 code</b>（{@code FORM_DESIGN_CODE_CANNOT_UPDATE}）——
     * 运行时与菜单都靠 code 引用。
     */
    private String code;

    private String category;

    /**
     * 表单类型
     */
    private Integer formType;

    /**
     * 表单设计 JSON（form-create schema）
     */
    private String formConf;

    private List<String> formFields;

    /** ⚠️ 属于 {@link #CLEARABLE_FIELDS} */
    private String listConf;

    private Long dataSourceConfigId;

    private String mainTable;

    private String mainPk;

    private String tableBinding;

    /** ⚠️ 属于 {@link #CLEARABLE_FIELDS} */
    private String logicRules;
    /** ⚠️ 属于 {@link #CLEARABLE_FIELDS} */
    private String validateRules;
    /** ⚠️ 属于 {@link #CLEARABLE_FIELDS} */
    private String customButtons;
    /** ⚠️ 属于 {@link #CLEARABLE_FIELDS} */
    private String detailTabs;
    /** ⚠️ 属于 {@link #CLEARABLE_FIELDS} */
    private String printTemplate;

    private Integer extensionMode;

    /** ⚠️ 属于 {@link #CLEARABLE_FIELDS} */
    private String formScript;

    private Integer submitHookTimeoutAction;

    private Integer sort;

    /** ⚠️ 属于 {@link #CLEARABLE_FIELDS} */
    private String remark;

    /**
     * 乐观锁版本。<b>改表单时必传</b>，取自 {@code FormDesignApi#getFormDesign} 的返回值
     *
     * <p>不传是<b>硬拒绝</b>而不是跳过加锁：插件在 version 为 null 时静默跳过版本条件。
     *
     * <p>🔴 <b>不属于 {@link #CLEARABLE_FIELDS}，也不许被当成「可打补丁的业务字段」。</b>
     * {@code FormUpdateTool.PATCHABLE_FIELDS} 是拿本类的 {@code getDeclaredFields()} 反射出来的，
     * 所以那个名单必须<b>显式排除 version</b> —— 否则模型可以随意指定版本号，
     * 想覆盖谁就覆盖谁，乐观锁从「防并发」退化成「装饰」。
     */
    private Integer version;

}
