package com.sqlcopilot.studio.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcopilot.studio.dialect.JdbcUrlBuilder;
import com.sqlcopilot.studio.dto.connection.*;
import com.sqlcopilot.studio.entity.ConnectionEntity;
import com.sqlcopilot.studio.mapper.ConnectionMapper;
import com.sqlcopilot.studio.service.ConnectionService;
import com.sqlcopilot.studio.support.driver.IsolatedJdbcConnectionManager;
import com.sqlcopilot.studio.support.ssh.SshTunnelManager;
import com.sqlcopilot.studio.util.BusinessException;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ConnectionServiceImpl implements ConnectionService {

    private static final String DB_TYPE_MYSQL = "MYSQL";
    private static final String DB_TYPE_POSTGRESQL = "POSTGRESQL";
    private static final String DB_TYPE_SQLITE = "SQLITE";
    private static final String DB_TYPE_SQLSERVER = "SQLSERVER";
    private static final String DB_TYPE_ORACLE = "ORACLE";

    private static final String SSH_AUTH_PASSWORD = "SSH_PASSWORD";
    private static final String SSH_AUTH_KEY_PATH = "SSH_KEY_PATH";
    private static final String SSH_AUTH_KEY_TEXT = "SSH_KEY_TEXT";

    private final ConnectionMapper connectionMapper;
    private final IsolatedJdbcConnectionManager isolatedJdbcConnectionManager;
    private final SshTunnelManager sshTunnelManager;
    private final ObjectMapper objectMapper;
    private final AtomicLong temporaryConnectionIdGenerator = new AtomicLong(-1L);

    public ConnectionServiceImpl(ConnectionMapper connectionMapper,
                                 IsolatedJdbcConnectionManager isolatedJdbcConnectionManager,
                                 SshTunnelManager sshTunnelManager,
                                 ObjectMapper objectMapper) {
        this.connectionMapper = connectionMapper;
        this.isolatedJdbcConnectionManager = isolatedJdbcConnectionManager;
        this.sshTunnelManager = sshTunnelManager;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ConnectionVO> listConnections() {
        return connectionMapper.findAll().stream().map(this::toVO).toList();
    }

    @Override
    public ConnectionVO createConnection(ConnectionCreateReq req) {
        long now = System.currentTimeMillis();
        ConnectionEntity entity = new ConnectionEntity();
        fillEntity(req, entity);
        validateConnectionConfig(entity);
        entity.setLastTestStatus("UNKNOWN");
        entity.setLastTestMessage("尚未测试");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        connectionMapper.insert(entity);
        return toVO(entity);
    }

    @Override
    public ConnectionVO updateConnection(ConnectionUpdateReq req) {
        ConnectionEntity existing = getConnectionEntity(req.getId());
        String preservedPassword = existing.getPassword();
        String preservedSshAuthType = existing.getSshAuthType();
        String preservedSshPassword = existing.getSshPassword();
        String preservedSshPrivateKeyPath = existing.getSshPrivateKeyPath();
        String preservedSshPrivateKeyText = existing.getSshPrivateKeyText();
        String preservedSshPrivateKeyPassphrase = existing.getSshPrivateKeyPassphrase();
        fillEntity(req, existing);

        // 关键操作：编辑连接时敏感字段留空按“保持原值”处理，避免误清空凭据。
        if (safeValue(req.getPassword()).isBlank()) {
            existing.setPassword(preservedPassword);
        }
        if (existing.getSshEnabled() != null && existing.getSshEnabled() == 1) {
            if (safeValue(req.getSshAuthType()).isBlank()) {
                existing.setSshAuthType(safeValue(preservedSshAuthType));
            }
            String sshAuthType = normalizeSshAuthType(existing.getSshAuthType());
            if (SSH_AUTH_PASSWORD.equals(sshAuthType) && safeValue(req.getSshPassword()).isBlank()) {
                existing.setSshPassword(safeValue(preservedSshPassword));
            }
            if (SSH_AUTH_KEY_PATH.equals(sshAuthType) && safeValue(req.getSshPrivateKeyPath()).isBlank()) {
                existing.setSshPrivateKeyPath(safeValue(preservedSshPrivateKeyPath));
            }
            if (SSH_AUTH_KEY_TEXT.equals(sshAuthType) && safeValue(req.getSshPrivateKeyText()).isBlank()) {
                existing.setSshPrivateKeyText(safeValue(preservedSshPrivateKeyText));
            }
            boolean keepPassphrase = (SSH_AUTH_KEY_PATH.equals(sshAuthType) || SSH_AUTH_KEY_TEXT.equals(sshAuthType))
                && safeValue(req.getSshPrivateKeyPassphrase()).isBlank();
            if (keepPassphrase) {
                existing.setSshPrivateKeyPassphrase(safeValue(preservedSshPrivateKeyPassphrase));
            }
        } else {
            existing.setSshAuthType("");
            existing.setSshPassword("");
            existing.setSshPrivateKeyPath("");
            existing.setSshPrivateKeyText("");
            existing.setSshPrivateKeyPassphrase("");
        }

        normalizeSshCredentialByAuthType(existing);
        validateConnectionConfig(existing);
        existing.setUpdatedAt(System.currentTimeMillis());
        connectionMapper.update(existing);
        isolatedJdbcConnectionManager.release(existing.getId());
        sshTunnelManager.release(existing.getId());
        return toVO(existing);
    }

    @Override
    public ConnectionDatabasePreviewVO previewDatabases(ConnectionDatabasePreviewReq req) {
        ConnectionEntity previewEntity = new ConnectionEntity();
        fillPreviewEntity(req, previewEntity);
        normalizeSshCredentialByAuthType(previewEntity);
        validateConnectionConfig(previewEntity);
        ConnectionDatabasePreviewVO vo = new ConnectionDatabasePreviewVO();
        vo.setDatabaseNames(queryDatabaseNames(previewEntity));
        return vo;
    }

    @Override
    public void removeConnection(Long id) {
        connectionMapper.deleteById(id);
        isolatedJdbcConnectionManager.release(id);
        sshTunnelManager.release(id);
    }

    @Override
    public ConnectionTestVO testConnection(Long connectionId) {
        ConnectionTestVO vo = new ConnectionTestVO();
        long now = System.currentTimeMillis();
        try (Connection ignored = openTargetConnection(connectionId)) {
            vo.setSuccess(Boolean.TRUE);
            vo.setMessage("连接成功");
            connectionMapper.updateTestStatus(connectionId, "SUCCESS", "连接成功", now);
        } catch (Exception ex) {
            vo.setSuccess(Boolean.FALSE);
            vo.setMessage(ex.getMessage());
            connectionMapper.updateTestStatus(connectionId, "FAIL", ex.getMessage(), now);
        }
        return vo;
    }

    @Override
    public ConnectionEntity getConnectionEntity(Long id) {
        ConnectionEntity entity = connectionMapper.findById(id);
        if (entity == null) {
            throw new BusinessException(404, "连接不存在: " + id);
        }
        return entity;
    }

    @Override
    public Connection openTargetConnection(Long connectionId) throws SQLException {
        ConnectionEntity entity = getConnectionEntity(connectionId);
        validateConnectionConfig(entity);
        if (isSshEnabled(entity)) {
            // 关键操作：SSH 场景先建立本地端口转发，再切换 JDBC 目标到 127.0.0.1:localPort。
            SshTunnelManager.TunnelEndpoint endpoint = sshTunnelManager.ensureTunnel(connectionId, entity);
            ConnectionEntity tunneledEntity = buildTunneledJdbcEntity(entity, endpoint);
            return openJdbcConnection(tunneledEntity, entity.getId());
        }
        return openJdbcConnection(entity, entity.getId());
    }

    private void fillEntity(ConnectionCreateReq req, ConnectionEntity entity) {
        entity.setName(safeValue(req.getName()));
        entity.setDbType(upper(req.getDbType()));
        entity.setHost(safeValue(req.getHost()));
        entity.setPort(req.getPort());
        entity.setDatabaseName(safeValue(req.getDatabaseName()));
        entity.setUsername(safeValue(req.getUsername()));
        entity.setPassword(safeValue(req.getPassword()));
        entity.setAuthType(safeValue(req.getAuthType()));
        entity.setEnv(upper(req.getEnv()));
        entity.setReadOnly(Boolean.TRUE.equals(req.getReadOnly()) ? 1 : 0);
        entity.setSshEnabled(Boolean.TRUE.equals(req.getSshEnabled()) ? 1 : 0);
        entity.setSshHost(safeValue(req.getSshHost()));
        entity.setSshPort(req.getSshPort());
        entity.setSshUser(safeValue(req.getSshUser()));
        entity.setSshAuthType(normalizeSshAuthType(req.getSshAuthType()));
        entity.setSshPassword(safeValue(req.getSshPassword()));
        entity.setSshPrivateKeyPath(safeValue(req.getSshPrivateKeyPath()));
        entity.setSshPrivateKeyText(safeValue(req.getSshPrivateKeyText()));
        entity.setSshPrivateKeyPassphrase(safeValue(req.getSshPrivateKeyPassphrase()));
        entity.setSelectedDatabasesJson(toSelectedDatabasesJson(req.getSelectedDatabases(), entity.getDbType()));
        normalizeSshCredentialByAuthType(entity);
    }

    private void fillPreviewEntity(ConnectionDatabasePreviewReq req, ConnectionEntity entity) {
        entity.setName("preview");
        entity.setDbType(upper(req.getDbType()));
        entity.setHost(safeValue(req.getHost()));
        entity.setPort(req.getPort());
        entity.setDatabaseName(safeValue(req.getDatabaseName()));
        entity.setUsername(safeValue(req.getUsername()));
        entity.setPassword(safeValue(req.getPassword()));
        entity.setAuthType("PASSWORD");
        entity.setEnv("DEV");
        entity.setReadOnly(0);
        entity.setSshEnabled(Boolean.TRUE.equals(req.getSshEnabled()) ? 1 : 0);
        entity.setSshHost(safeValue(req.getSshHost()));
        entity.setSshPort(req.getSshPort());
        entity.setSshUser(safeValue(req.getSshUser()));
        entity.setSshAuthType(normalizeSshAuthType(req.getSshAuthType()));
        entity.setSshPassword(safeValue(req.getSshPassword()));
        entity.setSshPrivateKeyPath(safeValue(req.getSshPrivateKeyPath()));
        entity.setSshPrivateKeyText(safeValue(req.getSshPrivateKeyText()));
        entity.setSshPrivateKeyPassphrase(safeValue(req.getSshPrivateKeyPassphrase()));
        entity.setSelectedDatabasesJson(null);
    }

    private ConnectionVO toVO(ConnectionEntity entity) {
        ConnectionVO vo = new ConnectionVO();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setDbType(entity.getDbType());
        vo.setHost(entity.getHost());
        vo.setPort(entity.getPort());
        vo.setDatabaseName(entity.getDatabaseName());
        vo.setSelectedDatabases(parseSelectedDatabases(entity.getSelectedDatabasesJson()));
        vo.setUsername(entity.getUsername());
        vo.setEnv(entity.getEnv());
        vo.setReadOnly(entity.getReadOnly() != null && entity.getReadOnly() == 1);
        vo.setSshEnabled(entity.getSshEnabled() != null && entity.getSshEnabled() == 1);
        vo.setSshHost(entity.getSshHost());
        vo.setSshPort(entity.getSshPort());
        vo.setSshUser(entity.getSshUser());
        vo.setSshAuthType(normalizeSshAuthType(entity.getSshAuthType()));
        vo.setSshPrivateKeyPath(safeValue(entity.getSshPrivateKeyPath()));
        vo.setSshPasswordConfigured(!safeValue(entity.getSshPassword()).isBlank());
        vo.setSshPrivateKeyTextConfigured(!safeValue(entity.getSshPrivateKeyText()).isBlank());
        vo.setSshPrivateKeyPassphraseConfigured(!safeValue(entity.getSshPrivateKeyPassphrase()).isBlank());
        vo.setLastTestStatus(entity.getLastTestStatus());
        vo.setLastTestMessage(entity.getLastTestMessage());
        vo.setRiskPolicySummary((vo.getReadOnly() ? "只读执行" : "可写执行") + " | " + vo.getEnv());
        return vo;
    }

    private Connection openJdbcConnection(ConnectionEntity jdbcEntity, Long connectionId) throws SQLException {
        ConnectionEntity driverEntity = cloneForRuntime(jdbcEntity);
        driverEntity.setId(connectionId);
        String url = JdbcUrlBuilder.build(driverEntity);
        return isolatedJdbcConnectionManager.open(
            driverEntity,
            url,
            safeValue(driverEntity.getUsername()),
            safeValue(driverEntity.getPassword())
        );
    }

    private List<String> queryDatabaseNames(ConnectionEntity entity) {
        String dbType = upper(entity.getDbType());
        if (DB_TYPE_SQLITE.equals(dbType)) {
            String sqliteName = safeValue(entity.getDatabaseName());
            return List.of(sqliteName.isBlank() ? "main" : sqliteName);
        }

        long temporaryId = temporaryConnectionIdGenerator.decrementAndGet();
        entity.setId(temporaryId);
        SshTunnelManager.TunnelSession tunnelSession = null;
        try {
            ConnectionEntity jdbcEntity = entity;
            if (isSshEnabled(entity)) {
                tunnelSession = sshTunnelManager.openEphemeralTunnel(entity);
                jdbcEntity = buildTunneledJdbcEntity(entity, tunnelSession.endpoint());
            }
            try (Connection connection = openJdbcConnection(jdbcEntity, temporaryId)) {
                return switch (dbType) {
                    case DB_TYPE_MYSQL -> querySingleColumn(connection, "SHOW DATABASES", 5000);
                    case DB_TYPE_POSTGRESQL -> querySingleColumn(connection,
                        "SELECT datname FROM pg_database WHERE datistemplate = false ORDER BY datname", 5000);
                    case DB_TYPE_SQLSERVER -> querySingleColumn(connection, "SELECT name FROM sys.databases ORDER BY name", 5000);
                    case DB_TYPE_ORACLE -> querySingleColumn(connection, "SELECT username FROM all_users ORDER BY username", 5000);
                    default -> List.of();
                };
            }
        } catch (SQLException ex) {
            throw new BusinessException(500, "读取数据库列表失败: " + ex.getMessage());
        } finally {
            isolatedJdbcConnectionManager.release(temporaryId);
            if (tunnelSession != null) {
                tunnelSession.close();
            }
        }
    }

    /**
     * 关键校验：连接落库、预览与执行前统一校验必要字段，避免运行期建连失败。
     */
    private void validateConnectionConfig(ConnectionEntity entity) {
        String type = upper(entity.getDbType());
        if (type.isBlank()) {
            throw new BusinessException(400, "数据库类型不能为空");
        }
        if (DB_TYPE_SQLITE.equals(type)) {
            if (safeValue(entity.getDatabaseName()).isBlank()) {
                throw new BusinessException(400, "SQLite 数据库文件路径不能为空");
            }
            if (isSshEnabled(entity)) {
                throw new BusinessException(400, "SQLite 不支持 SSH 隧道模式");
            }
            return;
        }
        if (safeValue(entity.getHost()).isBlank()) {
            throw new BusinessException(400, "数据库主机不能为空");
        }
        boolean validPort = entity.getPort() != null && entity.getPort() > 0 && entity.getPort() <= 65535;
        boolean hostContainsPort = containsPortInHost(entity.getHost());
        if (!validPort && !hostContainsPort) {
            throw new BusinessException(400, "数据库端口必须在 1-65535 之间");
        }
        String dbName = safeValue(entity.getDatabaseName());
        if (DB_TYPE_ORACLE.equals(type) && dbName.isBlank() && !containsDatabaseInHost(entity.getHost())) {
            throw new BusinessException(400, "Oracle 服务名不能为空");
        }
        if (safeValue(entity.getUsername()).isBlank()) {
            throw new BusinessException(400, "数据库用户名不能为空");
        }
        if (isSshEnabled(entity)) {
            validateSshConfig(entity);
        }
    }

    private void validateSshConfig(ConnectionEntity entity) {
        String sshHost = safeValue(entity.getSshHost());
        String sshUser = safeValue(entity.getSshUser());
        Integer sshPort = entity.getSshPort();
        if (sshHost.isBlank()) {
            throw new BusinessException(400, "SSH 主机不能为空");
        }
        if (sshPort == null || sshPort <= 0 || sshPort > 65535) {
            throw new BusinessException(400, "SSH 端口必须在 1-65535 之间");
        }
        if (sshUser.isBlank()) {
            throw new BusinessException(400, "SSH 用户名不能为空");
        }
        String authType = normalizeSshAuthType(entity.getSshAuthType());
        if (SSH_AUTH_PASSWORD.equals(authType)) {
            if (safeValue(entity.getSshPassword()).isBlank()) {
                throw new BusinessException(400, "SSH 密码认证模式下 sshPassword 不能为空");
            }
            if (!safeValue(entity.getSshPrivateKeyPath()).isBlank() || !safeValue(entity.getSshPrivateKeyText()).isBlank()) {
                throw new BusinessException(400, "SSH 认证模式冲突：密码模式下不可同时提交私钥信息");
            }
            return;
        }
        if (SSH_AUTH_KEY_PATH.equals(authType)) {
            if (safeValue(entity.getSshPrivateKeyPath()).isBlank()) {
                throw new BusinessException(400, "SSH 私钥路径模式下 sshPrivateKeyPath 不能为空");
            }
            if (!safeValue(entity.getSshPassword()).isBlank() || !safeValue(entity.getSshPrivateKeyText()).isBlank()) {
                throw new BusinessException(400, "SSH 认证模式冲突：私钥路径模式下不可同时提交密码或私钥文本");
            }
            return;
        }
        if (SSH_AUTH_KEY_TEXT.equals(authType)) {
            if (safeValue(entity.getSshPrivateKeyText()).isBlank()) {
                throw new BusinessException(400, "SSH 私钥文本模式下 sshPrivateKeyText 不能为空");
            }
            if (!safeValue(entity.getSshPassword()).isBlank() || !safeValue(entity.getSshPrivateKeyPath()).isBlank()) {
                throw new BusinessException(400, "SSH 认证模式冲突：私钥文本模式下不可同时提交密码或私钥路径");
            }
            return;
        }
        throw new BusinessException(400, "不支持的 SSH 认证模式: " + authType);
    }

    private void normalizeSshCredentialByAuthType(ConnectionEntity entity) {
        if (!isSshEnabled(entity)) {
            entity.setSshAuthType("");
            entity.setSshPassword("");
            entity.setSshPrivateKeyPath("");
            entity.setSshPrivateKeyText("");
            entity.setSshPrivateKeyPassphrase("");
            return;
        }
        String authType = normalizeSshAuthType(entity.getSshAuthType());
        entity.setSshAuthType(authType);
        if (SSH_AUTH_PASSWORD.equals(authType)) {
            entity.setSshPrivateKeyPath("");
            entity.setSshPrivateKeyText("");
            entity.setSshPrivateKeyPassphrase("");
            return;
        }
        if (SSH_AUTH_KEY_PATH.equals(authType)) {
            entity.setSshPassword("");
            entity.setSshPrivateKeyText("");
            return;
        }
        if (SSH_AUTH_KEY_TEXT.equals(authType)) {
            entity.setSshPassword("");
            entity.setSshPrivateKeyPath("");
            return;
        }
        throw new BusinessException(400, "不支持的 SSH 认证模式: " + authType);
    }

    private String normalizeSshAuthType(String input) {
        String authType = upper(input);
        return authType.isBlank() ? SSH_AUTH_PASSWORD : authType;
    }

    private boolean isSshEnabled(ConnectionEntity entity) {
        return entity.getSshEnabled() != null && entity.getSshEnabled() == 1;
    }

    private ConnectionEntity buildTunneledJdbcEntity(ConnectionEntity source, SshTunnelManager.TunnelEndpoint endpoint) {
        ConnectionEntity target = cloneForRuntime(source);
        target.setHost(endpoint.host());
        target.setPort(endpoint.port());
        if (safeValue(target.getDatabaseName()).isBlank()) {
            target.setDatabaseName(extractDatabaseNameFromHost(source.getHost()));
        }
        return target;
    }

    private String toSelectedDatabasesJson(List<String> selectedDatabases, String dbType) {
        if (!supportsSelectedDatabases(dbType)) {
            return null;
        }
        List<String> normalized = normalizeSelectedDatabases(selectedDatabases);
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (Exception ex) {
            throw new BusinessException(400, "selectedDatabases 序列化失败: " + ex.getMessage());
        }
    }

    private List<String> parseSelectedDatabases(String json) {
        String text = safeValue(json);
        if (text.isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = objectMapper.readValue(text, new TypeReference<List<String>>() {
            });
            return normalizeSelectedDatabases(parsed);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private boolean supportsSelectedDatabases(String dbType) {
        String type = upper(dbType);
        return DB_TYPE_MYSQL.equals(type) || DB_TYPE_POSTGRESQL.equals(type) || DB_TYPE_SQLSERVER.equals(type);
    }

    private List<String> normalizeSelectedDatabases(List<String> selectedDatabases) {
        if (selectedDatabases == null || selectedDatabases.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        selectedDatabases.forEach(item -> {
            String normalized = safeValue(item);
            if (!normalized.isBlank()) {
                unique.add(normalized);
            }
        });
        return new ArrayList<>(unique);
    }

    private List<String> querySingleColumn(Connection connection, String sql, int maxCount) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next() && values.size() < maxCount) {
                String value = rs.getString(1);
                if (value != null && !value.isBlank()) {
                    values.add(value.trim());
                }
            }
        }
        return values;
    }

    private String extractDatabaseNameFromHost(String rawHost) {
        String host = safeValue(rawHost);
        if (host.isBlank()) {
            return "";
        }
        int marker = host.indexOf("://");
        if (marker >= 0) {
            host = host.substring(marker + 3);
        }
        int queryIndex = host.indexOf("?");
        if (queryIndex >= 0) {
            host = host.substring(0, queryIndex);
        }
        int semicolonIndex = host.indexOf(";");
        if (semicolonIndex >= 0) {
            host = host.substring(0, semicolonIndex);
        }
        int slashIndex = host.indexOf("/");
        if (slashIndex >= 0 && slashIndex < host.length() - 1) {
            return host.substring(slashIndex + 1).trim();
        }
        return "";
    }

    private boolean containsPortInHost(String rawHost) {
        String host = safeValue(rawHost);
        int marker = host.indexOf("://");
        if (marker >= 0) {
            host = host.substring(marker + 3);
        }
        int slashIndex = host.indexOf("/");
        if (slashIndex >= 0) {
            host = host.substring(0, slashIndex);
        }
        int atIndex = host.lastIndexOf("@");
        if (atIndex >= 0 && atIndex < host.length() - 1) {
            host = host.substring(atIndex + 1);
        }
        return host.matches("^.+:\\\\d+$");
    }

    private boolean containsDatabaseInHost(String rawHost) {
        String host = safeValue(rawHost);
        int marker = host.indexOf("://");
        if (marker >= 0) {
            host = host.substring(marker + 3);
        }
        int queryIndex = host.indexOf("?");
        if (queryIndex >= 0) {
            host = host.substring(0, queryIndex);
        }
        int slashIndex = host.indexOf("/");
        return slashIndex >= 0 && slashIndex < host.length() - 1;
    }

    private ConnectionEntity cloneForRuntime(ConnectionEntity source) {
        ConnectionEntity target = new ConnectionEntity();
        target.setId(source.getId());
        target.setName(source.getName());
        target.setDbType(source.getDbType());
        target.setHost(source.getHost());
        target.setPort(source.getPort());
        target.setDatabaseName(source.getDatabaseName());
        target.setUsername(source.getUsername());
        target.setPassword(source.getPassword());
        target.setAuthType(source.getAuthType());
        target.setEnv(source.getEnv());
        target.setReadOnly(source.getReadOnly());
        target.setSshEnabled(source.getSshEnabled());
        target.setSshHost(source.getSshHost());
        target.setSshPort(source.getSshPort());
        target.setSshUser(source.getSshUser());
        target.setSshAuthType(source.getSshAuthType());
        target.setSshPassword(source.getSshPassword());
        target.setSshPrivateKeyPath(source.getSshPrivateKeyPath());
        target.setSshPrivateKeyText(source.getSshPrivateKeyText());
        target.setSshPrivateKeyPassphrase(source.getSshPrivateKeyPassphrase());
        target.setSelectedDatabasesJson(source.getSelectedDatabasesJson());
        return target;
    }

    private String upper(String input) {
        return Objects.toString(input, "").trim().toUpperCase(Locale.ROOT);
    }

    private String safeValue(String input) {
        return Objects.toString(input, "").trim();
    }
}
