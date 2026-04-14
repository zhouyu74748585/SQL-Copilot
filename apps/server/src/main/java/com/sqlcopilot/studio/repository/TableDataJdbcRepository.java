package com.sqlcopilot.studio.repository;

import com.sqlcopilot.studio.dto.schema.TableDataPageReq;
import com.sqlcopilot.studio.dto.schema.TableDataPageVO;
import com.sqlcopilot.studio.support.SchemaContextSupport;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 表数据动态 SQL 仓储。
 * <p>
 * 关键约束：动态 SQL 仅集中在该类中，Service 层不拼接 SQL。
 */
@Repository
public class TableDataJdbcRepository {

    /**
     * 分页查询数据行。
     */
    public List<TableDataPageVO.RowVO> queryPage(Connection connection,
                                                  String dbType,
                                                  String databaseName,
                                                  String tableName,
                                                  List<String> selectedColumns,
                                                  List<String> defaultOrderColumns,
                                                  List<TableDataPageReq.SortItem> sorts,
                                                  Set<String> allowedColumnsLower,
                                                  List<TableDataPageReq.FilterItem> filters,
                                                  int pageNo,
                                                  int pageSize,
                                                  int fetchSize) throws SQLException {
        SqlWithParams whereSql = buildWhereClause(dbType, filters, allowedColumnsLower);
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ")
            .append(buildColumnList(selectedColumns, dbType))
            .append(" FROM ")
            .append(buildTableReference(databaseName, tableName, dbType))
            .append(whereSql.sql())
            .append(" ORDER BY ")
            .append(buildOrderBy(defaultOrderColumns, sorts, dbType));

        List<Object> params = new ArrayList<>(whereSql.params());
        int offset = Math.max(0, (pageNo - 1) * pageSize);
        String dialect = normalize(dbType).toUpperCase(Locale.ROOT);
        if ("SQLSERVER".equals(dialect) || "ORACLE".equals(dialect)) {
            sql.append(" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
            params.add(offset);
            params.add(fetchSize);
        } else {
            sql.append(" LIMIT ? OFFSET ?");
            params.add(fetchSize);
            params.add(offset);
        }

        List<TableDataPageVO.RowVO> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindParams(statement, params);
            try (ResultSet rs = statement.executeQuery()) {
                int rowIndex = offset;
                while (rs.next()) {
                    rowIndex++;
                    TableDataPageVO.RowVO row = new TableDataPageVO.RowVO();
                    row.setRowKey("row-" + rowIndex);
                    List<TableDataPageVO.CellVO> cells = new ArrayList<>();
                    for (int i = 0; i < selectedColumns.size(); i++) {
                        Object value = rs.getObject(i + 1);
                        TableDataPageVO.CellVO cell = new TableDataPageVO.CellVO();
                        cell.setColumnName(selectedColumns.get(i));
                        cell.setCellValue(value == null ? null : String.valueOf(value));
                        cells.add(cell);
                    }
                    row.setCells(cells);
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    /**
     * 按主键删除一行。
     */
    public int deleteByPrimaryKey(Connection connection,
                                  String dbType,
                                  String databaseName,
                                  String tableName,
                                  List<String> primaryKeyColumns,
                                  LinkedHashMap<String, Object> primaryKeyValues) throws SQLException {
        String where = buildPrimaryKeyWhereClause(primaryKeyColumns, dbType);
        String sql = "DELETE FROM " + buildTableReference(databaseName, tableName, dbType) + " WHERE " + where;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindValuesByColumnOrder(statement, primaryKeyColumns, primaryKeyValues);
            return statement.executeUpdate();
        }
    }

    /**
     * 按主键更新一行。
     */
    public int updateByPrimaryKey(Connection connection,
                                  String dbType,
                                  String databaseName,
                                  String tableName,
                                  LinkedHashMap<String, Object> updateValues,
                                  List<String> primaryKeyColumns,
                                  LinkedHashMap<String, Object> primaryKeyValues) throws SQLException {
        List<String> updateColumns = new ArrayList<>(updateValues.keySet());
        StringBuilder setSql = new StringBuilder();
        for (int i = 0; i < updateColumns.size(); i++) {
            if (i > 0) {
                setSql.append(", ");
            }
            setSql.append(quoteIdentifier(updateColumns.get(i), dbType)).append(" = ?");
        }

        String where = buildPrimaryKeyWhereClause(primaryKeyColumns, dbType);
        String sql = "UPDATE " + buildTableReference(databaseName, tableName, dbType)
            + " SET " + setSql + " WHERE " + where;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (String column : updateColumns) {
                statement.setObject(index++, updateValues.get(column));
            }
            for (String pk : primaryKeyColumns) {
                statement.setObject(index++, primaryKeyValues.get(pk));
            }
            return statement.executeUpdate();
        }
    }

    /**
     * 新增一行。
     */
    public int insertRow(Connection connection,
                         String dbType,
                         String databaseName,
                         String tableName,
                         LinkedHashMap<String, Object> values) throws SQLException {
        List<String> columns = new ArrayList<>(values.keySet());
        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO ")
            .append(buildTableReference(databaseName, tableName, dbType))
            .append(" (")
            .append(buildColumnList(columns, dbType))
            .append(") VALUES (");

        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("?");
        }
        sql.append(")");

        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            for (String column : columns) {
                statement.setObject(index++, values.get(column));
            }
            return statement.executeUpdate();
        }
    }

