package cn.enlev.ainb.module.ai.service.model.bo;

import cn.enlev.ainb.module.ai.dal.dataobject.model.AiModelDO;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 模型故障切换的候选项：模型 + 已预构建的 ChatModel 客户端
 *
 * ChatModel 在构建候选链时（调用线程）急切创建，切换路径（IO 线程）上不再触碰 DB 与工厂。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public class AiModelCandidate {

    /**
     * 模型配置
     */
    private final AiModelDO model;

    /**
     * 预构建的 ChatModel 客户端
     */
    private final ChatModel chatModel;

}
