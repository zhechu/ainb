package cn.enlev.ainb.module.agent.framework.agent.core.tool;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 工具键打错时的「相近候选」推荐
 *
 * <h3>为什么抽出来，而不是让两个消费方各写一遍</h3>
 * 同一个错键会被两处消费：变更清单把它记进 {@code unresolved[]}（{@code WorkflowChangeManifestBuilder}），
 * 校验器把它报成 V1 的 error 并回灌给规划期的 LLM 自修正（{@code WorkflowDslValidator}）。
 * 两处的文案都靠这份候选，若各写一遍，阈值调了一边没调另一边，就会出现
 * 「清单说相近的有 A、体检说相近的有 B」——与 D7「节点 schema 四处副本」同病。
 *
 * <h3>为什么候选值得给</h3>
 * DSL 是 LLM 产的，工具键打错（{@code datamodel_lst}）是「工具认不出」的头号成因。
 * 只说「不存在」模型会换个别的错法，说「相近的有 datamodel_list」它一轮就能改对 ——
 * 而每一轮都是一次真实的模型调用、一次真实的计费。口径同 {@code AgentValidateResult}
 * 类注释的「说清怎么改，而不只是说清哪里错」。
 *
 * @author 走进新时代
 */
public final class ToolKeySuggester {

    /** 相似度阈值：低于此值不算「相近」，否则会推出一堆无关工具把真正的候选挤掉 */
    private static final double SIMILAR_THRESHOLD = 0.5D;

    /** 最多推几条：文案要塞进一句错误消息里，推多了模型反而不知道该抄哪个 */
    private static final int SIMILAR_LIMIT = 2;

    private ToolKeySuggester() {
    }

    /**
     * 从已知工具键里挑出与 {@code toolId} 最相近的若干个
     *
     * @param toolId   认不出的工具键（调用方保证非空白）
     * @param knownIds 已知工具键全集（内置注册表 + 当前租户启用中的自定义工具）
     * @return 按相似度降序的候选，最多 2 条；没有相近的则为空列表
     */
    public static List<String> suggest(String toolId, Collection<String> knownIds) {
        List<String> hits = new ArrayList<>();
        for (String name : knownIds) {
            if (StrUtil.similar(toolId, name) >= SIMILAR_THRESHOLD) {
                hits.add(name);
            }
        }
        hits.sort((a, b) -> Double.compare(StrUtil.similar(toolId, b), StrUtil.similar(toolId, a)));
        return hits.size() <= SIMILAR_LIMIT ? hits : hits.subList(0, SIMILAR_LIMIT);
    }

}
