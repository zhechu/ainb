package cn.enlev.ainb.module.ai.service.chat;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.file.FileNameUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.framework.tenant.core.context.TenantContextHolder;
import cn.enlev.ainb.framework.tenant.core.util.TenantUtils;
import cn.enlev.ainb.module.ai.framework.ai.config.EnlevAiProperties;
import cn.enlev.ainb.module.ai.controller.admin.chat.vo.message.AiChatMessagePageReqVO;
import cn.enlev.ainb.module.ai.controller.admin.chat.vo.message.AiChatMessageRespVO;
import cn.enlev.ainb.module.ai.controller.admin.chat.vo.message.AiChatMessageSendReqVO;
import cn.enlev.ainb.module.ai.controller.admin.chat.vo.message.AiChatMessageSendRespVO;
import cn.enlev.ainb.module.ai.dal.dataobject.chat.AiChatConversationDO;
import cn.enlev.ainb.module.ai.dal.dataobject.chat.AiChatMessageDO;
import cn.enlev.ainb.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentDO;
import cn.enlev.ainb.module.ai.dal.dataobject.model.AiChatRoleDO;
import cn.enlev.ainb.module.ai.dal.dataobject.model.AiModelDO;
import cn.enlev.ainb.module.ai.dal.dataobject.model.AiToolDO;
import cn.enlev.ainb.module.ai.dal.mysql.chat.AiChatMessageMapper;
import cn.enlev.ainb.module.ai.enums.ErrorCodeConstants;
import cn.enlev.ainb.module.ai.enums.model.AiModelTypeEnum;
import cn.enlev.ainb.module.ai.enums.model.AiPlatformEnum;
import cn.enlev.ainb.module.ai.framework.ai.core.webserch.AiWebSearchClient;
import cn.enlev.ainb.module.ai.framework.ai.core.webserch.AiWebSearchRequest;
import cn.enlev.ainb.module.ai.framework.ai.core.webserch.AiWebSearchResponse;
import cn.enlev.ainb.module.ai.service.knowledge.AiKnowledgeDocumentService;
import cn.enlev.ainb.module.ai.service.knowledge.AiKnowledgeSegmentService;
import cn.enlev.ainb.module.ai.service.knowledge.bo.AiKnowledgeSegmentSearchReqBO;
import cn.enlev.ainb.module.ai.service.knowledge.bo.AiKnowledgeSegmentSearchRespBO;
import cn.enlev.ainb.module.ai.service.model.AiChatRoleService;
import cn.enlev.ainb.module.ai.service.model.AiModelFailoverService;
import cn.enlev.ainb.module.ai.service.model.AiToolService;
import cn.enlev.ainb.module.ai.service.model.bo.AiModelCandidate;
import cn.enlev.ainb.module.ai.service.model.bo.AiModelInvocationBO;
import cn.enlev.ainb.module.ai.util.AiUtils;
import cn.enlev.ainb.module.ai.util.FileTypeUtils;
import com.google.common.collect.Maps;
import io.modelcontextprotocol.client.McpSyncClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpClientCommonProperties;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.framework.common.pojo.CommonResult.error;
import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;
import static cn.enlev.ainb.framework.common.util.collection.CollectionUtils.convertList;
import static cn.enlev.ainb.framework.common.util.collection.CollectionUtils.convertSet;
import static cn.enlev.ainb.module.ai.enums.ErrorCodeConstants.CHAT_CONVERSATION_NOT_EXISTS;
import static cn.enlev.ainb.module.ai.enums.ErrorCodeConstants.CHAT_MESSAGE_NOT_EXIST;
import static cn.enlev.ainb.module.ai.enums.ErrorCodeConstants.MODEL_ALL_UNAVAILABLE;

/**
 * AI 聊天消息 Service 实现类
 *
 * @author fansili
 */
@Service
@Slf4j
public class AiChatMessageServiceImpl implements AiChatMessageService {

    /**
     * 联网搜索的结束数
     */
    private static final Integer WEB_SEARCH_COUNT = 10;

    // TODO @芋艿：后续优化下对话的 Prompt 整体结构

    /**
     * 知识库转 {@link UserMessage} 的内容模版
     */
    private static final String KNOWLEDGE_USER_MESSAGE_TEMPLATE = "使用 <Reference></Reference> 标记中的内容作为本次对话的参考:\n\n" +
            "%s\n\n" + // 多个 <Reference></Reference> 的拼接
            "回答要求：\n- 避免提及你是从 <Reference></Reference> 获取的知识。";

