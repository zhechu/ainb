package cn.enlev.ainb.module.ai.service.model;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.module.ai.controller.admin.model.vo.apikey.AiApiKeyPageReqVO;
import cn.enlev.ainb.module.ai.controller.admin.model.vo.apikey.AiApiKeySaveReqVO;
import cn.enlev.ainb.module.ai.dal.dataobject.model.AiApiKeyDO;
import cn.enlev.ainb.module.ai.dal.mysql.model.AiApiKeyMapper;
import com.mzt.logapi.context.LogRecordContext;
import com.mzt.logapi.service.impl.DiffParseFunction;
import com.mzt.logapi.starter.annotation.LogRecord;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.ai.enums.ErrorCodeConstants.API_KEY_DISABLE;
import static cn.enlev.ainb.module.ai.enums.ErrorCodeConstants.API_KEY_NOT_EXISTS;
import static cn.enlev.ainb.module.ai.enums.LogRecordConstants.AI_API_KEY_CREATE_SUB_TYPE;
import static cn.enlev.ainb.module.ai.enums.LogRecordConstants.AI_API_KEY_CREATE_SUCCESS;
import static cn.enlev.ainb.module.ai.enums.LogRecordConstants.AI_API_KEY_DELETE_SUB_TYPE;
import static cn.enlev.ainb.module.ai.enums.LogRecordConstants.AI_API_KEY_DELETE_SUCCESS;
import static cn.enlev.ainb.module.ai.enums.LogRecordConstants.AI_API_KEY_TYPE;
import static cn.enlev.ainb.module.ai.enums.LogRecordConstants.AI_API_KEY_UPDATE_STATUS_SUB_TYPE;
import static cn.enlev.ainb.module.ai.enums.LogRecordConstants.AI_API_KEY_UPDATE_STATUS_SUCCESS;
import static cn.enlev.ainb.module.ai.enums.LogRecordConstants.AI_API_KEY_UPDATE_SUB_TYPE;
import static cn.enlev.ainb.module.ai.enums.LogRecordConstants.AI_API_KEY_UPDATE_SUCCESS;

/**
 * AI API 密钥 Service 实现类
 *
 * @author 芋道源码
 */
@Service
@Validated
public class AiApiKeyServiceImpl implements AiApiKeyService {

    @Resource
    private AiApiKeyMapper apiKeyMapper;

    @Override
    @LogRecord(type = AI_API_KEY_TYPE, subType = AI_API_KEY_CREATE_SUB_TYPE, bizNo = "{{#apiKey.id}}",
            success = AI_API_KEY_CREATE_SUCCESS)
    public Long createApiKey(AiApiKeySaveReqVO createReqVO) {
        // 插入
        AiApiKeyDO apiKey = BeanUtils.toBean(createReqVO, AiApiKeyDO.class);
        apiKeyMapper.insert(apiKey);

        // 记录操作日志上下文
        LogRecordContext.putVariable("apiKey", toLogContext(apiKey));
        // 返回
        return apiKey.getId();
    }

    @Override
    @LogRecord(type = AI_API_KEY_TYPE, subType = AI_API_KEY_UPDATE_SUB_TYPE, bizNo = "{{#updateReqVO.id}}",
            success = AI_API_KEY_UPDATE_SUCCESS)
    public void updateApiKey(AiApiKeySaveReqVO updateReqVO) {
        // 校验存在
        AiApiKeyDO oldApiKey = validateApiKeyExists(updateReqVO.getId());
        // 更新
        AiApiKeyDO updateObj = BeanUtils.toBean(updateReqVO, AiApiKeyDO.class);
        apiKeyMapper.updateById(updateObj);

        // 记录操作日志上下文（_DIFF 拿旧对象与新 ReqVO 比，只输出打了 @DiffLogField 的字段）
        AiApiKeySaveReqVO oldForLog = toLogContext(oldApiKey);
        LogRecordContext.putVariable(DiffParseFunction.OLD_OBJECT, oldForLog);
        LogRecordContext.putVariable("apiKey", oldForLog);
    }

