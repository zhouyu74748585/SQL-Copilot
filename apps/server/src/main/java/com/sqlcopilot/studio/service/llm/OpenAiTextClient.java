package com.sqlcopilot.studio.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqlcopilot.studio.util.BusinessException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

@Component
public class OpenAiTextClient {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    public OpenAiTextClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public OpenAiTextResult requestText(String apiKey,
                                        String baseUrl,
                                        String model,
                                        String systemPrompt,
                                        String userPrompt,
                                        Duration timeout,
                                        Double temperature) {
        OpenAiEndpoint endpoint = resolveOpenAiEndpoint(baseUrl, model);
        ObjectNode payload = buildPayload(model, endpoint.apiType(), systemPrompt, userPrompt, temperature);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint.url()))
                .timeout(resolveTimeout(timeout))
                .header("Authorization", "Bearer " + safe(apiKey))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(500, "OpenAI 接口返回状态码: " + response.statusCode());
            }
            OpenAiTextResult parsed = parseOpenAiResponse(response, endpoint.apiType());
            TokenUsage usage = normalizeUsage(parsed.usage(), systemPrompt, userPrompt, parsed.content());
            return new OpenAiTextResult(parsed.content(), usage);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(500, "OpenAI 调用失败: " + safe(ex.getMessage()));
        }
    }

    private Duration resolveTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return Duration.ofSeconds(30);
        }
        return timeout;
    }

    private ObjectNode buildPayload(String model,
                                    OpenAiApiType apiType,
                                    String systemPrompt,
                                    String userPrompt,
                                    Double temperature) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", safe(model));
        if (apiType == OpenAiApiType.RESPONSES) {
            ArrayNode input = payload.putArray("input");
            input.addObject().put("role", "system").put("content", safe(systemPrompt));
            input.addObject().put("role", "user").put("content", safe(userPrompt));
            return payload;
        }
        if (temperature != null) {
            payload.put("temperature", temperature);
        }
        ArrayNode messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content", safe(systemPrompt));
        messages.addObject().put("role", "user").put("content", safe(userPrompt));
        return payload;
    }

    private OpenAiEndpoint resolveOpenAiEndpoint(String baseUrl, String model) {
        String normalized = stripTrailingSlash(baseUrl);
        if (normalized.isBlank()) {
            normalized = "https://api.openai.com/v1";
        }
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

    private OpenAiTextResult parseOpenAiResponse(HttpResponse<String> response, OpenAiApiType apiType) throws Exception {
        String body = Objects.toString(response.body(), "");
        String contentType = response.headers().firstValue("content-type").orElse("").toLowerCase();
        if (contentType.contains("text/event-stream") || body.startsWith("event:") || body.contains("\nevent:")) {
            return parseResponsesSseText(body);
        }
        JsonNode root = objectMapper.readTree(body);
        TokenUsage usage = parseUsage(root, apiType);
        if (apiType == OpenAiApiType.RESPONSES) {
            String text = parseResponsesJsonText(root);
            if (!text.isBlank()) {
                return new OpenAiTextResult(text, usage);
            }
        }
        String chatText = parseChatCompletionsText(root);
        if (!chatText.isBlank()) {
            return new OpenAiTextResult(chatText, usage);
        }
        return new OpenAiTextResult(parseResponsesJsonText(root), usage);
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
                if (text.isBlank()) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(text);
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

    private OpenAiTextResult parseResponsesSseText(String body) {
        StringBuilder deltaText = new StringBuilder();
        String doneText = "";
        TokenUsage usage = null;
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
                    JsonNode responseNode = eventNode.path("response");
                    String text = parseResponsesJsonText(responseNode);
                    if (!text.isBlank()) {
                        doneText = text;
                    }
                    TokenUsage completedUsage = parseUsage(responseNode, OpenAiApiType.RESPONSES);
                    if (completedUsage != null) {
                        usage = completedUsage;
                    }
                }
            } catch (Exception ignored) {
                // ignore non-json lines
            }
        }
        if (!doneText.isBlank()) {
            return new OpenAiTextResult(doneText, usage);
        }
        return new OpenAiTextResult(deltaText.toString().trim(), usage);
    }

    private TokenUsage normalizeUsage(TokenUsage usage, String systemPrompt, String userPrompt, String content) {
        if (usage != null && (usage.promptTokens() > 0 || usage.completionTokens() > 0 || usage.totalTokens() > 0)) {
            int promptTokens = Math.max(0, usage.promptTokens());
            int completionTokens = Math.max(0, usage.completionTokens());
            int totalTokens = usage.totalTokens();
            if (totalTokens <= 0) {
                totalTokens = promptTokens + completionTokens;
            }
            return new TokenUsage(promptTokens, completionTokens, totalTokens, usage.estimated());
        }
        int promptTokens = estimateTokens(safe(systemPrompt) + "\n" + safe(userPrompt));
        int completionTokens = estimateTokens(content);
        return new TokenUsage(promptTokens, completionTokens, promptTokens + completionTokens, true);
    }

    private TokenUsage parseUsage(JsonNode root, OpenAiApiType apiType) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return null;
        }
        JsonNode usageNode = root.path("usage");
        if ((usageNode.isMissingNode() || usageNode.isNull()) && apiType == OpenAiApiType.RESPONSES) {
            usageNode = root.path("response").path("usage");
        }
        if (usageNode.isMissingNode() || usageNode.isNull()) {
            return null;
        }
        int promptTokens = firstIntValue(usageNode, "prompt_tokens", "input_tokens");
        int completionTokens = firstIntValue(usageNode, "completion_tokens", "output_tokens");
        int totalTokens = firstIntValue(usageNode, "total_tokens");
        if (totalTokens <= 0 && (promptTokens > 0 || completionTokens > 0)) {
            totalTokens = Math.max(0, promptTokens) + Math.max(0, completionTokens);
        }
        if (promptTokens <= 0 && totalTokens > 0 && completionTokens > 0 && totalTokens >= completionTokens) {
            promptTokens = totalTokens - completionTokens;
        }
        if (completionTokens <= 0 && totalTokens > 0 && promptTokens > 0 && totalTokens >= promptTokens) {
            completionTokens = totalTokens - promptTokens;
        }
        if (promptTokens <= 0 && completionTokens <= 0 && totalTokens <= 0) {
            return null;
        }
        return new TokenUsage(Math.max(0, promptTokens), Math.max(0, completionTokens), Math.max(0, totalTokens), false);
    }

    private int firstIntValue(JsonNode node, String... names) {
        if (node == null || names == null) {
            return 0;
        }
        for (String name : names) {
            int value = parseIntNode(node.path(name));
            if (value > 0) {
                return value;
            }
        }
        return 0;
    }

    private int parseIntNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return 0;
        }
        if (node.isInt() || node.isLong()) {
            return Math.max(0, node.asInt());
        }
        String text = safe(node.asText(""));
        if (text.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(text.replace(",", "")));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private int estimateTokens(String text) {
        int length = safe(text).length();
        if (length <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(length / 4.0));
    }

    private String safe(String value) {
        return Objects.toString(value, "").trim();
    }

    private enum OpenAiApiType {
        CHAT_COMPLETIONS,
        RESPONSES
    }

    public record TokenUsage(int promptTokens,
                             int completionTokens,
                             int totalTokens,
                             boolean estimated) {
    }

    public record OpenAiTextResult(String content, TokenUsage usage) {
    }

    private record OpenAiEndpoint(String url, OpenAiApiType apiType) {
    }
}
