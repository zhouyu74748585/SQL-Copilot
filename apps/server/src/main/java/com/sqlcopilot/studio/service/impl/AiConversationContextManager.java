package com.sqlcopilot.studio.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqlcopilot.studio.dto.ai.AiConfigVO;
import com.sqlcopilot.studio.dto.ai.AiGenerateSqlReq;
import com.sqlcopilot.studio.dto.schema.ContextBuildReq;
import com.sqlcopilot.studio.dto.schema.ContextBuildVO;
import com.sqlcopilot.studio.entity.QueryHistoryEntity;
import com.sqlcopilot.studio.mapper.QueryHistoryMapper;
import com.sqlcopilot.studio.service.AiConfigService;
import com.sqlcopilot.studio.service.MemoryService;
import com.sqlcopilot.studio.service.SchemaService;
import com.sqlcopilot.studio.service.llm.LlmGatewayRequest;
import com.sqlcopilot.studio.service.llm.LlmGatewayResult;
import com.sqlcopilot.studio.service.llm.LlmGatewayService;
import com.sqlcopilot.studio.service.rag.QdrantClientService;
import com.sqlcopilot.studio.service.rag.RagEmbeddingService;
import com.sqlcopilot.studio.service.rag.model.QdrantScoredPoint;
import com.sqlcopilot.studio.service.rag.model.RagPromptContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Service
public class AiConversationContextManager {

    private static final Logger log = LoggerFactory.getLogger(AiConversationContextManager.class);

    private static final int DEFAULT_MEMORY_WINDOW_SIZE = 12;
    private static final int MIN_MEMORY_WINDOW_SIZE = 4;
    private static final int MAX_MEMORY_WINDOW_SIZE = 50;
    private static final int DEFAULT_MEMORY_WINDOW_TOKENS = 6000;
    private static final int MIN_MEMORY_WINDOW_TOKENS = 512;
    private static final int MAX_MEMORY_WINDOW_TOKENS = 32000;
    private static final double DEFAULT_AUTO_COMPRESS_RATIO = 0.75D;
    private static final double MIN_AUTO_COMPRESS_RATIO = 0.30D;
    private static final double MAX_AUTO_COMPRESS_RATIO = 0.95D;
    private static final int MIN_RAW_RECENT_TOKENS = 512;
    private static final ThreadLocal<Map<String, Object>> REQUEST_CONTEXT_CACHE = ThreadLocal.withInitial(ConcurrentHashMap::new);
    private static final ThreadLocal<Integer> REQUEST_CONTEXT_CACHE_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final String CONTEXT_COMPRESS_SYSTEM_PROMPT = """
        你是对话上下文压缩器。请基于输入内容生成压缩摘要，用于后续 SQL 问答。
        要求：
        1) 只输出最终摘要，不输出推理过程；
        2) 保留业务意图、关键筛选、关键表字段、已确认结论与未解决问题；
        3) 不要编造不存在的信息；
        4) 输出纯文本，控制在 400 中文字以内。
        """;

    private final SchemaService schemaService;
    private final AiConfigService aiConfigService;
    private final MemoryService memoryService;
    private final QueryHistoryMapper queryHistoryMapper;
    private final RagEmbeddingService ragEmbeddingService;
    private final QdrantClientService qdrantClientService;
    private final ObjectMapper objectMapper;
    private final LlmGatewayService llmGatewayService;
    private final String sqlHistoryCollectionName;

    public AiConversationContextManager(SchemaService schemaService,
                                        AiConfigService aiConfigService,
                                        MemoryService memoryService,
                                        QueryHistoryMapper queryHistoryMapper,
                                        RagEmbeddingService ragEmbeddingService,
                                        QdrantClientService qdrantClientService,
                                        ObjectMapper objectMapper,
                                        LlmGatewayService llmGatewayService,
                                        @Value("${rag.collection.sql-history:sql_history}") String sqlHistoryCollectionName) {
        this.schemaService = schemaService;
        this.aiConfigService = aiConfigService;
        this.memoryService = memoryService;
        this.queryHistoryMapper = queryHistoryMapper;
        this.ragEmbeddingService = ragEmbeddingService;
        this.qdrantClientService = qdrantClientService;
        this.objectMapper = objectMapper;
        this.llmGatewayService = llmGatewayService;
        this.sqlHistoryCollectionName = sqlHistoryCollectionName;
    }

