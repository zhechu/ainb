package cn.enlev.ainb.module.agent.framework.agent.core.validate;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDesignSaveReqDTO;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表单「静默清空」的防线（方案 §5.3）
 *
 * <h3>要防的是什么</h3>
 * {@code updateFormDesign} <b>不是增量更新</b>。
 * {@code FormDesignServiceImpl.clearableColumnsWrapper} 对 8 个列做显式清空：
 * <b>这一次没传值的，一律 set null</b>。
 *
 * <p>这个设计对设计器是对的 —— 设计器永远提交全量，用户「清空某项配置」正是靠它。
 * 但 AI 若按「我只改一个字段」的直觉提交局部 DTO，该表单的
 * <b>联动规则、校验规则、自定义按钮、页签、打印模板、前端脚本会被静默清空</b>。
 *
 * <h3>🔴 为什么它比 datamodel_update 更危险</h3>
 * 建表那边至少还有 {@code riskConfirmed} 与有损变更校验拦一道。
 * <b>这边什么都没有</b> —— 后端直接 set null，接口返回成功，
 * 用户重新打开表单才发现自己配了半天的联动规则不见了。
 * 而那时已经没有草稿期快照可回滚（版本快照只在 publish 时产生）。
 *
 * <p>所以这道防线只能建在调用方这一侧。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class FormClearGuard {

    /**
     * 比对「原本有值 → 这次没传」的字段
     *
     * @param original 从库里取回来的全量定义
     * @param updated  即将提交的定义
     * @return 会被清空的字段清单
     */
    public ClearDiff diff(FormDesignSaveReqDTO original, FormDesignSaveReqDTO updated) {
        ClearDiff diff = new ClearDiff();
        if (original == null || updated == null) {
            return diff;
        }
        for (String field : FormDesignSaveReqDTO.CLEARABLE_FIELDS) {
            String before = readField(original, field);
            String after = readField(updated, field);
            // 只关心「原本有 → 现在没了」。原本就是空的不算清空，
            // 而「原本没有 → 现在有了」是正常的新增配置
            if (StrUtil.isNotBlank(before) && StrUtil.isBlank(after)) {
                diff.cleared.add(field);
                diff.originalValues.put(field, before);
            }
        }
        return diff;
    }

    /**
     * 把丢失的字段<b>补回去</b>
     *
     * <h3>为什么默认是「补回去」而不是「报错」</h3>
     * 报错会让 AI 陷进一个它很难自己走出来的坑：它想改标题，
     * 却被告知「你漏传了 printTemplate」—— 而它压根不知道原来的打印模板是什么。
     * 正确的做法本来就是「取回全量 → 局部改 → 整体回写」，
     * 那么这里直接替它把没动的部分补回去，是同一件事的更可靠实现。
     *
     * <p>⚠️ 用户<b>明确要求删除</b>某项配置时，调用方要跳过这一步 ——
     * 那时「变成空」是意图，不是事故。
     *
     * @param original 原始全量定义
     * @param updated  即将提交的定义，会被就地修改
     * @return 实际补回了哪些字段
     */
    public List<String> restoreCleared(FormDesignSaveReqDTO original, FormDesignSaveReqDTO updated) {
        ClearDiff diff = diff(original, updated);
        for (Map.Entry<String, String> entry : diff.getOriginalValues().entrySet()) {
            writeField(updated, entry.getKey(), entry.getValue());
        }
        if (!diff.getCleared().isEmpty()) {
            log.info("[restoreCleared][补回了会被静默清空的字段：{}]", diff.getCleared());
        }
        return diff.getCleared();
    }

    private String readField(FormDesignSaveReqDTO dto, String field) {
        try {
            Method getter = FormDesignSaveReqDTO.class.getMethod(
                    "get" + StrUtil.upperFirst(field));
            Object value = getter.invoke(dto);
            return value == null ? null : value.toString();
        } catch (Exception ex) {
            // 字段名对不上说明 CLEARABLE_FIELDS 与 DTO 漂移了 —— 这是个真问题，
            // 但不该在运行时炸掉一次表单保存。打 warn 让它在日志里可见
            log.warn("[readField][读不到字段({})，CLEARABLE_FIELDS 可能已与 DTO 漂移]", field);
            return null;
        }
    }

    private void writeField(FormDesignSaveReqDTO dto, String field, String value) {
        try {
            Method setter = FormDesignSaveReqDTO.class.getMethod(
                    "set" + StrUtil.upperFirst(field), String.class);
            setter.invoke(dto, value);
        } catch (Exception ex) {
            log.warn("[writeField][写不回字段({})]", field);
        }
    }

    /**
     * 清空差异
     */
    @Getter
    public static class ClearDiff {

        /**
         * 会被清空的字段名
         */
        private final List<String> cleared = new ArrayList<>();

        /**
         * 字段名 → 原值
         *
         * 带上原值是为了让 Plan 卡片能显示「本来是什么」——
         * 只说「printTemplate 会被清空」，用户无从判断该不该同意。
         */
        private final Map<String, String> originalValues = new LinkedHashMap<>();

        public boolean isEmpty() {
            return cleared.isEmpty();
        }
    }

    /**
     * 字段名 → 中文说明，用于 Plan 卡片上的高亮提示
     *
     * 直接显示 {@code logicRules} 对用户没有意义 —— 他不知道那是「联动规则」。
     */
    public static String describe(String field) {
        return switch (field) {
            case "listConf" -> "列表设计";
            case "logicRules" -> "联动规则";
            case "validateRules" -> "校验规则";
            case "customButtons" -> "自定义按钮";
            case "detailTabs" -> "详情页页签";
            case "printTemplate" -> "打印模板";
            case "formScript" -> "前端脚本";
            case "remark" -> "备注";
            default -> field;
        };
    }

}
