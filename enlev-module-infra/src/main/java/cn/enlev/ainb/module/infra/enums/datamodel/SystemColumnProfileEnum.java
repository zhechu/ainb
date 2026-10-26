package cn.enlev.ainb.module.infra.enums.datamodel;

import cn.enlev.ainb.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 系统列注入策略
 *
 * 🔴 <b>这个枚举的存在是为了消灭一处「两套事实规范」的冲突。</b>
 *
 * <p>本分支里同时存在两种系统列约定，而且<b>互不兼容</b>：
 * <table border="1">
 *   <tr><th></th><th>框架基线表</th><th>本分支 20 张 test_demo_* 样板表</th></tr>
 *   <tr><td>tenant_id</td><td>有</td><td><b>零</b></td></tr>
 *   <tr><td>version</td><td>无</td><td><b>有</b>（两个 SQL 文件合计 15 处）</td></tr>
 * </table>
 *
 * <p>后果是「人工建的表」与「数据模型建的表」结构必然不同，而那 20 张恰恰是
 * 表单设计文档与 demo 脚本的基准样本 —— <b>照着示例表学，学到的是错的</b>。
 *
 * <p>此前唯一的「解法」是绕过注入器：显式提交一个同名列让注入器让位
 * （见 {@code SystemColumnInjector} 的「不覆盖」语义）。那是实现细节泄漏成的偶然行为，
 * 不是设计。本枚举把它变成<b>显式声明</b>。
 *
 * <p>⚠️ <b>profile 不落库，只在建表那一刻起作用。</b>
 * 因为 {@code DynamicSqlExecutor} 判断要不要拼租户条件，靠的是
 * 「这张表实际有没有 tenant_id 列」（{@code actualColumns.contains(...)}），
 * 而不是任何配置。物理表结构本身就是唯一的事实来源，再存一份 profile 只会产生
 * 「配置说是全局表、实际有 tenant_id」这类对不上的状态。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum SystemColumnProfileEnum implements ArrayValuable<String> {

    /**
     * 多租户表（默认）
     *
     * id + 审计四列 + deleted + tenant_id，与框架基线表完全一致。
     * <b>业务表一律用这个</b>，除非确有理由。
     */
    TENANT("TENANT", "多租户表，含 tenant_id，与框架表一致"),

    /**
     * 全局表
     *
     * id + 审计四列 + deleted，<b>不含 tenant_id</b>。
     * 适用于全租户共享的字典型数据（如模型单价表、行业模板库）。
     *
     * <p>🔴 选它意味着<b>这张表的数据对所有租户可见</b> ——
     * {@code DynamicSqlExecutor.appendTenantCondition} 发现没有 tenant_id 列就不拼租户条件。
     * 不要用它来「省事」。
     */
    GLOBAL("GLOBAL", "全局表，不含 tenant_id，数据对所有租户可见"),

    /**
     * 兼容存量样板表
     *
     * id + 审计四列 + version + deleted，不含 tenant_id ——
     * 对齐本分支 test_demo_* 那 20 张表的结构。
     *
     * <p>⚠️ <b>仅为让存量表能被一致地管理而存在，新表不要用。</b>
     * 它同时缺租户隔离、又多一个框架并不消费的 version 列
     * （MyBatis 的 {@code @Version} 乐观锁在动态 CRUD 路径上根本不生效）。
     */
    LEGACY("LEGACY", "兼容存量样板表：含 version、不含 tenant_id，新表不要用"),
    ;

    public static final String[] ARRAYS = Arrays.stream(values())
            .map(SystemColumnProfileEnum::getProfile).toArray(String[]::new);

    /**
     * 策略标识
     */
    private final String profile;
    /**
     * 描述
     */
    private final String description;

    @Override
    public String[] array() {
        return ARRAYS;
    }

    /**
     * 根据标识获得枚举，找不到或为空返回默认的 {@link #TENANT}
     *
     * 刻意「找不到就用默认」而不是抛错：这样老调用方不传 profile 时行为与改造前完全一致。
     */
    public static SystemColumnProfileEnum profileOf(String profile) {
        return Arrays.stream(values())
                .filter(item -> item.getProfile().equalsIgnoreCase(profile))
                .findFirst().orElse(TENANT);
    }

}
