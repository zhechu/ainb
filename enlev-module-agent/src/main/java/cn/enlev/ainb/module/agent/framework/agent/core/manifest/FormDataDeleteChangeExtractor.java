package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.tool.impl.formdata.FormDataDeleteTool;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@code form_data_delete} → {@link ChangeKind#DATA_WRITE}
 *
 * <h3>「删除」这个词必须马上跟着「逻辑删除，可恢复」</h3>
 * 工具契约原话：「逻辑删除，数据仍在库里、标记为已删除，可恢复」
 * （{@link FormDataDeleteTool} 的 {@code description} 与 {@code FormDataParamResolver.DELETE_SCHEMA}）。
 * 这是 HIGH 风险工具，审批人看到「删除记录 1024」的第一反应是「删了就没了」，
 * 于是拒掉一个其实可恢复的操作；反过来，若哪天它改成了物理删除而清单还写着「可恢复」，
 * 那就是让人同意掉一次不可逆的数据丢失。所以这句话必须与工具契约同源、同改。
 *
 * <p>{@code reversible} 字段本身由 builder 按风险等级推导（见 {@link ChangeItem}），
 * 这里说的是一句<b>给人读的解释</b>，不是第二份判定。
 *
 * <h3>🔴 刻意不说「会连带删掉子表明细」</h3>
 * 它确实会（{@code FormDesignRuntimeServiceImpl#delete} 按「子表 → 副表 → 主表」的顺序删）。
 * 不在这里说的理由不是隐瞒，而是<b>说一句泛泛的话远不如报一个确切的数</b>：
 * 「会连带删子表」对审批人几乎没有信息量（哪张子表？多少行？），
 * 而「这条记录挂着 30 行采购明细会一并删除」才是能据以判断的话 ——
 * 后者要查库，属于 S2-d 干跑器的活（只读查询即可）。
 * 抽取器只报 DSL 里写了什么，这条边界不破。
 *
 * @author 走进新时代
 */
@Component
public class FormDataDeleteChangeExtractor implements ToolChangeExtractor {

    @Override
    public String toolId() {
        return FormDataDeleteTool.NAME;
    }

    @Override
    public ChangeItem extract(NodeDTO node, ManifestContext ctx) {
        Map<String, Object> args = ManifestArgs.of(node);
        String code = FormDataArgs.code(args);
        String pk = FormDataArgs.pk(args);

        ChangeItem item = new ChangeItem();
        item.setKind(ChangeKind.DATA_WRITE);
        item.getTarget().put("code", code);
        item.getTarget().put("operation", "删除");
        item.getTarget().put("pk", pk);

        Map<String, Object> detail = item.getDetail();
        detail.put("deleteNote", "逻辑删除：数据仍在库里、标记为已删除，可恢复");
        if (ManifestArgs.isPending(code)) {
            detail.put("codePending", Boolean.TRUE);
        }
        if (ManifestArgs.isPending(pk)) {
            detail.put("pkPending", Boolean.TRUE);
        }

        String pkText = FormDataArgs.describePk(pk);
        StringBuilder sb = new StringBuilder("删除表单").append(FormDataArgs.codeSlot(code)).append("中");
        sb.append(pkText == null ? "一条记录" : "记录 " + pkText);
        sb.append(pkText == null
                ? "。⚠️ 主键未填，执行时会被拒"
                // 用逗号而不是括号收尾：主键是占位符时 describePk 已经带了一对括号，
                // 再接一对会读成「记录 {{$.x}}（运行期才确定）（逻辑删除…）」
                : "，逻辑删除、数据仍在库里、可恢复");
        item.setSummary(sb.toString());
        return item;
    }

}
