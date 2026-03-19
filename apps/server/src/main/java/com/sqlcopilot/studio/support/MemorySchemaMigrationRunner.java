package com.sqlcopilot.studio.support;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
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
        } catch (SQLException ex) {
            throw new IllegalStateException("记忆管理表迁移失败", ex);
        }
    }
}
