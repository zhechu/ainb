package cn.enlev.ainb.module.system.service.dict;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.data.DictDataExcelVO;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.data.DictDataImportRespVO;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.data.DictDataPageReqVO;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.data.DictDataSaveReqVO;
import cn.enlev.ainb.module.system.dal.dataobject.dict.DictDataDO;
import cn.enlev.ainb.module.system.dal.redis.dict.DictDataCache;
import org.springframework.lang.Nullable;

import java.util.Collection;
import java.util.List;

/**
 * 字典数据 Service 接口
 *
 * @author ruoyi
 */
public interface DictDataService {

    /**
     * 创建字典数据
     *
     * @param createReqVO 字典数据信息
     * @return 字典数据编号
     */
    Long createDictData(DictDataSaveReqVO createReqVO);

    /**
     * 更新字典数据
     *
     * @param updateReqVO 字典数据信息
     */
    void updateDictData(DictDataSaveReqVO updateReqVO);

    /**
     * 删除字典数据
     *
     * @param id      字典数据编号
     * @param cascade 是否级联删除子节点；false 时若存在子节点会直接报错
     */
    void deleteDictData(Long id, boolean cascade);

    /**
     * 批量删除字典数据
     *
     * @param ids 字典数据编号列表
     */
    void deleteDictDataList(List<Long> ids);

    /**
     * 获得字典数据列表
     *
     * @param status   状态
     * @param dictType 字典类型
     * @return 字典数据全列表
     */
    List<DictDataDO> getDictDataList(@Nullable Integer status, @Nullable String dictType);

    /**
     * 获得全量启用的字典数据，供管理后台前端一次性缓存到本地
     *
     * 与 {@link #getDictDataList(Integer, String)} 的区别是走 Redis 缓存，
     * 因为这个接口每个登录用户每次刷新都会打，且没有权限校验。
     *
     * @return 字典数据全列表
     */
    List<DictDataDO> getSimpleDictDataList();

    /**
     * 获得全量启用的字典数据，连同产生它的版本号
     *
     * 版本号与数据来自同一次读写，因此一定是自洽的一对。调用方（前端）把这一对整体存下来，
     * 就不会出现「版本号是新的、数据是旧的」这种无法自愈的错配。
     *
     * @return 缓存条目
     */
    DictDataCache getDictDataCache();

    /**
     * 获得字典版本号
     *
     * 前端用它判断本地缓存是否还新鲜：一致就直接用本地的，不一致才去拉全量。
     * 注意版本号不保证单调递增（Redis 被清空会重新计数），调用方只能判断是否相等。
     *
     * @return 版本号
     */
    Long getDictVersion();

    /**
     * 获得字典数据分页列表
     *
     * @param pageReqVO 分页请求
     * @return 字典数据分页列表
     */
    PageResult<DictDataDO> getDictDataPage(DictDataPageReqVO pageReqVO);

    /**
     * 获得字典数据详情
     *
     * @param id 字典数据编号
     * @return 字典数据
     */
    DictDataDO getDictData(Long id);

    /**
     * 获得指定字典类型的数据数量
     *
     * @param dictType 字典类型
     * @return 数据数量
     */
    long getDictDataCountByDictType(String dictType);

    /**
     * 校验字典数据们是否有效。如下情况，视为无效：
     * 1. 字典数据不存在
     * 2. 字典数据被禁用
     *
     * @param dictType 字典类型
     * @param values   字典数据值的数组
     */
    void validateDictDataList(String dictType, Collection<String> values);

    /**
     * 获得指定的字典数据
     *
     * @param dictType 字典类型
     * @param value    字典数据值
     * @return 字典数据
     */
    DictDataDO getDictData(String dictType, String value);

    /**
     * 解析获得指定的字典数据，从缓存中
     *
     * @param dictType 字典类型
     * @param label    字典数据标签
     * @return 字典数据
     */
    DictDataDO parseDictData(String dictType, String label);

    /**
     * 获得指定数据类型的字典数据列表
     *
     * @param dictType 字典类型
     * @return 字典数据列表
     */
    List<DictDataDO> getDictDataListByDictType(String dictType);

    /**
     * 获得某个字典下的全部数据，按「父节点 → 同级排序」排列
     *
     * 返回的是扁平列表，由前端 handleTree 组装成树——与部门、菜单的做法一致。
     * keyword 非空时只返回命中项及其全部祖先，否则树在界面上会断掉。
     *
     * @param dictType 字典类型
     * @param keyword  关键字，匹配标签或键值；为空表示不过滤
     * @return 字典数据列表
     */
    List<DictDataDO> getDictDataTreeList(String dictType, String keyword);

    /**
     * 获得某个节点的直接子级，供懒加载字典按需展开
     *
     * @param dictType 字典类型
     * @param parentId 父节点编号
     * @return 字典数据列表
     */
    List<DictDataDO> getDictDataChildren(String dictType, Long parentId);

    /**
     * 移动字典数据节点
     *
     * @param id       节点编号
     * @param parentId 目标父节点编号
     * @param sort     目标同级排序
     */
    void moveDictData(Long id, Long parentId, Integer sort);

    /**
     * 导出某个字典的全部数据
     *
     * 父子关系用「上级键值」表达而不是编号：编号跨环境不一致，
     * 导出的文件换个环境导入就全乱了。
     *
     * @param dictType 字典类型
     * @return Excel 模型列表，按「父节点在前、子节点在后」排列
     */
    List<DictDataExcelVO> exportDictDataList(String dictType);

    /**
     * 导入字典数据
     *
     * @param dictType        导入到哪个字典
     * @param list            Excel 解析出的数据
     * @param isUpdateSupport 键值已存在时是否更新
     * @return 导入结果
     */
    DictDataImportRespVO importDictDataList(String dictType, List<DictDataExcelVO> list, boolean isUpdateSupport);

}