    private SqlWithParams buildWhereClause(String dbType,
                                           List<TableDataPageReq.FilterItem> filters,
                                           Set<String> allowedColumnsLower) {
        List<TableDataPageReq.FilterItem> safeFilters = filters == null ? List.of() : filters;
        if (safeFilters.isEmpty()) {
            return new SqlWithParams("", List.of());
        }

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        for (TableDataPageReq.FilterItem filter : safeFilters) {
            if (filter == null) {
                continue;
            }
            String column = normalize(filter.getColumnName());
            String lower = column.toLowerCase(Locale.ROOT);
            if (!allowedColumnsLower.contains(lower)) {
                continue;
            }
            String operator = normalize(filter.getOperator()).toUpperCase(Locale.ROOT);
            String quotedColumn = quoteIdentifier(column, dbType);
            switch (operator) {
                case "EQ" -> {
                    where.append(" AND ").append(quotedColumn).append(" = ?");
                    params.add(filter.getValue());
                }
                case "NE" -> {
                    where.append(" AND ").append(quotedColumn).append(" <> ?");
                    params.add(filter.getValue());
                }
                case "GT" -> {
                    where.append(" AND ").append(quotedColumn).append(" > ?");
                    params.add(filter.getValue());
                }
                case "GTE" -> {
                    where.append(" AND ").append(quotedColumn).append(" >= ?");
                    params.add(filter.getValue());
                }
                case "LT" -> {
                    where.append(" AND ").append(quotedColumn).append(" < ?");
                    params.add(filter.getValue());
                }
                case "LTE" -> {
                    where.append(" AND ").append(quotedColumn).append(" <= ?");
                    params.add(filter.getValue());
                }
                case "LIKE" -> {
                    where.append(" AND ").append(quotedColumn).append(" LIKE ?");
                    String raw = Objects.toString(filter.getValue(), "");
                    params.add("%" + raw + "%");
                }
                case "IS_NULL" -> where.append(" AND ").append(quotedColumn).append(" IS NULL");
                case "IS_NOT_NULL" -> where.append(" AND ").append(quotedColumn).append(" IS NOT NULL");
                default -> {
                    // 已在 Service 层做校验，此处忽略未知操作符。
                }
            }
        }
        return new SqlWithParams(where.toString(), params);
    }

