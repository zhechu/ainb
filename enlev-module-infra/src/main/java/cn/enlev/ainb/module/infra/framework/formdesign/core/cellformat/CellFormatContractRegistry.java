package cn.enlev.ainb.module.infra.framework.formdesign.core.cellformat;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 列格式化契约的注册表
 *
 * <h3>为什么要有这份契约</h3>
 *
 * 列表的格式化原本只在前端（{@code components/FormWidgets/listCell.ts}），
 * 而导出在后端。「照列表导出」意味着后端要再实现一遍同样的规则 ——
 * 两份实现必然漂移，而漂移的表现是：
 * 前端给某种格式化改了行为，导出那边不知道，
 * 于是<b>列表显示正常、导出的却是另一套</b>。又一个「配了但不生效」。
 *
 * 所以规则抽成一份声明式契约，两端各自实现、共用一份声明，靠一致性测试守住。
 * 与 {@code form-widget-contracts.json} 是同一个模式，同步约定见同目录 SYNC.md。
 *
 * @author 走进新时代
 */
@Slf4j
@Component
public class CellFormatContractRegistry {

    private static final String CONTRACT_RESOURCE = "form-design/cell-format-contracts.json";

    /**
     * 格式化类型 → 契约
     */
    private Map<String, CellFormatContract> contractByType = Collections.emptyMap();

    private CellFormatContract.NumberFormat numberFormat = new CellFormatContract.NumberFormat();

    private CellFormatContract.BooleanText booleanText = new CellFormatContract.BooleanText();

    private CellFormatContract.RowKinds rowKinds = new CellFormatContract.RowKinds();

    private String emptyCell = "-";

    private Integer contractVersion;

    @PostConstruct
    public void init() {
        String json = cn.hutool.core.io.resource.ResourceUtil.readUtf8Str(CONTRACT_RESOURCE);
        if (StrUtil.isBlank(json)) {
            throw new IllegalStateException("列格式化契约文件为空：" + CONTRACT_RESOURCE);
        }
        ContractFile file = JsonUtils.parseObject(json, ContractFile.class);
        if (file == null || file.getFormatters() == null || file.getFormatters().isEmpty()) {
            throw new IllegalStateException("列格式化契约解析失败或不含任何条目：" + CONTRACT_RESOURCE);
        }

        Map<String, CellFormatContract> map = new LinkedHashMap<>();
        Set<String> duplicated = new HashSet<>();
        for (CellFormatContract contract : file.getFormatters()) {
            if (StrUtil.isBlank(contract.getType())) {
                throw new IllegalStateException("列格式化契约存在没有 type 的条目：" + CONTRACT_RESOURCE);
            }
            if (map.put(contract.getType(), contract) != null) {
                duplicated.add(contract.getType());
            }
        }
        // 重复的 type 会让后放入的悄悄覆盖前一条，是最难查的一类配置错误，直接拦住
        if (!duplicated.isEmpty()) {
            throw new IllegalStateException("列格式化契约存在重复的 type：" + duplicated);
        }

        this.contractByType = Collections.unmodifiableMap(map);
        this.contractVersion = file.getContractVersion();
        if (file.getNumberFormat() != null) {
            this.numberFormat = file.getNumberFormat();
        }
        if (file.getBooleanText() != null) {
            this.booleanText = file.getBooleanText();
        }
        if (file.getRowKinds() != null) {
            this.rowKinds = file.getRowKinds();
        }
        if (StrUtil.isNotBlank(file.getEmptyCell())) {
            this.emptyCell = file.getEmptyCell();
        }

        selfCheck();
        log.info("[init][列格式化契约加载完成，版本 {}，格式化 {} 种]",
                contractVersion, contractByType.size());
    }

