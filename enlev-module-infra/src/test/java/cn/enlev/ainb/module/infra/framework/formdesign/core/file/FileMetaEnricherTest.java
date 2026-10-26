package cn.enlev.ainb.module.infra.framework.formdesign.core.file;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.dal.dataobject.file.FileDO;
import cn.enlev.ainb.module.infra.dal.mysql.file.FileMapper;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import cn.enlev.ainb.module.infra.framework.formdesign.core.contract.WidgetContract;
import cn.enlev.ainb.module.infra.framework.formdesign.core.contract.WidgetContractRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上传值元信息补齐的测试
 *
 * 设计方案 §5.9 要求上传字段的值统一成 {@code [{name,url,size,type}]}。
 * 这些用例守三件事：
 *
 * <ol>
 *   <li><b>补得上</b>：URL 数组换成对象数组，四个键都有值</li>
 *       非文件字段一律不动 —— 误改别的字段是不报错的那种坏</li>
 *   <li><b>补不上时不添乱</b>：查不到元信息就保持原样，而不是造一个只有 url 的对象；
 *       查库失败不能把整次提交挡下来</li>
 * </ol>
 *
 * @author 走进新时代
 */
public class FileMetaEnricherTest extends BaseMockitoUnitTest {

    @InjectMocks
    private FileMetaEnricher enricher;

    @Mock
    private WidgetContractRegistry contractRegistry;
    @Mock
    private FileMapper fileMapper;
    /**
     * 字段解析复用它。用真实实例而不是 mock —— 递归（布局容器 + 子表）的正确性
     * 正是本类依赖的东西，mock 掉就等于把它假设成对的
     */
    private final RichContentGuard richContentGuard = new RichContentGuard();

    @BeforeEach
    public void setUp() {
        ReflectionTestUtils.setField(richContentGuard, "contractRegistry", contractRegistry);
        ReflectionTestUtils.setField(enricher, "richContentGuard", richContentGuard);
        // 两个数组型文件控件 + 一个 scalar 的单图
        lenient().when(contractRegistry.isFileWidget("UploadFile")).thenReturn(true);
        lenient().when(contractRegistry.isFileWidget("UploadImg")).thenReturn(true);
        lenient().when(contractRegistry.isRichTextWidget(any())).thenReturn(false);
        lenient().when(contractRegistry.get("UploadFile")).thenReturn(contract("json"));
        lenient().when(contractRegistry.get("UploadImg")).thenReturn(contract("scalar"));
    }

    private WidgetContract contract(String storage) {
        WidgetContract contract = new WidgetContract();
        contract.setStorage(storage);
        return contract;
    }

    private FileDO file(String url, String name, long size, String type) {
        FileDO fileDO = new FileDO();
        fileDO.setUrl(url);
        fileDO.setName(name);
        fileDO.setSize(size);
        fileDO.setType(type);
        return fileDO;
    }

    private String fields(String... rules) {
        return FormFieldsCodec.write(List.of(rules));
    }

    private static final String ATTACH = "{\"type\":\"UploadFile\",\"field\":\"attachments\",\"title\":\"附件\"}";
    private static final String AVATAR = "{\"type\":\"UploadImg\",\"field\":\"avatar\",\"title\":\"头像\"}";

