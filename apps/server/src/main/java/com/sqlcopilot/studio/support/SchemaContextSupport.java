package com.sqlcopilot.studio.support;

import java.util.Locale;
import java.util.Objects;

/**
 * Schema 上下文解析工具：统一处理 database::schema 组合上下文。
 */
public final class SchemaContextSupport {

    public static final String SEPARATOR = "::";

    private SchemaContextSupport() {
    }

    public static boolean supportsSchemaLayer(String dbType) {
        String type = normalize(dbType).toUpperCase(Locale.ROOT);
        return "POSTGRESQL".equals(type) || "SQLSERVER".equals(type) || "ORACLE".equals(type);
    }

    public static SchemaContext parse(String dbType, String rawContext) {
        String raw = normalize(rawContext);
        if (raw.isBlank()) {
            return new SchemaContext("", "", "");
        }
        if (!supportsSchemaLayer(dbType)) {
            return new SchemaContext(raw, raw, "");
        }
        int separatorIndex = raw.indexOf(SEPARATOR);
        if (separatorIndex <= 0 || separatorIndex >= raw.length() - SEPARATOR.length()) {
            return new SchemaContext(raw, raw, "");
        }
        String databaseName = normalize(raw.substring(0, separatorIndex));
        String namespaceName = normalize(raw.substring(separatorIndex + SEPARATOR.length()));
        if (databaseName.isBlank() || namespaceName.isBlank()) {
            return new SchemaContext(raw, raw, "");
        }
        return new SchemaContext(raw, databaseName, namespaceName);
    }

    private static String normalize(String value) {
        return Objects.toString(value, "").trim();
    }

    public record SchemaContext(String rawContext, String databaseName, String namespaceName) {
        public boolean hasNamespace() {
            return !namespaceName.isBlank();
        }
    }
}
