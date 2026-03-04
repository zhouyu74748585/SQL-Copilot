package com.sqlcopilot.studio.support.ssh;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.sqlcopilot.studio.entity.ConnectionEntity;
import com.sqlcopilot.studio.util.BusinessException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * SSH 隧道管理器：按连接 ID 复用会话，避免重复建隧道。
 */
@Component
public class SshTunnelManager {

    private static final int CONNECT_TIMEOUT_MS = 12_000;
    private static final String SSH_AUTH_PASSWORD = "SSH_PASSWORD";
    private static final String SSH_AUTH_KEY_PATH = "SSH_KEY_PATH";
    private static final String SSH_AUTH_KEY_TEXT = "SSH_KEY_TEXT";

    private final ConcurrentHashMap<Long, TunnelSession> sessionByConnectionId = new ConcurrentHashMap<>();

    public TunnelEndpoint ensureTunnel(Long connectionId, ConnectionEntity entity) {
        if (connectionId == null) {
            throw new BusinessException(400, "连接 ID 不能为空，无法复用 SSH 隧道");
        }
        TunnelSpec spec = buildTunnelSpec(entity);
        TunnelSession existing = sessionByConnectionId.get(connectionId);
        if (existing != null && existing.matches(spec)) {
            return existing.endpoint();
        }
        synchronized (this) {
            existing = sessionByConnectionId.get(connectionId);
            if (existing != null && existing.matches(spec)) {
                return existing.endpoint();
            }
            if (existing != null) {
                existing.close();
            }
            TunnelSession created = openTunnel(spec);
            sessionByConnectionId.put(connectionId, created);
            return created.endpoint();
        }
    }

    public TunnelSession openEphemeralTunnel(ConnectionEntity entity) {
        return openTunnel(buildTunnelSpec(entity));
    }

    public void release(Long connectionId) {
        if (connectionId == null) {
            return;
        }
        TunnelSession session = sessionByConnectionId.remove(connectionId);
        if (session != null) {
            session.close();
        }
    }

    private TunnelSession openTunnel(TunnelSpec spec) {
        Session session = null;
        Path keyTempFile = null;
        try {
            JSch jSch = new JSch();
            String passphrase = normalize(spec.sshPrivateKeyPassphrase());
            byte[] passphraseBytes = passphrase.isBlank() ? null : passphrase.getBytes(StandardCharsets.UTF_8);
            if (SSH_AUTH_KEY_PATH.equals(spec.sshAuthType())) {
                jSch.addIdentity(spec.sshPrivateKeyPath(), passphraseBytes);
            } else if (SSH_AUTH_KEY_TEXT.equals(spec.sshAuthType())) {
                keyTempFile = createPrivateKeyTempFile(spec.sshPrivateKeyText());
                jSch.addIdentity(keyTempFile.toString(), passphraseBytes);
            }
            session = jSch.getSession(spec.sshUser(), spec.sshHost(), spec.sshPort());
            if (SSH_AUTH_PASSWORD.equals(spec.sshAuthType())) {
                session.setPassword(spec.sshPassword());
                session.setConfig("PreferredAuthentications", "password");
            } else {
                session.setConfig("PreferredAuthentications", "publickey");
            }
            // 关键配置：按既定策略启用宽松指纹校验。
            session.setConfig("StrictHostKeyChecking", "no");
            session.connect(CONNECT_TIMEOUT_MS);
            int localPort = session.setPortForwardingL(0, spec.targetHost(), spec.targetPort());
            return new TunnelSession(spec, session, new TunnelEndpoint("127.0.0.1", localPort), keyTempFile);
        } catch (Exception ex) {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
            deleteQuietly(keyTempFile);
            throw new BusinessException(500, "SSH 隧道建立失败: " + ex.getMessage());
        }
    }

    private TunnelSpec buildTunnelSpec(ConnectionEntity entity) {
        String sshHost = normalize(entity.getSshHost());
        String sshUser = normalize(entity.getSshUser());
        int sshPort = validPort(entity.getSshPort(), 22, "SSH 端口必须在 1-65535 之间");
        if (sshHost.isBlank()) {
            throw new BusinessException(400, "SSH 主机不能为空");
        }
        if (sshUser.isBlank()) {
            throw new BusinessException(400, "SSH 用户名不能为空");
        }
        String sshAuthType = normalize(entity.getSshAuthType()).toUpperCase(Locale.ROOT);
        if (sshAuthType.isBlank()) {
            sshAuthType = SSH_AUTH_PASSWORD;
        }
        String sshPassword = normalize(entity.getSshPassword());
        String sshPrivateKeyPath = normalize(entity.getSshPrivateKeyPath());
        String sshPrivateKeyText = normalize(entity.getSshPrivateKeyText());
        String sshPrivateKeyPassphrase = normalize(entity.getSshPrivateKeyPassphrase());

        if (SSH_AUTH_PASSWORD.equals(sshAuthType)) {
            if (sshPassword.isBlank()) {
                throw new BusinessException(400, "SSH 密码认证模式下 sshPassword 不能为空");
            }
        } else if (SSH_AUTH_KEY_PATH.equals(sshAuthType)) {
            if (sshPrivateKeyPath.isBlank()) {
                throw new BusinessException(400, "SSH 私钥路径模式下 sshPrivateKeyPath 不能为空");
            }
        } else if (SSH_AUTH_KEY_TEXT.equals(sshAuthType)) {
            if (sshPrivateKeyText.isBlank()) {
                throw new BusinessException(400, "SSH 私钥文本模式下 sshPrivateKeyText 不能为空");
            }
        } else {
            throw new BusinessException(400, "不支持的 SSH 认证模式: " + sshAuthType);
        }

        Endpoint endpoint = resolveTargetEndpoint(entity);
        return new TunnelSpec(
            sshHost,
            sshPort,
            sshUser,
            sshAuthType,
            sshPassword,
            sshPrivateKeyPath,
            sshPrivateKeyText,
            sshPrivateKeyPassphrase,
            endpoint.host(),
            endpoint.port()
        );
    }

