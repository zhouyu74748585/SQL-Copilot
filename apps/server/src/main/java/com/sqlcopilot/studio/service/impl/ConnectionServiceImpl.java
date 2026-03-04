package com.sqlcopilot.studio.service.impl;

import com.sqlcopilot.studio.dialect.JdbcUrlBuilder;
import com.sqlcopilot.studio.dto.connection.ConnectionCreateReq;
import com.sqlcopilot.studio.dto.connection.ConnectionTestVO;
import com.sqlcopilot.studio.dto.connection.ConnectionUpdateReq;
import com.sqlcopilot.studio.dto.connection.ConnectionVO;
import com.sqlcopilot.studio.entity.ConnectionEntity;
import com.sqlcopilot.studio.mapper.ConnectionMapper;
import com.sqlcopilot.studio.service.ConnectionService;
import com.sqlcopilot.studio.support.driver.IsolatedJdbcConnectionManager;
import com.sqlcopilot.studio.util.BusinessException;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

@Service
public class ConnectionServiceImpl implements ConnectionService {

    private final ConnectionMapper connectionMapper;
    private final IsolatedJdbcConnectionManager isolatedJdbcConnectionManager;

    public ConnectionServiceImpl(ConnectionMapper connectionMapper,
                                 IsolatedJdbcConnectionManager isolatedJdbcConnectionManager) {
        this.connectionMapper = connectionMapper;
        this.isolatedJdbcConnectionManager = isolatedJdbcConnectionManager;
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
        fillEntity(req, existing);
        // 编辑连接未输入新密码时保留原密码，避免无意清空导致连接不可用。
        if (safeValue(req.getPassword()).isBlank()) {
            existing.setPassword(preservedPassword);
        }
        validateConnectionConfig(existing);
        existing.setUpdatedAt(System.currentTimeMillis());
        connectionMapper.update(existing);
        isolatedJdbcConnectionManager.release(existing.getId());
        return toVO(existing);
    }

    @Override
    public void removeConnection(Long id) {
        connectionMapper.deleteById(id);
        isolatedJdbcConnectionManager.release(id);
    }

    @Override
    public ConnectionTestVO testConnection(Long connectionId) {
        ConnectionEntity entity = getConnectionEntity(connectionId);
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
        String url = JdbcUrlBuilder.build(entity);
        // 关键操作：连接按 connectionId 维度绑定独立驱动上下文，保障连接隔离。
        return isolatedJdbcConnectionManager.open(entity, url, safeValue(entity.getUsername()), safeValue(entity.getPassword()));
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
    }

    private ConnectionVO toVO(ConnectionEntity entity) {
        ConnectionVO vo = new ConnectionVO();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setDbType(entity.getDbType());
        vo.setHost(entity.getHost());
        vo.setPort(entity.getPort());
        vo.setDatabaseName(entity.getDatabaseName());
        vo.setUsername(entity.getUsername());
        vo.setEnv(entity.getEnv());
        vo.setReadOnly(entity.getReadOnly() != null && entity.getReadOnly() == 1);
        vo.setSshEnabled(entity.getSshEnabled() != null && entity.getSshEnabled() == 1);
        vo.setSshHost(entity.getSshHost());
        vo.setSshPort(entity.getSshPort());
        vo.setSshUser(entity.getSshUser());
        vo.setLastTestStatus(entity.getLastTestStatus());
        vo.setLastTestMessage(entity.getLastTestMessage());
        vo.setRiskPolicySummary((vo.getReadOnly() ? "只读执行" : "可写执行") + " | " + vo.getEnv());
        return vo;
    }

    private String upper(String input) {
        return Objects.toString(input, "").trim().toUpperCase();
    }

    private String safeValue(String input) {
        return Objects.toString(input, "").trim();
    }

    /**
     * 关键校验：连接落库与执行前统一校验必要字段，避免 JDBC URL 格式错误。
     */
    private void validateConnectionConfig(ConnectionEntity entity) {
        String type = upper(entity.getDbType());
        if ("SQLITE".equals(type)) {
            if (safeValue(entity.getDatabaseName()).isBlank()) {
                throw new BusinessException(400, "SQLite 数据库文件路径不能为空");
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
        if ("ORACLE".equals(type) && dbName.isBlank() && !containsDatabaseInHost(entity.getHost())) {
            throw new BusinessException(400, "Oracle 服务名不能为空");
        }
        if (safeValue(entity.getUsername()).isBlank()) {
            throw new BusinessException(400, "数据库用户名不能为空");
        }
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
}