    /**
     * 关键步骤：为一次 AI 请求建立上下文缓存作用域，避免同一条链路内重复压缩和重复召回。
     */
    public void enterRequestScope() {
        int depth = REQUEST_CONTEXT_CACHE_DEPTH.get();
        REQUEST_CONTEXT_CACHE_DEPTH.set(depth + 1);
    }

    /**
     * 关键步骤：在请求结束时清理缓存，避免不同会话之间的上下文相互污染。
     */
    public void exitRequestScope() {
        int depth = REQUEST_CONTEXT_CACHE_DEPTH.get();
        if (depth <= 1) {
            REQUEST_CONTEXT_CACHE.remove();
            REQUEST_CONTEXT_CACHE_DEPTH.remove();
            return;
        }
        REQUEST_CONTEXT_CACHE_DEPTH.set(depth - 1);
    }

    /**
     * 关键步骤：统一判断本次请求是否启用会话记忆，优先尊重请求级开关，其次回退全局配置。
     */
    public boolean isMemoryEnabled(AiGenerateSqlReq req) {
        return isMemoryEnabled(req, aiConfigService.getConfig());
    }

    public boolean isMemoryEnabled(AiGenerateSqlReq req, AiConfigVO config) {
        if (req.getMemoryEnabled() != null) {
            return Boolean.TRUE.equals(req.getMemoryEnabled());
        }
        return config == null || !Boolean.FALSE.equals(config.getConversationMemoryEnabled());
    }

    /**
     * 关键步骤：构建给生成链路使用的完整上下文，统一收口 RAG、Schema 和记忆三类来源。
     */
    public ConversationGenerationContext buildGenerationContext(AiGenerateSqlReq req,
                                                                RagPromptContext ragPromptContext,
                                                                String retrievalHintForPrompt) {
        List<String> relatedTables = new ArrayList<>();
        if (ragPromptContext != null && ragPromptContext.getRelatedTables() != null) {
            relatedTables.addAll(ragPromptContext.getRelatedTables());
        }
        String ragContextText = safe(ragPromptContext == null ? "" : ragPromptContext.getPromptContext());
        String promptRetrievalHint = safe(retrievalHintForPrompt);
        if (promptRetrievalHint.isBlank()) {
            promptRetrievalHint = buildRetrievalInput(req.getPrompt());
        }

        String schemaContextText = "";
        if (ragPromptContext == null || !Boolean.TRUE.equals(ragPromptContext.getHit()) || ragContextText.isBlank()) {
            ContextBuildReq contextReq = new ContextBuildReq();
            contextReq.setConnectionId(req.getConnectionId());
            contextReq.setDatabaseName(req.getDatabaseName());
            // 关键步骤：Schema fallback 也复用已经整理过的检索提示，避免回退时丢失意图识别得到的重点信息。
            contextReq.setQuestion(promptRetrievalHint);
            contextReq.setTokenBudget(1200);
            ContextBuildVO schemaContext = schemaService.buildContext(contextReq);
            if (schemaContext.getRelatedTables() != null && !schemaContext.getRelatedTables().isEmpty()) {
                relatedTables.addAll(schemaContext.getRelatedTables());
            }
            schemaContextText = safe(schemaContext.getContext());
        }
        if (!isMemoryEnabled(req)) {
            String fallbackContext = !ragContextText.isBlank() ? ragContextText : schemaContextText;
            return new ConversationGenerationContext(fallbackContext, deduplicateTables(relatedTables), promptRetrievalHint);
        }

        AiConfigVO aiConfig = aiConfigService.getConfig();
        ConversationMemoryPolicy memoryPolicy = resolveMemoryPolicy(aiConfig);
        ConversationMemorySnapshot snapshot = loadConversationMemorySnapshot(req, memoryPolicy);

        List<String> segments = new ArrayList<>();
        // 关键步骤：先放最近窗口与滑动摘要，再拼接 RAG 长期记忆与知识上下文。
        if (!snapshot.windowSummary().isBlank()) {
            segments.add("Conversation Recent Summary:\n" + snapshot.windowSummary());
        }
        if (!snapshot.slidingSummary().isBlank()) {
            segments.add("Conversation Sliding Summary:\n" + snapshot.slidingSummary());
        }
        if (!snapshot.windowStructuredContext().isBlank()) {
            segments.add("Conversation Window Context(JSON):\n" + snapshot.windowStructuredContext());
        }
        if (!ragContextText.isBlank()) {
            segments.add(ragContextText);
        } else if (!schemaContextText.isBlank()) {
            segments.add(schemaContextText);
        }
        return new ConversationGenerationContext(
            String.join("\n\n", segments),
            deduplicateTables(relatedTables),
            promptRetrievalHint
        );
    }

