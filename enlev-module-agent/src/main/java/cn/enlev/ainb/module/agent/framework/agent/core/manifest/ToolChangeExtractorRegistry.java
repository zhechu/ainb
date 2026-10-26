package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 变更抽取器注册表：toolId → 抽取器
 *
 * <p>照 {@code NodeExecutorRegistry} 的套路：Spring 注入 {@code List<ToolChangeExtractor>} 建 Map，
 * 新增一种工具只要加一个 bean，注册表自动收录 —— 少一处「加了抽取器忘了注册」的静默缺口。
 *
 * <h3>🔴 与 {@code NodeExecutorRegistry} 的两处刻意不同</h3>
 * <ol>
 *   <li><b>空 toolId 直接启动失败</b>，不是 warn。注册表按 toolId 查，空键的实现永远查不到 ——
 *       它会安静地躺在那里，表现为「这个工具的变更在清单里消失了」。warn 会被刷过去，
 *       启动失败不会。{@code NodeExecutorRegistry} 那里不存在这种情况（{@code getType()} 是
 *       执行器分派的唯一依据，写空的执行器连编译期都过不了心智关），这里却是新增 bean 时
 *       最容易顺手留空的一个返回值。</li>
 *   <li><b>查不到不抛异常，返回 null</b>。节点执行器缺失是「DSL 引用了引擎没实现的类型」，
 *       属于必须拦下的部署错误；而抽取器缺失只是「这项变更解读得粗一些」，
 *       由调用方退回 {@link GenericToolChangeExtractor} 兜底。清单是诊断物，
 *       它自己绝不能成为新的失败点。</li>
 * </ol>
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class ToolChangeExtractorRegistry {

    private final Map<String, ToolChangeExtractor> extractors = new HashMap<>();

    @Resource
    public void setExtractors(List<ToolChangeExtractor> extractorList) {
        for (ToolChangeExtractor extractor : extractorList) {
            String toolId = extractor.toolId();
            if (StrUtil.isBlank(toolId)) {
                // 见类注释：空键的抽取器不可达，等于这项变更从清单里静默消失，必须在启动时炸出来
                throw new IllegalStateException(StrUtil.format(
                        "变更抽取器 {} 的 toolId() 返回空白，它将永远无法被分派到；"
                                + "请返回与 @AgentTool(name=...) 逐字一致的工具键",
                        extractor.getClass().getName()));
            }
            ToolChangeExtractor previous = extractors.put(toolId, extractor);
            if (previous != null) {
                // 两个 bean 认领同一个工具是编码错误。这里 warn 而不抛：兜底仍在，
                // 用户看到的只是解读口径变成后注册那个 —— 但必须留痕，否则两处解读不一致查不出原因
                log.warn("[setExtractors][工具({}) 的变更抽取器被重复注册：{} 覆盖 {}]",
                        toolId, extractor.getClass().getSimpleName(), previous.getClass().getSimpleName());
            }
        }
        log.info("[setExtractors][变更抽取器注册完成，共 {} 个：{}]", extractors.size(), extractors.keySet());
    }

    /**
     * 按工具键取抽取器，没有则返回 null（调用方退回兜底）
     */
    public ToolChangeExtractor get(String toolId) {
        return toolId == null ? null : extractors.get(toolId);
    }

    public boolean supports(String toolId) {
        return toolId != null && extractors.containsKey(toolId);
    }

}
