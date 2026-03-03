package com.sqlcopilot.studio.service.rag.impl;

import com.sqlcopilot.studio.entity.ConnectionEntity;
import com.sqlcopilot.studio.entity.QueryHistoryEntity;
import com.sqlcopilot.studio.entity.SchemaColumnCacheEntity;
import com.sqlcopilot.studio.entity.SchemaTableCacheEntity;
import com.sqlcopilot.studio.service.ConnectionService;
import com.sqlcopilot.studio.service.rag.QdrantClientService;
import com.sqlcopilot.studio.service.rag.RagEmbeddingService;
import com.sqlcopilot.studio.service.rag.RagIngestionService;
import com.sqlcopilot.studio.service.rag.model.QdrantPoint;
import com.sqlcopilot.studio.service.rag.model.RagCollectionNames;
import com.sqlcopilot.studio.service.rag.model.SqlFeatureMeta;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class RagIngestionServiceImpl implements RagIngestionService {

    private static final Logger log = LoggerFactory.getLogger(RagIngestionServiceImpl.class);
    private static final Pattern TABLE_PATTERN = Pattern.compile("(?i)\\b(?:from|join|update|into|table)\\s+([a-zA-Z0-9_$.`\"]+)");
    private static final Pattern CTE_PATTERN = Pattern.compile("(?is)([a-zA-Z0-9_]+)\\s+as\\s*\\((.*?)\\)");
    private static final Pattern SELECT_SPLIT_PATTERN = Pattern.compile("(?i)\\bselect\\b");
    private static final Pattern COLUMN_ALIAS_PATTERN = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)\\.([a-zA-Z_][a-zA-Z0-9_]*)");

    private final QdrantClientService qdrantClientService;
    private final RagEmbeddingService ragEmbeddingService;
    private final ConnectionService connectionService;

    private final boolean ragEnabled;
    private final boolean sqlFragmentEnabled;
    private final int sqlFragmentMaxCount;
    private final int embeddingBatchSize;
    private final int embeddingParallelism;
    private final int qdrantUpsertBatchSize;
    private final ExecutorService embeddingBatchExecutor;
    private final RagCollectionNames collectionNames;

    public RagIngestionServiceImpl(QdrantClientService qdrantClientService,
                                   RagEmbeddingService ragEmbeddingService,
                                   ConnectionService connectionService,
                                   @Value("${rag.enabled:true}") boolean ragEnabled,
                                   @Value("${rag.collection.schema-table:schema_table}") String schemaTableCollection,
                                   @Value("${rag.collection.schema-column:schema_column}") String schemaColumnCollection,
                                   @Value("${rag.collection.sql-history:sql_history}") String sqlHistoryCollection,
                                   @Value("${rag.collection.sql-fragment:sql_fragment}") String sqlFragmentCollection,
                                   @Value("${rag.sql-fragment.enabled:true}") boolean sqlFragmentEnabled,
                                   @Value("${rag.sql-fragment.max-count:8}") int sqlFragmentMaxCount,
                                   @Value("${rag.embedding.batch-size:16}") int embeddingBatchSize,
                                   @Value("${rag.embedding.parallelism:2}") int embeddingParallelism,
                                   @Value("${rag.qdrant.upsert-batch-size:300}") int qdrantUpsertBatchSize) {
        this.qdrantClientService = qdrantClientService;
        this.ragEmbeddingService = ragEmbeddingService;
        this.connectionService = connectionService;
        this.ragEnabled = ragEnabled;
        this.sqlFragmentEnabled = sqlFragmentEnabled;
        this.sqlFragmentMaxCount = Math.max(1, sqlFragmentMaxCount);
        this.embeddingBatchSize = Math.max(1, embeddingBatchSize);
        this.embeddingParallelism = Math.max(1, embeddingParallelism);
        this.qdrantUpsertBatchSize = Math.max(1, qdrantUpsertBatchSize);
        this.embeddingBatchExecutor = this.embeddingParallelism <= 1
            ? null
            : Executors.newFixedThreadPool(this.embeddingParallelism, new EmbeddingThreadFactory());
        this.collectionNames = new RagCollectionNames(
            schemaTableCollection,
            schemaColumnCollection,
            sqlHistoryCollection,
            sqlFragmentCollection
        );
    }

    @PreDestroy
    public void shutdownEmbeddingExecutor() {
        if (embeddingBatchExecutor != null) {
            embeddingBatchExecutor.shutdownNow();
        }
    }

    @Override
    public void ingestSchema(Long connectionId,
                             String databaseName,
                             List<SchemaTableCacheEntity> tableMetaList,
                             List<SchemaColumnCacheEntity> columnMetaList) {
        if (!ragEnabled) {
            return;
        }
        if (tableMetaList == null || tableMetaList.isEmpty()) {
            return;
        }

        String normalizedDatabaseName = normalizeDatabaseName(databaseName);

        try {
            List<SchemaColumnCacheEntity> safeColumnMetaList = columnMetaList == null ? List.of() : columnMetaList;
            Map<String, List<SchemaColumnCacheEntity>> tableColumns = safeColumnMetaList.stream()
                .collect(Collectors.groupingBy(SchemaColumnCacheEntity::getTableName));

            List<PointEmbeddingTask> tableTasks = new ArrayList<>();
            for (SchemaTableCacheEntity table : tableMetaList) {
                List<SchemaColumnCacheEntity> columns = tableColumns.getOrDefault(table.getTableName(), List.of());
                SchemaTablePayload payload = buildTablePayload(connectionId, normalizedDatabaseName, table, columns);
                Map<String, Object> metadata = sanitizeMetadata(payload.toMetadataMap());
                tableTasks.add(new PointEmbeddingTask(
                    stablePointId("schema_table", connectionId, normalizedDatabaseName, table.getTableName()),
                    buildTableDocumentText(table, columns),
                    metadata
                ));
            }
            List<QdrantPoint> tablePoints = buildQdrantPoints(tableTasks);
            writePoints(collectionNames.getSchemaTable(), tablePoints);

            List<PointEmbeddingTask> columnTasks = new ArrayList<>();
            for (SchemaColumnCacheEntity column : safeColumnMetaList) {
                SchemaColumnPayload payload = buildColumnPayload(connectionId, normalizedDatabaseName, column);
                Map<String, Object> metadata = sanitizeMetadata(payload.toMetadataMap());
                columnTasks.add(new PointEmbeddingTask(
                    stablePointId("schema_column", connectionId,
                        normalizedDatabaseName, column.getTableName(), column.getColumnName()),
                    buildColumnDocumentText(column),
                    metadata
                ));
            }
            List<QdrantPoint> columnPoints = buildQdrantPoints(columnTasks);
            writePoints(collectionNames.getSchemaColumn(), columnPoints);
        } catch (Exception ex) {
            ex.printStackTrace();
            log.warn("Schema RAG 写入失败, connectionId={}, databaseName={}, reason={}",
                connectionId, normalizedDatabaseName, ex.getMessage());
        }
    }

    @Override
    public void ingestSqlHistory(QueryHistoryEntity historyEntity) {
        if (!ragEnabled || historyEntity == null || isBlank(historyEntity.getSqlText())) {
            return;
        }

        try {
            ConnectionEntity connectionEntity = connectionService.getConnectionEntity(historyEntity.getConnectionId());
            String databaseName = normalizeDatabaseName(connectionEntity.getDatabaseName());
            SqlFeatureMeta featureMeta = extractSqlFeatureMeta(historyEntity.getSqlText());

            SqlHistoryPayload payload = new SqlHistoryPayload(
                historyEntity.getConnectionId(),
                databaseName,
                safeText(historyEntity.getSessionId()),
                safeText(historyEntity.getPromptText()),
                safeText(historyEntity.getSqlText()),
                historyEntity.getExecutionMs() == null ? 0L : historyEntity.getExecutionMs(),
                historyEntity.getSuccessFlag() != null && historyEntity.getSuccessFlag() == 1,
                historyEntity.getCreatedAt() == null ? System.currentTimeMillis() : historyEntity.getCreatedAt(),
                featureMeta.getTables(),
                featureMeta.getColumns(),
                featureMeta.getJoinCount(),
                featureMeta.getHasCte()
            );

            String historyText = buildSqlHistoryDocumentText(payload);
            Map<String, Object> historyMetadata = sanitizeMetadata(payload.toMetadataMap());
            List<QdrantPoint> historyPoints = buildQdrantPoints(List.of(new PointEmbeddingTask(
                stablePointId("sql_history", historyEntity.getConnectionId(), databaseName,
                    String.valueOf(historyEntity.getId() == null ? payload.getCreatedAt() : historyEntity.getId())),
                historyText,
                historyMetadata
            )));
            writePoints(collectionNames.getSqlHistory(), historyPoints);

            if (sqlFragmentEnabled) {
                List<SqlFragmentPayload> fragments = buildSqlFragments(payload);
                if (!fragments.isEmpty()) {
                    List<PointEmbeddingTask> fragmentTasks = new ArrayList<>();
                    for (SqlFragmentPayload fragment : fragments) {
                        Map<String, Object> fragmentMetadata = sanitizeMetadata(fragment.toMetadataMap());
                        fragmentTasks.add(new PointEmbeddingTask(
                            stablePointId("sql_fragment", historyEntity.getConnectionId(), databaseName,
                                String.valueOf(historyEntity.getId() == null ? payload.getCreatedAt() : historyEntity.getId()),
                                fragment.getFragmentType(),
                                String.valueOf(fragment.getFragmentIndex())),
                            fragment.getFragmentText(),
                            fragmentMetadata
                        ));
                    }
                    List<QdrantPoint> fragmentPoints = buildQdrantPoints(fragmentTasks);
                    writePoints(collectionNames.getSqlFragment(), fragmentPoints);
                }
            }
        } catch (Exception ex) {
            log.warn("SQL 历史 RAG 写入失败, historyId={}, reason={}", historyEntity.getId(), ex.getMessage());
        }
    }

    private void writePoints(String collectionName, List<QdrantPoint> points) {
        if (points == null || points.isEmpty()) {
            return;
        }
        int vectorSize = points.get(0).getVector().size();
        qdrantClientService.ensureCollection(collectionName, vectorSize);
        for (int start = 0; start < points.size(); start += qdrantUpsertBatchSize) {
            int end = Math.min(start + qdrantUpsertBatchSize, points.size());
            qdrantClientService.upsertPoints(collectionName, points.subList(start, end));
        }
    }

    private List<QdrantPoint> buildQdrantPoints(List<PointEmbeddingTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        List<String> texts = tasks.stream().map(PointEmbeddingTask::text).toList();
        List<List<Float>> vectors = embedTextsInBatches(texts);
        if (vectors.size() != tasks.size()) {
            throw new IllegalStateException("向量数量与任务数量不一致");
        }
        List<QdrantPoint> points = new ArrayList<>(tasks.size());
        for (int i = 0; i < tasks.size(); i++) {
            PointEmbeddingTask task = tasks.get(i);
            points.add(new QdrantPoint(task.pointId(), vectors.get(i), task.metadata()));
        }
        return points;
    }

    private List<List<Float>> embedTextsInBatches(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<EmbeddingBatchTask> tasks = new ArrayList<>();
        int order = 0;
        for (int start = 0; start < texts.size(); start += embeddingBatchSize) {
            int end = Math.min(start + embeddingBatchSize, texts.size());
            tasks.add(new EmbeddingBatchTask(order++, List.copyOf(texts.subList(start, end))));
        }
        if (embeddingBatchExecutor == null || tasks.size() == 1) {
            List<List<Float>> vectors = new ArrayList<>(texts.size());
            for (EmbeddingBatchTask task : tasks) {
                vectors.addAll(ragEmbeddingService.embedTexts(task.texts()));
            }
            return vectors;
        }

        try {
            // 关键操作：批次内并发受 embeddingParallelism 限制，兼顾吞吐与资源占用。
            List<CompletableFuture<EmbeddingBatchResult>> futures = tasks.stream()
                .map(task -> CompletableFuture.supplyAsync(
                    () -> new EmbeddingBatchResult(task.order(), ragEmbeddingService.embedTexts(task.texts())),
                    embeddingBatchExecutor
                ))
                .toList();
            List<EmbeddingBatchResult> batchResults = futures.stream().map(CompletableFuture::join)
                .sorted(Comparator.comparingInt(EmbeddingBatchResult::order))
                .toList();
            List<List<Float>> vectors = new ArrayList<>(texts.size());
            for (EmbeddingBatchResult result : batchResults) {
                vectors.addAll(result.vectors());
            }
            return vectors;
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("批量向量化失败: " + Objects.toString(cause == null ? ex : cause, ex.toString()));
        }
    }

    private String buildTableDocumentText(SchemaTableCacheEntity table, List<SchemaColumnCacheEntity> columns) {
        StringBuilder builder = new StringBuilder();
        builder.append("数据库: ").append(normalizeDatabaseName(table.getDatabaseName())).append("\n");
        builder.append("表名: ").append(table.getTableName()).append("\n");
        builder.append("备注: ").append(safeText(table.getTableComment())).append("\n");
        if (!columns.isEmpty()) {
            builder.append("字段: ");
            List<String> preview = columns.stream()
                .sorted(Comparator.comparing(SchemaColumnCacheEntity::getColumnName, String.CASE_INSENSITIVE_ORDER))
                .limit(30)
                .map(item -> item.getColumnName() + "(" + safeText(item.getDataType()) + ")")
                .toList();
            builder.append(String.join(", ", preview));
        }
        return builder.toString();
    }

    private String buildColumnDocumentText(SchemaColumnCacheEntity column) {
        return "数据库: " + normalizeDatabaseName(column.getDatabaseName()) + "\n"
            + "表名: " + safeText(column.getTableName()) + "\n"
            + "字段: " + safeText(column.getColumnName()) + "\n"
            + "类型: " + safeText(column.getDataType()) + "\n"
            + "备注: " + safeText(column.getColumnComment()) + "\n"
            + "主键: " + (column.getPrimaryKeyFlag() != null && column.getPrimaryKeyFlag() == 1) + "\n"
            + "索引: " + (column.getIndexedFlag() != null && column.getIndexedFlag() == 1);
    }

    private String buildSqlHistoryDocumentText(SqlHistoryPayload payload) {
        return "连接ID: " + payload.getConnectionId() + "\n"
            + "数据库: " + payload.getDatabaseName() + "\n"
            + "Prompt: " + payload.getPromptText() + "\n"
            + "SQL: " + payload.getSqlText() + "\n"
            + "执行耗时: " + payload.getExecutionMs() + "ms\n"
            + "成功: " + payload.getSuccess() + "\n"
            + "涉及表: " + String.join(",", payload.getTables()) + "\n"
            + "涉及列: " + String.join(",", payload.getColumns()) + "\n"
            + "JOIN数量: " + payload.getJoinCount() + "\n"
            + "包含CTE: " + payload.getHasCte();
    }

    private SchemaTablePayload buildTablePayload(Long connectionId,
                                                 String databaseName,
                                                 SchemaTableCacheEntity table,
                                                 List<SchemaColumnCacheEntity> columns) {
        List<String> columnNames = columns.stream().map(SchemaColumnCacheEntity::getColumnName)
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
        return new SchemaTablePayload(
            connectionId,
            databaseName,
            table.getTableName(),
            safeText(table.getTableComment()),
            table.getRowEstimate() == null ? 0L : table.getRowEstimate(),
            table.getTableSizeBytes() == null ? 0L : table.getTableSizeBytes(),
            columnNames
        );
    }

    private SchemaColumnPayload buildColumnPayload(Long connectionId,
                                                   String databaseName,
                                                   SchemaColumnCacheEntity column) {
        return new SchemaColumnPayload(
            connectionId,
            databaseName,
            safeText(column.getTableName()),
            safeText(column.getColumnName()),
            safeText(column.getDataType()),
            column.getColumnSize(),
            column.getDecimalDigits(),
            safeText(column.getColumnDefault()),
            column.getAutoIncrementFlag() != null && column.getAutoIncrementFlag() == 1,
            column.getNullableFlag() == null || column.getNullableFlag() == 1,
            safeText(column.getColumnComment()),
            column.getIndexedFlag() != null && column.getIndexedFlag() == 1,
            column.getPrimaryKeyFlag() != null && column.getPrimaryKeyFlag() == 1
        );
    }

    private List<SqlFragmentPayload> buildSqlFragments(SqlHistoryPayload payload) {
        List<SqlFragmentPayload> fragments = new ArrayList<>();
        String sql = payload.getSqlText();

        Matcher cteMatcher = CTE_PATTERN.matcher(sql);
        int idx = 0;
        while (cteMatcher.find() && fragments.size() < sqlFragmentMaxCount) {
            String cteName = safeText(cteMatcher.group(1));
            String cteBody = safeText(cteMatcher.group(2));
            if (cteBody.isBlank()) {
                continue;
            }
            fragments.add(new SqlFragmentPayload(
                payload.getConnectionId(),
                payload.getDatabaseName(),
                payload.getSessionId(),
                payload.getCreatedAt(),
                "CTE",
                idx++,
                cteName,
                cteBody,
                payload.getTables(),
                payload.getColumns(),
                payload.getJoinCount(),
                payload.getHasCte()
            ));
        }

        List<Integer> selectPositions = new ArrayList<>();
        Matcher selectMatcher = SELECT_SPLIT_PATTERN.matcher(sql);
        while (selectMatcher.find()) {
            selectPositions.add(selectMatcher.start());
        }

        for (int i = 0; i < selectPositions.size() && fragments.size() < sqlFragmentMaxCount; i++) {
            int start = selectPositions.get(i);
            int end = (i + 1 < selectPositions.size()) ? selectPositions.get(i + 1) : sql.length();
            String fragment = safeText(sql.substring(start, end));
            if (fragment.length() < 12) {
                continue;
            }
            fragments.add(new SqlFragmentPayload(
                payload.getConnectionId(),
                payload.getDatabaseName(),
                payload.getSessionId(),
                payload.getCreatedAt(),
                "SELECT",
                idx++,
                "select_" + i,
                fragment,
                payload.getTables(),
                payload.getColumns(),
                payload.getJoinCount(),
                payload.getHasCte()
            ));
        }
        return fragments;
    }

    private SqlFeatureMeta extractSqlFeatureMeta(String sqlText) {
        String sql = safeText(sqlText);
        String lowered = sql.toLowerCase(Locale.ROOT);

        int joinCount = 0;
        Matcher joinMatcher = Pattern.compile("(?i)\\bjoin\\b").matcher(sql);
        while (joinMatcher.find()) {
            joinCount++;
        }

        boolean hasCte = lowered.startsWith("with ") || lowered.contains(" with ");

        Set<String> tableSet = new HashSet<>();
        Matcher tableMatcher = TABLE_PATTERN.matcher(sql);
        while (tableMatcher.find()) {
            String table = normalizeIdentifier(tableMatcher.group(1));
            if (!table.isBlank()) {
                tableSet.add(table);
            }
        }

        Set<String> columnSet = new HashSet<>();
        Matcher colMatcher = COLUMN_ALIAS_PATTERN.matcher(sql);
        while (colMatcher.find()) {
            String col = normalizeIdentifier(colMatcher.group(2));
            if (!col.isBlank()) {
                columnSet.add(col);
            }
        }

        List<String> tables = tableSet.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        List<String> columns = columnSet.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        return new SqlFeatureMeta(tables, columns, joinCount, hasCte);
    }

    private String normalizeIdentifier(String raw) {
        String value = safeText(raw)
            .replace("`", "")
            .replace("\"", "")
            .trim();
        if (value.contains(".")) {
            String[] parts = value.split("\\.");
            return parts[parts.length - 1];
        }
        return value;
    }

    private String stablePointId(Object... values) {
        String joined = java.util.Arrays.stream(values).map(String::valueOf).collect(Collectors.joining("|"));
        return UUID.nameUUIDFromBytes(joined.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String normalizeDatabaseName(String databaseName) {
        String value = safeText(databaseName);
        return value.isBlank() ? "__default__" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String safeText(String value) {
        return Objects.toString(value, "").trim();
    }

    private Map<String, Object> sanitizeMetadata(Map<String, Object> metadata) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (metadata == null || metadata.isEmpty()) {
            return result;
        }
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            Object sanitized = sanitizeMetadataValue(entry.getValue());
            if (sanitized != null) {
                result.put(entry.getKey(), sanitized);
            }
        }
        return result;
    }

    private Object sanitizeMetadataValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> nested = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                Object sanitized = sanitizeMetadataValue(entry.getValue());
                if (sanitized != null) {
                    nested.put(String.valueOf(entry.getKey()), sanitized);
                }
            }
            return nested;
        }
        if (value instanceof List<?> listValue) {
            List<Object> list = new ArrayList<>();
            for (Object item : listValue) {
                Object sanitized = sanitizeMetadataValue(item);
                if (sanitized != null) {
                    list.add(sanitized);
                }
            }
            return list;
        }
        return value;
    }

    private record PointEmbeddingTask(String pointId, String text, Map<String, Object> metadata) {
    }

    private record EmbeddingBatchTask(int order, List<String> texts) {
    }

    private record EmbeddingBatchResult(int order, List<List<Float>> vectors) {
    }

    private static class EmbeddingThreadFactory implements ThreadFactory {

        private final AtomicInteger index = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "rag-embedding-batch-" + index.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }

    private static class SchemaTablePayload {
        private final Long connectionId;
        private final String databaseName;
        private final String tableName;
        private final String tableComment;
        private final Long rowEstimate;
        private final Long tableSizeBytes;
        private final List<String> columns;

        SchemaTablePayload(Long connectionId,
                           String databaseName,
                           String tableName,
                           String tableComment,
                           Long rowEstimate,
                           Long tableSizeBytes,
                           List<String> columns) {
            this.connectionId = connectionId;
            this.databaseName = databaseName;
            this.tableName = tableName;
            this.tableComment = tableComment;
            this.rowEstimate = rowEstimate;
            this.tableSizeBytes = tableSizeBytes;
            this.columns = columns;
        }

        Map<String, Object> toMetadataMap() {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("connection_id", connectionId);
            metadata.put("database_name", databaseName);
            metadata.put("table_name", tableName);
            metadata.put("table_comment", tableComment);
            metadata.put("row_estimate", rowEstimate);
            metadata.put("table_size_bytes", tableSizeBytes);
            metadata.put("columns", columns);
            return metadata;
        }
    }

    private static class SchemaColumnPayload {
        private final Long connectionId;
        private final String databaseName;
        private final String tableName;
        private final String columnName;
        private final String dataType;
        private final Integer columnSize;
        private final Integer decimalDigits;
        private final String columnDefault;
        private final Boolean autoIncrement;
        private final Boolean nullable;
        private final String columnComment;
        private final Boolean indexed;
        private final Boolean primaryKey;

        SchemaColumnPayload(Long connectionId,
                            String databaseName,
                            String tableName,
                            String columnName,
                            String dataType,
                            Integer columnSize,
                            Integer decimalDigits,
                            String columnDefault,
                            Boolean autoIncrement,
                            Boolean nullable,
                            String columnComment,
                            Boolean indexed,
                            Boolean primaryKey) {
            this.connectionId = connectionId;
            this.databaseName = databaseName;
            this.tableName = tableName;
            this.columnName = columnName;
            this.dataType = dataType;
            this.columnSize = columnSize;
            this.decimalDigits = decimalDigits;
            this.columnDefault = columnDefault;
            this.autoIncrement = autoIncrement;
            this.nullable = nullable;
            this.columnComment = columnComment;
            this.indexed = indexed;
            this.primaryKey = primaryKey;
        }

        Map<String, Object> toMetadataMap() {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("connection_id", connectionId);
            metadata.put("database_name", databaseName);
            metadata.put("table_name", tableName);
            metadata.put("column_name", columnName);
            metadata.put("data_type", dataType);
            metadata.put("column_size", columnSize);
            metadata.put("decimal_digits", decimalDigits);
            metadata.put("column_default", columnDefault);
            metadata.put("auto_increment", autoIncrement);
            metadata.put("nullable", nullable);
            metadata.put("column_comment", columnComment);
            metadata.put("indexed", indexed);
            metadata.put("primary_key", primaryKey);
            return metadata;
        }
    }

    private static class SqlHistoryPayload {
        private final Long connectionId;
        private final String databaseName;
        private final String sessionId;
        private final String promptText;
        private final String sqlText;
        private final Long executionMs;
        private final Boolean success;
        private final Long createdAt;
        private final List<String> tables;
        private final List<String> columns;
        private final Integer joinCount;
        private final Boolean hasCte;

        SqlHistoryPayload(Long connectionId,
                          String databaseName,
                          String sessionId,
                          String promptText,
                          String sqlText,
                          Long executionMs,
                          Boolean success,
                          Long createdAt,
                          List<String> tables,
                          List<String> columns,
                          Integer joinCount,
                          Boolean hasCte) {
            this.connectionId = connectionId;
            this.databaseName = databaseName;
            this.sessionId = sessionId;
            this.promptText = promptText;
            this.sqlText = sqlText;
            this.executionMs = executionMs;
            this.success = success;
            this.createdAt = createdAt;
            this.tables = tables;
            this.columns = columns;
            this.joinCount = joinCount;
            this.hasCte = hasCte;
        }

        Map<String, Object> toMetadataMap() {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("connection_id", connectionId);
            metadata.put("database_name", databaseName);
            metadata.put("session_id", sessionId);
            metadata.put("prompt_text", promptText);
            metadata.put("sql_text", sqlText);
            metadata.put("execution_ms", executionMs);
            metadata.put("success", success);
            metadata.put("created_at", createdAt);
            metadata.put("tables", tables);
            metadata.put("columns", columns);
            metadata.put("join_count", joinCount);
            metadata.put("has_cte", hasCte);
            return metadata;
        }

        public Long getConnectionId() {
            return connectionId;
        }

        public String getDatabaseName() {
            return databaseName;
        }

        public String getSessionId() {
            return sessionId;
        }

        public String getPromptText() {
            return promptText;
        }

        public String getSqlText() {
            return sqlText;
        }

        public Long getExecutionMs() {
            return executionMs;
        }

        public Boolean getSuccess() {
            return success;
        }

        public Long getCreatedAt() {
            return createdAt;
        }

        public List<String> getTables() {
            return tables;
        }

        public List<String> getColumns() {
            return columns;
        }

        public Integer getJoinCount() {
            return joinCount;
        }

        public Boolean getHasCte() {
            return hasCte;
        }
    }

    private static class SqlFragmentPayload {
        private final Long connectionId;
        private final String databaseName;
        private final String sessionId;
        private final Long createdAt;
        private final String fragmentType;
        private final Integer fragmentIndex;
        private final String fragmentName;
        private final String fragmentText;
        private final List<String> tables;
        private final List<String> columns;
        private final Integer joinCount;
        private final Boolean hasCte;

        SqlFragmentPayload(Long connectionId,
                           String databaseName,
                           String sessionId,
                           Long createdAt,
                           String fragmentType,
                           Integer fragmentIndex,
                           String fragmentName,
                           String fragmentText,
                           List<String> tables,
                           List<String> columns,
                           Integer joinCount,
                           Boolean hasCte) {
            this.connectionId = connectionId;
            this.databaseName = databaseName;
            this.sessionId = sessionId;
            this.createdAt = createdAt;
            this.fragmentType = fragmentType;
            this.fragmentIndex = fragmentIndex;
            this.fragmentName = fragmentName;
            this.fragmentText = fragmentText;
            this.tables = tables;
            this.columns = columns;
            this.joinCount = joinCount;
            this.hasCte = hasCte;
        }

        Map<String, Object> toMetadataMap() {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("connection_id", connectionId);
            metadata.put("database_name", databaseName);
            metadata.put("session_id", sessionId);
            metadata.put("created_at", createdAt);
            metadata.put("fragment_type", fragmentType);
            metadata.put("fragment_index", fragmentIndex);
            metadata.put("fragment_name", fragmentName);
            metadata.put("fragment_text", fragmentText);
            metadata.put("tables", tables);
            metadata.put("columns", columns);
            metadata.put("join_count", joinCount);
            metadata.put("has_cte", hasCte);
            return metadata;
        }

        public String getFragmentType() {
            return fragmentType;
        }

        public Integer getFragmentIndex() {
            return fragmentIndex;
        }

        public String getFragmentText() {
            return fragmentText;
        }
    }
}
