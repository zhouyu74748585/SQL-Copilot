package com.sqlcopilot.studio.service.impl;

import com.sqlcopilot.studio.dto.schema.*;
import com.sqlcopilot.studio.entity.ConnectionEntity;
import com.sqlcopilot.studio.entity.SchemaColumnCacheEntity;
import com.sqlcopilot.studio.entity.SchemaTableCacheEntity;
import com.sqlcopilot.studio.service.ConnectionService;
import com.sqlcopilot.studio.service.SchemaService;
import com.sqlcopilot.studio.service.rag.RagIngestionService;
import com.sqlcopilot.studio.util.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class SchemaServiceImpl implements SchemaService {

    private static final Logger log = LoggerFactory.getLogger(SchemaServiceImpl.class);
    private static final String DEFAULT_CACHE_DATABASE_NAME = "__default__";
    private static final long MIN_CACHE_TTL_MS = 5_000L;

    private final ConnectionService connectionService;
    private final RagIngestionService ragIngestionService;
    private final long schemaCacheTtlMs;
    private final Map<SchemaCacheKey, SchemaSnapshot> schemaSnapshotCache = new ConcurrentHashMap<>();
    private final Map<SchemaCacheKey, Object> schemaCacheLocks = new ConcurrentHashMap<>();

    public SchemaServiceImpl(ConnectionService connectionService,
                             RagIngestionService ragIngestionService,
                             @Value("${schema.cache.ttl-ms:300000}") long schemaCacheTtlMs) {
        this.connectionService = connectionService;
        this.ragIngestionService = ragIngestionService;
        this.schemaCacheTtlMs = Math.max(schemaCacheTtlMs, MIN_CACHE_TTL_MS);
    }

    @Override
    public SchemaSyncVO syncSchema(Long connectionId, String databaseName) {
        String cacheDatabaseName = resolveCacheDatabaseName(connectionId, databaseName);
        SchemaCacheKey cacheKey = new SchemaCacheKey(connectionId, cacheDatabaseName);
        SchemaSnapshot snapshot = refreshSnapshot(cacheKey, databaseName, true);

        SchemaSyncVO vo = new SchemaSyncVO();
        vo.setSuccess(Boolean.TRUE);
        vo.setTableCount(snapshot.tables().size());
        vo.setColumnCount(snapshot.columns().size());
        vo.setMessage("同步完成（内存缓存）");
        return vo;
    }

    @Override
    public SchemaOverviewVO getOverview(Long connectionId, String databaseName) {
        SchemaSnapshot snapshot = ensureCacheReady(connectionId, databaseName);

        SchemaOverviewVO vo = new SchemaOverviewVO();
        vo.setConnectionId(connectionId);
        vo.setTableCount(snapshot.tables().size());
        vo.setColumnCount(snapshot.columns().size());
        List<SchemaOverviewVO.TableSummaryVO> summaries = snapshot.tables().stream().map(item -> {
            SchemaOverviewVO.TableSummaryVO summary = new SchemaOverviewVO.TableSummaryVO();
            summary.setTableName(item.getTableName());
            summary.setTableComment(item.getTableComment());
            summary.setRowEstimate(item.getRowEstimate());
            summary.setTableSizeBytes(item.getTableSizeBytes());
            return summary;
        }).toList();
        vo.setTableSummaries(summaries);
        return vo;
    }

    @Override
    public TableDetailVO getTableDetail(Long connectionId, String databaseName, String tableName) {
        SchemaSnapshot snapshot = ensureCacheReady(connectionId, databaseName);
        List<SchemaColumnCacheEntity> columnCache = snapshot.columns().stream()
            .filter(item -> tableName.equals(item.getTableName()))
            .toList();

        TableDetailVO vo = new TableDetailVO();
        vo.setConnectionId(connectionId);
        vo.setTableName(tableName);
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
            return detail;
        }).toList();
        vo.setColumns(columnDetails);
        return vo;
    }

    @Override
    public List<String> listDatabases(Long connectionId) {
        ConnectionEntity connectionEntity = connectionService.getConnectionEntity(connectionId);
        String dbType = normalize(connectionEntity.getDbType()).toUpperCase(Locale.ROOT);

        if ("SQLITE".equals(dbType)) {
            String sqliteName = normalize(connectionEntity.getDatabaseName());
            return List.of(sqliteName.isBlank() ? "main" : sqliteName);
        }

        try (Connection connection = connectionService.openTargetConnection(connectionId)) {
            return switch (dbType) {
                case "MYSQL" -> querySingleColumn(connection, "SHOW DATABASES", 2000);
                case "POSTGRESQL" -> querySingleColumn(connection,
                    "SELECT datname FROM pg_database WHERE datistemplate = false ORDER BY datname", 2000);
                case "SQLSERVER" -> querySingleColumn(connection, "SELECT name FROM sys.databases ORDER BY name", 2000);
                case "ORACLE" -> querySingleColumn(connection, "SELECT username FROM all_users ORDER BY username", 2000);
                default -> List.of();
            };
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
        SchemaSnapshot snapshot = ensureCacheReady(req.getConnectionId(), req.getDatabaseName());

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
        for (SchemaColumnCacheEntity column : columns) {
            String lower = column.getColumnName().toLowerCase(Locale.ROOT);
            if (question.contains(lower)) {
                scoreMap.compute(column.getTableName(), (k, v) -> (v == null ? 0 : v) + 1);
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
            String segment = buildTableSegment(tableColumns.getOrDefault(tableName, List.of()), tableName);
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
                refreshSnapshot(cacheKey, fromCacheDatabaseName(cacheKey.databaseName()), true);
            } catch (Exception ex) {
                log.warn("定时刷新 Schema 缓存失败, connectionId={}, databaseName={}, reason={}",
                    cacheKey.connectionId(), cacheKey.databaseName(), ex.getMessage());
            }
        }
    }

    private SchemaSnapshot ensureCacheReady(Long connectionId, String databaseName) {
        String cacheDatabaseName = resolveCacheDatabaseName(connectionId, databaseName);
        SchemaCacheKey cacheKey = new SchemaCacheKey(connectionId, cacheDatabaseName);
        return refreshSnapshot(cacheKey, databaseName, false);
    }

    private SchemaSnapshot refreshSnapshot(SchemaCacheKey cacheKey, String databaseName, boolean forceRefresh) {
        Object lock = schemaCacheLocks.computeIfAbsent(cacheKey, key -> new Object());
        synchronized (lock) {
            SchemaSnapshot current = schemaSnapshotCache.get(cacheKey);
            if (!forceRefresh && current != null && !isCacheExpired(current)) {
                return current;
            }

            try {
                SchemaSnapshot latest = loadSnapshot(cacheKey.connectionId(), databaseName, cacheKey.databaseName());
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

    private SchemaSnapshot loadSnapshot(Long connectionId, String databaseName, String cacheDatabaseName) {
        long now = System.currentTimeMillis();
        List<SchemaTableCacheEntity> tables = new ArrayList<>();
        List<SchemaColumnCacheEntity> columns = new ArrayList<>();

        ConnectionEntity connectionEntity = connectionService.getConnectionEntity(connectionId);
        String targetDatabaseName = resolveTargetDatabaseName(connectionEntity, databaseName);

        try (Connection connection = connectionService.openTargetConnection(connectionId)) {
            applyDatabaseContext(connection, connectionEntity.getDbType(), targetDatabaseName);
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
                }
            }
        } catch (SQLException ex) {
            throw new BusinessException(500, "Schema 同步失败: " + ex.getMessage());
        }

        List<SchemaTableCacheEntity> readonlyTables = List.copyOf(tables);
        List<SchemaColumnCacheEntity> readonlyColumns = List.copyOf(columns);
        // 关键操作：元数据快照构建完成后异步链路写入 RAG（失败不影响主流程）。
        ragIngestionService.ingestSchema(connectionId, cacheDatabaseName, readonlyTables, readonlyColumns);
        return new SchemaSnapshot(readonlyTables, readonlyColumns, now);
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
        if (columnList.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("表: ").append(tableName).append("\n");
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

    private record SchemaSnapshot(List<SchemaTableCacheEntity> tables,
                                  List<SchemaColumnCacheEntity> columns,
                                  long updatedAt) {
    }
}
