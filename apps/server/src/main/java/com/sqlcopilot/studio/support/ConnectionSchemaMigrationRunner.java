package com.sqlcopilot.studio.support;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.*;

@Component
public class ConnectionSchemaMigrationRunner implements ApplicationRunner {

    public static final String DEFAULT_GROUP_NAME = "未分组";

    private final DataSource dataSource;

    public ConnectionSchemaMigrationRunner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            ensureConnectionGroupTable(statement);
            ensureConnectionColumn(connection, statement, "ssh_auth_type", "TEXT");
            ensureConnectionColumn(connection, statement, "ssh_password", "TEXT");
            ensureConnectionColumn(connection, statement, "ssh_private_key_path", "TEXT");
            ensureConnectionColumn(connection, statement, "ssh_private_key_text", "TEXT");
            ensureConnectionColumn(connection, statement, "ssh_private_key_passphrase", "TEXT");
            ensureConnectionColumn(connection, statement, "custom_params", "TEXT");
            ensureConnectionColumn(connection, statement, "selected_databases_json", "TEXT");
            ensureConnectionColumn(connection, statement, "group_id", "INTEGER");
            long defaultGroupId = ensureDefaultConnectionGroup(connection);
            backfillConnectionGroupId(connection, defaultGroupId);
            backfillSshAuthType(connection);
        } catch (SQLException ex) {
            throw new IllegalStateException("连接配置表迁移失败", ex);
        }
    }

    /**
     * 关键操作：补齐连接分组表，支持空分组持久化与连接拖拽分组。
     */
    private void ensureConnectionGroupTable(Statement statement) throws SQLException {
        statement.execute("""
            CREATE TABLE IF NOT EXISTS connection_group (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                sort_order INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """);
        statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_connection_group_name ON connection_group(name)");
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

    /**
     * 关键操作：保证默认分组始终存在，历史连接统一归入该分组。
     */
    private long ensureDefaultConnectionGroup(Connection connection) throws SQLException {
        try (PreparedStatement selectPs = connection.prepareStatement("SELECT id FROM connection_group WHERE name = ?")) {
            selectPs.setString(1, DEFAULT_GROUP_NAME);
            try (ResultSet rs = selectPs.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        long now = System.currentTimeMillis();
        try (PreparedStatement insertPs = connection.prepareStatement(
            """
                INSERT INTO connection_group(name, sort_order, created_at, updated_at)
                VALUES (?, 0, ?, ?)
                """,
            Statement.RETURN_GENERATED_KEYS
        )) {
            insertPs.setString(1, DEFAULT_GROUP_NAME);
            insertPs.setLong(2, now);
            insertPs.setLong(3, now);
            insertPs.executeUpdate();
            try (ResultSet rs = insertPs.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        throw new SQLException("默认连接分组创建失败");
    }

    private void backfillConnectionGroupId(Connection connection, long defaultGroupId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            """
                UPDATE connection_info
                SET group_id = ?
                WHERE group_id IS NULL
                """
        )) {
            ps.setLong(1, defaultGroupId);
            ps.executeUpdate();
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
