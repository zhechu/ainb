package cn.enlev.ainb.module.infra.service.datamodel;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.module.infra.controller.admin.datamodel.vo.DataModelDataPageReqVO;
import cn.enlev.ainb.module.infra.framework.datamodel.core.model.TableColumnModel;

import java.util.List;
import java.util.Map;

/**
 * 动态数据 Service 接口
 *
 * 「免代码生成的增删改查」：不生成任何 Java 代码，直接依据模型定义操作数据。
 *
 * 与 {@link DataModelService#getTableDataPage} 的区别：那个是建模页面的「打开数据」，
 * 纯只读、不带查询条件；本接口是完整的业务数据管理能力。
 *
 * @author 走进新时代
 */
public interface DataModelDataService {

    /**
     * 分页查询业务数据
     *
     * @param pageReqVO 查询条件
     * @return 分页结果，每行是「字段名 → 值」的 Map
     */
    PageResult<Map<String, Object>> getDataPage(DataModelDataPageReqVO pageReqVO);

    /**
     * 获得单条业务数据
     *
     * @param modelId 模型编号
     * @param id      数据主键
     */
    Map<String, Object> getData(Long modelId, Object id);

    /**
     * 创建业务数据
     *
     * @param modelId 模型编号
     * @param data    字段值
     * @return 自增主键
     */
    Object createData(Long modelId, Map<String, Object> data);

    /**
     * 更新业务数据
     *
     * @param modelId 模型编号
     * @param id      数据主键
     * @param data    字段值
     */
    void updateData(Long modelId, Object id, Map<String, Object> data);

    /**
     * 删除业务数据
     *
     * 表有 deleted 字段时做逻辑删除，否则物理删除
     *
     * @param modelId 模型编号
     * @param id      数据主键
     */
    void deleteData(Long modelId, Object id);

    /**
     * 批量删除业务数据
     *
     * @param modelId 模型编号
     * @param ids     主键列表
     */
    void deleteDataList(Long modelId, List<Object> ids);

    /**
     * 获得模型的字段定义，供前端渲染动态表单与列表
     *
     * @param modelId 模型编号
     */
    List<TableColumnModel> getDataColumns(Long modelId);

}