    private static final String WEB_SEARCH_USER_MESSAGE_TEMPLATE = "使用 <WebSearch></WebSearch> 标记中的内容作为本次对话的参考:\n\n" +
            "%s\n\n" + // 多个 <WebSearch></WebSearch> 的拼接
            "回答要求：\n- 避免提及你是从 <WebSearch></WebSearch> 获取的知识。";

    /**
     * 附件转 ${@link UserMessage} 的内容模版
     */
    @SuppressWarnings("TextBlockMigration")
    private static final String Attachment_USER_MESSAGE_TEMPLATE = "使用 <Attachment></Attachment> 标记用户对话上传的附件内容:\n\n" +
            "%s\n\n" + // 多个 <Attachment></Attachment> 的拼接
            "回答要求：\n- 避免提及 <Attachment></Attachment> 附件的编码格式。";

    @Resource
    private AiChatMessageMapper chatMessageMapper;

    @Resource
    private AiChatConversationService chatConversationService;
    @Resource
    private AiChatRoleService chatRoleService;
    @Resource
    private AiModelFailoverService failoverService;
    @Resource
    private AiKnowledgeSegmentService knowledgeSegmentService;
    @Resource
    private AiKnowledgeDocumentService knowledgeDocumentService;
    @Resource
    private AiToolService toolService;

    @SuppressWarnings("SpringJavaAutowiredFieldsWarningInspection")
    @Autowired(required = false) // 由于 enlev.ai.web-search.enable 配置项，可以关闭 AiWebSearchClient 的功能，所以这里只能不强制注入
    private AiWebSearchClient webSearchClient;

    @SuppressWarnings("SpringJavaAutowiredFieldsWarningInspection")
    @Autowired(required = false) // 由于 enlev.ai.mcp.client.enable 配置项，可以关闭 McpSyncClient 的功能，所以这里只能不强制注入
    private List<McpSyncClient> mcpClients;

    @SuppressWarnings("SpringJavaAutowiredFieldsWarningInspection")
    @Autowired(required = false) // 由于 enlev.ai.mcp.client.enable 配置项，可以关闭 McpSyncClient 的功能，所以这里只能不强制注入
    private McpClientCommonProperties mcpClientCommonProperties;

    @Resource
    private ToolCallbackResolver toolCallbackResolver;

    /** 流式超时配置（B18） */
    @Resource
    private EnlevAiProperties aiProperties;

