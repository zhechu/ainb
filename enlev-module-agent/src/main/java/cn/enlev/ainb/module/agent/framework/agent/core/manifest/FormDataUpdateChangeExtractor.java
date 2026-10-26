package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.tool.impl.formdata.FormDataUpdateTool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@code form_data_update} → {@link ChangeKind#DATA_WRITE}
 *
 * <h3>为什么 PATCH 语义必须在清单里说出来</h3>
 * 工具契约原话：「只改 data 里给的字段，没给的保持原值不变」（{@link FormDataUpdateTool} 的
 * {@code description} 与 {@code FormDataParamResolver.UPDATE_SCHEMA}）。
 * 审批人看见「修改 3 个字段」时，脑子里的默认假设往往是「这行被整行覆盖成这 3 个字段」——
 * 那会让他以为其余字段要被清空，从而拒掉一个本来无害的改动。
 * 反过来，若它真是全量覆盖而清单不说，审批人就会同意掉一次数据丢失。
 * 两种误判都致命，所以这句话不能省。
 *
 * <h3>主键可能是「本次流程里刚建出来的那条」</h3>
 * 典型 DSL 是 create 节点之后接 update 节点，pk 写成 {@code {{$.createOut.id}}}。
 * 这时清单说的是「运行期才确定」而不是某个具体编号 ——
 * 审批人要能看出改的是<b>本流程自己刚写入的行</b>，而不是库里某条既有数据。
 * 见 {@link FormDataArgs#describePk}。
 *
 * <p>其余取舍（只报字段名不报值、嵌套占位符陷阱）同 {@link FormDataCreateChangeExtractor}。
 *
 * @author 走进新时代
 */
@Component
public class FormDataUpdateChangeExtractor implements ToolChangeExtractor {

    @Override
    public String toolId() {
        return FormDataUpdateTool.NAME;
    }

    @Override
    public ChangeItem extract(NodeDTO node, ManifestContext ctx) {
        Map<String, Object> args = ManifestArgs.of(node);
        String code = FormDataArgs.code(args);
        String pk = FormDataArgs.pk(args);
        Map<String, Object> data = FormDataArgs.data(args);
        List<String> fields = FormDataArgs.fieldNames(data);
        List<String> placeholders = FormDataArgs.nestedPlaceholderFields(data);

        ChangeItem item = new ChangeItem();
        item.setKind(ChangeKind.DATA_WRITE);
        item.getTarget().put("code", code);
        item.getTarget().put("operation", "修改");
        item.getTarget().put("pk", pk);

        Map<String, Object> detail = item.getDetail();
        detail.put("fields", fields);
        detail.put("fieldCount", fields.size());
        detail.put("patchNote", "只改上面列出的字段，未提到的字段保持原值不变（不会被清空）");
        if (!placeholders.isEmpty()) {
            detail.put("unresolvedPlaceholders", placeholders);
        }
        if (ManifestArgs.isPending(code)) {
            detail.put("codePending", Boolean.TRUE);
        }
        if (ManifestArgs.isPending(pk)) {
            detail.put("pkPending", Boolean.TRUE);
        }

        item.setSummary(buildSummary(code, pk, fields, placeholders));
        return item;
    }

    private String buildSummary(String code, String pk, List<String> fields, List<String> placeholders) {
        StringBuilder sb = new StringBuilder("修改表单").append(FormDataArgs.codeSlot(code)).append("中");
        String pkText = FormDataArgs.describePk(pk);
        sb.append(pkText == null ? "一条记录" : "记录 " + pkText);
        if (fields.isEmpty()) {
            // 解析器对「缺 pk」和「空 data」都是直接抛异常的，清单提前说，别让人点完同意才看见报错
            sb.append("。⚠️ 没有认出任何要改的字段，执行时会被拒（data 缺失、为空或不是合法对象）");
            if (pkText == null) {
                sb.append("；主键也没填");
            }
            return sb.toString();
        }
        sb.append("，改动 ").append(fields.size()).append(" 个字段（")
                .append(FormDataArgs.joinLimited(fields)).append("）；未提到的字段保持原值");
        if (pkText == null) {
            sb.append("。⚠️ 主键未填，执行时会被拒");
        }
        String warning = FormDataArgs.nestedPlaceholderWarning(placeholders);
        if (warning != null) {
            sb.append("。").append(warning);
        }
        return sb.toString();
    }

}
