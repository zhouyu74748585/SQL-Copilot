package com.sqlcopilot.studio.util;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识中心结构化元数据派生工具，统一样例 SQL 与术语的默认元数据生成规则。
 */
public final class KnowledgeMetadataUtil {

    private static final Pattern TABLE_PATTERN = Pattern.compile("(?i)\\b(?:from|join|update|into|table)\\s+([a-zA-Z0-9_$.`\"]+)");
    private static final Pattern COLUMN_ALIAS_PATTERN = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)\\.([a-zA-Z_][a-zA-Z0-9_]*)");
    private static final Pattern SQL_STRING_LITERAL_PATTERN = Pattern.compile("'(?:''|[^'])*'");
    private static final Pattern SQL_NUMBER_LITERAL_PATTERN = Pattern.compile("(?<![a-zA-Z0-9_])\\d+(?:\\.\\d+)?(?![a-zA-Z0-9_])");

    private KnowledgeMetadataUtil() {
    }

    public static DerivedTermMetadata deriveTermMetadata(String rawTerm, String rawDescription, ObjectMapper objectMapper) {
        String term = safe(rawTerm);
        String description = safe(rawDescription);
        List<String> aliases = buildAliases(term, description);
        String metricExpression = description.isBlank() ? term : description;
        return new DerivedTermMetadata(
            toJson(objectMapper, aliases),
            metricExpression,
            "[]",
            "[]",
            inferTermType(term, description)
        );
    }

    public static DerivedExampleMetadata deriveExampleMetadata(String rawSqlText,
                                                               String rawDescription,
                                                               List<Long> termIds,
                                                               ObjectMapper objectMapper) {
        String sqlText = safe(rawSqlText);
        String description = safe(rawDescription);
        SqlShape sqlShape = analyzeSql(sqlText);
        String normalizedSql = normalizeSql(sqlText);
        List<String> questionVariants = description.isBlank() ? List.of() : List.of(description);
        return new DerivedExampleMetadata(
            description.isBlank() ? buildFallbackQuestion(sqlShape) : description,
            toJson(objectMapper, questionVariants),
            description.isBlank() ? buildFallbackSemantic(sqlShape) : description,
            normalizedSql,
            normalizedSql,
            buildSqlAstJson(objectMapper, sqlShape),
            toJson(objectMapper, sqlShape.tables()),
            toJson(objectMapper, sqlShape.columns()),
            toJson(objectMapper, buildMetricTags(sqlShape.columns(), normalizedSql)),
            toJson(objectMapper, buildTimeTags(sqlText, sqlShape.columns())),
            1,
            0.95D,
            "MANUAL",
            sqlShape.operationType(),
            toJson(objectMapper, normalizeTermIds(termIds))
        );
    }

    public static SqlShape analyzeSql(String rawSqlText) {
        String sqlText = safe(rawSqlText);
        Set<String> tableSet = new LinkedHashSet<>();
        Matcher tableMatcher = TABLE_PATTERN.matcher(sqlText);
        while (tableMatcher.find()) {
            String table = normalizeIdentifier(tableMatcher.group(1));
            if (!table.isBlank()) {
                tableSet.add(table);
            }
        }

        Set<String> columnSet = new LinkedHashSet<>();
        Matcher columnMatcher = COLUMN_ALIAS_PATTERN.matcher(sqlText);
        while (columnMatcher.find()) {
            String column = normalizeIdentifier(columnMatcher.group(2));
            if (!column.isBlank()) {
                columnSet.add(column);
            }
        }
        return new SqlShape(
            List.copyOf(tableSet),
            List.copyOf(columnSet),
            extractOperationType(sqlText)
        );
    }

    public static String normalizeSql(String rawSqlText) {
        String normalized = safe(rawSqlText).replace('　', ' ');
        normalized = SQL_STRING_LITERAL_PATTERN.matcher(normalized).replaceAll("<str>");
        normalized = SQL_NUMBER_LITERAL_PATTERN.matcher(normalized).replaceAll("<num>");
        return normalized.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static List<Long> normalizeTermIds(List<Long> termIds) {
        if (termIds == null || termIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> normalized = new LinkedHashSet<>();
        for (Long value : termIds) {
            if (value != null && value > 0) {
                normalized.add(value);
            }
        }
        return new ArrayList<>(normalized);
    }

    private static String inferTermType(String term, String description) {
        String merged = (term + " " + description).toLowerCase(Locale.ROOT);
        if (merged.contains("count") || merged.contains("sum") || merged.contains("avg")
            || merged.contains("total") || merged.contains("rate") || merged.contains("金额")
            || merged.contains("数量") || merged.contains("占比") || merged.contains("均值")) {
            return "METRIC";
        }
        if (merged.contains("维度") || merged.contains("分类") || merged.contains("枚举")) {
            return "DIMENSION";
        }
        return "TERM";
    }

    private static List<String> buildAliases(String term, String description) {
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        if (!term.isBlank()) {
            aliases.add(term);
        }
        for (String token : description.split("[,，/；;\\s]+")) {
            String normalized = safe(token);
            if (!normalized.isBlank() && normalized.length() <= 24) {
                aliases.add(normalized);
            }
        }
        return new ArrayList<>(aliases);
    }

    private static String buildFallbackQuestion(SqlShape sqlShape) {
        if (!sqlShape.tables().isEmpty()) {
            return "查询 " + String.join("、", sqlShape.tables()) + " 相关数据";
        }
        return "参考 SQL 样例";
    }

    private static String buildFallbackSemantic(SqlShape sqlShape) {
        String tables = sqlShape.tables().isEmpty() ? "未识别表" : String.join(",", sqlShape.tables());
        String columns = sqlShape.columns().isEmpty() ? "未识别字段" : String.join(",", sqlShape.columns());
        return "SQL类型=" + sqlShape.operationType() + "；涉及表=" + tables + "；涉及字段=" + columns;
    }

    private static String buildSqlAstJson(ObjectMapper objectMapper, SqlShape sqlShape) {
        Map<String, Object> ast = new LinkedHashMap<>();
        ast.put("operationType", sqlShape.operationType());
        ast.put("tables", sqlShape.tables());
        ast.put("columns", sqlShape.columns());
        return toJson(objectMapper, ast);
    }

    private static List<String> buildMetricTags(List<String> columns, String normalizedSql) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        if (normalizedSql.contains("count(")) {
            tags.add("count");
        }
        if (normalizedSql.contains("sum(")) {
            tags.add("sum");
        }
        if (normalizedSql.contains("avg(")) {
            tags.add("avg");
        }
        for (String column : columns) {
            String lower = column.toLowerCase(Locale.ROOT);
            if (lower.contains("amount") || lower.contains("price") || lower.contains("count")
                || lower.contains("num") || lower.contains("qty") || lower.contains("rate")) {
                tags.add(column);
            }
        }
        return new ArrayList<>(tags);
    }

    private static List<String> buildTimeTags(String sqlText, List<String> columns) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        String loweredSql = safe(sqlText).toLowerCase(Locale.ROOT);
        if (loweredSql.contains("date") || loweredSql.contains("day") || loweredSql.contains("week")
            || loweredSql.contains("month") || loweredSql.contains("year")
            || loweredSql.contains("时间") || loweredSql.contains("日期")) {
            tags.add("time_filter");
        }
        for (String column : columns) {
            String lower = column.toLowerCase(Locale.ROOT);
            if (lower.contains("time") || lower.contains("date") || lower.contains("day")
                || lower.contains("month") || lower.contains("year") || lower.contains("created")) {
                tags.add(column);
            }
        }
        return new ArrayList<>(tags);
    }

    private static String extractOperationType(String sqlText) {
        String normalized = safe(sqlText).toLowerCase(Locale.ROOT);
        if (normalized.startsWith("insert")) {
            return "INSERT";
        }
        if (normalized.startsWith("update")) {
            return "UPDATE";
        }
        if (normalized.startsWith("delete")) {
            return "DELETE";
        }
        if (normalized.startsWith("create")) {
            return "DDL";
        }
        return "SELECT";
    }

    private static String normalizeIdentifier(String rawValue) {
        String value = safe(rawValue).replace("`", "").replace("\"", "");
        if (value.contains(".")) {
            String[] parts = value.split("\\.");
            return safe(parts[parts.length - 1]);
        }
        return value;
    }

    private static String toJson(ObjectMapper objectMapper, Object value) {
        if (objectMapper == null) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private static String safe(String value) {
        return Objects.toString(value, "").trim();
    }

    public record DerivedTermMetadata(String aliasesJson,
                                      String metricExpression,
                                      String relatedTablesJson,
                                      String relatedColumnsJson,
                                      String termType) {
    }

    public record DerivedExampleMetadata(String questionText,
                                         String questionVariantsJson,
                                         String semanticSummary,
                                         String normalizedSql,
                                         String sqlTemplate,
                                         String sqlAstJson,
                                         String tableNamesJson,
                                         String columnNamesJson,
                                         String metricTagsJson,
                                         String timeTagsJson,
                                         Integer verifiedFlag,
                                         Double qualityScore,
                                         String sourceType,
                                         String sqlOperationType,
                                         String termIdsNormalizedJson) {
    }

    public record SqlShape(List<String> tables, List<String> columns, String operationType) {
    }
}