    // 非流式发送不加事务：方法内含向量库召回、联网搜索、LLM 阻塞调用三次外部 I/O，
    // 加事务会占用一个数据库连接数十秒，高并发时会耗尽连接池。
    // 三次 DB 写（user/assistant insert + content update）无需原子性，流式路径也未加事务。
    public AiChatMessageSendRespVO sendMessage(AiChatMessageSendReqVO sendReqVO, Long userId) {
        // 1.1 校验对话存在
        AiChatConversationDO conversation = chatConversationService
                .validateChatConversationExists(sendReqVO.getConversationId());
        if (ObjUtil.notEqual(conversation.getUserId(), userId)) {
            throw exception(CHAT_CONVERSATION_NOT_EXISTS);
        }
        List<AiChatMessageDO> historyMessages = chatMessageMapper.selectListByConversationId(conversation.getId());
        // 1.2 构建模型候选链：默认「启用 + 密钥启用 + sort 最前」，调用失败自动切换下一个候选；
        //     会话快照的模型不可用时不再报错，静默降级到候选链（warn 日志在候选链构建处）
        List<AiModelCandidate> candidates = failoverService.buildCandidateChain(
                conversation.getModelId(), AiModelTypeEnum.CHAT.getType());
        if (candidates.isEmpty()) {
            throw exception(MODEL_ALL_UNAVAILABLE,
                    "会话模型(" + conversation.getModelId() + ")及所有备选模型均不可用");
        }
        AiModelDO model = candidates.get(0).getModel();

        // 2.1 知识库召回
        List<AiKnowledgeSegmentSearchRespBO> knowledgeSegments = recallKnowledgeSegment(
                sendReqVO.getContent(), conversation);

        // 2.2 联网搜索
        AiWebSearchResponse webSearchResponse = Boolean.TRUE.equals(sendReqVO.getUseSearch()) && webSearchClient != null ?
                webSearchClient.search(new AiWebSearchRequest().setQuery(sendReqVO.getContent())
                        .setSummary(true).setCount(WEB_SEARCH_COUNT)) : null;

        // 3. 插入 user 发送消息
        AiChatMessageDO userMessage = createChatMessage(conversation.getId(), null, model,
                userId, conversation.getRoleId(), MessageType.USER, sendReqVO.getContent(), sendReqVO.getUseContext(),
                null, sendReqVO.getAttachmentUrls(), null);

        // 4.1 插入 assistant 接收消息
        AiChatMessageDO assistantMessage = createChatMessage(conversation.getId(), userMessage.getId(), model,
                userId, conversation.getRoleId(), MessageType.ASSISTANT, "", sendReqVO.getUseContext(),
                knowledgeSegments, null, webSearchResponse);

        // 4.2 创建 chat 需要的 Prompt，并进行调用（单候选失败自动切换下一个）
        AiModelInvocationBO<ChatResponse> invocation = failoverService.callWithFallback(candidates,
                candidate -> candidate.getChatModel().call(buildPrompt(
                        conversation, historyMessages, knowledgeSegments, webSearchResponse,
                        candidate.getModel(), sendReqVO)));
        ChatResponse chatResponse = invocation.getPayload();
        AiModelDO usedModel = invocation.getModel();

        // 4.3 更新响应内容；故障切换后回写实际使用的模型（会话快照不改，切换是单次请求行为）
        String newContent = AiUtils.getChatResponseContent(chatResponse);
        String newReasoningContent = AiUtils.getChatResponseReasoningContent(chatResponse);
        AiChatMessageDO updateObj = new AiChatMessageDO().setId(assistantMessage.getId())
                .setContent(newContent).setReasoningContent(newReasoningContent);
        if (!usedModel.getId().equals(model.getId())) {
            log.info("[sendMessage][会话({}) 实际应答模型由({})切换为({}/{})]",
                    conversation.getId(), model.getId(), usedModel.getId(), usedModel.getName());
            updateObj.setModelId(usedModel.getId()).setModel(usedModel.getModel());
        }
        chatMessageMapper.updateById(updateObj);
        // 4.4 响应结果
        Map<Long, AiKnowledgeDocumentDO> documentMap = knowledgeDocumentService.getKnowledgeDocumentMap(
                convertSet(knowledgeSegments, AiKnowledgeSegmentSearchRespBO::getDocumentId));
        List<AiChatMessageRespVO.KnowledgeSegment> segments = BeanUtils.toBean(knowledgeSegments,
                AiChatMessageRespVO.KnowledgeSegment.class, segment -> {
                    AiKnowledgeDocumentDO document = documentMap.get(segment.getDocumentId());
                    segment.setDocumentName(document != null ? document.getName() : null);
                });
        return new AiChatMessageSendRespVO()
                .setSend(BeanUtils.toBean(userMessage, AiChatMessageSendRespVO.Message.class))
                .setReceive(BeanUtils.toBean(assistantMessage, AiChatMessageSendRespVO.Message.class)
                        .setContent(newContent).setSegments(segments)
                        .setWebSearchPages(webSearchResponse != null ? webSearchResponse.getLists() : null));
    }

