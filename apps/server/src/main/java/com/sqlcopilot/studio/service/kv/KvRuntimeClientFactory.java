package com.sqlcopilot.studio.service.kv;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.sqlcopilot.studio.entity.ConnectionEntity;
import com.sqlcopilot.studio.support.ssh.SshTunnelManager;
import com.sqlcopilot.studio.util.BusinessException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.api.StatefulRedisConnection;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * KV 运行时连接工厂：统一处理 MongoDB / Redis 的直连与 SSH 隧道接入。
 */
@Component
public class KvRuntimeClientFactory {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final SshTunnelManager sshTunnelManager;

    public KvRuntimeClientFactory(SshTunnelManager sshTunnelManager) {
        this.sshTunnelManager = sshTunnelManager;
    }

    public <T> T withMongoClient(ConnectionEntity entity, Long runtimeId, MongoClientCallback<T> callback) {
        RuntimeEndpoint endpoint = resolveRuntimeEndpoint(entity, runtimeId);
        try (MongoClient client = createMongoClient(entity, endpoint.host(), endpoint.port())) {
            return callback.execute(client);
        } finally {
            endpoint.closeQuietly();
        }
    }

    public <T> T withRedisConnection(ConnectionEntity entity, Long runtimeId, RedisConnectionCallback<T> callback) {
        return withRedisConnection(entity, runtimeId, null, callback);
    }

    public List<String> listRedisDatabases(ConnectionEntity entity, Long runtimeId) {
        int configuredIndex = parseRedisDatabaseQuietly(entity == null ? null : entity.getDatabaseName());
        int fallbackCount = Math.max(configuredIndex + 1, 16);
        return withRedisConnection(entity, runtimeId, "0", connection -> {
            int databaseCount = resolveRedisDatabaseCount(connection.sync(), fallbackCount);
            List<String> result = new ArrayList<>(databaseCount);
            for (int index = 0; index < databaseCount; index++) {
                result.add(String.valueOf(index));
            }
            return result;
        });
    }

    public <T> T withRedisConnection(ConnectionEntity entity,
                                     Long runtimeId,
                                     String databaseNameOverride,
                                     RedisConnectionCallback<T> callback) {
        RuntimeEndpoint endpoint = resolveRuntimeEndpoint(entity, runtimeId);
        RedisClient client = null;
        StatefulRedisConnection<String, String> connection = null;
        try {
            client = RedisClient.create(buildRedisUri(entity, endpoint.host(), endpoint.port(), databaseNameOverride));
            connection = client.connect();
            return callback.execute(connection);
        } finally {
            if (connection != null) {
                connection.close();
            }
            if (client != null) {
                client.shutdown();
            }
            endpoint.closeQuietly();
        }
    }

