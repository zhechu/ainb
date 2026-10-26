package cn.enlev.ainb.module.ai.api.model.dto;

import lombok.Data;

import java.util.List;

/**
 * AI 对话请求 DTO
 *
 * @author 走进新时代
 */
@Data
public class AiChatReqDTO {

    /**
     * 模型编号。为空时取该类型的默认模型
     *
     * <p>🔴 传了 {@link #taskKey} 时，粘滞是按「taskKey + 本字段」两维分档的：
     * <b>同一个 taskKey、同一个 modelId</b> 的后续调用一律沿用首次成功的那个模型，改写不了；
     * 而同一 taskKey 下<b>不同的</b> modelId 各自独立 —— 一次流程执行里两个节点各配一个模型是
     * 用户的显式选择，不该被别的节点钉住的模型顶掉（{@code AiModelFailoverService} 类注释有完整理由）。
     */
    private Long modelId;

    /**
     * 系统提示词
     */
    private String systemPrompt;

    /**
     * 对话消息，按时间顺序
     */
    private List<Message> messages;

    /**
     * 温度，为空用模型自身配置
     */
    private Double temperature;

    /**
     * 最大回复 token 数，为空用模型自身配置
     */
    private Integer maxTokens;

    /**
     * 是否开启思考模式（通义专属，其余平台忽略），为空 = 不表态、由 ai 模块按端点分档
     *
     * <p>「不表态」<b>不等于</b>「不发这个字段」：{@code DashScopeChatOptions} 把
     * {@code enableThinking} 的字段初值就赋成了 {@code false}，所以最终请求里一定有值。
     * 不表态时的档位是「多模态端点开、文本端点关」，理由见
     * {@code AiUtils#resolveTongYiThinking}。
     *
     * <p>🔴 显式传 {@code false} 之前要想清楚代价：思考模式下 DashScope 的 {@code max_tokens}
     * 只约束回复、思考另有 budget，回传的 {@code completion_tokens = 思考 + 回复}。
     * 实测思考字数可达正文的 100~4500 倍，把超时预算吃光后表现为「仍未答完」，
     * 看着像功能缺陷。agent 的 CHAT / INTENT / PLAN 三条路径因此显式关掉。
     */
    private Boolean enableThinking;

    /**
     * 任务键：同一「任务」的多次模型调用传同一个值，任务内只用同一个模型
     *
     * <p>为空 = 不做任务级粘滞，行为与今天完全一致（每次调用独立选模型、失败自动切换）。
     *
     * <p>为什么需要它：一次流程执行 / 一次规划会打多轮模型，如果每轮都独立走候选链，
     * 中途某个模型额度没了就会<b>静默换成另一个模型继续</b> —— 前后半程不是同一个模型，
     * 这轮的效果既没法归因、也没法和上一轮比。口径是「同一个任务只能用同一个模型，
     * 额度没了就算这次失败，换模型只发生在任务边界」。
     *
     * <p>🔴 「同一个模型」是按 <b>taskKey + {@link #modelId}</b> 分档讲的：同一任务里
     * 显式配了不同 modelId 的调用各钉各的，互不覆盖。详见 {@link #modelId}。
     *
     * <p>🔴 粘滞是<b>进程内</b>的，不入库、不走 Redis：它只解决「一次任务内别换」，
     * 不承担跨实例一致性。多实例部署下同一 taskKey 落到不同实例会各钉各的 —— 这是可接受的，
     * 因为「换模型」的代价是效果不可比，不是数据错乱。
     */
    private String taskKey;

    @Data
    public static class Message {

        /**
         * 角色：user / assistant
         *
         * system 角色请用 {@link #systemPrompt}，不要混在这里 ——
         * 多条 system 消息在不同厂商上的行为并不一致
         */
        private String role;

        /**
         * 内容
         */
        private String content;

        public static Message of(String role, String content) {
            Message message = new Message();
            message.setRole(role);
            message.setContent(content);
            return message;
        }
    }

}
