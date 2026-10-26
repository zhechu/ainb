package cn.enlev.ainb.module.ai.util;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.util.collection.SetUtils;
import cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils;
import cn.enlev.ainb.framework.tenant.core.context.TenantContextHolder;
import cn.enlev.ainb.module.ai.enums.model.AiPlatformEnum;
import cn.enlev.ainb.module.ai.framework.ai.config.EnlevAiProperties;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import cn.hutool.extra.spring.SpringUtil;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.env.Environment;

import java.util.*;
import java.util.regex.Pattern;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.ai.enums.ErrorCodeConstants.API_CONFIG_PLACEHOLDER_NOT_RESOLVED;
import static cn.enlev.ainb.module.ai.enums.ErrorCodeConstants.API_KEY_BLANK;

/**
 * Spring AI 工具类
 *
 * @author 芋道源码
 */
public class AiUtils {

    public static final String TOOL_CONTEXT_LOGIN_USER = "LOGIN_USER";
    public static final String TOOL_CONTEXT_TENANT_ID = "TENANT_ID";

    /**
     * 解析 DB 等动态配置里的 Spring 占位符，例如 ${OPENAI_API_KEY}
     *
     * @param value 待解析的配置值
     * @return 解析后的配置值
     */
    public static String resolveSpringPlaceholders(String value) {
        if (StrUtil.isBlank(value) || !StrUtil.contains(value, "${")) {
            return value;
        }
        try {
            return SpringUtil.getBean(Environment.class).resolveRequiredPlaceholders(value);
        } catch (IllegalArgumentException ex) {
            throw exception(API_CONFIG_PLACEHOLDER_NOT_RESOLVED, value);
        }
    }

    /**
     * 各平台 SDK 的示例密钥。配置里留着它等于没填，故与「空」同档处理
     */
    public static final String API_KEY_PLACEHOLDER = "sk-xxxx";

    /**
     * 密钥是不是「等于没填」：空白，或仍是 SDK 的示例值 {@link #API_KEY_PLACEHOLDER}
     *
     * <p>抽成谓词是因为「什么算没填」必须有<b>一个</b>权威定义：
     * {@link #validateApiKey(String)} 抛不抛、候选链要不要把这个模型剔掉，判据必须是同一句话。
     * 两处各写一遍 {@code isBlank || placeholder} 的话，哪天多加一种占位值就只改得掉一处。
     *
     * @param apiKey 密钥原文（DB 里存的值，可能还是 {@code ${XXX}} 占位符 —— 那种<b>不算</b>空，
     *               它由 {@link #resolveSpringPlaceholders(String)} 抛
     *               {@code API_CONFIG_PLACEHOLDER_NOT_RESOLVED} 说清「解析不出来」，
     *               与「压根没填」是两个成因，不该合并）
     * @return 是否等于没填
     */
    public static boolean isApiKeyBlank(String apiKey) {
        return StrUtil.isBlank(apiKey) || API_KEY_PLACEHOLDER.equals(apiKey);
    }

    /**
     * 校验 API Key，避免集成测试使用默认占位值发起调用。
     *
     * <p>🔴 抛<b>带码</b>的 {@code ServiceException}，不抛 {@code IllegalStateException}（台账 D4-29）：
     * 后者到调用方手上「没有码、只有一句内部术语」，于是每个消费者只能自己猜一个成因 ——
     * agent 侧的 {@code AgentAssistantServiceImpl#toReadableMessage} 就曾拿
     * 「没有可用的聊天模型，请启用一个」去接<b>所有</b> {@code IllegalStateException}，
     * 而「密钥没填」与「模型没启用」的排查方向完全不同，界面却只给后一句，
     * 管理员于是去反复启停一个本来就启用着的模型（红线 §10.2 第 7 条：不替 error 编病因）。
     * 带码之后消费者只剩两条路：原样透传 message，或按码分流 —— 都不必猜。
     *
     * <p>⚠️ 2026-10-07 曾实测到本方法在 {@code src/main} 下<b>零调用点</b>（调用方全是
     * {@code framework/ai/core/model/**}{@code *ChatModelTests} 那批集成测试），
     * 于是生产聊天路径上「密钥没填」被 {@code AiModelFailoverServiceImpl#buildCandidateChain}
     * 第 5 步的 {@code catch (Exception)} 吞掉、跳过该候选，最终以空链变成
     * {@code MODEL_ALL_UNAVAILABLE}「没有可用的聊天模型，请去启用一个」—— 正是上面那段要防的误报。
     * 现已把本方法接进候选链第 5 步与 {@code buildPinnedChain}，
     * 由「一个候选都不剩」时原样抛出，故本方法<b>在生产路径上可达</b>。
     *
     * @param apiKey API Key
     */
    public static void validateApiKey(String apiKey) {
        if (isApiKeyBlank(apiKey)) {
            throw exception(API_KEY_BLANK);
        }
    }

