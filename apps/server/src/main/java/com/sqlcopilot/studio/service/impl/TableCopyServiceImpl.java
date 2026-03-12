package com.sqlcopilot.studio.service.impl;

import com.sqlcopilot.studio.dto.schema.TableCopyMode;
import com.sqlcopilot.studio.dto.schema.TableCopyReq;
import com.sqlcopilot.studio.dto.schema.TableCopyTaskVO;
import com.sqlcopilot.studio.dto.schema.TableCopyVO;
import com.sqlcopilot.studio.dto.schema.TableDetailVO;
import com.sqlcopilot.studio.entity.ConnectionEntity;
import com.sqlcopilot.studio.service.ConnectionService;
import com.sqlcopilot.studio.service.RagVectorizeQueueService;
import com.sqlcopilot.studio.service.SchemaService;
import com.sqlcopilot.studio.service.TableCopyService;
import com.sqlcopilot.studio.support.JdbcDriverResolver;
import com.sqlcopilot.studio.util.BusinessException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TableCopyServiceImpl implements TableCopyService {

    private static final Logger log = LoggerFactory.getLogger(TableCopyServiceImpl.class);
    private static final int SOURCE_FETCH_SIZE = 1000;
    private static final int TARGET_BATCH_SIZE = 1000;

    private final ConnectionService connectionService;
    private final SchemaService schemaService;
    private final RagVectorizeQueueService ragVectorizeQueueService;
    private final JdbcDriverResolver jdbcDriverResolver;
    private final ExecutorService taskExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "table-copy-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, TableCopyTaskState> taskStateMap = new ConcurrentHashMap<>();

    public TableCopyServiceImpl(ConnectionService connectionService,
                                SchemaService schemaService,
                                RagVectorizeQueueService ragVectorizeQueueService,
                                JdbcDriverResolver jdbcDriverResolver) {
        this.connectionService = connectionService;
        this.schemaService = schemaService;
        this.ragVectorizeQueueService = ragVectorizeQueueService;
        this.jdbcDriverResolver = jdbcDriverResolver;
    }

    @PreDestroy
    public void shutdownExecutor() {
        taskExecutor.shutdownNow();
    }

    @Override
    public TableCopyVO copyTable(TableCopyReq req) {
        CopyContext context = buildCopyContext(req);
        boolean async = context.crossConnection() && context.copyMode() == TableCopyMode.STRUCTURE_AND_DATA;
        if (async) {
            String taskId = UUID.randomUUID().toString();
            TableCopyTaskState taskState = TableCopyTaskState.pending(taskId, context);
            taskStateMap.put(taskId, taskState);
            taskExecutor.submit(() -> runAsyncCopy(taskState));
            TableCopyVO vo = new TableCopyVO();
            vo.setSuccess(true);
            vo.setAsync(true);
            vo.setTaskId(taskId);
            vo.setMessage("复制任务已创建");
            vo.setCopyMode(context.copyMode());
            vo.setTargetConnectionId(context.targetConnection().getId());
            vo.setTargetDatabaseName(context.targetDatabaseName());
            vo.setTargetTableName(context.targetTableName());
            return vo;
        }

        executeCopy(context, null);
        TableCopyVO vo = new TableCopyVO();
        vo.setSuccess(true);
        vo.setAsync(false);
        vo.setMessage(context.copyMode() == TableCopyMode.STRUCTURE_ONLY ? "表结构复制成功" : "表结构和数据复制成功");
        vo.setCopyMode(context.copyMode());
        vo.setTargetConnectionId(context.targetConnection().getId());
        vo.setTargetDatabaseName(context.targetDatabaseName());
        vo.setTargetTableName(context.targetTableName());
        return vo;
    }

    @Override
    public TableCopyTaskVO getTask(String taskId) {
        TableCopyTaskState state = taskStateMap.get(normalize(taskId));
        if (state == null) {
            throw new BusinessException(404, "复制任务不存在或已过期");
        }
        return state.toVo();
    }

    private void runAsyncCopy(TableCopyTaskState taskState) {
        try {
            executeCopy(taskState.context, taskState);
            taskState.markSuccess("复制完成", 100);
        } catch (Exception ex) {
            log.warn("表复制任务失败, taskId={}, reason={}", taskState.taskId, ex.getMessage());
            taskState.markFailed(ex.getMessage());
        }
    }

    private void executeCopy(CopyContext context, TableCopyTaskState taskState) {
        if (taskState != null) {
            taskState.markRunning("VALIDATING", "校验复制请求", 2);
        }
        if (tableExists(context.targetConnection().getId(), context.targetDatabaseName(), context.targetTableName())) {
            throw new BusinessException(400, "目标表已存在: " + context.targetTableName());
        }

        boolean keepTableOnCopyFailure = context.crossConnection() && context.copyMode() == TableCopyMode.STRUCTURE_AND_DATA;
        long copiedRows = 0L;
        if (taskState != null) {
            taskState.markRunning("CREATING_TABLE", "创建目标表", 8);
        }
        if (tryExecuteFastPathCopy(context, taskState)) {
            if (taskState != null) {
                taskState.markRunning("FINALIZING", "刷新元数据缓存", 95);
            }
            schemaService.refreshSchemaCache(context.targetConnection().getId(), context.targetDatabaseName());
            ragVectorizeQueueService.enqueue(context.targetConnection().getId(), context.targetDatabaseName());
            return;
        }

        createTargetTable(context);

        try {
            if (context.copyMode() == TableCopyMode.STRUCTURE_AND_DATA) {
                if (taskState != null) {
                    taskState.markRunning("COUNTING_ROWS", "统计源表行数", 15);
                }
                long totalRows = countSourceRows(context);
                if (taskState != null) {
                    taskState.totalRows = totalRows;
                    taskState.copiedRows = 0L;
                    taskState.markRunning("COPYING_DATA", "迁移表数据", totalRows <= 0 ? 85 : 20);
                }
                if (context.sameConnection() && context.sameDatabase()) {
                    copiedRows = copyDataWithinSameDatabase(context);
                } else {
                    copiedRows = copyDataAcrossConnections(context, taskState, totalRows);
                }
                if (taskState != null) {
                    taskState.copiedRows = copiedRows;
                    taskState.totalRows = totalRows;
                    taskState.markRunning("FINALIZING", "刷新元数据缓存", 95);
                }
            } else if (taskState != null) {
                taskState.markRunning("FINALIZING", "刷新元数据缓存", 95);
            }

            schemaService.refreshSchemaCache(context.targetConnection().getId(), context.targetDatabaseName());
            ragVectorizeQueueService.enqueue(context.targetConnection().getId(), context.targetDatabaseName());
        } catch (Exception ex) {
            if (!keepTableOnCopyFailure) {
                cleanupTargetTableQuietly(context);
            }
            throw ex;
        }
    }

    private CopyContext buildCopyContext(TableCopyReq req) {
        ConnectionEntity sourceConnection = connectionService.getConnectionEntity(req.getSourceConnectionId());
        ConnectionEntity targetConnection = connectionService.getConnectionEntity(req.getTargetConnectionId());
        String sourceDbType = normalizeDbType(sourceConnection.getDbType());
        String targetDbType = normalizeDbType(targetConnection.getDbType());
        if (!Objects.equals(sourceDbType, targetDbType)) {
            throw new BusinessException(400, "仅支持相同数据库类型之间复制表");
        }
        if (targetConnection.getReadOnly() != null && targetConnection.getReadOnly() == 1) {
            throw new BusinessException(403, "目标连接为只读模式，禁止复制表");
        }

        String sourceDatabaseName = resolveDatabaseName(sourceConnection, req.getSourceDatabaseName());
        String targetDatabaseName = resolveDatabaseName(targetConnection, req.getTargetDatabaseName());
        String sourceTableName = normalize(req.getSourceTableName());
        String targetTableName = normalize(req.getTargetTableName());
        if (sourceTableName.isBlank()) {
            throw new BusinessException(400, "源表名不能为空");
        }
        if (targetTableName.isBlank()) {
            throw new BusinessException(400, "目标表名不能为空");
        }
        if (!tableExists(sourceConnection.getId(), sourceDatabaseName, sourceTableName)) {
            throw new BusinessException(404, "源表不存在: " + sourceTableName);
        }
        if (tableExists(targetConnection.getId(), targetDatabaseName, targetTableName)) {
            throw new BusinessException(400, "目标表已存在: " + targetTableName);
        }

        TableDetailVO sourceTableDetail = schemaService.getTableDetail(
            sourceConnection.getId(),
            sourceDatabaseName,
            sourceTableName
        );
        if (sourceTableDetail == null || sourceTableDetail.getColumns() == null || sourceTableDetail.getColumns().isEmpty()) {
            throw new BusinessException(400, "未读取到源表结构，无法复制");
        }
        return new CopyContext(
            sourceConnection,
            sourceDatabaseName,
            sourceTableName,
            targetConnection,
            targetDatabaseName,
            targetTableName,
            req.getCopyMode(),
            sourceTableDetail,
            sourceDbType
        );
    }

    private boolean tableExists(Long connectionId, String databaseName, String tableName) {
        List<String> tableNames = schemaService.listObjectNames(connectionId, databaseName, "tables");
        return tableNames.stream().anyMatch(item -> item.equalsIgnoreCase(normalize(tableName)));
    }

    private String resolveDatabaseName(ConnectionEntity connection, String requestedDatabaseName) {
        String requested = normalize(requestedDatabaseName);
        if (!requested.isBlank()) {
            return requested;
        }
        String configured = normalize(connection.getDatabaseName());
        if (!configured.isBlank()) {
            return configured;
        }
        if ("SQLITE".equalsIgnoreCase(normalizeDbType(connection.getDbType()))) {
            return "main";
        }
        return configured;
    }

    private boolean tryExecuteFastPathCopy(CopyContext context, TableCopyTaskState taskState) {
        if (!context.sameConnection()) {
            return false;
        }
        if (context.copyMode() == TableCopyMode.STRUCTURE_AND_DATA && hasGeneratedColumns(context.sourceTableDetail())) {
            return false;
        }
        JdbcDriverResolver.TableCopyFastPathSpec spec = jdbcDriverResolver.findTableCopyFastPathSpec(context.dbType());
        if (spec == null) {
            return false;
        }
        String sqlTemplate = resolveFastPathSqlTemplate(context, spec);
        if (normalize(sqlTemplate).isBlank()) {
            return false;
        }
        try (Connection connection = connectionService.openTargetConnection(context.targetConnection().getId())) {
            applyDatabaseContext(connection, context.dbType(), context.targetDatabaseName());
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                List<String> statements = splitSqlStatements(renderCopySqlTemplate(sqlTemplate, context));
                long totalRows = 0L;
                if (context.copyMode() == TableCopyMode.STRUCTURE_AND_DATA) {
                    totalRows = countSourceRows(context);
                    if (taskState != null) {
                        taskState.totalRows = totalRows;
                    }
                }
                long affectedRows = executeSqlStatements(connection, statements);
                connection.commit();
                if (taskState != null && context.copyMode() == TableCopyMode.STRUCTURE_AND_DATA) {
                    taskState.copiedRows = affectedRows > 0 ? affectedRows : totalRows;
                }
                return true;
            } catch (Exception ex) {
                connection.rollback();
                cleanupTargetTableQuietly(context);
                throw ex;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException ex) {
            throw new BusinessException(500, "执行同连接快速复制失败: " + ex.getMessage());
        }
    }

    private String resolveFastPathSqlTemplate(CopyContext context, JdbcDriverResolver.TableCopyFastPathSpec spec) {
        if (context.sameDatabase()) {
            return context.copyMode() == TableCopyMode.STRUCTURE_ONLY
                ? spec.structureOnlySameDatabaseSql()
                : spec.structureAndDataSameDatabaseSql();
        }
        return context.copyMode() == TableCopyMode.STRUCTURE_ONLY
            ? spec.structureOnlyCrossDatabaseSql()
            : spec.structureAndDataCrossDatabaseSql();
    }

    private void createTargetTable(CopyContext context) {
        String createTableSql = tryLoadCreateTableSql(context);
        boolean usedSystemDdl = !createTableSql.isBlank();
        List<String> indexSqlList = usedSystemDdl
            ? List.of()
            : buildCreateIndexSqlList(context.targetTableName(), context.sourceTableDetail(), context.dbType());
        List<String> commentSqlList = usedSystemDdl
            ? List.of()
            : buildCommentSqlList(context.targetTableName(), context.sourceTableDetail(), context.dbType());
        if (!usedSystemDdl) {
            createTableSql = buildCreateTableSql(context.targetTableName(), context.sourceTableDetail(), context.dbType());
        }

        try (Connection connection = connectionService.openTargetConnection(context.targetConnection().getId())) {
            applyDatabaseContext(connection, context.dbType(), context.targetDatabaseName());
            try (Statement statement = connection.createStatement()) {
                // 关键操作：复制结构时先创建表，再按数据库方言补齐索引和注释，避免多数据库语法差异导致整条 DDL 失败。
                statement.execute(createTableSql);
                for (String sql : indexSqlList) {
                    statement.execute(sql);
                }
                for (String sql : commentSqlList) {
                    statement.execute(sql);
                }
            }
        } catch (SQLException ex) {
            throw new BusinessException(500, "创建目标表失败: " + ex.getMessage());
        }
    }

    private String tryLoadCreateTableSql(CopyContext context) {
        JdbcDriverResolver.CreateTableSpec spec = jdbcDriverResolver.findCreateTableSpec(context.dbType());
        if (spec == null || normalize(spec.sql()).isBlank()) {
            return "";
        }
        try (Connection connection = connectionService.openTargetConnection(context.sourceConnection().getId())) {
            applyDatabaseContext(connection, context.dbType(), context.sourceDatabaseName());
            String sql = renderCreateTableSql(spec.sql(), context);
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return "";
                }
                String ddl = readCreateTableDdl(rs, spec);
                if (ddl.isBlank()) {
                    return "";
                }
                return rewriteCreateTableTarget(ddl, context);
            }
        } catch (Exception ex) {
            log.warn("读取源表 DDL 失败，回退结构重建, source={}.{}, reason={}",
                context.sourceDatabaseName(), context.sourceTableName(), ex.getMessage());
            return "";
        }
    }

    private String renderCopySqlTemplate(String sqlTemplate, CopyContext context) {
        return sqlTemplate
            .replace("{source_table}", normalize(context.sourceTableName()))
            .replace("{target_table}", normalize(context.targetTableName()))
            .replace("{source_table_literal}", toSqlLiteral(context.sourceTableName()))
            .replace("{target_table_literal}", toSqlLiteral(context.targetTableName()))
            .replace("{source_database_literal}", toSqlLiteral(context.sourceDatabaseName()))
            .replace("{target_database_literal}", toSqlLiteral(context.targetDatabaseName()))
            .replace("{quoted_source_table}", quoteIdentifier(context.sourceTableName(), context.dbType()))
            .replace("{quoted_target_table}", quoteIdentifier(context.targetTableName(), context.dbType()))
            .replace("{qualified_source_table}", qualifyTableName(context.sourceDatabaseName(), context.sourceTableName(), context.dbType()))
            .replace("{qualified_target_table}", qualifyTableName(context.targetDatabaseName(), context.targetTableName(), context.dbType()));
    }

    private String renderCreateTableSql(String sqlTemplate, CopyContext context) {
        return sqlTemplate
            .replace("{source_table}", normalize(context.sourceTableName()))
            .replace("{source_table_literal}", toSqlLiteral(context.sourceTableName()))
            .replace("{source_database_literal}", toSqlLiteral(context.sourceDatabaseName()))
            .replace("{quoted_source_table}", quoteIdentifier(context.sourceTableName(), context.dbType()));
    }

    private String readCreateTableDdl(ResultSet rs, JdbcDriverResolver.CreateTableSpec spec) throws SQLException {
        String ddlColumnLabel = normalize(spec.ddlColumnLabel());
        if (!ddlColumnLabel.isBlank()) {
            return normalize(rs.getString(ddlColumnLabel));
        }
        Integer ddlColumnIndex = spec.ddlColumnIndex();
        int columnIndex = ddlColumnIndex == null || ddlColumnIndex <= 0 ? 1 : ddlColumnIndex;
        return normalize(rs.getString(columnIndex));
    }

    private String rewriteCreateTableTarget(String sourceDdl, CopyContext context) {
        String ddl = sourceDdl.trim();
        if (ddl.isBlank()) {
            return ddl;
        }
        String targetIdentifier = quoteIdentifier(context.targetTableName(), context.dbType());
        String sourceIdentifier = quoteIdentifier(context.sourceTableName(), context.dbType());
        String qualifiedSourceIdentifier = qualifyTableName(context.sourceDatabaseName(), context.sourceTableName(), context.dbType());
        String rewritten = replaceCreateTableIdentifier(ddl, qualifiedSourceIdentifier, targetIdentifier);
        if (rewritten.equals(ddl)) {
            rewritten = replaceCreateTableIdentifier(ddl, sourceIdentifier, targetIdentifier);
        }
        if (rewritten.equals(ddl)) {
            rewritten = replaceCreateTableIdentifier(ddl, normalize(context.sourceTableName()), normalize(context.targetTableName()));
        }
        if (!normalize(context.sourceTableName()).equalsIgnoreCase(normalize(context.targetTableName()))
            && rewritten.equals(ddl)) {
            throw new BusinessException(500, "源表 DDL 改写失败，未识别到可替换的 CREATE TABLE 表名");
        }
        return rewritten;
    }

    private String replaceCreateTableIdentifier(String ddl, String sourceIdentifier, String targetIdentifier) {
        if (normalize(sourceIdentifier).isBlank() || normalize(targetIdentifier).isBlank()) {
            return ddl;
        }
        Pattern pattern = Pattern.compile(
            "(?is)(create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?)" + Pattern.quote(sourceIdentifier)
        );
        Matcher matcher = pattern.matcher(ddl);
        if (!matcher.find()) {
            return ddl;
        }
        return matcher.replaceFirst("$1" + Matcher.quoteReplacement(targetIdentifier));
    }

    private long countSourceRows(CopyContext context) {
        String sql = "SELECT COUNT(*) FROM " + quoteIdentifier(context.sourceTableName(), context.dbType());
        try (Connection connection = connectionService.openTargetConnection(context.sourceConnection().getId())) {
            applyDatabaseContext(connection, context.dbType(), context.sourceDatabaseName());
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(sql)) {
                if (resultSet.next()) {
                    return resultSet.getLong(1);
                }
                return 0L;
            }
        } catch (SQLException ex) {
            throw new BusinessException(500, "统计源表行数失败: " + ex.getMessage());
        }
    }

    private long copyDataWithinSameDatabase(CopyContext context) {
        List<String> columnNames = extractInsertableColumnNames(context.sourceTableDetail());
        if (columnNames.isEmpty()) {
            throw new BusinessException(400, "源表不存在可写入的普通列，无法复制数据");
        }
        String columnSql = joinIdentifiers(columnNames, context.dbType());
        String sql = "INSERT INTO " + quoteIdentifier(context.targetTableName(), context.dbType())
            + " (" + columnSql + ") SELECT " + columnSql
            + " FROM " + quoteIdentifier(context.sourceTableName(), context.dbType());
        try (Connection connection = connectionService.openTargetConnection(context.targetConnection().getId())) {
            applyDatabaseContext(connection, context.dbType(), context.targetDatabaseName());
            try (Statement statement = connection.createStatement()) {
                return statement.executeUpdate(sql);
            }
        } catch (SQLException ex) {
            throw new BusinessException(500, "复制表数据失败: " + ex.getMessage());
        }
    }

    private long copyDataAcrossConnections(CopyContext context, TableCopyTaskState taskState, long totalRows) {
        List<String> columnNames = extractInsertableColumnNames(context.sourceTableDetail());
        if (columnNames.isEmpty()) {
            throw new BusinessException(400, "源表不存在可写入的普通列，无法复制数据");
        }
        String selectSql = "SELECT " + joinIdentifiers(columnNames, context.dbType())
            + " FROM " + quoteIdentifier(context.sourceTableName(), context.dbType());
        String insertSql = "INSERT INTO " + quoteIdentifier(context.targetTableName(), context.dbType())
            + " (" + joinIdentifiers(columnNames, context.dbType()) + ") VALUES ("
            + String.join(", ", columnNames.stream().map(item -> "?").toList()) + ")";
        long copiedRows = 0L;
        try (Connection sourceConnection = connectionService.openTargetConnection(context.sourceConnection().getId());
             Connection targetConnection = connectionService.openTargetConnection(context.targetConnection().getId())) {
            applyDatabaseContext(sourceConnection, context.dbType(), context.sourceDatabaseName());
            applyDatabaseContext(targetConnection, context.dbType(), context.targetDatabaseName());
            boolean sourceOriginalAutoCommit = sourceConnection.getAutoCommit();
            sourceConnection.setAutoCommit(false);
            targetConnection.setAutoCommit(false);
            try (PreparedStatement selectStatement = sourceConnection.prepareStatement(
                    selectSql,
                    ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_READ_ONLY
                );
                 PreparedStatement insertStatement = targetConnection.prepareStatement(insertSql)) {
                selectStatement.setFetchSize(SOURCE_FETCH_SIZE);
                try (ResultSet resultSet = selectStatement.executeQuery()) {
                    int pendingBatchSize = 0;
                    int columnCount = columnNames.size();
                    while (resultSet.next()) {
                        for (int index = 1; index <= columnCount; index += 1) {
                            insertStatement.setObject(index, resultSet.getObject(index));
                        }
                        insertStatement.addBatch();
                        pendingBatchSize += 1;
                        if (pendingBatchSize >= TARGET_BATCH_SIZE) {
                            copiedRows += executeInsertBatch(insertStatement, targetConnection, pendingBatchSize);
                            pendingBatchSize = 0;
                            updateTaskProgress(taskState, copiedRows, totalRows);
                        }
                    }
                    if (pendingBatchSize > 0) {
                        copiedRows += executeInsertBatch(insertStatement, targetConnection, pendingBatchSize);
                        updateTaskProgress(taskState, copiedRows, totalRows);
                    }
                }
            } catch (SQLException ex) {
                targetConnection.rollback();
                throw new BusinessException(500, "建表成功，数据复制失败: " + ex.getMessage());
            } finally {
                sourceConnection.setAutoCommit(sourceOriginalAutoCommit);
                targetConnection.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new BusinessException(500, "建表成功，数据复制失败: " + ex.getMessage());
        }
        if (taskState != null) {
            taskState.copiedRows = copiedRows;
            taskState.totalRows = totalRows;
        }
        return copiedRows;
    }

    private long executeInsertBatch(PreparedStatement insertStatement, Connection targetConnection, int expectedRows) throws SQLException {
        int[] counts = insertStatement.executeBatch();
        targetConnection.commit();
        long copiedRows = 0L;
        for (int count : counts) {
            copiedRows += count >= 0 ? count : 1;
        }
        return Math.max(copiedRows, expectedRows);
    }

    private void updateTaskProgress(TableCopyTaskState taskState, long copiedRows, long totalRows) {
        if (taskState == null) {
            return;
        }
        taskState.copiedRows = copiedRows;
        taskState.totalRows = totalRows;
        if (totalRows <= 0) {
            taskState.markRunning("COPYING_DATA", "迁移表数据", 85);
            return;
        }
        int progressPercent = 20 + (int) Math.min(75, Math.round((copiedRows * 75.0d) / totalRows));
        taskState.markRunning("COPYING_DATA", "迁移表数据", progressPercent);
    }

    private void cleanupTargetTableQuietly(CopyContext context) {
        String sql = "DROP TABLE " + quoteIdentifier(context.targetTableName(), context.dbType());
        try (Connection connection = connectionService.openTargetConnection(context.targetConnection().getId())) {
            applyDatabaseContext(connection, context.dbType(), context.targetDatabaseName());
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        } catch (Exception ex) {
            log.warn("复制失败后清理目标表失败, target={}.{}, reason={}",
                context.targetDatabaseName(), context.targetTableName(), ex.getMessage());
        }
    }

    private long executeSqlStatements(Connection connection, List<String> statements) throws SQLException {
        long affectedRows = 0L;
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                if (sql.isBlank()) {
                    continue;
                }
                statement.execute(sql);
                int updateCount = statement.getUpdateCount();
                if (updateCount > 0) {
                    affectedRows += updateCount;
                }
            }
        }
        return affectedRows;
    }

    private List<String> splitSqlStatements(String sqlText) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int i = 0; i < sqlText.length(); i += 1) {
            char ch = sqlText.charAt(i);
            if (ch == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            }
            if (ch == ';' && !inSingleQuote && !inDoubleQuote) {
                String segment = current.toString().trim();
                if (!segment.isBlank()) {
                    statements.add(segment);
                }
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        String tail = current.toString().trim();
        if (!tail.isBlank()) {
            statements.add(tail);
        }
        return statements;
    }

    private String buildCreateTableSql(String targetTableName, TableDetailVO tableDetail, String dbType) {
        List<String> definitions = new ArrayList<>();
        boolean sqliteInlinePrimaryKeyHandled = false;
        List<String> primaryKeyColumns = new ArrayList<>();
        for (TableDetailVO.ColumnDetailVO column : tableDetail.getColumns()) {
            boolean inlinePrimaryKey = isSqliteInlinePrimaryKeyColumn(column, dbType, tableDetail);
            if (inlinePrimaryKey) {
                sqliteInlinePrimaryKeyHandled = true;
            }
            definitions.add(buildColumnDefinitionSql(column, dbType, inlinePrimaryKey));
            if (Boolean.TRUE.equals(column.getPrimaryKey()) && !inlinePrimaryKey) {
                primaryKeyColumns.add(quoteIdentifier(column.getColumnName(), dbType));
            }
        }
        if (!primaryKeyColumns.isEmpty() && !sqliteInlinePrimaryKeyHandled) {
            definitions.add("PRIMARY KEY (" + String.join(", ", primaryKeyColumns) + ")");
        }

        StringBuilder sql = new StringBuilder();
        sql.append("CREATE TABLE ")
            .append(quoteIdentifier(targetTableName, dbType))
            .append(" (\n  ")
            .append(String.join(",\n  ", definitions))
            .append("\n)");
        if ("MYSQL".equals(dbType) && !normalize(tableDetail.getTableComment()).isBlank()) {
            sql.append(" COMMENT='").append(escapeSingleQuote(tableDetail.getTableComment())).append("'");
        }
        return sql.toString();
    }

    private List<String> buildCreateIndexSqlList(String targetTableName, TableDetailVO tableDetail, String dbType) {
        List<TableDetailVO.IndexDetailVO> indexes = tableDetail.getIndexes() == null ? List.of() : tableDetail.getIndexes();
        List<String> sqlList = new ArrayList<>();
        for (TableDetailVO.IndexDetailVO index : indexes) {
            String indexName = normalize(index.getIndexName());
            if (indexName.isBlank()) {
                continue;
            }
            List<String> columns = index.getColumns() == null
                ? List.of()
                : index.getColumns().stream()
                    .map(column -> quoteIdentifier(column, dbType))
                    .toList();
            if (columns.isEmpty()) {
                continue;
            }
            StringBuilder sql = new StringBuilder();
            sql.append("CREATE ");
            if (Boolean.TRUE.equals(index.getUnique())) {
                sql.append("UNIQUE ");
            }
            sql.append("INDEX ")
                .append(quoteIdentifier(indexName, dbType))
                .append(" ON ")
                .append(quoteIdentifier(targetTableName, dbType))
                .append(" (")
                .append(String.join(", ", columns))
                .append(")");
            sqlList.add(sql.toString());
        }
        return sqlList;
    }

    private List<String> buildCommentSqlList(String targetTableName, TableDetailVO tableDetail, String dbType) {
        List<String> sqlList = new ArrayList<>();
        String tableComment = normalize(tableDetail.getTableComment());
        if (("POSTGRESQL".equals(dbType) || "ORACLE".equals(dbType)) && !tableComment.isBlank()) {
            sqlList.add("COMMENT ON TABLE " + quoteIdentifier(targetTableName, dbType)
                + " IS '" + escapeSingleQuote(tableComment) + "'");
        }
        if (!"POSTGRESQL".equals(dbType) && !"ORACLE".equals(dbType)) {
            return sqlList;
        }
        for (TableDetailVO.ColumnDetailVO column : tableDetail.getColumns()) {
            String columnComment = normalize(column.getColumnComment());
            if (columnComment.isBlank()) {
                continue;
            }
            sqlList.add("COMMENT ON COLUMN " + quoteIdentifier(targetTableName, dbType)
                + "." + quoteIdentifier(column.getColumnName(), dbType)
                + " IS '" + escapeSingleQuote(columnComment) + "'");
        }
        return sqlList;
    }

    private String buildColumnDefinitionSql(TableDetailVO.ColumnDetailVO column, String dbType, boolean inlinePrimaryKey) {
        String columnName = quoteIdentifier(column.getColumnName(), dbType);
        String baseType = normalize(column.getDataType());
        String typeSql = appendColumnSizeIfNeeded(baseType, column.getColumnSize(), column.getDecimalDigits());
        List<String> fragments = new ArrayList<>();
        fragments.add(columnName + " " + typeSql);

        boolean isPostgresSerial = baseType.toUpperCase(Locale.ROOT).contains("SERIAL");
        if (Boolean.FALSE.equals(column.getNullable()) && !inlinePrimaryKey) {
            fragments.add("NOT NULL");
        }
        if (Boolean.TRUE.equals(column.getAutoIncrement())) {
            if ("MYSQL".equals(dbType)) {
                fragments.add("AUTO_INCREMENT");
            } else if ("SQLSERVER".equals(dbType)) {
                fragments.add("IDENTITY(1,1)");
            } else if (("POSTGRESQL".equals(dbType) || "ORACLE".equals(dbType)) && !isPostgresSerial) {
                fragments.add("GENERATED BY DEFAULT AS IDENTITY");
            }
        }
        if (Boolean.TRUE.equals(column.getDefaultCurrentTimestamp())) {
            fragments.add("DEFAULT CURRENT_TIMESTAMP");
        } else if (!normalize(column.getDefaultValue()).isBlank()) {
            fragments.add("DEFAULT " + column.getDefaultValue().trim());
        }
        if ("MYSQL".equals(dbType) && Boolean.TRUE.equals(column.getOnUpdateCurrentTimestamp())) {
            fragments.add("ON UPDATE CURRENT_TIMESTAMP");
        }
        if (inlinePrimaryKey) {
            fragments.add("PRIMARY KEY");
            fragments.add("AUTOINCREMENT");
        }
        if ("MYSQL".equals(dbType) && !normalize(column.getColumnComment()).isBlank()) {
            fragments.add("COMMENT '" + escapeSingleQuote(column.getColumnComment()) + "'");
        }
        return String.join(" ", fragments);
    }

    private boolean isSqliteInlinePrimaryKeyColumn(TableDetailVO.ColumnDetailVO column, String dbType, TableDetailVO tableDetail) {
        if (!"SQLITE".equals(dbType)) {
            return false;
        }
        if (!Boolean.TRUE.equals(column.getAutoIncrement()) || !Boolean.TRUE.equals(column.getPrimaryKey())) {
            return false;
        }
        long primaryKeyCount = tableDetail.getColumns().stream().filter(item -> Boolean.TRUE.equals(item.getPrimaryKey())).count();
        return primaryKeyCount == 1 && normalize(column.getDataType()).toUpperCase(Locale.ROOT).contains("INT");
    }

    private String appendColumnSizeIfNeeded(String baseType, Integer columnSize, Integer decimalDigits) {
        if (baseType.contains("(") || columnSize == null || columnSize <= 0) {
            return baseType;
        }
        String upper = baseType.toUpperCase(Locale.ROOT);
        if (upper.contains("TEXT") || upper.contains("BLOB") || upper.contains("DATE")
            || upper.contains("TIME") || upper.contains("JSON") || upper.contains("BOOL")
            || upper.contains("CLOB") || upper.contains("SERIAL")) {
            return baseType;
        }
        if (decimalDigits != null && decimalDigits > 0) {
            return baseType + "(" + columnSize + "," + decimalDigits + ")";
        }
        return baseType + "(" + columnSize + ")";
    }

    private List<String> extractInsertableColumnNames(TableDetailVO tableDetail) {
        return tableDetail.getColumns().stream()
            .filter(column -> !Boolean.TRUE.equals(column.getGenerated()))
            .map(TableDetailVO.ColumnDetailVO::getColumnName)
            .filter(Objects::nonNull)
            .toList();
    }

    private boolean hasGeneratedColumns(TableDetailVO tableDetail) {
        return tableDetail.getColumns().stream().anyMatch(column -> Boolean.TRUE.equals(column.getGenerated()));
    }

    private String joinIdentifiers(List<String> identifiers, String dbType) {
        return identifiers.stream().map(identifier -> quoteIdentifier(identifier, dbType)).reduce((left, right) -> left + ", " + right).orElse("");
    }

    private String qualifyTableName(String databaseName, String tableName, String dbType) {
        String normalizedDatabaseName = normalize(databaseName);
        String quotedTable = quoteIdentifier(tableName, dbType);
        if (normalizedDatabaseName.isBlank()) {
            return quotedTable;
        }
        return switch (normalize(dbType).toUpperCase(Locale.ROOT)) {
            case "MYSQL", "SQLSERVER", "POSTGRESQL", "ORACLE" ->
                quoteIdentifier(normalizedDatabaseName, dbType) + "." + quotedTable;
            default -> quotedTable;
        };
    }

    private String quoteIdentifier(String identifier, String dbType) {
        String normalized = normalize(identifier);
        if (normalized.isBlank()) {
            return normalized;
        }
        return switch (dbType) {
            case "SQLSERVER" -> "[" + normalized + "]";
            case "POSTGRESQL", "ORACLE" -> "\"" + normalized.replace("\"", "\"\"") + "\"";
            default -> "`" + normalized.replace("`", "``") + "`";
        };
    }

    private void applyDatabaseContext(Connection connection, String dbType, String databaseName) throws SQLException {
        String normalizedDatabaseName = normalize(databaseName);
        if (normalizedDatabaseName.isBlank()) {
            return;
        }
        if ("MYSQL".equals(dbType) || "POSTGRESQL".equals(dbType)) {
            connection.setCatalog(normalizedDatabaseName);
            return;
        }
        if ("SQLSERVER".equals(dbType) || "ORACLE".equals(dbType)) {
            connection.setSchema(normalizedDatabaseName);
        }
    }

    private String normalizeDbType(String dbType) {
        return normalize(dbType).toUpperCase(Locale.ROOT);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String escapeSingleQuote(String value) {
        return normalize(value).replace("'", "''");
    }

    private String toSqlLiteral(String value) {
        return "'" + escapeSingleQuote(value) + "'";
    }

    private record CopyContext(ConnectionEntity sourceConnection,
                               String sourceDatabaseName,
                               String sourceTableName,
                               ConnectionEntity targetConnection,
                               String targetDatabaseName,
                               String targetTableName,
                               TableCopyMode copyMode,
                               TableDetailVO sourceTableDetail,
                               String dbType) {

        boolean crossConnection() {
            return !sameConnection();
        }

        boolean sameConnection() {
            return Objects.equals(sourceConnection.getId(), targetConnection.getId());
        }

        boolean sameDatabase() {
            return Objects.equals(normalizeDb(sourceDatabaseName), normalizeDb(targetDatabaseName));
        }

        private String normalizeDb(String value) {
            return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        }
    }

    private static final class TableCopyTaskState {

        private final String taskId;
        private final CopyContext context;
        private volatile String status;
        private volatile String stage;
        private volatile String message;
        private volatile int progressPercent;
        private volatile long copiedRows;
        private volatile long totalRows;
        private volatile long updatedAt;

        private TableCopyTaskState(String taskId, CopyContext context) {
            this.taskId = taskId;
            this.context = context;
            this.status = "PENDING";
            this.stage = "PENDING";
            this.message = "等待执行";
            this.progressPercent = 0;
            this.copiedRows = 0L;
            this.totalRows = 0L;
            this.updatedAt = System.currentTimeMillis();
        }

        private static TableCopyTaskState pending(String taskId, CopyContext context) {
            return new TableCopyTaskState(taskId, context);
        }

        private void markRunning(String stage, String message, int progressPercent) {
            this.status = "RUNNING";
            this.stage = stage;
            this.message = message;
            this.progressPercent = Math.max(this.progressPercent, Math.min(progressPercent, 99));
            this.updatedAt = System.currentTimeMillis();
        }

        private void markSuccess(String message, int progressPercent) {
            this.status = "SUCCESS";
            this.stage = "COMPLETED";
            this.message = message;
            this.progressPercent = Math.max(100, progressPercent);
            this.updatedAt = System.currentTimeMillis();
        }

        private void markFailed(String message) {
            this.status = "FAILED";
            this.stage = "FAILED";
            this.message = normalizeFailureMessage(message);
            this.updatedAt = System.currentTimeMillis();
        }

        private String normalizeFailureMessage(String message) {
            String normalized = message == null ? "" : message.trim();
            return normalized.isBlank() ? "复制任务失败" : normalized;
        }

        private TableCopyTaskVO toVo() {
            TableCopyTaskVO vo = new TableCopyTaskVO();
            vo.setTaskId(taskId);
            vo.setStatus(status);
            vo.setStage(stage);
            vo.setMessage(message);
            vo.setProgressPercent(progressPercent);
            vo.setCopiedRows(copiedRows);
            vo.setTotalRows(totalRows);
            vo.setSourceConnectionId(context.sourceConnection().getId());
            vo.setSourceDatabaseName(context.sourceDatabaseName());
            vo.setSourceTableName(context.sourceTableName());
            vo.setTargetConnectionId(context.targetConnection().getId());
            vo.setTargetDatabaseName(context.targetDatabaseName());
            vo.setTargetTableName(context.targetTableName());
            vo.setCopyMode(context.copyMode());
            vo.setUpdatedAt(updatedAt);
            return vo;
        }
    }
}
