package cn.enlev.ainb.module.bpm.controller.admin.definition.vo.model;

import cn.enlev.ainb.framework.common.core.KeyValue;
import cn.enlev.ainb.framework.common.validation.InEnum;
import cn.enlev.ainb.module.bpm.controller.admin.definition.vo.model.simple.BpmSimpleModelNodeVO;
import cn.enlev.ainb.module.bpm.enums.definition.BpmAutoApproveTypeEnum;
import cn.enlev.ainb.module.bpm.enums.definition.BpmModelFormTypeEnum;
import cn.enlev.ainb.module.bpm.enums.definition.BpmModelTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.hibernate.validator.constraints.URL;

import java.util.List;

/**
 * BPM 流程 MetaInfo Response DTO
 * 主要用于 { Model#setMetaInfo(String)} 的存储
 *
 * 最终，它的字段和
 * {@link cn.enlev.ainb.module.bpm.dal.dataobject.definition.BpmProcessDefinitionInfoDO}
 * 是一致的
 *
 * @author 芋道源码
 */
@Data
public class BpmModelMetaInfoVO {

    @Schema(description = "流程图标", example = "http://127.0.0.1:48080/enlev.jpg")
    @URL(message = "流程图标格式不正确")
    private String icon;

    @Schema(description = "流程描述", example = "我是描述")
    private String description;

    @Schema(description = "流程类型", requiredMode = Schema.RequiredMode.REQUIRED, example = "10")
    @InEnum(BpmModelTypeEnum.class)
    @NotNull(message = "流程类型不能为空")
    private Integer type;

    @Schema(description = "表单类型", requiredMode = Schema.RequiredMode.REQUIRED, example = "10")
    @InEnum(BpmModelFormTypeEnum.class)
    @NotNull(message = "表单类型不能为空")
    private Integer formType;
    @Schema(description = "表单编号", example = "1024")
    private Long formId; // formType 为 NORMAL 使用，必须非空

    @Schema(description = "在线表单编码", example = "leave_apply")
    private String formDesignCode; // formType 为 ONLINE 使用，必须非空。关联 infra_form_design 的 code
                                   // 只存 code 不存版本：流程始终使用该表单的最新版本

    @Schema(description = "自定义表单的提交路径，使用 Vue 的路由地址", example = "/bpm/oa/leave/create")
    private String formCustomCreatePath; // 表单类型为 CUSTOM 时，必须非空
    @Schema(description = "自定义表单的查看路径，使用 Vue 的路由地址", example = "/bpm/oa/leave/view")
    private String formCustomViewPath; // 表单类型为 CUSTOM 时，必须非空

    @Schema(description = "是否可见", requiredMode = Schema.RequiredMode.REQUIRED, example = "true")
    @NotNull(message = "是否可见不能为空")
    private Boolean visible;

    @Schema(description = "可发起用户编号数组", example = "[1,2,3]")
    private List<Long> startUserIds;

    @Schema(description = "可发起部门编号数组", example = "[2,4,6]")
    private List<Long> startDeptIds;

    @Schema(description = "可管理用户编号数组", requiredMode = Schema.RequiredMode.REQUIRED, example = "[2,4,6]")
    @NotEmpty(message = "可管理用户编号数组不能为空")
    private List<Long> managerUserIds;

    @Schema(description = "排序", example = "1")
    private Long sort; // 创建时，后端自动生成

    @Schema(description = "允许撤销审批中的申请", example = "true")
    private Boolean allowCancelRunningProcess;

    @Schema(description = "允许允许审批人撤回任务", example = "false")
    private Boolean allowWithdrawTask;

    @Schema(description = "流程 ID 规则", example = "{}")
    private ProcessIdRule processIdRule;

    @Schema(description = "自动去重类型", example = "1")
    @InEnum(BpmAutoApproveTypeEnum.class)
    private Integer autoApprovalType;

    @Schema(description = "标题设置", example = "{}")
    private TitleSetting titleSetting;

    @Schema(description = "摘要设置", example = "{}")
    private SummarySetting summarySetting;

    @Schema(description = "流程前置通知设置", example = "{}")
    private HttpRequestSetting processBeforeTriggerSetting;

    @Schema(description = "流程后置通知设置", example = "{}")
    private HttpRequestSetting processAfterTriggerSetting;

    @Schema(description = "任务前置通知设置", example = "{}")
    private HttpRequestSetting taskBeforeTriggerSetting;

    @Schema(description = "任务后置通知设置", example = "{}")
    private HttpRequestSetting taskAfterTriggerSetting;

