package cn.enlev.ainb.module.ai.service.model;

import cn.enlev.ainb.module.ai.enums.model.AiModelTypeEnum;
import cn.enlev.ainb.module.ai.enums.model.AiPlatformEnum;
import cn.enlev.ainb.module.ai.framework.ai.core.model.AiModelFactory;
import cn.enlev.ainb.module.ai.framework.ai.core.model.midjourney.api.MidjourneyApi;
import cn.enlev.ainb.module.ai.framework.ai.core.model.suno.api.SunoApi;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.framework.tenant.core.context.TenantContextHolder;
import cn.enlev.ainb.framework.tenant.core.util.TenantUtils;
import cn.enlev.ainb.module.ai.controller.admin.model.vo.model.AiModelPageReqVO;
import cn.enlev.ainb.module.ai.controller.admin.model.vo.model.AiModelSaveReqVO;
import cn.enlev.ainb.module.ai.controller.admin.model.vo.model.AiModelTestRespVO;
import cn.enlev.ainb.module.ai.dal.dataobject.model.AiApiKeyDO;
import cn.enlev.ainb.module.ai.dal.dataobject.model.AiModelDO;
import cn.enlev.ainb.module.ai.dal.mysql.model.AiChatMapper;
import cn.enlev.ainb.module.ai.util.AiUtils;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.cloud.ai.dashscope.rerank.DashScopeRerankOptions;
import com.alibaba.cloud.ai.dashscope.spec.DashScopeApiSpec;
import com.alibaba.cloud.ai.document.DocumentWithScore;
import com.alibaba.cloud.ai.model.RerankModel;
import com.alibaba.cloud.ai.model.RerankRequest;
import com.alibaba.cloud.ai.model.RerankResponse;
import com.mzt.logapi.context.LogRecordContext;
import com.mzt.logapi.service.impl.DiffParseFunction;
import com.mzt.logapi.starter.annotation.LogRecord;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.ai.enums.ErrorCodeConstants.*;
import static cn.enlev.ainb.module.ai.enums.LogRecordConstants.*;

/**
 * AI 模型 Service 实现类
 *
 * @author fansili
 */
@Service
@Validated
public class AiModelServiceImpl implements AiModelService {

    /** 探活硬超时的下限，单位：秒 */
    private static final int TEST_TIMEOUT_MIN_SECONDS = 5;
    /** 探活硬超时的上限，单位：秒。再长也只是白占一个 Tomcat 线程 */
    private static final int TEST_TIMEOUT_MAX_SECONDS = 120;
    /** 探活硬超时的默认值，单位：秒 */
    private static final int TEST_TIMEOUT_DEFAULT_SECONDS = 30;
    /** 回复片段的截断长度：够确认「出字了」即可，全文没有诊断价值 */
    private static final int TEST_REPLY_MAX_LENGTH = 200;
    /** 厂商错误原文的截断长度，与 {@code ErrorCodeConstants#WEB_SEARCH_FAIL} 同口径 */
    private static final int TEST_ERROR_MAX_LENGTH = 500;
    /**
     * 探活用的极短提示词
     *
     * <p>只要一个词：足以暴露端点选错、模型名不存在、鉴权失败、思考模式受限这四类配置型错误，
     * 同时把成本与延迟压到最低。刻意不写「请回复 pong」之类的引导语 ——
     * 那会让「厂商返回空回复」这个事实被提示词自己掩盖掉。
     */
    private static final String TEST_PROMPT = "ping";
    /**
     * 探活的输出上限
     *
     * <p>不按模型自身的 {@code maxTokens}（可能是 8192）去烧：一次点按不该产生一次长对话的账单。
     */
    private static final int TEST_MAX_TOKENS = 256;

    /**
     * 厂商「端点不匹配」的原文特征
     *
     * <p>通义在「模型名不归这个端点管」时逐字回 {@code InvalidParameter "url error, please check url！"}，
     * 三个端点（文本向量 / 多模态向量 / 重排序）互撞时都是这一句，2026-10-26 实测。
     * 认这一句而不是认 HTTP 400，是因为 400 还有一堆别的成因（模型名不存在、参数越界），
     * 那些不该被引到「去核对模型类型」这条路上。
     */
    private static final String EMBEDDING_URL_ERROR_MARKER = "url error";

    /** 重排序探活的查询 */
    private static final String RERANK_TEST_QUERY = "启凌智搭的知识库支持哪些向量模型";
    /** 重排序探活的<b>相关</b>候选：与查询同主题，理应排在前面 */
    private static final String RERANK_TEST_RELATED =
            "启凌智搭的知识库按向量模型分文件存向量，切换模型需要重新嵌入全部段落。";
    /** 重排序探活的<b>无关</b>候选：用来确认「排序真的排了」，而不是随便回两条 */
    private static final String RERANK_TEST_UNRELATED = "紫色河马在星期四下午三点十七分跳了一支探戈。";
    /** 重排序探活的 topN，等于候选数（截断了就看不出「少返回一条」这类异常） */
    private static final int RERANK_TEST_TOP_N = 2;

    private static final AtomicInteger TEST_THREAD_SEQ = new AtomicInteger();

