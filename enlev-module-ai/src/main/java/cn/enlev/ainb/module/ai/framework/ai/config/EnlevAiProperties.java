package cn.enlev.ainb.module.ai.framework.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 启凌智搭 AI 配置类
 *
 * @author fansili
 * @since 1.0
 */
@ConfigurationProperties(prefix = "enlev.ai")
@Data
public class EnlevAiProperties {

    /**
     * 谷歌 Gemini
     */
    private Gemini gemini;

    /**
     * 字节豆包
     */
    private DouBao doubao;

    /**
     * 腾讯混元
     */
    private HunYuan hunyuan;

    /**
     * 硅基流动
     */
    private SiliconFlow siliconflow;

    /**
     * 讯飞星火
     */
    private XingHuo xinghuo;

    /**
     * 百川
     */
    private BaiChuan baichuan;

    /**
     * 文心一言
     */
    private YiYan yiyan;

    /**
     * 智谱
     */
    private ZhiPu zhipu;

    /**
     * MiniMax
     */
    private MiniMax minimax;

    /**
     * 月之暗面
     */
    private Moonshot moonshot;

    /**
     * 阶跃星辰
     */
    private StepFun stepfun;

    /**
     * Grok
     */
    private Grok grok;

    /**
     * Midjourney 绘图
     */
    private Midjourney midjourney;

    /**
     * Suno 音乐
     */
    @SuppressWarnings("SpellCheckingInspection")
    private Suno suno;

    /**
     * 网络搜索
     */
    private WebSearch webSearch;

    /**
     * 通义千问的端点 / 思考模式路由
     */
    private TongYi tongyi = new TongYi();

    /**
     * 厂商 HTTP 客户端的超时
     */
    private Http http = new Http();

    @Data
    public static class Gemini {

        private String enable;
        private String apiKey;

        private String model;
        private Double temperature;
        private Integer maxTokens;
        private Double topP;

    }

    @Data
    public static class DouBao {

        private String enable;
        private String apiKey;

        private String model;
        private Double temperature;
        private Integer maxTokens;
        private Double topP;

    }

    @Data
    public static class HunYuan {

        private String enable;
        private String baseUrl;
        private String apiKey;

        private String model;
        private Double temperature;
        private Integer maxTokens;
        private Double topP;

    }

    @Data
    public static class SiliconFlow {

        private String enable;
        private String apiKey;

        private String model;
        private Double temperature;
        private Integer maxTokens;
        private Double topP;

    }

    @Data
    public static class XingHuo {

        private String enable;
        private String apiKey;

        private String model;
        private Double temperature;
        private Integer maxTokens;
        private Double topP;

    }

    @Data
    public static class BaiChuan {

        private String enable;
        private String apiKey;

        private String model;
        private Double temperature;
        private Integer maxTokens;
        private Double topP;

    }

    @Data
    public static class YiYan {

        private String enable;
        private String baseUrl;
        private String apiKey;

        private String model;
        private Double temperature;
        private Integer maxTokens;
        private Double topP;

    }

    @Data
    public static class ZhiPu {

        private String enable;
        private String baseUrl;
        private String apiKey;

        private String model;
        private Double temperature;
        private Integer maxTokens;
        private Double topP;

    }

    @Data
    public static class MiniMax {

        private String enable;
        private String baseUrl;
        private String apiKey;

        private String model;
        private Double temperature;
        private Integer maxTokens;
        private Double topP;

    }

    @Data
    public static class Moonshot {

        private String enable;
        private String baseUrl;
        private String apiKey;

        private String model;
        private Double temperature;
        private Integer maxTokens;
        private Double topP;

    }

    @Data
    public static class StepFun {

        private String enable;
        private String apiKey;
        private String baseUrl;

        private String model;
        private Double temperature;
        private Integer maxTokens;
        private Double topP;

    }

    @Data
    public static class Midjourney {

        private String enable;
        private String baseUrl;

        private String apiKey;
        private String notifyUrl;

    }

    @Data
    public static class Suno {

        private boolean enable;

        private String baseUrl;

    }

    @Data
    public static class Grok {

        private String enable;
        private String apiKey;
        private String baseUrl;

        private String model;
        private Double temperature;
        private Integer maxTokens;
        private Double topP;

    }

    @Data
    public static class WebSearch {

        private boolean enable;

        private String apiKey;

    }

    /**
     * 流式对话
     */
    private Stream stream = new Stream();

    /**
     * 流式对话的兜底约束（缺陷 B18）
     *
     * <h3>为什么非配不可</h3>
     * 厂商侧连接<b>挂起</b>（TCP 连着但不再发数据）时，Reactor 的
     * {@code doOnComplete} / {@code doOnError} / {@code doOnCancel} <b>三条都不会触发</b>。
     * 表现是：SSE 连接长期悬挂、assistant 消息记录永久停在空白，
     * 用户界面上是一个永远转不完的圈，而服务端不认为出了任何问题 ——
     * 既不报错也不留日志，排查时无从下手。
     *
     * <p>Spring AI 底层 RestClient 即便有默认 read-timeout，也管不到「连接活着但不发数据」
     * 这一档。所以必须在 Reactor 这一层用 {@code Flux.timeout(...)} 兜底：
     * 它量的是<b>相邻两个元素之间</b>的间隔，正好对应这个故障形态。
     */
    @Data
    public static class Stream {

