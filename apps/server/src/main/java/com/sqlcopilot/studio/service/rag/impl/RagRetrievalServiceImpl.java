package com.sqlcopilot.studio.service.rag.impl;

import com.sqlcopilot.studio.dto.rag.RagConfigVO;
import com.sqlcopilot.studio.dto.schema.SchemaOverviewVO;
import com.sqlcopilot.studio.dto.schema.TableDetailVO;
import com.sqlcopilot.studio.service.MemoryService;
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
    private static final int PROMPT_TABLE_LIMIT = 4;
    private static final int PROMPT_MEMORY_LIMIT = 3;
    private static final int PROMPT_TERM_LIMIT = 3;
    private static final int PROMPT_EXAMPLE_LIMIT = 2;
    private static final int PROMPT_HISTORY_LIMIT = 1;
    private static final int PROMPT_COLUMNS_PER_TABLE_LIMIT = 6;
    private static final int PROMPT_CONTEXT_TOKEN_BUDGET = 900;
    private static final Pattern TABLE_PATTERN = Pattern.compile("(?i)\\b(?:from|join|update|into|table)\\s+([a-zA-Z0-9_$.`\"]+)");
    private static final Pattern RETRIEVAL_QUERY_PATTERN = Pattern.compile("(?m)^检索关键词:\\s*(.+)$");
    private static final Pattern FOCUS_TABLES_PATTERN = Pattern.compile("(?m)^重点表:\\s*(.+)$");
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("\\b[a-zA-Z][a-zA-Z0-9_]{2,}\\b");

    private final boolean ragEnabled;
    private final boolean defaultRerankEnabled;
    private final RagConfigService ragConfigService;
    private final SchemaService schemaService;
    private final MemoryService memoryService;
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
                                   @Value("${rag.collection.managed-memory:managed_memory}") String managedMemoryCollection,
                                   @Value("${rag.collection.metric-term:metric_term}") String metricTermCollection,
                                   @Value("${rag.collection.example-sql:example_sql}") String exampleSqlCollection,
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
                                   MemoryService memoryService,
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
            managedMemoryCollection
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
        this.memoryService = memoryService;
        this.ragEmbeddingService = ragEmbeddingService;
        this.qdrantClientService = qdrantClientService;
        this.ragRerankService = ragRerankService;
    }

    @Override
    public RagPromptContext retrievePromptContext(Long connectionId, String databaseName, String userInput) {
        boolean rerankEnabled = isRerankEnabled();
        String initialRerankProvider = currentRerankProvider(rerankEnabled, "DISABLED");
        String normalizedDatabaseName = normalizeDatabaseName(databaseName);
        RetrievalQuery retrievalQuery = parseRetrievalQuery(userInput);
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
            initialRerankProvider
        );

        RagPromptContext empty = emptyContext(rerankEnabled, initialRerankProvider);
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

        List<Float> inputVector = ragEmbeddingService.embedText(retrievalQuery.embeddingText());
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
        List<QdrantScoredPoint> historyHits = List.of();
        List<QdrantScoredPoint> managedMemoryHits = searchManagedMemoryAcrossScopes(
            collectionNames.getManagedMemory(),
            inputVector,
            PROMPT_MEMORY_LIMIT * 2,
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
        ExampleSqlSupplementResult focusTableSupplement = supplementSchemaHitsByFocusTables(
            connectionId,
            normalizedDatabaseName,
            retrievalQuery.focusTables(),
            tableHits,
            columnHits
        );
        tableHits = focusTableSupplement.tableHits();
        columnHits = focusTableSupplement.columnHits();
        if (focusTableSupplement.supplementedTableCount() > 0 || focusTableSupplement.supplementedColumnCount() > 0) {
            log.info(
                "[RAG-RETRIEVE-FOCUS-SUPPLEMENT] connectionId={}, databaseName={}, focusTableCount={}, supplementedTableCount={}, supplementedColumnCount={}, tableHitCount={}, columnHitCount={}, historyHitCount={}",
                connectionId,
                normalizedDatabaseName,
                retrievalQuery.focusTables().size(),
                focusTableSupplement.supplementedTableCount(),
                focusTableSupplement.supplementedColumnCount(),
                tableHits.size(),
                columnHits.size(),
                historyHits.size()
            );
        }

        List<Map<String, Object>> rerankDetails = new ArrayList<>();
        RerankResult tableRerank = rerankHits(retrievalQuery, "table", tableHits, rerankEnabled);
        tableHits = tableRerank.hits();
        rerankDetails.add(tableRerank.traceDetail());
        RerankResult columnRerank = rerankHits(retrievalQuery, "column", columnHits, rerankEnabled);
        columnHits = columnRerank.hits();
        rerankDetails.add(columnRerank.traceDetail());
        RerankResult historyRerank = rerankHits(retrievalQuery, "query_history", historyHits, rerankEnabled);
        historyHits = historyRerank.hits();
        rerankDetails.add(historyRerank.traceDetail());
        RerankResult memoryRerank = rerankHits(retrievalQuery, "managed_memory", managedMemoryHits, rerankEnabled);
        managedMemoryHits = memoryRerank.hits();
        rerankDetails.add(memoryRerank.traceDetail());
        RerankResult metricRerank = rerankHits(retrievalQuery, "metric_term", metricTermHits, rerankEnabled);
        metricTermHits = metricRerank.hits();
        rerankDetails.add(metricRerank.traceDetail());
        RerankResult exampleRerank = rerankHits(retrievalQuery, "example_sql", exampleSqlHits, rerankEnabled);
        exampleSqlHits = exampleRerank.hits();
        rerankDetails.add(exampleRerank.traceDetail());
        String rerankProvider = currentRerankProvider(rerankEnabled, initialRerankProvider);
        List<Map<String, Object>> selectionDetails = new ArrayList<>();
        LinkedHashSet<String> anchorTables = buildAnchorTables(retrievalQuery, tableHits, metricTermHits);
        if (!anchorTables.isEmpty()) {
            int columnBefore = columnHits.size();
            int historyBefore = historyHits.size();
            int memoryBefore = managedMemoryHits.size();
            int metricBefore = metricTermHits.size();
            columnHits = filterColumnHitsByTables(columnHits, anchorTables);
            historyHits = filterHistoryHitsByTables(historyHits, anchorTables);
            managedMemoryHits = filterMemoryHitsByTables(managedMemoryHits, anchorTables);
            metricTermHits = filterHitsByTables(metricTermHits, anchorTables);
            selectionDetails.add(buildSelectionDetail(
                "anchor_filter",
                "accepted",
                "按表锚点约束字段/历史/长期记忆/术语",
                Map.of(
                    "anchorTables", new ArrayList<>(anchorTables),
                    "columnBefore", columnBefore,
                    "columnAfter", columnHits.size(),
                    "historyBefore", historyBefore,
                    "historyAfter", historyHits.size(),
                    "memoryBefore", memoryBefore,
                    "memoryAfter", managedMemoryHits.size(),
                    "metricBefore", metricBefore,
                    "metricAfter", metricTermHits.size()
                )
            ));
        }

        ExampleGateResult exampleGateResult = gateExampleSqlHits(exampleSqlHits, retrievalQuery, anchorTables, metricTermHits);
        exampleSqlHits = exampleGateResult.acceptedHits();
        selectionDetails.addAll(exampleGateResult.traceDetails());

        List<QdrantScoredPoint> selectedTableHits = selectAnchorTableHits(tableHits, anchorTables);
        List<QdrantScoredPoint> selectedMemoryHits = managedMemoryHits.stream().limit(PROMPT_MEMORY_LIMIT).toList();
        List<QdrantScoredPoint> selectedTermHits = metricTermHits.stream().limit(PROMPT_TERM_LIMIT).toList();
        List<QdrantScoredPoint> selectedColumnHits = selectPromptColumnHits(columnHits, selectedTableHits);
        List<QdrantScoredPoint> selectedExampleHits = exampleSqlHits.stream().limit(PROMPT_EXAMPLE_LIMIT).toList();
        List<QdrantScoredPoint> selectedHistoryHits = historyHits.stream().limit(PROMPT_HISTORY_LIMIT).toList();
        PromptBuildResult promptBuildResult = buildPromptContext(
            retrievalQuery,
            selectedTableHits,
            selectedColumnHits,
            selectedMemoryHits,
            selectedTermHits,
            selectedExampleHits,
            selectedHistoryHits,
            exampleGateResult.acceptedById()
        );
        selectionDetails.add(buildSelectionDetail(
            "prompt",
            "assembled",
            "完成预算化上下文组装",
            Map.of(
                "tableCount", selectedTableHits.size(),
                "columnCount", selectedColumnHits.size(),
                "memoryCount", selectedMemoryHits.size(),
                "termCount", selectedTermHits.size(),
                "exampleCount", selectedExampleHits.size(),
                "historyCount", selectedHistoryHits.size(),
                "promptBudgetUsed", promptBuildResult.promptBudgetUsed()
            )
        ));

        RagPromptContext context = new RagPromptContext();
        context.setPromptContext(promptBuildResult.promptContext());
        context.setRelatedTables(promptBuildResult.relatedTables());
        context.setRelatedColumns(promptBuildResult.relatedColumns());
        context.setHistorySqlSamples(promptBuildResult.historySqlSamples());
        context.setHit(!tableHits.isEmpty() || !columnHits.isEmpty() || !historyHits.isEmpty()
            || !managedMemoryHits.isEmpty() || !metricTermHits.isEmpty() || !exampleSqlHits.isEmpty());
        context.setRerankEnabled(rerankEnabled);
        context.setRerankProvider(rerankProvider);
        context.setRerankDetails(rerankDetails);
        context.setSelectionDetails(selectionDetails);
        context.setPromptBudgetUsed(promptBuildResult.promptBudgetUsed());
        markRetrievedManagedMemories(selectedMemoryHits);

        log.info(
            "[RAG-RETRIEVE-RESP] connectionId={}, databaseName={}, hit={}, tableHitCount={}, columnHitCount={}, historyHitCount={}, memoryHitCount={}, metricHitCount={}, exampleHitCount={}, anchorTableCount={}, relatedTableCount={}, relatedColumnCount={}, historyCount={}, contextLength={}, promptBudgetUsed={}",
            connectionId,
            normalizedDatabaseName,
            context.getHit(),
            tableHits.size(),
            columnHits.size(),
            historyHits.size(),
            managedMemoryHits.size(),
            metricTermHits.size(),
            exampleSqlHits.size(),
            anchorTables.size(),
            context.getRelatedTables().size(),
            context.getRelatedColumns().size(),
            context.getHistorySqlSamples().size(),
            context.getPromptContext().length(),
            context.getPromptBudgetUsed()
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

    private List<QdrantScoredPoint> searchManagedMemoryAcrossScopes(String collectionName,
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
        return merged.values().stream()
            .sorted(Comparator.comparingDouble(QdrantScoredPoint::getScore).reversed())
            .limit(Math.max(limit * 2L, limit))
            .toList();
    }

    private List<QdrantScoredPoint> filterHistoryEntryType(List<QdrantScoredPoint> historyHits) {
        if (historyHits == null || historyHits.isEmpty()) {
            return List.of();
        }
        return historyHits.stream()
            .filter(hit -> "history_query".equals(payloadString(hit.getPayload(), "entry_type")))
            .toList();
    }

    private List<QdrantScoredPoint> filterMemoryHitsByTables(List<QdrantScoredPoint> hits, Set<String> anchorTables) {
        if (hits == null || hits.isEmpty() || anchorTables == null || anchorTables.isEmpty()) {
            return hits == null ? List.of() : hits;
        }
        List<QdrantScoredPoint> filtered = new ArrayList<>();
        for (QdrantScoredPoint hit : hits) {
            List<String> relatedTables = payloadStringList(hit.getPayload(), "related_tables");
            if (relatedTables.isEmpty()) {
                filtered.add(hit);
                continue;
            }
            boolean matched = relatedTables.stream().map(this::normalizeTableName).anyMatch(anchorTables::contains);
            if (matched) {
                filtered.add(hit);
            }
        }
        return filtered;
    }

    private void markRetrievedManagedMemories(List<QdrantScoredPoint> memoryHits) {
        if (memoryHits == null || memoryHits.isEmpty()) {
            return;
        }
        LinkedHashSet<Long> memoryIds = new LinkedHashSet<>();
        for (QdrantScoredPoint hit : memoryHits) {
            if (hit == null || hit.getPayload() == null) {
                continue;
            }
            Object memoryId = hit.getPayload().get("memory_id");
            if (memoryId instanceof Number number && number.longValue() > 0) {
                memoryIds.add(number.longValue());
            } else {
                String text = Objects.toString(memoryId, "").trim();
                if (!text.isBlank()) {
                    try {
                        memoryIds.add(Long.parseLong(text));
                    } catch (Exception ignore) {
                        // ignore malformed memory id payload
                    }
                }
            }
        }
        if (memoryIds.isEmpty()) {
            return;
        }
        try {
            memoryService.markRetrieved(new ArrayList<>(memoryIds));
        } catch (Exception ex) {
            log.warn("长期记忆命中计数更新失败, reason={}", ex.getMessage());
        }
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

    private LinkedHashSet<String> buildAnchorTables(RetrievalQuery query,
                                                    List<QdrantScoredPoint> tableHits,
                                                    List<QdrantScoredPoint> termHits) {
        LinkedHashSet<String> anchorTables = new LinkedHashSet<>();
        if (query != null && query.focusTables() != null) {
            anchorTables.addAll(query.focusTables());
        }
        if (tableHits != null) {
            for (QdrantScoredPoint hit : tableHits) {
                String tableName = normalizeTableName(payloadString(hit.getPayload(), "table_name"));
                if (!tableName.isBlank()) {
                    anchorTables.add(tableName);
                }
                if (anchorTables.size() >= PROMPT_TABLE_LIMIT) {
                    break;
                }
            }
        }
        if (termHits != null && anchorTables.size() < PROMPT_TABLE_LIMIT) {
            for (QdrantScoredPoint hit : termHits) {
                for (String table : payloadStringList(hit.getPayload(), "related_tables")) {
                    String normalized = normalizeTableName(table);
                    if (!normalized.isBlank()) {
                        anchorTables.add(normalized);
                    }
                    if (anchorTables.size() >= PROMPT_TABLE_LIMIT) {
                        break;
                    }
                }
                if (anchorTables.size() >= PROMPT_TABLE_LIMIT) {
                    break;
                }
            }
        }
        return anchorTables;
    }

    private List<QdrantScoredPoint> selectAnchorTableHits(List<QdrantScoredPoint> tableHits, Set<String> anchorTables) {
        if (tableHits == null || tableHits.isEmpty()) {
            return List.of();
        }
        List<QdrantScoredPoint> selected = new ArrayList<>();
        Set<String> accepted = new LinkedHashSet<>();
        for (QdrantScoredPoint hit : tableHits) {
            String tableName = normalizeTableName(payloadString(hit.getPayload(), "table_name"));
            if (tableName.isBlank()) {
                continue;
            }
            if (anchorTables != null && !anchorTables.isEmpty() && !anchorTables.contains(tableName)) {
                continue;
            }
            if (accepted.add(tableName)) {
                selected.add(hit);
            }
            if (selected.size() >= PROMPT_TABLE_LIMIT) {
                break;
            }
        }
        return selected;
    }

    private List<QdrantScoredPoint> selectPromptColumnHits(List<QdrantScoredPoint> columnHits, List<QdrantScoredPoint> selectedTableHits) {
        if (columnHits == null || columnHits.isEmpty() || selectedTableHits == null || selectedTableHits.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> selectedTables = new LinkedHashSet<>();
        for (QdrantScoredPoint hit : selectedTableHits) {
            String tableName = normalizeTableName(payloadString(hit.getPayload(), "table_name"));
            if (!tableName.isBlank()) {
                selectedTables.add(tableName);
            }
        }
        Map<String, Integer> perTableCount = new LinkedHashMap<>();
        List<QdrantScoredPoint> sorted = new ArrayList<>(columnHits);
        sorted.sort(Comparator.comparingInt(this::columnPromptPriority)
            .thenComparing((QdrantScoredPoint hit) -> safeScore(hit), Comparator.reverseOrder()));
        List<QdrantScoredPoint> selected = new ArrayList<>();
        for (QdrantScoredPoint hit : sorted) {
            String tableName = normalizeTableName(payloadString(hit.getPayload(), "table_name"));
            String columnName = normalizeColumnName(payloadString(hit.getPayload(), "column_name"));
            if (tableName.isBlank() || columnName.isBlank() || !selectedTables.contains(tableName)) {
                continue;
            }
            int count = perTableCount.getOrDefault(tableName, 0);
            if (count >= PROMPT_COLUMNS_PER_TABLE_LIMIT) {
                continue;
            }
            selected.add(hit);
            perTableCount.put(tableName, count + 1);
        }
        return selected;
    }

    private int columnPromptPriority(QdrantScoredPoint hit) {
        if (hit == null || hit.getPayload() == null) {
            return Integer.MAX_VALUE;
        }
        List<String> roles = payloadStringList(hit.getPayload(), "column_roles");
        if (roles.contains("join") || roles.contains("primary_key")) {
            return 0;
        }
        if (roles.contains("time")) {
            return 1;
        }
        if (roles.contains("metric")) {
            return 2;
        }
        if (roles.contains("indexed")) {
            return 3;
        }
        return 4;
    }

    private ExampleGateResult gateExampleSqlHits(List<QdrantScoredPoint> hits,
                                                 RetrievalQuery query,
                                                 Set<String> anchorTables,
                                                 List<QdrantScoredPoint> termHits) {
        if (hits == null || hits.isEmpty()) {
            return new ExampleGateResult(List.of(), Map.of(), List.of(
                buildSelectionDetail("example_sql", "skipped", "无样例 SQL 候选", Map.of())
            ));
        }
        Set<Long> termIds = new LinkedHashSet<>();
        if (termHits != null) {
            for (QdrantScoredPoint hit : termHits) {
                Object entityId = hit == null || hit.getPayload() == null ? null : hit.getPayload().get("entity_id");
                if (entityId instanceof Number number) {
                    termIds.add(number.longValue());
                }
            }
        }
        List<ExampleSelectionInfo> accepted = new ArrayList<>();
        List<Map<String, Object>> traceDetails = new ArrayList<>();
        for (QdrantScoredPoint hit : hits) {
            if (hit == null) {
                continue;
            }
            Map<String, Object> payload = hit.getPayload();
            String sqlOperationType = safeOperationType(payloadString(payload, "sql_operation_type"));
            String queryOperationType = query == null ? "" : safeOperationType(query.operationType());
            double qualityScore = payloadDouble(payload, "quality_score", payloadDouble(payload, "trust_level", 0.0D));
            List<String> exampleTables = payloadStringList(payload, "tables");
            double tableOverlap = calculateTableOverlap(anchorTables, exampleTables);
            boolean operationMatch = queryOperationType.isBlank() || queryOperationType.equals(sqlOperationType) || "SELECT".equals(queryOperationType);
            boolean tableMatch = anchorTables == null || anchorTables.isEmpty() || tableOverlap > 0D;
            boolean qualityMatch = qualityScore >= 0.55D;
            boolean verified = payloadBoolean(payload, "verified_flag");
            int scopePriority = resolveScopePriority(payloadString(payload, "scope"));
            boolean termMatched = hasTermOverlap(payload.get("term_ids"), termIds);
            boolean scopeMatch = scopePriority > 0;
            String decision = "accepted";
            String reason = "通过门控";
            if (!qualityMatch) {
                decision = "dropped";
                reason = "质量分过低";
            } else if (!operationMatch) {
                decision = "dropped";
                reason = "SQL 操作类型不匹配";
            } else if (!tableMatch) {
                decision = "dropped";
                reason = "样例表集合与表锚点无重叠";
            } else if (!verified && scopePriority <= 1) {
                decision = "dropped";
                reason = "低作用域且未验证样例";
            }
            Map<String, Object> detailExtra = new LinkedHashMap<>();
            detailExtra.put("entityId", payload.get("entity_id"));
            detailExtra.put("scope", payloadString(payload, "scope"));
            detailExtra.put("scopePriority", scopePriority);
            detailExtra.put("scopeMatch", scopeMatch);
            detailExtra.put("qualityScore", qualityScore);
            detailExtra.put("trustLevel", qualityScore);
            detailExtra.put("tableOverlap", tableOverlap);
            detailExtra.put("queryOperationType", queryOperationType);
            detailExtra.put("sqlOperationType", sqlOperationType);
            detailExtra.put("termMatched", termMatched);
            detailExtra.put("verified", verified);
            detailExtra.put("dropReason", "accepted".equals(decision) ? "" : reason);
            Map<String, Object> detail = buildSelectionDetail(
                "example_sql",
                decision,
                reason,
                detailExtra
            );
            traceDetails.add(detail);
            if ("accepted".equals(decision)) {
                accepted.add(new ExampleSelectionInfo(hit, tableOverlap, qualityScore, scopePriority, termMatched));
            }
        }
        accepted.sort(Comparator.comparingDouble(ExampleSelectionInfo::tableOverlap).reversed()
            .thenComparing((left, right) -> Boolean.compare(right.termMatched(), left.termMatched()))
            .thenComparing(Comparator.comparingInt(ExampleSelectionInfo::scopePriority).reversed())
            .thenComparing(Comparator.comparingDouble(ExampleSelectionInfo::qualityScore).reversed())
            .thenComparing(item -> safeScore(item.hit()), Comparator.reverseOrder()));
        Map<String, ExampleSelectionInfo> acceptedById = new LinkedHashMap<>();
        List<QdrantScoredPoint> acceptedHits = new ArrayList<>();
        for (ExampleSelectionInfo info : accepted) {
            acceptedHits.add(info.hit());
            acceptedById.put(Objects.toString(info.hit().getId(), ""), info);
        }
        traceDetails.add(buildSelectionDetail(
            "example_sql",
            "summary",
            "样例 SQL 门控完成",
            Map.of("acceptedCount", acceptedHits.size(), "candidateCount", hits.size())
        ));
        return new ExampleGateResult(acceptedHits, acceptedById, traceDetails);
    }

    private boolean hasTermOverlap(Object rawTermIds, Set<Long> termIds) {
        if (termIds == null || termIds.isEmpty() || !(rawTermIds instanceof List<?> termIdList)) {
            return false;
        }
        for (Object item : termIdList) {
            if (item instanceof Number number && termIds.contains(number.longValue())) {
                return true;
            }
        }
        return false;
    }

    private double calculateTableOverlap(Set<String> anchorTables, List<String> exampleTables) {
        if (anchorTables == null || anchorTables.isEmpty() || exampleTables == null || exampleTables.isEmpty()) {
            return 0D;
        }
        int overlap = 0;
        for (String table : exampleTables) {
            if (anchorTables.contains(normalizeTableName(table))) {
                overlap++;
            }
        }
        return overlap <= 0 ? 0D : overlap * 1.0D / Math.max(1, exampleTables.size());
    }

    private int resolveScopePriority(String scope) {
        String normalized = Objects.toString(scope, "").trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "DATABASE" -> 3;
            case "CONNECTION" -> 2;
            case "GLOBAL" -> 1;
            default -> 0;
        };
    }

    private Map<String, Object> buildSelectionDetail(String bucket, String decision, String reason, Map<String, Object> extra) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("bucket", bucket);
        detail.put("decision", decision);
        detail.put("reason", reason);
        if (extra != null && !extra.isEmpty()) {
            detail.putAll(extra);
        }
        return detail;
    }

    private PromptBuildResult buildPromptContext(RetrievalQuery query,
                                                 List<QdrantScoredPoint> tableHits,
                                                 List<QdrantScoredPoint> columnHits,
                                                 List<QdrantScoredPoint> memoryHits,
                                                 List<QdrantScoredPoint> termHits,
                                                 List<QdrantScoredPoint> exampleHits,
                                                 List<QdrantScoredPoint> historyHits,
                                                 Map<String, ExampleSelectionInfo> acceptedExampleById) {
        StringBuilder builder = new StringBuilder();
        LinkedHashSet<String> relatedTables = new LinkedHashSet<>();
        LinkedHashSet<String> relatedColumns = new LinkedHashSet<>();
        List<String> historySqlSamples = new ArrayList<>();

        StringBuilder goalSection = new StringBuilder();
        if (query != null && !isBlank(query.semanticQuery())) {
            goalSection.append("- 用户目标: ").append(query.semanticQuery()).append("\n");
        }
        if (query != null && query.focusTables() != null && !query.focusTables().isEmpty()) {
            goalSection.append("- 重点表: ").append(String.join(", ", query.focusTables())).append("\n");
        }
        if (query != null && !isBlank(query.operationType())) {
            goalSection.append("- SQL 类型: ").append(query.operationType()).append("\n");
        }
        appendSection(builder, "用户目标与硬约束", goalSection.toString().trim());

        StringBuilder tableSection = new StringBuilder();
        for (QdrantScoredPoint hit : tableHits) {
            Map<String, Object> payload = hit.getPayload();
            String tableName = payloadString(payload, "table_name");
            if (tableName.isBlank()) {
                continue;
            }
            relatedTables.add(tableName);
            tableSection.append("- ").append(tableName);
            String comment = payloadString(payload, "table_comment");
            if (!comment.isBlank()) {
                tableSection.append("（").append(comment).append("）");
            }
            appendTableRole(tableSection, "PK", payloadStringList(payload, "primary_keys"));
            appendTableRole(tableSection, "IDX", payloadStringList(payload, "indexed_columns"));
            appendTableRole(tableSection, "时间", payloadStringList(payload, "time_columns"));
            appendTableRole(tableSection, "度量", payloadStringList(payload, "metric_columns"));
            appendTableRole(tableSection, "维度", payloadStringList(payload, "dimension_columns"));
            tableSection.append("\n");
        }
        appendSection(builder, "确认的表锚点", tableSection.toString().trim());

        StringBuilder columnSection = new StringBuilder();
        Map<String, List<QdrantScoredPoint>> groupedColumns = new LinkedHashMap<>();
        for (QdrantScoredPoint tableHit : tableHits) {
            String tableName = payloadString(tableHit.getPayload(), "table_name");
            if (!tableName.isBlank()) {
                groupedColumns.put(tableName, new ArrayList<>());
            }
        }
        for (QdrantScoredPoint hit : columnHits) {
            String tableName = payloadString(hit.getPayload(), "table_name");
            if (!tableName.isBlank()) {
                groupedColumns.computeIfAbsent(tableName, key -> new ArrayList<>()).add(hit);
            }
        }
        for (Map.Entry<String, List<QdrantScoredPoint>> entry : groupedColumns.entrySet()) {
            columnSection.append("- ").append(entry.getKey()).append(": ");
            Map<String, List<String>> byRole = new LinkedHashMap<>();
            for (QdrantScoredPoint hit : entry.getValue()) {
                String columnName = payloadString(hit.getPayload(), "column_name");
                if (columnName.isBlank()) {
                    continue;
                }
                relatedColumns.add(entry.getKey() + "." + columnName);
                List<String> roles = payloadStringList(hit.getPayload(), "column_roles");
                String majorRole = roles.contains("join") ? "join"
                    : roles.contains("time") ? "time"
                    : roles.contains("metric") ? "metric"
                    : roles.contains("dimension") ? "dimension"
                    : "filter";
                byRole.computeIfAbsent(majorRole, key -> new ArrayList<>()).add(columnName);
            }
            List<String> roleSegments = new ArrayList<>();
            for (String role : List.of("join", "filter", "metric", "dimension", "time")) {
                List<String> columns = byRole.get(role);
                if (columns == null || columns.isEmpty()) {
                    continue;
                }
                roleSegments.add(role + "=" + String.join(",", columns));
            }
            columnSection.append(String.join("; ", roleSegments)).append("\n");
        }
        appendSection(builder, "相关字段", columnSection.toString().trim());

        StringBuilder memorySection = new StringBuilder();
        for (QdrantScoredPoint hit : memoryHits) {
            Map<String, Object> payload = hit.getPayload();
            relatedTables.addAll(payloadStringList(payload, "related_tables"));
            memorySection.append("- ").append(payloadString(payload, "title"));
            String scope = payloadString(payload, "scope");
            if (!scope.isBlank()) {
                memorySection.append(" [scope=").append(scope).append("]");
            }
            if (!payloadString(payload, "summary").isBlank()) {
                memorySection.append(": ").append(payloadString(payload, "summary"));
            }
            appendListIfPresent(memorySection, "关联表", payloadStringList(payload, "related_tables"));
            memorySection.append("\n");
        }
        appendSection(builder, "长期记忆", memorySection.toString().trim());

        StringBuilder termSection = new StringBuilder();
        for (QdrantScoredPoint hit : termHits) {
            Map<String, Object> payload = hit.getPayload();
            termSection.append("- ").append(payloadString(payload, "term"));
            if (!payloadString(payload, "definition").isBlank()) {
                termSection.append(": ").append(payloadString(payload, "definition"));
            }
            if (!payloadString(payload, "metric_expression").isBlank()) {
                termSection.append("（口径=").append(payloadString(payload, "metric_expression")).append("）");
            }
            appendListIfPresent(termSection, "别名", payloadStringList(payload, "aliases"));
            appendListIfPresent(termSection, "关联表", payloadStringList(payload, "related_tables"));
            termSection.append("\n");
        }
        appendSection(builder, "口径与术语", termSection.toString().trim());

        StringBuilder referenceSection = new StringBuilder();
        for (QdrantScoredPoint hit : exampleHits) {
            Map<String, Object> payload = hit.getPayload();
            ExampleSelectionInfo selectionInfo = acceptedExampleById == null ? null : acceptedExampleById.get(Objects.toString(hit.getId(), ""));
            String scope = payloadString(payload, "scope");
            double trustLevel = payloadDouble(payload, "quality_score", payloadDouble(payload, "trust_level", 0D));
            referenceSection.append("- 样例 SQL [scope=").append(scope)
                .append(", trust=").append(String.format(Locale.ROOT, "%.2f", trustLevel))
                .append(", overlap=").append(String.format(Locale.ROOT, "%.2f", selectionInfo == null ? 0D : selectionInfo.tableOverlap()))
                .append("]\n");
            if (!payloadString(payload, "question_text").isBlank()) {
                referenceSection.append("  问法: ").append(payloadString(payload, "question_text")).append("\n");
            }
            if (!payloadString(payload, "semantic_description").isBlank()) {
                referenceSection.append("  适用原因: ").append(payloadString(payload, "semantic_description")).append("\n");
            }
            referenceSection.append("  不可直接照搬: 仅作结构参考，需以当前 schema 与术语定义为准\n");
            referenceSection.append("  SQL: ").append(shortenSql(payloadString(payload, "sql_text"))).append("\n");
            historySqlSamples.add(payloadString(payload, "sql_text"));
        }
        for (QdrantScoredPoint hit : historyHits) {
            Map<String, Object> payload = hit.getPayload();
            referenceSection.append("- 历史 SQL [fallback, trust=")
                .append(String.format(Locale.ROOT, "%.2f", payloadDouble(payload, "trust_level", 0.55D)))
                .append("]\n");
            if (!payloadString(payload, "semantic_description").isBlank()) {
                referenceSection.append("  适用原因: ").append(payloadString(payload, "semantic_description")).append("\n");
            }
            referenceSection.append("  不可直接照搬: 仅作回退参考，需重新核对当前 schema\n");
            referenceSection.append("  SQL: ").append(shortenSql(payloadString(payload, "sql_text"))).append("\n");
            historySqlSamples.add(payloadString(payload, "sql_text"));
        }
        appendSection(builder, "参考 SQL", referenceSection.toString().trim());

        String promptContext = builder.toString().trim();
        int promptBudgetUsed = Math.max(1, promptContext.length() / 4);
        if (promptBudgetUsed > PROMPT_CONTEXT_TOKEN_BUDGET) {
            promptContext = shorten(promptContext, PROMPT_CONTEXT_TOKEN_BUDGET * 4);
            promptBudgetUsed = Math.max(1, promptContext.length() / 4);
        }
        return new PromptBuildResult(promptContext, new ArrayList<>(relatedTables), new ArrayList<>(relatedColumns), historySqlSamples, promptBudgetUsed);
    }

    private void appendSection(StringBuilder builder, String title, String content) {
        if (builder == null || isBlank(content)) {
            return;
        }
        if (builder.length() > 0) {
            builder.append("\n\n");
        }
        builder.append("【").append(title).append("】\n").append(content);
    }

    private void appendTableRole(StringBuilder builder, String label, List<String> values) {
        if (builder == null || values == null || values.isEmpty()) {
            return;
        }
        builder.append(" ").append(label).append("(").append(String.join(",", values.stream().limit(4).toList())).append(")");
    }

    private void appendListIfPresent(StringBuilder builder, String label, List<String> values) {
        if (builder == null || values == null || values.isEmpty()) {
            return;
        }
        builder.append("（").append(label).append("=").append(String.join(",", values.stream().limit(4).toList())).append("）");
    }

    private String shortenSql(String sqlText) {
        return shorten(Objects.toString(sqlText, "").replaceAll("\\s+", " ").trim(), 360);
    }

    private RetrievalQuery parseRetrievalQuery(String userInput) {
        String rawInput = Objects.toString(userInput, "").trim();
        String semanticQuery = extractPatternValue(RETRIEVAL_QUERY_PATTERN, rawInput);
        if (semanticQuery.isBlank()) {
            int contextIndex = rawInput.indexOf("\n补充上下文:");
            semanticQuery = contextIndex >= 0 ? rawInput.substring(0, contextIndex).trim() : rawInput;
        }
        LinkedHashSet<String> focusTables = new LinkedHashSet<>();
        String focusTableLine = extractPatternValue(FOCUS_TABLES_PATTERN, rawInput);
        if (!focusTableLine.isBlank()) {
            for (String token : focusTableLine.split("[,，\\s]+")) {
                String normalized = normalizeTableName(token);
                if (!normalized.isBlank()) {
                    focusTables.add(normalized);
                }
            }
        }
        Matcher identifierMatcher = IDENTIFIER_PATTERN.matcher(rawInput);
        while (identifierMatcher.find()) {
            String identifier = identifierMatcher.group();
            if (identifier == null || identifier.chars().filter(ch -> ch == '_').count() < 2) {
                continue;
            }
            String normalized = normalizeTableName(identifier);
            if (!normalized.isBlank()) {
                focusTables.add(normalized);
            }
        }
        StringBuilder embeddingBuilder = new StringBuilder();
        if (!semanticQuery.isBlank()) {
            embeddingBuilder.append(semanticQuery);
        }
        if (!focusTables.isEmpty()) {
            if (embeddingBuilder.length() > 0) {
                embeddingBuilder.append('\n');
            }
            embeddingBuilder.append("重点表: ").append(String.join(",", focusTables));
        }
        String embeddingText = embeddingBuilder.length() == 0 ? rawInput : embeddingBuilder.toString();
        return new RetrievalQuery(rawInput, semanticQuery, List.copyOf(focusTables), embeddingText, resolveSqlOperationType(rawInput));
    }

    private String extractPatternValue(Pattern pattern, String text) {
        if (pattern == null || isBlank(text)) {
            return "";
        }
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return "";
        }
        return Objects.toString(matcher.group(1), "").trim();
    }

    private ExampleSqlSupplementResult supplementSchemaHitsByFocusTables(Long connectionId,
                                                                        String databaseName,
                                                                        List<String> focusTables,
                                                                        List<QdrantScoredPoint> tableHits,
                                                                        List<QdrantScoredPoint> columnHits) {
        if (focusTables == null || focusTables.isEmpty()) {
            return new ExampleSqlSupplementResult(
                tableHits == null ? List.of() : tableHits,
                columnHits == null ? List.of() : columnHits,
                0,
                0
            );
        }
        return supplementSchemaHitsByTables(connectionId, databaseName, focusTables, tableHits, columnHits, "focus_table");
    }

    private List<QdrantScoredPoint> supplementHistoryHitsByFocusTables(Long connectionId,
                                                                       String databaseName,
                                                                       List<Float> vector,
                                                                       List<String> focusTables,
                                                                       List<QdrantScoredPoint> historyHits) {
        Map<String, QdrantScoredPoint> merged = new LinkedHashMap<>();
        mergeHits(merged, historyHits);
        if (connectionId == null || vector == null || vector.isEmpty() || focusTables == null || focusTables.isEmpty()) {
            return merged.values().stream()
                .sorted(Comparator.comparingDouble(QdrantScoredPoint::getScore).reversed())
                .toList();
        }
        for (String focusTable : focusTables) {
            if (focusTable == null || focusTable.isBlank()) {
                continue;
            }
            mergeHits(merged, safeSearchByFilters(collectionNames.getSqlHistory(), vector, Math.max(2, sqlHistoryLimit), List.of(
                new QdrantPayloadFilter("connection_id", connectionId),
                new QdrantPayloadFilter("database_name", databaseName),
                new QdrantPayloadFilter("entry_type", "history_query"),
                new QdrantPayloadFilter("tables", focusTable)
            )));
        }
        return merged.values().stream()
            .sorted(Comparator.comparingDouble(QdrantScoredPoint::getScore).reversed())
            .toList();
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
                List<String> relatedTables = payloadStringList(hit.getPayload(), "related_tables");
                if (tableName.isBlank() && tables.isEmpty() && relatedTables.isEmpty()) {
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
                for (String table : relatedTables) {
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
        return supplementSchemaHitsByTables(connectionId, databaseName, exampleTables, tableHits, columnHits, "example_sql");
    }

    private ExampleSqlSupplementResult supplementSchemaHitsByTables(Long connectionId,
                                                                    String databaseName,
                                                                    Collection<String> tablesToSupplement,
                                                                    List<QdrantScoredPoint> tableHits,
                                                                    List<QdrantScoredPoint> columnHits,
                                                                    String sourcePrefix) {
        if (connectionId == null || tablesToSupplement == null || tablesToSupplement.isEmpty()) {
            return new ExampleSqlSupplementResult(
                tableHits == null ? List.of() : tableHits,
                columnHits == null ? List.of() : columnHits,
                0,
                0
            );
        }
        Set<String> existingTableNames = collectConstraintTables(tableHits);
        List<String> missingTables = tablesToSupplement.stream()
            .map(this::normalizeTableName)
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
        double tableBoostScore = resolveBoostScore(mergedTableHits)
            + ("focus_table".equals(sourcePrefix) ? 0.10D : 0D);
        double columnBoostScore = resolveBoostScore(mergedColumnHits)
            + ("focus_table".equals(sourcePrefix) ? 0.08D : 0D);
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
                sourcePrefix + "_supplement_table:" + normalizeTableName(canonicalTableName),
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
                    sourcePrefix + "_supplement_column:" + columnKey,
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
        payload.put("entity_type", "schema_table");
        payload.put("entity_id", tableName);
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
        payload.put("primary_keys", columns.stream()
            .filter(Objects::nonNull)
            .filter(column -> Boolean.TRUE.equals(column.getPrimaryKey()))
            .map(TableDetailVO.ColumnDetailVO::getColumnName)
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(name -> !name.isBlank())
            .limit(4)
            .toList());
        payload.put("indexed_columns", columns.stream()
            .filter(Objects::nonNull)
            .filter(column -> Boolean.TRUE.equals(column.getIndexed()))
            .map(TableDetailVO.ColumnDetailVO::getColumnName)
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(name -> !name.isBlank())
            .limit(6)
            .toList());
        payload.put("time_columns", columns.stream()
            .filter(Objects::nonNull)
            .filter(column -> resolveColumnRoles(column).contains("time"))
            .map(TableDetailVO.ColumnDetailVO::getColumnName)
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(name -> !name.isBlank())
            .limit(4)
            .toList());
        payload.put("metric_columns", columns.stream()
            .filter(Objects::nonNull)
            .filter(column -> resolveColumnRoles(column).contains("metric"))
            .map(TableDetailVO.ColumnDetailVO::getColumnName)
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(name -> !name.isBlank())
            .limit(4)
            .toList());
        payload.put("dimension_columns", columns.stream()
            .filter(Objects::nonNull)
            .filter(column -> resolveColumnRoles(column).contains("dimension"))
            .map(TableDetailVO.ColumnDetailVO::getColumnName)
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(name -> !name.isBlank())
            .limit(6)
            .toList());
        payload.put("trust_level", 1.0D);
        payload.put("entity_version", 0L);
        return payload;
    }

    private Map<String, Object> buildSupplementColumnPayload(String tableName, TableDetailVO.ColumnDetailVO column) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String columnName = column == null ? "" : Objects.toString(column.getColumnName(), "").trim();
        payload.put("entity_type", "schema_column");
        payload.put("entity_id", tableName + "." + columnName);
        payload.put("table_name", tableName);
        payload.put("column_name", columnName);
        payload.put("data_type", column == null ? "" : Objects.toString(column.getDataType(), "").trim());
        payload.put("column_comment", column == null ? "" : Objects.toString(column.getColumnComment(), "").trim());
        payload.put("indexed", column != null && Boolean.TRUE.equals(column.getIndexed()));
        payload.put("primary_key", column != null && Boolean.TRUE.equals(column.getPrimaryKey()));
        payload.put("nullable", column == null || Boolean.TRUE.equals(column.getNullable()));
        payload.put("column_roles", resolveColumnRoles(column));
        payload.put("trust_level", 1.0D);
        payload.put("entity_version", 0L);
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

    private RerankResult rerankHits(RetrievalQuery query,
                                    String bucket,
                                    List<QdrantScoredPoint> hits,
                                    boolean rerankEnabled) {
        String rerankProvider = currentRerankProvider(rerankEnabled, "DISABLED");
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

        String rerankQueryText = query == null ? "" : query.embeddingText();
        List<Double> onnxScores = ragRerankService.score(rerankQueryText, bucket, hits);
        rerankProvider = currentRerankProvider(rerankEnabled, rerankProvider);
        boolean onnxAvailable = onnxScores.size() == hits.size();
        List<ScoredHit> rescored = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            QdrantScoredPoint hit = hits.get(i);
            double vectorScore = hit.getScore() == null ? 0.0 : hit.getScore();
            double ruleBonus = resolveRuleBonus(query, bucket, hit.getPayload());
            double onnxScore = onnxAvailable
                ? clip01(onnxScores.get(i))
                : clip01(vectorScore + Math.min(1.0D, ruleBonus) * 0.35D);
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
            case "example_sql" -> {
                String questionText = payloadString(payload, "question_text");
                if (!questionText.isBlank()) {
                    yield shorten(questionText, 96);
                }
                yield shorten(payloadString(payload, "sql_text"), 96);
            }
            case "query_history" -> {
                String questionText = payloadString(payload, "question_text");
                if (!questionText.isBlank()) {
                    yield shorten(questionText, 96);
                }
                yield shorten(payloadString(payload, "sql_text"), 96);
            }
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

    private String currentRerankProvider(boolean rerankEnabled, String fallback) {
        if (!rerankEnabled) {
            return "DISABLED";
        }
        String runtimeProvider = Objects.toString(ragRerankService.getRuntimeProvider(), "").trim();
        if (!runtimeProvider.isBlank()) {
            return runtimeProvider;
        }
        return Objects.toString(fallback, "").trim();
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

    private double resolveRuleBonus(RetrievalQuery query, String bucket, Map<String, Object> payload) {
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
        String queryText = query == null ? "" : query.rawInput();
        double timeBonus = containsStructuredTimeSignal(queryText) ? 0.2 : 0.0;
        double focusTableBonus = focusTableMatchBonus(query, payload);
        double mentionBonus = payloadMentionBonus(queryText, payload);
        double trustBonus = Math.min(1.0D, payloadDouble(payload, "trust_level", 0.0D));
        double scopeBonus = resolveScopePriority(payloadString(payload, "scope")) * 0.08D;
        double operationBonus = operationMatchBonus(query, payload);
        return schemaBonus + timeBonus + focusTableBonus + mentionBonus + trustBonus + scopeBonus + operationBonus;
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

    private double focusTableMatchBonus(RetrievalQuery query, Map<String, Object> payload) {
        if (query == null || query.focusTables().isEmpty() || payload == null) {
            return 0.0;
        }
        String tableName = normalizeTableName(payloadString(payload, "table_name"));
        if (!tableName.isBlank() && query.focusTables().contains(tableName)) {
            return 1.2;
        }
        for (String table : payloadStringList(payload, "tables")) {
            if (query.focusTables().contains(normalizeTableName(table))) {
                return 1.0;
            }
        }
        return 0.0;
    }

    private double operationMatchBonus(RetrievalQuery query, Map<String, Object> payload) {
        if (query == null || payload == null) {
            return 0.0;
        }
        String queryOperation = safeOperationType(query.operationType());
        if (queryOperation.isBlank()) {
            return 0.0;
        }
        String payloadOperation = safeOperationType(payloadString(payload, "sql_operation_type"));
        if (payloadOperation.isBlank()) {
            return "SELECT".equals(queryOperation) ? 0.1D : 0.0D;
        }
        return queryOperation.equals(payloadOperation) ? 0.6D : -0.4D;
    }

    private double payloadMentionBonus(String queryText, Map<String, Object> payload) {
        String normalizedQuery = Objects.toString(queryText, "").toLowerCase(Locale.ROOT);
        if (normalizedQuery.isBlank() || payload == null) {
            return 0.0;
        }
        String tableName = normalizeTableName(payloadString(payload, "table_name"));
        if (!tableName.isBlank() && normalizedQuery.contains(tableName)) {
            return 0.6;
        }
        String columnName = normalizeColumnName(payloadString(payload, "column_name"));
        if (!columnName.isBlank() && normalizedQuery.contains(columnName)) {
            return 0.2;
        }
        return 0.0;
    }

    private boolean containsStructuredTimeSignal(String text) {
        String normalized = Objects.toString(text, "").toLowerCase(Locale.ROOT);
        return containsTimeSignal(text)
            || normalized.contains("日") || normalized.contains("周") || normalized.contains("月")
            || normalized.contains("年") || normalized.contains("季度");
    }

    private record ScoredHit(QdrantScoredPoint hit,
                             double score,
                             double vectorScore,
                             double onnxScore,
                             double ruleBonus) {
    }

    private record RerankResult(List<QdrantScoredPoint> hits, Map<String, Object> traceDetail) {
    }

    private record RetrievalQuery(String rawInput,
                                  String semanticQuery,
                                  List<String> focusTables,
                                  String embeddingText,
                                  String operationType) {
    }

    private record ExampleSqlSupplementResult(List<QdrantScoredPoint> tableHits,
                                              List<QdrantScoredPoint> columnHits,
                                              int supplementedTableCount,
                                              int supplementedColumnCount) {
    }

    private record ExampleSelectionInfo(QdrantScoredPoint hit,
                                        double tableOverlap,
                                        double qualityScore,
                                        int scopePriority,
                                        boolean termMatched) {
    }

    private record ExampleGateResult(List<QdrantScoredPoint> acceptedHits,
                                     Map<String, ExampleSelectionInfo> acceptedById,
                                     List<Map<String, Object>> traceDetails) {
    }

    private record PromptBuildResult(String promptContext,
                                     List<String> relatedTables,
                                     List<String> relatedColumns,
                                     List<String> historySqlSamples,
                                     int promptBudgetUsed) {
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
        context.setSelectionDetails(List.of());
        context.setPromptBudgetUsed(0);
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

    private double payloadDouble(Map<String, Object> payload, String key, double defaultValue) {
        if (payload == null || payload.get(key) == null) {
            return defaultValue;
        }
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(Objects.toString(value, ""));
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    private boolean payloadBoolean(Map<String, Object> payload, String key) {
        if (payload == null || payload.get(key) == null) {
            return false;
        }
        Object value = payload.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        String text = Objects.toString(value, "").trim();
        return "1".equals(text) || "true".equalsIgnoreCase(text);
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

    private String safeOperationType(String value) {
        return Objects.toString(value, "").trim().toUpperCase(Locale.ROOT);
    }

    private String resolveSqlOperationType(String rawText) {
        String normalized = Objects.toString(rawText, "").trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("insert") || normalized.contains("插入") || normalized.contains("新增")) {
            return "INSERT";
        }
        if (normalized.startsWith("update") || normalized.contains("更新") || normalized.contains("修改")) {
            return "UPDATE";
        }
        if (normalized.startsWith("delete") || normalized.contains("删除")) {
            return "DELETE";
        }
        if (normalized.startsWith("create") || normalized.startsWith("alter") || normalized.startsWith("drop")) {
            return "DDL";
        }
        return "SELECT";
    }

    private String normalizeDatabaseName(String databaseName) {
        String value = Objects.toString(databaseName, "").trim();
        return value.isBlank() ? "" : value;
    }

    private List<String> resolveColumnRoles(TableDetailVO.ColumnDetailVO column) {
        if (column == null) {
            return List.of();
        }
        LinkedHashSet<String> roles = new LinkedHashSet<>();
        String name = trimText(column.getColumnName()).toLowerCase(Locale.ROOT);
        String type = trimText(column.getDataType()).toLowerCase(Locale.ROOT);
        String comment = trimText(column.getColumnComment()).toLowerCase(Locale.ROOT);
        if (Boolean.TRUE.equals(column.getPrimaryKey())) {
            roles.add("primary_key");
        }
        if (Boolean.TRUE.equals(column.getIndexed())) {
            roles.add("indexed");
        }
        if (name.endsWith("_id") || "id".equals(name) || comment.contains("关联") || comment.contains("主键")) {
            roles.add("join");
        }
        if (name.contains("time") || name.contains("date") || name.contains("day") || name.contains("month")
            || name.contains("year") || name.contains("created") || comment.contains("时间") || comment.contains("日期")) {
            roles.add("time");
        }
        if (name.contains("amount") || name.contains("price") || name.contains("count") || name.contains("num")
            || name.contains("qty") || name.contains("rate") || type.contains("int") || type.contains("decimal")
            || type.contains("number") || type.contains("double") || type.contains("float")) {
            roles.add("metric");
        }
        if (!roles.contains("metric")) {
            roles.add("dimension");
        }
        return new ArrayList<>(roles);
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