    @Override
    public Flux<CommonResult<AiChatMessageSendRespVO>> sendChatMessageStream(AiChatMessageSendReqVO sendReqVO,
            Long userId) {
        // 1.1 校验对话存在
        AiChatConversationDO conversation = chatConversationService
                .validateChatConversationExists(sendReqVO.getConversationId());
        if (ObjUtil.notEqual(conversation.getUserId(), userId)) {
            throw exception(CHAT_CONVERSATION_NOT_EXISTS);
        }
        List<AiChatMessageDO> historyMessages = chatMessageMapper.selectListByConversationId(conversation.getId());
        // 1.2 构建模型候选链：默认「启用 + 密钥启用 + sort 最前」，首包前失败自动切换下一个候选
        List<AiModelCandidate> candidates = failoverService.buildCandidateChain(
                conversation.getModelId(), AiModelTypeEnum.CHAT.getType());
        if (candidates.isEmpty()) {
            throw exception(MODEL_ALL_UNAVAILABLE,
                    "会话模型(" + conversation.getModelId() + ")及所有备选模型均不可用");
        }
        AiModelDO model = candidates.get(0).getModel();

        // 2.1 知识库找回
        List<AiKnowledgeSegmentSearchRespBO> knowledgeSegments = recallKnowledgeSegment(
                sendReqVO.getContent(), conversation);

        // 2.2 联网搜索
        AiWebSearchResponse webSearchResponse = Boolean.TRUE.equals(sendReqVO.getUseSearch()) && webSearchClient != null ?
                webSearchClient.search(new AiWebSearchRequest().setQuery(sendReqVO.getContent())
                        .setSummary(true).setCount(WEB_SEARCH_COUNT)) : null;

        // 3. 插入 user 发送消息
        AiChatMessageDO userMessage = createChatMessage(conversation.getId(), null, model,
                userId, conversation.getRoleId(), MessageType.USER, sendReqVO.getContent(), sendReqVO.getUseContext(),
                null, sendReqVO.getAttachmentUrls(), null);

        // 4.1 插入 assistant 接收消息
        AiChatMessageDO assistantMessage = createChatMessage(conversation.getId(), userMessage.getId(), model,
                userId, conversation.getRoleId(), MessageType.ASSISTANT, "", sendReqVO.getUseContext(),
                knowledgeSegments, null, webSearchResponse);

        // 4.2 构建 Prompt，并进行调用：仅「首包发射前」失败的候选会被切换（首包后无法撤回前端已收到的内容）；
        //     timeout 下推到单候选，连接挂起（连接在、不发数据）也能触发切换，外层总兜底保持不变
        AtomicReference<AiModelDO> usedModelRef = new AtomicReference<>();
        Flux<ChatResponse> streamResponse = failoverService.streamWithFallback(candidates,
                        candidate -> candidate.getChatModel().stream(buildPrompt(
                                        conversation, historyMessages, knowledgeSegments, webSearchResponse,
                                        candidate.getModel(), sendReqVO))
                                .timeout(aiProperties.getStream().getTimeout()))
                .map(invocation -> {
                    usedModelRef.compareAndSet(null, invocation.getModel());
                    return invocation.getPayload();
                });

        // 4.3 流式返回
        // 🔴 租户上下文：在这里（仍是 Servlet 线程）抓一份快照，后面所有回调都显式贴回去。
        //    原先各回调用的是 TenantUtils.executeIgnore —— 那是把租户隔离整个关掉
        //    （TenantDatabaseInterceptor 直接 return true、不拼 tenant_id）。
        //    对 updateById/deleteById 这种带主键的写，影响是「跨租户也能改到」；
        //    真正危险的是它成了本模块的惯用手法，一旦被抄到按条件查询的地方就是跨租户泄漏。
        //    换成显式重建之后，语义与段式路径一致，B15 的「两条路径租户语义不同」也随之消失。
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        StringBuffer contentBuffer = new StringBuffer();
        StringBuffer reasoningContentBuffer = new StringBuffer();

        // 防止执行多次知识库和联网搜索
        AtomicBoolean firstExecuteFlag = new AtomicBoolean(true);
        AtomicReference<List<AiChatMessageRespVO.KnowledgeSegment>> cacheSegments = new AtomicReference<>();
        AtomicReference<List<AiWebSearchResponse.WebPage>> cacheWebSearchPages = new AtomicReference<>();
        return streamResponse.map(chunk -> {
            // 仅首次：返回知识库、联网搜索
            if (StrUtil.isEmpty(contentBuffer)) {
                if (firstExecuteFlag.compareAndSet(true, false)) { // CAS 操作，确保仅执行一次
                    // 🔴 这一段跑在厂商 HTTP 客户端的 IO 线程上 —— 那是应用启动时就建好的池化线程，
                    //    继承不到任何请求上下文，直接读 TenantContextHolder 必然是 null。
                    //    所以用上面抓的快照显式重建，而不是 executeIgnore 把隔离关掉：
                    //    这是一次【按 documentId 集合查询】，忽略租户等于让条件里不带 tenant_id。
                    Map<Long, AiKnowledgeDocumentDO> documentMap = TenantUtils.execute(tenantId,
                            () -> knowledgeDocumentService.getKnowledgeDocumentMap(
                                    convertSet(knowledgeSegments, AiKnowledgeSegmentSearchRespBO::getDocumentId)));
                    cacheSegments.set(BeanUtils.toBean(knowledgeSegments, AiChatMessageRespVO.KnowledgeSegment.class, segment -> {
                        AiKnowledgeDocumentDO document = documentMap.get(segment.getDocumentId());
                        segment.setDocumentName(document != null ? document.getName() : null);
                    }));
                    if (webSearchResponse != null) {
                        cacheWebSearchPages.set(webSearchResponse.getLists());
                    }
                }
            }
            // 响应结果
            String newContent = AiUtils.getChatResponseContent(chunk);
            String newReasoningContent = AiUtils.getChatResponseReasoningContent(chunk);
            if (StrUtil.isNotEmpty(newContent)) {
                contentBuffer.append(newContent);
            }
            if (StrUtil.isNotEmpty(newReasoningContent)) {
                reasoningContentBuffer.append(newReasoningContent);
            }
            return success(new AiChatMessageSendRespVO()
                    .setSend(BeanUtils.toBean(userMessage, AiChatMessageSendRespVO.Message.class))
                    .setReceive(BeanUtils.toBean(assistantMessage, AiChatMessageSendRespVO.Message.class)
                            .setContent(StrUtil.nullToDefault(newContent, "")) // 避免 null 的 情况
                            .setReasoningContent(StrUtil.nullToDefault(newReasoningContent, "")) // 避免 null 的 情况
                            .setSegments(cacheSegments.get()).setWebSearchPages(cacheWebSearchPages.get()))); // 知识库 + 联网搜索
        })
                // 🔴 B18 兜底：量的是【相邻两个分片之间】的间隔。
                //    厂商连接挂起时（连接在、不发数据）本来三条终止回调一条都不会触发，
                //    超时在这里把它转成一个 TimeoutException，走下面的 doOnError —— 于是
                //    assistant 空记录会被删掉、SSE 正常收尾，而不是永远悬着。
                .timeout(aiProperties.getStream().getTimeout())
                .doOnComplete(() -> {
            // 用快照显式重建租户，而不是忽略租户（理由见上面抓 tenantId 处）
            TenantUtils.execute(tenantId, () -> {
                AiChatMessageDO updateObj = new AiChatMessageDO().setId(assistantMessage.getId())
                        .setContent(contentBuffer.toString())
                        .setReasoningContent(reasoningContentBuffer.toString());
                // 故障切换后回写实际使用的模型（会话快照不改，切换是单次请求行为）
                AiModelDO usedModel = usedModelRef.get();
                if (usedModel != null && !usedModel.getId().equals(model.getId())) {
                    log.info("[sendChatMessageStream][会话({}) 实际应答模型由({})切换为({}/{})]",
                            conversation.getId(), model.getId(), usedModel.getId(), usedModel.getName());
                    updateObj.setModelId(usedModel.getId()).setModel(usedModel.getModel());
                }
                chatMessageMapper.updateById(updateObj);
            });
        }).doOnError(throwable -> {
            log.error("[sendChatMessageStream][userId({}) sendReqVO({}) 发生异常]", userId, sendReqVO, throwable);
            TenantUtils.execute(tenantId, () -> {
                // 如果有内容，则更新内容
                if (StrUtil.isNotEmpty(contentBuffer)) {
                    chatMessageMapper.updateById(new AiChatMessageDO().setId(assistantMessage.getId())
                            .setContent(contentBuffer.toString()).setReasoningContent(reasoningContentBuffer.toString()));
                } else {
                    // 否则，则进行删除
                    chatMessageMapper.deleteById(assistantMessage.getId());
                }
            });
        }).doOnCancel(() -> {
            log.info("[sendChatMessageStream][userId({}) sendReqVO({}) 取消请求]", userId, sendReqVO);
            TenantUtils.execute(tenantId, () -> {
                // 如果有内容，则更新内容
                if (StrUtil.isNotEmpty(contentBuffer)) {
                    chatMessageMapper.updateById(new AiChatMessageDO().setId(assistantMessage.getId())
                            .setContent(contentBuffer.toString()).setReasoningContent(reasoningContentBuffer.toString()));
                } else {
                    // 否则，则进行删除
                    chatMessageMapper.deleteById(assistantMessage.getId());
                }
            });
        }).onErrorResume(error -> Flux.just(error(ErrorCodeConstants.CHAT_STREAM_ERROR)));
    }

