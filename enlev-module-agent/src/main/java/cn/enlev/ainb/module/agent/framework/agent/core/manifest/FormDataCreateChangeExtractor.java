package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.tool.impl.formdata.FormDataCreateTool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@code form_data_create} → {@link ChangeKind#DATA_WRITE}
 *
 * <h3>与 {@code form_save_draft} 的区别，也是它单列一类的原因</h3>
 * 那个改的是<b>表单定义</b>（结构），这个写的是<b>用户提交的业务数据行</b>。
 * 两者的作用对象、风险等级、回滚方式全都不同，混成一类的话
 * 「本次将新建 1 张表单」和「本次将写入 1 条业务数据」在 headline 里就没法分开数了。
 *
 * <h3>🔴 只报字段名，绝不报字段值</h3>
 * {@code data} 里装的是真实业务数据（金额、身份证、手机号都可能在里面），
 * 而清单会被冻结进 BPM 流程变量、渲染给审批人看。
 * 审批人需要判断的是「往哪张表写、写哪几个字段」，不是「写成了什么值」——
 * 后者没有理由顺着清单流出去。理由与取舍详见 {@link FormDataArgs#fieldNames}。
 *
 * <p>⚠️ 兜底抽取器 {@link GenericToolChangeExtractor} 是<b>带</b>截断值的，
 * 那是「工具都不认识、参数契约无从查起」时的唯一信号，且它明确标了
 * {@code detail.incomplete=true}。本抽取器认识这个工具，故不需要付出这个代价。
 *
 * <h3>本刀抓的一个真实陷阱</h3>
 * {@code data} 里的 {@code {{$.x.y}}} 在运行期<b>不会被替换</b>（{@code ToolNodeExecutor#execute}
 * 只替换顶层字符串），会当成普通文字写进库。见 {@link FormDataArgs#nestedPlaceholderFields}。
 *
 * @author 走进新时代
 */
@Component
public class FormDataCreateChangeExtractor implements ToolChangeExtractor {

    @Override
    public String toolId() {
        return FormDataCreateTool.NAME;
    }

    @Override
    public ChangeItem extract(NodeDTO node, ManifestContext ctx) {
        Map<String, Object> args = ManifestArgs.of(node);
        String code = FormDataArgs.code(args);
        Map<String, Object> data = FormDataArgs.data(args);
        List<String> fields = FormDataArgs.fieldNames(data);
        List<String> subTables = FormDataArgs.subTableFields(data);
        List<String> placeholders = FormDataArgs.nestedPlaceholderFields(data);

        ChangeItem item = new ChangeItem();
        item.setKind(ChangeKind.DATA_WRITE);
        item.getTarget().put("code", code);
        item.getTarget().put("operation", "新增");

        Map<String, Object> detail = item.getDetail();
        detail.put("fields", fields);
        detail.put("fieldCount", fields.size());
        if (!subTables.isEmpty()) {
            detail.put("subTableFields", subTables);
        }
        if (!placeholders.isEmpty()) {
            detail.put("unresolvedPlaceholders", placeholders);
        }
        if (ManifestArgs.isPending(code)) {
            detail.put("codePending", Boolean.TRUE);
        }

        item.setSummary(buildSummary(code, fields, subTables, placeholders));
        return item;
    }

    private String buildSummary(String code, List<String> fields, List<String> subTables,
                                List<String> placeholders) {
        StringBuilder sb = new StringBuilder("在表单").append(FormDataArgs.codeSlot(code)).append("新增 1 条记录");
        if (fields.isEmpty()) {
            // 解析器对空 data 是直接抛异常的（resolveData），清单提前说，别让人点完同意才看见报错
            sb.append("。⚠️ 没有认出任何要写入的字段，执行时会被拒（data 缺失、为空或不是合法对象）");
            return sb.toString();
        }
        sb.append("，写入 ").append(fields.size()).append(" 个字段（")
                .append(FormDataArgs.joinLimited(fields)).append("）");
        if (!subTables.isEmpty()) {
            sb.append("，其中 ").append(subTables.size()).append(" 个是子表明细（")
                    .append(FormDataArgs.joinLimited(subTables)).append("）");
        }
        String warning = FormDataArgs.nestedPlaceholderWarning(placeholders);
        if (warning != null) {
            sb.append("。").append(warning);
        }
        return sb.toString();
    }

}