        /**
         * 相邻两个流式分片之间的最大间隔，超过即判定为连接挂起
         *
         * <p>默认 120 秒。取这个值是因为推理型模型「想很久才吐第一个字」是正常现象 ——
         * 配太短会把正常的长思考误杀成超时，那比不配更糟：用户会看到随机的失败。
         */
        private java.time.Duration timeout = java.time.Duration.ofSeconds(120);

    }

    /**
     * 通义千问的端点与思考模式路由（A3）
     *
     * <h3>为什么这两张表要能配</h3>
     * 端点选错的代价是 400「url error, please check url！」—— 一句看不出根因的话，
     * 而阿里云是<b>逐月</b>新增快照模型名的，指望把每个新名字都补进代码必然漏。
     * 思考模式同理：厂商原文 {@code enable_thinking parameter is restricted to True} 是实测到的，
     * 但「哪些模型被限死在 true」只有厂商知道，写在代码里就等于「撞上同类模型必须发版」。
     *
     * <h3>🔴 两个字段的默认值都是 null，语义是「用 {@code AiUtils} 里那份内置清单」</h3>
     * 内置清单是<b>唯一权威</b>：{@code AiUtils#TONG_YI_MULTI_MODELS} 那 20 个名字旁边逐条记着
     * 「2026-09-29 直连两个端点实测」的结论。在这里再抄一份默认值，就等于制造一个必然漂移的副本 ——
     * 哪天实测更新了，两处只改得掉一处，而配置类字段还因为「非 null 优先」把对的那份盖掉。
     * 所以要<b>清空</b>内置清单，得显式写 {@code multi-models: []}（空集合 ≠ null）。
     */
    @Data
    public static class TongYi {

        /**
         * 走「多模态」端点的模型名（基础版，不含快照后缀 —— 快照版由 {@code AiUtils} 自动剥后缀归类）
         *
         * <p>null = 用 {@code AiUtils#TONG_YI_MULTI_MODELS}；配了非空集合 = <b>整体替换</b>，不是追加。
         */
        private java.util.Set<String> multiModels;

        /**
         * 被厂商限死「必须开思考」的模型名，命中即 {@code enable_thinking=true}，不看端点
         *
         * <p>null = 用 {@code AiUtils#TONG_YI_THINKING_ONLY_MODELS}（当前只有 {@code qwen3.7-max}）。
         * 这一档<b>压过</b>「文本端点 ⇒ 思考关」，但<b>压不过</b>调用方的显式表态 ——
         * agent 的 CHAT / INTENT / PLAN 三条路径显式传 false，它们要的是快，不是深思。
         */
        private java.util.Set<String> thinkingOnlyModels;

        /**
         * 走「<b>多模态向量</b>」端点的模型名（基础版，不含快照后缀）
         *
         * <p>与 {@link #multiModels} 不是一回事：那份分档<b>对话</b>端点，本份分档<b>向量</b>端点。
         *
         * <p>null = 用 {@code AiUtils#TONG_YI_MULTI_MODAL_EMBEDDING_MODELS}；配了非空集合 = <b>整体替换</b>。
         */
        private java.util.Set<String> multiModalEmbeddingModels;

    }

    /**
     * 厂商 HTTP 客户端的超时（A4 / 缺陷 B18）
     *
     * <h3>为什么之前一个超时都没有</h3>
     * {@code AiModelFactoryImpl} 全文件零超时设置，厂商侧一挂起，占住的就是一个 Tomcat 线程，
     * 而且<b>永久</b>：Reactor 的 {@code doOnComplete/doOnError/doOnCancel} 三条都不触发，
     * 服务端既不报错也不留日志。上面的 {@link Stream#timeout} 只兜住流式那条路（相邻分片间隔），
     * 同步 {@code call} 完全没人管 —— 本类补的就是这一层。
     */
    @Data
    public static class Http {

        /**
         * 建连超时。默认 10 秒：厂商域名不可达时应当<b>很快</b>失败，好让故障切换去试下一个候选。
         */
        private java.time.Duration connectTimeout = java.time.Duration.ofSeconds(10);

        /**
         * 读超时。默认 <b>300 秒</b>
         *
         * <p>🔴 不要配小。这个值的意义不是「快」，是<b>把无限挂起变成有界失败</b>。
         * 实测过「CHAT 单次 284 秒」（思考模式下思考字数达正文的 100~4500 倍），
         * 配 60 秒会把正常的长思考误杀成超时 —— 那比不配更糟：用户看到的是随机失败，
         * 而随机失败比稳定失败难排查一个数量级。
         *
         * <p>流式路径<b>不</b>使用本字段：reactor-netty 的 response-timeout 会掐断长时间 SSE 流，
         * 那边由 {@link Stream#timeout} 按「相邻分片间隔」兜，位置是对的，不要挪过来。
         */
        private java.time.Duration readTimeout = java.time.Duration.ofSeconds(300);

    }

}