    private List<AiKnowledgeSegmentSearchRespBO> recallKnowledgeSegment(String content,
            AiChatConversationDO conversation) {
        // 1. 查询聊天角色
        if (conversation == null || conversation.getRoleId() == null) {
            return Collections.emptyList();
        }
        AiChatRoleDO role = chatRoleService.getChatRole(conversation.getRoleId());
        if (role == null || CollUtil.isEmpty(role.getKnowledgeIds())) {
            return Collections.emptyList();
        }

        // 2. 遍历召回
        List<AiKnowledgeSegmentSearchRespBO> knowledgeSegments = new ArrayList<>();
        for (Long knowledgeId : role.getKnowledgeIds()) {
            knowledgeSegments.addAll(knowledgeSegmentService.searchKnowledgeSegment(new AiKnowledgeSegmentSearchReqBO()
                    .setKnowledgeId(knowledgeId).setContent(content)));
        }
        return knowledgeSegments;
    }

    private Prompt buildPrompt(AiChatConversationDO conversation, List<AiChatMessageDO> messages,
                               List<AiKnowledgeSegmentSearchRespBO> knowledgeSegments,
                               AiWebSearchResponse webSearchResponse,
                               AiModelDO model, AiChatMessageSendReqVO sendReqVO) {
        List<Message> chatMessages = new ArrayList<>();
        // 1.1 System Context 角色设定
        if (StrUtil.isNotBlank(conversation.getSystemMessage())) {
            chatMessages.add(new SystemMessage(conversation.getSystemMessage()));
        }

        // 1.2 历史 history message 历史消息
        List<AiChatMessageDO> contextMessages = filterContextMessages(messages, conversation, sendReqVO);
        contextMessages.forEach(message -> {
            chatMessages.add(AiUtils.buildMessage(message.getType(), message.getContent()));
            UserMessage attachmentUserMessage = buildAttachmentUserMessage(message.getAttachmentUrls());
            if (attachmentUserMessage != null) {
                chatMessages.add(attachmentUserMessage);
            }
            // TODO @芋艿：历史的知识库；历史的搜索，要不要拼接？
        });

        // 1.3 当前 user message 新发送消息
        chatMessages.add(new UserMessage(sendReqVO.getContent()));

        // 1.4 知识库，通过 UserMessage 实现
        if (CollUtil.isNotEmpty(knowledgeSegments)) {
            String reference = knowledgeSegments.stream()
                    .map(segment -> "<Reference>" + segment.getContent() + "</Reference>")
                    .collect(Collectors.joining("\n\n"));
            chatMessages.add(new UserMessage(String.format(KNOWLEDGE_USER_MESSAGE_TEMPLATE, reference)));
        }

        // 1.5 联网搜索，通过 UserMessage 实现
        if (webSearchResponse != null && CollUtil.isNotEmpty(webSearchResponse.getLists())) {
            String webSearch = webSearchResponse.getLists().stream()
                    .map(page -> {
                        String summary = StrUtil.isNotEmpty(page.getSummary()) ?
                                "\nSummary: " + page.getSummary() : "";
                        return "<WebSearch title=\"" + page.getTitle() + "\" url=\"" + page.getUrl() + "\">"
                                + StrUtil.blankToDefault(page.getSummary(), page.getSnippet()) + "</WebSearch>";
                    })
                    .collect(Collectors.joining("\n\n"));
            chatMessages.add(new UserMessage(String.format(WEB_SEARCH_USER_MESSAGE_TEMPLATE, webSearch)));
        }

        // 1.6 附件，通过 UserMessage 实现
        if (CollUtil.isNotEmpty(sendReqVO.getAttachmentUrls())) {
            UserMessage attachmentUserMessage = buildAttachmentUserMessage(sendReqVO.getAttachmentUrls());
            if (attachmentUserMessage != null) {
                chatMessages.add(attachmentUserMessage);
            }
        }

        // 2.1 查询 tool 工具
        List<ToolCallback> toolCallbacks = getToolCallbackListByRoleId(conversation.getRoleId());
        Map<String,Object> toolContext = CollUtil.isNotEmpty(toolCallbacks) ? AiUtils.buildCommonToolContext()
                : Map.of();
        // 2.2 构建 ChatOptions 对象
        AiPlatformEnum platform = AiPlatformEnum.validatePlatform(model.getPlatform());
        ChatOptions chatOptions = AiUtils.buildChatOptions(platform, model.getModel(),
                conversation.getTemperature(), conversation.getMaxTokens(),
                toolCallbacks, toolContext);
        return new Prompt(chatMessages, chatOptions);
    }

