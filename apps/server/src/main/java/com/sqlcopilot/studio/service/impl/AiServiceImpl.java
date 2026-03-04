package com.sqlcopilot.studio.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqlcopilot.studio.dto.ai.*;
import com.sqlcopilot.studio.dto.schema.*;
import com.sqlcopilot.studio.dto.sql.QueryRowVO;
import com.sqlcopilot.studio.entity.ConnectionEntity;
import com.sqlcopilot.studio.service.AiConfigService;
import com.sqlcopilot.studio.service.AiService;
import com.sqlcopilot.studio.service.ConnectionService;
import com.sqlcopilot.studio.service.SchemaService;
import com.sqlcopilot.studio.service.rag.RagRetrievalService;
import com.sqlcopilot.studio.service.rag.model.RagPromptContext;
import com.sqlcopilot.studio.util.BusinessException;
import com.sqlcopilot.studio.util.ResultSetConverter;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AiServiceImpl implements AiService {

    private static final Pattern SQL_FENCE_PATTERN = Pattern.compile("(?is)```(?:sql)?\\s*(.*?)```");
    private static final Pattern CTE_NAME_PATTERN = Pattern.compile("(?is)(?:^|,|\\s)([a-zA-Z_][a-zA-Z0-9_]*)\\s+as\\s*\\(");
    private static final Pattern COMMAND_TOKEN_PATTERN = Pattern.compile("\"([^\"]*)\"|'([^']*)'|(\\S+)");
    private static final Pattern SQL_KEYWORD_PATTERN = Pattern.compile("(?is)\\b(select|with|update|delete|insert)\\b");
    private static final int RELATED_TABLE_META_LIMIT = 8;
    private static final int RELATED_INDEX_COLUMN_LIMIT = 12;
    private static final Set<String> CODEX_SUBCOMMANDS = Set.of(
        "exec", "e", "review", "login", "logout", "mcp", "mcp-server",
        "app-server", "app", "completion", "sandbox", "debug", "apply",
        "a", "resume", "fork", "cloud", "features", "help"
    );
    private static final long CLI_TIMEOUT_SECONDS = 45L;
    private static final int ANALYZE_EXPLAIN_PLAN_ROW_LIMIT = 200;
    private static final int ANALYZE_EXPLAIN_PLAN_TEXT_LIMIT = 6000;
    private static final double AUTO_INTENT_MIN_CONFIDENCE = 0.70D;
    private static final String OPENAI_SYSTEM_PROMPT = "你是数据库 SQL 专家。基于提供的 RAG 上下文生成 SQL。仅返回可执行 SQL，不要输出解释。";
    private static final String INTENT_CLASSIFY_SYSTEM_PROMPT = """
        你是数据库助手的意图识别器。请根据用户输入识别唯一意图，并输出严格 JSON，不要输出任何额外文本。
        JSON 格式：
        {
          "intentType": "GENERATE_SQL|EXPLAIN_SQL|ANALYZE_SQL|GENERATE_CHART",
          "confidence": 0.00,
          "reason": "中文简述判断依据"
        }
        识别规则：
        1) 用户要“画图/可视化/图表/趋势图/柱状图/饼图”等，intentType=GENERATE_CHART；
        2) 用户要“解释 SQL 含义”，intentType=EXPLAIN_SQL；
        3) 用户要“分析 SQL 合理性/性能风险”，intentType=ANALYZE_SQL；
        4) 其他自然语言查询默认 intentType=GENERATE_SQL。
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
        1) chartType=LINE/BAR/TREND 时必须提供 xField + yFields(至少1项)；
        2) chartType=PIE 时必须提供 categoryField + valueField；
        3) chartType=SCATTER 时必须提供 xField + yFields(仅1项)；
        4) SQL 必须可执行，不要使用 markdown 代码块。
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
        You are a database relationship inference assistant.
        Based on selected table metadata and known foreign key relations, infer possible additional relationships.
        Output strict JSON only, no markdown and no extra text.
        JSON format:
        {
          "relations": [
            {
              "sourceTable": "orders",
              "sourceColumn": "customer_id",
              "targetTable": "customers",
              "targetColumn": "id",
              "confidence": 0.82,
              "reason": "column naming and semantic match"
            }
          ]
        }
        Rules:
        1) Only infer relations between selected tables.
        2) Keep confidence between 0 and 1.
        3) Do not return exact duplicate pairs from provided foreign keys.
        """;
    private static final Logger log = LoggerFactory.getLogger(AiServiceImpl.class);

    private final SchemaService schemaService;
    private final AiConfigService aiConfigService;
    private final ConnectionService connectionService;
    private final RagRetrievalService ragRetrievalService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    public AiServiceImpl(SchemaService schemaService,
                         AiConfigService aiConfigService,
                         ConnectionService connectionService,
                         RagRetrievalService ragRetrievalService,
                         ObjectMapper objectMapper) {
        this.schemaService = schemaService;
        this.aiConfigService = aiConfigService;
        this.connectionService = connectionService;
        this.ragRetrievalService = ragRetrievalService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiGenerateSqlVO generateSql(AiGenerateSqlReq req) {
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
        // 关键操作：先将用户需求向量化并做 Qdrant 分层检索，构造 Prompt 上下文。
        String retrievalInput = buildRetrievalInput(req.getPrompt());
        timer.mark("build_retrieval_input");
        RagPromptContext ragPromptContext = ragRetrievalService.retrievePromptContext(
            req.getConnectionId(),
            req.getDatabaseName(),
            retrievalInput
        );
        timer.mark("rag_retrieve");
        GenerationContext generationContext = buildGenerationContext(req, ragPromptContext);
        timer.mark("build_generation_context");

        String reasoning;
        String generatedSql;
        boolean fallbackUsed = false;
        try {
            ProviderResult result = generateByConfiguredProvider(req, generationContext);
            generatedSql = safe(result.sqlText());
            reasoning = safe(result.reasoning());
            timer.mark("provider_generate_sql");
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
        }

        if (!looksLikeSql(generatedSql)) {
            fallbackUsed = true;
            generatedSql = fallbackOutputText(generatedSql);
            timer.mark("extract_sql_or_fallback");
            if (!reasoning.isBlank()) {
                reasoning = reasoning + "；";
            }
            reasoning = reasoning + "模型未返回可识别 SQL，已返回说明内容。";
        } else {
            AstValidationResult astResult = validateByAst(req, generatedSql);
            timer.mark("ast_validate");
            if (!astResult.valid()) {
                fallbackUsed = true;
                generatedSql = fallbackOutputText("SQL 结构校验未通过: " + astResult.message() + "\n模型输出:\n" + generatedSql);
                if (!reasoning.isBlank()) {
                    reasoning = reasoning + "；";
                }
                reasoning = reasoning + "AST 校验未通过，已返回说明内容。原因: " + astResult.message();
            } else {
                generatedSql = astResult.sqlText();
                if (!reasoning.isBlank()) {
                    reasoning = reasoning + "；";
                }
                reasoning = reasoning + astResult.message();
            }
        }

        AiGenerateSqlVO vo = new AiGenerateSqlVO();
        vo.setSqlText(generatedSql);
        vo.setReasoning(reasoning);
        vo.setFallbackUsed(fallbackUsed);
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
        return vo;
    }

    @Override
    public AiAutoQueryVO autoQuery(AiGenerateSqlReq req) {
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

        IntentResult intentResult = identifyIntent(req);
        timer.mark("identify_intent");
        IntentType intentType = intentResult.intentType();
        boolean hasSqlSnippet = hasSqlSnippetInPrompt(req.getPrompt());
        timer.mark("detect_sql_snippet");

        AiAutoQueryVO vo = new AiAutoQueryVO();
        vo.setIntentType(intentType.name());
        vo.setIntentLabel(intentType.label());
        vo.setIntentConfidence(intentResult.confidence());

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
        } else if (intentType == IntentType.EXPLAIN_SQL) {
            if (!hasSqlSnippet) {
                throw new BusinessException(400, "自动识别为“解释 SQL”时，提示词中必须包含 SQL 片段");
            }
            AiTextResponseVO explained = explainSql(req);
            timer.mark("route_explain_sql");
            vo.setContent(explained.getContent());
            vo.setFallbackUsed(Boolean.TRUE.equals(explained.getFallbackUsed()));
            vo.setReasoning(joinReasoning(baseReasoning, explained.getReasoning()));
        } else if (intentType == IntentType.ANALYZE_SQL) {
            if (!hasSqlSnippet) {
                throw new BusinessException(400, "自动识别为“分析 SQL”时，提示词中必须包含 SQL 片段");
            }
            AiTextResponseVO analyzed = analyzeSql(req);
            timer.mark("route_analyze_sql");
            vo.setContent(analyzed.getContent());
            vo.setFallbackUsed(Boolean.TRUE.equals(analyzed.getFallbackUsed()));
            vo.setReasoning(joinReasoning(baseReasoning, analyzed.getReasoning()));
        } else {
            AiGenerateChartVO chart = generateChart(req);
            timer.mark("route_generate_chart");
            vo.setSqlText(chart.getSqlText());
            vo.setChartConfig(chart.getChartConfig());
            vo.setConfigSummary(chart.getConfigSummary());
            vo.setFallbackUsed(Boolean.TRUE.equals(chart.getFallbackUsed()));
            vo.setReasoning(joinReasoning(baseReasoning, chart.getReasoning()));
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
        return vo;
    }

    @Override
    public AiGenerateChartVO generateChart(AiGenerateSqlReq req) {
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

        String retrievalInput = buildRetrievalInput(req.getPrompt());
        timer.mark("build_retrieval_input");
        RagPromptContext ragPromptContext = ragRetrievalService.retrievePromptContext(
            req.getConnectionId(),
            req.getDatabaseName(),
            retrievalInput
        );
        timer.mark("rag_retrieve");
        GenerationContext generationContext = buildGenerationContext(req, ragPromptContext);
        timer.mark("build_generation_context");

        String reasoning;
        String rawContent;
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
            timer.mark("provider_generate_chart");
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
        }

        ParsedChartResponse parsed = parseChartResponse(rawContent);
        timer.mark("parse_chart_response");
        String sqlText = safe(parsed.sqlText());
        ChartConfigVO chartConfig = parsed.chartConfig();
        String configSummary = safe(parsed.configSummary());
        if (!parsed.parsed()) {
            fallbackUsed = true;
            if (!reasoning.isBlank()) {
                reasoning += "；";
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
                reasoning += "；";
            }
            reasoning += "未识别到可执行 SQL，已返回说明内容。";
        }

        if (chartConfig != null) {
            ChartConfigValidationResult validationResult = validateChartConfig(chartConfig);
            timer.mark("validate_chart_config");
            if (!validationResult.valid()) {
                fallbackUsed = true;
                chartConfig = null;
                if (!reasoning.isBlank()) {
                    reasoning += "；";
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

        AiGenerateChartVO vo = new AiGenerateChartVO();
        vo.setSqlText(sqlText);
        vo.setChartConfig(chartConfig);
        vo.setConfigSummary(configSummary);
        vo.setReasoning(reasoning);
        vo.setFallbackUsed(fallbackUsed);
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
        return vo;
    }

    @Override
    public AiTextResponseVO explainSql(AiGenerateSqlReq req) {
        return generateTextResponse(req, "EXPLAIN-TEXT", EXPLAIN_SQL_SYSTEM_PROMPT, "SQL 含义解释");
    }

    @Override
    public AiTextResponseVO analyzeSql(AiGenerateSqlReq req) {
        return generateTextResponse(req, "ANALYZE-SQL", ANALYZE_SQL_SYSTEM_PROMPT, "SQL 合理性分析");
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
            providerReq.setPrompt("Infer possible relationships between selected tables and output strict JSON.");

            String contextText = buildErInferenceContext(req);
            List<String> relatedTables = tables.stream()
                .map(ErTableNodeVO::getTableName)
                .map(this::safe)
                .filter(item -> !item.isBlank())
                .toList();
            GenerationContext generationContext = new GenerationContext(contextText, relatedTables);
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

    @Override
    public AiRepairVO repairSql(AiRepairReq req) {
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

        AiRepairVO vo = new AiRepairVO();
        try {
            AiGenerateSqlReq providerReq = buildRepairGenerateReq(req, sourceSql, errorMessage);
            timer.mark("build_repair_prompt");
            String retrievalInput = buildRetrievalInput(providerReq.getPrompt(), sourceSql + "\n" + errorMessage);
            timer.mark("build_retrieval_input");
            RagPromptContext ragPromptContext = ragRetrievalService.retrievePromptContext(
                req.getConnectionId(),
                req.getDatabaseName(),
                retrievalInput
            );
            timer.mark("rag_retrieve");
            GenerationContext generationContext = buildGenerationContext(providerReq, ragPromptContext);
            timer.mark("build_generation_context");
            TextProviderResult providerResult = generateTextByConfiguredProvider(
                providerReq,
                generationContext,
                REPAIR_SQL_SYSTEM_PROMPT,
                "SQL 修复"
            );
            timer.mark("provider_repair_sql");

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
                vo.setRepairNote("模型修复完成");
            }
            timer.mark("assemble_response");

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
            return vo;
        } catch (Exception ex) {
            ParsedRepairResult fallback = fallbackRepairResult(sourceSql, errorMessage);
            vo.setRepaired(Boolean.FALSE);
            vo.setErrorExplanation(fallback.errorExplanation());
            vo.setRepairedSql(fallback.repairedSql());
            vo.setRepairNote("模型修复失败，已使用规则兜底: " + safe(ex.getMessage()));
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
        Set<String> foreignKeySet = new HashSet<>();
        List<ErRelationVO> fkList = foreignKeyRelations == null ? List.of() : foreignKeyRelations;
        fkList.forEach(item -> foreignKeySet.add(buildErRelationKey(item)));

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
            relation.setConfidence(Math.max(0D, Math.min(1D, confidence)));
            relation.setReason(safe(item.getReason()));
            if (relation.getReason().isBlank()) {
                relation.setReason("ai inferred relation");
            }
            String key = buildErRelationKey(relation);
            if (foreignKeySet.contains(key)) {
                continue;
            }
            dedup.putIfAbsent(key, relation);
        }
        List<ErRelationVO> relations = new ArrayList<>(dedup.values());
        relations.sort(Comparator
            .comparing(ErRelationVO::getSourceTable, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ErRelationVO::getTargetTable, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ErRelationVO::getSourceColumn, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ErRelationVO::getTargetColumn, String.CASE_INSENSITIVE_ORDER));
        return relations;
    }

    private String buildErRelationKey(ErRelationVO relation) {
        return normalizeIdentifier(relation.getSourceTable()) + "|"
            + normalizeIdentifier(relation.getSourceColumn()) + "|"
            + normalizeIdentifier(relation.getTargetTable()) + "|"
            + normalizeIdentifier(relation.getTargetColumn());
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
        aiReq.setModelName(req.getModelName());
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
        TextProviderResult providerResult;
        try {
            // 关键操作：意图识别不走 RAG 检索，仅基于当前输入降低噪声和延迟。
            providerResult = generateTextByConfiguredProvider(
                req,
                new GenerationContext("", List.of()),
                INTENT_CLASSIFY_SYSTEM_PROMPT,
                "意图识别"
            );
            timer.mark("provider_identify_intent");
        } catch (Exception ex) {
            timer.mark("provider_identify_intent_failed");
            log.info(
                "[AI-IDENTIFY-INTENT-TIMING] connectionId={}, sessionId={}, databaseName={}, modelName={}, steps={}, totalMs={}",
                req.getConnectionId(),
                safe(req.getSessionId()),
                safe(req.getDatabaseName()),
                safe(req.getModelName()),
                timer.stepsSummary(),
                timer.totalElapsedMs()
            );
            throw new BusinessException(400, "意图识别失败: " + safe(ex.getMessage()));
        }

        ParsedIntentResponse parsed = parseIntentResponse(providerResult.content());
        timer.mark("parse_intent_response");
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
                return new ParsedIntentResponse(intentType, confidence, reason, true);
            } catch (Exception ignore) {
                // ignore malformed JSON candidate
            }
        }
        return null;
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

    private AiTextResponseVO generateTextResponse(AiGenerateSqlReq req,
                                                  String logScene,
                                                  String systemPrompt,
                                                  String taskLabel) {
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

        String retrievalInput = buildRetrievalInput(req.getPrompt());
        timer.mark("build_retrieval_input");
        RagPromptContext ragPromptContext = ragRetrievalService.retrievePromptContext(
            req.getConnectionId(),
            req.getDatabaseName(),
            retrievalInput
        );
        timer.mark("rag_retrieve");
        GenerationContext generationContext = buildGenerationContext(req, ragPromptContext);
        timer.mark("build_generation_context");
        String extraPromptContext = "";
        if ("ANALYZE-SQL".equalsIgnoreCase(logScene)) {
            extraPromptContext = tryBuildExplainPlanPromptContext(req);
            timer.mark("try_explain_sql");
        }
        String mergedPromptContext = mergePromptContext(generationContext.promptContext(), extraPromptContext);
        if (!Objects.equals(mergedPromptContext, generationContext.promptContext())) {
            generationContext = new GenerationContext(mergedPromptContext, generationContext.relatedTables());
            timer.mark("append_explain_plan_context");
        }

        String content;
        String reasoning;
        boolean fallbackUsed = false;
        try {
            TextProviderResult result = generateTextByConfiguredProvider(req, generationContext, systemPrompt, taskLabel);
            content = result.content();
            reasoning = result.reasoning();
            timer.mark("provider_generate_text");
        } catch (Exception ex) {
            content = "未能完成本次" + taskLabel + "，请稍后重试。";
            reasoning = "AI 配置调用失败，已降级为错误提示。原因: " + ex.getMessage();
            fallbackUsed = true;
            timer.mark("provider_generate_text_failed");
            log.warn(
                "[AI-{}-PROVIDER-FAILED] connectionId={}, sessionId={}, databaseName={}, modelName={}, reason={}",
                logScene,
                req.getConnectionId(),
                safe(req.getSessionId()),
                safe(req.getDatabaseName()),
                safe(req.getModelName()),
                safe(ex.getMessage())
            );
        }

        AiTextResponseVO vo = new AiTextResponseVO();
        vo.setContent(safe(content));
        vo.setReasoning(safe(reasoning));
        vo.setFallbackUsed(fallbackUsed);
        timer.mark("assemble_response");
        log.info(
            "[AI-{}-RESP] connectionId={}, sessionId={}, databaseName={}, modelName={}, ragHit={}, relatedTableCount={}, contextLength={}, contentLength={}, fallbackUsed={}, elapsedMs={}",
            logScene,
            req.getConnectionId(),
            safe(req.getSessionId()),
            safe(req.getDatabaseName()),
            safe(req.getModelName()),
            Boolean.TRUE.equals(ragPromptContext.getHit()),
            generationContext.relatedTables().size(),
            safe(generationContext.promptContext()).length(),
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
        return vo;
    }

    private String tryBuildExplainPlanPromptContext(AiGenerateSqlReq req) {
        String sourceSql = extractSqlForAnalyze(req.getPrompt());
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

    private String extractSqlForAnalyze(String prompt) {
        String extracted = normalizeSqlText(extractSql(prompt));
        if (extracted.isBlank()) {
            String normalizedPrompt = normalizeSqlText(prompt);
            if (!looksLikeSql(normalizedPrompt)) {
                return "";
            }
            extracted = normalizedPrompt;
        }
        return trimTrailingSemicolon(extracted);
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

    private String trimTrailingSemicolon(String sql) {
        String normalized = safe(sql);
        while (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        return normalized;
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

    private String mergePromptContext(String promptContext, String extraPromptContext) {
        String base = safe(promptContext);
        String extra = safe(extraPromptContext);
        if (extra.isBlank()) {
            return base;
        }
        if (base.isBlank()) {
            return extra;
        }
        return base + "\n\n" + extra;
    }

    /**
     * 关键操作：统一抽象 LLM 通道，支持 OpenAI API 与本地 CLI。
     */
    private ProviderResult generateByConfiguredProvider(AiGenerateSqlReq req, GenerationContext context) {
        AiConfigVO config = aiConfigService.getConfig();
        AiModelOptionVO option = resolveModelOption(req.getModelName(), config);
        if ("LOCAL_CLI".equals(safe(option.getProviderType()).toUpperCase())) {
            return generateByLocalCli(req, context, option);
        }
        return generateByOpenAi(req, context, option);
    }

    private TextProviderResult generateTextByConfiguredProvider(AiGenerateSqlReq req,
                                                                GenerationContext context,
                                                                String systemPrompt,
                                                                String taskLabel) {
        AiConfigVO config = aiConfigService.getConfig();
        AiModelOptionVO option = resolveModelOption(req.getModelName(), config);
        if ("LOCAL_CLI".equals(safe(option.getProviderType()).toUpperCase())) {
            return generateTextByLocalCli(req, context, option, systemPrompt, taskLabel);
        }
        return generateTextByOpenAi(req, context, option, systemPrompt, taskLabel);
    }

    private ProviderResult generateByOpenAi(AiGenerateSqlReq req, GenerationContext context, AiModelOptionVO option) {
        String apiKey = safe(option.getOpenaiApiKey());
        if (apiKey.isBlank()) {
            throw new BusinessException(400, "OpenAI API Key 未配置: " + safe(option.getName()));
        }
        String model = resolveOpenAiModel(req.getModelName(), option);
        String baseUrl = safe(option.getOpenaiBaseUrl());
        if (baseUrl.isBlank()) {
            baseUrl = "https://api.openai.com/v1";
        }
        OpenAiEndpoint endpoint = resolveOpenAiEndpoint(baseUrl, model);

        String contextText = safe(context.promptContext());
        ObjectNode payload = buildOpenAiPayload(
            req,
            model,
            contextText,
            endpoint.apiType(),
            OPENAI_SYSTEM_PROMPT,
            context.relatedTables()
        );

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint.url()))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(500, "OpenAI 接口返回状态码: " + response.statusCode());
            }
            // 关键操作：统一兼容 chat/completions 与 responses（含 SSE）两种返回体。
            String content = parseOpenAiResponseText(response, endpoint.apiType());
            String sqlText = extractSql(content);
            if (sqlText.isBlank()) {
                return new ProviderResult(
                    content,
                    "已通过 OpenAI API(" + safe(option.getName()) + "/" + model + ") 返回说明内容（未识别到 SQL）"
                );
            }
            return new ProviderResult(sqlText, "已通过 OpenAI API(" + safe(option.getName()) + "/" + model + ") 生成 SQL");
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(500, "OpenAI 调用失败: " + ex.getMessage());
        }
    }

    private TextProviderResult generateTextByOpenAi(AiGenerateSqlReq req,
                                                    GenerationContext context,
                                                    AiModelOptionVO option,
                                                    String systemPrompt,
                                                    String taskLabel) {
        String apiKey = safe(option.getOpenaiApiKey());
        if (apiKey.isBlank()) {
            throw new BusinessException(400, "OpenAI API Key 未配置: " + safe(option.getName()));
        }
        String model = resolveOpenAiModel(req.getModelName(), option);
        String baseUrl = safe(option.getOpenaiBaseUrl());
        if (baseUrl.isBlank()) {
            baseUrl = "https://api.openai.com/v1";
        }
        OpenAiEndpoint endpoint = resolveOpenAiEndpoint(baseUrl, model);

        String contextText = safe(context.promptContext());
        ObjectNode payload = buildOpenAiPayload(
            req,
            model,
            contextText,
            endpoint.apiType(),
            systemPrompt,
            context.relatedTables()
        );

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint.url()))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(500, "OpenAI 接口返回状态码: " + response.statusCode());
            }
            String content = parseOpenAiResponseText(response, endpoint.apiType());
            if (safe(content).isBlank()) {
                throw new BusinessException(500, "OpenAI 返回内容为空");
            }
            return new TextProviderResult(content, "已通过 OpenAI API(" + safe(option.getName()) + "/" + model + ")完成" + taskLabel);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(500, "OpenAI 调用失败: " + ex.getMessage());
        }
    }

    private ObjectNode buildOpenAiPayload(AiGenerateSqlReq req,
                                          String model,
                                          String contextText,
                                          OpenAiApiType apiType,
                                          String systemPrompt,
                                          List<String> relatedTables) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", model);
        String userPrompt = buildProviderUserPrompt(req, contextText, relatedTables);
        if (apiType == OpenAiApiType.RESPONSES) {
            ArrayNode input = payload.putArray("input");
            input.addObject()
                .put("role", "system")
                .put("content", systemPrompt);
            input.addObject()
                .put("role", "user")
                .put("content", userPrompt);
            return payload;
        }
        payload.put("temperature", 0.1D);
        ArrayNode messages = payload.putArray("messages");
        messages.addObject()
            .put("role", "system")
            .put("content", systemPrompt);
        messages.addObject()
            .put("role", "user")
            .put("content", userPrompt);
        return payload;
    }

    private String parseOpenAiResponseText(HttpResponse<String> response, OpenAiApiType apiType) throws Exception {
        String body = Objects.toString(response.body(), "");
        String contentType = response.headers().firstValue("content-type").orElse("").toLowerCase();
        if (contentType.contains("text/event-stream") || body.startsWith("event:") || body.contains("\nevent:")) {
            return parseResponsesSseText(body);
        }
        JsonNode root = objectMapper.readTree(body);
        if (apiType == OpenAiApiType.RESPONSES) {
            String text = parseResponsesJsonText(root);
            if (!text.isBlank()) {
                return text;
            }
        }
        String chatText = parseChatCompletionsText(root);
        if (!chatText.isBlank()) {
            return chatText;
        }
        return parseResponsesJsonText(root);
    }

    private String parseChatCompletionsText(JsonNode root) {
        JsonNode contentNode = root.at("/choices/0/message/content");
        if (contentNode.isTextual()) {
            return safe(contentNode.asText(""));
        }
        if (contentNode.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode part : contentNode) {
                String text = safe(part.path("text").asText(""));
                if (!text.isBlank()) {
                    if (builder.length() > 0) {
                        builder.append('\n');
                    }
                    builder.append(text);
                }
            }
            return builder.toString().trim();
        }
        return "";
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

    private String parseResponsesJsonText(JsonNode root) {
        String directText = safe(root.path("output_text").asText(""));
        if (!directText.isBlank()) {
            return directText;
        }
        JsonNode outputItems = root.path("output");
        if (!outputItems.isArray()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode item : outputItems) {
            JsonNode contentItems = item.path("content");
            if (!contentItems.isArray()) {
                continue;
            }
            for (JsonNode content : contentItems) {
                String text = safe(content.path("text").asText(""));
                if (text.isBlank()) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(text);
            }
        }
        return builder.toString().trim();
    }

    private String parseResponsesSseText(String body) {
        StringBuilder deltaText = new StringBuilder();
        String doneText = "";
        String[] lines = Objects.toString(body, "").split("\\R");
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String trimmed = line.trim();
            if (!trimmed.startsWith("data:")) {
                continue;
            }
            String jsonData = trimmed.substring(5).trim();
            if (jsonData.isEmpty() || "[DONE]".equalsIgnoreCase(jsonData)) {
                continue;
            }
            try {
                JsonNode eventNode = objectMapper.readTree(jsonData);
                String eventType = safe(eventNode.path("type").asText(""));
                if ("response.output_text.delta".equals(eventType)) {
                    deltaText.append(eventNode.path("delta").asText(""));
                    continue;
                }
                if ("response.output_text.done".equals(eventType)) {
                    String text = safe(eventNode.path("text").asText(""));
                    if (!text.isBlank()) {
                        doneText = text;
                    }
                    continue;
                }
                if ("response.completed".equals(eventType)) {
                    String text = parseResponsesJsonText(eventNode.path("response"));
                    if (!text.isBlank()) {
                        doneText = text;
                    }
                }
            } catch (Exception ignored) {
                // 忽略非 JSON data 行，继续消费后续流式事件。
            }
        }
        if (!doneText.isBlank()) {
            return doneText;
        }
        return deltaText.toString().trim();
    }

    private ProviderResult generateByLocalCli(AiGenerateSqlReq req, GenerationContext context, AiModelOptionVO option) {
        String command = safe(option.getCliCommand());
        if (command.isBlank()) {
            throw new BusinessException(400, "本地 CLI 命令未配置: " + safe(option.getName()));
        }

        String backendPrompt = buildProviderUserPrompt(req, safe(context.promptContext()), context.relatedTables());
        String constrainedPrompt = buildCliConstrainedPrompt(backendPrompt);
        List<String> commandLine = parseCliCommand(command);
        if (commandLine.isEmpty()) {
            throw new BusinessException(400, "本地 CLI 命令无效");
        }
        CliInvocation cliInvocation = buildCliInvocation(commandLine, constrainedPrompt);

        ProcessBuilder processBuilder = new ProcessBuilder(cliInvocation.commandLine());
        processBuilder.redirectErrorStream(true);
        log.info(
            "[AI-CLI-CALL] providerName={}, providerId={}, rawCommand={}, resolvedCommandHead={}, commandArgCount={}, promptAsArg={}, connectionId={}, sessionId={}, databaseName={}, modelName={}, userPromptLength={}, ragContextLength={}, finalPromptLength={}, ignoredWorkingDirSet={}",
            safe(option.getName()),
            safe(option.getId()),
            command,
            summarizeCommandHead(cliInvocation.commandLine()),
            cliInvocation.commandLine().size(),
            !cliInvocation.writePromptToStdin(),
            req.getConnectionId(),
            safe(req.getSessionId()),
            safe(req.getDatabaseName()),
            safe(req.getModelName()),
            safe(req.getPrompt()).length(),
            safe(context.promptContext()).length(),
            constrainedPrompt.length(),
            !safe(option.getCliWorkingDir()).isBlank()
        );

        try {
            Process process = processBuilder.start();
            // 关键操作：codex 自动走 exec 非交互参数模式，避免 "stdin is not a terminal"。
            if (cliInvocation.writePromptToStdin()) {
                try (java.io.OutputStream stdin = process.getOutputStream()) {
                    stdin.write(constrainedPrompt.getBytes(StandardCharsets.UTF_8));
                    stdin.write('\n');
                    stdin.flush();
                }
            } else {
                process.getOutputStream().close();
            }
            byte[] outputBytes = process.getInputStream().readAllBytes();
            boolean finished = process.waitFor(CLI_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new BusinessException(500, "本地 CLI 执行超时");
            }
            int exitCode = process.exitValue();
            String output = new String(outputBytes, StandardCharsets.UTF_8);
            log.info(
                "[AI-CLI-RESULT] providerName={}, providerId={}, connectionId={}, sessionId={}, exitCode={}, outputLength={}",
                safe(option.getName()),
                safe(option.getId()),
                req.getConnectionId(),
                safe(req.getSessionId()),
                exitCode,
                output.length()
            );
            String sqlText = extractSql(output);
            if (sqlText.isBlank()) {
                return new ProviderResult(output, "已通过本地 CLI(" + safe(option.getName()) + ") 返回说明内容（未识别到 SQL）");
            }
            return new ProviderResult(sqlText, "已通过本地 CLI(" + safe(option.getName()) + ") 生成 SQL（提示词由后端构建）");
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(500, "本地 CLI 调用失败: " + ex.getMessage());
        }
    }

    private TextProviderResult generateTextByLocalCli(AiGenerateSqlReq req,
                                                       GenerationContext context,
                                                       AiModelOptionVO option,
                                                       String systemPrompt,
                                                       String taskLabel) {
        String command = safe(option.getCliCommand());
        if (command.isBlank()) {
            throw new BusinessException(400, "本地 CLI 命令未配置: " + safe(option.getName()));
        }

        String backendPrompt = buildProviderUserPrompt(req, safe(context.promptContext()), context.relatedTables());
        String constrainedPrompt = buildCliConstrainedPromptForText(systemPrompt, backendPrompt);
        List<String> commandLine = parseCliCommand(command);
        if (commandLine.isEmpty()) {
            throw new BusinessException(400, "本地 CLI 命令无效");
        }
        CliInvocation cliInvocation = buildCliInvocation(commandLine, constrainedPrompt);

        ProcessBuilder processBuilder = new ProcessBuilder(cliInvocation.commandLine());
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            if (cliInvocation.writePromptToStdin()) {
                try (java.io.OutputStream stdin = process.getOutputStream()) {
                    stdin.write(constrainedPrompt.getBytes(StandardCharsets.UTF_8));
                    stdin.write('\n');
                    stdin.flush();
                }
            } else {
                process.getOutputStream().close();
            }
            byte[] outputBytes = process.getInputStream().readAllBytes();
            boolean finished = process.waitFor(CLI_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new BusinessException(500, "本地 CLI 执行超时");
            }
            int exitCode = process.exitValue();
            String output = new String(outputBytes, StandardCharsets.UTF_8).trim();
            if (output.isBlank()) {
                throw new BusinessException(500, "本地 CLI 输出为空");
            }
            log.info(
                "[AI-CLI-TEXT-RESULT] providerName={}, providerId={}, connectionId={}, sessionId={}, taskLabel={}, exitCode={}, outputLength={}",
                safe(option.getName()),
                safe(option.getId()),
                req.getConnectionId(),
                safe(req.getSessionId()),
                safe(taskLabel),
                exitCode,
                output.length()
            );
            return new TextProviderResult(output, "已通过本地 CLI(" + safe(option.getName()) + ")完成" + taskLabel + "（提示词由后端构建）");
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(500, "本地 CLI 调用失败: " + ex.getMessage());
        }
    }

    private String buildCliConstrainedPrompt(String basePrompt) {
        return """
            你是数据库 SQL 专家。
            严格要求：
            1. 仅基于下面给出的用户需求、SQL片段（如有）和 RAG Context 生成 SQL。
            2. 禁止扫描、读取或参考当前工程/仓库/本地文件系统的任何内容。
            3. 仅返回可执行 SQL，不要输出解释。

            """ + basePrompt;
    }

    private String buildCliConstrainedPromptForText(String systemPrompt, String basePrompt) {
        return """
            %s
            严格要求：
            1. 仅基于下面给出的用户需求、SQL片段（如有）和 RAG Context 输出结果。
            2. 禁止扫描、读取或参考当前工程/仓库/本地文件系统的任何内容。
            3. 仅输出结论文本，不要输出执行命令。

            %s
            """.formatted(systemPrompt, basePrompt);
    }

    private List<String> parseCliCommand(String rawCommand) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = COMMAND_TOKEN_PATTERN.matcher(safe(rawCommand));
        while (matcher.find()) {
            String token = matcher.group(1);
            if (token == null) {
                token = matcher.group(2);
            }
            if (token == null) {
                token = matcher.group(3);
            }
            if (token != null && !token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private CliInvocation buildCliInvocation(List<String> parsedCommand, String prompt) {
        List<String> commandLine = new ArrayList<>(parsedCommand);
        if (isCodexExecutable(commandLine.get(0))) {
            if (!containsCodexSubcommand(commandLine)) {
                commandLine.add("exec");
            }
            commandLine.add(prompt);
            return new CliInvocation(commandLine, false);
        }
        commandLine.add(prompt);
        return new CliInvocation(commandLine, true);
    }

    private boolean isCodexExecutable(String executable) {
        return "codex".equalsIgnoreCase(new File(safe(executable)).getName());
    }

    private boolean containsCodexSubcommand(List<String> commandLine) {
        for (int i = 1; i < commandLine.size(); i++) {
            if (CODEX_SUBCOMMANDS.contains(safe(commandLine.get(i)))) {
                return true;
            }
        }
        return false;
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

    private String buildProviderUserPrompt(AiGenerateSqlReq req, String contextText, List<String> relatedTables) {
        DatabaseBasicInfo basicInfo = loadDatabaseBasicInfo(req.getConnectionId(), req.getDatabaseName());
        String relatedIndexInfo = buildRelatedTableIndexInfo(req.getConnectionId(), req.getDatabaseName(), relatedTables);
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
        builder.append("\n\nRAG Context:\n").append(contextText);
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

            List<String> referencedTables = collectReferencedTables(statement, rawSql);
            Set<String> schemaTables = loadSchemaTables(req.getConnectionId(), req.getDatabaseName());
            if (!referencedTables.isEmpty() && !schemaTables.isEmpty()) {
                List<String> missingTables = referencedTables.stream()
                    .filter(table -> !schemaTables.contains(normalizeIdentifier(table)))
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

    private List<String> collectReferencedTables(Statement statement, String rawSql) {
        TablesNamesFinder finder = new TablesNamesFinder();
        List<String> tables = new ArrayList<>(finder.getTableList(statement));
        Set<String> cteNames = extractCteNames(rawSql);
        return tables.stream()
            .map(this::normalizeIdentifier)
            .filter(item -> !item.isBlank())
            .filter(item -> !cteNames.contains(item))
            .sorted(String.CASE_INSENSITIVE_ORDER)
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
        String normalized = safe(identifier).replace("`", "").replace("\"", "");
        if (normalized.contains(".")) {
            String[] segments = normalized.split("\\.");
            normalized = segments[segments.length - 1];
        }
        return normalized.toLowerCase();
    }

    private GenerationContext buildGenerationContext(AiGenerateSqlReq req, RagPromptContext ragPromptContext) {
        List<String> relatedTables = new ArrayList<>(ragPromptContext.getRelatedTables());
        String ragContextText = safe(ragPromptContext.getPromptContext());
        if (Boolean.TRUE.equals(ragPromptContext.getHit()) && !ragContextText.isBlank()) {
            return new GenerationContext(ragContextText, relatedTables);
        }

        ContextBuildReq contextReq = new ContextBuildReq();
        contextReq.setConnectionId(req.getConnectionId());
        contextReq.setDatabaseName(req.getDatabaseName());
        contextReq.setQuestion(buildRetrievalInput(req.getPrompt()));
        contextReq.setTokenBudget(1200);
        ContextBuildVO schemaContext = schemaService.buildContext(contextReq);
        if (schemaContext.getRelatedTables() != null && !schemaContext.getRelatedTables().isEmpty()) {
            relatedTables.addAll(schemaContext.getRelatedTables());
        }
        return new GenerationContext(safe(schemaContext.getContext()), relatedTables);
    }

    private String buildRetrievalInput(String prompt) {
        return buildRetrievalInput(prompt, "");
    }

    private String buildRetrievalInput(String prompt, String extraContext) {
        String normalizedPrompt = safe(prompt);
        String normalizedExtraContext = safe(extraContext);
        if (normalizedExtraContext.isBlank()) {
            return normalizedPrompt;
        }
        return normalizedPrompt + "\n补充上下文:\n" + normalizedExtraContext;
    }

    /**
     * 关键操作：根据地址与模型自动判断 OpenAI 接口风格，兼容 chat/completions 与 responses。
     */
    private OpenAiEndpoint resolveOpenAiEndpoint(String baseUrl, String model) {
        String normalized = stripTrailingSlash(baseUrl);
        String lowerUrl = normalized.toLowerCase();
        if (lowerUrl.endsWith("/chat/completions")) {
            return new OpenAiEndpoint(normalized, OpenAiApiType.CHAT_COMPLETIONS);
        }
        if (lowerUrl.endsWith("/responses")) {
            return new OpenAiEndpoint(normalized, OpenAiApiType.RESPONSES);
        }
        if (preferResponsesApi(normalized, model)) {
            return new OpenAiEndpoint(normalized + "/responses", OpenAiApiType.RESPONSES);
        }
        return new OpenAiEndpoint(normalized + "/chat/completions", OpenAiApiType.CHAT_COMPLETIONS);
    }

    private boolean preferResponsesApi(String baseUrl, String model) {
        String lowerModel = safe(model).toLowerCase();
        String lowerBaseUrl = safe(baseUrl).toLowerCase();
        return lowerModel.contains("codex")
            || lowerModel.startsWith("gpt-5")
            || lowerBaseUrl.contains("/codex/");
    }

    private String stripTrailingSlash(String value) {
        String normalized = safe(value);
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String safe(String input) {
        return Objects.toString(input, "").trim();
    }

    private String summarizeCommandHead(List<String> commandLine) {
        if (commandLine == null || commandLine.isEmpty()) {
            return "";
        }
        int max = Math.min(4, commandLine.size());
        return String.join(" ", commandLine.subList(0, max));
    }

    private AiModelOptionVO resolveModelOption(String requestModel, AiConfigVO config) {
        List<AiModelOptionVO> options = config.getModelOptions() == null ? List.of() : config.getModelOptions();
        String target = safe(requestModel);
        if (!target.isBlank()) {
            for (AiModelOptionVO option : options) {
                if (option == null) {
                    continue;
                }
                if (target.equalsIgnoreCase(safe(option.getId()))) {
                    return option;
                }
            }
        }
        if (!options.isEmpty()) {
            return options.get(0);
        }

        AiModelOptionVO fallback = new AiModelOptionVO();
        fallback.setId("openai-default");
        fallback.setName("OpenAI gpt-4.1-mini");
        fallback.setProviderType("OPENAI");
        fallback.setOpenaiBaseUrl("https://api.openai.com/v1");
        fallback.setOpenaiApiKey("");
        fallback.setOpenaiModel("gpt-4.1-mini");
        fallback.setCliCommand("");
        fallback.setCliWorkingDir("");
        return fallback;
    }

    private String resolveOpenAiModel(String requestModel, AiModelOptionVO option) {
        String requestValue = safe(requestModel);
        if (!requestValue.isBlank() && !requestValue.equalsIgnoreCase(safe(option.getId()))) {
            return requestValue;
        }
        String raw = safe(option.getOpenaiModel());
        if (raw.isBlank()) {
            return "gpt-4.1-mini";
        }
        for (String token : raw.split("[,\\n\\r\\t]")) {
            String model = token.trim();
            if (!model.isBlank()) {
                return model;
            }
        }
        return "gpt-4.1-mini";
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

    private record CliInvocation(List<String> commandLine, boolean writePromptToStdin) {
    }

    private record ProviderResult(String sqlText, String reasoning) {
    }

    private record TextProviderResult(String content, String reasoning) {
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
                                        boolean parsed) {
    }

    private record ChartConfigValidationResult(boolean valid, String message) {
    }

    private record AstValidationResult(boolean valid, String sqlText, String message) {
    }

    private record GenerationContext(String promptContext, List<String> relatedTables) {
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

    private enum OpenAiApiType {
        CHAT_COMPLETIONS,
        RESPONSES
    }

    private record OpenAiEndpoint(String url, OpenAiApiType apiType) {
    }
}
