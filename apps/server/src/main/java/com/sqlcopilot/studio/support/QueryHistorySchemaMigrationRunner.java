package com.sqlcopilot.studio.support;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.*;

@Component
public class QueryHistorySchemaMigrationRunner implements ApplicationRunner {

    private final DataSource dataSource;

    public QueryHistorySchemaMigrationRunner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            ensureHistoryColumn(connection, statement, "history_type", "TEXT");
            ensureHistoryColumn(connection, statement, "action_type", "TEXT");
            ensureHistoryColumn(connection, statement, "assistant_content", "TEXT");
            ensureHistoryColumn(connection, statement, "database_name", "TEXT");
            ensureHistoryColumn(connection, statement, "chart_config_json", "TEXT");
            ensureHistoryColumn(connection, statement, "chart_image_cache_key", "TEXT");
            ensureHistoryColumn(connection, statement, "structured_context_json", "TEXT");
            ensureHistoryColumn(connection, statement, "token_estimate", "INTEGER");
            ensureHistoryColumn(connection, statement, "memory_enabled", "INTEGER");
            backfillHistoryType(connection);
        } catch (SQLException ex) {
            throw new IllegalStateException("查询历史表迁移失败", ex);
        }
    }

    /**
     * 关键操作：增量补齐 query_history 缺失列，兼容旧版本 SQLite 库升级。
     */
    private void ensureHistoryColumn(Connection connection,
                                     Statement statement,
                                     String columnName,
                                     String columnType) throws SQLException {
        if (!hasTable(connection, "query_history")) {
            return;
        }
        if (hasColumn(connection, "query_history", columnName)) {
            return;
        }
        statement.execute("ALTER TABLE query_history ADD COLUMN " + columnName + " " + columnType);
    }

    /**
     * 关键操作：按历史特征回填 history_type，确保会话分页可过滤执行记录。
     */
    private void backfillHistoryType(Connection connection) throws SQLException {
        String sql = """
            UPDATE query_history
            SET history_type = CASE
                WHEN (prompt_text IS NULL OR TRIM(prompt_text) = '')
                     AND execution_ms IS NOT NULL THEN 'EXECUTE'
                ELSE 'CHAT'
            END
            WHERE history_type IS NULL OR TRIM(history_type) = ''
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