    @Override
    @LogRecord(type = AI_API_KEY_TYPE, subType = AI_API_KEY_UPDATE_STATUS_SUB_TYPE, bizNo = "{{#updateReqVO.id}}",
            success = AI_API_KEY_UPDATE_STATUS_SUCCESS)
    public void updateApiKeyStatus(Long id, Integer status) {
        // 校验存在
        AiApiKeyDO oldApiKey = validateApiKeyExists(id);

        // 只写 status 一个字段：MyBatis-Plus 默认 NOT_NULL 策略，其余列一律不动。
        // 🔴 这条路径上 api_key 列永远不被触碰 —— 详见 AiApiKeyService#updateApiKeyStatus
        AiApiKeyDO updateObj = new AiApiKeyDO();
        updateObj.setId(id);
        updateObj.setStatus(status);
        apiKeyMapper.updateById(updateObj);

        // 记录操作日志上下文。toLogContext 把 apiKey 置 null，_DIFF 因此不可能带出密钥原文
        AiApiKeySaveReqVO oldForLog = toLogContext(oldApiKey);
        AiApiKeySaveReqVO newForLog = toLogContext(oldApiKey);
        newForLog.setStatus(status);
        LogRecordContext.putVariable(DiffParseFunction.OLD_OBJECT, oldForLog);
        LogRecordContext.putVariable("apiKey", oldForLog);
        LogRecordContext.putVariable("updateReqVO", newForLog);
    }

    @Override
    @LogRecord(type = AI_API_KEY_TYPE, subType = AI_API_KEY_DELETE_SUB_TYPE, bizNo = "{{#id}}",
            success = AI_API_KEY_DELETE_SUCCESS)
    public void deleteApiKey(Long id) {
        // 校验存在
        AiApiKeyDO apiKey = validateApiKeyExists(id);
        // 删除
        apiKeyMapper.deleteById(id);

        // 记录操作日志上下文
        LogRecordContext.putVariable("apiKey", toLogContext(apiKey));
    }

    /**
     * 转成【不含明文密钥】的日志上下文对象。
     *
     * <p>🔴 {@code ai_api_key.api_key} 落库是**加密**的（{@code AiApiKeyEncryptTypeHandler}），
     * 但那个 TypeHandler 只在这一张表的读写上生效 —— 查出来到 Java 内存里就已经是**解密后的原文**，
     * 而 {@code system_operate_log.action} 没有这层加密，写进去就是明文，
     * 进而进备份、进每一次日志导出。所以「库里是密文」并不等于「日志里也会是密文」。
     *
     * <p>一旦它进了 LogRecordContext，就可能被模板、异常栈、或日后谁给 VO 补一个 {@code @DiffLogField} 带出去，
     * 所以这里从源头掐掉，不依赖「记得别打注解」那一层。
     */
    private AiApiKeySaveReqVO toLogContext(AiApiKeyDO apiKey) {
        AiApiKeySaveReqVO logContext = BeanUtils.toBean(apiKey, AiApiKeySaveReqVO.class);
        logContext.setApiKey(null);
        return logContext;
    }

    private AiApiKeyDO validateApiKeyExists(Long id) {
        AiApiKeyDO apiKey = apiKeyMapper.selectById(id);
        if (apiKey == null) {
            throw exception(API_KEY_NOT_EXISTS);
        }
        return apiKey;
    }

    @Override
    public AiApiKeyDO getApiKey(Long id) {
        return apiKeyMapper.selectById(id);
    }

    @Override
    public AiApiKeyDO validateApiKey(Long id) {
        AiApiKeyDO apiKey = validateApiKeyExists(id);
        if (CommonStatusEnum.isDisable(apiKey.getStatus())) {
            throw exception(API_KEY_DISABLE);
        }
        return apiKey;
    }

    @Override
    public PageResult<AiApiKeyDO> getApiKeyPage(AiApiKeyPageReqVO pageReqVO) {
        return apiKeyMapper.selectPage(pageReqVO);
    }

    @Override
    public List<AiApiKeyDO> getApiKeyList() {
        return apiKeyMapper.selectList();
    }

    @Override
    public AiApiKeyDO getRequiredDefaultApiKey(String platform, Integer status) {
        AiApiKeyDO apiKey = apiKeyMapper.selectFirstByPlatformAndStatus(platform, status);
        if (apiKey == null) {
            throw exception(API_KEY_NOT_EXISTS);
        }
        return apiKey;
    }

}