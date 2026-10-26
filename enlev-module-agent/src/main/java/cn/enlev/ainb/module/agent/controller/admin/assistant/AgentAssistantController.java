package cn.enlev.ainb.module.agent.controller.admin.assistant;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.module.agent.controller.admin.assistant.vo.AgentChatResumeReqVO;
import cn.enlev.ainb.module.agent.controller.admin.assistant.vo.AgentChatRunWorkflowReqVO;
import cn.enlev.ainb.module.agent.controller.admin.assistant.vo.AgentChatSendReqVO;
import cn.enlev.ainb.module.agent.controller.admin.assistant.vo.AgentConversationRespVO;
import cn.enlev.ainb.module.agent.controller.admin.assistant.vo.AgentMessageRespVO;
import cn.enlev.ainb.module.agent.dal.dataobject.conversation.AgentConversationDO;
import cn.enlev.ainb.module.agent.dal.dataobject.conversation.AgentMessageDO;
import cn.enlev.ainb.module.agent.framework.agent.core.chat.AgentAnswerStreamSplitter.Suggestion;
import cn.enlev.ainb.module.agent.framework.agent.core.gateway.AgentExecutionContext;
import cn.enlev.ainb.module.agent.service.chat.AgentAssistantService;
import cn.enlev.ainb.module.agent.service.chat.AgentAssistantService.AgentChatEvent;
import cn.enlev.ainb.module.agent.service.chat.AgentAssistantService.AgentChatReqBO;
import cn.enlev.ainb.module.agent.service.conversation.AgentConversationService;
import cn.enlev.ainb.module.ai.api.rag.dto.AiRagSegmentRespDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

import static cn.enlev.ainb.framework.common.exception.enums.GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR;
import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;
import static cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

/**
 * AI 助手接口
 *
 * <h3>入口权限统一是 {@code agent:assistant:use}</h3>
 * 不按「发消息 / 看会话 / 删会话」逐个拆权限点：它们都只作用于<b>用户自己的</b>会话，
 * 拆开只会让授权界面多出三行谁都不会去区分的勾选框，而拦不住任何真实风险。
 * 真正需要逐个把关的是<b>工具</b>，那一层由权限网关负责。
 *
 * @author 走进新时代
 */
@Tag(name = "管理后台 - AI 助手")
@Slf4j
@RestController
@RequestMapping("/agent/assistant")
@Validated
public class AgentAssistantController {

    @Resource
    private AgentAssistantService assistantService;
    @Resource
    private AgentConversationService conversationService;

    @Operation(summary = "发送消息（流式）")
    @PostMapping(value = "/send-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@ss.hasPermission('agent:assistant:use')")
    public Flux<CommonResult<AgentChatEvent>> sendStream(@Valid @RequestBody AgentChatSendReqVO reqVO) {
        // 🔴 快照必须在<b>这里</b>捕获 —— 此刻还在 Servlet 线程上，ThreadLocal 里有身份。
        //    晚一步落到 Reactor 线程或线程池里，捕到的可能就是 null
        //    （实测预先创建好的线程池上 userId 与 tenantId 都是 null）。
        //    这一行的位置是有意义的，不能为了"整洁"挪进 Service。
        AgentExecutionContext context = AgentExecutionContext.snapshot();
        try {
            return assistantService.chat(BeanUtils.toBean(reqVO, AgentChatReqBO.class), context)
                    .map(CommonResult::success);
        } catch (ServiceException ex) {
            // 🔴 同步段抛出的业务异常必须在这里接住，转成流里的一个元素。
            //
            // 不接的话会很难查：异常冒到全局处理器，处理器要写一个 JSON 响应，
            // 而客户端的 Accept 是 text/event-stream —— 内容协商失败，
            // 请求落到错误页，错误页又重新过了一遍安全过滤器（此时已经没有 token），
            // 于是用户看到的是<b>「账号未登录」</b>。
            // 真实原因（配额超限 / 没输入内容 / 助手未启用）全丢了。
            //
            // 实测确认过：空消息与配额超限都会变成这个样子。
            // 转成流里的元素之后，Content-Type 仍是 event-stream，
            // 错误码与提示语原样保留，前端本来就是按 CommonResult 逐条消费的
            log.info("[sendStream][请求被拒绝，错误码({}) 原因({})]", ex.getCode(), ex.getMessage());
            return Flux.just(CommonResult.error(ex.getCode(), ex.getMessage()));
        } catch (Exception ex) {
            // 🔴 兜住 ServiceException 之外的任何异常，理由同上，只是更严重：
            //    这类异常是<b>意料之外</b>的，冒到全局处理器后写不进 event-stream 响应，
            //    客户端拿到的是一个<b>空 body</b>（实测过：连 curl 的退出码都是 0）。
            //    前端看到「连接正常结束、一个字都没有」，只能标成成功或干等着 ——
            //    这就是用户口中「输入完没反应」最难查的那一种形态。
            //
            // 转成流里的一个 ERROR 元素之后，真实原因至少能到用户眼前、也能进前端日志。
            log.error("[sendStream][请求处理异常]", ex);
            return Flux.just(CommonResult.error(INTERNAL_SERVER_ERROR.getCode(),
                    "AI 助手处理请求时出错：" + ex.getMessage()));
        }
    }