    /**
     * 把一次调用失败说成人话：沿 cause 链取<b>最里层</b>那句非空消息
     *
     * <h3>为什么必须挖到最里层</h3>
     * 厂商 SDK 常把真正的响应体包在两三层异常里（{@code RuntimeException → WebClientResponseException → …}），
     * 最外层往往只有一句「调用失败」。把外层那句回显给管理员，等于让他照着「失败了」去排查 ——
     * 而他要的是 {@code 400 - url error, please check url！} 或 {@code 403 - Free quota exhausted}
     * 这种能直接指出病因的原文。与本模块「不替 error 编病因」那条纪律是同一件事。
     *
     * <h3>为什么放在这里而不是各自的 Service 里</h3>
     * 「怎么把异常说清楚」只能有<b>一个</b>定义：模型测试按钮与向量模型选链都要用，
     * 两处各写一遍 cause 链遍历的话，迟早一处限深一处不限深 ——
     * 而 {@code ex.initCause(ex)} 这种自引用在真实代码里出现过，不限深的那份会转出栈溢出。
     *
     * @param error 异常，不可为 null
     * @return 最里层的非空消息；全链都没有消息时回落 {@code error.toString()}
     */
    public static String describeError(Throwable error) {
        String message = null;
        Throwable cursor = error;
        // 限深而不是 while(true)：cause 自引用在真实代码里出现过，那样会转出栈溢出
        for (int depth = 0; cursor != null && depth < 10; depth++) {
            if (StrUtil.isNotBlank(cursor.getMessage())) {
                message = cursor.getMessage();
            }
            cursor = cursor.getCause() == cursor ? null : cursor.getCause();
        }
        return message != null ? message : error.toString();
    }

    /**
     * 通义千问要走「多模态」端点的模型，即 multiModel = true
     *
     * 不在本集合里的模型走「文本」端点。两个端点 URL 不同，选错<b>不会</b>报「模型不支持」，
     * 而是 400 InvalidParameter「url error, please check url！」—— 一句看不出根因的话，
     * 排查代价极高。所以下面的归类以实测为准，不能按系列名推断。
     *
     * 🔴 2026-09-29 直连 dashscope.aliyuncs.com 两个端点逐一实测：
     * <pre>
     *   qwen3.7-max              仅 text-generation 通，multimodal 报 url error
     *   qwen3.7-flash            仅 multimodal 通，text-generation 报 url error
     *   qwen3.7-plus-2026-05-26  仅 multimodal 通，text-generation 报 url error
     * </pre>
     * 也就是说同一代里 max 与 plus/flash 的主干并不一致：max 是纯文本主干，
     * 把它按「qwen3.7 系列」整批归成多模态，正是线上 qwen3.7-max 全部对话失败的根因。
     *
     * @see <a href="https://bailian.console.aliyun.com/cn-beijing/?tab=model#/model-market/all?providers=qwen&capabilities=VU">模型广场</a>
     * @see <a href="https://help.aliyun.com/zh/model-studio/error-code#error-url">必须开启 withMultiModel 参数</a>
     */
    public static final Set<String> TONG_YI_MULTI_MODELS = SetUtils.asSet(
            // qwen3.5 / 3.6 / 3.7 系列（统一多模态主干；qwen3.7-max 是例外，纯文本主干）
            "qwen3.7-plus", "qwen3.7-flash",
            "qwen3.6-plus", "qwen3.6-flash",
            "qwen3.5-plus", "qwen3.5-flash",
            // qwen-vl 视觉理解
            "qwen3-vl-plus", "qwen3-vl-flash",
            "qwen-vl-max", "qwen-vl-plus",
            "qwen2.5-vl-72b-instruct", "qwen2.5-vl-32b-instruct",
            "qwen2.5-vl-7b-instruct", "qwen2.5-vl-3b-instruct",
            // qvq 视觉推理
            "qvq-max", "qvq-plus",
            // qwen-omni 全模态
            "qwen3.5-omni-plus", "qwen3.5-omni-flash",
            "qwen3-omni-flash", "qwen-omni-turbo"
    );

