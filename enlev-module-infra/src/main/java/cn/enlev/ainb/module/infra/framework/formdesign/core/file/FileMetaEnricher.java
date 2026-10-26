package cn.enlev.ainb.module.infra.framework.formdesign.core.file;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.infra.dal.dataobject.file.FileDO;
import cn.enlev.ainb.module.infra.dal.mysql.file.FileMapper;
import cn.enlev.ainb.module.infra.framework.formdesign.core.contract.WidgetContract;
import cn.enlev.ainb.module.infra.framework.formdesign.core.contract.WidgetContractRegistry;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 上传值的元信息补齐（B8）
 *
 * <h3>它补的是什么</h3>
 *
 * 设计方案 §5.9 要求上传字段的值统一成 {@code [{name, url, size, type}]}。
 * 改造前三个共享上传组件（{@code UploadImg} / {@code UploadImgs} / {@code UploadFile}）
 * <b>只发 URL</b>，所以库里存的是 {@code ["/xxx/4578fa….png"]} —— 一串哈希文件名，
 * 既看不出原文件名，也不知道多大、是什么类型。导出、列表、外部系统读库拿到的都是这个。
 *
 * <h3>为什么在服务端补，而不是让前端发完整的值</h3>
 *
 * 那三个组件在 {@code plugins/formCreate/index.ts} 里全局注册，
 * <b>src 下有 80 个调用方</b>不 import、直接靠它解析。改它们的值形态等于改这 80 处，
 * 正是交接文档第十一节要避开的事。
 *
 * 服务端补齐把这件事整个绕开：前端一行不用改，元信息从 {@code infra_file} 来 ——
 * 那张表在每次上传时就记了原文件名、大小、MIME。
 *
 * <ul>
 *   <li><b>落库形态</b>：JSON 数组，附件与多图<b>完全一致</b>
 *       （读时 {@code JsonUtil.getJsonToListMap}）。本类产出同样的形态</li>
 *       （{@code UploaderVO{name,url,fileVersionId,fileSize,fileExtension,thumbUrl}}），
 *       原文件名取自浏览器的 File 对象。我们改不动共享上传接口的返回形态
 *       （{@code CommonResult<String>}，同样是那 80 个调用方），所以改成从
 *       {@code infra_file} 反查 —— 那张表记的就是上传当时的这几样，保真度等价</li>
 *       {@code thumbUrl}（缩略图）、{@code fileVersionId}（预览版本）。这三样都是
 *       永远为空的字段，而「契约字段有几个是空的或假的，那比不做更糟」正是
 *       交接文档第十一节的原话</li>
 * </ul>
 *
 * <h3>只补数组型的文件字段，单图与签名仍是裸 URL</h3>
 *
 * {@code UploadImg} / {@code Signature} 的落库形态是 {@code storage=scalar}
 * 的一个 URL 字符串（见 {@code FormValueSerializer}）。
 * 它的单图控件 {@code UploadImgSingle} 存的同样是裸字符串。
 * 把它们也变成对象的话，{@code Mode.FIRST_URL} 那条落库路径要跟着改，
 * 而单图字段本来就只有一个值，包一层对象换不来什么。
 *
 * <h3>⚠️ 必须排在富媒体净化之后</h3>
 *
 * {@code RichContentGuard} 把内联 base64 转存成 URL，转存之后才有 {@code infra_file}
 * 记录。顺序反过来的话，base64 那一类值查不到元信息，补出来是空的 ——
 * 而它本该是能补上的。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class FileMetaEnricher {

    /**
     * 元素的键。与设计方案 §5.9 一致
     */
    private static final String KEY_NAME = "name";
    private static final String KEY_URL = "url";
    private static final String KEY_SIZE = "size";
    private static final String KEY_TYPE = "type";

    /**
     * 落库形态为 JSON 的标记。与控件契约里的 {@code storage} 取值一致
     */
    private static final String STORAGE_JSON = "json";

    @Resource
    private WidgetContractRegistry contractRegistry;
    @Resource
    private RichContentGuard richContentGuard;
    @Resource
    private FileMapper fileMapper;

    /**
     * 给提交数据里的文件字段补上元信息
     *
     * @param formFieldsJson 表单字段规则
     * @param data           提交数据。本方法<b>不修改入参</b>
     * @return 补齐后的数据
     */
    public Map<String, Object> enrich(String formFieldsJson, Map<String, Object> data) {
        Map<String, Object> result = data != null ? new LinkedHashMap<>(data) : new LinkedHashMap<>();
        // 只处理落库形态是数组的文件字段。单图/签名是裸 URL，见类注释
        List<RichContentGuard.RichField> fields = parseArrayFileFields(formFieldsJson);
        if (fields.isEmpty()) {
            return result;
        }

        // 先把这次提交涉及的 URL 一次性收齐再查库。逐个字段查的话，
        // 一次带三个附件字段的提交就是三次查询，而它们本可以合成一次
        Set<String> urls = new LinkedHashSet<>();
        for (RichContentGuard.RichField field : fields) {
            collectUrls(field, result, urls);
        }
        if (urls.isEmpty()) {
            return result;
        }
        Map<String, FileDO> metaByUrl = loadMeta(urls);
        if (metaByUrl.isEmpty()) {
            // 一个都查不到：可能是外部地址，也可能 infra_file 被清过。
            // 不改动数据 —— 补一堆只有 url 的对象，等于把形态换了却什么也没补上
            return result;
        }
        for (RichContentGuard.RichField field : fields) {
            applyEnrich(field, result, metaByUrl);
        }
        return result;
    }

    // ==================== 收集与查询 ====================

    private void collectUrls(RichContentGuard.RichField field, Map<String, Object> data,
                             Set<String> urls) {
        if (field.getSubField() == null) {
            collectFromValue(data.get(field.getField()), urls);
            return;
        }
        if (data.get(field.getSubField()) instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> map) {
                    collectFromValue(map.get(field.getField()), urls);
                }
            }
        }
    }

    private void collectFromValue(Object value, Set<String> urls) {
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String url && StrUtil.isNotBlank(url)) {
                    urls.add(url);
                } else if (item instanceof Map<?, ?> map && map.get(KEY_URL) instanceof String url
                        && StrUtil.isNotBlank(url)) {
                    // 已经是对象形态：可能是历史数据回填后又提交回来的。
                    // 也收进来 —— 元信息以库里的为准，能顺带把当初没补上的补上
                    urls.add(url);
                }
            }
        }
    }

    private Map<String, FileDO> loadMeta(Set<String> urls) {
        try {
            return fileMapper.selectListByUrls(urls).stream()
                    // 同一个地址理论上只有一条；真撞上重复时取先查到的那条，
                    // 而不是让 toMap 抛 IllegalStateException 把整次提交打挂
                    .collect(Collectors.toMap(FileDO::getUrl, Function.identity(), (a, b) -> a));
        } catch (Exception e) {
            // 补元信息是增强，不是约束。查库失败时按「补不上」处理，
            // 让提交照常进行 —— 为了几个展示用的字段把保存挡下来不合算
            log.warn("[loadMeta][按 URL 查文件元信息失败（{} 个地址），本次提交不补元信息]",
                    urls.size(), e);
            return Map.of();
        }
    }

    // ==================== 改写 ====================

    private void applyEnrich(RichContentGuard.RichField field, Map<String, Object> data,
                             Map<String, FileDO> metaByUrl) {
        if (field.getSubField() == null) {
            Object enriched = enrichValue(data.get(field.getField()), metaByUrl);
            if (enriched != null) {
                data.put(field.getField(), enriched);
            }
            return;
        }
        // 子表明细列：整列复制而不是就地改行，理由同 RichContentGuard ——
        // 「本方法不修改入参」这条约定被单据编号重试依赖着
        if (!(data.get(field.getSubField()) instanceof List<?> rows) || rows.isEmpty()) {
            return;
        }
        List<Object> copied = new ArrayList<>(rows.size());
        boolean changed = false;
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> map)) {
                copied.add(item);
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) map;
            Object enriched = enrichValue(row.get(field.getField()), metaByUrl);
            if (enriched == null) {
                copied.add(row);
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.put(field.getField(), enriched);
            copied.add(copy);
            changed = true;
        }
        if (changed) {
            data.put(field.getSubField(), copied);
        }
    }

    /**
     * 把一个字段的值补成对象数组
     *
     * @return 补齐后的值；不需要改动时返回 null
     */
    private Object enrichValue(Object value, Map<String, FileDO> metaByUrl) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        List<Object> items = new ArrayList<>(list.size());
        boolean changed = false;
        for (Object item : list) {
            String url = urlOf(item);
            if (url == null) {
                items.add(item);
                continue;
            }
            FileDO meta = metaByUrl.get(url);
            if (meta == null) {
                // 查不到就保持原样，不要造一个只有 url 的对象 ——
                // 同一个字段里一半是对象一半是字符串，读的人无从判断
                items.add(item);
                continue;
            }
            Map<String, Object> enriched = new LinkedHashMap<>();
            enriched.put(KEY_NAME, meta.getName());
            enriched.put(KEY_URL, url);
            enriched.put(KEY_SIZE, meta.getSize());
            enriched.put(KEY_TYPE, meta.getType());
            items.add(enriched);
            // 原本就是等价对象时也算「变了」无妨：值相同，写库结果一致
            changed = true;
        }
        return changed ? items : null;
    }

    private String urlOf(Object item) {
        if (item instanceof String url) {
            return StrUtil.isBlank(url) ? null : url;
        }
        if (item instanceof Map<?, ?> map && map.get(KEY_URL) instanceof String url) {
            return StrUtil.isBlank(url) ? null : url;
        }
        return null;
    }

    // ==================== 字段筛选 ====================

    /**
     * 找出落库形态是数组的文件字段
     *
     * 复用 {@link RichContentGuard#parseRichFields} 的递归（它已经处理了布局容器与子表），
     * 再按契约筛掉两类：非文件字段、以及 {@code storage != json} 的文件字段
     * （单图 / 签名，它们存的是裸 URL）。
     *
     * ⚠️ 判据全部来自控件契约，不在这里列控件名 —— 新增一个文件类控件时忘了改这里，
     * 表现会是那个控件的值永远补不上元信息，而且不报错。
     */
    private List<RichContentGuard.RichField> parseArrayFileFields(String formFieldsJson) {
        return richContentGuard.parseRichFields(formFieldsJson).stream()
                .filter(field -> field.getKind() == RichContentGuard.Kind.FILE)
                .filter(this::isJsonStorage)
                .collect(Collectors.toList());
    }

    /**
     * 落库形态是不是 JSON 数组
     *
     * 契约里查不到的控件按「不是」处理：拿不准的时候不去改动用户的值
     */
    private boolean isJsonStorage(RichContentGuard.RichField field) {
        WidgetContract contract = contractRegistry.get(field.getType());
        return contract != null && STORAGE_JSON.equals(contract.getStorage());
    }

}
