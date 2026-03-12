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

@Service
public class TableCopyServiceImpl implements TableCopyService {

    private static final Logger log = LoggerFactory.getLogger(TableCopyServiceImpl.class);
    private static final int COPY_BATCH_SIZE = 500;

    private final ConnectionService connectionService;
    private final SchemaService schemaService;
    private final RagVectorizeQueueService ragVectorizeQueueService;
    private final ExecutorService taskExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "table-copy-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, TableCopyTaskState> taskStateMap = new ConcurrentHashMap<>();

    public TableCopyServiceImpl(ConnectionService connectionService,
                                SchemaService schemaService,
                                RagVectorizeQueueService ragVectorizeQueueService) {
        this.connectionService = connectionService;
        this.schemaService = schemaService;
        this.ragVectorizeQueueService = ragVectorizeQueueService;
    }

    @PreDestroy
    public void shutdownExecutor() {
        taskExecutor.shutdownNow();
    }

    @Override
    public TableCopyVO copyTable(TableCopyReq req) {
        CopyContext context = buildCopyContext(req);
        boolean async = context.crossScope() && context.copyMode() == TableCopyMode.STRUCTURE_AND_DATA;
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

        if (taskState != null) {
            taskState.markRunning("CREATING_TABLE", "创建目标表", 8);
        }
        createTargetTable(context);

        boolean structureCreated = true;
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
                if (context.sameScope()) {
                    long copiedRows = copyDataWithinSameScope(context);
                    if (taskState != null) {
                        taskState.copiedRows = copiedRows;
                        taskState.totalRows = totalRows;
                        taskState.markRunning("FINALIZING", "刷新元数据缓存", 95);
                    }
                } else {
                    copyDataAcrossConnections(context, taskState, totalRows);
                    if (taskState != null) {
                        taskState.markRunning("FINALIZING", "刷新元数据缓存", 95);
                    }
                }
            } else if (taskState != null) {
                taskState.markRunning("FINALIZING", "刷新元数据缓存", 95);
            }

            schemaService.refreshSchemaCache(context.targetConnection().getId(), context.targetDatabaseName());
            ragVectorizeQueueService.enqueue(context.targetConnection().getId(), context.targetDatabaseName());
        } catch (Exception ex) {
            if (structureCreated) {
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

    private void createTargetTable(CopyContext context) {
        String createTableSql = buildCreateTableSql(context.targetTableName(), context.sourceTableDetail(), context.dbType());
        List<String> indexSqlList = buildCreateIndexSqlList(context.targetTableName(), context.sourceTableDetail(), context.dbType());
        List<String> commentSqlList = buildCommentSqlList(context.targetTableName(), context.sourceTableDetail(), context.dbType());

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

    private long copyDataWithinSameScope(CopyContext context) {
        List<String> columnNames = extractColumnNames(context.sourceTableDetail());
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

    private void copyDataAcrossConnections(CopyContext context, TableCopyTaskState taskState, long totalRows) {
        List<String> columnNames = extractColumnNames(context.sourceTableDetail());
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
            targetConnection.setAutoCommit(false);
            try (PreparedStatement selectStatement = sourceConnection.prepareStatement(selectSql);
                 PreparedStatement insertStatement = targetConnection.prepareStatement(insertSql)) {
                selectStatement.setFetchSize(COPY_BATCH_SIZE);
                try (ResultSet resultSet = selectStatement.executeQuery()) {
                    int pendingBatchSize = 0;
                    int columnCount = columnNames.size();
                    while (resultSet.next()) {
                        for (int index = 1; index <= columnCount; index += 1) {
                            insertStatement.setObject(index, resultSet.getObject(index));
                        }
                        insertStatement.addBatch();
                        pendingBatchSize += 1;
                        if (pendingBatchSize >= COPY_BATCH_SIZE) {
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
                throw ex;
            } finally {
                targetConnection.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new BusinessException(500, "跨库复制数据失败: " + ex.getMessage());
        }
        taskState.copiedRows = copiedRows;
        taskState.totalRows = totalRows;
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

    private List<String> extractColumnNames(TableDetailVO tableDetail) {
        return tableDetail.getColumns().stream()
            .map(TableDetailVO.ColumnDetailVO::getColumnName)
            .filter(Objects::nonNull)
            .toList();
    }

    private String joinIdentifiers(List<String> identifiers, String dbType) {
        return identifiers.stream().map(identifier -> quoteIdentifier(identifier, dbType)).reduce((left, right) -> left + ", " + right).orElse("");
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

    private record CopyContext(ConnectionEntity sourceConnection,
                               String sourceDatabaseName,
                               String sourceTableName,
                               ConnectionEntity targetConnection,
                               String targetDatabaseName,
                               String targetTableName,
                               TableCopyMode copyMode,
                               TableDetailVO sourceTableDetail,
                               String dbType) {

        boolean crossScope() {
            return !sameScope();
        }

        boolean sameScope() {
            return Objects.equals(sourceConnection.getId(), targetConnection.getId())
                && Objects.equals(normalizeDb(sourceDatabaseName), normalizeDb(targetDatabaseName));
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