    @Schema(description = "自定义打印模板设置", example = "{}")
    @Valid
    private PrintTemplateSetting printTemplateSetting;

    /**
     * 父子流程可见性设置（v9 §4.3）
     *
     * ⚠️ 三条默认值就是 S5a 参与表已经实现的行为，这里把它们变成【流程级可配置】。
     * 不配等于沿用默认，与本设置引入之前完全一致
     */
    private ChildProcessVisibilitySetting childProcessVisibilitySetting;

    @Schema(description = "流程 ID 规则")
    @Data
    @Valid
    public static class ProcessIdRule {

        @Schema(description = "是否启用", example = "false")
        @NotNull(message = "是否启用不能为空")
        private Boolean enable;

        @Schema(description = "前缀", example = "XX")
        private String prefix;

        @Schema(description = "中缀", example = "20250120")
        private String infix; // 精确到日、精确到时、精确到分、精确到秒

        @Schema(description = "后缀", example = "YY")
        private String postfix;

        @Schema(description = "序列长度", example = "5")
        @NotNull(message = "序列长度不能为空")
        private Integer length;

    }

    @Schema(description = "标题设置")
    @Data
    @Valid
    public static class TitleSetting {

        @Schema(description = "是否自定义", example = "false")
        @NotNull(message = "是否自定义不能为空")
        private Boolean enable;

        @Schema(description = "标题", example = "流程标题")
        private String title;

    }

    @Schema(description = "摘要设置")
    @Data
    @Valid
    public static class SummarySetting {

        @Schema(description = "是否自定义", example = "false")
        @NotNull(message = "是否自定义不能为空")
        private Boolean enable;

        @Schema(description = "摘要字段数组", example = "[]")
        private List<String> summary;

    }

    @Schema(description = "http 请求通知设置", example = "{}")
    @Data
    public static class HttpRequestSetting {

        @Schema(description = "请求路径", example = "http://127.0.0.1")
        @NotEmpty(message = "请求 URL 不能为空")
        @URL(message = "请求 URL 格式不正确")
        private String url;

        @Schema(description = "请求头参数设置", example = "[]")
        @Valid
        private List<BpmSimpleModelNodeVO.HttpRequestParam> header;

        @Schema(description = "请求头参数设置", example = "[]")
        @Valid
        private List<BpmSimpleModelNodeVO.HttpRequestParam> body;

        /**
         * 请求返回处理设置，用于修改流程表单值
         * <p>
         * key：表示要修改的流程表单字段名(name)
         * value：接口返回的字段名
         */
        @Schema(description = "请求返回处理设置", example = "[]")
        private List<KeyValue<String, String>> response;

    }

    @Schema(description = "自定义打印模板设置")
    @Data
    public static class PrintTemplateSetting {

        @Schema(description = "是否自定义打印模板", example = "false")
        @NotNull(message = "是否自定义打印模板不能为空")
        private Boolean enable;

        @Schema(description = "打印模板", example = "<p></p>")
        private String template;

    }


    /**
     * 父子流程可见性设置
     *
     * <h3>为什么这三条要能配</h3>
     *
     * v9 §4.3 给的默认值适用于大多数场景，但不是全部：
     *
     * <ul>
     *   <li>父流程参与者看子流程 —— 默认可见。但子流程若是「薪资核定」，
     *       主单据的发起人就不该看到</li>
     *   <li>子流程参与者看父流程 —— 默认可见。否则审批人不知道自己在审什么的上游</li>
     *   <li>子流程参与者看兄弟子流程 —— 默认不可见。这一条几乎不该开，
     *       开了等于同一主单据下所有子流程互相透明</li>
     * </ul>
     *
     * ⚠️ 三个字段都用 {@code Boolean} 而不是 {@code boolean}：null 表示「没配过」，
     * 走默认值。用基本类型的话，老数据反序列化出来是 false，
     * 于是升级瞬间所有父子可见性【全部关闭】—— 那是功能下线，不是收紧权限
     */
    @Schema(description = "父子流程可见性设置")
    @Data
    public static class ChildProcessVisibilitySetting {

        @Schema(description = "父流程参与者能否看子流程；null/true = 可见")
        private Boolean parentCanViewChild;

        @Schema(description = "子流程参与者能否看父流程；null/true = 可见")
        private Boolean childCanViewParent;

        /**
         * ⚠️ 默认值与上面两条【相反】，别顺手抄成 true
         */
        @Schema(description = "子流程参与者能否看兄弟子流程；null/false = 不可见")
        private Boolean childCanViewSibling;

    }

}
