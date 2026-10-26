package cn.enlev.ainb.module.agent.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 用户这句话的意图
 *
 * <h3>只有两类，刻意不细分</h3>
 * 分类的唯一用途是<b>选一条路径</b>：问答还是规划。多分出来的类别没有对应的路径，
 * 只会让分类器更容易出错 —— 而它每条消息都要跑一次。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum AgentIntentEnum {

    /**
     * 问答：想知道怎么做、为什么不生效、某个功能在哪
     *
     * 🔴 <b>这是回落值。</b>分类失败、模型没配好、输出看不懂 —— 一律落到这里。
     * 方向是有意的：走错到问答，最坏结果是用手册答了一句不那么贴题的话；
     * 走错到规划，最坏结果是用户面前突然出现一张他没要求过的「建表」卡片。
     * 两者的代价完全不对等。
     */
    CHAT("CHAT", "问答"),

    /**
     * 要求变更：让助手真的去建表、建表单、改配置
     */
    BUILD("BUILD", "变更"),
    ;

    private final String intent;
    private final String description;

}
