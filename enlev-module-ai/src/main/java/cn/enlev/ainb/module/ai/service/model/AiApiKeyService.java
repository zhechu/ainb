package cn.enlev.ainb.module.ai.service.model;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.module.ai.controller.admin.model.vo.apikey.AiApiKeyPageReqVO;
import cn.enlev.ainb.module.ai.controller.admin.model.vo.apikey.AiApiKeySaveReqVO;
import cn.enlev.ainb.module.ai.dal.dataobject.model.AiApiKeyDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * AI API 密钥 Service 接口
 *
 * @author 芋道源码
 */
public interface AiApiKeyService {

    /**
     * 创建 API 密钥
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Long createApiKey(@Valid AiApiKeySaveReqVO createReqVO);

    /**
     * 更新 API 密钥
     *
     * @param updateReqVO 更新信息
     */
    void updateApiKey(@Valid AiApiKeySaveReqVO updateReqVO);

    /**
     * 只改密钥的启用状态
     *
     * <h3>🔴 为什么必须单独一个方法，不能复用 {@link #updateApiKey}</h3>
     * {@code AiApiKeyRespVO.apiKey} 带 {@code @SliderDesensitize(prefixKeep = 6, suffixKeep = 4)} ——
     * <b>列表页拿到的密钥是打码的</b>。若让前端把整行回传 {@code /update}，
     * {@code BeanUtils.toBean(reqVO, AiApiKeyDO.class)} + {@code updateById} 会把
     * {@code sk-abc***wxyz} 这样的<b>掩码串当成真密钥加密落库</b>，静默毁掉每一个密钥。
     * 这不是理论风险，是那条路的必然结果。
     *
     * <h3>影响面（写进前端确认文案的事实依据）</h3>
     * 密钥一禁用，挂在它下面的模型<b>全部</b>在故障切换候选链第 2 步与
     * {@code /ai/model/simple-list} 里被剔除，不是等到真发请求才失败。
     *
     * <p>与模型侧同理：{@code updateById} 刷新 {@code update_time}，
     * 而 {@code update_time} 正是故障切换冷却记忆的配置指纹。
     *
     * @param id 密钥编号
     * @param status 状态，见 {@link cn.enlev.ainb.framework.common.enums.CommonStatusEnum}
     */
    void updateApiKeyStatus(Long id, Integer status);

    /**
     * 删除 API 密钥
     *
     * @param id 编号
     */
    void deleteApiKey(Long id);

    /**
     * 获得 API 密钥
     *
     * @param id 编号
     * @return API 密钥
     */
    AiApiKeyDO getApiKey(Long id);

    /**
     * 校验 API 密钥
     *
     * @param id 比那好
     * @return API 密钥
     */
    AiApiKeyDO validateApiKey(Long id);

    /**
     * 获得 API 密钥分页
     *
     * @param pageReqVO 分页查询
     * @return API 密钥分页
     */
    PageResult<AiApiKeyDO> getApiKeyPage(AiApiKeyPageReqVO pageReqVO);

    /**
     * 获得 API 密钥列表
     *
     * @return API 密钥列表
     */
    List<AiApiKeyDO> getApiKeyList();

    /**
     * 获得默认的 API 密钥
     *
     * @param platform 平台
     * @param status 状态
     * @return API 密钥
     */
    AiApiKeyDO getRequiredDefaultApiKey(String platform, Integer status);

}