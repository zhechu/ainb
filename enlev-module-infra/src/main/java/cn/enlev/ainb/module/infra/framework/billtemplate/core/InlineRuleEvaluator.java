package cn.enlev.ainb.module.infra.framework.billtemplate.core;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.module.infra.enums.billtemplate.BillGenTypeEnum;
import cn.enlev.ainb.module.infra.enums.billtemplate.ResetCycleEnum;
import cn.enlev.ainb.module.infra.enums.billtemplate.SegmentSourceEnum;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 内联单据规则的求值器
 *
 * 把 {@link InlineRuleConfig} 连同当前提交数据，求值成一个可直接生成号码的
 * {@link BillNumberSpec}。求值只发生在「前后缀」这一层，其余参数原样透传 ——
 * 这正是把两种模式归一到 BillNumberSpec 的意义所在。
 *
 * <h3>字段作用域</h3>
 * 子表内的单据字段取值时，先在本行找，找不到再回主表找。反过来主表字段不会去子表里找 ——
 * 子表有多行，取哪一行都是错的。
 *
 * @author 走进新时代
 */
@Slf4j
public final class InlineRuleEvaluator {

    /**
     * 计数作用域的前缀，用于与模板模式的作用域区分开
     *
     * 内联规则没有模板编码可用，只能靠「表单 + 字段」定位它的计数器。
     */
    private static final String SCOPE_PREFIX = "inline:";

    private InlineRuleEvaluator() {
    }

    /**
     * 求值成生成规格
     *
     * @param formCode  表单编码
     * @param field     单据字段名
     * @param config    内联规则配置
     * @param rowData   当前行数据。主表字段传主表数据本身
     * @param mainData  主表数据，供子表行回退查找；主表字段传 null 即可
     * @return 生成规格
     */
    public static BillNumberSpec evaluate(String formCode, String field, InlineRuleConfig config,
                                          Map<String, Object> rowData, Map<String, Object> mainData) {
        String prefix = joinSegments(config.getPrefixSegments(), rowData, mainData);
        String suffix = joinSegments(config.getSuffixSegments(), rowData, mainData);
        return BillNumberSpec.builder()
                .counterScope(SCOPE_PREFIX + formCode + ":" + field)
                .prefix(prefix)
                .suffix(suffix)
                .genType(resolveGenType(config.getGenType()))
                .dateFormat(config.getDateFormat())
                .digit(config.getDigit())
                .startNumber(config.getStartNumber())
                .step(config.getStep())
                .resetCycle(ResetCycleEnum.codeOf(config.getResetCycle()))
                .groupByPrefix(config.groupByPrefixOrDefault())
                .randomDigit(config.getRandomDigit())
                .randomType(config.getRandomType())
                .build();
    }

    /**
     * 把原始 Map 配置转成强类型
     *
     * 解析失败返回 null 而非抛异常：内联规则是用户在设计器里点出来的，
     * 结构会随设计器演进，历史脏数据不该让整张单子存不进去。
     * 配置层面的问题由发布校验负责给出明确报错。
     */
    public static InlineRuleConfig parse(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return JsonUtils.parseObject(JsonUtils.toJsonString(raw), InlineRuleConfig.class);
        } catch (Exception e) {
            log.warn("[parse][内联单据规则配置解析失败，已忽略]", e);
            return null;
        }
    }

    /**
     * 按顺序拼接段
     */
    private static String joinSegments(List<InlineRuleConfig.RuleSegment> segments,
                                       Map<String, Object> rowData, Map<String, Object> mainData) {
        if (segments == null || segments.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (InlineRuleConfig.RuleSegment segment : segments) {
            if (segment == null || StrUtil.isBlank(segment.getValue())) {
                continue;
            }
            sb.append(resolveSegment(segment, rowData, mainData));
        }
        return sb.toString();
    }

    private static String resolveSegment(InlineRuleConfig.RuleSegment segment,
                                         Map<String, Object> rowData, Map<String, Object> mainData) {
        SegmentSourceEnum source = SegmentSourceEnum.typeOf(segment.getSourceType());
        if (source == SegmentSourceEnum.CONSTANT) {
            return segment.getValue();
        }
        Object value = lookup(segment.getValue(), rowData, mainData);
        if (value == null) {
            // 被引用的字段用户没填。这里输出空串而不是报错 ——
            // 号码会短一截，但单子能存进去；报错会让用户在提交环节撞墙且不知道为什么
            log.debug("[resolveSegment][单据规则引用的字段({})没有值，该段输出为空]", segment.getValue());
            return "";
        }
        return stringify(value);
    }

    /**
     * 按作用域查找字段值：先本行，再主表
     */
    private static Object lookup(String field, Map<String, Object> rowData, Map<String, Object> mainData) {
        if (rowData != null && rowData.containsKey(field)) {
            return rowData.get(field);
        }
        if (mainData != null) {
            return mainData.get(field);
        }
        return null;
    }

    /**
     * 字段值转字符串
     *
     * 日期类型单独处理：直接 toString 会得到 {@code 2026-09-06T14:30} 这种带分隔符的形式，
     * 塞进单号里既难看又可能超长。
     */
    private static String stringify(Object value) {
        if (value instanceof LocalDateTime dateTime) {
            return DateTimeFormatter.ofPattern("yyyyMMdd").format(dateTime);
        }
        if (value instanceof java.time.LocalDate date) {
            return DateTimeFormatter.ofPattern("yyyyMMdd").format(date);
        }
        return String.valueOf(value);
    }

    private static BillGenTypeEnum resolveGenType(Integer genType) {
        BillGenTypeEnum resolved = BillGenTypeEnum.typeOf(genType);
        // 没配就按最常用的时间流水处理，而不是让 body() 拿到 null 输出空主体
        return resolved != null ? resolved : BillGenTypeEnum.TIME;
    }

}
