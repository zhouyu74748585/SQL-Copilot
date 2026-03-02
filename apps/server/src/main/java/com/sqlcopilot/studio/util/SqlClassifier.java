package com.sqlcopilot.studio.util;

import java.util.Locale;

public final class SqlClassifier {

    private SqlClassifier() {
    }

    public static boolean isQuery(String sql) {
        String normalized = normalize(sql);
        return normalized.startsWith("select") || normalized.startsWith("show") || normalized.startsWith("with");
    }

    public static boolean isDml(String sql) {
        String normalized = normalize(sql);
        return normalized.startsWith("insert") || normalized.startsWith("update") || normalized.startsWith("delete")
            || normalized.startsWith("replace");
    }

    public static boolean hasWhereForUpdateDelete(String sql) {
        String normalized = normalize(sql);
        if (!(normalized.startsWith("update") || normalized.startsWith("delete"))) {
            return true;
        }
        return normalized.contains(" where ");
    }

    public static String normalize(String sql) {
        return (sql == null ? "" : sql).trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public static String digest(String sql) {
        String normalized = normalize(sql);
        return normalized.length() > 120 ? normalized.substring(0, 120) : normalized;
    }
}
