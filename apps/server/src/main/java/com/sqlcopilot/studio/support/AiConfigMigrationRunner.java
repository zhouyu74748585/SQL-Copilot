package com.sqlcopilot.studio.support;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

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
            ensureRagConfigTable(statement);
            ensureRagVectorizeStatusTable(statement);
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

    /**
     * 关键操作：保证 RAG 模型配置与 AI 接入配置物理分表。
     */
    private void ensureRagConfigTable(Statement statement) throws SQLException {
        statement.execute("""
            CREATE TABLE IF NOT EXISTS rag_embedding_config (
                id INTEGER PRIMARY KEY,
                rag_embedding_model_dir TEXT,
                rag_embedding_model_file_name TEXT,
                rag_embedding_model_data_file_name TEXT,
                rag_embedding_tokenizer_file_name TEXT,
                rag_embedding_tokenizer_config_file_name TEXT,
                rag_embedding_config_file_name TEXT,
                rag_embedding_special_tokens_file_name TEXT,
                rag_embedding_sentencepiece_file_name TEXT,
                rag_embedding_model_path TEXT,
                rag_embedding_model_data_path TEXT,
                updated_at INTEGER NOT NULL
            )
            """);
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
                PRIMARY KEY(connection_id, database_name)
            )
            """);
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
        legacyColumns.add("rag_embedding_model_file_name");
        legacyColumns.add("rag_embedding_model_data_file_name");
        legacyColumns.add("rag_embedding_tokenizer_file_name");
        legacyColumns.add("rag_embedding_tokenizer_config_file_name");
        legacyColumns.add("rag_embedding_config_file_name");
        legacyColumns.add("rag_embedding_special_tokens_file_name");
        legacyColumns.add("rag_embedding_sentencepiece_file_name");
        legacyColumns.add("rag_embedding_model_path");
        legacyColumns.add("rag_embedding_model_data_path");

        String selectSql = "SELECT " + String.join(", ", legacyColumns)
            + ", updated_at FROM ai_provider_config WHERE id = ?";
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
                        rag_embedding_model_file_name,
                        rag_embedding_model_data_file_name,
                        rag_embedding_tokenizer_file_name,
                        rag_embedding_tokenizer_config_file_name,
                        rag_embedding_config_file_name,
                        rag_embedding_special_tokens_file_name,
                        rag_embedding_sentencepiece_file_name,
                        rag_embedding_model_path,
                        rag_embedding_model_data_path,
                        updated_at
                    )
                    VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
                try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
                    insert.setLong(1, SINGLETON_ID);
                    for (int i = 0; i < values.size(); i++) {
                        insert.setString(i + 2, values.get(i));
                    }
                    insert.setLong(12, updatedAt);
                    insert.executeUpdate();
                }
            }
        }
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