    /**
     * 关键步骤：构建给 RAG 检索使用的输入，并按需拼入窗口摘要或最近原文，避免检索只看当前一句话。
     */
    public String buildRetrievalInputForRag(AiGenerateSqlReq req) {
        return buildRetrievalInputForRag(req, "");
    }

    public String buildRetrievalInputForRag(AiGenerateSqlReq req, String extraContext) {
        String compactHint = extractCompactRetrievalHint(extraContext);
        String baseInput = compactHint.isBlank()
            ? buildRetrievalInput(req.getPrompt(), extraContext)
            : compactHint;
        if (!isMemoryEnabled(req)) {
            return baseInput;
        }
        AiConfigVO aiConfig = aiConfigService.getConfig();
        ConversationMemoryPolicy memoryPolicy = resolveMemoryPolicy(aiConfig);
        ConversationMemorySnapshot snapshot = loadConversationMemorySnapshot(req, memoryPolicy);
        List<String> memorySegments = new ArrayList<>();
        if (!snapshot.windowSummary().isBlank()) {
            memorySegments.add("会话窗口摘要:\n" + snapshot.windowSummary());
        } else if (!snapshot.windowDialogContext().isBlank()) {
            // 关键步骤：未达到自动压缩阈值时，优先保留最近原文，让检索继续看到真实追问细节。
            memorySegments.add("最近会话原文:\n" + snapshot.windowDialogContext());
        }
        if (memorySegments.isEmpty()) {
            return baseInput;
        }
        return buildRetrievalInput(baseInput, String.join("\n", memorySegments));
    }

    /**
     * 关键步骤：统一构建“最近几轮对话”文本，供轻量/最终意图识别复用。
     */
    public String buildIntentRecentDialogContext(AiGenerateSqlReq req, int limit) {
        List<QueryHistoryEntity> chatHistory = queryHistoryMapper.listBySession(
            req.getConnectionId(),
            safe(req.getSessionId()),
            Math.max(1, limit)
        );
        List<QueryHistoryEntity> windowRecords = pickWindowRecords(chatHistory, limit);
        if (windowRecords.isEmpty()) {
            return "";
        }
        ArrayNode rows = objectMapper.createArrayNode();
        for (QueryHistoryEntity item : windowRecords) {
            ObjectNode node = objectMapper.createObjectNode();
            // 关键步骤：最近对话按“单轮记录”输出，避免把一整条含 SQL/助手回答的记录错误标成 user。
            node.put("turnType", "chat_history_turn");
            node.put("userPrompt", safe(item.getPromptText()));
            node.put("assistantReply", safe(item.getAssistantContent()));
            node.put("sqlOutput", safe(item.getSqlText()));
            node.put("actionType", safe(item.getActionType()));
            node.put("database", safe(item.getDatabaseName()));
            rows.add(node);
        }
        return rows.toString();
    }

    /**
     * 关键步骤：统一拉取意图识别需要的会话内历史和全局历史，避免意图链路在主服务中散落多处拼接逻辑。
     */
    public String retrieveIntentHistoryContext(AiGenerateSqlReq req,
                                               int sessionTopK,
                                               int globalTopK,
                                               String query,
                                               List<String> focusTables) {
        List<String> lines = new ArrayList<>();
        String sessionContext = retrieveSessionHistoryContext(req, sessionTopK);
        if (!sessionContext.isBlank()) {
            lines.add("会话历史:\n" + sessionContext);
        }
        String globalContext = retrieveGlobalHistoryContext(req, globalTopK, query, focusTables);
        if (!globalContext.isBlank()) {
            lines.add("全局历史:\n" + globalContext);
        }
        return String.join("\n\n", lines);
    }

    public String buildRetrievalInput(String prompt) {
        return buildRetrievalInput(prompt, "");
    }

