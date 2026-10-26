package cn.enlev.ainb.module.ai.enums;

import cn.enlev.ainb.framework.common.exception.ErrorCode;

/**
 * AI 错误码枚举类
 * <p>
 * ai 系统，使用 1-040-000-000 段
 */
public interface ErrorCodeConstants {

    // ========== API 密钥 1-040-000-000 ==========
    ErrorCode API_KEY_NOT_EXISTS = new ErrorCode(1_040_000_000, "API 密钥不存在");
    ErrorCode API_KEY_DISABLE = new ErrorCode(1_040_000_001, "API 密钥已禁用！");
    ErrorCode API_CONFIG_PLACEHOLDER_NOT_RESOLVED = new ErrorCode(1_040_000_002, "AI 配置({})无法解析，请检查环境变量或配置项");
    /**
     * 密钥是空的（或仍是占位值）—— 缺陷 D4-29
     *
     * <p>🔴 单独给一个码，是为了不再与 {@link #MODEL_ALL_UNAVAILABLE} 共用一句话：
     * 后者说的是「一个能用的模型都没有」，本条说的是「模型选出来了、也启用着，只是它那把密钥没填」。
     * 两者混成一句，管理员就会去「AI 模型」里反复启停一个<b>本来就启用着</b>的模型 ——
     * 排查方向从第一句起就是错的。
     *
     * <p>这个成因原先以 {@code IllegalStateException("apiKey 不能为空")} 的形式冒出来：
     * 没有码、只有一句内部术语，于是每个消费者只能自己<b>猜</b>一个成因。
     * agent 侧的 {@code AgentAssistantServiceImpl#toReadableMessage} 就曾拿
     * 「没有可用的聊天模型」去接所有 {@code IllegalStateException}（红线 §10.2 第 7 条：不替 error 编病因）。
     * 带码之后消费者只剩两条路：原样透传 message，或按码分流 —— 都不必猜。
     *
     * <p>🔴 抛点是 {@code AiUtils#validateApiKey}，而它在 {@code src/main} 下的调用点是
     * {@code AiModelFailoverServiceImpl} 的候选链第 5 步与 {@code buildPinnedChain}
     * （2026-10-07 接进去的；此前只有 {@code *ChatModelTests} 那批集成测试在调，
     * 生产路径上「密钥没填」被 {@code catch (Exception)} 吞掉、最终以空链误报成
     * {@link #MODEL_ALL_UNAVAILABLE}「没有可用的聊天模型，请去启用一个」）。
     * 于是本码在<b>聊天路径上真的可达</b>，且 agent 侧 {@code toReadableMessage} 的第一支
     * （{@code ServiceException} → 原样返回 message）会把它逐字送到界面上。
     */
    ErrorCode API_KEY_BLANK = new ErrorCode(1_040_000_003,
            "AI 密钥为空（或仍是占位值），请在「AI 大模型密钥」里填上真实密钥后再试");

