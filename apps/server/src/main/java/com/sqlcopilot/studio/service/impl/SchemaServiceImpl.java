package com.sqlcopilot.studio.service.impl;

import com.sqlcopilot.studio.dto.schema.*;
import com.sqlcopilot.studio.entity.ConnectionEntity;
import com.sqlcopilot.studio.entity.SchemaColumnCacheEntity;
import com.sqlcopilot.studio.entity.SchemaTableCacheEntity;
import com.sqlcopilot.studio.repository.SchemaObjectDefinitionJdbcRepository;
import com.sqlcopilot.studio.service.ConnectionService;
import com.sqlcopilot.studio.service.SchemaService;
import com.sqlcopilot.studio.service.rag.RagIngestionService;
import com.sqlcopilot.studio.support.JdbcDriverResolver;
import com.sqlcopilot.studio.util.BusinessException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
public class SchemaServiceImpl implements SchemaService {

    private static final Logger log = LoggerFactory.getLogger(SchemaServiceImpl.class);
    private static final String DEFAULT_CACHE_DATABASE_NAME = "__default__";
    private static final long MIN_CACHE_TTL_MS = 5_000L;
    private static final long MIN_TABLE_STATS_REFRESH_INTERVAL_MS = 10_000L;

    private final ConnectionService connectionService;
    private final RagIngestionService ragIngestionService;
    private final JdbcDriverResolver jdbcDriverResolver;
    private final SchemaObjectDefinitionJdbcRepository schemaObjectDefinitionJdbcRepository;
    private final long schemaCacheTtlMs;
    private final long tableStatsRefreshIntervalMs;
    private final Map<SchemaCacheKey, SchemaSnapshot> schemaSnapshotCache = new ConcurrentHashMap<>();
    private final Map<SchemaCacheKey, Object> schemaCacheLocks = new ConcurrentHashMap<>();
    private final Map<SchemaCacheKey, TableStatsSnapshot> tableStatsSnapshotCache = new ConcurrentHashMap<>();
    private final Set<SchemaCacheKey> tableStatsRefreshingKeys = ConcurrentHashMap.newKeySet();
    private final ExecutorService tableStatsExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "schema-table-stats-worker");
        thread.setDaemon(true);
        return thread;
    });

    public SchemaServiceImpl(ConnectionService connectionService,
                             RagIngestionService ragIngestionService,
                             JdbcDriverResolver jdbcDriverResolver,
                             SchemaObjectDefinitionJdbcRepository schemaObjectDefinitionJdbcRepository,
                             @Value("${schema.cache.ttl-ms:300000}") long schemaCacheTtlMs,
                             @Value("${schema.table-stats.refresh-interval-ms:60000}") long tableStatsRefreshIntervalMs) {
        this.connectionService = connectionService;
        this.ragIngestionService = ragIngestionService;
        this.jdbcDriverResolver = jdbcDriverResolver;
        this.schemaObjectDefinitionJdbcRepository = schemaObjectDefinitionJdbcRepository;
        this.schemaCacheTtlMs = Math.max(schemaCacheTtlMs, MIN_CACHE_TTL_MS);
        this.tableStatsRefreshIntervalMs = Math.max(tableStatsRefreshIntervalMs, MIN_TABLE_STATS_REFRESH_INTERVAL_MS);
    }

    @PreDestroy
    public void shutdownTableStatsExecutor() {
        tableStatsExecutor.shutdownNow();
    }

    @Override
    public SchemaSyncVO syncSchema(Long connectionId, String databaseName) {
        String cacheDatabaseName = resolveCacheDatabaseName(connectionId, databaseName);
        SchemaCacheKey cacheKey = new SchemaCacheKey(connectionId, cacheDatabaseName);
        SchemaSnapshot snapshot = refreshSnapshot(cacheKey, databaseName, true, true);
        // 关键操作：显式同步请求才执行全量字段向量化，避免 overview 读链路被重型任务阻塞。
        ragIngestionService.ingestSchema(connectionId, cacheDatabaseName, snapshot.tables(), snapshot.columns());

        SchemaSyncVO vo = new SchemaSyncVO();
        vo.setSuccess(Boolean.TRUE);
        vo.setTableCount(snapshot.tables().size());
        vo.setColumnCount(snapshot.columns().size());
        vo.setMessage("同步完成（内存缓存）");
        return vo;
    }

    @Override
    public SchemaOverviewVO getOverview(Long connectionId, String databaseName) {
        SchemaSnapshot snapshot = ensureCacheReady(connectionId, databaseName, false);
        String cacheDatabaseName = resolveCacheDatabaseName(connectionId, databaseName);
        SchemaCacheKey cacheKey = new SchemaCacheKey(connectionId, cacheDatabaseName);
        triggerTableStatsRefresh(cacheKey, databaseName, false);
        Map<String, TableStat> statsByTable = resolveLatestTableStats(cacheKey);

        SchemaOverviewVO vo = new SchemaOverviewVO();
        vo.setConnectionId(connectionId);
        vo.setDatabaseName(fromCacheDatabaseName(snapshot.databaseName()));
        vo.setTableCount(snapshot.tables().size());
        vo.setColumnCount(snapshot.columnCount());
        List<SchemaOverviewVO.TableSummaryVO> summaries = snapshot.tables().stream().map(item -> {
            SchemaOverviewVO.TableSummaryVO summary = new SchemaOverviewVO.TableSummaryVO();
            summary.setTableName(item.getTableName());
            summary.setTableComment(item.getTableComment());
            TableStat latest = statsByTable.get(item.getTableName());
            summary.setRowEstimate(latest == null ? item.getRowEstimate() : latest.rowEstimate());
            summary.setTableSizeBytes(latest == null ? item.getTableSizeBytes() : latest.tableSizeBytes());
            return summary;
        }).toList();
        vo.setTableSummaries(summaries);
        return vo;
    }

    @Override
    public SchemaTableStatsVO getTableStats(Long connectionId, String databaseName) {
        String cacheDatabaseName = resolveCacheDatabaseName(connectionId, databaseName);
        SchemaCacheKey cacheKey = new SchemaCacheKey(connectionId, cacheDatabaseName);
        if (tableStatsRefreshIntervalMs >= 0) {
            triggerTableStatsRefresh(cacheKey, databaseName, false);

            TableStatsSnapshot latest = tableStatsSnapshotCache.get(cacheKey);
            SchemaTableStatsVO vo = new SchemaTableStatsVO();
            vo.setConnectionId(connectionId);
            vo.setDatabaseName(fromCacheDatabaseName(cacheDatabaseName));
            vo.setRefreshing(tableStatsRefreshingKeys.contains(cacheKey));
            vo.setUpdatedAt(latest == null ? null : latest.updatedAt());
            List<SchemaTableStatsVO.TableStatVO> tableStats = latest == null
                ? List.of()
                : latest.tableStats().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .map(entry -> {
                    SchemaTableStatsVO.TableStatVO item = new SchemaTableStatsVO.TableStatVO();
                    item.setTableName(entry.getKey());
                    item.setRowEstimate(entry.getValue().rowEstimate());
                    item.setTableSizeBytes(entry.getValue().tableSizeBytes());
                    return item;
                })
                .toList();
            vo.setTableStats(tableStats);
            return vo;
        }
        long now = System.currentTimeMillis();
        TableStatsSnapshot current = tableStatsSnapshotCache.get(cacheKey);
        boolean needRefresh = current == null || now - current.updatedAt() >= tableStatsRefreshIntervalMs;
        if (needRefresh && tableStatsRefreshingKeys.add(cacheKey)) {
            // 关键操作：表行数/大小统计异步执行，避免阻塞对象浏览主链路。
            tableStatsExecutor.submit(() -> refreshTableStatsAsync(cacheKey, databaseName));
        }

        TableStatsSnapshot latest = tableStatsSnapshotCache.get(cacheKey);
        SchemaTableStatsVO vo = new SchemaTableStatsVO();
        vo.setConnectionId(connectionId);
        vo.setDatabaseName(fromCacheDatabaseName(cacheDatabaseName));
        vo.setRefreshing(tableStatsRefreshingKeys.contains(cacheKey));
        vo.setUpdatedAt(latest == null ? null : latest.updatedAt());
        List<SchemaTableStatsVO.TableStatVO> tableStats = latest == null
            ? List.of()
            : latest.tableStats().entrySet().stream()
            .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
            .map(entry -> {
                SchemaTableStatsVO.TableStatVO item = new SchemaTableStatsVO.TableStatVO();
                item.setTableName(entry.getKey());
                item.setRowEstimate(entry.getValue().rowEstimate());
                item.setTableSizeBytes(entry.getValue().tableSizeBytes());
                return item;
            })
            .toList();
        vo.setTableStats(tableStats);
        return vo;
    }

    @Override
    public TableDetailVO getTableDetail(Long connectionId, String databaseName, String tableName) {
        SchemaSnapshot snapshot = ensureCacheReady(connectionId, databaseName, false);
        List<SchemaColumnCacheEntity> columnCache;
        if (snapshot.columns().isEmpty()) {
            // 关键操作：字段详情改为按表按需读取，不在 overview 阶段全量抓取。
            columnCache = loadColumnsForTable(connectionId, databaseName, tableName);
        } else {
            columnCache = snapshot.columns().stream()
                .filter(item -> tableName.equals(item.getTableName()))
                .toList();
        }

        String tableComment = snapshot.tables().stream()
            .filter(item -> tableName.equals(item.getTableName()))
            .map(SchemaTableCacheEntity::getTableComment)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse("");
        List<TableDetailVO.IndexDetailVO> indexDetails = List.of();
        Map<String, String> mysqlColumnExtraMap = Map.of();
        ConnectionEntity connectionEntity = connectionService.getConnectionEntity(connectionId);
        String targetDatabaseName = resolveTargetDatabaseName(connectionEntity, databaseName);
        try (Connection connection = connectionService.openTargetConnection(connectionId)) {
            applyDatabaseContext(connection, connectionEntity.getDbType(), targetDatabaseName);
            DatabaseMetaData metaData = connection.getMetaData();
            String catalog = resolveCatalog(connection, connectionEntity.getDbType(), targetDatabaseName);
            String schemaPattern = resolveSchemaPattern(connectionEntity.getDbType(), targetDatabaseName);
            indexDetails = readTableIndexDetails(metaData, catalog, schemaPattern, tableName);
            if ("MYSQL".equalsIgnoreCase(normalize(connectionEntity.getDbType()))) {
                mysqlColumnExtraMap = readMysqlColumnExtras(connection, targetDatabaseName, tableName);
            }
        } catch (SQLException ex) {
            log.warn("读取表索引/扩展信息失败, connectionId={}, databaseName={}, tableName={}, reason={}",
                connectionId, databaseName, tableName, ex.getMessage());
        }

        TableDetailVO vo = new TableDetailVO();
        vo.setConnectionId(connectionId);
        vo.setTableName(tableName);
        vo.setTableComment(tableComment);
        Map<String, String> finalMysqlColumnExtraMap = mysqlColumnExtraMap;
        List<TableDetailVO.ColumnDetailVO> columnDetails = columnCache.stream().map(item -> {
            TableDetailVO.ColumnDetailVO detail = new TableDetailVO.ColumnDetailVO();
            detail.setColumnName(item.getColumnName());
            detail.setDataType(item.getDataType());
            detail.setColumnSize(item.getColumnSize());
            detail.setDecimalDigits(item.getDecimalDigits());
            detail.setDefaultValue(item.getColumnDefault());
            detail.setAutoIncrement(item.getAutoIncrementFlag() == 1);
            detail.setNullable(item.getNullableFlag() == 1);
            detail.setColumnComment(item.getColumnComment());
            detail.setIndexed(item.getIndexedFlag() == 1);
            detail.setPrimaryKey(item.getPrimaryKeyFlag() == 1);
            detail.setDefaultCurrentTimestamp(isCurrentTimestampDefault(item.getColumnDefault()));
            String extra = finalMysqlColumnExtraMap.getOrDefault(normalize(item.getColumnName()).toLowerCase(Locale.ROOT), "");
            detail.setOnUpdateCurrentTimestamp(hasOnUpdateCurrentTimestamp(extra));
            return detail;
        }).toList();
        vo.setColumns(columnDetails);
        vo.setIndexes(indexDetails);
        return vo;
    }

    @Override
    public SchemaObjectDefinitionVO getObjectDefinition(Long connectionId, String databaseName, String objectType, String objectName) {
        String normalizedObjectType = normalizeObjectType(objectType);
        String normalizedObjectName = normalize(objectName);
        if (normalizedObjectName.isBlank()) {
            throw new BusinessException(400, "对象名称不能为空");
        }
        ConnectionEntity connectionEntity = connectionService.getConnectionEntity(connectionId);
        String targetDatabaseName = resolveTargetDatabaseName(connectionEntity, databaseName);
        JdbcDriverResolver.ObjectDefinitionSpec spec = jdbcDriverResolver.findObjectDefinitionSpec(connectionEntity.getDbType(), normalizedObjectType);
        if (spec == null) {
            throw new BusinessException(400, "当前数据库未配置对象定义 SQL: " + normalizedObjectType);
        }
        if (!objectExists(connectionId, targetDatabaseName, normalizedObjectType, normalizedObjectName)) {
            throw new BusinessException(404, "对象不存在: " + normalizedObjectName);
        }

        try (Connection connection = connectionService.openTargetConnection(connectionId)) {
            applyDatabaseContext(connection, connectionEntity.getDbType(), targetDatabaseName);
            String definitionSql = schemaObjectDefinitionJdbcRepository.fetchDefinition(
                connection,
                connectionEntity.getDbType(),
                spec,
                targetDatabaseName,
                normalizedObjectName
            );
            if (definitionSql.isBlank()) {
                throw new BusinessException(404, "未读取到对象定义: " + normalizedObjectName);
            }
            SchemaObjectDefinitionVO vo = new SchemaObjectDefinitionVO();
            vo.setConnectionId(connectionId);
            vo.setDatabaseName(targetDatabaseName);
            vo.setObjectType(normalizedObjectType);
            vo.setObjectName(normalizedObjectName);
            vo.setDefinitionSql(definitionSql);
            return vo;
        } catch (SQLException ex) {
            throw new BusinessException(500, "读取对象定义失败: " + ex.getMessage());
        }
    }

    @Override
    public List<String> listDatabases(Long connectionId) {
        ConnectionEntity connectionEntity = connectionService.getConnectionEntity(connectionId);
        String dbType = normalize(connectionEntity.getDbType()).toUpperCase(Locale.ROOT);

        try (Connection connection = connectionService.openTargetConnection(connectionId)) {
            String schemasSql = jdbcDriverResolver.findSchemasSql(dbType);
            if (!schemasSql.isBlank()) {
                return queryConfiguredSchemas(connection, schemasSql, connectionEntity);
            }
            if ("SQLITE".equals(dbType)) {
                String sqliteName = normalize(connectionEntity.getDatabaseName());
                return List.of(sqliteName.isBlank() ? "main" : sqliteName);
            }
            return List.of();
        } catch (Exception ex) {
            throw new BusinessException(500, "读取数据库列表失败: " + ex.getMessage());
        }
    }

    @Override
    public List<String> listObjectNames(Long connectionId, String databaseName, String objectType) {
        ConnectionEntity connectionEntity = connectionService.getConnectionEntity(connectionId);
        String normalizedType = normalize(objectType).toUpperCase(Locale.ROOT);
        String targetDatabaseName = resolveTargetDatabaseName(connectionEntity, databaseName);
        try (Connection connection = connectionService.openTargetConnection(connectionId)) {
            // 关键操作：对象元数据按当前连接和目标库隔离读取，避免跨库串读。
            applyDatabaseContext(connection, connectionEntity.getDbType(), targetDatabaseName);
            DatabaseMetaData metaData = connection.getMetaData();
            String catalog = resolveCatalog(connection, connectionEntity.getDbType(), targetDatabaseName);
            String schemaPattern = resolveSchemaPatternForObjects(connectionEntity.getDbType(), targetDatabaseName, normalizedType);
            List<String> names = switch (normalizedType) {
                case "TABLE", "TABLES" -> readTableLikeObjects(metaData, catalog, schemaPattern, "TABLE");
                case "VIEW", "VIEWS" -> readTableLikeObjects(metaData, catalog, schemaPattern, "VIEW");
                case "FUNCTION", "FUNCTIONS" -> readFunctions(metaData, catalog, schemaPattern);
                case "EVENT", "EVENTS", "QUERY", "QUERIES", "BACKUP", "BACKUPS" -> List.of();
                default -> throw new BusinessException(400, "不支持的对象类型: " + objectType);
            };
            return names.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        } catch (SQLException ex) {
            throw new BusinessException(500, "读取对象列表失败: " + ex.getMessage());
        }
    }

    @Override
    public ContextBuildVO buildContext(ContextBuildReq req) {
        SchemaSnapshot snapshot = ensureCacheReady(req.getConnectionId(), req.getDatabaseName(), false);

        int tokenBudget = req.getTokenBudget() == null ? 1200 : req.getTokenBudget();
        List<SchemaTableCacheEntity> tables = snapshot.tables();
        List<SchemaColumnCacheEntity> columns = snapshot.columns();
        if (tables.isEmpty()) {
            throw new BusinessException(400, "当前库未读取到可用 Schema 元数据，请检查连接配置与权限");
        }

        String question = req.getQuestion().toLowerCase(Locale.ROOT);
        Map<String, Integer> scoreMap = new HashMap<>();
        for (SchemaTableCacheEntity table : tables) {
            int score = 0;
            String tableName = table.getTableName().toLowerCase(Locale.ROOT);
            if (question.contains(tableName)) {
                score += 5;
            }
            if (table.getTableComment() != null && question.contains(table.getTableComment().toLowerCase(Locale.ROOT))) {
                score += 2;
            }
            scoreMap.put(table.getTableName(), score);
        }
        if (!columns.isEmpty()) {
            for (SchemaColumnCacheEntity column : columns) {
                String lower = column.getColumnName().toLowerCase(Locale.ROOT);
                if (question.contains(lower)) {
                    scoreMap.compute(column.getTableName(), (k, v) -> (v == null ? 0 : v) + 1);
                }
            }
        }

        List<String> sortedTables = scoreMap.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
            .map(Map.Entry::getKey).toList();

        Map<String, List<SchemaColumnCacheEntity>> tableColumns = columns.stream()
            .collect(Collectors.groupingBy(SchemaColumnCacheEntity::getTableName));

        StringBuilder contextBuilder = new StringBuilder();
        List<String> relatedTables = new ArrayList<>();
        int usedTokens = 0;
        Set<String> accepted = new HashSet<>();
        for (String tableName : sortedTables) {
            if (accepted.contains(tableName)) {
                continue;
            }
            List<SchemaColumnCacheEntity> tableColumnList = tableColumns.get(tableName);
            if (tableColumnList == null && columns.isEmpty()) {
                tableColumnList = loadColumnsForTable(req.getConnectionId(), req.getDatabaseName(), tableName);
                tableColumns.put(tableName, tableColumnList);
            }
            String segment = buildTableSegment(tableColumnList == null ? List.of() : tableColumnList, tableName);
            int segmentTokens = Math.max(1, segment.length() / 4);
            if (usedTokens + segmentTokens > tokenBudget) {
                continue;
            }
            contextBuilder.append(segment).append("\n");
            usedTokens += segmentTokens;
            relatedTables.add(tableName);
            accepted.add(tableName);
        }

        ContextBuildVO vo = new ContextBuildVO();
        vo.setContext(contextBuilder.toString());
        vo.setUsedTokens(usedTokens);
        vo.setRelatedTables(relatedTables);
        return vo;
    }

    @Override
    public TableOperationVO createTable(TableCreateReq req) {
        String ddl = req.getDdl();
        if (ddl == null || ddl.isBlank()) {
            ddl = buildCreateTableDDL(req);
        }
        return executeDDL(req.getConnectionId(), req.getDatabaseName(), ddl, "表创建成功");
    }

    @Override
    public TableOperationVO alterTable(TableAlterReq req) {
        String ddl = req.getDdl();
        if (ddl == null || ddl.isBlank()) {
            return TableOperationVO.failure("暂不支持自动生成ALTER TABLE，请手动编写DDL");
        }
        return executeDDL(req.getConnectionId(), req.getDatabaseName(), ddl, "表结构更新成功");
    }

    @Override
    public TableRenameVO renameTable(TableRenameReq req) {
        String sourceTableName = normalize(req.getSourceTableName());
        String targetTableName = normalize(req.getTargetTableName());
        if (sourceTableName.isBlank()) {
            throw new BusinessException(400, "原表名不能为空");
        }
        if (targetTableName.isBlank()) {
            throw new BusinessException(400, "新表名不能为空");
        }
        if (sourceTableName.equalsIgnoreCase(targetTableName)) {
            throw new BusinessException(400, "新表名不能与原表名相同");
        }
        ConnectionEntity connectionEntity = connectionService.getConnectionEntity(req.getConnectionId());
        String dbType = normalize(connectionEntity.getDbType()).toUpperCase(Locale.ROOT);
        String renameTableSql = jdbcDriverResolver.findRenameTableSql(dbType);
        if (renameTableSql.isBlank()) {
            throw new BusinessException(400, "当前数据库未配置表重命名 SQL，请检查 jdbc-drivers.yml");
        }
        String databaseName = resolveTargetDatabaseName(connectionEntity, req.getDatabaseName());
        List<String> tableNames = listObjectNames(req.getConnectionId(), databaseName, "tables");
        if (tableNames.stream().noneMatch(item -> item.equalsIgnoreCase(sourceTableName))) {
            throw new BusinessException(404, "原表不存在: " + sourceTableName);
        }
        if (tableNames.stream().anyMatch(item -> item.equalsIgnoreCase(targetTableName))) {
            throw new BusinessException(400, "目标表已存在: " + targetTableName);
        }

        try (Connection connection = connectionService.openTargetConnection(req.getConnectionId())) {
            applyDatabaseContext(connection, dbType, databaseName);
            String sql = renderSchemaOperationSql(renameTableSql, dbType, databaseName, sourceTableName, targetTableName);
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        } catch (SQLException ex) {
            throw new BusinessException(500, "重命名表失败: " + ex.getMessage());
        }

        TableRenameVO vo = new TableRenameVO();
        vo.setSuccess(Boolean.TRUE);
        vo.setMessage("表重命名成功");
        vo.setDatabaseName(databaseName);
        vo.setSourceTableName(sourceTableName);
        vo.setTargetTableName(targetTableName);
        return vo;
    }

    @Override
    public SchemaObjectDefinitionSaveVO saveObjectDefinition(SchemaObjectDefinitionSaveReq req) {
        String normalizedObjectType = normalizeObjectType(req.getObjectType());
        String normalizedObjectName = normalize(req.getObjectName());
        String definitionSql = normalize(req.getDefinitionSql());
        if (normalizedObjectName.isBlank()) {
            throw new BusinessException(400, "对象名称不能为空");
        }
        if (definitionSql.isBlank()) {
            throw new BusinessException(400, "定义SQL不能为空");
        }

        ConnectionEntity connectionEntity = connectionService.getConnectionEntity(req.getConnectionId());
        String targetDatabaseName = resolveTargetDatabaseName(connectionEntity, req.getDatabaseName());
        JdbcDriverResolver.ObjectDefinitionSpec spec = jdbcDriverResolver.findObjectDefinitionSpec(connectionEntity.getDbType(), normalizedObjectType);
        if (spec == null) {
            throw new BusinessException(400, "当前数据库未配置对象定义保存 SQL: " + normalizedObjectType);
        }
        if (!objectExists(req.getConnectionId(), targetDatabaseName, normalizedObjectType, normalizedObjectName)) {
            throw new BusinessException(404, "对象不存在: " + normalizedObjectName);
        }

        try (Connection connection = connectionService.openTargetConnection(req.getConnectionId())) {
            applyDatabaseContext(connection, connectionEntity.getDbType(), targetDatabaseName);
            schemaObjectDefinitionJdbcRepository.saveDefinition(
                connection,
                connectionEntity.getDbType(),
                spec,
                targetDatabaseName,
                normalizedObjectType,
                normalizedObjectName,
                definitionSql
            );
        } catch (SQLException ex) {
            throw new BusinessException(500, "保存对象定义失败: " + ex.getMessage());
        }

        if (!objectExists(req.getConnectionId(), targetDatabaseName, normalizedObjectType, normalizedObjectName)) {
            throw new BusinessException(500, "对象定义保存后未找到原对象，当前不支持通过定义编辑页改名");
        }

        SchemaObjectDefinitionVO latest = getObjectDefinition(
            req.getConnectionId(),
            targetDatabaseName,
            normalizedObjectType,
            normalizedObjectName
        );
        SchemaObjectDefinitionSaveVO vo = new SchemaObjectDefinitionSaveVO();
        vo.setSuccess(Boolean.TRUE);
        vo.setMessage("对象定义保存成功");
        vo.setDatabaseName(targetDatabaseName);
        vo.setObjectType(normalizedObjectType);
        vo.setObjectName(normalizedObjectName);
        vo.setDefinitionSql(latest.getDefinitionSql());
        return vo;
    }

    @Override
    public TableOperationVO dropTable(Long connectionId, String databaseName, String tableName) {
        String normalizedTableName = normalize(tableName);
        if (normalizedTableName.isBlank()) {
            return TableOperationVO.failure("表名不能为空");
        }
        String ddl = "DROP TABLE `" + normalizedTableName + "`";
        return executeDDL(connectionId, databaseName, ddl, "表删除成功");
    }

    @Override
    public TableOperationVO truncateTable(Long connectionId, String databaseName, String tableName) {
        String normalizedTableName = normalize(tableName);
        if (normalizedTableName.isBlank()) {
            return TableOperationVO.failure("表名不能为空");
        }
        String ddl = "TRUNCATE TABLE `" + normalizedTableName + "`";
        return executeDDL(connectionId, databaseName, ddl, "表清空成功");
    }

    @Override
    public void refreshSchemaCache(Long connectionId, String databaseName) {
        if (connectionId == null) {
            return;
        }
        String cacheDatabaseName = resolveCacheDatabaseName(connectionId, databaseName);
        SchemaCacheKey cacheKey = new SchemaCacheKey(connectionId, cacheDatabaseName);
        // 移除缓存，强制下次访问时重新加载
        schemaSnapshotCache.remove(cacheKey);
        tableStatsSnapshotCache.remove(cacheKey);
        tableStatsRefreshingKeys.remove(cacheKey);
        log.info("Schema缓存已刷新, connectionId={}, databaseName={}", connectionId, databaseName);
    }

    private String buildCreateTableDDL(TableCreateReq req) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(req.getTableName()).append(" (");

        List<String> definitionSegments = new ArrayList<>();
        for (TableCreateReq.ColumnDefinition col : req.getColumns()) {
            definitionSegments.add(buildColumnDefinitionSql(col));
        }

        List<String> pkColumns = req.getColumns().stream()
            .filter(c -> Boolean.TRUE.equals(c.getPrimaryKey()))
            .map(TableCreateReq.ColumnDefinition::getColumnName)
            .toList();
        if (!pkColumns.isEmpty()) {
            definitionSegments.add("PRIMARY KEY (" + String.join(", ", pkColumns) + ")");
        }

        if (req.getIndexes() != null) {
            req.getIndexes().stream()
                .map(this::buildIndexDefinitionSql)
                .filter(def -> !def.isBlank())
                .forEach(definitionSegments::add);
        }

        sb.append(String.join(", ", definitionSegments));

        if (req.getTableComment() != null && !req.getTableComment().isBlank()) {
            sb.append(") COMMENT='").append(escapeSingleQuote(req.getTableComment())).append("'");
        } else {
            sb.append(")");
        }

        sb.append(";");
        return sb.toString();
    }

    private String buildColumnDefinitionSql(TableCreateReq.ColumnDefinition col) {
        StringBuilder colDef = new StringBuilder();
        colDef.append(col.getColumnName()).append(" ").append(col.getDataType());

        if (col.getColumnSize() != null && !isTypeWithoutSize(col.getDataType())) {
            colDef.append("(").append(col.getColumnSize());
            if (col.getDecimalDigits() != null) {
                colDef.append(",").append(col.getDecimalDigits());
            }
            colDef.append(")");
        }

        if (Boolean.FALSE.equals(col.getNullable())) {
            colDef.append(" NOT NULL");
        }

        if (Boolean.TRUE.equals(col.getAutoIncrement()) && Boolean.TRUE.equals(col.getPrimaryKey())) {
            colDef.append(" AUTO_INCREMENT");
        }

        if (Boolean.TRUE.equals(col.getDefaultCurrentTimestamp())) {
            colDef.append(" DEFAULT CURRENT_TIMESTAMP");
        } else if (col.getDefaultValue() != null && !col.getDefaultValue().isBlank()) {
            colDef.append(" DEFAULT ").append(col.getDefaultValue());
        }

        if (Boolean.TRUE.equals(col.getOnUpdateCurrentTimestamp())) {
            colDef.append(" ON UPDATE CURRENT_TIMESTAMP");
        }

        if (col.getColumnComment() != null && !col.getColumnComment().isBlank()) {
            colDef.append(" COMMENT '").append(escapeSingleQuote(col.getColumnComment())).append("'");
        }
        return colDef.toString();
    }

    private String buildIndexDefinitionSql(TableCreateReq.IndexDefinition idx) {
        String indexName = normalize(idx.getIndexName());
        if (indexName.isBlank()) {
            return "";
        }
        List<String> columns = idx.getColumns() == null ? List.of() : idx.getColumns().stream()
            .map(this::normalize)
            .filter(column -> !column.isBlank())
            .toList();
        if (columns.isEmpty()) {
            return "";
        }
        String prefix = Boolean.TRUE.equals(idx.getUnique()) ? "UNIQUE INDEX " : "INDEX ";
        return prefix + indexName + " (" + String.join(", ", columns) + ")";
    }

    private String renderSchemaOperationSql(String sqlTemplate,
                                            String dbType,
                                            String databaseName,
                                            String sourceTableName,
                                            String targetTableName) {
        return sqlTemplate
            .replace("{source_table}", sourceTableName)
            .replace("{target_table}", targetTableName)
            .replace("{source_table_literal}", toSqlStringLiteral(sourceTableName))
            .replace("{target_table_literal}", toSqlStringLiteral(targetTableName))
            .replace("{quoted_source_table}", quoteIdentifier(sourceTableName, dbType))
            .replace("{quoted_target_table}", quoteIdentifier(targetTableName, dbType))
            .replace("{qualified_source_table}", qualifyTableName(databaseName, sourceTableName, dbType))
            .replace("{qualified_target_table}", qualifyTableName(databaseName, targetTableName, dbType))
            .replace("{source_full_name_literal}", toSqlStringLiteral(buildSourceFullName(databaseName, sourceTableName, dbType)));
    }

    private String qualifyTableName(String databaseName, String tableName, String dbType) {
        String normalizedDatabaseName = normalize(databaseName);
        String quotedTable = quoteIdentifier(tableName, dbType);
        if (normalizedDatabaseName.isBlank()) {
            return quotedTable;
        }
        return switch (normalize(dbType).toUpperCase(Locale.ROOT)) {
            case "MYSQL", "POSTGRESQL", "ORACLE" -> quoteIdentifier(normalizedDatabaseName, dbType) + "." + quotedTable;
            case "SQLSERVER" -> quoteIdentifier(normalizedDatabaseName, dbType) + "." + quotedTable;
            default -> quotedTable;
        };
    }

    private String buildSourceFullName(String databaseName, String tableName, String dbType) {
        String normalizedDatabaseName = normalize(databaseName);
        if (normalizedDatabaseName.isBlank()) {
            return normalize(tableName);
        }
        return switch (normalize(dbType).toUpperCase(Locale.ROOT)) {
            case "SQLSERVER", "POSTGRESQL", "ORACLE" -> normalizedDatabaseName + "." + normalize(tableName);
            default -> normalize(tableName);
        };
    }

    private String quoteIdentifier(String identifier, String dbType) {
        String normalized = normalize(identifier);
        if (normalized.isBlank()) {
            return normalized;
        }
        return switch (normalize(dbType).toUpperCase(Locale.ROOT)) {
            case "SQLSERVER" -> "[" + normalized.replace("]", "]]") + "]";
            case "POSTGRESQL", "ORACLE" -> "\"" + normalized.replace("\"", "\"\"") + "\"";
            default -> "`" + normalized.replace("`", "``") + "`";
        };
    }

    private boolean isTypeWithoutSize(String dataType) {
        String upper = dataType.toUpperCase(Locale.ROOT);
        return upper.equals("INT") || upper.equals("INTEGER") || upper.equals("BIGINT")
            || upper.equals("SMALLINT") || upper.equals("TINYINT") || upper.equals("TEXT")
            || upper.equals("LONGTEXT") || upper.equals("MEDIUMTEXT") || upper.equals("DATE")
            || upper.equals("DATETIME") || upper.equals("TIMESTAMP") || upper.equals("TIME")
            || upper.equals("BLOB") || upper.equals("JSON") || upper.equals("BOOLEAN")
            || upper.equals("BOOL") || upper.equals("FLOAT") || upper.equals("DOUBLE");
    }

    private TableOperationVO executeDDL(Long connectionId, String databaseName, String ddl, String successMessage) {
        ConnectionEntity connectionEntity = connectionService.getConnectionEntity(connectionId);
        try (Connection connection = connectionService.openTargetConnection(connectionId)) {
            applyDatabaseContext(connection, connectionEntity.getDbType(), databaseName);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute(ddl);
                return TableOperationVO.success(successMessage, ddl);
            }
        } catch (SQLException ex) {
            log.error("执行DDL失败: {}", ddl, ex);
            return TableOperationVO.failure("执行失败: " + ex.getMessage());
        }
    }

    @Scheduled(
        fixedDelayString = "${schema.cache.refresh-interval-ms:180000}",
        initialDelayString = "${schema.cache.refresh-initial-delay-ms:60000}"
    )
    public void refreshSchemaCacheOnSchedule() {
        if (schemaSnapshotCache.isEmpty()) {
            return;
        }
        // 关键操作：定时刷新仅覆盖已访问过的缓存键，避免无意义扫描所有连接。
        for (SchemaCacheKey cacheKey : new ArrayList<>(schemaSnapshotCache.keySet())) {
            try {
                refreshSnapshot(cacheKey, fromCacheDatabaseName(cacheKey.databaseName()), true, false);
            } catch (Exception ex) {
                log.warn("定时刷新 Schema 缓存失败, connectionId={}, databaseName={}, reason={}",
                    cacheKey.connectionId(), cacheKey.databaseName(), ex.getMessage());
            }
        }
    }

    @Scheduled(
        fixedDelayString = "${schema.table-stats.refresh-interval-ms:60000}",
        initialDelayString = "${schema.table-stats.refresh-interval-ms:60000}"
    )
    public void refreshTableStatsOnSchedule() {
        Set<SchemaCacheKey> cacheKeys = new LinkedHashSet<>();
        cacheKeys.addAll(schemaSnapshotCache.keySet());
        cacheKeys.addAll(tableStatsSnapshotCache.keySet());
        if (cacheKeys.isEmpty()) {
            return;
        }
        for (SchemaCacheKey cacheKey : cacheKeys) {
            try {
                triggerTableStatsRefresh(cacheKey, fromCacheDatabaseName(cacheKey.databaseName()), true);
            } catch (Exception ex) {
                log.warn("瀹氭椂鍒锋柊琛ㄧ粺璁″け璐? connectionId={}, databaseName={}, reason={}",
                    cacheKey.connectionId(), cacheKey.databaseName(), ex.getMessage());
            }
        }
    }

    private SchemaSnapshot ensureCacheReady(Long connectionId, String databaseName, boolean requireColumnDetails) {
        String cacheDatabaseName = resolveCacheDatabaseName(connectionId, databaseName);
        SchemaCacheKey cacheKey = new SchemaCacheKey(connectionId, cacheDatabaseName);
        return refreshSnapshot(cacheKey, databaseName, false, requireColumnDetails);
    }

    private SchemaSnapshot refreshSnapshot(SchemaCacheKey cacheKey,
                                           String databaseName,
                                           boolean forceRefresh,
                                           boolean requireColumnDetails) {
        Object lock = schemaCacheLocks.computeIfAbsent(cacheKey, key -> new Object());
        synchronized (lock) {
            SchemaSnapshot current = schemaSnapshotCache.get(cacheKey);
            if (!forceRefresh && current != null && !isCacheExpired(current)
                && (!requireColumnDetails || !current.columns().isEmpty())) {
                return current;
            }

            try {
                SchemaSnapshot latest = loadSnapshot(
                    cacheKey.connectionId(),
                    databaseName,
                    cacheKey.databaseName(),
                    requireColumnDetails
                );
                schemaSnapshotCache.put(cacheKey, latest);
                return latest;
            } catch (BusinessException ex) {
                if (current != null) {
                    log.warn("Schema 刷新失败，沿用旧缓存, connectionId={}, databaseName={}, reason={}",
                        cacheKey.connectionId(), cacheKey.databaseName(), ex.getMessage());
                    return current;
                }
                throw ex;
            }
        }
    }

    private SchemaSnapshot loadSnapshot(Long connectionId,
                                        String databaseName,
                                        String cacheDatabaseName,
                                        boolean includeColumnDetails) {
        long now = System.currentTimeMillis();
        List<SchemaTableCacheEntity> tables = new ArrayList<>();
        List<SchemaColumnCacheEntity> columns = new ArrayList<>();

        ConnectionEntity connectionEntity = connectionService.getConnectionEntity(connectionId);
        String targetDatabaseName = resolveTargetDatabaseName(connectionEntity, databaseName);

        try (Connection connection = connectionService.openTargetConnection(connectionId)) {
            applyDatabaseContext(connection, connectionEntity.getDbType(), targetDatabaseName);
            List<SchemaTableCacheEntity> configuredTables = queryTablesByConfiguredSql(
                connection,
                connectionEntity.getDbType(),
                connectionId,
                cacheDatabaseName,
                targetDatabaseName,
                now
            );
            if (!configuredTables.isEmpty()) {
                tables.addAll(configuredTables);
                if (includeColumnDetails) {
                    for (SchemaTableCacheEntity table : configuredTables) {
                        columns.addAll(queryColumnsByConfiguredSql(
                            connection,
                            connectionEntity.getDbType(),
                            connectionId,
                            cacheDatabaseName,
                            targetDatabaseName,
                            table.getTableName(),
                            now
                        ));
                    }
                }
            } else {
                DatabaseMetaData metaData = connection.getMetaData();
                String catalog = resolveCatalog(connection, connectionEntity.getDbType(), targetDatabaseName);
                String schemaPattern = resolveSchemaPattern(connectionEntity.getDbType(), targetDatabaseName);

                try (ResultSet tableRs = metaData.getTables(catalog, schemaPattern, "%", new String[]{"TABLE"})) {
                    while (tableRs.next()) {
                        String tableName = tableRs.getString("TABLE_NAME");
                        if (tableName == null) {
                            continue;
                        }

                        SchemaTableCacheEntity tableEntity = new SchemaTableCacheEntity();
                        tableEntity.setConnectionId(connectionId);
                        tableEntity.setDatabaseName(cacheDatabaseName);
                        tableEntity.setTableName(tableName);
                        tableEntity.setTableComment(tableRs.getString("REMARKS"));
                        tableEntity.setRowEstimate(0L);
                        tableEntity.setTableSizeBytes(0L);
                        tableEntity.setUpdatedAt(now);
                        tables.add(tableEntity);

                        if (includeColumnDetails) {
                            columns.addAll(readColumnsForTable(metaData, catalog, schemaPattern, connectionId, cacheDatabaseName, tableName, now));
                        }
                    }
                }
            }
        } catch (SQLException ex) {
            throw new BusinessException(500, "Schema 同步失败: " + ex.getMessage());
        }

        List<SchemaTableCacheEntity> readonlyTables = List.copyOf(tables);
        List<SchemaColumnCacheEntity> readonlyColumns = List.copyOf(columns);
        return new SchemaSnapshot(cacheDatabaseName, readonlyTables, readonlyColumns, readonlyColumns.size(), now);
    }

    private void refreshTableStatsAsync(SchemaCacheKey cacheKey, String databaseName) {
        try {
            TableStatsSnapshot snapshot = loadTableStatsSnapshot(
                cacheKey.connectionId(),
                databaseName,
                cacheKey.databaseName()
            );
            tableStatsSnapshotCache.put(cacheKey, snapshot);
        } catch (Exception ex) {
            log.warn("异步表统计刷新失败, connectionId={}, databaseName={}, reason={}",
                cacheKey.connectionId(), cacheKey.databaseName(), ex.getMessage());
        } finally {
            tableStatsRefreshingKeys.remove(cacheKey);
        }
    }

    private void triggerTableStatsRefresh(SchemaCacheKey cacheKey, String databaseName, boolean forceRefresh) {
        long now = System.currentTimeMillis();
        TableStatsSnapshot current = tableStatsSnapshotCache.get(cacheKey);
        boolean needRefresh = forceRefresh
            || current == null
            || now - current.updatedAt() >= tableStatsRefreshIntervalMs;
        if (!needRefresh || !tableStatsRefreshingKeys.add(cacheKey)) {
            return;
        }
        // 关键操作：表行数/大小统计异步执行，避免阻塞对象浏览主链路。
        tableStatsExecutor.submit(() -> refreshTableStatsAsync(cacheKey, databaseName));
    }

    private Map<String, TableStat> resolveLatestTableStats(SchemaCacheKey cacheKey) {
        TableStatsSnapshot latest = tableStatsSnapshotCache.get(cacheKey);
        return latest == null ? Map.of() : latest.tableStats();
    }

    private TableStatsSnapshot loadTableStatsSnapshot(Long connectionId,
                                                      String databaseName,
                                                      String cacheDatabaseName) {
        long now = System.currentTimeMillis();
        ConnectionEntity connectionEntity = connectionService.getConnectionEntity(connectionId);
        String targetDatabaseName = resolveTargetDatabaseName(connectionEntity, databaseName);
        Map<String, TableStat> statsMap;
        try (Connection connection = connectionService.openTargetConnection(connectionId)) {
            applyDatabaseContext(connection, connectionEntity.getDbType(), targetDatabaseName);
            statsMap = queryTableStats(connection, connectionEntity.getDbType(), targetDatabaseName);
        } catch (SQLException ex) {
            throw new BusinessException(500, "读取表统计失败: " + ex.getMessage());
        }
        return new TableStatsSnapshot(cacheDatabaseName, statsMap, now);
    }

    private Map<String, TableStat> queryTableStats(Connection connection, String dbType, String targetDatabaseName)
        throws SQLException {
        String type = normalize(dbType).toUpperCase(Locale.ROOT);
        if ("MYSQL".equals(type)) {
            return queryMySqlTableStats(connection, targetDatabaseName);
        }
        if ("POSTGRESQL".equals(type)) {
            return queryPostgreSqlTableStats(connection);
        }
        if ("SQLSERVER".equals(type)) {
            return querySqlServerTableStats(connection);
        }
        if ("ORACLE".equals(type)) {
            return queryOracleTableStats(connection);
        }
        return Map.of();
    }

    private Map<String, TableStat> queryMySqlTableStats(Connection connection, String targetDatabaseName) throws SQLException {
        String schemaName = normalize(targetDatabaseName);
        if (schemaName.isBlank()) {
            schemaName = normalize(connection.getCatalog());
        }
        if (schemaName.isBlank()) {
            return Map.of();
        }
        String sql = """
            SELECT TABLE_NAME,
                   COALESCE(TABLE_ROWS, 0) AS row_estimate,
                   COALESCE(DATA_LENGTH, 0) + COALESCE(INDEX_LENGTH, 0) AS table_size_bytes
            FROM information_schema.TABLES
            WHERE TABLE_SCHEMA = ?
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schemaName);
            try (ResultSet rs = ps.executeQuery()) {
                return readTableStatsResult(rs);
            }
        }
    }

    private Map<String, TableStat> queryPostgreSqlTableStats(Connection connection) throws SQLException {
        String sql = """
            SELECT c.relname AS table_name,
                   GREATEST(COALESCE(c.reltuples, 0), 0)::bigint AS row_estimate,
                   COALESCE(pg_total_relation_size(c.oid), 0) AS table_size_bytes
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relkind = 'r'
              AND n.nspname = current_schema()
            """;
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return readTableStatsResult(rs);
        }
    }

    private Map<String, TableStat> querySqlServerTableStats(Connection connection) throws SQLException {
        String sql = """
            SELECT t.name AS table_name,
                   SUM(p.rows) AS row_estimate,
                   SUM(a.total_pages) * 8192 AS table_size_bytes
            FROM sys.tables t
            JOIN sys.schemas s ON t.schema_id = s.schema_id
            JOIN sys.indexes i ON t.object_id = i.object_id
            JOIN sys.partitions p ON i.object_id = p.object_id AND i.index_id = p.index_id
            JOIN sys.allocation_units a ON p.partition_id = a.container_id
            WHERE s.name = SCHEMA_NAME()
            GROUP BY t.name
            """;
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return readTableStatsResult(rs);
        }
    }

    private Map<String, TableStat> queryOracleTableStats(Connection connection) throws SQLException {
        String sql = """
            SELECT table_name,
                   NVL(num_rows, 0) AS row_estimate,
                   NVL(blocks, 0) * 8192 AS table_size_bytes
            FROM user_tables
            """;
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return readTableStatsResult(rs);
        }
    }

    private Map<String, TableStat> readTableStatsResult(ResultSet rs) throws SQLException {
        Map<String, TableStat> stats = new LinkedHashMap<>();
        while (rs.next()) {
            String tableName = normalize(rs.getString("table_name"));
            if (tableName.isBlank()) {
                tableName = normalize(rs.getString("TABLE_NAME"));
            }
            if (tableName.isBlank()) {
                continue;
            }
            long rowEstimate = safeLong(rs.getObject("row_estimate"));
            if (rowEstimate == 0L) {
                rowEstimate = safeLong(rs.getObject("ROW_ESTIMATE"));
            }
            long tableSizeBytes = safeLong(rs.getObject("table_size_bytes"));
            if (tableSizeBytes == 0L) {
                tableSizeBytes = safeLong(rs.getObject("TABLE_SIZE_BYTES"));
            }
            stats.put(tableName, new TableStat(Math.max(0L, rowEstimate), Math.max(0L, tableSizeBytes)));
        }
        return stats;
    }

    private List<SchemaColumnCacheEntity> loadColumnsForTable(Long connectionId, String databaseName, String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return List.of();
        }
        String cacheDatabaseName = resolveCacheDatabaseName(connectionId, databaseName);
        long now = System.currentTimeMillis();
        ConnectionEntity connectionEntity = connectionService.getConnectionEntity(connectionId);
        String targetDatabaseName = resolveTargetDatabaseName(connectionEntity, databaseName);
        try (Connection connection = connectionService.openTargetConnection(connectionId)) {
            applyDatabaseContext(connection, connectionEntity.getDbType(), targetDatabaseName);
            List<SchemaColumnCacheEntity> configuredColumns = queryColumnsByConfiguredSql(
                connection,
                connectionEntity.getDbType(),
                connectionId,
                cacheDatabaseName,
                targetDatabaseName,
                tableName,
                now
            );
            if (!configuredColumns.isEmpty()) {
                return configuredColumns;
            }
            DatabaseMetaData metaData = connection.getMetaData();
            String catalog = resolveCatalog(connection, connectionEntity.getDbType(), targetDatabaseName);
            String schemaPattern = resolveSchemaPattern(connectionEntity.getDbType(), targetDatabaseName);
            return readColumnsForTable(metaData, catalog, schemaPattern, connectionId, cacheDatabaseName, tableName, now);
        } catch (SQLException ex) {
            throw new BusinessException(500, "读取表字段详情失败: " + ex.getMessage());
        }
    }

    private List<SchemaColumnCacheEntity> readColumnsForTable(DatabaseMetaData metaData,
                                                              String catalog,
                                                              String schemaPattern,
                                                              Long connectionId,
                                                              String cacheDatabaseName,
                                                              String tableName,
                                                              long now) throws SQLException {
        List<SchemaColumnCacheEntity> columns = new ArrayList<>();
        Set<String> primaryKeys = readPrimaryKeys(metaData, catalog, schemaPattern, tableName);
        Set<String> indexedColumns = readIndexedColumns(metaData, catalog, schemaPattern, tableName);
        try (ResultSet columnRs = metaData.getColumns(catalog, schemaPattern, tableName, "%")) {
            while (columnRs.next()) {
                String columnName = columnRs.getString("COLUMN_NAME");
                SchemaColumnCacheEntity columnEntity = new SchemaColumnCacheEntity();
                columnEntity.setConnectionId(connectionId);
                columnEntity.setDatabaseName(cacheDatabaseName);
                columnEntity.setTableName(tableName);
                columnEntity.setColumnName(columnName);
                columnEntity.setDataType(columnRs.getString("TYPE_NAME"));
                columnEntity.setColumnSize(readIntegerColumn(columnRs, "COLUMN_SIZE"));
                columnEntity.setDecimalDigits(readIntegerColumn(columnRs, "DECIMAL_DIGITS"));
                columnEntity.setColumnDefault(columnRs.getString("COLUMN_DEF"));
                columnEntity.setAutoIncrementFlag(parseAutoIncrementFlag(columnRs.getString("IS_AUTOINCREMENT")));
                columnEntity.setNullableFlag(columnRs.getInt("NULLABLE") == DatabaseMetaData.columnNullable ? 1 : 0);
                columnEntity.setColumnComment(columnRs.getString("REMARKS"));
                columnEntity.setIndexedFlag(indexedColumns.contains(columnName) ? 1 : 0);
                columnEntity.setPrimaryKeyFlag(primaryKeys.contains(columnName) ? 1 : 0);
                columnEntity.setUpdatedAt(now);
                columns.add(columnEntity);
            }
        }
        return columns;
    }

    private List<String> queryConfiguredSchemas(Connection connection,
                                                String schemasSql,
                                                ConnectionEntity connectionEntity) throws SQLException {
        List<String> values = new ArrayList<>();
        try (PreparedStatement statement = prepareConfiguredStatement(connection, schemasSql, connectionEntity, null);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next() && values.size() < 2000) {
                String value = readOptionalStringColumn(rs, "schema_name");
                if (value.isBlank()) {
                    value = normalize(rs.getString(1));
                }
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    private List<SchemaTableCacheEntity> queryTablesByConfiguredSql(Connection connection,
                                                                    String dbType,
                                                                    Long connectionId,
                                                                    String cacheDatabaseName,
                                                                    String targetDatabaseName,
                                                                    long now) throws SQLException {
        String tablesSql = jdbcDriverResolver.findTablesSql(dbType);
        if (tablesSql.isBlank()) {
            return List.of();
        }
        List<SchemaTableCacheEntity> tables = new ArrayList<>();
        try (PreparedStatement statement = prepareConfiguredStatement(connection, tablesSql, null, targetDatabaseName);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                String tableName = readOptionalStringColumn(rs, "table_name");
                if (tableName.isBlank()) {
                    tableName = readOptionalStringColumn(rs, "TABLE_NAME");
                }
                if (tableName.isBlank()) {
                    continue;
                }
                SchemaTableCacheEntity tableEntity = new SchemaTableCacheEntity();
                tableEntity.setConnectionId(connectionId);
                tableEntity.setDatabaseName(cacheDatabaseName);
                tableEntity.setTableName(tableName);
                tableEntity.setTableComment(readOptionalStringColumn(rs, "table_comment"));
                tableEntity.setRowEstimate(0L);
                tableEntity.setTableSizeBytes(0L);
                tableEntity.setUpdatedAt(now);
                tables.add(tableEntity);
            }
        }
        return tables;
    }

    private List<SchemaColumnCacheEntity> queryColumnsByConfiguredSql(Connection connection,
                                                                      String dbType,
                                                                      Long connectionId,
                                                                      String cacheDatabaseName,
                                                                      String targetDatabaseName,
                                                                      String tableName,
                                                                      long now) throws SQLException {
        String columnsSql = jdbcDriverResolver.findColumnsSql(dbType);
        if (columnsSql.isBlank()) {
            return List.of();
        }
        Set<String> primaryKeys = queryPrimaryKeysByConfiguredSql(connection, dbType, targetDatabaseName, tableName);
        Set<String> indexedColumns = queryIndexedColumnsByMetadata(connection, dbType, targetDatabaseName, tableName);
        List<SchemaColumnCacheEntity> columns = new ArrayList<>();
        try (PreparedStatement statement = prepareConfiguredStatement(connection, columnsSql, null, targetDatabaseName, tableName);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                String columnName = readOptionalStringColumn(rs, "column_name");
                if (columnName.isBlank()) {
                    continue;
                }
                SchemaColumnCacheEntity columnEntity = new SchemaColumnCacheEntity();
                columnEntity.setConnectionId(connectionId);
                columnEntity.setDatabaseName(cacheDatabaseName);
                columnEntity.setTableName(tableName);
                columnEntity.setColumnName(columnName);
                columnEntity.setDataType(readOptionalStringColumn(rs, "data_type"));
                Integer columnSize = readOptionalIntegerColumn(rs, "character_maximum_length");
                if (columnSize == null) {
                    columnSize = readOptionalIntegerColumn(rs, "numeric_precision");
                }
                columnEntity.setColumnSize(columnSize);
                columnEntity.setDecimalDigits(readOptionalIntegerColumn(rs, "numeric_scale"));
                columnEntity.setColumnDefault(readOptionalStringColumn(rs, "column_default"));
                columnEntity.setAutoIncrementFlag(parseConfiguredAutoIncrementFlag(rs));
                columnEntity.setNullableFlag(parseConfiguredNullableFlag(rs));
                columnEntity.setColumnComment(readOptionalStringColumn(rs, "column_comment"));
                columnEntity.setIndexedFlag(indexedColumns.contains(columnName) ? 1 : 0);
                columnEntity.setPrimaryKeyFlag(primaryKeys.contains(columnName) ? 1 : 0);
                columnEntity.setUpdatedAt(now);
                columns.add(columnEntity);
            }
        }
        return columns;
    }

    private Set<String> queryPrimaryKeysByConfiguredSql(Connection connection,
                                                        String dbType,
                                                        String targetDatabaseName,
                                                        String tableName) throws SQLException {
        String primaryKeysSql = jdbcDriverResolver.findPrimaryKeysSql(dbType);
        if (primaryKeysSql.isBlank()) {
            return Set.of();
        }
        Set<String> keys = new HashSet<>();
        try (PreparedStatement statement = prepareConfiguredStatement(connection, primaryKeysSql, null, targetDatabaseName, tableName);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                String columnName = readOptionalStringColumn(rs, "column_name");
                if (columnName.isBlank()) {
                    columnName = normalize(rs.getString(1));
                }
                if (!columnName.isBlank()) {
                    keys.add(columnName);
                }
            }
        }
        return keys;
    }

    private Set<String> queryIndexedColumnsByMetadata(Connection connection,
                                                      String dbType,
                                                      String targetDatabaseName,
                                                      String tableName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        String catalog = resolveCatalog(connection, dbType, targetDatabaseName);
        String schemaPattern = resolveSchemaPattern(dbType, targetDatabaseName);
        return readIndexedColumns(metaData, catalog, schemaPattern, tableName);
    }

    private PreparedStatement prepareConfiguredStatement(Connection connection,
                                                         String sqlTemplate,
                                                         ConnectionEntity connectionEntity,
                                                         String databaseName,
                                                         String... extraParams) throws SQLException {
        String renderedSql = renderConfiguredSql(sqlTemplate, connectionEntity, databaseName, extraParams);
        PreparedStatement statement = connection.prepareStatement(renderedSql);
        int parameterIndex = 1;
        if (renderedSql.contains("?")) {
            statement.setString(parameterIndex++, normalize(databaseName));
            for (String value : extraParams) {
                statement.setString(parameterIndex++, value);
            }
        }
        return statement;
    }

    private String renderConfiguredSql(String sqlTemplate,
                                       ConnectionEntity connectionEntity,
                                       String databaseName,
                                       String... extraParams) {
        String rendered = sqlTemplate;
        String dbLiteral = toSqlStringLiteral(databaseName);
        String tableName = extraParams.length > 0 ? extraParams[0] : "";
        String tableLiteral = toSqlStringLiteral(tableName);
        rendered = rendered.replace("{database_literal}", dbLiteral);
        rendered = rendered.replace("{table_literal}", tableLiteral);
        if (connectionEntity != null) {
            rendered = rendered.replace("{configured_database_literal}", toSqlStringLiteral(connectionEntity.getDatabaseName()));
        }
        return rendered;
    }

    private int parseConfiguredNullableFlag(ResultSet rs) throws SQLException {
        String nullable = readOptionalStringColumn(rs, "is_nullable");
        if (nullable.isBlank()) {
            Object value = readOptionalObject(rs, "nullable_flag");
            if (value instanceof Number number) {
                return number.intValue() == 1 ? 1 : 0;
            }
            return 1;
        }
        return "YES".equalsIgnoreCase(nullable) || "Y".equalsIgnoreCase(nullable) || "TRUE".equalsIgnoreCase(nullable) ? 1 : 0;
    }

    private int parseConfiguredAutoIncrementFlag(ResultSet rs) throws SQLException {
        String autoIncrement = readOptionalStringColumn(rs, "is_auto_increment");
        if (autoIncrement.isBlank()) {
            String extra = readOptionalStringColumn(rs, "column_extra");
            if (!extra.isBlank() && extra.toLowerCase(Locale.ROOT).contains("auto_increment")) {
                return 1;
            }
            return 0;
        }
        return "YES".equalsIgnoreCase(autoIncrement)
            || "Y".equalsIgnoreCase(autoIncrement)
            || "TRUE".equalsIgnoreCase(autoIncrement) ? 1 : 0;
    }

    private Integer readOptionalIntegerColumn(ResultSet rs, String columnLabel) throws SQLException {
        Object value = readOptionalObject(rs, columnLabel);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Object readOptionalObject(ResultSet rs, String columnLabel) throws SQLException {
        if (!hasColumn(rs, columnLabel)) {
            return null;
        }
        return rs.getObject(columnLabel);
    }

    private String readOptionalStringColumn(ResultSet rs, String columnLabel) throws SQLException {
        if (!hasColumn(rs, columnLabel)) {
            return "";
        }
        return normalize(rs.getString(columnLabel));
    }

    private boolean hasColumn(ResultSet rs, String columnLabel) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        for (int index = 1; index <= metaData.getColumnCount(); index += 1) {
            if (columnLabel.equalsIgnoreCase(metaData.getColumnLabel(index))
                || columnLabel.equalsIgnoreCase(metaData.getColumnName(index))) {
                return true;
            }
        }
        return false;
    }

    private String toSqlStringLiteral(String value) {
        return "'" + normalize(value).replace("'", "''") + "'";
    }

    private boolean objectExists(Long connectionId, String databaseName, String objectType, String objectName) {
        return listObjectNames(connectionId, databaseName, objectType).stream()
            .anyMatch(item -> item.equalsIgnoreCase(normalize(objectName)));
    }

    private boolean isCacheExpired(SchemaSnapshot snapshot) {
        return System.currentTimeMillis() - snapshot.updatedAt() >= schemaCacheTtlMs;
    }

    private String fromCacheDatabaseName(String cacheDatabaseName) {
        if (DEFAULT_CACHE_DATABASE_NAME.equals(cacheDatabaseName)) {
            return "";
        }
        return cacheDatabaseName;
    }

    private List<String> querySingleColumn(Connection connection, String sql, int maxCount) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next() && values.size() < maxCount) {
                String value = rs.getString(1);
                if (value != null && !value.isBlank()) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    private Integer readIntegerColumn(ResultSet resultSet, String columnLabel) throws SQLException {
        Object value = resultSet.getObject(columnLabel);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ignore) {
            return null;
        }
    }

    private long safeLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignore) {
            return 0L;
        }
    }

    private Integer parseAutoIncrementFlag(String autoIncrementValue) {
        return "YES".equalsIgnoreCase(normalize(autoIncrementValue)) ? 1 : 0;
    }

    /**
     * 关键操作：针对未指定默认库的连接，允许由调用方指定目标库用于 Metadata 抓取。
     */
    private void applyDatabaseContext(Connection connection, String dbType, String targetDatabaseName) throws SQLException {
        String type = normalize(dbType).toUpperCase(Locale.ROOT);
        if (targetDatabaseName.isBlank()) {
            return;
        }
        if ("MYSQL".equals(type) || "POSTGRESQL".equals(type)) {
            connection.setCatalog(targetDatabaseName);
        }
        if ("SQLSERVER".equals(type) || "ORACLE".equals(type)) {
            connection.setSchema(targetDatabaseName);
        }
    }

    private String resolveCatalog(Connection connection, String dbType, String targetDatabaseName) throws SQLException {
        String type = normalize(dbType).toUpperCase(Locale.ROOT);
        if ("MYSQL".equals(type) || "POSTGRESQL".equals(type)) {
            if (!targetDatabaseName.isBlank()) {
                return targetDatabaseName;
            }
            return normalize(connection.getCatalog());
        }
        return null;
    }

    private String resolveSchemaPattern(String dbType, String targetDatabaseName) {
        String type = normalize(dbType).toUpperCase(Locale.ROOT);
        if (("SQLSERVER".equals(type) || "ORACLE".equals(type)) && !targetDatabaseName.isBlank()) {
            return targetDatabaseName;
        }
        return null;
    }

    private String resolveSchemaPatternForObjects(String dbType, String targetDatabaseName, String objectType) {
        String type = normalize(dbType).toUpperCase(Locale.ROOT);
        if ("POSTGRESQL".equals(type)
            && ("TABLE".equals(objectType) || "TABLES".equals(objectType)
            || "VIEW".equals(objectType) || "VIEWS".equals(objectType)
            || "FUNCTION".equals(objectType) || "FUNCTIONS".equals(objectType))) {
            return "public";
        }
        return resolveSchemaPattern(dbType, targetDatabaseName);
    }

    private String resolveTargetDatabaseName(ConnectionEntity connectionEntity, String requestedDatabaseName) {
        String req = normalize(requestedDatabaseName);
        if (!req.isBlank()) {
            return req;
        }
        String fromField = normalize(connectionEntity.getDatabaseName());
        if (!fromField.isBlank()) {
            return fromField;
        }
        return extractDatabaseNameFromHost(connectionEntity.getHost());
    }

    private String extractDatabaseNameFromHost(String rawHost) {
        String host = normalize(rawHost);
        if (host.isBlank()) {
            return "";
        }
        int marker = host.indexOf("://");
        if (marker >= 0) {
            host = host.substring(marker + 3);
        }
        int queryIndex = host.indexOf("?");
        if (queryIndex >= 0) {
            host = host.substring(0, queryIndex);
        }
        int slashIndex = host.indexOf("/");
        if (slashIndex >= 0 && slashIndex < host.length() - 1) {
            return host.substring(slashIndex + 1);
        }
        return "";
    }

    private Set<String> readPrimaryKeys(DatabaseMetaData metaData, String catalog, String schemaPattern, String tableName)
        throws SQLException {
        Set<String> keys = new HashSet<>();
        try (ResultSet pk = metaData.getPrimaryKeys(catalog, schemaPattern, tableName)) {
            while (pk.next()) {
                keys.add(pk.getString("COLUMN_NAME"));
            }
        }
        return keys;
    }

    private Set<String> readIndexedColumns(DatabaseMetaData metaData, String catalog, String schemaPattern, String tableName)
        throws SQLException {
        Set<String> columns = new HashSet<>();
        try (ResultSet indexes = metaData.getIndexInfo(catalog, schemaPattern, tableName, false, false)) {
            while (indexes.next()) {
                String columnName = indexes.getString("COLUMN_NAME");
                if (columnName != null) {
                    columns.add(columnName);
                }
            }
        }
        return columns;
    }

    private List<TableDetailVO.IndexDetailVO> readTableIndexDetails(DatabaseMetaData metaData,
                                                                    String catalog,
                                                                    String schemaPattern,
                                                                    String tableName) throws SQLException {
        Map<String, IndexAccumulator> indexMap = new LinkedHashMap<>();
        try (ResultSet indexes = metaData.getIndexInfo(catalog, schemaPattern, tableName, false, false)) {
            while (indexes.next()) {
                short type = indexes.getShort("TYPE");
                if (type == DatabaseMetaData.tableIndexStatistic) {
                    continue;
                }
                String indexName = normalize(indexes.getString("INDEX_NAME"));
                String columnName = normalize(indexes.getString("COLUMN_NAME"));
                if (indexName.isBlank() || columnName.isBlank() || "PRIMARY".equalsIgnoreCase(indexName)) {
                    continue;
                }
                boolean nonUnique = indexes.getBoolean("NON_UNIQUE");
                boolean unique = !nonUnique;
                IndexAccumulator accumulator = indexMap.computeIfAbsent(indexName, key -> new IndexAccumulator());
                Integer ordinal = readIntegerColumn(indexes, "ORDINAL_POSITION");
                int position = ordinal == null || ordinal <= 0 ? accumulator.columnsByPosition.size() + 1 : ordinal;
                accumulator.unique = accumulator.initialized ? (accumulator.unique && unique) : unique;
                accumulator.initialized = true;
                accumulator.columnsByPosition.put(position, columnName);
            }
        }
        return indexMap.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
            .map(entry -> {
                List<String> indexColumns = entry.getValue().columnsByPosition.values().stream()
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .toList();
                if (indexColumns.isEmpty()) {
                    return null;
                }
                TableDetailVO.IndexDetailVO detail = new TableDetailVO.IndexDetailVO();
                detail.setIndexName(entry.getKey());
                detail.setUnique(entry.getValue().unique);
                detail.setColumns(indexColumns);
                return detail;
            })
            .filter(Objects::nonNull)
            .toList();
    }

    private Map<String, String> readMysqlColumnExtras(Connection connection,
                                                      String databaseName,
                                                      String tableName) throws SQLException {
        if (normalize(databaseName).isBlank()) {
            return Map.of();
        }
        Map<String, String> extras = new HashMap<>();
        String sql = "SELECT COLUMN_NAME, EXTRA FROM information_schema.columns WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, databaseName);
            statement.setString(2, tableName);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String columnName = normalize(rs.getString("COLUMN_NAME"));
                    if (columnName.isBlank()) {
                        continue;
                    }
                    extras.put(columnName.toLowerCase(Locale.ROOT), normalize(rs.getString("EXTRA")));
                }
            }
        }
        return extras;
    }

    private boolean isCurrentTimestampDefault(String defaultValue) {
        String normalized = normalize(defaultValue)
            .replace("(", "")
            .replace(")", "")
            .replace("`", "")
            .replace("'", "")
            .toUpperCase(Locale.ROOT);
        return "CURRENT_TIMESTAMP".equals(normalized) || "NOW".equals(normalized);
    }

    private boolean hasOnUpdateCurrentTimestamp(String extra) {
        return normalize(extra).toUpperCase(Locale.ROOT).contains("ON UPDATE CURRENT_TIMESTAMP");
    }

    private List<String> readTableLikeObjects(DatabaseMetaData metaData, String catalog, String schemaPattern, String type)
        throws SQLException {
        List<String> names = new ArrayList<>();
        try (ResultSet rs = metaData.getTables(catalog, schemaPattern, "%", new String[]{type})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (name != null && !name.isBlank()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private List<String> readFunctions(DatabaseMetaData metaData, String catalog, String schemaPattern) throws SQLException {
        List<String> names = new ArrayList<>();
        try (ResultSet rs = metaData.getFunctions(catalog, schemaPattern, "%")) {
            while (rs.next()) {
                String name = rs.getString("FUNCTION_NAME");
                if (name != null && !name.isBlank()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private String buildTableSegment(List<SchemaColumnCacheEntity> columnList, String tableName) {
        StringBuilder builder = new StringBuilder();
        builder.append("表: ").append(tableName).append("\n");
        if (columnList.isEmpty()) {
            builder.append("- (字段详情按需加载)\n");
            return builder.toString();
        }
        for (SchemaColumnCacheEntity column : columnList) {
            builder.append("- ").append(column.getColumnName())
                .append(" ").append(column.getDataType())
                .append(column.getPrimaryKeyFlag() == 1 ? " [PK]" : "")
                .append(column.getIndexedFlag() == 1 ? " [IDX]" : "")
                .append("\n");
        }
        return builder.toString();
    }

    private String normalize(String value) {
        return Objects.toString(value, "").trim();
    }

    private String normalizeObjectType(String value) {
        String normalized = normalize(value).toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "view", "views" -> "views";
            case "function", "functions" -> "functions";
            default -> throw new BusinessException(400, "不支持的对象类型: " + value);
        };
    }

    private String escapeSingleQuote(String value) {
        return normalize(value).replace("'", "''");
    }

    private static class IndexAccumulator {
        private final TreeMap<Integer, String> columnsByPosition = new TreeMap<>();
        private boolean unique = true;
        private boolean initialized = false;
    }

    private String resolveCacheDatabaseName(Long connectionId, String databaseName) {
        ConnectionEntity connectionEntity = connectionService.getConnectionEntity(connectionId);
        String targetDatabaseName = resolveTargetDatabaseName(connectionEntity, databaseName);
        return toCacheDatabaseName(targetDatabaseName);
    }

    private String toCacheDatabaseName(String databaseName) {
        String normalized = normalize(databaseName);
        return normalized.isBlank() ? DEFAULT_CACHE_DATABASE_NAME : normalized;
    }

    private record SchemaCacheKey(Long connectionId, String databaseName) {
    }

    private record SchemaSnapshot(String databaseName,
                                  List<SchemaTableCacheEntity> tables,
                                  List<SchemaColumnCacheEntity> columns,
                                  int columnCount,
                                  long updatedAt) {
    }

    private record TableStatsSnapshot(String databaseName,
                                      Map<String, TableStat> tableStats,
                                      long updatedAt) {
    }

    private record TableStat(long rowEstimate, long tableSizeBytes) {
    }
}