    private List<ToolCallback> getToolCallbackListByRoleId(Long roleId) {
        if (roleId == null) {
            return null;
        }
        AiChatRoleDO chatRole = chatRoleService.getChatRole(roleId);
        if (chatRole == null) {
            return null;
        }
        List<ToolCallback> toolCallbacks = new ArrayList<>();
        // 1. 通过 toolIds
        if (CollUtil.isNotEmpty(chatRole.getToolIds())) {
            Set<String> toolNames = convertSet(toolService.getToolList(chatRole.getToolIds()), AiToolDO::getName);
            toolNames.forEach(toolName -> {
                ToolCallback toolCallback = toolCallbackResolver.resolve(toolName);
                if (toolCallback != null) {
                    toolCallbacks.add(toolCallback);
                }
            });
        }
        // 2. 通过 mcpClients
        if (CollUtil.isNotEmpty(mcpClients) && CollUtil.isNotEmpty(chatRole.getMcpClientNames())) {
            chatRole.getMcpClientNames().forEach(mcpClientName -> {
                // 2.1 标准化名字，参考 McpClientAutoConfiguration 的 connectedClientName 方法
                String finalMcpClientName = mcpClientCommonProperties.getName() + " - " + mcpClientName;
                // 2.2 匹配对应的 McpSyncClient
                mcpClients.forEach(mcpClient -> {
                    if (ObjUtil.notEqual(mcpClient.getClientInfo().name(), finalMcpClientName)) {
                        return;
                    }
                    ToolCallback[] mcpToolCallBacks = new SyncMcpToolCallbackProvider(mcpClient).getToolCallbacks();
                    CollUtil.addAll(toolCallbacks, mcpToolCallBacks);
                });
            });
        }
        return toolCallbacks;
    }

