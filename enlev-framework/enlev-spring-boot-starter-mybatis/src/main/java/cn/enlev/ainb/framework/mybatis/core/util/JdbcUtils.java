package cn.enlev.ainb.framework.mybatis.core.util;

import cn.enlev.ainb.framework.common.util.object.ObjectUtils;
import cn.enlev.ainb.framework.common.util.spring.SpringUtils;
import cn.enlev.ainb.framework.mybatis.core.enums.DbTypeEnum;
import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.mybatisplus.annotation.DbType;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC 工具类
 *
 * @author 芋道源码
 */
public class JdbcUtils {

    /**
     * 判断连接是否正确
     *
     * @param url      数据源连接
     * @param username 账号
     * @param password 密码
     * @return 是否正确
     */
    public static boolean isConnectionOK(String url, String username, String password) {
        try (Connection ignored = DriverManager.getConnection(url, username, password)) {
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * 获得 URL 对应的 DB 类型
     *
     * @param url URL
     * @return DB 类型
     */
    public static DbType getDbType(String url) {
        return com.baomidou.mybatisplus.extension.toolkit.JdbcUtils.getDbType(url);
    }

    /**
     * 通过当前数据库连接获得对应的 DB 类型
     *
     * @return DB 类型
     */
    public static DbType getDbType() {
        DataSource dataSource;
        try {
            DynamicRoutingDataSource dynamicRoutingDataSource = SpringUtils.getBean(DynamicRoutingDataSource.class);
            dataSource = dynamicRoutingDataSource.determineDataSource();
        } catch (NoSuchBeanDefinitionException e) {
            dataSource = SpringUtils.getBean(DataSource.class);
        }
        try (Connection conn = dataSource.getConnection()) {
            return DbTypeEnum.find(conn.getMetaData().getDatabaseProductName());
        } catch (SQLException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    /**
     * 判断 JDBC 连接是否为 SQLServer 数据库
     *
     * @param url JDBC 连接
     * @return 是否为 SQLServer 数据库
     */
    public static boolean isSQLServer(String url) {
        DbType dbType = getDbType(url);
        return isSQLServer(dbType);
    }

    /**
     * 判断 JDBC 连接是否为 SQLServer 数据库
     *
     * @param dbType DB 类型
     * @return 是否为 SQLServer 数据库
     */
    public static boolean isSQLServer(DbType dbType) {
        return ObjectUtils.equalsAny(dbType, DbType.SQL_SERVER, DbType.SQL_SERVER2005);
    }

    /**
     * 获取数据库表列表及字段信息
     *
     * @param url      数据源连接
     * @param username 账号
     * @param password 密码
     * @return 表列表，每个表包含字段信息
     */
    public static List<TableInfo> getTableList(String url, String username, String password) {
        List<TableInfo> tables = new ArrayList<>();

        try (Connection conn = DriverManager.getConnection(url, username, password)) {
            DbType dbType = getDbType(url);

            // MySQL 需要特殊处理才能获取到表注释和字段注释
            if (dbType == DbType.MYSQL || dbType == DbType.MARIADB) {
                return getTableListForMySQL(conn);
            }

            // 其他数据库使用标准 JDBC 元数据 API
            return getTableListByMetaData(conn);
        } catch (SQLException e) {
            throw new IllegalArgumentException("获取数据库表列表失败：" + e.getMessage(), e);
        }
    }

    /**
     * 使用 JDBC 元数据 API 获取表列表（适用于大部分数据库）
     */
    private static List<TableInfo> getTableListByMetaData(Connection conn) throws SQLException {
        List<TableInfo> tables = new ArrayList<>();
        DatabaseMetaData metaData = conn.getMetaData();
        String catalog = conn.getCatalog();
        String schema = conn.getSchema();

        // 获取表列表（TABLE 类型，不包含系统表）
        try (ResultSet tableRs = metaData.getTables(catalog, schema, "%", new String[]{"TABLE"})) {
            while (tableRs.next()) {
                String tableName = tableRs.getString("TABLE_NAME");
                String tableComment = tableRs.getString("REMARKS");

                // 过滤系统表和框架表
                if (isSystemTable(tableName)) {
                    continue;
                }

                TableInfo table = new TableInfo();
                table.setTableName(tableName);
                table.setTableComment(tableComment);

                // 获取该表的列信息
                List<ColumnInfo> columns = new ArrayList<>();
                try (ResultSet columnRs = metaData.getColumns(catalog, schema, tableName, "%")) {
                    while (columnRs.next()) {
                        ColumnInfo column = new ColumnInfo();
                        column.setColumnName(columnRs.getString("COLUMN_NAME"));
                        column.setColumnComment(columnRs.getString("REMARKS"));
                        column.setDataType(columnRs.getString("TYPE_NAME"));
                        columns.add(column);
                    }
                }
                table.setColumns(columns);
                tables.add(table);
            }
        }
        return tables;
    }

    /**
     * MySQL 专用：通过 information_schema 获取表列表和注释
     * 因为 MySQL JDBC 驱动默认不返回 REMARKS，需要在连接串加 useInformationSchema=true
     * 或者直接查询 information_schema
     */
    private static List<TableInfo> getTableListForMySQL(Connection conn) throws SQLException {
        List<TableInfo> tables = new ArrayList<>();
        String catalog = conn.getCatalog();

        // 查询表列表及注释
        String tableSql = "SELECT TABLE_NAME, TABLE_COMMENT FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE'";
        try (PreparedStatement ps = conn.prepareStatement(tableSql)) {
            ps.setString(1, catalog);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String tableName = rs.getString("TABLE_NAME");
                    String tableComment = rs.getString("TABLE_COMMENT");

                    // 过滤系统表和框架表
                    if (isSystemTable(tableName)) {
                        continue;
                    }

                    TableInfo table = new TableInfo();
                    table.setTableName(tableName);
                    table.setTableComment(tableComment);
                    table.setColumns(getColumnsForMySQL(conn, catalog, tableName));
                    tables.add(table);
                }
            }
        }
        return tables;
    }

    /**
     * MySQL 专用：获取指定表的列信息
     */
    private static List<ColumnInfo> getColumnsForMySQL(Connection conn, String catalog, String tableName)
            throws SQLException {
        List<ColumnInfo> columns = new ArrayList<>();
        String columnSql = "SELECT COLUMN_NAME, COLUMN_COMMENT, COLUMN_TYPE FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION";
        try (PreparedStatement ps = conn.prepareStatement(columnSql)) {
            ps.setString(1, catalog);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ColumnInfo column = new ColumnInfo();
                    column.setColumnName(rs.getString("COLUMN_NAME"));
                    column.setColumnComment(rs.getString("COLUMN_COMMENT"));
                    column.setDataType(rs.getString("COLUMN_TYPE"));
                    columns.add(column);
                }
            }
        }
        return columns;
    }

