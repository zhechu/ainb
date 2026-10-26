package cn.enlev.ainb.module.agent.framework.agent.core.chat;

import cn.hutool.core.util.StrUtil;

/**
 * token 估算
 *
 * <h3>用在两个地方，口径必须一致</h3>
 * <ol>
 *   <li><b>历史截断</b>：按 token 预算裁掉过老的轮次（方案 §7）</li>
 *   <li><b>计量兜底</b>：厂商没回传 usage 时估一个值，
 *       记为 {@code token_source = ESTIMATED}（方案 §8.6）</li>
 * </ol>
 * 两处用同一份实现，否则「按 A 口径截断、按 B 口径计费」，
 * 用户会看到「明明没说几句话却扣了很多」而无法解释。
 *
 * <h3>🔴 刻意往多了估</h3>
 * 这不是精确的 tokenizer，是个字符级近似。既然有误差，就要选一个<b>安全的偏向</b>：
 * <ul>
 *   <li>估多了 → 历史截得更狠一点，少带几轮上下文。代价是回答略微不连贯</li>
 *   <li>估少了 → 超出模型窗口，整次调用<b>直接失败</b>，
 *       而且报错来自厂商、信息含糊，事后很难定位是哪一轮撑爆的</li>
 * </ul>
 * 两种代价不对等，所以按上界估。
 *
 * <p>真正的 tokenizer（jtokkit 之类）没引：它对中文与通义的词表并不准，
 * 引一个「看起来精确其实也不准」的依赖，反而会让人误以为这个数字可以拿来对账。
 * 账要对得准只能用厂商回传值 —— 那正是 {@code token_source} 这一列存在的理由。
 *
 * @author 走进新时代
 */
public final class AgentTokenEstimator {

    private AgentTokenEstimator() {
    }

    /**
     * 每个非 CJK 字符折算多少 token
     *
     * 英文里经验值约 4 个字符 1 token。取 4 是常用近似，
     * 对中文为主的语料影响很小 —— 那部分走 CJK 分支。
     */
    private static final double LATIN_CHARS_PER_TOKEN = 4.0;

    /**
     * 估算一段文本的 token 数
     *
     * CJK 字符按 <b>1 字 1 token</b> 算（实际通义多在 0.6~1 之间，这里取上界）；
     * 其余字符按 4 字符 1 token。
     *
     * @param text 文本，可为 null
     * @return 估算值，至少为 0
     */
    public static int estimate(String text) {
        if (StrUtil.isEmpty(text)) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isCjk(text.charAt(i))) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (int) Math.ceil(other / LATIN_CHARS_PER_TOKEN);
    }

    /**
     * 估算多段文本的合计
     */
    public static int estimate(String... texts) {
        int total = 0;
        for (String text : texts) {
            total += estimate(text);
        }
        return total;
    }

    /**
     * 是不是中日韩字符
     *
     * 只判最常用的几个区段就够了 —— 这是估算，不是文本分析。
     * 判全所有扩展区会让这个方法复杂几倍，而对结果的影响小于本来就存在的误差。
     */
    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)     // 中日韩统一表意文字
                || (c >= 0x3400 && c <= 0x4DBF) // 扩展 A
                || (c >= 0x3000 && c <= 0x303F) // 中文标点
                || (c >= 0xFF00 && c <= 0xFFEF) // 全角字符
                || (c >= 0x3040 && c <= 0x30FF) // 日文假名
                || (c >= 0xAC00 && c <= 0xD7AF); // 韩文
    }

}
