package cn.enlev.ainb.module.infra.controller.admin.formdesign.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "管理后台 - 表单设计 Response VO")
@Data
public class FormDesignRespVO {

    @Schema(description = "编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "表单名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "客户管理")
    private String name;

    @Schema(description = "表单编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "customer")
    private String code;

    @Schema(description = "分类，字典 infra_form_design_category", example = "business")
    private String category;

    @Schema(description = "表单类型：1-纯表单 2-表单+列表", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    private Integer formType;

    @Schema(description = "状态：0-草稿 1-已发布 2-已发布有改动", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer status;

    @Schema(description = "表单配置 JSON")
    private String formConf;

    @Schema(description = "表单字段规则")
    private List<String> formFields;

    @Schema(description = "列表配置 JSON")
    private String listConf;

    @Schema(description = "数据源编号", example = "0")
    private Long dataSourceConfigId;

    @Schema(description = "主表名", example = "biz_customer")
    private String mainTable;

    @Schema(description = "主表主键列名", example = "id")
    private String mainPk;

    @Schema(description = "表绑定 JSON")
    private String tableBinding;

    @Schema(description = "表单产物的 Schema 版本号，排查结构兼容问题时用", example = "1")
    private Integer schemaVersion;

    @Schema(description = "联动规则 JSON 数组")
    private String logicRules;

    @Schema(description = "跨字段校验规则 JSON 数组")
    private String validateRules;

    @Schema(description = "自定义按钮 JSON 数组")
    private String customButtons;

    @Schema(description = "详情页页签 JSON 数组")
    private String detailTabs;

    @Schema(description = "打印模板 HTML")
    private String printTemplate;

    @Schema(description = "扩展模式：0-无 1-前端脚本 2-服务端扩展点", example = "1")
    private Integer extensionMode;

    @Schema(description = "前端脚本 JSON")
    private String formScript;

    @Schema(description = "beforeSubmit 超时后的动作：1-阻断 2-放行", example = "1")
    private Integer submitHookTimeoutAction;

    @Schema(description = "该表单是否存在服务端扩展点实现",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED, example = "false")
    private Boolean hasServerExtension;

    @Schema(description = "服务端扩展点实现类名，模式错配提示里展示用")
    private java.util.List<String> serverExtensionNames;

    @Schema(description = "当前线上版本号", example = "3")
    private Integer publishedVersion;

    @Schema(description = "发布生成的菜单编号", example = "2538")
    private Long menuId;

    @Schema(description = "发布生成的 App 端导航节点编号", example = "356")
    private Long appNavNodeId;

    @Schema(description = "排序", example = "0")
    private Integer sort;

    @Schema(description = "备注", example = "客户主数据维护")
    private String remark;

    @Schema(description = "创建者", example = "admin")
    private String creator;

    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createTime;

    @Schema(description = "更新者", example = "admin")
    private String updater;

    @Schema(description = "更新时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime updateTime;

    // ========== AI 产物治理（B4-1）==========

    /**
     * 乐观锁版本，保存时<b>必须原样回传</b>
     */
    @Schema(description = "乐观锁版本，保存时原样回传", example = "3")
    private Integer version;

    @Schema(description = "来源：MANUAL=人工创建 AI=Agent 工具创建", example = "AI")
    private String source;

    @Schema(description = "是否已被人工接管（AI 建的表单被人在设计器里改过）", example = "false")
    private Boolean manualEditedFlag;

}
