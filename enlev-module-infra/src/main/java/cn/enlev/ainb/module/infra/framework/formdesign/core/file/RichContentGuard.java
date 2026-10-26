package cn.enlev.ainb.module.infra.framework.formdesign.core.file;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.module.infra.api.file.FileApi;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import cn.enlev.ainb.module.infra.framework.formdesign.core.contract.WidgetContractRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.FORM_DESIGN_FILE_TRANSFER_FAIL;

/**
 * 富媒体内容净化：内联 base64 图片转存文件服务 + 富文本按白名单清洗
 *
 * <h3>为什么必须有这一步</h3>
 *
 * 手写签名的画布产物、富文本里粘贴进来的图片，天然形态都是
 * {@code data:image/png;base64,....} 的内联串，单张 20–100KB。它直接落库的后果不是「有点浪费」：
 *
 * <ul>
 *   <li>映射到 {@code varchar(255)} 的列直接写失败，报的是数据库层的截断错误，用户看不懂</li>
 *   <li>映射到 TEXT 能写进去，但列表查询 {@code SELECT *} 会把每一行的整串 base64 一起捞出来</li>
 *   <li>导出时体积异常，一张几十行的表能导出上百 MB</li>
 * </ul>
 *
 * 前端已经改成上传文件服务后只提交 URL（Signature 控件、富文本的 uploadImgShowBase64=false），
 * 但那只是体验层：构造一个请求就能绕过。所以服务端这一步是【唯一】能保证
 * 「库里不出现 base64」的地方，与公式重算、系统值剥离是同一类兜底。
 *
 * <h3>转存必须排在清洗之前</h3>
 *
 * 清洗用的白名单只允许 {@code img[src]} 走 http/https —— {@code data:} 协议会被丢掉。
 * 顺序反过来的话，base64 图片会在清洗阶段被静默删除，用户看到的是「粘贴的图片保存后不见了」，
 * 而且没有任何报错。两件事写在同一个类里就是为了让这个顺序不可能被拆错。
 *
 * <h3>转存失败为什么报错而不是降级</h3>
 *
 * 本模块的容错原则是「配置坏掉就降级 + 记日志」，但这里不适用：能降级成什么？
 * 保留 base64 等于把上面三个后果全部放进库，丢弃值等于悄悄吞掉用户刚签的名。
 * 文件服务不可用是基础设施故障而不是配置错误，报错让用户重试是唯一诚实的处理。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class RichContentGuard {

    /**
     * 上传到文件服务时使用的目录
     */
    private static final String UPLOAD_DIRECTORY = "form-design";

    /**
     * data URL 的前缀，形如 {@code data:image/png;base64,}
     *
     * 只认 base64 编码的那一种。{@code data:image/svg+xml,<svg…>} 这类未编码的 data URL
     * 不在这里处理 —— 它们不是「图片被内联了」，而是一段可执行内容，由清洗环节丢掉。
     */
    private static final Pattern DATA_URL_PREFIX = Pattern.compile(
            "^\\s*data:([\\w.+-]+/[\\w.+-]+)?;?base64,", Pattern.CASE_INSENSITIVE);

    /**
     * base64 正文的合法字符
     *
     * 为什么要自己先校一遍，而不是直接交给解码器：
     * {@link Base64#getMimeDecoder()} 会【静默忽略】字母表之外的所有字符，
     * 把 {@code data:image/png;base64,这不是图片} 解成 4 个字节的垃圾并当成图片存下去；
     * 而 {@link Base64#getDecoder()} 又会因为 HTML 属性里常见的换行直接抛异常。
     * 所以先去掉空白、再用这条正则确认是纯 base64，然后交给严格解码器。
     */
    private static final Pattern BASE64_BODY = Pattern.compile("[A-Za-z0-9+/]+={0,2}");

    /**
     * 富文本的标签白名单
     *
     * 以 {@link Safelist#relaxed()} 为底，补三类它没有但编辑器会产出的东西：
     *
     * <ul>
     *   <li>{@code style} / {@code class}：编辑器的字号、颜色、对齐、表格列宽全靠内联样式。
     *       不放行会让「保存后格式全丢」，而这是最容易被当成编辑器 bug 的一种表现</li>
     *   <li>{@code video} / {@code source}：编辑器支持插入视频，relaxed 里没有这两个标签</li>
     *   <li>{@code a[target]}：编辑器的「在新窗口打开」</li>
     * </ul>
     *
     * 真正的 XSS 载体（{@code script}、{@code on*} 事件属性、{@code javascript:} 协议、
     * {@code iframe}、{@code object}）relaxed 本来就不放行，这里也不去放开。
     *
     * ⚠️ img/video 的 src 限定 http/https。文件服务返回的是绝对地址，正常内容不受影响；
     * 但如果有人手工往富文本里塞相对路径的图片，它会被丢掉 —— 所以清洗改动了内容时一定记 warn。
     */
    private static final Safelist RICH_TEXT_SAFELIST = Safelist.relaxed()
            .addTags("video", "source", "hr", "figure", "figcaption", "del", "ins", "s")
            .addAttributes(":all", "style", "class")
            .addAttributes("video", "src", "controls", "poster", "width", "height")
            .addAttributes("source", "src", "type")
            .addAttributes("a", "target", "rel")
            .addProtocols("img", "src", "http", "https")
            .addProtocols("video", "src", "http", "https")
            .addProtocols("source", "src", "http", "https");

    @Resource
    private WidgetContractRegistry contractRegistry;
    @Resource
    private FileApi fileApi;

    /**
     * 净化提交数据里的富媒体字段
     *
     * @param formFieldsJson 表单字段规则 JSON
     * @param data           提交数据。本方法不修改入参
     * @return 净化后的数据
     */
    public Map<String, Object> purify(String formFieldsJson, Map<String, Object> data) {
        Map<String, Object> result = data != null ? new LinkedHashMap<>(data) : new LinkedHashMap<>();
        List<RichField> fields = parseRichFields(formFieldsJson);
        for (RichField field : fields) {
            if (field.getSubField() == null) {
                purifyMainField(field, result);
            } else {
                purifySubField(field, result);
            }
        }
        return result;
    }

    // ==================== 主表字段 ====================

    private void purifyMainField(RichField field, Map<String, Object> data) {
        Object value = data.get(field.getField());
        Object purified = purifyValue(field, value);
        if (purified != value) {
            data.put(field.getField(), purified);
        }
    }

    /**
     * 净化子表明细列
     *
     * 改动时整列复制而不是就地改行：行 Map 是调用方传进来的那一份，
     * 就地改会让「本方法不修改入参」这条约定失效 —— 而单据编号重试正依赖
     * 「每次尝试用一份干净的数据副本」，入参被改过会让重试拿到上一次的结果。
     */
    private void purifySubField(RichField field, Map<String, Object> data) {
        Object raw = data.get(field.getSubField());
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        List<Object> rows = new ArrayList<>(list.size());
        boolean changed = false;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                rows.add(item);
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) map;
            Object value = row.get(field.getField());
            Object purified = purifyValue(field, value);
            if (purified == value) {
                rows.add(row);
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.put(field.getField(), purified);
            rows.add(copy);
            changed = true;
        }
        if (changed) {
            data.put(field.getSubField(), rows);
        }
    }

    // ==================== 按值形态分派 ====================

    /**
     * 净化一个字段值
     *
     * @return 净化后的值。没有任何改动时【返回入参本身】，调用方靠引用相等判断是否要写回
     */
    private Object purifyValue(RichField field, Object value) {
        if (value == null) {
            return null;
        }
        if (field.getKind() == Kind.RICH_TEXT) {
            return value instanceof String html ? purifyHtml(html, field.label()) : value;
        }
        return purifyFileValue(value, field.label());
    }

    /**
     * 净化文件型字段的值
     *
     * 值的形态有三种，都要覆盖：裸 URL 字符串（单图 / 签名）、URL 或文件对象的数组
     * （多图 / 多文件），以及单个文件对象。少覆盖一种的表现是那一种照旧把 base64 写进库。
     */
    private Object purifyFileValue(Object value, String label) {
        if (value instanceof String text) {
            return transferDataUrl(text, label);
        }
        if (value instanceof List<?> list) {
            List<Object> items = new ArrayList<>(list.size());
            boolean changed = false;
            for (Object item : list) {
                Object purified = purifyFileValue(item, label);
                changed |= purified != item;
                items.add(purified);
            }
            return changed ? items : value;
        }
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> file = (Map<String, Object>) map;
            if (!(file.get("url") instanceof String url)) {
                return value;
            }
            String transferred = transferDataUrl(url, label);
            if (transferred.equals(url)) {
                return value;
            }
            Map<String, Object> copy = new LinkedHashMap<>(file);
            copy.put("url", transferred);
            return copy;
        }
        return value;
    }

    // ==================== 富文本 ====================

    /**
     * 净化一段独立的 HTML —— 目前的调用方是打印模板
     *
     * 与富文本字段走的是同一条路（转存内联图片 + 白名单清洗），因为两者是同一件事：
     * 一段由人写出来、将来会被塞进页面渲染的 HTML。模板的作者是表单设计者而不是
     * 填表人，但「设计者比填表人可信」并不等于「设计者可以往里写 script」——
     * 表单设计权限目前就等于脚本编辑权限（见交接文档的台账第 4 项），
     * 那已经是一处待收紧的面，不该再多开一处。
     *
     * ⚠️ 这一步清洗的是【模板】。模板里 {@code {字段}} 位置上填进去的【值】
     * 是填表人填的，必须由插值那一步做 HTML 转义 —— 两者各管一段，缺任何一段都有洞。
     * 值那一段在前端（{@code printTemplate.ts}）。
     *
     * @param html 模板 HTML
     * @return 清洗后的 HTML；入参为空时原样返回
     */
    public String purifyHtml(String html) {
        return purifyHtml(html, "打印模板");
    }

    /**
     * 净化一段富文本：先把内联 base64 图片转存，再按白名单清洗
     *
     * <h3>为什么要拿「同样序列化过一遍」的串做比较</h3>
     *
     * Jsoup 解析再输出会规范化属性顺序、补齐未闭合标签、按自己的规则缩进 ——
     * 直接把它的输出和用户提交的原串比，几乎每次都「不相等」。
     * 那会导致两件事：每次提交都记一条清洗告警（真正的告警被淹掉），
     * 以及每次提交都把值改写一遍（内容相同、字节不同）。
     *
     * 所以比较的两端都经过同一个序列化器：一端只解析不清洗，一端解析后清洗。
     * 两者的差异就只剩「清洗真的删掉了东西」。
     */
    private String purifyHtml(String html, String label) {
        if (StrUtil.isBlank(html)) {
            return html;
        }
        Document parsed = Jsoup.parseBodyFragment(html);
        boolean transferred = transferInlineImages(parsed, html, label);
        parsed.outputSettings().prettyPrint(false);
        String normalized = parsed.body().html();

        Document cleanDoc = new Cleaner(RICH_TEXT_SAFELIST).clean(parsed);
        cleanDoc.outputSettings().prettyPrint(false);
        String cleaned = cleanDoc.body().html();

        if (!cleaned.equals(normalized)) {
            // 清洗是有损的：被丢掉的可能是 XSS 载体，也可能是编辑器新版本产出的、
            // 白名单还没覆盖的标签。后者的表现是「保存后格式丢了」，用户不会想到是服务端清洗，
            // 所以这条日志是排查那类问题的唯一线索
            log.warn("[purifyHtml][字段({})的富文本内容在清洗中被改动，清洗前 {} 字符、清洗后 {} 字符]",
                    label, normalized.length(), cleaned.length());
        }
        if (!transferred && cleaned.equals(normalized)) {
            return html;
        }
        return cleaned;
    }

    /**
     * 把富文本里 src 为 data URL 的内联图片转存文件服务
     *
     * @param doc 就地修改
     * @return 是否发生了转存
     */
    private boolean transferInlineImages(Document doc, String html, String label) {
        if (!html.contains("base64,")) {
            // 绝大多数富文本内容里没有内联图片，先做一次廉价判断，
            // 省掉一次全文档的属性扫描
            return false;
        }
        boolean changed = false;
        for (Element element : doc.select("[src]")) {
            String src = element.attr("src");
            String transferred = transferDataUrl(src, label);
            if (!transferred.equals(src)) {
                element.attr("src", transferred);
                changed = true;
            }
        }
        return changed;
    }

    // ==================== 转存 ====================

    /**
     * 把一个 data URL 转存到文件服务
     *
     * @return 文件服务的访问地址；入参不是 base64 形态的 data URL 时【原样返回入参】
     */
    private String transferDataUrl(String value, String label) {
        if (value == null) {
            return null;
        }
        Matcher matcher = DATA_URL_PREFIX.matcher(value);
        if (!matcher.find()) {
            return value;
        }
        String mimeType = StrUtil.blankToDefault(matcher.group(1), "image/png");
        String base64 = value.substring(matcher.end()).replaceAll("\\s", "");
        if (base64.isEmpty() || !BASE64_BODY.matcher(base64).matches()) {
            throw exception(FORM_DESIGN_FILE_TRANSFER_FAIL, label, "图片内容不是合法的 base64");
        }
        byte[] content;
        try {
            content = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            // 字符都合法但长度不合法（base64 的长度必须是 4 的倍数）
            throw exception(FORM_DESIGN_FILE_TRANSFER_FAIL, label, "图片内容不是合法的 base64");
        }
        if (content.length == 0) {
            throw exception(FORM_DESIGN_FILE_TRANSFER_FAIL, label, "图片内容为空");
        }
        try {
            // name 传 null：文件服务会按内容的 sha256 生成名字并按 MIME 补后缀，
            // 比我们自己拼一个更不容易撞名
            return fileApi.createFile(content, null, UPLOAD_DIRECTORY, mimeType);
        } catch (Exception e) {
            log.error("[transferDataUrl][字段({})的图片({} 字节)转存文件服务失败]", label, content.length, e);
            throw exception(FORM_DESIGN_FILE_TRANSFER_FAIL, label, "文件服务不可用");
        }
    }

    // ==================== 字段解析 ====================

    /**
     * 从表单字段规则里提取全部富媒体字段
     *
     * 要递归：字段可能在布局容器或子表里。递归结构与 FormValidationGuard 一致。
     */
    public List<RichField> parseRichFields(String formFieldsJson) {
        List<RichField> result = new ArrayList<>();
        for (String rule : FormFieldsCodec.read(formFieldsJson)) {
            Map<String, Object> node = JsonUtils.parseObjectQuietly(rule,
                    new TypeReference<Map<String, Object>>() {});
            if (node != null) {
                collect(node, result, null, null);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void collect(Map<String, Object> node, List<RichField> result,
                         String subField, String subTitle) {
        String type = node.get("type") instanceof String t ? t : null;
        String field = node.get("field") instanceof String f && StrUtil.isNotBlank(f) ? f : null;
        Kind kind = resolveKind(type);
        if (field != null && kind != null) {
            RichField rich = new RichField();
            rich.setField(field);
            rich.setType(type);
            rich.setTitle(node.get("title") instanceof String t ? t : null);
            rich.setSubField(subField);
            rich.setSubTitle(subTitle);
            rich.setKind(kind);
            result.add(rich);
        }
        // 子表控件：内部规则挂在 props.rule 下，它们的归属子表就是本控件
        if (field != null && node.get("props") instanceof Map<?, ?> propsMap
                && ((Map<String, Object>) propsMap).get("rule") instanceof List<?> innerRules) {
            String title = node.get("title") instanceof String t ? t : null;
            for (Object inner : innerRules) {
                if (inner instanceof Map<?, ?> innerMap) {
                    collect((Map<String, Object>) innerMap, result, field, title);
                }
            }
        }
        if (node.get("children") instanceof List<?> list) {
            for (Object child : list) {
                if (child instanceof Map<?, ?> childMap) {
                    // 布局容器不改变归属，原样把子表信息传下去
                    collect((Map<String, Object>) childMap, result, subField, subTitle);
                }
            }
        }
    }

    /**
     * 判断一个控件类型属于哪类富媒体
     *
     * 依据全部来自控件契约：{@code valueType = file} 的是文件型，
     * {@code structural.richTextWidgetTypes} 里的是富文本。不在这里列类型名 ——
     * 新增一个文件类控件时忘了改这里，表现会是那个控件的 base64 照旧入库。
     *
     * @return 不是富媒体字段时返回 null
     */
    private Kind resolveKind(String type) {
        if (type == null) {
            return null;
        }
        if (contractRegistry.isRichTextWidget(type)) {
            return Kind.RICH_TEXT;
        }
        return contractRegistry.isFileWidget(type) ? Kind.FILE : null;
    }

    /**
     * 富媒体字段的类别
     */
    public enum Kind {
        /**
         * 值是文件 URL 或文件对象
         */
        FILE,
        /**
         * 值是 HTML
         */
        RICH_TEXT
    }

    /**
     * 一个富媒体字段
     */
    @Data
    public static class RichField {

        /**
         * 字段名
         */
        private String field;
        /**
         * 控件类型
         *
         * 加它是为了让调用方能再按契约细分 —— 如 FileMetaEnricher 只处理
         * storage=json 的文件控件（单图/签名存的是裸 URL，不是数组）。
         * 判据放在调用方而不是这里：本类关心的只是「是不是富媒体」
         */
        private String type;
        /**
         * 字段标题，报错时用它而不是字段 ID
         */
        private String title;
        /**
         * 所属子表控件的字段名，主表字段为 null
         */
        private String subField;
        /**
         * 所属子表控件的标题
         */
        private String subTitle;
        /**
         * 类别
         */
        private Kind kind;

        /**
         * 报错与日志里用的字段名
         */
        public String label() {
            if (StrUtil.isNotBlank(title)) {
                return StrUtil.isNotBlank(subTitle) ? subTitle + " · " + title : title;
            }
            return field;
        }

    }

}
