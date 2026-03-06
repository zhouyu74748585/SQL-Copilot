package com.sqlcopilot.studio.service.rag.impl;

import com.sqlcopilot.studio.service.rag.QdrantClientService;
import com.sqlcopilot.studio.service.rag.RagEmbeddingService;
import com.sqlcopilot.studio.service.rag.RagRerankService;
import com.sqlcopilot.studio.service.rag.RagRetrievalService;
import com.sqlcopilot.studio.service.rag.model.QdrantScoredPoint;
import com.sqlcopilot.studio.service.rag.model.RagCollectionNames;
import com.sqlcopilot.studio.service.rag.model.RagPromptContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class RagRetrievalServiceImpl implements RagRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RagRetrievalServiceImpl.class);

    private final boolean ragEnabled;
    private final RagEmbeddingService ragEmbeddingService;
    private final QdrantClientService qdrantClientService;
    private final RagRerankService ragRerankService;
    private final RagCollectionNames collectionNames;
    private final int schemaTableLimit;
    private final int schemaColumnLimit;
    private final int sqlHistoryLimit;
    private final int metricTermLimit;
    private final int exampleSqlLimit;
    private final boolean rerankEnabled;
    private final double alphaVectorScore;
    private final double betaOnnxScore;
    private final double gammaRuleBonus;

    public RagRetrievalServiceImpl(@Value("${rag.enabled:true}") boolean ragEnabled,
                                   @Value("${rag.collection.schema-table:schema_table}") String schemaTableCollection,
                                   @Value("${rag.collection.schema-column:schema_column}") String schemaColumnCollection,
                                   @Value("${rag.collection.sql-history:sql_history}") String sqlHistoryCollection,
                                   @Value("${rag.collection.metric-term:metric_term}") String metricTermCollection,
                                   @Value("${rag.collection.example-sql:example_sql}") String exampleSqlCollection,
                                   @Value("${rag.collection.sql-fragment:sql_fragment}") String sqlFragmentCollection,
                                   @Value("${rag.retrieval.schema-table-limit:6}") int schemaTableLimit,
                                   @Value("${rag.retrieval.schema-column-limit:8}") int schemaColumnLimit,
                                   @Value("${rag.retrieval.sql-history-limit:6}") int sqlHistoryLimit,
                                   @Value("${rag.retrieval.metric-term-limit:6}") int metricTermLimit,
                                   @Value("${rag.retrieval.example-sql-limit:6}") int exampleSqlLimit,
                                   @Value("${rag.rerank.enabled:false}") boolean rerankEnabled,
                                   @Value("${rag.rerank.alpha:0.65}") double alphaVectorScore,
                                   @Value("${rag.rerank.beta:0.30}") double betaOnnxScore,
                                   @Value("${rag.rerank.gamma:0.05}") double gammaRuleBonus,
                                   RagEmbeddingService ragEmbeddingService,
                                   QdrantClientService qdrantClientService,
                                   RagRerankService ragRerankService) {
        this.ragEnabled = ragEnabled;
        this.collectionNames = new RagCollectionNames(
            schemaTableCollection,
            schemaColumnCollection,
            sqlHistoryCollection,
            metricTermCollection,
            exampleSqlCollection,
            sqlFragmentCollection
        );
        this.schemaTableLimit = Math.max(1, schemaTableLimit);
        this.schemaColumnLimit = Math.max(1, schemaColumnLimit);
        this.sqlHistoryLimit = Math.max(1, sqlHistoryLimit);
        this.metricTermLimit = Math.max(1, metricTermLimit);
        this.exampleSqlLimit = Math.max(1, exampleSqlLimit);
        this.rerankEnabled = rerankEnabled;
        this.alphaVectorScore = alphaVectorScore;
        this.betaOnnxScore = betaOnnxScore;
        this.gammaRuleBonus = gammaRuleBonus;
        this.ragEmbeddingService = ragEmbeddingService;
        this.qdrantClientService = qdrantClientService;
        this.ragRerankService = ragRerankService;
    }

    @Override
    public RagPromptContext retrievePromptContext(Long connectionId, String databaseName, String userInput) {
        String normalizedDatabaseName = normalizeDatabaseName(databaseName);
        log.info(
            "[RAG-RETRIEVE-REQ] connectionId={}, databaseName={}, inputLength={}, ragEnabled={}, schemaTableLimit={}, schemaColumnLimit={}, sqlHistoryLimit={}, metricTermLimit={}, exampleSqlLimit={}, rerankEnabled={}, rerankProvider={}",
            connectionId,
            normalizedDatabaseName,
            Objects.toString(userInput, "").trim().length(),
            ragEnabled,
            schemaTableLimit,
            schemaColumnLimit,
            sqlHistoryLimit,
            metricTermLimit,
            exampleSqlLimit,
            rerankEnabled,
            ragRerankService.getRuntimeProvider()
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
        List<QdrantScoredPoint> metricTermHits = safeSearch(
            collectionNames.getMetricTerm(),
            inputVector,
            metricTermLimit,
            connectionId,
            normalizedDatabaseName
        );
        List<QdrantScoredPoint> exampleSqlHits = safeSearch(
            collectionNames.getExampleSql(),
            inputVector,
            exampleSqlLimit,
            connectionId,
            normalizedDatabaseName
        );
        Set<String> tableConstraints = collectConstraintTables(tableHits);
        if (!tableConstraints.isEmpty()) {
            int columnBefore = columnHits.size();
            int historyBefore = historyHits.size();
            int metricBefore = metricTermHits.size();
            int exampleBefore = exampleSqlHits.size();
            columnHits = filterColumnHitsByTables(columnHits, tableConstraints);
            historyHits = filterHistoryHitsByTables(historyHits, tableConstraints);
            metricTermHits = filterHitsByTables(metricTermHits, tableConstraints);
            exampleSqlHits = filterHitsByTables(exampleSqlHits, tableConstraints);
            log.info(
                "[RAG-RETRIEVE-TABLE-CONSTRAINT] connectionId={}, databaseName={}, tableConstraintCount={}, columnBefore={}, columnAfter={}, historyBefore={}, historyAfter={}, metricBefore={}, metricAfter={}, exampleBefore={}, exampleAfter={}",
                connectionId,
                normalizedDatabaseName,
                tableConstraints.size(),
                columnBefore,
                columnHits.size(),
                historyBefore,
                historyHits.size(),
                metricBefore,
                metricTermHits.size(),
                exampleBefore,
                exampleSqlHits.size()
            );
        }

        tableHits = rerankHits(userInput, "table", tableHits);
        columnHits = rerankHits(userInput, "column", columnHits);
        historyHits = rerankHits(userInput, "query_history", historyHits);
        metricTermHits = rerankHits(userInput, "metric_term", metricTermHits);
        exampleSqlHits = rerankHits(userInput, "example_sql", exampleSqlHits);

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

        if (!metricTermHits.isEmpty()) {
            contextBuilder.append("【命中业务术语】\n");
            int idx = 1;
            for (QdrantScoredPoint hit : metricTermHits) {
                Map<String, Object> payload = hit.getPayload();
                String term = payloadString(payload, "term");
                String definition = payloadString(payload, "definition");
                String expression = payloadString(payload, "metric_expression");
                contextBuilder.append(idx++)
                    .append(". ")
                    .append(term.isBlank() ? "术语" : term)
                    .append(definition.isBlank() ? "" : "：" + definition)
                    .append(expression.isBlank() ? "" : "（口径=" + expression + "）")
                    .append("\n");
            }
            contextBuilder.append("\n");
        }

        if (!exampleSqlHits.isEmpty()) {
            contextBuilder.append("【命中SQL样例】\n");
            int idx = 1;
            for (QdrantScoredPoint hit : exampleSqlHits) {
                Map<String, Object> payload = hit.getPayload();
                String sqlText = payloadString(payload, "sql_text");
                String nlQuestion = payloadString(payload, "nl_question");
                if (!isBlank(sqlText)) {
                    historySqlSamples.add(sqlText);
                }
                contextBuilder.append(idx++)
                    .append(". ")
                    .append(nlQuestion.isBlank() ? "" : ("问法=" + nlQuestion + "；"))
                    .append(sqlText.isBlank() ? payloadString(payload, "sql_semantic") : sqlText)
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
        context.setHit(!tableHits.isEmpty() || !columnHits.isEmpty() || !historyHits.isEmpty()
            || !metricTermHits.isEmpty() || !exampleSqlHits.isEmpty());

        log.info(
            "[RAG-RETRIEVE-RESP] connectionId={}, databaseName={}, hit={}, tableHitCount={}, columnHitCount={}, historyHitCount={}, metricHitCount={}, exampleHitCount={}, relatedTableCount={}, relatedColumnCount={}, historyCount={}, contextLength={}",
            connectionId,
            normalizedDatabaseName,
            context.getHit(),
            tableHits.size(),
            columnHits.size(),
            historyHits.size(),
            metricTermHits.size(),
            exampleSqlHits.size(),
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


    private List<QdrantScoredPoint> filterHitsByTables(List<QdrantScoredPoint> hits, Set<String> constraints) {
        if (hits == null || hits.isEmpty() || constraints == null || constraints.isEmpty()) {
            return hits == null ? List.of() : hits;
        }
        return hits.stream()
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

    private List<QdrantScoredPoint> rerankHits(String userInput, String bucket, List<QdrantScoredPoint> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        if (!rerankEnabled) {
            return hits;
        }

        List<Double> onnxScores = ragRerankService.score(userInput, bucket, hits);
        boolean onnxAvailable = onnxScores.size() == hits.size();
        List<ScoredHit> rescored = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            QdrantScoredPoint hit = hits.get(i);
            double vectorScore = hit.getScore() == null ? 0.0 : hit.getScore();
            double ruleBonus = resolveRuleBonus(userInput, bucket, hit.getPayload());
            double onnxScore = onnxAvailable
                ? clip01(onnxScores.get(i))
                : clip01(vectorScore + ruleBonus * 0.2);
            double finalScore = alphaVectorScore * vectorScore + betaOnnxScore * onnxScore + gammaRuleBonus * ruleBonus;
            rescored.add(new ScoredHit(hit, finalScore));
        }
        rescored.sort(Comparator.comparingDouble(ScoredHit::score).reversed());
        List<QdrantScoredPoint> sorted = new ArrayList<>(rescored.size());
        for (ScoredHit item : rescored) {
            sorted.add(item.hit());
        }
        return sorted;
    }

    private double resolveRuleBonus(String userInput, String bucket, Map<String, Object> payload) {
        if (payload == null) {
            return 0.0;
        }
        double schemaBonus = switch (bucket) {
            case "table" -> isBlank(payloadString(payload, "table_name")) ? 0.0 : 1.0;
            case "column" -> (!isBlank(payloadString(payload, "table_name")) && !isBlank(payloadString(payload, "column_name"))) ? 1.0 : 0.0;
            case "metric_term" -> isBlank(payloadString(payload, "metric_expression")) ? 0.2 : 1.0;
            case "example_sql", "query_history" -> isBlank(payloadString(payload, "sql_text")) ? 0.3 : 1.0;
            default -> 0.0;
        };
        double timeBonus = containsTimeSignal(userInput) ? 0.2 : 0.0;
        return clip01(schemaBonus + timeBonus);
    }

    private double clip01(double score) {
        if (score < 0.0) {
            return 0.0;
        }
        return Math.min(1.0, score);
    }

    private boolean containsTimeSignal(String text) {
        String normalized = Objects.toString(text, "").toLowerCase(Locale.ROOT);
        return normalized.contains("日") || normalized.contains("周") || normalized.contains("月")
            || normalized.contains("季度") || normalized.contains("year") || normalized.contains("month");
    }

    private record ScoredHit(QdrantScoredPoint hit, double score) {
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
