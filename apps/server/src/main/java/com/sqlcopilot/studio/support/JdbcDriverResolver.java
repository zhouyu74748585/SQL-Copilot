package com.sqlcopilot.studio.support;

import com.sqlcopilot.studio.util.BusinessException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.*;

/**
 * JDBC 驱动解析器：从 jdbc-drivers.yml 读取数据库类型与驱动映射。
 */
@Component
public class JdbcDriverResolver {

    private static final String DEFAULT_RESOURCE_PATTERN = "drivers/{type}/{version}/driver.jar";
    private static final String INTROSPECTION_SCHEMAS = "schemas";
    private static final String INTROSPECTION_TABLES = "tables";
    private static final String INTROSPECTION_COLUMNS = "columns";
    private static final String INTROSPECTION_PRIMARY_KEYS = "primaryKeys";
    private final Map<String, DriverSpec> specs;

    public JdbcDriverResolver() {
        this.specs = loadSpecs();
    }

    public ResolvedDriver resolve(String dbType, String requestedVersion) {
        String type = normalizeType(dbType);
        DriverSpec spec = specs.get(type);
        if (spec == null) {
            throw new BusinessException(400, "未配置数据库驱动映射: " + dbType + "，请检查 jdbc-drivers.yml");
        }
        String version = normalizeVersion(requestedVersion);
        if (version.isBlank()) {
            version = spec.defaultVersion;
        }

        String driverClass = spec.driversByVersion.getOrDefault(version, spec.defaultDriver);
        if (driverClass == null || driverClass.isBlank()) {
            throw new BusinessException(400, "未找到驱动类映射: " + dbType + " version=" + version);
        }

        String resourceVersion = spec.resourceAliases.getOrDefault(version, version);
        String resourcePath = spec.resourcePattern
            .replace("{type}", type.toLowerCase(Locale.ROOT))
            .replace("{version}", resourceVersion);

        return new ResolvedDriver(type, version, driverClass, resourcePath);
    }

    public String findSchemasSql(String dbType) {
        return findIntrospectionSql(dbType, INTROSPECTION_SCHEMAS);
    }

    public List<SupportedDbTypeSpec> listSupportedDbTypes() {
        List<SupportedDbTypeSpec> result = new ArrayList<>();
        for (Map.Entry<String, DriverSpec> entry : specs.entrySet()) {
            DriverSpec spec = entry.getValue();
            result.add(new SupportedDbTypeSpec(
                entry.getKey(),
                spec.displayName,
                spec.defaultPort,
                spec.supportsSelectedDatabases
            ));
        }
        return result;
    }

    public boolean supportsSelectedDatabases(String dbType) {
        String type = normalizeType(dbType);
        DriverSpec spec = specs.get(type);
        return spec != null && spec.supportsSelectedDatabases;
    }

    public String findTablesSql(String dbType) {
        return findIntrospectionSql(dbType, INTROSPECTION_TABLES);
    }

    public String findColumnsSql(String dbType) {
        return findIntrospectionSql(dbType, INTROSPECTION_COLUMNS);
    }

    public String findPrimaryKeysSql(String dbType) {
        return findIntrospectionSql(dbType, INTROSPECTION_PRIMARY_KEYS);
    }

    public CreateTableSpec findCreateTableSpec(String dbType) {
        String type = normalizeType(dbType);
        DriverSpec spec = specs.get(type);
        return spec == null ? null : spec.createTableSpec;
    }

    public TableCopyFastPathSpec findTableCopyFastPathSpec(String dbType) {
        String type = normalizeType(dbType);
        DriverSpec spec = specs.get(type);
        return spec == null ? null : spec.tableCopyFastPathSpec;
    }

    public String findRenameTableSql(String dbType) {
        String type = normalizeType(dbType);
        DriverSpec spec = specs.get(type);
        if (spec == null || spec.tableOperationSpec == null) {
            return "";
        }
        return trimText(spec.tableOperationSpec.renameTableSql());
    }

