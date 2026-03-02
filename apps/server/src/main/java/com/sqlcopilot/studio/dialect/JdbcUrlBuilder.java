package com.sqlcopilot.studio.dialect;

import com.sqlcopilot.studio.entity.ConnectionEntity;
import com.sqlcopilot.studio.util.BusinessException;

import java.util.Locale;
import java.util.Objects;

public final class JdbcUrlBuilder {

    private JdbcUrlBuilder() {
    }

    public static String build(ConnectionEntity entity) {
        String type = normalize(entity.getDbType()).toUpperCase(Locale.ROOT);
        return switch (type) {
            case "MYSQL" -> {
                Endpoint endpoint = resolveEndpoint(entity.getHost(), entity.getPort(), 3306, "MySQL 主机不能为空");
                String dbName = sanitizeDbName(firstNonBlank(entity.getDatabaseName(), endpoint.dbNameFromHost()));
                String databasePart = dbName.isBlank() ? "" : "/" + dbName;
                yield String.format(
                    "jdbc:mysql://%s:%d%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8",
                    endpoint.host(),
                    endpoint.port(),
                    databasePart);
            }
            case "POSTGRESQL" -> {
                Endpoint endpoint = resolveEndpoint(entity.getHost(), entity.getPort(), 5432, "PostgreSQL 主机不能为空");
                String dbName = sanitizeDbName(firstNonBlank(entity.getDatabaseName(), endpoint.dbNameFromHost()));
                if (dbName.isBlank()) {
                    dbName = "postgres";
                }
                yield String.format(
                    "jdbc:postgresql://%s:%d/%s",
                    endpoint.host(),
                    endpoint.port(),
                    dbName);
            }
            case "SQLITE" -> String.format("jdbc:sqlite:%s",
                requiredText(entity.getDatabaseName(), "SQLite 数据库文件路径不能为空"));
            case "SQLSERVER" -> {
                Endpoint endpoint = resolveEndpoint(entity.getHost(), entity.getPort(), 1433, "SQL Server 主机不能为空");
                String dbName = sanitizeDbName(firstNonBlank(entity.getDatabaseName(), endpoint.dbNameFromHost()));
                yield String.format(
                    "jdbc:sqlserver://%s:%d%s",
                    endpoint.host(),
                    endpoint.port(),
                    dbName.isBlank() ? "" : ";databaseName=" + dbName);
            }
            case "ORACLE" -> {
                Endpoint endpoint = resolveEndpoint(entity.getHost(), entity.getPort(), 1521, "Oracle 主机不能为空");
                yield String.format(
                    "jdbc:oracle:thin:@%s:%d:%s",
                    endpoint.host(),
                    endpoint.port(),
                    requiredDbName(firstNonBlank(entity.getDatabaseName(), endpoint.dbNameFromHost()), "Oracle 服务名不能为空"));
            }
            default -> throw new BusinessException(400, "不支持的数据库类型: " + entity.getDbType());
        };
    }

    private static Integer validPort(Integer port, Integer fallback) {
        int actualPort = port == null || port <= 0 ? fallback : port;
        if (actualPort <= 0 || actualPort > 65535) {
            throw new BusinessException(400, "端口范围不合法，应在 1-65535 之间");
        }
        return actualPort;
    }

    private static String requiredDbName(String rawDbName, String errorMessage) {
        String dbName = normalize(rawDbName);
        while (dbName.startsWith("/")) {
            dbName = dbName.substring(1);
        }
        int queryIndex = dbName.indexOf("?");
        if (queryIndex >= 0) {
            dbName = dbName.substring(0, queryIndex);
        }
        int semicolonIndex = dbName.indexOf(";");
        if (semicolonIndex >= 0) {
            dbName = dbName.substring(0, semicolonIndex);
        }
        if (dbName.isBlank()) {
            throw new BusinessException(400, errorMessage);
        }
        return dbName;
    }

    private static String requiredText(String value, String errorMessage) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            throw new BusinessException(400, errorMessage);
        }
        return normalized;
    }

    private static String normalize(String value) {
        return Objects.toString(value, "").trim();
    }

    private static Endpoint resolveEndpoint(String rawHost, Integer rawPort, Integer fallbackPort, String hostErrorMessage) {
        String endpoint = stripProtocol(requiredText(rawHost, hostErrorMessage));
        int queryIndex = endpoint.indexOf("?");
        if (queryIndex >= 0) {
            endpoint = endpoint.substring(0, queryIndex);
        }
        int semicolonIndex = endpoint.indexOf(";");
        if (semicolonIndex >= 0) {
            endpoint = endpoint.substring(0, semicolonIndex);
        }

        String dbNameFromHost = null;
        int slashIndex = endpoint.indexOf("/");
        if (slashIndex >= 0) {
            dbNameFromHost = endpoint.substring(slashIndex + 1);
            endpoint = endpoint.substring(0, slashIndex);
        }
        int atIndex = endpoint.lastIndexOf("@");
        if (atIndex >= 0 && atIndex < endpoint.length() - 1) {
            endpoint = endpoint.substring(atIndex + 1);
        }

        String host = endpoint;
        Integer resolvedPort = rawPort;
        int colonIndex = host.lastIndexOf(":");
        if (colonIndex > 0 && colonIndex < host.length() - 1 && !host.contains("]")) {
            String maybePort = host.substring(colonIndex + 1);
            if (maybePort.chars().allMatch(Character::isDigit)) {
                if (resolvedPort == null || resolvedPort <= 0) {
                    resolvedPort = Integer.parseInt(maybePort);
                }
                host = host.substring(0, colonIndex);
            }
        }

        if (host.isBlank()) {
            throw new BusinessException(400, hostErrorMessage);
        }
        return new Endpoint(host, validPort(resolvedPort, fallbackPort), normalize(dbNameFromHost));
    }

    private static String stripProtocol(String input) {
        String value = normalize(input);
        int marker = value.indexOf("://");
        if (marker >= 0) {
            return value.substring(marker + 3);
        }
        if (value.startsWith("jdbc:")) {
            return value.substring("jdbc:".length());
        }
        return value;
    }

    private static String firstNonBlank(String primary, String secondary) {
        String first = normalize(primary);
        if (!first.isBlank()) {
            return first;
        }
        return normalize(secondary);
    }

    private static String sanitizeDbName(String rawDbName) {
        String dbName = normalize(rawDbName);
        while (dbName.startsWith("/")) {
            dbName = dbName.substring(1);
        }
        int queryIndex = dbName.indexOf("?");
        if (queryIndex >= 0) {
            dbName = dbName.substring(0, queryIndex);
        }
        int semicolonIndex = dbName.indexOf(";");
        if (semicolonIndex >= 0) {
            dbName = dbName.substring(0, semicolonIndex);
        }
        return dbName.trim();
    }

    private record Endpoint(String host, Integer port, String dbNameFromHost) {
    }
}
