package cn.enlev.ainb.module.agent.framework.agent.core.validate;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * 生成后校验的结果（方案 §5.5）
 *
 * <h3>为什么是「收集全部错误」而不是「遇到第一个就抛」</h3>
 * 这个结果要回灌给 LLM 自修正。一次只告诉它一个错误，它改完再撞下一个，
 * 三轮上限很快就烧完了 —— 而每一轮都是一次真实的模型调用、一次真实的计费。
 * 一次性把问题全列出来，它一轮就能改对。
 *
 * <h3>错误信息是写给 LLM 看的，不是写给人看的</h3>
 * 所以每条都要<b>说清楚怎么改</b>，而不只是说哪里错了。
 * 「表名必须以 biz_ 开头」不如「表名 order_info 不合法，应改为 biz_order_info」——
 * 后者模型照做就对了，前者它可能改成 biz_order 或 bizOrderInfo。
 *
 * @author 走进新时代
 */
@Getter
public class AgentValidateResult {

    private final List<String> errors = new ArrayList<>();

    /**
     * 警告：不阻断，但要让用户在 Plan 卡片上看到
     *
     * 典型是 §5.5 那 8 条「静默改写」—— 后端不报错、保存成功、
     * 但建出来的不是你要的。既然后端不拦，就只能在这里说出来。
     */
    private final List<String> warnings = new ArrayList<>();

    public void addError(String format, Object... args) {
        errors.add(args.length == 0 ? format : String.format(format, args));
    }

    public void addWarning(String format, Object... args) {
        warnings.add(args.length == 0 ? format : String.format(format, args));
    }

    public boolean isPassed() {
        return errors.isEmpty();
    }

    /**
     * 拼成给 LLM 的反馈文本
     */
    public String toFeedback() {
        StringBuilder sb = new StringBuilder();
        if (!errors.isEmpty()) {
            sb.append("以下问题必须修正：\n");
            for (int i = 0; i < errors.size(); i++) {
                sb.append(i + 1).append(". ").append(errors.get(i)).append('\n');
            }
        }
        if (!warnings.isEmpty()) {
            sb.append("以下写法会被后端静默改写，建议一并调整：\n");
            for (String warning : warnings) {
                sb.append("- ").append(warning).append('\n');
            }
        }
        return sb.toString();
    }

}
