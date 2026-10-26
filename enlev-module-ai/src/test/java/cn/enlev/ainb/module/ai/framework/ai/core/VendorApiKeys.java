package cn.enlev.ainb.module.ai.framework.ai.core;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 厂商 API 密钥一律从环境变量读，<b>绝不写进源码</b>
 *
 * <h3>WHY</h3>
 * 这一批是「必须联网才能跑」的集成测试，平时全挂着 {@code @Disabled}。把真密钥直接写在字段初始化里，
 * 换来的结果是：密钥随 git <b>永久</b>留在历史里，而它一次也没被执行过 —— 纯亏。
 * 2026-10-07 量到时，本模块 src/test 下有 10 处这种写法（Anthropic / OpenAI / SiliconFlow×2 /
 * 通义×3 / Midjourney / StabilityAi / 博查），全部来自上游提交，本仓只是继承了它们。
 *
 * <h3>⚠️ 改掉源码不等于密钥不再泄露</h3>
 * git 历史里那 10 个值仍然存在，任何一次 {@code git log -p} 都能捞出来。
 * 真正的补救只有<b>去各家控制台吊销并换发</b>；本类只负责「HEAD 不再继续外传」。
 * 而且这些是上游作者的演示密钥，本仓的使用者多半根本没权限吊销 —— 所以别把「已经改掉了」
 * 当成「已经安全了」。
 *
 * <h3>取不到就 assumeTrue 跳过，而不是抛异常或返回空串</h3>
 * <ul>
 *   <li>返回空串最糟：会带着一个必然 401 的请求真打到厂商接口上，报错信息里看不出「是你没配环境变量」。</li>
 *   <li>抛异常会让「有人在 CI 里去掉了 @Disabled 但没配密钥」直接把构建打红 —— 那是环境缺失，不是代码错。</li>
 *   <li>{@code assumeTrue} 把它记成 skipped 并带上原因，是集成测试缺凭据时的通行做法。</li>
 * </ul>
 *
 * @author 走进新时代
 */
public final class VendorApiKeys {

    private VendorApiKeys() {
    }

    /**
     * 读环境变量 {@code envName} 当作厂商密钥；没配则<b>跳过</b>当前测试并说明原因
     *
     * @param envName 环境变量名，如 {@code OPENAI_API_KEY}
     * @return 去掉首尾空白的密钥；返回值一定非空（为空时上面那条 assumeTrue 已经把测试中止了）
     */
    public static String of(String envName) {
        String value = System.getenv(envName);
        assumeTrue(value != null && !value.isBlank(),
                () -> "跳过：没有配置环境变量 " + envName
                        + "。厂商密钥不许写进源码（它会随 git 永久留在历史里），"
                        + "要真跑这个集成测试，请先在环境里导出 " + envName + "=你的密钥");
        return value.trim();
    }

}
