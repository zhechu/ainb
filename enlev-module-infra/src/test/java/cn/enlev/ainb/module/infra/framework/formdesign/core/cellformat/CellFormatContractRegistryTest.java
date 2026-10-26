package cn.enlev.ainb.module.infra.framework.formdesign.core.cellformat;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CellFormatContractRegistry} 的单元测试
 *
 * <h3>守的是什么</h3>
 *
 * 这份契约是「列表怎么显示、导出怎么导」两端共用的唯一声明。
 * 它一旦被改坏，后果不是报错而是<b>两端行为悄悄不一致</b> ——
 * 列表上是「生产制造」，导出成了 {@code MAKE}，而没有任何地方会提示。
 *
 * 所以这里既测拼装规则（numFmt 两端必须拼出同一个串），
 * 也测自检（改契约时改漏要当场拦住）。
 *
 * @author 走进新时代
 */
public class CellFormatContractRegistryTest extends BaseMockitoUnitTest {

    private CellFormatContractRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new CellFormatContractRegistry();
        registry.init();
    }

    // ==================== 契约本身 ====================

    @Test
    @DisplayName("契约能加载，11 种格式化都在")
    void testLoad() {
        assertEquals(11, registry.listAll().size());
        Set<String> types = registry.listAll().stream()
                .map(CellFormatContract::getType).collect(Collectors.toSet());
        assertEquals(Set.of("NONE", "DICT", "TAG", "DATE", "DATETIME", "NUMBER",
                "PERCENT", "BOOLEAN", "LINK", "IMAGE", "FILE"), types);
    }

    @Test
    @DisplayName("认不出的格式化回落到 NONE —— 历史配置不该让整张表导不出来")
    void testUnknownFallsBackToNone() {
        assertEquals("NONE", registry.get("SOMETHING_NEW").getType());
        assertEquals("NONE", registry.get(null).getType());
        assertEquals("NONE", registry.get("").getType());
    }

    @Test
    @DisplayName("每种格式化都声明了导出规则")
    void testEveryFormatterHasExportRule() {
        for (CellFormatContract contract : registry.listAll()) {
            assertNotNull(contract.getExport(), contract.getType() + " 缺 export");
            assertNotNull(contract.getLabel(), contract.getType() + " 缺 label");
        }
    }

    @Test
    @DisplayName("⚠️ 三处故意不跟随列表的格式化，都显式标了 followsList=false")
    void testDeliberateDeviationsAreDeclared() {
        // 标成 false 是为了让「不跟随」是一个被记录过的决定，
        // 而不是某处实现忘了做。漏标的话，下一个人会把它当成 bug 去「修复」
        for (String type : List.of("LINK", "IMAGE", "FILE")) {
            assertEquals(Boolean.FALSE, registry.get(type).getExport().getFollowsList(),
                    type + " 应当显式声明不跟随列表");
        }
    }

    @Test
    @DisplayName("⚠️ 标签色不导出 —— 跨模块拿不到 colorType，且颜色不承载数据")
    void testTagColorNotExported() {
        assertEquals(Boolean.FALSE, registry.get("TAG").getExport().getFillByDictColor());
    }

    // ==================== numFmt 拼装 ====================

    @Test
    @DisplayName("数字格式串按 precision 与 thousands 拼")
    void testBuildNumFmt() {
        assertEquals("#,##0.00", registry.buildNumFmt(2, true, null));
        assertEquals("0.00", registry.buildNumFmt(2, false, null));
        assertEquals("#,##0", registry.buildNumFmt(0, true, null));
        assertEquals("0", registry.buildNumFmt(0, false, null));
        assertEquals("0.0000", registry.buildNumFmt(4, false, null));
    }

    @Test
    @DisplayName("没配 precision 时用契约里的默认值")
    void testBuildNumFmtDefaultPrecision() {
        assertEquals("#,##0.00", registry.buildNumFmt(null, true, null));
        assertEquals("#,##0.00", registry.buildNumFmt(-1, true, null));
    }

    @Test
    @DisplayName("百分比在格式串尾部接 %")
    void testBuildNumFmtPercent() {
        assertEquals("0.00%", registry.buildNumFmt(2, false, "%"));
        assertEquals("#,##0%", registry.buildNumFmt(0, true, "%"));
    }

    // ==================== 判真 ====================

    @Test
    @DisplayName("布尔判真认 true / 1 / \"1\"，与前端一致")
    void testIsTruthy() {
        assertTrue(registry.isTruthy(true));
        assertTrue(registry.isTruthy(1));
        assertTrue(registry.isTruthy("1"));
    }

    @Test
    @DisplayName("⚠️ 数据库读出来的 1L / BigDecimal(1) 也要判真")
    void testIsTruthyFromDatabaseTypes() {
        // tinyint 列经 JDBC 读出来可能是 Integer / Long / BigDecimal，
        // 逐个比对象会全部落空 —— 表现是布尔列导出全是「否」
        assertTrue(registry.isTruthy(1L));
        assertTrue(registry.isTruthy(new java.math.BigDecimal("1")));
        assertTrue(registry.isTruthy(new java.math.BigDecimal("1.00")));
    }

    @Test
    @DisplayName("其余一律判假")
    void testIsFalsy() {
        assertFalse(registry.isTruthy(null));
        assertFalse(registry.isTruthy(0));
        assertFalse(registry.isTruthy("0"));
        assertFalse(registry.isTruthy(false));
        assertFalse(registry.isTruthy("是"));
    }

    // ==================== 视图产物行 ====================

    @Test
    @DisplayName("合计行标记有值，且导入侧要跳过")
    void testRowKinds() {
        assertEquals("合计", registry.getRowKinds().getSummaryLabel());
        assertTrue(registry.getRowKinds().isSkipOnImport());
    }

    @Test
    @DisplayName("空值占位有值")
    void testEmptyCell() {
        assertEquals("-", registry.getEmptyCell());
    }

}
