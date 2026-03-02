package com.sqlcopilot.studio.service.rag.impl;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcopilot.studio.service.rag.QdrantClientService;
import com.sqlcopilot.studio.service.rag.model.QdrantPoint;
import com.sqlcopilot.studio.service.rag.model.QdrantScoredPoint;
import com.sqlcopilot.studio.util.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class QdrantClientServiceImpl implements QdrantClientService {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String qdrantBaseUrl;
    private final String qdrantApiKey;

    public QdrantClientServiceImpl(@Value("${rag.qdrant.url:http://127.0.0.1:6333}") String qdrantBaseUrl,
                                   @Value("${rag.qdrant.api-key:}") String qdrantApiKey) {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.qdrantBaseUrl = qdrantBaseUrl;
        this.qdrantApiKey = qdrantApiKey == null ? "" : qdrantApiKey.trim();
    }

    @Override
    public void ensureCollection(String collectionName, int vectorSize) {
        if (vectorSize <= 0) {
            throw new BusinessException(500, "向量维度无效，无法创建 Qdrant 集合");
        }

        HttpResponse<String> getResponse = send(buildRequest("GET",
            "/collections/" + collectionName,
            null));
        if (getResponse.statusCode() == 200) {
            return;
        }
        if (getResponse.statusCode() != 404) {
            throw new BusinessException(500,
                "检查 Qdrant 集合失败: HTTP " + getResponse.statusCode() + " - " + getResponse.body());
        }

        CreateCollectionReq req = new CreateCollectionReq();
        req.setVectors(new VectorConfig(vectorSize, "Cosine"));
        String body = toJson(req);

        HttpResponse<String> putResponse = send(buildRequest("PUT",
            "/collections/" + collectionName,
            body));
        if (putResponse.statusCode() != 200) {
            throw new BusinessException(500,
                "创建 Qdrant 集合失败: HTTP " + putResponse.statusCode() + " - " + putResponse.body());
        }
    }

    @Override
    public void upsertPoints(String collectionName, List<QdrantPoint> points) {
        if (points == null || points.isEmpty()) {
            return;
        }

        List<PointReq> pointReqList = new ArrayList<>();
        for (QdrantPoint point : points) {
            // 关键操作：Qdrant payload 不允许 null 值，写入前递归清洗 metadata。
            pointReqList.add(new PointReq(point.getId(), point.getVector(), sanitizePayload(point.getPayload())));
        }
        UpsertReq req = new UpsertReq(pointReqList);

        HttpResponse<String> response = send(buildRequest("PUT",
            "/collections/" + collectionName + "/points?wait=true",
            toJson(req)));
        if (response.statusCode() != 200) {
            throw new BusinessException(500,
                "写入 Qdrant 向量失败: HTTP " + response.statusCode() + " - " + response.body());
        }

        validateQdrantResponse(response.body());
    }

    @Override
    public List<QdrantScoredPoint> searchPoints(String collectionName,
                                                List<Float> vector,
                                                int limit,
                                                Long connectionId,
                                                String databaseName) {
        if (vector == null || vector.isEmpty() || limit <= 0 || connectionId == null) {
            return List.of();
        }

        SearchReq req = new SearchReq();
        req.setVector(vector);
        req.setLimit(limit);
        req.setWithPayload(true);
        req.setFilter(buildFilter(connectionId, databaseName));

        HttpResponse<String> response = send(buildRequest("POST",
            "/collections/" + collectionName + "/points/search",
            toJson(req)));
        if (response.statusCode() == 404) {
            return List.of();
        }
        if (response.statusCode() != 200) {
            throw new BusinessException(500,
                "检索 Qdrant 向量失败: HTTP " + response.statusCode() + " - " + response.body());
        }
        validateQdrantResponse(response.body());
        return parseSearchResults(response.body());
    }

    private HttpRequest buildRequest(String method, String path, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .timeout(Duration.ofSeconds(30))
            .uri(URI.create(qdrantBaseUrl + path));

        if (!qdrantApiKey.isBlank()) {
            builder.header("api-key", qdrantApiKey);
        }
        builder.header("Content-Type", "application/json; charset=utf-8");

        if ("GET".equals(method)) {
            return builder.GET().build();
        }
        return builder.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8)).build();
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new BusinessException(500, "访问 Qdrant 失败: " + ex.getMessage());
        }
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new BusinessException(500, "序列化 Qdrant 请求失败: " + ex.getMessage());
        }
    }

    private Object sanitizePayload(Object payload) {
        if (payload == null) {
            return null;
        }
        if (payload instanceof Map<?, ?> mapPayload) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : mapPayload.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                Object sanitizedValue = sanitizePayload(entry.getValue());
                if (sanitizedValue != null) {
                    result.put(String.valueOf(entry.getKey()), sanitizedValue);
                }
            }
            return result;
        }
        if (payload instanceof List<?> listPayload) {
            List<Object> result = new ArrayList<>();
            for (Object item : listPayload) {
                Object sanitizedValue = sanitizePayload(item);
                if (sanitizedValue != null) {
                    result.add(sanitizedValue);
                }
            }
            return result;
        }
        return payload;
    }

    private void validateQdrantResponse(String body) {
        try {
            JsonNode jsonNode = objectMapper.readTree(body);
            JsonNode statusNode = jsonNode.get("status");
            if (statusNode == null) {
                return;
            }
            String status = statusNode.asText();
            if (!"ok".equalsIgnoreCase(status)) {
                throw new BusinessException(500, "Qdrant 返回非成功状态: " + body);
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(500, "解析 Qdrant 响应失败: " + ex.getMessage());
        }
    }

    private FilterReq buildFilter(Long connectionId, String databaseName) {
        List<FilterConditionReq> mustConditions = new ArrayList<>();
        mustConditions.add(new FilterConditionReq("connection_id", new MatchReq(connectionId)));
        String normalizedDatabaseName = Objects.toString(databaseName, "").trim();
        if (!normalizedDatabaseName.isBlank()) {
            mustConditions.add(new FilterConditionReq("database_name", new MatchReq(normalizedDatabaseName)));
        }
        return new FilterReq(mustConditions);
    }

    private List<QdrantScoredPoint> parseSearchResults(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode resultNode = root.path("result");
            if (!resultNode.isArray()) {
                return List.of();
            }

            List<QdrantScoredPoint> results = new ArrayList<>();
            for (JsonNode node : resultNode) {
                String id = node.path("id").asText("");
                double score = node.path("score").asDouble(0D);
                Map<String, Object> payload = new HashMap<>();
                JsonNode payloadNode = node.path("payload");
                if (payloadNode.isObject()) {
                    payload = objectMapper.convertValue(payloadNode, objectMapper.getTypeFactory()
                        .constructMapType(HashMap.class, String.class, Object.class));
                }
                results.add(new QdrantScoredPoint(id, score, payload));
            }
            return results;
        } catch (Exception ex) {
            throw new BusinessException(500, "解析 Qdrant 检索结果失败: " + ex.getMessage());
        }
    }

    private static class CreateCollectionReq {
        private VectorConfig vectors;

        public VectorConfig getVectors() {
            return vectors;
        }

        public void setVectors(VectorConfig vectors) {
            this.vectors = vectors;
        }
    }

    private static class VectorConfig {
        private int size;
        private String distance;

        VectorConfig(int size, String distance) {
            this.size = size;
            this.distance = distance;
        }

        public int getSize() {
            return size;
        }

        public String getDistance() {
            return distance;
        }
    }

    private record UpsertReq(List<PointReq> points) {
    }

    private record PointReq(String id, List<Float> vector, Object payload) {
    }

    private static class SearchReq {
        private List<Float> vector;
        private Integer limit;
        private Boolean withPayload;
        private FilterReq filter;

        public List<Float> getVector() {
            return vector;
        }

        public void setVector(List<Float> vector) {
            this.vector = vector;
        }

        public Integer getLimit() {
            return limit;
        }

        public void setLimit(Integer limit) {
            this.limit = limit;
        }

        public Boolean getWithPayload() {
            return withPayload;
        }

        public void setWithPayload(Boolean withPayload) {
            this.withPayload = withPayload;
        }

        public FilterReq getFilter() {
            return filter;
        }

        public void setFilter(FilterReq filter) {
            this.filter = filter;
        }
    }

    private static class FilterReq {
        private List<FilterConditionReq> must;

        FilterReq(List<FilterConditionReq> must) {
            this.must = must;
        }

        public List<FilterConditionReq> getMust() {
            return must;
        }
    }

    private record FilterConditionReq(String key, MatchReq match) {
    }

    private record MatchReq(Object value) {
    }
}
