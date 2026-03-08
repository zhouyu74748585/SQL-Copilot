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
public class EditorKnowledgeSchemaMigrationRunner implements ApplicationRunner {

    private final DataSource dataSource;

    public EditorKnowledgeSchemaMigrationRunner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS saved_query (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    connection_id INTEGER NOT NULL,
                    database_name TEXT NOT NULL DEFAULT '',
                    title TEXT NOT NULL,
                    sql_text TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    UNIQUE(connection_id, database_name, title)
                )
                """);
            statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_saved_query_conn_db_updated
                ON saved_query(connection_id, database_name, updated_at DESC)
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS knowledge_term (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    scope TEXT NOT NULL,
                    connection_id INTEGER NOT NULL DEFAULT 0,
                    database_name TEXT NOT NULL DEFAULT '',
                    term TEXT NOT NULL,
                    description TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """);
            normalizeKnowledgeTermTable(connection, statement);
            statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_knowledge_term_scope_ctx_updated
                ON knowledge_term(scope, connection_id, database_name, updated_at DESC)
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS knowledge_example_sql (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    scope TEXT NOT NULL,
                    connection_id INTEGER NOT NULL DEFAULT 0,
                    database_name TEXT NOT NULL DEFAULT '',
                    sql_text TEXT NOT NULL,
                    description TEXT,
                    term_ids_json TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """);
            normalizeKnowledgeExampleTable(connection, statement);
            statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_knowledge_example_scope_ctx_updated
                ON knowledge_example_sql(scope, connection_id, database_name, updated_at DESC)
                """);
        } catch (SQLException ex) {
            throw new IllegalStateException("编辑器与知识中心表迁移失败", ex);
        }
    }

    private void normalizeKnowledgeTermTable(Connection connection, Statement statement) throws SQLException {
        if (!hasTable(connection, "knowledge_term")) {
            return;
        }
        if (hasColumn(connection, "knowledge_term", "connection_id")
            && hasColumn(connection, "knowledge_term", "database_name")
            && hasColumn(connection, "knowledge_term", "description")) {
            return;
        }
        statement.execute("DROP TABLE IF EXISTS knowledge_term_new");
        statement.execute("""
            CREATE TABLE knowledge_term_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                scope TEXT NOT NULL,
                connection_id INTEGER NOT NULL DEFAULT 0,
                database_name TEXT NOT NULL DEFAULT '',
                term TEXT NOT NULL,
                description TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """);
        statement.execute("""
            INSERT INTO knowledge_term_new(id, scope, connection_id, database_name, term, description, created_at, updated_at)
            SELECT
                id,
                scope,
                COALESCE(scope_connection_id, 0),
                COALESCE(scope_database_name, ''),
                term,
                definition,
                created_at,
                updated_at
            FROM knowledge_term
            """);
        statement.execute("DROP TABLE knowledge_term");
        statement.execute("ALTER TABLE knowledge_term_new RENAME TO knowledge_term");
    }

    private void normalizeKnowledgeExampleTable(Connection connection, Statement statement) throws SQLException {
        if (!hasTable(connection, "knowledge_example_sql")) {
            return;
        }
        if (hasColumn(connection, "knowledge_example_sql", "connection_id")
            && hasColumn(connection, "knowledge_example_sql", "database_name")) {
            return;
        }
        statement.execute("DROP TABLE IF EXISTS knowledge_example_sql_new");
        statement.execute("""
            CREATE TABLE knowledge_example_sql_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                scope TEXT NOT NULL,
                connection_id INTEGER NOT NULL DEFAULT 0,
                database_name TEXT NOT NULL DEFAULT '',
                sql_text TEXT NOT NULL,
                description TEXT,
                term_ids_json TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """);
        statement.execute("""
            INSERT INTO knowledge_example_sql_new(id, scope, connection_id, database_name, sql_text, description, term_ids_json, created_at, updated_at)
            SELECT
                id,
                scope,
                COALESCE(scope_connection_id, 0),
                COALESCE(scope_database_name, ''),
                sql_text,
                description,
                term_ids_json,
                created_at,
                updated_at
            FROM knowledge_example_sql
            """);
        statement.execute("DROP TABLE knowledge_example_sql");
        statement.execute("ALTER TABLE knowledge_example_sql_new RENAME TO knowledge_example_sql");
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

    private boolean hasTable(Connection connection, String tableName) throws SQLException {
        try (PreparedStatement preparedStatement = connection.prepareStatement(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?")) {
            preparedStatement.setString(1, tableName);
            try (ResultSet rs = preparedStatement.executeQuery()) {
                return rs.next();
            }
        }
    }
}
