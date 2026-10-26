package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.tool.impl.datainterface.DataInterfaceInvokeTool;
import cn.enlev.ainb.module.agent.tool.impl.datainterface.DataInterfaceParamResolver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code data_interface_invoke} → {@link ChangeKind#DATA_INTERFACE}
 *
 * <h3>为什么方案里没有它、却必须有它</h3>
 * 方案的抽取器清单（§3-L2 文件树）只列到 {@code FormDataChangeExtractor} 为止，
 * {@code data_interface_invoke} 会落到 {@link GenericToolChangeExtractor} 兜底。
 * 兜底保证「不静默少一块」，但说不出<b>在调哪个数据接口</b> —— 而这是一个 HIGH 风险的
 * 全能力写工具（INSERT/UPDATE/DELETE、HTTP POST 都可能）。
 * 审批人看见「调用工具（参数略）」与看见「调用数据接口 employee_batch_update」，
 * 判断力完全不同。故本刀补上，是方案之外新增的第 11 个抽取器。
 *
 * <h3>🔴 它说不清副作用，而且必须承认自己说不清</h3>
 * 接口到底读还是写、写哪张表、影响多少行，全在 infra 侧的接口实现里，DSL 里只有 {@code code}。
 * 于是这里既<b>不</b>假装知道（绝不写「只读」「安全」这类词），也<b>不</b>装作什么都不知道：
 * {@code detail.effectUnknown=true} 明说「清单判断不了这个接口的副作用」，
 * 让人在点同意前知道这一项是黑盒。
 *
 * <p>把黑盒变透明要查库（{@code DataInterfaceApi} 能给出接口的只读标记与入参定义，是只读查询），
 * 归 S2-d 的干跑器。抽取器只报 DSL 里写了什么，这条边界不破。
 *
 * <h3>为什么这一项<b>带</b>参数值预览，与其余专用抽取器不同</h3>
 * 其余十项都知道自己作用对象的契约（表结构、表单字段），故只报字段名、不报值 ——
 * 见 {@link FormDataArgs#fieldNames} 的隐私取舍。本项不知道：{@code params} 的键名与语义
 * 由接口自己定义，不查库无从判断哪个键是业务数据、哪个是范围限定。
 * 而「传了什么」常常是唯一能看出量级的信号（{@code params.ids} 是一个 id 还是一万个 id，
 * 后果差四个数量级）。故沿用兜底那份截断预览（{@link ManifestArgs#preview}，
 * 200 字/值、20 个键），与兜底<b>同一口径</b>，不会两处对同一个值截出不同长度。
 *
 * <p>⚠️ 因此它同时是「清单里可能带业务数据」的两个来源之一（另一个是兜底）。
 * 清单要不要在冻结进 BPM 流程变量时剥掉 {@code argsPreview}，是 S6 的决策项。
 *
 * @author 走进新时代
 */
@Component
public class DataInterfaceInvokeChangeExtractor implements ToolChangeExtractor {

    @Override
    public String toolId() {
        return DataInterfaceInvokeTool.NAME;
    }

    @Override
    public ChangeItem extract(NodeDTO node, ManifestContext ctx) {
        Map<String, Object> args = ManifestArgs.of(node);
        String code = ManifestArgs.str(args, DataInterfaceParamResolver.PARAM_CODE);
        // 与 DataInterfaceParamResolver#resolveArgs 同样容忍「对象」与「JSON 串」两种形态，
        // 但不抛：认不出来就当无参（见 ManifestArgs#map）
        Map<String, Object> params = ManifestArgs.map(args, DataInterfaceParamResolver.PARAM_PARAMS);
        List<String> paramKeys = new ArrayList<>(params.keySet());

        ChangeItem item = new ChangeItem();
        item.setKind(ChangeKind.DATA_INTERFACE);
        item.getTarget().put("code", code);

        Map<String, Object> detail = item.getDetail();
        detail.put("paramKeys", paramKeys);
        detail.put("argsPreview", ManifestArgs.preview(params));
        detail.put("effectUnknown", Boolean.TRUE);
        detail.put("effectNote", "这是全能力执行（可能写库、可能调外部 HTTP）。"
                + "接口具体改什么由 infra 侧的接口实现决定，清单判断不了，请谨慎确认");
        if (ManifestArgs.isPending(code)) {
            detail.put("codePending", Boolean.TRUE);
        }

        // code 缺失时工具直接拒（DataInterfaceParamResolver#resolveCode），此时谈副作用没有意义 ——
        // 与 ProcessDeployChangeExtractor「没绑表单就早退」同理：必死的那一项说清楚就够了
        item.setSummary(code == null
                ? "调用数据接口（编码未填）。⚠️ code 缺失，执行时会被直接拒绝"
                : "调用数据接口 " + code
                        + (paramKeys.isEmpty() ? "，无入参"
                        : "，传 " + paramKeys.size() + " 个入参（" + FormDataArgs.joinLimited(paramKeys) + "）")
                        + "。⚠️ 全能力执行，可能产生写副作用，清单判断不了它到底改什么");
        return item;
    }

}