    public String buildRetrievalInput(String prompt, String extraContext) {
        String normalizedPrompt = safe(prompt);
        String normalizedExtraContext = safe(extraContext);
        if (normalizedExtraContext.isBlank()) {
            return normalizedPrompt;
        }
        return normalizedPrompt + "\n补充上下文:\n" + normalizedExtraContext;
    }

    private String extractCompactRetrievalHint(String extraContext) {
        String normalized = safe(extraContext);
        if (normalized.isBlank()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (String rawLine : normalized.split("\\r?\\n")) {
            String line = safe(rawLine);
            if (line.startsWith("检索关键词:") || line.startsWith("重点表:")) {
                lines.add(line);
            }
        }
        return String.join("\n", lines).trim();
    }

    private ConversationMemorySnapshot loadConversationMemorySnapshot(AiGenerateSqlReq req, ConversationMemoryPolicy memoryPolicy) {
        String cacheKey = "conversation-memory-snapshot:"
            + req.getConnectionId() + ":"
            + safe(req.getSessionId()) + ":"
            + safe(req.getDatabaseName()) + ":"
            + resolveRequestedModelId(req) + ":"
            + safe(req.getPrompt()).hashCode() + ":"
            + memoryPolicy.windowSize() + ":"
            + memoryPolicy.windowTokens() + ":"
            + String.format(Locale.ROOT, "%.2f", memoryPolicy.autoCompressRatio());
        return getOrComputeRequestCache(cacheKey, () -> buildConversationMemorySnapshot(req, memoryPolicy));
    }

    /**
     * 关键步骤：一次性拉平本次请求用到的记忆片段，供检索输入和最终生成上下文复用。
     */
    private ConversationMemorySnapshot buildConversationMemorySnapshot(AiGenerateSqlReq req, ConversationMemoryPolicy memoryPolicy) {
        List<QueryHistoryEntity> chatHistory = queryHistoryMapper.listBySession(
            req.getConnectionId(),
            safe(req.getSessionId()),
            500
        );
        if (chatHistory == null || chatHistory.isEmpty()) {
            return new ConversationMemorySnapshot("", "", "[]", "");
        }
        List<QueryHistoryEntity> windowRecords = pickWindowRecordsByTokenBudget(chatHistory, memoryPolicy.windowSize(), memoryPolicy.windowTokens());
        int windowStartIndex = Math.max(0, chatHistory.size() - windowRecords.size());
        List<QueryHistoryEntity> olderRecords = windowStartIndex <= 0 ? List.of() : new ArrayList<>(chatHistory.subList(0, windowStartIndex));
        int windowTokens = sumHistoryTokens(windowRecords);
        boolean shouldCompressWindow = windowTokens >= memoryPolicy.compressTriggerTokens();
        List<QueryHistoryEntity> rawRecentRecords = shouldCompressWindow
            ? pickWindowRecordsByTokenBudget(windowRecords, windowRecords.size(), resolveRecentRawTokenBudget(memoryPolicy))
            : windowRecords;
        String windowDialogContext = buildWindowDialogContext(rawRecentRecords);
        String windowSummary = shouldCompressWindow ? buildCompressedSummary(req, windowRecords) : "";
        String windowStructuredContext = buildStructuredContextJson(rawRecentRecords);
        String slidingSummary = "";
        if (!olderRecords.isEmpty()) {
            // 关键步骤：超出最近原文窗口的更早历史统一折叠成滑动摘要，既保留语义又避免无限堆积 token。
            slidingSummary = buildCompressedSummary(req, olderRecords);
            try {
                memoryService.autoUpsertSessionMemory(
                    req.getConnectionId(),
                    req.getDatabaseName(),
                    req.getSessionId(),
                    slidingSummary,
                    olderRecords
                );
            } catch (Exception ex) {
                log.warn("[AI-LONG-MEMORY-UPSERT-FAILED] sessionId={}, reason={}",
                    safe(req.getSessionId()), safe(ex.getMessage()));
            }
        }
        return new ConversationMemorySnapshot(windowSummary, slidingSummary, windowStructuredContext, windowDialogContext);
    }

    private int resolveMemoryWindowSize(AiConfigVO config) {
        Integer size = config == null ? null : config.getConversationMemoryWindowSize();
        if (size == null) {
            return DEFAULT_MEMORY_WINDOW_SIZE;
        }
        return Math.max(MIN_MEMORY_WINDOW_SIZE, Math.min(size, MAX_MEMORY_WINDOW_SIZE));
    }

    private int resolveMemoryWindowTokens(AiConfigVO config) {
        Integer tokens = config == null ? null : config.getConversationMemoryWindowTokens();
        if (tokens == null) {
            return DEFAULT_MEMORY_WINDOW_TOKENS;
        }
        return Math.max(MIN_MEMORY_WINDOW_TOKENS, Math.min(tokens, MAX_MEMORY_WINDOW_TOKENS));
    }

    private double resolveAutoCompressRatio(AiConfigVO config) {
        Double ratio = config == null ? null : config.getConversationAutoCompressRatio();
        if (ratio == null) {
            return DEFAULT_AUTO_COMPRESS_RATIO;
        }
        return Math.max(MIN_AUTO_COMPRESS_RATIO, Math.min(ratio, MAX_AUTO_COMPRESS_RATIO));
    }

    private ConversationMemoryPolicy resolveMemoryPolicy(AiConfigVO config) {
        int windowSize = resolveMemoryWindowSize(config);
        int windowTokens = resolveMemoryWindowTokens(config);
        double autoCompressRatio = resolveAutoCompressRatio(config);
        int compressTriggerTokens = Math.max(1, (int) Math.ceil(windowTokens * autoCompressRatio));
        return new ConversationMemoryPolicy(windowSize, windowTokens, autoCompressRatio, compressTriggerTokens);
    }

    private int resolveRecentRawTokenBudget(ConversationMemoryPolicy memoryPolicy) {
        int reserved = memoryPolicy.windowTokens() - memoryPolicy.compressTriggerTokens();
        return Math.max(MIN_RAW_RECENT_TOKENS, reserved);
    }

    private List<QueryHistoryEntity> pickWindowRecordsByTokenBudget(List<QueryHistoryEntity> rows, int windowSize, int windowTokenBudget) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<QueryHistoryEntity> selected = new ArrayList<>();
        int usedTokens = 0;
        for (int index = rows.size() - 1; index >= 0; index--) {
            if (selected.size() >= Math.max(1, windowSize)) {
                break;
            }
            QueryHistoryEntity item = rows.get(index);
            int rowTokens = estimateHistoryTokens(item);
            if (!selected.isEmpty() && usedTokens + rowTokens > Math.max(1, windowTokenBudget)) {
                break;
            }
            selected.add(0, item);
            usedTokens += rowTokens;
        }
        return selected;
    }

