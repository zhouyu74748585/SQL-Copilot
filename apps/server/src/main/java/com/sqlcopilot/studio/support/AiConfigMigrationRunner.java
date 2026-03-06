package com.sqlcopilot.studio.support;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

@Component
public class AiConfigMigrationRunner implements ApplicationRunner {

    private static final long SINGLETON_ID = 1L;

    private final DataSource dataSource;

    public AiConfigMigrationRunner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            ensureAiProviderModelOptionsColumn(connection);
            ensureConversationMemoryColumns(connection);
            ensureRagConfigTable(statement);
            normalizeRagConfigTable(connection);
            ensureRagVectorizeStatusTable(statement);
            ensureRagVectorizeStatusColumns(connection);
            migrateLegacyRagColumns(connection);
        } catch (SQLException ex) {
            throw new IllegalStateException("AI 配置表迁移失败", ex);
        }
    }

    /**
     * 关键操作：为 AI 配置补充多模型路由字段，兼容旧库升级。
     */
    private void ensureAiProviderModelOptionsColumn(Connection connection) throws SQLException {
        if (!hasTable(connection, "ai_provider_config")) {
            return;
        }
        if (hasColumn(connection, "ai_provider_config", "model_options_json")) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE ai_provider_config ADD COLUMN model_options_json TEXT");
        }
    }


    private void ensureConversationMemoryColumns(Connection connection) throws SQLException {
        if (!hasTable(connection, "ai_provider_config")) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            if (!hasColumn(connection, "ai_provider_config", "conversation_memory_enabled")) {
                statement.execute("ALTER TABLE ai_provider_config ADD COLUMN conversation_memory_enabled INTEGER DEFAULT 1");
            }
            if (!hasColumn(connection, "ai_provider_config", "conversation_memory_window_size")) {
                statement.execute("ALTER TABLE ai_provider_config ADD COLUMN conversation_memory_window_size INTEGER DEFAULT 12");
            }
        }
    }

    /**
     * 关键操作：保证 RAG 模型配置与 AI 接入配置物理分表。
     */
    private void ensureRagConfigTable(Statement statement) throws SQLException {
        statement.execute("""
            CREATE TABLE IF NOT EXISTS rag_embedding_config (
                id INTEGER PRIMARY KEY,
                rag_embedding_model_dir TEXT,
                updated_at INTEGER NOT NULL
            )
            """);
    }

    /**
     * 关键操作：将历史宽表结构收敛为仅模型目录字段，避免无效配置持续堆积。
     */
    private void normalizeRagConfigTable(Connection connection) throws SQLException {
        if (!hasTable(connection, "rag_embedding_config")) {
            return;
        }
        if (!hasColumn(connection, "rag_embedding_config", "rag_embedding_model_dir")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE rag_embedding_config ADD COLUMN rag_embedding_model_dir TEXT");
            }
        }
        if (!hasColumn(connection, "rag_embedding_config", "updated_at")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE rag_embedding_config ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0");
            }
        }
        if (!hasAnyLegacyRagConfigColumns(connection)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS rag_embedding_config_new (
                    id INTEGER PRIMARY KEY,
                    rag_embedding_model_dir TEXT,
                    updated_at INTEGER NOT NULL
                )
                """);
            statement.execute("""
                INSERT OR REPLACE INTO rag_embedding_config_new(id, rag_embedding_model_dir, updated_at)
                SELECT id,
                       rag_embedding_model_dir,
                       CASE
                           WHEN updated_at IS NULL OR updated_at <= 0 THEN CAST(strftime('%s', 'now') AS INTEGER) * 1000
                           ELSE updated_at
                       END
                FROM rag_embedding_config
                """);
            statement.execute("DROP TABLE rag_embedding_config");
            statement.execute("ALTER TABLE rag_embedding_config_new RENAME TO rag_embedding_config");
        }
    }

    /**
     * 关键操作：持久化数据库向量化状态，确保服务重启后状态可恢复。
     */
    private void ensureRagVectorizeStatusTable(Statement statement) throws SQLException {
        statement.execute("""
            CREATE TABLE IF NOT EXISTS rag_vectorize_status (
                connection_id INTEGER NOT NULL,
                database_name TEXT NOT NULL,
                status TEXT NOT NULL,
                message TEXT,
                updated_at INTEGER NOT NULL,
                last_full_vectorize_duration_ms INTEGER,
                last_full_vectorize_provider TEXT,
                PRIMARY KEY(connection_id, database_name)
            )
            """);
    }

    /**
     * 关键操作：补齐整库全量向量化统计字段，兼容历史库平滑升级。
     */
    private void ensureRagVectorizeStatusColumns(Connection connection) throws SQLException {
        if (!hasTable(connection, "rag_vectorize_status")) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            if (!hasColumn(connection, "rag_vectorize_status", "last_full_vectorize_duration_ms")) {
                statement.execute("ALTER TABLE rag_vectorize_status ADD COLUMN last_full_vectorize_duration_ms INTEGER");
            }
            if (!hasColumn(connection, "rag_vectorize_status", "last_full_vectorize_provider")) {
                statement.execute("ALTER TABLE rag_vectorize_status ADD COLUMN last_full_vectorize_provider TEXT");
            }
        }
    }

    private void migrateLegacyRagColumns(Connection connection) throws SQLException {
        if (!hasColumn(connection, "ai_provider_config", "rag_embedding_model_dir")) {
            return;
        }
        if (hasAnyRagConfig(connection)) {
            return;
        }

        List<String> legacyColumns = new ArrayList<>();
        legacyColumns.add("rag_embedding_model_dir");
        String selectSql = "SELECT " + String.join(", ", legacyColumns) + ", updated_at FROM ai_provider_config WHERE id = ?";
        try (PreparedStatement select = connection.prepareStatement(selectSql)) {
            select.setLong(1, SINGLETON_ID);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    return;
                }
                boolean hasAnyValue = false;
                List<String> values = new ArrayList<>();
                for (String column : legacyColumns) {
                    String value = rs.getString(column);
                    values.add(value);
                    if (value != null && !value.trim().isEmpty()) {
                        hasAnyValue = true;
                    }
                }
                if (!hasAnyValue) {
                    return;
                }
                long updatedAt = rs.getLong("updated_at");
                if (updatedAt <= 0) {
                    updatedAt = System.currentTimeMillis();
                }
                String insertSql = """
                    INSERT INTO rag_embedding_config(
                        id,
                        rag_embedding_model_dir,
                        updated_at
                    )
                    VALUES(?, ?, ?)
                    """;
                try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
                    insert.setLong(1, SINGLETON_ID);
                    insert.setString(2, values.get(0));
                    insert.setLong(3, updatedAt);
                    insert.executeUpdate();
                }
            }
        }
    }

    private boolean hasAnyLegacyRagConfigColumns(Connection connection) throws SQLException {
        return hasColumn(connection, "rag_embedding_config", "rag_embedding_model_file_name")
            || hasColumn(connection, "rag_embedding_config", "rag_embedding_model_data_file_name")
            || hasColumn(connection, "rag_embedding_config", "rag_embedding_tokenizer_file_name")
            || hasColumn(connection, "rag_embedding_config", "rag_embedding_tokenizer_config_file_name")
            || hasColumn(connection, "rag_embedding_config", "rag_embedding_config_file_name")
            || hasColumn(connection, "rag_embedding_config", "rag_embedding_special_tokens_file_name")
            || hasColumn(connection, "rag_embedding_config", "rag_embedding_sentencepiece_file_name")
            || hasColumn(connection, "rag_embedding_config", "rag_embedding_model_path")
            || hasColumn(connection, "rag_embedding_config", "rag_embedding_model_data_path");
    }

    private boolean hasAnyRagConfig(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT id FROM rag_embedding_config WHERE id = ?")) {
            ps.setLong(1, SINGLETON_ID);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
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
