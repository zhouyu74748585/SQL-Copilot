package com.sqlcopilot.studio.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqlcopilot.studio.dto.ai.*;
import com.sqlcopilot.studio.dto.schema.ContextBuildReq;
import com.sqlcopilot.studio.dto.schema.ContextBuildVO;
import com.sqlcopilot.studio.service.AiConfigService;
import com.sqlcopilot.studio.service.AiService;
import com.sqlcopilot.studio.service.SchemaService;
import com.sqlcopilot.studio.util.BusinessException;
import com.sqlcopilot.studio.util.SqlClassifier;
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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AiServiceImpl implements AiService {

    private static final Pattern SQL_FENCE_PATTERN = Pattern.compile("(?is)```(?:sql)?\\s*(.*?)```");
    private static final long CLI_TIMEOUT_SECONDS = 45L;
    private static final Logger log = LoggerFactory.getLogger(AiServiceImpl.class);

    private final SchemaService schemaService;
    private final AiConfigService aiConfigService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    public AiServiceImpl(SchemaService schemaService, AiConfigService aiConfigService, ObjectMapper objectMapper) {
        this.schemaService = schemaService;
        this.aiConfigService = aiConfigService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiGenerateSqlVO generateSql(AiGenerateSqlReq req) {
        // 关键操作：先构建结构化上下文，避免 AI 在未知 Schema 上盲猜。
        ContextBuildReq contextReq = new ContextBuildReq();
        contextReq.setConnectionId(req.getConnectionId());
        contextReq.setQuestion(req.getPrompt());
        contextReq.setTokenBudget(1200);
        ContextBuildVO context = schemaService.buildContext(contextReq);

        String reasoning;
        String generatedSql;
        boolean fallbackUsed = false;
        try {
            ProviderResult result = generateByConfiguredProvider(req, context);
            generatedSql = result.sqlText();
            reasoning = result.reasoning();
            log.info("[AI-GENERATE] connectionId={}, sessionId={}, sql={}", req.getConnectionId(), req.getSessionId(), generatedSql);
        } catch (Exception ex) {
            generatedSql = fallbackSql(req.getPrompt(), context);
            reasoning = "AI 配置调用失败，已降级到本地规则生成。原因: " + ex.getMessage();
            fallbackUsed = true;
            log.warn("[AI-GENERATE-FALLBACK] connectionId={}, sessionId={}, reason={}, sql={}",
                req.getConnectionId(), req.getSessionId(), ex.getMessage(), generatedSql);
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

    private ProviderResult generateByConfiguredProvider(AiGenerateSqlReq req, ContextBuildVO context) {
        AiConfigVO config = aiConfigService.getConfig();
        String providerType = safe(config.getProviderType()).toUpperCase();
        if ("LOCAL_CLI".equals(providerType)) {
            return generateByLocalCli(req, context, config);
        }
        return generateByOpenAi(req, context, config);
    }

    private ProviderResult generateByOpenAi(AiGenerateSqlReq req, ContextBuildVO context, AiConfigVO config) {
        String apiKey = safe(config.getOpenaiApiKey());
        if (apiKey.isBlank()) {
            throw new BusinessException(400, "OpenAI API Key 未配置");
        }
        String model = resolveOpenAiModel(req.getModelName(), config.getOpenaiModel());
        String baseUrl = safe(config.getOpenaiBaseUrl());
        if (baseUrl.isBlank()) {
            baseUrl = "https://api.openai.com/v1";
        }
        String endpoint = normalizeOpenAiEndpoint(baseUrl);

        String contextText = safe(context.getContext());
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", model);
        payload.put("temperature", 0.1D);
        ArrayNode messages = payload.putArray("messages");
        messages.addObject()
            .put("role", "system")
            .put("content", "你是数据库 SQL 专家。仅返回可执行 SQL，不要输出解释。查询语句默认增加 LIMIT 100。");
        messages.addObject()
            .put("role", "user")
            .put("content", "用户需求:\n" + req.getPrompt() + "\n\nSchema Context:\n" + contextText);

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
            return new ProviderResult(sqlText, "已通过 OpenAI API(" + model + ") 生成 SQL。");
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(500, "OpenAI 调用失败: " + ex.getMessage());
        }
    }

    private ProviderResult generateByLocalCli(AiGenerateSqlReq req, ContextBuildVO context, AiConfigVO config) {
        String command = safe(config.getCliCommand());
        if (command.isBlank()) {
            throw new BusinessException(400, "本地 CLI 命令未配置");
        }

        List<String> commandLine = new ArrayList<>();
        commandLine.add(command);
        List<String> args = parseCliArgs(config.getCliArgs());
        String contextText = safe(context.getContext());
        for (String arg : args) {
            commandLine.add(arg
                .replace("{prompt}", req.getPrompt())
                .replace("{context}", contextText));
        }

        ProcessBuilder processBuilder = new ProcessBuilder(commandLine);
        String workingDir = safe(config.getCliWorkingDir());
        if (!workingDir.isBlank()) {
            processBuilder.directory(new File(workingDir));
        }
        processBuilder.redirectErrorStream(true);

        try {
            Process process = processBuilder.start();
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
            return new ProviderResult(sqlText, "已通过本地 CLI 生成 SQL。");
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(500, "本地 CLI 调用失败: " + ex.getMessage());
        }
    }

    private List<String> parseCliArgs(String rawArgs) {
        List<String> args = new ArrayList<>();
        String value = safe(rawArgs);
        if (value.isBlank()) {
            args.add("{prompt}");
            return args;
        }
        for (String line : value.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isBlank()) {
                args.add(trimmed);
            }
        }
        if (args.isEmpty()) {
            args.add("{prompt}");
        }
        return args;
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

    private String fallbackSql(String prompt, ContextBuildVO context) {
        String table = "sqlite_master";
        if (context.getRelatedTables() != null && !context.getRelatedTables().isEmpty()) {
            table = context.getRelatedTables().get(0);
        }
        String normalizedPrompt = safe(prompt).toLowerCase();
        if (prompt.contains("数量") || normalizedPrompt.contains("count")) {
            return "SELECT COUNT(1) AS total_count FROM " + table;
        }
        return "SELECT * FROM " + table + " LIMIT 100";
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

    private String resolveOpenAiModel(String requestModel, String configuredModels) {
        String direct = safe(requestModel);
        if (!direct.isBlank()) {
            return direct;
        }
        String raw = safe(configuredModels);
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
}