    // ========== API 模型 1-040-001-000 ==========
    ErrorCode MODEL_NOT_EXISTS = new ErrorCode(1_040_001_000, "模型不存在!");
    ErrorCode MODEL_DISABLE = new ErrorCode(1_040_001_001, "模型({})已禁用!");
    ErrorCode MODEL_DEFAULT_NOT_EXISTS = new ErrorCode(1_040_001_002, "操作失败，找不到默认模型");
    ErrorCode MODEL_USE_TYPE_ERROR = new ErrorCode(1_040_001_003, "操作失败，该模型的模型类型不正确");
    ErrorCode MODEL_ALL_UNAVAILABLE = new ErrorCode(1_040_001_004, "所有 AI 模型均不可用，最后错误：{}");
    /**
     * 模型额度耗尽（缺陷 D4-28 / 红线 §10.9 第 30 条）
     *
     * <p>单独给一个码，是为了让「额度没了」与「厂商抖动」在库里能分开查：
     * 前者按裁定必须<b>判这一次调用失败</b>、绝不静默换模型救回来，
     * 后者才允许按候选链切换。两者混在 {@link #MODEL_ALL_UNAVAILABLE} 里就再也分不出来了。
     *
     * <p>第一个占位符刻意放「模型编号/模型标识」：agent 侧的失败路径会把本消息原样写进
     * {@code agent_usage_log.error_msg}，于是「哪个模型在哪一刻额度耗尽」不必新增列就落库可查。
     */
    ErrorCode MODEL_QUOTA_EXHAUSTED = new ErrorCode(1_040_001_005, "模型({})额度耗尽，本次调用判失败：{}");
    /**
     * 任务已钉住某个模型，而该模型现在不可用
     *
     * <p>按「同一个任务只准用一个模型」的口径，这里<b>刻意不切换</b> —— 换模型只准发生在任务边界。
     * 所以文案要给出路（开新任务），而不是让人以为是环境没配好模型。
     */
    ErrorCode MODEL_TASK_PINNED_UNAVAILABLE = new ErrorCode(1_040_001_006,
            "本次任务已钉住模型({})，它现在不可用；按「同一任务只用一个模型」的口径不切换，请开一个新任务");
    /**
     * 启用中的向量模型一个都不可用
     *
     * <p>与 {@link #MODEL_ALL_UNAVAILABLE} 分开，是因为两者的<b>出路完全不同</b>：
     * 对话模型不可用通常是「去启用一个」，而向量模型这边实测的形态是
     * 「9 个都启用着，其中 6 个根本不是本仓向量代码路径能打的模型」——
     * 出路是「把这 6 个的模型类型改对 / 停用」，而不是「再启用一个」。
     * 混成一句话，管理员会照着「去启用一个」的方向反复启停本来就启用着的模型。
     *
     * <p>🔴 第二个占位符必须逐个列出<b>试过哪些、各自的厂商原文</b>，不能只给最后一个。
     * 只给最后一个的话，「额度耗尽」会被后面一个「url error」盖掉，
     * 而前者才是真正要处置的那条（充值 / 关掉「仅用免费额度」）。
     *
     * <p>🔴 前两个占位符刻意分成「启用 N 个」与「其中密钥可用 M 个」：
     * {@code M == 0} 时病因是密钥被禁用（去密钥页启用），{@code M > 0} 时才是模型本身的问题。
     * 只报一个总数的话，这两种完全不同的修法会长成同一句话。
     */
    ErrorCode EMBEDDING_MODEL_ALL_UNAVAILABLE = new ErrorCode(1_040_001_007,
            "启用中的 {} 个向量模型（其中密钥可用 {} 个）全部探不通：{}");

    // ========== API 聊天角色 1-040-002-000 ==========
    ErrorCode CHAT_ROLE_NOT_EXISTS = new ErrorCode(1_040_002_000, "聊天角色不存在");
    ErrorCode CHAT_ROLE_DISABLE = new ErrorCode(1_040_002_001, "聊天角色({})已禁用!");

    // ========== API 聊天会话 1-040-003-000 ==========
    ErrorCode CHAT_CONVERSATION_NOT_EXISTS = new ErrorCode(1_040_003_000, "对话不存在!");
    ErrorCode CHAT_CONVERSATION_MODEL_ERROR = new ErrorCode(1_040_003_001, "操作失败，该聊天模型的配置不完整");

    // ========== API 聊天消息 1-040-004-000 ==========
    ErrorCode CHAT_MESSAGE_NOT_EXIST = new ErrorCode(1_040_004_000, "消息不存在!");
    ErrorCode CHAT_STREAM_ERROR = new ErrorCode(1_040_004_001, "对话生成异常!");

    // ========== API 绘画 1-040-005-000 ==========
    ErrorCode IMAGE_NOT_EXISTS = new ErrorCode(1_040_005_000, "图片不存在!");
    ErrorCode IMAGE_MIDJOURNEY_SUBMIT_FAIL = new ErrorCode(1_040_005_001, "Midjourney 提交失败!原因：{}");
    ErrorCode IMAGE_CUSTOM_ID_NOT_EXISTS = new ErrorCode(1_040_005_002, "Midjourney 按钮 customId 不存在! {}");