    /**
     * 聊天内恢复挂起的工作流（增量13 / 收口 slice-3c 的 RK5）
     *
     * <p>与 {@link #sendStream} 完全同款的 SSE 纪律：身份在 Servlet 线程快照、同步段的 ServiceException
     * 与任何异常都<b>接住转成流内元素</b>（否则 Accept=text/event-stream 与全局处理器的 JSON 内容协商失败，
     * 用户只看到「账号未登录」或空 body，真实原因全丢——见 sendStream 的长注释）。
     *
     * <p>权限沿用入口统一的 {@code agent:assistant:use}（作用于用户自己的会话，不逐个拆点，见类注释）。
     */
    @Operation(summary = "聊天内恢复挂起的工作流（流式，hitl 确认/拒绝后）")
    @PostMapping(value = "/resume-workflow-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@ss.hasPermission('agent:assistant:use')")
    public Flux<CommonResult<AgentChatEvent>> resumeWorkflowStream(@Valid @RequestBody AgentChatResumeReqVO reqVO) {
        // 🔴 快照必须在这里捕获（此刻还在 Servlet 线程上，ThreadLocal 里有身份），晚一步就可能是 null
        AgentExecutionContext context = AgentExecutionContext.snapshot();
        try {
            return assistantService.resumeWorkflowInChat(reqVO.getExecutionId(), reqVO.getApproved(),
                            reqVO.getConversationId(), context)
                    .map(CommonResult::success);
        } catch (ServiceException ex) {
            // 执行不存在/非挂起态/被并发认领/会话不属于你——都是「用户能看懂」的业务错，原样进流
            log.info("[resumeWorkflowStream][请求被拒绝，错误码({}) 原因({})]", ex.getCode(), ex.getMessage());
            return Flux.just(CommonResult.error(ex.getCode(), ex.getMessage()));
        } catch (Exception ex) {
            log.error("[resumeWorkflowStream][请求处理异常]", ex);
            return Flux.just(CommonResult.error(INTERNAL_SERVER_ERROR.getCode(),
                    "恢复工作流时出错：" + ex.getMessage()));
        }
    }