    private List<QueryHistoryEntity> pickWindowRecords(List<QueryHistoryEntity> rows, int windowSize) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        int start = Math.max(0, rows.size() - Math.max(1, windowSize));
        return rows.subList(start, rows.size());
    }

    /**
     * 关键步骤：将原始对话记录压缩为摘要，减少长对话进入模型时的 token 膨胀。
     */
    private String buildCompressedSummary(AiGenerateSqlReq req, List<QueryHistoryEntity> rows) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (QueryHistoryEntity item : rows) {
            String prompt = safe(item.getPromptText());
            String sql = safe(item.getSqlText());
            String assistant = safe(item.getAssistantContent());
            if (!prompt.isBlank()) {
                builder.append("U: ").append(prompt).append("\n");
            }
            if (!sql.isBlank()) {
                builder.append("SQL: ").append(sql).append("\n");
            }
            if (!assistant.isBlank()) {
                builder.append("A: ").append(assistant).append("\n");
            }
        }
        String source = builder.toString().trim();
        if (source.isBlank()) {
            return "";
        }
        String cacheKey = "compress:summary:" + source.hashCode();
        return getOrComputeRequestCache(cacheKey, () -> compactTextByLlm(req, "会话历史压缩", source));
    }

    /**
     * 关键步骤：保留最近窗口的结构化原文，给后续模型留出“可追溯”的精确上下文。
     */
    private String buildStructuredContextJson(List<QueryHistoryEntity> rows) {
        if (rows == null || rows.isEmpty()) {
            return "[]";
        }
        ArrayNode arrayNode = objectMapper.createArrayNode();
        for (QueryHistoryEntity item : rows) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("id", item.getId() == null ? 0L : item.getId());
            node.put("historyType", safe(item.getHistoryType()));
            node.put("actionType", safe(item.getActionType()));
            node.put("prompt", safe(item.getPromptText()));
            node.put("sql", safe(item.getSqlText()));
            node.put("assistant", safe(item.getAssistantContent()));
            node.put("database", safe(item.getDatabaseName()));
            node.put("createdAt", item.getCreatedAt() == null ? 0L : item.getCreatedAt());
            arrayNode.add(node);
        }
        return arrayNode.toString();
    }

    private String buildWindowDialogContext(List<QueryHistoryEntity> rows) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (QueryHistoryEntity item : rows) {
            String prompt = safe(item.getPromptText());
            String sql = safe(item.getSqlText());
            String assistant = safe(item.getAssistantContent());
            if (!prompt.isBlank()) {
                builder.append("U: ").append(prompt).append("\n");
            }
            if (!sql.isBlank()) {
                builder.append("SQL: ").append(sql).append("\n");
            }
            if (!assistant.isBlank()) {
                builder.append("A: ").append(assistant).append("\n");
            }
        }
        return builder.toString().trim();
    }

    private String retrieveSessionHistoryContext(AiGenerateSqlReq req, int topK) {
        List<QueryHistoryEntity> rows = queryHistoryMapper.listBySession(
            req.getConnectionId(),
            safe(req.getSessionId()),
            Math.max(1, topK)
        );
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        int index = 1;
        int start = Math.max(0, rows.size() - topK);
        for (QueryHistoryEntity item : rows.subList(start, rows.size())) {
            String prompt = safe(item.getPromptText());
            String sql = safe(item.getSqlText());
            String assistant = safe(item.getAssistantContent());
            if (prompt.isBlank() && sql.isBlank() && assistant.isBlank()) {
                continue;
            }
            builder.append(index++).append(". ");
            if (!prompt.isBlank()) {
                builder.append("Q=").append(prompt).append("; ");
            }
            if (!sql.isBlank()) {
                builder.append("SQL=").append(sql).append("; ");
            }
            if (!assistant.isBlank()) {
                builder.append("A=").append(assistant);
            }
            builder.append('\n');
        }
        return builder.toString().trim();
    }

    private String retrieveGlobalHistoryContext(AiGenerateSqlReq req, int topK, String query, List<String> focusTables) {
        String retrievalText = safe(query).isBlank() ? safe(req.getPrompt()) : safe(query);
        try {
            List<Float> vector = ragEmbeddingService.embedText(retrievalText);
            if (vector == null || vector.isEmpty()) {
                return "";
            }
            List<QdrantScoredPoint> points = qdrantClientService.searchPoints(
                sqlHistoryCollectionName,
                vector,
                Math.max(1, topK),
                req.getConnectionId(),
                req.getDatabaseName()
            );
            if (points == null || points.isEmpty()) {
                return "";
            }
            Set<String> tableFilter = new LinkedHashSet<>();
            if (focusTables != null) {
                focusTables.stream().map(this::normalizeRelatedTableName).filter(item -> !item.isBlank()).forEach(tableFilter::add);
            }
            StringBuilder builder = new StringBuilder();
            int idx = 1;
            for (QdrantScoredPoint point : points) {
                Map<String, Object> payload = point.getPayload();
                String sessionId = Objects.toString(payload.get("session_id"), "").trim();
                if (safe(req.getSessionId()).equals(sessionId)) {
                    continue;
                }
                List<String> tables = payloadStringList(payload, "tables");
                if (!tableFilter.isEmpty()) {
                    boolean matched = tables.stream().map(this::normalizeRelatedTableName).anyMatch(tableFilter::contains);
                    if (!matched) {
                        continue;
                    }
                }
                String sqlText = Objects.toString(payload.get("sql_text"), "").trim();
                String semantic = Objects.toString(payload.get("semantic_description"), "").trim();
                if (sqlText.isBlank() && semantic.isBlank()) {
                    continue;
                }
                builder.append(idx++).append(". ");
                if (!semantic.isBlank()) {
                    builder.append("语义=").append(semantic).append("；");
                }
                if (!sqlText.isBlank()) {
                    builder.append("SQL=").append(sqlText).append("；");
                }
                if (!tables.isEmpty()) {
                    builder.append("Tables=").append(String.join(",", tables));
                }
                builder.append('\n');
                if (idx > topK) {
                    break;
                }
            }
            return builder.toString().trim();
        } catch (Exception ex) {
            log.warn("[AI-INTENT-HISTORY-RECALL-FAILED] sessionId={}, reason={}", safe(req.getSessionId()), safe(ex.getMessage()));
            return "";
        }
    }

    /**
     * 关键步骤：上下文压缩统一走原始提示词通道，避免再次拼入上下文造成递归放大。
     */
    private String compactTextByLlm(AiGenerateSqlReq req, String title, String sourceText) {
        String input = "压缩任务: " + safe(title) + "\n\n原始内容:\n" + safe(sourceText);
        try {
            LlmGatewayRequest gatewayRequest = new LlmGatewayRequest();
            gatewayRequest.setModelId(resolveRequestedModelId(req));
            gatewayRequest.setLegacyModelName(req.getModelName());
            gatewayRequest.setSystemPrompt(CONTEXT_COMPRESS_SYSTEM_PROMPT);
            gatewayRequest.setUserPrompt(input);
            gatewayRequest.setTaskLabel("上下文压缩");
            gatewayRequest.setTimeout(Duration.ofSeconds(30));
            gatewayRequest.setTemperature(0.1D);
            LlmGatewayResult gatewayResult = llmGatewayService.callStream(gatewayRequest, null);
            String content = safe(gatewayResult == null ? "" : gatewayResult.getContent());
            if (!content.isBlank()) {
                return content;
            }
        } catch (Exception ex) {
            log.warn("[AI-CONTEXT-COMPRESS-FAILED] sessionId={}, reason={}", safe(req.getSessionId()), safe(ex.getMessage()));
        }
        return safe(sourceText);
    }

    @SuppressWarnings("unchecked")
    private <T> T getOrComputeRequestCache(String key, Supplier<T> supplier) {
        if (key == null || key.isBlank()) {
            return supplier.get();
        }
        Map<String, Object> cache = REQUEST_CONTEXT_CACHE.get();
        if (cache.containsKey(key)) {
            return (T) cache.get(key);
        }
        T value = supplier.get();
        cache.put(key, value);
        return value;
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

    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = Objects.toString(value, "").trim();
        if (text.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(text);
        } catch (Exception ignore) {
            return 0L;
        }
    }

    private int sumHistoryTokens(List<QueryHistoryEntity> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (QueryHistoryEntity item : rows) {
            total += estimateHistoryTokens(item);
        }
        return total;
    }

    private int estimateHistoryTokens(QueryHistoryEntity item) {
        if (item == null) {
            return 0;
        }
        Integer tokenEstimate = item.getTokenEstimate();
        if (tokenEstimate != null && tokenEstimate > 0) {
            return tokenEstimate;
        }
        String source = buildWindowDialogContext(List.of(item));
        return estimateTokens(source);
    }

    private int estimateTokens(String text) {
        int length = safe(text).length();
        if (length <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(length / 4.0));
    }

    private List<String> deduplicateTables(List<String> tables) {
        if (tables == null || tables.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String table : tables) {
            String normalized = normalizeRelatedTableName(table);
            if (!normalized.isBlank()) {
                unique.add(normalized);
            }
        }
        return new ArrayList<>(unique);
    }

    private String normalizeRelatedTableName(String tableName) {
        String normalized = safe(tableName).replace("`", "").replace("\"", "");
        if (normalized.contains(".")) {
            String[] segments = normalized.split("\\.");
            normalized = safe(segments[segments.length - 1]);
        }
        return normalized;
    }

    private String resolveRequestedModelId(AiGenerateSqlReq req) {
        String modelId = safe(req.getModelId());
        if (!modelId.isBlank()) {
            return modelId;
        }
        return safe(req.getModelName());
    }

    private String safe(String input) {
        return Objects.toString(input, "").trim();
    }

    public record ConversationGenerationContext(String promptContext,
                                               List<String> relatedTables,
                                               String retrievalInputForPrompt) {
    }

    private record ConversationMemorySnapshot(String windowSummary,
                                              String slidingSummary,
                                              String windowStructuredContext,
                                              String windowDialogContext) {
    }

    private record ConversationMemoryPolicy(int windowSize,
                                            int windowTokens,
                                            double autoCompressRatio,
                                            int compressTriggerTokens) {
    }
}
