package cn.enlev.ainb.module.infra.framework.formdesign.core.imports;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.infra.framework.formdesign.core.imports.ImportModels.ErrorLevel;
import cn.enlev.ainb.module.infra.framework.formdesign.core.imports.ImportModels.RowError;
import cn.enlev.ainb.module.infra.framework.formdesign.core.logic.FormLogicGuard;
import cn.enlev.ainb.module.infra.framework.formdesign.core.subtable.SubTableGuard;
import cn.enlev.ainb.module.infra.framework.formdesign.core.validation.FormValidationGuard;
import lombok.Data;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 干跑校验：按运行时的同一套规则预判，但不落库
 *
 * <h3>为什么必须和运行时用同一套组件</h3>
 *
 * 「预览说没问题、执行却报错」是最伤信任的一种表现 —— 用户会开始怀疑
 * 这个功能整体不可靠，然后回头去手工录入。所以要尽可能早地把运行时
 * 必然会拦的东西拦在前面。
 *
 * 但**做不到 100% 一致**，有意识地留下两个差异：
 *
 * <ol>
 *   <li><b>唯一性并发竞态</b>：预览时查了没重复，执行时另一个人刚好插了一条。
 *       这个差异无法消除（除非加锁到执行完），但不该假装没有 ——
 *       前端在执行结果的提示里要说清「预览通过不代表一定成功」</li>
 *   <li><b>服务端扩展点</b>：扩展点可以按任意业务规则拒绝。
 *       干跑不调它（调它有副作用，比如发通知）。所以第 8 步的拒绝只在执行时出现</li>
 * </ol>
 *
 * <h3>这里【不】做唯一性查询</h3>
 *
 * 唯一性要查库，查库的结果会随执行时刻变化，属于「存在性」判断而不是「格式」判断。
 * 它由 {@code FormDesignRuntimeServiceImpl} 在预览时单独处理，
 * 因为那边才有 dataSourceId 与 SQL 构造器。
 *
 * @author 走进新时代
 */
@Component
public class ImportDryRunValidator {

    @Resource
    private FormLogicGuard logicGuard;
    @Resource
    private SubTableGuard subTableGuard;
    @Resource
    private FormValidationGuard validationGuard;

    /**
     * 一次干跑的结果
     */
    @Data
    public static class Result {
        /**
         * 净化后的数据（联动规则可能丢弃/改写了字段）
         */
        private Map<String, Object> data = new LinkedHashMap<>();
        /**
         * 致命问题
         */
        private List<RowError> errors = new ArrayList<>();
        /**
         * 提示性问题
         */
        private List<RowError> warnings = new ArrayList<>();

        public boolean isPassed() {
            return errors.isEmpty();
        }
    }

    /**
     * 校验一条主记录及其明细
     *
     * @param formFieldsJson 表单字段规则
     * @param validateRulesJson 跨字段校验规则
     * @param logicRulesJson 联动规则
     * @param data 这一条主记录的数据（含子表数组）
     * @param rowNo 出错时报的行号
     * @param widgets 可导入字段，用于把字段名翻成中文标题
     * @return 干跑结果
     */
    public Result validate(String formFieldsJson, String validateRulesJson, String logicRulesJson,
                           Map<String, Object> data, int rowNo,
                           Map<String, ImportWidget> widgets) {
        Result result = new Result();

        // 1. 联动规则复校验。
        //    它同时承担两件事：丢弃不可写字段的提交值，以及**基础必填校验**。
        //    没有联动规则的表单也走它 —— checkRequiredOnly 那条分支就是为这种表单准备的，
        //    不调的话「必填」在预览阶段完全不起作用
        FormLogicGuard.GuardResult guardResult =
                logicGuard.guard(logicRulesJson, formFieldsJson, data);
        result.setData(guardResult.getData());

        for (String label : guardResult.getMissingRequiredFields()) {
            result.getErrors().add(buildError(rowNo, null, null, label,
                    String.format("「%s」是必填项，这一行没填", label), null));
        }
        for (String label : guardResult.getConflictFields()) {
            // 配置矛盾（既必填又不可填）不是用户的问题，但这一行一定进不去 ——
            // 报成错误并说清是配置问题，免得用户对着单元格反复改
            result.getErrors().add(buildError(rowNo, null, null, label,
                    String.format("「%s」被配置成必填但同时又不可填，这是表单配置矛盾，请联系管理员", label),
                    null));
        }

        // 2. 子表行数与行内必填
        SubTableGuard.ValidationResult subTableResult =
                subTableGuard.validate(formFieldsJson, guardResult.getData());
        if (!subTableResult.isPassed()) {
            for (String message : subTableResult.getErrors()) {
                result.getErrors().add(buildError(rowNo, null, null, null, message, null));
            }
        }

        // 3. 字段级格式与跨字段校验。
        //    ⚠️ 顺序与 FormSubmitProcessor 一致：联动 → 子表 → 格式。
        //    子表排在格式前面，是因为格式校验里也含子表明细列的规则，
        //    行数都不对时再报「明细里的手机号不对」是噪音
        FormValidationGuard.ValidationResult validationResult =
                validationGuard.validate(formFieldsJson, validateRulesJson, guardResult.getData());
        if (!validationResult.isPassed()) {
            for (String message : validationResult.getErrors()) {
                result.getErrors().add(buildError(rowNo, null, null, null, message, null));
            }
        }
        return result;
    }

    /**
     * 公式字段的值与文件里填的不一致
     *
     * 这不是错误 —— 服务端本来就该重算，而且必须重算
     * （否则用户改一个数就能伪造合计）。但要**告诉**用户，
     * 否则他会以为「我填的值存进去了」，下次对账时对不上。
     */
    public RowError formulaOverridden(int rowNo, String field, String label,
                                      Object fileValue, Object calcValue) {
        RowError warning = buildError(rowNo, null, field, label,
                String.format("「%s」是公式字段，文件里填的是「%s」，"
                        + "导入时会按公式重算为「%s」", label, fileValue, calcValue), null);
        warning.setLevel(ErrorLevel.WARN);
        return warning;
    }

    /**
     * 造一条错误
     */
    public static RowError buildError(int rowNo, String columnLetter, String field,
                                      String fieldLabel, String message, String rawValue) {
        RowError error = new RowError();
        error.setRowNo(rowNo);
        error.setColumnLetter(columnLetter);
        error.setField(field);
        error.setFieldLabel(fieldLabel);
        error.setMessage(message);
        error.setRawValue(rawValue);
        error.setLevel(ErrorLevel.ERROR);
        return error;
    }

    /**
     * 字段名 → 中文标题。找不到时用字段名兜底
     */
    public static String labelOf(Map<String, ImportWidget> widgets, String field) {
        ImportWidget widget = widgets.get(field);
        return widget != null ? widget.label() : field;
    }

    /**
     * 把「不含表头也要能定位」的信息补进错误里
     *
     * 调用方拿到的是「解析阶段的错误」（那些带着列字母与原始文本），
     * 而干跑阶段的错误只知道行号。这个方法把两者合并 ——
     * 用户需要的是「第 7 行 I 列 预算金额：必须是数字」，缺一不可。
     */
    public static void mergeLocation(RowError target, ImportModels.RawRow source) {
        if (target.getColumnLetter() != null || source == null) {
            return;
        }
        if (target.getField() == null) {
            return;
        }
        String raw = source.getRawTexts().get(target.getField());
        if (StrUtil.isNotBlank(raw)) {
            target.setRawValue(raw);
        }
    }

}