    /**
     * 从历史消息中，获得倒序的 n 组消息作为消息上下文
     * <p>
     * n 组：指的是 user + assistant 形成一组
     *
     * @param messages     消息列表
     * @param conversation 对话
     * @param sendReqVO    发送请求
     * @return 消息上下文
     */
    private List<AiChatMessageDO> filterContextMessages(List<AiChatMessageDO> messages,
            AiChatConversationDO conversation,
            AiChatMessageSendReqVO sendReqVO) {
        if (conversation.getMaxContexts() == null || ObjUtil.notEqual(sendReqVO.getUseContext(), Boolean.TRUE)) {
            return Collections.emptyList();
        }
        List<AiChatMessageDO> contextMessages = new ArrayList<>(conversation.getMaxContexts() * 2);
        for (int i = messages.size() - 1; i >= 0; i--) {
            AiChatMessageDO assistantMessage = CollUtil.get(messages, i);
            if (assistantMessage == null || assistantMessage.getReplyId() == null) {
                continue;
            }
            AiChatMessageDO userMessage = CollUtil.get(messages, i - 1);
            if (userMessage == null
                    || ObjUtil.notEqual(assistantMessage.getReplyId(), userMessage.getId())
                    || StrUtil.isEmpty(assistantMessage.getContent())) {
                continue;
            }
            // 由于后续要 reverse 反转，所以先添加 assistantMessage
            contextMessages.add(assistantMessage);
            contextMessages.add(userMessage);
            // 超过最大上下文，结束
            if (contextMessages.size() >= conversation.getMaxContexts() * 2) {
                break;
            }
        }
        Collections.reverse(contextMessages);
        return contextMessages;
    }

