package com.sqlcopilot.studio.repository;

import com.sqlcopilot.studio.support.JdbcDriverResolver;
import com.sqlcopilot.studio.util.BusinessException;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 视图/函数定义动态 SQL 仓储。
 * <p>
 * 关键约束：对象定义读取与保存相关动态 SQL 仅集中在该仓储中。
 */
@Repository
public class SchemaObjectDefinitionJdbcRepository {

    /**
     * 读取对象定义 SQL。
     */
    public String fetchDefinition(Connection connection,
                                  String dbType,
                                  JdbcDriverResolver.ObjectDefinitionSpec spec,
                                  String databaseName,
                                  String objectName) throws SQLException {
        String sql = renderSqlTemplate(spec.fetchSql(), dbType, databaseName, objectName, "");
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) {
                return "";
            }
            String label = normalize(spec.fetchColumnLabel());
            if (!label.isBlank()) {
                return normalize(rs.getString(label));
            }
            int columnIndex = spec.fetchColumnIndex() == null || spec.fetchColumnIndex() <= 0 ? 1 : spec.fetchColumnIndex();
            return normalize(rs.getString(columnIndex));
        }
    }

    /**
     * 保存对象定义 SQL。
     */
    public void saveDefinition(Connection connection,
                               String dbType,
                               JdbcDriverResolver.ObjectDefinitionSpec spec,
                               String databaseName,
                               String objectType,
                               String objectName,
                               String definitionSql) throws SQLException {
        validateDefinitionTarget(objectType, objectName, definitionSql);
        List<String> statements = new ArrayList<>();
        if ("DROP_CREATE".equalsIgnoreCase(normalize(spec.saveStrategy()))) {
            String dropSql = renderSqlTemplate(spec.dropSql(), dbType, databaseName, objectName, definitionSql);
            if (!dropSql.isBlank()) {
                statements.addAll(splitSqlStatements(dropSql));
            }
        }
        String replaceSql = renderSqlTemplate(spec.replaceSql(), dbType, databaseName, objectName, definitionSql);
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
            if (!replaceSql.isBlank()) {
                statement.execute(replaceSql);
            }
        }
    }

    private void validateDefinitionTarget(String objectType, String expectedObjectName, String definitionSql) {
        String actualObjectName = extractDefinedObjectName(objectType, definitionSql);
        if (actualObjectName.isBlank()) {
            throw new BusinessException(400, "未识别到对象定义头，暂不支持保存当前 SQL");
        }
        if (!actualObjectName.equalsIgnoreCase(normalize(expectedObjectName))) {
            throw new BusinessException(400, "当前不支持通过定义编辑页修改对象名");
        }
    }

    private String extractDefinedObjectName(String objectType, String definitionSql) {
        String normalizedType = normalize(objectType).toLowerCase(Locale.ROOT);
        String keyword = "views".equals(normalizedType) ? "view" : "function";
        Pattern pattern = Pattern.compile(
            "(?is)^\\s*create\\s+(?:or\\s+replace\\s+|or\\s+alter\\s+)?(?:algorithm\\s*=\\s*\\w+\\s+)?(?:definer\\s*=\\s*[^\\s]+\\s+)?(?:sql\\s+security\\s+\\w+\\s+)?"
                + "(?:\\w+\\s+)*?"
                + keyword
                + "\\s+([^\\s(]+)"
        );
        Matcher matcher = pattern.matcher(normalize(definitionSql));
        if (!matcher.find()) {
            return "";
        }
        String rawName = normalize(matcher.group(1));
        if (rawName.contains(".")) {
            String[] segments = rawName.split("\\.");
            rawName = segments[segments.length - 1];
        }
        return stripIdentifierWrapper(rawName);
    }

    private String stripIdentifierWrapper(String rawName) {
        String normalized = normalize(rawName);
        if ((normalized.startsWith("`") && normalized.endsWith("`"))
            || (normalized.startsWith("\"") && normalized.endsWith("\""))
            || (normalized.startsWith("[") && normalized.endsWith("]"))
            || (normalized.startsWith("'") && normalized.endsWith("'"))) {
            return normalized.substring(1, normalized.length() - 1).trim();
        }
        return normalized;
    }

    private String renderSqlTemplate(String sqlTemplate,
                                     String dbType,
                                     String databaseName,
                                     String objectName,
                                     String definitionSql) {
        return normalize(sqlTemplate)
            .replace("{object_name}", normalize(objectName))
            .replace("{quoted_object_name}", quoteIdentifier(objectName, dbType))
            .replace("{object_literal}", toSqlLiteral(objectName))
            .replace("{database_name}", normalize(databaseName))
            .replace("{database_literal}", toSqlLiteral(databaseName))
            .replace("{definition_sql}", normalize(definitionSql));
    }

    private List<String> splitSqlStatements(String sqlText) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int index = 0; index < sqlText.length(); index += 1) {
            char ch = sqlText.charAt(index);
            if (ch == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            }
            if (ch == ';' && !inSingleQuote && !inDoubleQuote) {
                String segment = current.toString().trim();
                if (!segment.isBlank()) {
                    statements.add(segment);
                }
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        String tail = current.toString().trim();
        if (!tail.isBlank()) {
            statements.add(tail);
        }
        return statements;
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
