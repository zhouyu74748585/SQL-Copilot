package com.sqlcopilot.studio.service.rag.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqlcopilot.studio.dto.rag.RagConfigVO;
import com.sqlcopilot.studio.service.RagConfigService;
import com.sqlcopilot.studio.service.rag.RagRerankService;
import com.sqlcopilot.studio.service.rag.model.QdrantScoredPoint;
import com.sqlcopilot.studio.util.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class OpenAiCompatRerankServiceImpl implements RagRerankService {

    private static final String RUNTIME_PROVIDER = "ONLINE_OPENAI_COMPAT";

    private final RagConfigService ragConfigService;
    private final OpenAiCompatRagHttpClient openAiCompatRagHttpClient;
    private final ObjectMapper objectMapper;
    private final int timeoutSeconds;

    public OpenAiCompatRerankServiceImpl(RagConfigService ragConfigService,
                                         OpenAiCompatRagHttpClient openAiCompatRagHttpClient,
                                         ObjectMapper objectMapper,
                                         @Value("${rag.rerank.online.timeout-seconds:30}") int timeoutSeconds) {
        this.ragConfigService = ragConfigService;
        this.openAiCompatRagHttpClient = openAiCompatRagHttpClient;
        this.objectMapper = objectMapper;
        this.timeoutSeconds = Math.max(5, timeoutSeconds);
    }

    @Override
    public List<Double> score(String query, String bucket, List<QdrantScoredPoint> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        RagConfigVO config = ragConfigService.getConfig();
        String baseUrl = safe(config.getRagRerankOnlineBaseUrl());
        String apiKey = safe(config.getRagRerankOnlineApiKey());
        String model = safe(config.getRagRerankOnlineModel());
        validateConfig(baseUrl, apiKey, model);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", model);
        payload.put("query", safe(query));
        payload.put("top_n", hits.size());
        payload.put("return_documents", false);
        ArrayNode documents = payload.putArray("documents");
        for (QdrantScoredPoint hit : hits) {
            documents.add(buildDocument(bucket, hit));
        }

        JsonNode root = openAiCompatRagHttpClient.postJson(
            resolveRerankEndpoint(baseUrl),
            payload,
            apiKey,
            Duration.ofSeconds(timeoutSeconds),
            "在线 Rerank"
        );
        return parseScores(root, hits.size());
    }

    @Override
    public String getRuntimeProvider() {
        return RUNTIME_PROVIDER;
    }

    private void validateConfig(String baseUrl, String apiKey, String model) {
        if (baseUrl.isBlank()) {
            throw new BusinessException(400, "在线 Rerank 配置缺少 Base URL");
        }
        if (apiKey.isBlank()) {
            throw new BusinessException(400, "在线 Rerank 配置缺少 API Key");
        }
        if (model.isBlank()) {
            throw new BusinessException(400, "在线 Rerank 配置缺少模型名称");
        }
    }

    private List<Double> parseScores(JsonNode root, int expectedSize) {
        JsonNode rankingNode = root.path("data");
        if (!rankingNode.isArray()) {
            rankingNode = root.path("results");
        }
        if (!rankingNode.isArray()) {
            throw new BusinessException(500, "在线 Rerank 响应缺少 data/results 数组");
        }

        List<Double> scores = new ArrayList<>(expectedSize);
        for (int i = 0; i < expectedSize; i++) {
            scores.add(0D);
        }

        for (int i = 0; i < rankingNode.size(); i++) {
            JsonNode item = rankingNode.get(i);
            int index = resolveIndex(item, i, expectedSize);
            double score = resolveScore(item);
            scores.set(index, normalizeScore(score));
        }
        return scores;
    }

    private int resolveIndex(JsonNode item, int fallbackIndex, int size) {
        if (item == null || size <= 0) {
            return 0;
        }
        JsonNode indexNode = item.path("index");
        if (!indexNode.isNumber()) {
            indexNode = item.path("document_index");
        }
        if (!indexNode.isNumber()) {
            indexNode = item.path("id");
        }
        if (indexNode.isNumber()) {
            int index = indexNode.asInt();
            if (index >= 0 && index < size) {
                return index;
            }
        }
        return Math.max(0, Math.min(fallbackIndex, size - 1));
    }

    private double resolveScore(JsonNode item) {
        if (item == null) {
            return 0D;
        }
        JsonNode scoreNode = item.path("relevance_score");
        if (!scoreNode.isNumber()) {
            scoreNode = item.path("score");
        }
        if (!scoreNode.isNumber()) {
            return 0D;
        }
        return scoreNode.asDouble(0D);
    }

    private double normalizeScore(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0D;
        }
        if (value >= 0D && value <= 1D) {
            return value;
        }
        double sigmoid = 1D / (1D + Math.exp(-value));
        if (sigmoid < 0D) {
            return 0D;
        }
        return Math.min(1D, sigmoid);
    }

    private String buildDocument(String bucket, QdrantScoredPoint hit) {
        if (hit == null || hit.getPayload() == null || hit.getPayload().isEmpty()) {
            return "";
        }
        Map<String, Object> payload = hit.getPayload();
        StringBuilder builder = new StringBuilder();
        switch (bucket) {
            case "table" -> {
                appendIfPresent(builder, payload, "table_name");
                appendIfPresent(builder, payload, "table_comment");
                appendListIfPresent(builder, payload, "primary_keys");
                appendListIfPresent(builder, payload, "indexed_columns");
                appendListIfPresent(builder, payload, "time_columns");
                appendListIfPresent(builder, payload, "metric_columns");
                appendListIfPresent(builder, payload, "dimension_columns");
            }
            case "column" -> {
                appendIfPresent(builder, payload, "table_name");
                appendIfPresent(builder, payload, "column_name");
                appendIfPresent(builder, payload, "data_type");
                appendIfPresent(builder, payload, "column_comment");
                appendListIfPresent(builder, payload, "column_roles");
            }
            case "metric_term" -> {
                appendIfPresent(builder, payload, "term");
                appendIfPresent(builder, payload, "term_type");
                appendIfPresent(builder, payload, "definition");
                appendIfPresent(builder, payload, "metric_expression");
                appendListIfPresent(builder, payload, "aliases");
                appendListIfPresent(builder, payload, "related_tables");
                appendListIfPresent(builder, payload, "related_columns");
            }
            case "example_sql" -> {
                appendIfPresent(builder, payload, "question_text");
                appendIfPresent(builder, payload, "semantic_description");
                appendIfPresent(builder, payload, "sql_text");
                appendIfPresent(builder, payload, "normalized_sql_text");
                appendIfPresent(builder, payload, "sql_template");
                appendListIfPresent(builder, payload, "tables");
                appendListIfPresent(builder, payload, "columns");
                appendListIfPresent(builder, payload, "metric_tags");
                appendListIfPresent(builder, payload, "time_tags");
                appendIfPresent(builder, payload, "sql_operation_type");
                appendIfPresent(builder, payload, "verified_flag");
                appendIfPresent(builder, payload, "quality_score");
            }
            case "query_history" -> {
                appendIfPresent(builder, payload, "question_text");
                appendIfPresent(builder, payload, "semantic_description");
                appendIfPresent(builder, payload, "sql_text");
                appendListIfPresent(builder, payload, "tables");
                appendListIfPresent(builder, payload, "columns");
                appendIfPresent(builder, payload, "sql_operation_type");
                appendIfPresent(builder, payload, "source_type");
                appendIfPresent(builder, payload, "trust_level");
                appendIfPresent(builder, payload, "execution_ms");
                appendIfPresent(builder, payload, "success");
            }
            default -> {
                appendIfPresent(builder, payload, "table_name");
                appendIfPresent(builder, payload, "column_name");
                appendIfPresent(builder, payload, "term");
                appendIfPresent(builder, payload, "definition");
                appendIfPresent(builder, payload, "metric_expression");
                appendIfPresent(builder, payload, "sql_text");
                appendIfPresent(builder, payload, "prompt_text");
            }
        }
        return builder.toString().trim();
    }

    private void appendIfPresent(StringBuilder builder, Map<String, Object> payload, String key) {
        if (payload == null) {
            return;
        }
        String value = Objects.toString(payload.get(key), "").trim();
        if (value.isBlank()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(" | ");
        }
        builder.append(key).append('=').append(value);
    }

    private void appendListIfPresent(StringBuilder builder, Map<String, Object> payload, String key) {
        if (payload == null) {
            return;
        }
        Object rawValue = payload.get(key);
        if (!(rawValue instanceof List<?> rawList) || rawList.isEmpty()) {
            return;
        }
        List<String> values = new ArrayList<>();
        for (Object item : rawList) {
            String value = Objects.toString(item, "").trim();
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        if (values.isEmpty()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(" | ");
        }
        builder.append(key).append('=').append(String.join(",", values));
    }

    private String resolveRerankEndpoint(String baseUrl) {
        String normalized = trimTrailingSlash(baseUrl);
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.endsWith("/rerank")) {
            return normalized;
        }
        if (lower.endsWith("/v1")) {
            return normalized + "/rerank";
        }
        return normalized + "/v1/rerank";
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
