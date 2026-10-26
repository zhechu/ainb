package cn.enlev.ainb.module.ai.service.model;

import cn.enlev.ainb.module.ai.framework.ai.core.model.midjourney.api.MidjourneyApi;
import cn.enlev.ainb.module.ai.framework.ai.core.model.suno.api.SunoApi;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.module.ai.controller.admin.model.vo.model.AiModelPageReqVO;
import cn.enlev.ainb.module.ai.controller.admin.model.vo.model.AiModelSaveReqVO;
import cn.enlev.ainb.module.ai.controller.admin.model.vo.model.AiModelTestRespVO;
import cn.enlev.ainb.module.ai.dal.dataobject.model.AiModelDO;
import jakarta.validation.Valid;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.vectorstore.VectorStore;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * AI 模型 Service 接口
 *
 * @author fansili
 * @since 2024/4/24 19:42
 */
public interface AiModelService {

    /**
     * 创建模型
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Long createModel(@Valid AiModelSaveReqVO createReqVO);

    /**
     * 更新模型
     *
     * @param updateReqVO 更新信息
     */
    void updateModel(@Valid AiModelSaveReqVO updateReqVO);

    /**
     * 只改模型的启用状态
     *
     * <h3>为什么不复用 {@link #updateModel}</h3>
     * {@code AiModelSaveReqVO} 的<b>每个字段都是 {@code @NotNull}/{@code @NotEmpty}</b>，
     * 走那条路就得把整行数据重新提交一遍 —— 而 2026-10-10 19:27:28 / 19:27:42 / 19:27:56 /
     * 19:28:15 / 19:28:36 那串连续的 {@code update_time}，正是管理员在故障风暴里
     * 逐个开弹窗禁用模型留下的痕迹：一次点击的事，走了五遍完整表单。
     *
     * <h3>🔴 刻意不校验密钥</h3>
     * {@code updateModel} 里的 {@code apiKeyService.validateApiKey(keyId)} 在密钥被禁用时抛
     * {@link cn.enlev.ainb.module.ai.enums.ErrorCodeConstants#API_KEY_DISABLE}，
     * 套到状态切换上就变成「密钥关着的时候模型连启用都不让启用」。
     * 切换状态并没有改变模型与密钥的绑定关系；而密钥禁用时该模型本来就被
     * {@code simple-list} 与故障切换候选链第 2 步过滤掉 —— 多这一道拦不住任何东西，只会让人困惑。
     *
     * <h3>🔴 必须挂审计</h3>
     * {@code status} 正是 2026-10-08 那轮「T1 跑到一半模型被停用、27 分钟作废、
     * 事后问不出谁改的」的字段。所以这里构造一对 {@link AiModelSaveReqVO}（旧的来自 DO，
     * 新的 = 旧副本改 status）交给 {@code _DIFF}，日志因此只输出状态那一行。
     *
     * <p>副作用（刻意的）：{@code updateById} 会刷新 {@code update_time}，而故障切换的冷却记忆
     * 以 {@code update_time} 为配置指纹 —— 于是「点一下启用」同时等于「清掉这个模型的失败记忆，
     * 立刻重新纳入候选」，不必再等最长 30 分钟的配置类冷却。
     *
     * @param id 模型编号
     * @param status 状态，见 {@link cn.enlev.ainb.framework.common.enums.CommonStatusEnum}
     */
    void updateModelStatus(Long id, Integer status);

    /**
     * 删除模型
     *
     * @param id 编号
     */
    void deleteModel(Long id);

    /**
     * 获得模型
     *
     * @param id 编号
     * @return 模型
     */
    AiModelDO getModel(Long id);

    /**
     * 获得默认的模型
     *
     * 如果获取不到，则抛出 {@link cn.enlev.ainb.framework.common.exception.ServiceException} 业务异常
     *
     * @return 模型
     */
    AiModelDO getRequiredDefaultModel(Integer type);

    /**
     * 获得模型分页
     *
     * @param pageReqVO 分页查询
     * @return 模型分页
     */
    PageResult<AiModelDO> getModelPage(AiModelPageReqVO pageReqVO);

    /**
     * 校验模型是否可使用
     *
     * @param id 编号
     * @return 模型
     */
    AiModelDO validateModel(Long id);

    /**
     * 获得模型列表
     *
     * @param status 状态
     * @param type 类型
     * @param platform 平台，允许空
     * @return 模型列表
     */
    List<AiModelDO> getModelListByStatusAndType(Integer status, Integer type,
                                                @Nullable String platform);

    /**
     * 测试单个模型的可用性：发一次真实的最小对话请求
     *
     * <h3>为什么需要它</h3>
     * {@code ai_model.status} 只表达「管理员想不想用它」，不表达「它到底能不能用」。
     * 选择期的两道过滤（模型启用 + 密钥启用）全过之后，仍要到真发请求才吃到厂商的
     * {@code 400 url error} / {@code 404 Model not exist} / {@code enable_thinking restricted to True}。
     * 管理员在 UI 上看不出任何一个模型是坏的，只能靠「用一次、失败一次、再去禁用一个」逐个试错。
     *
     * <h3>🔴 刻意绕开 {@code AiModelFailoverServiceImpl}，0 侵入生产链</h3>
     * 不走 {@code buildCandidateChain} + {@code callWithFallback}，两个理由：
     * <ol>
     *   <li>{@code callWithFallback} 失败会 {@code recordFailure} 给<b>生产链</b>上 60 秒冷却
     *       （额度误判甚至 30 分钟）—— 一次测试就能把真模型拉黑</li>
     *   <li>链式切换意味着「测试通过」可能是别的候选替它通过的，那是在骗管理员</li>
     * </ol>
     * {@code buildPinnedChain} 虽能钉单模型，但它对禁用/额度直接返回空链，拿不到厂商原文。
     *
     * <h3>🔴 失败不抛异常</h3>
     * 除「模型不存在」复用 {@link cn.enlev.ainb.module.ai.enums.ErrorCodeConstants#MODEL_NOT_EXISTS}
     * 之外，一律回 {@code success=false} + 明细 —— 本方法的价值恰恰在失败明细里，
     * 抛异常会把它压成一句 message。零新增错误码。
     *
     * @param id 模型编号。<b>允许是已禁用的模型</b>：能不能用与启不启用是两件事
     * @param timeoutSeconds 硬超时，null 取 30，服务端夹到 [5, 120]
     * @return 结构化的可用/不可用结论 + 厂商原始错误 + 路由事实
     */
    AiModelTestRespVO testModel(Long id, @Nullable Integer timeoutSeconds);

    // ========== 与 Spring AI 集成 ==========

    /**
     * 获得 ChatModel 对象
     *
     * @param id 编号
     * @return ChatModel 对象
     */
    ChatModel getChatModel(Long id);

    /**
     * 获得 ImageModel 对象
     *
     * @param id 编号
     * @return ImageModel 对象
     */
    ImageModel getImageModel(Long id);

    /**
     * 获得 MidjourneyApi 对象
     *
     * @param id 编号
     * @return MidjourneyApi 对象
     */
    MidjourneyApi getMidjourneyApi(Long id);

    /**
     * 获得 SunoApi 对象
     *
     * @return SunoApi 对象
     */
    SunoApi getSunoApi();

    /**
     * 获得 VectorStore 对象
     *
     * @param id 编号
     * @param metadataFields 元数据的定义
     * @return VectorStore 对象
     */
    VectorStore getOrCreateVectorStore(Long id, Map<String, Class<?>> metadataFields);

}
