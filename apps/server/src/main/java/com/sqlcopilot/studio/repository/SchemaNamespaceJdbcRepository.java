package com.sqlcopilot.studio.repository;

import com.sqlcopilot.studio.support.JdbcDriverResolver;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Objects;

/**
 * 库/命名空间动态 SQL 仓储。
 * <p>
 * 关键约束：命名空间创建、重命名、删除相关动态 SQL 统一集中在该仓储中。
 */
@Repository
public class SchemaNamespaceJdbcRepository {

    /**
     * 执行新建库/命名空间。
     */
    public String createNamespace(Connection connection,
                                  String dbType,
                                  JdbcDriverResolver.NamespaceSpec spec,
                                  String targetNamespaceName) throws SQLException {
        String sql = renderNamespaceSql(spec.createSql(), dbType, "", targetNamespaceName);
        executeSql(connection, sql);
        return sql;
    }

    /**
     * 执行重命名库/命名空间。
     */
    public String renameNamespace(Connection connection,
                                  String dbType,
                                  JdbcDriverResolver.NamespaceSpec spec,
                                  String sourceNamespaceName,
                                  String targetNamespaceName) throws SQLException {
        String sql = renderNamespaceSql(spec.renameSql(), dbType, sourceNamespaceName, targetNamespaceName);
        executeSql(connection, sql);
        return sql;
    }

    /**
     * 执行删除库/命名空间。
     */
    public String dropNamespace(Connection connection,
                                String dbType,
                                JdbcDriverResolver.NamespaceSpec spec,
                                String sourceNamespaceName) throws SQLException {
        String sql = renderNamespaceSql(spec.dropSql(), dbType, sourceNamespaceName, "");
        executeSql(connection, sql);
        return sql;
    }

    private void executeSql(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String renderNamespaceSql(String sqlTemplate,
                                      String dbType,
                                      String sourceNamespaceName,
                                      String targetNamespaceName) {
        return normalize(sqlTemplate)
            .replace("{source_namespace}", normalize(sourceNamespaceName))
            .replace("{target_namespace}", normalize(targetNamespaceName))
            .replace("{source_namespace_literal}", toSqlLiteral(sourceNamespaceName))
            .replace("{target_namespace_literal}", toSqlLiteral(targetNamespaceName))
            .replace("{quoted_source_namespace}", quoteIdentifier(sourceNamespaceName, dbType))
            .replace("{quoted_target_namespace}", quoteIdentifier(targetNamespaceName, dbType));
    }

    private String quoteIdentifier(String identifier, String dbType) {
        String normalized = normalize(identifier);
        if (normalized.isBlank()) {
            return normalized;
        }
        return switch (normalize(dbType).toUpperCase(Locale.ROOT)) {
            case "SQLSERVER" -> "[" + normalized.replace("]", "]]") + "]";
            case "POSTGRESQL", "ORACLE" -> "\"" + normalized.replace("\"", "\"\"") + "\"";
            default -> "`" + normalized.replace("`", "``") + "`";
        };
    }

    private String toSqlLiteral(String value) {
        return "'" + normalize(value).replace("'", "''") + "'";
    }

    private String normalize(String value) {
        return Objects.toString(value, "").trim();
    }
}
