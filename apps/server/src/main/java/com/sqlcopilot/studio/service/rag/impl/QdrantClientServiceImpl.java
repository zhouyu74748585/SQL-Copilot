package com.sqlcopilot.studio.service.rag.impl;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcopilot.studio.service.rag.QdrantClientService;
import com.sqlcopilot.studio.service.rag.model.QdrantPoint;
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
import java.util.List;

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
            pointReqList.add(new PointReq(point.getId(), point.getVector(), point.getPayload()));
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
}
