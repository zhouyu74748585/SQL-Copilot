package com.sqlcopilot.studio.service.rag.impl;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcopilot.studio.service.rag.QdrantClientService;
import com.sqlcopilot.studio.service.rag.model.QdrantCollectionMetric;
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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class QdrantClientServiceImpl implements QdrantClientService {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String qdrantBaseUrl;
    private final String qdrantApiKey;
    private final Map<String, Integer> ensuredCollectionVectorSizeCache = new ConcurrentHashMap<>();

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
        Integer cachedSize = ensuredCollectionVectorSizeCache.get(collectionName);
        if (cachedSize != null && cachedSize == vectorSize) {
            return;
        }

        HttpResponse<String> getResponse = send(buildRequest("GET",
            "/collections/" + collectionName,
            null));
        if (getResponse.statusCode() == 200) {
            Integer existingSize = parseCollectionVectorDimension(getResponse.body());
            if (existingSize != null && existingSize > 0 && existingSize != vectorSize) {
                throw new BusinessException(500, "Qdrant 集合向量维度不一致: collection=" + collectionName
                    + ", expected=" + vectorSize + ", actual=" + existingSize);
            }
            ensuredCollectionVectorSizeCache.put(collectionName, existingSize == null || existingSize <= 0 ? vectorSize : existingSize);
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
        ensuredCollectionVectorSizeCache.put(collectionName, vectorSize);
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


    @Override
    public void deletePointsByFilter(String collectionName, Long connectionId, String databaseName, String sessionId) {
        DeleteReq req = new DeleteReq(buildFilter(connectionId, databaseName, sessionId));
        HttpResponse<String> response = send(buildRequest(
            "POST",
            "/collections/" + collectionName + "/points/delete?wait=true",
            toJson(req)
        ));
        if (response.statusCode() == 404) {
            return;
        }
        if (response.statusCode() != 200) {
            throw new BusinessException(500,
                "删除 Qdrant 向量失败: HTTP " + response.statusCode() + " - " + response.body());
        }
        validateQdrantResponse(response.body());
    }

    @Override
    public QdrantCollectionMetric queryCollectionMetric(String collectionName, Long connectionId, String databaseName) {
        if (collectionName == null || collectionName.isBlank() || connectionId == null) {
            return new QdrantCollectionMetric(collectionName, 0, 0L);
        }

        HttpResponse<String> collectionResponse = send(buildRequest("GET", "/collections/" + collectionName, null));
        if (collectionResponse.statusCode() == 404) {
            return new QdrantCollectionMetric(collectionName, 0, 0L);
        }
        if (collectionResponse.statusCode() != 200) {
            throw new BusinessException(500,
                "查询 Qdrant 集合信息失败: HTTP " + collectionResponse.statusCode() + " - " + collectionResponse.body());
        }
        validateQdrantResponse(collectionResponse.body());

        Integer vectorDimension = parseCollectionVectorDimension(collectionResponse.body());

        // 关键操作：通过连接 + 数据库过滤统计点位数量，避免返回跨库汇总数据。
        CountReq countReq = new CountReq(buildFilter(connectionId, databaseName), true);
        HttpResponse<String> countResponse = send(buildRequest(
            "POST",
            "/collections/" + collectionName + "/points/count",
            toJson(countReq)
        ));
        if (countResponse.statusCode() == 404) {
            return new QdrantCollectionMetric(collectionName, vectorDimension, 0L);
        }
        if (countResponse.statusCode() != 200) {
            throw new BusinessException(500,
                "查询 Qdrant 点位数量失败: HTTP " + countResponse.statusCode() + " - " + countResponse.body());
        }
        validateQdrantResponse(countResponse.body());
        Long count = parseCollectionPointCount(countResponse.body());
        return new QdrantCollectionMetric(collectionName, vectorDimension, count);
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

    private Integer parseCollectionVectorDimension(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode vectorsNode = root.path("result").path("config").path("params").path("vectors");
            if (vectorsNode.isObject() && vectorsNode.has("size")) {
                return vectorsNode.path("size").asInt(0);
            }
            if (vectorsNode.isObject()) {
                java.util.Iterator<JsonNode> iterator = vectorsNode.elements();
                while (iterator.hasNext()) {
                    JsonNode item = iterator.next();
                    if (item.isObject() && item.has("size")) {
                        return item.path("size").asInt(0);
                    }
                }
            }
            return 0;
        } catch (Exception ex) {
            throw new BusinessException(500, "解析 Qdrant 集合维度失败: " + ex.getMessage());
        }
    }

    private Long parseCollectionPointCount(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            return root.path("result").path("count").asLong(0L);
        } catch (Exception ex) {
            throw new BusinessException(500, "解析 Qdrant 点位数量失败: " + ex.getMessage());
        }
    }

    private FilterReq buildFilter(Long connectionId, String databaseName) {
        return buildFilter(connectionId, databaseName, "");
    }

    private FilterReq buildFilter(Long connectionId, String databaseName, String sessionId) {
        List<FilterConditionReq> mustConditions = new ArrayList<>();
        mustConditions.add(new FilterConditionReq("connection_id", new MatchReq(connectionId)));
        String normalizedDatabaseName = Objects.toString(databaseName, "").trim();
        if (!normalizedDatabaseName.isBlank()) {
            mustConditions.add(new FilterConditionReq("database_name", new MatchReq(normalizedDatabaseName)));
        }
        String normalizedSessionId = Objects.toString(sessionId, "").trim();
        if (!normalizedSessionId.isBlank()) {
            mustConditions.add(new FilterConditionReq("session_id", new MatchReq(normalizedSessionId)));
        }
        return new FilterReq(mustConditions);
    }

    private List<QdrantScoredPoint> parseSearchResults(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode resultNode = root.path("result");
            JsonNode pointsNode = resultNode;
            if (!pointsNode.isArray() && resultNode.isObject()) {
                pointsNode = resultNode.path("points");
            }
            if (!pointsNode.isArray()) {
                return List.of();
            }

            List<QdrantScoredPoint> results = new ArrayList<>();
            for (JsonNode node : pointsNode) {
                String id = node.path("id").asText("");
                double score = node.path("score").asDouble(0D);
                Map<String, Object> payload = new HashMap<>();
                JsonNode payloadNode = node.path("payload");
                if (!payloadNode.isObject()) {
                    payloadNode = node.path("point").path("payload");
                }
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

    private record CountReq(FilterReq filter, Boolean exact) {
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

        @JsonProperty("with_payload")
        public Boolean getWithPayload() {
            return withPayload;
        }

        @JsonProperty("with_payload")
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
