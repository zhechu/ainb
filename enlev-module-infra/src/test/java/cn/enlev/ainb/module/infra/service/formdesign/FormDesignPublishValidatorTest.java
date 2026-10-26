package cn.enlev.ainb.module.infra.service.formdesign;

import cn.enlev.ainb.framework.common.biz.system.dict.DictDataCommonApi;
import cn.enlev.ainb.framework.common.biz.system.dict.dto.DictTypeRespDTO;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignDO;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link FormDesignPublishValidator} 中「字典选项来源」校验的单元测试
 *
 * 这段校验防的是一类没有任何报错线索的故障：表单里的下拉框把某个字典当选项来源，
 * 字典后来被删除 / 停用 / 改成懒加载，页面上的选项就静默变成空白。
 * 用户只会觉得「这个下拉框坏了」，排查时也不会想到去看字典。
 * 发布是把它拦在上线之前的最后一道关口，所以这里的每条分支都要有用例守着。
 */
public class FormDesignPublishValidatorTest extends BaseMockitoUnitTest {

    @InjectMocks
    private FormDesignPublishValidator validator;

    @Mock
    private DictDataCommonApi dictDataApi;

    /** 造一个「某个控件把 dictType 当选项来源」的表单 */
    private FormDesignDO formWithDictSource(String title, String dictType) {
        String rule = String.format(
                "{\"type\":\"select\",\"field\":\"f1\",\"title\":\"%s\"," +
                        "\"props\":{\"dictSource\":{\"dictType\":\"%s\",\"valueType\":\"str\"}}}",
                title, dictType);
        FormDesignDO formDesign = new FormDesignDO();
        formDesign.setFormFields(FormFieldsCodec.write(List.of(rule)));
        return formDesign;
    }

    private DictTypeRespDTO dictType(String type, Integer status, Integer nodeType, Integer cacheMode) {
        DictTypeRespDTO dto = new DictTypeRespDTO();
        dto.setType(type);
        dto.setName(type);
        dto.setStatus(status);
        dto.setNodeType(nodeType);
        dto.setCacheMode(cacheMode);
        return dto;
    }

    @Test
    public void testValidateDictSources_success() {
        when(dictDataApi.getDictTypeMap(any())).thenReturn(Map.of(
                "area", dictType("area", CommonStatusEnum.ENABLE.getStatus(), 2, 1)));

        // 正常的字典不该被拦下来
        validator.validateDictSources(formWithDictSource("所属区域", "area"));
    }

    @Test
    public void testValidateDictSources_notExists() {
        when(dictDataApi.getDictTypeMap(any())).thenReturn(Map.of());

        assertServiceException(
                () -> validator.validateDictSources(formWithDictSource("所属区域", "area")),
                FORM_DESIGN_DICT_SOURCE_NOT_EXISTS, "所属区域", "area");
    }

    @Test
    public void testValidateDictSources_notEnable() {
        when(dictDataApi.getDictTypeMap(any())).thenReturn(Map.of(
                "area", dictType("area", CommonStatusEnum.DISABLE.getStatus(), 2, 1)));

        assertServiceException(
                () -> validator.validateDictSources(formWithDictSource("所属区域", "area")),
                FORM_DESIGN_DICT_SOURCE_NOT_ENABLE, "所属区域", "area");
    }

    @Test
    public void testValidateDictSources_isGroup() {
        // 分组只用来在管理界面上归拢字典，本身不挂数据，选了它选项永远是空的
        when(dictDataApi.getDictTypeMap(any())).thenReturn(Map.of(
                "area", dictType("area", CommonStatusEnum.ENABLE.getStatus(), 1, 1)));

        assertServiceException(
                () -> validator.validateDictSources(formWithDictSource("所属区域", "area")),
                FORM_DESIGN_DICT_SOURCE_IS_GROUP, "所属区域", "area");
    }

    @Test
    public void testValidateDictSources_lazy() {
        // 懒加载字典不进前端全量缓存，同步取不到，控件会渲染成空
        when(dictDataApi.getDictTypeMap(any())).thenReturn(Map.of(
                "area", dictType("area", CommonStatusEnum.ENABLE.getStatus(), 2, 2)));

        assertServiceException(
                () -> validator.validateDictSources(formWithDictSource("所属区域", "area")),
                FORM_DESIGN_DICT_SOURCE_LAZY, "所属区域", "area");
    }

    @Test
    public void testValidateDictSources_noDictSource() {
        // 没用到字典的表单不该去打字典接口
        FormDesignDO formDesign = new FormDesignDO();
        formDesign.setFormFields(FormFieldsCodec.write(List.of(
                "{\"type\":\"input\",\"field\":\"f1\",\"title\":\"名称\"}")));

        validator.validateDictSources(formDesign);
    }

    /**
     * 子表控件内部的字段同样要被扫到
     *
     * 子表的内部规则挂在 props.rule 下，与布局容器的 children 不是一回事。
     * 漏扫的话，明细行里的字典下拉会绕过全部校验。
     */
    @Test
    public void testValidateDictSources_insideSubTable() {
        String rule = "{\"type\":\"group\",\"field\":\"items\",\"title\":\"明细\",\"props\":{\"rule\":[" +
                "{\"type\":\"select\",\"field\":\"f2\",\"title\":\"明细区域\"," +
                "\"props\":{\"dictSource\":{\"dictType\":\"area\"}}}]}}";
        FormDesignDO formDesign = new FormDesignDO();
        formDesign.setFormFields(FormFieldsCodec.write(List.of(rule)));
        when(dictDataApi.getDictTypeMap(any())).thenReturn(Map.of());

        assertServiceException(() -> validator.validateDictSources(formDesign),
                FORM_DESIGN_DICT_SOURCE_NOT_EXISTS, "明细区域", "area");
    }

    /**
     * 布局容器嵌套的字段同样要被扫到
     */
    @Test
    public void testValidateDictSources_insideLayout() {
        String rule = "{\"type\":\"row\",\"children\":[{\"type\":\"col\",\"children\":[" +
                "{\"type\":\"cascader\",\"field\":\"f3\",\"title\":\"分类\"," +
                "\"props\":{\"dictSource\":{\"dictType\":\"category\"}}}]}]}";
        FormDesignDO formDesign = new FormDesignDO();
        formDesign.setFormFields(FormFieldsCodec.write(List.of(rule)));
        when(dictDataApi.getDictTypeMap(any())).thenReturn(Map.of());

        assertServiceException(() -> validator.validateDictSources(formDesign),
                FORM_DESIGN_DICT_SOURCE_NOT_EXISTS, "分类", "category");
    }

}
