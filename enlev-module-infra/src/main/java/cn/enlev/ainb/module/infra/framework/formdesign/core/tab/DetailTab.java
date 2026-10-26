package cn.enlev.ainb.module.infra.framework.formdesign.core.tab;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 详情页页签
 *
 * <h3>它补的是什么</h3>
 *
 * 详情页此前只有表单本体。业务上常见的「这条记录被改过什么」「挂在它下面的关联单据
 * 有哪些」只能另找入口，或者干脆看不到 —— 而这两件恰恰是查一条单据时最想看的。
 *
 * <h3>两种类型</h3>
 *
 * 变更日志（{@code CHANGE_LOG}）与关联数据列表（{@code RELATION_LIST}）。
 *
 * 页签当初做成清单而不是几个布尔开关，就是为了加类型这一步：加第二种只动
 * {@link Type} 与渲染分支，配置结构没动过 —— 只是多了两个仅
 * {@code RELATION_LIST} 用的字段。
 *
 * @author 走进新时代
 */
@Data
public class DetailTab {

    /**
     * 页签标识
     *
     * 同一张表单内不能重复：前端按它做 el-tabs 的 name，重复会让切换错乱
     * （点第二个页签，显示的是第三个的内容）。发布校验会拦。
     */
    @Schema(description = "页签标识", requiredMode = Schema.RequiredMode.REQUIRED, example = "changeLog")
    private String key;

    @Schema(description = "页签标题", requiredMode = Schema.RequiredMode.REQUIRED, example = "变更记录")
    private String label;

    @Schema(description = "页签类型", requiredMode = Schema.RequiredMode.REQUIRED, example = "CHANGE_LOG")
    private String type;

    /**
     * 目标表单编码。仅 {@code RELATION_LIST} 使用
     *
     * 「挂在这条记录下面的单据」在哪张表单里。运行时调的是<b>那张表单的分页接口</b>，
     * 所以它必须是已发布的 —— 发布校验会核。
     */
    @Schema(description = "目标表单编码。仅 RELATION_LIST 使用", example = "order_item")
    private String targetFormCode;

    /**
     * 目标表单上指向本记录的那个字段。仅 {@code RELATION_LIST} 使用
     *
     * 运行时按 {@code 该字段 EQ 本记录主键} 去查。字段必须真的在目标表单上 ——
     * 不在的话运行时会<b>静默忽略这个条件</b>（分页接口对认不出的字段就是这么处理的），
     * 于是页签里列出目标表单的<b>全部</b>数据，看起来像是「关联查询把别人的单据也带出来了」。
     * 发布校验因此必须核这个字段。
     */
    @Schema(description = "目标表单上指向本记录的字段。仅 RELATION_LIST 使用", example = "order_id")
    private String targetField;

    /**
     * 页签类型
     */
    public enum Type {
        /**
         * 变更日志时间线。要求表单启用了变更日志，否则页签是空的 ——
         * 发布校验会拦住这种组合
         */
        CHANGE_LOG,
        /**
         * 关联数据列表
         *
         * 配「目标表单编码 + 目标表单上指向本记录的那个字段」，运行时调那张表单的
         * 分页接口并带一个 {@code EQ} 条件。<b>后端不需要任何新东西</b> ——
         * 分页接口本来就支持 {@code conditions}。
         *
         * ⚠️ 权限按<b>目标表单</b>算：调的是它的运行时接口，要
         * {@code infra:form-design-run:{目标编码}:query}。没有的话页签是空的，
         * 这是对的 —— 不该因为「在另一张表单的详情页里」就绕过目标表单的权限。
         */
        RELATION_LIST;

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

}