    /**
     * 自检：防的是我们自己改契约时改漏
     *
     * 例如把某个格式化的 export.mode 写成一个不存在的值 ——
     * 那会让导出对这一列静默走兜底分支，表现为「这一列导出的和别的列不是一套」。
     */
    private void selfCheck() {
        if (!contractByType.containsKey("NONE")) {
            throw new IllegalStateException("列格式化契约缺少兜底的 NONE 条目");
        }
        for (CellFormatContract contract : contractByType.values()) {
            CellFormatContract.ExportRule rule = contract.getExport();
            if (rule == null) {
                throw new IllegalStateException("列格式化(" + contract.getType() + ")缺少 export 规则");
            }
            if (!List.of("TEXT", "VALUE_WITH_FMT").contains(rule.getMode())) {
                throw new IllegalStateException(
                        "列格式化(" + contract.getType() + ")的 export.mode 认不出来：" + rule.getMode());
            }
            if ("TEXT".equals(rule.getMode())
                    && !List.of("RAW", "DICT_LABEL", "BOOLEAN_TEXT", "FILE_URL")
                    .contains(String.valueOf(rule.getSource()))) {
                throw new IllegalStateException(
                        "列格式化(" + contract.getType() + ")的 export.source 认不出来：" + rule.getSource());
            }
            if ("VALUE_WITH_FMT".equals(rule.getMode())
                    && StrUtil.isBlank(rule.getNumFmt()) && StrUtil.isBlank(rule.getNumFmtFrom())) {
                throw new IllegalStateException(
                        "列格式化(" + contract.getType() + ")声明了 VALUE_WITH_FMT 却没给 numFmt");
            }
        }
    }

    public CellFormatContract get(String type) {
        if (StrUtil.isBlank(type)) {
            return contractByType.get("NONE");
        }
        // 认不出的格式化按「无格式化」处理，与前端的 default 分支一致 ——
        // 这里抛异常的话，一个历史配置会让整张表导不出来
        return contractByType.getOrDefault(type, contractByType.get("NONE"));
    }

    public List<CellFormatContract> listAll() {
        return List.copyOf(contractByType.values());
    }

    public CellFormatContract.NumberFormat getNumberFormat() {
        return numberFormat;
    }

    public CellFormatContract.BooleanText getBooleanText() {
        return booleanText;
    }

    public CellFormatContract.RowKinds getRowKinds() {
        return rowKinds;
    }

    public String getEmptyCell() {
        return emptyCell;
    }

    public Integer getContractVersion() {
        return contractVersion;
    }

    /**
     * 按 precision / thousands 拼出 Excel 的数字格式串
     *
     * 两端按契约里同一套规则拼，不各写各的 ——
     * 一端拼成 {@code #,##0.00}、另一端拼成 {@code #,###.00}，
     * 差别只在千位分组上，而那种差异没人会在代码评审里看出来。
     *
     * @param precision  小数位。null 时用契约里的默认值
     * @param thousands  要不要千分位
     * @param suffix     后缀。百分比是 "%"
     */
    public String buildNumFmt(Integer precision, boolean thousands, String suffix) {
        int scale = numberFormat.precisionOr(precision);
        StringBuilder sb = new StringBuilder(
                thousands ? numberFormat.getIntegerThousands() : numberFormat.getInteger());
        if (scale > 0) {
            sb.append(numberFormat.getDecimalSeparator());
            sb.append(numberFormat.getDecimalDigit().repeat(scale));
        }
        if (StrUtil.isNotBlank(suffix)) {
            sb.append(suffix);
        }
        return sb.toString();
    }

    /**
     * 值算不算「真」
     *
     * 判据来自契约的 booleanText.trueValues，与前端 formatCellText 的
     * {@code value === true || value === 1 || value === '1'} 一致。
     */
    public boolean isTruthy(Object value) {
        if (value == null) {
            return false;
        }
        for (Object candidate : booleanText.getTrueValues()) {
            if (candidate == null) {
                continue;
            }
            if (candidate.equals(value)) {
                return true;
            }
            // JSON 里的 1 解析成 Integer，而库里读出来可能是 Long / BigDecimal，
            // 逐个比对象会全部落空 —— 表现是布尔列导出全是「否」
            if (String.valueOf(candidate).equals(normalizeNumberText(value))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 数字的文本形态归一
     *
     * 1、1L、1.0、new BigDecimal("1.00") 都要归一成 "1"，
     * 否则 tinyint 列读出来的 1 与契约里的 1 比不上。
     */
    private String normalizeNumberText(Object value) {
        if (value instanceof java.math.BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof Number number) {
            return new java.math.BigDecimal(number.toString())
                    .stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }

    /**
     * 契约文件的根结构
     */
    @Data
    public static class ContractFile {
        private Integer contractVersion;
        private String emptyCell;
        private CellFormatContract.NumberFormat numberFormat;
        private CellFormatContract.BooleanText booleanText;
        private CellFormatContract.RowKinds rowKinds;
        private List<CellFormatContract> formatters;
    }

}
