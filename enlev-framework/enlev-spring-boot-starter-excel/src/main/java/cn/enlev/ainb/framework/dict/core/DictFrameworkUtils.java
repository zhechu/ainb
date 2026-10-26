package cn.enlev.ainb.framework.dict.core;

import cn.hutool.core.collection.CollUtil;
import cn.enlev.ainb.framework.common.biz.system.dict.DictDataCommonApi;
import cn.enlev.ainb.framework.common.util.cache.CacheUtils;
import cn.enlev.ainb.framework.common.biz.system.dict.dto.DictDataRespDTO;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import static cn.enlev.ainb.framework.common.util.collection.CollectionUtils.convertList;

/**
 * 字典工具类
 *
 * @author 芋道源码
 */
@Slf4j
public class DictFrameworkUtils {

    private static DictDataCommonApi dictDataApi;

    /**
     * 针对 dictType 的字段数据缓存
     */
    private static final LoadingCache<String, List<DictDataRespDTO>> GET_DICT_DATA_CACHE = CacheUtils.buildAsyncReloadingCache(
            Duration.ofMinutes(1L), // 过期时间 1 分钟
            new CacheLoader<String, List<DictDataRespDTO>>() {

                @Override
                public List<DictDataRespDTO> load(String dictType) {
                    return dictDataApi.getDictDataList(dictType);
                }

            });

    public static void init(DictDataCommonApi dictDataApi) {
        DictFrameworkUtils.dictDataApi = dictDataApi;
        log.info("[init][初始化 DictFrameworkUtils 成功]");
    }

    public static void clearCache() {
        GET_DICT_DATA_CACHE.invalidateAll();
    }

    @SneakyThrows
    public static String parseDictDataLabel(String dictType, Integer value) {
        if (value == null) {
            return null;
        }
        return parseDictDataLabel(dictType, String.valueOf(value));
    }

    @SneakyThrows
    public static String parseDictDataLabel(String dictType, String value) {
        List<DictDataRespDTO> dictDatas = GET_DICT_DATA_CACHE.get(dictType);
        DictDataRespDTO dictData = CollUtil.findOne(dictDatas, data -> Objects.equals(data.getValue(), value));
        return dictData != null ? dictData.getLabel(): null;
    }

    @SneakyThrows
    public static List<String> getDictDataLabelList(String dictType) {
        List<DictDataRespDTO> dictDatas = GET_DICT_DATA_CACHE.get(dictType);
        return convertList(dictDatas, DictDataRespDTO::getLabel);
    }

    /**
     * 获得某个字典类型的全部字典项
     *
     * 给「导入」这类**需要整份字典**的场景用：它既要把中文标签反查成编码，
     * 又要在报错时把可选范围列给用户看。上面那几个方法都只解决其中一半 ——
     * 用 parseDictDataValue 反查是单向的，拿不到「还有哪些选项」。
     *
     * 与其它方法一样走缓存。返回的列表是缓存里的**原对象**，调用方不要修改它。
     *
     * @param dictType 字典类型
     * @return 字典项列表，字典不存在时返回空列表
     */
    @SneakyThrows
    public static List<DictDataRespDTO> getDictDataList(String dictType) {
        return GET_DICT_DATA_CACHE.get(dictType);
    }

    @SneakyThrows
    public static String parseDictDataValue(String dictType, String label) {
        List<DictDataRespDTO> dictDatas = GET_DICT_DATA_CACHE.get(dictType);
        DictDataRespDTO dictData = CollUtil.findOne(dictDatas, data -> Objects.equals(data.getLabel(), label));
        return dictData!= null ? dictData.getValue(): null;
    }

    @SneakyThrows
    public static List<String> getDictDataValueList(String dictType) {
        List<DictDataRespDTO> dictDatas = GET_DICT_DATA_CACHE.get(dictType);
        return convertList(dictDatas, DictDataRespDTO::getValue);
    }
}
