package com.sqlcopilot.studio.service.rag.impl;

import com.sqlcopilot.studio.dto.rag.RagConfigVO;
import com.sqlcopilot.studio.dto.schema.SchemaOverviewVO;
import com.sqlcopilot.studio.dto.schema.TableDetailVO;
import com.sqlcopilot.studio.service.RagConfigService;
import com.sqlcopilot.studio.service.SchemaService;
import com.sqlcopilot.studio.service.rag.QdrantClientService;
import com.sqlcopilot.studio.service.rag.RagEmbeddingService;
import com.sqlcopilot.studio.service.rag.RagRerankService;
import com.sqlcopilot.studio.service.rag.RagRetrievalService;
import com.sqlcopilot.studio.service.rag.model.QdrantPayloadFilter;
import com.sqlcopilot.studio.service.rag.model.QdrantScoredPoint;
import com.sqlcopilot.studio.service.rag.model.RagCollectionNames;
import com.sqlcopilot.studio.service.rag.model.RagPromptContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RagRetrievalServiceImpl implements RagRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RagRetrievalServiceImpl.class);
    private static final long RAG_CONFIG_CACHE_TTL_MS = 10_000L;
    private static final long GLOBAL_SCOPE_CONNECTION_ID = 0L;
    private static final int SUPPLEMENT_TABLE_COLUMNS_PREVIEW_LIMIT = 30;
    private static final int SUPPLEMENT_COLUMN_PER_TABLE_LIMIT = 12;
    private static final double SUPPLEMENT_SCORE_STEP = 0.001D;
    private static final Pattern TABLE_PATTERN = Pattern.compile("(?i)\\b(?:from|join|update|into|table)\\s+([a-zA-Z0-9_$.`\"]+)");

    private final boolean ragEnabled;
    private final boolean defaultRerankEnabled;
    private final RagConfigService ragConfigService;
    private final SchemaService schemaService;
    private final RagEmbeddingService ragEmbeddingService;
    private final QdrantClientService qdrantClientService;
    private final RagRerankService ragRerankService;
    private final RagCollectionNames collectionNames;
    private final int schemaTableLimit;
    private final int schemaColumnLimit;
    private final int sqlHistoryLimit;
    private final int metricTermLimit;
    private final int exampleSqlLimit;
    private final double alphaVectorScore;
    private final double betaOnnxScore;
    private final double gammaRuleBonus;
    private final Object configCacheLock = new Object();
    private RagConfigVO cachedRagConfig;
    private long cachedRagConfigLoadedAt;

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
                                   @Value("${rag.rerank.enabled:false}") boolean defaultRerankEnabled,
                                   @Value("${rag.rerank.alpha:0.65}") double alphaVectorScore,
                                   @Value("${rag.rerank.beta:0.30}") double betaOnnxScore,
                                   @Value("${rag.rerank.gamma:0.05}") double gammaRuleBonus,
                                   RagConfigService ragConfigService,
                                   SchemaService schemaService,
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
        this.defaultRerankEnabled = defaultRerankEnabled;
        this.alphaVectorScore = alphaVectorScore;
        this.betaOnnxScore = betaOnnxScore;
        this.gammaRuleBonus = gammaRuleBonus;
        this.ragConfigService = ragConfigService;
        this.schemaService = schemaService;
        this.ragEmbeddingService = ragEmbeddingService;
        this.qdrantClientService = qdrantClientService;
        this.ragRerankService = ragRerankService;
    }

    @Override
    public RagPromptContext retrievePromptContext(Long connectionId, String databaseName, String userInput) {
        boolean rerankEnabled = isRerankEnabled();
        String rerankProvider = ragRerankService.getRuntimeProvider();
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
            rerankProvider
        );

        RagPromptContext empty = emptyContext(rerankEnabled, rerankProvider);
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
        List<QdrantScoredPoint> metricTermHits = searchKnowledgeAcrossScopes(
            collectionNames.getMetricTerm(),
            inputVector,
            metricTermLimit,
            connectionId,
            normalizedDatabaseName
        );
        List<QdrantScoredPoint> exampleSqlHits = searchKnowledgeAcrossScopes(
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
            columnHits = filterColumnHitsByTables(columnHits, tableConstraints);
            historyHits = filterHistoryHitsByTables(historyHits, tableConstraints);
            metricTermHits = filterHitsByTables(metricTermHits, tableConstraints);
            log.info(
                "[RAG-RETRIEVE-TABLE-CONSTRAINT] connectionId={}, databaseName={}, tableConstraintCount={}, columnBefore={}, columnAfter={}, historyBefore={}, historyAfter={}, metricBefore={}, metricAfter={}, exampleCount={}",
                connectionId,
                normalizedDatabaseName,
                tableConstraints.size(),
                columnBefore,
                columnHits.size(),
                historyBefore,
                historyHits.size(),
                metricBefore,
                metricTermHits.size(),
                exampleSqlHits.size()
            );
        }

        List<Map<String, Object>> rerankDetails = new ArrayList<>();
        RerankResult tableRerank = rerankHits(userInput, "table", tableHits, rerankEnabled, rerankProvider);
        tableHits = tableRerank.hits();
        rerankDetails.add(tableRerank.traceDetail());
        RerankResult columnRerank = rerankHits(userInput, "column", columnHits, rerankEnabled, rerankProvider);
        columnHits = columnRerank.hits();
        rerankDetails.add(columnRerank.traceDetail());
        RerankResult historyRerank = rerankHits(userInput, "query_history", historyHits, rerankEnabled, rerankProvider);
        historyHits = historyRerank.hits();
        rerankDetails.add(historyRerank.traceDetail());
        RerankResult metricRerank = rerankHits(userInput, "metric_term", metricTermHits, rerankEnabled, rerankProvider);
        metricTermHits = metricRerank.hits();
        rerankDetails.add(metricRerank.traceDetail());
        RerankResult exampleRerank = rerankHits(userInput, "example_sql", exampleSqlHits, rerankEnabled, rerankProvider);
        exampleSqlHits = exampleRerank.hits();
        rerankDetails.add(exampleRerank.traceDetail());
        ExampleSqlSupplementResult supplementResult = supplementSchemaHitsByExampleSql(
            connectionId,
            normalizedDatabaseName,
            exampleSqlHits,
            tableHits,
            columnHits
        );
        tableHits = supplementResult.tableHits();
        columnHits = supplementResult.columnHits();
        if (supplementResult.supplementedTableCount() > 0 || supplementResult.supplementedColumnCount() > 0) {
            log.info(
                "[RAG-RETRIEVE-EXAMPLE-SUPPLEMENT] connectionId={}, databaseName={}, supplementedTableCount={}, supplementedColumnCount={}, tableHitCount={}, columnHitCount={}",
                connectionId,
                normalizedDatabaseName,
                supplementResult.supplementedTableCount(),
                supplementResult.supplementedColumnCount(),
                tableHits.size(),
                columnHits.size()
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
        context.setRerankEnabled(rerankEnabled);
        context.setRerankProvider(rerankProvider);
        context.setRerankDetails(rerankDetails);

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

    private List<QdrantScoredPoint> searchKnowledgeAcrossScopes(String collectionName,
                                                                List<Float> vector,
                                                                int limit,
                                                                Long connectionId,
                                                                String databaseName) {
        Map<String, QdrantScoredPoint> merged = new LinkedHashMap<>();
        if (connectionId != null) {
            mergeHits(merged, safeSearchByFilters(collectionName, vector, limit, List.of(
                new QdrantPayloadFilter("connection_id", connectionId),
                new QdrantPayloadFilter("database_name", normalizeDatabaseName(databaseName))
            )));
            mergeHits(merged, safeSearchByFilters(collectionName, vector, limit, List.of(
                new QdrantPayloadFilter("connection_id", connectionId),
                new QdrantPayloadFilter("database_name", "")
            )));
        }
        mergeHits(merged, safeSearchByFilters(collectionName, vector, limit, List.of(
            new QdrantPayloadFilter("connection_id", GLOBAL_SCOPE_CONNECTION_ID),
            new QdrantPayloadFilter("database_name", "")
        )));
        return merged.values().stream()
            .sorted(Comparator.comparingDouble(QdrantScoredPoint::getScore).reversed())
            .limit(Math.max(limit * 2L, limit))
            .toList();
    }

    private void mergeHits(Map<String, QdrantScoredPoint> target, List<QdrantScoredPoint> hits) {
        if (hits == null || hits.isEmpty()) {
            return;
        }
        for (QdrantScoredPoint hit : hits) {
            if (hit == null) {
                continue;
            }
            QdrantScoredPoint current = target.get(hit.getId());
            if (current == null || hit.getScore() > current.getScore()) {
                target.put(hit.getId(), hit);
            }
        }
    }

    private List<QdrantScoredPoint> safeSearchByFilters(String collectionName,
                                                        List<Float> vector,
                                                        int limit,
                                                        List<QdrantPayloadFilter> filters) {
        try {
            return qdrantClientService.searchPointsByFilters(collectionName, vector, limit, filters);
        } catch (Exception ex) {
            log.warn(
                "RAG 知识向量检索失败，自动降级, collection={}, filters={}, reason={}",
                collectionName,
                filters,
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
                List<String> tables = payloadStringList(hit.getPayload(), "tables");
                if (tableName.isBlank() && tables.isEmpty()) {
                    return true;
                }
                if (!tableName.isBlank() && constraints.contains(tableName)) {
                    return true;
                }
                for (String table : tables) {
                    if (constraints.contains(normalizeTableName(table))) {
                        return true;
                    }
                }
                return false;
            })
            .toList();
    }

    private ExampleSqlSupplementResult supplementSchemaHitsByExampleSql(Long connectionId,
                                                                        String databaseName,
                                                                        List<QdrantScoredPoint> exampleSqlHits,
                                                                        List<QdrantScoredPoint> tableHits,
                                                                        List<QdrantScoredPoint> columnHits) {
        if (connectionId == null || exampleSqlHits == null || exampleSqlHits.isEmpty()) {
            return new ExampleSqlSupplementResult(
                tableHits == null ? List.of() : tableHits,
                columnHits == null ? List.of() : columnHits,
                0,
                0
            );
        }

        LinkedHashSet<String> exampleTables = collectExampleSqlRelatedTables(exampleSqlHits);
        if (exampleTables.isEmpty()) {
            return new ExampleSqlSupplementResult(
                tableHits == null ? List.of() : tableHits,
                columnHits == null ? List.of() : columnHits,
                0,
                0
            );
        }

        Set<String> existingTableNames = collectConstraintTables(tableHits);
        List<String> missingTables = exampleTables.stream()
            .filter(table -> !existingTableNames.contains(table))
            .toList();
        if (missingTables.isEmpty()) {
            return new ExampleSqlSupplementResult(
                tableHits == null ? List.of() : tableHits,
                columnHits == null ? List.of() : columnHits,
                0,
                0
            );
        }

        Map<String, SchemaOverviewVO.TableSummaryVO> tableSummaryMap = loadTableSummaryMap(connectionId, databaseName);
        if (tableSummaryMap.isEmpty()) {
            return new ExampleSqlSupplementResult(
                tableHits == null ? List.of() : tableHits,
                columnHits == null ? List.of() : columnHits,
                0,
                0
            );
        }

        List<QdrantScoredPoint> mergedTableHits = new ArrayList<>(tableHits == null ? List.of() : tableHits);
        List<QdrantScoredPoint> mergedColumnHits = new ArrayList<>(columnHits == null ? List.of() : columnHits);
        Set<String> existingColumnKeys = collectColumnKeys(mergedColumnHits);
        double tableBoostScore = resolveBoostScore(mergedTableHits);
        double columnBoostScore = resolveBoostScore(mergedColumnHits);
        int supplementedTableCount = 0;
        int supplementedColumnCount = 0;
        int tableOrder = 0;

        for (String missingTable : missingTables) {
            SchemaOverviewVO.TableSummaryVO summary = tableSummaryMap.get(missingTable);
            if (summary == null) {
                continue;
            }
            String canonicalTableName = trimText(summary.getTableName());
            if (canonicalTableName.isBlank()) {
                continue;
            }
            TableDetailVO tableDetail;
            try {
                tableDetail = schemaService.getTableDetail(connectionId, databaseName, canonicalTableName);
            } catch (Exception ex) {
                log.debug(
                    "样例 SQL 关联表补全失败，忽略当前表, connectionId={}, databaseName={}, tableName={}, reason={}",
                    connectionId,
                    databaseName,
                    canonicalTableName,
                    ex.getMessage()
                );
                continue;
            }
            List<TableDetailVO.ColumnDetailVO> columns = tableDetail == null || tableDetail.getColumns() == null
                ? List.of()
                : tableDetail.getColumns();
            Map<String, Object> tablePayload = buildSupplementTablePayload(canonicalTableName, summary, tableDetail, columns);
            double tableScore = tableBoostScore - tableOrder * SUPPLEMENT_SCORE_STEP;
            tableOrder++;
            mergedTableHits.add(new QdrantScoredPoint(
                "example_sql_supplement_table:" + normalizeTableName(canonicalTableName),
                tableScore,
                tablePayload
            ));
            supplementedTableCount++;

            int perTableColumnCount = 0;
            for (TableDetailVO.ColumnDetailVO column : columns) {
                if (perTableColumnCount >= SUPPLEMENT_COLUMN_PER_TABLE_LIMIT) {
                    break;
                }
                String columnName = column == null ? "" : trimText(column.getColumnName());
                if (columnName.isBlank()) {
                    continue;
                }
                String columnKey = normalizeTableName(canonicalTableName) + "." + normalizeColumnName(columnName);
                if (existingColumnKeys.contains(columnKey)) {
                    continue;
                }
                double columnScore = columnBoostScore - supplementedColumnCount * SUPPLEMENT_SCORE_STEP;
                mergedColumnHits.add(new QdrantScoredPoint(
                    "example_sql_supplement_column:" + columnKey,
                    columnScore,
                    buildSupplementColumnPayload(canonicalTableName, column)
                ));
                existingColumnKeys.add(columnKey);
                supplementedColumnCount++;
                perTableColumnCount++;
            }
        }

        mergedTableHits.sort(Comparator.comparingDouble(this::safeScore).reversed());
        mergedColumnHits.sort(Comparator.comparingDouble(this::safeScore).reversed());
        return new ExampleSqlSupplementResult(mergedTableHits, mergedColumnHits, supplementedTableCount, supplementedColumnCount);
    }

    private LinkedHashSet<String> collectExampleSqlRelatedTables(List<QdrantScoredPoint> exampleSqlHits) {
        LinkedHashSet<String> relatedTables = new LinkedHashSet<>();
        for (QdrantScoredPoint hit : exampleSqlHits) {
            if (hit == null) {
                continue;
            }
            Map<String, Object> payload = hit.getPayload();
            List<String> payloadTables = payloadStringList(payload, "tables");
            for (String table : payloadTables) {
                String normalized = normalizeTableName(table);
                if (!normalized.isBlank()) {
                    relatedTables.add(normalized);
                }
            }
            if (!payloadTables.isEmpty()) {
                continue;
            }
            String sqlText = payloadString(payload, "sql_text");
            Matcher tableMatcher = TABLE_PATTERN.matcher(sqlText);
            while (tableMatcher.find()) {
                String normalized = normalizeTableName(tableMatcher.group(1));
                if (!normalized.isBlank()) {
                    relatedTables.add(normalized);
                }
            }
        }
        return relatedTables;
    }

    private Map<String, SchemaOverviewVO.TableSummaryVO> loadTableSummaryMap(Long connectionId, String databaseName) {
        try {
            SchemaOverviewVO overview = schemaService.getOverview(connectionId, databaseName);
            if (overview == null || overview.getTableSummaries() == null || overview.getTableSummaries().isEmpty()) {
                return Map.of();
            }
            Map<String, SchemaOverviewVO.TableSummaryVO> mapping = new LinkedHashMap<>();
            for (SchemaOverviewVO.TableSummaryVO summary : overview.getTableSummaries()) {
                if (summary == null) {
                    continue;
                }
                String normalized = normalizeTableName(summary.getTableName());
                if (normalized.isBlank() || mapping.containsKey(normalized)) {
                    continue;
                }
                mapping.put(normalized, summary);
            }
            return mapping;
        } catch (Exception ex) {
            log.debug(
                "加载 Schema 表概览失败，跳过样例 SQL 表补全, connectionId={}, databaseName={}, reason={}",
                connectionId,
                databaseName,
                ex.getMessage()
            );
            return Map.of();
        }
    }

    private Set<String> collectColumnKeys(List<QdrantScoredPoint> columnHits) {
        Set<String> keys = new HashSet<>();
        if (columnHits == null || columnHits.isEmpty()) {
            return keys;
        }
        for (QdrantScoredPoint hit : columnHits) {
            if (hit == null) {
                continue;
            }
            Map<String, Object> payload = hit.getPayload();
            String tableName = normalizeTableName(payloadString(payload, "table_name"));
            String columnName = normalizeColumnName(payloadString(payload, "column_name"));
            if (tableName.isBlank() || columnName.isBlank()) {
                continue;
            }
            keys.add(tableName + "." + columnName);
        }
        return keys;
    }

    private Map<String, Object> buildSupplementTablePayload(String tableName,
                                                            SchemaOverviewVO.TableSummaryVO summary,
                                                            TableDetailVO tableDetail,
                                                            List<TableDetailVO.ColumnDetailVO> columns) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("table_name", tableName);
        String tableComment = tableDetail == null ? "" : trimText(tableDetail.getTableComment());
        if (tableComment.isBlank()) {
            tableComment = summary == null ? "" : trimText(summary.getTableComment());
        }
        payload.put("table_comment", tableComment);
        List<String> columnNames = columns.stream()
            .filter(Objects::nonNull)
            .map(TableDetailVO.ColumnDetailVO::getColumnName)
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(name -> !name.isBlank())
            .limit(SUPPLEMENT_TABLE_COLUMNS_PREVIEW_LIMIT)
            .toList();
        payload.put("columns", columnNames);
        return payload;
    }

    private Map<String, Object> buildSupplementColumnPayload(String tableName, TableDetailVO.ColumnDetailVO column) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("table_name", tableName);
        payload.put("column_name", column == null ? "" : Objects.toString(column.getColumnName(), "").trim());
        payload.put("data_type", column == null ? "" : Objects.toString(column.getDataType(), "").trim());
        payload.put("column_comment", column == null ? "" : Objects.toString(column.getColumnComment(), "").trim());
        payload.put("indexed", column != null && Boolean.TRUE.equals(column.getIndexed()));
        payload.put("primary_key", column != null && Boolean.TRUE.equals(column.getPrimaryKey()));
        payload.put("nullable", column == null || Boolean.TRUE.equals(column.getNullable()));
        return payload;
    }

    private double resolveBoostScore(List<QdrantScoredPoint> hits) {
        double maxScore = 0D;
        if (hits != null) {
            for (QdrantScoredPoint hit : hits) {
                maxScore = Math.max(maxScore, safeScore(hit));
            }
        }
        return Math.max(1.2D, maxScore + 0.05D);
    }

    private double safeScore(QdrantScoredPoint hit) {
        if (hit == null || hit.getScore() == null) {
            return 0D;
        }
        return hit.getScore();
    }

    private RerankResult rerankHits(String userInput,
                                    String bucket,
                                    List<QdrantScoredPoint> hits,
                                    boolean rerankEnabled,
                                    String rerankProvider) {
        if (hits == null || hits.isEmpty()) {
            return new RerankResult(
                List.of(),
                buildRerankTraceDetail(bucket, rerankEnabled, false, false, rerankProvider, 0, 0, List.of())
            );
        }
        if (!rerankEnabled) {
            return new RerankResult(
                hits,
                buildRerankTraceDetail(bucket, false, false, false, rerankProvider, hits.size(), 0, summarizeVectorTopHits(bucket, hits))
            );
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
            rescored.add(new ScoredHit(hit, finalScore, vectorScore, onnxScore, ruleBonus));
        }
        rescored.sort(Comparator.comparingDouble(ScoredHit::score).reversed());
        List<QdrantScoredPoint> sorted = new ArrayList<>(rescored.size());
        for (ScoredHit item : rescored) {
            sorted.add(item.hit());
        }
        return new RerankResult(
            sorted,
            buildRerankTraceDetail(
                bucket,
                true,
                true,
                onnxAvailable,
                rerankProvider,
                hits.size(),
                countRankingChanges(hits, sorted),
                summarizeRerankedTopHits(bucket, rescored)
            )
        );
    }

    private Map<String, Object> buildRerankTraceDetail(String bucket,
                                                       boolean enabled,
                                                       boolean applied,
                                                       boolean onnxAvailable,
                                                       String rerankProvider,
                                                       int candidateCount,
                                                       int rankingChangedCount,
                                                       List<String> topResults) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("bucket", bucket);
        detail.put("enabled", enabled);
        detail.put("applied", applied);
        detail.put("onnxAvailable", onnxAvailable);
        detail.put("provider", Objects.toString(rerankProvider, "").trim());
        detail.put("candidateCount", Math.max(0, candidateCount));
        detail.put("rankingChangedCount", Math.max(0, rankingChangedCount));
        detail.put("topResults", topResults == null ? List.of() : topResults);
        return detail;
    }

    private int countRankingChanges(List<QdrantScoredPoint> original, List<QdrantScoredPoint> sorted) {
        int max = Math.min(original == null ? 0 : original.size(), sorted == null ? 0 : sorted.size());
        int changed = 0;
        for (int i = 0; i < max; i++) {
            String originalId = original.get(i) == null ? "" : Objects.toString(original.get(i).getId(), "");
            String sortedId = sorted.get(i) == null ? "" : Objects.toString(sorted.get(i).getId(), "");
            if (!Objects.equals(originalId, sortedId)) {
                changed++;
            }
        }
        return changed;
    }

    private List<String> summarizeVectorTopHits(String bucket, List<QdrantScoredPoint> hits) {
        return hits.stream()
            .limit(3)
            .map(hit -> describeHit(bucket, hit) + " | vector=" + String.format(Locale.ROOT, "%.3f", clip01(hit.getScore() == null ? 0.0 : hit.getScore())))
            .toList();
    }

    private List<String> summarizeRerankedTopHits(String bucket, List<ScoredHit> rescored) {
        return rescored.stream()
            .limit(3)
            .map(item -> describeHit(bucket, item.hit())
                + " | vector=" + String.format(Locale.ROOT, "%.3f", item.vectorScore())
                + " | onnx=" + String.format(Locale.ROOT, "%.3f", item.onnxScore())
                + " | rule=" + String.format(Locale.ROOT, "%.3f", item.ruleBonus())
                + " | final=" + String.format(Locale.ROOT, "%.3f", item.score()))
            .toList();
    }

    private String describeHit(String bucket, QdrantScoredPoint hit) {
        if (hit == null) {
            return "";
        }
        Map<String, Object> payload = hit.getPayload();
        return switch (bucket) {
            case "table" -> payloadString(payload, "table_name");
            case "column" -> {
                String tableName = payloadString(payload, "table_name");
                String columnName = payloadString(payload, "column_name");
                yield isBlank(tableName) || isBlank(columnName) ? Objects.toString(hit.getId(), "") : tableName + "." + columnName;
            }
            case "metric_term" -> payloadString(payload, "term");
            case "example_sql", "query_history" -> shorten(payloadString(payload, "sql_text"), 96);
            default -> Objects.toString(hit.getId(), "");
        };
    }

    private String shorten(String text, int maxLength) {
        String normalized = Objects.toString(text, "").trim();
        if (normalized.length() <= Math.max(0, maxLength)) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private boolean isRerankEnabled() {
        RagConfigVO config = getCachedRagConfig();
        if (config.getRagRerankEnabled() != null) {
            return config.getRagRerankEnabled();
        }
        return defaultRerankEnabled;
    }

    private RagConfigVO getCachedRagConfig() {
        long now = System.currentTimeMillis();
        RagConfigVO localCache = cachedRagConfig;
        if (localCache != null && now - cachedRagConfigLoadedAt < RAG_CONFIG_CACHE_TTL_MS) {
            return localCache;
        }
        synchronized (configCacheLock) {
            long refreshedNow = System.currentTimeMillis();
            if (cachedRagConfig != null && refreshedNow - cachedRagConfigLoadedAt < RAG_CONFIG_CACHE_TTL_MS) {
                return cachedRagConfig;
            }
            // 关键优化：检索链路中短时缓存配置，降低频繁读取配置表的开销。
            cachedRagConfig = ragConfigService.getConfig();
            cachedRagConfigLoadedAt = refreshedNow;
            return cachedRagConfig;
        }
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

    private record ScoredHit(QdrantScoredPoint hit,
                             double score,
                             double vectorScore,
                             double onnxScore,
                             double ruleBonus) {
    }

    private record RerankResult(List<QdrantScoredPoint> hits, Map<String, Object> traceDetail) {
    }

    private record ExampleSqlSupplementResult(List<QdrantScoredPoint> tableHits,
                                              List<QdrantScoredPoint> columnHits,
                                              int supplementedTableCount,
                                              int supplementedColumnCount) {
    }

    private RagPromptContext emptyContext(boolean rerankEnabled, String rerankProvider) {
        RagPromptContext context = new RagPromptContext();
        context.setPromptContext("");
        context.setRelatedTables(List.of());
        context.setRelatedColumns(List.of());
        context.setHistorySqlSamples(List.of());
        context.setHit(Boolean.FALSE);
        context.setRerankEnabled(rerankEnabled);
        context.setRerankProvider(Objects.toString(rerankProvider, "").trim());
        context.setRerankDetails(List.of());
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

    private String trimText(String value) {
        return Objects.toString(value, "").trim();
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

    private String normalizeColumnName(String columnName) {
        return Objects.toString(columnName, "")
            .trim()
            .replace("`", "")
            .replace("\"", "")
            .toLowerCase(Locale.ROOT);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}