    // ========== API 音乐 1-040-006-000 ==========
    ErrorCode MUSIC_NOT_EXISTS = new ErrorCode(1_040_006_000, "音乐不存在!");

    // ========== API 写作 1-040-007-000 ==========
    ErrorCode WRITE_NOT_EXISTS = new ErrorCode(1_040_007_000, "作文不存在!");
    ErrorCode WRITE_STREAM_ERROR = new ErrorCode(1_040_007_001, "写作生成异常!");

    // ========== API 思维导图 1-040-008-000 ==========
    ErrorCode MIND_MAP_NOT_EXISTS = new ErrorCode(1_040_008_000, "思维导图不存在!");

    // ========== API 知识库 1-040-009-000 ==========
    ErrorCode KNOWLEDGE_NOT_EXISTS = new ErrorCode(1_040_009_000, "知识库不存在!");

    ErrorCode KNOWLEDGE_DOCUMENT_NOT_EXISTS = new ErrorCode(1_040_009_101, "文档不存在!");
    ErrorCode KNOWLEDGE_DOCUMENT_FILE_EMPTY = new ErrorCode(1_040_009_102, "文档内容为空!");
    ErrorCode KNOWLEDGE_DOCUMENT_FILE_DOWNLOAD_FAIL = new ErrorCode(1_040_009_103, "文件下载失败!");
    ErrorCode KNOWLEDGE_DOCUMENT_FILE_READ_FAIL = new ErrorCode(1_040_009_104, "文档加载失败!");

    ErrorCode KNOWLEDGE_SEGMENT_NOT_EXISTS = new ErrorCode(1_040_009_202, "段落不存在!");
    ErrorCode KNOWLEDGE_SEGMENT_CONTENT_TOO_LONG = new ErrorCode(1_040_009_203, "内容 Token 数为 {}，超过最大限制 {}");

    // ========== AI 工具 1-040-010-000 ==========
    ErrorCode TOOL_NOT_EXISTS = new ErrorCode(1_040_010_000, "工具不存在");
    ErrorCode TOOL_NAME_NOT_EXISTS = new ErrorCode(1_040_010_001, "工具({})找不到 Bean");

    // ========== 联网搜索 1-040-011-000 ==========
    /**
     * 联网搜索这一步失败了，占位符里是<b>厂商自己的话</b>
     *
     * <p>🔴 单独给一个码，理由与 {@link #API_KEY_BLANK} 完全同一条（台账 D4-29）：
     * 这三处原先抛的是<b>没有码</b>的 {@code IllegalStateException}，
     * 于是它一路走到 {@code GlobalExceptionHandler#defaultExceptionHandler} ——
     * 那一支<b>不透传 message</b>，返回的是 {@code INTERNAL_SERVER_ERROR(500, "系统异常")}。
     * 用户勾了「联网搜索」发一句话，博查那边密钥失效 / 余额不足 / 限流，
     * 界面上只出现「系统异常」四个字：既看不出是搜索这一步，也看不出厂商说了什么。
     * 厂商原文原先<b>只在服务端日志里</b>（{@code log.error} 那一行），
     * 于是排查必须先猜到去翻日志、再猜到哪一条日志 —— 而「系统异常」这句话把方向指向了平台自身。
     *
     * <p>⚠️ 占位符里放的是<b>引用</b>不是<b>解释</b>：红线 §10.2 第 7 条禁止替 error 编病因，
     * 所以这里逐字带上厂商返回的状态码与响应体（截断到 500 字，防止代理返回的整页 HTML
     * 灌进界面与 {@code infra_api_error_log}），而不去猜「大概是密钥不对」。
     */
    ErrorCode WEB_SEARCH_FAIL = new ErrorCode(1_040_011_000, "联网搜索失败：{}");

}