    /**
     * 被厂商限死「必须开思考」的通义模型，即 {@code enable_thinking} 只能是 true
     *
     * <p>🔴 这一档修的是<b>具体故障</b>不是假设。2026-10-10 实测模型 79
     * （{@code qwen3.7-max-2026-05-17}）逐字回：
     * <pre>400 - enable_thinking parameter is restricted to True</pre>
     * 它是纯文本主干（见 {@link #TONG_YI_MULTI_MODELS} 的实测记录），于是被
     * 「文本端点 ⇒ 思考关」这条硬规则判死 —— 一个<b>永远不可能成功</b>的请求。
     *
     * <p>可配（{@code enlev.ai.tongyi.thinking-only-models}）的理由是「哪些模型被限死」
     * 只有厂商知道，写死在代码里就等于「撞上同类模型必须发版」。
     *
     * <p>⚠️ 它<b>压不过</b>调用方的显式表态（见 {@link #resolveTongYiThinking}）。
     * 后果是刻意的：agent 的 CHAT / INTENT / PLAN 显式传 false，若命中本集合里的模型，
     * 厂商会回上面那句 400 —— 而 A2 已把 {@code enable_thinking} 收进
     * {@code AiModelFailureReason.CONFIG_MARKERS}，所以它会被判 CONFIG：
     * 立刻切下一个候选 + 30 分钟冷却，而不是每 60 秒重演一遍。
     * 静默把显式 false 改写成 true 才是更糟的那个选择：那会让「实测单次 284 秒」的
     * 预算约束在没人知道的情况下失效。
     */
    public static final Set<String> TONG_YI_THINKING_ONLY_MODELS = SetUtils.asSet("qwen3.7-max");

    /**
     * 通义千问的快照版后缀：{@code qwen3.7-plus-2026-05-26}、{@code qwen-vl-max-latest}
     */
    private static final Pattern TONG_YI_SNAPSHOT_SUFFIX = Pattern.compile("(-\\d{4}-\\d{2}-\\d{2}|-latest)$");

