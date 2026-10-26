package cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.runtime;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 运行时渲染配置
 *
 * 运行时渲染页需要的最小配置集合。与设计态的 FormDesignRespVO 分开，原因有两个：
 * 1. 权限不同 —— 运行时用户只有 infra:form-design-run:{code}:query，
 *    没有设计态的 infra:form-design:query
 * 2. 不该把 tableBinding（含物理表名与列名）下发给前端 —— 那是服务端的实现细节，
 *    暴露出去等于把可攻击面摊开
 */
@Schema(description = "管理后台 - 表单运行时渲染配置 Response VO")
@Data
public class FormRuntimeConfigRespVO {

    @Schema(description = "表单编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "customer")
    private String code;

    @Schema(description = "表单名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "客户管理")
    private String name;

    @Schema(description = "表单类型：1-纯表单 2-表单+列表", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    private Integer formType;

    @Schema(description = "线上版本号", example = "3")
    private Integer version;

    @Schema(description = "form-create 的表单配置 JSON")
    private String formConf;

    @Schema(description = "form-create 的字段规则")
    private List<String> formFields;

    @Schema(description = "列表配置 JSON。仅表单+列表类型有值")
    private String listConf;

    @Schema(description = "主键字段名，前端做编辑/删除时需要", example = "id")
    private String pkField;

    /**
     * 乐观锁的版本字段名
     *
     * 有值即表示启用了乐观锁。前端要做的事只有一件：把详情里这个键的值原样存着，
     * 提交更新时一起带回来 —— 服务端拿它作为更新条件。
     *
     * 为空表示未启用，前端不需要做任何事。
     */
    @Schema(description = "乐观锁的版本字段名。为空表示未启用", example = "version")
    private String versionField;

    /**
     * 是否启用了数据变更日志
     *
     * 前端据此决定要不要在行操作里放「变更记录」入口。不下发的话前端只能
     * 无条件放一个按钮，点开却永远是空的。
     */
    @Schema(description = "是否启用数据变更日志", example = "true")
    private Boolean changeLogEnabled;

    /**
     * 自定义按钮 JSON 数组
     *
     * 前端按 position 分发到工具栏 / 行内 / 详情三处，可见条件用与联动规则
     * 同一个求值器（matchesGroup）按当前记录求值。
     */
    @Schema(description = "自定义按钮 JSON 数组")
    private String customButtons;

    /**
     * 详情页页签 JSON 数组
     *
     * 前端把表单本体作为第一个页签，其余按 type 分发渲染。
     */
    @Schema(description = "详情页页签 JSON 数组")
    private String detailTabs;

    /**
     * 打印模板 HTML
     *
     * 为空表示这张表单没配模板，前端据此决定要不要放「打印」按钮 ——
     * 无条件放的话，点开是一张空白的打印预览。
     *
     * 插值在前端做：模板是一整页 HTML，服务端渲染要把它和数据一起吐出来，
     * 而打印窗口本来就在浏览器里。⚠️ 前端插值时必须对【值】做 HTML 转义，
     * 见 {@code printTemplate.ts} 的说明。
     */
    @Schema(description = "打印模板 HTML，占位符为 {字段}")
    private String printTemplate;

    /**
     * 跨字段校验规则
     *
     * 下发给前端做即时校验。服务端提交时还会用同一份规则再校验一遍 ——
     * 下发它是为了体验（不用等提交才知道日期填反了），不是把校验交给前端。
     *
     * 字段【自身】的格式校验不在这里：它随 formFields 一起下发
     * （存在控件的 props.validateRules 里）。
     */
    @Schema(description = "跨字段校验规则 JSON 数组")
    private String validateRules;

    /**
     * 联动规则
     *
     * 下发给前端做即时反馈（字段显示/隐藏、必填状态随填写变化）。
     * 服务端提交时会用同一份规则重算一遍并据此丢弃不可写字段的值、拒绝必填空值 ——
     * 下发它是为了体验，不是把约束交给前端。
     *
     * <p>⚠️ 这个字段是补上来的：B1 交付了双端求值器与设计器 UI，但运行时渲染页
     * 从来没拿到过规则，联动只在「设计器里能配」与「提交时服务端会拦」两端存在，
     * 中间的交互是空的。
     */
    @Schema(description = "联动规则 JSON 数组")
    private String logicRules;

    /**
     * 扩展模式：0-无 1-前端脚本 2-服务端扩展点
     *
     * 前端据此判断要不要执行脚本。三者互斥 —— 服务端扩展点模式下前端一行脚本都不该跑，
     * 否则两边同时改数据，「这个字段到底是谁改的」无从追查。
     */
    @Schema(description = "扩展模式：0-无 1-前端脚本 2-服务端扩展点", example = "1")
    private Integer extensionMode;

    /**
     * 前端脚本 JSON（钩子名 → 脚本文本）
     *
     * <p>⚠️ 与 logicRules 一样，这个字段是补上来的。而它的缺失更严重：
     * extensionMode 的默认值就是「前端脚本」，也就是说在补上之前，
     * <b>默认模式下用户写的脚本一次都不会执行</b>，且没有任何报错。
     */
    @Schema(description = "前端脚本 JSON")
    private String formScript;

    @Schema(description = "beforeSubmit 超时后的动作：1-阻断 2-放行", example = "1")
    private Integer submitHookTimeoutAction;

}
