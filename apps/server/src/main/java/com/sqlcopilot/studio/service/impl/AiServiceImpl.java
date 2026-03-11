package com.sqlcopilot.studio.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcopilot.studio.dto.ai.*;
import com.sqlcopilot.studio.dto.schema.*;
import com.sqlcopilot.studio.dto.sql.QueryRowVO;
import com.sqlcopilot.studio.entity.ConnectionEntity;
import com.sqlcopilot.studio.service.AiConfigService;
import com.sqlcopilot.studio.service.AiService;
import com.sqlcopilot.studio.service.ConnectionService;
import com.sqlcopilot.studio.service.SchemaService;
import com.sqlcopilot.studio.service.llm.*;
import com.sqlcopilot.studio.service.rag.RagRetrievalService;
import com.sqlcopilot.studio.service.rag.model.RagPromptContext;
import com.sqlcopilot.studio.service.stream.AiStreamObserver;
import com.sqlcopilot.studio.util.BusinessException;
import com.sqlcopilot.studio.util.ResultSetConverter;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AiServiceImpl implements AiService {

    private static final Pattern SQL_FENCE_PATTERN = Pattern.compile("(?is)```(?:sql)?\\s*(.*?)```");
    private static final Pattern CTE_NAME_PATTERN = Pattern.compile("(?is)(?:^|,|\\s)([a-zA-Z_][a-zA-Z0-9_]*)\\s+as\\s*\\(");
    private static final Pattern SQL_KEYWORD_PATTERN = Pattern.compile("(?is)\\b(select|with|update|delete|insert)\\b");
    private static final Pattern AGGREGATE_FUNCTION_PATTERN = Pattern.compile("(?i)\\b(count|sum|avg|min|max)\\s*\\(");
    private static final Pattern GROUP_BY_PATTERN = Pattern.compile("(?i)\\bgroup\\s+by\\b");
    private static final Pattern ORDER_BY_PATTERN = Pattern.compile("(?i)\\border\\s+by\\b");
    private static final Pattern WHERE_PATTERN = Pattern.compile("(?i)\\bwhere\\b");
    private static final Pattern JOIN_PATTERN = Pattern.compile("(?i)\\bjoin\\b");
    private static final int RELATED_TABLE_META_LIMIT = 8;
    private static final int RELATED_INDEX_COLUMN_LIMIT = 12;
    private static final int ANALYZE_EXPLAIN_PLAN_ROW_LIMIT = 200;
    private static final int ANALYZE_EXPLAIN_PLAN_TEXT_LIMIT = 6000;
    private static final double AUTO_INTENT_MIN_CONFIDENCE = 0.70D;
    private static final int MEMORY_SUMMARY_LIMIT = 8;
    private static final int GLOBAL_HISTORY_RECALL_LIMIT = 10;
    private static final int SESSION_HISTORY_RECALL_LIMIT = 8;
    private static final int SQL_UNDERSTAND_TABLE_LIMIT = 8;
    private static final Set<String> SYSTEM_SCHEMA_NAMES = Set.of(
        "information_schema",
        "performance_schema",
        "mysql",
        "sys",
        "pg_catalog",
        "pg_toast",
        "sqlite_schema",
        "sqlite_master",
        "sqlite_temp_schema",
        "sqlite_temp_master",
        "system"
    );
    private static final ThreadLocal<AiStreamObserver> STREAM_OBSERVER = new ThreadLocal<>();
    private static final ThreadLocal<String> STREAM_ACTION_TYPE = new ThreadLocal<>();
    private static final String AUTO_INTENT_CLARIFY_CONTENT =
        "未能准确识别你的需求，请将需求描述得更清晰（例如：生成SQL/解释SQL/分析SQL/生成图表，也可以补充关键表、字段与筛选条件）。";
    private static final String INTENT_CLASSIFY_LIGHT_SYSTEM_PROMPT = """
        你是数据库助手的轻量意图预判器。输入是“用户输入 + 最近几轮对话摘要”。
        请输出严格 JSON，不要输出任何额外文本：
        {
          "intentType": "GENERATE_SQL|EXPLAIN_SQL|ANALYZE_SQL|GENERATE_CHART",
          "confidence": 0.00,
          "reason": "中文简述",
          "retrieval": {
            "sessionTopK": 4,
            "globalTopK": 6,
            "query": "用于历史检索的短查询语句",
            "focusTables": ["table_a","table_b"]
          }
        }
        规则：
        1) retrieval.sessionTopK 范围 1~8，globalTopK 范围 1~10；
        2) query 必须是简短中文语义检索词，不要写 SQL；
        3) focusTables 仅在明显出现表名时填写。
        """;
    private static final String INTENT_CLASSIFY_FINAL_SYSTEM_PROMPT = """
        你是数据库助手的最终意图识别器。输入包含用户输入、最近几轮对话、历史检索结果。
        请输出严格 JSON，不要输出任何额外文本：
        {
          "intentType": "GENERATE_SQL|EXPLAIN_SQL|ANALYZE_SQL|GENERATE_CHART",
          "confidence": 0.00,
          "reason": "中文简述判断依据"
        }
        """;
    private static final String SQL_EXTRACT_SYSTEM_PROMPT = """
        你是 SQL 抽取器。请从输入文本中识别 SQL 并输出严格 JSON，不要输出任何额外文本。
        输出格式固定：
        {
          "has_sql": true,
          "sql_list": ["SELECT ..."]
        }
        规则：
        1) 如果没有 SQL，返回 {"has_sql":false,"sql_list":[]}；
        2) sql_list 必须保留原文 SQL，不做改写、补全、修复、格式化；
        3) 支持多条 SQL 时按出现顺序返回。
        """;
    private static final String OPENAI_SYSTEM_PROMPT = """
        你是数据库 SQL 专家。基于提供的上下文生成 SQL。仅返回可执行 SQL，不要输出解释。
        约束：
        1）重要!!如果存在样例SQL，则优先参考样例SQL
        2) SQL 必须可执行，不要使用 markdown 代码块。
        """;
    private static final String GENERATE_CHART_SYSTEM_PROMPT = """
        你是数据库图表方案助手。请基于用户需求和数据库上下文，输出严格 JSON，不要输出任何额外文本。
        JSON 格式：
        {
          "sqlText": "可执行 SQL",
          "chartConfig": {
            "chartType": "LINE|BAR|PIE|SCATTER|TREND",
            "xField": "x轴字段(折线/柱状/散点/趋势必填)",
            "yFields": ["y轴字段1","y轴字段2"],
            "categoryField": "饼图分类字段",
            "valueField": "饼图数值字段",
            "sortField": "排序字段",
            "sortDirection": "NONE|ASC|DESC",
            "title": "图表标题",
            "description": "图表说明"
          },
          "configSummary": "配置摘要"
        }
        约束：
        1）重要!!如果存在样例SQL，则优先参考样例SQL
        2) chartType=LINE/BAR/TREND 时必须提供 xField + yFields(至少1项)；
        3) chartType=PIE 时必须提供 categoryField + valueField；
        4) chartType=SCATTER 时必须提供 xField + yFields(仅1项)；
        5) SQL 必须可执行，不要使用 markdown 代码块。
        """;
    private static final String EXPLAIN_SQL_SYSTEM_PROMPT = """
        你是数据库讲解助手。请用中文解释 SQL 的业务含义。
        要求：
        1) 用自然语言解释查询目标、筛选条件、关联关系、聚合与排序逻辑；
        2) 不要输出 EXPLAIN 执行计划，不要给出数据库命令；
        3) 语言简洁，必要时分点说明；
        4) 可以指出潜在风险点，但不要生成改写 SQL。
        """;
    private static final String ANALYZE_SQL_SYSTEM_PROMPT = """
        你是数据库审查助手。请基于给定数据库元数据分析 SQL 的合理性。
        要求：
        1) 重点检查：是否命中索引、谓词/Join 条件是否合理、是否可能全表扫描、是否存在歧义或高风险写法；
        2) 输出格式固定为“结论、问题、优化建议”三部分；
        3) 不执行 EXPLAIN，不要编造不存在的表结构；
        4) 如果上下文不足要明确指出不确定项。
        """;
    private static final String REPAIR_SQL_SYSTEM_PROMPT = """
        你是一个 SQL 修复助手。
        要求：
        1) 请根据提供的执行错误信息和数据库元数据上下文，修复失败的 SQL。
        2) 输出必须且只能是一个 JSON 对象，包含以下字段：
            - errorExplanation：使用中文简要说明 SQL 为什么失败，以及做了哪些修改
            - repairedSql：可执行的修复后 SQL
        3) 不要输出 Markdown、代码块或任何额外文本。
        """;
    private static final String ER_RELATION_INFER_SYSTEM_PROMPT = """
        你是一个数据库关系推断助手。
        基于已选择的表元数据以及已知的外键关系，推断可能存在的额外表关系。
        输出必须是严格的 JSON 格式，不要输出 markdown，也不要输出任何额外文本。
        JSON 格式如下：
        {
            "relations": [
                {
                    "sourceTable": "orders",
                    "sourceColumn": "customer_id",
                    "targetTable": "customers",
                    "targetColumn": "id",
                    "relationDirection": "SOURCE_TO_TARGET",
                    "confidence": 0.82,
                    "reason": "列命名和语义匹配"
                }
            ]
        }
        规则：
        1）只推断 已选择表之间 的关系。
        2）confidence 的取值范围必须在 0 到 1 之间。
        3）不要返回与已提供外键 完全重复的关系对。
        4）relationDirection 取值必须是 SOURCE_TO_TARGET / TARGET_TO_SOURCE / BIDIRECTIONAL 之一。
        5）如果两张表存在多条不同起始字段的关系，需要分别返回多条记录，不要合并成一条。
        """;
    private static final Logger log = LoggerFactory.getLogger(AiServiceImpl.class);

    private final SchemaService schemaService;
    private final AiConfigService aiConfigService;
    private final ConnectionService connectionService;
    private final RagRetrievalService ragRetrievalService;
    private final ObjectMapper objectMapper;
    private final LlmGatewayService llmGatewayService;
    private final AiConversationContextManager conversationContextManager;

    public AiServiceImpl(SchemaService schemaService,
                         AiConfigService aiConfigService,
                         ConnectionService connectionService,
                         RagRetrievalService ragRetrievalService,
                         ObjectMapper objectMapper,
                         LlmGatewayService llmGatewayService,
                         AiConversationContextManager conversationContextManager) {
        this.schemaService = schemaService;
        this.aiConfigService = aiConfigService;
        this.connectionService = connectionService;
        this.ragRetrievalService = ragRetrievalService;
        this.objectMapper = objectMapper;
        this.llmGatewayService = llmGatewayService;
        this.conversationContextManager = conversationContextManager;
    }

    @Override
    public AiAutoQueryVO autoQuery(AiGenerateSqlReq req) {
        return autoQuery(req, currentStreamObserver());
    }

    @Override
    public AiAutoQueryVO autoQuery(AiGenerateSqlReq req, AiStreamObserver observer) {
        return executeWithStreamObserver(observer, "auto", () -> doAutoQuery(req));
    }

    private AiAutoQueryVO doAutoQuery(AiGenerateSqlReq req) {
        conversationContextManager.enterRequestScope();
        try {
        long startAt = System.currentTimeMillis();
        StepTimer timer = new StepTimer();
        log.info(
                "[AI-AUTO-REQ] connectionId={}, sessionId={}, databaseName={}, modelName={}, promptLength={}",
                req.getConnectionId(),
                safe(req.getSessionId()),
                safe(req.getDatabaseName()),
                safe(req.getModelName()),
                safe(req.getPrompt()).length()
        );
        boolean detailOutputEnabled = resolveDetailOutputEnabled(req);
        List<AiTraceStageVO> traceStages = detailOutputEnabled ? new ArrayList<>() : List.of();

        IntentResult intentResult;
        try {
            intentResult = identifyIntent(req);
            timer.mark("identify_intent");
        } catch (BusinessException ex) {
            timer.mark("identify_intent_failed");
            AiAutoQueryVO clarifyVo = buildIntentClarifyResponse(ex);
            if (detailOutputEnabled) {
                List<AiTraceStageVO> failedStages = new ArrayList<>(List.of(buildTraceStage(
                        "identify_intent",
                        "意图识别",
                        "pipeline",
                        "failed",
                        0L,
                        List.of(buildTraceField("prompt", "userPrompt", req.getPrompt())),
                        List.of(buildTraceField("error", "error", ex.getMessage())),
                        null
                    )));
                failedStages.forEach(stage -> addTraceStage(req.getSessionId(), "auto", traceStages, stage));
                publishTraceSnapshot(req.getSessionId(), "auto", traceStages, System.currentTimeMillis() - startAt);
                clarifyVo.setTrace(buildTrace(traceStages, System.currentTimeMillis() - startAt));
            }
            log.warn(
                    "[AI-AUTO-INTENT-FALLBACK] connectionId={}, sessionId={}, databaseName={}, modelName={}, message={}",
                    req.getConnectionId(),
                    safe(req.getSessionId()),
                    safe(req.getDatabaseName()),
                    safe(req.getModelName()),
                    safe(ex.getMessage())
            );
            log.info(
                    "[AI-AUTO-RESP] connectionId={}, sessionId={}, databaseName={}, modelName={}, intentType={}, intentConfidence={}, fallbackUsed={}, elapsedMs={}",
                    req.getConnectionId(),
                    safe(req.getSessionId()),
                    safe(req.getDatabaseName()),
                    safe(req.getModelName()),
                    clarifyVo.getIntentType(),
                    clarifyVo.getIntentConfidence(),
                    Boolean.TRUE.equals(clarifyVo.getFallbackUsed()),
                    System.currentTimeMillis() - startAt
            );
            log.info(
                    "[AI-AUTO-TIMING] connectionId={}, sessionId={}, databaseName={}, modelName={}, intentType={}, steps={}, totalMs={}",
                    req.getConnectionId(),
                    safe(req.getSessionId()),
                    safe(req.getDatabaseName()),
                    safe(req.getModelName()),
                    "IDENTIFY_INTENT_FAILED",
                    timer.stepsSummary(),
                    timer.totalElapsedMs()
            );
            publishFinalResult(req.getSessionId(), "auto", buildFinalResult("auto", clarifyVo));
            return clarifyVo;
        }
        IntentType intentType = intentResult.intentType();
        boolean memoryEnabled = conversationContextManager.isMemoryEnabled(req, aiConfigService.getConfig());
        boolean hasSqlSnippet = hasSqlSnippetInPrompt(req.getPrompt());
        String recentDialogContextForSqlFallback = "";
        String conversationSqlFallback = "";
        if (!hasSqlSnippet && memoryEnabled
            && (intentType == IntentType.EXPLAIN_SQL || intentType == IntentType.ANALYZE_SQL)) {
            recentDialogContextForSqlFallback = conversationContextManager.buildIntentRecentDialogContext(req, MEMORY_SUMMARY_LIMIT);
            conversationSqlFallback = extractLatestSqlFromRecentDialogContext(recentDialogContextForSqlFallback);
        }
        timer.mark("detect_sql_snippet");

        AiAutoQueryVO vo = new AiAutoQueryVO();
        vo.setIntentType(intentType.name());
        vo.setIntentLabel(intentType.label());
        vo.setIntentConfidence(intentResult.confidence());
        AiTraceVO delegatedTrace = null;
        if (detailOutputEnabled) {
            addTraceStage(req.getSessionId(), "auto", traceStages, buildTraceStage(
                "identify_intent",
                "意图识别",
                "pipeline",
                "success",
                0L,
                List.of(buildTraceField("prompt", "userPrompt", req.getPrompt())),
                List.of(
                    buildTraceField("intentType", "intentType", intentType.name()),
                    buildTraceField("confidence", "confidence", intentResult.confidence()),
                    buildTraceField("reason", "reason", intentResult.reason())
                ),
                null
            ));
        }
        emitIntentResolved(req.getSessionId(), "auto", intentType.name(), intentType.label(), intentResult.confidence(), intentResult.reason());

        String baseReasoning = "意图识别: " + intentType.name()
                + "（置信度 " + String.format(Locale.ROOT, "%.2f", intentResult.confidence()) + "）";
        if (!safe(intentResult.reason()).isBlank()) {
            baseReasoning += "，依据：" + safe(intentResult.reason());
        }

        if (intentType == IntentType.GENERATE_SQL) {
            AiGenerateSqlVO generated = generateSql(req);
            timer.mark("route_generate_sql");
            vo.setSqlText(generated.getSqlText());
            vo.setFallbackUsed(Boolean.TRUE.equals(generated.getFallbackUsed()));
            vo.setReasoning(joinReasoning(baseReasoning, generated.getReasoning()));
            vo.setTotalTokens(generated.getTotalTokens());
            delegatedTrace = generated.getTrace();
        } else if (intentType == IntentType.EXPLAIN_SQL) {
            if (!hasSqlSnippet && conversationSqlFallback.isBlank()) {
                throw new BusinessException(400, "自动识别为“解释 SQL”时，提示词中必须包含 SQL 片段");
            }
            AiGenerateSqlReq explainReq = !hasSqlSnippet && !conversationSqlFallback.isBlank()
                ? appendSqlFallbackToPrompt(req, conversationSqlFallback)
                : req;
            AiTextResponseVO explained = explainSql(explainReq);
            timer.mark("route_explain_sql");
            vo.setContent(explained.getContent());
            vo.setFallbackUsed(Boolean.TRUE.equals(explained.getFallbackUsed()));
            vo.setReasoning(joinReasoning(baseReasoning, explained.getReasoning()));
            vo.setTotalTokens(explained.getTotalTokens());
            delegatedTrace = explained.getTrace();
        } else if (intentType == IntentType.ANALYZE_SQL) {
            if (!hasSqlSnippet && conversationSqlFallback.isBlank()) {
                throw new BusinessException(400, "自动识别为“分析 SQL”时，提示词中必须包含 SQL 片段");
            }
            AiGenerateSqlReq analyzeReq = !hasSqlSnippet && !conversationSqlFallback.isBlank()
                ? appendSqlFallbackToPrompt(req, conversationSqlFallback)
                : req;
            AiTextResponseVO analyzed = analyzeSql(analyzeReq);
            timer.mark("route_analyze_sql");
            vo.setContent(analyzed.getContent());
            vo.setFallbackUsed(Boolean.TRUE.equals(analyzed.getFallbackUsed()));
            vo.setReasoning(joinReasoning(baseReasoning, analyzed.getReasoning()));
            vo.setTotalTokens(analyzed.getTotalTokens());
            delegatedTrace = analyzed.getTrace();
        } else {
            AiGenerateChartVO chart = generateChart(req);
            timer.mark("route_generate_chart");
            vo.setSqlText(chart.getSqlText());
            vo.setChartConfig(chart.getChartConfig());
            vo.setConfigSummary(chart.getConfigSummary());
            vo.setFallbackUsed(Boolean.TRUE.equals(chart.getFallbackUsed()));
            vo.setReasoning(joinReasoning(baseReasoning, chart.getReasoning()));
            vo.setTotalTokens(chart.getTotalTokens());
            delegatedTrace = chart.getTrace();
        }

        if (detailOutputEnabled) {
            mergeTraceStages(traceStages, delegatedTrace);
            publishTraceSnapshot(req.getSessionId(), "auto", traceStages, System.currentTimeMillis() - startAt);
            vo.setTrace(buildTrace(traceStages, System.currentTimeMillis() - startAt));
        }

        log.info(
                "[AI-AUTO-RESP] connectionId={}, sessionId={}, databaseName={}, modelName={}, intentType={}, intentConfidence={}, fallbackUsed={}, elapsedMs={}",
                req.getConnectionId(),
                safe(req.getSessionId()),
                safe(req.getDatabaseName()),
                safe(req.getModelName()),
                intentType.name(),
                intentResult.confidence(),
                Boolean.TRUE.equals(vo.getFallbackUsed()),
                System.currentTimeMillis() - startAt
        );
        log.info(
                "[AI-AUTO-TIMING] connectionId={}, sessionId={}, databaseName={}, modelName={}, intentType={}, steps={}, totalMs={}",
                req.getConnectionId(),
                safe(req.getSessionId()),
                safe(req.getDatabaseName()),
                safe(req.getModelName()),
                intentType.name(),
                timer.stepsSummary(),
                timer.totalElapsedMs()
        );
        publishFinalResult(req.getSessionId(), "auto", buildFinalResult("auto", vo));
        return vo;
        } finally {
            conversationContextManager.exitRequestScope();
        }
    }

    @Override
    public AiGenerateSqlVO generateSql(AiGenerateSqlReq req) {
        return generateSql(req, currentStreamObserver());
    }

    @Override
    public AiGenerateSqlVO generateSql(AiGenerateSqlReq req, AiStreamObserver observer) {
        return executeWithStreamObserver(observer, "generate", () -> doGenerateSql(req));
    }

    private AiGenerateSqlVO doGenerateSql(AiGenerateSqlReq req) {
        conversationContextManager.enterRequestScope();
        try {
        long startAt = System.currentTimeMillis();
        StepTimer timer = new StepTimer();
        log.info(
            "[AI-GENERATE-REQ] connectionId={}, sessionId={}, databaseName={}, modelName={}, promptLength={}",
            req.getConnectionId(),
            safe(req.getSessionId()),
            safe(req.getDatabaseName()),
            safe(req.getModelName()),
            safe(req.getPrompt()).length()
        );
        boolean detailOutputEnabled = resolveDetailOutputEnabled(req);
        List<AiTraceStageVO> traceStages = detailOutputEnabled ? new ArrayList<>() : List.of();
        ParsedIntentResponse retrievalIntent = identifyRetrievalIntentForSql(req);
        timer.mark("identify_retrieval_intent");
        String retrievalPromptHint = buildIntentAwareRetrievalHint(req, retrievalIntent);
        String retrievalInput = conversationContextManager.buildRetrievalInputForRag(req, retrievalPromptHint);
        timer.mark("build_retrieval_input");
        if (detailOutputEnabled) {
            IntentRetrievalParams params = retrievalIntent == null || retrievalIntent.retrievalParams() == null
                ? IntentRetrievalParams.defaultValue()
                : retrievalIntent.retrievalParams();
            addTraceStage(req.getSessionId(), "generate", traceStages, buildTraceStage(
                "identify_retrieval_intent",
                "检索意图识别",
                "pipeline",
                "success",
                0L,
                List.of(buildTraceField("prompt", "userPrompt", req.getPrompt())),
                List.of(
                    buildTraceField("intentType", "intentType", retrievalIntent != null && retrievalIntent.intentType() != null ? retrievalIntent.intentType().name() : ""),
                    buildTraceField("confidence", "confidence", retrievalIntent == null ? 0D : normalizeIntentConfidence(retrievalIntent.confidence())),
                    buildTraceField("reason", "reason", retrievalIntent == null ? "" : safe(retrievalIntent.reason())),
                    buildTraceField("query", "query", safe(params.query())),
                    buildTraceField("focusTables", "focusTables", params.focusTables() == null ? List.of() : params.focusTables())
                ),
                null
            ));
        }
        long ragStageStart = System.currentTimeMillis();
        RagPromptContext ragPromptContext = ragRetrievalService.retrievePromptContext(
            req.getConnectionId(),
            req.getDatabaseName(),
            retrievalInput
        );
        timer.mark("rag_retrieve");
        if (detailOutputEnabled) {
            addTraceStage(req.getSessionId(), "generate", traceStages, buildRagTraceStage("rag_retrieve", "向量库召回", retrievalInput, ragPromptContext, System.currentTimeMillis() - ragStageStart));
        }
        long contextStageStart = System.currentTimeMillis();
        // 关键步骤：生成链路统一向上下文管理器要最终上下文，避免主流程再次拼接历史和记忆。
        AiConversationContextManager.ConversationGenerationContext generationContext =
            conversationContextManager.buildGenerationContext(req, ragPromptContext, retrievalPromptHint);
        timer.mark("build_generation_context");
        if (detailOutputEnabled) {
            addTraceStage(req.getSessionId(), "generate", traceStages, buildGenerationContextTraceStage(generationContext, System.currentTimeMillis() - contextStageStart));
        }

        String reasoning;
        String generatedSql;
        OpenAiTextClient.TokenUsage providerTokenUsage = null;
        LlmGatewayResult gatewayResult = null;
        boolean fallbackUsed = false;
        try {
            ProviderResult result = generateByConfiguredProvider(req, generationContext);
            generatedSql = safe(result.sqlText());
            reasoning = safe(result.reasoning());
            providerTokenUsage = result.usage();
            gatewayResult = result.gatewayResult();
            timer.mark("provider_generate_sql");
            if (detailOutputEnabled) {
                addTraceStage(req.getSessionId(), "generate", traceStages, buildTraceStage(
                    "llm_generate_sql",
                    "SQL生成",
                    "llm",
                    "success",
                    0L,
                    List.of(
                        buildTraceField("modelId", "modelId", resolveRequestedModelId(req)),
                        buildTraceField("prompt", "userPrompt", req.getPrompt()),
                        buildTraceField("promptContext", "promptContext", generationContext.promptContext())
                    ),
                    List.of(
                        buildTraceField("sqlText", "sqlText", generatedSql),
                        buildTraceField("reasoning", "reasoning", reasoning)
                    ),
                    buildTraceLlmCall(gatewayResult)
                ));
            }
        } catch (Exception ex) {
            generatedSql = fallbackOutputText("模型调用失败: " + safe(ex.getMessage()));
            reasoning = "AI 配置调用失败，已返回说明内容。原因: " + safe(ex.getMessage());
            fallbackUsed = true;
            timer.mark("provider_generate_sql_failed");
            log.warn(
                "[AI-GENERATE-PROVIDER-FAILED] connectionId={}, sessionId={}, databaseName={}, modelName={}, reason={}",
                req.getConnectionId(),
                safe(req.getSessionId()),
                safe(req.getDatabaseName()),
                safe(req.getModelName()),
                safe(ex.getMessage())
            );
            if (detailOutputEnabled) {
                addTraceStage(req.getSessionId(), "generate", traceStages, buildTraceStage(
                    "llm_generate_sql",
                    "SQL生成",
                    "llm",
                    "failed",
                    0L,
                    List.of(
                        buildTraceField("modelId", "modelId", resolveRequestedModelId(req)),
                        buildTraceField("prompt", "userPrompt", req.getPrompt()),
                        buildTraceField("promptContext", "promptContext", generationContext.promptContext())
                    ),
                    List.of(
                        buildTraceField("error", "error", ex.getMessage()),
                        buildTraceField("fallback", "fallback", generatedSql)
                    ),
                    null
                ));
            }
        }

        if (!looksLikeSql(generatedSql)) {
            fallbackUsed = true;
            generatedSql = fallbackOutputText(generatedSql);
            timer.mark("extract_sql_or_fallback");
            if (!reasoning.isBlank()) {
                reasoning = reasoning + "\n";
            }
            reasoning = reasoning + "模型未返回可识别 SQL，已返回说明内容。";
        } else {
            AstValidationResult astResult = validateByAst(req, generatedSql);
            timer.mark("ast_validate");
            if (!astResult.valid()) {
                fallbackUsed = true;
                generatedSql = fallbackOutputText("SQL 结构校验未通过: " + astResult.message() + "\n模型输出:\n" + generatedSql);
                if (!reasoning.isBlank()) {
                    reasoning = reasoning + "\n";
                }
                reasoning = reasoning + "AST 校验未通过，已返回说明内容。原因: " + astResult.message();
            } else {
                generatedSql = astResult.sqlText();
                if (!reasoning.isBlank()) {
                    reasoning = reasoning + "\n";
                }
                reasoning = reasoning + astResult.message();
            }
        }

        if (detailOutputEnabled) {
            addTraceStage(req.getSessionId(), "generate", traceStages, buildTraceStage(
                "sql_validate",
                "SQL校验",
                "pipeline",
                fallbackUsed ? "fallback" : "success",
                0L,
                List.of(buildTraceField("finalSqlText", "finalSqlText", generatedSql)),
                List.of(
                    buildTraceField("fallbackUsed", "fallbackUsed", fallbackUsed),
                    buildTraceField("reasoning", "reasoning", reasoning)
                ),
                null
            ));
        }

        AiGenerateSqlVO vo = new AiGenerateSqlVO();
        vo.setSqlText(generatedSql);
        vo.setReasoning(reasoning);
        vo.setFallbackUsed(fallbackUsed);
        TokenUsageStats tokenUsage = resolveTokenUsage(
            providerTokenUsage,
            req.getPrompt() + "\n" + generationContext.promptContext(),
            generatedSql + "\n" + reasoning
        );
        vo.setPromptTokens(tokenUsage.promptTokens());
        vo.setCompletionTokens(tokenUsage.completionTokens());
        vo.setTotalTokens(tokenUsage.totalTokens());
        if (detailOutputEnabled) {
            publishTraceSnapshot(req.getSessionId(), "generate", traceStages, System.currentTimeMillis() - startAt);
            vo.setTrace(buildTrace(traceStages, System.currentTimeMillis() - startAt));
        }
        timer.mark("assemble_response");
        log.info(
            "[AI-GENERATE-RESP] connectionId={}, sessionId={}, databaseName={}, modelName={}, ragHit={}, relatedTableCount={}, contextLength={}, sqlLength={}, fallbackUsed={}, elapsedMs={}",
            req.getConnectionId(),
            safe(req.getSessionId()),
            safe(req.getDatabaseName()),
            safe(req.getModelName()),
            Boolean.TRUE.equals(ragPromptContext.getHit()),
            generationContext.relatedTables().size(),
            safe(generationContext.promptContext()).length(),
            safe(generatedSql).length(),
            fallbackUsed,
            System.currentTimeMillis() - startAt
        );
        log.info(
            "[AI-GENERATE-TIMING] connectionId={}, sessionId={}, databaseName={}, modelName={}, steps={}, totalMs={}",
            req.getConnectionId(),
            safe(req.getSessionId()),
            safe(req.getDatabaseName()),
            safe(req.getModelName()),
            timer.stepsSummary(),
            timer.totalElapsedMs()
        );
        publishFinalResult(req.getSessionId(), "generate", buildFinalResult("generate", vo));
        return vo;
        } finally {
            conversationContextManager.exitRequestScope();
        }
    }

    @Override
    public AiGenerateChartVO generateChart(AiGenerateSqlReq req) {
        return generateChart(req, currentStreamObserver());
    }

    @Override
    public AiGenerateChartVO generateChart(AiGenerateSqlReq req, AiStreamObserver observer) {
        return executeWithStreamObserver(observer, "generate-chart", () -> doGenerateChart(req));
    }

    private AiGenerateChartVO doGenerateChart(AiGenerateSqlReq req) {
        conversationContextManager.enterRequestScope();
        try {
        long startAt = System.currentTimeMillis();
        StepTimer timer = new StepTimer();
        log.info(
            "[AI-GENERATE-CHART-REQ] connectionId={}, sessionId={}, databaseName={}, modelName={}, promptLength={}",
            req.getConnectionId(),
            safe(req.getSessionId()),
            safe(req.getDatabaseName()),
            safe(req.getModelName()),
            safe(req.getPrompt()).length()
        );

        boolean detailOutputEnabled = resolveDetailOutputEnabled(req);
        List<AiTraceStageVO> traceStages = detailOutputEnabled ? new ArrayList<>() : List.of();
        String retrievalPromptHint = conversationContextManager.buildRetrievalInput(req.getPrompt());
        String retrievalInput = conversationContextManager.buildRetrievalInputForRag(req);
        timer.mark("build_retrieval_input");
        long ragStageStart = System.currentTimeMillis();
        RagPromptContext ragPromptContext = ragRetrievalService.retrievePromptContext(
            req.getConnectionId(),
            req.getDatabaseName(),
            retrievalInput
        );
        timer.mark("rag_retrieve");
        if (detailOutputEnabled) {
            addTraceStage(req.getSessionId(), "generate-chart", traceStages, buildRagTraceStage("rag_retrieve", "向量库召回", retrievalInput, ragPromptContext, System.currentTimeMillis() - ragStageStart));
        }
        long contextStageStart = System.currentTimeMillis();
        // 关键步骤：图表链路与 SQL 生成共用同一套上下文装配逻辑，避免后续优化分叉。
        AiConversationContextManager.ConversationGenerationContext generationContext =
            conversationContextManager.buildGenerationContext(req, ragPromptContext, retrievalPromptHint);
        timer.mark("build_generation_context");
        if (detailOutputEnabled) {
            addTraceStage(req.getSessionId(), "generate-chart", traceStages, buildGenerationContextTraceStage(generationContext, System.currentTimeMillis() - contextStageStart));
        }

        String reasoning;
        String rawContent;
        OpenAiTextClient.TokenUsage providerTokenUsage = null;
        LlmGatewayResult gatewayResult = null;
        boolean fallbackUsed = false;
        try {
            TextProviderResult result = generateTextByConfiguredProvider(
                req,
                generationContext,
                GENERATE_CHART_SYSTEM_PROMPT,
                "图表方案生成"
            );
            rawContent = safe(result.content());
            reasoning = safe(result.reasoning());
            providerTokenUsage = result.usage();
            gatewayResult = result.gatewayResult();
            timer.mark("provider_generate_chart");
            if (detailOutputEnabled) {
                addTraceStage(req.getSessionId(), "generate-chart", traceStages, buildTraceStage(
                    "llm_generate_chart",
                    "图表生成",
                    "llm",
                    "success",
                    0L,
                    List.of(
                        buildTraceField("modelId", "modelId", resolveRequestedModelId(req)),
                        buildTraceField("prompt", "userPrompt", req.getPrompt()),
                        buildTraceField("promptContext", "promptContext", generationContext.promptContext())
                    ),
                    List.of(
                        buildTraceField("rawContent", "rawContent", rawContent),
                        buildTraceField("reasoning", "reasoning", reasoning)
                    ),
                    buildTraceLlmCall(gatewayResult)
                ));
            }
        } catch (Exception ex) {
            rawContent = "未能生成图表方案：" + safe(ex.getMessage());
            reasoning = "AI 配置调用失败，已返回说明内容。原因: " + safe(ex.getMessage());
            fallbackUsed = true;
            timer.mark("provider_generate_chart_failed");
            log.warn(
                "[AI-GENERATE-CHART-PROVIDER-FAILED] connectionId={}, sessionId={}, databaseName={}, modelName={}, reason={}",
                req.getConnectionId(),
                safe(req.getSessionId()),
                safe(req.getDatabaseName()),
                safe(req.getModelName()),
                safe(ex.getMessage())
            );
            if (detailOutputEnabled) {
                addTraceStage(req.getSessionId(), "generate-chart", traceStages, buildTraceStage(
                    "llm_generate_chart",
                    "图表生成",
                    "llm",
                    "failed",
                    0L,
                    List.of(
                        buildTraceField("modelId", "modelId", resolveRequestedModelId(req)),
                        buildTraceField("prompt", "userPrompt", req.getPrompt()),
                        buildTraceField("promptContext", "promptContext", generationContext.promptContext())
                    ),
                    List.of(
                        buildTraceField("error", "error", ex.getMessage()),
                        buildTraceField("fallback", "fallback", rawContent)
                    ),
                    null
                ));
            }
        }

        ParsedChartResponse parsed = parseChartResponse(rawContent);
        timer.mark("parse_chart_response");
        String sqlText = safe(parsed.sqlText());
        ChartConfigVO chartConfig = parsed.chartConfig();
        String configSummary = safe(parsed.configSummary());
        if (!parsed.parsed()) {
            fallbackUsed = true;
            if (!reasoning.isBlank()) {
                reasoning += "\n";
            }
            reasoning += "模型返回非结构化内容，已降级解析。";
        }

        if (sqlText.isBlank()) {
            sqlText = extractSql(rawContent);
            timer.mark("extract_sql_from_output");
        }
        if (sqlText.isBlank()) {
            fallbackUsed = true;
            sqlText = fallbackOutputText(rawContent);
            if (!reasoning.isBlank()) {
                reasoning += "\n";
            }
            reasoning += "未识别到可执行 SQL，已返回说明内容。";
        } else {
            AstValidationResult astResult = validateByAst(req, sqlText);
            timer.mark("ast_validate");
            if (!astResult.valid()) {
                fallbackUsed = true;
                sqlText = fallbackOutputText("图表SQL校验未通过: " + astResult.message() + "\n模型输出:\n" + sqlText);
                if (!reasoning.isBlank()) {
                    reasoning += "\n";
                }
                reasoning += "图表SQL AST 校验未通过，已降级返回说明。原因: " + astResult.message();
            } else {
                sqlText = astResult.sqlText();
            }
        }

        if (chartConfig != null) {
            ChartConfigValidationResult validationResult = validateChartConfig(chartConfig);
            timer.mark("validate_chart_config");
            if (!validationResult.valid()) {
                fallbackUsed = true;
                chartConfig = null;
                if (!reasoning.isBlank()) {
                    reasoning += "\n";
                }
                reasoning += "图表配置校验未通过：" + validationResult.message();
                if (configSummary.isBlank()) {
                    configSummary = "未返回可用图表配置，请手动配置后生成图表。";
                }
            }
        } else if (configSummary.isBlank()) {
            configSummary = "未返回可用图表配置，请手动配置后生成图表。";
        }
        if (configSummary.isBlank()) {
            configSummary = buildChartConfigSummary(chartConfig);
        }

        if (detailOutputEnabled) {
            addTraceStage(req.getSessionId(), "generate-chart", traceStages, buildTraceStage(
                "chart_result_validate",
                "图表结果校验",
                "pipeline",
                fallbackUsed ? "fallback" : "success",
                0L,
                List.of(
                    buildTraceField("rawContent", "妯″瀷杈撳嚭", rawContent),
                    buildTraceField("parsedChartConfig", "瑙ｆ瀽閰嶇疆", chartConfig)
                ),
                List.of(
                    buildTraceField("sqlText", "鏈€缁?SQL", sqlText),
                    buildTraceField("configSummary", "閰嶇疆璇存槑", configSummary),
                    buildTraceField("fallbackUsed", "鏄惁闄嶇骇", fallbackUsed)
                ),
                null
            ));
        }

        AiGenerateChartVO vo = new AiGenerateChartVO();
        vo.setSqlText(sqlText);
        vo.setChartConfig(chartConfig);
        vo.setConfigSummary(configSummary);
        vo.setReasoning(reasoning);
        vo.setFallbackUsed(fallbackUsed);
        TokenUsageStats tokenUsage = resolveTokenUsage(
            providerTokenUsage,
            req.getPrompt() + "\n" + generationContext.promptContext(),
            sqlText + "\n" + reasoning + "\n" + configSummary
        );
        vo.setPromptTokens(tokenUsage.promptTokens());
        vo.setCompletionTokens(tokenUsage.completionTokens());
        vo.setTotalTokens(tokenUsage.totalTokens());
        if (detailOutputEnabled) {
            publishTraceSnapshot(req.getSessionId(), "generate-chart", traceStages, System.currentTimeMillis() - startAt);
            vo.setTrace(buildTrace(traceStages, System.currentTimeMillis() - startAt));
        }
        timer.mark("assemble_response");
        log.info(
            "[AI-GENERATE-CHART-RESP] connectionId={}, sessionId={}, databaseName={}, modelName={}, ragHit={}, relatedTableCount={}, sqlLength={}, hasChartConfig={}, fallbackUsed={}, elapsedMs={}",
            req.getConnectionId(),
            safe(req.getSessionId()),
            safe(req.getDatabaseName()),
            safe(req.getModelName()),
            Boolean.TRUE.equals(ragPromptContext.getHit()),
            generationContext.relatedTables().size(),
            safe(sqlText).length(),
            chartConfig != null,
            fallbackUsed,
            System.currentTimeMillis() - startAt
        );
        log.info(
            "[AI-GENERATE-CHART-TIMING] connectionId={}, sessionId={}, databaseName={}, modelName={}, steps={}, totalMs={}",
            req.getConnectionId(),
            safe(req.getSessionId()),
            safe(req.getDatabaseName()),
            safe(req.getModelName()),
            timer.stepsSummary(),
            timer.totalElapsedMs()
        );
        publishFinalResult(req.getSessionId(), "generate-chart", buildFinalResult("generate-chart", vo));
        return vo;
        } finally {
            conversationContextManager.exitRequestScope();
        }
    }

    @Override
    public AiTextResponseVO explainSql(AiGenerateSqlReq req) {
        return explainSql(req, currentStreamObserver());
    }

    @Override
    public AiTextResponseVO explainSql(AiGenerateSqlReq req, AiStreamObserver observer) {
        return executeWithStreamObserver(observer, "explain", () -> {
            conversationContextManager.enterRequestScope();
            try {
                return explainSqlWithPipeline(req);
            } finally {
                conversationContextManager.exitRequestScope();
            }
        });
    }

    @Override
    public AiTextResponseVO analyzeSql(AiGenerateSqlReq req) {
        return analyzeSql(req, currentStreamObserver());
    }

    @Override
    public AiTextResponseVO analyzeSql(AiGenerateSqlReq req, AiStreamObserver observer) {
        return executeWithStreamObserver(observer, "analyze", () -> {
            conversationContextManager.enterRequestScope();
            try {
                return analyzeSqlWithPipeline(req);
            } finally {
                conversationContextManager.exitRequestScope();
            }
        });
    }

    @Override
    public AiRepairVO repairSql(AiRepairReq req) {
        return repairSql(req, currentStreamObserver());
    }

    @Override
    public AiRepairVO repairSql(AiRepairReq req, AiStreamObserver observer) {
        return executeWithStreamObserver(observer, "repair", () -> doRepairSql(req));
    }

    private AiRepairVO doRepairSql(AiRepairReq req) {
        conversationContextManager.enterRequestScope();
        long startAt = System.currentTimeMillis();
        StepTimer timer = new StepTimer();
        String sourceSql = safe(req.getSqlText());
        String errorMessage = safe(req.getErrorMessage());
        log.info(
            "[AI-REPAIR-REQ] connectionId={}, sessionId={}, databaseName={}, modelName={}, sqlLength={}, errorLength={}",
            req.getConnectionId(),
            safe(req.getSessionId()),
            safe(req.getDatabaseName()),
            safe(req.getModelName()),
            sourceSql.length(),
            errorMessage.length()
        );

        boolean detailOutputEnabled = resolveDetailOutputEnabled(req);
        List<AiTraceStageVO> traceStages = detailOutputEnabled ? new ArrayList<>() : List.of();
        AiRepairVO vo = new AiRepairVO();
        try {
            AiGenerateSqlReq providerReq = buildRepairGenerateReq(req, sourceSql, errorMessage);
            timer.mark("build_repair_prompt");
            String retrievalPromptHint = conversationContextManager.buildRetrievalInput(providerReq.getPrompt(), sourceSql + "\n" + errorMessage);
            String retrievalInput = conversationContextManager.buildRetrievalInputForRag(providerReq, sourceSql + "\n" + errorMessage);
            timer.mark("build_retrieval_input");
            RagPromptContext ragPromptContext = ragRetrievalService.retrievePromptContext(
                req.getConnectionId(),
                req.getDatabaseName(),
                retrievalInput
            );
            timer.mark("rag_retrieve");
            if (detailOutputEnabled) {
                addTraceStage(req.getSessionId(), "repair", traceStages, buildRagTraceStage("rag_retrieve", "向量库召回", retrievalInput, ragPromptContext, 0L));
            }
            // 关键步骤：修复链路也复用同一套上下文管理，保证修复提示词与生成提示词看到同样的记忆。
            AiConversationContextManager.ConversationGenerationContext generationContext =
                conversationContextManager.buildGenerationContext(providerReq, ragPromptContext, retrievalPromptHint);
            timer.mark("build_generation_context");
            if (detailOutputEnabled) {
                addTraceStage(req.getSessionId(), "repair", traceStages, buildGenerationContextTraceStage(generationContext, 0L));
            }
            TextProviderResult providerResult = generateTextByConfiguredProvider(
                providerReq,
                generationContext,
                REPAIR_SQL_SYSTEM_PROMPT,
                "SQL 修复"
            );
            timer.mark("provider_repair_sql");
            if (detailOutputEnabled) {
                addTraceStage(req.getSessionId(), "repair", traceStages, buildTraceStage(
                    "llm_repair_sql",
                    "repair_sql",
                    "llm",
                    "success",
                    0L,
                    List.of(
                        buildTraceField("modelId", "modelId", safe(req.getModelId()).isBlank() ? req.getModelName() : req.getModelId()),
                        buildTraceField("sourceSql", "sourceSql", sourceSql),
                        buildTraceField("errorMessage", "errorMessage", errorMessage)
                    ),
                    List.of(buildTraceField("rawContent", "rawContent", providerResult.content())),
                    buildTraceLlmCall(providerResult.gatewayResult())
                ));
            }

            ParsedRepairResult parsed = parseRepairResult(providerResult.content(), sourceSql, errorMessage);
            timer.mark("parse_repair_output");
            boolean fallbackUsed = safe(parsed.repairedSql()).isBlank();
            if (fallbackUsed) {
                ParsedRepairResult fallback = fallbackRepairResult(sourceSql, errorMessage);
                vo.setRepaired(Boolean.FALSE);
                vo.setErrorExplanation(fallback.errorExplanation());
                vo.setRepairedSql(fallback.repairedSql());
                vo.setRepairNote("模型输出未识别到有效 SQL，已使用规则兜底");
            } else {
                vo.setRepaired(Boolean.TRUE);
                vo.setErrorExplanation(parsed.errorExplanation());
                vo.setRepairedSql(parsed.repairedSql());
                vo.setRepairNote("Model returned a repair result.");
            }
            if (detailOutputEnabled) {
                addTraceStage(req.getSessionId(), "repair", traceStages, buildTraceStage(
                    "repair_result",
                    "repair_result",
                    "pipeline",
                    fallbackUsed ? "fallback" : "success",
                    0L,
                    List.of(buildTraceField("rawOutput", "妯″瀷杈撳嚭", providerResult.content())),
                    List.of(
                        buildTraceField("repairedSql", "repairedSql", vo.getRepairedSql()),
                        buildTraceField("errorExplanation", "errorExplanation", vo.getErrorExplanation()),
                        buildTraceField("repairNote", "repairNote", vo.getRepairNote())
                    ),
                    null
                ));
            }
            timer.mark("assemble_response");
            if (detailOutputEnabled) {
                publishTraceSnapshot(req.getSessionId(), "repair", traceStages, System.currentTimeMillis() - startAt);
                vo.setTrace(buildTrace(traceStages, System.currentTimeMillis() - startAt));
            }

            log.info(
                "[AI-REPAIR-RESP] connectionId={}, sessionId={}, databaseName={}, modelName={}, ragHit={}, repairedSqlLength={}, elapsedMs={}, fallbackUsed={}",
                req.getConnectionId(),
                safe(req.getSessionId()),
                safe(req.getDatabaseName()),
                safe(req.getModelName()),
                Boolean.TRUE.equals(ragPromptContext.getHit()),
                safe(vo.getRepairedSql()).length(),
                System.currentTimeMillis() - startAt,
                fallbackUsed
            );
            log.info(
                "[AI-REPAIR-TIMING] connectionId={}, sessionId={}, databaseName={}, modelName={}, steps={}, totalMs={}",
                req.getConnectionId(),
                safe(req.getSessionId()),
                safe(req.getDatabaseName()),
                safe(req.getModelName()),
                timer.stepsSummary(),
                timer.totalElapsedMs()
            );
            publishFinalResult(req.getSessionId(), "repair", buildFinalResult("repair", vo));
            return vo;
        } catch (Exception ex) {
            ParsedRepairResult fallback = fallbackRepairResult(sourceSql, errorMessage);
            vo.setRepaired(Boolean.FALSE);
            vo.setErrorExplanation(fallback.errorExplanation());
            vo.setRepairedSql(fallback.repairedSql());
            vo.setRepairNote("模型修复失败，已使用规则兜底: " + safe(ex.getMessage()));
            if (detailOutputEnabled) {
                addTraceStage(req.getSessionId(), "repair", traceStages, buildTraceStage(
                    "repair_result",
                    "repair_result",
                    "pipeline",
                    "failed",
                    0L,
                    List.of(
                        buildTraceField("sourceSql", "sourceSql", sourceSql),
                        buildTraceField("errorMessage", "errorMessage", errorMessage)
                    ),
                    List.of(
                        buildTraceField("errorExplanation", "errorExplanation", vo.getErrorExplanation()),
                        buildTraceField("repairedSql", "repairedSql", vo.getRepairedSql()),
                        buildTraceField("repairNote", "repairNote", vo.getRepairNote())
                    ),
                    null
                ));
                publishTraceSnapshot(req.getSessionId(), "repair", traceStages, System.currentTimeMillis() - startAt);
                vo.setTrace(buildTrace(traceStages, System.currentTimeMillis() - startAt));
            }
            log.warn(
                "[AI-REPAIR-FALLBACK] connectionId={}, sessionId={}, databaseName={}, modelName={}, reason={}, elapsedMs={}",
                req.getConnectionId(),
                safe(req.getSessionId()),
                safe(req.getDatabaseName()),
                safe(req.getModelName()),
                safe(ex.getMessage()),
                System.currentTimeMillis() - startAt
            );
            log.info(
                "[AI-REPAIR-TIMING] connectionId={}, sessionId={}, databaseName={}, modelName={}, steps={}, totalMs={}",
                req.getConnectionId(),
                safe(req.getSessionId()),
                safe(req.getDatabaseName()),
                safe(req.getModelName()),
                timer.stepsSummary(),
                timer.totalElapsedMs()
            );
            publishFinalResult(req.getSessionId(), "repair", buildFinalResult("repair", vo));
            return vo;
        } finally {
            conversationContextManager.exitRequestScope();
        }
    }

    @Override
    public ErAiInferenceResultVO inferErRelations(ErAiInferenceReq req) {
        ErAiInferenceResultVO vo = new ErAiInferenceResultVO();
        vo.setSuccess(Boolean.FALSE);
        vo.setMessage("AI inference failed");
        vo.setRelations(List.of());
        if (req == null || req.getConnectionId() == null) {
            vo.setMessage("connectionId is required");
            return vo;
        }
        List<ErTableNodeVO> tables = req.getTables() == null ? List.of() : req.getTables();
        if (tables.isEmpty()) {
            vo.setMessage("selected tables are empty");
            return vo;
        }

        try {
            AiGenerateSqlReq providerReq = new AiGenerateSqlReq();
            providerReq.setConnectionId(req.getConnectionId());
            providerReq.setSessionId("er-infer-" + System.currentTimeMillis());
            providerReq.setDatabaseName(req.getDatabaseName());
            providerReq.setModelName(req.getModelName());
            providerReq.setPrompt("推断已选表之间可能存在的关系，并输出严格的 JSON。");

            String contextText = buildErInferenceContext(req);
            List<String> relatedTables = tables.stream()
                .map(ErTableNodeVO::getTableName)
                .map(this::safe)
                .filter(item -> !item.isBlank())
                .toList();
            AiConversationContextManager.ConversationGenerationContext generationContext =
                new AiConversationContextManager.ConversationGenerationContext(
                    contextText,
                    relatedTables,
                    providerReq.getPrompt()
                );
            TextProviderResult providerResult = generateTextByConfiguredProvider(
                providerReq,
                generationContext,
                ER_RELATION_INFER_SYSTEM_PROMPT,
                "ER relationship inference"
            );

            List<ErRelationVO> parsed = parseErRelationResponse(providerResult.content());
            List<ErRelationVO> filtered = filterErRelations(
                parsed,
                tables,
                req.getForeignKeyRelations(),
                req.getConfidenceThreshold()
            );
            vo.setSuccess(Boolean.TRUE);
            vo.setMessage("ok");
            vo.setRelations(filtered);
            return vo;
        } catch (Exception ex) {
            vo.setSuccess(Boolean.FALSE);
            vo.setMessage("AI inference failed: " + safe(ex.getMessage()));
            vo.setRelations(List.of());
            return vo;
        }
    }


    private String buildErInferenceContext(ErAiInferenceReq req) {
        StringBuilder builder = new StringBuilder();
        builder.append("Selected tables and columns:\n");
        List<ErTableNodeVO> tables = req.getTables() == null ? List.of() : req.getTables();
        for (ErTableNodeVO table : tables) {
            String tableName = safe(table.getTableName());
            if (tableName.isBlank()) {
                continue;
            }
            builder.append("- ").append(tableName);
            String comment = safe(table.getTableComment());
            if (!comment.isBlank()) {
                builder.append(" // ").append(comment);
            }
            builder.append('\n');
            if (table.getColumns() == null || table.getColumns().isEmpty()) {
                builder.append("  - (no columns)\n");
                continue;
            }
            table.getColumns().forEach(column -> {
                String columnName = safe(column.getColumnName());
                if (columnName.isBlank()) {
                    return;
                }
                builder.append("  - ").append(columnName)
                    .append(" ").append(safe(column.getDataType()));
                if (Boolean.TRUE.equals(column.getPrimaryKey())) {
                    builder.append(" [PK]");
                }
                if (Boolean.TRUE.equals(column.getIndexed())) {
                    builder.append(" [IDX]");
                }
                builder.append('\n');
            });
        }
        builder.append("\nKnown foreign keys:\n");
        List<ErRelationVO> fkRelations = req.getForeignKeyRelations() == null ? List.of() : req.getForeignKeyRelations();
        if (fkRelations.isEmpty()) {
            builder.append("- (none)\n");
        } else {
            fkRelations.forEach(item -> builder
                .append("- ")
                .append(safe(item.getSourceTable())).append('.').append(safe(item.getSourceColumn()))
                .append(" -> ")
                .append(safe(item.getTargetTable())).append('.').append(safe(item.getTargetColumn()))
                .append('\n'));
        }
        return builder.toString();
    }

    private List<ErRelationVO> parseErRelationResponse(String rawOutput) {
        List<ErRelationVO> fallback = List.of();
        String normalized = safe(rawOutput);
        if (normalized.isBlank()) {
            return fallback;
        }
        List<String> candidates = new ArrayList<>();
        candidates.add(normalized);
        Matcher fenceMatcher = Pattern.compile("(?is)```(?:json)?\\s*(\\{.*?\\}|\\[.*?\\])\\s*```").matcher(normalized);
        if (fenceMatcher.find()) {
            candidates.add(fenceMatcher.group(1));
        }
        int jsonStart = normalized.indexOf('{');
        int jsonEnd = normalized.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            candidates.add(normalized.substring(jsonStart, jsonEnd + 1));
        }
        int arrStart = normalized.indexOf('[');
        int arrEnd = normalized.lastIndexOf(']');
        if (arrStart >= 0 && arrEnd > arrStart) {
            candidates.add(normalized.substring(arrStart, arrEnd + 1));
        }

        for (String candidate : candidates) {
            try {
                JsonNode root = objectMapper.readTree(candidate);
                JsonNode relationNode;
                if (root.isArray()) {
                    relationNode = root;
                } else {
                    relationNode = root.path("relations");
                }
                if (!relationNode.isArray()) {
                    continue;
                }
                List<ErRelationVO> parsed = new ArrayList<>();
                for (JsonNode item : relationNode) {
                    if (item == null || !item.isObject()) {
                        continue;
                    }
                    ErRelationVO relation = new ErRelationVO();
                    relation.setSourceTable(safe(item.path("sourceTable").asText("")));
                    relation.setSourceColumn(safe(item.path("sourceColumn").asText("")));
                    relation.setTargetTable(safe(item.path("targetTable").asText("")));
                    relation.setTargetColumn(safe(item.path("targetColumn").asText("")));
                    relation.setRelationType("AI_INFERRED");
                    relation.setRelationDirection(normalizeErRelationDirection(
                        safe(item.path("relationDirection").asText(item.path("direction").asText("")))
                    ));
                    relation.setConfidence(parseConfidence(item.path("confidence")));
                    relation.setReason(safe(item.path("reason").asText("")));
                    parsed.add(relation);
                }
                return parsed;
            } catch (Exception ignored) {
                // ignore malformed candidate
            }
        }
        return fallback;
    }

    private List<ErRelationVO> filterErRelations(List<ErRelationVO> rawRelations,
                                                 List<ErTableNodeVO> selectedTables,
                                                 List<ErRelationVO> foreignKeyRelations,
                                                 Double confidenceThreshold) {
        double threshold = normalizeConfidenceThreshold(confidenceThreshold);
        Map<String, String> selectedTableNameMap = new LinkedHashMap<>();
        selectedTables.forEach(item -> {
            String canonical = safe(item.getTableName());
            String tableName = normalizeIdentifier(canonical);
            if (!tableName.isBlank() && !canonical.isBlank()) {
                selectedTableNameMap.putIfAbsent(tableName, canonical);
            }
        });
        Set<String> selectedTableSet = selectedTableNameMap.keySet();
        Set<String> foreignKeyStartSet = new HashSet<>();
        List<ErRelationVO> fkList = foreignKeyRelations == null ? List.of() : foreignKeyRelations;
        fkList.forEach(item -> foreignKeyStartSet.add(buildErRelationStartKey(item)));

        Map<String, ErRelationVO> dedup = new LinkedHashMap<>();
        List<ErRelationVO> source = rawRelations == null ? List.of() : rawRelations;
        for (ErRelationVO item : source) {
            if (item == null) {
                continue;
            }
            String sourceTable = normalizeIdentifier(item.getSourceTable());
            String targetTable = normalizeIdentifier(item.getTargetTable());
            String sourceColumn = safe(item.getSourceColumn());
            String targetColumn = safe(item.getTargetColumn());
            if (sourceTable.isBlank() || targetTable.isBlank() || sourceColumn.isBlank() || targetColumn.isBlank()) {
                continue;
            }
            if (!selectedTableSet.contains(sourceTable) || !selectedTableSet.contains(targetTable)) {
                continue;
            }
            double confidence = item.getConfidence() == null ? 0D : item.getConfidence();
            if (!Double.isFinite(confidence) || confidence < threshold) {
                continue;
            }
            ErRelationVO relation = new ErRelationVO();
            relation.setSourceTable(selectedTableNameMap.getOrDefault(sourceTable, safe(item.getSourceTable())));
            relation.setSourceColumn(sourceColumn);
            relation.setTargetTable(selectedTableNameMap.getOrDefault(targetTable, safe(item.getTargetTable())));
            relation.setTargetColumn(targetColumn);
            relation.setRelationType("AI_INFERRED");
            relation.setRelationDirection(normalizeErRelationDirection(item.getRelationDirection()));
            relation.setConfidence(Math.max(0D, Math.min(1D, confidence)));
            relation.setReason(safe(item.getReason()));
            if (relation.getReason().isBlank()) {
                relation.setReason("ai inferred relation");
            }
            String key = buildErRelationStartKey(relation);
            if (foreignKeyStartSet.contains(key)) {
                continue;
            }
            ErRelationVO existing = dedup.get(key);
            if (shouldReplaceErRelation(existing, relation)) {
                dedup.put(key, relation);
            }
        }
        List<ErRelationVO> relations = new ArrayList<>(dedup.values());
        relations.sort(Comparator
            .comparing(ErRelationVO::getSourceTable, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ErRelationVO::getTargetTable, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ErRelationVO::getSourceColumn, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ErRelationVO::getTargetColumn, String.CASE_INSENSITIVE_ORDER));
        return relations;
    }

    private String buildErRelationStartKey(ErRelationVO relation) {
        return normalizeIdentifier(relation.getSourceTable()) + "|"
            + normalizeIdentifier(relation.getSourceColumn()) + "|"
            + normalizeIdentifier(relation.getTargetTable());
    }

    private boolean shouldReplaceErRelation(ErRelationVO existing, ErRelationVO candidate) {
        if (existing == null) {
            return true;
        }
        double existingConfidence = existing.getConfidence() == null ? 0D : existing.getConfidence();
        double candidateConfidence = candidate.getConfidence() == null ? 0D : candidate.getConfidence();
        if (candidateConfidence > existingConfidence) {
            return true;
        }
        if (candidateConfidence < existingConfidence) {
            return false;
        }
        String existingReason = safe(existing.getReason());
        String candidateReason = safe(candidate.getReason());
        return !candidateReason.isBlank() && existingReason.isBlank();
    }

    private double parseConfidence(JsonNode node) {
        if (node == null || node.isNull()) {
            return 0D;
        }
        if (node.isNumber()) {
            return node.asDouble(0D);
        }
        String value = safe(node.asText(""));
        if (value.isBlank()) {
            return 0D;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignore) {
            return 0D;
        }
    }

    private String normalizeErRelationDirection(String rawDirection) {
        String direction = safe(rawDirection).toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (direction.isBlank()) {
            return "SOURCE_TO_TARGET";
        }
        if ("TARGET_TO_SOURCE".equals(direction) || "INBOUND".equals(direction) || "REVERSE".equals(direction) || "<-".equals(direction)) {
            return "TARGET_TO_SOURCE";
        }
        if ("BIDIRECTIONAL".equals(direction) || "BOTH".equals(direction) || "TWO_WAY".equals(direction) || "<->".equals(direction)) {
            return "BIDIRECTIONAL";
        }
        if ("SOURCE_TO_TARGET".equals(direction) || "OUTBOUND".equals(direction) || "FORWARD".equals(direction) || "->".equals(direction)) {
            return "SOURCE_TO_TARGET";
        }
        return "SOURCE_TO_TARGET";
    }

    private double normalizeConfidenceThreshold(Double threshold) {
        if (threshold == null || !Double.isFinite(threshold)) {
            return 0.6D;
        }
        return Math.max(0D, Math.min(1D, threshold));
    }

    private AiGenerateSqlReq buildRepairGenerateReq(AiRepairReq req, String sqlText, String errorMessage) {
        AiGenerateSqlReq aiReq = new AiGenerateSqlReq();
        aiReq.setConnectionId(req.getConnectionId());
        aiReq.setSessionId(req.getSessionId());
        aiReq.setDatabaseName(req.getDatabaseName());
        aiReq.setModelId(req.getModelId());
        aiReq.setModelName(req.getModelName());
        aiReq.setDetailOutputEnabled(req.getDetailOutputEnabled());
        aiReq.setPrompt(buildRepairPrompt(sqlText, errorMessage));
        return aiReq;
    }

    private String buildRepairPrompt(String sqlText, String errorMessage) {
        return """
            Repair the failed SQL according to the execution error.
            Keep business intent unchanged while making it executable.

            Execution error:
            %s

            Original SQL:
            %s

            Return strict JSON with keys:
            errorExplanation
            repairedSql
            """.formatted(safe(errorMessage), safe(sqlText));
    }

    private ParsedRepairResult parseRepairResult(String rawOutput, String sourceSql, String errorMessage) {
        ParsedRepairResult jsonResult = tryParseRepairJson(rawOutput);
        if (jsonResult != null && !safe(jsonResult.repairedSql()).isBlank()) {
            return jsonResult;
        }

        String repairedSql = extractSql(rawOutput);
        if (!repairedSql.isBlank()) {
            String explanation = extractRepairExplanation(rawOutput, repairedSql, errorMessage);
            return new ParsedRepairResult(explanation, repairedSql);
        }
        String fallbackExplanation = "Model output did not contain a valid repaired SQL.";
        return new ParsedRepairResult(fallbackExplanation, "");
    }

    private ParsedRepairResult tryParseRepairJson(String rawOutput) {
        String normalized = safe(rawOutput).trim();
        if (normalized.isBlank()) {
            return null;
        }
        List<String> candidates = new ArrayList<>();
        candidates.add(normalized);
        int jsonStart = normalized.indexOf('{');
        int jsonEnd = normalized.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            candidates.add(normalized.substring(jsonStart, jsonEnd + 1));
        }

        for (String candidate : candidates) {
            try {
                JsonNode node = objectMapper.readTree(candidate);
                if (node == null || !node.isObject()) {
                    continue;
                }
                String explanation = safe(node.path("errorExplanation").asText(""));
                String repairedSql = normalizeSqlText(safe(node.path("repairedSql").asText("")));
                if (repairedSql.isBlank()) {
                    repairedSql = extractSql(candidate);
                }
                if (!repairedSql.isBlank()) {
                    if (explanation.isBlank()) {
                        explanation = "The SQL has been repaired based on the execution error.";
                    }
                    return new ParsedRepairResult(explanation.trim(), repairedSql.trim());
                }
            } catch (Exception ignore) {
                // ignore non-json candidate
            }
        }
        return null;
    }

    private String extractRepairExplanation(String rawOutput, String repairedSql, String errorMessage) {
        String text = safe(rawOutput).trim();
        if (text.isBlank()) {
            return "SQL execution failed: " + safe(errorMessage);
        }
        String withoutFence = SQL_FENCE_PATTERN.matcher(text).replaceAll("").trim();
        if (!safe(repairedSql).isBlank()) {
            withoutFence = withoutFence.replace(repairedSql, "").trim();
        }
        if (withoutFence.startsWith("{") && withoutFence.endsWith("}")) {
            ParsedRepairResult parsed = tryParseRepairJson(withoutFence);
            if (parsed != null && !safe(parsed.errorExplanation()).isBlank()) {
                return parsed.errorExplanation();
            }
        }
        if (withoutFence.isBlank()) {
            return "SQL execution failed: " + safe(errorMessage);
        }
        return withoutFence;
    }

    private ParsedRepairResult fallbackRepairResult(String sourceSql, String errorMessage) {
        String repairedSql = safe(sourceSql);
        if (safe(errorMessage).toLowerCase(Locale.ROOT).contains("unknown column")) {
            String explanation = "SQL execution failed: " + safe(errorMessage)
                + ". Rule-based fallback was applied. Please verify referenced column names.";
            return new ParsedRepairResult(explanation, repairedSql);
        }
        String explanation = "SQL execution failed: " + safe(errorMessage)
            + ". Rule-based fallback was applied.";
        return new ParsedRepairResult(explanation, repairedSql);
    }

    private IntentResult identifyIntent(AiGenerateSqlReq req) {
        StepTimer timer = new StepTimer();
        AiConfigVO aiConfig = aiConfigService.getConfig();
        boolean memoryEnabled = conversationContextManager.isMemoryEnabled(req, aiConfig);
        ParsedIntentResponse parsed;
        if (!memoryEnabled) {
            parsed = identifyIntentSingleStage(req);
            timer.mark("identify_intent_single_stage");
        } else {
            // 关键步骤：意图识别先读最近窗口对话，再补全历史召回，避免把长对话整段塞给模型。
            String recentDialogContext = conversationContextManager.buildIntentRecentDialogContext(req, MEMORY_SUMMARY_LIMIT);
            timer.mark("load_recent_dialog");
            ParsedIntentResponse light = identifyIntentLight(req, recentDialogContext);
            timer.mark("identify_intent_light");
            IntentRetrievalParams retrievalParams = light == null ? IntentRetrievalParams.defaultValue() : light.retrievalParams();
            String historyContext = conversationContextManager.retrieveIntentHistoryContext(
                req,
                retrievalParams.sessionTopK(),
                retrievalParams.globalTopK(),
                retrievalParams.query(),
                retrievalParams.focusTables()
            );
            timer.mark("retrieve_history");
            parsed = identifyIntentFinal(req, recentDialogContext, historyContext);
            timer.mark("identify_intent_final");
        }

        if (parsed == null || parsed.intentType() == null) {
            log.info(
                "[AI-IDENTIFY-INTENT-TIMING] connectionId={}, sessionId={}, databaseName={}, modelName={}, steps={}, totalMs={}",
                req.getConnectionId(),
                safe(req.getSessionId()),
                safe(req.getDatabaseName()),
                safe(req.getModelName()),
                timer.stepsSummary(),
                timer.totalElapsedMs()
            );
            throw new BusinessException(400, "意图识别失败，请明确输入需求（生成SQL/解释SQL/分析SQL/生成图表）");
        }
        double confidence = normalizeIntentConfidence(parsed.confidence());
        timer.mark("validate_intent_confidence");
        if (confidence < AUTO_INTENT_MIN_CONFIDENCE) {
            log.info(
                "[AI-IDENTIFY-INTENT-TIMING] connectionId={}, sessionId={}, databaseName={}, modelName={}, steps={}, totalMs={}",
                req.getConnectionId(),
                safe(req.getSessionId()),
                safe(req.getDatabaseName()),
                safe(req.getModelName()),
                timer.stepsSummary(),
                timer.totalElapsedMs()
            );
            throw new BusinessException(
                400,
                "意图识别置信度不足(" + String.format(Locale.ROOT, "%.2f", confidence) + ")，请补充更明确的需求"
            );
        }
        log.info(
            "[AI-IDENTIFY-INTENT-TIMING] connectionId={}, sessionId={}, databaseName={}, modelName={}, intentType={}, confidence={}, steps={}, totalMs={}",
            req.getConnectionId(),
            safe(req.getSessionId()),
            safe(req.getDatabaseName()),
            safe(req.getModelName()),
            parsed.intentType().name(),
            confidence,
            timer.stepsSummary(),
            timer.totalElapsedMs()
        );
        return new IntentResult(parsed.intentType(), confidence, safe(parsed.reason()));
    }

    private ParsedIntentResponse parseIntentResponse(String rawOutput) {
        String normalized = safe(rawOutput);
        if (normalized.isBlank()) {
            return null;
        }
        List<String> candidates = new ArrayList<>();
        candidates.add(normalized);
        Matcher fenceMatcher = Pattern.compile("(?is)```(?:json)?\\s*(\\{.*?\\})\\s*```").matcher(normalized);
        if (fenceMatcher.find()) {
            candidates.add(fenceMatcher.group(1));
        }
        int jsonStart = normalized.indexOf('{');
        int jsonEnd = normalized.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            candidates.add(normalized.substring(jsonStart, jsonEnd + 1));
        }

        for (String candidate : candidates) {
            try {
                JsonNode root = objectMapper.readTree(candidate);
                if (root == null || !root.isObject()) {
                    continue;
                }
                IntentType intentType = parseIntentType(safe(root.path("intentType").asText("")));
                if (intentType == null) {
                    continue;
                }
                double confidence = parseIntentConfidence(root.path("confidence"));
                String reason = safe(root.path("reason").asText(""));
                if (reason.isBlank()) {
                    reason = safe(root.path("reasoning").asText(""));
                }
                if (reason.isBlank()) {
                    reason = safe(root.path("message").asText(""));
                }
                IntentRetrievalParams retrievalParams = parseIntentRetrievalParams(root.path("retrieval"));
                return new ParsedIntentResponse(intentType, confidence, reason, true, retrievalParams);
            } catch (Exception ignore) {
                // ignore malformed JSON candidate
            }
        }
        return null;
    }

    private ParsedIntentResponse identifyIntentSingleStage(AiGenerateSqlReq req) {
        String finalInput = "用户输入:\n" + safe(req.getPrompt());
        TextProviderResult providerResult = generateRawTextByConfiguredProvider(
            req,
            INTENT_CLASSIFY_FINAL_SYSTEM_PROMPT,
            finalInput,
            "意图识别-单阶段"
        );
        ParsedIntentResponse parsed = parseIntentResponse(providerResult.content());
        if (parsed == null) {
            throw new BusinessException(400, "意图识别失败，请补充更明确的目标");
        }
        return parsed;
    }

    private ParsedIntentResponse identifyIntentLight(AiGenerateSqlReq req, String recentDialogContext) {
        StringBuilder input = new StringBuilder();
        input.append("用户输入:\n").append(safe(req.getPrompt())).append("\n");
        if (!recentDialogContext.isBlank()) {
            input.append("\n最近几轮对话:\n").append(recentDialogContext).append("\n");
        }
        TextProviderResult providerResult = generateRawTextByConfiguredProvider(
            req,
            INTENT_CLASSIFY_LIGHT_SYSTEM_PROMPT,
            input.toString(),
            "意图识别-轻量"
        );
        ParsedIntentResponse parsed = parseIntentResponse(providerResult.content());
        if (parsed == null) {
            return new ParsedIntentResponse(IntentType.GENERATE_SQL, 0.75D, "轻量意图识别降级为默认生成SQL", false, IntentRetrievalParams.defaultValue());
        }
        IntentRetrievalParams retrievalParams = parsed.retrievalParams() == null ? IntentRetrievalParams.defaultValue() : parsed.retrievalParams();
        return new ParsedIntentResponse(parsed.intentType(), parsed.confidence(), parsed.reason(), parsed.parsed(), retrievalParams);
    }

    private ParsedIntentResponse identifyIntentFinal(AiGenerateSqlReq req, String recentDialogContext, String historyContext) {
        StringBuilder input = new StringBuilder();
        input.append("用户输入:\n").append(safe(req.getPrompt())).append("\n");
        if (!recentDialogContext.isBlank()) {
            input.append("\n最近几轮对话:\n").append(recentDialogContext).append("\n");
        }
        if (!historyContext.isBlank()) {
            input.append("\n历史检索结果:\n").append(historyContext).append("\n");
        }
        TextProviderResult providerResult = generateRawTextByConfiguredProvider(
            req,
            INTENT_CLASSIFY_FINAL_SYSTEM_PROMPT,
            input.toString(),
            "意图识别-最终"
        );
        ParsedIntentResponse parsed = parseIntentResponse(providerResult.content());
        if (parsed == null) {
            throw new BusinessException(400, "意图识别失败，请补充更明确的目标");
        }
        return parsed;
    }

    private ParsedIntentResponse identifyRetrievalIntentForSql(AiGenerateSqlReq req) {
        try {
            AiConfigVO aiConfig = aiConfigService.getConfig();
            boolean memoryEnabled = conversationContextManager.isMemoryEnabled(req, aiConfig);
            String recentDialogContext = "";
            if (memoryEnabled) {
                recentDialogContext = conversationContextManager.buildIntentRecentDialogContext(req, MEMORY_SUMMARY_LIMIT);
            }
            return identifyIntentLight(req, recentDialogContext);
        } catch (Exception ex) {
            log.warn(
                "[AI-GENERATE-RETRIEVAL-INTENT-FAILED] connectionId={}, sessionId={}, databaseName={}, modelName={}, reason={}",
                req.getConnectionId(),
                safe(req.getSessionId()),
                safe(req.getDatabaseName()),
                safe(req.getModelName()),
                safe(ex.getMessage())
            );
            return new ParsedIntentResponse(
                IntentType.GENERATE_SQL,
                0D,
                "检索意图识别失败，已降级为默认检索",
                false,
                IntentRetrievalParams.defaultValue()
            );
        }
    }

    private IntentRetrievalParams parseIntentRetrievalParams(JsonNode retrievalNode) {
        if (retrievalNode == null || retrievalNode.isMissingNode() || retrievalNode.isNull() || !retrievalNode.isObject()) {
            return IntentRetrievalParams.defaultValue();
        }
        int sessionTopK = normalizeTopK(retrievalNode.path("sessionTopK").asInt(4), 1, SESSION_HISTORY_RECALL_LIMIT);
        int globalTopK = normalizeTopK(retrievalNode.path("globalTopK").asInt(6), 1, GLOBAL_HISTORY_RECALL_LIMIT);
        String query = safe(retrievalNode.path("query").asText(""));
        List<String> focusTables = new ArrayList<>();
        JsonNode focusNode = retrievalNode.path("focusTables");
        if (focusNode != null && focusNode.isArray()) {
            for (JsonNode item : focusNode) {
                String table = safe(item == null ? "" : item.asText(""));
                if (!table.isBlank()) {
                    focusTables.add(normalizeRelatedTableName(table));
                }
            }
        }
        return new IntentRetrievalParams(sessionTopK, globalTopK, query, focusTables);
    }

    private int normalizeTopK(int input, int min, int max) {
        return Math.max(min, Math.min(max, input));
    }

    private IntentType parseIntentType(String rawIntent) {
        String normalized = safe(rawIntent).toUpperCase(Locale.ROOT).replace('-', '_');
        if ("GENERATE_SQL".equals(normalized) || "GENERATE".equals(normalized) || "SQL_GENERATE".equals(normalized)) {
            return IntentType.GENERATE_SQL;
        }
        if ("EXPLAIN_SQL".equals(normalized) || "EXPLAIN".equals(normalized)) {
            return IntentType.EXPLAIN_SQL;
        }
        if ("ANALYZE_SQL".equals(normalized) || "ANALYSE_SQL".equals(normalized) || "ANALYZE".equals(normalized)) {
            return IntentType.ANALYZE_SQL;
        }
        if ("GENERATE_CHART".equals(normalized) || "CHART".equals(normalized) || "CHART_PLAN".equals(normalized)) {
            return IntentType.GENERATE_CHART;
        }
        return null;
    }

    private double parseIntentConfidence(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return 0D;
        }
        if (node.isNumber()) {
            return node.asDouble(0D);
        }
        String value = safe(node.asText(""));
        if (value.isBlank()) {
            return 0D;
        }
        try {
            return Double.parseDouble(value);
        } catch (Exception ignore) {
            return 0D;
        }
    }

    private double normalizeIntentConfidence(double rawValue) {
        if (Double.isNaN(rawValue) || Double.isInfinite(rawValue)) {
            return 0D;
        }
        double value = rawValue;
        if (value > 1D && value <= 100D) {
            value = value / 100D;
        }
        if (value < 0D) {
            return 0D;
        }
        if (value > 1D) {
            return 1D;
        }
        return value;
    }

    private String joinReasoning(String first, String second) {
        String left = safe(first);
        String right = safe(second);
        if (left.isBlank()) {
            return right;
        }
        if (right.isBlank()) {
            return left;
        }
        return left + "；" + right;
    }

    /**
     * 关键操作：意图识别失败时返回可直接展示的提示文本，避免前端走异常分支。
     */
    private AiAutoQueryVO buildIntentClarifyResponse(BusinessException ex) {
        AiAutoQueryVO vo = new AiAutoQueryVO();
        vo.setIntentType(IntentType.GENERATE_SQL.name());
        vo.setIntentLabel(IntentType.GENERATE_SQL.label());
        vo.setIntentConfidence(0D);
        vo.setFallbackUsed(true);
        vo.setReasoning(AUTO_INTENT_CLARIFY_CONTENT);
        return vo;
    }

    private AiTextResponseVO explainSqlWithPipeline(AiGenerateSqlReq req) {
        return generateSqlUnderstandingResponse(req, false);
    }

    private AiTextResponseVO analyzeSqlWithPipeline(AiGenerateSqlReq req) {
        return generateSqlUnderstandingResponse(req, true);
    }

    private AiTextResponseVO generateSqlUnderstandingResponse(AiGenerateSqlReq req, boolean analyzeMode) {
        String logScene = analyzeMode ? "ANALYZE-SQL" : "EXPLAIN-TEXT";
        String taskLabel = analyzeMode ? "SQL 合理性分析" : "SQL 含义解释";
        String actionType = analyzeMode ? "analyze" : "explain";
        long startAt = System.currentTimeMillis();
        StepTimer timer = new StepTimer();
        log.info(
            "[AI-{}-REQ] connectionId={}, sessionId={}, databaseName={}, modelName={}, promptLength={}",
            logScene,
            req.getConnectionId(),
            safe(req.getSessionId()),
            safe(req.getDatabaseName()),
            safe(req.getModelName()),
            safe(req.getPrompt()).length()
        );

        boolean detailOutputEnabled = resolveDetailOutputEnabled(req);
        List<AiTraceStageVO> traceStages = detailOutputEnabled ? new ArrayList<>() : List.of();
        SqlExtractionResult extraction = extractSqlByLlm(req, safe(req.getPrompt()));
        timer.mark("extract_sql_llm");
        if (detailOutputEnabled) {
            addTraceStage(req.getSessionId(), actionType, traceStages, buildTraceStage(
                "extract_sql",
                "extract_sql",
                "pipeline",
                extraction.hasSql() ? "success" : "failed",
                0L,
                List.of(buildTraceField("sourcePrompt", "sourcePrompt", req.getPrompt())),
                List.of(buildTraceField("sqlList", "sqlList", extraction.sqlList())),
                null
            ));
        }
        if (!extraction.hasSql() || extraction.sqlList().isEmpty()) {
            throw new BusinessException(400, "未识别到 SQL，请在问题中包含 SQL 片段");
        }
        String sourceSql = safe(extraction.sqlList().get(0));
        ParsedSqlInsights insights = parseSqlInsights(sourceSql);
        timer.mark("parse_sql");
        ExactMetadataContext metadataContext = buildExactMetadataContext(req, insights.tables(), analyzeMode);
        timer.mark("exact_metadata");
        if (detailOutputEnabled) {
            addTraceStage(req.getSessionId(), actionType, traceStages, buildTraceStage(
                "sql_understanding",
                "sql_understanding",
                "pipeline",
                "success",
                0L,
                List.of(buildTraceField("sourceSql", "sourceSql", sourceSql)),
                List.of(
                    buildTraceField("sqlInsights", "sqlInsights", insights.summary()),
                    buildTraceField("metadataContext", "metadataContext", metadataContext.contextText())
                ),
                null
            ));
        }
        boolean needSupplement = shouldSupplementRetrieval(insights, metadataContext, extraction.sqlList().size());
        String supplementContext = "";
        if (needSupplement) {
            supplementContext = buildSupplementRetrievalContext(req, sourceSql);
            timer.mark("supplement_retrieve");
            if (detailOutputEnabled) {
                addTraceStage(req.getSessionId(), actionType, traceStages, buildTraceStage(
                    "supplement_retrieve",
                    "supplement_retrieve",
                    "rag",
                    "success",
                    0L,
                    List.of(buildTraceField("sourceSql", "sourceSql", sourceSql)),
                    List.of(buildTraceField("supplementContext", "supplementContext", supplementContext)),
                    null
                ));
            }
        }
        String explainPlanContext = "";
        if (analyzeMode) {
            explainPlanContext = tryBuildExplainPlanPromptContext(req, sourceSql);
            timer.mark("append_explain_plan_context");
        }

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("用户输入:\n").append(safe(req.getPrompt())).append("\n\n");
        userPrompt.append("提取到的SQL:\n").append(sourceSql).append("\n\n");
        userPrompt.append("SQL解析结果:\n").append(insights.summary()).append("\n\n");
        userPrompt.append("精确元数据:\n").append(metadataContext.contextText()).append("\n");
        if (!supplementContext.isBlank()) {
            userPrompt.append("\n补充召回(example_sql/metric/history):\n").append(supplementContext).append("\n");
        }
        if (!explainPlanContext.isBlank()) {
            userPrompt.append("\n执行计划上下文:\n").append(explainPlanContext).append("\n");
        }

        String content;
        String reasoning;
        OpenAiTextClient.TokenUsage providerTokenUsage = null;
        LlmGatewayResult gatewayResult = null;
        boolean fallbackUsed = false;
        try {
            TextProviderResult result = generateRawTextByConfiguredProvider(
                req,
                analyzeMode ? ANALYZE_SQL_SYSTEM_PROMPT : EXPLAIN_SQL_SYSTEM_PROMPT,
                userPrompt.toString(),
                taskLabel
            );
            content = safe(result.content());
            reasoning = safe(result.reasoning());
            providerTokenUsage = result.usage();
            gatewayResult = result.gatewayResult();
            timer.mark("provider_generate_text");
            if (detailOutputEnabled) {
                addTraceStage(req.getSessionId(), actionType, traceStages, buildTraceStage(
                    analyzeMode ? "llm_analyze_sql" : "llm_explain_sql",
                    analyzeMode ? "analyze_sql" : "explain_sql",
                    "llm",
                    "success",
                    0L,
                    List.of(
                        buildTraceField("modelId", "modelId", resolveRequestedModelId(req)),
                        buildTraceField("userPrompt", "userPrompt", userPrompt.toString())
                    ),
                    List.of(
                        buildTraceField("content", "content", content),
                        buildTraceField("reasoning", "reasoning", reasoning)
                    ),
                    buildTraceLlmCall(gatewayResult)
                ));
            }
        } catch (Exception ex) {
            content = "未能完成本次" + taskLabel + "，请稍后重试。";
            reasoning = "AI 配置调用失败，已降级为错误提示。原因: " + safe(ex.getMessage());
            fallbackUsed = true;
            timer.mark("provider_generate_text_failed");
            if (detailOutputEnabled) {
                addTraceStage(req.getSessionId(), actionType, traceStages, buildTraceStage(
                    analyzeMode ? "llm_analyze_sql" : "llm_explain_sql",
                    analyzeMode ? "analyze_sql" : "explain_sql",
                    "llm",
                    "failed",
                    0L,
                    List.of(
                        buildTraceField("modelId", "modelId", resolveRequestedModelId(req)),
                        buildTraceField("userPrompt", "userPrompt", userPrompt.toString())
                    ),
                    List.of(buildTraceField("error", "error", ex.getMessage())),
                    null
                ));
            }
        }

        AiTextResponseVO vo = new AiTextResponseVO();
        vo.setContent(content);
        vo.setReasoning(reasoning);
        vo.setFallbackUsed(fallbackUsed);
        TokenUsageStats tokenUsage = resolveTokenUsage(
            providerTokenUsage,
            userPrompt.toString(),
            content + "\n" + reasoning
        );
        vo.setPromptTokens(tokenUsage.promptTokens());
        vo.setCompletionTokens(tokenUsage.completionTokens());
        vo.setTotalTokens(tokenUsage.totalTokens());
        if (detailOutputEnabled) {
            publishTraceSnapshot(req.getSessionId(), actionType, traceStages, System.currentTimeMillis() - startAt);
            vo.setTrace(buildTrace(traceStages, System.currentTimeMillis() - startAt));
        }
        timer.mark("assemble_response");
        log.info(
            "[AI-{}-RESP] connectionId={}, sessionId={}, databaseName={}, modelName={}, contextLength={}, contentLength={}, fallbackUsed={}, elapsedMs={}",
            logScene,
            req.getConnectionId(),
            safe(req.getSessionId()),
            safe(req.getDatabaseName()),
            safe(req.getModelName()),
            userPrompt.length(),
            safe(content).length(),
            fallbackUsed,
            System.currentTimeMillis() - startAt
        );
        log.info(
            "[AI-{}-TIMING] connectionId={}, sessionId={}, databaseName={}, modelName={}, steps={}, totalMs={}",
            logScene,
            req.getConnectionId(),
            safe(req.getSessionId()),
            safe(req.getDatabaseName()),
            safe(req.getModelName()),
            timer.stepsSummary(),
            timer.totalElapsedMs()
        );
        publishFinalResult(req.getSessionId(), actionType, buildFinalResult(actionType, vo));
        return vo;
    }

    private SqlExtractionResult extractSqlByLlm(AiGenerateSqlReq req, String sourceText) {
        TextProviderResult providerResult = generateRawTextByConfiguredProvider(
            req,
            SQL_EXTRACT_SYSTEM_PROMPT,
            "输入文本:\n" + safe(sourceText),
            "SQL提取"
        );
        String raw = safe(providerResult.content());
        SqlExtractionResult parsed = parseSqlExtractionResult(raw);
        if (parsed != null) {
            return parsed;
        }
        String fallbackSql = extractSql(sourceText);
        if (!fallbackSql.isBlank()) {
            return new SqlExtractionResult(true, List.of(fallbackSql), false);
        }
        return new SqlExtractionResult(false, List.of(), false);
    }

    private SqlExtractionResult parseSqlExtractionResult(String rawOutput) {
        String normalized = safe(rawOutput);
        if (normalized.isBlank()) {
            return null;
        }
        List<String> candidates = new ArrayList<>();
        candidates.add(normalized);
        int jsonStart = normalized.indexOf('{');
        int jsonEnd = normalized.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            candidates.add(normalized.substring(jsonStart, jsonEnd + 1));
        }
        for (String candidate : candidates) {
            try {
                JsonNode root = objectMapper.readTree(candidate);
                if (root == null || !root.isObject()) {
                    continue;
                }
                boolean hasSql = root.path("has_sql").asBoolean(root.path("hasSql").asBoolean(false));
                JsonNode listNode = root.path("sql_list");
                if (!listNode.isArray()) {
                    listNode = root.path("sqlList");
                }
                List<String> sqlList = new ArrayList<>();
                if (listNode.isArray()) {
                    for (JsonNode item : listNode) {
                        String sql = item == null ? "" : Objects.toString(item.asText(""), "");
                        if (!sql.isBlank()) {
                            sqlList.add(sql);
                        }
                    }
                }
                return new SqlExtractionResult(hasSql || !sqlList.isEmpty(), sqlList, true);
            } catch (Exception ignore) {
                // ignore malformed json
            }
        }
        return null;
    }

    private ParsedSqlInsights parseSqlInsights(String sqlText) {
        String sourceSql = safe(sqlText);
        if (sourceSql.isBlank()) {
            return ParsedSqlInsights.empty("SQL为空");
        }
        try {
            Statements statements = CCJSqlParserUtil.parseStatements(sourceSql);
            if (statements == null || statements.getStatements() == null || statements.getStatements().isEmpty()) {
                return ParsedSqlInsights.empty("SQL解析为空");
            }
            if (statements.getStatements().size() != 1) {
                return ParsedSqlInsights.empty("检测到多条SQL语句");
            }
            Statement statement = statements.getStatements().get(0);
            String normalizedSql = safe(statement.toString());
            TablesNamesFinder finder = new TablesNamesFinder();
            List<String> tables = finder.getTableList(statement).stream()
                .map(this::normalizeRelatedTableName)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
            List<String> columns = extractSqlColumns(normalizedSql);
            List<String> aggregates = extractAggregateFunctions(normalizedSql);
            int joinCount = countRegexMatches(JOIN_PATTERN, normalizedSql);
            boolean hasWhere = WHERE_PATTERN.matcher(normalizedSql).find();
            boolean hasGroupBy = GROUP_BY_PATTERN.matcher(normalizedSql).find();
            boolean hasOrderBy = ORDER_BY_PATTERN.matcher(normalizedSql).find();
            return new ParsedSqlInsights(
                true,
                normalizedSql,
                tables,
                columns,
                aggregates,
                joinCount,
                hasWhere,
                hasGroupBy,
                hasOrderBy,
                "解析成功"
            );
        } catch (Exception ex) {
            return ParsedSqlInsights.empty("SQL解析失败: " + safe(ex.getMessage()), sourceSql);
        }
    }

    private List<String> extractSqlColumns(String sqlText) {
        Matcher matcher = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)\\.([a-zA-Z_][a-zA-Z0-9_]*)").matcher(safe(sqlText));
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        while (matcher.find()) {
            String table = safe(matcher.group(1));
            String column = safe(matcher.group(2));
            if (!table.isBlank() && !column.isBlank()) {
                columns.add(table + "." + column);
            }
        }
        return new ArrayList<>(columns);
    }

    private List<String> extractAggregateFunctions(String sqlText) {
        Matcher matcher = AGGREGATE_FUNCTION_PATTERN.matcher(safe(sqlText));
        LinkedHashSet<String> functions = new LinkedHashSet<>();
        while (matcher.find()) {
            String fn = safe(matcher.group(1)).toUpperCase(Locale.ROOT);
            if (!fn.isBlank()) {
                functions.add(fn);
            }
        }
        return new ArrayList<>(functions);
    }

    private int countRegexMatches(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(safe(text));
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private ExactMetadataContext buildExactMetadataContext(AiGenerateSqlReq req, List<String> tables, boolean includeIndexInfo) {
        if (tables == null || tables.isEmpty()) {
            return new ExactMetadataContext("未从 SQL 中解析到表名", 0D, false);
        }
        StringBuilder builder = new StringBuilder();
        int total = 0;
        int covered = 0;
        for (String table : tables) {
            if (total >= SQL_UNDERSTAND_TABLE_LIMIT) {
                break;
            }
            String tableName = normalizeRelatedTableName(table);
            if (tableName.isBlank()) {
                continue;
            }
            total++;
            try {
                TableDetailVO detail = schemaService.getTableDetail(req.getConnectionId(), req.getDatabaseName(), tableName);
                if (detail == null || detail.getColumns() == null || detail.getColumns().isEmpty()) {
                    builder.append("- ").append(tableName).append(": 未获取到字段元数据\n");
                    continue;
                }
                covered++;
                builder.append("- 琛?").append(tableName).append(":\n");
                for (TableDetailVO.ColumnDetailVO column : detail.getColumns()) {
                    String columnName = safe(column.getColumnName());
                    if (columnName.isBlank()) {
                        continue;
                    }
                    builder.append("  - ").append(columnName)
                        .append(" ").append(safe(column.getDataType()));
                    if (includeIndexInfo) {
                        if (Boolean.TRUE.equals(column.getPrimaryKey())) {
                            builder.append(" [PK]");
                        }
                        if (Boolean.TRUE.equals(column.getIndexed())) {
                            builder.append(" [IDX]");
                        }
                    }
                    if (!safe(column.getColumnComment()).isBlank()) {
                        builder.append(" // ").append(safe(column.getColumnComment()));
                    }
                    builder.append('\n');
                }
            } catch (Exception ex) {
                builder.append("- ").append(tableName).append(": 元数据读取失败(").append(safe(ex.getMessage())).append(")\n");
            }
        }
        double coverage = total <= 0 ? 0D : (covered * 1.0D / total);
        return new ExactMetadataContext(builder.toString().trim(), coverage, covered > 0);
    }

    private boolean shouldSupplementRetrieval(ParsedSqlInsights insights, ExactMetadataContext metadataContext, int sqlCount) {
        if (insights == null || !insights.parseSuccess()) {
            return true;
        }
        if (sqlCount > 1) {
            return true;
        }
        if (metadataContext == null || !metadataContext.hasMetadata()) {
            return true;
        }
        return metadataContext.coverage() < 0.6D;
    }

    private String buildSupplementRetrievalContext(AiGenerateSqlReq req, String sourceSql) {
        String retrievalInput = conversationContextManager.buildRetrievalInputForRag(req, sourceSql);
        RagPromptContext context = ragRetrievalService.retrievePromptContext(
            req.getConnectionId(),
            req.getDatabaseName(),
            retrievalInput
        );
        return safe(context == null ? "" : context.getPromptContext());
    }


    private String tryBuildExplainPlanPromptContext(AiGenerateSqlReq req, String sourceSql) {
        if (sourceSql.isBlank()) {
            return "";
        }
        if (hasMultipleStatements(sourceSql)) {
            log.info(
                "[AI-ANALYZE-EXPLAIN-SKIP] connectionId={}, sessionId={}, reason=multiple_statements",
                req.getConnectionId(),
                safe(req.getSessionId())
            );
            return "";
        }

        try {
            ConnectionEntity connectionEntity = connectionService.getConnectionEntity(req.getConnectionId());
            String targetDatabaseName = resolveTargetDatabaseName(connectionEntity.getDatabaseName(), req.getDatabaseName());
            String explainSql = buildExplainSql(connectionEntity.getDbType(), sourceSql);

            try (java.sql.Connection connection = connectionService.openTargetConnection(req.getConnectionId())) {
                applyDatabaseContext(connection, connectionEntity.getDbType(), targetDatabaseName);
                try (java.sql.Statement statement = connection.createStatement();
                     java.sql.ResultSet resultSet = statement.executeQuery(explainSql)) {
                    List<QueryRowVO> rows = ResultSetConverter.readRows(
                        resultSet,
                        ANALYZE_EXPLAIN_PLAN_ROW_LIMIT
                    );
                    log.info(
                        "[AI-ANALYZE-EXPLAIN-SUCCESS] connectionId={}, sessionId={}, databaseName={}, rows={}",
                        req.getConnectionId(),
                        safe(req.getSessionId()),
                        safe(targetDatabaseName),
                        rows.size()
                    );
                    return buildExplainPlanPromptContext(sourceSql, explainSql, compactPlanRows(rows));
                }
            }
        } catch (Exception ex) {
            log.info(
                "[AI-ANALYZE-EXPLAIN-SKIP] connectionId={}, sessionId={}, databaseName={}, reason={}",
                req.getConnectionId(),
                safe(req.getSessionId()),
                safe(req.getDatabaseName()),
                safe(ex.getMessage())
            );
            return "";
        }
    }

    private boolean hasMultipleStatements(String sql) {
        try {
            Statements statements = CCJSqlParserUtil.parseStatements(sql);
            if (statements == null || statements.getStatements() == null) {
                return true;
            }
            return statements.getStatements().size() != 1;
        } catch (Exception ex) {
            return true;
        }
    }

    private String buildExplainPlanPromptContext(String sourceSql, String explainSql, String planRows) {
        StringBuilder builder = new StringBuilder();
        builder.append("Execution plan context from backend pre-executed EXPLAIN.\n");
        builder.append("Source SQL:\n").append(safe(sourceSql)).append('\n');
        builder.append("Explain SQL:\n").append(safe(explainSql)).append('\n');
        builder.append("Plan rows (JSON):\n").append(safe(planRows)).append('\n');
        builder.append("Use this plan as primary evidence when assessing index usage and scan risks.");
        return builder.toString();
    }

    private String compactPlanRows(List<QueryRowVO> rows) {
        if (rows == null || rows.isEmpty()) {
            return "[]";
        }
        try {
            String json = objectMapper.writeValueAsString(rows);
            if (json.length() <= ANALYZE_EXPLAIN_PLAN_TEXT_LIMIT) {
                return json;
            }
            return json.substring(0, ANALYZE_EXPLAIN_PLAN_TEXT_LIMIT) + "...(truncated)";
        } catch (Exception ex) {
            String fallback = rows.toString();
            if (fallback.length() <= ANALYZE_EXPLAIN_PLAN_TEXT_LIMIT) {
                return fallback;
            }
            return fallback.substring(0, ANALYZE_EXPLAIN_PLAN_TEXT_LIMIT) + "...(truncated)";
        }
    }

    private String buildExplainSql(String dbType, String sql) {
        String upper = safe(dbType).toUpperCase(Locale.ROOT);
        if ("SQLITE".equals(upper)) {
            return "EXPLAIN QUERY PLAN " + sql;
        }
        return "EXPLAIN " + sql;
    }

    private void applyDatabaseContext(java.sql.Connection connection,
                                      String dbType,
                                      String targetDatabaseName) throws java.sql.SQLException {
        String type = safe(dbType).toUpperCase(Locale.ROOT);
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

    private String resolveTargetDatabaseName(String configuredDatabaseName, String requestedDatabaseName) {
        String requested = safe(requestedDatabaseName);
        if (!requested.isBlank()) {
            return requested;
        }
        return safe(configuredDatabaseName);
    }


    /**
     * 关键操作：统一抽象 LLM 通道，支持 OpenAI API 与本地 CLI。
     */
    private ProviderResult generateByConfiguredProvider(AiGenerateSqlReq req,
                                                        AiConversationContextManager.ConversationGenerationContext context) {
        String userPrompt = buildProviderUserPrompt(req, context);
        LlmGatewayRequest gatewayRequest = new LlmGatewayRequest();
        gatewayRequest.setModelId(resolveRequestedModelId(req));
        gatewayRequest.setLegacyModelName(req.getModelName());
        gatewayRequest.setSystemPrompt(OPENAI_SYSTEM_PROMPT);
        gatewayRequest.setUserPrompt(userPrompt);
        gatewayRequest.setTaskLabel("生成 SQL");
        gatewayRequest.setTimeout(Duration.ofSeconds(30));
        gatewayRequest.setTemperature(0.1D);
        LlmGatewayResult gatewayResult = llmGatewayService.callStream(
            gatewayRequest,
            createLlmStreamListener(req.getSessionId(), "generate")
        );
        String sqlText = extractSql(gatewayResult.getContent());
        if (sqlText.isBlank()) {
            return new ProviderResult(gatewayResult.getContent(), gatewayResult.getReasoning(), gatewayResult.getUsage(), gatewayResult);
        }
        return new ProviderResult(sqlText, gatewayResult.getReasoning(), gatewayResult.getUsage(), gatewayResult);
    }

    private TextProviderResult generateTextByConfiguredProvider(AiGenerateSqlReq req,
                                                                AiConversationContextManager.ConversationGenerationContext context,
                                                                String systemPrompt,
                                                                String taskLabel) {
        String userPrompt = buildProviderUserPrompt(req, context);
        LlmGatewayRequest gatewayRequest = new LlmGatewayRequest();
        gatewayRequest.setModelId(resolveRequestedModelId(req));
        gatewayRequest.setLegacyModelName(req.getModelName());
        gatewayRequest.setSystemPrompt(systemPrompt);
        gatewayRequest.setUserPrompt(userPrompt);
        gatewayRequest.setTaskLabel(taskLabel);
        gatewayRequest.setTimeout(Duration.ofSeconds(30));
        gatewayRequest.setTemperature(0.1D);
        LlmGatewayResult gatewayResult = llmGatewayService.callStream(
            gatewayRequest,
            shouldStreamTaskLabel(taskLabel) ? createLlmStreamListener(req.getSessionId(), resolveActionTypeByTaskLabel(taskLabel)) : null
        );
        return new TextProviderResult(gatewayResult.getContent(), gatewayResult.getReasoning(), gatewayResult.getUsage(), gatewayResult);
    }

    private TextProviderResult generateRawTextByConfiguredProvider(AiGenerateSqlReq req,
                                                                   String systemPrompt,
                                                                   String userPrompt,
                                                                   String taskLabel) {
        LlmGatewayRequest gatewayRequest = new LlmGatewayRequest();
        gatewayRequest.setModelId(resolveRequestedModelId(req));
        gatewayRequest.setLegacyModelName(req.getModelName());
        gatewayRequest.setSystemPrompt(systemPrompt);
        gatewayRequest.setUserPrompt(userPrompt);
        gatewayRequest.setTaskLabel(taskLabel);
        gatewayRequest.setTimeout(Duration.ofSeconds(30));
        gatewayRequest.setTemperature(0.1D);
        LlmGatewayResult gatewayResult = llmGatewayService.callStream(
            gatewayRequest,
            shouldStreamTaskLabel(taskLabel) ? createLlmStreamListener(req.getSessionId(), resolveActionTypeByTaskLabel(taskLabel)) : null
        );
        return new TextProviderResult(gatewayResult.getContent(), gatewayResult.getReasoning(), gatewayResult.getUsage(), gatewayResult);
    }

    private boolean hasSqlSnippetInPrompt(String prompt) {
        String sql = extractSql(prompt);
        if (!sql.isBlank()) {
            return true;
        }
        String normalized = safe(prompt).trim().toLowerCase(Locale.ROOT);
        return normalized.contains("select ")
            || normalized.contains("with ")
            || normalized.contains("insert ")
            || normalized.contains("update ")
            || normalized.contains("delete ")
            || normalized.contains("merge ")
            || normalized.contains("create ")
            || normalized.contains("alter ")
            || normalized.contains("drop ")
            || normalized.contains("truncate ");
    }

    private String extractLatestSqlFromRecentDialogContext(String recentDialogContext) {
        String normalized = safe(recentDialogContext);
        if (normalized.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(normalized);
            if (root == null || !root.isArray()) {
                return "";
            }
            for (int index = root.size() - 1; index >= 0; index--) {
                JsonNode item = root.get(index);
                if (item == null || !item.isObject()) {
                    continue;
                }
                String sqlText = safe(item.path("sqlOutput").asText(""));
                if (!sqlText.isBlank()) {
                    return sqlText;
                }
            }
        } catch (Exception ignore) {
            // ignore malformed recent dialog context
        }
        return "";
    }

    private AiGenerateSqlReq appendSqlFallbackToPrompt(AiGenerateSqlReq req, String sqlText) {
        AiGenerateSqlReq next = new AiGenerateSqlReq();
        next.setConnectionId(req.getConnectionId());
        next.setSessionId(req.getSessionId());
        next.setDatabaseName(req.getDatabaseName());
        next.setModelId(req.getModelId());
        next.setModelName(req.getModelName());
        next.setMemoryEnabled(req.getMemoryEnabled());
        next.setDetailOutputEnabled(req.getDetailOutputEnabled());
        String prompt = safe(req.getPrompt());
        String normalizedSql = safe(sqlText);
        if (prompt.isBlank()) {
            next.setPrompt("SQL:\n" + normalizedSql);
        } else {
            next.setPrompt(prompt + "\n\nSQL:\n" + normalizedSql);
        }
        return next;
    }


    private String extractSql(String rawOutput) {
        String output = normalizeSqlText(rawOutput);
        if (output.isBlank()) {
            return "";
        }
        Matcher matcher = SQL_FENCE_PATTERN.matcher(output);
        while (matcher.find()) {
            String candidate = normalizeSqlText(matcher.group(1));
            if (looksLikeSql(candidate)) {
                return candidate;
            }
        }

        int idx = firstSqlKeywordIndex(output);
        if (idx >= 0) {
            return normalizeSqlText(output.substring(idx));
        }
        return "";
    }

    private String buildProviderUserPrompt(AiGenerateSqlReq req,
                                           AiConversationContextManager.ConversationGenerationContext context) {
        DatabaseBasicInfo basicInfo = loadDatabaseBasicInfo(req.getConnectionId(), req.getDatabaseName());
        String relatedIndexInfo = buildRelatedTableIndexInfo(req.getConnectionId(), req.getDatabaseName(), context.relatedTables());
        StringBuilder builder = new StringBuilder();
        builder.append("数据库基本信息:\n")
            .append("- 类型: ").append(basicInfo.dbType()).append('\n')
            .append("- 版本: ").append(basicInfo.dbVersion()).append('\n')
            .append("- 连接默认库: ").append(basicInfo.configuredDatabaseName());
        if (!basicInfo.requestDatabaseName().isBlank()) {
            builder.append('\n').append("- 本次目标库: ").append(basicInfo.requestDatabaseName());
        }
        if (!relatedIndexInfo.isBlank()) {
            builder.append("\n\n关联表索引字段:\n").append(relatedIndexInfo);
        }
        builder.append("\n\n");
        builder.append("用户需求:\n").append(req.getPrompt());
        builder.append("\n\n检索增强输入(含会话记忆):\n").append(context.retrievalInputForPrompt());
        builder.append("\n\nRAG Context:\n").append(context.promptContext());
        return builder.toString();
    }

    private DatabaseBasicInfo loadDatabaseBasicInfo(Long connectionId, String requestDatabaseName) {
        String dbType = "UNKNOWN";
        String dbVersion = "UNKNOWN";
        String configuredDatabaseName = "-";
        try {
            ConnectionEntity connectionEntity = connectionService.getConnectionEntity(connectionId);
            String entityDbType = safe(connectionEntity.getDbType());
            if (!entityDbType.isBlank()) {
                dbType = entityDbType;
            }
            String entityDatabaseName = safe(connectionEntity.getDatabaseName());
            if (!entityDatabaseName.isBlank()) {
                configuredDatabaseName = entityDatabaseName;
            }
        } catch (Exception ignored) {
            // 关键操作：元信息获取失败不影响主流程，使用默认值继续生成。
        }

        try (java.sql.Connection connection = connectionService.openTargetConnection(connectionId)) {
            java.sql.DatabaseMetaData metaData = connection.getMetaData();
            if (metaData != null) {
                String productName = safe(metaData.getDatabaseProductName());
                if (!productName.isBlank()) {
                    dbType = productName;
                }
                String productVersion = safe(metaData.getDatabaseProductVersion());
                if (!productVersion.isBlank()) {
                    dbVersion = productVersion;
                }
            }
        } catch (Exception ignored) {
            // 关键操作：数据库版本读取失败不阻断 AI 主流程。
        }
        return new DatabaseBasicInfo(dbType, dbVersion, configuredDatabaseName, safe(requestDatabaseName));
    }

    private String buildRelatedTableIndexInfo(Long connectionId, String databaseName, List<String> relatedTables) {
        if (relatedTables == null || relatedTables.isEmpty()) {
            return "";
        }
        LinkedHashSet<String> tableNames = new LinkedHashSet<>();
        for (String table : relatedTables) {
            String normalizedTable = normalizeRelatedTableName(table);
            if (normalizedTable.isBlank()) {
                continue;
            }
            tableNames.add(normalizedTable);
            if (tableNames.size() >= RELATED_TABLE_META_LIMIT) {
                break;
            }
        }
        if (tableNames.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (String tableName : tableNames) {
            try {
                TableDetailVO tableDetail = schemaService.getTableDetail(connectionId, databaseName, tableName);
                List<TableDetailVO.ColumnDetailVO> columns = tableDetail == null ? List.of() : tableDetail.getColumns();
                if (columns == null || columns.isEmpty()) {
                    builder.append("- ").append(tableName).append(": 未获取到索引字段元数据").append('\n');
                    continue;
                }
                LinkedHashSet<String> pkColumns = new LinkedHashSet<>();
                LinkedHashSet<String> indexedColumns = new LinkedHashSet<>();
                for (TableDetailVO.ColumnDetailVO column : columns) {
                    String columnName = safe(column == null ? "" : column.getColumnName());
                    if (columnName.isBlank()) {
                        continue;
                    }
                    if (Boolean.TRUE.equals(column.getPrimaryKey())) {
                        pkColumns.add(columnName);
                    }
                    if (Boolean.TRUE.equals(column.getIndexed())) {
                        indexedColumns.add(columnName);
                    }
                }
                if (pkColumns.isEmpty() && indexedColumns.isEmpty()) {
                    builder.append("- ").append(tableName).append(": 未识别到索引字段").append('\n');
                    continue;
                }
                String pkText = joinTopColumns(pkColumns, RELATED_INDEX_COLUMN_LIMIT);
                String indexedText = joinTopColumns(indexedColumns, RELATED_INDEX_COLUMN_LIMIT);
                builder.append("- ").append(tableName).append(": ");
                if (!pkText.isBlank()) {
                    builder.append("PK(").append(pkText).append(")");
                }
                if (!indexedText.isBlank()) {
                    if (!pkText.isBlank()) {
                        builder.append("; ");
                    }
                    builder.append("IDX(").append(indexedText).append(")");
                }
                builder.append('\n');
            } catch (Exception ex) {
                builder.append("- ").append(tableName).append(": 索引字段读取失败(").append(safe(ex.getMessage())).append(")").append('\n');
            }
        }
        return builder.toString().trim();
    }

    private String joinTopColumns(Set<String> columns, int limit) {
        if (columns == null || columns.isEmpty()) {
            return "";
        }
        List<String> list = new ArrayList<>(columns);
        int end = Math.min(limit, list.size());
        String joined = String.join(", ", list.subList(0, end));
        if (list.size() > end) {
            return joined + ", ...";
        }
        return joined;
    }

    private String normalizeRelatedTableName(String tableName) {
        String normalized = safe(tableName).replace("`", "").replace("\"", "");
        if (normalized.contains(".")) {
            String[] segments = normalized.split("\\.");
            normalized = safe(segments[segments.length - 1]);
        }
        return normalized;
    }

    private int firstSqlKeywordIndex(String text) {
        Matcher matcher = SQL_KEYWORD_PATTERN.matcher(Objects.toString(text, ""));
        if (matcher.find()) {
            return matcher.start();
        }
        return -1;
    }

    private boolean looksLikeSql(String text) {
        String normalized = normalizeSqlText(text);
        Matcher matcher = SQL_KEYWORD_PATTERN.matcher(normalized);
        return matcher.find() && matcher.start() == 0;
    }

    private String normalizeSqlText(String input) {
        String value = Objects.toString(input, "").trim();
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                value = value.substring(1, value.length() - 1).trim();
            }
        }
        return value
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\r", "\r")
            .replace("\\\"", "\"");
    }

    private String fallbackOutputText(String outputText) {
        String normalized = normalizeSqlText(outputText);
        if (!normalized.isBlank()) {
            return normalized;
        }
        return "未能生成可执行 SQL，请补充更明确的需求后重试。";
    }

    private ParsedChartResponse parseChartResponse(String rawOutput) {
        String normalized = safe(rawOutput);
        if (normalized.isBlank()) {
            return new ParsedChartResponse("", null, "", false);
        }
        ParsedChartResponse jsonParsed = tryParseChartJson(normalized);
        if (jsonParsed != null) {
            return jsonParsed;
        }
        return new ParsedChartResponse("", null, normalized, false);
    }

    private ParsedChartResponse tryParseChartJson(String rawOutput) {
        List<String> candidates = new ArrayList<>();
        String trimmed = safe(rawOutput);
        candidates.add(trimmed);
        Matcher fenceMatcher = Pattern.compile("(?is)```(?:json)?\\s*(\\{.*?\\})\\s*```").matcher(trimmed);
        if (fenceMatcher.find()) {
            candidates.add(fenceMatcher.group(1));
        }
        int jsonStart = trimmed.indexOf('{');
        int jsonEnd = trimmed.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            candidates.add(trimmed.substring(jsonStart, jsonEnd + 1));
        }

        for (String candidate : candidates) {
            try {
                JsonNode root = objectMapper.readTree(candidate);
                if (root == null || !root.isObject()) {
                    continue;
                }
                String sqlText = normalizeSqlText(safe(root.path("sqlText").asText("")));
                ChartConfigVO chartConfig = null;
                JsonNode configNode = root.path("chartConfig");
                if (configNode != null && configNode.isObject()) {
                    chartConfig = objectMapper.convertValue(configNode, ChartConfigVO.class);
                    normalizeChartConfig(chartConfig);
                }
                String configSummary = safe(root.path("configSummary").asText(""));
                if (configSummary.isBlank()) {
                    configSummary = buildChartConfigSummary(chartConfig);
                }
                return new ParsedChartResponse(sqlText, chartConfig, configSummary, true);
            } catch (Exception ignore) {
                // ignore malformed JSON candidate
            }
        }
        return null;
    }

    private void normalizeChartConfig(ChartConfigVO chartConfig) {
        if (chartConfig == null) {
            return;
        }
        chartConfig.setChartType(safe(chartConfig.getChartType()).toUpperCase(Locale.ROOT));
        chartConfig.setXField(safe(chartConfig.getXField()));
        chartConfig.setCategoryField(safe(chartConfig.getCategoryField()));
        chartConfig.setValueField(safe(chartConfig.getValueField()));
        chartConfig.setSortField(safe(chartConfig.getSortField()));
        chartConfig.setSortDirection(safe(chartConfig.getSortDirection()).toUpperCase(Locale.ROOT));
        chartConfig.setTitle(safe(chartConfig.getTitle()));
        chartConfig.setDescription(safe(chartConfig.getDescription()));
        if (chartConfig.getYFields() != null) {
            chartConfig.setYFields(
                chartConfig.getYFields().stream()
                    .map(this::safe)
                    .filter(item -> !item.isBlank())
                    .distinct()
                    .toList()
            );
        }
    }

    private ChartConfigValidationResult validateChartConfig(ChartConfigVO chartConfig) {
        if (chartConfig == null) {
            return new ChartConfigValidationResult(false, "图表配置为空");
        }
        String chartType = safe(chartConfig.getChartType()).toUpperCase(Locale.ROOT);
        if (chartType.isBlank()) {
            return new ChartConfigValidationResult(false, "chartType 不能为空");
        }
        switch (chartType) {
            case "LINE", "BAR", "TREND" -> {
                if (safe(chartConfig.getXField()).isBlank()) {
                    return new ChartConfigValidationResult(false, chartType + " 缺少 xField");
                }
                if (chartConfig.getYFields() == null || chartConfig.getYFields().isEmpty()) {
                    return new ChartConfigValidationResult(false, chartType + " 缺少 yFields");
                }
                return new ChartConfigValidationResult(true, "ok");
            }
            case "PIE" -> {
                if (safe(chartConfig.getCategoryField()).isBlank() || safe(chartConfig.getValueField()).isBlank()) {
                    return new ChartConfigValidationResult(false, "PIE 需要 categoryField 和 valueField");
                }
                return new ChartConfigValidationResult(true, "ok");
            }
            case "SCATTER" -> {
                if (safe(chartConfig.getXField()).isBlank()) {
                    return new ChartConfigValidationResult(false, "SCATTER 缺少 xField");
                }
                if (chartConfig.getYFields() == null || chartConfig.getYFields().size() != 1) {
                    return new ChartConfigValidationResult(false, "SCATTER 需要且仅支持 1 个 yField");
                }
                return new ChartConfigValidationResult(true, "ok");
            }
            default -> {
                return new ChartConfigValidationResult(false, "不支持的 chartType: " + chartType);
            }
        }
    }

    private String buildChartConfigSummary(ChartConfigVO chartConfig) {
        if (chartConfig == null) {
            return "未返回可用图表配置，请手动配置后生成图表。";
        }
        String type = safe(chartConfig.getChartType());
        if ("PIE".equals(type)) {
            return "图表类型: PIE，分类字段: " + safe(chartConfig.getCategoryField())
                + "，数值字段: " + safe(chartConfig.getValueField());
        }
        String yFields = chartConfig.getYFields() == null ? "" : String.join(", ", chartConfig.getYFields());
        return "图表类型: " + type + "，X轴: " + safe(chartConfig.getXField())
            + "，Y轴: " + yFields;
    }

    /**
     * 关键操作：生成 SQL 后强制做 AST 解析和表结构校验，减少不可执行 SQL 返回给前端。
     */
    private AstValidationResult validateByAst(AiGenerateSqlReq req, String sqlText) {
        String rawSql = safe(sqlText);
        if (rawSql.isBlank()) {
            return new AstValidationResult(false, "", "SQL 为空");
        }

        try {
            Statements statements = CCJSqlParserUtil.parseStatements(rawSql);
            if (statements == null || statements.getStatements().size() != 1) {
                return new AstValidationResult(false, rawSql, "仅支持单条 SQL 语句");
            }
            Statement statement = statements.getStatements().get(0);
            String normalizedSql = statement.toString();

            List<TableReference> referencedTables = collectReferencedTables(statement, rawSql);
            Set<String> schemaTables = loadSchemaTables(req.getConnectionId(), req.getDatabaseName());
            if (!referencedTables.isEmpty() && !schemaTables.isEmpty()) {
                ConnectionEntity connectionEntity = connectionService.getConnectionEntity(req.getConnectionId());
                String currentDatabaseName = safe(req.getDatabaseName());
                if (currentDatabaseName.isBlank()) {
                    currentDatabaseName = safe(connectionEntity.getDatabaseName());
                }
                String resolvedCurrentDatabaseName = currentDatabaseName;
                String dbType = safe(connectionEntity.getDbType());
                Map<String, Set<String>> qualifiedSchemaTableCache = new HashMap<>();
                List<String> missingTables = referencedTables.stream()
                    .filter(table -> !tableExistsInValidationScope(
                        req.getConnectionId(),
                        resolvedCurrentDatabaseName,
                        dbType,
                        table,
                        schemaTables,
                        qualifiedSchemaTableCache
                    ))
                    .map(TableReference::displayName)
                    .distinct()
                    .toList();
                if (!missingTables.isEmpty()) {
                    return new AstValidationResult(
                        false,
                        normalizedSql,
                        "引用了当前库不存在的表: " + String.join(", ", missingTables)
                    );
                }
            }

            return new AstValidationResult(true, normalizedSql, "AST 解析与结构校验通过");
        } catch (Exception ex) {
            return new AstValidationResult(false, rawSql, "AST 解析失败: " + ex.getMessage());
        }
    }

    private List<TableReference> collectReferencedTables(Statement statement, String rawSql) {
        TablesNamesFinder finder = new TablesNamesFinder();
        List<String> tables = new ArrayList<>(finder.getTableList(statement));
        Set<String> cteNames = extractCteNames(rawSql);
        return tables.stream()
            .map(this::buildTableReference)
            .filter(item -> !item.normalizedName().isBlank())
            .filter(item -> !cteNames.contains(item.normalizedName()))
            .distinct()
            .sorted(Comparator.comparing(TableReference::displayName, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    private Set<String> loadSchemaTables(Long connectionId, String databaseName) {
        SchemaOverviewVO overview = schemaService.getOverview(connectionId, databaseName);
        if (overview.getTableSummaries() == null || overview.getTableSummaries().isEmpty()) {
            return Set.of();
        }
        Set<String> tables = new HashSet<>();
        overview.getTableSummaries().stream()
            .sorted(Comparator.comparing(SchemaOverviewVO.TableSummaryVO::getTableName, String.CASE_INSENSITIVE_ORDER))
            .forEach(item -> {
                String name = normalizeIdentifier(item.getTableName());
                if (!name.isBlank()) {
                    tables.add(name);
                }
            });
        return tables;
    }

    private boolean tableExistsInValidationScope(Long connectionId,
                                                 String currentDatabaseName,
                                                 String dbType,
                                                 TableReference tableReference,
                                                 Set<String> currentSchemaTables,
                                                 Map<String, Set<String>> qualifiedSchemaTableCache) {
        if (currentSchemaTables.contains(tableReference.normalizedName())) {
            return true;
        }
        for (String qualifier : tableReference.qualifierCandidates()) {
            if (qualifier.isBlank() || qualifier.equals(normalizeIdentifier(currentDatabaseName))) {
                continue;
            }
            Set<String> qualifiedTables = qualifiedSchemaTableCache.computeIfAbsent(
                qualifier,
                key -> safeLoadSchemaTables(connectionId, key)
            );
            if (!qualifiedTables.isEmpty() && qualifiedTables.contains(tableReference.normalizedName())) {
                return true;
            }
        }
        return tableReference.qualifierCandidates().stream().anyMatch(item -> isSystemSchema(item, dbType));
    }

    private Set<String> safeLoadSchemaTables(Long connectionId, String databaseName) {
        try {
            return loadSchemaTables(connectionId, databaseName);
        } catch (Exception ex) {
            log.warn("加载限定 schema 表清单失败, connectionId={}, databaseName={}, reason={}",
                connectionId, databaseName, ex.getMessage());
            return Set.of();
        }
    }

    private boolean isSystemSchema(String schemaName, String dbType) {
        String normalizedSchema = normalizeIdentifier(schemaName);
        if (normalizedSchema.isBlank()) {
            return false;
        }
        if (SYSTEM_SCHEMA_NAMES.contains(normalizedSchema)) {
            return true;
        }
        String normalizedDbType = safe(dbType).toUpperCase(Locale.ROOT);
        return switch (normalizedDbType) {
            case "MYSQL" -> Set.of("information_schema", "performance_schema", "mysql", "sys").contains(normalizedSchema);
            case "POSTGRESQL" -> Set.of("information_schema", "pg_catalog", "pg_toast").contains(normalizedSchema);
            case "SQLSERVER" -> Set.of("information_schema", "sys").contains(normalizedSchema);
            case "SQLITE" -> Set.of("sqlite_schema", "sqlite_master", "sqlite_temp_schema", "sqlite_temp_master").contains(normalizedSchema);
            case "ORACLE" -> Set.of("sys", "system").contains(normalizedSchema);
            default -> false;
        };
    }

    private TableReference buildTableReference(String identifier) {
        List<String> segments = splitIdentifierSegments(identifier);
        if (segments.isEmpty()) {
            return new TableReference(safe(identifier), "", "", List.of());
        }
        String normalizedName = normalizeIdentifier(segments.get(segments.size() - 1));
        LinkedHashSet<String> qualifiers = new LinkedHashSet<>();
        for (int i = 0; i < segments.size() - 1; i++) {
            String qualifier = normalizeIdentifier(segments.get(i));
            if (!qualifier.isBlank()) {
                qualifiers.add(qualifier);
            }
        }
        return new TableReference(
            safe(identifier),
            String.join(".", segments),
            normalizedName,
            List.copyOf(qualifiers)
        );
    }

    private List<String> splitIdentifierSegments(String identifier) {
        String normalized = safe(identifier);
        if (normalized.isBlank()) {
            return List.of();
        }
        return Arrays.stream(normalized.split("\\."))
            .map(this::cleanIdentifierSegment)
            .filter(item -> !item.isBlank())
            .toList();
    }

    private String cleanIdentifierSegment(String identifier) {
        String normalized = safe(identifier)
            .replace("`", "")
            .replace("\"", "")
            .replace("[", "")
            .replace("]", "");
        return normalized.trim();
    }

    private Set<String> extractCteNames(String sql) {
        Matcher matcher = CTE_NAME_PATTERN.matcher(safe(sql).toLowerCase());
        Set<String> names = new HashSet<>();
        while (matcher.find()) {
            String cte = normalizeIdentifier(matcher.group(1));
            if (!cte.isBlank()) {
                names.add(cte);
            }
        }
        return names;
    }

    private String normalizeIdentifier(String identifier) {
        List<String> segments = splitIdentifierSegments(identifier);
        if (!segments.isEmpty()) {
            return segments.get(segments.size() - 1).toLowerCase(Locale.ROOT);
        }
        return "";
    }

    private String buildIntentAwareRetrievalHint(AiGenerateSqlReq req, ParsedIntentResponse parsedIntent) {
        ParsedIntentResponse resolvedIntent = parsedIntent == null
            ? new ParsedIntentResponse(IntentType.GENERATE_SQL, 0D, "", false, IntentRetrievalParams.defaultValue())
            : parsedIntent;
        IntentRetrievalParams params = resolvedIntent.retrievalParams() == null
            ? IntentRetrievalParams.defaultValue()
            : resolvedIntent.retrievalParams();
        String retrievalQuery = safe(params.query());
        if (retrievalQuery.isBlank()) {
            retrievalQuery = safe(req.getPrompt());
        }
        List<String> focusTables = new ArrayList<>();
        if (params.focusTables() != null) {
            for (String table : params.focusTables()) {
                String normalized = normalizeRelatedTableName(table);
                if (!normalized.isBlank() && !focusTables.contains(normalized)) {
                    focusTables.add(normalized);
                }
            }
        }
        StringBuilder keyInfoBuilder = new StringBuilder();
        keyInfoBuilder.append("检索关键词: ").append(retrievalQuery);
        if (!focusTables.isEmpty()) {
            keyInfoBuilder.append("\n重点表: ").append(String.join(",", focusTables));
        }
        if (!safe(resolvedIntent.reason()).isBlank()) {
            keyInfoBuilder.append("\n意图依据: ").append(safe(resolvedIntent.reason()));
        }
        if (resolvedIntent.intentType() != null) {
            keyInfoBuilder.append("\n意图类型: ").append(resolvedIntent.intentType().name());
        }
        double confidence = normalizeIntentConfidence(resolvedIntent.confidence());
        if (confidence > 0D) {
            keyInfoBuilder.append("\n意图置信度: ").append(String.format(Locale.ROOT, "%.2f", confidence));
        }
        return conversationContextManager.buildRetrievalInput(retrievalQuery, keyInfoBuilder.toString());
    }
    private TokenUsageStats resolveTokenUsage(OpenAiTextClient.TokenUsage providerUsage,
                                              String promptText,
                                              String completionText) {
        if (providerUsage != null) {
            int promptTokens = Math.max(0, providerUsage.promptTokens());
            int completionTokens = Math.max(0, providerUsage.completionTokens());
            int totalTokens = providerUsage.totalTokens();
            if (totalTokens <= 0) {
                totalTokens = promptTokens + completionTokens;
            }
            return new TokenUsageStats(promptTokens, completionTokens, totalTokens);
        }
        int promptTokens = estimateTokens(promptText);
        int completionTokens = estimateTokens(completionText);
        return new TokenUsageStats(promptTokens, completionTokens, promptTokens + completionTokens);
    }

    private AiStreamObserver currentStreamObserver() {
        return STREAM_OBSERVER.get();
    }

    private <T> T executeWithStreamObserver(AiStreamObserver observer, String actionType, Supplier<T> supplier) {
        AiStreamObserver previousObserver = STREAM_OBSERVER.get();
        String previousActionType = STREAM_ACTION_TYPE.get();
        if (observer != null) {
            STREAM_OBSERVER.set(observer);
        }
        if (!safe(actionType).isBlank()) {
            STREAM_ACTION_TYPE.set(actionType);
        }
        try {
            return supplier.get();
        } finally {
            if (previousObserver != null) {
                STREAM_OBSERVER.set(previousObserver);
            } else {
                STREAM_OBSERVER.remove();
            }
            if (previousActionType != null) {
                STREAM_ACTION_TYPE.set(previousActionType);
            } else {
                STREAM_ACTION_TYPE.remove();
            }
        }
    }

    private void emitIntentResolved(String sessionId,
                                    String actionType,
                                    String intentType,
                                    String intentLabel,
                                    Double intentConfidence,
                                    String reasoning) {
        AiStreamObserver observer = currentStreamObserver();
        if (observer == null) {
            return;
        }
        observer.onIntentResolved(sessionId, actionType, intentType, intentLabel, intentConfidence, reasoning);
    }

    private void addTraceStage(String sessionId, String actionType, List<AiTraceStageVO> traceStages, AiTraceStageVO stage) {
        if (traceStages == null || stage == null) {
            return;
        }
        traceStages.add(stage);
        AiStreamObserver observer = currentStreamObserver();
        if (observer != null) {
            observer.onStageUpdated(sessionId, actionType, stage);
        }
    }

    private void publishTraceSnapshot(String sessionId, String actionType, List<AiTraceStageVO> traceStages, long totalDurationMs) {
        AiStreamObserver observer = currentStreamObserver();
        if (observer == null) {
            return;
        }
        AiTraceVO trace = buildTrace(traceStages, totalDurationMs);
        if (trace != null) {
            observer.onTraceSnapshot(sessionId, actionType, trace);
        }
    }

    private void publishFinalResult(String sessionId, String actionType, AiStreamFinalVO finalResult) {
        AiStreamObserver observer = currentStreamObserver();
        if (observer == null || finalResult == null) {
            return;
        }
        observer.onResultFinal(sessionId, actionType, finalResult);
    }

    private AiStreamFinalVO buildFinalResult(String actionType, Object result) {
        AiStreamFinalVO finalVO = new AiStreamFinalVO();
        finalVO.setActionType(actionType);
        if (result instanceof AiGenerateSqlVO generateSqlVO) {
            finalVO.setGenerateSql(generateSqlVO);
        } else if (result instanceof AiAutoQueryVO autoQueryVO) {
            finalVO.setAutoQuery(autoQueryVO);
        } else if (result instanceof AiTextResponseVO textResponseVO) {
            finalVO.setTextResponse(textResponseVO);
        } else if (result instanceof AiGenerateChartVO generateChartVO) {
            finalVO.setGenerateChart(generateChartVO);
        } else if (result instanceof AiRepairVO repairVO) {
            finalVO.setRepair(repairVO);
        }
        return finalVO;
    }

    private LlmStreamListener createLlmStreamListener(String sessionId, String actionType) {
        AiStreamObserver observer = currentStreamObserver();
        if (observer == null) {
            return null;
        }
        return new LlmStreamListener() {
            @Override
            public void onThinkingDelta(String deltaText, String accumulatedText) {
                observer.onThinkingDelta(sessionId, actionType, deltaText, accumulatedText);
            }

            @Override
            public void onOutputDelta(String deltaText, String accumulatedText) {
                observer.onOutputDelta(sessionId, actionType, deltaText, accumulatedText);
            }
        };
    }

    private String resolveActionTypeByTaskLabel(String taskLabel) {
        String normalized = safe(taskLabel);
        if (normalized.contains("解释")) {
            return "explain";
        }
        if (normalized.contains("分析")) {
            return "analyze";
        }
        if (normalized.contains("图表")) {
            return "generate-chart";
        }
        if (normalized.contains("修复")) {
            return "repair";
        }
        return "generate";
    }

    private boolean shouldStreamTaskLabel(String taskLabel) {
        String normalized = safe(taskLabel);
        return "生成 SQL".equals(normalized)
            || normalized.contains("含义解释")
            || normalized.contains("合理性分析")
            || normalized.contains("图表方案")
            || normalized.contains("SQL 修复");
    }

    private int estimateTokens(String text) {
        int length = safe(text).length();
        if (length <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(length / 4.0));
    }

    private boolean resolveDetailOutputEnabled(AiGenerateSqlReq req) {
        if (req.getDetailOutputEnabled() != null) {
            return Boolean.TRUE.equals(req.getDetailOutputEnabled());
        }
        return Boolean.TRUE.equals(aiConfigService.getConfig().getDetailOutputEnabled());
    }

    private boolean resolveDetailOutputEnabled(AiRepairReq req) {
        if (req.getDetailOutputEnabled() != null) {
            return Boolean.TRUE.equals(req.getDetailOutputEnabled());
        }
        return Boolean.TRUE.equals(aiConfigService.getConfig().getDetailOutputEnabled());
    }

    private AiTraceVO buildTrace(List<AiTraceStageVO> stages, long totalDurationMs) {
        if (stages == null || stages.isEmpty()) {
            return null;
        }
        AiTraceVO trace = new AiTraceVO();
        trace.setStages(stages);
        trace.setStageCount(stages.size());
        trace.setTotalDurationMs(Math.max(0L, totalDurationMs));
        return trace;
    }

    private void mergeTraceStages(List<AiTraceStageVO> targetStages, AiTraceVO delegatedTrace) {
        if (targetStages == null || delegatedTrace == null || delegatedTrace.getStages() == null || delegatedTrace.getStages().isEmpty()) {
            return;
        }
        targetStages.addAll(
            delegatedTrace.getStages()
                .stream()
                .filter(Objects::nonNull)
                .toList()
        );
    }

    private AiTraceStageVO buildTraceStage(String stageCode,
                                           String stageLabel,
                                           String stageType,
                                           String status,
                                           long durationMs,
                                           List<AiTraceFieldVO> inputFields,
                                           List<AiTraceFieldVO> outputFields,
                                           AiTraceLlmCallVO llmCall) {
        AiTraceStageVO stage = new AiTraceStageVO();
        stage.setStageCode(stageCode);
        stage.setStageLabel(stageLabel);
        stage.setStageType(stageType);
        stage.setStatus(status);
        stage.setDurationMs(Math.max(0L, durationMs));
        stage.setInputFields(filterTraceFields(inputFields));
        stage.setOutputFields(filterTraceFields(outputFields));
        stage.setLlmCall(llmCall);
        return stage;
    }

    private List<AiTraceFieldVO> filterTraceFields(List<AiTraceFieldVO> fields) {
        if (fields == null || fields.isEmpty()) {
            return List.of();
        }
        return fields.stream()
            .filter(Objects::nonNull)
            .filter(field -> !safe(field.getFieldLabel()).isBlank() || !safe(field.getFieldCode()).isBlank() || !safe(field.getFieldValue()).isBlank())
            .toList();
    }

    private AiTraceFieldVO buildTraceField(String fieldCode, String fieldLabel, Object fieldValue) {
        AiTraceFieldVO field = new AiTraceFieldVO();
        field.setFieldCode(fieldCode);
        field.setFieldLabel(fieldLabel);
        field.setFieldValue(formatTraceValue(fieldValue));
        return field;
    }

    private String formatTraceValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text.trim();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception ignore) {
            return String.valueOf(value).trim();
        }
    }

    private AiTraceLlmCallVO buildTraceLlmCall(LlmGatewayResult result) {
        if (result == null) {
            return null;
        }
        AiTraceLlmCallVO llmCall = new AiTraceLlmCallVO();
        llmCall.setModelId(safe(result.getModelId()));
        llmCall.setProviderType(safe(result.getProviderType()));
        llmCall.setProviderName(safe(result.getProviderName()));
        llmCall.setActualModel(safe(result.getActualModel()));
        llmCall.setSystemPrompt(safe(result.getSystemPrompt()));
        llmCall.setUserPrompt(safe(result.getUserPrompt()));
        llmCall.setFullOutput(safe(result.getFullOutput()));
        llmCall.setThinkingContent(safe(result.getThinkingContent()));
        llmCall.setProviderRequestId(safe(result.getProviderRequestId()));
        llmCall.setStreaming(Boolean.TRUE.equals(result.getStreaming()));
        OpenAiTextClient.TokenUsage usage = result.getUsage();
        if (usage != null) {
            llmCall.setPromptTokens(Math.max(0, usage.promptTokens()));
            llmCall.setCompletionTokens(Math.max(0, usage.completionTokens()));
            llmCall.setTotalTokens(Math.max(0, usage.totalTokens()));
        }
        return llmCall;
    }

    private AiTraceStageVO buildRagTraceStage(String stageCode,
                                              String stageLabel,
                                              String retrievalInput,
                                              RagPromptContext ragPromptContext,
                                              long durationMs) {
        return buildTraceStage(
            stageCode,
            stageLabel,
            "rag",
            "success",
            durationMs,
            List.of(buildTraceField("retrievalInput", "retrievalInput", retrievalInput)),
            List.of(
                buildTraceField("hit", "hit", ragPromptContext == null ? null : ragPromptContext.getHit()),
                buildTraceField("rerankEnabled", "rerankEnabled", ragPromptContext == null ? null : ragPromptContext.getRerankEnabled()),
                buildTraceField("rerankProvider", "rerankProvider", ragPromptContext == null ? "" : ragPromptContext.getRerankProvider()),
                buildTraceField("rerankDetails", "rerankDetails", ragPromptContext == null ? List.of() : ragPromptContext.getRerankDetails()),
                buildTraceField("relatedTables", "relatedTables", ragPromptContext == null ? List.of() : ragPromptContext.getRelatedTables()),
                buildTraceField("relatedColumns", "relatedColumns", ragPromptContext == null ? List.of() : ragPromptContext.getRelatedColumns()),
                buildTraceField("historySqlSamples", "historySqlSamples", ragPromptContext == null ? List.of() : ragPromptContext.getHistorySqlSamples()),
                buildTraceField("promptContext", "promptContext", ragPromptContext == null ? "" : ragPromptContext.getPromptContext())
            ),
            null
        );
    }

    private AiTraceStageVO buildGenerationContextTraceStage(AiConversationContextManager.ConversationGenerationContext context,
                                                            long durationMs) {
        return buildTraceStage(
            "generation_context",
            "构建上下文",
            "pipeline",
            "success",
            durationMs,
            List.of(),
            List.of(
                buildTraceField("relatedTables", "relatedTables", context == null ? List.of() : context.relatedTables()),
                buildTraceField("promptContext", "promptContext", context == null ? "" : context.promptContext())
            ),
            null
        );
    }

    private String safe(String input) {
        return Objects.toString(input, "").trim();
    }

    private String resolveRequestedModelId(AiGenerateSqlReq req) {
        String modelId = safe(req.getModelId());
        if (!modelId.isBlank()) {
            return modelId;
        }
        return safe(req.getModelName());
    }

    /**
     * 关键操作：记录 AI 链路分阶段耗时，便于快速定位性能瓶颈。
     */
    private static final class StepTimer {
        private final long startAt;
        private long lastMarkAt;
        private final List<String> steps = new ArrayList<>();

        private StepTimer() {
            this.startAt = System.currentTimeMillis();
            this.lastMarkAt = this.startAt;
        }

        private void mark(String stepName) {
            long now = System.currentTimeMillis();
            steps.add(stepName + "=" + Math.max(0L, now - lastMarkAt) + "ms");
            lastMarkAt = now;
        }

        private String stepsSummary() {
            if (steps.isEmpty()) {
                return "-";
            }
            return String.join(", ", steps);
        }

        private long totalElapsedMs() {
            return Math.max(0L, System.currentTimeMillis() - startAt);
        }
    }

    private static final class ProviderResult {
        private final String sqlText;
        private final String reasoning;
        private final OpenAiTextClient.TokenUsage usage;
        private final LlmGatewayResult gatewayResult;

        private ProviderResult(String sqlText, String reasoning, OpenAiTextClient.TokenUsage usage) {
            this(sqlText, reasoning, usage, null);
        }

        private ProviderResult(String sqlText,
                               String reasoning,
                               OpenAiTextClient.TokenUsage usage,
                               LlmGatewayResult gatewayResult) {
            this.sqlText = sqlText;
            this.reasoning = reasoning;
            this.usage = usage;
            this.gatewayResult = gatewayResult;
        }

        private String sqlText() {
            return sqlText;
        }

        private String reasoning() {
            return reasoning;
        }

        private OpenAiTextClient.TokenUsage usage() {
            return usage;
        }

        private LlmGatewayResult gatewayResult() {
            return gatewayResult;
        }
    }

    private static final class TextProviderResult {
        private final String content;
        private final String reasoning;
        private final OpenAiTextClient.TokenUsage usage;
        private final LlmGatewayResult gatewayResult;

        private TextProviderResult(String content, String reasoning, OpenAiTextClient.TokenUsage usage) {
            this(content, reasoning, usage, null);
        }

        private TextProviderResult(String content,
                                   String reasoning,
                                   OpenAiTextClient.TokenUsage usage,
                                   LlmGatewayResult gatewayResult) {
            this.content = content;
            this.reasoning = reasoning;
            this.usage = usage;
            this.gatewayResult = gatewayResult;
        }

        private String content() {
            return content;
        }

        private String reasoning() {
            return reasoning;
        }

        private OpenAiTextClient.TokenUsage usage() {
            return usage;
        }

        private LlmGatewayResult gatewayResult() {
            return gatewayResult;
        }
    }

    private record TokenUsageStats(int promptTokens, int completionTokens, int totalTokens) {
    }

    private record ParsedRepairResult(String errorExplanation, String repairedSql) {
    }

    private record ParsedChartResponse(String sqlText,
                                       ChartConfigVO chartConfig,
                                       String configSummary,
                                       boolean parsed) {
    }

    private record ParsedIntentResponse(IntentType intentType,
                                        double confidence,
                                        String reason,
                                        boolean parsed,
                                        IntentRetrievalParams retrievalParams) {
    }

    private record IntentRetrievalParams(int sessionTopK,
                                         int globalTopK,
                                         String query,
                                         List<String> focusTables) {
        private static IntentRetrievalParams defaultValue() {
            return new IntentRetrievalParams(4, 6, "", List.of());
        }
    }

    private record SqlExtractionResult(boolean hasSql, List<String> sqlList, boolean parsed) {
    }

    private record ParsedSqlInsights(boolean parseSuccess,
                                     String normalizedSql,
                                     List<String> tables,
                                     List<String> columns,
                                     List<String> aggregateFunctions,
                                     int joinCount,
                                     boolean hasWhere,
                                     boolean hasGroupBy,
                                     boolean hasOrderBy,
                                     String message) {
        private static ParsedSqlInsights empty(String message) {
            return new ParsedSqlInsights(false, "", List.of(), List.of(), List.of(), 0, false, false, false, message);
        }

        private static ParsedSqlInsights empty(String message, String sql) {
            return new ParsedSqlInsights(false, sql, List.of(), List.of(), List.of(), 0, false, false, false, message);
        }

        private String summary() {
            return "parseSuccess=" + parseSuccess
                + ", tables=" + String.join(",", tables)
                + ", columns=" + String.join(",", columns)
                + ", aggregates=" + String.join(",", aggregateFunctions)
                + ", joinCount=" + joinCount
                + ", hasWhere=" + hasWhere
                + ", hasGroupBy=" + hasGroupBy
                + ", hasOrderBy=" + hasOrderBy
                + ", message=" + message;
        }
    }

    private record ExactMetadataContext(String contextText, double coverage, boolean hasMetadata) {
    }

    private record ChartConfigValidationResult(boolean valid, String message) {
    }

    private record AstValidationResult(boolean valid, String sqlText, String message) {
    }

    private record TableReference(String rawName,
                                  String displayName,
                                  String normalizedName,
                                  List<String> qualifierCandidates) {
    }

    private record DatabaseBasicInfo(String dbType,
                                     String dbVersion,
                                     String configuredDatabaseName,
                                     String requestDatabaseName) {
    }

    private record IntentResult(IntentType intentType, double confidence, String reason) {
    }

    private enum IntentType {
        GENERATE_SQL("生成 SQL"),
        EXPLAIN_SQL("解释 SQL"),
        ANALYZE_SQL("分析 SQL"),
        GENERATE_CHART("图表方案");

        private final String label;

        IntentType(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

}
