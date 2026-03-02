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
import com.sqlcopilot.studio.dto.schema.ContextBuildReq;
import com.sqlcopilot.studio.dto.schema.ContextBuildVO;
import com.sqlcopilot.studio.dto.schema.SchemaOverviewVO;
import com.sqlcopilot.studio.service.AiConfigService;
import com.sqlcopilot.studio.service.AiService;
import com.sqlcopilot.studio.service.SchemaService;
import com.sqlcopilot.studio.service.rag.RagRetrievalService;
import com.sqlcopilot.studio.service.rag.model.RagPromptContext;
import com.sqlcopilot.studio.util.BusinessException;
import com.sqlcopilot.studio.util.SqlClassifier;
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
import java.util.List;
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
    private static final long CLI_TIMEOUT_SECONDS = 45L;
    private static final Logger log = LoggerFactory.getLogger(AiServiceImpl.class);

    private final SchemaService schemaService;
    private final AiConfigService aiConfigService;
    private final RagRetrievalService ragRetrievalService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    public AiServiceImpl(SchemaService schemaService,
                         AiConfigService aiConfigService,
                         RagRetrievalService ragRetrievalService,
                         ObjectMapper objectMapper) {
        this.schemaService = schemaService;
        this.aiConfigService = aiConfigService;
        this.ragRetrievalService = ragRetrievalService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiGenerateSqlVO generateSql(AiGenerateSqlReq req) {
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
            generatedSql = result.sqlText();
            reasoning = result.reasoning();
            log.info("[AI-GENERATE] connectionId={}, sessionId={}, sql={}", req.getConnectionId(), req.getSessionId(), generatedSql);
        } catch (Exception ex) {
            generatedSql = fallbackSql(req.getPrompt(), generationContext.relatedTables());
            reasoning = "AI 配置调用失败，已降级到本地规则生成。原因: " + ex.getMessage();
            fallbackUsed = true;
            log.warn("[AI-GENERATE-FALLBACK] connectionId={}, sessionId={}, reason={}, sql={}",
                req.getConnectionId(), req.getSessionId(), ex.getMessage(), generatedSql);
        }

        AstValidationResult astResult = validateByAst(req, generatedSql);
        if (!astResult.valid()) {
            generatedSql = fallbackSql(req.getPrompt(), generationContext.relatedTables());
            fallbackUsed = true;
            reasoning = reasoning + "；AST 校验未通过，已降级。原因: " + astResult.message();
            AstValidationResult fallbackValidation = validateByAst(req, generatedSql);
            if (!fallbackValidation.valid()) {
                throw new BusinessException(500, "SQL 生成后 AST 校验失败: " + fallbackValidation.message());
            }
            generatedSql = fallbackValidation.sqlText();
            reasoning = reasoning + "；降级 SQL 已通过 AST 校验。";
        } else {
            generatedSql = astResult.sqlText();
            reasoning = reasoning + "；" + astResult.message();
        }

        AiGenerateSqlVO vo = new AiGenerateSqlVO();
        vo.setSqlText(generatedSql);
        vo.setReasoning(reasoning);
        vo.setFallbackUsed(fallbackUsed);
        return vo;
    }

    @Override
    public AiRepairVO repairSql(AiRepairReq req) {
        String sql = req.getSqlText();
        log.info("[AI-REPAIR] connectionId={}, sessionId={}, beforeSql={}", req.getConnectionId(), req.getSessionId(), sql);
        if (SqlClassifier.isQuery(sql) && !SqlClassifier.normalize(sql).contains(" limit ")) {
            sql = sql + " LIMIT 100";
        }
        if (req.getErrorMessage().toLowerCase().contains("unknown column")) {
            sql = "/* 请检查字段名称是否存在 */\n" + sql;
        }

        AiRepairVO vo = new AiRepairVO();
        vo.setRepaired(Boolean.TRUE);
        vo.setRepairedSql(sql);
        vo.setRepairNote("已执行基础修复策略（LIMIT 补充/字段错误提示）。");
        log.info("[AI-REPAIR] connectionId={}, sessionId={}, afterSql={}", req.getConnectionId(), req.getSessionId(), sql);
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
        String endpoint = normalizeOpenAiEndpoint(baseUrl);

        String contextText = safe(context.promptContext());
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", model);
        payload.put("temperature", 0.1D);
        ArrayNode messages = payload.putArray("messages");
        messages.addObject()
            .put("role", "system")
            .put("content", "你是数据库 SQL 专家。基于提供的 RAG 上下文生成 SQL。仅返回可执行 SQL，不要输出解释。查询语句默认增加 LIMIT 100。");
        messages.addObject()
            .put("role", "user")
            .put("content", buildProviderUserPrompt(req, contextText));

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(500, "OpenAI 接口返回状态码: " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            String content = root.at("/choices/0/message/content").asText("");
            String sqlText = extractSql(content);
            if (sqlText.isBlank()) {
                throw new BusinessException(500, "OpenAI 返回内容未识别出 SQL");
            }
            return new ProviderResult(sqlText, "已通过 OpenAI API(" + safe(option.getName()) + "/" + model + ") 生成 SQL");
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(500, "OpenAI 调用失败: " + ex.getMessage());
        }
    }

    private ProviderResult generateByLocalCli(AiGenerateSqlReq req, GenerationContext context, AiModelOptionVO option) {
        String command = safe(option.getCliCommand());
        if (command.isBlank()) {
            throw new BusinessException(400, "本地 CLI 命令未配置: " + safe(option.getName()));
        }

        String backendPrompt = buildProviderUserPrompt(req, safe(context.promptContext()));
        List<String> commandLine = parseCliCommand(command);
        if (commandLine.isEmpty()) {
            throw new BusinessException(400, "本地 CLI 命令无效");
        }
        // 关键操作：统一把后端拼装提示词作为命令参数传递，避免用户侧拼装输入模板。
        commandLine.add(backendPrompt);

        ProcessBuilder processBuilder = new ProcessBuilder(commandLine);
        String workingDir = safe(option.getCliWorkingDir());
        if (!workingDir.isBlank()) {
            processBuilder.directory(new File(workingDir));
        }
        processBuilder.redirectErrorStream(true);

        try {
            Process process = processBuilder.start();
            // 关键操作：提示词由后端统一拼装后写入 CLI 标准输入，避免用户侧自定义模板干扰生成链路。
            try (java.io.OutputStream stdin = process.getOutputStream()) {
                stdin.write(backendPrompt.getBytes(StandardCharsets.UTF_8));
                stdin.write('\n');
                stdin.flush();
            }
            byte[] outputBytes = process.getInputStream().readAllBytes();
            boolean finished = process.waitFor(CLI_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new BusinessException(500, "本地 CLI 执行超时");
            }
            String output = new String(outputBytes, StandardCharsets.UTF_8);
            String sqlText = extractSql(output);
            if (sqlText.isBlank()) {
                throw new BusinessException(500, "本地 CLI 输出未识别到 SQL");
            }
            return new ProviderResult(sqlText, "已通过本地 CLI(" + safe(option.getName()) + ") 生成 SQL（提示词由后端构建）");
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(500, "本地 CLI 调用失败: " + ex.getMessage());
        }
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

    private String extractSql(String rawOutput) {
        String output = safe(rawOutput);
        if (output.isBlank()) {
            return "";
        }
        Matcher matcher = SQL_FENCE_PATTERN.matcher(output);
        while (matcher.find()) {
            String candidate = safe(matcher.group(1));
            if (looksLikeSql(candidate)) {
                return candidate;
            }
        }

        String lower = output.toLowerCase();
        int idx = firstSqlKeywordIndex(lower);
        if (idx >= 0) {
            return output.substring(idx).trim();
        }
        return "";
    }

    private String buildProviderUserPrompt(AiGenerateSqlReq req, String contextText) {
        StringBuilder builder = new StringBuilder();
        builder.append("用户需求:\n").append(req.getPrompt());
        String sqlSnippet = safe(req.getSqlSnippet());
        if (!sqlSnippet.isBlank()) {
            builder.append("\n\n用户 SQL 片段:\n").append(sqlSnippet);
        }
        builder.append("\n\nRAG Context:\n").append(contextText);
        return builder.toString();
    }

    private int firstSqlKeywordIndex(String text) {
        int min = Integer.MAX_VALUE;
        for (String keyword : List.of("select ", "with ", "update ", "delete ", "insert ")) {
            int idx = text.indexOf(keyword);
            if (idx >= 0 && idx < min) {
                min = idx;
            }
        }
        return min == Integer.MAX_VALUE ? -1 : min;
    }

    private boolean looksLikeSql(String text) {
        String normalized = safe(text).toLowerCase();
        return normalized.startsWith("select ")
            || normalized.startsWith("with ")
            || normalized.startsWith("update ")
            || normalized.startsWith("delete ")
            || normalized.startsWith("insert ");
    }

    private String fallbackSql(String prompt, List<String> relatedTables) {
        String table = "sqlite_master";
        if (relatedTables != null && !relatedTables.isEmpty()) {
            table = relatedTables.get(0);
        }
        String normalizedPrompt = safe(prompt).toLowerCase();
        if (prompt.contains("数量") || normalizedPrompt.contains("count")) {
            return "SELECT COUNT(1) AS total_count FROM " + table;
        }
        return "SELECT * FROM " + table + " LIMIT 100";
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

    private String normalizeOpenAiEndpoint(String baseUrl) {
        if (baseUrl.endsWith("/chat/completions")) {
            return baseUrl;
        }
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return normalized + "/chat/completions";
    }

    private String safe(String input) {
        return Objects.toString(input, "").trim();
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

    private record ProviderResult(String sqlText, String reasoning) {
    }

    private record AstValidationResult(boolean valid, String sqlText, String message) {
    }

    private record GenerationContext(String promptContext, List<String> relatedTables) {
    }
}
