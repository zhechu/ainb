package cn.enlev.ainb.module.system.api.dict;

import cn.hutool.core.collection.CollUtil;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.framework.common.biz.system.dict.dto.DictDataRespDTO;
import cn.enlev.ainb.framework.common.biz.system.dict.dto.DictTypeRespDTO;
import cn.enlev.ainb.module.system.dal.dataobject.dict.DictDataDO;
import cn.enlev.ainb.module.system.dal.dataobject.dict.DictTypeDO;
import cn.enlev.ainb.module.system.service.dict.DictDataService;
import cn.enlev.ainb.module.system.service.dict.DictTypeService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 字典数据 API 实现类
 *
 * @author 芋道源码
 */
@Service
public class DictDataApiImpl implements DictDataApi {

    @Resource
    private DictDataService dictDataService;

    @Resource
    private DictTypeService dictTypeService;

    @Override
    public void validateDictDataList(String dictType, Collection<String> values) {
        dictDataService.validateDictDataList(dictType, values);
    }

    @Override
    public List<DictDataRespDTO> getDictDataList(String dictType) {
        List<DictDataDO> list = dictDataService.getDictDataListByDictType(dictType);
        return BeanUtils.toBean(list, DictDataRespDTO.class);
    }

    @Override
    public DictTypeRespDTO getDictType(String dictType) {
        return BeanUtils.toBean(dictTypeService.getDictType(dictType), DictTypeRespDTO.class);
    }

    @Override
    public Map<String, DictTypeRespDTO> getDictTypeMap(Collection<String> dictTypes) {
        if (CollUtil.isEmpty(dictTypes)) {
            return Collections.emptyMap();
        }
        // 一次取回全部字典类型再在内存里筛。字典类型总量是几百的量级，
        // 全取一次比按集合逐个查库更省，也避免调用方传入大集合时打出 N 次查询
        Set<String> wanted = new HashSet<>(dictTypes);
        return dictTypeService.getDictTypeList().stream()
                .filter(dictType -> wanted.contains(dictType.getType()))
                .collect(Collectors.toMap(DictTypeDO::getType,
                        dictType -> BeanUtils.toBean(dictType, DictTypeRespDTO.class),
                        (a, b) -> a));
    }

}
