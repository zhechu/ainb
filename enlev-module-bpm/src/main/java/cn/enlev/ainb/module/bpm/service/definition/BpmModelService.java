package cn.enlev.ainb.module.bpm.service.definition;

import cn.enlev.ainb.module.bpm.controller.admin.definition.vo.model.BpmModelSaveReqVO;
import cn.enlev.ainb.module.bpm.controller.admin.definition.vo.model.simple.BpmSimpleModelNodeVO;
import cn.enlev.ainb.module.bpm.controller.admin.definition.vo.model.simple.BpmSimpleModelUpdateReqVO;
import jakarta.validation.Valid;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.engine.repository.Model;

import java.util.List;

/**
 * 流程模型接口
 *
 * @author yunlongn
 */
public interface BpmModelService {

    /**
     * 获得流程模型列表
     *
     * @param name 模型名称
     * @return 流程模型列表
     */
    List<Model> getModelList(String name);

    /**
     * 根据分类编码获得流程模型数量
     *
     * @param category 分类编码
     * @return 流程模型数量
     */
    Long getModelCountByCategory(String category);

    /**
     * 创建流程模型
     *
     * @param modelVO 创建信息
     * @return 创建的流程模型的编号
     */
    String createModel(@Valid BpmModelSaveReqVO modelVO);

    /**
     * 导入流程模型
     *
     * @param reqVO 导入信息
     * @return 创建的流程模型的编号
     */
    String importModel(@Valid BpmModelSaveReqVO reqVO);

    /**
     * 导出流程模型
     *
     * @param id 编号
     * @return 导出的流程模型
     */
    BpmModelSaveReqVO exportModel(String id);

    /**
     * 获得流程模块
     *
     * @param id 编号
     * @return 流程模型
     */
    Model getModel(String id);

    /**
     * 按流程标识获得流程模型
     *
     * 供「按 key 覆盖式发布」使用：流程标识是全局唯一的，
     * {@code createModel} 撞上已存在的 key 会直接拒绝，
     * 调用方需要先判断该走创建还是走更新。
     *
     * @param key 流程标识
     * @return 流程模型，不存在时返回 null
     */
    Model getModelByKey(String key);

    /**
     * 获得流程模型的 BPMN XML
     *
     * @param id 编号
     * @return BPMN XML
     */
    byte[] getModelBpmnXML(String id);

    /**
     * 修改流程模型
     *
     * @param userId 用户编号
     * @param updateReqVO 更新信息
     */
    void updateModel(Long userId, @Valid BpmModelSaveReqVO updateReqVO);

    /**
     * 批量更新模型排序
     *
     * @param userId 用户编号
     * @param ids 编号列表
     */
    void updateModelSortBatch(Long userId, List<String> ids);

    /**
     * 将流程模型，部署成一个流程定义
     *
     * @param userId 用户编号
     * @param id 编号
     */
    void deployModel(Long userId, String id);

    /**
     * 删除模型
     *
     * @param userId  用户编号
     * @param id 编号
     */
    void deleteModel(Long userId, String id);

    /**
     * 清理模型，包括流程实例
     *
     * @param userId  用户编号
     * @param id 编号
     */
    void cleanModel(Long userId, String id);

    /**
     * 修改模型的状态，实际更新的部署的流程定义的状态
     *
     * @param userId 用户编号
     * @param id    编号
     * @param state 状态
     */
    void updateModelState(Long userId, String id, Integer state);

    /**
     * 获得流程定义编号对应的 BPMN Model
     *
     * @param processDefinitionId 流程定义编号
     * @return BPMN Model
     */
    BpmnModel getBpmnModelByDefinitionId(String processDefinitionId);

    // ========== 仿钉钉/飞书的精简模型 =========

    /**
     * 获取仿钉钉流程设计模型结构
     *
     * @param modelId 流程模型编号
     * @return 仿钉钉流程设计模型结构
     */
    BpmSimpleModelNodeVO getSimpleModel(String modelId);

    /**
     * 更新仿钉钉流程设计模型
     *
     * @param userId 用户编号
     * @param reqVO 请求信息
     */
    void updateSimpleModel(Long userId, @Valid BpmSimpleModelUpdateReqVO reqVO);

}
