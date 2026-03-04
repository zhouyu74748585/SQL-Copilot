package com.sqlcopilot.studio.service.rag.impl;

import com.sqlcopilot.studio.service.rag.QdrantClientService;
import com.sqlcopilot.studio.service.rag.RagEmbeddingService;
import com.sqlcopilot.studio.service.rag.RagRetrievalService;
import com.sqlcopilot.studio.service.rag.model.QdrantScoredPoint;
import com.sqlcopilot.studio.service.rag.model.RagCollectionNames;
import com.sqlcopilot.studio.service.rag.model.RagPromptContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

@Service
public class RagRetrievalServiceImpl implements RagRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RagRetrievalServiceImpl.class);

    private final boolean ragEnabled;
    private final RagEmbeddingService ragEmbeddingService;
    private final QdrantClientService qdrantClientService;
    private final RagCollectionNames collectionNames;
    private final int schemaTableLimit;
    private final int schemaColumnLimit;
    private final int sqlHistoryLimit;

    public RagRetrievalServiceImpl(@Value("${rag.enabled:true}") boolean ragEnabled,
                                   @Value("${rag.collection.schema-table:schema_table}") String schemaTableCollection,
                                   @Value("${rag.collection.schema-column:schema_column}") String schemaColumnCollection,
                                   @Value("${rag.collection.sql-history:sql_history}") String sqlHistoryCollection,
                                   @Value("${rag.collection.sql-fragment:sql_fragment}") String sqlFragmentCollection,
                                   @Value("${rag.retrieval.schema-table-limit:6}") int schemaTableLimit,
                                   @Value("${rag.retrieval.schema-column-limit:8}") int schemaColumnLimit,
                                   @Value("${rag.retrieval.sql-history-limit:6}") int sqlHistoryLimit,
                                   RagEmbeddingService ragEmbeddingService,
                                   QdrantClientService qdrantClientService) {
        this.ragEnabled = ragEnabled;
        this.collectionNames = new RagCollectionNames(
            schemaTableCollection,
            schemaColumnCollection,
            sqlHistoryCollection,
            sqlFragmentCollection
        );
        this.schemaTableLimit = Math.max(1, schemaTableLimit);
        this.schemaColumnLimit = Math.max(1, schemaColumnLimit);
        this.sqlHistoryLimit = Math.max(1, sqlHistoryLimit);
        this.ragEmbeddingService = ragEmbeddingService;
        this.qdrantClientService = qdrantClientService;
    }

    @Override
    public RagPromptContext retrievePromptContext(Long connectionId, String databaseName, String userInput) {
        String normalizedDatabaseName = normalizeDatabaseName(databaseName);
        log.info(
            "[RAG-RETRIEVE-REQ] connectionId={}, databaseName={}, inputLength={}, ragEnabled={}, schemaTableLimit={}, schemaColumnLimit={}, sqlHistoryLimit={}",
            connectionId,
            normalizedDatabaseName,
            Objects.toString(userInput, "").trim().length(),
            ragEnabled,
            schemaTableLimit,
            schemaColumnLimit,
            sqlHistoryLimit
        );

        RagPromptContext empty = emptyContext();
        if (!ragEnabled || connectionId == null || isBlank(userInput)) {
            log.info(
                "[RAG-RETRIEVE-RESP] connectionId={}, databaseName={}, hit={}, reason={}, relatedTableCount={}, relatedColumnCount={}, historyCount={}, contextLength={}",
                connectionId,
                normalizedDatabaseName,
                false,
                "SKIPPED_INVALID_INPUT_OR_DISABLED",
                empty.getRelatedTables().size(),
                empty.getRelatedColumns().size(),
                empty.getHistorySqlSamples().size(),
                empty.getPromptContext().length()
            );
            return empty;
        }

        List<Float> inputVector = ragEmbeddingService.embedText(userInput);
        if (inputVector == null || inputVector.isEmpty()) {
            log.info(
                "[RAG-RETRIEVE-RESP] connectionId={}, databaseName={}, hit={}, reason={}, relatedTableCount={}, relatedColumnCount={}, historyCount={}, contextLength={}",
                connectionId,
                normalizedDatabaseName,
                false,
                "EMPTY_EMBEDDING_VECTOR",
                empty.getRelatedTables().size(),
                empty.getRelatedColumns().size(),
                empty.getHistorySqlSamples().size(),
                empty.getPromptContext().length()
            );
            return empty;
        }

        List<QdrantScoredPoint> tableHits = safeSearch(
            collectionNames.getSchemaTable(),
            inputVector,
            schemaTableLimit,
            connectionId,
            normalizedDatabaseName
        );
        List<QdrantScoredPoint> columnHits = safeSearch(
            collectionNames.getSchemaColumn(),
            inputVector,
            schemaColumnLimit,
            connectionId,
            normalizedDatabaseName
        );
        List<QdrantScoredPoint> historyHits = safeSearch(
            collectionNames.getSqlHistory(),
            inputVector,
            sqlHistoryLimit,
            connectionId,
            normalizedDatabaseName
        );
        Set<String> tableConstraints = collectConstraintTables(tableHits);
        if (!tableConstraints.isEmpty()) {
            int columnBefore = columnHits.size();
            int historyBefore = historyHits.size();
            columnHits = filterColumnHitsByTables(columnHits, tableConstraints);
            historyHits = filterHistoryHitsByTables(historyHits, tableConstraints);
            log.info(
                "[RAG-RETRIEVE-TABLE-CONSTRAINT] connectionId={}, databaseName={}, tableConstraintCount={}, columnBefore={}, columnAfter={}, historyBefore={}, historyAfter={}",
                connectionId,
                normalizedDatabaseName,
                tableConstraints.size(),
                columnBefore,
                columnHits.size(),
                historyBefore,
                historyHits.size()
            );
        }

        Set<String> relatedTables = new LinkedHashSet<>();
        Set<String> relatedColumns = new LinkedHashSet<>();
        List<String> historySqlSamples = new ArrayList<>();

        StringBuilder contextBuilder = new StringBuilder();
        contextBuilder.append("【用户输入】\n").append(userInput.trim()).append("\n\n");

        if (!tableHits.isEmpty()) {
            contextBuilder.append("【命中表】\n");
            int idx = 1;
            for (QdrantScoredPoint hit : tableHits) {
                Map<String, Object> payload = hit.getPayload();
                String tableName = payloadString(payload, "table_name");
                String tableComment = payloadString(payload, "table_comment");
                String columns = String.join(", ", payloadStringList(payload, "columns"));
                if (!isBlank(tableName)) {
                    relatedTables.add(tableName);
                }
                contextBuilder.append(idx++)
                    .append(". ")
                    .append(tableName)
                    .append(isBlank(tableComment) ? "" : "（" + tableComment + "）")
                    .append(isBlank(columns) ? "" : " 字段: " + columns)
                    .append("\n");
            }
            contextBuilder.append("\n");
        }

        if (!columnHits.isEmpty()) {
            contextBuilder.append("【命中字段】\n");
            int idx = 1;
            for (QdrantScoredPoint hit : columnHits) {
                Map<String, Object> payload = hit.getPayload();
                String tableName = payloadString(payload, "table_name");
                String columnName = payloadString(payload, "column_name");
                String dataType = payloadString(payload, "data_type");
                String columnComment = payloadString(payload, "column_comment");
                if (!isBlank(tableName)) {
                    relatedTables.add(tableName);
                }
                if (!isBlank(tableName) && !isBlank(columnName)) {
                    relatedColumns.add(tableName + "." + columnName);
                }
                contextBuilder.append(idx++)
                    .append(". ")
                    .append(tableName)
                    .append(".")
                    .append(columnName)
                    .append(isBlank(dataType) ? "" : " ")
                    .append(dataType)
                    .append(isBlank(columnComment) ? "" : "（" + columnComment + "）")
                    .append("\n");
            }
            contextBuilder.append("\n");
        }

        if (!historyHits.isEmpty()) {
            contextBuilder.append("【命中历史SQL】\n");
            int idx = 1;
            for (QdrantScoredPoint hit : historyHits) {
                Map<String, Object> payload = hit.getPayload();
                String sqlText = payloadString(payload, "sql_text");
                if (isBlank(sqlText)) {
                    String tables = String.join(",", payloadStringList(payload, "tables"));
                    String columns = String.join(",", payloadStringList(payload, "columns"));
                    sqlText = "tables=[" + tables + "], columns=[" + columns + "]";
                }
                historySqlSamples.add(sqlText);
                contextBuilder.append(idx++).append(". ").append(sqlText).append("\n");
            }
        }

        RagPromptContext context = new RagPromptContext();
        context.setPromptContext(contextBuilder.toString().trim());
        context.setRelatedTables(new ArrayList<>(relatedTables));
        context.setRelatedColumns(new ArrayList<>(relatedColumns));
        context.setHistorySqlSamples(historySqlSamples);
        context.setHit(!tableHits.isEmpty() || !columnHits.isEmpty() || !historyHits.isEmpty());

        log.info(
            "[RAG-RETRIEVE-RESP] connectionId={}, databaseName={}, hit={}, tableHitCount={}, columnHitCount={}, historyHitCount={}, relatedTableCount={}, relatedColumnCount={}, historyCount={}, contextLength={}",
            connectionId,
            normalizedDatabaseName,
            context.getHit(),
            tableHits.size(),
            columnHits.size(),
            historyHits.size(),
            context.getRelatedTables().size(),
            context.getRelatedColumns().size(),
            context.getHistorySqlSamples().size(),
            context.getPromptContext().length()
        );
        return context;
    }

    private List<QdrantScoredPoint> safeSearch(String collectionName,
                                               List<Float> vector,
                                               int limit,
                                               Long connectionId,
                                               String databaseName) {
        try {
            return qdrantClientService.searchPoints(collectionName, vector, limit, connectionId, databaseName);
        } catch (Exception ex) {
            log.warn(
                "RAG 向量检索失败，自动降级关键词召回, collection={}, connectionId={}, databaseName={}, reason={}",
                collectionName,
                connectionId,
                databaseName,
                ex.getMessage()
            );
            return List.of();
        }
    }

    private Set<String> collectConstraintTables(List<QdrantScoredPoint> tableHits) {
        Set<String> constraints = new LinkedHashSet<>();
        if (tableHits == null || tableHits.isEmpty()) {
            return constraints;
        }
        for (QdrantScoredPoint hit : tableHits) {
            String tableName = normalizeTableName(payloadString(hit.getPayload(), "table_name"));
            if (!tableName.isBlank()) {
                constraints.add(tableName);
            }
        }
        return constraints;
    }

    private List<QdrantScoredPoint> filterColumnHitsByTables(List<QdrantScoredPoint> columnHits, Set<String> constraints) {
        if (columnHits == null || columnHits.isEmpty() || constraints == null || constraints.isEmpty()) {
            return columnHits == null ? List.of() : columnHits;
        }
        return columnHits.stream()
            .filter(hit -> constraints.contains(normalizeTableName(payloadString(hit.getPayload(), "table_name"))))
            .toList();
    }

    private List<QdrantScoredPoint> filterHistoryHitsByTables(List<QdrantScoredPoint> historyHits, Set<String> constraints) {
        if (historyHits == null || historyHits.isEmpty() || constraints == null || constraints.isEmpty()) {
            return historyHits == null ? List.of() : historyHits;
        }
        return historyHits.stream()
            .filter(hit -> {
                String tableName = normalizeTableName(payloadString(hit.getPayload(), "table_name"));
                if (!tableName.isBlank() && constraints.contains(tableName)) {
                    return true;
                }
                List<String> tables = payloadStringList(hit.getPayload(), "tables");
                for (String table : tables) {
                    if (constraints.contains(normalizeTableName(table))) {
                        return true;
                    }
                }
                return false;
            })
            .toList();
    }


    private RagPromptContext emptyContext() {
        RagPromptContext context = new RagPromptContext();
        context.setPromptContext("");
        context.setRelatedTables(List.of());
        context.setRelatedColumns(List.of());
        context.setHistorySqlSamples(List.of());
        context.setHit(Boolean.FALSE);
        return context;
    }

    private List<String> payloadStringList(Map<String, Object> payload, String key) {
        if (payload == null || payload.get(key) == null) {
            return List.of();
        }
        Object value = payload.get(key);
        if (!(value instanceof List<?> rawList)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object item : rawList) {
            String text = Objects.toString(item, "").trim();
            if (!text.isBlank()) {
                values.add(text);
            }
        }
        return values;
    }

    private String payloadString(Map<String, Object> payload, String key) {
        if (payload == null) {
            return "";
        }
        return Objects.toString(payload.get(key), "").trim();
    }

    private String normalizeDatabaseName(String databaseName) {
        String value = Objects.toString(databaseName, "").trim();
        return value.isBlank() ? "" : value;
    }

    private String normalizeTableName(String tableName) {
        String value = Objects.toString(tableName, "")
            .trim()
            .replace("`", "")
            .replace("\"", "")
            .toLowerCase(Locale.ROOT);
        if (value.contains(".")) {
            String[] segments = value.split("\\.");
            value = segments[segments.length - 1];
        }
        return value;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}