    @Test
    @DisplayName("URL 数组补成 [{name,url,size,type}]")
    public void testEnrich() {
        when(fileMapper.selectListByUrls(any()))
                .thenReturn(List.of(file("/a.pdf", "合同.pdf", 1024L, "application/pdf")));

        Map<String, Object> result = enricher.enrich(fields(ATTACH),
                Map.of("attachments", List.of("/a.pdf")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("attachments");
        assertEquals(1, items.size());
        // 原文件名是这一整件事的核心 —— 库里原本只有 /a.pdf 这样的哈希名
        assertEquals("合同.pdf", items.get(0).get("name"));
        assertEquals("/a.pdf", items.get(0).get("url"));
        assertEquals(1024L, items.get(0).get("size"));
        assertEquals("application/pdf", items.get(0).get("type"));
    }

    @Test
    @DisplayName("⚠️ 单图不动：它的落库形态是 scalar 的裸 URL")
    public void testScalarUntouched() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("avatar", "/avatar.png");

        Map<String, Object> result = enricher.enrich(fields(AVATAR), data);

        assertEquals("/avatar.png", result.get("avatar"));
        // 一次库都不该查：这张表单里没有数组型文件字段
        verify(fileMapper, never()).selectListByUrls(any());
    }

    @Test
    @DisplayName("查不到元信息时保持原样，不造一个只有 url 的对象")
    public void testUnknownUrlKept() {
        // 半对象半字符串的数组，读的人无从判断 —— 比不补更糟
        when(fileMapper.selectListByUrls(any()))
                .thenReturn(List.of(file("/a.pdf", "合同.pdf", 1L, "application/pdf")));

        Map<String, Object> result = enricher.enrich(fields(ATTACH),
                Map.of("attachments", List.of("/a.pdf", "https://外部.com/x.pdf")));

        @SuppressWarnings("unchecked")
        List<Object> items = (List<Object>) result.get("attachments");
        assertTrue(items.get(0) instanceof Map);
        assertEquals("https://外部.com/x.pdf", items.get(1));
    }

    @Test
    @DisplayName("一个都查不到时整个字段不动")
    public void testNoMetaAtAll() {
        when(fileMapper.selectListByUrls(any())).thenReturn(List.of());

        Map<String, Object> result = enricher.enrich(fields(ATTACH),
                Map.of("attachments", List.of("/x.pdf")));

        assertEquals(List.of("/x.pdf"), result.get("attachments"));
    }

    @Test
    @DisplayName("⚠️ 查库失败不挡提交：补元信息是增强，不是约束")
    public void testQueryFailureDegrades() {
        when(fileMapper.selectListByUrls(any())).thenThrow(new RuntimeException("连不上"));

        Map<String, Object> result = enricher.enrich(fields(ATTACH),
                Map.of("attachments", List.of("/a.pdf")));

        // 为了几个展示用的字段把保存挡下来不合算
        assertEquals(List.of("/a.pdf"), result.get("attachments"));
    }

    @Test
    @DisplayName("一次提交里多个文件字段合并成一次查询")
    public void testBatchedQuery() {
        when(fileMapper.selectListByUrls(any())).thenReturn(List.of(
                file("/a.pdf", "a", 1L, "t"), file("/b.pdf", "b", 2L, "t")));
        String two = fields(ATTACH,
                "{\"type\":\"UploadFile\",\"field\":\"proofs\",\"title\":\"凭证\"}");
        lenient().when(contractRegistry.get("UploadFile")).thenReturn(contract("json"));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("attachments", List.of("/a.pdf"));
        data.put("proofs", List.of("/b.pdf"));
        enricher.enrich(two, data);

        // 逐字段查的话，带三个附件字段的提交就是三次查询，而它们本可以合成一次
        verify(fileMapper).selectListByUrls(any());
    }

    @Test
    @DisplayName("子表明细列也补，且不修改入参")
    public void testSubTableRows() {
        when(fileMapper.selectListByUrls(any()))
                .thenReturn(List.of(file("/p.jpg", "凭证.jpg", 3L, "image/jpeg")));
        lenient().when(contractRegistry.isSubTableWidget("group")).thenReturn(true);
        String sub = fields("{\"type\":\"group\",\"field\":\"items\",\"title\":\"明细\","
                + "\"props\":{\"rule\":[" + ATTACH.replace("attachments", "proof") + "]}}");

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("proof", new ArrayList<>(List.of("/p.jpg")));
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", rows);

        Map<String, Object> result = enricher.enrich(sub, data);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outRows = (List<Map<String, Object>>) result.get("items");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> proof = (List<Map<String, Object>>) outRows.get(0).get("proof");
        assertEquals("凭证.jpg", proof.get(0).get("name"));
        // ⚠️ 入参那一行必须还是原样：单据编号重试依赖「每次尝试用一份干净的数据副本」
        assertEquals(List.of("/p.jpg"), row.get("proof"));
    }

    @Test
    @DisplayName("没有文件字段时原样返回，一次库都不查")
    public void testNoFileField() {
        Map<String, Object> data = Map.of("remark", "x");

        Map<String, Object> result = enricher.enrich(
                fields("{\"type\":\"input\",\"field\":\"remark\",\"title\":\"备注\"}"), data);

        assertEquals("x", result.get("remark"));
        verify(fileMapper, never()).selectListByUrls(any());
    }

}