    private void bindParams(PreparedStatement statement, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            statement.setObject(i + 1, params.get(i));
        }
    }

    private void bindValuesByColumnOrder(PreparedStatement statement,
                                         List<String> columns,
                                         LinkedHashMap<String, Object> values) throws SQLException {
        for (int i = 0; i < columns.size(); i++) {
            statement.setObject(i + 1, values.get(columns.get(i)));
        }
    }

    private String buildPrimaryKeyWhereClause(List<String> primaryKeyColumns, String dbType) {
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < primaryKeyColumns.size(); i++) {
            if (i > 0) {
                where.append(" AND ");
            }
            where.append(quoteIdentifier(primaryKeyColumns.get(i), dbType)).append(" = ?");
        }
        return where.toString();
    }

    private String buildOrderBy(List<String> defaultOrderColumns, List<TableDataPageReq.SortItem> sorts, String dbType) {
        if (sorts != null && !sorts.isEmpty()) {
            List<String> sortExpressions = sorts.stream()
                .filter(Objects::nonNull)
                .map(item -> quoteIdentifier(item.getColumnName(), dbType) + " " + normalize(item.getDirection()).toUpperCase(Locale.ROOT))
                .toList();
            if (!sortExpressions.isEmpty()) {
                return String.join(", ", sortExpressions);
            }
        }
        if (defaultOrderColumns == null || defaultOrderColumns.isEmpty()) {
            return "1";
        }
        List<String> quoted = defaultOrderColumns.stream()
            .map(item -> quoteIdentifier(item, dbType))
            .toList();
        return String.join(", ", quoted);
    }

    private String buildColumnList(List<String> columns, String dbType) {
        return columns.stream().map(item -> quoteIdentifier(item, dbType)).reduce((a, b) -> a + ", " + b).orElse("*");
    }

    private String buildTableReference(String databaseName, String tableName, String dbType) {
        QualifiedTableName qualifiedTableName = resolveQualifiedTableName(databaseName, tableName, dbType);
        String quotedTable = quoteIdentifier(qualifiedTableName.tableName(), dbType);
        if (quotedTable.isBlank()) {
            return quotedTable;
        }
        String qualifiedNamespace = qualifiedTableName.namespaceName();
        if (qualifiedNamespace.isBlank()) {
            return quotedTable;
        }
        return quoteIdentifier(qualifiedNamespace, dbType) + "." + quotedTable;
    }

    private QualifiedTableName resolveQualifiedTableName(String databaseName, String tableName, String dbType) {
        String normalizedTableName = normalize(tableName);
        String namespaceName = resolveQualifiedNamespace(databaseName, dbType);
        if (normalizedTableName.isBlank()) {
            return new QualifiedTableName(namespaceName, "");
        }
        int dotIndex = normalizedTableName.lastIndexOf('.');
        if (dotIndex <= 0 || dotIndex >= normalizedTableName.length() - 1) {
            return new QualifiedTableName(namespaceName, normalizedTableName);
        }
        String explicitNamespace = normalize(normalizedTableName.substring(0, dotIndex));
        String simpleTableName = normalize(normalizedTableName.substring(dotIndex + 1));
        if (simpleTableName.isBlank()) {
            return new QualifiedTableName(namespaceName, normalizedTableName);
        }
        return new QualifiedTableName(explicitNamespace.isBlank() ? namespaceName : explicitNamespace, simpleTableName);
    }

    private String resolveQualifiedNamespace(String databaseName, String dbType) {
        String type = normalize(dbType).toUpperCase(Locale.ROOT);
        SchemaContextSupport.SchemaContext context = SchemaContextSupport.parse(type, databaseName);
        return switch (type) {
            case "POSTGRESQL", "SQLSERVER", "ORACLE" -> context.hasNamespace() ? context.namespaceName() : "";
            case "MYSQL" -> context.databaseName();
            default -> "";
        };
    }

    private String quoteIdentifier(String identifier, String dbType) {
        String text = normalize(identifier);
        if (text.isBlank()) {
            return text;
        }
        String type = normalize(dbType).toUpperCase(Locale.ROOT);
        if ("SQLSERVER".equals(type)) {
            return "[" + text.replace("]", "]]") + "]";
        }
        if ("POSTGRESQL".equals(type) || "ORACLE".equals(type)) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return "`" + text.replace("`", "``") + "`";
    }

    private String normalize(String value) {
        return Objects.toString(value, "").trim();
    }

    private record SqlWithParams(String sql, List<Object> params) {
    }

    private record QualifiedTableName(String namespaceName, String tableName) {
    }
}
