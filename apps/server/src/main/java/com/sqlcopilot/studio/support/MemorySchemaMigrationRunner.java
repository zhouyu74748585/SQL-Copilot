package com.sqlcopilot.studio.support;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

@Component
public class MemorySchemaMigrationRunner implements ApplicationRunner {

    private final DataSource dataSource;

    public MemorySchemaMigrationRunner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS memory_entry (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    scope TEXT NOT NULL,
                    connection_id INTEGER NOT NULL,
                    database_name TEXT NOT NULL DEFAULT '',
                    title TEXT NOT NULL,
                    summary TEXT NOT NULL,
                    structured_summary_json TEXT NOT NULL DEFAULT '{}',
                    source_type TEXT NOT NULL,
                    source_session_id TEXT,
                    source_history_ids_json TEXT,
                    hit_count INTEGER NOT NULL DEFAULT 0,
                    last_used_at INTEGER,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """);
            statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_memory_entry_scope_ctx_updated
                ON memory_entry(scope, connection_id, database_name, updated_at DESC)
                """);
            statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_memory_entry_source_session
                ON memory_entry(source_type, source_session_id, connection_id, database_name)
                """);
            if (!hasColumn(connection, "memory_entry", "structured_summary_json")) {
                statement.execute("ALTER TABLE memory_entry ADD COLUMN structured_summary_json TEXT NOT NULL DEFAULT '{}'");
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("记忆管理表迁移失败", ex);
        }
    }

    private boolean hasColumn(Connection connection, String tableName, String columnName) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (rs.next()) {
                if (columnName.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }
}
