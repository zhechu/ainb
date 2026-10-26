package cn.enlev.ainb.module.infra.framework.formdesign.core.button;

import cn.enlev.ainb.module.infra.framework.formdesign.core.logic.LogicRule;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 运行时的自定义按钮
 *
 * <h3>它补的是什么</h3>
 *
 * 运行时的按钮此前固定为四个（新增 / 编辑 / 删除 / 导出）。业务上常见的
 * 「跳到关联报表并带上本行单号」「打开外部系统的对应页面」这类动作无处可放，
 * 只能改渲染页的代码 —— 那等于放弃了低代码。
 *
 * <h3>可见条件复用联动规则的条件模型</h3>
 *
 * {@code visibleWhen} 就是 {@link LogicRule.ConditionGroup}，与联动规则、
 * 跨字段校验用的是同一棵条件树、同一个求值器、同一个编辑器组件。
 * 另造一套的话，「大于」在三处的语义迟早会漂。
 *
 * <h3>⚠️ permission 只是界面级的</h3>
 *
 * 它决定「这个按钮画不画出来」，**不构成任何服务端约束**。按钮的动作自己该带
 * 什么权限，由那个动作的接口去管（跳转动作根本不调后端）。
 * 把它当成访问控制会是彻底的安全假象 —— 藏起来的按钮，构造一个请求照样能做。
 *
 * @author 走进新时代
 */
@Data
public class CustomButton {

    /**
     * 按钮标识
     *
     * 同一张表单内不能重复：前端按它做 key，重复会让 Vue 的列表渲染错位
     * （删掉一个按钮，消失的可能是另一个）。发布校验会拦。
     */
    @Schema(description = "按钮标识", requiredMode = Schema.RequiredMode.REQUIRED, example = "goReport")
    private String key;

    @Schema(description = "按钮文字", requiredMode = Schema.RequiredMode.REQUIRED, example = "查看报表")
    private String label;

    @Schema(description = "图标", example = "ep:link")
    private String icon;

    /**
     * 位置：TOOLBAR 工具栏 / ROW 列表行内 / DETAIL 详情弹窗
     *
     * 见 {@link Position}。
     */
    @Schema(description = "位置", requiredMode = Schema.RequiredMode.REQUIRED, example = "ROW")
    private String position;

    @Schema(description = "动作")
    private ButtonAction action;

    /**
     * 可见条件。为空表示始终可见
     *
     * ⚠️ 只对 ROW 与 DETAIL 生效 —— 它们有一条记录可以作为求值上下文。
     * TOOLBAR 在列表页上，没有「当前记录」，条件无从求值，配了也会被忽略。
     * 发布校验会对「给工具栏按钮配了条件」给出提示。
     */
    @Schema(description = "可见条件。仅 ROW / DETAIL 生效")
    private LogicRule.ConditionGroup visibleWhen;

    @Schema(description = "权限点。仅控制按钮是否画出来，不构成服务端约束", example = "infra:report:query")
    private String permission;

    /**
     * 按钮位置
     */
    public enum Position {
        /**
         * 列表页的工具栏。没有「当前记录」，可见条件不生效
         */
        TOOLBAR,
        /**
         * 列表行内。可见条件按该行数据求值
         */
        ROW,
        /**
         * 详情弹窗。可见条件按打开的那条记录求值
         */
        DETAIL;

        public static Position parse(String value) {
            if (value == null) {
                return null;
            }
            try {
                return valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /**
     * 按钮动作
     *
     * 两种：跳转与调数据接口。另两种（导出 / 触发流程）没做 ——
     * 导出已由内置按钮覆盖；触发流程要跨到 bpm，且得先定「表单数据怎么映射成流程变量」。
     */
    @Data
    public static class ButtonAction {

        @Schema(description = "动作类型", requiredMode = Schema.RequiredMode.REQUIRED, example = "NAVIGATE")
        private String type;

        /**
         * 跳转地址，支持 {@code {字段}} 插值
         *
         * 协议走白名单（与超链接控件同一套 {@code LinkUrlGuard}）——
         * {@code javascript:} 与 {@code data:} 会在用户点击时执行任意脚本。
         */
        @Schema(description = "跳转地址。支持 {字段} 插值", example = "/report/order?no={order_no}")
        private String url;

        @Schema(description = "打开方式：_self 当前页 / _blank 新窗口", example = "_blank")
        private String target;

        /**
         * 要调用的数据接口编码
         *
         * ⚠️ <b>它由服务端从已发布的表单配置里读出来，不接受客户端传</b>。
         * 让客户端指定接口编码的话，这个端点就变成「任何对某张表单有 update 权限的人
         * 都能调任意写型数据接口」—— 那是一个真正的权限提升面。
         * 现在能调到的，只有表单设计者在这张表单上明确接过线的那几个接口。
         */
        @Schema(description = "数据接口编码。仅 INVOKE 动作使用", example = "void_order")
        private String interfaceCode;

        /**
         * 入参映射：把当前记录的字段值喂给接口的入参
         *
         * 同样由服务端解析：服务端按主键重新读一遍记录再取值，<b>不用客户端送上来的值</b>。
         * 用客户端的值等于让调用方自由构造接口入参，那和让它自由指定接口编码一样危险。
         */
        @Schema(description = "入参映射。仅 INVOKE 动作使用")
        private List<ParamMapping> params;

        @Schema(description = "成功提示语", example = "已作废")
        private String successMessage;

        /**
         * 动作类型
         */
        public enum Type {
            /**
             * 跳转。内部路由或外部地址，协议走白名单
             */
            NAVIGATE,
            /**
             * 调数据接口
             *
             * 与取选项那条路（{@code /data-interface/options}）的区别：那条被服务端
             * <b>强制只允许读取型接口</b>，作废单据这类写操作过不去。所以这里走的是
             * {@code invoke}，允许写型接口。
             *
             * ⚠️ 鉴权是<b>临时状态</b>：沿用表单自己的运行时权限
             * （{@code infra:form-design-run:{code}:update}），没有针对数据接口的
             * 独立权限模型。见交接文档第十五节与台账。
             */
            INVOKE;

            public static Type parse(String value) {
                if (value == null) {
                    return null;
                }
                try {
                    return valueOf(value.trim().toUpperCase());
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        }

        /**
         * 一条入参映射
         *
         * {@code name} 是接口那边的参数名，{@code field} 是本表单的字段名。
         * 不支持写死常量：常量应当配在数据接口自己的参数默认值里，
         * 在两处都能配同一件事，用户改了 A 处不生效会一路找到 B 处。
         */
        @Data
        public static class ParamMapping {

            @Schema(description = "接口的参数名", requiredMode = Schema.RequiredMode.REQUIRED, example = "id")
            private String name;

            @Schema(description = "取值的表单字段名", requiredMode = Schema.RequiredMode.REQUIRED, example = "id")
            private String field;

        }

    }

}
