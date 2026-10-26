package cn.enlev.ainb.module.infra.framework.formdesign.core.file;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.api.file.FileApi;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import cn.enlev.ainb.module.infra.framework.formdesign.core.contract.WidgetContractRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 富媒体净化的测试
 *
 * 守的是一条硬约束：**库里不得出现 base64**。
 *
 * 这条约束在前端已经做了（Signature 控件上传后只提交 URL、富文本关掉了
 * uploadImgShowBase64），但那一层能被绕过 —— 直接构造一个请求就行。
 * 所以这些用例全部从「服务端收到了一串 base64」出发，验证它离开本类时已经是一个 URL。
 *
 * @author 走进新时代
 */
public class RichContentGuardTest extends BaseMockitoUnitTest {

    /** 一张 1x1 的透明 PNG，用它当所有测试里的「图片内容」 */
    private static final String PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNkAAAABgAD"
                    + "NjfnRQAAAABJRU5ErkJggg==";
    private static final String PNG_DATA_URL = "data:image/png;base64," + PNG_BASE64;

    private static final String UPLOADED_URL = "http://127.0.0.1:48080/admin-api/infra/file/4/get/a.png";

    @InjectMocks
    private RichContentGuard guard;

    /**
     * 用真实契约注册表：要守的正是「按契约里的 valueType / richTextWidgetTypes 认字段」
     * 这条链路，mock 掉它等于把被测的判断一起 mock 掉
     */
    @Spy
    private WidgetContractRegistry contractRegistry = new WidgetContractRegistry();

    @Mock
    private FileApi fileApi;

    @BeforeEach
    void setUp() {
        // 契约是在 @PostConstruct 里读的，单测不经过容器要手动调
        contractRegistry.init();
    }

    // ==================== 造数据的辅助方法 ====================

    private String widget(String type, String field, String title) {
        return String.format("{\"type\":\"%s\",\"field\":\"%s\",\"title\":\"%s\"}", type, field, title);
    }

    private String fields(String... rules) {
        return FormFieldsCodec.write(List.of(rules));
    }

    private Map<String, Object> data(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    private void mockUpload() {
        when(fileApi.createFile(any(), any(), any(), any())).thenReturn(UPLOADED_URL);
    }

    // ==================== 签名：base64 转存 ====================

    @Test
    @DisplayName("签名字段提交 base64：转存文件服务，只留 URL")
    void testSignatureBase64Transferred() {
        mockUpload();
        Map<String, Object> result = guard.purify(
                fields(widget("Signature", "sign", "客户签名")),
                data("sign", PNG_DATA_URL));

        assertEquals(UPLOADED_URL, result.get("sign"));
        // MIME 要一路传到文件服务，否则存下来的文件没有后缀、浏览器不当图片渲染
        verify(fileApi).createFile(any(), isNull(), eq("form-design"), eq("image/png"));
    }

    @Test
    @DisplayName("签名字段已经是 URL：不重复上传")
    void testSignatureUrlUntouched() {
        Map<String, Object> result = guard.purify(
                fields(widget("Signature", "sign", "客户签名")),
                data("sign", UPLOADED_URL));

        assertEquals(UPLOADED_URL, result.get("sign"));
        verify(fileApi, never()).createFile(any(), any(), any(), any());
    }

    @Test
    @DisplayName("被废弃的内核 signaturePad 也要转存")
    void testKernelSignaturePadTransferred() {
        // 它在契约里已标 supported=false，发布校验会拒绝。但历史表单里可能还留着，
        // 而运行时提交走的是本类 —— 少了这一条，那些表单照旧把 base64 写进库
        mockUpload();
        Map<String, Object> result = guard.purify(
                fields(widget("signaturePad", "sign", "签名")),
                data("sign", PNG_DATA_URL));

        assertEquals(UPLOADED_URL, result.get("sign"));
    }

    // ==================== 上传三件套：三种值形态 ====================

    @Test
    @DisplayName("多图上传的数组里混着 base64：逐个转存，已是 URL 的原样保留")
    void testUploadImgsArray() {
        mockUpload();
        List<Object> value = new ArrayList<>(List.of("http://cdn/a.png", PNG_DATA_URL));
        Map<String, Object> result = guard.purify(
                fields(widget("UploadImgs", "imgs", "现场照片")),
                data("imgs", value));

        assertEquals(List.of("http://cdn/a.png", UPLOADED_URL), result.get("imgs"));
        verify(fileApi).createFile(any(), any(), any(), any());
    }

    @Test
    @DisplayName("文件对象形态 {name,url,size,type}：只换 url，其余元信息保留")
    void testUploadFileObject() {
        mockUpload();
        Map<String, Object> file = data("name", "签名.png", "url", PNG_DATA_URL,
                "size", 68, "type", "image/png");
        Map<String, Object> result = guard.purify(
                fields(widget("UploadFile", "files", "附件")),
                data("files", List.of(file)));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) result.get("files");
        assertEquals(UPLOADED_URL, files.get(0).get("url"));
        assertEquals("签名.png", files.get(0).get("name"));
        assertEquals(68, files.get(0).get("size"));
        // 入参不能被就地修改
        assertEquals(PNG_DATA_URL, file.get("url"));
    }

    // ==================== 富文本 ====================

    @Test
    @DisplayName("富文本里的内联 base64 图片：转存后 src 换成 URL")
    void testRichTextInlineImage() {
        mockUpload();
        Map<String, Object> result = guard.purify(
                fields(widget("Editor", "desc", "详情")),
                data("desc", "<p>看图</p><img src=\"" + PNG_DATA_URL + "\">"));

        String html = (String) result.get("desc");
        assertFalse(html.contains("base64"), "净化后的富文本里不允许再有 base64：" + html);
        assertTrue(html.contains(UPLOADED_URL));
    }

