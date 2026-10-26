package cn.enlev.ainb.module.infra.framework.formdesign.core.sql;

/**
 * 多值列的落库形态 —— 查询条件拼装侧需要知道的全部信息
 *
 * <h3>为什么查询侧必须知道这件事</h3>
 *
 * 多选类控件（checkbox / select multiple / 标签选择…）落库时不是一个标量：
 * 映射到 JSON 列时存 {@code ["微生物","重金属"]}，映射到 varchar 列时存
 * {@code 微生物,重金属}。两种形态下「这行有没有勾中『微生物』」都不是
 * {@code col = '微生物'} 也不是 {@code col IN ('微生物','重金属')} 能表达的 ——
 * 前者要求整串恰好等于，后者要求整串恰好是其中一项。
 *
 * <p>实测后果：多选字段作查询条件时永远查不出数据，但<b>不报错</b>。
 * varchar 形态下还有个更坏的变体 —— 存量里「只勾了一项」的行存的就是
 * {@code 微生物}，它<b>能</b>被 {@code IN} 命中，于是表现为「有时查得到、
 * 有时查不到」，比一律查不到更难被当成 bug 报上来。
 *
 * <h3>为什么不直接用 {@code FormValueSerializer.Mode}</h3>
 *
 * Mode 是<b>值转换</b>那一侧的词表（JSON / DELIMITED / FIRST_URL），
 * 它回答「读出来要不要拆、写进去要不要拼」。查询侧问的是另一件事：
 * 「这一列该用 JSON 函数还是分隔串匹配，分隔符是什么」。
 *
 * <p>把 Mode 直接搬到 SQL 构建器上会带来两个问题：一是 {@code FIRST_URL}
 * 在查询侧没有任何意义（文件控件按首个 URL 落库，是标量），
 * SQL 层却要在 switch 里为它写一个空分支；二是分隔符不在 Mode 里，
 * 而 {@code FIND_IN_SET} 只认逗号，分隔符可配（{@code structural.delimitedSeparator}）
 * 这件事必须传到 SQL 层，否则改配置就会静默查出错误结果。
 *
 * <p>所以这里是一个<b>投影</b>而不是第二套词表：由运行时服务层从
 * {@code StoredField.mode} + 实际分隔符一次性算出，SQL 层只消费、不解释。
 *
 * @param json      true 表示列是 JSON 形态（用 {@code JSON_CONTAINS}），
 *                  false 表示分隔串形态（用 {@code FIND_IN_SET} 或等价的边界匹配）
 * @param separator 分隔串形态下的分隔符。{@code json=true} 时忽略。
 *                  🔴 不能假定为逗号 —— 它来自控件契约声明，是可配的
 */
public record MultiValueShape(boolean json, String separator) {

    /** JSON 列形态 */
    public static MultiValueShape ofJson() {
        return new MultiValueShape(true, null);
    }

    /**
     * 分隔串形态
     *
     * @param separator 实际分隔符，空值由调用方兜成逗号（口径同 FormValueSerializer#separator）
     */
    public static MultiValueShape ofDelimited(String separator) {
        return new MultiValueShape(false, separator);
    }
}
