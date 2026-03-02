package com.sqlcopilot.studio.service.rag.impl;

import com.sqlcopilot.studio.service.rag.QdrantClientService;
import com.sqlcopilot.studio.service.rag.RagEmbeddingService;
import com.sqlcopilot.studio.service.rag.RagRetrievalService;
import com.sqlcopilot.studio.service.rag.model.QdrantScoredPoint;
import com.sqlcopilot.studio.service.rag.model.RagCollectionNames;
import com.sqlcopilot.studio.service.rag.model.RagPromptContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class RagRetrievalServiceImpl implements RagRetrievalService {

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
        RagPromptContext empty = emptyContext();
        if (!ragEnabled || connectionId == null || isBlank(userInput)) {
            return empty;
        }

        List<Float> inputVector = ragEmbeddingService.embedText(userInput);
        if (inputVector == null || inputVector.isEmpty()) {
            return empty;
        }

        String normalizedDatabaseName = normalizeDatabaseName(databaseName);
        List<QdrantScoredPoint> tableHits = qdrantClientService.searchPoints(
            collectionNames.getSchemaTable(),
            inputVector,
            schemaTableLimit,
            connectionId,
            normalizedDatabaseName
        );
        List<QdrantScoredPoint> columnHits = qdrantClientService.searchPoints(
            collectionNames.getSchemaColumn(),
            inputVector,
            schemaColumnLimit,
            connectionId,
            normalizedDatabaseName
        );
        List<QdrantScoredPoint> historyHits = qdrantClientService.searchPoints(
            collectionNames.getSqlHistory(),
            inputVector,
            sqlHistoryLimit,
            connectionId,
            normalizedDatabaseName
        );

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
        return context;
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

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
