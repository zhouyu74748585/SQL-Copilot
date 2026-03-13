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
                    aliases_json TEXT,
                    metric_expression TEXT,
                    related_tables_json TEXT,
                    related_columns_json TEXT,
                    term_type TEXT DEFAULT 'TERM',
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
                    question_text TEXT,
                    question_variants_json TEXT,
                    semantic_summary TEXT,
                    normalized_sql TEXT,
                    sql_template TEXT,
                    sql_ast_json TEXT,
                    table_names_json TEXT,
                    column_names_json TEXT,
                    metric_tags_json TEXT,
                    time_tags_json TEXT,
                    verified_flag INTEGER DEFAULT 1,
                    quality_score REAL DEFAULT 0.95,
                    source_type TEXT DEFAULT 'MANUAL',
                    sql_operation_type TEXT DEFAULT 'SELECT',
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
        boolean hasConnectionId = hasColumn(connection, "knowledge_term", "connection_id");
        boolean hasDatabaseName = hasColumn(connection, "knowledge_term", "database_name");
        boolean hasDescription = hasColumn(connection, "knowledge_term", "description");
        boolean hasAliasesJson = hasColumn(connection, "knowledge_term", "aliases_json");
        boolean hasMetricExpression = hasColumn(connection, "knowledge_term", "metric_expression");
        boolean hasRelatedTablesJson = hasColumn(connection, "knowledge_term", "related_tables_json");
        boolean hasRelatedColumnsJson = hasColumn(connection, "knowledge_term", "related_columns_json");
        boolean hasTermType = hasColumn(connection, "knowledge_term", "term_type");
        if (hasColumn(connection, "knowledge_term", "connection_id")
            && hasColumn(connection, "knowledge_term", "database_name")
            && hasColumn(connection, "knowledge_term", "description")
            && hasAliasesJson
            && hasMetricExpression
            && hasRelatedTablesJson
            && hasRelatedColumnsJson
            && hasTermType) {
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
                aliases_json TEXT,
                metric_expression TEXT,
                related_tables_json TEXT,
                related_columns_json TEXT,
                term_type TEXT DEFAULT 'TERM',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """);
        String connectionIdExpr = hasConnectionId ? "COALESCE(connection_id, 0)" : "COALESCE(scope_connection_id, 0)";
        String databaseNameExpr = hasDatabaseName ? "COALESCE(database_name, '')" : "COALESCE(scope_database_name, '')";
        String descriptionExpr = hasDescription ? "COALESCE(description, '')" : "COALESCE(definition, '')";
        String aliasesExpr = hasAliasesJson ? "COALESCE(aliases_json, '[]')" : "'[]'";
        String metricExpressionExpr = hasMetricExpression ? "COALESCE(metric_expression, '')" : descriptionExpr;
        String relatedTablesExpr = hasRelatedTablesJson ? "COALESCE(related_tables_json, '[]')" : "'[]'";
        String relatedColumnsExpr = hasRelatedColumnsJson ? "COALESCE(related_columns_json, '[]')" : "'[]'";
        String termTypeExpr = hasTermType ? "COALESCE(term_type, 'TERM')" : "'TERM'";
        statement.execute("""
            INSERT INTO knowledge_term_new(
                id, scope, connection_id, database_name, term, description,
                aliases_json, metric_expression, related_tables_json, related_columns_json, term_type,
                created_at, updated_at
            )
            SELECT
                id,
                scope,
                %s,
                %s,
                term,
                %s,
                %s,
                %s,
                %s,
                %s,
                %s,
                created_at,
                updated_at
            FROM knowledge_term
            """.formatted(
            connectionIdExpr,
            databaseNameExpr,
            descriptionExpr,
            aliasesExpr,
            metricExpressionExpr,
            relatedTablesExpr,
            relatedColumnsExpr,
            termTypeExpr
        ));
        statement.execute("DROP TABLE knowledge_term");
        statement.execute("ALTER TABLE knowledge_term_new RENAME TO knowledge_term");
    }

    private void normalizeKnowledgeExampleTable(Connection connection, Statement statement) throws SQLException {
        if (!hasTable(connection, "knowledge_example_sql")) {
            return;
        }
        boolean hasConnectionId = hasColumn(connection, "knowledge_example_sql", "connection_id");
        boolean hasDatabaseName = hasColumn(connection, "knowledge_example_sql", "database_name");
        boolean hasQuestionText = hasColumn(connection, "knowledge_example_sql", "question_text");
        boolean hasQuestionVariants = hasColumn(connection, "knowledge_example_sql", "question_variants_json");
        boolean hasSemanticSummary = hasColumn(connection, "knowledge_example_sql", "semantic_summary");
        boolean hasNormalizedSql = hasColumn(connection, "knowledge_example_sql", "normalized_sql");
        boolean hasSqlTemplate = hasColumn(connection, "knowledge_example_sql", "sql_template");
        boolean hasSqlAstJson = hasColumn(connection, "knowledge_example_sql", "sql_ast_json");
        boolean hasTableNamesJson = hasColumn(connection, "knowledge_example_sql", "table_names_json");
        boolean hasColumnNamesJson = hasColumn(connection, "knowledge_example_sql", "column_names_json");
        boolean hasMetricTagsJson = hasColumn(connection, "knowledge_example_sql", "metric_tags_json");
        boolean hasTimeTagsJson = hasColumn(connection, "knowledge_example_sql", "time_tags_json");
        boolean hasVerifiedFlag = hasColumn(connection, "knowledge_example_sql", "verified_flag");
        boolean hasQualityScore = hasColumn(connection, "knowledge_example_sql", "quality_score");
        boolean hasSourceType = hasColumn(connection, "knowledge_example_sql", "source_type");
        boolean hasSqlOperationType = hasColumn(connection, "knowledge_example_sql", "sql_operation_type");
        if (hasColumn(connection, "knowledge_example_sql", "connection_id")
            && hasColumn(connection, "knowledge_example_sql", "database_name")
            && hasQuestionText
            && hasQuestionVariants
            && hasSemanticSummary
            && hasNormalizedSql
            && hasSqlTemplate
            && hasSqlAstJson
            && hasTableNamesJson
            && hasColumnNamesJson
            && hasMetricTagsJson
            && hasTimeTagsJson
            && hasVerifiedFlag
            && hasQualityScore
            && hasSourceType
            && hasSqlOperationType) {
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
                question_text TEXT,
                question_variants_json TEXT,
                semantic_summary TEXT,
                normalized_sql TEXT,
                sql_template TEXT,
                sql_ast_json TEXT,
                table_names_json TEXT,
                column_names_json TEXT,
                metric_tags_json TEXT,
                time_tags_json TEXT,
                verified_flag INTEGER DEFAULT 1,
                quality_score REAL DEFAULT 0.95,
                source_type TEXT DEFAULT 'MANUAL',
                sql_operation_type TEXT DEFAULT 'SELECT',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """);
        String connectionIdExpr = hasConnectionId ? "COALESCE(connection_id, 0)" : "COALESCE(scope_connection_id, 0)";
        String databaseNameExpr = hasDatabaseName ? "COALESCE(database_name, '')" : "COALESCE(scope_database_name, '')";
        String questionTextExpr = hasQuestionText ? "COALESCE(question_text, description, '')" : "COALESCE(description, '')";
        String questionVariantsExpr = hasQuestionVariants ? "COALESCE(question_variants_json, '[]')" : "'[]'";
        String semanticSummaryExpr = hasSemanticSummary ? "COALESCE(semantic_summary, description, '')" : "COALESCE(description, '')";
        String normalizedSqlExpr = hasNormalizedSql ? "COALESCE(normalized_sql, LOWER(TRIM(sql_text)))" : "LOWER(TRIM(sql_text))";
        String sqlTemplateExpr = hasSqlTemplate ? "COALESCE(sql_template, LOWER(TRIM(sql_text)))" : "LOWER(TRIM(sql_text))";
        String sqlAstExpr = hasSqlAstJson ? "COALESCE(sql_ast_json, '{}')" : "'{}'";
        String tableNamesExpr = hasTableNamesJson ? "COALESCE(table_names_json, '[]')" : "'[]'";
        String columnNamesExpr = hasColumnNamesJson ? "COALESCE(column_names_json, '[]')" : "'[]'";
        String metricTagsExpr = hasMetricTagsJson ? "COALESCE(metric_tags_json, '[]')" : "'[]'";
        String timeTagsExpr = hasTimeTagsJson ? "COALESCE(time_tags_json, '[]')" : "'[]'";
        String verifiedExpr = hasVerifiedFlag ? "COALESCE(verified_flag, 1)" : "1";
        String qualityExpr = hasQualityScore ? "COALESCE(quality_score, 0.95)" : "0.95";
        String sourceTypeExpr = hasSourceType ? "COALESCE(source_type, 'MANUAL')" : "'MANUAL'";
        String operationExpr = hasSqlOperationType ? "COALESCE(sql_operation_type, 'SELECT')" : "'SELECT'";
        statement.execute("""
            INSERT INTO knowledge_example_sql_new(
                id, scope, connection_id, database_name, sql_text, description, term_ids_json,
                question_text, question_variants_json, semantic_summary, normalized_sql, sql_template,
                sql_ast_json, table_names_json, column_names_json, metric_tags_json, time_tags_json,
                verified_flag, quality_score, source_type, sql_operation_type, created_at, updated_at
            )
            SELECT
                id,
                scope,
                %s,
                %s,
                sql_text,
                description,
                term_ids_json,
                %s,
                %s,
                %s,
                %s,
                %s,
                %s,
                %s,
                %s,
                %s,
                %s,
                %s,
                %s,
                %s,
                %s,
                created_at,
                updated_at
            FROM knowledge_example_sql
            """.formatted(
            connectionIdExpr,
            databaseNameExpr,
            questionTextExpr,
            questionVariantsExpr,
            semanticSummaryExpr,
            normalizedSqlExpr,
            sqlTemplateExpr,
            sqlAstExpr,
            tableNamesExpr,
            columnNamesExpr,
            metricTagsExpr,
            timeTagsExpr,
            verifiedExpr,
            qualityExpr,
            sourceTypeExpr,
            operationExpr
        ));
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
