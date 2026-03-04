package com.sqlcopilot.studio.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class ConnectionSchemaMigrationRunner implements ApplicationRunner {

    private final DataSource dataSource;

    public ConnectionSchemaMigrationRunner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            ensureConnectionColumn(connection, statement, "ssh_auth_type", "TEXT");
            ensureConnectionColumn(connection, statement, "ssh_password", "TEXT");
            ensureConnectionColumn(connection, statement, "ssh_private_key_path", "TEXT");
            ensureConnectionColumn(connection, statement, "ssh_private_key_text", "TEXT");
            ensureConnectionColumn(connection, statement, "ssh_private_key_passphrase", "TEXT");
            ensureConnectionColumn(connection, statement, "selected_databases_json", "TEXT");
            backfillSshAuthType(connection);
        } catch (SQLException ex) {
            throw new IllegalStateException("连接配置表迁移失败", ex);
        }
    }

    /**
     * 关键操作：增量补齐连接表缺失列，兼容旧版本 SQLite 库升级。
     */
    private void ensureConnectionColumn(Connection connection,
                                        Statement statement,
                                        String columnName,
                                        String columnType) throws SQLException {
        if (!hasTable(connection, "connection_info")) {
            return;
        }
        if (hasColumn(connection, "connection_info", columnName)) {
            return;
        }
        statement.execute("ALTER TABLE connection_info ADD COLUMN " + columnName + " " + columnType);
    }

    /**
     * 关键操作：历史 SSH 连接无认证模式时回填密码模式，确保兼容老数据。
     */
    private void backfillSshAuthType(Connection connection) throws SQLException {
        String sql = """
            UPDATE connection_info
            SET ssh_auth_type = 'SSH_PASSWORD'
            WHERE COALESCE(ssh_enabled, 0) = 1
              AND (ssh_auth_type IS NULL OR TRIM(ssh_auth_type) = '')
            """;
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
            preparedStatement.executeUpdate();
        }
    }

    private boolean hasColumn(Connection connection, String tableName, String columnName) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (rs.next()) {
                String current = rs.getString("name");
                if (columnName.equalsIgnoreCase(current)) {
                    return true;
                }
            }
            return false;
        }
    }

    private boolean hasTable(Connection connection, String tableName) throws SQLException {
        String sql = "SELECT name FROM sqlite_master WHERE type='table' AND name=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