    /**
     * 判断是否为系统表或框架表，需要过滤掉
     */
    private static boolean isSystemTable(String tableName) {
        if (tableName == null) {
            return true;
        }
        String lower = tableName.toLowerCase();
        // MySQL 系统表
        if (lower.startsWith("sys_") || lower.startsWith("mysql.") || lower.startsWith("information_schema.")) {
            return true;
        }
        // 数据库迁移工具表
        if (lower.equals("flyway_schema_history") || lower.equals("schema_version") || lower.equals("databasechangelog")) {
            return true;
        }
        return false;
    }

    /**
     * 表信息
     */
    public static class TableInfo {
        private String tableName;
        private String tableComment;
        private List<ColumnInfo> columns;

        public String getTableName() { return tableName; }
        public void setTableName(String tableName) { this.tableName = tableName; }
        public String getTableComment() { return tableComment; }
        public void setTableComment(String tableComment) { this.tableComment = tableComment; }
        public List<ColumnInfo> getColumns() { return columns; }
        public void setColumns(List<ColumnInfo> columns) { this.columns = columns; }
    }

    /**
     * 列信息
     */
    public static class ColumnInfo {
        private String columnName;
        private String columnComment;
        private String dataType;

        public String getColumnName() { return columnName; }
        public void setColumnName(String columnName) { this.columnName = columnName; }
        public String getColumnComment() { return columnComment; }
        public void setColumnComment(String columnComment) { this.columnComment = columnComment; }
        public String getDataType() { return dataType; }
        public void setDataType(String dataType) { this.dataType = dataType; }
    }

}
