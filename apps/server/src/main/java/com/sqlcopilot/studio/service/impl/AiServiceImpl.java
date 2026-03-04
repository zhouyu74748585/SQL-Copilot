package com.sqlcopilot.studio.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqlcopilot.studio.dto.ai.AiConfigVO;
import com.sqlcopilot.studio.dto.ai.AiGenerateSqlReq;
import com.sqlcopilot.studio.dto.ai.AiGenerateSqlVO;
import com.sqlcopilot.studio.dto.ai.AiModelOptionVO;
import com.sqlcopilot.studio.dto.ai.AiRepairReq;
import com.sqlcopilot.studio.dto.ai.AiRepairVO;
import com.sqlcopilot.studio.dto.ai.AiTextResponseVO;
import com.sqlcopilot.studio.dto.schema.ContextBuildReq;
import com.sqlcopilot.studio.dto.schema.ContextBuildVO;
import com.sqlcopilot.studio.dto.schema.SchemaOverviewVO;
import com.sqlcopilot.studio.dto.schema.TableDetailVO;
import com.sqlcopilot.studio.entity.ConnectionEntity;
import com.sqlcopilot.studio.service.AiConfigService;
import com.sqlcopilot.studio.service.AiService;
import com.sqlcopilot.studio.service.ConnectionService;
import com.sqlcopilot.studio.service.SchemaService;
import com.sqlcopilot.studio.service.rag.RagRetrievalService;
import com.sqlcopilot.studio.service.rag.model.RagPromptContext;
import com.sqlcopilot.studio.util.BusinessException;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
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
    private static final String OPENAI_SYSTEM_PROMPT = "你是数据库 SQL 专家。基于提供的 RAG 上下文生成 SQL。仅返回可执行 SQL，不要输出解释。";
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
        log.info(
            "[AI-GENERATE-REQ] connectionId={}, sessionId={}, databaseName={}, modelName={}, promptLength={}, sqlSnippetLength={}",
            req.getConnectionId(),
            safe(req.getSessionId()),
            safe(req.getDatabaseName()),
            safe(req.getModelName()),
            safe(req.getPrompt()).length(),
            safe(req.getSqlSnippet()).length()
        );
        // 关键操作：先将用户需求/SQL片段向量化并做 Qdrant 分层检索，构造 Prompt 上下文。
        String retrievalInput = buildRetrievalInput(req.getPrompt(), req.getSqlSnippet());
        RagPromptContext ragPromptContext = ragRetrievalService.retrievePromptContext(
            req.getConnectionId(),
            req.getDatabaseName(),
            retrievalInput
        );
        GenerationContext generationContext = buildGenerationContext(req, ragPromptContext);

        String reasoning;
        String generatedSql;
        boolean fallbackUsed = false;
        try {
            ProviderResult result = generateByConfiguredProvider(req, generationContext);
            generatedSql = safe(result.sqlText());
            reasoning = safe(result.reasoning());
        } catch (Exception ex) {
            generatedSql = fallbackOutputText("模型调用失败: " + safe(ex.getMessage()));
            reasoning = "AI 配置调用失败，已返回说明内容。原因: " + safe(ex.getMessage());
            fallbackUsed = true;
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
            if (!reasoning.isBlank()) {
                reasoning = reasoning + "；";
            }
            reasoning = reasoning + "模型未返回可识别 SQL，已返回说明内容。";
        } else {
            AstValidationResult astResult = validateByAst(req, generatedSql);
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
        return vo;
    }

    @Override
    public AiTextResponseVO explainSql(AiGenerateSqlReq req) {
        ensureExplainOrAnalyzeSnippet(req, "解释 SQL");
        return generateTextResponse(req, "EXPLAIN-TEXT", EXPLAIN_SQL_SYSTEM_PROMPT, "SQL 含义解释");
    }

    @Override
    public AiTextResponseVO analyzeSql(AiGenerateSqlReq req) {
        ensureExplainOrAnalyzeSnippet(req, "分析 SQL");
        return generateTextResponse(req, "ANALYZE-SQL", ANALYZE_SQL_SYSTEM_PROMPT, "SQL 合理性分析");
    }

    @Override
    public AiRepairVO repairSql(AiRepairReq req) {
        long startAt = System.currentTimeMillis();
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
            String retrievalInput = buildRetrievalInput(providerReq.getPrompt(), sourceSql + "\n" + errorMessage);
            RagPromptContext ragPromptContext = ragRetrievalService.retrievePromptContext(
                req.getConnectionId(),
                req.getDatabaseName(),
                retrievalInput
            );
            GenerationContext generationContext = buildGenerationContext(providerReq, ragPromptContext);
            TextProviderResult providerResult = generateTextByConfiguredProvider(
                providerReq,
                generationContext,
                REPAIR_SQL_SYSTEM_PROMPT,
                "SQL 修复"
            );

            ParsedRepairResult parsed = parseRepairResult(providerResult.content(), sourceSql, errorMessage);
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
            return vo;
        }
    }

    private AiGenerateSqlReq buildRepairGenerateReq(AiRepairReq req, String sqlText, String errorMessage) {
        AiGenerateSqlReq aiReq = new AiGenerateSqlReq();
        aiReq.setConnectionId(req.getConnectionId());
        aiReq.setSessionId(req.getSessionId());
        aiReq.setDatabaseName(req.getDatabaseName());
        aiReq.setModelName(req.getModelName());
        aiReq.setSqlSnippet(sqlText);
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

    private void ensureExplainOrAnalyzeSnippet(AiGenerateSqlReq req, String actionName) {
        if (safe(req.getSqlSnippet()).isBlank()) {
            throw new BusinessException(400, "请先提供需要" + actionName + "的 SQL 片段");
        }
    }

    private AiTextResponseVO generateTextResponse(AiGenerateSqlReq req,
                                                  String logScene,
                                                  String systemPrompt,
                                                  String taskLabel) {
        long startAt = System.currentTimeMillis();
        log.info(
            "[AI-{}-REQ] connectionId={}, sessionId={}, databaseName={}, modelName={}, promptLength={}, sqlSnippetLength={}",
            logScene,
            req.getConnectionId(),
            safe(req.getSessionId()),
            safe(req.getDatabaseName()),
            safe(req.getModelName()),
            safe(req.getPrompt()).length(),
            safe(req.getSqlSnippet()).length()
        );

        String retrievalInput = buildRetrievalInput(req.getPrompt(), req.getSqlSnippet());
        RagPromptContext ragPromptContext = ragRetrievalService.retrievePromptContext(
            req.getConnectionId(),
            req.getDatabaseName(),
            retrievalInput
        );
        GenerationContext generationContext = buildGenerationContext(req, ragPromptContext);

        String content;
        String reasoning;
        boolean fallbackUsed = false;
        try {
            TextProviderResult result = generateTextByConfiguredProvider(req, generationContext, systemPrompt, taskLabel);
            content = result.content();
            reasoning = result.reasoning();
        } catch (Exception ex) {
            content = "未能完成本次" + taskLabel + "，请稍后重试。";
            reasoning = "AI 配置调用失败，已降级为错误提示。原因: " + ex.getMessage();
            fallbackUsed = true;
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
        return vo;
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
            "[AI-CLI-CALL] providerName={}, providerId={}, rawCommand={}, resolvedCommandHead={}, commandArgCount={}, promptAsArg={}, connectionId={}, sessionId={}, databaseName={}, modelName={}, userPromptLength={}, sqlSnippetLength={}, ragContextLength={}, finalPromptLength={}, ignoredWorkingDirSet={}",
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
            safe(req.getSqlSnippet()).length(),
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
        String sqlSnippet = safe(req.getSqlSnippet());
        if (!sqlSnippet.isBlank()) {
            builder.append("\n\n用户 SQL 片段:\n").append(sqlSnippet);
        }
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
        contextReq.setQuestion(buildRetrievalInput(req.getPrompt(), req.getSqlSnippet()));
        contextReq.setTokenBudget(1200);
        ContextBuildVO schemaContext = schemaService.buildContext(contextReq);
        if (schemaContext.getRelatedTables() != null && !schemaContext.getRelatedTables().isEmpty()) {
            relatedTables.addAll(schemaContext.getRelatedTables());
        }
        return new GenerationContext(safe(schemaContext.getContext()), relatedTables);
    }

    private String buildRetrievalInput(String prompt, String sqlSnippet) {
        String normalizedPrompt = safe(prompt);
        String normalizedSnippet = safe(sqlSnippet);
        if (normalizedSnippet.isBlank()) {
            return normalizedPrompt;
        }
        return normalizedPrompt + "\nSQL片段:\n" + normalizedSnippet;
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

    private record CliInvocation(List<String> commandLine, boolean writePromptToStdin) {
    }

    private record ProviderResult(String sqlText, String reasoning) {
    }

    private record TextProviderResult(String content, String reasoning) {
    }

    private record ParsedRepairResult(String errorExplanation, String repairedSql) {
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

    private enum OpenAiApiType {
        CHAT_COMPLETIONS,
        RESPONSES
    }

    private record OpenAiEndpoint(String url, OpenAiApiType apiType) {
    }
}
