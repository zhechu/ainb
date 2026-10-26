package cn.enlev.ainb.module.infra.framework.datamodel.core;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.enlev.ainb.module.infra.framework.datamodel.core.model.TableColumnModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 表结构指纹：把「一张表此刻长什么样」压成一个 64 位十六进制串
 *
 * <h3>它解决的问题</h3>
 * 平台的改表流程是「读结构 → 人在界面上改 → 提交全量列 → 生成 ALTER」。
 * 读与提交之间可能隔着几分钟（AI 走工具时可能隔着好几轮对话），
 * 这期间表完全可能被<b>绕过平台</b>改掉 —— DBA 直接跑了 SQL、别的工具动了它、
 * 迁移脚本改过一轮。此时：
 * <ul>
 *   <li>ALTER 照样能生成、照样能执行成功（因为 diff 是拿<b>实时</b>结构算的）</li>
 *   <li>但登记在这张模型上的 {@code columnMeta} / {@code listConfig} / {@code formConfig}，
 *       以及绑到这张表上的表单字段映射，描述的已经是<b>另一张表</b>了</li>
 * </ul>
 * 结果就是本项目最贵的那类缺陷：<b>接口返回成功，配置却在悄悄失效</b>。
 * 指纹让「我看到的那张表」与「现在这张表」能被判等，从而在执行 DDL 之前拦住。
 *
 * <h3>🔴 只参与「结构」的字段才进指纹</h3>
 * {@link TableColumnModel} 里有三类字段，只有第一类进：
 * <ol>
 *   <li><b>存储结构</b>：列名、类型、长度、精度、可空、主键、自增、默认值、
 *       ON UPDATE CURRENT_TIMESTAMP —— 进</li>
 *   <li><b>业务语义</b>：dictType / refTable / refColumn —— <b>不进</b>。
 *       它们存在 {@code infra_data_model.column_meta} 里、不在物理表上，
 *       改了它们表结构一个字节都没变；把它们算进来的话，
 *       「给某列挂个字典」会让指纹变化，进而被误判成「表被人动过」</li>
 *   <li><b>注释</b>：columnComment —— <b>不进</b>。它改不动数据的存取，
 *       而 MySQL 对注释有归一化行为（尾随空格等），算进来只会带来假阳性。
 *       注释的真实变化由 {@link TableDiffer} 走 MODIFY COLUMN 正常处理</li>
 * </ol>
 * 假阳性的代价很高：这道闸是<b>硬拦</b>，判错就把用户挡在改表门外，
 * 而他看不出自己哪里做错了。
 *
 * <p>🔴 由此得出一条<b>必须守住的不变量</b>：{@code ColumnMetaStore.merge} 往列模型上
 * 贴 dictType / refTable / refColumn，<b>不得改变指纹</b>。两个计算点的输入并不一致 ——
 * {@code getDataModelStructure} 拿的是 merge 过的列，{@code updateDataModel} 拿的是
 * {@link TableMetaReader#getColumns} 的原始列。业务语义一旦进了指纹，
 * 这两处就会对同一张表算出两个值，那道闸从此<b>永远</b>报「结构被人改过」。
 * 所以加字段进 {@link #canonicalize} 之前，先确认它属于上面第 1 类。
 *
 * <h3>🔴 编码是「长度前缀」的，不是拿分隔符拼的</h3>
 * 只有 {@code defaultValue} 是自由文本，其余字段都受字符集约束。
 * 拿 {@code |} 或换行拼的话，一个默认值里带上分隔符就能伪造出字段的边界，
 * 于是<b>两种不同的表结构可以算出同一个指纹</b> —— 而指纹的全部用途就是判等。
 * 长度前缀（{@code 长度:内容}）在构造上是单射的，不存在这种碰撞。
 *
 * <h3>🔴 按列名排序，不按 ORDINAL_POSITION</h3>
 * 两边都取自 {@link TableMetaReader#getColumns}，同一张表两次读出来顺序一致，
 * 所以按原始顺序也不会算错。刻意还是排一次序，是为了让指纹对
 * 「列的物理摆放位置」不敏感：{@code ADD COLUMN ... AFTER x} 与追加到末尾
 * 在语义上是同一张表，而位置一变，按原始顺序算出来的指纹就变了 ——
 * 那种「变了」不携带任何有用信息，只会制造假阳性。
 *
 * @author 走进新时代
 */
public final class SchemaFingerprint {

    /**
     * 指纹长度：sha256 的十六进制串固定 64 位
     *
     * <p>与 DDL 里 {@code schema_fingerprint varchar(64)} 的列宽是一对。
     * 改摘要算法要同时改列宽，否则截断后的指纹会与完整值永不相等 ——
     * 表现是<b>每一次改表都被拦</b>，而报错说的是「结构被人改过」。
     */
    public static final int LENGTH = 64;

    private SchemaFingerprint() {
    }

    /**
     * 算一批列的结构指纹
     *
     * @param columns 列模型；null 或空按「空表结构」处理，仍返回一个确定的值
     * @return 64 位十六进制串，永不为 null
     */
    public static String of(List<TableColumnModel> columns) {
        return DigestUtil.sha256Hex(canonicalize(columns));
    }

    /**
     * 这一列有没有记录过基线
     *
     * <p>🔴 {@code null} 与 {@code ""} 都算「没记录过」，而且这个判据<b>必须</b>与
     * 「记录过、但对不上」分开处理：存量模型（本列上线之前建的）从来没记过指纹，
     * 把它们判成「结构被人改过」的话，<b>加这一列之前建的每一张表都会从此改不动</b>。
     * 正确的行为是放行、并在 DDL 成功后顺手把基线补上。
     */
    public static boolean isRecorded(String fingerprint) {
        return StrUtil.isNotBlank(fingerprint);
    }

    /**
     * 取指纹的前 12 位，只用于报错信息
     *
     * <p>64 位十六进制串塞进提示语里是一堵墙，用户不会去比，也占满了 toast。
     * 12 位足够让人（和日志）确认「这两个值确实不一样」。
     *
     * <p>⚠️ <b>只用于展示，不要拿它做判等</b> —— 截断之后就不再是单射的了。
     */
    public static String shortOf(String fingerprint) {
        if (StrUtil.isBlank(fingerprint)) {
            return "（无基线）";
        }
        return fingerprint.length() <= 12 ? fingerprint : fingerprint.substring(0, 12);
    }

    /**
     * 规范化编码：把一批列压成一个确定的串，{@link #of} 对它取 sha256
     *
     * <p>单独暴露出来是为了能被单测直接断言 —— 指纹算错了（漏了一个字段、
     * 或者两种结构撞出同一个值）在跑起来之前是看不出来的，
     * 而编码是可读的，一眼能核对。
     */
    public static String canonicalize(List<TableColumnModel> columns) {
        List<TableColumnModel> sorted = new ArrayList<>(columns == null ? List.of() : columns);
        // 列名为空的排在最前，只为让排序稳定；这种列本身是脏数据，由上游校验拦
        sorted.sort(Comparator.comparing(column -> StrUtil.nullToEmpty(column.getColumnName())
                .toLowerCase(Locale.ROOT)));

        StringBuilder sb = new StringBuilder();
        for (TableColumnModel column : sorted) {
            token(sb, lower(column.getColumnName()));
            token(sb, upper(column.getColumnType()));
            token(sb, column.getLength());
            token(sb, column.getScale());
            // 用 isXxx() 而不是 getXxx()：那几个方法把 null 归一成 false，
            // 否则「没设」与「设成 false」会算出两个不同的指纹，而它们在 DDL 上是同一个意思
            token(sb, column.isNullable());
            token(sb, column.isPrimaryKey());
            token(sb, column.isAutoIncrement());
            token(sb, column.isOnUpdateCurrentTimestamp());
            token(sb, column.getDefaultValue());
        }
        return sb.toString();
    }

    /**
     * 追加一个「长度:内容」的记号 —— 见类注释里为什么不能用分隔符
     */
    private static void token(StringBuilder sb, Object value) {
        String text = value == null ? "" : String.valueOf(value);
        sb.append(text.length()).append(':').append(text);
    }

    private static String lower(String value) {
        // MySQL 的列名不区分大小写：Biz_Name 与 biz_name 是同一列
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static String upper(String value) {
        // 类型名统一大写：TableMetaReader 对受支持的类型给的是枚举值（已大写），
        // 对不受支持的类型给的是 dataType.toUpperCase()，这里再兜一次，
        // 免得将来有人改了其中一处的大小写就让指纹整体漂移
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

}
