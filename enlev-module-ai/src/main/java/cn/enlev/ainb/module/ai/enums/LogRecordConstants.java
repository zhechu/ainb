package cn.enlev.ainb.module.ai.enums;

/**
 * AI 操作日志枚举
 * 目的：统一管理，也减少 Service 里各种“复杂”字符串
 *
 * <p>🔴 为什么 AI 模型 / 密钥也要有：这两张表是**共享配置**，改一下就会换掉所有调用方
 * （聊天、意图分类、编排、节点执行）实际用的模型，而 2026-10-08 之前管理端改它们在
 * {@code system_operate_log} 里留 0 行痕迹 —— 那一轮 T1 跑到一半模型 76 被停用，
 * 一个任务里出现 3 个模型、27 分钟真跑整轮作废，事后连「谁改的、什么时候改的」都问不出来，
 * 只能记 ⚠️ 无法判定。
 *
 * <p>🔴 密钥的 {@code apiKey} 字段**永远不进日志**，见 AiApiKeySaveReqVO 上的说明。
 */
public interface LogRecordConstants {

    // ======================= AI_MODEL 模型 =======================

    String AI_MODEL_TYPE = "AI 模型";
    String AI_MODEL_CREATE_SUB_TYPE = "创建模型";
    String AI_MODEL_CREATE_SUCCESS = "创建了模型【{{#model.name}}】";
    String AI_MODEL_UPDATE_SUB_TYPE = "更新模型";
    String AI_MODEL_UPDATE_SUCCESS = "更新了模型【{{#model.name}}】: {_DIFF{#updateReqVO}}";
    String AI_MODEL_UPDATE_STATUS_SUB_TYPE = "启用/停用模型";
    String AI_MODEL_UPDATE_STATUS_SUCCESS = "更新了模型【{{#model.name}}】: {_DIFF{#updateReqVO}}";
    String AI_MODEL_DELETE_SUB_TYPE = "删除模型";
    String AI_MODEL_DELETE_SUCCESS = "删除了模型【{{#model.name}}】";

    // ======================= AI_API_KEY 密钥 =======================

    String AI_API_KEY_TYPE = "AI API 密钥";
    String AI_API_KEY_CREATE_SUB_TYPE = "创建密钥";
    String AI_API_KEY_CREATE_SUCCESS = "创建了 API 密钥【{{#apiKey.name}}】";
    String AI_API_KEY_UPDATE_SUB_TYPE = "更新密钥";
    String AI_API_KEY_UPDATE_SUCCESS = "更新了 API 密钥【{{#apiKey.name}}】: {_DIFF{#updateReqVO}}";
    String AI_API_KEY_UPDATE_STATUS_SUB_TYPE = "启用/停用密钥";
    String AI_API_KEY_UPDATE_STATUS_SUCCESS = "更新了 API 密钥【{{#apiKey.name}}】: {_DIFF{#updateReqVO}}";
    String AI_API_KEY_DELETE_SUB_TYPE = "删除密钥";
    String AI_API_KEY_DELETE_SUCCESS = "删除了 API 密钥【{{#apiKey.name}}】";

}
