package cn.enlev.ainb.module.infra.framework.datamodel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 数据模型的规范配置
 *
 * 这里配的都是<b>建模规范</b>（别把库搞乱），不是防注入（别把库搞坏）。
 * 后者在 {@link cn.enlev.ainb.module.infra.framework.datamodel.core.SqlSafeGuard}
 * 里是硬编码的、不可关闭的。
 *
 * <p>本类的每一项都可关，是因为规范要能落到<b>存量不一致</b>的库上：
 * 老项目接入时先关掉前缀与命名约束跑起来，再逐步收敛。
 * 但<b>默认全开</b> —— 新库应当一开始就是规范的。
 *
 * <p>⚠️ 所有规范只作用于<b>新建</b>路径。{@code importTables} 导入存量表一律放行，
 * 否则库里本就存在的 {@code test_demo_*}、{@code qrtz_*} 全部导不进来。
 *
 * @author 走进新时代
 */
@ConfigurationProperties(prefix = "enlev.data-model")
@Validated
@Data
public class DataModelProperties {

    /**
     * 是否强制业务表前缀
     */
    private Boolean tablePrefixEnforced = true;

    /**
     * 业务表前缀
     *
     * 与「框架表前缀黑名单」是两回事：黑名单防的是撞上框架表（反向），
     * 本项是正向白名单，让业务表在库里一眼可辨。二者都要过。
     */
    private String tablePrefix = "biz_";

    /**
     * 是否强制 snake_case 命名
     *
     * 关掉后仍会走 {@code SqlSafeGuard} 的标识符白名单（字母数字下划线），
     * 只是不再要求全小写 —— 即 {@code myTableName} 这类驼峰会被放行。
     */
    private Boolean snakeCaseEnforced = true;

    /**
     * 是否强制索引名前缀（唯一索引 uk_、普通索引 idx_）
     */
    private Boolean indexPrefixEnforced = true;

    /**
     * 单表字段数上限，0 表示不限制
     *
     * 超过这个数通常说明该拆表了。人工建表手速有限，天然不会失控；
     * 接口调用方（尤其是带重试循环的）没有这个限制，所以要有配额。
     */
    private Integer maxColumnCount = 100;

    /**
     * 单租户的数据模型数量上限，0 表示不限制
     *
     * 🔴 <b>默认不限制，是刻意的。</b>这一项与其它规范不同：命名、前缀那些只作用于新建，
     * 存量表不受影响；而本项按<b>总数</b>算，存量直接决定还能不能建新表。
     *
     * <p>实测本地库租户 1 已有 286 个模型 —— 若默认给个 200，升上去的第一反应就是
     * 「所有人都建不了表了」，而他们什么都没做错。
     *
     * <p>所以默认放开，**接入 AI 等带重试循环的调用方之前再按实际情况显式配一个上限**。
     * 那才是这条配额真正要防的场景。
     */
    private Integer maxTableCount = 0;

}