    @Test
    @DisplayName("转存必须早于清洗：否则 data: 图片会被白名单静默丢掉")
    void testTransferBeforeClean() {
        // 这条用例是给顺序上锁的。把 purifyHtml 里的两步调换，它会失败：
        // 清洗先跑的话 src="data:..." 不合白名单，整个 src 被删掉，
        // 表现是「粘贴的图片保存后不见了」且没有任何报错
        mockUpload();
        Map<String, Object> result = guard.purify(
                fields(widget("Editor", "desc", "详情")),
                data("desc", "<img src=\"" + PNG_DATA_URL + "\">"));

        assertTrue(((String) result.get("desc")).contains(UPLOADED_URL));
    }

    @Test
    @DisplayName("富文本清洗掉 script 与事件属性，保留内联样式与表格")
    void testRichTextSanitized() {
        Map<String, Object> result = guard.purify(
                fields(widget("Editor", "desc", "详情")),
                data("desc", "<p style=\"color:red\">红</p>"
                        + "<table><tbody><tr><td>1</td></tr></tbody></table>"
                        + "<script>alert(1)</script>"
                        + "<img src=\"http://cdn/a.png\" onerror=\"alert(1)\">"
                        + "<a href=\"javascript:alert(1)\">点</a>"));

        String html = (String) result.get("desc");
        assertFalse(html.contains("script"), html);
        assertFalse(html.contains("onerror"), html);
        assertFalse(html.contains("javascript:"), html);
        // 编辑器的字号颜色对齐全靠内联样式，清洗掉它等于「保存后格式全丢」
        assertTrue(html.contains("color:red"), html);
        assertTrue(html.contains("<table>"), html);
        assertTrue(html.contains("http://cdn/a.png"), html);
    }

    @Test
    @DisplayName("干净的富文本原样返回：不能因为 Jsoup 的序列化差异改写内容")
    void testCleanHtmlUntouched() {
        // 这里刻意用一个没加引号的属性：Jsoup 输出时会补上引号，也就是说
        // 「解析再输出」本身就会让字节变化。如果拿它的输出无条件覆盖，
        // 每次提交都会产生一份「内容相同、字节不同」的值 ——
        // 变更留痕、乐观锁这类按值比对的功能会全都误判成有改动
        String html = "<p style=color:red>第一段</p>";
        Map<String, Object> result = guard.purify(
                fields(widget("Editor", "desc", "详情")),
                data("desc", html));

        assertSame(html, result.get("desc"));
    }

    // ==================== 子表 ====================

    @Test
    @DisplayName("子表明细列里的 base64：逐行转存，且不修改入参")
    void testSubTableRow() {
        mockUpload();
        String subTable = "{\"type\":\"group\",\"field\":\"items\",\"title\":\"明细\",\"props\":{\"rule\":["
                + widget("UploadImg", "photo", "照片") + "]}}";
        Map<String, Object> row0 = data("photo", PNG_DATA_URL);
        Map<String, Object> row1 = data("photo", "http://cdn/b.png");
        List<Map<String, Object>> rows = List.of(row0, row1);
        Map<String, Object> incoming = data("items", rows);

        Map<String, Object> result = guard.purify(fields(subTable), incoming);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> out = (List<Map<String, Object>>) result.get("items");
        assertEquals(UPLOADED_URL, out.get(0).get("photo"));
        assertEquals("http://cdn/b.png", out.get(1).get("photo"));
        // 入参的那一份必须还是原值：单据编号重试依赖「每次尝试用一份干净副本」
        assertEquals(PNG_DATA_URL, row0.get("photo"));
    }

    // ==================== 转存失败 ====================

    @Test
    @DisplayName("文件服务不可用：报错中止，不能把 base64 留在数据里")
    void testUploadFailRejects() {
        when(fileApi.createFile(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("connection refused"));

        ServiceException e = assertThrows(ServiceException.class, () -> guard.purify(
                fields(widget("Signature", "sign", "客户签名")),
                data("sign", PNG_DATA_URL)));
        // 报错要能定位到是哪个字段，用标题而不是字段 ID
        assertTrue(e.getMessage().contains("客户签名"), e.getMessage());
    }

    @Test
    @DisplayName("base64 本身是坏的：同样中止，且不去调文件服务")
    void testBrokenBase64Rejects() {
        assertThrows(ServiceException.class, () -> guard.purify(
                fields(widget("Signature", "sign", "客户签名")),
                data("sign", "data:image/png;base64,这不是base64")));
        verify(fileApi, never()).createFile(any(), any(), any(), any());
    }

    // ==================== 不相关的字段不受影响 ====================

    @Test
    @DisplayName("普通输入框的值即便长得像 data URL 也不动它")
    void testNonRichFieldUntouched() {
        // 只扫契约里 valueType=file 与富文本这两类字段。普通字符串字段里的超长内容
        // 由「超长值拦截」处理，不是这里的职责 —— 扫全部字符串字段的代价是
        // 每次提交都要对每个值做一次正则匹配，而误伤的后果是用户填的文本被换成了一个 URL
        Map<String, Object> result = guard.purify(
                fields(widget("input", "remark", "备注")),
                data("remark", PNG_DATA_URL));

        assertEquals(PNG_DATA_URL, result.get("remark"));
        verify(fileApi, never()).createFile(any(), any(), any(), any());
    }

    @Test
    @DisplayName("表单里没有富媒体字段时原样返回")
    void testNoRichField() {
        Map<String, Object> result = guard.purify(
                fields(widget("input", "name", "姓名")),
                data("name", "张三"));

        assertEquals(1, result.size());
        assertEquals("张三", result.get("name"));
    }

}