    public ObjectDefinitionSpec findObjectDefinitionSpec(String dbType, String objectType) {
        String type = normalizeType(dbType);
        DriverSpec spec = specs.get(type);
        if (spec == null || spec.objectDefinitionSpecMap.isEmpty()) {
            return null;
        }
        String key = normalizeObjectType(objectType);
        return spec.objectDefinitionSpecMap.get(key);
    }

    private Map<String, DriverSpec> loadSpecs() {
        ClassPathResource resource = new ClassPathResource("jdbc-drivers.yml");
        if (!resource.exists()) {
            return Collections.emptyMap();
        }
        try (InputStream input = resource.getInputStream()) {
            Yaml yaml = new Yaml();
            Object root = yaml.load(input);
            if (!(root instanceof Map<?, ?> rootMap)) {
                return Collections.emptyMap();
            }
            Object driversNode = rootMap.get("drivers");
            if (!(driversNode instanceof Map<?, ?> driversMap)) {
                return Collections.emptyMap();
            }

            Map<String, DriverSpec> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : driversMap.entrySet()) {
                String type = normalizeType(String.valueOf(entry.getKey()));
                if (!(entry.getValue() instanceof Map<?, ?> node)) {
                    continue;
                }
                DriverSpec spec = parseSpec(type, node);
                if (spec != null) {
                    result.put(type, spec);
                }
            }
            return result;
        } catch (Exception ex) {
            throw new BusinessException(500, "加载 jdbc-drivers.yml 失败: " + ex.getMessage());
        }
    }

    private DriverSpec parseSpec(String type, Map<?, ?> node) {
        String defaultVersion = normalizeVersion(node.get("defaultVersion"));
        String defaultDriver = trimText(node.get("defaultDriver"));
        String displayName = trimText(node.get("displayName"));
        Integer defaultPort = parseInteger(node.get("defaultPort"));
        boolean supportsSelectedDatabases = parseBoolean(node.get("supportsSelectedDatabases"));
        String resourcePattern = trimText(node.get("resourcePattern"));
        if (resourcePattern.isBlank()) {
            resourcePattern = DEFAULT_RESOURCE_PATTERN;
        }
        if (defaultVersion.isBlank() || defaultDriver.isBlank()) {
            return null;
        }

        Map<String, String> driversByVersion = new LinkedHashMap<>();
        Object versionNode = node.get("driversByVersion");
        if (versionNode instanceof Map<?, ?> versionMap) {
            for (Map.Entry<?, ?> entry : versionMap.entrySet()) {
                String key = normalizeVersion(entry.getKey());
                String value = trimText(entry.getValue());
                if (!key.isBlank() && !value.isBlank()) {
                    driversByVersion.put(key, value);
                }
            }
        }
        driversByVersion.putIfAbsent(defaultVersion, defaultDriver);

        Map<String, String> aliases = new LinkedHashMap<>();
        Object aliasNode = node.get("resourceAliases");
        if (aliasNode instanceof Map<?, ?> aliasMap) {
            for (Map.Entry<?, ?> entry : aliasMap.entrySet()) {
                String key = normalizeVersion(entry.getKey());
                String value = trimText(entry.getValue());
                if (!key.isBlank() && !value.isBlank()) {
                    aliases.put(key, value);
                }
            }
        }
        aliases.putIfAbsent(defaultVersion, defaultVersion);

        Map<String, String> introspectionSqlMap = parseStringMap(node.get("introspection"));
        CreateTableSpec createTableSpec = parseCreateTableSpec(node.get("tableCopy"));
        TableCopyFastPathSpec tableCopyFastPathSpec = parseTableCopyFastPathSpec(node.get("tableCopy"));
        TableOperationSpec tableOperationSpec = parseTableOperationSpec(node.get("tableOperations"));
        Map<String, ObjectDefinitionSpec> objectDefinitionSpecMap = parseObjectDefinitionSpecMap(node.get("objectDefinitions"));
        return new DriverSpec(
            displayName.isBlank() ? type : displayName,
            defaultPort,
            supportsSelectedDatabases,
            defaultVersion,
            defaultDriver,
            resourcePattern,
            driversByVersion,
            aliases,
            introspectionSqlMap,
            createTableSpec,
            tableCopyFastPathSpec,
            tableOperationSpec,
            objectDefinitionSpecMap
        );
    }

    private CreateTableSpec parseCreateTableSpec(Object node) {
        if (!(node instanceof Map<?, ?> tableCopyMap)) {
            return null;
        }
        String sql = trimText(tableCopyMap.get("createTableSql"));
        if (sql.isBlank()) {
            return null;
        }
        String ddlColumnLabel = trimText(tableCopyMap.get("ddlColumnLabel"));
        Integer ddlColumnIndex = parseInteger(tableCopyMap.get("ddlColumnIndex"));
        return new CreateTableSpec(sql, ddlColumnLabel, ddlColumnIndex == null || ddlColumnIndex <= 0 ? 1 : ddlColumnIndex);
    }

    private TableCopyFastPathSpec parseTableCopyFastPathSpec(Object node) {
        if (!(node instanceof Map<?, ?> tableCopyMap)) {
            return null;
        }
        return new TableCopyFastPathSpec(
            trimText(tableCopyMap.get("structureOnlySameDatabaseSql")),
            trimText(tableCopyMap.get("structureAndDataSameDatabaseSql")),
            trimText(tableCopyMap.get("structureOnlyCrossDatabaseSql")),
            trimText(tableCopyMap.get("structureAndDataCrossDatabaseSql"))
        );
    }

    private TableOperationSpec parseTableOperationSpec(Object node) {
        if (!(node instanceof Map<?, ?> operationMap)) {
            return null;
        }
        String renameTableSql = trimText(operationMap.get("renameTableSql"));
        if (renameTableSql.isBlank()) {
            return null;
        }
        return new TableOperationSpec(renameTableSql);
    }

    private Map<String, ObjectDefinitionSpec> parseObjectDefinitionSpecMap(Object node) {
        Map<String, ObjectDefinitionSpec> result = new LinkedHashMap<>();
        if (!(node instanceof Map<?, ?> definitionMap)) {
            return result;
        }
        for (Map.Entry<?, ?> entry : definitionMap.entrySet()) {
            String objectType = normalizeObjectType(Objects.toString(entry.getKey(), ""));
            if (objectType.isBlank() || !(entry.getValue() instanceof Map<?, ?> valueMap)) {
                continue;
            }
            String fetchSql = trimText(valueMap.get("fetchSql"));
            String saveStrategy = trimText(valueMap.get("saveStrategy"));
            String replaceSql = trimText(valueMap.get("replaceSql"));
            String dropSql = trimText(valueMap.get("dropSql"));
            String fetchColumnLabel = trimText(valueMap.get("fetchColumnLabel"));
            Integer fetchColumnIndex = parseInteger(valueMap.get("fetchColumnIndex"));
            if (fetchSql.isBlank() || replaceSql.isBlank()) {
                continue;
            }
            result.put(objectType, new ObjectDefinitionSpec(
                fetchSql,
                fetchColumnLabel,
                fetchColumnIndex == null || fetchColumnIndex <= 0 ? 1 : fetchColumnIndex,
                saveStrategy.isBlank() ? "REPLACE" : saveStrategy,
                replaceSql,
                dropSql
            ));
        }
        return result;
    }

    private Map<String, String> parseStringMap(Object node) {
        Map<String, String> result = new LinkedHashMap<>();
        if (!(node instanceof Map<?, ?> valueMap)) {
            return result;
        }
        for (Map.Entry<?, ?> entry : valueMap.entrySet()) {
            String key = trimText(entry.getKey());
            String value = trimText(entry.getValue());
            if (!key.isBlank() && !value.isBlank()) {
                result.put(key, value);
            }
        }
        return result;
    }

    private String findIntrospectionSql(String dbType, String key) {
        String type = normalizeType(dbType);
        DriverSpec spec = specs.get(type);
        if (spec == null) {
            return "";
        }
        return trimText(spec.introspectionSqlMap.get(key));
    }

    private Integer parseInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private boolean parseBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String normalized = trimText(value).toLowerCase(Locale.ROOT);
        return "true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized);
    }

    private String normalizeType(String value) {
        return trimText(value).toUpperCase(Locale.ROOT);
    }

    private String normalizeObjectType(String value) {
        String normalized = trimText(value).toLowerCase(Locale.ROOT);
        if (normalized.endsWith("s")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String normalizeVersion(Object value) {
        return trimText(value).toLowerCase(Locale.ROOT);
    }

    private String trimText(Object value) {
        return Objects.toString(value, "").trim();
    }

    private static final class DriverSpec {
        private final String displayName;
        private final Integer defaultPort;
        private final boolean supportsSelectedDatabases;
        private final String defaultVersion;
        private final String defaultDriver;
        private final String resourcePattern;
        private final Map<String, String> driversByVersion;
        private final Map<String, String> resourceAliases;
        private final Map<String, String> introspectionSqlMap;
        private final CreateTableSpec createTableSpec;
        private final TableCopyFastPathSpec tableCopyFastPathSpec;
        private final TableOperationSpec tableOperationSpec;
        private final Map<String, ObjectDefinitionSpec> objectDefinitionSpecMap;

        private DriverSpec(String displayName,
                           Integer defaultPort,
                           boolean supportsSelectedDatabases,
                           String defaultVersion,
                           String defaultDriver,
                           String resourcePattern,
                           Map<String, String> driversByVersion,
                           Map<String, String> resourceAliases,
                           Map<String, String> introspectionSqlMap,
                           CreateTableSpec createTableSpec,
                           TableCopyFastPathSpec tableCopyFastPathSpec,
                           TableOperationSpec tableOperationSpec,
                           Map<String, ObjectDefinitionSpec> objectDefinitionSpecMap) {
            this.displayName = displayName;
            this.defaultPort = defaultPort;
            this.supportsSelectedDatabases = supportsSelectedDatabases;
            this.defaultVersion = defaultVersion;
            this.defaultDriver = defaultDriver;
            this.resourcePattern = resourcePattern;
            this.driversByVersion = driversByVersion;
            this.resourceAliases = resourceAliases;
            this.introspectionSqlMap = introspectionSqlMap;
            this.createTableSpec = createTableSpec;
            this.tableCopyFastPathSpec = tableCopyFastPathSpec;
            this.tableOperationSpec = tableOperationSpec;
            this.objectDefinitionSpecMap = objectDefinitionSpecMap;
        }
    }

    public record CreateTableSpec(String sql,
                                  String ddlColumnLabel,
                                  Integer ddlColumnIndex) {
    }

    public record TableCopyFastPathSpec(String structureOnlySameDatabaseSql,
                                        String structureAndDataSameDatabaseSql,
                                        String structureOnlyCrossDatabaseSql,
                                        String structureAndDataCrossDatabaseSql) {
    }

    public record TableOperationSpec(String renameTableSql) {
    }

    public record ObjectDefinitionSpec(String fetchSql,
                                       String fetchColumnLabel,
                                       Integer fetchColumnIndex,
                                       String saveStrategy,
                                       String replaceSql,
                                       String dropSql) {
    }

    public record SupportedDbTypeSpec(String dbType,
                                      String displayName,
                                      Integer defaultPort,
                                      boolean supportsSelectedDatabases) {
    }

    public record ResolvedDriver(String dbType,
                                 String version,
                                 String driverClass,
                                 String resourcePath) {
    }
}
