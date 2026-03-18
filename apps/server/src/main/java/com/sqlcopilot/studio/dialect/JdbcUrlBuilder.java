package com.sqlcopilot.studio.dialect;

import com.sqlcopilot.studio.entity.ConnectionEntity;
import com.sqlcopilot.studio.util.BusinessException;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

public final class JdbcUrlBuilder {

    private JdbcUrlBuilder() {
    }

    public static String build(ConnectionEntity entity) {
        String type = normalize(entity.getDbType()).toUpperCase(Locale.ROOT);
        String customParams = toParameterText(resolveRuntimeProperties(entity));
        return switch (type) {
            case "MYSQL" -> {
                Endpoint endpoint = resolveEndpoint(entity.getHost(), entity.getPort(), 3306, "MySQL 主机不能为空");
                String dbName = sanitizeDbName(firstNonBlank(entity.getDatabaseName(), endpoint.dbNameFromHost()));
                String databasePart = dbName.isBlank() ? "" : "/" + dbName;
                String url = String.format(
                    "jdbc:mysql://%s:%d%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8",
                    endpoint.host(),
                    endpoint.port(),
                    databasePart);
                yield appendQueryParameters(url, customParams);
            }
            case "POSTGRESQL" -> {
                Endpoint endpoint = resolveEndpoint(entity.getHost(), entity.getPort(), 5432, "PostgreSQL 主机不能为空");
                String dbName = sanitizeDbName(firstNonBlank(entity.getDatabaseName(), endpoint.dbNameFromHost()));
                if (dbName.isBlank()) {
                    dbName = "postgres";
                }
                String url = String.format(
                    "jdbc:postgresql://%s:%d/%s",
                    endpoint.host(),
                    endpoint.port(),
                    dbName);
                yield appendQueryParameters(url, customParams);
            }
            case "SQLITE" -> appendQueryParameters(
                String.format("jdbc:sqlite:%s", requiredText(entity.getDatabaseName(), "SQLite 数据库文件路径不能为空")),
                customParams
            );
            case "SQLSERVER" -> {
                Endpoint endpoint = resolveEndpoint(entity.getHost(), entity.getPort(), 1433, "SQL Server 主机不能为空");
                String dbName = sanitizeDbName(firstNonBlank(entity.getDatabaseName(), endpoint.dbNameFromHost()));
                String url = String.format(
                    "jdbc:sqlserver://%s:%d%s",
                    endpoint.host(),
                    endpoint.port(),
                    dbName.isBlank() ? "" : ";databaseName=" + dbName);
                yield appendSemicolonParameters(url, customParams);
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

    public static Map<String, String> parseCustomParameters(String rawText) {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        String normalizedText = safe(rawText).replace("\r", "\n");
        if (normalizedText.isBlank()) {
            return params;
        }
        String[] lines = normalizedText.split("\n");
        for (String rawLine : lines) {
            String line = safe(rawLine);
            if (line.isBlank() || line.startsWith("#") || line.startsWith("--")) {
                continue;
            }
            String[] segments = line.split("[&;]");
            for (String rawSegment : segments) {
                String segment = safe(rawSegment);
                if (segment.isBlank()) {
                    continue;
                }
                int delimiterIndex = segment.indexOf('=');
                if (delimiterIndex < 0) {
                    delimiterIndex = segment.indexOf(':');
                }
                String key = delimiterIndex >= 0 ? safe(segment.substring(0, delimiterIndex)) : segment;
                String value = delimiterIndex >= 0 ? safe(segment.substring(delimiterIndex + 1)) : "true";
                if (!key.isBlank()) {
                    params.put(key, value);
                }
            }
        }
        return params;
    }

    public static Map<String, String> resolveRuntimeProperties(ConnectionEntity entity) {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        String type = normalize(entity == null ? null : entity.getDbType()).toUpperCase(Locale.ROOT);
        if ("SQLSERVER".equals(type)) {
            params.put("encrypt", "true");
            params.put("trustServerCertificate", "true");
        }
        params.putAll(parseCustomParameters(entity == null ? null : entity.getCustomParams()));
        return params;
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

    private static String appendQueryParameters(String jdbcUrl, String rawParams) {
        Map<String, String> customParams = parseCustomParameters(rawParams);
        if (customParams.isEmpty()) {
            return jdbcUrl;
        }
        int queryIndex = jdbcUrl.indexOf('?');
        String baseUrl = queryIndex >= 0 ? jdbcUrl.substring(0, queryIndex) : jdbcUrl;
        LinkedHashMap<String, String> mergedParams = new LinkedHashMap<>();
        if (queryIndex >= 0 && queryIndex < jdbcUrl.length() - 1) {
            String query = jdbcUrl.substring(queryIndex + 1);
            String[] entries = query.split("&");
            for (String entry : entries) {
                String normalizedEntry = safe(entry);
                if (normalizedEntry.isBlank()) {
                    continue;
                }
                int delimiterIndex = normalizedEntry.indexOf('=');
                String key = delimiterIndex >= 0 ? urlDecode(normalizedEntry.substring(0, delimiterIndex)) : urlDecode(normalizedEntry);
                String value = delimiterIndex >= 0 ? urlDecode(normalizedEntry.substring(delimiterIndex + 1)) : "";
                if (!key.isBlank()) {
                    mergedParams.put(key, value);
                }
            }
        }
        mergedParams.putAll(customParams);
        StringJoiner joiner = new StringJoiner("&");
        mergedParams.forEach((key, value) -> joiner.add(urlEncode(key) + "=" + urlEncode(value)));
        return baseUrl + "?" + joiner;
    }

    private static String appendSemicolonParameters(String jdbcUrl, String rawParams) {
        Map<String, String> params = parseCustomParameters(rawParams);
        if (params.isEmpty()) {
            return jdbcUrl;
        }
        StringBuilder builder = new StringBuilder(jdbcUrl);
        params.forEach((key, value) -> {
            if (builder.length() > 0 && builder.charAt(builder.length() - 1) != ';') {
                builder.append(';');
            }
            builder.append(key).append('=').append(value);
        });
        return builder.toString();
    }

    private static String toParameterText(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner("\n");
        params.forEach((key, value) -> joiner.add(key + "=" + value));
        return joiner.toString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String safe(String value) {
        return Objects.toString(value, "").trim();
    }

    private record Endpoint(String host, Integer port, String dbNameFromHost) {
    }
}
