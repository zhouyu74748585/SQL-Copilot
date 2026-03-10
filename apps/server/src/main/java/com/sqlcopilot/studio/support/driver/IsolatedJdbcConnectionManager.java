package com.sqlcopilot.studio.support.driver;

import com.sqlcopilot.studio.entity.ConnectionEntity;
import com.sqlcopilot.studio.support.JdbcDriverResolver;
import com.sqlcopilot.studio.support.JdbcDriverResolver.ResolvedDriver;
import com.sqlcopilot.studio.util.BusinessException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 连接隔离管理器：每个连接 ID 维护独立驱动上下文，避免不同连接间驱动冲突。
 */
@Component
public class IsolatedJdbcConnectionManager {

    private final JdbcDriverResolver jdbcDriverResolver;
    private final ConcurrentHashMap<Long, DriverSession> sessionByConnectionId = new ConcurrentHashMap<>();

    public IsolatedJdbcConnectionManager(JdbcDriverResolver jdbcDriverResolver) {
        this.jdbcDriverResolver = jdbcDriverResolver;
    }

    public Connection open(ConnectionEntity entity, String jdbcUrl, String username, String password) throws SQLException {
        if (entity.getId() == null) {
            throw new BusinessException(400, "连接 ID 不能为空，无法建立隔离上下文");
        }
        ResolvedDriver resolved = jdbcDriverResolver.resolve(entity.getDbType(), null);
        DriverSession session = ensureSession(entity.getId(), resolved);

        Properties properties = new Properties();
        if (username != null && !username.isBlank()) {
            properties.setProperty("user", username);
        }
        if (password != null && !password.isBlank()) {
            properties.setProperty("password", password);
        }

        ClassLoader previousCl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(session.classLoader());
        try {
            Connection connection = session.driver().connect(jdbcUrl, properties);
            if (connection == null) {
                throw new SQLException("驱动不接受该连接串: " + jdbcUrl);
            }
            return connection;
        } finally {
            Thread.currentThread().setContextClassLoader(previousCl);
        }
    }

    public void release(Long connectionId) {
        DriverSession session = sessionByConnectionId.remove(connectionId);
        if (session != null) {
            session.closeQuietly();
        }
    }

    private DriverSession ensureSession(Long connectionId, ResolvedDriver resolved) throws SQLException {
        DriverSession existing = sessionByConnectionId.get(connectionId);
        if (existing != null && existing.matches(resolved)) {
            return existing;
        }
        synchronized (this) {
            existing = sessionByConnectionId.get(connectionId);
            if (existing != null && existing.matches(resolved)) {
                return existing;
            }
            if (existing != null) {
                existing.closeQuietly();
            }
            DriverSession created = createSession(resolved);
            sessionByConnectionId.put(connectionId, created);
            return created;
        }
    }

    /**
     * 内置支持的 JDBC 驱动优先直接从应用 classpath 实例化；
     * 只有未知扩展驱动才回退到资源 jar + 隔离类加载器。
     */
    private DriverSession createSession(ResolvedDriver resolved) throws SQLException {
        ClassLoader applicationClassLoader = Thread.currentThread().getContextClassLoader();

        Driver bundledDriver = BundledJdbcDriverFactory.createIfSupported(resolved.driverClass());
        if (bundledDriver != null) {
            return new DriverSession(resolved, bundledDriver, applicationClassLoader, null);
        }

        try {
            ClassPathResource resource = new ClassPathResource(resolved.resourcePath());
            ClassLoader classLoader = applicationClassLoader;
            URLClassLoader isolatedLoader = null;
            if (resource.exists()) {
                try {
                    Path tempJar = Files.createTempFile("sql-copilot-driver-", ".jar");
                    try (InputStream in = resource.getInputStream()) {
                        Files.copy(in, tempJar, StandardCopyOption.REPLACE_EXISTING);
                    }
                    URL url = tempJar.toUri().toURL();
                    isolatedLoader = new URLClassLoader(new URL[]{url}, ClassLoader.getPlatformClassLoader());
                    classLoader = isolatedLoader;
                } catch (IOException ex) {
                    throw new SQLException("加载隔离驱动包失败: " + ex.getMessage(), ex);
                }
            }
            Driver driver = instantiateDriverReflectively(resolved.driverClass(), classLoader);
            return new DriverSession(resolved, driver, classLoader, isolatedLoader);
        } catch (Exception ex) {
            throw new SQLException("初始化驱动失败: " + resolved.driverClass() + "，" + ex.getMessage(), ex);
        }
    }

    private java.sql.Driver instantiateDriverReflectively(String driverClassName, ClassLoader classLoader) throws Exception {
        Class<?> driverClass = Class.forName(driverClassName, true, classLoader);
        Object instance = driverClass.getDeclaredConstructor().newInstance();
        if (instance instanceof java.sql.Driver driver) {
            return driver;
        }
        throw new SQLException("驱动类未实现 java.sql.Driver: " + driverClassName);
    }

    private record DriverSession(ResolvedDriver resolved,
                                 Driver driver,
                                 ClassLoader classLoader,
                                 URLClassLoader isolatedLoader) {

        private boolean matches(ResolvedDriver target) {
            return Objects.equals(resolved.dbType(), target.dbType())
                && Objects.equals(resolved.version(), target.version())
                && Objects.equals(resolved.driverClass(), target.driverClass())
                && Objects.equals(resolved.resourcePath(), target.resourcePath());
        }

        private void closeQuietly() {
            if (isolatedLoader == null) {
                return;
            }
            try {
                isolatedLoader.close();
            } catch (IOException ignored) {
                // 忽略驱动类加载器释放异常，避免影响主流程。
            }
        }
    }
}
