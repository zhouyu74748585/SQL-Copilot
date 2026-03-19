package com.sqlcopilot.studio;

import org.mybatis.spring.annotation.MapperScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.security.Security;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.StringJoiner;

@SpringBootApplication(exclude = {
    MongoAutoConfiguration.class,
    MongoDataAutoConfiguration.class,
    MongoRepositoriesAutoConfiguration.class,
    RedisAutoConfiguration.class,
    RedisRepositoriesAutoConfiguration.class,
})
@MapperScan(
    value = "com.sqlcopilot.studio.mapper",
    sqlSessionTemplateRef = "sqlSessionTemplate"
)
@EnableScheduling
public class SqlCopilotApplication {

    private static final Logger log = LoggerFactory.getLogger(SqlCopilotApplication.class);
    private static final String DJL_CACHE_DIR_KEY = "DJL_CACHE_DIR";
    private static final String ENGINE_CACHE_DIR_KEY = "ENGINE_CACHE_DIR";
    private static final String SQLCOPILOT_DJL_CACHE_DIR_KEY = "sqlcopilot.djl.cache-dir";
    private static final String SQLCOPILOT_LEGACY_TLS_ENABLED_KEY = "sqlcopilot.legacy-tls.enabled";
    private static final String JDK_TLS_CLIENT_PROTOCOLS = "jdk.tls.client.protocols";
    private static final String JDK_TLS_DISABLED_ALGORITHMS = "jdk.tls.disabledAlgorithms";

    public static void main(String[] args) {
        configureDjlCacheDir();
        configureLegacyTlsCompatibility();
        SpringApplication.run(SqlCopilotApplication.class, args);
    }

    private static void configureDjlCacheDir() {
        Path explicitPath = resolveExplicitCachePath();
        if (explicitPath != null) {
            if (tryActivateDjlCacheDir(explicitPath)) {
                return;
            }
            log.warn("Configured DJL cache dir is not writable, fallback will be used: {}", explicitPath);
        }

        String localAppData = trimToNull(System.getenv("LOCALAPPDATA"));
        if (localAppData != null && tryActivateDjlCacheDir(Path.of(localAppData, "SQL-Copilot", "djl-cache"))) {
            return;
        }

        String userHome = Objects.toString(System.getProperty("user.home"), "").trim();
        if (!userHome.isEmpty() && tryActivateDjlCacheDir(Path.of(userHome, ".sql-copilot", "djl-cache"))) {
            return;
        }

        String tempDir = Objects.toString(System.getProperty("java.io.tmpdir"), "").trim();
        if (!tempDir.isEmpty() && tryActivateDjlCacheDir(Path.of(tempDir, "sql-copilot", "djl-cache"))) {
            return;
        }

        log.warn("Unable to set a writable DJL cache dir, DJL default path will be used.");
    }

    private static Path resolveExplicitCachePath() {
        String configured = trimToNull(System.getProperty(SQLCOPILOT_DJL_CACHE_DIR_KEY));
        if (configured == null) {
            configured = trimToNull(System.getenv("SQLCOPILOT_DJL_CACHE_DIR"));
        }
        if (configured == null) {
            configured = trimToNull(System.getProperty(DJL_CACHE_DIR_KEY));
        }
        if (configured == null) {
            configured = trimToNull(System.getenv(DJL_CACHE_DIR_KEY));
        }
        return configured == null ? null : Path.of(configured);
    }

    private static boolean tryActivateDjlCacheDir(Path cacheDir) {
        try {
            Path target = cacheDir.toAbsolutePath().normalize();
            Files.createDirectories(target);
            String resolved = target.toString();
            System.setProperty(DJL_CACHE_DIR_KEY, resolved);
            System.setProperty(ENGINE_CACHE_DIR_KEY, resolved);
            log.info("Using DJL cache dir: {}", resolved);
            return true;
        } catch (Exception ex) {
            log.warn("Cannot use DJL cache dir {}: {}", cacheDir, ex.getMessage());
            return false;
        }
    }

    private static void configureLegacyTlsCompatibility() {
        String enabled = trimToNull(System.getProperty(SQLCOPILOT_LEGACY_TLS_ENABLED_KEY));
        if (enabled == null) {
            enabled = trimToNull(System.getenv("SQLCOPILOT_LEGACY_TLS_ENABLED"));
        }
        if ("false".equalsIgnoreCase(enabled)) {
            log.info("Legacy TLS compatibility disabled by config.");
            return;
        }

        String currentProtocols = trimToNull(System.getProperty(JDK_TLS_CLIENT_PROTOCOLS));
        if (currentProtocols == null || !currentProtocols.contains("TLSv1")) {
            System.setProperty(JDK_TLS_CLIENT_PROTOCOLS, "TLSv1,TLSv1.1,TLSv1.2,TLSv1.3");
        }

        String originalDisabledAlgorithms = Objects.toString(Security.getProperty(JDK_TLS_DISABLED_ALGORITHMS), "");
        String relaxedAlgorithms = removeDisabledLegacyTls(originalDisabledAlgorithms);
        if (!Objects.equals(originalDisabledAlgorithms, relaxedAlgorithms)) {
            Security.setProperty(JDK_TLS_DISABLED_ALGORITHMS, relaxedAlgorithms);
        }
        log.warn("Legacy TLS compatibility enabled for old database servers; disable with -D{}=false if not needed.",
            SQLCOPILOT_LEGACY_TLS_ENABLED_KEY);
    }

    private static String removeDisabledLegacyTls(String originalValue) {
        if (originalValue == null || originalValue.isBlank()) {
            return Objects.toString(originalValue, "");
        }
        StringJoiner joiner = new StringJoiner(", ");
        for (String item : originalValue.split(",")) {
            String normalized = item == null ? "" : item.trim();
            if ("TLSv1".equalsIgnoreCase(normalized) || "TLSv1.1".equalsIgnoreCase(normalized)) {
                continue;
            }
            if (!normalized.isEmpty()) {
                joiner.add(normalized);
            }
        }
        return joiner.toString();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
