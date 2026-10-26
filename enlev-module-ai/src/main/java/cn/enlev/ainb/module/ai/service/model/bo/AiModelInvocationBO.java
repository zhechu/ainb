package cn.enlev.ainb.module.ai.service.model.bo;

import cn.enlev.ainb.module.ai.dal.dataobject.model.AiModelDO;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 模型调用结果：payload + 实际使用的模型
 *
 * 发生故障切换后，实际使用的模型可能与请求/会话快照的模型不同，
 * 调用方据此回写消息表、计量日志，保证可追溯。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public class AiModelInvocationBO<T> {

    /**
     * 实际使用的模型
     */
    private final AiModelDO model;

    /**
     * 调用结果
     */
    private final T payload;

}