    /**
     * 通义千问要走「<b>多模态向量</b>」端点的模型，即 {@code type=向量} 但不归文本向量端点管
     *
     * <p>与 {@link #TONG_YI_MULTI_MODELS} 是<b>两回事</b>，别混：那份管的是<b>对话</b>模型的
     * 文本/多模态生成端点分档，本份管的是<b>向量</b>模型的端点分档。两者恰好都叫「多模态」，
     * 且都靠 {@code -vl-} 这类名字认，所以最容易看串。
     *
     * <p>🔴 2026-10-26 直连 {@code dashscope.aliyuncs.com} 三端点逐个实测（同一密钥，见
     * {@code DashScopeMultiModalEmbeddingModel} 类注释里那张表）：下面 4 个只有多模态向量端点通，
     * 打文本向量端点一律 {@code 400 InvalidParameter "url error, please check url！"}；
     * 而 {@code text-embedding-v4} / {@code qwen3.7-text-embedding-flash} 反过来只有文本端点通。
     * 归类以实测为准，不能按系列名推断 —— 与 {@link #TONG_YI_MULTI_MODELS} 那条纪律同源。
     *
     * <p>可配（{@code enlev.ai.tongyi.multi-modal-embedding-models}）的理由也一样：
     * 「哪个模型归哪个端点」只有厂商知道，写死在代码里就等于「冒出一个同类新模型必须发版」。
     */
    public static final Set<String> TONG_YI_MULTI_MODAL_EMBEDDING_MODELS = SetUtils.asSet(
            // 视觉语言向量（qwen-vl 系的 embedding 档）
            "qwen3-vl-embedding", "qwen2.5-vl-embedding",
            // 通义视觉向量
            "tongyi-embedding-vision-flash", "tongyi-embedding-vision-plus"
    );

    /**
     * 判断通义千问模型是否要走多模态端点（读配置，配了就用配的）
     *
     * @param model 模型名，例如 qwen3.7-plus-2026-05-26
     * @return 是否多模态模型
     */
    public static boolean isTongYiMultiModel(String model) {
        return isTongYiMultiModel(model, configuredMultiModels());
    }

    /**
     * 纯函数版：清单由调用方给，不碰 Spring
     *
     * <p>抽出来是为了让单测能钉住「配置<b>整体替换</b>内置清单」这条语义
     * （含 {@code multi-models: []} 显式清空），而不必为此拉起一个 Spring 上下文。
     *
     * @param model       模型名
     * @param multiModels 生效的多模态清单
     */
    static boolean isTongYiMultiModel(String model, Set<String> multiModels) {
        return matchesIgnoringSnapshot(model, multiModels);
    }

    /**
     * 判断通义千问<b>向量</b>模型是否要走多模态向量端点（读配置，配了就用配的）
     *
     * @param model 模型名，例如 qwen3-vl-embedding
     * @return 是否多模态向量模型
     */
    public static boolean isTongYiMultiModalEmbeddingModel(String model) {
        return isTongYiMultiModalEmbeddingModel(model, configuredMultiModalEmbeddingModels());
    }

    /**
     * 纯函数版，理由同 {@link #isTongYiMultiModel(String, Set)}
     */
    static boolean isTongYiMultiModalEmbeddingModel(String model, Set<String> multiModalEmbeddingModels) {
        return matchesIgnoringSnapshot(model, multiModalEmbeddingModels);
    }

    /**
     * 判断通义千问模型是否被厂商限死「必须开思考」（读配置，配了就用配的）
     *
     * @param model 模型名，例如 qwen3.7-max-2026-05-17
     * @return 是否只能 {@code enable_thinking=true}
     */
    public static boolean isTongYiThinkingOnly(String model) {
        return isTongYiThinkingOnly(model, configuredThinkingOnlyModels());
    }

    /**
     * 纯函数版，理由同 {@link #isTongYiMultiModel(String, Set)}
     */
    static boolean isTongYiThinkingOnly(String model, Set<String> thinkingOnlyModels) {
        return matchesIgnoringSnapshot(model, thinkingOnlyModels);
    }

    /**
     * 命中清单，快照版先剥后缀再比
     *
     * <p>「快照名怎么映射回基础名」只能有<b>一个</b>定义：主干能力由基础模型决定，
     * 而快照名是阿里云逐月新增的，指望把它们逐个补进清单必然会漏 —— 实测漏掉的
     * {@code qwen3.7-plus-2026-05-26} 就被打到文本端点，报 url error。
     * 端点分档与思考模式分档共用本方法，所以两处不会各剥各的。
     */
    private static boolean matchesIgnoringSnapshot(String model, Set<String> models) {
        if (StrUtil.isBlank(model) || models == null) {
            return false;
        }
        return models.contains(model)
                || models.contains(TONG_YI_SNAPSHOT_SUFFIX.matcher(model).replaceAll(""));
    }