    private UserMessage buildAttachmentUserMessage(List<String> attachmentUrls) {
        if (CollUtil.isEmpty(attachmentUrls)) {
            return null;
        }
        // 读取文件内容
        Map<String, String> attachmentContents = Maps.newLinkedHashMapWithExpectedSize(attachmentUrls.size());
        for (String attachmentUrl : attachmentUrls) {
            try {
                String name = FileNameUtil.getName(attachmentUrl);
                String mineType = FileTypeUtils.getMineType(name);
                String content;
                if (FileTypeUtils.isImage(mineType)) {
                    // 特殊：图片则转为 Base64
                    byte[] bytes = HttpUtil.downloadBytes(attachmentUrl);
                    content = Base64.encode(bytes);
                } else {
                    content = knowledgeDocumentService.readUrl(attachmentUrl);
                }
                if (StrUtil.isNotEmpty(content)) {
                    attachmentContents.put(name, content);
                }
            } catch (Exception e) {
                log.error("[buildAttachmentUserMessage][读取附件({}) 发生异常]", attachmentUrl, e);
            }
        }
        if (CollUtil.isEmpty(attachmentContents)) {
            return null;
        }

        // 拼接 UserMessage 消息
        String attachment = attachmentContents.entrySet().stream()
                .map(entry -> "<Attachment name=\"" + entry.getKey() + "\">" + entry.getValue() + "</Attachment>")
                .collect(Collectors.joining("\n\n"));
        return new UserMessage(String.format(Attachment_USER_MESSAGE_TEMPLATE, attachment));
    }

    private AiChatMessageDO createChatMessage(Long conversationId, Long replyId,
                                              AiModelDO model, Long userId, Long roleId,
                                              MessageType messageType, String content, Boolean useContext,
                                              List<AiKnowledgeSegmentSearchRespBO> knowledgeSegments,
                                              List<String> attachmentUrls,
                                              AiWebSearchResponse webSearchResponse) {
        AiChatMessageDO message = new AiChatMessageDO().setConversationId(conversationId).setReplyId(replyId)
                .setModel(model.getModel()).setModelId(model.getId()).setUserId(userId).setRoleId(roleId)
                .setType(messageType.getValue()).setContent(content).setUseContext(useContext)
                .setSegmentIds(convertList(knowledgeSegments, AiKnowledgeSegmentSearchRespBO::getId))
                .setAttachmentUrls(attachmentUrls);
        if (webSearchResponse != null) {
            message.setWebSearchPages(webSearchResponse.getLists());
        }
        message.setCreateTime(LocalDateTime.now());
        chatMessageMapper.insert(message);
        return message;
    }

    @Override
    public List<AiChatMessageDO> getChatMessageListByConversationId(Long conversationId) {
        return chatMessageMapper.selectListByConversationId(conversationId);
    }

    @Override
    public AiChatMessageDO getChatMessage(Long id) {
        return chatMessageMapper.selectById(id);
    }

    @Override
    public void deleteChatMessage(Long id, Long userId) {
        // 1. 校验消息存在
        AiChatMessageDO message = chatMessageMapper.selectById(id);
        if (message == null || ObjUtil.notEqual(message.getUserId(), userId)) {
            throw exception(CHAT_MESSAGE_NOT_EXIST);
        }
        // 2. 执行删除
        chatMessageMapper.deleteById(id);
    }

    @Override
    public void deleteChatMessageByConversationId(Long conversationId, Long userId) {
        // 1. 校验消息存在
        List<AiChatMessageDO> messages = chatMessageMapper.selectListByConversationId(conversationId);
        if (CollUtil.isEmpty(messages) || ObjUtil.notEqual(messages.get(0).getUserId(), userId)) {
            throw exception(CHAT_MESSAGE_NOT_EXIST);
        }
        // 2. 执行删除
        chatMessageMapper.deleteByIds(convertList(messages, AiChatMessageDO::getId));
    }

    @Override
    public void deleteChatMessageByAdmin(Long id) {
        // 1. 校验消息存在
        AiChatMessageDO message = chatMessageMapper.selectById(id);
        if (message == null) {
            throw exception(CHAT_MESSAGE_NOT_EXIST);
        }
        // 2. 执行删除
        chatMessageMapper.deleteById(id);
    }

    @Override
    public Integer getChatMessageCount(Long conversationId) {
        return chatMessageMapper.selectCount(AiChatMessageDO::getConversationId, conversationId).intValue();
    }

    @Override
    public Map<Long, Integer> getChatMessageCountMap(Collection<Long> conversationIds) {
        return chatMessageMapper.selectCountMapByConversationId(conversationIds);
    }

    @Override
    public PageResult<AiChatMessageDO> getChatMessagePage(AiChatMessagePageReqVO pageReqVO) {
        return chatMessageMapper.selectPage(pageReqVO);
    }

}
