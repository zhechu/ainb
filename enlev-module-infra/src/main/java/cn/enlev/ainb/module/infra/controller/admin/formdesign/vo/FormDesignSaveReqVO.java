package cn.enlev.ainb.module.infra.controller.admin.formdesign.vo;

import cn.enlev.ainb.framework.common.validation.InEnum;
import cn.enlev.ainb.module.infra.enums.formdesign.FormExtensionModeEnum;
import cn.enlev.ainb.module.infra.enums.formdesign.SubmitHookTimeoutActionEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 表单设计创建/修改 Request VO")
@Data
public class FormDesignSaveReqVO {

    @Schema(description = "编号。修改时传入", example = "1")
    private Long id;

    @Schema(description = "表单名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "客户管理")
    @NotEmpty(message = "表单名称不能为空")
    private String name;

    /**
     * 编码作为运行时与菜单的引用键，因此限制字符集且发布后不允许修改
     */
    @Schema(description = "表单编码，运行时与菜单引用它", requiredMode = Schema.RequiredMode.REQUIRED, example = "customer")
    @NotEmpty(message = "表单编码不能为空")
    @Pattern(regexp = "^[A-Za-z_][A-Za-z0-9_]*$",
            message = "表单编码只允许字母、数字、下划线，且以字母或下划线开头")
    private String code;

    @Schema(description = "分类，字典 infra_form_design_category", example = "business")
    private String category;

    @Schema(description = "表单类型：1-纯表单 2-表单+列表", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    @NotNull(message = "表单类型不能为空")
    private Integer formType;

    // ========== 表单设计产物（form-create） ==========

    @Schema(description = "表单配置 JSON，form-create 的 getOption()")
    private String formConf;

    @Schema(description = "表单字段规则，form-create 的 getRule() 逐条序列化")
    private List<String> formFields;

    // ========== 列表设计产物 ==========

    @Schema(description = "列表配置 JSON：列/查询/按钮/树形/汇总/脚本。仅表单+列表类型有意义")
    private String listConf;

    // ========== 数据绑定（只绑定已有表，不建表不改表） ==========

    @Schema(description = "数据源编号，0 为主库", example = "0")
    private Long dataSourceConfigId;

    @Schema(description = "主表名", example = "biz_customer")
    private String mainTable;

    @Schema(description = "主表主键列名", example = "id")
    private String mainPk;

    @Schema(description = "表绑定 JSON：字段映射/从表关联/子表配置")
    private String tableBinding;

    @Schema(description = "联动规则 JSON 数组")
    private String logicRules;

    @Schema(description = "跨字段校验规则 JSON 数组")
    private String validateRules;

    @Schema(description = "自定义按钮 JSON 数组：[{key,label,icon,position,action,visibleWhen,permission}]")
    private String customButtons;

    @Schema(description = "详情页页签 JSON 数组：[{key,label,type}]")
    private String detailTabs;

    @Schema(description = "打印模板 HTML，占位符为 {字段}")
    private String printTemplate;

    @Schema(description = "扩展模式：0-无 1-前端脚本 2-服务端扩展点", example = "1")
    @InEnum(FormExtensionModeEnum.class)
    private Integer extensionMode;

    @Schema(description = "前端脚本 JSON，仅扩展模式为前端脚本时执行")
    private String formScript;

    @Schema(description = "beforeSubmit 超时后的动作：1-阻断 2-放行", example = "1")
    @InEnum(SubmitHookTimeoutActionEnum.class)
    private Integer submitHookTimeoutAction;

    @Schema(description = "排序", example = "0")
    private Integer sort;

    @Schema(description = "备注", example = "客户主数据维护")
    private String remark;

    /**
     * 乐观锁版本。<b>修改时必传</b>，取自 {@code GET /infra/form-design/get} 的返回值
     *
     * <p>不传不是「跳过加锁」，是<b>硬拒绝</b>：MyBatis-Plus 的乐观锁插件在 version 为 null 时
     * 静默跳过版本条件，锁就等于没开，所以由 Service 显式判空并报
     * {@code FORM_DESIGN_RECORD_VERSION_REQUIRED}。
     *
     * <p>🔴 它跟本 VO 里已有的两个「version」都不是一回事：
     * {@code schemaVersion}（DO 上）是这份 JSON 产物的结构版本，
     * {@code publishedVersion} 是线上发布了几次。别互相赋值。
     */
    @Schema(description = "乐观锁版本，修改时必传", example = "3")
    private Integer version;

}