    private Endpoint resolveTargetEndpoint(ConnectionEntity entity) {
        String rawHost = normalize(entity.getHost());
        if (rawHost.isBlank()) {
            throw new BusinessException(400, "数据库主机不能为空");
        }
        String host = stripProtocol(rawHost);
        int queryIndex = host.indexOf("?");
        if (queryIndex >= 0) {
            host = host.substring(0, queryIndex);
        }
        int semicolonIndex = host.indexOf(";");
        if (semicolonIndex >= 0) {
            host = host.substring(0, semicolonIndex);
        }
        int slashIndex = host.indexOf("/");
        if (slashIndex >= 0) {
            host = host.substring(0, slashIndex);
        }
        int atIndex = host.lastIndexOf("@");
        if (atIndex >= 0 && atIndex < host.length() - 1) {
            host = host.substring(atIndex + 1);
        }

        Integer parsedPort = null;
        int colonIndex = host.lastIndexOf(":");
        if (colonIndex > 0 && colonIndex < host.length() - 1 && !host.contains("]")) {
            String maybePort = host.substring(colonIndex + 1);
            if (maybePort.chars().allMatch(Character::isDigit)) {
                parsedPort = Integer.parseInt(maybePort);
                host = host.substring(0, colonIndex);
            }
        }
        if (host.isBlank()) {
            throw new BusinessException(400, "数据库主机不能为空");
        }
        int fallbackPort = defaultPortByType(entity.getDbType());
        int port = validPort(
            entity.getPort() != null && entity.getPort() > 0 ? entity.getPort() : parsedPort,
            fallbackPort,
            "数据库端口必须在 1-65535 之间"
        );
        return new Endpoint(host, port);
    }

    private int defaultPortByType(String dbType) {
        String type = normalize(dbType).toUpperCase(Locale.ROOT);
        if ("MYSQL".equals(type)) {
            return 3306;
        }
        if ("POSTGRESQL".equals(type)) {
            return 5432;
        }
        if ("SQLSERVER".equals(type)) {
            return 1433;
        }
        if ("ORACLE".equals(type)) {
            return 1521;
        }
        return 0;
    }

    private int validPort(Integer rawPort, int fallback, String errorMessage) {
        int port = rawPort == null || rawPort <= 0 ? fallback : rawPort;
        if (port <= 0 || port > 65535) {
            throw new BusinessException(400, errorMessage);
        }
        return port;
    }

    private String stripProtocol(String rawHost) {
        String normalized = normalize(rawHost);
        int marker = normalized.indexOf("://");
        if (marker >= 0) {
            return normalized.substring(marker + 3);
        }
        if (normalized.startsWith("jdbc:")) {
            return normalized.substring("jdbc:".length());
        }
        return normalized;
    }

    private Path createPrivateKeyTempFile(String privateKeyText) throws IOException {
        Path path = Files.createTempFile("sqlcopilot-ssh-key-", ".pem");
        Files.writeString(path, privateKeyText, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows 等不支持 Posix 权限的平台忽略。
        }
        return path;
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignore) {
            // 忽略临时文件清理异常，避免影响主流程。
        }
    }

    private String normalize(String value) {
        return Objects.toString(value, "").trim();
    }

    private record Endpoint(String host, int port) {
    }

    private record TunnelSpec(String sshHost,
                              int sshPort,
                              String sshUser,
                              String sshAuthType,
                              String sshPassword,
                              String sshPrivateKeyPath,
                              String sshPrivateKeyText,
                              String sshPrivateKeyPassphrase,
                              String targetHost,
                              int targetPort) {
    }

    public record TunnelEndpoint(String host, int port) {
    }

    public static final class TunnelSession implements AutoCloseable {
        private final TunnelSpec spec;
        private final Session session;
        private final TunnelEndpoint endpoint;
        private final Path keyTempFile;

        private TunnelSession(TunnelSpec spec, Session session, TunnelEndpoint endpoint, Path keyTempFile) {
            this.spec = spec;
            this.session = session;
            this.endpoint = endpoint;
            this.keyTempFile = keyTempFile;
        }

        public TunnelEndpoint endpoint() {
            return endpoint;
        }

        private boolean matches(TunnelSpec target) {
            return Objects.equals(spec, target) && session != null && session.isConnected();
        }

        @Override
        public void close() {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
            if (keyTempFile != null) {
                try {
                    Files.deleteIfExists(keyTempFile);
                } catch (IOException ignore) {
                    // 忽略临时文件清理异常，避免影响主流程。
                }
            }
        }
    }
}
