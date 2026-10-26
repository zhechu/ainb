package cn.enlev.ainb.module.agent.framework.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * AI 助手的文档语料配置
 *
 * <p>语料是<b>手册站的 markdown 源</b>（{@code xpy-blog/docs}），不抓线上 HTML：
 * 线上抓回来要过 Tika，抽出的是纯文本、丢标题层级，切分质量明显更差；
 * 而 markdown 源与线上产物同源同版本（{@code pnpm docs:build} 就是从它构建的）。
 *
 * <p>🔴 <b>不再向上探测目录</b>。第一版靠标记 {@code ainb-ui-admin-vue3/docs} 逐级向上找，
 * 那个目录压根不存在，于是同步永久空转 —— 而空转<b>不报错</b>：接口返回 200、计数全是 0，
 * 看着像「跑成功了、只是没文档」。这正是本平台头号缺陷「配了但不生效且无提示」的形状。
 * 现在 {@code root-path} 必须显式配，没配就抛错。
 *
 * @author 走进新时代
 */
@ConfigurationProperties(prefix = "enlev.agent.doc")
@Validated
@Data
public class AgentDocProperties {

    /**
     * 文档根目录（手册仓库的 {@code docs/} 目录），<b>必填</b>
     *
     * 如 {@code C:/code/xpy-blog/docs}。留空则同步接口直接报错 —— 见类注释。
     */
    private String rootPath;

    /**
     * 要同步的子目录
     *
     * {@code guide} 是给使用者看的产品手册，{@code develop} 是给二开者看的开发手册。
     * 两册都要：AI 助手的问题里「这个页面怎么配」与「这个扩展点在哪」各占一半。
     */
    private List<String> directories = List.of("guide", "develop");

    /**
     * 排除的相对路径（{@code **} 通配）
     *
     * <ul>
     *   <li>{@code .generated/}、{@code .vitepress/}、{@code public/}：构建产物、站点配置与图片，不是正文</li>
     *   <li>{@code develop/04-upstream/}：上游芋道的说明，对本平台的使用者没有意义，
     *       灌进去只会让 AI 引用一堆「上游怎么做」来回答「本平台怎么用」</li>
     * </ul>
     */
    private List<String> excludePatterns = List.of(
            ".generated/**", ".vitepress/**", "public/**", "develop/04-upstream/**");

    /**
     * 手册站的公开地址前缀，用于把相对路径映射成可点链接
     *
     * <p>🔴 不写死域名：{@code doc.enlev.cn} 当前被云厂商拦截（备案/解封中），
     * 换域名或先在测试环境验证时只改这一项，不用改代码。
     */
    private String baseUrl = "https://doc.enlev.cn";

    /**
     * 分片最大 token 数
     *
     * 手册页均长约 9.5K 字符（≈6K token），800 让一页切成 7~9 段，
     * 每段大致对上一个小节。切太碎会丢上下文（「常见坑」的问题与答案被拆开就都没用了）。
     */
    private Integer segmentMaxTokens = 800;

    /**
     * 内置知识库的名称，作为幂等键 —— 重复同步不会建出一堆同名知识库
     *
     * <p>🔴 别改：它同时是 {@code ai_knowledge} 里那行的 {@code name}，改了等于另建一个库，
     * 旧库和它已经向量化的段落会原地留着继续被召回。
     */
    private String knowledgeName = "平台产品手册（AI 助手内置）";

    /**
     * 召回数量
     *
     * 手册类语料给 5 条：太少答不全（一个问题的线索常散在两三篇里），
     * 太多会把不相干的段落塞进 prompt 稀释注意力。
     */
    private Integer topK = 5;

    /**
     * 相似度阈值
     *
     * 给得低一点。中文技术文档的向量相似度普遍不高，阈值卡太严会出现
     * 「文档里明明写了却召回不到」—— 那比多召回几条噪声糟糕得多。
     */
    private Double similarityThreshold = 0.2;

}
