package cn.enlev.ainb.module.infra.framework.formdesign.core.export;

import lombok.Data;

import java.util.List;

/**
 * 一次导出的选项
 *
 * <h3>为什么用一个对象而不是往方法上加参数</h3>
 *
 * 导出的参数已经有查询条件、排序、可见列三组，再加范围、分页、合计行，
 * 方法签名会变成七八个参数 —— 那种签名在调用处完全看不出谁是谁，
 * 调换两个 String 参数的位置编译器也不会报错。
 *
 * <h3>⚠️ 这里的每一项都来自「用户在界面上的选择」，不是设计期配置</h3>
 *
 * 导出的原则是<b>列表怎么显示就怎么导</b>，所以格式、列、顺序都不在这里 ——
 * 它们由列表配置与用户的列设置决定。
 *
 * 这里只放三件「同一张列表、同一次导出也可能不同」的事：
 *
 * <ul>
 *   <li><b>范围</b>：导当前这一页，还是这次查询的全部。
 *       两者都符合所见即所得 —— 当前页就是他正看着的那一页</li>
 *   <li><b>合计行</b>：要拿去做透视表时，合计行会碍事</li>
 * </ul>
 *
 * 行数上限不在这里：它是设计期配置（{@code listConf.exportMaxRows}），
 * 由表单设计者定，不该让每个使用者自己选。
 *
 * @author 走进新时代
 */
@Data
public class FormExportOptions {

    /**
     * 导出范围
     */
    public enum Scope {
        /**
         * 当前这一页 —— 他正看着的那些行
         */
        CURRENT_PAGE,
        /**
         * 按当前查询条件的全部
         */
        ALL
    }

    /**
     * 范围。默认全部 —— 这是改造前的行为，不给值时不该悄悄改变它
     */
    private Scope scope = Scope.ALL;

    /**
     * 页码。仅 CURRENT_PAGE 用
     */
    private Integer pageNo;

    /**
     * 每页条数。仅 CURRENT_PAGE 用
     */
    private Integer pageSize;

    /**
     * 要不要导出合计行。
     *
     * 为空表示<b>跟随列表</b>（列表上有合计行就导、没有就不导）——
     * 这是所见即所得的默认。用户显式选了才按他选的来
     */
    private Boolean includeSummary;

    /**
     * 用户在列设置里保留下来的列，含顺序
     */
    private List<String> visibleFields;

    /**
     * 排序字段
     */
    private String sortField;

    /**
     * 排序方向
     */
    private String sortOrder;

    public boolean isCurrentPage() {
        return scope == Scope.CURRENT_PAGE;
    }

    /**
     * 解析前端传来的范围
     *
     * 认不出的值按「全部」处理，不抛异常 —— 导出是个读操作，
     * 为一个拼错的枚举值让用户导不出来不值得
     */
    public static Scope parseScope(String raw) {
        if (raw == null || raw.isBlank()) {
            return Scope.ALL;
        }
        try {
            return Scope.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Scope.ALL;
        }
    }

}
