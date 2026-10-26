package cn.enlev.ainb.module.infra.framework.billtemplate.core;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.infra.dal.dataobject.billtemplate.BillTemplateDO;
import cn.enlev.ainb.module.infra.enums.billtemplate.BillDateFormats;
import cn.enlev.ainb.module.infra.enums.billtemplate.BillGenTypeEnum;
import cn.enlev.ainb.module.infra.enums.billtemplate.ResetCycleEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 单据号的生成规格
 *
 * <h3>为什么需要这一层</h3>
 * 单据号有两种配置来源：
 * <ul>
 *   <li><b>模板模式</b>：引用 {@link BillTemplateDO}，前后缀是固定字符串</li>
 *   <li><b>内联模式</b>：规则写在表单字段上，前后缀由若干「段」拼成，段可以取自其它表单字段</li>
 * </ul>
 * 两者的差别只在「前后缀怎么来」。把求值后的前后缀连同其余参数归一到本类之后，
 * {@link BillNumberGenerator} 就只需要一套实现、一套测试。若两种模式各写一套拼号逻辑，
 * 补零、起始值换算、位数溢出这些细节就要维护两遍，迟早会走样。
 *
 * <h3>prefix / suffix 是【已求值】的结果</h3>
 * 内联模式的段求值（取字段值、拼常量）发生在构造本对象之前，本类拿到的就是最终字符串。
 *
 * @author 走进新时代
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillNumberSpec {

    /**
     * 计数作用域，用于拼 Redis 计数 key
     *
     * 模板模式为模板编码；内联模式为 {@code inline:{表单编码}:{字段}}。
     * 它决定了「哪些请求共用同一个流水计数器」。
     */
    private String counterScope;

    /**
     * 已求值的前缀
     */
    private String prefix;
    /**
     * 已求值的后缀
     */
    private String suffix;

    /**
     * 生成方式
     */
    private BillGenTypeEnum genType;

    /**
     * 日期格式，见 {@link BillDateFormats}
     */
    private String dateFormat;
    /**
     * 流水位数
     */
    private Integer digit;
    /**
     * 流水起始值
     */
    private Integer startNumber;
    /**
     * 流水步长
     */
    private Integer step;
    /**
     * 重置周期
     */
    private ResetCycleEnum resetCycle;
    /**
     * 流水是否按前缀分组重置
     */
    private boolean groupByPrefix;

    /**
     * 随机位数
     */
    private Integer randomDigit;
    /**
     * 随机类型：1-纯数字 2-字母+数字
     */
    private Integer randomType;

    /**
     * 由模板构造规格
     *
     * @param rule 单据模板
     * @return 生成规格
     */
    public static BillNumberSpec of(BillTemplateDO rule) {
        return BillNumberSpec.builder()
                .counterScope(rule.getCode())
                .prefix(StrUtil.nullToEmpty(rule.getPrefix()))
                .suffix(StrUtil.nullToEmpty(rule.getSuffix()))
                .genType(BillGenTypeEnum.typeOf(rule.getGenType()))
                .dateFormat(rule.getDateFormat())
                .digit(rule.getDigit())
                .startNumber(rule.getStartNumber())
                .step(rule.getStep())
                .resetCycle(ResetCycleEnum.codeOf(rule.getResetCycle()))
                .groupByPrefix(Boolean.TRUE.equals(rule.getGroupByPrefix()))
                .randomDigit(rule.getRandomDigit())
                .randomType(rule.getRandomType())
                .build();
    }

    /**
     * 是否需要消耗流水计数
     */
    public boolean needSequence() {
        return genType != null && genType.needSequence();
    }

    /**
     * 计算当前时刻的周期标记
     */
    public String period(LocalDateTime now) {
        return resetCycle().period(dateFormat, now);
    }

    /**
     * 计算 Redis 计数 key 的存活时长，null 表示永不过期
     */
    public java.time.Duration ttl() {
        return resetCycle().ttl(dateFormat);
    }

    /**
     * 重置周期，为空时按 {@link ResetCycleEnum#AUTO} 处理
     */
    public ResetCycleEnum resetCycle() {
        return resetCycle != null ? resetCycle : ResetCycleEnum.AUTO;
    }

}
