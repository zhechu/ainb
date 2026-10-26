package cn.enlev.ainb.module.agent.framework.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 意图分类的配置
 *
 * <h3>这个分类器决定一句话走问答还是走规划</h3>
 * 助手有两条完全不同的路径：问答走流式 RAG（几秒内开始吐字、带手册出处），
 * 规划走 Agent 循环（十几二十秒、产出一张待确认的计划卡片）。
 * 入口必须分流，而分流的判据只能来自用户那句话本身。
 *
 * @author 走进新时代
 */
@ConfigurationProperties(prefix = "enlev.agent.intent")
@Validated
@Data
public class AgentIntentProperties {

    /**
     * 是否开启意图分类
     *
     * <h3>关掉之后会怎样：助手退化成纯问答</h3>
     * 这不是「省一次调用」的开关，而是<b>整条写路径的总闸</b>。
     * 关掉之后任何一句话都只会得到基于手册的回答，
     * 不会再产出任何计划卡片 —— 也就不可能走到确认与执行。
     *
     * <p>留这个开关是因为它是出事时最快的止血手段：
     * 一旦发现分类器把普通提问大量误判成建表需求，
     * 改配置重启比回滚代码快得多，而退化后的行为（P1 的纯问答）是已经验证过的。
     */
    private Boolean enabled = true;

    /**
     * 分类回复的最大 token
     *
     * <h3>为什么给 8 这么小的数</h3>
     * 期望的输出就是 {@code CHAT} 或 {@code BUILD} 一个词。给大了没有任何好处：
     * 模型会顺手写一段「根据您的描述，这属于……」的解释，
     * 既拖慢了每一条消息，又要多写一段文本去把那个词抠出来。
     *
     * <p>⚠️ 但也不能给 1~2：中文分词下一个词可能占好几个 token，
     * 截断后拿到半个词，分类就永远落到回落分支上了。
     */
    private Integer maxTokens = 8;

    /**
     * 分类使用的模型编号，留空则使用动态默认模型
     * （启用 + 密钥启用 + sort 最前，不可用时自动切换下一个候选，见 AiModelFailoverService）
     *
     * <p>分出来是为了将来能把它换成更便宜的小模型：分类不需要主模型这个级别，
     * 而它是每条消息都要跑的。现在先用动态默认，等确实有小模型可用再配。
     */
    private Long modelId;

}