    private MongoClient createMongoClient(ConnectionEntity entity, String host, int port) {
        MongoClientSettings.Builder builder = MongoClientSettings.builder()
            .applyToSocketSettings(settings -> {
                settings.connectTimeout((int) DEFAULT_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                settings.readTimeout((int) DEFAULT_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            })
            .applyToClusterSettings(settings -> settings.hosts(List.of(new ServerAddress(host, port))));

        String username = safe(entity.getUsername());
        if (!username.isBlank()) {
            String authDb = safe(entity.getDatabaseName());
            if (authDb.isBlank()) {
                authDb = "admin";
            }
            builder.credential(MongoCredential.createCredential(username, authDb, safe(entity.getPassword()).toCharArray()));
        }
        return MongoClients.create(builder.build());
    }

    private RedisURI buildRedisUri(ConnectionEntity entity, String host, int port, String databaseNameOverride) {
        RedisURI.Builder builder = RedisURI.builder()
            .withHost(host)
            .withPort(port)
            .withTimeout(DEFAULT_TIMEOUT)
            .withDatabase(parseRedisDatabase(Objects.requireNonNullElse(databaseNameOverride, entity.getDatabaseName())));
        String username = safe(entity.getUsername());
        String password = safe(entity.getPassword());
        if (!username.isBlank()) {
            builder.withAuthentication(username, password);
        } else if (!password.isBlank()) {
            builder.withPassword(password.toCharArray());
        }
        return builder.build();
    }

    private int parseRedisDatabase(String databaseName) {
        String text = safe(databaseName);
        if (text.isBlank()) {
            return 0;
        }
        try {
            int index = Integer.parseInt(text);
            if (index < 0) {
                throw new NumberFormatException("negative");
            }
            return index;
        } catch (NumberFormatException ex) {
            throw new BusinessException(400, "Redis logical db 必须是非负整数");
        }
    }

    private int parseRedisDatabaseQuietly(String databaseName) {
        String text = safe(databaseName);
        if (text.isBlank()) {
            return 0;
        }
        try {
            int index = Integer.parseInt(text);
            return Math.max(index, 0);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private int resolveRedisDatabaseCount(RedisCommands<String, String> commands, int fallbackCount) {
        try {
            String value = safe(commands.configGet("databases").get("databases"));
            if (!value.isBlank()) {
                int parsed = Integer.parseInt(value);
                if (parsed > 0) {
                    return parsed;
                }
            }
        } catch (Exception ignored) {
            // 兼容禁用 CONFIG GET 的托管 Redis，回退到默认库范围。
        }
        return fallbackCount;
    }

    private RuntimeEndpoint resolveRuntimeEndpoint(ConnectionEntity entity, Long runtimeId) {
        Endpoint endpoint = parseEndpoint(entity);
        if (!isSshEnabled(entity)) {
            return new RuntimeEndpoint(endpoint.host(), endpoint.port(), null);
        }
        if (runtimeId != null && runtimeId > 0) {
            SshTunnelManager.TunnelEndpoint tunnelEndpoint = sshTunnelManager.ensureTunnel(runtimeId, entity);
            return new RuntimeEndpoint(tunnelEndpoint.host(), tunnelEndpoint.port(), null);
        }
        SshTunnelManager.TunnelSession session = sshTunnelManager.openEphemeralTunnel(entity);
        return new RuntimeEndpoint(session.endpoint().host(), session.endpoint().port(), session);
    }

    private Endpoint parseEndpoint(ConnectionEntity entity) {
        String rawHost = safe(entity.getHost());
        if (rawHost.isBlank()) {
            throw new BusinessException(400, "数据库主机不能为空");
        }
        String host = rawHost;
        int protocolMarker = host.indexOf("://");
        if (protocolMarker >= 0) {
            host = host.substring(protocolMarker + 3);
        }
        int slashIndex = host.indexOf("/");
        if (slashIndex >= 0) {
            host = host.substring(0, slashIndex);
        }
        int atIndex = host.lastIndexOf("@");
        if (atIndex >= 0 && atIndex < host.length() - 1) {
            host = host.substring(atIndex + 1);
        }
        Integer port = entity.getPort();
        int colonIndex = host.lastIndexOf(':');
        if (colonIndex > 0 && colonIndex < host.length() - 1) {
            String maybePort = host.substring(colonIndex + 1);
            if (maybePort.chars().allMatch(Character::isDigit)) {
                if (port == null || port <= 0) {
                    port = Integer.parseInt(maybePort);
                }
                host = host.substring(0, colonIndex);
            }
        }
        if (host.isBlank()) {
            throw new BusinessException(400, "数据库主机不能为空");
        }
        int actualPort = port == null || port <= 0 ? defaultPort(entity.getDbType()) : port;
        if (actualPort <= 0 || actualPort > 65535) {
            throw new BusinessException(400, "数据库端口必须在 1-65535 之间");
        }
        return new Endpoint(host, actualPort);
    }

    private int defaultPort(String dbType) {
        String normalized = safe(dbType).toUpperCase(Locale.ROOT);
        if ("MONGODB".equals(normalized)) {
            return 27017;
        }
        if ("REDIS".equals(normalized)) {
            return 6379;
        }
        return 0;
    }

    private boolean isSshEnabled(ConnectionEntity entity) {
        return entity != null && entity.getSshEnabled() != null && entity.getSshEnabled() == 1;
    }

    private String safe(String value) {
        return Objects.toString(value, "").trim();
    }

    public interface MongoClientCallback<T> {
        T execute(MongoClient client);
    }

    public interface RedisConnectionCallback<T> {
        T execute(StatefulRedisConnection<String, String> connection);
    }

    private record Endpoint(String host, int port) {
    }

    private record RuntimeEndpoint(String host, int port, SshTunnelManager.TunnelSession session) {
        private void closeQuietly() {
            if (session != null) {
                session.close();
            }
        }
    }
}
