package com.sqlcopilot.studio.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqlcopilot.studio.service.TokenEstimatorService;
import com.sqlcopilot.studio.util.BusinessException;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

@Component
public class OpenAiTextClient {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OpenAiTextClient.class);

    private final ObjectMapper objectMapper;
    private final TokenEstimatorService tokenEstimatorService;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    public OpenAiTextClient(ObjectMapper objectMapper, TokenEstimatorService tokenEstimatorService) {
        this.objectMapper = objectMapper;
        this.tokenEstimatorService = tokenEstimatorService;
    }

    public OpenAiTextResult requestText(String apiKey,
                                        String baseUrl,
                                        String model,
                                        String systemPrompt,
                                        String userPrompt,
                                        Duration timeout,
                                        Double temperature) {
        return requestText(apiKey, baseUrl, model, systemPrompt, userPrompt, timeout, temperature, null);
    }

    public OpenAiTextResult requestText(String apiKey,
                                        String baseUrl,
                                        String model,
                                        String systemPrompt,
                                        String userPrompt,
                                        Duration timeout,
                                        Double temperature,
                                        Boolean thinkingEnabled) {
        OpenAiEndpoint endpoint = resolveOpenAiEndpoint(baseUrl, model);
        ObjectNode payload = buildPayload(model, endpoint.apiType(), systemPrompt, userPrompt, temperature, false, thinkingEnabled);
        log.debug("[OpenAI] request url={} model={} apiType={} thinking={}", maskUrl(endpoint.url()), safe(model), endpoint.apiType(), thinkingEnabled);
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
                if (response.statusCode() == 404 && endpoint.apiType() == OpenAiApiType.RESPONSES) {
                    return requestTextWithFallback(apiKey, baseUrl, model, systemPrompt, userPrompt, timeout, temperature, thinkingEnabled);
                }
                throw new BusinessException(500, "OpenAI 接口返回状态码: " + response.statusCode()
                    + " url=" + maskUrl(endpoint.url())
                    + " model=" + safe(model)
                    + " body=" + shorten(response.body(), 260));
            }
            OpenAiTextResult parsed = parseOpenAiResponse(Objects.toString(response.body(), ""), response.headers().firstValue("content-type").orElse(""), endpoint.apiType());
            TokenUsage usage = normalizeUsage(parsed.usage(), systemPrompt, userPrompt, parsed.content());
            return new OpenAiTextResult(parsed.content(), usage);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(500, "OpenAI 调用失败: " + safe(ex.getMessage()));
        }
    }

    private OpenAiTextResult requestTextWithFallback(String apiKey,
                                                      String baseUrl,
                                                      String model,
                                                      String systemPrompt,
                                                      String userPrompt,
                                                      Duration timeout,
                                                      Double temperature,
                                                      Boolean thinkingEnabled) {
        OpenAiEndpoint fallbackEndpoint = new OpenAiEndpoint(stripTrailingSlash(baseUrl) + "/chat/completions", OpenAiApiType.CHAT_COMPLETIONS);
        ObjectNode fallbackPayload = buildPayload(model, fallbackEndpoint.apiType(), systemPrompt, userPrompt, temperature, false, thinkingEnabled);
        try {
            HttpRequest fallbackRequest = HttpRequest.newBuilder()
                .uri(URI.create(fallbackEndpoint.url()))
                .timeout(resolveTimeout(timeout))
                .header("Authorization", "Bearer " + safe(apiKey))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(fallbackPayload), StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> fallbackResponse = httpClient.send(fallbackRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (fallbackResponse.statusCode() < 200 || fallbackResponse.statusCode() >= 300) {
                throw new BusinessException(500, "OpenAI 接口返回状态码: " + fallbackResponse.statusCode()
                    + " url=" + maskUrl(fallbackEndpoint.url())
                    + " model=" + safe(model)
                    + " body=" + shorten(fallbackResponse.body(), 260));
            }
            OpenAiTextResult parsed = parseOpenAiResponse(Objects.toString(fallbackResponse.body(), ""), fallbackResponse.headers().firstValue("content-type").orElse(""), fallbackEndpoint.apiType());
            TokenUsage usage = normalizeUsage(parsed.usage(), systemPrompt, userPrompt, parsed.content());
            return new OpenAiTextResult(parsed.content(), usage);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(500, "OpenAI 调用失败（Responses API 404 降级后）: " + safe(ex.getMessage()));
        }
    }

    public OpenAiStreamResult requestTextStream(String apiKey,
                                                String baseUrl,
                                                String model,
                                                String systemPrompt,
                                                String userPrompt,
                                                Duration timeout,
                                                Double temperature,
                                                LlmStreamListener listener,
                                                Boolean thinkingEnabled) {
        OpenAiEndpoint endpoint = resolveOpenAiEndpoint(baseUrl, model);
        ObjectNode payload = buildPayload(model, endpoint.apiType(), systemPrompt, userPrompt, temperature, true, thinkingEnabled);
        log.debug("[OpenAI] stream request url={} model={} apiType={} thinking={}", maskUrl(endpoint.url()), safe(model), endpoint.apiType(), thinkingEnabled);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint.url()))
                .timeout(resolveTimeout(timeout))
                .header("Authorization", "Bearer " + safe(apiKey))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                if (response.statusCode() == 404 && endpoint.apiType() == OpenAiApiType.RESPONSES) {
                    try { response.body().close(); } catch (Exception ignored) {}
                    return requestTextStreamWithFallback(apiKey, baseUrl, model, systemPrompt, userPrompt, timeout, temperature, listener, thinkingEnabled);
                }
                String errorBody = readErrorBody(response);
                throw new BusinessException(500, "OpenAI 接口返回状态码: " + response.statusCode()
                    + " url=" + maskUrl(endpoint.url())
                    + " model=" + safe(model)
                    + " body=" + errorBody);
            }
            String contentType = response.headers().firstValue("content-type").orElse("").toLowerCase();
            String providerRequestId = response.headers().firstValue("x-request-id").orElse("");
            try (BufferedInputStream input = new BufferedInputStream(response.body())) {
                if (!looksLikeStreamingResponse(contentType, input)) {
                    String body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    OpenAiTextResult parsed = parseOpenAiResponse(body, response.headers().firstValue("content-type").orElse(""), endpoint.apiType());
                    String text = safe(parsed.content());
                    if (!text.isBlank() && listener != null) {
                        listener.onOutputDelta(text, text);
                    }
                    return new OpenAiStreamResult(text, "", normalizeUsage(parsed.usage(), systemPrompt, userPrompt, text), providerRequestId, false);
                }
                return parseStreamingResponse(input, endpoint.apiType(), listener, providerRequestId, systemPrompt, userPrompt);
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(500, "OpenAI 流式调用失败: " + safe(ex.getMessage()));
        }
    }

    private OpenAiStreamResult requestTextStreamWithFallback(String apiKey,
                                                              String baseUrl,
                                                              String model,
                                                              String systemPrompt,
                                                              String userPrompt,
                                                              Duration timeout,
                                                              Double temperature,
                                                              LlmStreamListener listener,
                                                              Boolean thinkingEnabled) {
        OpenAiEndpoint fallbackEndpoint = new OpenAiEndpoint(stripTrailingSlash(baseUrl) + "/chat/completions", OpenAiApiType.CHAT_COMPLETIONS);
        ObjectNode fallbackPayload = buildPayload(model, fallbackEndpoint.apiType(), systemPrompt, userPrompt, temperature, true, thinkingEnabled);
        try {
            HttpRequest fallbackRequest = HttpRequest.newBuilder()
                .uri(URI.create(fallbackEndpoint.url()))
                .timeout(resolveTimeout(timeout))
                .header("Authorization", "Bearer " + safe(apiKey))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(fallbackPayload), StandardCharsets.UTF_8))
                .build();
            HttpResponse<InputStream> fallbackResponse = httpClient.send(fallbackRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (fallbackResponse.statusCode() < 200 || fallbackResponse.statusCode() >= 300) {
                String errorBody = readErrorBody(fallbackResponse);
                throw new BusinessException(500, "OpenAI 接口返回状态码: " + fallbackResponse.statusCode()
                    + " url=" + maskUrl(fallbackEndpoint.url())
                    + " model=" + safe(model)
                    + " body=" + errorBody);
            }
            String contentType = fallbackResponse.headers().firstValue("content-type").orElse("").toLowerCase();
            String providerRequestId = fallbackResponse.headers().firstValue("x-request-id").orElse("");
            try (BufferedInputStream input = new BufferedInputStream(fallbackResponse.body())) {
                if (!looksLikeStreamingResponse(contentType, input)) {
                    String body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    OpenAiTextResult parsed = parseOpenAiResponse(body, fallbackResponse.headers().firstValue("content-type").orElse(""), fallbackEndpoint.apiType());
                    String text = safe(parsed.content());
                    if (!text.isBlank() && listener != null) {
                        listener.onOutputDelta(text, text);
                    }
                    return new OpenAiStreamResult(text, "", normalizeUsage(parsed.usage(), systemPrompt, userPrompt, text), providerRequestId, false);
                }
                return parseStreamingResponse(input, fallbackEndpoint.apiType(), listener, providerRequestId, systemPrompt, userPrompt);
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(500, "OpenAI 流式调用失败（Responses API 404 降级后）: " + safe(ex.getMessage()));
        }
    }

    private String readErrorBody(HttpResponse<InputStream> response) {
        try (InputStream body = response.body()) {
            return shorten(new String(body.readAllBytes(), StandardCharsets.UTF_8), 260);
        } catch (Exception ignored) {
            return "";
        }
    }

    private String shorten(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.length() <= maxLen) {
            return trimmed;
        }
        return trimmed.substring(0, maxLen) + "...";
    }

    private String maskUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(url);
            String result = uri.getScheme() + "://" + uri.getHost();
            if (uri.getPort() > 0) {
                result += ":" + uri.getPort();
            }
            result += uri.getPath();
            return result;
        } catch (Exception ignored) {
            return url;
        }
    }

    private Duration resolveTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return Duration.ofSeconds(30);
        }
        return timeout;
    }

    private boolean looksLikeStreamingResponse(String contentType, BufferedInputStream input) throws Exception {
        if (safe(contentType).contains("text/event-stream")) {
            return true;
        }
        input.mark(2048);
        byte[] previewBytes = input.readNBytes(1024);
        input.reset();
        String preview = new String(previewBytes, StandardCharsets.UTF_8).stripLeading();
        return preview.startsWith("event:")
            || preview.startsWith("data:")
            || preview.contains("\nevent:")
            || preview.contains("\ndata:");
    }

    private ObjectNode buildPayload(String model,
                                    OpenAiApiType apiType,
                                    String systemPrompt,
                                    String userPrompt,
                                    Double temperature,
                                    boolean stream) {
        return buildPayload(model, apiType, systemPrompt, userPrompt, temperature, stream, null);
    }

    private ObjectNode buildPayload(String model,
                                    OpenAiApiType apiType,
                                    String systemPrompt,
                                    String userPrompt,
                                    Double temperature,
                                    boolean stream,
                                    Boolean thinkingEnabled) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", safe(model));
        if (stream) {
            payload.put("stream", true);
        }
        if (apiType == OpenAiApiType.RESPONSES) {
            ArrayNode input = payload.putArray("input");
            input.addObject().put("role", "system").put("content", safe(systemPrompt));
            input.addObject().put("role", "user").put("content", safe(userPrompt));
            return payload;
        }
        if (temperature != null) {
            payload.put("temperature", temperature);
        }
        // 千问等兼容服务商通过 enable_thinking 控制思考模式
        if (thinkingEnabled != null) {
            payload.put("enable_thinking", thinkingEnabled);
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
        // 仅 OpenAI 官方 API 支持 Responses API；第三方兼容服务商不支持，直接跳过避免 404
        boolean isOpenAiOfficial = lowerBaseUrl.contains("api.openai.com");
        if (!isOpenAiOfficial) {
            return false;
        }
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

    private OpenAiTextResult parseOpenAiResponse(String body, String contentType, OpenAiApiType apiType) throws Exception {
        String normalizedContentType = Objects.toString(contentType, "").toLowerCase();
        if (normalizedContentType.contains("text/event-stream") || body.startsWith("event:") || body.contains("\nevent:")) {
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

    private OpenAiStreamResult parseStreamingResponse(InputStream input,
                                                      OpenAiApiType apiType,
                                                      LlmStreamListener listener,
                                                      String providerRequestId,
                                                      String systemPrompt,
                                                      String userPrompt) throws Exception {
        StringBuilder outputBuilder = new StringBuilder();
        StringBuilder thinkingBuilder = new StringBuilder();
        TokenUsage usage = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            String eventName = "";
            StringBuilder dataBuilder = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    usage = processStreamingEvent(
                        eventName,
                        dataBuilder.toString(),
                        apiType,
                        outputBuilder,
                        thinkingBuilder,
                        usage,
                        listener
                    );
                    eventName = "";
                    dataBuilder.setLength(0);
                    continue;
                }
                if (line.startsWith("event:")) {
                    eventName = raw(line.substring(6)).trim();
                    continue;
                }
                if (line.startsWith("data:")) {
                    if (dataBuilder.length() > 0) {
                        dataBuilder.append('\n');
                    }
                    dataBuilder.append(line.substring(5).trim());
                }
            }
            usage = processStreamingEvent(
                eventName,
                dataBuilder.toString(),
                apiType,
                outputBuilder,
                thinkingBuilder,
                usage,
                listener
            );
        }
        String finalOutput = outputBuilder.toString().trim();
        String finalThinking = thinkingBuilder.toString().trim();
        TokenUsage normalizedUsage = normalizeUsage(usage, systemPrompt, userPrompt, finalOutput);
        return new OpenAiStreamResult(finalOutput, finalThinking, normalizedUsage, providerRequestId, true);
    }

    private TokenUsage processStreamingEvent(String eventName,
                                             String data,
                                             OpenAiApiType apiType,
                                             StringBuilder outputBuilder,
                                             StringBuilder thinkingBuilder,
                                             TokenUsage currentUsage,
                                             LlmStreamListener listener) {
        String normalizedData = raw(data).trim();
        if (normalizedData.isEmpty() || "[DONE]".equalsIgnoreCase(normalizedData)) {
            return currentUsage;
        }
        try {
            JsonNode eventNode = objectMapper.readTree(normalizedData);
            String eventType = !safe(eventName).isBlank() ? safe(eventName) : safe(eventNode.path("type").asText(""));
            if (apiType == OpenAiApiType.RESPONSES) {
                return processResponsesStreamingEvent(eventNode, eventType, outputBuilder, thinkingBuilder, currentUsage, listener);
            }
            return processChatStreamingEvent(eventNode, eventType, outputBuilder, thinkingBuilder, currentUsage, listener);
        } catch (Exception ignored) {
            return currentUsage;
        }
    }

    private TokenUsage processResponsesStreamingEvent(JsonNode eventNode,
                                                      String eventType,
                                                      StringBuilder outputBuilder,
                                                      StringBuilder thinkingBuilder,
                                                      TokenUsage currentUsage,
                                                      LlmStreamListener listener) {
        String normalizedType = safe(eventType);
        if (normalizedType.contains("reasoning") && normalizedType.contains("delta")) {
            appendStreamDelta(thinkingBuilder, extractReasoningDelta(eventNode), true, listener);
            return currentUsage;
        }
        if (normalizedType.contains("output_text") && normalizedType.contains("delta")) {
            appendStreamDelta(outputBuilder, extractOutputDelta(eventNode), false, listener);
            return currentUsage;
        }
        if (normalizedType.contains("reasoning") && normalizedType.endsWith(".done")) {
            replaceWithLonger(thinkingBuilder, extractDoneText(eventNode));
            return currentUsage;
        }
        if (normalizedType.contains("output_text") && normalizedType.endsWith(".done")) {
            replaceWithLonger(outputBuilder, extractDoneText(eventNode));
            return currentUsage;
        }
        if ("response.completed".equals(normalizedType)) {
            JsonNode responseNode = eventNode.path("response");
            replaceWithLonger(outputBuilder, parseResponsesJsonText(responseNode));
            replaceWithLonger(thinkingBuilder, parseResponseReasoningText(responseNode));
            TokenUsage usage = parseUsage(responseNode, OpenAiApiType.RESPONSES);
            return usage == null ? currentUsage : usage;
        }
        return currentUsage;
    }

    private TokenUsage processChatStreamingEvent(JsonNode eventNode,
                                                 String eventType,
                                                 StringBuilder outputBuilder,
                                                 StringBuilder thinkingBuilder,
                                                 TokenUsage currentUsage,
                                                 LlmStreamListener listener) {
        JsonNode choice = eventNode.path("choices").path(0);
        JsonNode deltaNode = choice.path("delta");
        appendStreamDelta(thinkingBuilder, extractChatReasoningDelta(deltaNode), true, listener);
        appendStreamDelta(outputBuilder, extractChatOutputDelta(deltaNode), false, listener);
        TokenUsage usage = parseUsage(eventNode, OpenAiApiType.CHAT_COMPLETIONS);
        if (usage != null) {
            currentUsage = usage;
        }
        if ("chat.completion".equals(safe(eventType))) {
            replaceWithLonger(outputBuilder, parseChatCompletionsText(eventNode));
        }
        return currentUsage;
    }

    private void appendStreamDelta(StringBuilder builder, String delta, boolean thinking, LlmStreamListener listener) {
        String normalized = raw(delta);
        if (normalized.isEmpty()) {
            return;
        }
        builder.append(normalized);
        if (listener == null) {
            return;
        }
        if (thinking) {
            listener.onThinkingDelta(normalized, builder.toString());
            return;
        }
        listener.onOutputDelta(normalized, builder.toString());
    }

    private void replaceWithLonger(StringBuilder builder, String candidate) {
        String normalized = raw(candidate).trim();
        if (normalized.isEmpty()) {
            return;
        }
        if (builder.length() == 0 || normalized.length() > builder.length()) {
            builder.setLength(0);
            builder.append(normalized);
        }
    }

    private String extractOutputDelta(JsonNode eventNode) {
        return firstNonBlank(
            raw(eventNode.path("delta").asText("")),
            raw(eventNode.path("text").asText("")),
            raw(eventNode.path("output_text").asText("")),
            raw(eventNode.path("item").path("text").asText(""))
        );
    }

    private String extractReasoningDelta(JsonNode eventNode) {
        return firstNonBlank(
            raw(eventNode.path("delta").asText("")),
            raw(eventNode.path("text").asText("")),
            raw(eventNode.path("summary").asText("")),
            raw(eventNode.path("reasoning").asText(""))
        );
    }

    private String extractDoneText(JsonNode eventNode) {
        return firstNonBlank(
            raw(eventNode.path("text").asText("")),
            raw(eventNode.path("output_text").asText("")),
            raw(eventNode.path("summary").asText("")),
            raw(eventNode.path("delta").asText(""))
        );
    }

    private String extractChatOutputDelta(JsonNode deltaNode) {
        if (deltaNode == null || deltaNode.isMissingNode() || deltaNode.isNull()) {
            return "";
        }
        String direct = raw(deltaNode.path("content").asText(""));
        if (!direct.isEmpty()) {
            return direct;
        }
        JsonNode contentArray = deltaNode.path("content");
        if (contentArray.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode item : contentArray) {
                String type = safe(item.path("type").asText(""));
                if (type.contains("reasoning")) {
                    continue;
                }
                String text = raw(item.path("text").asText(""));
                if (!text.isEmpty()) {
                    builder.append(text);
                }
            }
            return builder.toString();
        }
        return "";
    }

    private String extractChatReasoningDelta(JsonNode deltaNode) {
        if (deltaNode == null || deltaNode.isMissingNode() || deltaNode.isNull()) {
            return "";
        }
        String direct = firstNonBlank(
            raw(deltaNode.path("reasoning_content").asText("")),
            raw(deltaNode.path("reasoning").asText(""))
        );
        if (!direct.isEmpty()) {
            return direct;
        }
        JsonNode contentArray = deltaNode.path("content");
        if (contentArray.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode item : contentArray) {
                String type = safe(item.path("type").asText(""));
                if (!type.contains("reasoning")) {
                    continue;
                }
                String text = firstNonBlank(
                    raw(item.path("text").asText("")),
                    raw(item.path("delta").asText("")),
                    raw(item.path("summary").asText(""))
                );
                if (!text.isEmpty()) {
                    builder.append(text);
                }
            }
            return builder.toString();
        }
        return "";
    }

    private String parseResponseReasoningText(JsonNode responseNode) {
        if (responseNode == null || responseNode.isMissingNode() || responseNode.isNull()) {
            return "";
        }
        JsonNode outputItems = responseNode.path("output");
        if (!outputItems.isArray()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode item : outputItems) {
            String type = safe(item.path("type").asText(""));
            if (!type.contains("reasoning")) {
                continue;
            }
            JsonNode contentItems = item.path("content");
            if (!contentItems.isArray()) {
                String summaryText = raw(item.path("summary").asText(""));
                if (!summaryText.isEmpty()) {
                    if (builder.length() > 0) {
                        builder.append('\n');
                    }
                    builder.append(summaryText);
                }
                continue;
            }
            for (JsonNode content : contentItems) {
                String text = firstNonBlank(
                    raw(content.path("text").asText("")),
                    raw(content.path("summary").asText("")),
                    raw(content.path("delta").asText(""))
                );
                if (text.isEmpty()) {
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

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
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
        int promptTokens = tokenEstimatorService.estimateTokens(
            safe(systemPrompt) + "\n" + safe(userPrompt),
            TokenEstimatorService.TOKENIZER_OPENAI_COMPAT
        );
        int completionTokens = tokenEstimatorService.estimateTokens(content, TokenEstimatorService.TOKENIZER_OPENAI_COMPAT);
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

    private String safe(String value) {
        return stripInvisibleChars(Objects.toString(value, "").trim());
    }

    /**
     * 剥离零宽字符、不可见标记等隐藏 Unicode 字符，避免复制粘贴引入不可见字符导致 API 调用失败。
     */
    private String stripInvisibleChars(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        // 移除常见零宽/不可见字符：U+200B~U+200F, U+202A~U+202E, U+2060~U+206F, U+FEFF, U+200C, U+200D
        return value.replaceAll("[\\u200B\\u200C\\u200D\\u200E\\u200F\\u202A\\u202B\\u202C\\u202D\\u202E\\u2060\\u2061\\u2062\\u2063\\u2064\\u2065\\u2066\\u2067\\u2068\\u2069\\u206A\\u206B\\u206C\\u206D\\u206E\\u206F\\uFEFF]", "");
    }

    private String raw(String value) {
        return Objects.toString(value, "");
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

    public record OpenAiStreamResult(String content,
                                     String thinkingContent,
                                     TokenUsage usage,
                                     String providerRequestId,
                                     boolean streaming) {
    }

    private record OpenAiEndpoint(String url, OpenAiApiType apiType) {
    }

}
