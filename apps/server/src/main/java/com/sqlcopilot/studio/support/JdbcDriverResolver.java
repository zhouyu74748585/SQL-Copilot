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
                DriverSpec spec = parseSpec(node);
                if (spec != null) {
                    result.put(type, spec);
                }
            }
            return result;
        } catch (Exception ex) {
            throw new BusinessException(500, "加载 jdbc-drivers.yml 失败: " + ex.getMessage());
        }
    }

    private DriverSpec parseSpec(Map<?, ?> node) {
        String defaultVersion = normalizeVersion(node.get("defaultVersion"));
        String defaultDriver = trimText(node.get("defaultDriver"));
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

        return new DriverSpec(defaultVersion, defaultDriver, resourcePattern, driversByVersion, aliases);
    }

    private String normalizeType(String value) {
        return trimText(value).toUpperCase(Locale.ROOT);
    }

    private String normalizeVersion(Object value) {
        return trimText(value).toLowerCase(Locale.ROOT);
    }

    private String trimText(Object value) {
        return Objects.toString(value, "").trim();
    }

    private static final class DriverSpec {
        private final String defaultVersion;
        private final String defaultDriver;
        private final String resourcePattern;
        private final Map<String, String> driversByVersion;
        private final Map<String, String> resourceAliases;

        private DriverSpec(String defaultVersion,
                           String defaultDriver,
                           String resourcePattern,
                           Map<String, String> driversByVersion,
                           Map<String, String> resourceAliases) {
            this.defaultVersion = defaultVersion;
            this.defaultDriver = defaultDriver;
            this.resourcePattern = resourcePattern;
            this.driversByVersion = driversByVersion;
            this.resourceAliases = resourceAliases;
        }
    }

    public record ResolvedDriver(String dbType,
                                 String version,
                                 String driverClass,
                                 String resourcePath) {
    }
}
