package cn.enlev.ainb.module.system.service.dict;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.transfer.DictImportRespVO;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.transfer.DictTransferVO;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.type.DictTypePageReqVO;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.type.DictTypeSaveReqVO;
import cn.enlev.ainb.module.system.dal.dataobject.dict.DictTypeDO;

import java.util.List;

/**
 * 字典类型 Service 接口
 *
 * @author 芋道源码
 */
public interface DictTypeService {

    /**
     * 创建字典类型
     *
     * @param createReqVO 字典类型信息
     * @return 字典类型编号
     */
    Long createDictType(DictTypeSaveReqVO createReqVO);

    /**
     * 更新字典类型
     *
     * @param updateReqVO 字典类型信息
     */
    void updateDictType(DictTypeSaveReqVO updateReqVO);

    /**
     * 删除字典类型
     *
     * @param id 字典类型编号
     */
    void deleteDictType(Long id);

    /**
     * 批量删除字典类型
     *
     * @param ids 字典类型编号列表
     */
    void deleteDictTypeList(List<Long> ids);

    /**
     * 获得字典类型分页列表
     *
     * @param pageReqVO 分页请求
     * @return 字典类型分页列表
     */
    PageResult<DictTypeDO> getDictTypePage(DictTypePageReqVO pageReqVO);

    /**
     * 获得字典类型详情
     *
     * @param id 字典类型编号
     * @return 字典类型
     */
    DictTypeDO getDictType(Long id);

    /**
     * 获得字典类型详情
     *
     * @param type 字典类型
     * @return 字典类型详情
     */
    DictTypeDO getDictType(String type);

    /**
     * 获得全部字典类型列表
     *
     * @return 字典类型列表
     */
    List<DictTypeDO> getDictTypeList();

    /**
     * 获得分类树的全部节点，按同级排序
     *
     * 返回的是扁平列表，由前端 handleTree 组装成树——与部门、菜单的做法一致。
     * keyword 非空时只返回命中项及其全部祖先，否则树在界面上会断掉。
     *
     * @param keyword 关键字，匹配名称或编码；为空表示不过滤
     * @return 字典类型列表
     */
    List<DictTypeDO> getDictTypeList(String keyword);

    /**
     * 移动分类
     *
     * @param id       分类编号
     * @param parentId 目标父分类编号
     * @param sort     目标同级排序
     */
    void moveDictType(Long id, Long parentId, Integer sort);

    /**
     * 导出一棵分类子树，连同子树下所有字典的数据
     *
     * 导的是「子树」而不是「单个节点」：分类之所以做成树，就是因为一组相关的字典要放在一起，
     * 只导根节点等于什么都没导，让用户逐个导出再手工拼装更不现实。
     *
     * @param id 分类编号，分组和字典都可以
     * @return 迁移包
     */
    DictTransferVO exportDictTypes(Long id);

    /**
     * 导入一个字典迁移包
     *
     * @param transfer 迁移包
     * @param policy   冲突处理策略，枚举 {@link cn.enlev.ainb.module.system.enums.dict.DictImportPolicyEnum}
     * @param parentId 包内根节点要挂到哪个分组下；为空表示挂到树的根
     * @return 逐个分类的导入结果
     */
    DictImportRespVO importDictTypes(DictTransferVO transfer, Integer policy, Long parentId);

}