    /**
     * 取通义路由配置。没有 Spring 上下文（纯单测）或压根没这个 bean 时回落 null，
     * 由 {@link #configuredMultiModels()} / {@link #configuredThinkingOnlyModels()} 换成内置清单。
     *
     * <p>静态工具读 Spring 配置在本类是既有写法：{@link #resolveSpringPlaceholders(String)}
     * 就是这么取 {@code Environment} 的，{@code AiModelFactoryImpl} 也是这么取本 properties 的。
     * 好处是 {@code buildChatOptions} 那 6 个调用点一个都不用改签名。
     */
    private static EnlevAiProperties.TongYi tongYiProperties() {
        try {
            EnlevAiProperties properties = SpringUtil.getBean(EnlevAiProperties.class);
            return properties == null ? null : properties.getTongyi();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * 生效的多模态清单：配了非 null 就<b>整体替换</b>，null 才用内置的
     *
     * <p>🔴 内置清单不抄进 {@link EnlevAiProperties} 当默认值，是为了不留一个必然漂移的副本：
     * {@link #TONG_YI_MULTI_MODELS} 旁边逐条记着实测结论，抄一份过去，哪天实测更新了只改得掉一处，
     * 而配置字段还因为「非 null 优先」把对的那份盖掉。要清空请显式写 {@code multi-models: []}。
     */
    private static Set<String> configuredMultiModels() {
        EnlevAiProperties.TongYi tongYi = tongYiProperties();
        Set<String> configured = tongYi == null ? null : tongYi.getMultiModels();
        return configured == null ? TONG_YI_MULTI_MODELS : configured;
    }

    /**
     * 生效的「必须开思考」清单，语义同 {@link #configuredMultiModels()}
     */
    private static Set<String> configuredThinkingOnlyModels() {
        EnlevAiProperties.TongYi tongYi = tongYiProperties();
        Set<String> configured = tongYi == null ? null : tongYi.getThinkingOnlyModels();
        return configured == null ? TONG_YI_THINKING_ONLY_MODELS : configured;
    }

    /**
     * 生效的「多模态向量」清单，语义同 {@link #configuredMultiModels()}
     */
    private static Set<String> configuredMultiModalEmbeddingModels() {
        EnlevAiProperties.TongYi tongYi = tongYiProperties();
        Set<String> configured = tongYi == null ? null : tongYi.getMultiModalEmbeddingModels();
        return configured == null ? TONG_YI_MULTI_MODAL_EMBEDDING_MODELS : configured;
    }

    /**
     * 决定通义调用发出去的 {@code enable_thinking}，三档，越靠前越优先
     *
     * <ol>
     *   <li><b>调用方显式表态</b>就照它办（agent 的 CHAT / INTENT / PLAN 三条路径显式传 false）。</li>
     *   <li>命中 {@link #TONG_YI_THINKING_ONLY_MODELS} ⇒ {@code true}。这一档压过第 3 档，
     *       因为「文本端点 ⇒ 思考关」对模型 79 是个<b>必然被厂商拒</b>的组合
     *       （实测原文 {@code enable_thinking parameter is restricted to True}）。</li>
     *   <li>按端点分档：多模态开、文本端点关。</li>
     * </ol>
     *
     * <p>第 3 档的多模态那一档守的是实测约束不是偏好：{@code TongYiChatModelTests#testStream_thinking}
     * 在「{@code qwen3.6-plus} + {@code multiModel(true)} + 流式」上注着
     * {@code enableThinking}「必须设置，否则会报错」，所以默认档不去碰它。
     *
     * <p>🔴 返回 {@code boolean} 而不是 {@code Boolean}，因为<b>没有「不发这个字段」这一档</b>：
     * {@code DashScopeChatOptions} 在字段初始化里就把 {@code enableThinking} 赋成了
     * {@code Boolean.valueOf(false)}（单测 {@code AiUtilsTest#testThinkingHasNoOmitState} 钉住，
     * 档位由 {@code testThinkingDefault_*} 钉住）。
     * 也就是说「不调 setter」拿到的是 {@code false} 而非 {@code null}，
     * 而请求体上那个 {@code @JsonInclude(NON_NULL)} 因此永远不会把它省掉。
     * 曾经按「null = 省略字段、走服务端默认」设计过一版，前提不成立。
     *
     * @param requested    调用方的显式意图，null = 不表态
     * @param multiModel   该模型是否走多模态端点
     * @param thinkingOnly 该模型是否被厂商限死必须开思考
     * @return 要发出去的 {@code enable_thinking}
     */
    static boolean resolveTongYiThinking(Boolean requested, boolean multiModel, boolean thinkingOnly) {
        if (requested != null) {
            return requested;
        }
        if (thinkingOnly) {
            return true;
        }
        return multiModel;
    }

    public static ChatOptions buildChatOptions(AiPlatformEnum platform, String model, Double temperature, Integer maxTokens) {
        return buildChatOptions(platform, model, temperature, maxTokens, null, null, null);
    }

    public static ChatOptions buildChatOptions(AiPlatformEnum platform, String model, Double temperature, Integer maxTokens,
                                               List<ToolCallback> toolCallbacks, Map<String, Object> toolContext) {
        return buildChatOptions(platform, model, temperature, maxTokens, toolCallbacks, toolContext, null);
    }

    /**
     * @param enableThinking 通义专属：是否开启思考模式。传 null 表示「调用方不表态」，
     *                       由 {@link #resolveTongYiThinking} 按模型决定
     */
    public static ChatOptions buildChatOptions(AiPlatformEnum platform, String model, Double temperature, Integer maxTokens,
                                               List<ToolCallback> toolCallbacks, Map<String, Object> toolContext,
                                               Boolean enableThinking) {
        toolCallbacks = ObjUtil.defaultIfNull(toolCallbacks, Collections.emptyList());
        toolContext = ObjUtil.defaultIfNull(toolContext, Collections.emptyMap());
        // noinspection EnhancedSwitchMigration
        switch (platform) {
            case TONG_YI: {
                boolean multiModel = isTongYiMultiModel(model); // 是否多模态模型，决定走哪个端点
                boolean thinkingOnly = isTongYiThinkingOnly(model); // 厂商是否限死必须开思考
                // 🔴 思考模式不再无条件硬开。上游原本是 .enableThinking(true) + 一句
                //    「后续可以让用户配置」的 TODO，实测代价是 CHAT 单次 284 秒、
                //    思考字数达正文的 100~4500 倍，480 秒预算只剩 18% 余量 ——
                //    撞线的表现是「仍未答完」，看着像功能缺陷，其实是预算被思考吃光。
                // 也不再「文本端点一律关思考」：那条硬规则把模型 79 判成了必然被拒的请求。
                // 现在的三档见 resolveTongYiThinking：显式表态 > 限死必须开 > 按端点分档。
                return DashScopeChatOptions.builder().model(model).temperature(temperature).maxToken(maxTokens)
                        .enableThinking(resolveTongYiThinking(enableThinking, multiModel, thinkingOnly))
                        .multiModel(multiModel)
                        .toolCallbacks(toolCallbacks).toolContext(toolContext).build();
            }
            case DEEP_SEEK:
            case DOU_BAO: // 复用 DeepSeek 客户端
            case HUN_YUAN: // 复用 DeepSeek 客户端
            case SILICON_FLOW: // 复用 DeepSeek 客户端
            case YI_YAN: // 复用 DeepSeek 客户端
            case ZHI_PU: // 复用 DeepSeek 客户端
            case XING_HUO: // 复用 DeepSeek 客户端
            case MINI_MAX: // 复用 DeepSeek 客户端
            case MOONSHOT: // 复用 DeepSeek 客户端
            case BAI_CHUAN: // 复用 DeepSeek 客户端
            case STEP_FUN: // 复用 DeepSeek 客户端
                return DeepSeekChatOptions.builder().model(model).temperature(temperature).maxTokens(maxTokens)
                        .toolCallbacks(toolCallbacks).toolContext(toolContext).build();
            case OPENAI:
            case GEMINI: // 复用 OpenAI 客户端
            case GROK: // 复用 OpenAI 客户端
                return OpenAiChatOptions.builder().model(model).temperature(temperature).maxTokens(maxTokens)
                        .toolCallbacks(toolCallbacks).toolContext(toolContext).build();
            case AZURE_OPENAI:
                return AzureOpenAiChatOptions.builder().deploymentName(model).temperature(temperature).maxTokens(maxTokens)
                        .toolCallbacks(toolCallbacks).toolContext(toolContext).build();
            case ANTHROPIC:
                return AnthropicChatOptions.builder().model(model).temperature(temperature).maxTokens(maxTokens)
                        .toolCallbacks(toolCallbacks).toolContext(toolContext).build();
            case OLLAMA:
                return OllamaChatOptions.builder().model(model).temperature(temperature).numPredict(maxTokens)
                        .toolCallbacks(toolCallbacks).toolContext(toolContext).build();
            default:
                throw new IllegalArgumentException(StrUtil.format("未知平台({})", platform));
        }
    }

    /**
     * 从已建好的 options 里读出<b>实际要发出去</b>的 {@code enable_thinking}；非通义平台返回 null
     *
     * <p>抽这个方法是为了不让厂商类型 {@code DashScopeChatOptions} 漏到 Service 层去：
     * 本类已经是本模块与厂商 SDK 的收敛点。
     *
     * <p>🔴 「读 options」而不是「再算一遍」是刻意的：A3 之后思考模式由三档决定
     * （显式表态 > 限死必须开 > 按端点），在别处复算等于把档位逻辑抄第二份，
     * 哪天加一档就只改得掉一处 —— 而回显出来的那一格正是管理员判断
     * 「改了 {@code enlev.ai.tongyi.thinking-only-models} 到底生效没有」的唯一依据。
     */
    public static Boolean getTongYiEnableThinking(ChatOptions options) {
        return options instanceof DashScopeChatOptions dashScopeOptions
                ? dashScopeOptions.getEnableThinking() : null;
    }

    public static Message buildMessage(String type, String content) {
        if (MessageType.USER.getValue().equals(type)) {
            return new UserMessage(content);
        }
        if (MessageType.ASSISTANT.getValue().equals(type)) {
            return new AssistantMessage(content);
        }
        if (MessageType.SYSTEM.getValue().equals(type)) {
            return new SystemMessage(content);
        }
        if (MessageType.TOOL.getValue().equals(type)) {
            throw new UnsupportedOperationException("暂不支持 tool 消息：" + content);
        }
        throw new IllegalArgumentException(StrUtil.format("未知消息类型({})", type));
    }

    public static Map<String, Object> buildCommonToolContext() {
        Map<String, Object> context = new HashMap<>();
        context.put(TOOL_CONTEXT_LOGIN_USER, SecurityFrameworkUtils.getLoginUser());
        context.put(TOOL_CONTEXT_TENANT_ID, TenantContextHolder.getTenantId());
        return context;
    }

    @SuppressWarnings("ConstantValue")
    public static String getChatResponseContent(ChatResponse response) {
        if (response == null
                || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }

    @SuppressWarnings("ConstantValue")
    public static String getChatResponseReasoningContent(ChatResponse response) {
        if (response == null
                || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return null;
        }
        AssistantMessage output = response.getResult().getOutput();
        // DeepSeek 通过专属 AssistantMessage 暴露 reasoningContent
        if (output instanceof DeepSeekAssistantMessage) {
            return ((DeepSeekAssistantMessage) output).getReasoningContent();
        }
        // 通义千问等通过 metadata 透传 reasoningContent
        return MapUtil.getStr(output.getMetadata(), "reasoningContent");
    }

}