    /**
     * 聊天内按 code 跑一条已发布的工作流（A1）
     *
     * <p>草稿卡「确认并运行」走这里，而不是 {@code /agent/workflow/run-stream}：
     * 后者只写 {@code agent_workflow_execution}、<b>全程不碰 agent_message</b>，而聊天历史与 LLM 的记忆
     * 都来自 agent_message —— 于是回复刷新即消失、追问一句模型完全不知道刚才跑过什么。
     * 这条入口把回复落进会话并回推聊天事件，落库与桥接复用助手已有的那一份（{@code bridgeWorkflowRun}）。
     * 旧端点保留（通用 REST 入口 + 自己的权限点 {@code agent:workflow:run}），只是前端不再调它。
     *
     * <p>🔴 SSE 纪律与 {@link #sendStream} 逐字相同：身份在 Servlet 线程快照、同步段的 ServiceException
     * 与任何异常都<b>接住转成流内元素</b>（不接的后果见 sendStream 的长注释：内容协商失败，
     * 用户看到「账号未登录」或空 body，真实原因全丢）。
     *
     * <p>权限沿用入口统一的 {@code agent:assistant:use}（见类注释）。
     */
    @Operation(summary = "聊天内运行已发布的工作流（流式，草稿卡「确认并运行」）")
    @PostMapping(value = "/run-workflow-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@ss.hasPermission('agent:assistant:use')")
    public Flux<CommonResult<AgentChatEvent>> runWorkflowStream(@Valid @RequestBody AgentChatRunWorkflowReqVO reqVO) {
        // 🔴 快照必须在这里捕获（此刻还在 Servlet 线程上，ThreadLocal 里有身份），晚一步就可能是 null
        AgentExecutionContext context = AgentExecutionContext.snapshot();
        try {
            return assistantService.runWorkflowInChat(reqVO.getCode(), reqVO.getInput(),
                            reqVO.getConversationId(), context)
                    .map(CommonResult::success);
        } catch (ServiceException ex) {
            // 流程没发布/code 查不到/会话不属于你/助手未启用——都是「用户能看懂」的业务错，原样进流
            log.info("[runWorkflowStream][请求被拒绝，错误码({}) 原因({})]", ex.getCode(), ex.getMessage());
            return Flux.just(CommonResult.error(ex.getCode(), ex.getMessage()));
        } catch (Exception ex) {
            log.error("[runWorkflowStream][请求处理异常]", ex);
            return Flux.just(CommonResult.error(INTERNAL_SERVER_ERROR.getCode(),
                    "运行工作流时出错：" + ex.getMessage()));
        }
    }

    @Operation(summary = "获得会话分页")
    @GetMapping("/conversation/page")
    @PreAuthorize("@ss.hasPermission('agent:assistant:use')")
    public CommonResult<PageResult<AgentConversationRespVO>> getConversationPage(
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize) {
        PageResult<AgentConversationDO> pageResult =
                conversationService.getConversationPage(getLoginUserId(), pageNo, pageSize);
        return success(BeanUtils.toBean(pageResult, AgentConversationRespVO.class));
    }

    @Operation(summary = "创建会话")
    @PostMapping("/conversation/create")
    @PreAuthorize("@ss.hasPermission('agent:assistant:use')")
    public CommonResult<Long> createConversation(
            @RequestParam(value = "title", required = false) String title) {
        return success(conversationService.createConversation(getLoginUserId(), title));
    }

    @Operation(summary = "修改会话标题")
    @PutMapping("/conversation/update-title")
    @Parameter(name = "id", description = "会话编号", required = true)
    @PreAuthorize("@ss.hasPermission('agent:assistant:use')")
    public CommonResult<Boolean> updateConversationTitle(@RequestParam("id") Long id,
                                                         @RequestParam("title") String title) {
        conversationService.updateConversationTitle(id, getLoginUserId(), title);
        return success(true);
    }

    @Operation(summary = "置顶/取消置顶会话")
    @PutMapping("/conversation/update-pinned")
    @PreAuthorize("@ss.hasPermission('agent:assistant:use')")
    public CommonResult<Boolean> updateConversationPinned(@RequestParam("id") Long id,
                                                          @RequestParam("pinned") Boolean pinned) {
        conversationService.updateConversationPinned(id, getLoginUserId(), pinned);
        return success(true);
    }

    @Operation(summary = "删除会话")
    @DeleteMapping("/conversation/delete")
    @Parameter(name = "id", description = "会话编号", required = true)
    @PreAuthorize("@ss.hasPermission('agent:assistant:use')")
    public CommonResult<Boolean> deleteConversation(@RequestParam("id") Long id) {
        conversationService.deleteConversation(id, getLoginUserId());
        return success(true);
    }

