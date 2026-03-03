package com.sqlcopilot.studio;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.mybatis.spring.annotation.MapperScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.sqlcopilot.studio.mapper")
@EnableScheduling
public class SqlCopilotApplication {

    private static final Logger log = LoggerFactory.getLogger(SqlCopilotApplication.class);
    private static final String DJL_CACHE_DIR_KEY = "DJL_CACHE_DIR";
    private static final String ENGINE_CACHE_DIR_KEY = "ENGINE_CACHE_DIR";
    private static final String SQLCOPILOT_DJL_CACHE_DIR_KEY = "sqlcopilot.djl.cache-dir";

    public static void main(String[] args) {
        configureDjlCacheDir();
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

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
