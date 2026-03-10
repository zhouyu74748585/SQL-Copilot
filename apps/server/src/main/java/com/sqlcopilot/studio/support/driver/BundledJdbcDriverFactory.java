package com.sqlcopilot.studio.support.driver;

import java.sql.Driver;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Creates JDBC driver instances for drivers that are already bundled on the application classpath.
 */
public final class BundledJdbcDriverFactory {

    private static final Map<String, List<String>> DRIVER_IMPLEMENTATIONS = createDriverImplementations();

    private BundledJdbcDriverFactory() {
    }

    public static Driver createIfSupported(String requestedDriverClassName) throws SQLException {
        List<String> candidates = DRIVER_IMPLEMENTATIONS.get(requestedDriverClassName);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = BundledJdbcDriverFactory.class.getClassLoader();
        }

        Exception lastError = null;
        for (String className : candidates) {
            try {
                Class<?> driverClass = Class.forName(className, true, classLoader);
                Object instance = driverClass.getDeclaredConstructor().newInstance();
                if (instance instanceof Driver driver) {
                    return driver;
                }
            } catch (Exception ex) {
                lastError = ex;
            }
        }

        if (lastError != null) {
            throw new SQLException("初始化内置 JDBC 驱动失败: " + requestedDriverClassName + "，" + lastError.getMessage(), lastError);
        }
        return null;
    }

    public static Set<String> supportedDriverClasses() {
        return new LinkedHashSet<>(DRIVER_IMPLEMENTATIONS.keySet());
    }

    private static Map<String, List<String>> createDriverImplementations() {
        Map<String, List<String>> mappings = new LinkedHashMap<>();
        mappings.put("com.mysql.cj.jdbc.Driver", List.of("com.mysql.cj.jdbc.Driver"));
        mappings.put("com.mysql.jdbc.Driver", List.of("com.mysql.jdbc.Driver", "com.mysql.cj.jdbc.Driver"));
        mappings.put("org.postgresql.Driver", List.of("org.postgresql.Driver"));
        mappings.put("com.microsoft.sqlserver.jdbc.SQLServerDriver", List.of("com.microsoft.sqlserver.jdbc.SQLServerDriver"));
        mappings.put("oracle.jdbc.OracleDriver", List.of("oracle.jdbc.OracleDriver"));
        mappings.put("org.sqlite.JDBC", List.of("org.sqlite.JDBC"));
        return mappings;
    }
}