    /**
     * 探活专用线程池：有界 + daemon + {@link SynchronousQueue} + {@code AbortPolicy}
     *
     * <p>🔴 为什么必须自己起池、而不是在 Servlet 线程上直接 {@code .call()}：
     * 同步 {@code .call()} 碰上厂商挂起（TCP 连着但不发数据）会占住一个 Tomcat 线程 ——
     * {@code EnlevAiProperties.Stream} 的 javadoc（缺陷 B18）记录的正是这个故障形态。
     * A4 之后 {@code AiModelFactoryImpl#timedRestClientBuilder} 给它装上了 read-timeout，
     * 但默认是 <b>300 秒</b>（不能配小，理由见 {@code EnlevAiProperties.Http#readTimeout}：
     * 实测过 CHAT 单次 284 秒），远长于本端点 {@code clampTimeout} 的 120 秒上限。
     * 管理员点一次「测试」不该等五分钟，所以仍然要把调用扔进独立线程、用 {@code Future.get(timeout)} 卡住。
     *
     * <p>🔴 为什么用 {@link SynchronousQueue} + {@code AbortPolicy} 而不是有界队列：
     * 排队等于让管理员点第二次时看到的还是上一次的结论。满了就快速拒绝、
     * 回一条结构化结果（见 {@link #testModel}），绝不堆积。
     *
     * <p>⚠️ {@code Future.get} 超时后 {@code cancel(true)} 未必能中断底层 HTTP 调用，孤儿线程会存活到
     * 自然结束。A4 之前这句话后面还得跟一句「上限 4 把这个泄漏封顶」—— 因为那时根本没有 read-timeout，
     * 「自然结束」可能永远不来。现在孤儿线程最长 300 秒就会自己退出，上限 4 只是多一道保险，
     * 不再是唯一的那道。
     */
    private static final ThreadPoolExecutor TEST_POOL = new ThreadPoolExecutor(
            0, 4, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(),
            runnable -> {
                Thread thread = new Thread(runnable, "ai-model-test-" + TEST_THREAD_SEQ.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());

    @Resource
    private AiApiKeyService apiKeyService;

    @Resource
    private AiChatMapper modelMapper;

    @Resource
    private AiModelFactory modelFactory;

    @Override
    @LogRecord(type = AI_MODEL_TYPE, subType = AI_MODEL_CREATE_SUB_TYPE, bizNo = "{{#model.id}}",
            success = AI_MODEL_CREATE_SUCCESS)
    public Long createModel(AiModelSaveReqVO createReqVO) {
        // 1. 校验
        AiPlatformEnum.validatePlatform(createReqVO.getPlatform());
        apiKeyService.validateApiKey(createReqVO.getKeyId());

        // 2. 插入
        AiModelDO model = BeanUtils.toBean(createReqVO, AiModelDO.class);
        modelMapper.insert(model);

        // 3. 记录操作日志上下文
        LogRecordContext.putVariable("model", model);
        return model.getId();
    }

    @Override
    @LogRecord(type = AI_MODEL_TYPE, subType = AI_MODEL_UPDATE_SUB_TYPE, bizNo = "{{#updateReqVO.id}}",
            success = AI_MODEL_UPDATE_SUCCESS)
    public void updateModel(AiModelSaveReqVO updateReqVO) {
        // 1. 校验
        AiModelDO oldModel = validateModelExists(updateReqVO.getId());
        AiPlatformEnum.validatePlatform(updateReqVO.getPlatform());
        apiKeyService.validateApiKey(updateReqVO.getKeyId());

        // 2. 更新
        AiModelDO updateObj = BeanUtils.toBean(updateReqVO, AiModelDO.class);
        modelMapper.updateById(updateObj);

        // 3. 记录操作日志上下文（_DIFF 拿旧对象与新 ReqVO 比，只输出打了 @DiffLogField 的字段）
        LogRecordContext.putVariable(DiffParseFunction.OLD_OBJECT,
                BeanUtils.toBean(oldModel, AiModelSaveReqVO.class));
        LogRecordContext.putVariable("model", oldModel);
    }

    @Override
    @LogRecord(type = AI_MODEL_TYPE, subType = AI_MODEL_UPDATE_STATUS_SUB_TYPE, bizNo = "{{#updateReqVO.id}}",
            success = AI_MODEL_UPDATE_STATUS_SUCCESS)
    public void updateModelStatus(Long id, Integer status) {
        // 1. 校验存在。🔴 刻意不调 apiKeyService.validateApiKey：详见 AiModelService#updateModelStatus
        AiModelDO oldModel = validateModelExists(id);

        // 2. 只写 status 一个字段：MyBatis-Plus 默认 NOT_NULL 策略，其余列一律不动。
        //    🔴 也因此绝不能复用 /update —— 那会把整行覆盖一遍
        AiModelDO updateObj = new AiModelDO();
        updateObj.setId(id);
        updateObj.setStatus(status);
        modelMapper.updateById(updateObj);

        // 3. 记录操作日志上下文。_DIFF 比的是「旧行」与「旧行改一个 status」，
        //    所以日志里恰好只有状态那一行，不会把温度/maxTokens 之类无关字段也刷进去
        AiModelSaveReqVO oldForLog = BeanUtils.toBean(oldModel, AiModelSaveReqVO.class);
        AiModelSaveReqVO newForLog = BeanUtils.toBean(oldModel, AiModelSaveReqVO.class);
        newForLog.setStatus(status);
        LogRecordContext.putVariable(DiffParseFunction.OLD_OBJECT, oldForLog);
        LogRecordContext.putVariable("model", oldModel);
        LogRecordContext.putVariable("updateReqVO", newForLog);
    }

    @Override
    @LogRecord(type = AI_MODEL_TYPE, subType = AI_MODEL_DELETE_SUB_TYPE, bizNo = "{{#id}}",
            success = AI_MODEL_DELETE_SUCCESS)
    public void deleteModel(Long id) {
        // 校验存在
        AiModelDO model = validateModelExists(id);
        // 删除
        modelMapper.deleteById(id);

        // 记录操作日志上下文
        LogRecordContext.putVariable("model", model);
    }

    private AiModelDO validateModelExists(Long id) {
        AiModelDO model = modelMapper.selectById(id);
        if (model == null) {
            throw exception(MODEL_NOT_EXISTS);
        }
        return model;
    }

    @Override
    public AiModelDO getModel(Long id) {
        return modelMapper.selectById(id);
    }

    @Override
    public AiModelDO getRequiredDefaultModel(Integer type) {
        AiModelDO model = modelMapper.selectFirstByStatus(type, CommonStatusEnum.ENABLE.getStatus());
        if (model == null) {
            throw exception(MODEL_DEFAULT_NOT_EXISTS);
        }
        return model;
    }

    @Override
    public PageResult<AiModelDO> getModelPage(AiModelPageReqVO pageReqVO) {
        return modelMapper.selectPage(pageReqVO);
    }

    @Override
    public AiModelDO validateModel(Long id) {
        AiModelDO model = validateModelExists(id);
        if (CommonStatusEnum.isDisable(model.getStatus())) {
            throw exception(MODEL_DISABLE);
        }
        return model;
    }

    @Override
    public List<AiModelDO> getModelListByStatusAndType(Integer status, Integer type, String platform) {
        return modelMapper.selectListByStatusAndType(status, type, platform);
    }

    @Override
    public AiModelTestRespVO testModel(Long id, Integer timeoutSeconds) {
        // 1. 模型：只判存在。刻意不走 validateModel —— 它会对禁用模型抛 MODEL_DISABLE，
        //    而「测一个已禁用的模型到底还能不能用」正是本端点的主要用途之一。
        //    当前启用状态不当门槛，当事实回显
        AiModelDO model = getModel(id);
        if (model == null) {
            throw exception(MODEL_NOT_EXISTS);
        }

        AiModelTestRespVO respVO = new AiModelTestRespVO().setSuccess(false).setTimedOut(false)
                .setPlatform(model.getPlatform()).setModel(model.getModel())
                .setModelStatus(model.getStatus());

        // 2. 密钥：同上，用非校验版 getApiKey，禁用密钥也照测
        AiApiKeyDO apiKey = apiKeyService.getApiKey(model.getKeyId());
        if (apiKey == null) {
            return respVO.setErrorMsg(API_KEY_NOT_EXISTS.getMsg());
        }
        respVO.setKeyStatus(apiKey.getStatus());

        // 3. 🔴 按模型类型分流：不同类型的「调通」判据完全不同，不能共用一条 chat 路径。
        //    改之前这里无条件建 ChatModel，于是向量模型（type=5）被拿它的 embedding 模型名
        //    去打平台的【对话】端点，厂商回一句 400，前端显示「模型不可用」——
        //    把「测试方式不对」误报成「模型坏了」，管理员会照着错的方向反复启停一个正常的模型
        //    （与本模块「不替 error 编病因」那条纪律是同一件事）。
        //    实测形态：9 个启用的向量模型点测试全部「连不上」，而同一个密钥下的对话模型全部正常。
        //
        //    🔴 分流之后仍有 6/9 失败，于是 2026-10-26 直连厂商三端点逐个复测，根因不在本方法而在
        //    「登记成向量」这件事本身：103/104 是重排序模型（只有 rerank 端点通）、
        //    105/107/108/109 是多模态向量模型（只有多模态向量端点通），65 是真的免费额度耗尽。
        //    前两类已由「补 rerank 分档 + 补多模态向量端点」修掉，见 testRerankModel 与
        //    AiUtils#isTongYiMultiModalEmbeddingModel；第三类是诚实结论，不该被"修"成绿。
        Integer type = model.getType();
        if (AiModelTypeEnum.EMBEDDING.getType().equals(type)) {
            return testEmbeddingModel(respVO, model, apiKey, timeoutSeconds);
        }
        if (AiModelTypeEnum.RERANK.getType().equals(type)) {
            return testRerankModel(respVO, model, apiKey, timeoutSeconds);
        }
        if (!AiModelTypeEnum.CHAT.getType().equals(type)) {
            // 图片 / 语音 / 视频各自有独立端点，本端点一个都不打。
            // 明确说「不支持」，而不是拿它们的模型名去撞对话端点、再把撞出来的 400 当成结论回显
            return respVO.setErrorMsg(StrUtil.format(
                    "模型类型「{}」暂不支持连通性测试：本端点只会打【对话】【向量】【重排】三类端点。"
                            + "拿 {} 模型的标识去打对话端点只会得到一句厂商 400，那说明的是测试方式不对，不是模型坏了",
                    describeModelType(type), describeModelType(type)));
        }
        return testChatModel(respVO, model, apiKey, timeoutSeconds);
    }

    /**
     * 测对话模型（{@code type=1}）
     */
    private AiModelTestRespVO testChatModel(AiModelTestRespVO respVO, AiModelDO model, AiApiKeyDO apiKey,
                                            Integer timeoutSeconds) {
        // 端点路由是本模块自己算出来的，属于证据不是推测：排查 400「url error, please check url」时，
        // 最需要知道的就是它被路由到了多模态还是文本端点。非通义平台没有这个分档，留 null。
        // 🔴 只在对话分支里设：向量模型压根没有「多模态端点」这一档，
        //    给它回显一个 false 等于替厂商编一个它没说过的结论
        if (AiPlatformEnum.TONG_YI.getPlatform().equals(model.getPlatform())) {
            respVO.setMultiModel(AiUtils.isTongYiMultiModel(model.getModel()));
        }

        final ChatModel chatModel;
        final Prompt prompt;
        try {
            // 密钥空/占位值在这里就拦掉，别等 SDK 去建客户端：各平台反应并不一致
            // （有的 Assert 抛、有的照建不误、要到真发请求才吃 401）。照建不误的那种最坏 ——
            // 401 会被 AiModelFailureReason 当成「厂商抖动」，把「没填密钥」说成「厂商挂了」
            AiUtils.validateApiKey(apiKey.getApiKey());
            chatModel = modelFactory.getOrCreateChatModel(
                    AiPlatformEnum.validatePlatform(apiKey.getPlatform()), apiKey.getApiKey(), apiKey.getUrl());
            prompt = buildTestPrompt(model);
        } catch (Exception ex) {
            return respVO.setErrorMsg(abbreviate(AiUtils.describeError(ex), TEST_ERROR_MAX_LENGTH));
        }
        // A3 之后思考模式由三档决定（显式表态 > 限死必须开 > 按端点），光看 success 分不出哪档起的作用。
        // 这里回显的是 prompt 里那份真正要发出去的 options，不是重新算一遍 —— 见 AiUtils#getTongYiEnableThinking
        respVO.setEnableThinking(AiUtils.getTongYiEnableThinking(prompt.getOptions()));

        return invokeProbe(respVO, timeoutSeconds, () -> chatModel.call(prompt),
                (vo, response) -> fillChatTestSuccess(vo, response));
    }

    /**
     * 测向量模型（{@code type=5}）
     *
     * <p>🔴 探活形态照抄生产，不自己发明：{@code SimpleVectorStore} 入库走
     * {@code EmbeddingModel#embed(Document)}、检索走 {@code embed(String)}，两者最终都落到
     * {@code call(new EmbeddingRequest(texts, EmbeddingOptions.builder().build()))}
     * （已用 javap 对 spring-ai 1.1.8 的 {@code EmbeddingModel} 默认方法逐条核对）。
     * 这里直接调最后那一层 {@code call}，为的是顺带拿到 {@code EmbeddingResponse} 里的用量与维度 ——
     * 走 {@code embed(String)} 只能拿到一个 {@code float[]}，看不出厂商按多少 token 计的费。
     *
     * <p>🔴 回显维度不是装饰：向量文件按<b>模型标识</b>命名（{@code simple_text-embedding-v4.json}，
     * 见 {@code AiModelFactoryImpl#buildSimpleVectorStore}），一个模型一份、互不覆写。
     * 但知识库钉住的模型一旦要换，旧向量<b>整个作废</b> —— 它们是在另一个语义空间里算出来的，
     * 维度还可能不一样。换之前先看这一格：两个模型维度不一致，
     * 就是「必须重嵌、不能直接改 {@code embedding_model_id} 了事」的硬证据。
     */
    private AiModelTestRespVO testEmbeddingModel(AiModelTestRespVO respVO, AiModelDO model, AiApiKeyDO apiKey,
                                                 Integer timeoutSeconds) {
        final EmbeddingModel embeddingModel;
        try {
            AiUtils.validateApiKey(apiKey.getApiKey());
            embeddingModel = modelFactory.getOrCreateEmbeddingModel(
                    AiPlatformEnum.validatePlatform(apiKey.getPlatform()), apiKey.getApiKey(), apiKey.getUrl(),
                    model.getModel());
        } catch (Exception ex) {
            return respVO.setErrorMsg(abbreviate(AiUtils.describeError(ex), TEST_ERROR_MAX_LENGTH));
        }

        return explainEmbeddingEndpointMismatch(model, invokeProbe(respVO, timeoutSeconds,
                () -> embeddingModel.call(new EmbeddingRequest(
                        List.of(TEST_PROMPT), EmbeddingOptions.builder().build())),
                (vo, response) -> fillEmbeddingTestSuccess(vo, response)));
    }

    /**
     * 把厂商那句「url error」翻译成管理员真能照着做的话
     *
     * <p>🔴 这句原文是本项目排查代价最高的一句：它字面上在说「你填的 URL 错了」，
     * 而<b>通义向量调用的 URL 压根不由管理员填</b> —— {@code buildTongYiEmbeddingModel}
     * 端点写死在代码里，密钥表的 {@code url} 为空时走官方默认域名。
     * 它真正的含义是「这个模型名不由该端点提供」，实测在两种情况下逐字出现：
     * 把重排序模型（{@code gte-rerank-v2}）或多模态向量模型（{@code qwen3-vl-embedding}）
     * 登记成了「向量」类型。2026-10-26 库里 9 个启用的向量模型有 6 个是这两种，
     * 管理员照着字面反复改密钥 URL，一个都改不好。
     *
     * <p>厂商原文<b>逐字保留</b>在前、解释追加在后：原文是证据，解释是导引，
     * 只留解释就成了「替厂商编病因」，只留原文就是现在这个没人看得懂的局面。
     */
    private static AiModelTestRespVO explainEmbeddingEndpointMismatch(AiModelDO model, AiModelTestRespVO respVO) {
        if (Boolean.TRUE.equals(respVO.getSuccess()) || StrUtil.isBlank(respVO.getErrorMsg())
                || !StrUtil.containsIgnoreCase(respVO.getErrorMsg(), EMBEDDING_URL_ERROR_MARKER)) {
            return respVO;
        }
        return respVO.setErrorMsg(respVO.getErrorMsg() + StrUtil.format(
                "\n↳ 这句「url error」不是在说你填的接口地址错了：向量端点由代码按模型名路由，"
                        + "密钥里的 URL 留空时走厂商默认域名，两种情况都不由你填。"
                        + "它真正的意思是【{}】这个模型名不由向量端点提供 —— 实测在「把重排序模型"
                        + "或多模态向量模型登记成向量类型」时逐字出现。请核对模型类型与模型名，"
                        + "不要去改密钥里的 URL。", model.getModel()));
    }

    /**
     * 测重排序模型（{@code type=6}）
     *
     * <p>🔴 判据与向量模型完全不同，所以必须有独立分支：重排序<b>不出向量</b>，
     * 它收「一个查询 + 若干候选文档」、回「按相关度排序的结果」。
     * 拿重排序模型名去建 {@code EmbeddingModel} 只会得到厂商一句
     * {@code 400 InvalidParameter "url error, please check url！"} —— 而这句字面上像在怪
     * 管理员填错了 URL，是本模块排查代价最高的一句（见 {@link #explainEmbeddingEndpointMismatch}）。
     * 库里 103/104 两行（{@code gte-rerank-v2}、{@code qwen3-vl-rerank}）曾长期是这个形态：
     * 直连厂商实测它们<b>只有 rerank 端点通</b>，模型本身是好的。
     *
     * <p>探活用的查询与候选刻意让相关度<b>拉得开</b>（一条明显相关、一条明显无关），
     * 这样「返回了结果」才是有意义的结论；两条都无关的话，排序对不对根本看不出来。
     */
    private AiModelTestRespVO testRerankModel(AiModelTestRespVO respVO, AiModelDO model, AiApiKeyDO apiKey,
                                              Integer timeoutSeconds) {
        final RerankModel rerankModel;
        final RerankRequest request;
        try {
            AiUtils.validateApiKey(apiKey.getApiKey());
            rerankModel = modelFactory.getOrCreateRerankModel(
                    AiPlatformEnum.validatePlatform(apiKey.getPlatform()), apiKey.getApiKey(), apiKey.getUrl(),
                    model.getModel());
            // topN 给满候选数：探活要看的是「能不能排序」，截断成 1 条会让「少返回一条」这类
            // 异常形态看不出来。returnDocuments 关掉，省流量也省得把候选原文再回显一遍
            //
            // 🔴 withModel 不是冗余，少了它厂商会回 403 AccessDenied（实测 2026-10-26，
            //    {@code DashScopeRerankWireContractTest} 抓下了出线请求逐字钉住）：
            //    {@code DashScopeRerankOptions} 的无参构造把 model 初始化成了 {@code "gte-rerank"}
            //    （topN=3、returnDocuments=false 同理），所以这里 builder 出来的 options
            //    <b>没有</b>「model 为 null」这一档；而 {@code DashScopeRerankModel#mergeOptions}
            //    是「请求级非 null 就压过构造时的默认值」，于是 getOrCreateRerankModel 里配的
            //    gte-rerank-v2 被静默改写成 gte-rerank —— 一个本账号没开通的旧模型。
            //    与 AiUtils 那条「enableThinking 没有『不发这个字段』这一档」是同一个陷阱形状。
            request = new RerankRequest(RERANK_TEST_QUERY,
                    List.of(new Document(RERANK_TEST_RELATED), new Document(RERANK_TEST_UNRELATED)),
                    DashScopeRerankOptions.builder().withModel(model.getModel())
                            .withTopN(RERANK_TEST_TOP_N).withReturnDocuments(false).build());
        } catch (Exception ex) {
            return respVO.setErrorMsg(abbreviate(AiUtils.describeError(ex), TEST_ERROR_MAX_LENGTH));
        }

        return invokeProbe(respVO, timeoutSeconds, () -> rerankModel.call(request),
                (vo, response) -> fillRerankTestSuccess(vo, response));
    }

    /**
     * 探活的公共骨架：有界线程池 + 硬超时 + 结构化失败
     *
     * <p>对话与向量两类模型共用这一层。它们「怎么算调通」不同，但「在哪儿等、等多久、
     * 等不到怎么报、池满怎么报」完全相同 —— 抄两份的代价是这两份迟早漂移，
     * 而漂移的表现恰好是「一类模型测得准、另一类测不准」，最难归因。
     *
     * @param probe     真正发请求的那一下，在池线程上执行
     * @param onSuccess 调通之后填类型专属字段（{@code success} 与 {@code durationMs} 由本方法填）
     */
    private <R> AiModelTestRespVO invokeProbe(AiModelTestRespVO respVO, Integer timeoutSeconds,
                                              Callable<R> probe, BiConsumer<AiModelTestRespVO, R> onSuccess) {
        int timeout = clampTimeout(timeoutSeconds);
        // 🔴 租户编号必须在 Servlet 线程上捕获。自建线程池会丢租户上下文 ——
        //    AgentDiagnosticController 的类注释里用实验钉死过：池化线程在任何请求到达之前
        //    就已创建，继承不到任何 ThreadLocal。.call() 本身是纯 HTTP、不碰 DB，
        //    但客户端内部的拦截器与埋点未必，所以照旧重建。
        //    🔴 用 TenantUtils.execute(tenantId, ...) 而不是 executeIgnore —— 后者整体关掉租户隔离
        Long tenantId = TenantContextHolder.getTenantId();
        AtomicReference<R> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        long startNanos = System.nanoTime();
        Future<?> future;
        try {
            future = TEST_POOL.submit(() -> TenantUtils.execute(tenantId, () -> {
                try {
                    resultRef.set(probe.call());
                } catch (Throwable ex) {
                    // 🔴 自己接住，不用 TenantUtils.execute(Callable) 那一档：
                    //    它会把异常包成 RuntimeException，于是「逐字带回厂商原文」
                    //    就变成带回一句 java.lang.RuntimeException: ... 的包装壳
                    errorRef.set(ex);
                }
            }));
        } catch (RejectedExecutionException ex) {
            // 池满（4 个测试同时在跑）。回结构化结果而不是抛异常：抛出去会被压成一句 message，
            // 管理员看不出这是「你自己点太快」还是「模型坏了」
            return respVO.setDurationMs(elapsedMillis(startNanos)).setErrorMsg(StrUtil.format(
                    "已有 {} 个模型测试在进行中，请等它们结束后再试", TEST_POOL.getMaximumPoolSize()));
        }

        try {
            future.get(timeout, TimeUnit.SECONDS);
            long durationMs = elapsedMillis(startNanos);
            Throwable error = errorRef.get();
            if (error != null) {
                return respVO.setDurationMs(durationMs)
                        .setErrorMsg(abbreviate(AiUtils.describeError(error), TEST_ERROR_MAX_LENGTH))
                        .setFailureReason(AiModelFailureReason.classify(error).name());
            }
            respVO.setSuccess(true).setDurationMs(durationMs);
            onSuccess.accept(respVO, resultRef.get());
            return respVO;
        } catch (TimeoutException ex) {
            // 🔴 cancel(true) 未必能中断底层 HTTP 调用（见 TEST_POOL 的 javadoc），
            //    但至少让它别再往回写结果
            future.cancel(true);
            // timedOut 与「厂商报错」是两件事：前者说明连错误都没拿到，
            // 拿厂商原文去分型（AiModelFailureReason.classify）会得出一个假的 TRANSIENT
            return respVO.setTimedOut(true).setDurationMs(elapsedMillis(startNanos))
                    .setErrorMsg(StrUtil.format("测试超时（{} 秒）：厂商未在该时间内返回完整响应", timeout));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return respVO.setDurationMs(elapsedMillis(startNanos)).setErrorMsg("测试被中断");
        } catch (ExecutionException ex) {
            // 走到这里说明失败发生在「租户重建」这一层而不是模型调用（模型调用的异常已被 errorRef 接住）
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            return respVO.setDurationMs(elapsedMillis(startNanos))
                    .setErrorMsg(abbreviate(AiUtils.describeError(cause), TEST_ERROR_MAX_LENGTH));
        }
    }

    /**
     * 模型类型的中文名，用于「本类型不支持测试」那句话
     *
     * <p>回显中文名而不是裸数字：管理员在列表页看到的是「向量」「重排序」，
     * 这里蹦一个 6 出来对不上号。取不到就退回裸值，不编名字。
     */
    private static String describeModelType(Integer type) {
        return Arrays.stream(AiModelTypeEnum.values())
                .filter(item -> item.getType().equals(type))
                .map(AiModelTypeEnum::getName)
                .findFirst()
                .orElse(String.valueOf(type));
    }

    /**
     * 组装探活用的 Prompt
     *
     * <p>🔴 options 必须与生产同口径，否则测出来的可用性是假的：复用
     * {@code AiUtils.buildChatOptions}，且 {@code enableThinking} 传 null 表示「调用方不表态」，
     * 由 {@code resolveTongYiThinking} 按「限死必须开 > 端点分档」两档决定 —— 与
     * {@code AiModelApiImpl#buildPrompt} 在 reqDTO 未表态时完全一致。
     *
     * <p>🔴 刻意<b>不</b>显式传 {@code enableThinking=false}：那样会掩盖「只支持思考模式」
     * 那类模型的真实形态。让它按生产口径失败，厂商原文会逐字说出
     * {@code enable_thinking parameter is restricted to True}，比我们自己下判断有用。
     * A3 之后这类模型已被收进 {@code AiUtils#TONG_YI_THINKING_ONLY_MODELS}、默认档就会开思考，
     * 于是这句原文只在「配置被人改错」或「又冒出一个同类新模型」时才出现 —— 正是它还留着的原因。
     */
    private Prompt buildTestPrompt(AiModelDO model) {
        List<Message> messages = List.of(new UserMessage(TEST_PROMPT));
        // 压成本与延迟：探活只需要看出不出字，不需要按模型自身的 maxTokens（可能是 8192）去生成
        Integer maxTokens = model.getMaxTokens() == null
                ? TEST_MAX_TOKENS : Math.min(model.getMaxTokens(), TEST_MAX_TOKENS);
        return new Prompt(messages, AiUtils.buildChatOptions(
                AiPlatformEnum.validatePlatform(model.getPlatform()),
                model.getModel(), model.getTemperature(), maxTokens, null, null, null));
    }

    /**
     * 对话模型调通之后填的字段。{@code success} 与 {@code durationMs} 已由 {@link #invokeProbe} 填好
     */
    private static void fillChatTestSuccess(AiModelTestRespVO respVO, ChatResponse response) {
        String content = AiUtils.getChatResponseContent(response);
        // 空回复也判「调通」：HTTP 层面厂商接受了这个平台/模型/端点组合，配置是对的。
        // 但要把「一个字都没回」显式说出来，不能让它看着像一次正常的成功
        respVO.setReply(StrUtil.isBlank(content)
                ? "（厂商返回了空回复）" : abbreviate(content, TEST_REPLY_MAX_LENGTH));
        if (response != null && response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            Usage usage = response.getMetadata().getUsage();
            respVO.setPromptTokens(usage.getPromptTokens()).setCompletionTokens(usage.getCompletionTokens());
        }
    }

    /**
     * 向量模型调通之后填的字段
     *
     * <p>{@code reply} 这一格对向量模型不是「回复」，但它是弹窗里唯一一块能放结论的地方，
     * 所以拿它回一句可核对的事实（维度）而不是留空 —— 留空会让「模型可用」这个绿条没有任何佐证。
     * {@code completionTokens} 刻意不填：向量模型不生成 token，填 0 会被读成「厂商说输出是 0」。
     */
    private static void fillEmbeddingTestSuccess(AiModelTestRespVO respVO, EmbeddingResponse response) {
        if (response == null) {
            respVO.setReply("（厂商返回了空响应）");
            return;
        }
        Embedding embedding = response.getResult();
        if (embedding != null && embedding.getOutput() != null) {
            respVO.setEmbeddingDimensions(embedding.getOutput().length);
        }
        respVO.setReply(respVO.getEmbeddingDimensions() == null
                ? "（厂商返回了响应，但里面没有向量）"
                : StrUtil.format("向量生成成功，维度 {}", respVO.getEmbeddingDimensions()));
        respVO.setPromptTokens(promptTokensOrNull(
                response.getMetadata() == null ? null : response.getMetadata().getUsage()));
    }

    /**
     * 厂商<b>确实报了</b>用量才返回数字，否则 null
     *
     * <p>🔴 spring-ai 的 {@code EmbeddingResponseMetadata#getUsage} 在字段为 null 时
     * 每次 {@code new} 一个 {@link EmptyUsage} 顶上（已用 javap 核过它的字节码），
     * 所以「非 null」<b>不等于</b>「厂商报了」。直接取 {@code getPromptTokens()}
     * 会把 {@code EmptyUsage} 那个 0 回显成「输入 Token 0」——一个厂商从没说过的数字。
     * 与本类「不替 error 编病因」是同一条纪律：宁可留空，也不填一个看着像事实的 0。
     *
     * <h3>🔴 为什么还要退一步取 total</h3>
     * 通义三个端点的 {@code usage} <b>字段名互不统一</b>，而 {@code DashScopeAiUsage#getPromptTokens}
     * 只读 {@code input_tokens}（javap 核实，它返回的是 {@code TokenUsage#inputTokens()}）。
     * 2026-10-26 直连厂商逐字实测（{@code _evidence/probe-rerank-usage.mjs}）：
     * <pre>
     *   端点                  模型                            usage 原文
     *   rerank                gte-rerank-v2                   {"total_tokens":79}                    ← 没有 input_tokens
     *   rerank                qwen3-vl-rerank                 {"image_tokens":0,"input_tokens":198,"total_tokens":198}
     *   text-embedding        text-embedding-v4               {"total_tokens":12}                    ← 没有 input_tokens
     *   text-embedding        qwen3.7-text-embedding-flash    {"prompt_tokens":26,…,"total_tokens":26} ← 叫 prompt_tokens
     *   multimodal-embedding  qwen3-vl-embedding              {"image_tokens":0,"input_tokens":31,"total_tokens":31}
     *   multimodal-embedding  qwen2.5-vl-embedding            {"image_tokens":0,"input_tokens":31}   ← 没有 total_tokens
     *   multimodal-embedding  tongyi-embedding-vision-*       {"input_tokens":12,…,"output_tokens":1,"total_tokens":13}
     * </pre>
     * 也就是说 7 个能通的模型里有 <b>3 个</b>报了用量却被这一格静默丢掉：弹窗上 token 空着，
     * 管理员只能猜「是不是厂商没计费」。{@code TokenUsage} 里压根没有 {@code prompt_tokens}
     * 对应的字段，所以那一档也只能靠 total 兜住。
     *
     * <p>退到 total 在这里是<b>语义安全</b>的：本方法只被向量与重排序两条分支调用，
     * 两者都不生成输出 token，实测每一档 {@code total} 都等于 {@code input}
     * （{@code tongyi-embedding-vision-*} 是唯一报了 {@code output_tokens:1} 的，
     * 但它有 {@code input_tokens}，走不到这条退路）。对话分支不经这里 ——
     * 它的输入/输出要分别回显，见 {@link #fillChatTestSuccess}。
     *
     * <h3>🔴 为什么「{@code promptTokens} 为 0」也要当成「没报」，还得去 nativeUsage 里捞</h3>
     * 上面那条只判 null 的规则在重排序分支上生效了（{@code gte-rerank-v2} 实测回显 79），
     * 但文本向量分支上<b>没生效</b>，而且是三层叠在一起（全部 javap 核实字节码）：
     * <ol>
     *   <li>{@code DashScopeApiSpec.EmbeddingUsage} 把 {@code total_tokens} 正确解析进了自己的
     *       record 字段（{@code @JsonProperty("total_tokens") Long totalTokens}），
     *       但它的 {@code getPromptTokens()} 与 {@code getCompletionTokens()}
     *       <b>字节码就是 {@code aconst_null}</b> —— 硬编码返回 null；</li>
     *   <li>{@code DashScopeEmbeddingModel#getDefaultUsage} 取的是
     *       {@code usage.getTotalTokens()}（{@code Usage} 接口的 <b>default</b> 方法，
     *       实现是 {@code (prompt ?? 0) + (completion ?? 0)}）而不是 record 自己的
     *       {@code totalTokens()} 访问器 —— 于是刚解析出来的 12 在这一步被算成了 0，
     *       <b>SDK 自己把厂商报的数字弄丢了</b>；</li>
     *   <li>{@code DefaultUsage} 的构造再把 null 一律按 0 处理，所以到本方法手里
     *       {@code getPromptTokens()} 是 0 而不是 null，「厂商没报 input」这个信号也没了。</li>
     * </ol>
     * 好在 {@code DefaultUsage} 的第 4 个构造参数原样留着厂商对象，
     * {@code getNativeUsage()} 能拿回那个 {@code EmbeddingUsage}，
     * 它的 {@code totalTokens()} 访问器<b>没被改掉</b>，12 还在里面。所以这里多走一步。
     *
     * <p>向量与重排序都不可能对一段非空文本报 0 个 token，所以把 0 与 null 同等看待；
     * 三条路都取不到正数就返回 null，让那一格空着。
     *
     * <p>🔴 {@code getTotalTokens()} 不能无条件调：{@code DashScopeAiUsage}（重排序那条路用的）
     * 在 {@code total} 与 {@code input} 都为 null 时会 NPE（字节码里是
     * {@code getPromptTokens().intValue() + getCompletionTokens().intValue()}，
     * 与 {@code Usage} 接口那个 null 安全的 default 实现不是一回事），
     * 而「厂商什么都没报」恰恰是本方法该返回 null 的那一档。
     */
    private static Integer promptTokensOrNull(Usage usage) {
        if (usage == null || usage instanceof EmptyUsage) {
            return null;
        }
        Integer promptTokens = usage.getPromptTokens();
        if (promptTokens != null && promptTokens > 0) {
            return promptTokens;
        }
        // 文本向量这一路：SDK 把 total_tokens 算成了 0，原始值只在 nativeUsage 里还活着
        if (usage.getNativeUsage() instanceof DashScopeApiSpec.EmbeddingUsage embeddingUsage) {
            Long totalTokens = embeddingUsage.totalTokens();
            if (totalTokens != null && totalTokens > 0) {
                return totalTokens.intValue();
            }
        }
        try {
            Integer totalTokens = usage.getTotalTokens();
            return totalTokens != null && totalTokens > 0 ? totalTokens : null;
        } catch (NullPointerException ignored) {
            return null;
        }
    }

    /**
     * 重排序模型调通之后填的字段
     *
     * <p>{@code reply} 这一格对重排序模型不是「回复」，但它是弹窗里唯一一块能放结论的地方，
     * 与向量模型那格同一个用法：回一句<b>可核对的事实</b>而不是留空 ——
     * 留空会让「模型可用」这个绿条没有任何佐证。
     *
     * <p>🔴 回显<b>首条是哪个候选</b>而不只回条数：条数对不代表排序对。
     * 探活的两个候选是刻意设计成一条相关、一条无关的（见 {@code RERANK_TEST_*}），
     * 所以「首条是相关那条」才是真正说明模型在干活的证据。厂商偶尔不给分，
     * 那就不给分，不编一个数出来。
     *
     * <p>{@code embeddingDimensions} 与 {@code completionTokens} 刻意不填：
     * 重排序既不出向量也不生成 token，填 0 会被读成「厂商说它是 0」。
     */
    private static void fillRerankTestSuccess(AiModelTestRespVO respVO, RerankResponse response) {
        List<DocumentWithScore> results = response == null ? null : response.getResults();
        if (CollUtil.isEmpty(results)) {
            respVO.setReply("（厂商返回了响应，但里面没有排序结果）");
        } else {
            DocumentWithScore top = results.get(0);
            String score = top.getScore() == null ? "厂商未给分" : String.format("%.4f", top.getScore());
            String topText = top.getOutput() == null ? null : top.getOutput().getText();
            respVO.setReply(StrUtil.format("重排序成功，返回 {} 条，首条相关度 {}{}", results.size(), score,
                    StrUtil.isBlank(topText) ? "" : StrUtil.format("（首条是「{}」）",
                            StrUtil.maxLength(topText, TEST_REPLY_MAX_LENGTH))));
        }
        respVO.setPromptTokens(promptTokensOrNull(response == null || response.getMetadata() == null
                ? null : response.getMetadata().getUsage()));
    }

    private static int clampTimeout(Integer timeoutSeconds) {
        if (timeoutSeconds == null) {
            return TEST_TIMEOUT_DEFAULT_SECONDS;
        }
        return Math.min(Math.max(timeoutSeconds, TEST_TIMEOUT_MIN_SECONDS), TEST_TIMEOUT_MAX_SECONDS);
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * 截断，并把「截断过」这件事说出来 —— 静默截断会让管理员以为看到了全部原文
     */
    private static String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...（已截断，原文共 " + text.length() + " 字）";
    }

    // ========== 与 Spring AI 集成 ==========

    @Override
    public ChatModel getChatModel(Long id) {
        AiModelDO model = validateModel(id);
        AiApiKeyDO apiKey = apiKeyService.validateApiKey(model.getKeyId());
        AiPlatformEnum platform = AiPlatformEnum.validatePlatform(apiKey.getPlatform());
        return modelFactory.getOrCreateChatModel(platform, apiKey.getApiKey(), apiKey.getUrl());
    }

    @Override
    public ImageModel getImageModel(Long id) {
        AiModelDO model = validateModel(id);
        AiApiKeyDO apiKey = apiKeyService.validateApiKey(model.getKeyId());
        AiPlatformEnum platform = AiPlatformEnum.validatePlatform(apiKey.getPlatform());
        return modelFactory.getOrCreateImageModel(platform, apiKey.getApiKey(), apiKey.getUrl());
    }

    @Override
    public MidjourneyApi getMidjourneyApi(Long id) {
        AiModelDO model = validateModel(id);
        AiApiKeyDO apiKey = apiKeyService.validateApiKey(model.getKeyId());
        return modelFactory.getOrCreateMidjourneyApi(apiKey.getApiKey(), apiKey.getUrl());
    }

    @Override
    public SunoApi getSunoApi() {
        AiApiKeyDO apiKey = apiKeyService.getRequiredDefaultApiKey(
                AiPlatformEnum.SUNO.getPlatform(), CommonStatusEnum.ENABLE.getStatus());
        return modelFactory.getOrCreateSunoApi(apiKey.getApiKey(), apiKey.getUrl());
    }

    @Override
    public VectorStore getOrCreateVectorStore(Long id, Map<String, Class<?>> metadataFields) {
        // 获取模型 + 密钥
        AiModelDO model = validateModel(id);
        AiApiKeyDO apiKey = apiKeyService.validateApiKey(model.getKeyId());
        AiPlatformEnum platform = AiPlatformEnum.validatePlatform(apiKey.getPlatform());

        // 创建或获取 EmbeddingModel 对象
        EmbeddingModel embeddingModel = modelFactory.getOrCreateEmbeddingModel(
                platform, apiKey.getApiKey(), apiKey.getUrl(), model.getModel());

        // 创建或获取 VectorStore 对象
        // 🔴 model.getModel() 必须传下去：本地向量库的文件名按模型区分，
        //    不区分的话所有同平台模型共用一个文件、各自的 Timer 每分钟互相覆写
        return modelFactory.getOrCreateVectorStore(SimpleVectorStore.class, embeddingModel,
                model.getModel(), metadataFields);
//         return modelFactory.getOrCreateVectorStore(QdrantVectorStore.class, embeddingModel, model.getModel(), metadataFields);
//         return modelFactory.getOrCreateVectorStore(RedisVectorStore.class, embeddingModel, model.getModel(), metadataFields);
//         return modelFactory.getOrCreateVectorStore(MilvusVectorStore.class, embeddingModel, model.getModel(), metadataFields);
    }

}