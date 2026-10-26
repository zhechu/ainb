package cn.enlev.ainb.module.ai.api.model;

import cn.enlev.ainb.module.ai.api.model.dto.AiChatReqDTO;
import cn.enlev.ainb.module.ai.api.model.dto.AiChatRespDTO;
import lombok.Data;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * AI 模型调用 API
 *
 * <p>⚠️ 与 {@code AiRagApi} 一样位于 {@code api/} 目录 —— 这是对 ai 模块的<b>纯新增</b>，
 * 不改它任何既有文件。
 *
 * <h3>与 ai 模块自带的聊天能力有什么不同</h3>
 * {@code AiChatMessageService} 是<b>面向「聊天会话」</b>的：它管会话、存消息、
 * 拼历史、召回知识库。agent 需要的是<b>纯粹的模型调用</b> ——
 * 会话与上下文组装由 agent 自己管（它要按 token 预算截断、要回灌工具结果、
 * 要把历史轮的知识库引用一并带上，这些 ai 模块现有实现都做不到，
 * 其中最后一条还是它自己标了 TODO 的已知缺陷）。
 *
 * <p>所以这里只做一件事：<b>给定消息，调模型，返回结果与用量</b>。
 *
 * @author 走进新时代
 */
public interface AiModelApi {

    /**
     * 同步对话
     *
     * @param reqDTO 请求
     * @return 回复，含 token 用量
     */
    AiChatRespDTO chat(AiChatReqDTO reqDTO);

    /**
     * 流式对话
     *
     * <p>⚠️ 两个已知约束：
     * <ol>
     *   <li><b>usage 通常只在末块回传，且并非所有厂商都给</b>。
     *       调用方要在 {@code doOnComplete} 里取最后一个带 usage 的元素</li>
     *   <li><b>没有显式超时</b>。厂商连接挂起（连接在、不发数据）时
     *       {@code doOnComplete}/{@code doOnError}/{@code doOnCancel} 三条都不触发，
     *       调用方应自行 {@code timeout(...)} 兜底</li>
     * </ol>
     *
     * @param reqDTO 请求
     * @return 流式回复
     */
    Flux<AiChatRespDTO> chatStream(AiChatReqDTO reqDTO);

    /**
     * 获得可用的模型列表
     *
     * 只返回<b>已启用且密钥也已启用</b>的模型 —— 模型本身开着但密钥禁用时，
     * 调用会在运行时才失败，提前过滤掉能省一轮无谓的重试。
     *
     * @param type 模型类型：1=聊天，5=向量。为空返回全部
     * @return 模型列表
     */
    List<AiModelSimpleRespDTO> getEnabledModelList(Integer type);

    /**
     * 选一个<b>现在真能用</b>的向量模型编号
     *
     * <p>🔴 与 {@link #getEnabledModelList} 的区别不是「多一步过滤」，而是<b>会真发一次厂商请求</b>：
     * 「启用中」只说明管理员勾了开关，不说明这个模型打得通。实测（2026-10-25，本机 local）
     * {@code type=5} 且启用的有 9 个，真能走本仓向量代码路径的只有 3 个，其中 1 个额度已尽 ——
     * 剩下 6 个是<b>被登记成向量类型的重排序模型与多模态向量模型</b>，厂商一律回
     * {@code 400 InvalidParameter「url error, please check url」}。
     * 所以「取列表第一个」是抽签，而抽中的多半是坏的。
     *
     * <p>调用方拿到编号之后仍要把真实调用的结果回传（{@code AiEmbeddingFailoverService}），
     * 否则下一次还是从头再抽一遍。
     *
     * @param preferredModelId 首选编号，通常是知识库已经钉住的那个；可为 null（= 直接逐个探测）
     * @return 探通的模型编号
     * @throws cn.enlev.ainb.framework.common.exception.ServiceException
     *         {@code EMBEDDING_MODEL_ALL_UNAVAILABLE}：一个都探不通，消息里逐个列出试过哪些、各自的厂商原文
     */
    Long selectUsableEmbeddingModelId(Long preferredModelId);

    /**
     * 模型简要信息
     */
    @Data
    class AiModelSimpleRespDTO {

        private Long id;
        /** 展示名 */
        private String name;
        /** 模型标识，如 qwen-max-latest */
        private String model;
        /** 平台，如 TongYi */
        private String platform;
        /** 类型：1=聊天，5=向量 */
        private Integer type;
    }

}