    @Operation(summary = "获得会话的消息列表")
    @GetMapping("/message/list")
    @Parameter(name = "conversationId", description = "会话编号", required = true)
    @PreAuthorize("@ss.hasPermission('agent:assistant:use')")
    public CommonResult<List<AgentMessageRespVO>> getMessageList(
            @RequestParam("conversationId") Long conversationId) {
        List<AgentMessageDO> messages =
                conversationService.getMessageList(conversationId, getLoginUserId());
        return success(messages.stream().map(AgentAssistantController::toMessageVO).toList());
    }

    /**
     * DO → VO
     *
     * 刻意不用 {@code BeanUtils.toBean}：{@code referenceJson} / {@code suggestions}
     * 在库里是 JSON <b>字符串</b>，而 VO 里是<b>对象列表</b>，字段名和类型都对不上，
     * 自动拷贝会静默留空 —— 表现为「历史消息的引用角标和建议全没了」，
     * 而且不报任何错。这种字段只能手工映射。
     *
     * <p>🔴 尤其 {@code suggestions}：它在 DO 和 VO 里<b>同名</b>（都叫 suggestions），
     * 但类型是 String vs {@code List<Suggestion>}。用 {@code BeanUtils.toBean} 整体拷贝时，
     * Hutool 会尝试把 JSON 字符串直接转成 {@code List<Suggestion>}，而 {@code Suggestion}
     * 是 record，转换抛 {@code ConvertException} → 整个 message/list 接口 500。
     * 表现是「凡是带对话后建议的历史会话，一点开就报系统异常、记录全看不到」，
     * 而没有建议的会话却正常 —— 极难联想到是这一个字段。
     * 所以这里用 {@code BeanUtil.copyProperties} 并<b>显式忽略 suggestions</b>，再手工解析。
     */
    // 包级可见（非 private）：为了让同包单测能直接验证 DO→VO 的字段映射，
    // 尤其是 suggestions 这个 record 列表 —— 它曾经因为同名类型冲突让整个接口 500。
    static AgentMessageRespVO toMessageVO(AgentMessageDO message) {
        AgentMessageRespVO vo = new AgentMessageRespVO();
        // 忽略 suggestions：DO 是 JSON 字符串、VO 是对象列表，交给下面的手工解析
        BeanUtil.copyProperties(message, vo, "suggestions");
        vo.setReferences(parseList(message.getReferenceJson(), AiRagSegmentRespDTO.class));
        vo.setSuggestions(parseSuggestions(message.getSuggestions()));
        return vo;
    }

    private static <T> List<T> parseList(String json, Class<T> type) {
        if (StrUtil.isBlank(json)) {
            return CollUtil.newArrayList();
        }
        try {
            return JSONUtil.toList(json, type);
        } catch (Exception ex) {
            // 历史数据格式对不上时给空列表，而不是让整个消息列表接口 500 ——
            // 少几个引用角标可以接受，整段对话记录打不开不行
            return CollUtil.newArrayList();
        }
    }

    /**
     * 解析对话后建议
     *
     * <p>🔴 不复用 {@link #parseList}：{@link Suggestion} 是 <b>record</b>，
     * {@code JSONUtil.toList(json, Suggestion.class)} 走 Hutool 的 record 转换会抛异常，
     * 被 parseList 的 catch 吞掉后<b>静默返回空列表</b> —— 表现是「历史消息的引用还在，
     * 对话后建议却全没了」，而且不报任何错。所以这里手工按字段名构造 record。
     */
    private static List<Suggestion> parseSuggestions(String json) {
        if (StrUtil.isBlank(json)) {
            return CollUtil.newArrayList();
        }
        try {
            List<Suggestion> result = CollUtil.newArrayList();
            for (Object item : JSONUtil.parseArray(json)) {
                JSONObject obj = (JSONObject) item;
                result.add(new Suggestion(obj.getStr("text"), obj.getStr("tool")));
            }
            return result;
        } catch (Exception ex) {
            return CollUtil.newArrayList();
        }
    }

}
