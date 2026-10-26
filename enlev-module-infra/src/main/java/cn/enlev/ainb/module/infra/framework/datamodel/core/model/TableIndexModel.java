package cn.enlev.ainb.module.infra.framework.datamodel.core.model;

import cn.hutool.core.util.StrUtil;

import java.util.List;
import java.util.stream.Collectors;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 表索引模型
 *
 * 与 {@link TableColumnModel} 一样双向流转：读 information_schema.STATISTICS 的产物，
 * 也是生成 CREATE TABLE / ALTER TABLE 索引子句的输入。
 *
 * 只支持普通索引与唯一索引两种 —— 覆盖低代码场景绝大多数需求；
 * FULLTEXT / SPATIAL 有各自的适用前提，交给 DBA 手工处理比在界面上误开更负责。
 *
 * @author 走进新时代
 */
@Data
@Accessors(chain = true)
public class TableIndexModel {

    /**
     * 普通索引
     */
    public static final String TYPE_NORMAL = "NORMAL";
    /**
     * 唯一索引
     */
    public static final String TYPE_UNIQUE = "UNIQUE";

    /**
     * 索引名
     */
    private String indexName;
    /**
     * 索引类型：NORMAL / UNIQUE
     */
    private String indexType;
    /**
     * 索引列，顺序即索引内的列顺序（影响最左前缀匹配）
     */
    private List<String> columnNames;

    public boolean isUnique() {
        return TYPE_UNIQUE.equalsIgnoreCase(indexType);
    }

    /**
     * 结构签名：类型 + 小写列名序列
     *
     * 差异比对用它判断「同名索引是否真的变了」。索引名大小写不敏感地匹配，
     * 列顺序参与签名 —— 调整列顺序会改变最左前缀语义，必须重建。
     */
    public String signature() {
        String columns = columnNames == null ? "" : columnNames.stream()
                .map(name -> StrUtil.emptyIfNull(name).toLowerCase())
                .collect(Collectors.joining(","));
        return (isUnique() ? TYPE_UNIQUE : TYPE_NORMAL) + ":" + columns;
    }

}
