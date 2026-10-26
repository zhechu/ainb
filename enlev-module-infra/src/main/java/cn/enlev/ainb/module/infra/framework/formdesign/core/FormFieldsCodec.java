package cn.enlev.ainb.module.infra.framework.formdesign.core;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.Collections;
import java.util.List;

/**
 * 表单字段规则的编解码工具
 *
 * form-create 的字段规则在前端是「逐条序列化的字符串数组」（designer.getRule() 的产物），
 * 在 DO 里存为整份 JSON 字符串，两侧需要转换。此约定与 bpm_form 一致，
 * 前端可直接复用 src/utils/formCreate.ts 的 encodeFields / decodeFields。
 *
 * @author 走进新时代
 */
public final class FormFieldsCodec {

    private FormFieldsCodec() {
    }

    /**
     * 字段规则列表 → JSON 字符串
     *
     * @param formFields 字段规则列表
     * @return JSON 字符串，空列表返回 null
     */
    public static String write(List<String> formFields) {
        if (CollUtil.isEmpty(formFields)) {
            return null;
        }
        return JsonUtils.toJsonString(formFields);
    }

    /**
     * JSON 字符串 → 字段规则列表
     *
     * 反序列化失败时返回空列表而非抛异常：配置结构会随迭代演进，
     * 历史脏数据不应导致整个列表页打不开。
     *
     * @param formFields JSON 字符串
     * @return 字段规则列表，解析失败返回空列表
     */
    public static List<String> read(String formFields) {
        if (StrUtil.isBlank(formFields)) {
            return Collections.emptyList();
        }
        List<String> result = JsonUtils.parseObjectQuietly(formFields, new TypeReference<List<String>>() {});
        return result != null ? result : Collections.emptyList();
    }

}
