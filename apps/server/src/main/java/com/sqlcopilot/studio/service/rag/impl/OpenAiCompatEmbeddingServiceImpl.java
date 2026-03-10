package com.sqlcopilot.studio.service.rag.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqlcopilot.studio.dto.rag.RagConfigVO;
import com.sqlcopilot.studio.service.RagConfigService;
import com.sqlcopilot.studio.service.rag.RagEmbeddingService;
import com.sqlcopilot.studio.util.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
public class OpenAiCompatEmbeddingServiceImpl implements RagEmbeddingService {

    private static final String RUNTIME_PROVIDER = "ONLINE_OPENAI_COMPAT";

    private final RagConfigService ragConfigService;
    private final OpenAiCompatRagHttpClient openAiCompatRagHttpClient;
    private final ObjectMapper objectMapper;
    private final int timeoutSeconds;

    public OpenAiCompatEmbeddingServiceImpl(RagConfigService ragConfigService,
                                            OpenAiCompatRagHttpClient openAiCompatRagHttpClient,
                                            ObjectMapper objectMapper,
                                            @Value("${rag.embedding.online.timeout-seconds:30}") int timeoutSeconds) {
        this.ragConfigService = ragConfigService;
        this.openAiCompatRagHttpClient = openAiCompatRagHttpClient;
        this.objectMapper = objectMapper;
        this.timeoutSeconds = Math.max(5, timeoutSeconds);
    }

    @Override
    public List<Float> embedText(String text) {
        String normalizedText = text == null ? "" : text;
        return embedTexts(List.of(normalizedText)).get(0);
    }

    @Override
    public List<List<Float>> embedTexts(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        RagConfigVO config = ragConfigService.getConfig();
        String baseUrl = safe(config.getRagEmbeddingOnlineBaseUrl());
        String apiKey = safe(config.getRagEmbeddingOnlineApiKey());
        String model = safe(config.getRagEmbeddingOnlineModel());
        validateConfig(baseUrl, apiKey, model);

        List<String> normalizedTexts = texts.stream().map(item -> item == null ? "" : item).toList();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", model);
        ArrayNode input = payload.putArray("input");
        normalizedTexts.forEach(input::add);

        JsonNode root = openAiCompatRagHttpClient.postJson(
            resolveEmbeddingsEndpoint(baseUrl),
            payload,
            apiKey,
            Duration.ofSeconds(timeoutSeconds),
            "在线向量化"
        );
        return parseEmbeddingVectors(root, normalizedTexts.size());
    }

    @Override
    public String getRuntimeProvider() {
        return RUNTIME_PROVIDER;
    }

    private void validateConfig(String baseUrl, String apiKey, String model) {
        if (baseUrl.isBlank()) {
            throw new BusinessException(400, "在线向量化配置缺少 Base URL");
        }
        if (apiKey.isBlank()) {
            throw new BusinessException(400, "在线向量化配置缺少 API Key");
        }
        if (model.isBlank()) {
            throw new BusinessException(400, "在线向量化配置缺少模型名称");
        }
    }

    private List<List<Float>> parseEmbeddingVectors(JsonNode root, int expectedSize) {
        JsonNode dataNode = root.path("data");
        if (!dataNode.isArray()) {
            throw new BusinessException(500, "在线向量化响应缺少 data 数组");
        }
        List<List<Float>> vectors = new ArrayList<>(expectedSize);
        for (int i = 0; i < expectedSize; i++) {
            vectors.add(null);
        }
        for (int i = 0; i < dataNode.size(); i++) {
            JsonNode item = dataNode.get(i);
            int index = parseIndex(item.path("index"), i, expectedSize);
            List<Float> vector = parseVector(item.path("embedding"));
            vectors.set(index, vector);
        }
        int dimension = -1;
        for (int i = 0; i < vectors.size(); i++) {
            List<Float> vector = vectors.get(i);
            if (vector == null || vector.isEmpty()) {
                throw new BusinessException(500, "在线向量化响应向量数量不足");
            }
            if (dimension < 0) {
                dimension = vector.size();
            } else if (dimension != vector.size()) {
                throw new BusinessException(500, "在线向量化响应向量维度不一致");
            }
        }
        return vectors;
    }

    private List<Float> parseVector(JsonNode node) {
        if (!node.isArray()) {
            throw new BusinessException(500, "在线向量化 embedding 字段格式错误");
        }
        List<Float> vector = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (!item.isNumber()) {
                throw new BusinessException(500, "在线向量化 embedding 含非数字元素");
            }
            vector.add((float) item.asDouble());
        }
        return vector;
    }

    private int parseIndex(JsonNode node, int fallback, int size) {
        if (node.isInt() || node.isLong()) {
            int index = node.asInt();
            if (index >= 0 && index < size) {
                return index;
            }
        }
        return Math.max(0, Math.min(fallback, size - 1));
    }

    private String resolveEmbeddingsEndpoint(String baseUrl) {
        String normalized = trimTrailingSlash(baseUrl);
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.endsWith("/embeddings")) {
            return normalized;
        }
        if (lower.endsWith("/v1")) {
            return normalized + "/embeddings";
        }
        return normalized + "/v1/embeddings";
    }

    private String trimTrailingSlash(String input) {
        String value = safe(input);
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String safe(String input) {
        return Objects.toString(input, "").trim();
    }
}
