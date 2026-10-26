package cn.enlev.ainb.framework.common.biz.system.dict;

import cn.enlev.ainb.framework.common.biz.system.dict.dto.DictDataRespDTO;
import cn.enlev.ainb.framework.common.biz.system.dict.dto.DictTypeRespDTO;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 字典数据 API 接口
 *
 * @author 芋道源码
 */
public interface DictDataCommonApi {

    /**
     * 获得指定字典类型的字典数据列表
     *
     * @param dictType 字典类型
     * @return 字典数据列表
     */
    List<DictDataRespDTO> getDictDataList(String dictType);

    /**
     * 获得指定字典类型的信息
     *
     * 供调用方判断字典是否存在、是否启用、是什么形态，各自决定怎么处理。
     * 这里不替调用方下判断，是因为「可用」的标准因场景而异——
     * 比如表单设计要求字典必须能进前端全量缓存，而后端取值就没有这个要求。
     *
     * @param dictType 字典类型
     * @return 字典类型信息；不存在时返回 null
     */
    DictTypeRespDTO getDictType(String dictType);

    /**
     * 批量获得字典类型的信息
     *
     * @param dictTypes 字典类型集合
     * @return 字典类型 -> 字典类型信息；不存在的字典类型不会出现在结果里
     */
    Map<String, DictTypeRespDTO> getDictTypeMap(Collection<String> dictTypes);

}
