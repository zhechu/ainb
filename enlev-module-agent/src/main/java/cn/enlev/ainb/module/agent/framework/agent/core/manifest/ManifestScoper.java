package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.GraphReachability;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ChangeManifest.ScopeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 按 hitl 节点把清单裁成「从此节点往后」的子图（方案 §3-L2 的 {@code ManifestScoper}）
 *
 * <h3>为什么要有这一刀</h3>
 * 审批人要回答的问题是「我点了同意<b>之后</b>会发生什么」，而不是「这条流程总共会做什么」。
 * 建表节点排在审批节点之前时，把整份清单摊给他有两个坏处：
 * 一是那张表<b>已经建好了</b>，标着红色的「不可逆」只会让他以为自己要为它负责；
 * 二是真正等他放行的那一两项被淹在十几项里 —— 而 Req 3 的整个立论是
 * 「看不懂的确认等于没有确认」，信息过多与过少同样让人看不懂。
 *
 * <h3>🔴 裁剪必须显式记账，不许静默</h3>
 * 这是本类唯一不容妥协的一条。裁掉两项变更之后，headline 会从「将新建 3 张表」变成
 * 「将新建 1 张表」，而这句话在界面上<b>看不出是被裁过的</b>。审批人据此点同意，
 * 他同意的其实是一份他没看见全貌的东西 —— 与 {@code NOT_PROBED} 塌成 {@code PASSED}
 * 是同一类撒谎，且方向相同（都是给了不该给的信心）。
 * 故裁了就必须在 {@link ScopeInfo} 里留下被裁掉的数量与一句人话，
 * 由渲染层印在 headline 上方。
 *
 * <h3>🔴 认不出起算点时，宁可多报也不裁</h3>
 * {@code fromNodeId} 在这份 DSL 里找不到对应节点时（调用方传错、DSL 刚被改过、
 * 或者传的是 {@code START}/{@code END} 这种保留端点），本类<b>不做任何裁剪</b>，
 * 只在 {@link ScopeInfo#getNote()} 里说明原因。
 * <p>反向的做法（当成空子图 ⟹ 裁到一项不剩）会让 headline 变成
 * 「本次不产生任何写操作」—— 那是一份看起来最安全的清单，也是最危险的：
 * {@link GraphReachability#reachableFrom(String)} 无条件把起点塞进结果集，
 * 它不校验起点是不是真节点，所以一个打错的 id 会稳稳地裁出一张空清单。
 *
 * <h3>为什么在干跑<b>之后</b>才裁</h3>
 * 干跑器有一项判定是跨变更项的：{@code tablesCreatedHere} 要先看完整份清单，
 * 才知道「本流程自己会不会把这张表建出来」（见 {@code ChangeManifestDryRunner} 的同名方法）。
 * 先裁再跑，上游那张建表节点就不在视野里了，于是一个下游的建表单节点会被判成
 * 「主表不存在，会被拒」—— 冤枉的恰恰是 {@code 建表 → 审批 → 建表单} 这种最正常的编排。
 * 所以顺序是<b>先按整份 DSL 干跑，再裁给人看</b>：裁剪改变的是「展示哪几项」，
 * 不是「探测时允许知道哪些事实」。
 *
 * <h3>🔴 溯源条目按<b>汇</b>裁，不按源</h3>
 * {@code untrustedInputs[]} 也一起裁，判据是 {@code sinkNodeId}，同样记账（{@code hiddenUntrusted}）。
 * 只按汇裁是刻意的：审批人被叫来是为了放行「这一步之后」的动作，汇在子图外就是「那件事不由他决定」；
 * 而源在上游恰恰是他该知道的上下文 ——「表名来自请求体」这句话，无论那张表是在他之前建的
 * 还是之后建的，都值得他看见。
 * <p>漏掉这一条的代价与漏掉 {@link ScopeInfo#getHiddenChanges()} 同形，只是更难察觉：
 * 「没有外部输入流进这一步」与「有，但流进的节点在你之前」在界面上是同一句话。
 *
 * <h3>与 {@link GraphReachability} 的关系</h3>
 * 本类不含任何遍历逻辑，可达性一律走 {@code graph.reachableFrom(nodeId)} ——
 * 那是全模块唯一一份、与运行期编译器同口径的实现（条件边只看 cases/default，不看多余的 {@code to}）。
 * 方案里那句「L2 的 {@code ManifestScoper} 也要同一套，别写两遍」说的就是这件事。
 *
 * @author 走进新时代
 */
public final class ManifestScoper {

    private ManifestScoper() {
    }

    /**
     * 就地裁剪 {@code manifest} 的四个列表与 {@code readToolNodeIds}
     *
     * <p>⚠️ {@code readToolNodeIds} 是<b>调用方的局部状态</b>（只读工具不进 {@code changes}，
     * 只在 {@code stats.readToolCount} 上留个数），本方法拿不到它就没法把它一起裁，
     * 于是那个数会仍旧是全流程的 —— 而它存在的意义正是「证明扫过了」，
     * 对着一份裁过的清单说「扫过 12 个只读工具」是句查不出原因的大话。
     * 故由调用方交进来一起裁，就地改。
     *
     * @param manifest       已遍历完、已干跑完、已溯源完的清单；就地改
     * @param ctx            本次构建的上下文，用它的 {@code graph} 与 {@code nodeById}
     * @param readToolNodeIds 只读工具节点的 id，顺序不限；就地裁
     * @param fromNodeId     起算节点；空白表示不裁剪
     * @return 裁剪记账，直接挂到 {@code manifest.scope} 上；{@code fromNodeId} 空白时返回
     *         {@code null}（表示这份清单覆盖整条流程，此时上面那几个列表一字未动）
     */
    public static ScopeInfo scope(ChangeManifest manifest, ManifestContext ctx,
                                  List<String> readToolNodeIds, String fromNodeId) {
        if (StrUtil.isBlank(fromNodeId)) {
            return null;
        }
        ScopeInfo scope = new ScopeInfo();
        scope.setFromNodeId(fromNodeId);

        NodeDTO from = ctx == null ? null : ctx.node(fromNodeId);
        if (from == null) {
            // 🔴 fail-safe：认不出起算点就一项都不裁，理由见类注释
            scope.setFromNodeName(fromNodeId);
            scope.setPruned(false);
            scope.setNote("没能按「" + fromNodeId + "」裁剪：这份 DSL 里没有 id 为它的节点。"
                    + "为免漏报，本清单仍覆盖整条流程");
            return scope;
        }
        scope.setFromNodeName(StrUtil.blankToDefault(from.getName(), fromNodeId));

        Set<String> kept = ctx.graph().reachableFrom(fromNodeId);
        int beforeChanges = manifest.getChanges().size();
        int beforeUnresolved = manifest.getUnresolved().size();
        int beforeApprovals = manifest.getApprovals().size();
        int beforeUntrusted = manifest.getUntrustedInputs().size();
        int beforeReadTools = readToolNodeIds == null ? 0 : readToolNodeIds.size();
        int beforeWaits = manifest.getWaits().size();
        // 六个列表用同一条判据。nodeId 为空的项一律留下：它在图上没有位置，
        // 于是既不能说它在子图内、也不能说它在子图外 —— 而「说不清就不许悄悄丢掉」
        manifest.getChanges().removeIf(item -> !keeps(item.getNodeId(), kept));
        manifest.getUnresolved().removeIf(item -> !keeps(item.getNodeId(), kept));
        manifest.getApprovals().removeIf(item -> !keeps(item.getNodeId(), kept));
        manifest.getWaits().removeIf(item -> !keeps(item.getNodeId(), kept));
        // 溯源条目用 sinkNodeId 走同一条 keeps，理由见类注释「按汇裁，不按源」。
        // 实际上它的 sinkNodeId 恒非空（UntrustedInputTracer#indexOf 跳过了没 id 的节点），
        // 但仍走 keeps 而不是自己写一遍 contains —— 「四个列表一条判据」是结构性的，不靠四处写得一像
        manifest.getUntrustedInputs().removeIf(item -> !keeps(item.getSinkNodeId(), kept));
        if (readToolNodeIds != null) {
            readToolNodeIds.removeIf(id -> !keeps(id, kept));
        }

        scope.setHiddenChanges(beforeChanges - manifest.getChanges().size());
        scope.setHiddenUnresolved(beforeUnresolved - manifest.getUnresolved().size());
        scope.setHiddenApprovals(beforeApprovals - manifest.getApprovals().size());
        scope.setHiddenUntrusted(beforeUntrusted - manifest.getUntrustedInputs().size());
        scope.setHiddenReadTools(beforeReadTools - (readToolNodeIds == null ? 0 : readToolNodeIds.size()));
        scope.setHiddenWaits(beforeWaits - manifest.getWaits().size());
        scope.setPruned(scope.getHiddenChanges() + scope.getHiddenUnresolved()
                + scope.getHiddenApprovals() + scope.getHiddenReadTools()
                + scope.getHiddenUntrusted() + scope.getHiddenWaits() > 0);
        scope.setNote(buildNote(scope));
        return scope;
    }

    /**
     * 这一项留不留。{@code nodeId} 空白时留（无处可判，故不许悄悄丢）
     */
    private static boolean keeps(String nodeId, Set<String> kept) {
        return StrUtil.isBlank(nodeId) || kept.contains(nodeId);
    }

    /**
     * 一句人话，说清「覆盖哪一段、漏了多少、去哪看全的」
     *
     * <p>措辞遵循本模块的留白规则（{@code SummarySpacingTest}）：ASCII token 两侧留空格，
     * 全角「」（）前不留。
     */
    private static String buildNote(ScopeInfo scope) {
        List<String> hidden = new ArrayList<>();
        if (scope.getHiddenChanges() > 0) {
            hidden.add(scope.getHiddenChanges() + " 项变更");
        }
        if (scope.getHiddenUntrusted() > 0) {
            hidden.add(scope.getHiddenUntrusted() + " 处外部输入的流向");
        }
        if (scope.getHiddenUnresolved() > 0) {
            hidden.add(scope.getHiddenUnresolved() + " 处说不清的部分");
        }
        if (scope.getHiddenApprovals() > 0) {
            hidden.add(scope.getHiddenApprovals() + " 个其他确认点");
        }
        if (scope.getHiddenWaits() > 0) {
            hidden.add(scope.getHiddenWaits() + " 个外部事件等待点");
        }
        if (scope.getHiddenReadTools() > 0) {
            hidden.add(scope.getHiddenReadTools() + " 个只读工具");
        }
        StringBuilder note = new StringBuilder("本清单只覆盖「").append(scope.getFromNodeName())
                .append("」及之后的动作");
        if (hidden.isEmpty()) {
            note.append("；它之前没有任何动作");
        } else {
            note.append("；此前已发生的 ").append(String.join("、", hidden)).append("没有列出");
        }
        return note.append("。要看整条流程的全部影响面，请不带 fromNodeId 重新生成").toString();
    }

}
