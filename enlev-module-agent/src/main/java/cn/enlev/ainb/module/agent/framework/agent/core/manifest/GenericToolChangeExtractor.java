package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import org.springframework.stereotype.Component;

/**
 * 兜底抽取器：工具认得、但没有专用抽取器时，至少把「调了谁、传了什么」摆出来
 *
 * <h3>🔴 它<b>不</b>实现 {@link ToolChangeExtractor}，这是刻意的</h3>
 * 那个接口的契约是「一个实现对应一个 toolId」，注册表据此建 toolId → 实现的映射。
 * 兜底没有 toolId（它对应<b>所有</b>没被认领的工具），硬塞进接口就得让 {@code toolId()} 返回 null
 * 或某个哨兵值，然后注册表再写一条「跳过哨兵」的特例 —— 那是把一个类型层面的区别
 * 降级成一个运行期的 if，且新增抽取器时没人会想起这条特例。
 * 所以：{@link WorkflowChangeManifestBuilder} 直接注入本类，注册表里永远只有真按键分派的实现。
 *
 * <h3>为什么必须有兜底</h3>
 * 方案原话：「新增工具时 manifest 自动有产出，不会因为漏注册抽取器就静默少一块」。
 * 漏配抽取器的表现如果是「清单里少一项」，那这项变更<b>照样会在运行期执行</b> ——
 * 用户点了一次同意，却不知道同意了什么。这正是本模块要消灭的缺陷类。
 *
 * <h3>参数原样展示，不做任何「看起来更友好」的加工</h3>
 * {@code argsPreview} 里的 {@code {{$.x.y}}} 就是 DSL 里的原文。不解析是对的：
 * 清单在图跑之前就生成，那时占位符没有值。而且原样展示顺带暴露了一个真实陷阱 ——
 * {@code ToolNodeExecutor#execute} 在解析入参时只替换<b>顶层字符串</b>，
 * 嵌在 Map/List 里的占位符会原样传给工具。用户在清单里看见字面量 {@code {{$.x}}}，
 * 比看见一个「看起来已经填好」的值更安全。（这条陷阱的系统性处理在 S2-e 的 {@code untrustedInputs}。）
 *
 * <p>渲染与截断规则见 {@link ManifestArgs#preview}：兜底与 {@code data_interface_invoke}
 * 的抽取器共用那一份，两处对同一个值的截断长度必然一致。
 *
 * @author 走进新时代
 */
@Component
public class GenericToolChangeExtractor {

    /**
     * 产出兜底变更项
     *
     * <p>不设 {@code summary}：留空由 builder 统一回落（见 {@link WorkflowChangeManifestBuilder}），
     * 那句话需要工具展示名，而展示名的唯一权威是工具注册表，不该传进抽取器再传出来。
     *
     * @param node DSL 节点，type 必为 {@code tool}
     * @param ctx  上下文。兜底目前用不到它，但签名与 {@link ToolChangeExtractor#extract} 对齐，
     *             将来某个工具的兜底需要看全局时不必改调用点
     */
    public ChangeItem extract(NodeDTO node, ManifestContext ctx) {
        ChangeItem item = new ChangeItem();
        item.setKind(ChangeKind.GENERIC_TOOL);
        item.getDetail().put("argsPreview", ManifestArgs.preview(ManifestArgs.of(node)));
        item.getDetail().put("incomplete", Boolean.TRUE);
        return item;
    }

}
